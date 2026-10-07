package app.vowed.ui.art

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Simple one-colour icons drawn in code (so they scale, tint with the theme and need no image files). */
enum class Glyph { Home, Explore, Plus, People, User, Bell, Study, Steps, Fitness, Sleep, Screen, Focus, Place, Custom, Trophy, Chevron }

fun categoryGlyph(category: String): Glyph = when (category) {
    "study" -> Glyph.Study
    "steps" -> Glyph.Steps
    "fitness" -> Glyph.Fitness
    "sleep" -> Glyph.Sleep
    "detox" -> Glyph.Screen
    "location" -> Glyph.Place
    "focus" -> Glyph.Focus
    else -> Glyph.Custom
}

@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, tint) }
}

private fun DrawScope.drawGlyph(g: Glyph, c: Color) {
    val w = size.width
    val sw = w * 0.09f
    val line = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (g) {
        Glyph.Home -> {
            val p = Path().apply { moveTo(w * 0.14f, w * 0.48f); lineTo(w * 0.5f, w * 0.16f); lineTo(w * 0.86f, w * 0.48f); moveTo(w * 0.24f, w * 0.42f); lineTo(w * 0.24f, w * 0.84f); lineTo(w * 0.76f, w * 0.84f); lineTo(w * 0.76f, w * 0.42f) }
            drawPath(p, c, style = line)
            drawRoundRect(c, Offset(w * 0.43f, w * 0.60f), Size(w * 0.14f, w * 0.24f), CornerRadius(w * 0.03f))
        }
        Glyph.Explore -> {
            drawCircle(c, w * 0.36f, Offset(w / 2, w / 2), style = line)
            val p = Path().apply { moveTo(w * 0.62f, w * 0.38f); lineTo(w * 0.54f, w * 0.54f); lineTo(w * 0.38f, w * 0.62f); lineTo(w * 0.46f, w * 0.46f); close() }
            drawPath(p, c)
        }
        Glyph.Plus -> {
            drawLine(c, Offset(w * 0.5f, w * 0.22f), Offset(w * 0.5f, w * 0.78f), sw * 1.4f, StrokeCap.Round)
            drawLine(c, Offset(w * 0.22f, w * 0.5f), Offset(w * 0.78f, w * 0.5f), sw * 1.4f, StrokeCap.Round)
        }
        Glyph.People -> {
            drawCircle(c, w * 0.12f, Offset(w * 0.36f, w * 0.34f), style = line)
            drawArc(c, 180f, 180f, false, Offset(w * 0.12f, w * 0.52f), Size(w * 0.48f, w * 0.36f), style = line)
            drawCircle(c, w * 0.10f, Offset(w * 0.70f, w * 0.38f), style = line)
            drawArc(c, 200f, 140f, false, Offset(w * 0.52f, w * 0.56f), Size(w * 0.38f, w * 0.30f), style = line)
        }
        Glyph.User -> {
            drawCircle(c, w * 0.16f, Offset(w * 0.5f, w * 0.34f), style = line)
            drawArc(c, 180f, 180f, false, Offset(w * 0.2f, w * 0.56f), Size(w * 0.6f, w * 0.5f), style = line)
        }
        Glyph.Bell -> {
            val p = Path().apply { moveTo(w * 0.22f, w * 0.70f); quadraticTo(w * 0.30f, w * 0.62f, w * 0.30f, w * 0.44f); cubicTo(w * 0.30f, w * 0.16f, w * 0.70f, w * 0.16f, w * 0.70f, w * 0.44f); quadraticTo(w * 0.70f, w * 0.62f, w * 0.78f, w * 0.70f); close() }
            drawPath(p, c, style = line)
            drawArc(c, 0f, 180f, false, Offset(w * 0.42f, w * 0.74f), Size(w * 0.16f, w * 0.12f), style = line)
        }
        Glyph.Study -> {
            val p = Path().apply { moveTo(w * 0.5f, w * 0.26f); lineTo(w * 0.5f, w * 0.80f); moveTo(w * 0.5f, w * 0.26f); quadraticTo(w * 0.3f, w * 0.18f, w * 0.14f, w * 0.26f); lineTo(w * 0.14f, w * 0.78f); quadraticTo(w * 0.3f, w * 0.70f, w * 0.5f, w * 0.80f); quadraticTo(w * 0.7f, w * 0.70f, w * 0.86f, w * 0.78f); lineTo(w * 0.86f, w * 0.26f); quadraticTo(w * 0.7f, w * 0.18f, w * 0.5f, w * 0.26f) }
            drawPath(p, c, style = line)
        }
        Glyph.Steps -> {
            drawOval(c, Offset(w * 0.18f, w * 0.40f), Size(w * 0.26f, w * 0.42f))
            drawOval(c, Offset(w * 0.54f, w * 0.18f), Size(w * 0.26f, w * 0.42f))
            for (i in 0 until 3) {
                drawCircle(c, w * 0.035f, Offset(w * (0.20f + i * 0.09f), w * 0.33f))
                drawCircle(c, w * 0.035f, Offset(w * (0.56f + i * 0.09f), w * 0.11f))
            }
        }
        Glyph.Fitness -> {
            drawLine(c, Offset(w * 0.26f, w * 0.5f), Offset(w * 0.74f, w * 0.5f), sw, StrokeCap.Round)
            drawRoundRect(c, Offset(w * 0.10f, w * 0.34f), Size(w * 0.12f, w * 0.32f), CornerRadius(w * 0.04f))
            drawRoundRect(c, Offset(w * 0.78f, w * 0.34f), Size(w * 0.12f, w * 0.32f), CornerRadius(w * 0.04f))
            drawRoundRect(c, Offset(w * 0.22f, w * 0.26f), Size(w * 0.10f, w * 0.48f), CornerRadius(w * 0.04f))
            drawRoundRect(c, Offset(w * 0.68f, w * 0.26f), Size(w * 0.10f, w * 0.48f), CornerRadius(w * 0.04f))
        }
        Glyph.Sleep -> {
            val p = Path().apply { moveTo(w * 0.66f, w * 0.16f); cubicTo(w * 0.30f, w * 0.14f, w * 0.14f, w * 0.52f, w * 0.34f, w * 0.74f); cubicTo(w * 0.52f, w * 0.92f, w * 0.84f, w * 0.80f, w * 0.88f, w * 0.54f); cubicTo(w * 0.66f, w * 0.64f, w * 0.48f, w * 0.40f, w * 0.66f, w * 0.16f); close() }
            drawPath(p, c)
        }
        Glyph.Screen -> {
            drawRoundRect(c, Offset(w * 0.28f, w * 0.12f), Size(w * 0.44f, w * 0.76f), CornerRadius(w * 0.09f), style = line)
            drawLine(c, Offset(w * 0.44f, w * 0.78f), Offset(w * 0.56f, w * 0.78f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * 0.18f, w * 0.20f), Offset(w * 0.82f, w * 0.80f), sw, StrokeCap.Round)
        }
        Glyph.Focus -> {
            drawCircle(c, w * 0.34f, Offset(w * 0.5f, w * 0.56f), style = line)
            drawLine(c, Offset(w * 0.5f, w * 0.56f), Offset(w * 0.5f, w * 0.38f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * 0.5f, w * 0.56f), Offset(w * 0.62f, w * 0.62f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * 0.42f, w * 0.12f), Offset(w * 0.58f, w * 0.12f), sw, StrokeCap.Round)
        }
        Glyph.Place -> {
            val p = Path().apply { moveTo(w * 0.5f, w * 0.88f); cubicTo(w * 0.18f, w * 0.52f, w * 0.20f, w * 0.14f, w * 0.5f, w * 0.14f); cubicTo(w * 0.80f, w * 0.14f, w * 0.82f, w * 0.52f, w * 0.5f, w * 0.88f); close() }
            drawPath(p, c, style = line)
            drawCircle(c, w * 0.10f, Offset(w * 0.5f, w * 0.40f))
        }
        Glyph.Custom -> {
            val p = Path()
            val cx = w * 0.5f
            val cy = w * 0.52f
            val r = w * 0.36f
            for (i in 0 until 10) {
                val a = Math.PI / 5 * i - Math.PI / 2
                val rad = if (i % 2 == 0) r else r * 0.46f
                val x = cx + (rad * Math.cos(a)).toFloat()
                val y = cy + (rad * Math.sin(a)).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            drawPath(p, c)
        }
        Glyph.Trophy -> {
            drawRoundRect(c, Offset(w * 0.30f, w * 0.14f), Size(w * 0.40f, w * 0.34f), CornerRadius(w * 0.08f))
            drawArc(c, 90f, 180f, false, Offset(w * 0.12f, w * 0.18f), Size(w * 0.24f, w * 0.22f), style = line)
            drawArc(c, -90f, 180f, false, Offset(w * 0.64f, w * 0.18f), Size(w * 0.24f, w * 0.22f), style = line)
            drawLine(c, Offset(w * 0.5f, w * 0.48f), Offset(w * 0.5f, w * 0.72f), sw, StrokeCap.Round)
            drawRoundRect(c, Offset(w * 0.30f, w * 0.72f), Size(w * 0.40f, w * 0.10f), CornerRadius(w * 0.04f))
        }
        Glyph.Chevron -> {
            val p = Path().apply { moveTo(w * 0.38f, w * 0.22f); lineTo(w * 0.66f, w * 0.5f); lineTo(w * 0.38f, w * 0.78f) }
            drawPath(p, c, style = line)
        }
    }
}
