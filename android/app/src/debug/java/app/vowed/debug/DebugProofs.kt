package app.vowed.debug

import app.vowed.data.ProofTarget
import app.vowed.proof.Collected
import app.vowed.proof.ProofKind
import app.vowed.proof.ProofMath
import kotlin.math.ceil

/**
 * DEBUG BUILDS ONLY (this file lives in src/debug; release builds compile the refusing stub in src/release).
 * Produces plausible test data so every proof flow can be run on an emulator. With [good] false it produces data that
 * misses the target, to show the server rejecting it. The data is labelled as injected in the evidence summary.
 */
object DebugProofs {
    const val ENABLED = true

    fun inject(kind: ProofKind, target: ProofTarget, now: Long, good: Boolean): Collected {
        val need = ProofMath.toSeconds(target.value, target.unit)?.toLong() ?: 0L
        val ev = "injected test data (debug build) for ${kind.name}"
        return when (kind) {
            ProofKind.SELF_ATTEST -> Collected(mapOf("done" to good), now - 1, now, ev)
            ProofKind.STEPS -> Collected(mapOf("steps" to if (good) ceil(target.value).toLong() + 25 else 3L), now - 600, now, ev)
            ProofKind.FOCUS_TIMER -> {
                val focused = if (good) need else need / 4
                Collected(mapOf("focusedSeconds" to focused), now - focused - 1, now, ev)
            }
            ProofKind.GEOFENCE -> {
                val dwell = if (good) need else need / 4
                Collected(mapOf("inside" to true, "dwellSeconds" to dwell), now - dwell - 1, now, ev)
            }
            ProofKind.USAGE_LIMIT -> Collected(
                mapOf("usageSeconds" to if (good) 0L else need + 600, "packagesChecked" to listOf("debug.injected.app")), now - 3600, now, ev,
            )
            ProofKind.NO_USE_WINDOW -> Collected(
                mapOf("usageSecondsInWindow" to if (good) 0L else need + 600, "packagesChecked" to listOf("debug.injected.app")), now - 3600, now, ev,
            )
            ProofKind.CAMERA_POSE -> {
                val reps = if (good) ceil(target.value).toLong() else 1L
                Collected(mapOf("reps" to reps, "livenessPassed" to true), now - maxOf(10L, reps * 2), now, ev)
            }
        }
    }
}
