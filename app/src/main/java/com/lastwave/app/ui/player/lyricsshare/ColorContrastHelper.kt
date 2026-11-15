package com.lastwave.app.ui.player.lyricsshare

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.palette.graphics.Palette
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A Lyrics Share picker entry, YouTube Music style: [color] is the faint card
 * fill and [darkColor] the deeper shade of the same colour behind the card.
 * [nameRes] 0 means the plain [label] is the accessibility name.
 */
data class ColorSwatch(
    val id: String,
    val nameRes: Int,
    val color: Int, // ARGB packed integer
    val isCoverArt: Boolean = false,
    val darkColor: Int = ColorContrastHelper.darken(color),
    val label: String = "",
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
    const val PURE_BLACK: Int = 0xFF333333.toInt()

    /** The ~70% shade YouTube Music pairs with every card colour for the screen behind it. */
    fun darken(color: Int, factor: Float = 0.70f): Int = AndroidColor.rgb(
        (AndroidColor.red(color) * factor).toInt(),
        (AndroidColor.green(color) * factor).toInt(),
        (AndroidColor.blue(color) * factor).toInt(),
    )

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

        // The card is the picked colour itself. Remapping every swatch to one deep
        // tone is what made all the colours render as the same few dark cards.
        val bg = backgroundColor or 0xFF000000.toInt()
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(bg, hsv)
        val hue = hsv[0]
        val sat = hsv[1]
        // Text goes whichever way (light or dark) has more headroom on this colour,
        // tinted with the card's own hue like YouTube Music's cream-on-brown.
        val light = contrastRatio(PURE_WHITE, bg) >= contrastRatio(0xFF000000.toInt(), bg)

        fun tint(satFactor: Float, maxSat: Float, value: Float): Int {
            val s = (sat * satFactor).coerceAtMost(maxSat)
            return AndroidColor.HSVToColor(0xFF, floatArrayOf(hue, s, value))
        }

        // ensureMinContrast falls back to pure white/black, so text on a mid tone
        // still gets the best contrast the colour allows.
        val lyricsColor: Int
        val secondaryColor: Int
        val brandColor: Int
        if (light) {
            lyricsColor = ensureMinContrast(tint(0.45f, 0.40f, 1.0f), bg, 7.0, preferLight = true)
            secondaryColor = ensureMinContrast(tint(0.35f, 0.30f, 0.86f), bg, 4.5, preferLight = true)
            brandColor = ensureMinContrast(tint(0.40f, 0.35f, 0.94f), bg, 4.5, preferLight = true)
        } else {
            lyricsColor = ensureMinContrast(tint(1.0f, 0.90f, 0.16f), bg, 7.0, preferLight = false)
            secondaryColor = ensureMinContrast(tint(0.8f, 0.70f, 0.30f), bg, 4.5, preferLight = false)
            brandColor = ensureMinContrast(tint(1.0f, 0.85f, 0.22f), bg, 4.5, preferLight = false)
        }

        return DerivedCardColors(
            backgroundColor = bg,
            lyricsTextColor = lyricsColor,
            secondaryTextColor = secondaryColor,
            brandTextColor = brandColor,
            dividerColor = (secondaryColor and 0x00FFFFFF) or (0x40 shl 24),
            isDarkBackground = light,
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

    private fun shade(color: Int, sat: (Float) -> Float, value: (Float) -> Float): Int {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(color, hsv)
        // A grey cover keeps its grey; forcing saturation would invent a hue.
        val s = if (hsv[1] < 0.12f) hsv[1] else sat(hsv[1])
        return AndroidColor.HSVToColor(0xFF, floatArrayOf(hsv[0], s, value(hsv[2])))
    }

    /**
     * Picker entries. The first three come from the cover (the theme accent
     * stands in until the artwork loads, so the default re-picks from the real
     * cover): a deep tone, the vibrant colour and a pale tint - YouTube Music's
     * cream-on-brown look for most covers. Then YouTube Music's fixed faint/dark pairs.
     */
    fun extractSwatches(
        artBitmap: Bitmap?,
        themeAccentColor: Int = 0xFF38BDF8.toInt(),
    ): List<ColorSwatch> {
        val palette = artBitmap?.let {
            runCatching { Palette.from(it).clearFilters().generate() }.getOrNull()
        }
        val seed = palette?.vibrantSwatch?.rgb
            ?: palette?.darkVibrantSwatch?.rgb
            ?: palette?.dominantSwatch?.rgb
            ?: themeAccentColor
        val pale = palette?.lightVibrantSwatch?.rgb
            ?: palette?.lightMutedSwatch?.rgb
            ?: palette?.mutedSwatch?.rgb
            ?: seed

        val deep = shade(seed, { it.coerceIn(0.60f, 0.95f) }, { 0.24f })
        val cover = listOf(
            ColorSwatch("song_deep", com.lastwave.app.R.string.lyshare_color_song, deep, darkColor = darken(deep, 0.65f)),
            ColorSwatch("song_vibrant", com.lastwave.app.R.string.lyshare_color_dominant, shade(seed, { it.coerceIn(0.45f, 0.85f) }, { it.coerceIn(0.55f, 0.80f) })),
            ColorSwatch("song_pale", com.lastwave.app.R.string.lyshare_color_muted, shade(pale, { it.coerceIn(0.20f, 0.45f) }, { it.coerceIn(0.85f, 0.97f) })),
        )
        val presets = listOf(
            ColorSwatch("white", com.lastwave.app.R.string.lyshare_color_white, PURE_WHITE, darkColor = 0xFFB2B2B2.toInt()),
            ColorSwatch("black", com.lastwave.app.R.string.lyshare_color_black, PURE_BLACK, darkColor = 0xFF0F0F0F.toInt()),
            ColorSwatch("teal", 0, 0xFF06413F.toInt(), darkColor = 0xFF052D2C.toInt(), label = "Deep teal"),
            ColorSwatch("navy", 0, 0xFF073B86.toInt(), darkColor = 0xFF062A5E.toInt(), label = "Navy blue"),
            ColorSwatch("maroon", 0, 0xFFDD5851.toInt(), darkColor = 0xFF9B3E39.toInt(), label = "Maroon"),
            ColorSwatch("green", 0, 0xFFAEF3A3.toInt(), darkColor = 0xFF79AA72.toInt(), label = "Green"),
            ColorSwatch("lavender", 0, 0xFFD29BF6.toInt(), darkColor = 0xFF946DAC.toInt(), label = "Lavender"),
            ColorSwatch("light_purple", 0, 0xFFAAACFF.toInt(), darkColor = 0xFF7678B1.toInt(), label = "Light purple"),
            ColorSwatch("peach", 0, 0xFFFFB59E.toInt(), label = "Peach"),
            ColorSwatch("sky", 0, 0xFF9ED8FF.toInt(), label = "Sky"),
            ColorSwatch("wine", 0, 0xFF4A1230.toInt(), label = "Wine"),
            ColorSwatch("brand", com.lastwave.app.R.string.lyshare_color_brand, BRAND_LIME),
        )
        // The picker keys selection by colour, so a cover tone equal to a preset must not appear twice.
        return (cover + presets).distinctBy { it.color }
    }
}
