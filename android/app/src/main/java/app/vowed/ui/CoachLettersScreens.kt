package app.vowed.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vowed.debug.DebugProofs
import app.vowed.UiState
import app.vowed.data.CoachSuggestion
import app.vowed.letters.Letter
import app.vowed.letters.LetterProgress
import app.vowed.letters.LetterRules
import app.vowed.letters.Trigger

private fun actionTitle(a: String) = when (a) {
    "easier" -> "Go a little easier next time"
    "harder" -> "Ready for a harder one"
    "keep" -> "Keep the difficulty"
    else -> "Not enough history yet"
}

@Composable
fun CoachScreen(state: UiState, onBack: () -> Unit, onLoad: () -> Unit, onTry: (String) -> Unit) {
    LaunchedEffect(Unit) { onLoad() }
    val co = state.coachUi
    Page("Coach", onBack = onBack, actions = { TextButton(onClick = onLoad) { Text("Refresh") } }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "The coach looks at your last 14 days of real challenges, by kind of goal, and aims for a success rate of about 70 to 85 percent. It only advises your NEXT challenge and never changes one that is running. Demo pools do not count.",
                style = MaterialTheme.typography.bodySmall,
            )
            co.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (co.loading) CircularProgressIndicator()
            if (co.loaded && co.items.isEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Nothing to say yet", style = MaterialTheme.typography.titleMedium)
                        Text("Finish a few days of a real (non-demo) challenge and the coach will have something to go on.")
                    }
                }
            }
            co.items.forEach { s -> CoachCard(s, onTry) }
        }
    }
}

@Composable
private fun CoachCard(s: CoachSuggestion, onTry: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(categoryLabel(s.category), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(actionTitle(s.action), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            s.successRate?.let { Text("Success rate over the last 14 days: ${(it * 100).toInt()}%", style = MaterialTheme.typography.bodySmall) }
            Text(s.message)
            s.hints.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            if (s.action == "easier" || s.action == "harder") {
                Text("Suggested size: about ${(s.targetScale * 100).toInt()}% of your usual target.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onTry(s.category) }) { Text("Start a ${categoryLabel(s.category).lowercase()} goal") }
            }
        }
    }
}

private val MILESTONES = listOf(3, 7, 14, 30)

@Composable
fun LettersScreen(
    state: UiState,
    onBack: () -> Unit,
    onLoad: () -> Unit,
    onAdd: (String, Trigger, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onRead: (Letter) -> Unit,
    onCloseReading: () -> Unit,
    onSimulate: (LetterProgress) -> Unit,
) {
    LaunchedEffect(Unit) { onLoad() }
    val lu = state.lettersUi
    var text by remember { mutableStateOf("") }
    var milestone by remember { mutableStateOf<Int?>(7) } // null means "if my streak breaks"
    var deleteAfter by remember { mutableStateOf(false) }

    Page("Letters to future me", onBack = onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Write to yourself now; it arrives when you reach a milestone, or when your streak breaks. Letters are stored encrypted on this phone, with a key kept in the Android Keystore. Nothing is uploaded, and if you lose the phone you lose the letters.",
                style = MaterialTheme.typography.bodySmall,
            )
            lu.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            OutlinedTextField(value = text, onValueChange = { if (it.length <= 2_000) text = it }, label = { Text("Dear me") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            Text("Deliver it", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MILESTONES.forEach { d -> FilterChip(selected = milestone == d, onClick = { milestone = d }, label = { Text("Day $d") }) }
                FilterChip(selected = milestone == null, onClick = { milestone = null }, label = { Text("Streak breaks") })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = deleteAfter, onCheckedChange = { deleteAfter = it })
                Text("Erase it after I have read it")
            }
            Button(
                enabled = text.isNotBlank(),
                onClick = { onAdd(text, milestone?.let { Trigger.Milestone(it) } ?: Trigger.StreakBroken, deleteAfter); text = "" },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Seal the letter") }

            Text("Your letters", style = MaterialTheme.typography.titleMedium)
            if (lu.list.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall)
            lu.list.forEach { l ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (l.deliveredAt == null) {
                            Text("Sealed: opens ${LetterRules.describe(l.trigger)}", fontWeight = FontWeight.Medium)
                            Text("The text stays hidden until then.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Delivered: ${l.deliveredBecause ?: ""}", fontWeight = FontWeight.Medium)
                            Button(onClick = { onRead(l) }) { Text("Open the letter") }
                        }
                        TextButton(onClick = { onDelete(l.id) }) { Text("Delete") }
                    }
                }
            }

            if (DebugProofs.ENABLED) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Debug build: simulate progress", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                        Text(
                            "Delivers your sealed letters as if the trigger had happened. The reason shown on the letter says SIMULATED. Not part of the release app.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = { onSimulate(LetterProgress(mostDaysCompleted = 30, streakBroken = false, simulated = true)) }, modifier = Modifier.fillMaxWidth()) { Text("Simulate: reached a milestone (30 days)") }
                        OutlinedButton(onClick = { onSimulate(LetterProgress(mostDaysCompleted = 0, streakBroken = true, simulated = true)) }, modifier = Modifier.fillMaxWidth()) { Text("Simulate: streak broken") }
                    }
                }
            }
        }
    }

    lu.reading?.let { l ->
        AlertDialog(
            onDismissRequest = onCloseReading,
            title = { Text("A letter from your past self") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    l.deliveredBecause?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    Text(l.text)
                    if (l.deleteAfterReading) Text("This letter will be erased when you close it.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = onCloseReading) { Text("Close") } },
        )
    }
}
