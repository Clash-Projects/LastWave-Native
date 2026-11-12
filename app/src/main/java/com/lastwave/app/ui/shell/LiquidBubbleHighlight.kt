package com.lastwave.app.ui.shell

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp

/**
 * Local illumination over Liquid's sampled pixels, giving its capsule a convex rim.
 * Geometry and brushes are cached; the press intensity is observed only while drawing.
 * Keep this inside the lens's graphics layer so the lighting moves with the glass.
 */
internal fun Modifier.liquidBubbleHighlight(
    isDark: Boolean,
    cornerFraction: Float = 0.5f,
    intensity: () -> Float,
): Modifier = drawWithCache {
    if (size.width <= 0f || size.height <= 0f) {
        onDrawWithContent { drawContent() }
    } else {
        val radius = size.minDimension * cornerFraction.coerceIn(0f, 0.5f)
        val silhouette = Path().apply {
            addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius)))
        }
        val rimWidth = 0.7.dp.toPx().coerceAtMost(radius)
        val rimInset = rimWidth / 2f
        val rim = Path().apply {
            addRoundRect(
                RoundRect(
                    rimInset,
                    rimInset,
                    size.width - rimInset,
                    size.height - rimInset,
                    CornerRadius((radius - rimInset).coerceAtLeast(0f)),
                ),
            )
        }
        val rimStroke = Stroke(rimWidth)
        val oppositeStroke = Stroke(0.45.dp.toPx().coerceAtMost(radius))
        val broadShade = Stroke(6.dp.toPx().coerceAtMost(radius))
        val middleShade = Stroke(3.5.dp.toPx().coerceAtMost(radius))
        val narrowShade = Stroke(1.5.dp.toPx().coerceAtMost(radius))

        // Light comes from above-left. Most of the rim remains transparent rather
        // than reading as a painted border around the complete capsule.
        val upperReflection = Brush.linearGradient(
            0f to Color.White.copy(alpha = if (isDark) 0.68f else 0.80f),
            0.24f to Color.White.copy(alpha = 0.30f),
            0.52f to Color.Transparent,
            1f to Color.Transparent,
            start = Offset.Zero,
            end = Offset(size.width * 0.78f, size.height),
        )
        val oppositeReflection = Brush.linearGradient(
            0f to Color.Transparent,
            0.62f to Color.Transparent,
            0.84f to Color.White.copy(alpha = 0.06f),
            1f to Color.White.copy(alpha = if (isDark) 0.28f else 0.34f),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        val lowerShade = Brush.verticalGradient(
            0f to Color.Transparent,
            0.50f to Color.Transparent,
            0.74f to Color.Black.copy(alpha = 0.025f),
            1f to Color.Black.copy(alpha = if (isDark) 0.13f else 0.09f),
            endY = size.height,
        )
        val surfaceReflection = Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (isDark) 0.055f else 0.045f),
            0.38f to Color.White.copy(alpha = 0.006f),
            0.64f to Color.Transparent,
            1f to Color.White.copy(alpha = 0.012f),
            endY = size.height,
        )

        onDrawWithContent {
            drawContent()
            val strength = intensity().coerceIn(0f, 1f)
            if (strength > 0f) {
                clipPath(silhouette) {
                    drawRect(surfaceReflection, alpha = strength)
                    // Clipping strokes centred on the outline leaves a soft inset
                    // shade. Layered narrow bands avoid a blur pass or hard bevel.
                    drawPath(silhouette, lowerShade, alpha = strength * 0.40f, style = broadShade)
                    drawPath(silhouette, lowerShade, alpha = strength * 0.50f, style = middleShade)
                    drawPath(silhouette, lowerShade, alpha = strength * 0.65f, style = narrowShade)
                    drawPath(rim, upperReflection, alpha = strength, style = rimStroke)
                    drawPath(rim, oppositeReflection, alpha = strength, style = oppositeStroke)
                }
            }
        }
    }
}
