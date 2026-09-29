package com.lastwave.app.widget

import android.graphics.Bitmap
import androidx.palette.graphics.Palette

/**
 * Extracts complementary two-tone color palettes for the TwoToneSplitWidget.
 *
 * Implements the 50/50 Dual-Zone dynamic tonal scheme:
 * - Left pane: Lighter accent/container surface for controls and track metadata.
 * - Right pane: Deeper accent/surface container for the album artwork frame.
 *
 * Exposes separate light and dark variants for each color role so that
 * [TwoToneSplitWidget] can render correctly in both system Day and Night modes
 * without an extra IPC round-trip when the user toggles dark theme.
 */
object TwoTonePaletteExtractor {

    // Default Material You Two-Tone colors
    const val DEFAULT_LEFT_SURFACE: Long = 0xFFB6F3C5L     // Mint green (light)
    const val DEFAULT_RIGHT_SURFACE: Long = 0xFF113426L    // Deep forest green (dark)
    const val DEFAULT_ON_LEFT: Long = 0xFF0C2D1BL          // Dark emerald text on light
    const val DEFAULT_ON_RIGHT: Long = 0xFFB6F3C5L         // Mint icon accent on dark

    // Dark-mode defaults: surfaces swap roles
    const val DEFAULT_LEFT_SURFACE_DARK: Long = 0xFF1B4D30L  // Deeper forest for left in dark
    const val DEFAULT_RIGHT_SURFACE_DARK: Long = 0xFF0A1F15L // Very deep emerald for right in dark
    const val DEFAULT_ON_LEFT_DARK: Long = 0xFFB6F3C5L       // Mint text on dark left
    const val DEFAULT_ON_RIGHT_DARK: Long = 0xFF7EE7C4L      // Lighter mint on deeper dark right

    const val DEFAULT_HEART_ACTIVE: Long = 0xFFE11D48L     // Vibrant rose red

    data class TwoToneColors(
        // Light-mode colors
        val leftSurface: Long = DEFAULT_LEFT_SURFACE,
        val rightSurface: Long = DEFAULT_RIGHT_SURFACE,
        val onLeft: Long = DEFAULT_ON_LEFT,
        val onRight: Long = DEFAULT_ON_RIGHT,
        val heartActive: Long = DEFAULT_HEART_ACTIVE,
        // Dark-mode color variants
        val leftSurfaceDark: Long = DEFAULT_LEFT_SURFACE_DARK,
        val rightSurfaceDark: Long = DEFAULT_RIGHT_SURFACE_DARK,
        val onLeftDark: Long = DEFAULT_ON_LEFT_DARK,
        val onRightDark: Long = DEFAULT_ON_RIGHT_DARK,
    )

    private val paletteCache = androidx.collection.LruCache<String, TwoToneColors>(64)

    @Volatile
    private var lastBitmapHash: Int = 0
    @Volatile
    private var lastExtracted: TwoToneColors? = null

    fun extract(bitmap: Bitmap?, songKey: String = ""): TwoToneColors {
        if (songKey.isNotBlank()) {
            val fromCache = synchronized(paletteCache) { paletteCache.get(songKey) }
            if (fromCache != null) return fromCache
        }
        if (bitmap == null) return TwoToneColors()

        val bitmapHash = System.identityHashCode(bitmap)
        val cachedColors = lastExtracted
        if (bitmapHash != 0 && lastBitmapHash == bitmapHash && cachedColors != null) {
            if (songKey.isNotBlank()) {
                synchronized(paletteCache) { paletteCache.put(songKey, cachedColors) }
            }
            return cachedColors
        }

        return runCatching {
            val palette = Palette.from(bitmap).maximumColorCount(16).generate()

            // ── Light-mode surfaces ────────────────────────────────────────────
            val lightColor = palette.getLightVibrantColor(
                palette.getLightMutedColor(
                    palette.getVibrantColor(DEFAULT_LEFT_SURFACE.toInt()),
                ),
            ).toLong() and 0xFFFFFFFFL

            val darkColor = palette.getDarkVibrantColor(
                palette.getDarkMutedColor(
                    palette.getDominantColor(DEFAULT_RIGHT_SURFACE.toInt()),
                ),
            ).toLong() and 0xFFFFFFFFL

            val onLeft = if (isLightColor(lightColor)) 0xFF0C2D1BL else 0xFFFFFFFFL
            val onRight = if (isLightColor(darkColor)) 0xFF113426L else 0xFFB6F3C5L

            // ── Dark-mode surfaces ─────────────────────────────────────────────
            val lightDarkMode = palette.getDarkVibrantColor(
                palette.getMutedColor(
                    DEFAULT_LEFT_SURFACE_DARK.toInt(),
                ),
            ).toLong() and 0xFFFFFFFFL

            val rightDarkMode = palette.getDarkMutedColor(
                palette.getDominantColor(
                    DEFAULT_RIGHT_SURFACE_DARK.toInt(),
                ),
            ).toLong() and 0xFFFFFFFFL

            val onLeftDark = if (isLightColor(lightDarkMode)) 0xFF0A1F15L else 0xFFB6F3C5L
            val onRightDark = if (isLightColor(rightDarkMode)) 0xFF0C2D1BL else 0xFF7EE7C4L

            val result = TwoToneColors(
                leftSurface = lightColor,
                rightSurface = darkColor,
                onLeft = onLeft,
                onRight = onRight,
                heartActive = DEFAULT_HEART_ACTIVE,
                leftSurfaceDark = lightDarkMode,
                rightSurfaceDark = rightDarkMode,
                onLeftDark = onLeftDark,
                onRightDark = onRightDark,
            )
            lastBitmapHash = bitmapHash
            lastExtracted = result
            if (songKey.isNotBlank()) {
                synchronized(paletteCache) { paletteCache.put(songKey, result) }
            }
            result
        }.getOrDefault(TwoToneColors())
    }

    private fun isLightColor(colorLong: Long): Boolean {
        val r = ((colorLong shr 16) and 0xFF).toDouble()
        val g = ((colorLong shr 8) and 0xFF).toDouble()
        val b = (colorLong and 0xFF).toDouble()
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return luminance > 0.5
    }
}
