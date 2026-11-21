package com.lastwave.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeNeutral
import com.google.android.material.color.utilities.TonalPalette
import com.google.android.material.color.utilities.Variant
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Builds Material Design 3 color schemes using the SchemeNeutral (reduced-chroma)
 * dynamic theming architecture from SleepTracker, tuned to deeply muted, dark obsidian
 * tones while preserving 100% cover art direction and contrast accessibility.
 */
object Md3SchemeBuilder {

    private val mdc = MaterialDynamicColors()

    // Deep obsidian dark surfaces (standard dark mode)
    private val DarkBackground = Color(0xFF0C0C0E)
    private val DarkSurface = Color(0xFF0C0C0E)
    private val DarkSurfaceDim = Color(0xFF0C0C0E)
    private val DarkSurfaceContainerLowest = Color(0xFF070709)
    private val DarkSurfaceContainerLow = Color(0xFF111113)
    private val DarkSurfaceContainer = Color(0xFF161619)
    private val DarkSurfaceContainerHigh = Color(0xFF1C1C20)
    private val DarkSurfaceContainerHighest = Color(0xFF232328)
    private val DarkSurfaceVariant = Color(0xFF1E1E23)
    private val DarkSurfaceBright = Color(0xFF2A2A30)

    // AMOLED tokens matching SleepTracker (deep true black)
    private val AmoledBlack = Color(0xFF000000)
    private val AmoledSurfaceContainerLow = Color(0xFF070707)
    private val AmoledSurfaceContainer = Color(0xFF0D0D0D)
    private val AmoledSurfaceContainerHigh = Color(0xFF141414)
    private val AmoledSurfaceContainerHighest = Color(0xFF1A1A1A)
    private val AmoledSurfaceVariant = Color(0xFF141414)
    private val AmoledSurfaceBright = Color(0xFF222222)

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

    private fun DynamicScheme.toComposeColorScheme(): ColorScheme {
        return if (this.isDark) {
            ColorScheme(
                primary = Color(primaryPalette.tone(72)),
                onPrimary = Color(primaryPalette.tone(10)),
                primaryContainer = Color(primaryPalette.tone(22)),
                onPrimaryContainer = Color(primaryPalette.tone(85)),
                inversePrimary = Color(primaryPalette.tone(40)),
                secondary = Color(secondaryPalette.tone(72)),
                onSecondary = Color(secondaryPalette.tone(10)),
                secondaryContainer = Color(secondaryPalette.tone(22)),
                onSecondaryContainer = Color(secondaryPalette.tone(85)),
                tertiary = Color(tertiaryPalette.tone(72)),
                onTertiary = Color(tertiaryPalette.tone(10)),
                tertiaryContainer = Color(tertiaryPalette.tone(22)),
                onTertiaryContainer = Color(tertiaryPalette.tone(85)),
                background = DarkBackground,
                onBackground = Color(neutralPalette.tone(90)),
                surface = DarkSurface,
                onSurface = Color(neutralPalette.tone(90)),
                surfaceVariant = DarkSurfaceVariant,
                onSurfaceVariant = Color(neutralVariantPalette.tone(75)),
                surfaceTint = Color(primaryPalette.tone(72)),
                inverseSurface = Color(neutralPalette.tone(90)),
                inverseOnSurface = Color(neutralPalette.tone(20)),
                error = Color(errorPalette.tone(70)),
                onError = Color(errorPalette.tone(10)),
                errorContainer = Color(errorPalette.tone(20)),
                onErrorContainer = Color(errorPalette.tone(85)),
                outline = Color(neutralVariantPalette.tone(45)),
                outlineVariant = Color(neutralVariantPalette.tone(25)),
                scrim = Color.Black,
                surfaceBright = DarkSurfaceBright,
                surfaceContainer = DarkSurfaceContainer,
                surfaceContainerHigh = DarkSurfaceContainerHigh,
                surfaceContainerHighest = DarkSurfaceContainerHighest,
                surfaceContainerLow = DarkSurfaceContainerLow,
                surfaceContainerLowest = DarkSurfaceContainerLowest,
                surfaceDim = DarkSurfaceDim,
            )
        } else {
            ColorScheme(
                primary = Color(mdc.primary().getArgb(this)),
                onPrimary = Color(mdc.onPrimary().getArgb(this)),
                primaryContainer = Color(mdc.primaryContainer().getArgb(this)),
                onPrimaryContainer = Color(mdc.onPrimaryContainer().getArgb(this)),
                inversePrimary = Color(mdc.inversePrimary().getArgb(this)),
                secondary = Color(mdc.secondary().getArgb(this)),
                onSecondary = Color(mdc.onSecondary().getArgb(this)),
                secondaryContainer = Color(mdc.secondaryContainer().getArgb(this)),
                onSecondaryContainer = Color(mdc.onSecondaryContainer().getArgb(this)),
                tertiary = Color(mdc.tertiary().getArgb(this)),
                onTertiary = Color(mdc.onTertiary().getArgb(this)),
                tertiaryContainer = Color(mdc.tertiaryContainer().getArgb(this)),
                onTertiaryContainer = Color(mdc.onTertiaryContainer().getArgb(this)),
                background = Color(mdc.background().getArgb(this)),
                onBackground = Color(mdc.onBackground().getArgb(this)),
                surface = Color(mdc.surface().getArgb(this)),
                onSurface = Color(mdc.onSurface().getArgb(this)),
                surfaceVariant = Color(mdc.surfaceVariant().getArgb(this)),
                onSurfaceVariant = Color(mdc.onSurfaceVariant().getArgb(this)),
                surfaceTint = Color(mdc.primary().getArgb(this)),
                inverseSurface = Color(mdc.inverseSurface().getArgb(this)),
                inverseOnSurface = Color(mdc.inverseOnSurface().getArgb(this)),
                error = Color(mdc.error().getArgb(this)),
                onError = Color(mdc.onError().getArgb(this)),
                errorContainer = Color(mdc.errorContainer().getArgb(this)),
                onErrorContainer = Color(mdc.onErrorContainer().getArgb(this)),
                outline = Color(mdc.outline().getArgb(this)),
                outlineVariant = Color(mdc.outlineVariant().getArgb(this)),
                scrim = Color.Black,
                surfaceBright = Color(mdc.surfaceBright().getArgb(this)),
                surfaceContainer = Color(mdc.surfaceContainer().getArgb(this)),
                surfaceContainerHigh = Color(mdc.surfaceContainerHigh().getArgb(this)),
                surfaceContainerHighest = Color(mdc.surfaceContainerHighest().getArgb(this)),
                surfaceContainerLow = Color(mdc.surfaceContainerLow().getArgb(this)),
                surfaceContainerLowest = Color(mdc.surfaceContainerLowest().getArgb(this)),
                surfaceDim = Color(mdc.surfaceDim().getArgb(this)),
            )
        }
    }

    private fun generateDynamicScheme(
        seedHex: String,
        isDark: Boolean,
    ): DynamicScheme {
        val argb = parseColor(seedHex)
        if (isAchromatic(seedHex)) {
            val greyHct = Hct.fromInt(argb)
            return DynamicScheme(
                greyHct, Variant.NEUTRAL, isDark, 0.0,
                TonalPalette.fromHueAndChroma(greyHct.hue, 0.0),
                TonalPalette.fromHueAndChroma(greyHct.hue, 0.0),
                TonalPalette.fromHueAndChroma(greyHct.hue, 0.0),
                TonalPalette.fromHueAndChroma(greyHct.hue, 0.0),
                TonalPalette.fromHueAndChroma(greyHct.hue, 0.0),
            )
        }
        val hct = Hct.fromInt(argb)
        val baseScheme = SchemeNeutral(hct, isDark, 0.0)
        // Allow vivid covers to slightly influence primary chroma (8..16) while keeping it deeply muted
        val priChroma = hct.chroma.coerceIn(8.0, 16.0)
        val priPalette = TonalPalette.fromHueAndChroma(hct.hue, priChroma)

        return DynamicScheme(
            baseScheme.sourceColorHct,
            baseScheme.variant,
            baseScheme.isDark,
            baseScheme.contrastLevel,
            priPalette,
            baseScheme.secondaryPalette,
            baseScheme.tertiaryPalette,
            baseScheme.neutralPalette,
            baseScheme.neutralVariantPalette,
        )
    }

    fun applyDarkSurfaces(scheme: ColorScheme): ColorScheme = scheme.copy(
        background = DarkBackground,
        surface = DarkSurface,
        surfaceDim = DarkSurfaceDim,
        surfaceContainerLowest = DarkSurfaceContainerLowest,
        surfaceContainerLow = DarkSurfaceContainerLow,
        surfaceContainer = DarkSurfaceContainer,
        surfaceContainerHigh = DarkSurfaceContainerHigh,
        surfaceContainerHighest = DarkSurfaceContainerHighest,
        surfaceVariant = DarkSurfaceVariant,
        surfaceBright = DarkSurfaceBright,
    )

    fun applyAmoledTheme(scheme: ColorScheme): ColorScheme = scheme.copy(
        background = AmoledBlack,
        surface = AmoledBlack,
        surfaceDim = AmoledBlack,
        surfaceContainerLowest = AmoledBlack,
        surfaceContainerLow = AmoledSurfaceContainerLow,
        surfaceContainer = AmoledSurfaceContainer,
        surfaceContainerHigh = AmoledSurfaceContainerHigh,
        surfaceContainerHighest = AmoledSurfaceContainerHighest,
        surfaceVariant = AmoledSurfaceVariant,
        surfaceBright = AmoledSurfaceBright,
        inverseSurface = AmoledSurfaceContainerHighest,
        inverseOnSurface = Color.White,
    )

    /**
     * (dark, light) schemes for now-playing artwork using a single major dominant color.
     */
    fun buildArtworkSchemes(seed: String, @Suppress("UNUSED_PARAMETER") second: String? = null, amoled: Boolean): Pair<ColorScheme, ColorScheme> {
        val darkScheme = generateDynamicScheme(seed, isDark = true).toComposeColorScheme().let {
            if (amoled) applyAmoledTheme(it) else it
        }
        val lightScheme = generateDynamicScheme(seed, isDark = false).toComposeColorScheme()
        return darkScheme to lightScheme
    }

    /** Full dynamic dark scheme, seeded from [hex]. */
    @Suppress("UNUSED_PARAMETER")
    fun buildScheme(hex: String, amoled: Boolean, liquidGlass: Boolean = false): ColorScheme =
        buildDarkScheme(hex, amoled, liquidGlass)

    /** Dark scheme, seeded from [hex]. */
    @Suppress("UNUSED_PARAMETER")
    fun buildDarkScheme(hex: String, amoled: Boolean, liquidGlass: Boolean = false): ColorScheme {
        val darkScheme = generateDynamicScheme(hex, isDark = true).toComposeColorScheme()
        return if (amoled) applyAmoledTheme(darkScheme) else darkScheme
    }

    /** Full dynamic light scheme, seeded from [hex]. */
    @Suppress("UNUSED_PARAMETER")
    fun buildLightScheme(hex: String, liquidGlass: Boolean = false): ColorScheme =
        generateDynamicScheme(hex, isDark = false).toComposeColorScheme()

    /** Pure grayscale dark scheme. */
    @Suppress("UNUSED_PARAMETER")
    fun buildMonochromeScheme(amoled: Boolean, liquidGlass: Boolean = false): ColorScheme =
        buildMonochromeDarkScheme(amoled, liquidGlass)

    /** Pure grayscale dark scheme. */
    @Suppress("UNUSED_PARAMETER")
    fun buildMonochromeDarkScheme(amoled: Boolean, liquidGlass: Boolean = false): ColorScheme =
        buildDarkScheme("#777777", amoled, liquidGlass)

    /** Pure grayscale light scheme. */
    @Suppress("UNUSED_PARAMETER")
    fun buildMonochromeLightScheme(liquidGlass: Boolean = false): ColorScheme =
        buildLightScheme("#777777", liquidGlass)

    /**
     * LastWave Signature Dark Scheme built specifically around the official app logo colors.
     */
    fun buildLastWaveDarkScheme(amoled: Boolean, @Suppress("UNUSED_PARAMETER") liquidGlass: Boolean = false): ColorScheme {
        val brandLime = Color(0xFFC9FB00)
        val darkTeal = Color(0xFF012226)
        val deepTeal = Color(0xFF01181B)

        val backgroundCol = if (amoled) Color.Black else DarkBackground
        val surfaceCol = if (amoled) Color.Black else DarkSurface
        val surfaceLowCol = if (amoled) Color.Black else DarkSurfaceContainerLow
        val surfaceLowestCol = if (amoled) Color.Black else DarkSurfaceContainerLowest

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
            surfaceContainer = if (amoled) AmoledSurfaceContainer else Color(0xFF02272C),
            surfaceContainerHigh = if (amoled) AmoledSurfaceContainerHigh else Color(0xFF04333A),
            surfaceContainerHighest = if (amoled) AmoledSurfaceContainerHighest else Color(0xFF073F47),
            surfaceContainerLow = surfaceLowCol,
            surfaceContainerLowest = surfaceLowestCol,
            surfaceVariant = if (amoled) AmoledSurfaceVariant else Color(0xFF063B42),
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
