package app.vowed.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import app.vowed.ui.components.AppCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vowed.UiState
import app.vowed.core.TxChecker
import app.vowed.perks.FreezeOption
import app.vowed.perks.FreezeOptions
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

@Composable
fun RewardsScreen(state: UiState, myWallet: String?, onBack: () -> Unit, onLoad: () -> Unit, onFreeze: (String, Int) -> Unit, onDismissMessage: () -> Unit, onSampleProvider: () -> Unit) {
    LaunchedEffect(Unit) { onLoad() }
    val ru = state.rewardsUi
    val st = ru.status
    val now = rememberNowSeconds()
    val options: List<FreezeOption> = state.details.values.flatMap { d -> FreezeOptions.forDetail(d, myWallet, planTitle(d.challenge), now) }
    Page("SKR rewards and perks", onBack = onBack, actions = { TextButton(onClick = onLoad) { Text("Refresh") } }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            app.vowed.ui.components.LabelChip("TEST SKR", app.vowed.ui.components.ChipKind.Test, moreTitle = "Test SKR", more = st?.label ?: "TEST SKR: this build runs on Solana devnet and SKR here is a test token with no value.")
            ru.message?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = onDismissMessage) { Text("OK") }
                }
            }
            ru.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (ru.loading && st == null) CircularProgressIndicator()
            if (st != null && !st.enabled) Text("Rewards and perks are not switched on on this server.")

            if (st != null && st.enabled) {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Weekly rewards for the longest streaks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "At the end of each week the top ${st.ladder.size} streaks (at least ${st.minStreak} days, in challenges with two or more players) receive " +
                                st.ladder.joinToString(", ") { TxChecker.formatUnits(BigInteger(it)) } + " SKR in that order. Streak freezes count.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (st.includesDemoPools) {
                            app.vowed.ui.components.LabelChip("DEMO", app.vowed.ui.components.ChipKind.Demo, moreTitle = "Demo rewards", more = "DEMO: on this server, demo pools count too, so a whole week of rewards can be shown.")
                        }
                        Text("This week so far (paid after the week ends):", style = MaterialTheme.typography.labelLarge)
                        if (st.standings.isEmpty()) Text("Nobody has a streak yet.", style = MaterialTheme.typography.bodySmall)
                        st.standings.forEach { s ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                app.vowed.ui.components.Avatar(s.wallet, 36.dp)
                                Text(
                                    "${s.rank}. ${if (s.you) "You" else short(s.wallet)}: streak ${s.streak}${if (s.qualifies) " (in the rewards)" else ""}",
                                    Modifier.weight(1f), fontWeight = if (s.you) FontWeight.Bold else FontWeight.Normal,
                                )
                                if (s.qualifies) app.vowed.ui.components.StatusBadge("SKR", app.vowed.ui.components.Tone.Success)
                            }
                        }
                    }
                }
                Text("Rewards you received", style = MaterialTheme.typography.titleMedium)
                if (st.mine.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall)
                st.mine.forEach { m ->
                    Text("Week ${m.week}: rank ${m.rank}, streak ${m.streak}, ${TxChecker.formatUnits(BigInteger(m.amount))} test SKR (${m.status})", style = MaterialTheme.typography.bodyMedium)
                }

                Text("Streak freeze (1 test SKR)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A missed day breaks your streak. A freeze, paid in SKR from your wallet, keeps the streak for that one day. It never changes your check-ins, your days completed or your payout: it is only for the streak shown here, in the squad leaderboard, and for the weekly rewards.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (options.isEmpty()) {
                    Text(
                        "No missed day to cover right now (a freeze covers a day missed up to ${FreezeOptions.LOOKBACK_DAYS} days ago, at most ${FreezeOptions.MAX_PER_POOL} per challenge).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                options.forEach { o ->
                    OutlinedButton(onClick = { onFreeze(o.pool, o.dayIndex) }, modifier = Modifier.fillMaxWidth()) { Text("Freeze day ${o.dayIndex + 1} of ${o.title}") }
                }
            }
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Yield on stakes", style = MaterialTheme.typography.titleMedium)
                        app.vowed.ui.components.LabelChip("SIMULATED", app.vowed.ui.components.ChipKind.Simulated)
                    }
                    Text(
                        "Stakes are not lent out in this version, so they earn no yield. Challenge pages show an illustrative number labelled SIMULATED, using an assumed ${SimulatedYield.ASSUMED_APY_PERCENT.toInt()}% a year. Nobody is credited with it and nothing is paid.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Open proof plug-ins (sample)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Other apps can sign a statement about what you did and hand it to Vowed (the format is in docs/proof-provider-spec.md). Only a built-in sample provider exists today; no outside app is connected. A statement is recorded with LOW trust and does not count as a check-in yet. Needs a devnet server with the sample provider switched on.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = onSampleProvider, modifier = Modifier.fillMaxWidth()) { Text("Send a signed sample statement") }
                }
            }
            if (st == null && !ru.loading) Button(onClick = onLoad) { Text("Try again") }
        }
    }
}
