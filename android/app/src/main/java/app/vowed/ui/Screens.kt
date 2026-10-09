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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.statusBarsPadding
import app.vowed.ui.components.AppCard
import app.vowed.ui.theme.VowedTheme
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

fun short(addr: String) = if (addr.length > 10) addr.take(4) + "…" + addr.takeLast(4) else addr

fun fmt(baseUnits: String): String = runCatching { TxChecker.formatUnits(BigInteger(baseUnits)) }.getOrDefault(baseUnits)

@Composable
fun DemoBadge(label: String?) {
    // the honesty label stays word for word; only its look changed
    app.vowed.ui.components.LabelChip("DEMO", app.vowed.ui.components.ChipKind.Demo, moreTitle = "Demo pool", more = label ?: "DEMO POOL: minutes-long days, test money only")
}

@Composable
fun Page(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onBack != null) {
                    Box(
                        Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.surface).clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) { Text("‹", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary) }
                }
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            }
            actions()
        }
        content()
    }
}

// ---------------------------------------------------------------- onboarding

/** A different handful each time the app starts: one idea from each of several kinds of goal, so no single kind is the face of the app. */
private val ONBOARDING_POOL = listOf(
    "Reading" to listOf("read for 30 minutes every day for 2 weeks", "study for 45 minutes a day for 10 days"),
    "Steps" to listOf("walk 8000 steps a day for a week", "hit 10000 steps every day for 5 days"),
    "Wake-up" to listOf("be up and moving by 7am on weekdays", "get out of bed before 6:30 every day for a week"),
    "Screen time" to listOf("keep Instagram under 30 minutes a day for a week", "no TikTok after 10pm for 10 days"),
    "Focus" to listOf("do a 25 minute focus session every day for a week", "deep work for 1 hour a day for 5 days"),
    "Gym" to listOf("go to the gym 4 times a week for a month", "be at the climbing wall 3 days this week"),
    "Sleep" to listOf("sleep 7 hours a night for a week", "be in bed by 11pm for 10 days"),
    "Self-report" to listOf("cook dinner at home every day for a week", "write in my journal each night for 10 days"),
    "Fitness" to listOf("do 20 squats a day for a week", "do 15 push-ups every day for 2 weeks"),
)

fun onboardingIdeas(seed: Long = System.currentTimeMillis()): List<Pair<String, String>> {
    val rnd = java.util.Random(seed)
    // camera-based fitness is one of nine kinds, and is only shown when the shuffle happens to put it in the first four
    return ONBOARDING_POOL.shuffled(rnd).take(4).map { (label, texts) -> label to texts[rnd.nextInt(texts.size)] }
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

fun planTitle(c: Challenge): String = HomeFilter.displayTitle(c)

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
fun NewGoalScreen(state: UiState, onBack: () -> Unit, onLoadTemplates: () -> Unit, onLoadExamples: () -> Unit, onParse: (String) -> Unit, onUseAi: (Boolean) -> Unit, onAlternative: (app.vowed.data.PlanOption) -> Unit, onClear: () -> Unit, onTextConsumed: () -> Unit, onStart: (app.vowed.goals.Edit, String, BigInteger, Boolean, Int, Pair<Double, Double>?, String, String?) -> Unit) {
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { onLoadTemplates(); onLoadExamples() }
    val goal = state.goal
    val result = goal.result
    var text by remember { mutableStateOf("") }
    // a tapped quick challenge or example arrives as ready-typed text
    androidx.compose.runtime.LaunchedEffect(state.pendingGoalText) {
        state.pendingGoalText?.let { text = it; onParse(it); onTextConsumed() }
    }
    val hint = remember(goal.examples) { goal.examples.firstOrNull()?.text ?: "read for 30 minutes every day for a week" }
    Page("New challenge", onBack = { if (result != null) onClear() else onBack() }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (result == null || result.status != "plan") {
                // ---------------------------------------------------------------- 1. say what you want
                Text("What do you want to commit to?", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = text, onValueChange = { if (it.length <= 300) text = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Your goal, in your own words") }, supportingText = { Text("For example: $hint") }, minLines = 2,
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
                app.vowed.ui.components.PrimaryButton("Preview plan", { onParse(text) }, enabled = text.trim().length >= 3 && !goal.parsing)
                if (goal.parsing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { app.vowed.ui.components.Spinner(size = 24.dp); Text("Working out a plan…") }
                goal.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                if (result != null && result.status == "unverifiable") {
                    AppCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("A phone cannot check this goal", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                            result.reason?.let { Text(it) }
                            result.suggestedAlternative?.let { Text("Closest checkable version: $it") }
                            result.alternatives.forEach { o -> OutlinedButton(onClick = { onAlternative(o) }, modifier = Modifier.fillMaxWidth()) { Text(o.label) } }
                        }
                    }
                }
                if (result != null && result.status == "unclear") {
                    AppCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("I need a little more", style = MaterialTheme.typography.titleMedium)
                            result.clarifyingQuestions.forEach { Text(it) }
                            result.reason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                result?.ai?.note?.let { if (result.status != "plan") Text("AI note: $it", style = MaterialTheme.typography.bodySmall) }

                // a rotating mix from the server (every kind of goal, camera reps only now and then); the built-in list is the offline fallback
                val shown: List<Pair<String, String>> = when {
                    result != null && result.examples.isNotEmpty() -> result.examples.map { "" to it }
                    goal.examples.isNotEmpty() -> goal.examples.map { categoryLabel(it.category) to it.text }
                    else -> goal.templates.map { categoryLabel(it.category) to it.example }
                }
                if (shown.isNotEmpty()) {
                    Text("Or start from an example", style = MaterialTheme.typography.titleSmall)
                    shown.forEach { (label, ex) ->
                        FilterChip(selected = false, onClick = { text = ex; onParse(ex) }, label = { Text(if (label.isEmpty()) ex else "$label: $ex") })
                    }
                    OutlinedButton(onClick = onLoadExamples) { Text("Show other ideas") }
                }
            } else {
                PlanPreview(result, ctx, state, onStart)
            }
        }
    }
}

@Composable
private fun PlanPreview(result: app.vowed.data.ParseResult, ctx: android.content.Context, state: UiState, onStart: (app.vowed.goals.Edit, String, BigInteger, Boolean, Int, Pair<Double, Double>?, String, String?) -> Unit) {
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
    var visibility by remember { mutableStateOf("private") }
    val squadId = state.newGoalSquadId

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

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Your plan", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                app.vowed.ui.components.LabelChip(app.vowed.ui.components.trustLabel(result.trustTier), app.vowed.ui.components.ChipKind.Neutral)
                app.vowed.ui.components.LabelChip("TEST USDC", app.vowed.ui.components.ChipKind.Test, moreTitle = "Test tokens", more = "TEST TOKENS: tUSDC and tSKR exist only on Solana devnet and have no real value.")
            }
            Text(sourceLabel(result), style = MaterialTheme.typography.labelMedium, color = if (result.source == "ai" || result.source == "cache") VowedTheme.extra.warning else MaterialTheme.colorScheme.primary)
            Text("How it is proved: ${proofLabel(type)}", fontWeight = FontWeight.Bold)
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
                listOf(60 to "1 min", 120 to "2 min", 300 to "5 min", 600 to "10 min").forEach { (secs, label) ->
                    FilterChip(selected = daySecs == secs, onClick = { daySecs = secs }, label = { Text("$label days") })
                }
            }
            Text("A demo pool asks for a small amount (for example 20 seconds of focus) so a short day can be completed. Test money only.", style = MaterialTheme.typography.bodySmall)
        }
    }
    Text("Who can see it", style = MaterialTheme.typography.titleMedium)
    if (squadId != null) {
        Text("This is a squad challenge, so it is private: only people with your squad's invite can find it.", style = MaterialTheme.typography.bodySmall)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = visibility == "private", onClick = { visibility = "private" }, label = { Text("Private") })
            FilterChip(selected = visibility == "public", onClick = { visibility = "public" }, label = { Text("Public (listed in Explore)") })
        }
        Text(
            if (visibility == "public") "Anyone can find this in Explore and join it. They see the goal name, kind, mode, token, stake and who joined by wallet address; nothing else about you."
            else "Private: only people you send the link or code to can join.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    OutlinedTextField(value = stake, onValueChange = { stake = it }, label = { Text("Stake (test USDC)") }, singleLine = true)
    if (overCap) Text("Above the limit of ${fmt(tierCap.toString())} for this kind of goal in this kind of pool.", color = MaterialTheme.colorScheme.error)
    Text("Test tokens on Solana devnet. No real money. Need some? Use \"Get test tokens\" on the Today screen.", style = MaterialTheme.typography.bodySmall)
    app.vowed.ui.components.PrimaryButton(
        "Review",
        {
            val edit = app.vowed.goals.Edit(title = title, value = amountN, totalDays = totalN, requiredDays = requiredN, app = appName.takeIf { result.needsApp }, place = placeName.takeIf { result.needsPlace })
            onStart(edit, mode, stakeUnits!!, demo && demoOn, daySecs, place, if (squadId != null) "private" else visibility, squadId)
        },
        enabled = valid && state.flow !is TxFlow.Working,
    )
    if (result.needsPlace && place == null) Text("Save the spot first.", style = MaterialTheme.typography.bodySmall)
    FlowStatus(state.flow)
}

@Composable
fun FlowStatus(flow: TxFlow) {
    when (flow) {
        is TxFlow.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            app.vowed.ui.components.Spinner(size = 24.dp); Text(flow.message)
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
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tx.review.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    Text("This app decoded the transaction itself and re-derived every address. It matches what you chose.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    tx.review.lines.forEach { (k, v) ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(k, Modifier.weight(0.4f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(v, Modifier.weight(0.6f), fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                    }
                }
            }
            app.vowed.ui.components.PrimaryButton("Sign with wallet", onSign)
            app.vowed.ui.components.SoftButton("Cancel", onCancel)
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
            if (state.detailError != null) Text(state.detailError, color = MaterialTheme.colorScheme.error) else app.vowed.ui.components.Spinner()
            return@Page
        }
        val c: Challenge = d.challenge
        val me = d.participants.firstOrNull { it.wallet == myWallet }
        val dv = if (me != null) CheckInLogic.dayView(c, me, now) else null
        val cat = planCategory(c)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // hero header
            app.vowed.ui.components.CategoryBackdrop(cat, Modifier.fillMaxWidth().height(168.dp).clip(MaterialTheme.shapes.large)) {
                Column(Modifier.align(Alignment.BottomStart).padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(planTitle(c), style = MaterialTheme.typography.headlineSmall, color = androidx.compose.ui.graphics.Color.White)
                    Text("Status: ${c.status} · ${c.mode} mode", style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.92f))
                }
            }
            if (c.isDemo) DemoBadge(c.demoLabel)
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${c.durationDays} days, ${c.requiredDays} needed · day length ${if (c.daySecs >= 3600) "${c.daySecs / 3600} h" else "${c.daySecs} s"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Pot ${fmt(c.totalDeposits)} · forfeited ${fmt(c.totalForfeit)} test USDC", style = MaterialTheme.typography.titleSmall)
                    val sim = SimulatedYield.estimateTokens(c.totalDeposits, c.durationDays)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        app.vowed.ui.components.LabelChip(
                            "SIMULATED yield", app.vowed.ui.components.ChipKind.Simulated, moreTitle = "Simulated yield",
                            more = "Yield: SIMULATED, about ${"%.4f".format(sim)} tokens at an assumed ${SimulatedYield.ASSUMED_APY_PERCENT.toInt()}% a year. Not earned, not paid.",
                        )
                        c.trustTier?.let { app.vowed.ui.components.LabelChip(app.vowed.ui.components.trustLabel(it), app.vowed.ui.components.ChipKind.Neutral) }
                    }
                }
            }
            if (me != null) {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Your days", style = MaterialTheme.typography.titleMedium)
                        DayDots(dv!!.doneBits, if (c.status == "Open") dv.day else c.durationDays, c.durationDays)
                        Text("Streak ${dv.streak} · ${me.daysCompleted} of ${c.requiredDays} needed days done", style = MaterialTheme.typography.bodyMedium)
                        if (c.status == "Open") {
                            if (dv.done) app.vowed.ui.components.StatusBadge("Today is recorded.", app.vowed.ui.components.Tone.Success)
                            else if (dv.dayOpen) app.vowed.ui.components.PrimaryButton("Check in for today", onCheckIn)
                            else Text("Check-ins are not open right now.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text("Players", style = MaterialTheme.typography.titleMedium)
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    d.participants.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            app.vowed.ui.components.Avatar(p.wallet, 40.dp)
                            Column(Modifier.weight(1f)) {
                                Text(if (p.wallet == myWallet) "You" else short(p.wallet), style = MaterialTheme.typography.titleSmall)
                                Text("stake ${fmt(p.stake)} · ${p.daysCompleted} days done · ${p.status}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            val canJoin = !d.me.joined && c.status == "Open"
            if (canJoin) {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = stake, onValueChange = { stake = it }, label = { Text("Stake (test USDC)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        val units = runCatching { BigDecimal(stake).movePointRight(6).toBigIntegerExact() }.getOrNull()
                        app.vowed.ui.components.PrimaryButton("Join with this stake", { onJoin(units.toString()) }, enabled = units != null && units.signum() > 0 && state.flow !is TxFlow.Working)
                    }
                }
            }
            val claimable = runCatching { BigInteger(d.me.claimable) }.getOrDefault(BigInteger.ZERO)
            if (d.me.joined && claimable.signum() > 0) {
                app.vowed.ui.components.PrimaryButton("Claim ${fmt(d.me.claimable)} test USDC", onClaim)
            } else if (d.me.joined && c.status != "Open") {
                Text(if (c.status == "Settled") "Nothing left to claim." else "Settling soon. Payouts are claimable once the challenge settles.", style = MaterialTheme.typography.bodyMedium)
            }
            FlowStatus(state.flow)
        }
    }
}

// ---------------------------------------------------------------- settings

@Composable
private fun HubRow(title: String, subtitle: String, glyph: app.vowed.ui.art.Glyph, colors: List<androidx.compose.ui.graphics.Color>, onClick: () -> Unit) {
    AppCard(onClick = onClick, Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            app.vowed.ui.components.GradientTile(colors, Modifier.size(46.dp)) { app.vowed.ui.art.GlyphIcon(glyph, androidx.compose.ui.graphics.Color.White, 26.dp) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            app.vowed.ui.art.GlyphIcon(app.vowed.ui.art.Glyph.Chevron, MaterialTheme.colorScheme.onSurfaceVariant, 22.dp)
        }
    }
}

@Composable
private fun AvatarPicker(wallet: String?) {
    val ctx = LocalContext.current
    val prefs = remember { app.vowed.data.Prefs(ctx) }
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your avatar", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                // automatic: the one chosen from your address
                Box(
                    Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (app.vowed.ui.components.Avatars.mineIndex < 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer)
                        .clickable { prefs.avatarIndex = -1; app.vowed.ui.components.Avatars.mineIndex = -1 },
                    contentAlignment = Alignment.Center,
                ) { Text("Auto", style = MaterialTheme.typography.labelMedium, color = if (app.vowed.ui.components.Avatars.mineIndex < 0) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.primary) }
                app.vowed.ui.components.Avatars.all.forEachIndexed { i, res ->
                    val selected = app.vowed.ui.components.Avatars.mineIndex == i
                    Box(
                        Modifier.size(56.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent).padding(3.dp)
                            .clickable { prefs.avatarIndex = i; app.vowed.ui.components.Avatars.mineIndex = i },
                    ) { androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(res), contentDescription = "Avatar ${i + 1}", modifier = Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.CircleShape)) }
                }
            }
            if (wallet == null) Text("Connect a wallet to keep your choice with your account on this phone.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun SettingsScreen(
    state: UiState, backendUrl: String, onBack: () -> Unit, onPractice: () -> Unit, onCoach: () -> Unit, onRewards: () -> Unit, onLetters: () -> Unit, onDisconnect: () -> Unit,
    onHideFromBoard: (Boolean) -> Unit = {}, onLoadBoard: () -> Unit = {}, onSampleProvider: () -> Unit = {},
    onResetConnection: () -> Unit = {}, onWalletHelp: () -> Unit = {}, onHidePastPools: () -> Unit = {}, onShowHiddenPools: () -> Unit = {},
    onWalletSetup: () -> Unit = {}, onCheckConnection: () -> Unit = {},
) {
    val ctx = LocalContext.current
    LaunchedEffect(state.signedIn) { if (state.signedIn) onLoadBoard() }
    Page("You") {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    app.vowed.ui.components.Avatar(state.account?.wallet ?: "?", 56.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(state.account?.wallet?.let(::short) ?: "not connected", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            app.vowed.ui.components.StatusBadge("Network: ${state.meta?.network ?: "devnet"} (test tokens only)", app.vowed.ui.components.Tone.Warning)
                        }
                        Text("Proof key trust cap: ${state.account?.trustCap ?: "-"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            AvatarPicker(state.account?.wallet)
            HubRow("Coach", "What to try next, from your own history", app.vowed.ui.art.Glyph.Focus, app.vowed.ui.components.CategoryStyle.colors("study"), onCoach)
            AppCard(onClick = onRewards, Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    app.vowed.ui.components.SkrIcon(46.dp)
                    Column(Modifier.weight(1f)) {
                        Text("SKR rewards and streak freezes", style = MaterialTheme.typography.titleSmall)
                        Text("Weekly top streaks, test SKR", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    app.vowed.ui.art.GlyphIcon(app.vowed.ui.art.Glyph.Chevron, MaterialTheme.colorScheme.onSurfaceVariant, 22.dp)
                }
            }
            HubRow("Letters to future me", "Written now, delivered later, kept on this phone", app.vowed.ui.art.Glyph.Bell, app.vowed.ui.components.CategoryStyle.colors("fitness"), onLetters)
            HubRow("Practice check-in", "Camera practice (nothing is sent)", app.vowed.ui.art.Glyph.Fitness, app.vowed.ui.components.CategoryStyle.colors("steps"), onPractice)
            HubRow("Home screen widget", "Add the Vowed widget to my home screen", app.vowed.ui.art.Glyph.Home, app.vowed.ui.components.CategoryStyle.colors("detox")) { app.vowed.widget.requestPinWidget(ctx) }
            // privacy: other people see only an avatar, a short name and a streak number; this switch removes even that
            val hidden = state.rewardsUi.week?.me?.hidden ?: state.rewardsUi.all?.me?.hidden
            AppCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Hide me from the leaderboard", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Others see only an avatar, a short name and a streak number. Turn this on to not appear at all. You are still ranked and still paid.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = hidden == true, onCheckedChange = onHideFromBoard, enabled = hidden != null && !state.rewardsUi.savingHidden)
                }
            }
            HubRow("Wallet setup help", "Switch your wallet to the practice network", app.vowed.ui.art.Glyph.Home, app.vowed.ui.components.CategoryStyle.colors("detox"), onWalletSetup)
            HubRow("Using a real wallet", "Throwaway wallet, devnet, never share a seed phrase", app.vowed.ui.art.Glyph.User, app.vowed.ui.components.CategoryStyle.colors("sleep"), onWalletHelp)
            LabsSection(onSampleProvider)
            Text("Backend: $backendUrl", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (app.vowed.BuildConfig.DEBUG) {
                // debug builds only: the release app always uses its built-in address
                val prefs = remember { app.vowed.data.Prefs(ctx) }
                var shown by remember { mutableStateOf(prefs.backendUrl) }
                AppCard(Modifier.fillMaxWidth(), container = VowedTheme.extra.demoTint) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Debug: backend address (restart the app after changing)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            app.vowed.ui.components.SoftButton("Hosted", { prefs.backendUrl = "https://vowed-backend.onrender.com"; shown = prefs.backendUrl }, Modifier.weight(1f))
                            app.vowed.ui.components.SoftButton("Local (emulator)", { prefs.backendUrl = "http://10.0.2.2:8787"; shown = prefs.backendUrl }, Modifier.weight(1f))
                        }
                        Text("Now: $shown", style = MaterialTheme.typography.bodySmall)
                        Text("Hidden test pools on this phone: ${state.hiddenPools.size} (nothing on chain or the server changes)", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            app.vowed.ui.components.SoftButton("Hide past pools", onHidePastPools, Modifier.weight(1f))
                            app.vowed.ui.components.SoftButton("Show hidden", onShowHiddenPools, Modifier.weight(1f))
                        }
                    }
                }
            }
            ConnectionCheckCard(state.connectionCheck, onCheckConnection)
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Connection trouble?", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Reset connection forgets the saved sign-in and the wallet permission, so the next Sign in starts clean. Your challenges, your stakes and the proof key on this phone are not touched.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    app.vowed.ui.components.SoftButton("Reset connection", onResetConnection, Modifier.fillMaxWidth())
                }
            }
            app.vowed.ui.components.SoftButton("Disconnect wallet", onDisconnect)
        }
    }
}

/** Experiments that are not part of the main flow. Collapsed until opened. */
@Composable
private fun LabsSection(onSampleProvider: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Labs", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                app.vowed.ui.components.LabelChip("SAMPLE", app.vowed.ui.components.ChipKind.Sample)
                Text(if (open) "Hide" else "Show", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (open) {
                Text("Open proof plug-ins (sample)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Other apps can sign a statement about what you did and hand it to Vowed (the format is in docs/proof-provider-spec.md). Only a built-in sample provider exists today; no outside app is connected. A statement is recorded with LOW trust and does not count as a check-in yet. Needs a devnet server with the sample provider switched on.",
                    style = MaterialTheme.typography.bodySmall,
                )
                app.vowed.ui.components.SoftButton("Send a signed sample statement", onSampleProvider, Modifier.fillMaxWidth())
            }
        }
    }
}

/** What to type in the new-challenge box when the coach suggests a kind of goal; the plan screen lets the person set the size. */
fun coachStarterText(category: String): String = when (category) {
    "study" -> "study for 30 minutes every day for a week"
    "steps" -> "walk 6000 steps a day for a week"
    "fitness" -> "do 20 squats a day for a week"
    "detox" -> "keep Instagram under 30 minutes a day for a week"
    "sleep" -> "sleep 7 hours a night for a week"
    "location" -> "go to the gym 3 times a week for a month"
    else -> "read for 20 minutes every day for a week"
}
