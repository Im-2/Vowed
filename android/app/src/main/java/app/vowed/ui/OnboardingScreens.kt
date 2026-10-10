package app.vowed.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vowed.UiState
import app.vowed.data.ConnectStep
import app.vowed.ui.art.GoalArt
import app.vowed.ui.art.SquadArt
import app.vowed.ui.art.StakeArt
import app.vowed.ui.art.WalletArt
import app.vowed.ui.components.AppCard
import app.vowed.ui.components.PageDots
import app.vowed.ui.components.PrimaryButton
import app.vowed.ui.components.SoftButton
import app.vowed.ui.components.Spinner
import app.vowed.ui.components.StatusBadge
import app.vowed.ui.components.SuccessPopup
import app.vowed.ui.components.Tone
import app.vowed.ui.components.VowedMark
import app.vowed.ui.theme.VowedColors
import app.vowed.ui.theme.VowedTheme

private val SoftBackground = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), VowedColors.Lavender))

/** The first screen: the logo scales in with a passing shine, then the name and a small spinner. Shown for a moment, then [onFinished]. */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val scale = remember { Animatable(0.55f) }
    val shine = remember { Animatable(0f) }
    val nameAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(350)
        shine.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        nameAlpha.animateTo(1f, tween(350))
        kotlinx.coroutines.delay(700)
        onFinished()
    }
    Box(Modifier.fillMaxSize().background(SoftBackground), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(220.dp).scale(scale.value).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
                    drawContent()
                    val x = shine.value * size.width * 1.7f - size.width * 0.4f
                    drawRect(
                        Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.75f), Color.Transparent), Offset(x, 0f), Offset(x + size.width * 0.35f, size.height * 0.35f)),
                        blendMode = BlendMode.SrcAtop,
                    )
                },
            ) { VowedMark(220.dp) }
            Text("Vowed", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.graphicsLayer { alpha = nameAlpha.value })
            Text("Put something behind your goal", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.graphicsLayer { alpha = nameAlpha.value })
        }
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 48.dp)) { Spinner() }
    }
}

private data class OnboardPage(val title: String, val art: @Composable () -> Unit)

/** Three swipeable pages with a dots indicator, then "Get started" (connect a wallet) or "Try camera practice" (no wallet). */
@Composable
fun OnboardingScreen(state: UiState, onGetStarted: () -> Unit, onPractice: () -> Unit) {
    val pages = remember {
        listOf(
            OnboardPage("Type any goal in plain words") { GoalArt() },
            OnboardPage("Stake it and prove it with your phone") { StakeArt() },
            OnboardPage("Play with friends, win when you stick to it") { SquadArt() },
        )
    }
    val pager = rememberPagerState(pageCount = { pages.size })
    Column(Modifier.fillMaxSize().background(SoftBackground).statusBarsPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VowedMark(34.dp)
            Text("Vowed", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                pages[i].art()
                Spacer(Modifier.height(20.dp))
                Text(pages[i].title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    when (i) {
                        0 -> "Read a book, walk, wake up early, cut screen time, go to the gym, focus, sleep: say it your way."
                        1 -> "Put a small stake behind it and check in each day with your phone's own sensors, camera or timer."
                        else -> "Join a squad, nudge each other, and get your stake back by showing up. Misses go to the people who did."
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        PageDots(pages.size, pager.currentPage, Modifier.padding(vertical = 14.dp))
        PrimaryButton("Get started", onGetStarted)
        Spacer(Modifier.height(10.dp))
        SoftButton("Try camera practice", onPractice)
        Text("No wallet needed for practice. This build runs on Solana devnet with test tokens only.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 14.dp))
    }
}

/** One screen for the wallet connection: no passwords, no sign-up forms. */
@Composable
fun ConnectScreen(state: UiState, onBack: () -> Unit, onConnect: () -> Unit, onDismissError: () -> Unit, onConnected: () -> Unit, onCheckConnection: () -> Unit = {}) {
    var popupDone by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(SoftBackground).statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Text("‹", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(8.dp))
        WalletArt(120.dp)
        Spacer(Modifier.height(16.dp))
        Text("Connect your wallet", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text("Your Solana wallet is your account. There is no password and no sign-up form.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConnectPoint("1", "Approve a sign-in message", "It proves the wallet is yours. No funds move.")
                ConnectPoint("2", "Register this phone's proof key", "A key made on this phone signs your daily proofs. It never leaves the phone.")
                ConnectPoint("3", "You stay in charge", "Vowed never holds a key. Each stake is approved in your wallet after the app checks the transaction on your phone.")
            }
        }
        Spacer(Modifier.height(14.dp))
        StatusBadge("DEVNET: test tokens only, no real money", Tone.Warning)
        Spacer(Modifier.height(20.dp))
        val step = state.connecting
        if (step != null) {
            AppCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Spinner()
                    Text(
                        when (step) {
                            ConnectStep.Wallet -> "Waiting for your wallet…"
                            ConnectStep.ReturnToVowed -> "Approved in your wallet. Switch back to Vowed to continue."
                            ConnectStep.SigningIn -> "Signing in…"
                            ConnectStep.RegisteringDevice -> "Registering this phone…"
                            ConnectStep.Done -> "Done"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        } else {
            PrimaryButton("Connect wallet", onConnect)
        }
        state.connectError?.let { err ->
            Spacer(Modifier.height(14.dp))
            AppCard(Modifier.fillMaxWidth(), container = VowedTheme.extra.demoTint) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("That did not work", style = MaterialTheme.typography.titleSmall, color = VowedTheme.extra.demo)
                    Text(err, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    ErrorDetails(state.connectErrorDetail)
                    if (state.connectionCheck.running || state.connectionCheck.result != null || state.connectErrorDetail != null) {
                        ConnectionCheckCard(state.connectionCheck, onCheckConnection)
                    } else {
                        app.vowed.ui.components.SoftButton("Check connection", onCheckConnection)
                    }
                    TextButton(onClick = onDismissError) { Text("Dismiss") }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (state.signedIn && !popupDone) {
        SuccessPopup("Wallet connected", "You are signed in. Let us find something to commit to.", "Continue") { popupDone = true; onConnected() }
    }
}

@Composable
private fun ConnectPoint(n: String, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(n, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
