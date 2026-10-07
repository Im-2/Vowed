package app.vowed.proof.pose

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Counting exercise reps from body landmarks. Everything here is plain Kotlin on numbers, so it is unit-tested without a camera:
 * the camera layer converts ML Kit landmarks into [PoseFrame]s, and nothing from the camera image reaches this code or leaves the phone.
 */
enum class JointId { L_SHOULDER, R_SHOULDER, L_ELBOW, R_ELBOW, L_WRIST, R_WRIST, L_HIP, R_HIP, L_KNEE, R_KNEE, L_ANKLE, R_ANKLE }

/** A landmark in image coordinates (any consistent scale) and the model's confidence (0..1) that it is really in the frame. */
class Joint(val x: Float, val y: Float, val likelihood: Float)

class PoseFrame(val tsMillis: Long, val joints: Map<JointId, Joint>)

enum class Exercise(val key: String, val label: String, val downBelow: Float, val upAbove: Float) {
    SQUAT("squat", "squats", 105f, 160f),
    PUSHUP("pushup", "push-ups", 95f, 155f),
    ;

    /** The three joints whose angle drives the count, for the left and right side of the body. */
    fun chain(left: Boolean): Triple<JointId, JointId, JointId> = when (this) {
        SQUAT -> if (left) Triple(JointId.L_HIP, JointId.L_KNEE, JointId.L_ANKLE) else Triple(JointId.R_HIP, JointId.R_KNEE, JointId.R_ANKLE)
        PUSHUP -> if (left) Triple(JointId.L_SHOULDER, JointId.L_ELBOW, JointId.L_WRIST) else Triple(JointId.R_SHOULDER, JointId.R_ELBOW, JointId.R_WRIST)
    }

    /** Groups of joints; at least one joint of every group must be visible for the pose to be judged at all (a side-on view hides one leg). */
    val mustSee: List<List<JointId>>
        get() = when (this) {
            SQUAT -> listOf(listOf(JointId.L_HIP, JointId.R_HIP), listOf(JointId.L_ANKLE, JointId.R_ANKLE))
            PUSHUP -> listOf(listOf(JointId.L_SHOULDER, JointId.R_SHOULDER), listOf(JointId.L_WRIST, JointId.R_WRIST))
        }

    companion object {
        fun fromKey(k: String?): Exercise = entries.firstOrNull { it.key == k } ?: SQUAT
    }
}

/** Angle at [b] in degrees formed by the segments b->a and b->c (0..180). */
fun angleDegrees(a: Joint, b: Joint, c: Joint): Float {
    val v1x = a.x - b.x
    val v1y = a.y - b.y
    val v2x = c.x - b.x
    val v2y = c.y - b.y
    val n = hypot(v1x, v1y) * hypot(v2x, v2y)
    if (n < 1e-6f) return 180f
    val cos = ((v1x * v2x + v1y * v2y) / n).coerceIn(-1f, 1f)
    return Math.toDegrees(acos(cos).toDouble()).toFloat()
}

enum class RepPhase { WAITING_FOR_BODY, UP, DOWN }

class RepUpdate(val phase: RepPhase, val reps: Int, val angle: Float?, val repCounted: Boolean, val hint: String?)

class RepCounter(
    private val exercise: Exercise,
    private val minLikelihood: Float = 0.5f,
    /** a rep must take at least this long from leaving "up" to returning (a flicker of noise is not a rep) */
    private val minRepMillis: Long = 600,
    /** and the bottom position must be held at least this long */
    private val minBottomMillis: Long = 120,
    private val smoothing: Float = 0.5f,
) {
    var reps = 0
        private set
    var phase = RepPhase.WAITING_FOR_BODY
        private set
    private var smoothed: Float? = null
    private var leftSide: Boolean? = null
    private var leftUpAt = 0L
    private var bottomSince = -1L
    var deepest = 360f
        private set
    var shallowest = 0f
        private set
    var framesUsed = 0
        private set
    var framesSkipped = 0
        private set

    private fun visible(f: PoseFrame, groups: List<List<JointId>>) = groups.all { g -> g.any { (f.joints[it]?.likelihood ?: 0f) >= minLikelihood } }

    fun update(f: PoseFrame): RepUpdate {
        if (!visible(f, exercise.mustSee)) {
            framesSkipped++
            return RepUpdate(phase, reps, null, false, if (exercise == Exercise.SQUAT) "Step back so your whole body is in view" else "Get your upper body and hands in view")
        }
        // use the side of the body the camera sees best
        val l = exercise.chain(true)
        val r = exercise.chain(false)
        val lScore = listOf(l.first, l.second, l.third).minOf { f.joints[it]?.likelihood ?: 0f }
        val rScore = listOf(r.first, r.second, r.third).minOf { f.joints[it]?.likelihood ?: 0f }
        val useLeft = if (leftSide == null || abs(lScore - rScore) > 0.2f) lScore >= rScore else leftSide!!
        if (leftSide != null && leftSide != useLeft) smoothed = null // switching sides: do not blend angles from two different limbs
        leftSide = useLeft
        val c = exercise.chain(useLeft)
        val a = f.joints[c.first]!!
        val b = f.joints[c.second]!!
        val d = f.joints[c.third]!!
        if (min(a.likelihood, min(b.likelihood, d.likelihood)) < minLikelihood) {
            framesSkipped++
            return RepUpdate(phase, reps, null, false, "Keep the whole side of your body in view")
        }
        framesUsed++
        val raw = angleDegrees(a, b, d)
        val prev = smoothed
        val ang = if (prev == null) raw else prev + smoothing * (raw - prev)
        smoothed = ang
        deepest = min(deepest, ang)
        shallowest = max(shallowest, ang)

        var counted = false
        var hint: String? = null
        when (phase) {
            RepPhase.WAITING_FOR_BODY -> {
                if (ang >= exercise.upAbove) phase = RepPhase.UP else hint = "Stand tall to start"
            }
            RepPhase.UP -> {
                if (ang <= exercise.downBelow) {
                    phase = RepPhase.DOWN
                    bottomSince = f.tsMillis
                } else if (ang < exercise.upAbove) {
                    leftUpAt = if (leftUpAt == 0L) f.tsMillis else leftUpAt
                    hint = "Go lower"
                } else {
                    leftUpAt = 0L
                }
            }
            RepPhase.DOWN -> {
                if (ang >= exercise.upAbove) {
                    val heldBottom = f.tsMillis - bottomSince
                    val total = f.tsMillis - (if (leftUpAt == 0L) bottomSince else leftUpAt)
                    phase = RepPhase.UP
                    leftUpAt = 0L
                    if (heldBottom >= minBottomMillis && total >= minRepMillis) {
                        reps++
                        counted = true
                    } else hint = "Slow down: move through the full range"
                } else {
                    if (leftUpAt == 0L) leftUpAt = bottomSince
                    hint = "Now stand back up"
                }
            }
        }
        return RepUpdate(phase, reps, ang, counted, hint)
    }
}

/**
 * Anti-spoof prompt: at a random moment the screen asks for a random hand to be raised, which a pre-recorded video of someone
 * doing squats cannot answer. Passing needs the right wrist clearly above the shoulder within [windowMillis].
 */
class Liveness(
    val askRightHandOnScreen: Boolean,
    /** milliseconds after the session start when the prompt appears */
    val promptAtMillis: Long,
    private val windowMillis: Long = 6_000,
    private val minLikelihood: Float = 0.5f,
) {
    enum class State { WAITING, PROMPTED, PASSED, FAILED }

    var state = State.WAITING
        private set

    /** [now] is milliseconds since the session start. */
    fun update(now: Long, f: PoseFrame): State {
        when (state) {
            State.WAITING -> if (now >= promptAtMillis) state = State.PROMPTED
            State.PROMPTED -> {
                if (now > promptAtMillis + windowMillis) state = State.FAILED
                else if (handRaised(f)) state = State.PASSED
            }
            else -> Unit
        }
        return state
    }

    val prompt: String? get() = if (state == State.PROMPTED) "Raise the hand on the ${if (askRightHandOnScreen) "RIGHT" else "LEFT"} side of the screen" else null

    private fun handRaised(f: PoseFrame): Boolean {
        val ls = f.joints[JointId.L_SHOULDER]
        val rs = f.joints[JointId.R_SHOULDER]
        if (ls == null || rs == null || ls.likelihood < minLikelihood || rs.likelihood < minLikelihood) return false
        val midX = (ls.x + rs.x) / 2
        val shoulderY = (ls.y + rs.y) / 2
        val hip = f.joints[JointId.L_HIP]?.takeIf { it.likelihood >= minLikelihood } ?: f.joints[JointId.R_HIP]?.takeIf { it.likelihood >= minLikelihood }
        val torso = if (hip != null) abs(hip.y - shoulderY) else abs(ls.x - rs.x) * 1.5f
        if (torso < 1e-3f) return false
        // either wrist counts, as long as it is on the asked side of the screen and clearly above the shoulders
        return listOf(JointId.L_WRIST, JointId.R_WRIST).any { id ->
            val w = f.joints[id] ?: return@any false
            val onSide = if (askRightHandOnScreen) w.x > midX else w.x < midX
            w.likelihood >= minLikelihood && onSide && (shoulderY - w.y) >= 0.25f * torso
        }
    }
}

/** What a finished session reports. Only these numbers and a short text summary go anywhere; no frames, no landmarks. */
class PoseSessionResult(val reps: Int, val livenessPassed: Boolean, val startedAtSec: Long, val endedAtSec: Long, val summary: String)

