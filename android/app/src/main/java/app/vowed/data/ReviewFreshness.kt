package app.vowed.data

/**
 * A transaction built for the review screen carries a recent blockhash, and the network stops accepting it after roughly a minute (some wallets refuse it
 * even sooner, or show nothing). A review that has been open longer than this is rebuilt, and the person checks it and taps Sign again.
 */
object ReviewFreshness {
    const val MAX_AGE_MS = 40_000L

    fun isStale(builtAtMs: Long, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean = nowMs - builtAtMs > maxAgeMs
}
