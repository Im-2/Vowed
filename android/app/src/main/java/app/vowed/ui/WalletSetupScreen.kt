package app.vowed.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vowed.ui.components.AppCard
import app.vowed.ui.components.PrimaryButton
import app.vowed.ui.components.SoftButton
import app.vowed.ui.components.StatusBadge
import app.vowed.ui.components.Tone
import app.vowed.ui.theme.VowedColors
import app.vowed.wallet.WalletSetupLogic

/**
 * "One quick step before connecting". Vowed runs on a practice network (devnet) with test money, and a wallet on the real network turns the
 * connection down. The app cannot flip a wallet's network itself, so this screen shows how, in three steps with simple drawings made here
 * (not copied from any wallet). Menu names come from Phantom's own documentation (docs/verified-facts.md); other wallets are listed only as far as
 * their documentation goes.
 */
@Composable
fun WalletSetupScreen(reason: WalletSetupLogic.Reason, onBack: () -> Unit, onConnectNow: () -> Unit) {
    val ctx = LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }
    var others by remember { mutableStateOf(false) }
    Page("Wallet setup", onBack = onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(WalletSetupLogic.headline(reason), style = MaterialTheme.typography.headlineSmall)
            Text(WalletSetupLogic.message(reason), style = MaterialTheme.typography.bodyLarge)
            StatusBadge("PRACTICE NETWORK: test money only, nothing real", Tone.Warning)

            SetupStep(1, "Open Phantom", "Open the Phantom app on this phone.") { PhoneArt() }
            SetupStep(2, "Settings, then Developer Settings", "Tap the settings icon, then Developer Settings.") { MenuArt() }
            SetupStep(3, "Turn on Testnet Mode", "Switch Testnet Mode on and pick Solana Devnet. You only do this once.") { ToggleArt() }

            Text(
                "Phantom may say Vowed's identity could not be verified. That is expected for a hackathon test app on a practice network.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PrimaryButton("Open Phantom", {
                val launch = ctx.packageManager.getLaunchIntentForPackage(WalletSetupLogic.PHANTOM_PACKAGE)
                if (launch != null) {
                    note = null
                    ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    note = "Phantom is not installed on this phone, so its Google Play page is opening. Install it, set it up with a new throwaway wallet, then come back."
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${WalletSetupLogic.PHANTOM_PACKAGE}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .recoverCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WalletSetupLogic.PHANTOM_PLAY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            })
            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = VowedColors.Warning) }
            SoftButton("I've switched it, connect now", onConnectNow)

            TextButton(onClick = { others = !others }) { Text(if (others) "Hide other wallets" else "Use a different wallet") }
            if (others) {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Other wallets", style = MaterialTheme.typography.titleSmall)
                        Text("Solflare (also listed by Solana Mobile as a compatible wallet): Settings, then Network, then Devnet, according to its help centre. We have not run it ourselves.", style = MaterialTheme.typography.bodyMedium)
                        Text("Seed Vault Wallet (Solana Seeker phones): Solana Mobile's documentation does not say how to use a practice network with it, so we cannot promise it works. Phantom or Solflare are safer for now.", style = MaterialTheme.typography.bodyMedium)
                        Text("Always use a new throwaway wallet for testing, and never share your seed phrase with anyone.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupStep(n: Int, title: String, body: String, art: @Composable () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(104.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { art() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Text("$n", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                    Text(title, style = MaterialTheme.typography.titleSmall)
                }
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val ink = Color(0xFF1B1740)
private val violet = Color(0xFF4A35D0)

/** Step 1: a phone with a generic wallet tile (our own drawing, not any wallet's logo). */
@Composable
private fun PhoneArt() {
    Box(Modifier.size(width = 52.dp, height = 84.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).border(3.dp, ink, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(violet), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 16.dp, height = 11.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 2.dp, bottom = 10.dp).size(14.dp).clip(CircleShape).background(Color(0x66FFB020)).border(2.dp, Color(0xFFFFB020), CircleShape))
    }
}

/** Step 2: a settings list with the "Developer Settings" row picked out. */
@Composable
private fun MenuArt() {
    Column(Modifier.width(84.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).border(2.dp, ink, RoundedCornerShape(10.dp)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).border(2.dp, ink, CircleShape))
            Box(Modifier.height(5.dp).width(36.dp).clip(CircleShape).background(ink))
        }
        MenuLine(false)
        MenuLine(true)
        MenuLine(false)
    }
}

@Composable
private fun MenuLine(picked: Boolean) {
    Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(5.dp)).background(if (picked) violet else Color(0xFFE9E5FF)), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.padding(start = 5.dp).height(4.dp).width(if (picked) 44.dp else 30.dp).clip(CircleShape).background(if (picked) Color.White else Color(0xFFA99BFF)))
    }
}

/** Step 3: a switch turned on. */
@Composable
private fun ToggleArt() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(width = 64.dp, height = 34.dp).clip(CircleShape).background(violet), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.padding(end = 4.dp).size(26.dp).clip(CircleShape).background(Color.White))
        }
        Box(Modifier.height(6.dp).width(56.dp).clip(CircleShape).background(ink))
        Box(Modifier.height(5.dp).width(40.dp).clip(CircleShape).background(Color(0xFFA99BFF)))
    }
}
