package app.vowed.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vowed.UiState
import app.vowed.data.FeedEvent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun SquadsScreen(state: UiState, onLoad: () -> Unit, onCreate: (String) -> Unit, onJoin: (String) -> Unit, onOpen: (String) -> Unit, joinCode: String?) {
    LaunchedEffect(Unit) { onLoad() }
    val sq = state.squads
    var name by remember { mutableStateOf("") }
    var code by remember(joinCode) { mutableStateOf(joinCode ?: "") }
    Page("Squads", actions = { TextButton(onClick = onLoad) { Text("Refresh") } }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("A squad is a few friends doing a challenge together: you see who checked in today, nudge friends who have not, and share the leaderboard. Squad challenges are private.", style = MaterialTheme.typography.bodySmall)
            sq.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            sq.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (sq.loading && !sq.loaded) CircularProgressIndicator()
            if (sq.loaded && sq.list.isEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("No squads yet", style = MaterialTheme.typography.titleMedium)
                        Text("Create one and share its code, or join a friend's squad with their code.")
                    }
                }
            }
            sq.list.forEach { s ->
                Card(onClick = { onOpen(s.id) }, Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                        Text("${s.memberCount} member${if (s.memberCount == 1) "" else "s"} · code ${s.inviteCode}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider()
            Text("Create a squad", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = name, onValueChange = { if (it.length <= 40) name = it }, label = { Text("Squad name") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(enabled = name.trim().length >= 2 && !sq.busy, onClick = { onCreate(name); name = "" }) { Text("Create") }
            }
            Text("Join with a code", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = code, onValueChange = { code = it.uppercase().take(12) }, label = { Text("Invite code") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(enabled = code.trim().length >= 6 && !sq.busy, onClick = { onJoin(code); code = "" }) { Text("Join") }
            }
        }
    }
}

private fun inviteText(name: String, code: String, link: String) = "Join my Vowed squad \"$name\": open the app, go to Squads and enter the code $code (or tap $link)."

fun shareInvite(ctx: Context, name: String, code: String, link: String) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, inviteText(name, code, link))
    }
    ctx.startActivity(Intent.createChooser(i, "Invite a friend").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun SquadDetailScreen(
    state: UiState,
    myWallet: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onNudge: (String) -> Unit,
    onOpenPool: (String) -> Unit,
    onNewChallenge: () -> Unit,
) {
    val ctx = LocalContext.current
    val sq = state.squads
    val d = sq.detail
    // live updates: the feed and leaderboard refresh every few seconds while this screen is open
    LaunchedEffect(d?.squad?.id) {
        while (d != null) {
            delay(8_000)
            onRefresh()
        }
    }
    Page(d?.squad?.name ?: "Squad", onBack = onBack, actions = { TextButton(onClick = onRefresh) { Text("Refresh") } }) {
        if (d == null) {
            if (sq.error != null) Text(sq.error, color = MaterialTheme.colorScheme.error) else CircularProgressIndicator()
            return@Page
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Invite code: ${d.squad.inviteCode}", fontWeight = FontWeight.SemiBold)
                    Text("${d.members.size} member${if (d.members.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { shareInvite(ctx, d.squad.name, d.squad.inviteCode, d.squad.deepLink) }) { Text("Share invite") }
                }
            }
            sq.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            sq.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Text("Leaderboard", style = MaterialTheme.typography.titleMedium)
            if (sq.board.isEmpty()) Text("Nobody has checked in yet. Start a challenge for this squad below.", style = MaterialTheme.typography.bodySmall)
            sq.board.forEach { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${r.rank}.", Modifier.padding(end = 2.dp), fontWeight = FontWeight.Bold)
                    Column(Modifier.weight(1f)) {
                        Text(if (r.wallet == myWallet) "You" else short(r.wallet), fontWeight = FontWeight.Medium)
                        Text("${r.daysCompleted} days done · best streak ${r.bestStreak}${if (r.checkedInToday) " · checked in today" else ""}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (r.wallet != myWallet && !r.checkedInToday) OutlinedButton(enabled = !sq.busy, onClick = { onNudge(r.wallet) }) { Text("Nudge") }
                }
            }

            Text("Challenges", style = MaterialTheme.typography.titleMedium)
            if (d.challenges.isEmpty()) Text("No challenge yet.", style = MaterialTheme.typography.bodySmall)
            d.challenges.forEach { c ->
                OutlinedButton(onClick = { onOpenPool(c.pool) }, modifier = Modifier.fillMaxWidth()) { Text("${c.status} · ${c.durationDays} days · ${short(c.pool)}") }
            }
            Button(onClick = onNewChallenge, modifier = Modifier.fillMaxWidth()) { Text("Start a challenge for this squad") }

            Text("Activity", style = MaterialTheme.typography.titleMedium)
            if (sq.feed.isEmpty()) Text("Nothing yet. Check-ins, joins and nudges show up here within seconds.", style = MaterialTheme.typography.bodySmall)
            sq.feed.forEach { e -> Text("• ${feedText(e, myWallet)}", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

fun feedText(e: FeedEvent, me: String?): String {
    val who = if (e.wallet == me) "You" else short(e.wallet)
    val day = (e.data["day"] as? JsonPrimitive)?.intOrNull
    return when (e.kind) {
        "checked_in" -> "$who checked in${if (day != null) " (day ${day + 1})" else ""}"
        "missed" -> "$who missed day ${(day ?: 0) + 1}"
        "joined" -> "$who joined the challenge"
        "nudge" -> "$who sent a nudge to ${(e.data["recipient"] as? JsonPrimitive)?.jsonPrimitive?.content?.let { if (it == me) "you" else short(it) } ?: "a friend"}"
        "settled" -> "$who finished: ${if ((e.data["succeeded"] as? JsonPrimitive)?.content == "true") "success" else "not this time"}"
        else -> "$who: ${e.kind}"
    }
}
