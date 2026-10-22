package com.lastwave.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Rebuilds the app's dark Material You scheme from a single source hue,
 * mirroring `_applyMaterialYouScheme()` / `_applyMonochromeScheme()` in the
 * legacy app.js (which itself hand-rolled dynamicDarkColorScheme()-style
 * tonal roles in CSS HSL space). This is intentionally NOT the same
 * algorithm as Android's real HCT-based dynamic color — using that here
 * would shift every color relationship in the app and count as a redesign.
 * This is a literal, faithful port so nothing has to be re-approved visually.
 */
object Md3SchemeBuilder {

    /** Chroma levels (CSS HSL %), match app.js constants exactly. */
    private const val CHROMA_PRIMARY = 30
    private const val CHROMA_SECONDARY = 14
    private const val CHROMA_TERTIARY = 22
    private const val CHROMA_NEUTRAL = 5
    private const val CHROMA_NEUTRAL_VARIANT = 8

    /** Extracts just the hue (0-360) from a hex color — the only component
     *  app.js's _hexToHsl() result that _applyMaterialYouScheme() actually uses. */
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

    /** Full dynamic dark scheme, seeded from [hex]'s hue. Matches _applyMaterialYouScheme(). */
    @Suppress("UNUSED_PARAMETER")
    fun buildScheme(hex: String, amoled: Boolean, liquidGlass: Boolean = false): ColorScheme =
        buildDarkScheme(hex, amoled, liquidGlass)

    /** Dark scheme, seeded from [hex]'s hue. */
    fun buildDarkScheme(hex: String, amoled: Boolean, liquidGlass: Boolean = false): ColorScheme {
        val h = hueOf(hex)
        val hT = (h + 60) % 360
        return build(
            primaryHue = h,
            secondaryHue = h,
            tertiaryHue = hT,
            neutralHue = h,
            amoled = amoled,
        )
    }

    /** Full dynamic light scheme, seeded from [hex]'s hue. */
    @Suppress("UNUSED_PARAMETER")
    fun buildLightScheme(hex: String, liquidGlass: Boolean = false): ColorScheme {
        val h = hueOf(hex)
        val hT = (h + 60) % 360
        return buildLight(
            primaryHue = h,
            secondaryHue = h,
            tertiaryHue = hT,
            neutralHue = h,
            monochrome = false,
        )
    }

    /** Pure grayscale dark scheme (all chroma = 0). Matches _applyMonochromeScheme(). */
    @Suppress("UNUSED_PARAMETER")
    fun buildMonochromeScheme(amoled: Boolean, liquidGlass: Boolean = false): ColorScheme =
        buildMonochromeDarkScheme(amoled, liquidGlass)

    /** Pure grayscale dark scheme. */
    fun buildMonochromeDarkScheme(amoled: Boolean, liquidGlass: Boolean = false): ColorScheme = build(
        primaryHue = 0,
        secondaryHue = 0,
        tertiaryHue = 0,
        neutralHue = 0,
        amoled = amoled,
        monochrome = true,
    )

    /** Pure grayscale light scheme (all chroma = 0). */
    @Suppress("UNUSED_PARAMETER")
    fun buildMonochromeLightScheme(liquidGlass: Boolean = false): ColorScheme = buildLight(
        primaryHue = 0,
        secondaryHue = 0,
        tertiaryHue = 0,
        neutralHue = 0,
        monochrome = true,
    )

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

        return androidx.compose.material3.lightColorScheme(
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

    private fun build(
        primaryHue: Int,
        secondaryHue: Int,
        tertiaryHue: Int,
        neutralHue: Int,
        amoled: Boolean,
        monochrome: Boolean = false,
    ): ColorScheme {
        val cP = if (monochrome) 0 else CHROMA_PRIMARY
        val cS = if (monochrome) 0 else CHROMA_SECONDARY
        val cT = if (monochrome) 0 else CHROMA_TERTIARY
        val cN = if (monochrome) 0 else CHROMA_NEUTRAL
        val cNV = if (monochrome) 0 else CHROMA_NEUTRAL_VARIANT

        val bgL = if (amoled) 0 else 6
        val sc1L = if (amoled) 4 else 11
        val sc2L = if (amoled) 8 else 16
        val sc3L = if (amoled) 12 else 20

        val backgroundCol = if (amoled) Color.Black else hsl(neutralHue, cN, bgL)
        val surfaceCol = if (amoled) Color.Black else hsl(neutralHue, cN, bgL)
        val surfaceLowCol = if (amoled) Color.Black else hsl(neutralHue, cN, bgL)
        val surfaceLowestCol = if (amoled) Color.Black else hsl(neutralHue, cN, (bgL - 2).coerceAtLeast(0))

        val scheme = darkColorScheme(
            primary = hsl(primaryHue, cP, 82),
            onPrimary = hsl(primaryHue, cP, 16),
            primaryContainer = hsl(primaryHue, cP, if (amoled) 24 else 30),
            onPrimaryContainer = hsl(primaryHue, (cP - 5).coerceAtLeast(0), 90),

            secondary = hsl(secondaryHue, cS, 80),
            onSecondary = hsl(secondaryHue, cS, 16),
            secondaryContainer = hsl(secondaryHue, cS, if (amoled) 22 else 28),
            onSecondaryContainer = hsl(secondaryHue, (cS - 4).coerceAtLeast(0), 90),

            tertiary = hsl(tertiaryHue, cT, 80),
            onTertiary = hsl(tertiaryHue, cT, 16),
            tertiaryContainer = hsl(tertiaryHue, cT, if (amoled) 22 else 28),
            onTertiaryContainer = hsl(tertiaryHue, (cT - 6).coerceAtLeast(0), 90),

            error = hsl(0, if (monochrome) 45 else 45, 80),
            errorContainer = hsl(0, if (monochrome) 30 else 30, 25),
            onErrorContainer = hsl(0, if (monochrome) 25 else 25, 88),
            onError = hsl(0, 45, 16),

            background = backgroundCol,
            onBackground = hsl(neutralHue, cNV, 90),
            surface = surfaceCol,
            onSurface = hsl(neutralHue, cNV, 90),
            surfaceContainer = hsl(neutralHue, cN, sc1L),
            surfaceContainerHigh = hsl(neutralHue, cN, sc2L),
            surfaceContainerHighest = hsl(neutralHue, cN, sc3L),
            surfaceContainerLow = surfaceLowCol,
            surfaceContainerLowest = surfaceLowestCol,
            surfaceVariant = hsl(neutralHue, cNV, sc2L + 4),
            onSurfaceVariant = hsl(neutralHue, cNV, 80),

            outline = hsl(neutralHue, cNV, 60),
            outlineVariant = hsl(neutralHue, cNV, 28),

            inverseSurface = hsl(neutralHue, cNV, 90),
            inverseOnSurface = hsl(neutralHue, cNV, 20),
            inversePrimary = hsl(primaryHue, cP, 40),
            scrim = Color.Black,
        )

        return scheme
    }

    private fun buildLight(
        primaryHue: Int,
        secondaryHue: Int,
        tertiaryHue: Int,
        neutralHue: Int,
        monochrome: Boolean = false,
    ): ColorScheme {
        val cP = if (monochrome) 0 else CHROMA_PRIMARY + 15
        val cS = if (monochrome) 0 else CHROMA_SECONDARY + 8
        val cT = if (monochrome) 0 else CHROMA_TERTIARY + 12
        val cN = if (monochrome) 0 else 4
        val cNV = if (monochrome) 0 else 6

        return androidx.compose.material3.lightColorScheme(
            primary = hsl(primaryHue, cP, 40),
            onPrimary = Color.White,
            primaryContainer = hsl(primaryHue, cP, 90),
            onPrimaryContainer = hsl(primaryHue, cP, 10),

            secondary = hsl(secondaryHue, cS, 40),
            onSecondary = Color.White,
            secondaryContainer = hsl(secondaryHue, cS, 90),
            onSecondaryContainer = hsl(secondaryHue, cS, 10),

            tertiary = hsl(tertiaryHue, cT, 40),
            onTertiary = Color.White,
            tertiaryContainer = hsl(tertiaryHue, cT, 90),
            onTertiaryContainer = hsl(tertiaryHue, cT, 10),

            error = hsl(0, 70, 40),
            errorContainer = hsl(0, 70, 90),
            onErrorContainer = hsl(0, 70, 12),
            onError = Color.White,

            background = hsl(neutralHue, cN, 98),
            onBackground = hsl(neutralHue, cNV, 10),
            surface = hsl(neutralHue, cN, 98),
            onSurface = hsl(neutralHue, cNV, 10),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = hsl(neutralHue, cN, 96),
            surfaceContainer = hsl(neutralHue, cN, 94),
            surfaceContainerHigh = hsl(neutralHue, cN, 92),
            surfaceContainerHighest = hsl(neutralHue, cN, 90),
            surfaceVariant = hsl(neutralHue, cNV, 90),
            onSurfaceVariant = hsl(neutralHue, cNV, 30),

            outline = hsl(neutralHue, cNV, 50),
            outlineVariant = hsl(neutralHue, cNV, 80),

            inverseSurface = hsl(neutralHue, cNV, 20),
            inverseOnSurface = hsl(neutralHue, cNV, 95),
            inversePrimary = hsl(primaryHue, cP, 80),
            scrim = Color.Black,
        )
    }

    /**
     * HSL(h in 0-360, s in 0-100, l in 0-100) -> Compose Color.
     * Standard conversion — must match browsers' CSS hsl() exactly so every
     * token lines up with the values app.css previously rendered.
     */
    private fun hsl(h: Int, s: Int, l: Int): Color {
        val hf = ((h % 360) + 360) % 360 / 360f
        val sf = (s.coerceIn(0, 100)) / 100f
        val lf = (l.coerceIn(0, 100)) / 100f

        if (sf == 0f) return Color(lf, lf, lf)

        val q = if (lf < 0.5f) lf * (1 + sf) else lf + sf - lf * sf
        val p = 2 * lf - q

        fun hueToRgb(t: Float): Float {
            var tt = t
            if (tt < 0f) tt += 1f
            if (tt > 1f) tt -= 1f
            return when {
                tt < 1f / 6f -> p + (q - p) * 6f * tt
                tt < 1f / 2f -> q
                tt < 2f / 3f -> p + (q - p) * (2f / 3f - tt) * 6f
                else -> p
            }
        }

        val r = hueToRgb(hf + 1f / 3f)
        val g = hueToRgb(hf)
        val b = hueToRgb(hf - 1f / 3f)
        return Color(r, g, b)
    }
}

/** Kept for readability at call sites that only care about hue distance. */
internal fun hueDelta(a: Int, b: Int): Int = abs(a - b).let { minOf(it, 360 - it) }
