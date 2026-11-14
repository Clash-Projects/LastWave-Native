package com.lastwave.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.android.material.color.utilities.CorePalette
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.TonalPalette
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Builds Material Design 3 dynamic color schemes from seed colors using Google's
 * Monet / Material Color Utilities engine (the same engine Android uses for wallpaper
 * dynamic theming). Produces cohesive, muted dark tones and readable, accessible contrasts.
 */
object Md3SchemeBuilder {

    private fun parseColor(hex: String, fallback: Int = 0xFFE03030.toInt()): Int {
        val clean = hex.trim()
        val formatted = if (clean.startsWith("#")) clean else "#$clean"
        return runCatching { android.graphics.Color.parseColor(formatted) }.getOrDefault(fallback)
    }

    /** Extracts just the hue (0-360) from a hex color. */
    fun hueOf(hex: String): Int {
        val clean = hex.removePrefix("#")
        if (clean.length < 6) return 0
        val rgb = clean.take(6).toIntOrNull(16) ?: return 0
        val r = ((rgb ushr 16) and 0xFF) / 255f
        val g = ((rgb ushr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max == min) return 0
        val d = max - min
        val h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } / 6f
        return (h * 360f).roundToInt().let { ((it % 360) + 360) % 360 }
    }

    /** HSL saturation (0-1) of a hex colour; 0 for anything unparseable. */
    private fun saturationOf(hex: String): Float {
        val rgb = hex.removePrefix("#").take(6).toIntOrNull(16) ?: return 0f
        val r = ((rgb ushr 16) and 0xFF) / 255f
        val g = ((rgb ushr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val span = 1f - abs(max + min - 1f)
        return if (span <= 0f) 0f else (max - min) / span
    }

    /** True for grey/black/white seeds: their hue is meaningless ([hueOf] gives 0, red). */
    fun isAchromatic(hex: String): Boolean = saturationOf(hex) < 0.1f

    /**
     * Builds CorePalette from a seed and optional second color.
     * Follows Google's Material Design 3 Monet algorithm used in wallpaper dynamic theming.
     * Keeps tones muted and elegant by constraining chroma.
     */
    private fun buildCorePalette(seedHex: String, secondHex: String? = null): CorePalette {
        val argb = parseColor(seedHex)
        if (isAchromatic(seedHex)) {
            val hct = Hct.fromInt(argb)
            return CorePalette.of(argb).apply {
                a1 = TonalPalette.fromHueAndChroma(hct.hue, 0.0)
                a2 = TonalPalette.fromHueAndChroma(hct.hue, 0.0)
                a3 = TonalPalette.fromHueAndChroma(hct.hue, 0.0)
                n1 = TonalPalette.fromHueAndChroma(hct.hue, 0.0)
                n2 = TonalPalette.fromHueAndChroma(hct.hue, 0.0)
            }
        }
        val hct = Hct.fromInt(argb)
        val core = CorePalette.of(argb)
        // Cap primary chroma to Monet's standard range (16..48) so tones remain beautifully muted
        core.a1 = TonalPalette.fromHueAndChroma(hct.hue, hct.chroma.coerceIn(16.0, 48.0))
        if (secondHex != null && !isAchromatic(secondHex)) {
            val secArgb = parseColor(secondHex)
            val secHct = Hct.fromInt(secArgb)
            core.a3 = TonalPalette.fromHueAndChroma(secHct.hue, 24.0)
        }
        return core
    }

    /**
     * (dark, light) schemes for now-playing artwork using Google's Monet dynamic palette engine.
     * Muted dark tone aligns with wallpaper Monet theming.
     */
    fun buildArtworkSchemes(seed: String, second: String?, amoled: Boolean): Pair<ColorScheme, ColorScheme> {
        val dark = buildMonetDarkScheme(seed, second, amoled)
        val light = buildMonetLightScheme(seed, second)
        return dark to light
    }

    /** Full dynamic dark scheme, seeded from [hex]. */
    @Suppress("UNUSED_PARAMETER")
    fun buildScheme(hex: String, amoled: Boolean, liquidGlass: Boolean = false): ColorScheme =
        buildDarkScheme(hex, amoled, liquidGlass)

    /** Dark scheme, seeded from [hex]. */
    @Suppress("UNUSED_PARAMETER")
    fun buildDarkScheme(hex: String, amoled: Boolean, liquidGlass: Boolean = false): ColorScheme =
        buildMonetDarkScheme(hex, secondHex = null, amoled = amoled)

    /** Full dynamic light scheme, seeded from [hex]. */
    @Suppress("UNUSED_PARAMETER")
    fun buildLightScheme(hex: String, liquidGlass: Boolean = false): ColorScheme =
        buildMonetLightScheme(hex, secondHex = null)

    /** Pure grayscale dark scheme. */
    @Suppress("UNUSED_PARAMETER")
    fun buildMonochromeScheme(amoled: Boolean, liquidGlass: Boolean = false): ColorScheme =
        buildMonochromeDarkScheme(amoled, liquidGlass)

    /** Pure grayscale dark scheme. */
    @Suppress("UNUSED_PARAMETER")
    fun buildMonochromeDarkScheme(amoled: Boolean, liquidGlass: Boolean = false): ColorScheme =
        buildMonetDarkScheme("#777777", secondHex = null, amoled = amoled)

    /** Pure grayscale light scheme. */
    @Suppress("UNUSED_PARAMETER")
    fun buildMonochromeLightScheme(liquidGlass: Boolean = false): ColorScheme =
        buildMonetLightScheme("#777777", secondHex = null)

    private fun buildMonetDarkScheme(
        seed: String,
        secondHex: String?,
        amoled: Boolean,
    ): ColorScheme {
        val core = buildCorePalette(seed, secondHex)
        val primary = Color(core.a1.tone(80))
        val onPrimary = Color(core.a1.tone(20))
        val primaryContainer = Color(core.a1.tone(30))
        val onPrimaryContainer = Color(core.a1.tone(90))

        val secondary = Color(core.a2.tone(80))
        val onSecondary = Color(core.a2.tone(20))
        val secondaryContainer = Color(core.a2.tone(30))
        val onSecondaryContainer = Color(core.a2.tone(90))

        val tertiary = Color(core.a3.tone(80))
        val onTertiary = Color(core.a3.tone(20))
        val tertiaryContainer = Color(core.a3.tone(30))
        val onTertiaryContainer = Color(core.a3.tone(90))

        val error = Color(core.error.tone(80))
        val onError = Color(core.error.tone(20))
        val errorContainer = Color(core.error.tone(30))
        val onErrorContainer = Color(core.error.tone(90))

        val background = if (amoled) Color.Black else Color(core.n1.tone(6))
        val onBackground = Color(core.n1.tone(90))
        val surface = if (amoled) Color.Black else Color(core.n1.tone(6))
        val onSurface = Color(core.n1.tone(90))

        val surfaceContainerLowest = if (amoled) Color.Black else Color(core.n1.tone(4))
        val surfaceContainerLow = if (amoled) Color.Black else Color(core.n1.tone(10))
        val surfaceContainer = if (amoled) Color.Black else Color(core.n1.tone(12))
        val surfaceContainerHigh = Color(core.n1.tone(17))
        val surfaceContainerHighest = Color(core.n1.tone(22))

        val surfaceVariant = Color(core.n2.tone(30))
        val onSurfaceVariant = Color(core.n2.tone(80))
        val outline = Color(core.n2.tone(60))
        val outlineVariant = Color(core.n2.tone(30))

        val inverseSurface = Color(core.n1.tone(90))
        val inverseOnSurface = Color(core.n1.tone(20))
        val inversePrimary = Color(core.a1.tone(40))

        return darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = inversePrimary,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = primary,
            surfaceContainerLowest = surfaceContainerLowest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            outline = outline,
            outlineVariant = outlineVariant,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            scrim = Color.Black,
        )
    }

    private fun buildMonetLightScheme(
        seed: String,
        secondHex: String?,
    ): ColorScheme {
        val core = buildCorePalette(seed, secondHex)
        val primary = Color(core.a1.tone(40))
        val onPrimary = Color(core.a1.tone(100))
        val primaryContainer = Color(core.a1.tone(90))
        val onPrimaryContainer = Color(core.a1.tone(10))
        val inversePrimary = Color(core.a1.tone(80))

        val secondary = Color(core.a2.tone(40))
        val onSecondary = Color(core.a2.tone(100))
        val secondaryContainer = Color(core.a2.tone(90))
        val onSecondaryContainer = Color(core.a2.tone(10))

        val tertiary = Color(core.a3.tone(40))
        val onTertiary = Color(core.a3.tone(100))
        val tertiaryContainer = Color(core.a3.tone(90))
        val onTertiaryContainer = Color(core.a3.tone(10))

        val error = Color(core.error.tone(40))
        val onError = Color(core.error.tone(100))
        val errorContainer = Color(core.error.tone(90))
        val onErrorContainer = Color(core.error.tone(10))

        val background = Color(core.n1.tone(98))
        val onBackground = Color(core.n1.tone(10))
        val surface = Color(core.n1.tone(98))
        val onSurface = Color(core.n1.tone(10))

        val surfaceContainerLowest = Color(core.n1.tone(100))
        val surfaceContainerLow = Color(core.n1.tone(96))
        val surfaceContainer = Color(core.n1.tone(94))
        val surfaceContainerHigh = Color(core.n1.tone(92))
        val surfaceContainerHighest = Color(core.n1.tone(90))

        val surfaceVariant = Color(core.n2.tone(90))
        val onSurfaceVariant = Color(core.n2.tone(30))
        val outline = Color(core.n2.tone(50))
        val outlineVariant = Color(core.n2.tone(80))

        val inverseSurface = Color(core.n1.tone(20))
        val inverseOnSurface = Color(core.n1.tone(95))

        return lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = inversePrimary,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = primary,
            surfaceContainerLowest = surfaceContainerLowest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            outline = outline,
            outlineVariant = outlineVariant,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            scrim = Color.Black,
        )
    }

    /**
     * LastWave Signature Dark Scheme built specifically around the official app logo colors:
     * Electric Lime (#C9FB00) and Deep Obsidian Teal (#012226).
     */
    fun buildLastWaveDarkScheme(amoled: Boolean, @Suppress("UNUSED_PARAMETER") liquidGlass: Boolean = false): ColorScheme {
        val brandLime = Color(0xFFC9FB00)
        val darkTeal = Color(0xFF012226)
        val deepTeal = Color(0xFF01181B)

        val backgroundCol = if (amoled) Color.Black else deepTeal
        val surfaceCol = if (amoled) Color.Black else darkTeal
        val surfaceLowCol = if (amoled) Color.Black else Color(0xFF011E22)
        val surfaceLowestCol = if (amoled) Color.Black else Color(0xFF001214)

        return darkColorScheme(
            primary = brandLime,
            onPrimary = darkTeal,
            primaryContainer = Color(0xFF063E45),
            onPrimaryContainer = Color(0xFFD6FF38),

            secondary = Color(0xFF38E5B7),
            onSecondary = darkTeal,
            secondaryContainer = Color(0xFF06474F),
            onSecondaryContainer = Color(0xFF67F8D8),

            tertiary = Color(0xFFA6FF38),
            onTertiary = Color(0xFF1B3800),
            tertiaryContainer = Color(0xFF0C464C),
            onTertiaryContainer = brandLime,

            error = Color(0xFFFFB4AB),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
            onError = Color(0xFF690005),

            background = backgroundCol,
            onBackground = Color(0xFFE4F8F8),
            surface = surfaceCol,
            onSurface = Color(0xFFE4F8F8),
            surfaceContainer = Color(0xFF02272C),
            surfaceContainerHigh = Color(0xFF04333A),
            surfaceContainerHighest = Color(0xFF073F47),
            surfaceContainerLow = surfaceLowCol,
            surfaceContainerLowest = surfaceLowestCol,
            surfaceVariant = Color(0xFF063B42),
            onSurfaceVariant = Color(0xFFA1CACE),

            outline = Color(0xFF23656E),
            outlineVariant = Color(0xFF0A444C),

            inverseSurface = Color(0xFFE4F8F8),
            inverseOnSurface = darkTeal,
            inversePrimary = Color(0xFF486300),
            scrim = Color.Black,
        )
    }

    /**
     * LastWave Signature Light Scheme built around the official app logo colors.
     */
    fun buildLastWaveLightScheme(@Suppress("UNUSED_PARAMETER") liquidGlass: Boolean = false): ColorScheme {
        val brandLime = Color(0xFFC9FB00)
        val darkTeal = Color(0xFF012226)

        return lightColorScheme(
            primary = Color(0xFF486300),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE0FE5B),
            onPrimaryContainer = darkTeal,

            secondary = Color(0xFF014D56),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFF9FF2E2),
            onSecondaryContainer = darkTeal,

            tertiary = Color(0xFF2C6855),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFB8F9A5),
            onTertiaryContainer = Color(0xFF0E3800),

            error = Color(0xFFBA1A1A),
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002),
            onError = Color.White,

            background = Color(0xFFF2FBF7),
            onBackground = darkTeal,
            surface = Color(0xFFF2FBF7),
            onSurface = darkTeal,
            surfaceContainer = Color(0xFFE0F1EC),
            surfaceContainerHigh = Color(0xFFD6EAE5),
            surfaceContainerHighest = Color(0xFFCCE4DE),
            surfaceContainerLow = Color(0xFFEAF6F2),
            surfaceContainerLowest = Color.White,
            surfaceVariant = Color(0xFFD6ECE8),
            onSurfaceVariant = Color(0xFF1E464B),

            outline = Color(0xFF5A8E89),
            outlineVariant = Color(0xFFB0D5D0),

            inverseSurface = darkTeal,
            inverseOnSurface = Color(0xFFE4F8F8),
            inversePrimary = brandLime,
            scrim = Color.Black,
        )
    }

    internal fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }
}

/** Kept for readability at call sites that only care about hue distance. */
internal fun hueDelta(a: Int, b: Int): Int = abs(a - b).let { minOf(it, 360 - it) }
