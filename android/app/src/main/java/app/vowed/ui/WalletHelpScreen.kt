package app.vowed.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vowed.ui.components.AppCard
import app.vowed.ui.components.ChipKind
import app.vowed.ui.components.LabelChip

/** What the app shows about wallets: the safe way to try Vowed with a real wallet app. Sources: docs/verified-facts.md ("Real wallets"). */
object WalletHelp {
    val rules = listOf(
        "Use a throwaway wallet." to "Make a new wallet only for testing Vowed. Do not use the wallet that holds your real money.",
        "Never share a seed phrase." to "Vowed never asks for your seed phrase or private key, and nobody who helps you test should either. Keep it secret.",
        "Switch the wallet to devnet." to "Vowed runs on Solana devnet with test tokens that have no value. Your wallet must be set to devnet or it will not work.",
    )
    val wallets = listOf(
        "Phantom" to "Settings, then Developer Settings, then Testnet Mode, then choose Solana Devnet.",
        "Solflare" to "Settings, then Network, then choose Devnet.",
        "Seed Vault Wallet (Seeker)" to "Solana Mobile does not document a network switch for it. Vowed asks for devnet through the standard wallet connection; if it refuses, use Phantom or Solflare.",
    )
    const val AFTER = "Then come back, tap Sign in, and approve in the wallet. In Home, tap the test-token pill and \"Get test tokens\": it also sends a little devnet SOL for fees."
    const val WRONG_NETWORK_HINT = "If the wallet says it does not support devnet, or a transaction fails for no clear reason, the wallet is probably on the wrong network."
}

@Composable
fun WalletHelpScreen(onBack: () -> Unit) {
    Page("Using a real wallet", onBack = onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LabelChip("TEST", ChipKind.Test, moreTitle = "Test network", more = "Vowed in this build runs on Solana devnet. The tokens are test tokens with no value.")
                Text("Devnet only. No real money.", style = MaterialTheme.typography.bodyMedium)
            }
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Three rules", style = MaterialTheme.typography.titleMedium)
                    WalletHelp.rules.forEachIndexed { i, (title, body) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.padding(top = 2.dp)) { Text("${i + 1}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                            Column {
                                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("How to switch to devnet", style = MaterialTheme.typography.titleMedium)
                    WalletHelp.wallets.forEach { (name, how) ->
                        Column {
                            Text(name, style = MaterialTheme.typography.titleSmall)
                            Text(how, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        "Menu names can change between wallet versions. These steps come from each wallet's own help pages; we have not tested every wallet.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Wrong network?", style = MaterialTheme.typography.titleMedium)
                    Text(WalletHelp.WRONG_NETWORK_HINT, style = MaterialTheme.typography.bodyMedium)
                    Text(app.vowed.wallet.WalletErrors.WRONG_NETWORK, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(WalletHelp.AFTER, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A one-sentence message at the top of the screen about something the app did on its own, with an OK to dismiss it. */
@Composable
fun NoticeBanner(text: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (text == null) return
    AppCard(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), container = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}
