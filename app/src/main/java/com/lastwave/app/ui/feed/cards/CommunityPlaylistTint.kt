package com.lastwave.app.ui.feed.cards

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/** Retains the cover's hue while leaving headroom for rounding in the dark, muted tint. */
internal fun tintFromColor(dominantColor: Int): Color {
    val red = ((dominantColor ushr 16) and 0xFF) / 255f
    val green = ((dominantColor ushr 8) and 0xFF) / 255f
    val blue = (dominantColor and 0xFF) / 255f
    val high = maxOf(red, green, blue)
    val low = minOf(red, green, blue)
    val lightness = (high + low) / 2f
    val range = high - low
    val saturation = if (range == 0f) 0f else range / (1f - abs(2f * lightness - 1f))
    val hue = when {
        range == 0f -> 0f
        high == red -> ((green - blue) / range + 6f) % 6f
        high == green -> (blue - red) / range + 2f
        else -> (red - green) / range + 4f
    } * 60f
    return Color.hsl(hue, saturation.coerceAtMost(0.44f), lightness.coerceAtMost(0.15f))
}
