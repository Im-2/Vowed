package app.vowed.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.view.WindowManager
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.vowed.BuildConfig
import app.vowed.proof.pose.CameraGeometry
import app.vowed.proof.pose.Exercise
import app.vowed.proof.pose.FramingCheck
import app.vowed.proof.pose.FramingResult
import app.vowed.proof.pose.FramingState
import app.vowed.proof.pose.JointId
import app.vowed.proof.pose.PoseCamera
import app.vowed.proof.pose.PoseFrame
import app.vowed.proof.pose.PoseSession
import app.vowed.proof.pose.PoseSessionResult
import app.vowed.proof.pose.TestPoses
import app.vowed.ui.components.PrimaryButton
import app.vowed.ui.components.SoftButton
import app.vowed.ui.theme.VowedColors
import kotlinx.coroutines.delay

/** Everything the camera screen shows, as plain values, so the same drawing code serves the live camera, the debug test poses and the screenshots. */
class CameraUi(
    val stage: PoseSession.Stage = PoseSession.Stage.READY,
    val reps: Int = 0,
    val target: Int = 20,
    val exerciseLabel: String = "squats",
    val framing: FramingResult = FramingResult(FramingState.NO_BODY, FramingCheck.HINT_STEP_BACK, emptyList()),
    val canStart: Boolean = false,
    val inFrame: Boolean = false,
    val hint: String? = null,
    val prompt: String? = null,
    val message: String? = null,
    val countdown: Int = 0,
    val frame: PoseFrame? = null,
    val elapsedSec: Int = 0,
    val succeeded: Boolean = false,
    val cameraError: String? = null,
    val front: Boolean = true,
    val soundOn: Boolean = false,
    val targetReached: Boolean = false,
)

private val Ink = Color(0xFF0E0B2A)
private val Lavender = Color(0xFFD9D2FF)
private val Ready = Color(0xFF3DDC97)

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Hides the system bars and lets the picture run under the notch, for as long as the camera screen is open. */
private fun makeFullScreen(window: android.view.Window, view: android.view.View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    if (Build.VERSION.SDK_INT >= 28) {
        val lp = window.attributes
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = lp
    }
    WindowInsetsControllerCompat(window, view).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.systemBars())
    }
}

/**
 * The full-screen camera: edge to edge, the picture fills the whole screen (cropped in the middle, never stretched), and the controls sit on top of it.
 * Frames are analysed on the phone only; nothing is stored or sent. The rep count, the hints and the "Done" state all come from [PoseSession].
 */
@Composable
fun PoseFullScreen(exercise: Exercise, target: Int, busy: Boolean, submitLabel: String, onClose: () -> Unit, onResult: (PoseSessionResult) -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        SideEffect { window?.let { makeFullScreen(it, view) } }
        // a set must not be interrupted by the phone turning: the screen stays as it is while the camera is open
        val activity = LocalContext.current.findActivity()
        DisposableEffect(activity) {
            val before = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            onDispose { activity?.requestedOrientation = before }
        }
        PoseCameraContent(exercise, target, busy, submitLabel, onClose, onResult)
    }
}

@Composable
private fun PoseCameraContent(exercise: Exercise, target: Int, busy: Boolean, submitLabel: String, onClose: () -> Unit, onResult: (PoseSessionResult) -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    var front by remember { mutableStateOf(true) }
    var run by remember { mutableLongStateOf(0L) }
    var soundOn by remember { mutableStateOf(false) }
    val session = remember(run) { PoseSession(exercise, target) }
    var frame by remember(run) { mutableStateOf<PoseFrame?>(null) }
    var tick by remember(run) { mutableIntStateOf(0) } // bumped on every frame so the state below is re-read
    var now by remember(run) { mutableLongStateOf(System.currentTimeMillis()) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var inject by remember { mutableStateOf<String?>(null) }
    val injecting by rememberUpdatedState(inject)

    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val camera = remember(front) { PoseCamera(ctx) }
    DisposableEffect(front, run) {
        camera.start(
            owner, previewView, front,
            onFrame = { f -> if (injecting == null) { session.onFrame(f); frame = f; tick++ } },
            onNoBody = { ts -> if (injecting == null) { session.onNoBody(ts); frame = null; tick++ } },
            onError = { cameraError = it },
        )
        onDispose { camera.stop() }
    }
    // the clock keeps running when no body is in view, so the countdown always ends
    LaunchedEffect(run) {
        while (true) {
            now = System.currentTimeMillis()
            session.tick(now)
            tick++
            delay(150)
        }
    }
    // debug builds only: made-up poses instead of the camera, so the overlay, the hints and the counter can be seen on an emulator
    LaunchedEffect(inject, run) {
        val mode = inject ?: return@LaunchedEffect
        val cycleStart = System.currentTimeMillis()
        while (true) {
            val ts = System.currentTimeMillis()
            val stand = TestPoses.standing(exercise, ts)
            val f = when (mode) {
                "stand" -> stand
                "feet" -> TestPoses.without(stand, JointId.L_ANKLE, JointId.R_ANKLE, JointId.L_KNEE, JointId.R_KNEE)
                "left" -> TestPoses.shifted(stand, dx = -0.42f)
                "low" -> TestPoses.shifted(stand, dy = 0.2f)
                "dark" -> TestPoses.dim(stand)
                "none" -> null
                else -> { // "reps": one rep every 2.1 s (1.8 s of movement and a short rest); the hand is raised while the check asks for it
                    val repMs = 1_800L
                    val pos = (ts - cycleStart) % (repMs + 300)
                    when {
                        session.livenessPrompt != null -> if (exercise == Exercise.SQUAT) TestPoses.squat(172f, ts, raisedRight = true) else TestPoses.pushup(168f, ts, raisedRight = true)
                        pos >= repMs -> stand
                        else -> TestPoses.repFrames(exercise, ts - pos, repMs, 50)[(pos / 50).toInt().coerceIn(0, (repMs / 50).toInt())]
                    }
                }
            }
            if (f != null) { session.onFrame(f); frame = f } else { session.onNoBody(ts); frame = null }
            tick++
            delay(50)
        }
    }

    // a counted rep: a short tick and (optionally) a beep; the number pulses in the overlay
    var lastReps by remember(run) { mutableIntStateOf(0) }
    val reps = session.reps
    LaunchedEffect(reps) {
        if (reps > lastReps && reps > 0) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            if (soundOn) runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 40).startTone(ToneGenerator.TONE_PROP_BEEP, 80) }
        }
        lastReps = reps
    }

    @Suppress("UNUSED_EXPRESSION") tick // read, so the values below are recomputed after every frame
    val stage = session.stage
    val ui = CameraUi(
        stage = stage, reps = reps, target = target, exerciseLabel = exercise.label, framing = session.framingTracker.last,
        canStart = session.canStart(now), inFrame = session.inFrame, hint = session.hint, prompt = session.livenessPrompt, message = session.message,
        countdown = session.countdownLeftSeconds(now), frame = frame,
        elapsedSec = if (stage == PoseSession.Stage.COUNTING) ((now / 1000) - session.startedAtSec).toInt().coerceAtLeast(0) else 0,
        succeeded = session.succeeded, cameraError = cameraError, front = front, soundOn = soundOn, targetReached = session.targetReached,
    )

    PoseCameraOverlay(
        ui = ui,
        preview = { AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize()) },
        onStart = { session.start(System.currentTimeMillis()); tick++ },
        onStop = { run += 1 },
        onFlip = { front = !front },
        onClose = onClose,
        onSound = { soundOn = !soundOn },
        onSubmit = { onResult(session.result()) },
        busy = busy,
        submitLabel = submitLabel,
        debugRow = if (BuildConfig.DEBUG) { { DebugPoseRow(inject) { inject = it } } } else null,
    )
}

/** Debug builds only: made-up poses. */
@Composable
private fun DebugPoseRow(current: String?, set: (String?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("DEBUG: inject a test pose (no camera needed)", color = Color(0xFFFFD54F), fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("stand" to "Stand", "feet" to "No feet", "left" to "Left", "low" to "Low", "dark" to "Dark", "reps" to "Reps", null to "Cam").forEach { (k, label) ->
                Box(
                    Modifier.clip(CircleShape).background(if (current == k) VowedColors.Indigo else Color.White.copy(alpha = 0.18f)).clickable { set(k) }.padding(horizontal = 9.dp, vertical = 6.dp),
                ) { Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1, softWrap = false) }
            }
        }
    }
}

private val shadowed = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 2f), 8f))

/** The drawing of the camera screen. [preview] is the live camera (or a stand-in for screenshots). */
@Composable
fun PoseCameraOverlay(
    ui: CameraUi,
    preview: @Composable () -> Unit,
    onStart: () -> Unit = {},
    onStop: () -> Unit = {},
    onFlip: () -> Unit = {},
    onClose: () -> Unit = {},
    onSound: () -> Unit = {},
    onSubmit: () -> Unit = {},
    busy: Boolean = false,
    submitLabel: String = "Submit",
    debugRow: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        preview()
        SkeletonAndGuide(ui)
        // dark gradients so white text stays readable on any picture
        Box(Modifier.fillMaxWidth().height(220.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent))).align(Alignment.TopCenter))
        Box(Modifier.fillMaxWidth().height(260.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)))).align(Alignment.BottomCenter))

        // top row: close, timer, sound, flip
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            RoundButton(onClose) { Icon(Icons.Default.Close, contentDescription = "Close the camera", tint = Color.White) }
            if (ui.stage == PoseSession.Stage.COUNTING) {
                Text("%d:%02d".format(ui.elapsedSec / 60, ui.elapsedSec % 60), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, style = shadowed)
            } else Spacer(Modifier.width(1.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable(onClickLabel = "Counting sound", onClick = onSound), contentAlignment = Alignment.Center) {
                    Text("♪", color = if (ui.soundOn) Ready else Color.White.copy(alpha = 0.5f), fontSize = 24.sp)
                }
                if (ui.stage == PoseSession.Stage.READY) RoundButton(onFlip) { Icon(Icons.Default.Refresh, contentDescription = if (ui.front) "Use the rear camera" else "Use the front camera", tint = Color.White) }
            }
        }

        // the counter: n / target with a ring that fills
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 62.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RepCounterRing(ui)
            Text(ui.exerciseLabel, color = Color.White.copy(alpha = 0.9f), fontSize = 16.sp, style = shadowed)
        }

        // the middle: countdown, or the success state
        Box(Modifier.align(Alignment.Center)) {
            when (ui.stage) {
                PoseSession.Stage.COUNTDOWN -> Text("${ui.countdown}", color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Bold, style = shadowed)
                PoseSession.Stage.DONE -> if (ui.succeeded) DoneBadge(ui)
                else -> Unit
            }
        }

        // bottom: hint, buttons, privacy
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val line = statusLine(ui)
            line?.let { (text, tone) ->
                Text(
                    text, color = when (tone) { Tone2.Good -> Ready; Tone2.Prompt -> Color(0xFFFFD54F); Tone2.Warn -> Color.White; Tone2.Error -> Color(0xFFFF8A80) },
                    textAlign = TextAlign.Center, fontSize = if (tone == Tone2.Prompt) 22.sp else 17.sp, fontWeight = if (tone == Tone2.Prompt || tone == Tone2.Good) FontWeight.Bold else FontWeight.Medium, style = shadowed,
                )
            }
            when (ui.stage) {
                PoseSession.Stage.READY -> PrimaryButton(if (ui.canStart) "Start" else "Get in frame to start", onStart, Modifier.fillMaxWidth(), enabled = ui.canStart)
                PoseSession.Stage.COUNTDOWN, PoseSession.Stage.COUNTING -> SoftButton("Stop and start over", onStop, Modifier.fillMaxWidth())
                PoseSession.Stage.DONE -> {
                    if (ui.succeeded) PrimaryButton("$submitLabel ${ui.reps} reps", onSubmit, Modifier.fillMaxWidth(), enabled = !busy)
                    SoftButton("Try again", onStop, Modifier.fillMaxWidth())
                }
            }
            Text("Your camera picture is analysed on this phone only. Nothing is stored or sent: only the number of reps goes to Vowed.", color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, textAlign = TextAlign.Center, style = shadowed)
            debugRow?.invoke()
        }
    }
}

private enum class Tone2 { Good, Prompt, Warn, Error }

private fun statusLine(ui: CameraUi): Pair<String, Tone2>? = when {
    ui.cameraError != null -> ui.cameraError!! to Tone2.Error
    ui.stage == PoseSession.Stage.COUNTDOWN -> "Get ready" to Tone2.Warn
    ui.prompt != null -> ui.prompt!! to Tone2.Prompt
    ui.stage == PoseSession.Stage.COUNTING && ui.targetReached -> "That is all of them. One last step: raise your hand when it asks." to Tone2.Good
    ui.stage == PoseSession.Stage.COUNTING && !ui.inFrame -> ((ui.hint ?: ui.framing.hint ?: FramingCheck.HINT_STEP_BACK) + ". Counting is paused.") to Tone2.Warn
    ui.stage == PoseSession.Stage.COUNTING -> (ui.hint ?: "Keep going") to Tone2.Warn
    ui.stage == PoseSession.Stage.READY && ui.canStart -> "You are in frame. Press Start." to Tone2.Good
    ui.stage == PoseSession.Stage.READY && ui.framing.ready -> "Hold still for a moment" to Tone2.Warn
    ui.stage == PoseSession.Stage.READY -> (ui.framing.hint ?: FramingCheck.HINT_STEP_BACK) to Tone2.Warn
    ui.stage == PoseSession.Stage.DONE && !ui.succeeded -> (ui.message ?: "The set did not count: ${ui.reps} of ${ui.target} reps.") to Tone2.Error
    else -> null
}

@Composable
private fun RoundButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

/** "n / target" in large type with a ring that fills as reps accumulate; the number pulses when a rep is counted. */
@Composable
private fun RepCounterRing(ui: CameraUi) {
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(ui.reps) {
        if (ui.reps > 0) { pulse.animateTo(1.28f, tween(90)); pulse.animateTo(1f, tween(160)) }
    }
    val progress = (ui.reps.toFloat() / ui.target.coerceAtLeast(1)).coerceIn(0f, 1f)
    val done = ui.reps >= ui.target
    Box(Modifier.size(128.dp).scale(pulse.value), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawCircle(Color.Black.copy(alpha = 0.35f), radius = size.minDimension / 2)
            drawArc(Color.White.copy(alpha = 0.28f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(if (done) Ready else Lavender, -90f, 360f * progress, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${ui.reps} / ${ui.target}", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, style = shadowed)
        }
    }
}

@Composable
private fun DoneBadge(ui: CameraUi) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(104.dp).clip(CircleShape).background(Ready), contentAlignment = Alignment.Center) {
            Text("✓", color = Ink, fontSize = 64.sp, fontWeight = FontWeight.Bold)
        }
        Text("Done", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, style = shadowed)
        Text("${ui.reps} of ${ui.target} ${ui.exerciseLabel}", color = Color.White, fontSize = 18.sp, style = shadowed)
    }
}

private val bones = listOf(
    JointId.L_SHOULDER to JointId.R_SHOULDER, JointId.L_SHOULDER to JointId.L_ELBOW, JointId.L_ELBOW to JointId.L_WRIST,
    JointId.R_SHOULDER to JointId.R_ELBOW, JointId.R_ELBOW to JointId.R_WRIST, JointId.L_SHOULDER to JointId.L_HIP, JointId.R_SHOULDER to JointId.R_HIP,
    JointId.L_HIP to JointId.R_HIP, JointId.L_HIP to JointId.L_KNEE, JointId.L_KNEE to JointId.L_ANKLE, JointId.R_HIP to JointId.R_KNEE, JointId.R_KNEE to JointId.R_ANKLE,
)

/** The body map (skeleton lines and joint dots) and the framing guide, drawn in the same coordinates as the full-screen preview. */
@Composable
private fun SkeletonAndGuide(ui: CameraUi) {
    Canvas(Modifier.fillMaxSize()) {
        val ready = ui.framing.ready
        val guide = if (ready) Ready else Color.White.copy(alpha = 0.75f)
        // framing guide: a dashed frame the whole body should fit inside, and a faint figure when nobody is detected yet
        if (ui.stage == PoseSession.Stage.READY || (ui.stage == PoseSession.Stage.COUNTING && !ui.inFrame)) {
            val w = size.width
            val h = size.height
            val left = w * 0.1f
            val top = h * 0.14f
            drawRoundRect(guide, Offset(left, top), Size(w * 0.8f, h * 0.72f), CornerRadius(36.dp.toPx()), style = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(26f, 18f))))
            if (ui.frame == null) drawFigure(w, h)
        }
        val f = ui.frame ?: return@Canvas
        val lineColor = if (ready || ui.inFrame) Ready else Lavender
        fun p(id: JointId): Offset? {
            val j = f.joints[id] ?: return null
            if (j.likelihood < FramingCheck.MIN_LIKELIHOOD) return null
            val (x, y) = CameraGeometry.toView(j.x, j.y, f.aspect, size.width, size.height)
            return Offset(x, y)
        }
        for ((a, b) in bones) {
            val pa = p(a) ?: continue
            val pb = p(b) ?: continue
            drawLine(Color.Black.copy(alpha = 0.45f), pa, pb, strokeWidth = 9.dp.toPx(), cap = StrokeCap.Round)
            drawLine(lineColor, pa, pb, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
        }
        for (id in JointId.entries) {
            val o = p(id) ?: continue
            drawCircle(Color.Black.copy(alpha = 0.5f), 9.dp.toPx(), o)
            drawCircle(Color.White, 7.dp.toPx(), o)
            drawCircle(VowedColors.Indigo, 4.dp.toPx(), o)
        }
    }
}

private fun DrawScope.drawFigure(w: Float, h: Float) {
    val c = Color.White.copy(alpha = 0.32f)
    val s = Stroke(6.dp.toPx(), cap = StrokeCap.Round)
    drawCircle(c, 0.055f * w, Offset(0.5f * w, 0.25f * h), style = s)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, Offset(x1 * w, y1 * h), Offset(x2 * w, y2 * h), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
    line(0.5f, 0.31f, 0.5f, 0.55f)
    line(0.5f, 0.35f, 0.34f, 0.47f); line(0.5f, 0.35f, 0.66f, 0.47f)
    line(0.5f, 0.55f, 0.42f, 0.78f); line(0.5f, 0.55f, 0.58f, 0.78f)
}
