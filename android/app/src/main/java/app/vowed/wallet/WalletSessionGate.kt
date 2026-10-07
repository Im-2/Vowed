package app.vowed.wallet

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wallet sessions (Mobile Wallet Adapter "transact" calls) must not overlap or follow each other too closely: the wallet's screen from the
 * previous request may still be closing when the next one starts, and then Android reports "Local association was cancelled before
 * connected". This gate
 *  - runs one session at a time, in the order asked,
 *  - leaves [minGapMs] after the previous session ended before opening the next,
 *  - retries ONCE after [retryDelayMs] when the failure is the connection step itself (nothing was shown to the user or signed yet),
 *    and never retries a decision by the user or a rejected transaction.
 */
class WalletSessionGate(
    private val minGapMs: Long = 1_500,
    private val retryDelayMs: Long = 2_000,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Mutex()
    private var lastEnd = 0L

    suspend fun <R> run(associationFailed: (R) -> Boolean, attempt: suspend () -> R): R = lock.withLock {
        val wait = lastEnd + minGapMs - now()
        if (lastEnd > 0 && wait > 0) sleep(wait)
        var r = attempt()
        if (associationFailed(r)) {
            sleep(retryDelayMs)
            r = attempt()
        }
        lastEnd = now()
        r
    }

    companion object {
        /** True for failures of the connection step (the wallet closed or did not answer before connecting), false for everything else. */
        fun isAssociationFailure(message: String?): Boolean {
            val m = message?.lowercase() ?: return false
            return "association" in m && ("cancel" in m || "failed establishing" in m || "timed out" in m || "timeout" in m)
        }

        const val CLOSED_BEFORE_CONNECTING =
            "Your wallet app closed before it connected. Open the wallet, make sure it is unlocked, then tap the button again. Nothing was signed."
    }
}
