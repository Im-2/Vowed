package app.vowed.debug

import app.vowed.data.ProofTarget
import app.vowed.proof.Collected
import app.vowed.proof.ProofKind

/** Release builds: test data injection does not exist. This stub is all that is compiled in, and it refuses to do anything. */
object DebugProofs {
    const val ENABLED = false

    fun inject(kind: ProofKind, target: ProofTarget, now: Long, good: Boolean): Collected =
        throw IllegalStateException("test-data injection is not available in release builds")
}
