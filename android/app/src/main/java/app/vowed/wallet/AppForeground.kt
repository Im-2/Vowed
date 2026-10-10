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
