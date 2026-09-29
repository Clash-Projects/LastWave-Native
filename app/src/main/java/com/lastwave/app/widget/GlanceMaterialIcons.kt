package com.lastwave.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.collection.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.glance.ImageProvider
import androidx.glance.LocalContext

/**
 * High-performance Material 3 Compose Vector to Glance ImageProvider bridge.
 *
 * Allows using pure Compose Material 3 icons (e.g. Icons.Rounded.PlayArrow, Icons.Rounded.Pause)
 * directly inside Jetpack Glance widgets with 0ms overhead via an in-memory LRU cache.
 */
object GlanceMaterialIcons {

    private val iconCache = LruCache<String, ImageProvider>(32)

    /**
     * Renders a [VectorPainter] to an Android [Bitmap] at the given size and tint.
     */
    fun renderPainterToBitmap(
        painter: VectorPainter,
        sizePx: Int,
        tint: Color? = null,
        density: Density = Density(1f)
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val drawScope = CanvasDrawScope()

        drawScope.draw(
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            canvas = androidx.compose.ui.graphics.Canvas(canvas),
            size = Size(sizePx.toFloat(), sizePx.toFloat())
        ) {
            with(painter) {
                draw(
                    size = size,
                    colorFilter = tint?.let { ColorFilter.tint(it) }
                )
            }
        }
        return bitmap
    }

    /**
     * Returns a cached [ImageProvider] for the given [ImageVector] or renders a new one.
     */
    @Composable
    fun rememberVectorProvider(
        image: ImageVector,
        tint: Color = Color.White,
        sizeDp: Dp = 24.dp
    ): ImageProvider {
        val context = LocalContext.current
        val displayDensity = remember(context) { Density(context.resources.displayMetrics.density) }
        val sizePx = remember(sizeDp, displayDensity) {
            with(displayDensity) { sizeDp.roundToPx() }.coerceAtLeast(1)
        }
        val cacheKey = "${image.name}_${tint.toArgb()}_$sizePx"

        val cached = remember(cacheKey) {
            synchronized(iconCache) { iconCache.get(cacheKey) }
        }
        if (cached != null) {
            return cached
        }

        // Bridge Compose runtime LocalDensity so rememberVectorPainter can resolve properly
        var resolvedPainter: VectorPainter? = null
        CompositionLocalProvider(LocalDensity provides displayDensity) {
            resolvedPainter = rememberVectorPainter(image = image)
        }
        val painter = resolvedPainter ?: error("Failed to resolve VectorPainter for ${image.name}")

        return remember(cacheKey, painter) {
            val bitmap = renderPainterToBitmap(
                painter = painter,
                sizePx = sizePx,
                tint = tint,
                density = displayDensity
            )
            val provider = ImageProvider(bitmap)
            synchronized(iconCache) {
                iconCache.put(cacheKey, provider)
            }
            provider
        }
    }
}
