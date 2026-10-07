package app.vowed.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vowed.UiState
import app.vowed.data.ExploreItem
import app.vowed.ui.art.GlyphIcon
import app.vowed.ui.art.categoryGlyph
import app.vowed.ui.components.AppCard
import app.vowed.ui.components.CategoryStyle
import app.vowed.ui.components.EmptyState
import app.vowed.ui.components.GradientTile
import app.vowed.ui.components.SmallButton
import app.vowed.ui.components.Spinner
import app.vowed.ui.components.StatusBadge
import app.vowed.ui.components.Tone
import app.vowed.ui.components.trustLabel
import app.vowed.ui.components.trustTone

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
private fun FilterPill(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label, style = MaterialTheme.typography.labelMedium) }, shape = MaterialTheme.shapes.extraLarge,
        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface, selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = Color.White),
    )
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
    onCategories: () -> Unit = {},
) {
    LaunchedEffect(Unit) { onLoad() }
    val ex = state.explore
    val now = rememberNowSeconds()
    var reporting by remember { mutableStateOf<ExploreItem?>(null) }

    Page("Explore", actions = { TextButton(onClick = onCategories) { Text("Categories") } }) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item {
                AppCard(Modifier.fillMaxWidth(), container = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Quick challenges", style = MaterialTheme.typography.titleMedium)
                        StatusBadge("DEMO MODE: days last a few minutes and the money is test money, so you can see a whole challenge in minutes.", Tone.Danger)
                        if (ex.quick.isEmpty() && ex.loading) Spinner()
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ex.quick.forEach { q -> FilterPill(false, "${categoryLabel(q.category)}: ${q.text}") { onQuick(q.text) } }
                        }
                        if (ex.demoPools.isNotEmpty()) {
                            Text("Open demo challenges by others", style = MaterialTheme.typography.titleSmall)
                            ex.demoPools.forEach { p -> ExploreCard(p, now, onOpen) { reporting = p } }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Public challenges", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterPill(ex.category == null, "All kinds") { onFilter(null, ex.mint, ex.endingSoon) }
                        CATEGORIES.forEach { (k, label) -> FilterPill(ex.category == k, label) { onFilter(if (ex.category == k) null else k, ex.mint, ex.endingSoon) } }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterPill(ex.mint == null, "Any token") { onFilter(ex.category, null, ex.endingSoon) }
                        tokenFilters(state).forEach { (sym, mint) -> FilterPill(ex.mint == mint, sym) { onFilter(ex.category, if (ex.mint == mint) null else mint, ex.endingSoon) } }
                        FilterPill(ex.endingSoon, "Ending soon") { onFilter(ex.category, ex.mint, !ex.endingSoon) }
                    }
                }
            }
            ex.message?.let { m -> item { Row(verticalAlignment = Alignment.CenterVertically) { Text(m, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary); TextButton(onClick = onDismissMessage) { Text("OK") } } } }
            ex.error?.let { e -> item { Column { Text(e, color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = onLoad) { Text("Try again") } } } }
            if (ex.loading && ex.items.isEmpty()) item { Spinner() }
            if (!ex.loading && ex.loaded && ex.items.isEmpty() && ex.error == null) {
                item { EmptyState("Nothing matches right now", "No public challenge fits these filters. Clear a filter, or start your own: you can make it public when you create it.") }
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
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                GradientTile(CategoryStyle.colors(p.category), Modifier.size(56.dp)) { GlyphIcon(categoryGlyph(p.category), Color.White, 30.dp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    Text("${categoryLabel(p.category)} · ${p.mode} mode · ${p.durationDays} days (${p.requiredDays} needed)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (p.isDemo) StatusBadge(p.demoLabel ?: "DEMO", Tone.Danger)
            if (p.sample) StatusBadge("SAMPLE: created by the Vowed team so there is always something to try", Tone.Warning)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusBadge(trustLabel(p.trustTier), trustTone(p.trustTier))
                if (p.tokenIsTest) StatusBadge("test token, no real value", Tone.Neutral)
            }
            Text("Token: ${p.tokenSymbol} · Pot ${fmt(p.totalDeposits)} · ${p.participantCount} of ${p.maxParticipants} joined", style = MaterialTheme.typography.bodySmall)
            Text("Join within ${timeLeft(p.joinDeadlineTs - now)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    p.createdByYou -> OutlinedButton(onClick = { onOpen(p.pool) }) { Text("Yours: open") }
                    p.joined -> OutlinedButton(onClick = { onOpen(p.pool) }) { Text("You joined: open") }
                    else -> SmallButton("Join", { onOpen(p.pool) })
                }
                if (!p.createdByYou && !p.sample) TextButton(onClick = onReport) { Text("Report") }
            }
        }
    }
}

/** The categories grid: gradient tiles; tapping one shows Explore filtered to that kind of goal. */
@Composable
fun CategoriesScreen(onBack: () -> Unit, onPick: (String) -> Unit) {
    val tiles = listOf(
        Triple("study", "Study", "reading, courses"), Triple("fitness", "Fitness", "reps, the gym"), Triple("steps", "Steps", "walking, running"), Triple("sleep", "Sleep", "bed and wake times"),
        Triple("detox", "Screen time", "less phone"), Triple("study", "Focus", "timers, deep work"), Triple("location", "Places", "the gym, the library"), Triple("custom", "Custom", "you confirm it"),
    )
    Page("Categories", onBack = onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { (cat, label, sub) ->
                        val tint = if (label == "Focus") "focus" else cat
                        AppCard(onClick = { onPick(cat) }, Modifier.weight(1f)) {
                            Column(Modifier.fillMaxWidth().background(Brush.linearGradient(CategoryStyle.colors(tint))).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                GlyphIcon(categoryGlyph(tint), Color.White, 34.dp)
                                Spacer(Modifier.height(18.dp))
                                Text(label, style = MaterialTheme.typography.titleMedium, color = Color.White)
                                Text(sub, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.9f))
                            }
                        }
                    }
                }
            }
        }
    }
}
