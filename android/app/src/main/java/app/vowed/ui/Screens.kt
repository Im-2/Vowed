package app.vowed.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vowed.GoalDraft
import app.vowed.PendingTx
import app.vowed.TxFlow
import app.vowed.UiState
import app.vowed.core.TxChecker
import app.vowed.data.Challenge
import app.vowed.data.ConnectStep
import app.vowed.goals.GoalTemplate
import app.vowed.goals.Templates
import java.math.BigDecimal
import java.math.BigInteger

private val Green = androidx.compose.ui.graphics.Color(0xFF1B7F5C)
private val LightColors: ColorScheme = lightColorScheme(primary = Green)
private val DarkColors: ColorScheme = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF5FD3A5))

@Composable
fun VowedTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

fun short(addr: String) = if (addr.length > 10) addr.take(4) + "…" + addr.takeLast(4) else addr

fun fmt(baseUnits: String): String = runCatching { TxChecker.formatUnits(BigInteger(baseUnits)) }.getOrDefault(baseUnits)

@Composable
fun DemoBadge(label: String?) {
    Card {
        Text(
            label ?: "DEMO POOL: minutes-long days, test money only",
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
fun Page(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) TextButton(onClick = onBack) { Text("Back") }
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            actions()
        }
        content()
    }
}

// ---------------------------------------------------------------- onboarding

@Composable
fun OnboardingScreen(state: UiState, onConnect: () -> Unit, onDismissError: () -> Unit) {
    val page = remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Vowed", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        when (page.intValue) {
            0 -> {
                Text("Put money behind your goal.", style = MaterialTheme.typography.titleLarge)
                Text("Pick a goal, stake a little, prove it each day with your phone. Finish and you get your stake back. Miss days and the missed part goes to the people who showed up.")
                Button(onClick = { page.intValue = 1 }, Modifier.fillMaxWidth()) { Text("Next") }
            }
            1 -> {
                Text("Your wallet stays in charge.", style = MaterialTheme.typography.titleLarge)
                Text("Vowed never holds a key. Every stake is approved in your Solana wallet app, and the app checks each transaction on your phone before it asks the wallet to sign.")
                Text("This build runs on Solana devnet with test tokens only.", color = MaterialTheme.colorScheme.error)
                Button(onClick = { page.intValue = 2 }, Modifier.fillMaxWidth()) { Text("Next") }
            }
            else -> {
                Text("Connect a wallet", style = MaterialTheme.typography.titleLarge)
                Text("You will approve a sign-in message and register this phone's proof key. No funds move.")
                val step = state.connecting
                if (step != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.height(24.dp))
                        Text(
                            when (step) {
                                ConnectStep.Wallet -> "Waiting for your wallet…"
                                ConnectStep.SigningIn -> "Signing in…"
                                ConnectStep.RegisteringDevice -> "Registering this phone…"
                                ConnectStep.Done -> "Done"
                            },
                        )
                    }
                } else {
                    Button(onClick = onConnect, Modifier.fillMaxWidth()) { Text("Connect wallet") }
                }
                state.connectError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onDismissError) { Text("Dismiss") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- home

@Composable
fun HomeScreen(state: UiState, onNew: () -> Unit, onOpen: (String) -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit, onSignIn: () -> Unit) {
    Page("My challenges", actions = { TextButton(onClick = onSettings) { Text("Settings") } }) {
        if (state.account == null || !state.signedIn) {
            Text("Sign in with your wallet to see your challenges.")
            Button(onClick = onSignIn) { Text("Sign in") }
            state.connectError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.connecting != null) CircularProgressIndicator()
            return@Page
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onNew) { Text("New challenge") }
            OutlinedButton(onClick = onRefresh) { Text("Refresh") }
        }
        Text("Daily proofs are not in this build yet: a joined challenge counts every day as missed, so Soft-mode challenges refund only the part that is not penalised.", style = MaterialTheme.typography.bodySmall)
        state.listError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.loadingList) CircularProgressIndicator()
        if (state.challenges.isEmpty() && !state.loadingList) Text("Nothing yet. Start a challenge.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.challenges, key = { it.pool }) { c ->
                Card(onClick = { onOpen(c.pool) }, Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (c.isDemo) DemoBadge(c.demoLabel)
                        Text(c.plan?.get("title")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "Challenge ${short(c.pool)}", fontWeight = FontWeight.SemiBold)
                        Text("${c.status} · ${c.mode} · ${c.durationDays} days (${c.requiredDays} needed) · ${c.participantCount} joined")
                        Text("Pot ${fmt(c.totalDeposits)} test USDC", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- new goal

@Composable
fun NewGoalScreen(state: UiState, onBack: () -> Unit, onStart: (GoalDraft) -> Unit) {
    var template by remember { mutableStateOf<GoalTemplate>(Templates.all.first()) }
    var mode by remember { mutableStateOf("Soft") }
    var demo by remember { mutableStateOf(true) }
    var stake by remember { mutableStateOf("1") }
    val demoOn = state.meta?.config?.demoEnabled == true
    val stakeUnits = runCatching { BigDecimal(stake).movePointRight(6).toBigIntegerExact() }.getOrNull()
    val cap = state.meta?.config?.let { if (demo) it.demoMaxStake else it.maxStake }?.let { runCatching { BigInteger(it) }.getOrNull() }
    val overCap = stakeUnits != null && cap != null && stakeUnits > cap
    val valid = stakeUnits != null && stakeUnits.signum() > 0 && !overCap
    Page("New challenge", onBack = onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Choose a goal", style = MaterialTheme.typography.titleMedium)
            Templates.all.forEach { t ->
                FilterChip(selected = t.id == template.id, onClick = { template = t }, label = { Text(t.title) })
            }
            Text(template.summary)
            Text("${template.tier.label}: ${template.tier.explanation}", style = MaterialTheme.typography.bodySmall)
            Text("Mode", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == "Soft", onClick = { mode = "Soft" }, label = { Text("Soft") })
                FilterChip(selected = mode == "Hard", onClick = { mode = "Hard" }, label = { Text("Hard") })
            }
            Text(
                if (mode == "Soft") "Soft: if you miss days you lose only a share of your stake; the rest comes back." else "Hard: finish all required days or lose the whole stake to the finishers.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (demoOn) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = demo, onCheckedChange = { demo = it })
                    Text("DEMO pool: days last one minute")
                }
            }
            OutlinedTextField(value = stake, onValueChange = { stake = it }, label = { Text("Stake (test USDC)") }, singleLine = true)
            if (overCap) Text("Above the limit of ${fmt(cap.toString())} for this kind of pool.", color = MaterialTheme.colorScheme.error)
            Text("Test tokens on Solana devnet. No real money.", style = MaterialTheme.typography.bodySmall)
            Button(
                enabled = valid && state.flow !is TxFlow.Working,
                onClick = { onStart(GoalDraft(template, 3, 2, mode, stakeUnits!!, demo && demoOn)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Review") }
            FlowStatus(state.flow)
        }
    }
}

@Composable
fun FlowStatus(flow: TxFlow) {
    when (flow) {
        is TxFlow.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.height(24.dp)); Text(flow.message)
        }
        is TxFlow.Failed -> Text(flow.message, color = MaterialTheme.colorScheme.error)
        is TxFlow.Done -> Text(flow.message, color = MaterialTheme.colorScheme.primary)
        else -> Unit
    }
}

// ---------------------------------------------------------------- review

@Composable
fun ReviewScreen(tx: PendingTx, onSign: () -> Unit, onCancel: () -> Unit) {
    Page("Check before you sign") {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tx.review.title, style = MaterialTheme.typography.titleMedium)
            Text("This app decoded the transaction itself and re-derived every address. It matches what you chose.", style = MaterialTheme.typography.bodySmall)
            tx.review.lines.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(k, Modifier.weight(0.4f))
                    Text(v, Modifier.weight(0.6f), fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
                HorizontalDivider()
            }
            Button(onClick = onSign, Modifier.fillMaxWidth()) { Text("Sign with wallet") }
            OutlinedButton(onClick = onCancel, Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

// ---------------------------------------------------------------- detail

@Composable
fun DetailScreen(state: UiState, pool: String, onBack: () -> Unit, onLoad: () -> Unit, onJoin: (String) -> Unit, onClaim: () -> Unit) {
    LaunchedEffect(pool) { onLoad() }
    val d = state.detail?.takeIf { it.challenge.pool == pool }
    var stake by remember { mutableStateOf("1") }
    Page("Challenge", onBack = onBack, actions = { TextButton(onClick = onLoad) { Text("Refresh") } }) {
        if (d == null) {
            if (state.detailError != null) Text(state.detailError, color = MaterialTheme.colorScheme.error) else CircularProgressIndicator()
            return@Page
        }
        val c: Challenge = d.challenge
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (c.isDemo) DemoBadge(c.demoLabel)
            Text(c.plan?.get("title")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: short(c.pool), style = MaterialTheme.typography.titleLarge)
            Text("Status: ${c.status} · ${c.mode} mode")
            Text("${c.durationDays} days, ${c.requiredDays} needed · day length ${if (c.daySecs >= 3600) "${c.daySecs / 3600} h" else "${c.daySecs} s"}")
            Text("Pot ${fmt(c.totalDeposits)} · forfeited ${fmt(c.totalForfeit)} test USDC")
            c.trustTier?.let { Text("Proof trust: $it", style = MaterialTheme.typography.bodySmall) }
            Text("Players", style = MaterialTheme.typography.titleMedium)
            d.participants.forEach { p ->
                Text("${short(p.wallet)} · stake ${fmt(p.stake)} · ${p.daysCompleted} days done · ${p.status}")
            }
            Spacer(Modifier.height(8.dp))
            val canJoin = !d.me.joined && c.status == "Open"
            if (canJoin) {
                OutlinedTextField(value = stake, onValueChange = { stake = it }, label = { Text("Stake (test USDC)") }, singleLine = true)
                val units = runCatching { BigDecimal(stake).movePointRight(6).toBigIntegerExact() }.getOrNull()
                Button(enabled = units != null && units.signum() > 0 && state.flow !is TxFlow.Working, onClick = { onJoin(units.toString()) }, modifier = Modifier.fillMaxWidth()) { Text("Join with this stake") }
            }
            val claimable = runCatching { BigInteger(d.me.claimable) }.getOrDefault(BigInteger.ZERO)
            if (d.me.joined && claimable.signum() > 0) {
                Button(onClick = onClaim, Modifier.fillMaxWidth()) { Text("Claim ${fmt(d.me.claimable)} test USDC") }
            } else if (d.me.joined) {
                Text(if (c.status == "Settled") "Nothing left to claim." else "You're in. Payouts are claimable once the challenge settles.")
            }
            FlowStatus(state.flow)
        }
    }
}

// ---------------------------------------------------------------- settings

@Composable
fun SettingsScreen(state: UiState, backendUrl: String, onBack: () -> Unit, onDisconnect: () -> Unit) {
    Page("Settings", onBack = onBack) {
        Text("Wallet: ${state.account?.wallet?.let(::short) ?: "not connected"}")
        Text("Proof key trust cap: ${state.account?.trustCap ?: "-"}")
        Text("Network: ${state.meta?.network ?: "devnet"} (test tokens only)")
        Text("Backend: $backendUrl", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onDisconnect) { Text("Disconnect wallet") }
    }
}
