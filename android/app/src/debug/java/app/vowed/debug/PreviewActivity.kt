package app.vowed.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.vowed.ui.HomeScreen
import app.vowed.ui.components.VowedBottomBar
import app.vowed.ui.theme.VowedTheme

/**
 * DEBUG BUILDS ONLY. Shows one screen with made-up data so screenshots can be taken without a wallet, a server or a long emulator session:
 *   adb shell am start -n app.vowed/.debug.PreviewActivity --es screen home
 * Nothing here talks to the backend or the wallet, and release builds do not contain this class.
 */
class PreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val screen = intent.getStringExtra("screen") ?: "home"
        setContent { VowedTheme { Surface(Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) { Preview(screen) } } }
    }
}

@Composable
private fun Preview(screen: String) {
    val none = {}
    val tab = when (screen) {
        "home", "home-empty" -> "home"
        "explore", "categories" -> screen
        "squads" -> "squads"
        "you" -> "settings"
        else -> null
    }
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        bottomBar = { if (tab != null) VowedBottomBar(tab, none, none, none, none, none) },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (screen) {
                "home" -> HomeScreen(Samples.state(), Samples.ME, none, {}, {}, none, none, none, none, none)
                "home-empty" -> HomeScreen(Samples.state(empty = true), Samples.ME, none, {}, {}, none, none, none, none, none)
                else -> PreviewMore(screen)
            }
        }
    }
}
