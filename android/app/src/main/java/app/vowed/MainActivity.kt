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
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Must be created before the activity is STARTED.
        val sender = ActivityResultSender(this)
        setContent { VowedTheme { Surface(Modifier.fillMaxSize()) { Root(vm, sender) } } }
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
                nav.popBackStack("home", false)
                nav.navigate("detail/${f.pool}")
            }
            else -> if (nav.currentDestination?.route == "review" && f !is TxFlow.Working) nav.popBackStack()
        }
    }

    NavHost(nav, startDestination = start) {
        composable("onboarding") { OnboardingScreen(state, onConnect = { vm.connect(sender) }, onDismissError = vm::dismissConnectError, onPractice = { nav.navigate("practice") }) }
        composable("home") {
            LaunchedEffect(state.signedIn) { if (state.signedIn) vm.refreshList() }
            HomeScreen(
                state, state.account?.wallet, onNew = { nav.navigate("new") }, onOpen = { nav.navigate("detail/$it") },
                onCheckIn = { pool -> vm.openCheckIn(pool); nav.navigate("checkin/$pool") }, onRefresh = vm::refreshList,
                onSettings = { nav.navigate("settings") }, onSignIn = { vm.connect(sender) },
                onLoadFaucet = vm::loadFaucet, onClaimFaucet = vm::claimTestTokens,
            )
        }
        composable("new") {
            NewGoalScreen(
                state, onBack = { vm.resetFlow(); nav.popBackStack() }, onLoadTemplates = vm::loadTemplates, onParse = vm::parseGoal, onUseAi = vm::setUseAi,
                onAlternative = vm::useAlternative, onClear = { vm.clearGoal(); vm.resetFlow() }, onStart = vm::startFromPreview,
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
        composable("practice") { app.vowed.ui.PracticeScreen(onBack = { nav.popBackStack() }) }
        composable("settings") {
            SettingsScreen(state, vm.prefs.backendUrl, onBack = { nav.popBackStack() }, onPractice = { nav.navigate("practice") }, onDisconnect = {
                vm.disconnect(sender)
                nav.navigate("onboarding") { popUpTo(0) }
            })
        }
    }
}
