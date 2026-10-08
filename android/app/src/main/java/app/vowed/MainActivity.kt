package app.vowed

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import app.vowed.ui.theme.VowedTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
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
        enableEdgeToEdge()
        // Must be created before the activity is STARTED.
        val sender = ActivityResultSender(this)
        handleLink(intent)
        setContent { VowedTheme { Surface(Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) { Root(vm, sender) } } }
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
    var splash by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    val nav: NavHostController = rememberNavController()
    val start = if (vm.prefs.onboarded && state.account != null) "home" else "onboarding"

    LaunchedEffect(Unit) { vm.loadMeta() }
    LaunchedEffect(state.deviceRecovery) { if (state.deviceRecovery == app.vowed.DeviceRecovery.Needed) vm.recoverDevice(sender) }
    LaunchedEffect(state.account?.wallet) { app.vowed.ui.components.Avatars.mineWallet = state.account?.wallet; app.vowed.ui.components.Avatars.mineIndex = vm.prefs.avatarIndex }
    // After a successful connect from onboarding, go home.
    LaunchedEffect(state.signedIn) {
        // the connect screen shows its own "Wallet connected" pop-up first and then navigates; only the old onboarding route jumps ahead
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
    // notifications are requested only when the person taps "Turn on notifications" on the Squads screen, never automatically
    var notifyOn by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Notifier.allowed(ctx)) }
    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { notifyOn = it }
    // squad activity: ask the server every 20 s while the app is in use, show a notification for each new item
    LaunchedEffect(state.signedIn) {
        while (state.signedIn) {
            vm.pollNotifications()
            kotlinx.coroutines.delay(20_000)
        }
    }
    LaunchedEffect(Unit) { vm.incoming.collect { Notifier.show(ctx, it) } }
    LaunchedEffect(Unit) { vm.lettersDelivered.collect { Notifier.showLetter(ctx, it) } }
    // an invite link opens the Squads tab with the code filled in
    LaunchedEffect(state.pendingJoinCode, state.signedIn) {
        if (state.pendingJoinCode != null && state.signedIn) nav.navigate("squads") { launchSingleTop = true }
    }

    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    // creating or joining a squad opens it
    val openedSquad = state.squads.detail?.squad?.id
    LaunchedEffect(openedSquad) {
        if (openedSquad != null && nav.currentDestination?.route == "squads") nav.navigate("squad/$openedSquad") { launchSingleTop = true }
    }
    val tabRoutes = listOf("home", "explore", "squads", "settings", "categories")
    val goTab = { r: String -> if (route != r) nav.navigate(r) { popUpTo("home") { saveState = false }; launchSingleTop = true } }
    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent, contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0), bottomBar = {
        if (route in tabRoutes) app.vowed.ui.components.VowedBottomBar(
            route, onHome = { goTab("home") }, onExplore = { goTab("explore") },
            onCreate = { vm.setNewGoalSquad(null); nav.navigate("new") }, onSquads = { goTab("squads") }, onYou = { goTab("settings") },
        )
    }) { pad -> Box(Modifier.padding(pad).then(if (route in tabRoutes) Modifier else Modifier.navigationBarsPadding())) {
    NavHost(nav, startDestination = start) {
        composable("onboarding") { OnboardingScreen(state, onGetStarted = { nav.navigate("connect") }, onPractice = { nav.navigate("practice") }) }
        composable("connect") {
            app.vowed.ui.ConnectScreen(
                state, onBack = { nav.popBackStack() }, onConnect = { vm.connect(sender) }, onDismissError = vm::dismissConnectError,
                onConnected = { nav.navigate("home") { popUpTo("onboarding") { inclusive = true } } },
            )
        }
        composable("home") {
            LaunchedEffect(state.signedIn) { if (state.signedIn) vm.refreshList() }
            HomeScreen(
                state, state.account?.wallet, onNew = { vm.setNewGoalSquad(null); nav.navigate("new") }, onOpen = { nav.navigate("detail/$it") },
                onCheckIn = { pool -> vm.openCheckIn(pool); nav.navigate("checkin/$pool") }, onRefresh = vm::refreshList,
                onSettings = { nav.navigate("settings") }, onSignIn = { vm.connect(sender) },
                onLoadFaucet = vm::loadFaucet, onClaimFaucet = vm::claimTestTokens,
                onSquads = { nav.navigate("squads") { launchSingleTop = true } }, onExplore = { nav.navigate("explore") { launchSingleTop = true } },
                onLoadDiscover = { vm.loadExplore(true); vm.loadRewards() }, onTryGoal = { text -> vm.setNewGoalSquad(null); vm.tryGoal(text, false); nav.navigate("new") },
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
            else Page("Working", onBack = { vm.cancelReview() }) { app.vowed.ui.FlowStatus(f) }
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
                onQuick = { text -> vm.setNewGoalSquad(null); vm.tryGoal(text, true); nav.navigate("new") }, onDismissMessage = vm::clearExploreMessage, onCategories = { nav.navigate("categories") { launchSingleTop = true } },
            )
        }
        composable("categories") {
            app.vowed.ui.CategoriesScreen(onBack = { nav.popBackStack() }, onPick = { cat -> vm.setExploreFilter(category = cat); nav.navigate("explore") { popUpTo("home"); launchSingleTop = true } })
        }
        composable("squads") {
            SquadsScreen(
                state, onLoad = vm::loadSquads, onCreate = vm::createSquad, onJoin = { vm.joinSquad(it); vm.setPendingJoinCode(null) },
                onOpen = { id -> vm.openSquad(id); nav.navigate("squad/$id") { launchSingleTop = true } }, joinCode = state.pendingJoinCode,
                notificationsOn = notifyOn || android.os.Build.VERSION.SDK_INT < 33, onTurnOnNotifications = { askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
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
        composable("rewards") {
            app.vowed.ui.RewardsScreen(state, state.account?.wallet, onBack = { nav.popBackStack() }, onLoad = vm::loadRewards, onFreeze = { pool, day -> vm.prepareFreeze(pool, day) }, onDismissMessage = vm::clearRewardsMessage, onSignIn = { vm.connect(sender) }, onLoadFaucet = vm::loadFaucet)
        }
        composable("coach") {
            app.vowed.ui.CoachScreen(
                state, onBack = { nav.popBackStack() }, onLoad = vm::loadCoach,
                onTry = { category -> vm.setNewGoalSquad(null); vm.tryGoal(app.vowed.ui.coachStarterText(category), false); nav.navigate("new") },
            )
        }
        composable("letters") {
            app.vowed.ui.LettersScreen(
                state, onBack = { nav.popBackStack() }, onLoad = vm::loadLetters, onAdd = vm::addLetter, onDelete = vm::deleteLetter,
                onRead = vm::readLetter, onCloseReading = vm::closeLetter, onSimulate = vm::evaluateLetters,
            )
        }
        composable("wallet-help") { app.vowed.ui.WalletHelpScreen(onBack = { nav.popBackStack() }) }
        composable("practice") { app.vowed.ui.PracticeScreen(onBack = { nav.popBackStack() }) }
        composable("settings") {
            SettingsScreen(state, vm.prefs.backendUrl, onBack = { nav.popBackStack() }, onPractice = { nav.navigate("practice") }, onCoach = { nav.navigate("coach") }, onRewards = { nav.navigate("rewards") }, onLetters = { nav.navigate("letters") }, onResetConnection = vm::resetConnection, onHidePastPools = { vm.hidePastPools(System.currentTimeMillis() / 1000) }, onShowHiddenPools = vm::showHiddenPools, onWalletHelp = { nav.navigate("wallet-help") }, onHideFromBoard = vm::setLeaderboardHidden, onLoadBoard = vm::loadRewards, onSampleProvider = vm::runSampleProvider, onDisconnect = {
                vm.disconnect(sender)
                nav.navigate("onboarding") { popUpTo(0) }
            })
        }
    }
    app.vowed.ui.NoticeBanner(state.notice, vm::dismissNotice, Modifier.align(androidx.compose.ui.Alignment.TopCenter).statusBarsPadding())
    } }
    if (splash) app.vowed.ui.SplashScreen { splash = false }
}
