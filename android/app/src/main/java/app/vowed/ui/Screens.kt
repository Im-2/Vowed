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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import app.vowed.CheckInLogic
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

/** One square per day: done, missed, today, or still to come. */
@Composable
fun DayDots(bits: BigInteger, today: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (d in 0 until total.coerceAtMost(30)) {
            val done = bits.testBit(d)
            val color = when {
                done -> MaterialTheme.colorScheme.primary
                d < today -> MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
                d == today -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(color))
        }
    }
}

/** The current time in unix seconds, re-read every second so "is today's check-in open" stays correct on a screen that is just sitting there. */
@Composable
fun rememberNowSeconds(): Long {
    var t by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            t = System.currentTimeMillis() / 1000
        }
    }
    return t
}

fun planTitle(c: Challenge): String = c.plan?.get("title")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "Challenge ${short(c.pool)}"

@Composable
fun HomeScreen(
    state: UiState,
    myWallet: String?,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onCheckIn: (String) -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onSignIn: () -> Unit,
    onLoadFaucet: () -> Unit,
    onClaimFaucet: () -> Unit,
) {
    val now = rememberNowSeconds()
    Page("Today", actions = { TextButton(onClick = onSettings) { Text("Settings") } }) {
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
        state.listError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        FaucetCard(state.faucet, myWallet, onLoadFaucet, onClaimFaucet)
        if (state.loadingList) CircularProgressIndicator()
        if (state.challenges.isEmpty() && !state.loadingList) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                    Text("Start a challenge: pick a goal, put a small stake behind it, and check in each day.")
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.challenges, key = { it.pool }) { c ->
                val detail = state.details[c.pool]
                val me = detail?.participants?.firstOrNull { it.wallet == myWallet }
                val dv = if (me != null) CheckInLogic.dayView(c, me, now) else null
                Card(onClick = { onOpen(c.pool) }, Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (c.isDemo) DemoBadge(c.demoLabel)
                        Text(planTitle(c), fontWeight = FontWeight.SemiBold)
                        Text("${c.status} · ${c.mode} · ${c.durationDays} days (${c.requiredDays} needed) · ${c.participantCount} joined", style = MaterialTheme.typography.bodySmall)
                        if (dv != null && c.status == "Open") {
                            DayDots(dv.doneBits, dv.day, c.durationDays)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Streak ${dv.streak}", fontWeight = FontWeight.Medium)
                                if (dv.done) Text("Today done", color = MaterialTheme.colorScheme.primary)
                                else if (dv.dayOpen) Button(onClick = { onCheckIn(c.pool) }) { Text("Check in") }
                                else Text("Not open yet", style = MaterialTheme.typography.bodySmall)
                            }
                        } else if (me != null) {
                            DayDots(java.math.BigInteger(me.checkinBitmap.ifBlank { "0" }), c.durationDays, c.durationDays)
                        }
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
    val ctx = LocalContext.current
    var template by remember { mutableStateOf<GoalTemplate>(Templates.all.first()) }
    var mode by remember { mutableStateOf("Soft") }
    var demo by remember { mutableStateOf(true) }
    var daySecs by remember { mutableStateOf(120) }
    var stake by remember { mutableStateOf("1") }
    var appName by remember(template) { mutableStateOf(template.proofParams["app"] ?: "") }
    var place by remember(template) { mutableStateOf<Pair<Double, Double>?>(null) }
    var placeMsg by remember(template) { mutableStateOf<String?>(null) }

    val demoOn = state.meta?.config?.demoEnabled == true
    val stakeUnits = runCatching { BigDecimal(stake).movePointRight(6).toBigIntegerExact() }.getOrNull()
    val cfg = state.meta?.config
    val cap = cfg?.let { if (demo) it.demoMaxStake else it.maxStake }?.let { runCatching { BigInteger(it) }.getOrNull() }
    // stakes are capped by how trustworthy the weakest proof is (backend/src/domain/plan.ts)
    val tierPct = when (template.tier) { app.vowed.goals.TrustTier.High -> 100; app.vowed.goals.TrustTier.Medium -> 50; else -> 10 }
    val tierCap = cap?.multiply(BigInteger.valueOf(tierPct.toLong()))?.divide(BigInteger.valueOf(100))
    val overCap = stakeUnits != null && tierCap != null && stakeUnits > tierCap
    val placeOk = !template.needsPlace || place != null
    val valid = stakeUnits != null && stakeUnits.signum() > 0 && !overCap && placeOk

    var wantLocation by remember { mutableStateOf(false) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) wantLocation = true else placeMsg = "Location permission was not given."
    }
    androidx.compose.runtime.LaunchedEffect(wantLocation) {
        if (!wantLocation) return@LaunchedEffect
        val probe = app.vowed.proof.LocationProbe(ctx)
        if (!probe.start()) { placeMsg = "Location is switched off on this phone."; wantLocation = false; return@LaunchedEffect }
        placeMsg = "Looking for your location…"
        var tries = 0
        while (probe.fix.value == null && tries < 30) { kotlinx.coroutines.delay(500); tries++ }
        val f = probe.fix.value
        probe.stop()
        if (f == null) placeMsg = "Could not get a location fix. Try outdoors or set a location in the emulator."
        else { place = f.latitude to f.longitude; placeMsg = "Spot saved on this phone." }
        wantLocation = false
    }

    Page("New challenge", onBack = onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Choose a goal", style = MaterialTheme.typography.titleMedium)
            Templates.all.forEach { t ->
                FilterChip(selected = t.id == template.id, onClick = { template = t }, label = { Text(t.title) })
            }
            Text(template.summary)
            Text("${template.tier.label}: ${template.tier.explanation}", style = MaterialTheme.typography.bodySmall)
            if (template.needsApp) OutlinedTextField(value = appName, onValueChange = { appName = it }, label = { Text("App to watch") }, singleLine = true)
            if (template.needsPlace) {
                OutlinedButton(onClick = {
                    if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) wantLocation = true
                    else ask.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                }) { Text(if (place == null) "Use my current location as the spot" else "Update the spot to here") }
                placeMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
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
                    Text("DEMO pool: a day lasts minutes")
                }
                if (demo) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(60 to "1 min", 120 to "2 min", 300 to "5 min").forEach { (secs, label) ->
                            FilterChip(selected = daySecs == secs, onClick = { daySecs = secs }, label = { Text("$label days") })
                        }
                    }
                    Text("Demo goals ask for a small amount (for example 20 seconds of focus) so a short day can be completed.", style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(value = stake, onValueChange = { stake = it }, label = { Text("Stake (test USDC)") }, singleLine = true)
            if (overCap) Text("Above the limit of ${fmt(tierCap.toString())} for ${template.tier.label.lowercase()} goals in this kind of pool.", color = MaterialTheme.colorScheme.error)
            Text("Test tokens on Solana devnet. No real money. Need some? Use \"Get test tokens\" on the Today screen.", style = MaterialTheme.typography.bodySmall)
            Button(
                enabled = valid && state.flow !is TxFlow.Working,
                onClick = {
                    val params = if (template.needsApp && appName.isNotBlank()) mapOf("app" to appName.trim()) else emptyMap()
                    onStart(GoalDraft(template, 3, 2, mode, stakeUnits!!, demo && demoOn, daySecs, params, place))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Review") }
            if (template.needsPlace && place == null) Text("Save the spot first.", style = MaterialTheme.typography.bodySmall)
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
fun DetailScreen(
    state: UiState,
    pool: String,
    myWallet: String?,
    onBack: () -> Unit,
    onLoad: () -> Unit,
    onJoin: (String) -> Unit,
    onClaim: () -> Unit,
    onCheckIn: () -> Unit,
) {
    LaunchedEffect(pool) { onLoad() }
    val d = state.detail?.takeIf { it.challenge.pool == pool }
    var stake by remember { mutableStateOf("1") }
    val now = rememberNowSeconds()
    Page("Challenge", onBack = onBack, actions = { TextButton(onClick = onLoad) { Text("Refresh") } }) {
        if (d == null) {
            if (state.detailError != null) Text(state.detailError, color = MaterialTheme.colorScheme.error) else CircularProgressIndicator()
            return@Page
        }
        val c: Challenge = d.challenge
        val me = d.participants.firstOrNull { it.wallet == myWallet }
        val dv = if (me != null) CheckInLogic.dayView(c, me, now) else null
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (c.isDemo) DemoBadge(c.demoLabel)
            Text(planTitle(c), style = MaterialTheme.typography.titleLarge)
            Text("Status: ${c.status} · ${c.mode} mode")
            Text("${c.durationDays} days, ${c.requiredDays} needed · day length ${if (c.daySecs >= 3600) "${c.daySecs / 3600} h" else "${c.daySecs} s"}")
            Text("Pot ${fmt(c.totalDeposits)} · forfeited ${fmt(c.totalForfeit)} test USDC")
            c.trustTier?.let { Text("Proof trust: $it", style = MaterialTheme.typography.bodySmall) }
            if (me != null) {
                Text("Your days", style = MaterialTheme.typography.titleMedium)
                DayDots(dv!!.doneBits, if (c.status == "Open") dv.day else c.durationDays, c.durationDays)
                Text("Streak ${dv.streak} · ${me.daysCompleted} of ${c.requiredDays} needed days done")
                if (c.status == "Open") {
                    if (dv.done) Text("Today is recorded.", color = MaterialTheme.colorScheme.primary)
                    else if (dv.dayOpen) Button(onClick = onCheckIn, modifier = Modifier.fillMaxWidth()) { Text("Check in for today") }
                    else Text("Check-ins are not open right now.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Players", style = MaterialTheme.typography.titleMedium)
            d.participants.forEach { p ->
                Text("${if (p.wallet == myWallet) "You" else short(p.wallet)} · stake ${fmt(p.stake)} · ${p.daysCompleted} days done · ${p.status}")
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
            } else if (d.me.joined && c.status != "Open") {
                Text(if (c.status == "Settled") "Nothing left to claim." else "Settling soon. Payouts are claimable once the challenge settles.")
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
