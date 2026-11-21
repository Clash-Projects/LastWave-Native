package com.lastwave.app.ui.moods

import androidx.compose.ui.graphics.Color

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
    // Vivid top-left fading to a deep, hue-shifted corner; the top value stays under the
    // white-label contrast limit (4.5:1) for every hue.
    val toneSaturation = (0.80f + saturation * 0.10f).coerceAtMost(0.90f)
    return MoodTileColors(
        accent = Color.hsv(hue, 0.64f, 0.96f),
        surfaceTop = Color.hsv(hue, toneSaturation, 0.42f),
        surfaceBottom = Color.hsv((hue + 24f) % 360f, toneSaturation, 0.22f),
    )
}
