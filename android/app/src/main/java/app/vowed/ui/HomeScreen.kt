package app.vowed.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vowed.CheckInLogic
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
import app.vowed.ui.components.EmptyState
import app.vowed.ui.components.GradientTile
import app.vowed.ui.components.PrimaryButton
import app.vowed.ui.components.SectionHeader
import app.vowed.ui.components.SmallButton
import app.vowed.ui.components.Spinner
import app.vowed.ui.components.StatusBadge
import app.vowed.ui.components.Tone
import app.vowed.ui.components.VowedMark
import app.vowed.ui.components.trustLabel
import app.vowed.ui.components.trustTone
import app.vowed.ui.theme.VowedColors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The proof type and trust tier written in a challenge's plan, if it has one. */
fun planProofType(c: Challenge): String? = ((c.plan?.get("proofMethods") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("type")?.let { (it as? JsonPrimitive)?.content }
fun planTrust(c: Challenge): String? = ((c.plan?.get("proofMethods") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("trustTier")?.let { (it as? JsonPrimitive)?.content }
fun planCategory(c: Challenge): String = c.plan?.get("category")?.let { (it as? JsonPrimitive)?.content } ?: "custom"

@Composable
fun HomeTopBar(onBell: () -> Unit, hasNews: Boolean) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VowedMark(36.dp)
            Text("Vowed", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
        Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).clickable(onClick = onBell), contentAlignment = Alignment.Center) {
            GlyphIcon(Glyph.Bell, MaterialTheme.colorScheme.primary, 24.dp)
            if (hasNews) Box(Modifier.align(Alignment.TopEnd).padding(12.dp).size(9.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
        }
    }
}

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
    LaunchedEffect(state.signedIn) { if (state.signedIn) onLoadDiscover() }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        HomeTopBar(onBell = onSquads, hasNews = false)
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
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // hero: play with friends
            Box(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(Brush.linearGradient(listOf(VowedColors.Violet, VowedColors.IndigoDeep))).padding(20.dp),
            ) {
                Column(Modifier.fillMaxWidth(0.68f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Play with your friends now!", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text("Start a squad, nudge each other, share the leaderboard.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
                    Box(Modifier.clip(CircleShape).background(Color.White).clickable(onClick = onSquads).padding(horizontal = 18.dp, vertical = 10.dp)) {
                        Text("Start a squad", style = MaterialTheme.typography.labelLarge, color = VowedColors.Indigo)
                    }
                }
                Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy((-14).dp)) {
                    Avatar("A", 44.dp); Avatar("K", 44.dp); Avatar("M", 44.dp)
                }
            }
            state.listError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            FaucetCard(state.faucet, myWallet, onLoadFaucet, onClaimFaucet)

            // today
            val open = state.challenges.filter { it.status == "Open" }
            val dueCards = open.mapNotNull { c ->
                val me = state.details[c.pool]?.participants?.firstOrNull { it.wallet == myWallet } ?: return@mapNotNull null
                c to CheckInLogic.dayView(c, me, now)
            }
            SectionHeader("Today's check-ins", "Refresh", onRefresh)
            if (state.loadingList && state.challenges.isEmpty()) Spinner()
            if (state.challenges.isEmpty() && !state.loadingList) {
                EmptyState(
                    "Nothing here yet",
                    "Type any goal in your own words, put a small stake behind it, and check in each day. Or join someone else's challenge in Explore.",
                    art = { GoalArt(170.dp) },
                    action = {
                        Spacer(Modifier.height(6.dp))
                        PrimaryButton("Start your first challenge", onNew)
                        Spacer(Modifier.height(6.dp))
                        onboardingIdeas().take(3).forEach { (label, text) ->
                            Box(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).clickable { onTryGoal(text) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                Text("$label: $text", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                            }
                        }
                    },
                )
            }
            dueCards.forEach { (c, dv) -> TodayCard(c, dv, onOpen, onCheckIn) }
            val others = state.challenges.filter { ch -> dueCards.none { it.first.pool == ch.pool } }
            if (others.isNotEmpty()) {
                SectionHeader("Your other challenges")
                others.forEach { c -> ChallengeRow(c, onOpen) }
            }

            // discover
            val picks = (state.explore.items + state.explore.demoPools).filter { !it.createdByYou && !it.joined }.take(8)
            SectionHeader("Discover", "View all", onExplore)
            if (picks.isEmpty()) {
                Text("Public challenges from other people will show up here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(picks, key = { it.pool }) { p -> DiscoverCard(p, onOpen) } }
            }

            // top streaks
            val st = state.rewardsUi.status
            val top = st?.standings.orEmpty().take(8)
            SectionHeader("Top streaks")
            if (top.isEmpty()) {
                Text("Nobody has a streak yet. Check in a few days in a row to be the first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
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

@Composable
private fun TodayCard(c: Challenge, dv: app.vowed.DayView, onOpen: (String) -> Unit, onCheckIn: (String) -> Unit) {
    val cat = planCategory(c)
    AppCard(onClick = { onOpen(c.pool) }, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GradientTile(CategoryStyle.colors(cat), Modifier.size(48.dp)) { GlyphIcon(categoryGlyph(cat), Color.White, 26.dp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(planTitle(c), style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    Text("${c.mode} · ${c.durationDays} days (${c.requiredDays} needed) · ${c.participantCount} joined", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (c.isDemo) DemoBadge(c.demoLabel)
                planProofType(c)?.let { StatusBadge(proofLabel(it).substringBefore(" (").take(34), Tone.Primary) }
                StatusBadge(trustLabel(planTrust(c)), trustTone(planTrust(c)))
            }
            DayDots(dv.doneBits, dv.day, c.durationDays)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Streak ${dv.streak}", style = MaterialTheme.typography.titleSmall)
                when {
                    dv.done -> StatusBadge("Today done", Tone.Success)
                    dv.dayOpen -> SmallButton("Check in", { onCheckIn(c.pool) })
                    else -> StatusBadge("Not open yet", Tone.Neutral)
                }
            }
        }
    }
}

@Composable
private fun ChallengeRow(c: Challenge, onOpen: (String) -> Unit) {
    val cat = planCategory(c)
    AppCard(onClick = { onOpen(c.pool) }, Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GradientTile(CategoryStyle.colors(cat), Modifier.size(44.dp)) { GlyphIcon(categoryGlyph(cat), Color.White, 24.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(planTitle(c), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text("${c.status} · pot ${fmt(c.totalDeposits)} test USDC", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (c.isDemo) DemoBadge(c.demoLabel)
            }
            GlyphIcon(Glyph.Chevron, MaterialTheme.colorScheme.onSurfaceVariant, 22.dp)
        }
    }
}

@Composable
private fun DiscoverCard(p: ExploreItem, onOpen: (String) -> Unit) {
    AppCard(onClick = { onOpen(p.pool) }, Modifier.width(210.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Box(Modifier.fillMaxWidth().height(86.dp).background(Brush.linearGradient(CategoryStyle.colors(p.category))), contentAlignment = Alignment.Center) {
                GlyphIcon(categoryGlyph(p.category), Color.White, 40.dp)
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (p.sample) StatusBadge("SAMPLE", Tone.Warning)
                    if (p.isDemo) StatusBadge("DEMO", Tone.Danger)
                }
                Text("${p.participantCount} joined · ${p.tokenSymbol}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
