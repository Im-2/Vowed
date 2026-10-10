package app.vowed.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vowed.data.FaucetBalances
import app.vowed.data.StakeToken
import app.vowed.data.StakeTokens

/** The icon of a token: the supplied SKR icon for test SKR, an original blue dollar coin for test USDC (not Circle's artwork), a grey coin for others. */
@Composable
fun TokenIcon(symbol: String, size: Dp = 32.dp, modifier: Modifier = Modifier) {
    when (symbol) {
        StakeTokens.TSKR -> SkrIcon(size, modifier)
        else -> {
            val blue = symbol == StakeTokens.TUSDC || symbol.startsWith("USDC")
            Box(
                modifier.size(size).clip(CircleShape).background(Brush.linearGradient(if (blue) listOf(Color(0xFF5BC8FF), Color(0xFF3566D6)) else listOf(Color(0xFFB9A8FF), Color(0xFF7C6CF0)))),
                contentAlignment = Alignment.Center,
            ) { Text("$", color = Color.White, fontSize = (size.value * 0.55f).sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/**
 * Choose the token a NEW challenge is staked in (test USDC or test SKR). Each choice shows its icon, symbol and the person's balance. A pool has one
 * stake token for its whole life: joiners see the pool's token and cannot change it (see the join screen).
 */
@Composable
fun TokenSelector(options: List<StakeToken>, chosen: StakeToken?, balances: FaucetBalances?, onPick: (StakeToken) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Stake token", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { t ->
                val on = t.mint == chosen?.mint
                Surface(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = "Stake in ${t.symbol}") { onPick(t) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TokenIcon(t.symbol, 36.dp)
                        Column {
                            Text(t.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(t.balanceOf(balances)?.let { "You have ${t.format(it)}" } ?: "balance not loaded", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Text(StakeTokens.HONESTY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
    }
}
