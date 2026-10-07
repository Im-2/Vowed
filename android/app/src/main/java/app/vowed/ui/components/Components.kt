package app.vowed.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.vowed.R
import app.vowed.ui.theme.VowedColors
import app.vowed.ui.theme.VowedTheme

/** The Vowed logo mark (vector drawable, same artwork as the launcher icon). */
@Composable
fun VowedMark(size: Dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(painterResource(R.drawable.ic_logo_mark), contentDescription = "Vowed", modifier = modifier.size(size))
}

/** A white rounded card with a soft shadow. Pass [onClick] to make it pressable (it dips slightly while pressed). */
@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = AppCardImpl(modifier, null, MaterialTheme.colorScheme.surface, content)

@Composable
fun AppCard(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = AppCardImpl(modifier, onClick, MaterialTheme.colorScheme.surface, content)

@Composable
fun AppCard(modifier: Modifier = Modifier, container: Color, content: @Composable ColumnScope.() -> Unit) = AppCardImpl(modifier, null, container, content)

@Composable
private fun AppCardImpl(
    modifier: Modifier,
    onClick: (() -> Unit)?,
    container: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, tween(90), label = "press")
    val shadow = VowedTheme.extra.cardShadow
    val base = modifier.scale(scale).shadow(shadow, shape, ambientColor = VowedColors.Indigo.copy(alpha = 0.12f), spotColor = VowedColors.Indigo.copy(alpha = 0.18f)).clip(shape)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = base, shape = shape, color = container, interactionSource = source) { Column(content = content) }
    } else {
        Surface(modifier = base, shape = shape, color = container) { Column(content = content) }
    }
}

/** The main action: fully rounded, indigo, at least 52dp tall. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, leading: (@Composable () -> Unit)? = null) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.defaultMinSize(minHeight = 52.dp).fillMaxWidth(), shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), disabledContentColor = Color.White.copy(alpha = 0.8f)),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 14.dp),
    ) {
        leading?.invoke()
        if (leading != null) Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** The quieter action: a soft violet pill. */
@Composable
fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.defaultMinSize(minHeight = 52.dp).fillMaxWidth(), shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Small pill action inside cards (for example "Check in"). */
@Composable
fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.defaultMinSize(minHeight = 40.dp), shape = CircleShape,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
    ) { Text(text, style = MaterialTheme.typography.labelMedium) }
}

enum class Tone { Neutral, Primary, Success, Warning, Danger }

/**
 * Honesty labels (DEMO, SAMPLE, TEST, SIMULATED, trust tiers) are drawn with this one component, so they look the same everywhere and
 * can be restyled but never go missing.
 */
@Composable
fun StatusBadge(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier) {
    val x = VowedTheme.extra
    val (bg, fg) = when (tone) {
        Tone.Neutral -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        Tone.Primary -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
        Tone.Success -> x.successTint to x.success
        Tone.Warning -> x.warningTint to x.warning
        Tone.Danger -> x.demoTint to x.demo
    }
    Box(modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

fun trustTone(tier: String?): Tone = when (tier) { "high" -> Tone.Success; "medium" -> Tone.Primary; else -> Tone.Warning }
fun trustLabel(tier: String?): String = when (tier) { "high" -> "High trust"; "medium" -> "Medium trust"; "low" -> "Low trust"; else -> "Trust: ?" }

/** A round avatar built from an address: a gradient chosen by the address and its first letters. */
@Composable
fun Avatar(seed: String, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val palette = listOf(
        listOf(Color(0xFF7C6CF0), Color(0xFF4A35D0)), listOf(Color(0xFFFF9F68), Color(0xFFE5566D)), listOf(Color(0xFF38C7A0), Color(0xFF1E8F6F)),
        listOf(Color(0xFFFFC857), Color(0xFFE8932A)), listOf(Color(0xFF5BB5FF), Color(0xFF3566D6)), listOf(Color(0xFFE48BF0), Color(0xFF9B4FD1)),
    )
    val colors = palette[(seed.hashCode() and 0x7fffffff) % palette.size]
    Box(modifier.size(size).clip(CircleShape).background(Brush.linearGradient(colors)), contentAlignment = Alignment.Center) {
        Text(seed.take(2).uppercase(), style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, art: (@Composable () -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    AppCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            art?.invoke()
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            action?.invoke()
        }
    }
}

/** Dots for the onboarding pager: the current one is a longer pill. */
@Composable
fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val w by animateFloatAsState(if (i == current) 26f else 8f, tween(220, easing = FastOutSlowInEasing), label = "dot")
            Box(Modifier.width(w.dp).height(8.dp).clip(CircleShape).background(if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
        }
    }
}

/** A drawn check mark that animates in. */
@Composable
fun AnimatedCheck(size: Dp = 72.dp, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    val pop = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) {
        pop.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
    }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(450, 150, FastOutSlowInEasing)) }
    Box(modifier.size(size).scale(pop.value).clip(CircleShape).background(VowedTheme.extra.successTint), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.5f)) {
            val w = this.size.width
            val p1 = Offset(w * 0.08f, w * 0.54f)
            val p2 = Offset(w * 0.38f, w * 0.84f)
            val p3 = Offset(w * 0.94f, w * 0.20f)
            val t = progress.value
            val first = (t / 0.4f).coerceIn(0f, 1f)
            val second = ((t - 0.4f) / 0.6f).coerceIn(0f, 1f)
            val stroke = Stroke(width = w * 0.14f, cap = StrokeCap.Round)
            val c = Color(0xFF1E9E6F)
            drawLine(c, p1, Offset(p1.x + (p2.x - p1.x) * first, p1.y + (p2.y - p1.y) * first), stroke.width, StrokeCap.Round)
            if (second > 0f) drawLine(c, p2, Offset(p2.x + (p3.x - p2.x) * second, p2.y + (p3.y - p2.y) * second), stroke.width, StrokeCap.Round)
        }
    }
}

/** A centered pop-up with the animated check, a title, a line of text and one button. */
@Composable
fun SuccessPopup(title: String, body: String, button: String, onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedCheck()
                Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                PrimaryButton(button, onDone)
            }
        }
    }
}

/** The loading spinner used everywhere (indigo, round caps). */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 28.dp) {
    CircularProgressIndicator(modifier.size(size), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primaryContainer, strokeWidth = 3.dp, strokeCap = StrokeCap.Round)
}

/** A rounded square holding an icon-like glyph on a gradient (used for category and challenge thumbnails). */
@Composable
fun GradientTile(colors: List<Color>, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(MaterialTheme.shapes.medium).background(Brush.linearGradient(colors)), contentAlignment = Alignment.Center) { content() }
}
