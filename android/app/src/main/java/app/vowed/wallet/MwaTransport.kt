package app.vowed.wallet

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationIntentCreator
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationScenario
import com.solana.mobilewalletadapter.common.protocol.SessionProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.solana.mobilewalletadapter.common.util.NotifyOnCompleteFuture
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Opens the wallet with the association intent, the same way the Mobile Wallet Adapter library does (the intent has no extra flags and is started
 * for a result from Vowed's activity), and reports when the wallet's screen returns. Created once in [app.vowed.MainActivity.onCreate]
 * (a result launcher must be registered before the activity is started).
 */
class WalletLauncher(private val activity: ComponentActivity) {
    @Volatile private var onResult: ((Int) -> Unit)? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val cb = onResult
        onResult = null
        cb?.invoke(r.resultCode)
    }

    val context: Context get() = activity

    /** Starts the wallet. Waits (briefly) until Vowed is on screen, because Android refuses to start another app's screen from the background. */
    suspend fun launch(intent: Intent, onResult: (Int) -> Unit) {
        AppForeground.awaitResumed(5_000)
        withContext(Dispatchers.Main) {
            this@WalletLauncher.onResult = onResult
            try {
                launcher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                this@WalletLauncher.onResult = null
                throw WalletException("No Solana wallet app was found on this phone. Install a Mobile Wallet Adapter wallet first.")
            }
        }
    }
}

/**
 * [WalletTransport] over the Mobile Wallet Adapter client library (the same classes the Kotlin wrapper uses), driven by Vowed's own code so that
 *  - the wallet has [WalletVisit.CONNECT_TIMEOUT_MS] to connect (the wrapper's fixed wait is 10 s),
 *  - nothing closes the session 5 s after the wallet's screen returns (the wrapper does),
 *  - every step goes into the trace with a time.
 */
class MwaTransport(
    private val launcher: WalletLauncher,
    private val identityUri: Uri,
    private val iconUri: Uri,
    private val identityName: String,
    private val chain: String,
    private val requestTimeoutMs: Int,
    private val trace: WalletTrace,
) : WalletTransport {
    private suspend fun <T> await(f: NotifyOnCompleteFuture<T>): T = awaitFuture(f)

    /**
     * Starts a library call on a background thread and waits for it WITHOUT being tied to it. Some of these calls (starting the association, writing a request
     * to the socket) block for as long as the wallet is slow or has died, and a blocking call cannot be interrupted by a coroutine time-out or by Cancel.
     * The call therefore runs in its own detached scope: the caller can stop waiting at any time (the time-out, Cancel, Try again) and the stuck call is
     * simply left behind until the session is closed, instead of freezing the screen or holding the main thread.
     */
    private val ioScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private suspend fun <T> onIo(block: () -> T): T = ioScope.async { block() }.await()

    private var scenario: LocalAssociationScenario? = null
    private var client: MobileWalletAdapterClient? = null
    private var protocol: SessionProperties.ProtocolVersion? = null

    /** The MWA error code the wallet answered with, looking through wrapped exceptions. */
    private fun remoteCode(e: Throwable?): Int? {
        var t = e
        var depth = 0
        while (t != null && depth++ < 6) {
            if (t is JsonRpc20Client.JsonRpc20RemoteException) return t.code
            t = t.cause
        }
        return null
    }

    private fun failure(step: String, e: Throwable): WalletException {
        val code = remoteCode(e)
        val text = WalletErrors.flatten(e)
        val kind = if (e is TimeoutException || (e is ExecutionException && e.cause is TimeoutException)) WalletErrors.Kind.NoResponse else WalletErrors.classify(text, code)
        trace.add("$step: failed", "kind=$kind${if (code != null) ", wallet error code $code" else ""}; $text")
        val message = when (kind) {
            WalletErrors.Kind.NoResponse -> WalletErrors.NO_RESPONSE
            WalletErrors.Kind.AuthorizationRefused -> WalletErrors.AUTHORIZATION_REFUSED
            WalletErrors.Kind.NetworkMismatch -> WalletErrors.WRONG_NETWORK
            WalletErrors.Kind.SessionClosed -> WalletErrors.SESSION_CLOSED
            else -> WalletErrors.explain(text)
        }
        return WalletException(message, kind, code, text)
    }

    override suspend fun launch() {
        val sc = LocalAssociationScenario(requestTimeoutMs)
        scenario = sc
        if (!LocalAssociationIntentCreator.isWalletEndpointAvailable(launcher.context.packageManager)) {
            trace.add("no wallet app found")
            throw WalletException("No Solana wallet app was found on this phone. Install a Mobile Wallet Adapter wallet first.")
        }
        val intent = LocalAssociationIntentCreator.createAssociationIntent(null, sc.port, sc.session)
        trace.add("association intent ready", "local port ${sc.port}, task: ${WalletEnvironment.taskSummary(launcher.context)}")
        launcher.launch(intent) { code ->
            trace.add("wallet screen returned a result", "result code $code (${if (code == -1) "OK" else if (code == 0) "cancelled" else "other"}); Vowed does NOT close the session because of it; task: ${WalletEnvironment.taskSummary(launcher.context)}")
        }
    }

    override suspend fun connect(timeoutMs: Long): String {
        val sc = scenario ?: error("launch first")
        try {
            val c = kotlinx.coroutines.withTimeout(timeoutMs) { await(onIo { sc.start() }) }
            client = c
            protocol = sc.session.sessionProperties.protocolVersion
            return "protocol ${protocol}"
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw failure("connect", TimeoutException("the wallet did not connect within ${timeoutMs / 1000} s"))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw failure("connect", e)
        }
    }

    override suspend fun authorize(token: String?): WalletAuth {
        val c = client ?: error("connect first")
        try {
            val result = if (protocol == SessionProperties.ProtocolVersion.LEGACY) {
                // a wallet that speaks the first protocol version: reauthorize with a token, otherwise authorize for the cluster
                await(onIo { if (token != null) c.reauthorize(identityUri, iconUri, identityName, token) else c.authorize(identityUri, iconUri, identityName, "devnet") })
            } else {
                // the current protocol: ONE method, `authorize`, which a wallet should answer without a screen when it still knows the token
                await(onIo { c.authorize(identityUri, iconUri, identityName, chain, token, null, null, null) })
            }
            val address = result.accounts.firstOrNull()?.publicKey ?: result.publicKey
            return WalletAuth(result.authToken, address)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw failure("authorize", e)
        }
    }

    override suspend fun signAndSend(tx: ByteArray): ByteArray {
        val c = client ?: error("connect first")
        try {
            return await(onIo { c.signAndSendTransactions(arrayOf(tx), null) }).signatures.first()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw failure("sign_and_send_transactions", e)
        }
    }

    override suspend fun close(reason: String) {
        val sc = scenario ?: return
        scenario = null
        client = null
        runCatching { kotlinx.coroutines.withTimeoutOrNull(10_000) { await(onIo { sc.close() }) } }
        ioScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
