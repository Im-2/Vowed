package app.vowed.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
 * The test-token panel, shown in a bottom sheet from the pill in the top bar: the server sends a small fixed amount of TEST USDC and TEST SKR
 * (devnet only, no value) to this wallet, once per wallet per day. It always says that these are test tokens, and that network fees still need
 * devnet SOL.
 */
@Composable
fun FaucetPanel(faucet: FaucetUi, wallet: String?, onLoad: () -> Unit, onClaim: () -> Unit) {
    LaunchedEffect(Unit) { onLoad() }
    val ctx = LocalContext.current
    val st = faucet.status
    val now = rememberNowSeconds()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Test tokens", style = MaterialTheme.typography.titleMedium)
        Text(
            st?.label ?: "TEST TOKENS: tUSDC and tSKR exist only on Solana devnet and have no real value.",
            style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color(0xFFB7791F),
        )
        if (st != null) {
            Text("You have: ${fmt(st.balances.tUSDC)} tUSDC · ${fmt(st.balances.tSKR)} tSKR", style = MaterialTheme.typography.titleSmall)
            val usdc = st.tokens.firstOrNull { it.symbol == "tUSDC" }
            val skr = st.tokens.firstOrNull { it.symbol == "tSKR" }
            if (usdc != null && skr != null) Text("One claim gives ${fmt(usdc.amount)} tUSDC and ${fmt(skr.amount)} tSKR, once a day per wallet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val wait = st.nextClaimAt - now
            when {
                st.canClaim -> app.vowed.ui.components.PrimaryButton("Get test tokens", onClaim, enabled = !faucet.claiming)
                wait > 0 -> app.vowed.ui.components.SoftButton("Available again in ${waitText(wait)}", {}, enabled = false)
                st.claimsLeftToday <= 0 -> app.vowed.ui.components.SoftButton("Out of test tokens for today (UTC). Try tomorrow.", {}, enabled = false)
                else -> app.vowed.ui.components.SoftButton("Refresh", onLoad)
            }
        } else if (faucet.loading) {
            app.vowed.ui.components.Spinner()
        }
        if (faucet.claiming) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { app.vowed.ui.components.Spinner(size = 20.dp); Text("Sending test tokens…") }
        faucet.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        faucet.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Network fees are paid in devnet SOL, which these tokens do not include.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (wallet != null) {
            androidx.compose.material3.TextButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("wallet address", wallet))
            }) { Text("Copy my wallet address (${short(wallet)})") }
        }
    }
}
