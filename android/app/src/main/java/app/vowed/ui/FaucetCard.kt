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
import androidx.compose.material3.Card
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Test tokens", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                st?.label ?: "TEST TOKENS: tUSDC and tSKR exist only on Solana devnet and have no real value.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
            )
            if (st != null) {
                val usdc = st.tokens.firstOrNull { it.symbol == "tUSDC" }
                val skr = st.tokens.firstOrNull { it.symbol == "tSKR" }
                Text("You have: ${fmt(st.balances.tUSDC)} tUSDC · ${fmt(st.balances.tSKR)} tSKR", fontWeight = FontWeight.Medium)
                if (usdc != null && skr != null) Text("One claim gives ${fmt(usdc.amount)} tUSDC and ${fmt(skr.amount)} tSKR, once a day per wallet.", style = MaterialTheme.typography.bodySmall)
                val wait = st.nextClaimAt - now
                when {
                    st.canClaim -> Button(onClick = onClaim, enabled = !faucet.claiming, modifier = Modifier.fillMaxWidth()) { Text("Get test tokens") }
                    wait > 0 -> OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Available again in ${waitText(wait)}") }
                    st.claimsLeftToday <= 0 -> OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Out of test tokens for today (UTC). Try tomorrow.") }
                    else -> OutlinedButton(onClick = onLoad, modifier = Modifier.fillMaxWidth()) { Text("Refresh") }
                }
            } else if (faucet.loading) {
                CircularProgressIndicator(Modifier.padding(4.dp))
            }
            if (faucet.claiming) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(Modifier.padding(2.dp)); Text("Sending test tokens…") }
            faucet.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            faucet.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                "Network fees are paid in devnet SOL, which these tokens do not include.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (wallet != null) {
                OutlinedButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("wallet address", wallet))
                }) { Text("Copy my wallet address (${short(wallet)})") }
            }
        }
    }
}
