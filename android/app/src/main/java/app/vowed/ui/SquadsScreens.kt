package app.vowed.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.vowed.UiState
import app.vowed.data.FeedEvent
import app.vowed.ui.art.Glyph
import app.vowed.ui.art.GlyphIcon
import app.vowed.ui.components.AppCard
import app.vowed.ui.components.Avatar
import app.vowed.ui.components.EmptyState
import app.vowed.ui.components.PrimaryButton
import app.vowed.ui.components.SectionHeader
import app.vowed.ui.components.SmallButton
import app.vowed.ui.components.SoftButton
import app.vowed.ui.components.Spinner
import app.vowed.ui.components.StatusBadge
import app.vowed.ui.components.Tone
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun SquadsScreen(
    state: UiState, onLoad: () -> Unit, onCreate: (String) -> Unit, onJoin: (String) -> Unit, onOpen: (String) -> Unit, joinCode: String?,
    notificationsOn: Boolean = true, onTurnOnNotifications: () -> Unit = {},
) {
    LaunchedEffect(Unit) { onLoad() }
    val sq = state.squads
    var name by remember { mutableStateOf("") }
    var code by remember(joinCode) { mutableStateOf(joinCode ?: "") }
    Page("Squads", actions = { TextButton(onClick = onLoad) { Text("Refresh") } }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "A squad is a few friends doing a challenge together: you see who checked in today, nudge friends who have not, and share the leaderboard. Squad challenges are private.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sq.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            sq.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (!notificationsOn) SoftButton("Turn on notifications for nudges and check-ins", onTurnOnNotifications)
            if (sq.loading && !sq.loaded) Spinner()
            if (sq.loaded && sq.list.isEmpty()) {
                EmptyState("No squads yet", "Create one and share its code, or join a friend's squad with their code.", art = { app.vowed.ui.art.SquadArt(150.dp) })
            }
            sq.list.forEach { s ->
                AppCard(onClick = { onOpen(s.id) }, Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Avatar(s.name, 48.dp)
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleSmall)
                            Text("${s.memberCount} member${if (s.memberCount == 1) "" else "s"} · code ${s.inviteCode}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        GlyphIcon(Glyph.Chevron, MaterialTheme.colorScheme.onSurfaceVariant, 22.dp)
                    }
                }
            }
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Create a squad", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(value = name, onValueChange = { if (it.length <= 40) name = it }, label = { Text("Squad name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    PrimaryButton("Create", { onCreate(name); name = "" }, enabled = name.trim().length >= 2 && !sq.busy)
                }
            }
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Join with a code", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(value = code, onValueChange = { code = it.uppercase().take(12) }, label = { Text("Invite code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    SoftButton("Join", { onJoin(code); code = "" }, enabled = code.trim().length >= 6 && !sq.busy)
                }
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
            if (sq.error != null) Text(sq.error, color = MaterialTheme.colorScheme.error) else Spinner()
            return@Page
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Invite code: ${d.squad.inviteCode}", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        d.members.take(6).forEach { m -> Avatar(m.wallet, 34.dp) }
                        Text("${d.members.size} member${if (d.members.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                    }
                    SoftButton("Share invite", { shareInvite(ctx, d.squad.name, d.squad.inviteCode, d.squad.deepLink) })
                }
            }
            sq.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            sq.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            SectionHeader("Leaderboard")
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (sq.board.isEmpty()) Text("Nobody has checked in yet. Start a challenge for this squad below.", style = MaterialTheme.typography.bodySmall)
                    sq.board.forEach { r ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(if (r.rank == 1) Color(0xFFFFC857) else MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) { Text("${r.rank}", style = MaterialTheme.typography.labelMedium, color = if (r.rank == 1) Color(0xFF5A3A08) else MaterialTheme.colorScheme.primary) }
                            Avatar(r.wallet, 40.dp)
                            Column(Modifier.weight(1f)) {
                                Text(if (r.wallet == myWallet) "You" else short(r.wallet), style = MaterialTheme.typography.titleSmall)
                                Text("${r.daysCompleted} days done · best streak ${r.bestStreak}${if (r.checkedInToday) " · checked in today" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (r.wallet != myWallet && !r.checkedInToday) SmallButton("Nudge", { onNudge(r.wallet) }, enabled = !sq.busy)
                        }
                    }
                }
            }

            SectionHeader("Challenges")
            if (d.challenges.isEmpty()) Text("No challenge yet.", style = MaterialTheme.typography.bodySmall)
            d.challenges.forEach { c ->
                AppCard(onClick = { onOpenPool(c.pool) }, Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusBadge(c.status, if (c.status == "Open") Tone.Success else Tone.Neutral)
                        Text("${c.durationDays} days · ${short(c.pool)}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        GlyphIcon(Glyph.Chevron, MaterialTheme.colorScheme.onSurfaceVariant, 22.dp)
                    }
                }
            }
            PrimaryButton("Start a challenge for this squad", onNewChallenge)

            SectionHeader("Activity")
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (sq.feed.isEmpty()) Text("Nothing yet. Check-ins, joins and nudges show up here within seconds.", style = MaterialTheme.typography.bodySmall)
                    sq.feed.forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Avatar(e.wallet, 30.dp)
                            Text(feedText(e, myWallet), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
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
