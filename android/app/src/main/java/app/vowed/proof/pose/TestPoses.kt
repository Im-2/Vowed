package app.vowed.proof.pose

import kotlin.math.cos
import kotlin.math.sin

/**
 * Made-up skeletons, for the unit tests and for the debug "inject test pose" buttons on the camera screen (so the overlay, the framing hints and
 * the counter can be checked on an emulator that has no person to film). Nothing here comes from a camera.
 */
object TestPoses {
    private fun j(x: Float, y: Float, l: Float = 0.95f) = Joint(x, y, l)

    private fun rad(deg: Float) = Math.toRadians(deg.toDouble())

    /**
     * A person squatting, seen from the front, whole body in view. [kneeAngle] is the angle at the knee: about 170 standing, about 80 deep.
     * [raisedRight] raises the wrist on the right side of the screen above the shoulder (for the hand-raise check).
     */
    fun squat(kneeAngle: Float, ts: Long, raisedRight: Boolean = false, raisedLeft: Boolean = false): PoseFrame {
        val flex = 180f - kneeAngle
        val ankleY = 0.86f
        val kneeY = ankleY - 0.2f
        val dx = (0.2f * sin(rad(flex))).toFloat()
        val dy = (0.2f * cos(rad(flex))).toFloat()
        val hipY = kneeY - dy
        val shoulderY = hipY - 0.22f
        val m = HashMap<JointId, Joint>()
        for ((side, off) in listOf(true to -0.06f, false to 0.06f)) {
            val cx = 0.5f + off
            m[if (side) JointId.L_ANKLE else JointId.R_ANKLE] = j(cx, ankleY)
            m[if (side) JointId.L_KNEE else JointId.R_KNEE] = j(cx, kneeY)
            m[if (side) JointId.L_HIP else JointId.R_HIP] = j(cx + dx, hipY)
            m[if (side) JointId.L_SHOULDER else JointId.R_SHOULDER] = j(cx + dx, shoulderY)
            m[if (side) JointId.L_ELBOW else JointId.R_ELBOW] = j(cx + dx + off, shoulderY + 0.12f)
            val raised = if (side) raisedLeft else raisedRight
            m[if (side) JointId.L_WRIST else JointId.R_WRIST] = if (raised) j(cx + dx + off * 2, shoulderY - 0.18f) else j(cx + dx + off, shoulderY + 0.24f)
        }
        return PoseFrame(ts, m)
    }

    /** A push-up seen from the side, upper body and hips in view. [elbowAngle]: about 165 at the top, about 80 at the bottom. */
    fun pushup(elbowAngle: Float, ts: Long, raisedRight: Boolean = false, raisedLeft: Boolean = false): PoseFrame {
        val flex = 180f - elbowAngle
        val wristY = 0.82f
        val elbowY = wristY - 0.14f
        val dx = (0.14f * sin(rad(flex))).toFloat()
        val dy = (0.14f * cos(rad(flex))).toFloat()
        val shoulderY = elbowY - dy
        val hipY = shoulderY + 0.06f
        val m = HashMap<JointId, Joint>()
        for ((side, off) in listOf(true to -0.05f, false to 0.05f)) {
            val cx = 0.2f + off
            m[if (side) JointId.L_WRIST else JointId.R_WRIST] = j(cx, wristY)
            m[if (side) JointId.L_ELBOW else JointId.R_ELBOW] = j(cx, elbowY)
            m[if (side) JointId.L_SHOULDER else JointId.R_SHOULDER] = j(cx + dx, shoulderY)
            m[if (side) JointId.L_HIP else JointId.R_HIP] = j(cx + dx + 0.3f, hipY)
            // the legs trail behind in a straight line (drawn on the body map; the count and the framing do not need them)
            m[if (side) JointId.L_KNEE else JointId.R_KNEE] = j(cx + dx + 0.46f, hipY + 0.03f)
            m[if (side) JointId.L_ANKLE else JointId.R_ANKLE] = j(cx + dx + 0.62f, hipY + 0.06f)
            val raised = if (side) raisedLeft else raisedRight
            if (raised) m[if (side) JointId.L_WRIST else JointId.R_WRIST] = j(cx + dx + off * 3, shoulderY - 0.25f)
        }
        return PoseFrame(ts, m)
    }

    /** The same frame with some joints reported as not in the picture (likelihood 0), like a body cut off by the edge. */
    fun without(f: PoseFrame, vararg ids: JointId): PoseFrame = PoseFrame(f.tsMillis, f.joints.mapValues { (k, v) -> if (k in ids) Joint(v.x, v.y, 0f) else v })

    /** The same frame moved sideways or down, so parts of the body run off the picture. */
    fun shifted(f: PoseFrame, dx: Float = 0f, dy: Float = 0f): PoseFrame = PoseFrame(f.tsMillis, f.joints.mapValues { (_, v) -> Joint(v.x + dx, v.y + dy, v.likelihood) })

    /** The same frame with the model unsure of every joint (a dark room). */
    fun dim(f: PoseFrame, likelihood: Float = 0.3f): PoseFrame = PoseFrame(f.tsMillis, f.joints.mapValues { (_, v) -> Joint(v.x, v.y, likelihood) })

    fun standing(exercise: Exercise, ts: Long = 0): PoseFrame = if (exercise == Exercise.SQUAT) squat(172f, ts) else pushup(168f, ts)

    /** One full rep as a list of frames [frameMs] apart, starting at [startTs]: up, down (held), up again. */
    fun repFrames(exercise: Exercise, startTs: Long, repMillis: Long = 1_600, frameMs: Long = 50, raisedRight: Boolean = false): List<PoseFrame> {
        val top = if (exercise == Exercise.SQUAT) 172f else 168f
        val bottom = if (exercise == Exercise.SQUAT) 80f else 78f
        val n = (repMillis / frameMs).toInt()
        return (0..n).map { i ->
            val t = i.toFloat() / n
            // 0..0.35 down, 0.35..0.6 hold at the bottom, 0.6..1 back up
            val depth = when {
                t < 0.35f -> t / 0.35f
                t < 0.6f -> 1f
                else -> 1f - (t - 0.6f) / 0.4f
            }
            val angle = top + (bottom - top) * depth
            if (exercise == Exercise.SQUAT) squat(angle, startTs + i * frameMs, raisedRight = raisedRight) else pushup(angle, startTs + i * frameMs, raisedRight = raisedRight)
        }
    }
}
