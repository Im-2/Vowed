package app.vowed

import app.vowed.proof.pose.CameraGeometry
import app.vowed.proof.pose.Exercise
import app.vowed.proof.pose.FramingCheck
import app.vowed.proof.pose.FramingState
import app.vowed.proof.pose.FramingTracker
import app.vowed.proof.pose.JointId
import app.vowed.proof.pose.PoseFrame
import app.vowed.proof.pose.PoseSession
import app.vowed.proof.pose.TestPoses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** The framing guide, the landmark visibility rules, the overlay geometry and the rep-counting state machine, all without a camera. */
class PoseCameraTest {
    // ---------------------------------------------------------------- landmark visibility and the framing hints

    private val squat = Exercise.SQUAT

    @Test fun aWholeBodyInViewIsReady() {
        val r = FramingCheck.check(TestPoses.standing(squat, 0), squat)
        assertEquals(FramingState.READY, r.state)
        assertNull(r.hint)
        assertTrue(r.missing.isEmpty())
        assertEquals(FramingState.READY, FramingCheck.check(TestPoses.standing(Exercise.PUSHUP, 0), Exercise.PUSHUP).state)
    }

    @Test fun aJointIsVisibleOnlyWhenConfidentAndInsideThePictureWithAMargin() {
        assertTrue(FramingCheck.visible(app.vowed.proof.pose.Joint(0.5f, 0.5f, 0.9f)))
        assertFalse(FramingCheck.visible(app.vowed.proof.pose.Joint(0.5f, 0.5f, 0.49f)))
        assertFalse(FramingCheck.visible(app.vowed.proof.pose.Joint(0.01f, 0.5f, 0.9f)))
        assertFalse(FramingCheck.visible(app.vowed.proof.pose.Joint(0.5f, 0.99f, 0.9f)))
        assertFalse(FramingCheck.visible(null))
    }

    @Test fun feetOutOfViewAsksToStepBack() {
        val f = TestPoses.without(TestPoses.standing(squat, 0), JointId.L_ANKLE, JointId.R_ANKLE, JointId.L_KNEE, JointId.R_KNEE)
        val r = FramingCheck.check(f, squat)
        assertEquals(FramingState.ADJUST, r.state)
        assertEquals("Step back so your whole body is in view", r.hint)
        assertTrue(JointId.L_ANKLE in r.missing && JointId.R_KNEE in r.missing)
    }

    @Test fun aBodyAtTheEdgeAsksToMoveTheOtherWay() {
        val left = FramingCheck.check(TestPoses.shifted(TestPoses.standing(squat, 0), dx = -0.55f), squat)
        assertEquals("Move to the right", left.hint)
        val right = FramingCheck.check(TestPoses.shifted(TestPoses.standing(squat, 0), dx = 0.55f), squat)
        assertEquals("Move to the left", right.hint)
    }

    @Test fun aBodyTooLowOrTooHighAsksToMoveThePhone() {
        assertEquals("Raise your phone", FramingCheck.check(TestPoses.shifted(TestPoses.standing(squat, 0), dy = 0.2f), squat).hint)
        assertEquals("Lower your phone", FramingCheck.check(TestPoses.shifted(TestPoses.standing(squat, 0), dy = -0.25f), squat).hint)
    }

    @Test fun anUnsureModelMeansBadLight() {
        assertEquals("Better lighting needed", FramingCheck.check(TestPoses.dim(TestPoses.standing(squat, 0), 0.3f), squat).hint)
    }

    @Test fun nobodyAtAllAsksToStepBackAndLaterSuggestsLight() {
        val soon = FramingCheck.check(null, squat, noBodyForMs = 1_000)
        assertEquals(FramingState.NO_BODY, soon.state)
        assertEquals("Step back so your whole body is in view", soon.hint)
        assertEquals("Better lighting needed", FramingCheck.check(null, squat, noBodyForMs = 4_500).hint)
    }

    @Test fun aPushUpNeedsTheUpperBodyAndHands() {
        val f = TestPoses.without(TestPoses.standing(Exercise.PUSHUP, 0), JointId.L_WRIST, JointId.R_WRIST)
        val r = FramingCheck.check(f, Exercise.PUSHUP)
        assertEquals(FramingState.ADJUST, r.state)
        assertEquals("Get your head, arms and hips in view", r.hint)
    }

    @Test fun readyMustHoldForAMomentBeforeStartIsOffered() {
        val t = FramingTracker(stableMillis = 700)
        val ok = TestPoses.standing(squat, 0)
        t.update(ok, squat, 0)
        assertFalse(t.stableReady(100))
        t.update(ok, squat, 600)
        assertFalse(t.stableReady(600))
        t.update(ok, squat, 800)
        assertTrue(t.stableReady(800))
        // a flicker out of frame starts the wait again
        t.update(TestPoses.without(ok, JointId.L_ANKLE, JointId.R_ANKLE), squat, 900)
        assertFalse(t.stableReady(900))
        t.update(ok, squat, 1_000)
        assertFalse(t.stableReady(1_200))
        assertTrue(t.stableReady(1_800))
        // time without any body is counted for the lighting hint
        t.update(null, squat, 2_000)
        t.update(null, squat, 6_100)
        assertEquals("Better lighting needed", t.last.hint)
    }

    // ---------------------------------------------------------------- where the overlay is drawn

    @Test fun theOverlayUsesTheSameCropAsTheFullScreenPreview() {
        // a 9:16 picture on a 1080x2400 phone: the picture is scaled to the full height and cropped left and right (FILL_CENTER)
        val (cx, cy) = CameraGeometry.toView(0.5f, 0.5f, 0.5625f, 1080f, 2400f)
        assertEquals(540f, cx, 0.01f)
        assertEquals(1200f, cy, 0.01f)
        val (lx, _) = CameraGeometry.toView(0f, 0f, 0.5625f, 1080f, 2400f)
        assertEquals(-135f, lx, 0.01f)
        // on a squarer view the picture is scaled to the full width and cropped top and bottom
        val (_, my) = CameraGeometry.toView(0.5f, 0.5f, 0.5625f, 1080f, 1200f)
        assertEquals(600f, my, 0.01f)
        val (_, ty) = CameraGeometry.toView(0f, 0f, 0.5625f, 1080f, 1200f)
        assertEquals(-360f, ty, 0.01f)
    }

    // ---------------------------------------------------------------- the rep counter in a session

    private val rng = object : Random() {
        override fun nextBoolean() = true // the hand asked for is the one on the RIGHT side of the screen
        override fun nextInt(bound: Int) = 0 // and the prompt comes 2 s after counting starts
    }

    /** A session driven by made-up frames: a clock in ms, the framing seen first, then the countdown. */
    private class Run(val exercise: Exercise, val target: Int, rng: Random) {
        var t = 1_000L
        val s = PoseSession(exercise, target, rng, countdownMillis = 3_000, nowSeconds = { t / 1000 })

        fun begin() {
            repeat(20) { s.onFrame(TestPoses.standing(exercise, t)); t += 50 } // a second in frame
            assertTrue(s.canStart(t))
            s.start(t)
            t += 3_000
            s.tick(t)
            assertEquals(PoseSession.Stage.COUNTING, s.stage)
        }

        fun feed(frames: List<PoseFrame>) { for (f in frames) { s.onFrame(f); t = f.tsMillis } ; t += 50 }
        fun rep(repMs: Long = 1_600, raised: Boolean = false) = feed(TestPoses.repFrames(exercise, t, repMs, 50, raisedRight = raised))
        fun rest(ms: Long, raised: Boolean = false) {
            val n = (ms / 50).toInt()
            feed((0 until n).map { i ->
                if (exercise == Exercise.SQUAT) TestPoses.squat(172f, t + i * 50L, raisedRight = raised) else TestPoses.pushup(168f, t + i * 50L, raisedRight = raised)
            })
        }
    }

    @Test fun aFullRepIsCounted() {
        for (e in Exercise.entries) {
            val r = Run(e, 5, rng).apply { begin(); rep() }
            assertEquals(e.key, 1, r.s.reps)
            assertTrue(r.s.inFrame)
        }
    }

    @Test fun aPartialRepIsNotCounted() {
        val r = Run(squat, 5, rng).apply { begin() }
        // only down to a 125 degree knee (a quarter squat): the count needs the knee below 105 degrees
        val shallow = (0..32).map { i ->
            val t = i / 32f
            val depth = if (t < 0.5f) t / 0.5f else (1 - t) / 0.5f
            TestPoses.squat(172f - 47f * depth, r.t + i * 50L)
        }
        r.feed(shallow)
        assertEquals(0, r.s.reps)
    }

    @Test fun aFlickerIsNotCountedAndTheSameRepIsNotCountedTwice() {
        val r = Run(squat, 5, rng).apply { begin() }
        r.rep(repMs = 350) // far too fast to be a rep
        assertEquals(0, r.s.reps)
        r.rep()
        assertEquals(1, r.s.reps)
        r.rest(1_500) // standing still does not add reps
        assertEquals(1, r.s.reps)
        r.rep()
        assertEquals(2, r.s.reps)
    }

    @Test fun nothingIsCountedWhileTheBodyIsOutOfFrameAndCountingResumesInFrame() {
        val r = Run(squat, 5, rng).apply { begin() }
        val hidden = TestPoses.repFrames(squat, r.t, 1_600, 50).map { TestPoses.without(it, JointId.L_ANKLE, JointId.R_ANKLE) }
        r.feed(hidden)
        assertEquals(0, r.s.reps)
        assertFalse(r.s.inFrame)
        assertEquals("Step back so your whole body is in view", r.s.hint)
        r.s.onNoBody(r.t) // nobody at all
        assertEquals(0, r.s.reps)
        r.rest(500)
        r.rep()
        assertEquals(1, r.s.reps)
        assertTrue(r.s.inFrame)
    }

    @Test fun countingIgnoresFramesBeforeStartAndDuringTheCountdown() {
        val s = PoseSession(squat, 3, rng, countdownMillis = 3_000, nowSeconds = { 0 })
        for (f in TestPoses.repFrames(squat, 0, 1_600, 50)) s.onFrame(f)
        assertEquals(0, s.reps)
        assertEquals(PoseSession.Stage.READY, s.stage)
        s.start(5_000)
        for (f in TestPoses.repFrames(squat, 5_000, 1_600, 50)) s.onFrame(f) // still the countdown (3 s)
        assertEquals(0, s.reps)
    }

    @Test fun reachingTheTargetStopsCountingAndFinishesAfterTheHandRaise() {
        val r = Run(squat, 2, rng).apply { begin(); rep(); rep() }
        assertEquals(2, r.s.reps)
        assertTrue(r.s.targetReached)
        assertEquals(PoseSession.Stage.COUNTING, r.s.stage) // the hand-raise check is still to come
        r.rep() // one more rep after the target is not counted
        assertEquals(2, r.s.reps)
        assertEquals("Raise the hand on the RIGHT side of the screen", r.s.livenessPrompt)
        r.rest(1_000, raised = true)
        assertTrue(r.s.livenessPassed)
        // never finish in under 5 s of counting
        r.rest(2_000)
        assertEquals(PoseSession.Stage.DONE, r.s.stage)
        assertTrue(r.s.succeeded)
        assertEquals(2, r.s.result().reps)
        r.rep() // nothing more after Done
        assertEquals(2, r.s.reps)
    }

    @Test fun withoutTheHandRaiseTheSetDoesNotSucceed() {
        val r = Run(squat, 1, rng).apply { begin(); rep() }
        assertEquals(1, r.s.reps)
        r.rest(2_000) // the prompt appears but the hand stays down
        assertFalse(r.s.livenessPassed)
        assertFalse(r.s.succeeded)
    }

    @Test fun theSavedSummaryHasTheCountAndTheTargetButNothingFromTheCamera() {
        val r = Run(Exercise.PUSHUP, 1, rng).apply { begin(); rep(); rest(1_000, raised = true); rest(3_000) }
        val res = r.s.result()
        assertEquals(1, res.reps)
        assertTrue(res.summary.startsWith("pose pushup reps=1 target=1 liveness=true"))
        assertFalse(res.summary.contains("joints"))
    }

    @Test fun startIsOfferedOnlyInFrame() {
        val s = PoseSession(squat, 3, rng, nowSeconds = { 0 })
        repeat(20) { s.onFrame(TestPoses.without(TestPoses.standing(squat, it * 50L), JointId.L_ANKLE, JointId.R_ANKLE)) }
        assertFalse(s.canStart(1_000))
        repeat(20) { s.onFrame(TestPoses.standing(squat, 1_000 + it * 50L)) }
        assertTrue(s.canStart(2_000))
    }
}
