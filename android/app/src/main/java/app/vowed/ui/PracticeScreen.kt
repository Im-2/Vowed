package app.vowed.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vowed.proof.pose.Exercise

/** Try the camera counter without a wallet, a pool or the server. Nothing is stored or sent. */
@Composable
fun PracticeScreen(onBack: () -> Unit) {
    var exercise by remember { mutableStateOf(Exercise.SQUAT) }
    var target by remember { mutableStateOf(5) }
    var finished by remember { mutableStateOf<String?>(null) }
    Page("Camera practice", onBack = onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Try the rep counter before you stake anything. Nothing is saved or sent: this screen never talks to the server.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Exercise.entries.forEach { e -> FilterChip(selected = exercise == e, onClick = { exercise = e; finished = null }, label = { Text(e.label) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 5, 10, 20).forEach { n -> FilterChip(selected = target == n, onClick = { target = n; finished = null }, label = { Text("$n reps") }) }
            }
            androidx.compose.runtime.key(exercise, target) {
                PoseCounterPanel(exercise, target, busy = false, submitLabel = "Finish with") { r ->
                    finished = "Practice done: ${r.reps} ${exercise.label} counted and the hand-raise check passed. Nothing was sent."
                }
            }
            finished?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
