package app.vowed.wallet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether one of Vowed's activities is on screen right now (resumed). Some wallets (Phantom, in the field tests) do not hand the screen back to
 * the app when they are done, so the app keeps running behind the wallet. Anything that needs the network, or that opens the wallet again, must
 * wait until Vowed is in front: a background app can have its network blocked, and Android refuses to start another app's screen from the background.
 */
object AppForeground {
    private val resumed = MutableStateFlow(false)

    val isResumed: Boolean get() = resumed.value

    fun set(value: Boolean) { resumed.value = value }

    /** Waits until Vowed is on screen; false when [timeoutMs] passes first. */
    suspend fun awaitResumed(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { resumed.first { it } } != null

    /**
     * Makes sure Vowed is on screen before the app goes on (network calls, a second wallet screen). Tries to bring the app forward, then asks the
     * person to switch back ([onReturnNeeded]) and waits up to [patienceMs]. Everything is written to the [trace].
     */
    suspend fun ensureOnScreen(trace: WalletTrace, bringToFront: () -> String, onReturnNeeded: () -> Unit, patienceMs: Long = 90_000): Boolean {
        if (isResumed) return true
        trace.add("waiting for Vowed to be on screen", "bring to front: ${bringToFront()}")
        if (awaitResumed(1_500)) { trace.add("Vowed is on screen"); return true }
        onReturnNeeded()
        val back = awaitResumed(patienceMs)
        trace.add(if (back) "Vowed is on screen again" else "gave up waiting for Vowed to come back (${patienceMs / 1000} s)")
        return back
    }
}

/** One automatic retry after a "session closed" error, in a fresh wallet session, and never a second one. */
object SessionRetry {
    suspend fun <T> run(trace: WalletTrace?, first: suspend () -> T, retry: suspend () -> T): T = try {
        first()
    } catch (e: WalletException) {
        if (e.kind != WalletErrors.Kind.SessionClosed) throw e
        trace?.add("retry", "the wallet closed the session; trying once in a fresh session")
        retry()
    }
}
