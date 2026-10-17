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
    val isCoverArt: Boolean = false,
)

/**
 * Contrast-evaluated text colors for a background swatch.
 */
data class DerivedCardColors(
    val backgroundColor: Int,
    val lyricsTextColor: Int,
    val secondaryTextColor: Int,
    val brandTextColor: Int,
    val dividerColor: Int = 0x33FFFFFF.toInt(),
    val isDarkBackground: Boolean = true,
)

object ColorContrastHelper {

    const val SWATCH_COVER_ART: Int = 0x00C0BEA7 // Sentinel ARGB for Cover Art background swatch
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
     * Iteratively adjusts [fgColor] brightness/saturation until it satisfies [minRatio] contrast against [bgColor].
     */
    fun ensureMinContrast(
        fgColor: Int,
        bgColor: Int,
        minRatio: Double,
        preferLight: Boolean = true,
    ): Int {
        var current = fgColor
        if (contrastRatio(current, bgColor) >= minRatio) return current

        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(current, hsv)

        var step = 0
        while (contrastRatio(current, bgColor) < minRatio && step < 20) {
            if (preferLight) {
                hsv[1] = (hsv[1] * 0.85f).coerceAtLeast(0f)
                hsv[2] = (hsv[2] + 0.05f).coerceAtMost(1.0f)
            } else {
                hsv[2] = (hsv[2] - 0.05f).coerceAtLeast(0f)
            }
            current = AndroidColor.HSVToColor(0xFF, hsv)
            step++
        }

        // Guaranteed absolute fallback if needed
        return if (contrastRatio(current, bgColor) >= minRatio) {
            current
        } else if (preferLight) {
            0xFFFFFFFF.toInt()
        } else {
            0xFF000000.toInt()
        }
    }

    /**
     * Derives rich YT Music style card background and high-contrast foreground text colors:
     * - Background: rich, deep, saturated version of the selected swatch (or flat color).
     * - Text: light tint of the same hue (e.g. warm peach/cream on deep brown).
     * - Contrast guarantee: >= 7:1 for lyrics, >= 4.5:1 for title/artist/brand.
     */
    fun deriveTextColors(backgroundColor: Int, isCoverScrim: Boolean = false): DerivedCardColors {
        if (isCoverScrim || backgroundColor == SWATCH_COVER_ART) {
            // Full card cover art with 50% dark scrim + gradient: effective bg is very dark (~0.05 luminance)
            return DerivedCardColors(
                backgroundColor = 0xFF14171A.toInt(),
                lyricsTextColor = 0xFFFFFFFF.toInt(),
                secondaryTextColor = 0xD9FFFFFF.toInt(), // ~85% white
                brandTextColor = 0xE6FFFFFF.toInt(),     // ~90% white
                dividerColor = 0x33FFFFFF.toInt(),
                isDarkBackground = true,
            )
        }

        val bgLum = relativeLuminance(backgroundColor)
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(backgroundColor, hsv)
        val hue = hsv[0]
        val sat = hsv[1]
        val value = hsv[2]

        // 1. Pure White / very light backgrounds
        if (bgLum > 0.80 || (sat < 0.08f && value > 0.90f)) {
            val bg = 0xFFFFFFFF.toInt()
            val lyricsColor = ensureMinContrast(0xFF101418.toInt(), bg, 7.0, preferLight = false)
            val secColor = ensureMinContrast(0xFF475569.toInt(), bg, 4.5, preferLight = false)
            val brandColor = ensureMinContrast(0xFF1E293B.toInt(), bg, 4.5, preferLight = false)
            return DerivedCardColors(
                backgroundColor = bg,
                lyricsTextColor = lyricsColor,
                secondaryTextColor = secColor,
                brandTextColor = brandColor,
                dividerColor = 0x26000000.toInt(),
                isDarkBackground = false,
            )
        }

        // 2. Pure Black / near-black backgrounds
        if (bgLum < 0.02 && sat < 0.15f) {
            val bg = 0xFF101214.toInt()
            val lyricsColor = ensureMinContrast(0xFFF9FAFB.toInt(), bg, 7.0, preferLight = true)
            val secColor = ensureMinContrast(0xFFA0AEC0.toInt(), bg, 4.5, preferLight = true)
            val brandColor = ensureMinContrast(0xFFE2E8F0.toInt(), bg, 4.5, preferLight = true)
            return DerivedCardColors(
                backgroundColor = bg,
                lyricsTextColor = lyricsColor,
                secondaryTextColor = secColor,
                brandTextColor = brandColor,
                dividerColor = 0x33FFFFFF.toInt(),
                isDarkBackground = true,
            )
        }

        // 3. Rich Saturated Colors (YouTube Music style: deep saturated background + same-hue tinted text)
        val richBgSat = (sat * 1.15f).coerceIn(0.65f, 1.0f)
        val richBgVal = if (value > 0.70f) 0.22f else value.coerceIn(0.18f, 0.28f)
        val richBg = AndroidColor.HSVToColor(0xFF, floatArrayOf(hue, richBgSat, richBgVal))

        // Same-hue subtle light tint for lyrics
        val tintSat = (sat * 0.16f).coerceIn(0.08f, 0.20f)
        val tintVal = 0.98f
        val rawLyrics = AndroidColor.HSVToColor(0xFF, floatArrayOf(hue, tintSat, tintVal))
        val lyricsColor = ensureMinContrast(rawLyrics, richBg, 7.0, preferLight = true)

        // Secondary text: same hue, slightly softer tint
        val secSat = (sat * 0.22f).coerceIn(0.12f, 0.26f)
        val secVal = 0.82f
        val rawSec = AndroidColor.HSVToColor(0xFF, floatArrayOf(hue, secSat, secVal))
        val secondaryColor = ensureMinContrast(rawSec, richBg, 4.5, preferLight = true)

        // Brand lockup: bold tint
        val brandSat = (sat * 0.18f).coerceIn(0.10f, 0.22f)
        val brandVal = 0.90f
        val rawBrand = AndroidColor.HSVToColor(0xFF, floatArrayOf(hue, brandSat, brandVal))
        val brandColor = ensureMinContrast(rawBrand, richBg, 4.5, preferLight = true)

        val dividerColor = AndroidColor.argb(
            50,
            AndroidColor.red(secondaryColor),
            AndroidColor.green(secondaryColor),
            AndroidColor.blue(secondaryColor),
        )

        return DerivedCardColors(
            backgroundColor = richBg,
            lyricsTextColor = lyricsColor,
            secondaryTextColor = secondaryColor,
            brandTextColor = brandColor,
            dividerColor = dividerColor,
            isDarkBackground = true,
        )
    }

    /**
     * Returns a pair of top and bottom gradient colors for the Select Lyrics screen,
     * maintaining high saturation and depth of the same hue without fading to black or grey.
     */
    fun getSelectScreenGradientColors(primaryColor: Int): Pair<Int, Int> {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(primaryColor, hsv)
        val hue = if (hsv[1] < 0.12f) 75f else hsv[0] // fallback to brand lime hue if input is grey
        val sat = (hsv[1] * 1.35f).coerceIn(0.70f, 1.0f)
        val topVal = 0.35f
        val bottomVal = 0.50f
        val top = AndroidColor.HSVToColor(0xFF, floatArrayOf(hue, sat, topVal))
        val bottom = AndroidColor.HSVToColor(0xFF, floatArrayOf(hue, sat, bottomVal))
        return Pair(top, bottom)
    }

    /**
     * Derives a dark, rich hue-tinted text color for the solid white selected line pill.
     */
    fun getSelectedPillTextColor(primaryColor: Int): Int {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(primaryColor, hsv)
        val hue = if (hsv[1] < 0.12f) 75f else hsv[0]
        val sat = 0.95f
        val value = 0.22f
        return AndroidColor.HSVToColor(0xFF, floatArrayOf(hue, sat, value))
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
     * Extracts palette colors from a software bitmap and combines them with theme tokens.
     * When [artBitmap] is present, prepends the Cover swatch as the FIRST item in the list.
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

        val list = mutableListOf<ColorSwatch>()

        list.add(ColorSwatch("song_vibrant", com.lastwave.app.R.string.lyshare_color_song, vibrant))
        list.add(ColorSwatch("muted", com.lastwave.app.R.string.lyshare_color_muted, muted))
        list.add(ColorSwatch("dominant", com.lastwave.app.R.string.lyshare_color_dominant, dominant))
        list.add(ColorSwatch("dark", com.lastwave.app.R.string.lyshare_color_dark, darkVariant))
        list.add(ColorSwatch("white", com.lastwave.app.R.string.lyshare_color_white, PURE_WHITE))
        list.add(ColorSwatch("black", com.lastwave.app.R.string.lyshare_color_black, PURE_BLACK))
        list.add(ColorSwatch("accent", com.lastwave.app.R.string.lyshare_color_accent, themeAccentColor))
        list.add(ColorSwatch("brand", com.lastwave.app.R.string.lyshare_color_brand, BRAND_LIME))

        return list
    }
}
