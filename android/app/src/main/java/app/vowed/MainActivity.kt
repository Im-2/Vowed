package app.vowed

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.vowed.ui.CheckInScreen
import app.vowed.ui.DetailScreen
import app.vowed.ui.Page
import androidx.compose.material3.Text
import app.vowed.ui.HomeScreen
import app.vowed.ui.NewGoalScreen
import app.vowed.ui.OnboardingScreen
import app.vowed.ui.ReviewScreen
import app.vowed.ui.SettingsScreen
import app.vowed.ui.VowedTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.currentBackStackEntryAsState
import app.vowed.ui.ExploreScreen
import app.vowed.ui.SquadDetailScreen
import app.vowed.ui.SquadsScreen
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Must be created before the activity is STARTED.
        val sender = ActivityResultSender(this)
        handleLink(intent)
        setContent { VowedTheme { Surface(Modifier.fillMaxSize()) { Root(vm, sender) } } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** https://vowed.app/join/CODE: remember the code; the Squads screen offers to join once the user is signed in. */
    private fun handleLink(i: android.content.Intent?) {
        val d = i?.data ?: return
        if (d.host == "vowed.app" && d.pathSegments.firstOrNull() == "join") d.pathSegments.getOrNull(1)?.let { code -> vm.setPendingJoinCode(code.uppercase().take(12)) }
    }
}

@Composable
private fun Root(vm: MainViewModel, sender: ActivityResultSender) {
    val state by vm.state.collectAsState()
    val nav: NavHostController = rememberNavController()
    val start = if (vm.prefs.onboarded && state.account != null) "home" else "onboarding"

    LaunchedEffect(Unit) { vm.loadMeta() }
    // After a successful connect from onboarding, go home.
    LaunchedEffect(state.signedIn) {
        if (state.signedIn && nav.currentDestination?.route == "onboarding") nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
    }
    // A transaction that passed the on-phone check opens the review screen; a finished one returns to the pool.
    LaunchedEffect(state.flow) {
        when (val f = state.flow) {
            is TxFlow.Review -> if (nav.currentDestination?.route != "review") nav.navigate("review")
            is TxFlow.Done -> {
                vm.resetFlow()
                vm.setNewGoalSquad(null)
                nav.popBackStack("home", false)
                nav.navigate("detail/${f.pool}")
            }
            else -> if (nav.currentDestination?.route == "review" && f !is TxFlow.Working) nav.popBackStack()
        }
    }

    val ctx = LocalContext.current
    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(state.signedIn) {
        if (state.signedIn && android.os.Build.VERSION.SDK_INT >= 33 && !Notifier.allowed(ctx) && !vm.prefs.askedNotify) {
            vm.prefs.askedNotify = true
            askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // squad activity: ask the server every 20 s while the app is in use, show a notification for each new item
    LaunchedEffect(state.signedIn) {
        while (state.signedIn) {
            vm.pollNotifications()
            kotlinx.coroutines.delay(20_000)
        }
    }
    LaunchedEffect(Unit) { vm.incoming.collect { Notifier.show(ctx, it) } }
    // an invite link opens the Squads tab with the code filled in
    LaunchedEffect(state.pendingJoinCode, state.signedIn) {
        if (state.pendingJoinCode != null && state.signedIn) nav.navigate("squads") { launchSingleTop = true }
    }

    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val tabs = listOf("home" to "Today", "explore" to "Explore", "squads" to "Squads", "settings" to "Settings")
    Scaffold(bottomBar = {
        if (route in tabs.map { it.first }) NavigationBar {
            tabs.forEach { (r, label) ->
                NavigationBarItem(
                    selected = route == r, label = { Text(label) },
                    icon = { Text(label.take(1), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                    onClick = { if (route != r) nav.navigate(r) { popUpTo("home") { saveState = false }; launchSingleTop = true } },
                )
            }
        }
    }) { pad -> Box(Modifier.padding(pad)) {
    NavHost(nav, startDestination = start) {
        composable("onboarding") { OnboardingScreen(state, onConnect = { vm.connect(sender) }, onDismissError = vm::dismissConnectError, onPractice = { nav.navigate("practice") }) }
        composable("home") {
            LaunchedEffect(state.signedIn) { if (state.signedIn) vm.refreshList() }
            HomeScreen(
                state, state.account?.wallet, onNew = { vm.setNewGoalSquad(null); nav.navigate("new") }, onOpen = { nav.navigate("detail/$it") },
                onCheckIn = { pool -> vm.openCheckIn(pool); nav.navigate("checkin/$pool") }, onRefresh = vm::refreshList,
                onSettings = { nav.navigate("settings") }, onSignIn = { vm.connect(sender) },
                onLoadFaucet = vm::loadFaucet, onClaimFaucet = vm::claimTestTokens,
            )
        }
        composable("new") {
            NewGoalScreen(
                state, onBack = { vm.resetFlow(); vm.setNewGoalSquad(null); nav.popBackStack() }, onLoadTemplates = vm::loadTemplates, onLoadExamples = { vm.loadExamples(true) },
                onParse = vm::parseGoal, onUseAi = vm::setUseAi, onAlternative = vm::useAlternative, onClear = { vm.clearGoal(); vm.resetFlow() },
                onTextConsumed = vm::goalTextConsumed, onStart = vm::startFromPreview,
            )
        }
        composable("review") {
            val f = state.flow
            if (f is TxFlow.Review) ReviewScreen(f.tx, onSign = { vm.confirm(sender) }, onCancel = { vm.cancelReview() })
        }
        composable("detail/{pool}") { entry ->
            val pool = entry.arguments?.getString("pool") ?: return@composable
            DetailScreen(
                state, pool, state.account?.wallet, onBack = { vm.clearDetail(); nav.popBackStack() }, onLoad = { vm.loadDetail(pool) },
                onJoin = { stake -> state.detail?.challenge?.let { vm.prepareJoin(pool, it.mint, stake) } },
                onClaim = { state.detail?.challenge?.let { vm.prepareClaim(pool, it.mint) } },
                onCheckIn = { vm.openCheckIn(pool); nav.navigate("checkin/$pool") },
            )
        }
        composable("checkin/{pool}") {
            val ci = state.checkIn
            if (ci == null) {
                Page("Check in", onBack = { nav.popBackStack() }) {
                    if (state.detailError != null) Text(state.detailError!!, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                    else androidx.compose.material3.CircularProgressIndicator()
                }
            } else {
                CheckInScreen(
                    ci, onBack = { vm.closeCheckIn(); nav.popBackStack() }, onSubmit = vm::submitProof, onInject = vm::injectProof, onReplay = vm::replayLast,
                    stepBaseline = vm::stepBaseline, saveStepBaseline = vm::saveStepBaseline, watchedApp = vm::watchedApp,
                )
            }
        }
        composable("explore") {
            ExploreScreen(
                state, onLoad = { vm.loadExplore(true) }, onMore = { vm.loadExplore(false) }, onFilter = { c, m, e -> vm.setExploreFilter(c, m, e) },
                onOpen = { nav.navigate("detail/$it") }, onReport = vm::reportChallenge,
                onQuick = { text -> vm.setNewGoalSquad(null); vm.tryGoal(text, true); nav.navigate("new") }, onDismissMessage = vm::clearExploreMessage,
            )
        }
        composable("squads") {
            SquadsScreen(
                state, onLoad = vm::loadSquads, onCreate = vm::createSquad, onJoin = { vm.joinSquad(it); vm.setPendingJoinCode(null) },
                onOpen = { id -> vm.openSquad(id); nav.navigate("squad/$id") }, joinCode = state.pendingJoinCode,
            )
        }
        composable("squad/{id}") { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            LaunchedEffect(id) { vm.openSquad(id) }
            SquadDetailScreen(
                state, state.account?.wallet, onBack = { vm.closeSquad(); nav.popBackStack() }, onRefresh = { vm.openSquad(id) },
                onNudge = { w -> vm.nudge(id, w) }, onOpenPool = { nav.navigate("detail/$it") },
                onNewChallenge = { vm.setNewGoalSquad(id); nav.navigate("new") },
            )
        }
        composable("practice") { app.vowed.ui.PracticeScreen(onBack = { nav.popBackStack() }) }
        composable("settings") {
            SettingsScreen(state, vm.prefs.backendUrl, onBack = { nav.popBackStack() }, onPractice = { nav.navigate("practice") }, onDisconnect = {
                vm.disconnect(sender)
                nav.navigate("onboarding") { popUpTo(0) }
            })
        }
    }
    } }
}
