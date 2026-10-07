package app.vowed.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.vowed.CheckInState
import app.vowed.proof.Collected
import app.vowed.proof.hasPermission
import app.vowed.proof.pose.Exercise
import app.vowed.proof.pose.PoseCamera
import app.vowed.proof.pose.PoseSession
import kotlinx.coroutines.delay

/** The check-in version: the plan decides the exercise and the target; a finished set becomes a signed proof. */
@Composable
fun PoseCheckInPanel(ci: CheckInState, busy: Boolean, onSubmit: (Collected) -> Unit) {
    val exercise = Exercise.fromKey(ci.params["exercise"] ?: if (ci.title.contains("push", ignoreCase = true)) "pushup" else "squat")
    val target = ci.target.value.toInt().coerceAtLeast(1)
    PoseCounterPanel(exercise, target, busy, "Submit") { r ->
        onSubmit(Collected(mapOf("reps" to r.reps, "livenessPassed" to true), r.startedAtSec, r.endedAtSec, r.summary))
    }
}

/**
 * Camera rep counting. The image never leaves the phone: only the number of reps and "liveness passed" come out of it.
 * Used by the check-in (which submits the result) and by the practice screen (which sends nothing).
 */
@Composable
fun PoseCounterPanel(exercise: Exercise, target: Int, busy: Boolean, submitLabel: String, onResult: (app.vowed.proof.pose.PoseSessionResult) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ctx.hasPermission(Manifest.permission.CAMERA)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    Text("The camera counts your ${exercise.label} on this phone. The picture is not saved or sent: only the number of reps goes to Vowed.", style = MaterialTheme.typography.bodySmall)
    if (!granted) {
        Text("To count reps, Vowed needs the camera.")
        Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        return
    }

    var front by remember { mutableStateOf(true) }
    var run by remember { mutableLongStateOf(0L) } // bump to restart a session
    var session by remember(run) { mutableStateOf(PoseSession(exercise, target)) }
    var stage by remember(run) { mutableStateOf(PoseSession.Stage.READY) }
    var reps by remember(run) { mutableStateOf(0) }
    var hint by remember(run) { mutableStateOf<String?>(null) }
    var prompt by remember(run) { mutableStateOf<String?>(null) }
    var message by remember(run) { mutableStateOf<String?>(null) }
    var countdown by remember(run) { mutableStateOf(0) }
    var noBody by remember(run) { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val camera = remember(front) { PoseCamera(ctx) }
    DisposableEffect(front, run) {
        val s = session
        camera.start(
            owner, previewView, front,
            onFrame = { f ->
                s.onFrame(f)
                stage = s.stage
                reps = s.reps
                hint = s.hint
                prompt = s.livenessPrompt
                message = s.message
                countdown = s.countdownLeftSeconds(f.tsMillis)
                noBody = false
            },
            onNoBody = { noBody = true },
            onError = { cameraError = it },
        )
        onDispose { camera.stop() }
    }
    // the session follows the clock even when no body is in view, so the countdown always ends
    LaunchedEffect(stage, run) {
        while (stage == PoseSession.Stage.COUNTDOWN || stage == PoseSession.Stage.COUNTING) {
            val now = System.currentTimeMillis()
            session.tick(now)
            stage = session.stage
            countdown = session.countdownLeftSeconds(now)
            delay(200)
        }
    }

    Box(Modifier.fillMaxWidth().height(380.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(380.dp))
        Column(Modifier.align(Alignment.TopCenter).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$reps / $target", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(exercise.label, color = Color.White.copy(alpha = 0.8f))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val line = when {
                cameraError != null -> cameraError
                stage == PoseSession.Stage.COUNTDOWN -> "Get ready… $countdown"
                prompt != null -> prompt
                stage == PoseSession.Stage.COUNTING && noBody -> "I cannot see a person. Step back so your whole body is in view."
                stage == PoseSession.Stage.COUNTING -> hint ?: "Keep going"
                stage == PoseSession.Stage.READY -> "Place the phone so your whole body is in view, then press Start."
                else -> null
            }
            line?.let { Text(it, color = if (prompt != null) Color(0xFFFFD54F) else Color.White, textAlign = TextAlign.Center, fontSize = if (prompt != null) 20.sp else 15.sp, fontWeight = if (prompt != null) FontWeight.Bold else FontWeight.Normal) }
        }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (stage) {
            PoseSession.Stage.READY -> Button(onClick = { session.start(System.currentTimeMillis()); stage = session.stage }) { Text("Start") }
            PoseSession.Stage.COUNTDOWN, PoseSession.Stage.COUNTING -> OutlinedButton(onClick = { run += 1 }) { Text("Stop and start over") }
            PoseSession.Stage.DONE -> {
                if (session.succeeded) {
                    Button(enabled = !busy, onClick = { onResult(session.result()) }) { Text("$submitLabel $reps reps") }
                }
                OutlinedButton(onClick = { run += 1 }) { Text("Try again") }
            }
        }
        if (stage == PoseSession.Stage.READY) OutlinedButton(onClick = { front = !front }) { Text(if (front) "Use rear camera" else "Use front camera") }
    }
    if (stage == PoseSession.Stage.DONE && !session.succeeded) Text("The set did not count: ${session.result().reps} of $target reps${if (!session.livenessPassed) " and the hand-raise check was not passed" else ""}.", color = MaterialTheme.colorScheme.error)
}
