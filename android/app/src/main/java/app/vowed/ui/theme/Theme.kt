package app.vowed.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vowed.R

/**
 * The whole look of Vowed lives in this file: colors, type, shapes, spacing and elevation. Screens read these through MaterialTheme and
 * [VowedTheme.extra], so restyling the app means editing here.
 */
object VowedColors {
    // brand
    val Indigo = Color(0xFF4A35D0)        // primary: deep indigo-violet
    val IndigoDeep = Color(0xFF2F2096)
    val Violet = Color(0xFF7C6CF0)
    val VioletSoft = Color(0xFFA99BFF)    // the light face of the logo
    val Tint = Color(0xFFE9E5FF)          // soft violet tint (containers, selected chips)
    val TintStrong = Color(0xFFD9D2FF)
    val Lavender = Color(0xFFF4F1FF)      // app background
    val Surface = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1B1740)           // text on light
    val InkSoft = Color(0xFF5E5A82)
    val Outline = Color(0xFFCFC9F2)
    // status
    val Success = Color(0xFF1E9E6F)
    val SuccessTint = Color(0xFFDDF5EB)
    val Warning = Color(0xFFB7791F)
    val WarningTint = Color(0xFFFFF0D2)
    val Error = Color(0xFFD64545)
    val ErrorTint = Color(0xFFFFE3E3)
}

private val LightColors: ColorScheme = lightColorScheme(
    primary = VowedColors.Indigo, onPrimary = Color.White,
    primaryContainer = VowedColors.Tint, onPrimaryContainer = VowedColors.IndigoDeep,
    secondary = VowedColors.Violet, onSecondary = Color.White,
    secondaryContainer = VowedColors.TintStrong, onSecondaryContainer = VowedColors.IndigoDeep,
    tertiary = VowedColors.Warning, onTertiary = Color.White,
    tertiaryContainer = VowedColors.WarningTint, onTertiaryContainer = Color(0xFF5A3A08),
    background = VowedColors.Lavender, onBackground = VowedColors.Ink,
    surface = VowedColors.Surface, onSurface = VowedColors.Ink,
    surfaceVariant = VowedColors.Tint, onSurfaceVariant = VowedColors.InkSoft,
    surfaceContainerLowest = VowedColors.Surface, surfaceContainerLow = VowedColors.Surface, surfaceContainer = VowedColors.Surface,
    surfaceContainerHigh = VowedColors.Tint, surfaceContainerHighest = VowedColors.Tint,
    outline = VowedColors.Outline, outlineVariant = Color(0xFFE3DFF8),
    error = VowedColors.Error, onError = Color.White, errorContainer = VowedColors.ErrorTint, onErrorContainer = Color(0xFF7A1F1F),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFB3A7FF), onPrimary = Color(0xFF1F1470),
    primaryContainer = Color(0xFF332A8F), onPrimaryContainer = Color(0xFFE6E1FF),
    secondary = Color(0xFF9D90F5), tertiary = Color(0xFFE8B45A),
    background = Color(0xFF12102B), onBackground = Color(0xFFEAE7FF),
    surface = Color(0xFF1B1840), onSurface = Color(0xFFEAE7FF),
    surfaceVariant = Color(0xFF2A2660), onSurfaceVariant = Color(0xFFC0BBE6),
    surfaceContainerLowest = Color(0xFF1B1840), surfaceContainerLow = Color(0xFF1B1840), surfaceContainer = Color(0xFF1B1840),
    surfaceContainerHigh = Color(0xFF2A2660), surfaceContainerHighest = Color(0xFF2A2660),
    outline = Color(0xFF5B5799), outlineVariant = Color(0xFF3A3680),
    error = Color(0xFFFF8A8A), errorContainer = Color(0xFF5A1F1F),
)

/** Colors and sizes that Material does not have a slot for. */
@Immutable
class VowedExtra(
    val success: Color,
    val successTint: Color,
    val warning: Color,
    val warningTint: Color,
    val demo: Color,
    val demoTint: Color,
    val cardShadow: Dp,
    val screenPadding: Dp,
    val gap: Dp,
    val minTouch: Dp,
)

private val LightExtra = VowedExtra(VowedColors.Success, VowedColors.SuccessTint, VowedColors.Warning, VowedColors.WarningTint, VowedColors.Error, VowedColors.ErrorTint, 6.dp, 20.dp, 12.dp, 48.dp)
private val DarkExtra = VowedExtra(Color(0xFF59D3A2), Color(0xFF173B31), Color(0xFFE8B45A), Color(0xFF3F3015), Color(0xFFFF8A8A), Color(0xFF4A2020), 0.dp, 20.dp, 12.dp, 48.dp)
private val LocalExtra = staticCompositionLocalOf { LightExtra }

object VowedTheme {
    val extra: VowedExtra @Composable get() = LocalExtra.current
}

/** Corner radii: pill buttons come from the component defaults; cards are 20dp, small chips 14dp. */
private val VowedShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@OptIn(ExperimentalTextApi::class)
private fun nunito(weight: Int) = Font(R.font.nunito, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Nunito (SIL Open Font License, bundled in res/font, works offline). */
val VowedFont = FontFamily(nunito(400), nunito(500), nunito(600), nunito(700), nunito(800))

private fun ts(size: Int, weight: Int, line: Int, spacing: Double = 0.0) =
    TextStyle(fontFamily = VowedFont, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.sp)

private val VowedTypography = Typography(
    displaySmall = ts(34, 800, 40),
    headlineLarge = ts(30, 800, 36),
    headlineMedium = ts(26, 800, 32),
    headlineSmall = ts(22, 700, 28),
    titleLarge = ts(20, 700, 26),
    titleMedium = ts(17, 700, 23),
    titleSmall = ts(15, 700, 20),
    bodyLarge = ts(16, 500, 24),
    bodyMedium = ts(14, 500, 21),
    bodySmall = ts(12, 500, 18),
    labelLarge = ts(15, 700, 20, 0.1),
    labelMedium = ts(12, 700, 16, 0.2),
    labelSmall = ts(11, 700, 15, 0.3),
)

@Composable
fun VowedTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtra provides if (darkTheme) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = VowedTypography, shapes = VowedShapes, content = content)
    }
}
