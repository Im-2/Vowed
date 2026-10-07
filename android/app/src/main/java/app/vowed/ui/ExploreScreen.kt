package app.vowed.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import app.vowed.UiState
import app.vowed.data.ExploreItem

private val CATEGORIES = listOf(
    "study" to "Study", "steps" to "Walking", "fitness" to "Fitness", "detox" to "Screen time", "sleep" to "Sleep", "location" to "Places", "custom" to "Self-report",
)

fun categoryLabel(c: String) = CATEGORIES.firstOrNull { it.first == c }?.second ?: c.replaceFirstChar { it.uppercase() }

fun timeLeft(seconds: Long): String = when {
    seconds <= 0 -> "closed"
    seconds < 90 -> "${seconds} s"
    seconds < 3600 -> "${seconds / 60} min"
    seconds < 86_400 -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    else -> "${seconds / 86_400} days"
}

/** The tokens a filter can offer: our test tokens (when the faucet told us their addresses) and Circle's devnet USDC. */
fun tokenFilters(state: UiState): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    state.faucet.status?.tokens?.forEach { out += it.symbol to it.mint }
    out += "USDC (devnet)" to "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
    return out
}

@Composable
fun ExploreScreen(
    state: UiState,
    onLoad: () -> Unit,
    onMore: () -> Unit,
    onFilter: (category: String?, mint: String?, endingSoon: Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onReport: (String, String) -> Unit,
    onQuick: (String) -> Unit,
    onDismissMessage: () -> Unit,
) {
    LaunchedEffect(Unit) { onLoad() }
    val ex = state.explore
    val now = rememberNowSeconds()
    var reporting by remember { mutableStateOf<ExploreItem?>(null) }

    Page("Explore", actions = { TextButton(onClick = onLoad) { Text("Refresh") } }) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Quick challenges", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("DEMO MODE: days last a few minutes and the money is test money, so you can see a whole challenge in minutes.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        if (ex.quick.isEmpty() && ex.loading) CircularProgressIndicator()
                        Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ex.quick.forEach { q -> FilterChip(selected = false, onClick = { onQuick(q.text) }, label = { Text("${categoryLabel(q.category)}: ${q.text}") }) }
                            }
                        }
                        if (ex.demoPools.isNotEmpty()) {
                            Text("Open demo challenges by others", style = MaterialTheme.typography.titleSmall)
                            ex.demoPools.forEach { p -> ExploreCard(p, now, onOpen, { reporting = p }) }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Public challenges", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = ex.category == null, onClick = { onFilter(null, ex.mint, ex.endingSoon) }, label = { Text("All kinds") })
                        CATEGORIES.forEach { (k, label) -> FilterChip(selected = ex.category == k, onClick = { onFilter(if (ex.category == k) null else k, ex.mint, ex.endingSoon) }, label = { Text(label) }) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = ex.mint == null, onClick = { onFilter(ex.category, null, ex.endingSoon) }, label = { Text("Any token") })
                        tokenFilters(state).forEach { (sym, mint) -> FilterChip(selected = ex.mint == mint, onClick = { onFilter(ex.category, if (ex.mint == mint) null else mint, ex.endingSoon) }, label = { Text(sym) }) }
                        FilterChip(selected = ex.endingSoon, onClick = { onFilter(ex.category, ex.mint, !ex.endingSoon) }, label = { Text("Ending soon") })
                    }
                }
            }
            ex.message?.let { m -> item { Row(verticalAlignment = Alignment.CenterVertically) { Text(m, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary); TextButton(onClick = onDismissMessage) { Text("OK") } } } }
            ex.error?.let { e -> item { Column { Text(e, color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = onLoad) { Text("Try again") } } } }
            if (ex.loading && ex.items.isEmpty()) item { CircularProgressIndicator() }
            if (!ex.loading && ex.loaded && ex.items.isEmpty() && ex.error == null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Nothing matches right now", style = MaterialTheme.typography.titleMedium)
                            Text("No public challenge fits these filters. Clear a filter, or start your own: you can make it public when you create it.")
                        }
                    }
                }
            }
            items(ex.items, key = { it.pool }) { p -> ExploreCard(p, now, onOpen) { reporting = p } }
            if (ex.nextCursor != null) item { OutlinedButton(onClick = onMore, enabled = !ex.loadingMore, modifier = Modifier.fillMaxWidth()) { Text(if (ex.loadingMore) "Loading…" else "Show more") } }
        }
    }

    reporting?.let { p ->
        AlertDialog(
            onDismissRequest = { reporting = null },
            title = { Text("Report this challenge") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("\"${p.title}\"", fontWeight = FontWeight.Medium)
                    Text("Why are you reporting it?")
                    listOf("spam" to "Spam", "offensive" to "Offensive language", "scam" to "Looks like a scam", "other" to "Something else").forEach { (k, label) ->
                        OutlinedButton(onClick = { onReport(p.pool, k); reporting = null }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { reporting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ExploreCard(p: ExploreItem, now: Long, onOpen: (String) -> Unit, onReport: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (p.isDemo) DemoBadge(p.demoLabel)
            if (p.sample) Text("SAMPLE: created by the Vowed team so there is always something to try", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            Text(p.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text("${categoryLabel(p.category)} · ${p.mode} mode · ${p.durationDays} days (${p.requiredDays} needed)", style = MaterialTheme.typography.bodySmall)
            Text("Token: ${p.tokenSymbol}${if (p.tokenIsTest) " (test token, no real value)" else ""} · Pot ${fmt(p.totalDeposits)}", style = MaterialTheme.typography.bodySmall)
            Text("${p.participantCount} of ${p.maxParticipants} joined · join within ${timeLeft(p.joinDeadlineTs - now)}", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    p.createdByYou -> OutlinedButton(onClick = { onOpen(p.pool) }) { Text("Yours: open") }
                    p.joined -> OutlinedButton(onClick = { onOpen(p.pool) }) { Text("You joined: open") }
                    else -> Button(onClick = { onOpen(p.pool) }) { Text("Join") }
                }
                if (!p.createdByYou && !p.sample) TextButton(onClick = onReport) { Text("Report") }
            }
        }
    }
}
