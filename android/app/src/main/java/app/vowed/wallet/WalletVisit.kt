package app.vowed.wallet

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the wallet answered to the authorization: the token to save for next time and the account's address bytes. */
class WalletAuth(val token: String?, val address: ByteArray)

/**
 * The few things one wallet visit needs, as Vowed's own code sees them. [MwaTransport] implements them with the Mobile Wallet Adapter client
 * library; tests use a fake that counts launches. Every method throws [WalletException] (never a raw library exception).
 */
interface WalletTransport {
    /** The wallet app that was opened (package name and the name a person knows it by); known after [launch]. */
    val walletPackage: String?
    val walletLabel: String?

    /** Sends the association intent: this is the moment the wallet app opens. Throws when there is no wallet app. */
    suspend fun launch()

    /** Waits for the wallet to connect to Vowed. Returns a short description (protocol version) for the trace. */
    suspend fun connect(timeoutMs: Long): String

    /** `get_capabilities`: what the wallet says it supports; null when it does not answer. */
    suspend fun capabilities(): WalletCaps?

    /** `authorize`, with the saved authorization token when there is one (the wallet should then answer silently). */
    suspend fun authorize(token: String?): WalletAuth

    /** `sign_and_send_transactions` for one transaction; returns the 64-byte transaction signature. */
    suspend fun signAndSend(tx: ByteArray): ByteArray

    /** `sign_transactions` for one transaction; returns the SIGNED transaction (the wallet does not send it). */
    suspend fun signOnly(tx: ByteArray): ByteArray

    /** Ends the session. Vowed is the only one that ever calls this (no timer of the library does), and it says why. */
    suspend fun close(reason: String)
}

/** What a visit produced: the wallet sent the transaction itself ([Sent]) or only signed it ([Signed]). */
sealed interface VisitResult {
    val method: SignMethod
    val walletPackage: String?

    class Sent(val signature: ByteArray, override val method: SignMethod, override val walletPackage: String?) : VisitResult
    class Signed(val signedTransaction: ByteArray, override val method: SignMethod, override val walletPackage: String?) : VisitResult
}

/** Why the screen shows the waiting hint: which wallet app Vowed is waiting on and whether the transaction request was already sent. */
class StallInfo(val walletLabel: String?, val requestSent: Boolean, val method: SignMethod?)

/** Everything a visit tells the screen and the memory, as callbacks (all optional). */
class VisitHooks(
    val saveToken: (String?) -> Unit = {},
    val walletKnown: (pkg: String?, label: String?) -> Unit = { _, _ -> },
    val methodChosen: (SignMethod) -> Unit = {},
    val stalled: (StallInfo) -> Unit = {},
)

/**
 * One visit to the wallet for ONE transaction: open the wallet once, authorize, ask for the signature by one of the two methods, close.
 *
 * Rules (each one has a test):
 *  - the wallet is launched exactly once per visit; nothing here ever launches it a second time;
 *  - a saved authorization is sent only to a wallet that has not refused one before ([memory]); a wallet that refuses it (MWA error -1) is remembered, and is asked
 *    to authorize again in the SAME session without it;
 *  - the method is the one asked for ([methodOverride], used by Try again), else the one that worked with this wallet before, else what `get_capabilities` says;
 *    the method that gets an answer is remembered for this wallet app;
 *  - Vowed is patient: [connectTimeoutMs] for the wallet to connect, and no timer closes the session while the wallet shows its screen;
 *  - the session is closed only by Vowed, when the visit ends, and the trace says why;
 *  - when nothing has answered [stallAfterMs] after the transaction request was sent, [VisitHooks.stalled] tells the screen to show the waiting hint
 *    (naming the wallet app) with Try again; the same hint appears if the wallet is still connecting after that long;
 *  - a failure carries [WalletException.requestSent]: whether the transaction request had been sent, which decides whether the chain must be checked.
 */
class WalletVisit(
    private val transport: WalletTransport,
    private val trace: WalletTrace,
    private val memory: WalletMemory? = null,
    private val hooks: VisitHooks = VisitHooks(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val stallAfterMs: Long = STALL_AFTER_MS,
    private val answerTimeoutMs: Long = ANSWER_TIMEOUT_MS,
    private val settleMs: Long = SETTLE_MS,
) {
    suspend fun run(tx: ByteArray, savedToken: String?, methodOverride: SignMethod? = null): VisitResult {
        val t0 = clock()
        fun since() = "${clock() - t0} ms after the visit started"
        var closeReason = "visit ended"
        var requestSent = false
        var chosen: SignMethod? = null
        trace.add("association started", "one visit for one transaction (${tx.size} bytes)")
        try {
            return coroutineScope {
                var stall: Job? = null
                fun arm(label: String) {
                    stall?.cancel()
                    stall = launch {
                        delay(stallAfterMs)
                        trace.add("no answer yet", "${stallAfterMs / 1000} s $label: showing the waiting hint with Try again")
                        hooks.stalled(StallInfo(transport.walletLabel, requestSent, chosen))
                    }
                }
                try {
                    arm("after the wallet was opened")
                    transport.launch()
                    val pkg = transport.walletPackage
                    trace.add("wallet launched", "${transport.walletLabel ?: "unknown wallet"} (${pkg ?: "package unknown"}), ${since()}")
                    hooks.walletKnown(pkg, transport.walletLabel)
                    val info = transport.connect(connectTimeoutMs)
                    trace.add("connection established", "$info, ${since()}")

                    val caps = withTimeoutOrNull(CAPS_TIMEOUT_MS) { runCatching { transport.capabilities() }.getOrNull() }
                    trace.add("wallet capabilities", caps?.describe() ?: "the wallet did not answer get_capabilities")
                    val method = MethodChooser.choose(methodOverride, pkg?.let { memory?.method(it) }, caps)
                    chosen = method
                    trace.add("method chosen", "${method.wire} (${method.plain})${when { methodOverride != null -> "; asked for by Try again"; pkg != null && memory?.method(pkg) != null -> "; remembered for this wallet"; else -> "" }}")
                    hooks.methodChosen(method)

                    val tokenHonoured = pkg == null || memory?.tokenHonoured(pkg) != false
                    val tokenToSend = if (tokenHonoured) savedToken else null
                    if (savedToken != null && !tokenHonoured) trace.add("saved authorization not sent", "this wallet refused one before; a plain authorize is used")
                    val tAuth = clock()
                    trace.add("authorize sent", "saved authorization: ${if (tokenToSend != null) "yes (the wallet should answer without a screen)" else "no (the wallet will ask to connect)"}")
                    val auth = try {
                        bounded("authorize") { transport.authorize(tokenToSend) }
                    } catch (e: WalletException) {
                        if (tokenToSend == null || e.code != WalletErrors.CODE_AUTHORIZATION_FAILED) throw e
                        // MWA spec: a token the wallet no longer accepts must be discarded; authorize again in the same session (no new launch), and never offer it to this wallet again
                        trace.add("wallet refused the saved authorization", "code -1 after ${clock() - tAuth} ms; remembered for this wallet; authorizing again in the same session")
                        pkg?.let { memory?.refuseToken(it) }
                        hooks.saveToken(null)
                        bounded("authorize") { transport.authorize(null) }
                    }
                    val authMs = clock() - tAuth
                    hooks.saveToken(auth.token)
                    trace.add("authorize result", "$authMs ms" + if (authMs > 2_500) " (the wallet took long: it probably showed a screen)" else " (quick: no screen needed)")

                    // Real Phantom 26.32 (emulator, 2026-10-11): when the transaction request arrives while its Connect sheet is still closing, it logs
                    // "requestUserApproval initiated" and never draws the approval sheet (the person is left on its Home screen). So after a screen was
                    // shown, the request is held back a moment, which is when the wallet's own screen has finished closing.
                    if (authMs > SCREEN_SHOWN_AFTER_MS && settleMs > 0) {
                        trace.add("waiting for the wallet's connect screen to close", "$settleMs ms before the transaction request is sent")
                        delay(settleMs)
                    }
                    arm("after the transaction request was sent")
                    requestSent = true
                    trace.add("request sent", "${method.wire}, ${since()}")
                    val tSign = clock()
                    val result: VisitResult = when (method) {
                        SignMethod.SendBySelf -> VisitResult.Sent(bounded(method.wire) { transport.signAndSend(tx) }, method, pkg)
                        SignMethod.SignOnly -> VisitResult.Signed(bounded(method.wire) { transport.signOnly(tx) }, method, pkg)
                    }
                    trace.add("wallet response", "${method.wire} answered after ${clock() - tSign} ms of waiting for the approval")
                    pkg?.let { memory?.rememberMethod(it, method) }
                    closeReason = "finished: the wallet answered"
                    result
                } finally {
                    stall?.cancel()
                }
            }
        } catch (e: Throwable) {
            closeReason = "Vowed ends the visit: ${e.javaClass.simpleName}${(e as? WalletException)?.let { " (${it.kind}${it.code?.let { c -> ", code $c" } ?: ""})" } ?: ""}"
            if (e is WalletException) e.requestSent = requestSent
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
        val r = withTimeoutOrNull(answerTimeoutMs) { block() }
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

        /** after this long without an answer to the transaction request the screen shows "Waiting for <wallet>" with Try again */
        const val STALL_AFTER_MS = 12_000L

        /** an authorize that took longer than this showed a screen (Connect), so the wallet needs a moment before the next request */
        const val SCREEN_SHOWN_AFTER_MS = 2_500L

        /** how long the transaction request is held back after a Connect screen, so that the wallet's approval sheet can be drawn */
        const val SETTLE_MS = 3_000L

        /** `get_capabilities` is only information: it never holds the visit up for longer than this */
        const val CAPS_TIMEOUT_MS = 8_000L
    }
}
