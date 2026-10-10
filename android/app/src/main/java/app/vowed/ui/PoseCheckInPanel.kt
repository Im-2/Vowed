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
 * Used by the check-in (which submits the result) and by the practice screen (which sends nothing). The counting itself happens on a full-screen
 * camera ([PoseFullScreen]); this card explains it and opens it.
 */
@Composable
fun PoseCounterPanel(exercise: Exercise, target: Int, busy: Boolean, submitLabel: String, onResult: (app.vowed.proof.pose.PoseSessionResult) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ctx.hasPermission(Manifest.permission.CAMERA)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var open by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<app.vowed.proof.pose.PoseSessionResult?>(null) }

    Text("The camera counts your ${exercise.label} on this phone, full screen, with your body mapped on top. The picture is not saved or sent: only the number of reps goes to Vowed.", style = MaterialTheme.typography.bodySmall)
    Text("Goal: $target ${exercise.label}. Stand where your whole body fits, then press Start.", style = MaterialTheme.typography.titleSmall)
    if (!granted) {
        Text("To count reps, Vowed needs the camera.")
        Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        return
    }
    Button(enabled = !busy, onClick = { open = true }) { Text("Open the camera") }
    last?.let { Text("Last set: ${it.reps} of $target ${exercise.label}.", style = MaterialTheme.typography.bodySmall) }
    if (open) {
        PoseFullScreen(exercise, target, busy, submitLabel, onClose = { open = false }) { r ->
            last = r
            open = false
            onResult(r)
        }
    }
}
