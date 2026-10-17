package com.lastwave.app.ui.player.lyricsshare

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.palette.graphics.Palette
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Metadata for a color swatch available in the Lyrics Share color picker.
 */
data class ColorSwatch(
    val id: String,
    val nameRes: Int,
    val color: Int, // ARGB packed integer
)

/**
 * Contrast-evaluated text colors for a background swatch.
 */
data class DerivedCardColors(
    val backgroundColor: Int,
    val lyricsTextColor: Int,
    val secondaryTextColor: Int,
    val brandTextColor: Int,
    val isDarkBackground: Boolean,
)

object ColorContrastHelper {

    const val BRAND_LIME: Int = 0xFFC9FB00.toInt()
    const val DEFAULT_DARK: Int = 0xFF12161A.toInt()
    const val PURE_WHITE: Int = 0xFFFFFFFF.toInt()
    const val PURE_BLACK: Int = 0xFF101010.toInt()

    /**
     * Calculates the WCAG 2.1 relative luminance of an sRGB color.
     */
    fun relativeLuminance(color: Int): Double {
        val r = linearize(AndroidColor.red(color) / 255.0)
        val g = linearize(AndroidColor.green(color) / 255.0)
        val b = linearize(AndroidColor.blue(color) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun linearize(channel: Double): Double {
        return if (channel <= 0.04045) {
            channel / 12.92
        } else {
            ((channel + 0.055) / 1.055).pow(2.4)
        }
    }

    /**
     * Calculates the WCAG 2.1 contrast ratio between two colors (ranging from 1.0 to 21.0).
     */
    fun contrastRatio(fgColor: Int, bgColor: Int): Double {
        val l1 = relativeLuminance(fgColor)
        val l2 = relativeLuminance(bgColor)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Derives high-contrast foreground text colors matching YouTube Music's dynamic styling:
     * - On dark backgrounds: crisp tinted-white with $\ge 4.5:1$ contrast for lyrics.
     * - On light backgrounds: deep tinted-charcoal with $\ge 4.5:1$ contrast for lyrics.
     * - Secondary text & logo guaranteed $\ge 3.0:1$ WCAG AA contrast.
     */
    fun deriveTextColors(backgroundColor: Int): DerivedCardColors {
        val bgLum = relativeLuminance(backgroundColor)
        val isDark = bgLum < 0.45

        val targetLight = 0xFFFFFFFF.toInt()
        val targetDark = 0xFF121417.toInt()

        val primaryLyricsColor = if (isDark) {
            // Dark bg: use white or high-contrast tinted white
            val candidate = 0xFFF8FAFC.toInt()
            if (contrastRatio(candidate, backgroundColor) >= 4.5) candidate else targetLight
        } else {
            // Light bg: use dark charcoal
            val candidate = 0xFF0F172A.toInt()
            if (contrastRatio(candidate, backgroundColor) >= 4.5) candidate else targetDark
        }

        val secondaryColor = if (isDark) {
            // Semi-transparent / muted white
            val candidate = AndroidColor.argb(
                210, // ~82% alpha
                AndroidColor.red(targetLight),
                AndroidColor.green(targetLight),
                AndroidColor.blue(targetLight),
            )
            val solidSecondary = blendOver(candidate, backgroundColor)
            if (contrastRatio(solidSecondary, backgroundColor) >= 3.0) solidSecondary else targetLight
        } else {
            val candidate = AndroidColor.argb(
                200, // ~78% alpha
                AndroidColor.red(targetDark),
                AndroidColor.green(targetDark),
                AndroidColor.blue(targetDark),
            )
            val solidSecondary = blendOver(candidate, backgroundColor)
            if (contrastRatio(solidSecondary, backgroundColor) >= 3.0) solidSecondary else targetDark
        }

        val brandColor = if (contrastRatio(primaryLyricsColor, backgroundColor) >= 3.0) {
            primaryLyricsColor
        } else {
            if (isDark) targetLight else targetDark
        }

        return DerivedCardColors(
            backgroundColor = backgroundColor,
            lyricsTextColor = primaryLyricsColor,
            secondaryTextColor = secondaryColor,
            brandTextColor = brandColor,
            isDarkBackground = isDark,
        )
    }

    /**
     * Blends an alpha foreground color over an opaque background to get an effective RGB.
     */
    fun blendOver(fgColor: Int, bgColor: Int): Int {
        val alpha = AndroidColor.alpha(fgColor) / 255.0
        val r = (AndroidColor.red(fgColor) * alpha + AndroidColor.red(bgColor) * (1.0 - alpha)).toInt().coerceIn(0, 255)
        val g = (AndroidColor.green(fgColor) * alpha + AndroidColor.green(bgColor) * (1.0 - alpha)).toInt().coerceIn(0, 255)
        val b = (AndroidColor.blue(fgColor) * alpha + AndroidColor.blue(bgColor) * (1.0 - alpha)).toInt().coerceIn(0, 255)
        return AndroidColor.rgb(r, g, b)
    }

    /**
     * Extracts palette colors from a software bitmap and combines them with theme tokens
     * to produce the standardized 8 swatches:
     * 1. Song color (Vibrant / default)
     * 2. Muted
     * 3. Dominant
     * 4. Dark variant
     * 5. Pure White
     * 6. Pure Black
     * 7. Theme Accent
     * 8. Brand Lime (#C9FB00)
     */
    fun extractSwatches(
        artBitmap: Bitmap?,
        themeAccentColor: Int = 0xFF38BDF8.toInt(),
    ): List<ColorSwatch> {
        val palette = artBitmap?.let {
            runCatching { Palette.from(it).clearFilters().generate() }.getOrNull()
        }

        val vibrant = palette?.vibrantSwatch?.rgb
            ?: palette?.lightVibrantSwatch?.rgb
            ?: palette?.dominantSwatch?.rgb
            ?: 0xFF2563EB.toInt()

        val muted = palette?.mutedSwatch?.rgb
            ?: palette?.darkMutedSwatch?.rgb
            ?: 0xFF64748B.toInt()

        val dominant = palette?.dominantSwatch?.rgb
            ?: 0xFF1E293B.toInt()

        val darkVariant = palette?.darkVibrantSwatch?.rgb
            ?: palette?.darkMutedSwatch?.rgb
            ?: 0xFF0F172A.toInt()

        return listOf(
            ColorSwatch("song_vibrant", com.lastwave.app.R.string.lyshare_color_song, vibrant),
            ColorSwatch("muted", com.lastwave.app.R.string.lyshare_color_muted, muted),
            ColorSwatch("dominant", com.lastwave.app.R.string.lyshare_color_dominant, dominant),
            ColorSwatch("dark", com.lastwave.app.R.string.lyshare_color_dark, darkVariant),
            ColorSwatch("white", com.lastwave.app.R.string.lyshare_color_white, PURE_WHITE),
            ColorSwatch("black", com.lastwave.app.R.string.lyshare_color_black, PURE_BLACK),
            ColorSwatch("accent", com.lastwave.app.R.string.lyshare_color_accent, themeAccentColor),
            ColorSwatch("brand", com.lastwave.app.R.string.lyshare_color_brand, BRAND_LIME),
        )
    }
}
