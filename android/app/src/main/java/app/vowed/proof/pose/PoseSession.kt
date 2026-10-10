package app.vowed.proof.pose

import java.security.SecureRandom
import java.util.Random

/**
 * One camera check-in: a short get-ready countdown, then counting reps, with a randomised "raise your hand" prompt somewhere in the
 * first seconds. The result carries only the rep count, whether the liveness prompt was passed, two timestamps and a text summary.
 * No frame, image or landmark is kept or sent anywhere.
 */
class PoseSession(
    val exercise: Exercise,
    val targetReps: Int,
    private val random: Random = SecureRandom(),
    private val countdownMillis: Long = 3_000,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    enum class Stage { READY, COUNTDOWN, COUNTING, DONE }

    var stage = Stage.READY
        private set
    private var startMs = 0L
    private var countingStartMs = 0L
    private var counter = RepCounter(exercise)
    private var liveness: Liveness? = null
    /** how the framing is right now; counting only happens while the whole body is in frame */
    val framingTracker = FramingTracker()
    private var livenessFailures = 0
    var livenessPassed = false
        private set
    var startedAtSec = 0L
        private set
    var endedAtSec = 0L
        private set

    val reps: Int get() = counter.reps

    /** false while the body is not (fully) in frame: no rep is counted then */
    var inFrame = false
        private set

    /** true once the target is reached: counting stops there, the hand-raise check may still be pending */
    val targetReached: Boolean get() = counter.reps >= targetReps

    /** the Start button is offered only after the body has been in frame for a moment */
    fun canStart(nowMs: Long): Boolean = stage == Stage.READY && framingTracker.stableReady(nowMs)
    val livenessPrompt: String? get() = liveness?.prompt
    var hint: String? = null
        private set
    /** Set when the liveness prompt was missed: the person is asked to start over. */
    var message: String? = null
        private set

    companion object {
        const val MIN_COUNTING_MILLIS = 5_000L
    }

    fun start(nowMs: Long) {
        stage = Stage.COUNTDOWN
        startMs = nowMs
        counter = RepCounter(exercise)
        livenessPassed = false
        livenessFailures = 0
        liveness = null
        message = null
    }

    fun countdownLeftSeconds(nowMs: Long): Int = if (stage == Stage.COUNTDOWN) (((countdownMillis - (nowMs - startMs)) + 999) / 1000).toInt().coerceAtLeast(0) else 0

    private fun newLiveness(sinceMs: Long) = Liveness(askRightHandOnScreen = random.nextBoolean(), promptAtMillis = sinceMs + 2_000 + random.nextInt(4_000))

    /**
     * Moves the session along with the clock. The screen calls this several times a second, so the countdown ends and counting starts even
     * when no body is in view (otherwise a person who is not yet in frame would stay stuck at "Get ready 0").
     */
    fun tick(nowMs: Long) {
        if (stage == Stage.COUNTDOWN && nowMs - startMs >= countdownMillis) {
            stage = Stage.COUNTING
            countingStartMs = nowMs
            startedAtSec = nowSeconds()
            liveness = newLiveness(0)
        }
    }

    /** The model found nobody in this picture (the clock still runs). */
    fun onNoBody(nowMs: Long) {
        framingTracker.update(null, exercise, nowMs)
        inFrame = false
        if (stage == Stage.COUNTING) hint = framingTracker.last.hint
        tick(nowMs)
    }

    fun onFrame(f: PoseFrame) {
        val now = f.tsMillis
        framingTracker.update(f, exercise, now)
        tick(now)
        when (stage) {
            Stage.READY, Stage.DONE -> return
            Stage.COUNTDOWN -> return
            Stage.COUNTING -> if (now < countingStartMs) return // a frame from before counting began
        }
        val sinceStart = now - countingStartMs
        // reps count only while the whole body is in frame; out of frame the person is told what to change and nothing is counted
        val framing = framingTracker.last
        inFrame = framing.ready
        if (!inFrame) {
            hint = framing.hint
        } else if (counter.reps < targetReps) {
            // at the target counting stops; the hand-raise check can still be answered
            hint = counter.update(f).hint
        } else {
            hint = null
        }
        val l = liveness
        if (l != null && !livenessPassed) {
            when (l.update(sinceStart, f)) {
                Liveness.State.PASSED -> {
                    livenessPassed = true
                    message = null
                }
                Liveness.State.FAILED -> {
                    livenessFailures++
                    if (livenessFailures >= 2) {
                        message = "The hand-raise check failed twice. Start again when you are ready."
                        finish(now)
                        return
                    }
                    message = "Missed the hand-raise check. One more try: keep going."
                    liveness = newLiveness(sinceStart)
                }
                else -> Unit
            }
        }
        // never finish in under MIN_COUNTING_MILLIS: the server refuses a session too short for its reps, and a real set takes longer
        if (reps >= targetReps && livenessPassed && sinceStart >= MIN_COUNTING_MILLIS) finish(now)
    }

    fun finish(nowMs: Long) {
        if (stage == Stage.DONE) return
        stage = Stage.DONE
        endedAtSec = nowSeconds()
        if (startedAtSec == 0L) startedAtSec = endedAtSec
        @Suppress("UNUSED_VARIABLE") val unused = nowMs
    }

    /** The reps reached the goal and the liveness prompt was passed. */
    val succeeded: Boolean get() = stage == Stage.DONE && livenessPassed && reps >= targetReps

    fun result(): PoseSessionResult = PoseSessionResult(
        reps = reps,
        livenessPassed = livenessPassed,
        startedAtSec = startedAtSec,
        endedAtSec = endedAtSec,
        summary = "pose ${exercise.key} reps=$reps target=$targetReps liveness=$livenessPassed frames=${counter.framesUsed}/${counter.framesSkipped} angle=${"%.0f".format(counter.deepest)}..${"%.0f".format(counter.shallowest)}",
    )
}
