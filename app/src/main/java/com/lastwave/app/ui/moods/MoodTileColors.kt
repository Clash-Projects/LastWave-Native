package com.lastwave.app.ui.moods

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

internal data class MoodTileColors(
    val accent: Color,
    val surfaceTop: Color,
    val surfaceBottom: Color,
    val content: Color = Color.White,
)

/** Keeps YTM's colour identity; neutral categories receive a stable colour of their own. */
internal fun moodTileColors(argb: Long, key: String): MoodTileColors {
    val source = Color(argb)
    val maximum = maxOf(source.red, source.green, source.blue)
    val minimum = minOf(source.red, source.green, source.blue)
    val delta = maximum - minimum
    val saturation = if (maximum > 0f) delta / maximum else 0f
    val neutralHues = floatArrayOf(166f, 218f, 265f, 332f, 18f, 44f, 192f, 145f)
    val hue = if (saturation < 0.18f || maximum < 0.10f) {
        neutralHues[((key.hashCode().toLong() and 0xFFFFFFFFL) % neutralHues.size).toInt()]
    } else {
        val sector = when (maximum) {
            source.red -> (source.green - source.blue) / delta
            source.green -> (source.blue - source.red) / delta + 2f
            else -> (source.red - source.green) / delta + 4f
        }
        (sector * 60f + 360f) % 360f
    }
    val toneSaturation = (0.70f + saturation * 0.12f).coerceAtMost(0.82f)
    return MoodTileColors(
        accent = Color.hsv(hue, 0.64f, 0.96f),
        surfaceTop = Color.hsv(hue, toneSaturation, 0.43f),
        surfaceBottom = Color.hsv((hue + 8f) % 360f, toneSaturation, 0.23f),
    )
}

/** Muted, translucent tiles keep the category hue on Home and the genres page. */
internal fun homeMoodTileColors(argb: Long, key: String): MoodTileColors {
    val accent = moodTileColors(argb, key).accent
    val warmBase = Color(0xFFE8E5E0)
    return MoodTileColors(
        accent = lerp(warmBase, accent, 0.56f),
        surfaceTop = lerp(warmBase, accent, 0.14f).copy(alpha = 0.86f),
        surfaceBottom = lerp(warmBase, accent, 0.24f).copy(alpha = 0.84f),
        content = Color(0xFF242832),
    )
}
