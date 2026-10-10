package app.vowed.proof.pose

import kotlin.math.max
import kotlin.math.min

/** Is the whole body (or the part an exercise needs) in the camera picture, clearly enough to count? */
enum class FramingState { READY, ADJUST, NO_BODY }

class FramingResult(val state: FramingState, val hint: String?, val missing: List<JointId>) {
    val ready: Boolean get() = state == FramingState.READY
}

/**
 * The framing check behind the guide on the camera screen. Plain numbers in, one short hint out, so it is tested without a camera.
 * Joints are in the coordinates of the picture the person sees (0..1, mirrored for the front camera): a joint counts as visible when the model is
 * confident it is in the frame AND it lies inside the picture with a small margin.
 */
object FramingCheck {
    const val MIN_LIKELIHOOD = 0.5f

    /** how close to the picture edge a joint may be before it counts as cut off */
    const val MARGIN = 0.03f

    /** the model is unsure about many joints that are there: the light is probably bad */
    const val LOW_CONFIDENCE = 0.62f

    /** after this long without any body at all, suggest the light instead of the distance */
    const val NO_BODY_LIGHT_HINT_MS = 4_000L

    const val HINT_STEP_BACK = "Step back so your whole body is in view"
    const val HINT_LEFT = "Move to the left"
    const val HINT_RIGHT = "Move to the right"
    const val HINT_RAISE = "Raise your phone"
    const val HINT_LOWER = "Lower your phone"
    const val HINT_LIGHT = "Better lighting needed"
    const val HINT_UPPER_BODY = "Get your head, arms and hips in view"

    /** the groups of joints that must each have at least one visible joint */
    fun required(e: Exercise): List<List<JointId>> = when (e) {
        Exercise.SQUAT -> listOf(
            listOf(JointId.L_SHOULDER, JointId.R_SHOULDER), listOf(JointId.L_HIP, JointId.R_HIP),
            listOf(JointId.L_KNEE, JointId.R_KNEE), listOf(JointId.L_ANKLE, JointId.R_ANKLE),
        )
        Exercise.PUSHUP -> listOf(
            listOf(JointId.L_SHOULDER, JointId.R_SHOULDER), listOf(JointId.L_ELBOW, JointId.R_ELBOW),
            listOf(JointId.L_WRIST, JointId.R_WRIST), listOf(JointId.L_HIP, JointId.R_HIP),
        )
    }

    fun visible(j: Joint?): Boolean = j != null && j.likelihood >= MIN_LIKELIHOOD && j.x in MARGIN..(1f - MARGIN) && j.y in MARGIN..(1f - MARGIN)

    /** [frame] is null when the model found no person at all; [noBodyForMs] is how long that has lasted. */
    fun check(frame: PoseFrame?, exercise: Exercise, noBodyForMs: Long = 0): FramingResult {
        val joints = frame?.joints?.filterValues { it.likelihood > 0.05f }.orEmpty()
        if (joints.isEmpty()) {
            return FramingResult(FramingState.NO_BODY, if (noBodyForMs >= NO_BODY_LIGHT_HINT_MS) HINT_LIGHT else HINT_STEP_BACK, emptyList())
        }
        val groups = required(exercise)
        val missingGroups = groups.filter { g -> g.none { visible(frame!!.joints[it]) } }
        if (missingGroups.isEmpty()) return FramingResult(FramingState.READY, null, emptyList())
        val missing = missingGroups.flatten().filter { !visible(frame!!.joints[it]) }

        // where the body is in the picture, from every joint the model reports (even the unsure ones)
        var minX = 1f; var maxX = 0f; var minY = 1f; var maxY = 0f
        for (j in joints.values) { minX = min(minX, j.x); maxX = max(maxX, j.x); minY = min(minY, j.y); maxY = max(maxY, j.y) }
        val confident = joints.values.filter { it.likelihood >= MIN_LIKELIHOOD }
        val avg = if (joints.isEmpty()) 0f else joints.values.map { it.likelihood }.average().toFloat()
        val cutLeft = minX < MARGIN
        val cutRight = maxX > 1f - MARGIN
        val shoulderY = listOf(JointId.L_SHOULDER, JointId.R_SHOULDER).mapNotNull { frame!!.joints[it]?.takeIf { j -> j.likelihood > 0.05f }?.y }.average().toFloat()
        val hint = when {
            // the person is there but the model is unsure of most of what it sees: light, not distance
            confident.size < groups.size && avg < LOW_CONFIDENCE -> HINT_LIGHT
            cutLeft && !cutRight -> HINT_RIGHT   // the body runs off the left edge of the picture: move towards the right
            cutRight && !cutLeft -> HINT_LEFT
            minY < MARGIN -> HINT_LOWER          // the head or shoulders are cut off at the top
            maxY > 1f - MARGIN && !shoulderY.isNaN() && shoulderY > 0.4f -> HINT_RAISE // the body sits low in the picture and the feet fall off the bottom
            exercise == Exercise.PUSHUP -> HINT_UPPER_BODY
            else -> HINT_STEP_BACK
        }
        return FramingResult(FramingState.ADJUST, hint, missing)
    }
}

/**
 * Remembers how the framing has been over the last moments, so the screen does not flicker: "ready" must hold for [stableMillis] before Start is
 * offered, and "no body at all" is timed for the lighting hint.
 */
class FramingTracker(private val stableMillis: Long = 700) {
    var last: FramingResult = FramingResult(FramingState.NO_BODY, FramingCheck.HINT_STEP_BACK, emptyList())
        private set
    private var readySince = -1L
    private var noBodySince = -1L

    fun noBodyForMs(now: Long): Long = if (noBodySince < 0) 0 else now - noBodySince

    fun update(frame: PoseFrame?, exercise: Exercise, now: Long): FramingResult {
        if (frame == null) { if (noBodySince < 0) noBodySince = now } else noBodySince = -1
        val r = FramingCheck.check(frame, exercise, noBodyForMs(now))
        if (r.ready) { if (readySince < 0) readySince = now } else readySince = -1
        last = r
        return r
    }

    /** ready now and for at least [stableMillis] */
    fun stableReady(now: Long): Boolean = last.ready && readySince >= 0 && now - readySince >= stableMillis
}

/** Where a point of the analysed picture lands on the screen when the preview fills the view and is cropped in the middle (FILL_CENTER). */
object CameraGeometry {
    /**
     * [u] and [v] are 0..1 across and down the upright picture whose width/height is [imageAspect]; the view is [viewW] x [viewH] pixels.
     * FILL_CENTER scales the picture by max(viewW/imageW, viewH/imageH) and cuts the middle window (CameraX documentation, PreviewView scale types).
     */
    fun toView(u: Float, v: Float, imageAspect: Float, viewW: Float, viewH: Float): Pair<Float, Float> {
        // work in units where the picture is imageAspect wide and 1 tall
        val scale = max(viewW / imageAspect, viewH / 1f)
        val shownW = imageAspect * scale
        val shownH = scale
        val offX = (viewW - shownW) / 2f
        val offY = (viewH - shownH) / 2f
        return Pair(u * shownW + offX, v * shownH + offY)
    }
}
