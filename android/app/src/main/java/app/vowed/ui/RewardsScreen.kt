package app.vowed.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vowed.UiState
import app.vowed.core.TxChecker
import app.vowed.data.Board
import app.vowed.data.BoardEntry
import app.vowed.perks.FreezeOption
import app.vowed.perks.FreezeOptions
import app.vowed.ui.art.Glyph
import app.vowed.ui.art.GlyphIcon
import app.vowed.ui.components.AppCard
import app.vowed.ui.components.Avatar
import app.vowed.ui.components.ChipKind
import app.vowed.ui.components.InfoIcon
import app.vowed.ui.components.LabelChip
import app.vowed.ui.components.PrimaryButton
import app.vowed.ui.components.SectionHeader
import app.vowed.ui.components.SkrIcon
import app.vowed.ui.components.SoftButton
import app.vowed.ui.components.Spinner
import app.vowed.ui.theme.VowedColors
import java.math.BigInteger

/** Illustrative yield only. Nothing here is earned, paid or credited to anyone; the number shows what lending a pot could look like. */
object SimulatedYield {
    const val ASSUMED_APY_PERCENT = 4.0

    /** Interest a pot of [potBaseUnits] would make at the assumed rate over [days] days, in whole tokens. */
    fun estimateTokens(potBaseUnits: String, days: Int): Double {
        val pot = runCatching { BigInteger(potBaseUnits) }.getOrDefault(BigInteger.ZERO).toDouble() / 1_000_000.0
        return pot * (ASSUMED_APY_PERCENT / 100.0) * (days / 365.0)
    }
}

private fun skr(units: String): String = runCatching { TxChecker.formatUnits(BigInteger(units)) }.getOrDefault("?")

/** "3d 4h", "4h 12m", "12m 05s": the time left until the weekly payout. */
fun countdownText(secondsLeft: Long): String {
    if (secondsLeft <= 0) return "any moment now"
    val d = secondsLeft / 86_400
    val h = (secondsLeft % 86_400) / 3_600
    val m = (secondsLeft % 3_600) / 60
    val s = secondsLeft % 60
    return when {
        d > 0 -> "${d}d ${h}h ${m}m"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m ${s.toString().padStart(2, '0')}s"
    }
}

private const val SAMPLE_EXPLAIN = "SAMPLE: rows marked SAMPLE are made-up players that show how the board looks. They are never paid and never change anyone's rank or reward."

@Composable
fun RewardsScreen(
    state: UiState,
    myWallet: String?,
    onBack: () -> Unit,
    onLoad: () -> Unit,
    onFreeze: (String, Int) -> Unit,
    onDismissMessage: () -> Unit,
    onSignIn: () -> Unit = {},
    onLoadFaucet: () -> Unit = {},
) {
    LaunchedEffect(state.signedIn) { onLoad(); if (state.signedIn) onLoadFaucet() }
    val ru = state.rewardsUi
    val st = ru.status
    val now = rememberNowSeconds()
    val options: List<FreezeOption> = state.details.values.flatMap { d -> FreezeOptions.forDetail(d, myWallet, planTitle(d.challenge), now) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Page("Rewards", onBack = onBack, actions = { TextButton(onClick = onLoad) { Text("Refresh") } }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BalanceCard(state.faucet.status?.balances?.tSKR, st?.label)

            ru.message?.let {
                AppCard(Modifier.fillMaxWidth(), container = MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onDismissMessage) { Text("OK") }
                    }
                }
            }

            when {
                ru.needsSignIn -> NoticeCard(
                    "Sign in again to see rewards",
                    "Your sign-in lasts an hour and then needs to be renewed with your wallet. Nothing is wrong with your account or your tokens.",
                    "Sign in", onSignIn,
                )
                ru.loading && st == null -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { Spinner() }
                st == null -> NoticeCard(
                    "Rewards are not available right now",
                    ru.error ?: "The server did not answer. Your tokens and streaks are safe; try again in a moment.",
                    "Refresh", onLoad,
                )
                else -> {
                    if (!st.enabled) {
                        NoticeCard("Rewards are off on this server", "Nobody is paid on this server, so only the sample leaderboard and the streak freeze preview are shown. Your streaks still count.", null, {})
                    }
                    ru.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    ThisWeek(ru.week, st.ladder, st.minStreak, st.weekEndsAt - now, st.includesDemoPools, st.enabled)
                    LeaderboardSection(tab, { tab = it }, ru.week, ru.all, onRetry = onLoad)
                    if (st.mine.isNotEmpty()) {
                        AppCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Rewards you received", style = MaterialTheme.typography.titleSmall)
                                st.mine.forEach { m ->
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SkrIcon(18.dp)
                                        Text("Week ${m.week}: rank ${m.rank}, streak ${m.streak}, ${skr(m.amount)} test SKR (${m.status})", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                    FreezeCard(options, onFreeze)
                }
            }
            SimulatedYieldCard()
        }
    }
}

@Composable
private fun BalanceCard(balanceUnits: String?, label: String?) {
    AppCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SkrIcon(52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("My SKR balance", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (balanceUnits != null) "${skr(balanceUnits)} tSKR" else "...", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            LabelChip("TEST SKR", ChipKind.Test, moreTitle = "Test SKR", more = label ?: "TEST SKR: this build runs on Solana devnet and SKR here is a test token with no value.")
        }
    }
}

@Composable
private fun NoticeCard(title: String, body: String, button: String?, onButton: () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (button != null) {
                Spacer(Modifier.height(4.dp))
                PrimaryButton(button, onButton)
            }
        }
    }
}

// ---------------------------------------------------------------- this week: countdown, podium, my rank

@Composable
private fun ThisWeek(board: Board?, ladder: List<String>, minStreak: Int, secondsLeft: Long, includesDemo: Boolean, paying: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("This week's rewards")
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        GlyphIcon(Glyph.Trophy, MaterialTheme.colorScheme.primary, 20.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Weekly payout in", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(countdownText(secondsLeft), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    InfoIcon(
                        "How the weekly rewards work",
                        "At the end of each week the top ${ladder.size} streaks (at least $minStreak days, in challenges with two or more players) receive " +
                            ladder.joinToString(", ") { skr(it) } + " test SKR in that order. Streak freezes count." +
                            if (paying) "" else " Rewards are switched off on this server, so nothing is paid here.",
                    )
                }
                if (board?.entries.orEmpty().take(3).any { it.sample }) {
                    LabelChip("SAMPLE", ChipKind.Sample, moreTitle = "Sample players", more = board?.sampleNote ?: SAMPLE_EXPLAIN)
                }
                if (includesDemo) LabelChip("DEMO", ChipKind.Demo, moreTitle = "Demo rewards", more = "DEMO: on this server, demo pools count too, so a whole week of rewards can be shown.")
                Podium(board?.entries.orEmpty().take(3), ladder)
                board?.let { MyRank(it, ladder, minStreak) }
            }
        }
    }
}

@Composable
private fun Podium(top: List<BoardEntry>, ladder: List<String>) {
    // second place on the left, first in the middle (taller), third on the right
    val order = listOf(1, 0, 2)
    val heights = listOf(104.dp, 124.dp, 96.dp)
    val medal = listOf(Color(0xFFE0A526), Color(0xFF9AA3B5), Color(0xFFC07A45))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        order.forEach { i ->
            val e = top.getOrNull(i)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (e != null) {
                    Avatar(e.wallet, if (i == 0) 62.dp else 50.dp)
                    Text(if (e.you) "You" else e.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, fontWeight = if (e.you) FontWeight.Bold else FontWeight.Normal)
                    Text("${e.streak} days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                } else {
                    Box(Modifier.size(50.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Text("?", color = MaterialTheme.colorScheme.primary) }
                    Text("open", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(
                    Modifier.fillMaxWidth().height(heights[i]).clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).background(medal[i].copy(alpha = 0.18f)),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(Modifier.padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(medal[i]), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                        ladder.getOrNull(i)?.let {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                SkrIcon(14.dp)
                                Text(skr(it), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }
                            Text("test SKR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** My own place, shown under the podium when I am not in the top three. */
@Composable
private fun MyRank(board: Board, ladder: List<String>, minStreak: Int) {
    val inTop3 = board.entries.take(3).any { it.you }
    if (inTop3) return
    val me = board.me
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(app.vowed.ui.components.Avatars.mineWallet ?: "?", 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (me.rank != null) {
                Text("You are #${me.rank} with a ${me.streak}-day streak", style = MaterialTheme.typography.titleSmall)
                val r = me.rewardIfNow
                Text(
                    if (r != null) "If the week ended now you would get ${skr(r)} test SKR." else "Reach a $minStreak-day streak and a top-${ladder.size} place to earn a reward.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text("You have no streak this week yet", style = MaterialTheme.typography.titleSmall)
                Text("Check in on a few days in a row to get on the board.", style = MaterialTheme.typography.bodySmall)
            }
            if (me.hidden) Text("You are hidden from the leaderboard (change this in You).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- leaderboard with two tabs

@Composable
private fun LeaderboardSection(tab: Int, onTab: (Int) -> Unit, week: Board?, all: Board?, onRetry: () -> Unit) {
    val board = if (tab == 0) week else all
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Leaderboard")
        Row(Modifier.fillMaxWidth().clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).padding(4.dp)) {
            listOf("This week", "All time").forEachIndexed { i, label ->
                val on = tab == i
                Box(
                    Modifier.weight(1f).clip(CircleShape).background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent).clickable { onTab(i) }.padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else MaterialTheme.colorScheme.primary) }
            }
        }
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                when {
                    board == null -> Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("The leaderboard could not be loaded", style = MaterialTheme.typography.titleSmall)
                        Text("Your streaks are safe. Pull it again in a moment.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        SoftButton("Refresh", onRetry)
                    }
                    board.entries.isEmpty() -> Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Nobody has a streak yet", style = MaterialTheme.typography.titleSmall)
                        Text("Check in a few days in a row to be the first on the board.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> board.entries.take(10).forEach { BoardRow(it, tab == 0) }
                }
            }
        }
        if (board?.hasSamples == true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabelChip("SAMPLE", ChipKind.Sample, moreTitle = "Sample players", more = board.sampleNote ?: SAMPLE_EXPLAIN)
                Text("Made-up players fill the board until real ones arrive.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BoardRow(e: BoardEntry, weekly: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(if (e.you) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("${e.rank}", Modifier.width(22.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Avatar(e.wallet, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (e.you) "You" else e.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, fontWeight = if (e.you) FontWeight.Bold else FontWeight.SemiBold)
                if (e.sample) LabelChip("SAMPLE", ChipKind.Sample, moreTitle = "Sample player", more = SAMPLE_EXPLAIN)
            }
            Text("${e.streak} day${if (e.streak == 1) "" else "s"}${if (weekly) " this week" else " best streak"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        e.reward?.let {
            Row(
                Modifier.clip(CircleShape).background(VowedColors.SuccessTint).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SkrIcon(14.dp)
                Text(skr(it), style = MaterialTheme.typography.labelLarge, color = VowedColors.Success, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---------------------------------------------------------------- streak freeze, simulated yield

@Composable
private fun FreezeCard(options: List<FreezeOption>, onFreeze: (String, Int) -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SkrIcon(36.dp)
                Column(Modifier.weight(1f)) {
                    Text("Streak freeze", style = MaterialTheme.typography.titleMedium)
                    Text("1 test SKR", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                InfoIcon(
                    "Streak freeze",
                    "A missed day breaks your streak. A freeze, paid in test SKR from your wallet, keeps the streak for that one day. It never changes your check-ins, your days completed or your payout: it is only for the streak shown here, in the squad leaderboard, and for the weekly rewards.",
                )
            }
            Text("Keeps your streak if you miss a day.", style = MaterialTheme.typography.bodyMedium)
            if (options.isEmpty()) {
                Text(
                    "No missed day to cover right now (a freeze covers a day missed up to ${FreezeOptions.LOOKBACK_DAYS} days ago, at most ${FreezeOptions.MAX_PER_POOL} per challenge).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            options.forEach { o -> SoftButton("Freeze day ${o.dayIndex + 1} of ${o.title}", { onFreeze(o.pool, o.dayIndex) }, Modifier.fillMaxWidth()) }
        }
    }
}

/** The simulated yield note: a small card that stays collapsed until tapped. The SIMULATED label is always visible. */
@Composable
private fun SimulatedYieldCard() {
    var open by remember { mutableStateOf(false) }
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Yield on stakes", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                LabelChip("SIMULATED", ChipKind.Simulated)
                Text(if (open) "Hide" else "Show", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (open) {
                Text(
                    "Stakes are not lent out in this version, so they earn no yield. Challenge pages show an illustrative number labelled SIMULATED, using an assumed ${SimulatedYield.ASSUMED_APY_PERCENT.toInt()}% a year. Nobody is credited with it and nothing is paid.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
