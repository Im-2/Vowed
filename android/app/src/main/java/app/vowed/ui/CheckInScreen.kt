package app.vowed.ui

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.vowed.CheckInState
import app.vowed.Submission
import app.vowed.data.ProofTarget
import app.vowed.debug.DebugProofs
import app.vowed.proof.Collected
import app.vowed.proof.DwellTracker
import app.vowed.proof.FocusTimer
import app.vowed.proof.LocationProbe
import app.vowed.proof.ProofKind
import app.vowed.proof.ProofMath
import app.vowed.proof.StepProbe
import app.vowed.proof.UsageProbe
import app.vowed.proof.hasPermission
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private fun nowSec() = System.currentTimeMillis() / 1000

fun describeTarget(kind: ProofKind, t: ProofTarget): String {
    val v = if (t.value % 1.0 == 0.0) t.value.toLong().toString() else t.value.toString()
    return when (kind) {
        ProofKind.SELF_ATTEST -> "Confirm that you did it"
        ProofKind.STEPS -> "At least $v steps"
        ProofKind.FOCUS_TIMER -> "At least $v ${t.unit} of focus"
        ProofKind.GEOFENCE -> "Stay at the place for at least $v ${t.unit}"
        ProofKind.USAGE_LIMIT -> "At most $v ${t.unit} on the app"
        ProofKind.NO_USE_WINDOW -> "No use of the app in the window"
        ProofKind.CAMERA_POSE -> "At least $v reps on camera"
    }
}

fun trustLine(kind: ProofKind) = when (kind.trust) {
    "high" -> "High trust"
    "medium" -> "Medium trust"
    else -> "Low trust (self-reported)"
}

@Composable
fun CheckInScreen(
    ci: CheckInState,
    onBack: () -> Unit,
    onSubmit: (Collected) -> Unit,
    onInject: (Boolean) -> Unit,
    onReplay: () -> Unit,
    stepBaseline: (String, Int) -> Long?,
    saveStepBaseline: (String, Int, Long) -> Unit,
    watchedApp: (String) -> String?,
) {
    Page("Check in", onBack = onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ci.challenge.isDemo) DemoBadge(ci.challenge.demoLabel)
            Text(ci.title, style = MaterialTheme.typography.titleLarge)
            Text("Day ${ci.day + 1} of ${ci.challenge.durationDays} · ${describeTarget(ci.kind, ci.target)}")
            Text(trustLine(ci.kind), style = MaterialTheme.typography.bodySmall)
            val sub = ci.submission
            when {
                sub is Submission.Accepted -> AcceptedAppCard(sub)
                ci.doneAlready -> Text("Today is already recorded. Come back tomorrow.", color = MaterialTheme.colorScheme.primary)
                !ci.dayOpen -> Text("Check-ins are not open right now for this challenge.", color = MaterialTheme.colorScheme.error)
                else -> ProofPanel(ci, sub is Submission.Working, onSubmit, stepBaseline, saveStepBaseline, watchedApp)
            }
            if (sub is Submission.Working) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(Modifier.padding(end = 4.dp)); Text("Checking your proof…") }
            if (sub is Submission.Rejected) Text(sub.reason, color = MaterialTheme.colorScheme.error)
            if (DebugProofs.ENABLED) DebugPanel(ci, sub, onInject, onReplay)
        }
    }
}

@Composable
private fun AcceptedAppCard(sub: Submission.Accepted) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Recorded", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("Streak ${sub.result.streak} · ${sub.result.daysCompleted} day(s) done · trust ${sub.result.trustTier}")
            val c = sub.result.checkin
            Text(
                when (c.status) {
                    "confirmed" -> "Written to Solana: ${c.signature?.let { short(it) } ?: ""}"
                    "pending" -> "Accepted; the on-chain record is still being confirmed."
                    else -> "Accepted, but the on-chain record failed (${c.error ?: "unknown"}). It will be retried."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DebugPanel(ci: CheckInState, sub: Submission, onInject: (Boolean) -> Unit, onReplay: () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("DEBUG BUILD: test data (not in release builds)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            Text("Fills in readings for ${ci.kind.name} so the flow can run on an emulator. The server treats it like a real proof; it is for testing only.", style = MaterialTheme.typography.bodySmall)
            val busy = sub is Submission.Working
            Button(enabled = !busy, onClick = { onInject(true) }, modifier = Modifier.fillMaxWidth()) { Text("Inject data that meets the goal") }
            OutlinedButton(enabled = !busy, onClick = { onInject(false) }, modifier = Modifier.fillMaxWidth()) { Text("Inject data that misses the goal") }
            OutlinedButton(enabled = !busy && ci.lastPackage != null, onClick = onReplay, modifier = Modifier.fillMaxWidth()) { Text("Replay the last proof") }
        }
    }
}

@Composable
private fun ProofPanel(
    ci: CheckInState,
    busy: Boolean,
    onSubmit: (Collected) -> Unit,
    stepBaseline: (String, Int) -> Long?,
    saveStepBaseline: (String, Int, Long) -> Unit,
    watchedApp: (String) -> String?,
) {
    when (ci.kind) {
        ProofKind.SELF_ATTEST -> {
            Text("Nothing on this phone can check this one, so you confirm it yourself and your squad can see you did. It is the lowest trust level, so the stake is capped low. Be honest: it is your money and your friends.")
            Button(enabled = !busy, onClick = { val n = nowSec(); onSubmit(Collected(mapOf("done" to true), n - 1, n, "self-attest tap")) }, modifier = Modifier.fillMaxWidth()) { Text("I did it") }
        }
        ProofKind.FOCUS_TIMER -> FocusPanel(ci, busy, onSubmit)
        ProofKind.STEPS -> StepsPanel(ci, busy, onSubmit, stepBaseline, saveStepBaseline)
        ProofKind.GEOFENCE -> GeofencePanel(ci, busy, onSubmit)
        ProofKind.USAGE_LIMIT, ProofKind.NO_USE_WINDOW -> UsagePanel(ci, busy, onSubmit, watchedApp)
        ProofKind.CAMERA_POSE -> PoseCheckInPanel(ci, busy, onSubmit)
    }
}

@Composable
private fun FocusPanel(ci: CheckInState, busy: Boolean, onSubmit: (Collected) -> Unit) {
    val timer = remember { FocusTimer() }
    var tick by remember { mutableLongStateOf(nowSec()) }
    var running by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { tick = nowSec(); delay(500) } }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) timer.pause(nowSec()) else if (e == Lifecycle.Event.ON_RESUME) timer.resume(nowSec())
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val focused = timer.focusedSeconds(tick)
    val needed = ProofMath.toSeconds(ci.target.value, ci.target.unit) ?: 0.0
    Text("Focused: ${formatDuration(focused)} of ${formatDuration(needed.toLong())}", style = MaterialTheme.typography.headlineSmall)
    Text("The timer stops when you leave this app and carries on when you come back.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!running) Button(onClick = { timer.start(nowSec()); running = true }) { Text(if (timer.startedAt == null) "Start" else "Resume") }
        else OutlinedButton(onClick = { timer.pause(nowSec()); running = false }) { Text("Pause") }
        val met = focused >= needed
        Button(enabled = met && !busy, onClick = {
            val end = nowSec(); timer.pause(end); running = false
            val f = timer.focusedSeconds(end)
            onSubmit(Collected(mapOf("focusedSeconds" to f), timer.startedAt ?: (end - f), end, "focus timer, foreground only"))
        }) { Text("Submit") }
    }
}

@Composable
private fun StepsPanel(ci: CheckInState, busy: Boolean, onSubmit: (Collected) -> Unit, stepBaseline: (String, Int) -> Long?, saveStepBaseline: (String, Int, Long) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ctx.hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        Text("Counting steps needs the Physical activity permission. Only the number of steps is used.")
        Button(onClick = { ask.launch(Manifest.permission.ACTIVITY_RECOGNITION) }) { Text("Allow step counting") }
        return
    }
    val probe = remember { StepProbe(ctx) }
    DisposableEffect(Unit) { probe.start(); onDispose { probe.stop() } }
    val total by probe.total.collectAsState()
    if (!probe.available) { Text("This phone has no step counter sensor.", color = MaterialTheme.colorScheme.error); return }
    val current = total
    if (current == null) { Text("Waiting for the first reading…"); return }
    var base = stepBaseline(ci.pool, ci.day)
    if (base == null || current < base) { saveStepBaseline(ci.pool, ci.day, current); base = current }
    val steps = (current - base).coerceAtLeast(0)
    Text("$steps of ${ci.target.value.toLong()} steps", style = MaterialTheme.typography.headlineSmall)
    Text("Counted since you first opened today's check-in on this phone.", style = MaterialTheme.typography.bodySmall)
    Button(enabled = steps >= ci.target.value && !busy, onClick = { val n = nowSec(); onSubmit(Collected(mapOf("steps" to steps), n - 60, n, "step counter delta")) }, modifier = Modifier.fillMaxWidth()) { Text("Submit") }
}

@Composable
private fun GeofencePanel(ci: CheckInState, busy: Boolean, onSubmit: (Collected) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ctx.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val place = ci.place
    if (place == null) { Text("The spot for this goal is saved on the phone that created it, and it is not on this one.", color = MaterialTheme.colorScheme.error); return }
    if (!granted) {
        Text("Checking that you are at the place needs your location while this screen is open. The coordinates stay on your phone; only \"inside\" and the minutes are sent.")
        Button(onClick = { ask.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("Allow location") }
        return
    }
    val radius = ci.params["radiusM"]?.toDoubleOrNull() ?: 150.0
    val probe = remember { LocationProbe(ctx) }
    val tracker = remember { DwellTracker() }
    var started by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { started = probe.start(); onDispose { probe.stop() } }
    val fix by probe.fix.collectAsState()
    var tick by remember { mutableLongStateOf(nowSec()) }
    var dist by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(fix) {
        while (true) {
            val f = fix
            val d = f?.let { LocationProbe.distanceMeters(it, place.first, place.second) }
            dist = d
            tracker.update(nowSec(), d != null && d <= radius)
            tick = nowSec()
            delay(1000)
        }
    }
    if (!started) Text("Location is switched off on this phone.", color = MaterialTheme.colorScheme.error)
    val inside = dist != null && dist!! <= radius
    Text(if (dist == null) "Looking for your location…" else if (inside) "You are at the place." else "About ${dist!!.toLong()} m away (needs to be within ${radius.toLong()} m).", style = MaterialTheme.typography.titleMedium)
    val need = ProofMath.toSeconds(ci.target.value, ci.target.unit) ?: 0.0
    Text("Time there: ${formatDuration(tracker.dwellSeconds)} of ${formatDuration(need.toLong())}")
    Button(enabled = inside && tracker.dwellSeconds >= need && !busy, onClick = {
        val n = nowSec()
        onSubmit(Collected(mapOf("inside" to true, "dwellSeconds" to tracker.dwellSeconds), tracker.startedAt ?: (n - tracker.dwellSeconds), n, "geofence dwell"))
    }, modifier = Modifier.fillMaxWidth()) { Text("Submit") }
}

@Composable
private fun UsagePanel(ci: CheckInState, busy: Boolean, onSubmit: (Collected) -> Unit, watchedApp: (String) -> String?) {
    val ctx = LocalContext.current
    val probe = remember { UsageProbe(ctx) }
    var access by remember { mutableStateOf(probe.hasAccess()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) access = probe.hasAccess() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val appName = watchedApp(ci.pool) ?: ci.params["app"] ?: "the app"
    if (!access) {
        Text("To check your use of $appName, Vowed needs Usage access (a system setting). It only reads how long the chosen app was open.")
        Button(onClick = { ctx.startActivity(probe.settingsIntent()) }) { Text("Open Usage access settings") }
        return
    }
    val packages = remember(appName) { probe.packagesNamed(appName) }
    Text("Checking: $appName" + if (packages.isEmpty()) " (not installed on this phone, so no use to count)" else "")
    val win = if (ci.kind == ProofKind.NO_USE_WINDOW) usageWindow(ci, nowSec()) else null
    if (win != null && !win.finished) Text("This goal's time window has not finished yet. Come back after it ends and check in then.", color = MaterialTheme.colorScheme.error)
    Button(enabled = !busy && (win == null || win.finished), onClick = {
        val end = nowSec()
        val (from, to) = if (ci.kind == ProofKind.NO_USE_WINDOW) usageWindow(ci, end).let { it.from to it.to } else (localMidnight(end) to end)
        val used = if (packages.isEmpty()) 0L else probe.foregroundSeconds(packages, from, to)
        val key = if (ci.kind == ProofKind.NO_USE_WINDOW) "usageSecondsInWindow" else "usageSeconds"
        onSubmit(Collected(mapOf(key to used, "packagesChecked" to (if (packages.isEmpty()) listOf(appName) else packages.toList())), from, end, "usage stats $from..$to"))
    }, modifier = Modifier.fillMaxWidth()) { Text("Check my usage and submit") }
}

private fun localMidnight(now: Long): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond().coerceAtMost(now)

/** One occurrence of the goal's daily window. [finished] is false while the window is still running, so the check-in waits for it to end. */
data class UsageWindow(val from: Long, val to: Long, val finished: Boolean)

/**
 * The most recent daily window that has started: for example 22:00 to 23:59 today, or 23:00 to 06:00 (which runs overnight, so it
 * ends on the next calendar day). Before today's window starts, yesterday's is used.
 */
fun usageWindowFor(start: LocalTime, end: LocalTime, now: Long, zone: ZoneId): UsageWindow {
    var date = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
    var from = date.atTime(start).atZone(zone).toEpochSecond()
    if (from > now) { date = date.minusDays(1); from = date.atTime(start).atZone(zone).toEpochSecond() }
    val endDate = if (!end.isAfter(start)) date.plusDays(1) else date
    val to = endDate.atTime(end).atZone(zone).toEpochSecond()
    return UsageWindow(from, minOf(now, to), now >= to)
}

private fun usageWindow(ci: CheckInState, now: Long): UsageWindow {
    val w = ci.challenge.plan?.get("window") as? kotlinx.serialization.json.JsonObject
    val s = (w?.get("startLocalTime") as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { LocalTime.parse(it) } ?: LocalTime.MIDNIGHT
    val e = (w?.get("endLocalTime") as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { LocalTime.parse(it) } ?: LocalTime.of(23, 59)
    return usageWindowFor(s, e, now, ZoneId.systemDefault())
}

fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
