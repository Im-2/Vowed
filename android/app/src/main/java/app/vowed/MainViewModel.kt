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
    /** The exact plan for the pool (the server-validated plan; the demo version for demo pools). It is hashed and shown as it is. */
    val plan: JsonObject,
    val mode: String, // "Soft" or "Hard"
    val stakeBaseUnits: BigInteger,
    val demo: Boolean,
    /** Length of a demo "day" in seconds (60..3600); only used when [demo]. */
    val demoDaySecs: Int = 120,
    /** The app a usage goal watches. */
    val appName: String? = null,
    /** The spot for place goals; stays on this phone. */
    val place: Pair<Double, Double>? = null,
    /** "private" (default), "public" (listed in Explore) or, with [squadId], a squad challenge (always private). */
    val visibility: String = "private",
    val squadId: String? = null,
) {
    val totalDays: Int get() = app.vowed.goals.PlanEdit.totalDays(plan)
    val requiredDays: Int get() = app.vowed.goals.PlanEdit.requiredDays(plan)
}

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

/** State of the goal text box and the plan preview. */
data class GoalUi(
    val parsing: Boolean = false,
    val result: app.vowed.data.ParseResult? = null,
    val error: String? = null,
    val templates: List<app.vowed.data.TemplateInfo> = emptyList(),
    /** a rotating, mixed set of example goals from the server (never mostly reps) */
    val examples: List<app.vowed.data.ExampleGoal> = emptyList(),
    val useAi: Boolean = true,
)

/** The Explore list: public challenges, filters, and the DEMO quick-challenge section. */
data class ExploreUi(
    val items: List<app.vowed.data.ExploreItem> = emptyList(),
    val demoPools: List<app.vowed.data.ExploreItem> = emptyList(),
    val quick: List<app.vowed.data.ExampleGoal> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val loaded: Boolean = false,
    val nextCursor: String? = null,
    val error: String? = null,
    val category: String? = null,
    val mint: String? = null,
    val endingSoon: Boolean = false,
    val message: String? = null,
)

/** Squads: my list, and one open squad with its feed and leaderboard. */
data class SquadsUi(
    val list: List<app.vowed.data.Squad> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val detail: app.vowed.data.SquadDetail? = null,
    val feed: List<app.vowed.data.FeedEvent> = emptyList(),
    val board: List<app.vowed.data.LeaderRow> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

/** State of the "Get test tokens" card. */
data class FaucetUi(
    val status: app.vowed.data.FaucetStatus? = null,
    val loading: Boolean = false,
    val claiming: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

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
    /** Detail of each of my open challenges (for today's status on the home screen). */
    val details: Map<String, ChallengeDetail> = emptyMap(),
    val checkIn: CheckInState? = null,
    val faucet: FaucetUi = FaucetUi(),
    val goal: GoalUi = GoalUi(),
    val explore: ExploreUi = ExploreUi(),
    val squads: SquadsUi = SquadsUi(),
    /** goal text to try as soon as the new-challenge screen opens (a tapped quick challenge or example) */
    val pendingGoalText: String? = null,
    val pendingDemo: Boolean = false,
    /** set when a challenge is started from a squad: it is created for that squad and stays private */
    val newGoalSquadId: String? = null,
    /** an invite code from a link, waiting for the Squads screen */
    val pendingJoinCode: String? = null,
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
                // today's status for my open challenges (a handful at most)
                for (ch in list.filter { it.status == "Open" }.take(8)) {
                    runCatching { c.api.challenge(ch.pool) }.onSuccess { d -> _state.update { st -> st.copy(details = st.details + (ch.pool to d)) } }
                }
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

                val daySecs = draft.demoDaySecs
                val now = System.currentTimeMillis() / 1000
                // A demo pool starts in two minutes (its join window is one demo day); a normal pool starts in ten.
                val startTs = now + if (draft.demo) 120L else 600L
                val penaltyBps = if (draft.mode == "Hard") 10_000 else 3_000
                val joinWindow = if (draft.demo) daySecs.toLong() else 3_600L
                val maxParticipants = if (draft.demo) 10 else 50
                val plan: JsonObject = draft.plan
                val goalHash = PlanHash.hash(plan)

                val resp = c.api.createTx(
                    CreateTxRequest(
                        mint = mint, kind = null, visibility = if (draft.squadId != null) "private" else draft.visibility, mode = draft.mode, penaltyBps = if (draft.mode == "Soft") penaltyBps else null,
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
                draft.place?.let { (lat, lon) -> c.prefs.setPlace(pool, lat, lon) }
                draft.appName?.let { c.prefs.setWatchedApp(pool, it) }
                setFlow(
                    TxFlow.Review(
                        PendingTx("create", review, txBytes, pool) {
                            // a squad challenge is linked to its squad as soon as the pool exists, then the creator joins with the chosen stake
                            draft.squadId?.let { sid -> runCatching { c.api.linkPool(sid, pool) } }
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

    // ------------------------------------------------------------------ daily proofs

    fun myParticipant(d: ChallengeDetail): app.vowed.data.Participant? = d.participants.firstOrNull { it.wallet == _state.value.account?.wallet }

    fun openCheckIn(pool: String) {
        viewModelScope.launch {
            try {
                val d = c.api.challenge(pool)
                _state.update { it.copy(detail = d, details = it.details + (pool to d)) }
                val ch = d.challenge
                val me = myParticipant(d)
                val kind = CheckInLogic.planKind(ch.plan) ?: throw ApiException(0, "no_plan", "This challenge has no goal plan to check in against.")
                val target = CheckInLogic.planTarget(ch.plan) ?: throw ApiException(0, "no_plan", "This challenge's goal plan is incomplete.")
                val dv = CheckInLogic.dayView(ch, me, System.currentTimeMillis() / 1000)
                _state.update {
                    it.copy(
                        checkIn = CheckInState(pool, ch, me, kind, dv.day, dv.dayOpen, dv.done, target, CheckInLogic.planParams(ch.plan), c.prefs.place(pool)),
                    )
                }
            } catch (e: Throwable) {
                _state.update { it.copy(detailError = friendly(e)) }
            }
        }
    }

    fun closeCheckIn() = _state.update { it.copy(checkIn = null) }

    private fun setSubmission(sub: Submission, pkg: JsonObject? = null) =
        _state.update { st -> st.checkIn?.let { ci -> st.copy(checkIn = ci.copy(submission = sub, lastPackage = pkg ?: ci.lastPackage)) } ?: st }

    /** Sends what a collector measured. The server decides; a refusal comes back with its reason and nothing is recorded. */
    fun submitProof(collected: app.vowed.proof.Collected) {
        val ci = _state.value.checkIn ?: return
        setSubmission(Submission.Working)
        viewModelScope.launch {
            try {
                val session = c.proofs.open(ci.pool, ci.day, ci.kind)
                val pkg = c.proofs.buildPackage(session, ci.pool, ci.kind, collected)
                setSubmission(Submission.Working, pkg)
                val res = c.proofs.resubmit(pkg)
                setSubmission(Submission.Accepted(res), pkg)
                refreshList()
                loadDetail(ci.pool)
            } catch (e: ApiException) {
                setSubmission(Submission.Rejected(if (e.code == "proof_rejected") "The server refused this proof: ${e.message}" else friendly(e)))
            } catch (e: Throwable) {
                setSubmission(Submission.Rejected(friendly(e)))
            }
        }
    }

    /** Debug builds only: produce test data for the proof type (good data meets the target, bad data misses it). */
    fun injectProof(good: Boolean) {
        if (!app.vowed.debug.DebugProofs.ENABLED) return
        val ci = _state.value.checkIn ?: return
        submitProof(app.vowed.debug.DebugProofs.inject(ci.kind, ci.target, System.currentTimeMillis() / 1000, good))
    }

    /** Debug builds only: send the last package again to show that a replay is refused. */
    fun replayLast() {
        if (!app.vowed.debug.DebugProofs.ENABLED) return
        val ci = _state.value.checkIn ?: return
        val pkg = ci.lastPackage ?: return
        setSubmission(Submission.Working)
        viewModelScope.launch {
            try {
                val res = c.proofs.resubmit(pkg)
                setSubmission(Submission.Accepted(res))
            } catch (e: Throwable) {
                setSubmission(Submission.Rejected("Replay: " + friendly(e)))
            }
        }
    }

    fun stepBaseline(pool: String, day: Int) = c.prefs.stepBaseline(pool, day)
    fun saveStepBaseline(pool: String, day: Int, total: Long) = c.prefs.setStepBaseline(pool, day, total)
    fun watchedApp(pool: String) = c.prefs.watchedApp(pool)
    val appContext get() = getApplication<Application>()

    // ------------------------------------------------------------------ test tokens (devnet faucet)

    fun loadFaucet() {
        if (!c.account.signedIn) return
        _state.update { it.copy(faucet = it.faucet.copy(loading = true, error = null)) }
        viewModelScope.launch {
            try {
                val st = c.api.faucet()
                _state.update { it.copy(faucet = it.faucet.copy(status = st, loading = false)) }
            } catch (e: Throwable) {
                _state.update { it.copy(faucet = it.faucet.copy(loading = false, error = friendly(e))) }
            }
        }
    }

    fun claimTestTokens() {
        if (_state.value.faucet.claiming) return
        _state.update { it.copy(faucet = it.faucet.copy(claiming = true, error = null, message = null)) }
        viewModelScope.launch {
            try {
                val r = c.api.faucetClaim()
                val st = runCatching { c.api.faucet() }.getOrNull()
                _state.update { it.copy(faucet = it.faucet.copy(claiming = false, status = st ?: it.faucet.status, message = "Sent ${app.vowed.core.TxChecker.formatUnits(java.math.BigInteger(r.minted.tUSDC))} tUSDC and ${app.vowed.core.TxChecker.formatUnits(java.math.BigInteger(r.minted.tSKR))} tSKR (test tokens).")) }
            } catch (e: Throwable) {
                val st = runCatching { c.api.faucet() }.getOrNull()
                _state.update { it.copy(faucet = it.faucet.copy(claiming = false, status = st ?: it.faucet.status, error = friendly(e))) }
            }
        }
    }

    // ------------------------------------------------------------------ goals in plain words

    fun loadTemplates() {
        if (_state.value.goal.templates.isNotEmpty() || !c.account.signedIn) return
        viewModelScope.launch {
            runCatching { c.api.goalTemplates() }.onSuccess { t -> _state.update { it.copy(goal = it.goal.copy(templates = t.templates)) } }
        }
    }

    fun loadExamples(force: Boolean = false) {
        if (!c.account.signedIn || (!force && _state.value.goal.examples.isNotEmpty())) return
        viewModelScope.launch {
            runCatching { c.api.goalExamples(8) }.onSuccess { e -> _state.update { it.copy(goal = it.goal.copy(examples = e.examples)) } }
        }
    }

    fun setNewGoalSquad(id: String?) = _state.update { it.copy(newGoalSquadId = id) }
    fun setPendingJoinCode(code: String?) = _state.update { it.copy(pendingJoinCode = code) }

    fun setUseAi(on: Boolean) = _state.update { it.copy(goal = it.goal.copy(useAi = on)) }

    /** Sends the typed text to the server (and, if AI is on and the templates are not sure, to the language model). Nothing else leaves the phone. */
    fun parseGoal(text: String) {
        val useAi = _state.value.goal.useAi
        _state.update { it.copy(goal = it.goal.copy(parsing = true, error = null)) }
        viewModelScope.launch {
            try {
                val r = c.api.parseGoal(text.trim(), useAi)
                _state.update { it.copy(goal = it.goal.copy(parsing = false, result = r)) }
            } catch (e: Throwable) {
                _state.update { it.copy(goal = it.goal.copy(parsing = false, error = friendly(e))) }
            }
        }
    }

    /** Shows one of the alternatives (for example the low-trust self-report version) as the plan. */
    fun useAlternative(o: app.vowed.data.PlanOption) = _state.update {
        it.copy(goal = it.goal.copy(result = app.vowed.data.ParseResult(
            status = "plan", source = it.goal.result?.source ?: "template", plan = o.plan, demoPlan = o.demoPlan, trustTier = "low", needsPlace = o.extras.needsPlace,
            needsApp = o.extras.needsApp, limitations = o.extras.limitations, confidence = "high",
        )))
    }

    fun clearGoal() = _state.update { it.copy(goal = it.goal.copy(result = null, error = null, parsing = false)) }

    /**
     * The user pressed Review on the preview: apply their edits, have the server validate the exact plan, then continue exactly like
     * a template goal (the phone re-checks the transaction against this plan before it asks the wallet to sign).
     */
    fun startFromPreview(edit: app.vowed.goals.Edit, mode: String, stakeBaseUnits: BigInteger, demo: Boolean, demoDaySecs: Int, place: Pair<Double, Double>?, visibility: String = "private", squadId: String? = null) {
        val base = _state.value.goal.result?.plan ?: return
        setFlow(TxFlow.Working("Checking your plan…"))
        viewModelScope.launch {
            try {
                val edited = app.vowed.goals.PlanEdit.apply(base, edit)
                val v = c.api.validateGoal(edited, demo = false)
                if (!v.ok || v.plan == null) throw ApiException(0, "plan_invalid", v.reason ?: "That plan cannot be staked on.")
                val chosen = if (demo) v.demoPlan ?: throw ApiException(0, "no_demo_version", "This goal has no demo version; turn off the demo pool.") else v.plan
                startChallenge(GoalDraft(chosen, mode, stakeBaseUnits, demo, demoDaySecs, edit.app, place, visibility, squadId))
            } catch (e: Throwable) {
                setFlow(TxFlow.Failed(friendly(e)))
            }
        }
    }

    // ------------------------------------------------------------------ Explore

    fun loadExplore(reset: Boolean = true) {
        if (!c.account.signedIn) return
        val ex0 = _state.value.explore
        if (ex0.loading || ex0.loadingMore) return
        _state.update { it.copy(explore = it.explore.copy(loading = reset, loadingMore = !reset, error = null)) }
        viewModelScope.launch {
            try {
                val e = _state.value.explore
                val page = c.api.explore(e.category, e.mint, "exclude", e.endingSoon, if (reset) null else e.nextCursor)
                val demo = if (reset) runCatching { c.api.explore(null, null, "only", false, null).items }.getOrDefault(emptyList()) else e.demoPools
                val quick = if (reset && e.quick.isEmpty()) runCatching { c.api.goalExamples(13).examples.filter { it.family != "screen" && it.family != "sleep" }.take(5) }.getOrDefault(emptyList()) else e.quick
                _state.update {
                    it.copy(explore = it.explore.copy(items = if (reset) page.items else it.explore.items + page.items, demoPools = demo, quick = quick, nextCursor = page.nextCursor, loading = false, loadingMore = false, loaded = true))
                }
            } catch (ex: Throwable) {
                _state.update { it.copy(explore = it.explore.copy(loading = false, loadingMore = false, error = friendly(ex))) }
            }
        }
    }

    fun setExploreFilter(category: String? = _state.value.explore.category, mint: String? = _state.value.explore.mint, endingSoon: Boolean = _state.value.explore.endingSoon) {
        _state.update { it.copy(explore = it.explore.copy(category = category, mint = mint, endingSoon = endingSoon, nextCursor = null)) }
        loadExplore(true)
    }

    fun reportChallenge(pool: String, reason: String) {
        viewModelScope.launch {
            try {
                c.api.report(pool, reason)
                _state.update { it.copy(explore = it.explore.copy(message = "Thanks. Your report was sent.")) }
            } catch (e: Throwable) {
                _state.update { it.copy(explore = it.explore.copy(message = friendly(e))) }
            }
        }
    }

    fun clearExploreMessage() = _state.update { it.copy(explore = it.explore.copy(message = null)) }

    /** Starts the new-challenge flow with a goal already typed (a quick challenge or an example). */
    fun tryGoal(text: String, demo: Boolean) = _state.update { it.copy(pendingGoalText = text, pendingDemo = demo, goal = it.goal.copy(result = null, error = null)) }
    fun goalTextConsumed() = _state.update { it.copy(pendingGoalText = null) }

    // ------------------------------------------------------------------ Squads

    fun loadSquads() {
        if (!c.account.signedIn) return
        _state.update { it.copy(squads = it.squads.copy(loading = true, error = null)) }
        viewModelScope.launch {
            try {
                val l = c.api.squads().squads
                _state.update { it.copy(squads = it.squads.copy(list = l, loading = false, loaded = true)) }
            } catch (e: Throwable) {
                _state.update { it.copy(squads = it.squads.copy(loading = false, error = friendly(e))) }
            }
        }
    }

    fun createSquad(name: String) = squadAction("Squad created.") { val sq = c.api.createSquad(name.trim()); loadSquads(); openSquad(sq.id) }
    fun joinSquad(code: String) = squadAction("You joined the squad.") { val sq = c.api.joinSquad(code.trim().uppercase()); loadSquads(); openSquad(sq.id) }

    private fun squadAction(done: String, block: suspend () -> Unit) {
        _state.update { it.copy(squads = it.squads.copy(busy = true, error = null, message = null)) }
        viewModelScope.launch {
            try {
                block()
                _state.update { it.copy(squads = it.squads.copy(busy = false, message = done)) }
            } catch (e: Throwable) {
                _state.update { it.copy(squads = it.squads.copy(busy = false, error = friendly(e))) }
            }
        }
    }

    fun openSquad(id: String) {
        viewModelScope.launch {
            try {
                val d = c.api.squad(id)
                val f = runCatching { c.api.feed(id).events }.getOrDefault(emptyList())
                val b = runCatching { c.api.leaderboard(id).rows }.getOrDefault(emptyList())
                _state.update { it.copy(squads = it.squads.copy(detail = d, feed = f, board = b, error = null)) }
            } catch (e: Throwable) {
                _state.update { it.copy(squads = it.squads.copy(error = friendly(e))) }
            }
        }
    }

    fun closeSquad() = _state.update { it.copy(squads = it.squads.copy(detail = null, feed = emptyList(), board = emptyList(), message = null)) }

    fun nudge(squadId: String, wallet: String) = squadAction("Nudge sent.") { c.api.nudge(squadId, wallet); openSquad(squadId) }

    // ------------------------------------------------------------------ squad notifications (a poll; push comes with a Firebase project)

    private val _incoming = kotlinx.coroutines.flow.MutableSharedFlow<app.vowed.data.NoteItem>(extraBufferCapacity = 32)
    val incoming: kotlinx.coroutines.flow.SharedFlow<app.vowed.data.NoteItem> = _incoming

    /** Asks the server what happened in my squads since the last look, and emits each new item once. */
    fun pollNotifications() {
        if (!c.account.signedIn) return
        viewModelScope.launch {
            try {
                val since = c.prefs.notesSince
                val n = c.api.notifications(since)
                if (since > 0) n.items.sortedBy { it.id }.forEach { _incoming.emit(it) } // the first look only sets the clock: no flood of old news
                c.prefs.notesSince = n.now
            } catch (_: Throwable) {
            }
        }
    }
}
