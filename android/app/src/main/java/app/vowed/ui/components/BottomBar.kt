package app.vowed.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.vowed.ui.art.Glyph
import app.vowed.ui.art.GlyphIcon
import app.vowed.ui.theme.VowedColors

/** The bottom navigation: Home, Explore, a raised round Create button in the middle, Squads, You. */
@Composable
fun VowedBottomBar(route: String?, onHome: () -> Unit, onExplore: () -> Unit, onCreate: () -> Unit, onSquads: () -> Unit, onYou: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().height(68.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceAround) {
                BarItem("Home", Glyph.Home, route == "home", onHome, Modifier.weight(1f))
                BarItem("Explore", Glyph.Explore, route == "explore" || route == "categories", onExplore, Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
                BarItem("Squads", Glyph.People, route == "squads" || route?.startsWith("squad/") == true, onSquads, Modifier.weight(1f))
                BarItem("You", Glyph.User, route == "settings" || route == "coach" || route == "letters" || route == "rewards", onYou, Modifier.weight(1f))
            }
        }
        // the round Create button sits half above the bar, so it is drawn outside the bar's clipped area
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 4.dp).size(62.dp).shadow(10.dp, CircleShape, ambientColor = VowedColors.Indigo, spotColor = VowedColors.Indigo)
                .clip(CircleShape).background(Brush.linearGradient(listOf(VowedColors.Violet, VowedColors.Indigo)))
                .clickable(role = Role.Button, onClickLabel = "Create a challenge", onClick = onCreate),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.Plus, Color.White, 30.dp) }
    }
}

@Composable
private fun BarItem(label: String, glyph: Glyph, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.height(56.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        GlyphIcon(glyph, tint, 26.dp)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
        Box(Modifier.padding(top = 2.dp).width(if (selected) 18.dp else 0.dp).height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
    }
}

/** Gradient pairs per goal category, shared by the Explore rows, the categories grid and the home cards. */
object CategoryStyle {
    fun colors(category: String): List<Color> = when (category) {
        "study" -> listOf(Color(0xFF8E7BFF), Color(0xFF5B3FE0))
        "steps" -> listOf(Color(0xFF4FD6A4), Color(0xFF1E9E6F))
        "fitness" -> listOf(Color(0xFFFF9F68), Color(0xFFE5566D))
        "sleep" -> listOf(Color(0xFF5B6CFF), Color(0xFF2F2096))
        "detox" -> listOf(Color(0xFF5BC8FF), Color(0xFF3566D6))
        "location" -> listOf(Color(0xFFFF8FC0), Color(0xFFC2457F))
        "focus" -> listOf(Color(0xFFFFC857), Color(0xFFE8932A))
        else -> listOf(Color(0xFFB9A8FF), Color(0xFF7C6CF0))
    }

    fun label(category: String): String = when (category) {
        "study" -> "Study"
        "steps" -> "Steps"
        "fitness" -> "Fitness"
        "sleep" -> "Sleep"
        "detox" -> "Screen time"
        "location" -> "Places"
        "focus" -> "Focus"
        else -> "Custom"
    }
}
