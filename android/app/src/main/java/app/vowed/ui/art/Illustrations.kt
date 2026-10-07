package app.vowed.ui.art

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.vowed.ui.theme.VowedColors

/**
 * Original, simple vector illustrations drawn in code (no image files, nothing borrowed). Each one fills a square and scales with it.
 * Colors come from the theme so a restyle changes them too.
 */
private val Amber = Color(0xFFFFC857)
private val Coral = Color(0xFFFF8F6B)
private val Mint = Color(0xFF38C7A0)

private fun DrawScope.sparkle(c: Offset, r: Float, color: Color) {
    val p = Path().apply {
        moveTo(c.x, c.y - r); quadraticTo(c.x, c.y, c.x + r, c.y); quadraticTo(c.x, c.y, c.x, c.y + r); quadraticTo(c.x, c.y, c.x - r, c.y); quadraticTo(c.x, c.y, c.x, c.y - r); close()
    }
    drawPath(p, color)
}

@Composable
private fun floatingOffset(period: Int, amplitude: Float): Float {
    val t = rememberInfiniteTransition(label = "float")
    val v = t.animateFloat(-amplitude, amplitude, infiniteRepeatable(tween(period, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse), label = "v")
    return v.value
}

/** 1. "Type any goal in plain words": a text card with typed lines and a cursor, with small goal tags around it. */
@Composable
fun GoalArt(size: Dp = 260.dp, modifier: Modifier = Modifier) {
    val bob = floatingOffset(2400, 6f)
    val blink = rememberInfiniteTransition(label = "cursor").animateFloat(0.2f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "c")
    Canvas(modifier.size(size)) {
        val w = this.size.width
        drawCircle(Brush.radialGradient(listOf(VowedColors.TintStrong, VowedColors.Tint.copy(alpha = 0f)), Offset(w / 2, w / 2), w * 0.5f), w * 0.5f, Offset(w / 2, w / 2))
        // the card
        val cardTop = w * 0.26f + bob
        drawRoundRect(VowedColors.Indigo.copy(alpha = 0.12f), Offset(w * 0.14f, cardTop + w * 0.03f), Size(w * 0.72f, w * 0.40f), CornerRadius(w * 0.07f))
        drawRoundRect(Color.White, Offset(w * 0.14f, cardTop), Size(w * 0.72f, w * 0.40f), CornerRadius(w * 0.07f))
        drawRoundRect(VowedColors.Indigo, Offset(w * 0.22f, cardTop + w * 0.07f), Size(w * 0.44f, w * 0.035f), CornerRadius(w * 0.02f))
        drawRoundRect(VowedColors.VioletSoft, Offset(w * 0.22f, cardTop + w * 0.14f), Size(w * 0.52f, w * 0.035f), CornerRadius(w * 0.02f))
        drawRoundRect(VowedColors.Tint, Offset(w * 0.22f, cardTop + w * 0.21f), Size(w * 0.30f, w * 0.035f), CornerRadius(w * 0.02f))
        drawRoundRect(VowedColors.Indigo.copy(alpha = blink.value), Offset(w * 0.54f, cardTop + w * 0.20f), Size(w * 0.012f, w * 0.06f), CornerRadius(w * 0.006f))
        // the send button
        drawCircle(VowedColors.Indigo, w * 0.055f, Offset(w * 0.77f, cardTop + w * 0.32f))
        val arrow = Path().apply { moveTo(w * 0.755f, cardTop + w * 0.335f); lineTo(w * 0.775f, cardTop + w * 0.305f); lineTo(w * 0.795f, cardTop + w * 0.335f) }
        drawPath(arrow, Color.White, style = Stroke(w * 0.012f, cap = StrokeCap.Round))
        // goal tags
        drawRoundRect(Amber, Offset(w * 0.06f, w * 0.14f - bob), Size(w * 0.30f, w * 0.085f), CornerRadius(w * 0.045f))
        drawRoundRect(Mint, Offset(w * 0.60f, w * 0.78f + bob), Size(w * 0.30f, w * 0.085f), CornerRadius(w * 0.045f))
        drawRoundRect(Coral, Offset(w * 0.64f, w * 0.12f + bob), Size(w * 0.24f, w * 0.085f), CornerRadius(w * 0.045f))
        drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(w * 0.09f, w * 0.162f - bob), Size(w * 0.15f, w * 0.02f), CornerRadius(w * 0.01f))
        drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(w * 0.64f, w * 0.80f + bob), Size(w * 0.15f, w * 0.02f), CornerRadius(w * 0.01f))
        drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(w * 0.67f, w * 0.142f + bob), Size(w * 0.12f, w * 0.02f), CornerRadius(w * 0.01f))
        sparkle(Offset(w * 0.10f, w * 0.62f), w * 0.04f, VowedColors.Violet)
        sparkle(Offset(w * 0.90f, w * 0.52f), w * 0.03f, Amber)
        sparkle(Offset(w * 0.30f, w * 0.90f), w * 0.025f, VowedColors.VioletSoft)
    }
}

/** 2. "Stake it and prove it with your phone": a phone showing a check ring, with a stack of coins and a small lock. */
@Composable
fun StakeArt(size: Dp = 260.dp, modifier: Modifier = Modifier) {
    val bob = floatingOffset(2600, 5f)
    Canvas(modifier.size(size)) {
        val w = this.size.width
        drawCircle(Brush.radialGradient(listOf(VowedColors.TintStrong, VowedColors.Tint.copy(alpha = 0f)), Offset(w / 2, w / 2), w * 0.5f), w * 0.5f, Offset(w / 2, w / 2))
        // phone
        val px = w * 0.30f
        val py = w * 0.10f + bob
        drawRoundRect(VowedColors.Indigo.copy(alpha = 0.14f), Offset(px, py + w * 0.03f), Size(w * 0.40f, w * 0.78f), CornerRadius(w * 0.07f))
        drawRoundRect(VowedColors.IndigoDeep, Offset(px, py), Size(w * 0.40f, w * 0.78f), CornerRadius(w * 0.07f))
        drawRoundRect(Color.White, Offset(px + w * 0.02f, py + w * 0.025f), Size(w * 0.36f, w * 0.73f), CornerRadius(w * 0.055f))
        drawRoundRect(VowedColors.Tint, Offset(px + w * 0.13f, py + w * 0.035f), Size(w * 0.14f, w * 0.014f), CornerRadius(w * 0.007f))
        // check ring
        val cx = px + w * 0.20f
        val cy = py + w * 0.28f
        drawCircle(VowedColors.Tint, w * 0.12f, Offset(cx, cy))
        drawArc(Mint, -90f, 270f, false, Offset(cx - w * 0.12f, cy - w * 0.12f), Size(w * 0.24f, w * 0.24f), style = Stroke(w * 0.028f, cap = StrokeCap.Round))
        val tick = Path().apply { moveTo(cx - w * 0.05f, cy + w * 0.005f); lineTo(cx - w * 0.01f, cy + w * 0.045f); lineTo(cx + w * 0.06f, cy - w * 0.04f) }
        drawPath(tick, Mint, style = Stroke(w * 0.03f, cap = StrokeCap.Round))
        // progress rows
        for (i in 0 until 3) {
            drawCircle(if (i < 2) Mint else VowedColors.Outline, w * 0.014f, Offset(px + w * 0.07f, py + w * (0.46f + i * 0.075f)))
            drawRoundRect(if (i < 2) VowedColors.VioletSoft else VowedColors.Tint, Offset(px + w * 0.10f, py + w * (0.445f + i * 0.075f)), Size(w * (0.20f - i * 0.03f), w * 0.026f), CornerRadius(w * 0.013f))
        }
        // coins
        for (i in 0 until 3) {
            val y = w * (0.80f - i * 0.045f) - bob
            drawOval(Color(0xFFE8A317), Offset(w * 0.64f, y + w * 0.012f), Size(w * 0.24f, w * 0.075f))
            drawOval(Amber, Offset(w * 0.64f, y), Size(w * 0.24f, w * 0.075f))
            drawOval(Color(0xFFFFE08A), Offset(w * 0.69f, y + w * 0.014f), Size(w * 0.14f, w * 0.045f))
        }
        // lock
        val lx = w * 0.07f
        val ly = w * 0.60f + bob
        drawArc(VowedColors.IndigoDeep, 180f, 180f, false, Offset(lx + w * 0.025f, ly - w * 0.06f), Size(w * 0.10f, w * 0.12f), style = Stroke(w * 0.022f, cap = StrokeCap.Round))
        drawRoundRect(VowedColors.Violet, Offset(lx, ly), Size(w * 0.15f, w * 0.12f), CornerRadius(w * 0.03f))
        drawCircle(Color.White, w * 0.014f, Offset(lx + w * 0.075f, ly + w * 0.055f))
        sparkle(Offset(w * 0.86f, w * 0.30f), w * 0.04f, Amber)
        sparkle(Offset(w * 0.14f, w * 0.30f), w * 0.03f, VowedColors.Violet)
    }
}

/** 3. "Play with friends, win when you stick to it": three friends on a podium with a star. */
@Composable
fun SquadArt(size: Dp = 260.dp, modifier: Modifier = Modifier) {
    val bob = floatingOffset(2200, 5f)
    Canvas(modifier.size(size)) {
        val w = this.size.width
        drawCircle(Brush.radialGradient(listOf(VowedColors.TintStrong, VowedColors.Tint.copy(alpha = 0f)), Offset(w / 2, w / 2), w * 0.5f), w * 0.5f, Offset(w / 2, w / 2))
        fun friend(cx: Float, base: Float, body: Color, hair: Color, skin: Color) {
            drawRoundRect(body, Offset(cx - w * 0.075f, base - w * 0.17f), Size(w * 0.15f, w * 0.17f), CornerRadius(w * 0.07f))
            drawCircle(skin, w * 0.055f, Offset(cx, base - w * 0.22f))
            drawArc(hair, 180f, 180f, true, Offset(cx - w * 0.058f, base - w * 0.285f), Size(w * 0.116f, w * 0.10f))
        }
        // podium
        val floor = w * 0.84f
        drawRoundRect(VowedColors.VioletSoft, Offset(w * 0.13f, floor - w * 0.12f), Size(w * 0.22f, w * 0.12f), CornerRadius(w * 0.02f))
        drawRoundRect(VowedColors.Indigo, Offset(w * 0.39f, floor - w * 0.20f), Size(w * 0.22f, w * 0.20f), CornerRadius(w * 0.02f))
        drawRoundRect(VowedColors.Violet, Offset(w * 0.65f, floor - w * 0.08f), Size(w * 0.22f, w * 0.08f), CornerRadius(w * 0.02f))
        friend(w * 0.24f, floor - w * 0.12f, Coral, Color(0xFF3B2A1E), Color(0xFFF2C6A0))
        friend(w * 0.50f, floor - w * 0.20f + bob * 0.5f, Mint, Color(0xFF1B1740), Color(0xFFC98E62))
        friend(w * 0.76f, floor - w * 0.08f, Amber, Color(0xFF7A3E1B), Color(0xFFE7B08A))
        // numbers on the podium
        for ((i, x) in listOf(0.24f to 2, 0.50f to 1, 0.76f to 3).withIndex()) {
            val px = w * x.first
            val py = floor - w * (if (i == 0) 0.06f else if (i == 1) 0.10f else 0.04f)
            drawCircle(Color.White.copy(alpha = 0.9f), w * 0.022f, Offset(px, py))
        }
        // star over the winner
        val star = Path()
        val cx = w * 0.50f
        val cy = w * 0.14f - bob
        val r = w * 0.065f
        for (i in 0 until 10) {
            val ang = Math.PI / 5 * i - Math.PI / 2
            val rad = if (i % 2 == 0) r else r * 0.45f
            val x = cx + (rad * Math.cos(ang)).toFloat()
            val y = cy + (rad * Math.sin(ang)).toFloat()
            if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        drawPath(star, Amber)
        sparkle(Offset(w * 0.20f, w * 0.30f), w * 0.04f, VowedColors.Violet)
        sparkle(Offset(w * 0.84f, w * 0.26f), w * 0.03f, Coral)
        sparkle(Offset(w * 0.64f, w * 0.12f), w * 0.022f, Amber)
    }
}

/** A small wallet glyph for the connect screen. */
@Composable
fun WalletArt(size: Dp = 120.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        drawCircle(VowedColors.Tint, w / 2, Offset(w / 2, w / 2))
        drawRoundRect(VowedColors.IndigoDeep, Offset(w * 0.20f, w * 0.30f), Size(w * 0.60f, w * 0.42f), CornerRadius(w * 0.08f))
        drawRoundRect(VowedColors.Indigo, Offset(w * 0.20f, w * 0.26f), Size(w * 0.52f, w * 0.18f), CornerRadius(w * 0.06f))
        drawRoundRect(VowedColors.VioletSoft, Offset(w * 0.56f, w * 0.44f), Size(w * 0.30f, w * 0.17f), CornerRadius(w * 0.06f))
        drawCircle(Color.White, w * 0.026f, Offset(w * 0.64f, w * 0.525f))
    }
}

@Suppress("unused")
@Composable
private fun Unused() { Box(Modifier.fillMaxSize()) }
