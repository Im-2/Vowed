package app.vowed.wallet

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the wallet answered to the authorization: the token to save for next time and the account's address bytes. */
class WalletAuth(val token: String?, val address: ByteArray)

/**
 * The few things one wallet visit needs, as Vowed's own code sees them. [MwaTransport] implements them with the Mobile Wallet Adapter client
 * library; tests use a fake that counts launches. Every method throws [WalletException] (never a raw library exception).
 */
interface WalletTransport {
    /** Sends the association intent: this is the moment the wallet app opens. Throws when there is no wallet app. */
    suspend fun launch()

    /** Waits for the wallet to connect to Vowed. Returns a short description (protocol version) for the trace. */
    suspend fun connect(timeoutMs: Long): String

    /** `authorize`, with the saved authorization token when there is one (the wallet should then answer silently). */
    suspend fun authorize(token: String?): WalletAuth

    /** `sign_and_send_transactions` for one transaction; returns the 64-byte transaction signature. */
    suspend fun signAndSend(tx: ByteArray): ByteArray

    /** Ends the session. Vowed is the only one that ever calls this (no timer of the library does), and it says why. */
    suspend fun close(reason: String)
}

/**
 * One visit to the wallet for ONE transaction: open the wallet once, authorize (with the saved token), ask for the signature, close.
 *
 * Rules (each one has a test):
 *  - the wallet is launched exactly once per visit; nothing here ever launches it a second time;
 *  - a wallet that refuses the saved token (MWA error -1) is asked to authorize again in the SAME session, without the token;
 *  - Vowed is patient: [connectTimeoutMs] for the wallet to connect, and no timer closes the session while the wallet shows its screen;
 *  - the session is closed only by Vowed, when the visit ends (success, failure or the person tapping Try again), and the trace says why;
 *  - when nothing has answered after [stallAfterMs], [onStalled] tells the screen to show the "Waiting for the wallet" hint with Try again.
 */
class WalletVisit(
    private val transport: WalletTransport,
    private val trace: WalletTrace,
    private val clock: () -> Long = System::currentTimeMillis,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val stallAfterMs: Long = STALL_AFTER_MS,
    private val onStalled: () -> Unit = {},
    private val answerTimeoutMs: Long = ANSWER_TIMEOUT_MS,
) {
    suspend fun signAndSend(tx: ByteArray, savedToken: String?, saveToken: (String?) -> Unit): ByteArray {
        val t0 = clock()
        fun since() = "${clock() - t0} ms after the visit started"
        var closeReason = "visit ended"
        trace.add("association started", "one visit for one transaction (${tx.size} bytes)")
        try {
            return coroutineScope {
                // the hint is armed from the moment the wallet is opened, until the signature (or a failure) arrives
                val stall = launch { delay(stallAfterMs); trace.add("no answer yet", "${stallAfterMs / 1000} s: showing the waiting hint with Try again"); onStalled() }
                try {
                    transport.launch()
                    trace.add("wallet launched", since())
                    val info = transport.connect(connectTimeoutMs)
                    trace.add("connection established", "$info, ${since()}")

                    val tAuth = clock()
                    trace.add("authorize sent", "saved authorization: ${if (savedToken != null) "yes (the wallet should answer without a screen)" else "no (the wallet will ask to connect)"}")
                    val auth = try {
                        bounded("authorize") { transport.authorize(savedToken) }
                    } catch (e: WalletException) {
                        if (savedToken == null || e.code != WalletErrors.CODE_AUTHORIZATION_FAILED) throw e
                        // MWA spec: a token the wallet no longer accepts must be discarded; authorize again in the same session (no new launch)
                        trace.add("wallet refused the saved authorization", "code -1 after ${clock() - tAuth} ms; discarded; authorizing again in the same session")
                        saveToken(null)
                        bounded("authorize") { transport.authorize(null) }
                    }
                    val authMs = clock() - tAuth
                    saveToken(auth.token)
                    trace.add(
                        "authorize result",
                        "$authMs ms" + if (authMs > 2_500) " (the wallet took long: it probably showed a screen)" else " (quick: no screen needed)",
                    )

                    trace.add("sign request sent", "sign_and_send_transactions, ${since()}")
                    val tSign = clock()
                    val sig = bounded("sign_and_send_transactions") { transport.signAndSend(tx) }
                    trace.add("wallet response", "signature received after ${clock() - tSign} ms of waiting for the approval")
                    closeReason = "finished: the wallet answered"
                    sig
                } finally {
                    stall.cancel()
                }
            }
        } catch (e: Throwable) {
            closeReason = "Vowed ends the visit: ${e.javaClass.simpleName}${(e as? WalletException)?.let { " (${it.kind}${it.code?.let { c -> ", code $c" } ?: ""})" } ?: ""}"
            throw e
        } finally {
            withContext(NonCancellable) {
                trace.add("session closed", "by Vowed; reason: $closeReason; ${since()}")
                runCatching { transport.close(closeReason) }
            }
        }
    }

    /**
     * The wallet must answer within [answerTimeoutMs]. The library has its own wait, but a wallet that died or never showed its screen can leave a
     * request pending without that wait ever firing, and the person must not be left looking at "Waiting" for ever: after this the visit ends with
     * the actionable "did not ask you to approve" message (Try again is the person's tap, never automatic).
     */
    private suspend fun <T> bounded(step: String, block: suspend () -> T): T {
        val r = kotlinx.coroutines.withTimeoutOrNull(answerTimeoutMs) { block() }
        if (r == null) {
            trace.add("$step: no answer", "the wallet did not answer within ${answerTimeoutMs / 1000} s")
            throw WalletException(WalletErrors.NO_RESPONSE, WalletErrors.Kind.NoResponse, raw = "no answer from the wallet to $step within ${answerTimeoutMs / 1000} s")
        }
        return r
    }

    companion object {
        /** how long the wallet has to answer one request (a Connect screen and an approval each take a person a few seconds to a minute) */
        const val ANSWER_TIMEOUT_MS = 90_000L

        /** the wallet must connect within this long after it was opened (the library's own fixed wait is 10 s; a first Phantom start can take longer) */
        const val CONNECT_TIMEOUT_MS = 45_000L

        /** after this long without an answer the screen shows "Waiting for the wallet" with Try again */
        const val STALL_AFTER_MS = 8_000L
    }
}
