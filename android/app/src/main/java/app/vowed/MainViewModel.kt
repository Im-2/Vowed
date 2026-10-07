package app.vowed

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.vowed.core.Base58
import app.vowed.core.ClaimExpectation
import app.vowed.core.CreateExpectation
import app.vowed.core.JoinExpectation
import app.vowed.core.PlanHash
import app.vowed.core.TxChecker
import app.vowed.core.TxRejected
import app.vowed.core.TxReview
import app.vowed.data.Account
import app.vowed.data.ApiException
import app.vowed.data.Challenge
import app.vowed.data.ChallengeDetail
import app.vowed.data.ClaimTxRequest
import app.vowed.data.ConnectStep
import app.vowed.data.CreateTxRequest
import app.vowed.data.DemoRequest
import app.vowed.data.JoinTxRequest
import app.vowed.data.Meta
import app.vowed.goals.GoalTemplate
import app.vowed.wallet.WalletException
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.math.BigInteger
import java.util.TimeZone

/** What the new-goal screen collected. All amounts are in token base units (6 decimals). */
class GoalDraft(
    val template: GoalTemplate,
    val totalDays: Int,
    val requiredDays: Int,
    val mode: String, // "Soft" or "Hard"
    val stakeBaseUnits: BigInteger,
    val demo: Boolean,
)

/** A transaction that passed the on-phone check and is waiting for the user's confirmation. */
class PendingTx(
    val kind: String, // create | join | claim
    val review: TxReview,
    val txBytes: ByteArray,
    val pool: String,
    /** Run after the wallet has sent it (e.g. to build the next transaction). */
    val after: (suspend () -> Unit)? = null,
)

sealed interface TxFlow {
    data object Idle : TxFlow
    data class Working(val message: String) : TxFlow
    data class Review(val tx: PendingTx) : TxFlow
    data class Done(val pool: String, val message: String) : TxFlow
    data class Failed(val message: String) : TxFlow
}

data class UiState(
    val account: Account? = null,
    val signedIn: Boolean = false,
    val connecting: ConnectStep? = null,
    val connectError: String? = null,
    val meta: Meta? = null,
    val challenges: List<Challenge> = emptyList(),
    val loadingList: Boolean = false,
    val listError: String? = null,
    val detail: ChallengeDetail? = null,
    val detailError: String? = null,
    val flow: TxFlow = TxFlow.Idle,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val c = (application as VowedApp).container
    private val _state = MutableStateFlow(UiState(account = c.account.current))
    val state: StateFlow<UiState> = _state.asStateFlow()

    val prefs get() = c.prefs
    val hasSavedAccount: Boolean get() = c.account.current != null

    private fun friendly(e: Throwable): String = when (e) {
        is ApiException -> if (e.code == "network") e.message else "${e.message} (${e.code})"
        is WalletException -> e.message ?: "The wallet request failed."
        is TxRejected -> "Stopped before signing: ${e.message}"
        else -> e.message ?: e.javaClass.simpleName
    }

    // ------------------------------------------------------------------ connect

    fun connect(sender: ActivityResultSender) {
        if (_state.value.connecting != null) return
        _state.update { it.copy(connecting = ConnectStep.Wallet, connectError = null) }
        viewModelScope.launch {
            try {
                val account = c.account.connect(sender) { step -> _state.update { s -> s.copy(connecting = step) } }
                c.prefs.onboarded = true
                _state.update { it.copy(account = account, signedIn = true, connecting = null) }
                loadMeta()
                refreshList()
            } catch (e: Throwable) {
                _state.update { it.copy(connecting = null, connectError = friendly(e)) }
            }
        }
    }

    /** The backend token is kept in memory only, so after a restart the wallet is asked once to sign in again. */
    fun ensureSignedIn(sender: ActivityResultSender, then: () -> Unit = {}) {
        if (c.account.signedIn) { then(); return }
        connect(sender)
    }

    fun disconnect(sender: ActivityResultSender) {
        viewModelScope.launch {
            runCatching { c.account.disconnect(sender) }
            _state.value = UiState()
        }
    }

    fun dismissConnectError() = _state.update { it.copy(connectError = null) }

    // ------------------------------------------------------------------ data

    fun loadMeta() {
        viewModelScope.launch { runCatching { c.api.meta() }.onSuccess { m -> _state.update { it.copy(meta = m) } } }
    }

    fun refreshList() {
        if (!c.account.signedIn) return
        _state.update { it.copy(loadingList = true, listError = null) }
        viewModelScope.launch {
            try {
                val list = c.api.challenges(mine = true).challenges
                _state.update { it.copy(challenges = list, loadingList = false) }
            } catch (e: Throwable) {
                _state.update { it.copy(loadingList = false, listError = friendly(e)) }
            }
        }
    }

    fun loadDetail(pool: String) {
        viewModelScope.launch {
            try {
                val d = c.api.challenge(pool)
                _state.update { it.copy(detail = d, detailError = null) }
            } catch (e: Throwable) {
                _state.update { it.copy(detailError = friendly(e)) }
            }
        }
    }

    fun clearDetail() = _state.update { it.copy(detail = null, detailError = null, flow = TxFlow.Idle) }

    // ------------------------------------------------------------------ transactions

    fun resetFlow() = _state.update { it.copy(flow = TxFlow.Idle) }

    private fun setFlow(f: TxFlow) = _state.update { it.copy(flow = f) }

    private fun requireAccount(): Account = _state.value.account ?: throw WalletException("Connect a wallet first.")

    /** Step 1 of a new challenge: build the create transaction, check it on the phone, then ask the user to confirm. */
    fun startChallenge(draft: GoalDraft) {
        viewModelScope.launch {
            try {
                val account = requireAccount()
                val meta = _state.value.meta ?: c.api.meta().also { m -> _state.update { it.copy(meta = m) } }
                val cfg = meta.config ?: throw ApiException(0, "not_initialised", "The Vowed program is not set up on this network yet.")
                val mint = (if (draft.demo) cfg.demoMints else cfg.allowedMints).firstOrNull() ?: throw ApiException(0, "no_token", "No token is enabled for this kind of challenge.")
                setFlow(TxFlow.Working("Preparing your challenge…"))

                val daySecs = 60
                val now = System.currentTimeMillis() / 1000
                // A demo pool starts in two minutes (its join window is one demo day); a normal pool starts in ten.
                val startTs = now + if (draft.demo) 120L else 600L
                val penaltyBps = if (draft.mode == "Hard") 10_000 else 3_000
                val joinWindow = if (draft.demo) daySecs.toLong() else 3_600L
                val maxParticipants = if (draft.demo) 10 else 50
                val plan: JsonObject = draft.template.plan(draft.totalDays, draft.requiredDays)
                val goalHash = PlanHash.hash(plan)

                val resp = c.api.createTx(
                    CreateTxRequest(
                        mint = mint, kind = "Squad", mode = draft.mode, penaltyBps = if (draft.mode == "Soft") penaltyBps else null,
                        startTs = startTs, joinWindowSecs = joinWindow, maxParticipants = maxParticipants, plan = plan,
                        demo = if (draft.demo) DemoRequest(daySecs) else null,
                    ),
                )
                val txBytes = Base64.decode(resp.transaction, Base64.DEFAULT)
                val poolId = resp.summary["poolId"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                    ?: throw TxRejected("the server did not say which pool this is")
                val review = TxChecker.checkCreate(
                    txBytes,
                    CreateExpectation(
                        wallet = Base58.decode(account.wallet), mint = Base58.decode(mint), poolId = poolId, mode = draft.mode, penaltyBps = penaltyBps,
                        startTs = startTs, durationDays = draft.totalDays, requiredDays = draft.requiredDays, joinWindowSecs = joinWindow,
                        maxParticipants = maxParticipants, demoDaySecs = if (draft.demo) daySecs else 0, goalHash = goalHash,
                    ),
                )
                val pool = resp.pool
                setFlow(
                    TxFlow.Review(
                        PendingTx("create", review, txBytes, pool) {
                            // After the pool exists, the creator joins it with the chosen stake.
                            prepareJoin(pool, mint, draft.stakeBaseUnits.toString(), final = true)
                        },
                    ),
                )
            } catch (e: Throwable) {
                setFlow(TxFlow.Failed(friendly(e)))
            }
        }
    }

    /** Builds, checks and queues a join transaction. Used after creating a pool and from a pool's detail screen. */
    fun prepareJoin(pool: String, mint: String, stakeBaseUnits: String, final: Boolean = true) {
        viewModelScope.launch { prepareJoinSuspend(pool, mint, stakeBaseUnits) }
    }

    private suspend fun prepareJoinSuspend(pool: String, mint: String, stakeBaseUnits: String) {
        try {
            val account = requireAccount()
            setFlow(TxFlow.Working("Preparing your stake…"))
            val tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
            val resp = c.api.joinTx(JoinTxRequest(pool, stakeBaseUnits, tz, account.deviceId))
            val txBytes = Base64.decode(resp.transaction, Base64.DEFAULT)
            val review = TxChecker.checkJoin(
                txBytes,
                JoinExpectation(Base58.decode(account.wallet), Base58.decode(pool), Base58.decode(mint), stakeBaseUnits, tz, hex(account.deviceId)),
            )
            setFlow(TxFlow.Review(PendingTx("join", review, txBytes, pool)))
        } catch (e: Throwable) {
            setFlow(TxFlow.Failed(friendly(e)))
        }
    }

    fun prepareClaim(pool: String, mint: String) {
        viewModelScope.launch {
            try {
                val account = requireAccount()
                setFlow(TxFlow.Working("Preparing your claim…"))
                val resp = c.api.claimTx(ClaimTxRequest(pool))
                val txBytes = Base64.decode(resp.transaction, Base64.DEFAULT)
                val review = TxChecker.checkClaim(txBytes, ClaimExpectation(Base58.decode(account.wallet), Base58.decode(pool), Base58.decode(mint)))
                setFlow(TxFlow.Review(PendingTx("claim", review, txBytes, pool)))
            } catch (e: Throwable) {
                setFlow(TxFlow.Failed(friendly(e)))
            }
        }
    }

    /** The user pressed "Sign with wallet" on a reviewed transaction. */
    fun confirm(sender: ActivityResultSender) {
        val flow = _state.value.flow as? TxFlow.Review ?: return
        val tx = flow.tx
        setFlow(TxFlow.Working("Waiting for your wallet…"))
        viewModelScope.launch {
            try {
                val sig = c.wallet.signAndSend(sender, tx.txBytes)
                setFlow(TxFlow.Working("Confirming on Solana…"))
                val signature = Base58.encode(sig)
                // the transaction is already submitted; tell the backend so it mirrors the result right away
                var synced = false
                repeat(6) { attempt ->
                    if (!synced) {
                        try { c.api.sync(signature); synced = true } catch (e: ApiException) { if (e.code != "not_found") throw e; delay(2_000L + attempt * 500L) }
                    }
                }
                val next = tx.after
                if (next != null) {
                    next()
                } else {
                    val msg = when (tx.kind) {
                        "join" -> "You're in. Your stake is held by the program."
                        "claim" -> "Payout sent to your wallet."
                        else -> "Done."
                    }
                    setFlow(TxFlow.Done(tx.pool, msg))
                    refreshList()
                }
            } catch (e: Throwable) {
                setFlow(TxFlow.Failed(friendly(e)))
            }
        }
    }

    fun cancelReview() = setFlow(TxFlow.Idle)

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
