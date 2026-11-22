package com.lastwave.app.ui.moods

import androidx.compose.ui.graphics.Color

internal data class MoodTileColors(
    val accent: Color,
    val surfaceTop: Color,
    val surfaceBottom: Color,
    val content: Color = Color.White,
    // Light sources the tile draws over its dark ink base; not used for label contrast.
    val glowPrimary: Color = accent,
    val glowSecondary: Color = accent,
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
    // A dark ink base keeps white labels readable for every hue; the colour comes from two
    // glows (the category hue and an analogous neighbour) that the tile paints on top.
    val neighbour = (hue + 55f) % 360f
    return MoodTileColors(
        accent = Color.hsv(hue, 0.55f, 1f),
        surfaceTop = Color.hsv(hue, 0.70f, 0.30f),
        surfaceBottom = Color.hsv(neighbour, 0.80f, 0.14f),
        glowPrimary = Color.hsv(hue, 0.80f, 1f),
        glowSecondary = Color.hsv(neighbour, 0.85f, 0.95f),
    )
}
