package app.vowed.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import app.vowed.ui.components.AppCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vowed.FaucetUi

private fun waitText(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "${h} h ${m} min"
        m > 0 -> "${m} min"
        else -> "a moment"
    }
}

/**
 * "Get test tokens": the server sends a small fixed amount of TEST USDC and TEST SKR (devnet only, no value) to this wallet,
 * once per wallet per day. The card always says that these are test tokens, and that network fees still need devnet SOL.
 */
@Composable
fun FaucetCard(faucet: FaucetUi, wallet: String?, onLoad: () -> Unit, onClaim: () -> Unit) {
    LaunchedEffect(Unit) { onLoad() }
    val ctx = LocalContext.current
    val st = faucet.status
    val now = rememberNowSeconds()
    // when the server has no faucet (for example a production backend), show nothing at all
    if (st != null && !st.enabled) return
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                app.vowed.ui.components.GradientTile(listOf(androidx.compose.ui.graphics.Color(0xFFFFC857), androidx.compose.ui.graphics.Color(0xFFE8932A)), Modifier.size(44.dp)) {
                    Text("T", style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text("Test tokens", style = MaterialTheme.typography.titleSmall)
                    if (st != null) Text("You have: ${fmt(st.balances.tUSDC)} tUSDC · ${fmt(st.balances.tSKR)} tSKR", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    else if (faucet.loading) app.vowed.ui.components.Spinner(size = 20.dp)
                }
                val wait = (st?.nextClaimAt ?: 0L) - now
                if (st != null && st.canClaim) app.vowed.ui.components.SmallButton("Get test tokens", onClaim, enabled = !faucet.claiming)
            }
            Text(
                st?.label ?: "TEST TOKENS: tUSDC and tSKR exist only on Solana devnet and have no real value.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
            )
            if (st != null) {
                val usdc = st.tokens.firstOrNull { it.symbol == "tUSDC" }
                val skr = st.tokens.firstOrNull { it.symbol == "tSKR" }
                if (usdc != null && skr != null) Text("One claim gives ${fmt(usdc.amount)} tUSDC and ${fmt(skr.amount)} tSKR, once a day per wallet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val wait = st.nextClaimAt - now
                when {
                    st.canClaim -> Unit
                    wait > 0 -> app.vowed.ui.components.StatusBadge("Available again in ${waitText(wait)}", app.vowed.ui.components.Tone.Neutral)
                    st.claimsLeftToday <= 0 -> app.vowed.ui.components.StatusBadge("Out of test tokens for today (UTC). Try tomorrow.", app.vowed.ui.components.Tone.Warning)
                    else -> androidx.compose.material3.TextButton(onClick = onLoad) { Text("Refresh") }
                }
            }
            if (faucet.claiming) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { app.vowed.ui.components.Spinner(size = 20.dp); Text("Sending test tokens…") }
            faucet.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            faucet.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                "Network fees are paid in devnet SOL, which these tokens do not include.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (wallet != null) {
                androidx.compose.material3.TextButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("wallet address", wallet))
                }) { Text("Copy my wallet address (${short(wallet)})") }
            }
        }
    }
}
