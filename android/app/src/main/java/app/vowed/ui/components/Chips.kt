package app.vowed.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.vowed.ui.theme.VowedTheme

/** What a chip means. Red is not here: red is for errors (see [Tone.Error]) and nothing else. */
enum class ChipKind { Demo, Sample, Test, Simulated, Neutral, Primary, Success }

/**
 * The one chip style for the whole app: 24 dp tall, small bold label. Caution labels (DEMO, TEST, SIMULATED) are an amber outline; SAMPLE is a
 * soft violet fill; trust and status are neutral or tinted. With [more] set the chip is tappable and opens a bottom sheet that carries the full
 * explanation, so the long honesty sentences are one tap away instead of repeated as paragraphs on every card.
 */
@Composable
fun LabelChip(text: String, kind: ChipKind = ChipKind.Neutral, modifier: Modifier = Modifier, dot: Color? = null, moreTitle: String? = null, more: String? = null) {
    var open by remember { mutableStateOf(false) }
    val x = VowedTheme.extra
    val cs = MaterialTheme.colorScheme
    val (bg, fg, border) = when (kind) {
        ChipKind.Demo, ChipKind.Test, ChipKind.Simulated -> Triple(Color.Transparent, x.warning, x.warning.copy(alpha = 0.7f))
        ChipKind.Sample -> Triple(cs.primaryContainer, cs.primary, Color.Transparent)
        ChipKind.Primary -> Triple(cs.primaryContainer, cs.primary, Color.Transparent)
        ChipKind.Success -> Triple(x.successTint, x.success, Color.Transparent)
        ChipKind.Neutral -> Triple(cs.surfaceVariant, cs.onSurfaceVariant, Color.Transparent)
    }
    var m = modifier.height(24.dp).clip(CircleShape).background(bg)
    if (border != Color.Transparent) m = m.border(BorderStroke(1.dp, border), CircleShape)
    if (more != null) m = m.clickable(role = Role.Button, onClickLabel = "More about $text") { open = true }
    Row(m.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (dot != null) Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
        if (more != null) Text("i", style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.8f))
    }
    if (open && more != null) InfoSheet(moreTitle ?: text, more) { open = false }
}

/** A small round "i" button that opens a bottom sheet with [body]. */
@Composable
fun InfoIcon(title: String, body: String, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    var open by remember { mutableStateOf(false) }
    Box(
        modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "More about $title") { open = true },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val w = size.width
            drawCircle(tint, w * 0.46f, Offset(w / 2, w / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.08f))
            drawCircle(tint, w * 0.055f, Offset(w / 2, w * 0.31f))
            drawLine(tint, Offset(w / 2, w * 0.45f), Offset(w / 2, w * 0.74f), w * 0.09f, StrokeCap.Round)
        }
    }
    if (open) InfoSheet(title, body) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(title: String, body: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SoftButton("Got it", onDismiss)
        }
    }
}

/** A slim single-line banner (for example DEMO MODE) with an info icon that opens the full explanation. */
@Composable
fun SlimBanner(text: String, kind: ChipKind, infoTitle: String, infoBody: String, modifier: Modifier = Modifier) {
    val x = VowedTheme.extra
    val fg = if (kind == ChipKind.Demo || kind == ChipKind.Test || kind == ChipKind.Simulated) x.warning else MaterialTheme.colorScheme.primary
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp).clip(MaterialTheme.shapes.small).background(fg.copy(alpha = 0.10f)).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
        InfoIcon(infoTitle, infoBody, tint = fg)
    }
}
