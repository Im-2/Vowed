package app.vowed.ui.components

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.vowed.R
import app.vowed.ui.art.GlyphIcon
import app.vowed.ui.art.categoryGlyph

/**
 * Category photos (res/drawable-nodpi/photo_<category>.webp, made by scripts/gen-photos.py from the owner's files in design/photos). A category
 * with no photo file returns null and the caller keeps the gradient tile with its icon. Sources and licenses: docs/photo-credits.md.
 */
object Photos {
    /** the drawable id of the photo for [category] ("study", "fitness", "steps", "sleep", "detox", "focus", "location", "custom"), or 0 when there is none */
    fun resFor(ctx: Context, category: String): Int = ctx.resources.getIdentifier("photo_$category", "drawable", ctx.packageName)
}

/** Dark at the bottom, clear towards the top, so white text stays readable on bright photos too. */
val PhotoScrim: Brush = Brush.verticalGradient(
    0.0f to Color.Black.copy(alpha = 0.10f),
    0.40f to Color.Black.copy(alpha = 0.18f),
    1.0f to Color.Black.copy(alpha = 0.82f),
)

/** The photo for a category filling its box (cropped), with the scrim on top; shows the gradient tile and icon when the photo is missing. */
@Composable
fun CategoryBackdrop(category: String, modifier: Modifier = Modifier, scrim: Brush = PhotoScrim, content: @Composable BoxScope.() -> Unit = {}) {
    val ctx = LocalContext.current
    val res = remember(category) { Photos.resFor(ctx, category) }
    Box(modifier) {
        if (res != 0) {
            Image(painterResource(res), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(scrim))
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(CategoryStyle.colors(category))))
            Box(Modifier.align(androidx.compose.ui.Alignment.TopStart).padding(16.dp)) { GlyphIcon(categoryGlyph(category), Color.White, 32.dp) }
        }
        content()
    }
}

/** A small rounded thumbnail: the category photo, or the gradient tile with the icon. */
@Composable
fun CategoryThumb(category: String, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val res = remember(category) { Photos.resFor(ctx, category) }
    if (res != 0) {
        Image(painterResource(res), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.size(size).clip(androidx.compose.material3.MaterialTheme.shapes.medium))
    } else {
        GradientTile(CategoryStyle.colors(category), modifier.size(size)) { GlyphIcon(categoryGlyph(category), Color.White, size * 0.55f) }
    }
}

/** The SKR token icon (the owner's file, design/brand/skr-logo.png). */
@Composable
fun SkrIcon(size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.skr_logo), contentDescription = "SKR", modifier = modifier.size(size))
}
