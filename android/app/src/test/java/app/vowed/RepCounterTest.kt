package app.vowed

import app.vowed.proof.pose.Exercise
import app.vowed.proof.pose.Joint
import app.vowed.proof.pose.JointId
import app.vowed.proof.pose.Liveness
import app.vowed.proof.pose.PoseFrame
import app.vowed.proof.pose.PoseSession
import app.vowed.proof.pose.RepCounter
import app.vowed.proof.pose.RepPhase
import app.vowed.proof.pose.angleDegrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The whole path from landmarks to a rep count, driven by a synthetic body (a stick figure moved by forward kinematics),
 * so the counting rules are checked without a camera. Real-person detection is a device test (docs/device-tests.md).
 */
class RepCounterTest {
    private val rnd = Random(42)

    /** Side view of a person: [kneeAngle] is the angle at the knee (180 = standing straight, about 80 = deep squat). */
    private fun squatFrame(ts: Long, kneeAngle: Double, like: Float = 0.9f, hideLeftAnkle: Boolean = false, noise: Double = 0.0): PoseFrame {
        val k = (kneeAngle + (if (noise > 0) rnd.nextGaussian() * noise else 0.0)) * PI / 180
        val kneeX = 0.5f
        val kneeY = 0.65f
        val ankleX = kneeX
        val ankleY = kneeY + 0.25f
        val hipX = kneeX + 0.25f * sin(k).toFloat()
        val hipY = kneeY + 0.25f * cos(k).toFloat()
        val shoulderX = hipX
        val shoulderY = hipY - 0.3f
        fun j(x: Float, y: Float, l: Float = like) = Joint(x, y, l)
        val m = mutableMapOf(
            JointId.L_SHOULDER to j(shoulderX, shoulderY), JointId.R_SHOULDER to j(shoulderX + 0.01f, shoulderY),
            JointId.L_HIP to j(hipX, hipY), JointId.R_HIP to j(hipX + 0.01f, hipY),
            JointId.L_KNEE to j(kneeX, kneeY), JointId.R_KNEE to j(kneeX + 0.01f, kneeY),
            JointId.L_ANKLE to j(ankleX, ankleY, if (hideLeftAnkle) 0.1f else like), JointId.R_ANKLE to j(ankleX + 0.01f, ankleY),
        )
        if (hideLeftAnkle) m[JointId.L_HIP] = j(hipX, hipY, 0.1f)
        return PoseFrame(ts, m)
    }

    /** One rep: stand, go down to [bottom], come up, over [millis]; sampled every 50 ms. */
    private fun repAngles(bottom: Double, millis: Int, holdBottomMillis: Int = 200): List<Double> {
        val out = ArrayList<Double>()
        val steps = millis / 50
        val half = steps / 2
        for (i in 0 until half) out += 175.0 - (175.0 - bottom) * (i + 1) / half
        repeat(holdBottomMillis / 50) { out += bottom }
        for (i in 0 until half) out += bottom + (175.0 - bottom) * (i + 1) / half
        return out
    }

    private fun run(counter: RepCounter, angles: List<Double>, startMs: Long = 0, mk: (Long, Double) -> PoseFrame = { t, a -> squatFrame(t, a) }): Long {
        var t = startMs
        for (a in angles) {
            counter.update(mk(t, a))
            t += 50
        }
        return t
    }

    @Test fun anglesAreRight() {
        val a = Joint(0f, 1f, 1f)
        val b = Joint(0f, 0f, 1f)
        assertEquals(90f, angleDegrees(a, b, Joint(1f, 0f, 1f)), 0.01f)
        assertEquals(180f, angleDegrees(a, b, Joint(0f, -1f, 1f)), 0.01f)
        assertEquals(0f, angleDegrees(a, b, Joint(0f, 2f, 1f)), 0.01f)
        assertEquals(180f, angleDegrees(a, b, b), 0.01f) // a degenerate limb never throws
    }

    @Test fun countsCleanSquats() {
        val c = RepCounter(Exercise.SQUAT)
        val angles = (1..5).flatMap { repAngles(85.0, 1500) }
        run(c, listOf(175.0, 175.0) + angles)
        assertEquals(5, c.reps)
        assertEquals(RepPhase.UP, c.phase)
    }

    @Test fun countsEachRepOnceWhenHeldAtTheBottom() {
        val c = RepCounter(Exercise.SQUAT)
        run(c, listOf(175.0) + repAngles(85.0, 1500, holdBottomMillis = 3000))
        assertEquals(1, c.reps)
    }

    @Test fun shallowDipsAreNotReps() {
        val c = RepCounter(Exercise.SQUAT)
        run(c, listOf(175.0) + (1..6).flatMap { repAngles(135.0, 1500) })
        assertEquals(0, c.reps)
    }

    @Test fun aFlickerOfNoiseIsNotARep() {
        val c = RepCounter(Exercise.SQUAT)
        // knee angle jumps 175 -> 80 -> 175 inside 150 ms: a tracking glitch, not a person
        run(c, listOf(175.0, 175.0, 80.0, 80.0, 175.0, 175.0, 175.0))
        assertEquals(0, c.reps)
    }

    @Test fun toleratesMeasurementNoise() {
        val c = RepCounter(Exercise.SQUAT)
        val angles = (1..8).flatMap { repAngles(85.0, 1600) }
        run(c, listOf(175.0, 175.0) + angles) { t, a -> squatFrame(t, a, noise = 4.0) }
        assertEquals(8, c.reps)
    }

    @Test fun ignoresFramesWhereTheBodyIsNotVisible() {
        val c = RepCounter(Exercise.SQUAT)
        val t0 = run(c, listOf(175.0, 175.0))
        // half a rep with the legs out of frame: nothing may be counted, and no hint pretends otherwise
        var t = t0
        var lastHint: String? = null
        for (a in repAngles(85.0, 1500)) {
            val u = c.update(squatFrame(t, a, like = 0.1f))
            lastHint = u.hint
            t += 50
        }
        assertEquals(0, c.reps)
        assertTrue(lastHint!!.contains("in view"))
        assertTrue(c.framesSkipped > 20)
        // back in view: counting works again
        run(c, repAngles(85.0, 1500), t)
        assertEquals(1, c.reps)
    }

    @Test fun usesTheSideTheCameraSees() {
        val c = RepCounter(Exercise.SQUAT)
        // the left leg is hidden the whole time; the right one drives the count
        run(c, listOf(175.0, 175.0) + (1..3).flatMap { repAngles(85.0, 1500) }) { t, a -> squatFrame(t, a, hideLeftAnkle = true) }
        assertEquals(3, c.reps)
    }

    @Test fun anAbortedRepThenAFullOneCountsOnlyTheFullOne() {
        val c = RepCounter(Exercise.SQUAT)
        run(c, listOf(175.0) + repAngles(130.0, 1500) + repAngles(85.0, 1500))
        assertEquals(1, c.reps)
    }

    private fun pushupFrame(ts: Long, elbowAngle: Double): PoseFrame {
        val e = elbowAngle * PI / 180
        val elbowX = 0.5f
        val elbowY = 0.5f
        val wristX = elbowX
        val wristY = elbowY + 0.2f
        val shoulderX = elbowX + 0.2f * sin(e).toFloat()
        val shoulderY = elbowY + 0.2f * cos(e).toFloat()
        fun j(x: Float, y: Float) = Joint(x, y, 0.9f)
        return PoseFrame(
            ts,
            mapOf(
                JointId.L_SHOULDER to j(shoulderX, shoulderY), JointId.R_SHOULDER to j(shoulderX, shoulderY + 0.01f),
                JointId.L_ELBOW to j(elbowX, elbowY), JointId.R_ELBOW to j(elbowX, elbowY + 0.01f),
                JointId.L_WRIST to j(wristX, wristY), JointId.R_WRIST to j(wristX, wristY + 0.01f),
            ),
        )
    }

    @Test fun countsPushUps() {
        val c = RepCounter(Exercise.PUSHUP)
        val angles = (1..6).flatMap { repAngles(70.0, 1400) }
        run(c, listOf(170.0, 170.0) + angles) { t, a -> pushupFrame(t, a) }
        assertEquals(6, c.reps)
    }

    // ------------------------------------------------------------------ liveness

    private fun raised(rightOnScreen: Boolean, ts: Long, up: Boolean): PoseFrame {
        val cx = 0.5f
        fun j(x: Float, y: Float) = Joint(x, y, 0.9f)
        val wristY = if (up) 0.15f else 0.55f
        val wristX = if (rightOnScreen) cx + 0.2f else cx - 0.2f
        return PoseFrame(
            ts,
            mapOf(
                JointId.L_SHOULDER to j(cx - 0.1f, 0.35f), JointId.R_SHOULDER to j(cx + 0.1f, 0.35f),
                JointId.L_HIP to j(cx - 0.05f, 0.7f), JointId.R_HIP to j(cx + 0.05f, 0.7f),
                JointId.L_WRIST to j(wristX, wristY), JointId.R_WRIST to j(cx + (if (rightOnScreen) -0.1f else 0.1f), 0.6f),
            ),
        )
    }

    @Test fun livenessPassesWhenTheAskedHandGoesUp() {
        val l = Liveness(askRightHandOnScreen = true, promptAtMillis = 3_000)
        assertEquals(Liveness.State.WAITING, l.update(1_000, raised(true, 1_000, true))) // raising early does not count
        assertNull(l.prompt)
        assertEquals(Liveness.State.PROMPTED, l.update(3_000, raised(true, 3_000, false)))
        assertNotNull(l.prompt)
        assertTrue(l.prompt!!.contains("RIGHT"))
        assertEquals(Liveness.State.PROMPTED, l.update(3_500, raised(true, 3_500, false)))
        assertEquals(Liveness.State.PASSED, l.update(4_000, raised(true, 4_000, true)))
    }

    @Test fun livenessFailsOnTheWrongSideOrWhenTooSlow() {
        val wrong = Liveness(askRightHandOnScreen = true, promptAtMillis = 1_000)
        wrong.update(1_000, raised(false, 1_000, false))
        assertEquals(Liveness.State.PROMPTED, wrong.update(2_000, raised(false, 2_000, true))) // left hand up, right asked
        assertEquals(Liveness.State.FAILED, wrong.update(8_000, raised(false, 8_000, true)))

        val slow = Liveness(askRightHandOnScreen = false, promptAtMillis = 1_000)
        slow.update(1_000, raised(false, 1_000, false))
        assertEquals(Liveness.State.FAILED, slow.update(7_500, raised(false, 7_500, false)))
        assertEquals(Liveness.State.FAILED, slow.update(7_600, raised(false, 7_600, true))) // too late stays failed
    }

    @Test fun livenessNeedsAClearlyRaisedHand() {
        val l = Liveness(askRightHandOnScreen = true, promptAtMillis = 0)
        l.update(0, raised(true, 0, false))
        // a wrist just at shoulder height is not "raised"
        val f = raised(true, 100, false)
        val shoulderLevel = PoseFrame(100, f.joints + (JointId.L_WRIST to Joint(0.7f, 0.34f, 0.9f)))
        assertEquals(Liveness.State.PROMPTED, l.update(100, shoulderLevel))
        assertEquals(Liveness.State.PASSED, l.update(200, raised(true, 200, true)))
    }
}

/** A whole check-in driven by a synthetic person: countdown, counting, the hand-raise prompt, and the final result. */
class PoseSessionTest {
    private fun frameAt(ts: Long, knee: Double, handUp: Boolean, rightOnScreen: Boolean): PoseFrame {
        val k = knee * PI / 180
        val kneeX = 0.5f
        val kneeY = 0.65f
        val hipX = kneeX + 0.25f * sin(k).toFloat()
        val hipY = kneeY + 0.25f * cos(k).toFloat()
        val shoulderY = hipY - 0.3f
        fun j(x: Float, y: Float) = Joint(x, y, 0.9f)
        val up = shoulderY - 0.15f
        val wristDown = hipY
        fun wrist(rightSide: Boolean): Joint {
            val x = hipX + if (rightSide) 0.2f else -0.2f
            val raisedThis = handUp && rightSide == rightOnScreen
            return j(x, if (raisedThis) up else wristDown)
        }
        return PoseFrame(
            ts,
            mapOf(
                JointId.L_SHOULDER to j(hipX - 0.1f, shoulderY), JointId.R_SHOULDER to j(hipX + 0.1f, shoulderY),
                JointId.L_HIP to j(hipX, hipY), JointId.R_HIP to j(hipX + 0.01f, hipY),
                JointId.L_KNEE to j(kneeX, kneeY), JointId.R_KNEE to j(kneeX + 0.01f, kneeY),
                JointId.L_ANKLE to j(kneeX, kneeY + 0.25f), JointId.R_ANKLE to j(kneeX + 0.01f, kneeY + 0.25f),
                JointId.L_WRIST to wrist(false), JointId.R_WRIST to wrist(true),
            ),
        )
    }

    private fun repAngles(bottom: Double, millis: Int): List<Double> {
        val out = ArrayList<Double>()
        val half = millis / 100
        for (i in 0 until half) out += 175.0 - (175.0 - bottom) * (i + 1) / half
        repeat(3) { out += bottom }
        for (i in 0 until half) out += bottom + (175.0 - bottom) * (i + 1) / half
        return out
    }

    /** Plays [seconds] of squats (a rep every 1.6 s). [raiseHand] makes the person obey the prompt. Returns the session. */
    private fun play(target: Int, seconds: Int, raiseHand: Boolean, seed: Long = 7): PoseSession {
        val s = PoseSession(Exercise.SQUAT, target, Random(seed), countdownMillis = 3_000, nowSeconds = { 1_700_000_000L })
        s.start(0)
        val angles = generateSequence { repAngles(85.0, 1_400) }.flatten().iterator()
        var t = 0L
        var handUntil = -1L
        var rightOnScreen = true
        while (t < seconds * 1000L && s.stage != PoseSession.Stage.DONE) {
            val prompt = s.livenessPrompt
            if (raiseHand && prompt != null && handUntil < t) {
                rightOnScreen = prompt.contains("RIGHT")
                handUntil = t + 1_200
            }
            val up = t <= handUntil && handUntil > 0
            s.onFrame(frameAt(t, angles.next(), up, rightOnScreen))
            t += 100
        }
        return s
    }

    @Test fun aWholeCheckInSucceeds() {
        val s = play(target = 3, seconds = 40, raiseHand = true)
        assertEquals(PoseSession.Stage.DONE, s.stage)
        assertTrue(s.succeeded)
        val r = s.result()
        assertTrue(r.reps >= 3)
        assertTrue(r.livenessPassed)
        assertTrue(r.summary.contains("reps="))
    }

    @Test fun nothingCountsDuringTheCountdown() {
        val s = PoseSession(Exercise.SQUAT, 3, Random(1), countdownMillis = 3_000)
        s.start(0)
        assertEquals(3, s.countdownLeftSeconds(0))
        assertEquals(1, s.countdownLeftSeconds(2_500))
        var t = 0L
        for (a in repAngles(85.0, 1_400) + repAngles(85.0, 1_400)) {
            if (t >= 2_900) break
            s.onFrame(frameAt(t, a, false, true))
            t += 100
        }
        assertEquals(PoseSession.Stage.COUNTDOWN, s.stage)
        assertEquals(0, s.reps)
    }

    @Test fun repsWithoutPassingTheHandPromptDoNotSucceed() {
        val s = play(target = 3, seconds = 60, raiseHand = false)
        assertEquals(PoseSession.Stage.DONE, s.stage) // ends after the second missed prompt
        assertFalse(s.succeeded)
        assertFalse(s.result().livenessPassed)
        assertNotNull(s.message)
    }

    @Test fun keepsGoingUntilTheHandPromptIsAnswered() {
        // the goal is reached quickly, but the prompt comes later and the person answers it: success only then
        val s = play(target = 1, seconds = 40, raiseHand = true, seed = 3)
        assertTrue(s.succeeded)
        assertTrue(s.result().endedAtSec >= s.result().startedAtSec)
    }

    @Test fun anAnswerWithTheWrongHandDoesNotPass() {
        val s = PoseSession(Exercise.SQUAT, 1, Random(11), countdownMillis = 0)
        s.start(0)
        var t = 0L
        val angles = generateSequence { repAngles(85.0, 1_400) }.flatten().iterator()
        var wrongSide = true
        while (t < 30_000 && s.stage != PoseSession.Stage.DONE) {
            val p = s.livenessPrompt
            val asked = p?.contains("RIGHT")
            // always raise the hand on the side that was NOT asked
            s.onFrame(frameAt(t, angles.next(), p != null, if (asked == null) true else !asked))
            t += 100
            wrongSide = wrongSide && true
        }
        assertFalse(s.succeeded)
        assertFalse(s.livenessPassed)
        assertTrue(wrongSide)
    }

    private fun assertFalse(b: Boolean) = org.junit.Assert.assertFalse(b)

    @Test fun theCountdownEndsEvenWhenNobodyIsInView() {
        val s = PoseSession(Exercise.SQUAT, 3, Random(1), countdownMillis = 3_000)
        s.start(10_000)
        s.tick(11_000)
        assertEquals(PoseSession.Stage.COUNTDOWN, s.stage)
        assertEquals(2, s.countdownLeftSeconds(11_000))
        s.tick(13_000)
        assertEquals(PoseSession.Stage.COUNTING, s.stage) // no frame was needed
        assertEquals(0, s.reps)
    }
}
