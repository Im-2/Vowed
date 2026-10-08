package app.vowed.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vowed.CheckInLogic
import app.vowed.R
import app.vowed.UiState
import app.vowed.data.Challenge
import app.vowed.data.ExploreItem
import app.vowed.ui.art.Glyph
import app.vowed.ui.art.GlyphIcon
import app.vowed.ui.art.GoalArt
import app.vowed.ui.art.categoryGlyph
import app.vowed.ui.components.AppCard
import app.vowed.ui.components.Avatar
import app.vowed.ui.components.CategoryStyle
import app.vowed.ui.components.ChipKind
import app.vowed.ui.components.EmptyState
import app.vowed.ui.components.GradientTile
import app.vowed.ui.components.LabelChip
import app.vowed.ui.components.PrimaryButton
import app.vowed.ui.components.SectionHeader
import app.vowed.ui.components.SmallButton
import app.vowed.ui.components.SoftButton
import app.vowed.ui.components.Spinner
import app.vowed.ui.components.VowedMark
import app.vowed.ui.components.trustLabel
import app.vowed.ui.theme.VowedColors
import app.vowed.ui.theme.VowedTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The proof type and trust tier written in a challenge's plan, if it has one. */
fun planProofType(c: Challenge): String? = ((c.plan?.get("proofMethods") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("type")?.let { (it as? JsonPrimitive)?.content }
fun planTrust(c: Challenge): String? = ((c.plan?.get("proofMethods") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("trustTier")?.let { (it as? JsonPrimitive)?.content }
fun planCategory(c: Challenge): String = c.plan?.get("category")?.let { (it as? JsonPrimitive)?.content } ?: "custom"

private fun compactAmount(baseUnits: String): String {
    val v = fmt(baseUnits)
    return if (v.length > 7) v.take(7).trimEnd('.') else v
}

/** The top bar: logo, name, and the compact test-token pill. Tapping the pill opens the full test-token panel in a bottom sheet. */
@Composable
fun HomeTopBar(state: UiState, onPill: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VowedMark(40.dp)
            Text("Vowed", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
        val b = state.faucet.status?.balances
        Row(
            Modifier.height(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).clickable(onClickLabel = "Test tokens", onClick = onPill).padding(start = 6.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LabelChip("TEST", ChipKind.Test)
            if (b != null) {
                Text("${compactAmount(b.tUSDC)} tUSDC", style = MaterialTheme.typography.labelMedium)
                app.vowed.ui.components.SkrIcon(16.dp)
                Text("${compactAmount(b.tSKR)} tSKR", style = MaterialTheme.typography.labelMedium)
            } else {
                Text("test tokens", style = MaterialTheme.typography.labelMedium)
            }
            Text("i", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * A shorter banner. The right side is a loose, scattered cluster of round circles of different sizes that overlap a little and cast soft shadows:
 * mostly illustrated avatars, plus exactly one Solana logo circle (the official mark, unaltered, on white) and exactly one SKR logo circle.
 */
@Composable
private fun HeroBanner(onSquads: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(148.dp).clip(MaterialTheme.shapes.large).background(Brush.linearGradient(listOf(VowedColors.Violet, VowedColors.IndigoDeep))),
    ) {
        Column(Modifier.align(Alignment.CenterStart).padding(start = 20.dp).fillMaxWidth(0.46f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Play with your friends", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Box(Modifier.clip(CircleShape).background(Color.White).clickable(onClick = onSquads).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Start a squad", style = MaterialTheme.typography.labelLarge, color = VowedColors.Indigo)
            }
        }
        // the cluster: offsets are from the top-left of the right half; sizes and positions are deliberately uneven
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.54f).height(148.dp)) {
            val w = maxWidth
            @Composable
            fun bubble(x: Float, y: Float, size: Int, content: @Composable () -> Unit) {
                Box(Modifier.offset(x = w * x, y = y.dp).size(size.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) { content() }
            }
            bubble(0.02f, 40f, 54) { Avatar("hero-a-0123456789", 54.dp) }
            bubble(0.30f, 8f, 44) { Avatar("hero-b-9876543210", 44.dp) }
            bubble(0.34f, 56f, 64) { Avatar("hero-c-5566778899", 64.dp) }
            bubble(0.64f, 16f, 40) { Image(painterResource(R.drawable.solana_logomark), contentDescription = "Solana", modifier = Modifier.size(22.dp)) }
            bubble(0.70f, 62f, 46) { Avatar("hero-d-1122334455", 46.dp) }
            bubble(0.56f, 100f, 34) { app.vowed.ui.components.SkrIcon(34.dp) }
            bubble(0.04f, 100f, 30) { Avatar("hero-e-6677889900", 30.dp) }
            bubble(0.84f, 8f, 28) { Avatar("hero-f-4433221100", 28.dp) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    onSquads: () -> Unit = {},
    onExplore: () -> Unit = {},
    onLoadDiscover: () -> Unit = {},
    onTryGoal: (String) -> Unit = {},
) {
    val now = rememberNowSeconds()
    var sheet by remember { mutableStateOf(false) }
    LaunchedEffect(state.signedIn) { if (state.signedIn) { onLoadDiscover(); onLoadFaucet() } }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        HomeTopBar(state, onPill = { sheet = true })
        if (state.account == null || !state.signedIn) {
            Spacer(Modifier.height(24.dp))
            EmptyState(
                "Sign in with your wallet", "Your challenges are tied to your wallet. Sign in to see them.",
                art = { GoalArt(180.dp) },
                action = {
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("Sign in", onSignIn)
                    if (state.connecting != null) Spinner()
                    state.connectError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                },
            )
            return@Column
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            HeroBanner(onSquads)
            state.listError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            val open = state.challenges.filter { it.status == "Open" }
            val dueCards = open.mapNotNull { c ->
                val me = state.details[c.pool]?.participants?.firstOrNull { it.wallet == myWallet } ?: return@mapNotNull null
                Triple(c, CheckInLogic.dayView(c, me, now), me)
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionHeader("Today's check-ins", "Refresh", onRefresh)
                if (state.loadingList && state.challenges.isEmpty()) Spinner()
                if (state.challenges.isEmpty() && !state.loadingList) {
                    EmptyState(
                        "Nothing here yet",
                        "Type any goal in your own words, put a small stake behind it, and check in each day. Or join someone else's challenge in Explore.",
                        art = { GoalArt(150.dp) },
                        action = {
                            Spacer(Modifier.height(8.dp))
                            PrimaryButton("Start your first challenge", onNew)
                            Spacer(Modifier.height(8.dp))
                            onboardingIdeas().take(3).forEach { (label, text) ->
                                Box(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).clickable { onTryGoal(text) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Text("$label: $text", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                                }
                            }
                        },
                    )
                }
                dueCards.forEach { (c, dv, me) -> TodayCard(c, dv, me.daysCompleted, onOpen, onCheckIn) }
                val others = state.challenges.filter { ch -> dueCards.none { it.first.pool == ch.pool } }
                others.forEach { c -> ChallengeRow(c, onOpen) }
            }

            val picks = (state.explore.items + state.explore.demoPools).filter { !it.createdByYou && !it.joined }.take(8)
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionHeader("Discover", "View all", onExplore)
                if (picks.isEmpty()) {
                    Text("Public challenges from other people will show up here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { items(picks, key = { it.pool }) { p -> DiscoverCard(p, now, onOpen) } }
                }
            }

            val top = state.rewardsUi.status?.standings.orEmpty().take(8)
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionHeader("Top streaks")
                if (top.isEmpty()) {
                    Text("Nobody has a streak yet. Check in a few days in a row to be the first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(top, key = { it.wallet }) { t ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.width(64.dp)) {
                                Avatar(t.wallet, 56.dp)
                                Text(if (t.you) "You" else short(t.wallet), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                Text("${t.streak} days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }, sheetState = rememberModalBottomSheetState(), containerColor = MaterialTheme.colorScheme.surface) {
            Box(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
                FaucetPanel(state.faucet, myWallet, onLoadFaucet, onClaimFaucet)
            }
        }
    }
}

@Composable
private fun TodayCard(c: Challenge, dv: app.vowed.DayView, daysDone: Int, onOpen: (String) -> Unit, onCheckIn: (String) -> Unit) {
    val cat = planCategory(c)
    AppCard(onClick = { onOpen(c.pool) }, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                app.vowed.ui.components.CategoryThumb(cat, 48.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(planTitle(c), style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (c.isDemo) LabelChip("DEMO", ChipKind.Demo, moreTitle = "Demo pool", more = c.demoLabel ?: "DEMO POOL: minutes-long days, test money only")
                        val t = planTrust(c)
                        LabelChip(trustLabel(t), ChipKind.Neutral, dot = when (t) { "high" -> VowedTheme.extra.success; "medium" -> VowedColors.Indigo; else -> VowedTheme.extra.warning })
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("$daysDone of ${c.requiredDays} days", style = MaterialTheme.typography.labelLarge)
                    Text("Streak ${dv.streak}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LinearProgressIndicator(
                    progress = { (daysDone.toFloat() / c.requiredDays.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primaryContainer, gapSize = 0.dp, drawStopIndicator = {},
                )
            }
            when {
                dv.done -> SoftButton("Done today · view", { onOpen(c.pool) })
                dv.dayOpen -> PrimaryButton("Check in", { onCheckIn(c.pool) })
                else -> SoftButton("Not open yet · view", { onOpen(c.pool) })
            }
        }
    }
}

@Composable
private fun ChallengeRow(c: Challenge, onOpen: (String) -> Unit) {
    val cat = planCategory(c)
    AppCard(onClick = { onOpen(c.pool) }, Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            app.vowed.ui.components.CategoryThumb(cat, 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(planTitle(c), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (c.isDemo) LabelChip("DEMO", ChipKind.Demo, moreTitle = "Demo pool", more = c.demoLabel ?: "DEMO POOL: minutes-long days, test money only")
                    Text("${c.status} · pot ${fmt(c.totalDeposits)} test USDC", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            GlyphIcon(Glyph.Chevron, MaterialTheme.colorScheme.onSurfaceVariant, 22.dp)
        }
    }
}

@Composable
private fun DiscoverCard(p: ExploreItem, now: Long, onOpen: (String) -> Unit) {
    AppCard(onClick = { onOpen(p.pool) }, Modifier.width(200.dp)) {
        app.vowed.ui.components.CategoryBackdrop(p.category, Modifier.fillMaxWidth().height(80.dp), scrim = Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.04f), 1f to Color.Black.copy(alpha = 0.30f)))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, minLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.sample) LabelChip("SAMPLE", ChipKind.Sample, moreTitle = "Sample challenge", more = "SAMPLE: created by the Vowed team so there is always something to try")
                if (p.isDemo) LabelChip("DEMO", ChipKind.Demo, moreTitle = "Demo pool", more = p.demoLabel ?: "DEMO POOL: minutes-long days, test money only")
            }
            Text("${p.participantCount} joined · ${timeLeft(p.joinDeadlineTs - now)} left", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
