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
fun OnboardingScreen(state: UiState, onConnect: () -> Unit, onDismissError: () -> Unit, onPractice: () -> Unit) {
    val page = remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Vowed", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        TextButton(onClick = onPractice) { Text("Try the camera rep counter first (no wallet needed)") }
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

fun trustExplanation(tier: String?): String = when (tier) {
    "high" -> "High trust: proved by the phone's camera or app-usage data, signed by a hardware-backed key."
    "medium" -> "Medium trust: proved by phone sensors or a timer; harder to fake than a tap, easier than a camera."
    "low" -> "Low trust: you confirm it yourself. Stakes are capped very low."
    else -> ""
}

fun proofLabel(type: String): String = when (type) {
    "CAMERA_POSE" -> "The camera counts your reps"
    "STEPS" -> "Your step sensor counts steps"
    "FOCUS_TIMER" -> "An in-app timer (it stops when you leave the app)"
    "GEOFENCE" -> "Your phone checks you are at a place"
    "USAGE_LIMIT" -> "Your app-usage data (Usage access)"
    "NO_USE_WINDOW" -> "Your app-usage data during a time window"
    "SELF_ATTEST" -> "You confirm it yourself"
    else -> type
}

private fun sourceLabel(r: app.vowed.data.ParseResult) = when (r.source) {
    "template" -> "Built-in template"
    "ai" -> "Understood by AI (Gemini); check it carefully"
    "cache" -> "Understood earlier by AI (Gemini); check it carefully"
    "template-after-ai-failed" -> "Built-in template (the AI was not used)"
    else -> ""
}

private fun amountText(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

@Composable
fun NewGoalScreen(state: UiState, onBack: () -> Unit, onLoadTemplates: () -> Unit, onParse: (String) -> Unit, onUseAi: (Boolean) -> Unit, onAlternative: (app.vowed.data.PlanOption) -> Unit, onClear: () -> Unit, onStart: (app.vowed.goals.Edit, String, BigInteger, Boolean, Int, Pair<Double, Double>?) -> Unit) {
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { onLoadTemplates() }
    val goal = state.goal
    val result = goal.result
    var text by remember { mutableStateOf("") }
    Page("New challenge", onBack = { if (result != null) onClear() else onBack() }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (result == null || result.status != "plan") {
                // ---------------------------------------------------------------- 1. say what you want
                Text("What do you want to commit to?", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = text, onValueChange = { if (it.length <= 300) text = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Your goal, in your own words") }, supportingText = { Text("For example: do 20 squats every day for a week") }, minLines = 2,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = goal.useAi, onCheckedChange = onUseAi)
                    Text("Use AI for goals the templates do not understand")
                }
                Text(
                    if (goal.useAi) "Only the text you type is sent, to Google's Gemini (free tier, which Google may use to improve its products). Do not put personal details in it. Nothing else about you is sent."
                    else "AI is off: your text goes only to the Vowed server and is matched against the built-in templates.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(enabled = text.trim().length >= 3 && !goal.parsing, onClick = { onParse(text) }, modifier = Modifier.fillMaxWidth()) { Text("Preview plan") }
                if (goal.parsing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(Modifier.height(24.dp)); Text("Working out a plan…") }
                goal.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                if (result != null && result.status == "unverifiable") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("A phone cannot check this goal", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                            result.reason?.let { Text(it) }
                            result.suggestedAlternative?.let { Text("Closest checkable version: $it") }
                            result.alternatives.forEach { o -> OutlinedButton(onClick = { onAlternative(o) }, modifier = Modifier.fillMaxWidth()) { Text(o.label) } }
                        }
                    }
                }
                if (result != null && result.status == "unclear") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("I need a little more", style = MaterialTheme.typography.titleMedium)
                            result.clarifyingQuestions.forEach { Text(it) }
                            result.reason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                result?.ai?.note?.let { if (result.status != "plan") Text("AI note: $it", style = MaterialTheme.typography.bodySmall) }

                val examples = if (result != null && result.examples.isNotEmpty()) result.examples else goal.templates.map { it.example }
                if (examples.isNotEmpty()) {
                    Text("Or start from an example", style = MaterialTheme.typography.titleSmall)
                    examples.forEach { ex ->
                        FilterChip(selected = false, onClick = { text = ex; onParse(ex) }, label = { Text(ex) })
                    }
                }
            } else {
                PlanPreview(result, ctx, state, onStart)
            }
        }
    }
}

@Composable
private fun PlanPreview(result: app.vowed.data.ParseResult, ctx: android.content.Context, state: UiState, onStart: (app.vowed.goals.Edit, String, BigInteger, Boolean, Int, Pair<Double, Double>?) -> Unit) {
    val plan = result.plan!!
    val type = app.vowed.goals.PlanEdit.proofType(plan)
    var title by remember(plan) { mutableStateOf(app.vowed.goals.PlanEdit.title(plan)) }
    var amount by remember(plan) { mutableStateOf(amountText(app.vowed.goals.PlanEdit.value(plan))) }
    var total by remember(plan) { mutableStateOf(app.vowed.goals.PlanEdit.totalDays(plan).toString()) }
    var required by remember(plan) { mutableStateOf(app.vowed.goals.PlanEdit.requiredDays(plan).toString()) }
    var appName by remember(plan) { mutableStateOf(app.vowed.goals.PlanEdit.param(plan, "app") ?: "") }
    var placeName by remember(plan) { mutableStateOf(app.vowed.goals.PlanEdit.param(plan, "place") ?: "") }
    var place by remember(plan) { mutableStateOf<Pair<Double, Double>?>(null) }
    var placeMsg by remember(plan) { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf("Soft") }
    var demo by remember { mutableStateOf(true) }
    var daySecs by remember { mutableStateOf(120) }
    var stake by remember { mutableStateOf("1") }

    val cfg = state.meta?.config
    val demoOn = cfg?.demoEnabled == true
    val stakeUnits = runCatching { BigDecimal(stake).movePointRight(6).toBigIntegerExact() }.getOrNull()
    val base = cfg?.let { if (demo && demoOn) it.demoMaxStake else it.maxStake }?.let { runCatching { BigInteger(it) }.getOrNull() }
    val pct = when (result.trustTier) { "high" -> 100; "medium" -> 50; else -> 10 }
    val tierCap = base?.multiply(BigInteger.valueOf(pct.toLong()))?.divide(BigInteger.valueOf(100))
    val overCap = stakeUnits != null && tierCap != null && stakeUnits > tierCap
    val totalN = total.toIntOrNull()
    val requiredN = required.toIntOrNull()
    val daysOk = totalN != null && requiredN != null && totalN in 1..60 && requiredN in 1..totalN
    val amountN = amount.toDoubleOrNull()
    val placeOk = !result.needsPlace || place != null
    val appOk = !result.needsApp || appName.isNotBlank()
    val valid = stakeUnits != null && stakeUnits.signum() > 0 && !overCap && daysOk && amountN != null && amountN >= 0 && placeOk && appOk && title.isNotBlank()

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

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Your plan", style = MaterialTheme.typography.titleMedium)
            Text(sourceLabel(result), style = MaterialTheme.typography.labelMedium, color = if (result.source == "ai" || result.source == "cache") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text("How it is proved: ${proofLabel(type)}")
            Text(trustExplanation(result.trustTier), style = MaterialTheme.typography.bodySmall)
            plan["window"]?.let { w -> (w as? kotlinx.serialization.json.JsonObject)?.let { Text("Window: ${(it["startLocalTime"] as? kotlinx.serialization.json.JsonPrimitive)?.content} to ${(it["endLocalTime"] as? kotlinx.serialization.json.JsonPrimitive)?.content}") } }
            result.limitations.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            result.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            result.ai.note?.let { Text("AI note: $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
    Text("Edit if you like", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(value = title, onValueChange = { if (it.length <= 120) title = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (type != "NO_USE_WINDOW") OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount (${app.vowed.goals.PlanEdit.unit(plan)})") }, singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = total, onValueChange = { total = it.filter(Char::isDigit).take(2) }, label = { Text("Days") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(value = required, onValueChange = { required = it.filter(Char::isDigit).take(2) }, label = { Text("Days needed") }, singleLine = true, modifier = Modifier.weight(1f))
    }
    if (!daysOk) Text("Days needed must be between 1 and the number of days (at most 60).", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    if (result.needsApp) OutlinedTextField(value = appName, onValueChange = { appName = it }, label = { Text("App to watch") }, singleLine = true)
    if (result.needsPlace) {
        OutlinedTextField(value = placeName, onValueChange = { placeName = it }, label = { Text("Name of the place") }, singleLine = true)
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
            Text("A demo pool asks for a small amount (for example 20 seconds of focus) so a short day can be completed. Test money only.", style = MaterialTheme.typography.bodySmall)
        }
    }
    OutlinedTextField(value = stake, onValueChange = { stake = it }, label = { Text("Stake (test USDC)") }, singleLine = true)
    if (overCap) Text("Above the limit of ${fmt(tierCap.toString())} for this kind of goal in this kind of pool.", color = MaterialTheme.colorScheme.error)
    Text("Test tokens on Solana devnet. No real money. Need some? Use \"Get test tokens\" on the Today screen.", style = MaterialTheme.typography.bodySmall)
    Button(
        enabled = valid && state.flow !is TxFlow.Working,
        onClick = {
            val edit = app.vowed.goals.Edit(title = title, value = amountN, totalDays = totalN, requiredDays = requiredN, app = appName.takeIf { result.needsApp }, place = placeName.takeIf { result.needsPlace })
            onStart(edit, mode, stakeUnits!!, demo && demoOn, daySecs, place)
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Review") }
    if (result.needsPlace && place == null) Text("Save the spot first.", style = MaterialTheme.typography.bodySmall)
    FlowStatus(state.flow)
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
fun SettingsScreen(state: UiState, backendUrl: String, onBack: () -> Unit, onPractice: () -> Unit, onDisconnect: () -> Unit) {
    Page("Settings", onBack = onBack) {
        Text("Wallet: ${state.account?.wallet?.let(::short) ?: "not connected"}")
        Text("Proof key trust cap: ${state.account?.trustCap ?: "-"}")
        Text("Network: ${state.meta?.network ?: "devnet"} (test tokens only)")
        Text("Backend: $backendUrl", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onPractice) { Text("Camera practice (nothing is sent)") }
        OutlinedButton(onClick = onDisconnect) { Text("Disconnect wallet") }
    }
}
