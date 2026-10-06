package app.vowed

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val walletAdapter = MobileWalletAdapter(
        connectionIdentity = ConnectionIdentity(
            identityUri = Uri.parse("https://vowed.app"),
            iconUri = Uri.parse("favicon.ico"),
            identityName = "Vowed",
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Must be created before the activity is STARTED.
        val sender = ActivityResultSender(this)
        setContent { MaterialTheme { Hello(walletAdapter, sender) } }
    }
}

@Composable
private fun Hello(adapter: MobileWalletAdapter, sender: ActivityResultSender) {
    var status by remember { mutableStateOf("Not connected") }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, androidx.compose.ui.Alignment.CenterVertically),
    ) {
        Text("Vowed (hello world)", style = MaterialTheme.typography.headlineMedium)
        Text(status)
        Button(onClick = {
            scope.launch {
                status = when (val r = adapter.connect(sender)) {
                    is TransactionResult.Success -> "Connected: " + r.authResult.accounts.first().publicKey.joinToString("") { "%02x".format(it) }.take(16) + "..."
                    is TransactionResult.NoWalletFound -> "No MWA wallet found"
                    is TransactionResult.Failure -> "Failed: " + r.message
                }
            }
        }) { Text("Connect wallet (MWA)") }
    }
}
