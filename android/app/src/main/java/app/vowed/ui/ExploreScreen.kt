package app.vowed.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.vowed.ui.components.ChipKind
import app.vowed.ui.components.EmptyState
import app.vowed.ui.components.GradientTile
import app.vowed.ui.components.LabelChip
import app.vowed.ui.components.SlimBanner
import app.vowed.ui.components.SmallButton
import app.vowed.ui.components.SoftButton
import app.vowed.ui.components.Spinner
import app.vowed.ui.components.trustLabel
import app.vowed.ui.theme.VowedColors
import app.vowed.ui.theme.VowedTheme

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

const val DEMO_MODE_TEXT = "DEMO MODE: days last a few minutes and the money is test money, so you can see a whole challenge in minutes."
const val SAMPLE_TEXT = "SAMPLE: created by the Vowed team so there is always something to try"

/** All tokens / test USDC / test SKR: filters Explore to the token a challenge is staked in. */
@Composable
private fun TokenPills(state: UiState, selectedMint: String?, onPick: (String?) -> Unit) {
    val tokens = state.faucet.status?.tokens.orEmpty()
    if (tokens.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Token", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilterPill(selectedMint == null, "All") { onPick(null) }
        tokens.forEach { t -> FilterPill(selectedMint == t.mint, t.symbol) { onPick(if (selectedMint == t.mint) null else t.mint) } }
    }
}

@Composable
private fun FilterPill(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label, style = MaterialTheme.typography.labelMedium) }, shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface, selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = Color.White),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
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
    var filterSheet by remember { mutableStateOf(false) }
    val extraFilters = (if (ex.mint != null) 1 else 0) + (if (ex.endingSoon) 1 else 0)

    Page("Explore", actions = { TextButton(onClick = onCategories) { Text("Categories") } }) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SlimBanner("DEMO MODE · quick challenges", ChipKind.Demo, "Demo mode", DEMO_MODE_TEXT)
                    if (ex.quick.isEmpty() && ex.loading) Spinner()
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ex.quick.forEach { q -> FilterPill(false, "${categoryLabel(q.category)}: ${q.text}") { onQuick(q.text) } }
                    }
                    ex.demoPools.forEach { p -> ExploreCard(p, now, onOpen) { reporting = p } }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Public challenges", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterPill(ex.category == null, "All kinds") { onFilter(null, ex.mint, ex.endingSoon) }
                            CATEGORIES.forEach { (k, label) -> FilterPill(ex.category == k, label) { onFilter(if (ex.category == k) null else k, ex.mint, ex.endingSoon) } }
                        }
                        Box(
                            Modifier.height(40.dp).clip(CircleShape).background(if (extraFilters > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                .clickable(onClickLabel = "Filters") { filterSheet = true }.padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(if (extraFilters > 0) "Filter · $extraFilters" else "Filter", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
            item { TokenPills(state, ex.mint) { onFilter(ex.category, it, ex.endingSoon) } }
            ex.message?.let { m -> item { Row(verticalAlignment = Alignment.CenterVertically) { Text(m, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary); TextButton(onClick = onDismissMessage) { Text("OK") } } } }
            ex.error?.let { e -> item { Column { Text(e, color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = onLoad) { Text("Try again") } } } }
            if (ex.loading && ex.items.isEmpty()) item { Spinner() }
            if (!ex.loading && ex.loaded && ex.items.isEmpty() && ex.error == null) {
                item { EmptyState("Nothing matches right now", "No public challenge fits these filters. Clear a filter, or start your own: you can make it public when you create it.") }
            }
            items(ex.items, key = { it.pool }) { p -> ExploreCard(p, now, onOpen) { reporting = p } }
            if (ex.nextCursor != null) item { SoftButton(if (ex.loadingMore) "Loading…" else "Show more", onMore, enabled = !ex.loadingMore) }
        }
    }

    if (filterSheet) {
        ModalBottomSheet(onDismissRequest = { filterSheet = false }, sheetState = rememberModalBottomSheetState(), containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Filters", style = MaterialTheme.typography.titleMedium)
                Text("Token", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(ex.mint == null, "Any token") { onFilter(ex.category, null, ex.endingSoon) }
                    tokenFilters(state).forEach { (sym, mint) -> FilterPill(ex.mint == mint, sym) { onFilter(ex.category, if (ex.mint == mint) null else mint, ex.endingSoon) } }
                }
                Text("Timing", style = MaterialTheme.typography.titleSmall)
                Row { FilterPill(ex.endingSoon, "Ending soon") { onFilter(ex.category, ex.mint, !ex.endingSoon) } }
                SoftButton("Done", { filterSheet = false })
            }
        }
    }

    reporting?.let { p ->
        AlertDialog(
            onDismissRequest = { reporting = null },
            title = { Text("Report this challenge") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("\"${HomeFilter.exploreTitle(p)}\"", fontWeight = FontWeight.Medium)
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
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** One layout for every challenge card: icon, title, one meta line, at most two chips, a stats row, a primary Join button, Report in a menu. */
@Composable
private fun ExploreCard(p: ExploreItem, now: Long, onOpen: (String) -> Unit, onReport: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                app.vowed.ui.components.CategoryThumb(p.category, 48.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(HomeFilter.exploreTitle(p), style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    Text("${categoryLabel(p.category)} · ${p.mode} · ${p.durationDays} days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (!p.createdByYou && !p.sample) {
                    Box {
                        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = "More options") { menu = true }, contentAlignment = Alignment.Center) {
                            Text("⋮", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Report") }, onClick = { menu = false; onReport() })
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    p.isDemo -> LabelChip("DEMO", ChipKind.Demo, moreTitle = "Demo pool", more = p.demoLabel ?: "DEMO POOL: minutes-long days, test money only")
                    p.sample -> LabelChip("SAMPLE", ChipKind.Sample, moreTitle = "Sample challenge", more = SAMPLE_TEXT)
                }
                LabelChip(
                    trustLabel(p.trustTier), ChipKind.Neutral,
                    dot = when (p.trustTier) { "high" -> VowedTheme.extra.success; "medium" -> VowedColors.Indigo; else -> VowedTheme.extra.warning },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat("Pot (${if (p.tokenIsTest) "test " else ""}${p.tokenSymbol})", fmt(p.totalDeposits), Modifier.weight(1f))
                Stat("Joined", "${p.participantCount} of ${p.maxParticipants}", Modifier.weight(1f))
                Stat("Join within", timeLeft(p.joinDeadlineTs - now), Modifier.weight(1f))
            }
            when {
                p.createdByYou -> SoftButton("Yours: open", { onOpen(p.pool) })
                p.joined -> SoftButton("You joined: open", { onOpen(p.pool) })
                else -> app.vowed.ui.components.PrimaryButton("Join", { onOpen(p.pool) })
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
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            tiles.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pair.forEach { (cat, label, sub) ->
                        val tint = if (label == "Focus") "focus" else cat
                        AppCard(onClick = { onPick(cat) }, Modifier.weight(1f)) {
                            // a real photo with a dark-to-clear scrim at the bottom and white text over it (gradient tile and icon when there is no photo)
                            app.vowed.ui.components.CategoryBackdrop(tint, Modifier.fillMaxWidth().height(156.dp)) {
                                Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(label, style = MaterialTheme.typography.titleMedium, color = Color.White)
                                    Text(sub, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.92f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
