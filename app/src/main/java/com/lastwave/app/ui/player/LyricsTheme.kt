package com.lastwave.app.ui.player

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

enum class LyricsStyle(val id: String, val displayName: String) {
    DEFAULT("default", "Default"),
    FLOW("flow", "Flow"),
    HALO("halo", "Halo"),
    VELVET("velvet", "Velvet");

    companion object {
        fun fromId(id: String?): LyricsStyle =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * Declarative visual specification for a lyrics theme.
 * All style-specific presentation logic lives here.
 */
data class LyricsThemeSpec(
    // ── Opacity hierarchy ──────────────────────────────────────────────────
    /** Alpha of the currently active line (1.0 = fully opaque). */
    val activeLineAlpha: Float = 1.0f,
    /** Alpha of lines BEFORE the active line (previous lines). */
    val previousLineAlpha: Float = 0.5f,
    /** Alpha of lines AFTER the active line (future lines). */
    val futureLineAlpha: Float = 0.65f,
    /** Per-distance alpha falloff for future lines (index 0 = distance 1). */
    val futureAlphaFalloff: List<Float> = listOf(0.65f, 0.45f, 0.30f),

    // ── Blur ───────────────────────────────────────────────────────────────
    /** Apply Gaussian blur to previous lines. */
    val blurPreviousLines: Boolean = false,
    /** Blur radius in pixels for previous lines. */
    val blurRadiusPx: Float = 18f,
    /** Apply blur to future lines. */
    val blurFutureLines: Boolean = false,
    /** Per-distance blur falloff for future lines (index 0 = distance 1). */
    val futureBlurFalloffPx: List<Float> = emptyList(),

    // ── Scale ──────────────────────────────────────────────────────────────
    /** Scale factor for the active line. 1.0 = no scale change. */
    val activeLineScale: Float = 1.0f,
    /** Scale factor per-distance falloff (index 0 = distance 1). */
    val inactiveScaleFalloff: List<Float> = listOf(0.97f, 0.94f, 0.92f),
    /** Use spring physics for scale transitions instead of tween. */
    val useSpringForScale: Boolean = true,

    // ── Typography ─────────────────────────────────────────────────────────
    /** Font family. If null, uses app/system default. */
    val fontFamily: FontFamily? = null,
    /** Font weight for the active line. */
    val activeFontWeight: FontWeight = FontWeight.Bold,
    /** Font weight for inactive lines. */
    val inactiveFontWeight: FontWeight = FontWeight.Bold,
    /** Line height multiplier (relative to fontSize). */
    val lineHeightMultiplier: Float = 1.20f,
    /** Font size multiplier to scale text for specific themes. */
    val fontSizeMultiplier: Float = 1.0f,
    /** Custom letter spacing override for lyrics text in this theme. If null, default spacing (-0.6.sp) is used. */
    val letterSpacing: TextUnit? = null,

    // ── Word sync ──────────────────────────────────────────────────────────
    /** Enable soft glow on the currently active word. */
    val glowActiveWord: Boolean = false,
    /** Glow radius in px when glowActiveWord = true. */
    val wordGlowRadiusPx: Float = 16f,
    /** Active line blur glow radius in px. Set to 0f to completely disable line-level glow. */
    val activeLineGlowRadiusPx: Float = 0f,
    /** Alpha of future (not-yet-sung) words within the active line. */
    val futureWordAlpha: Float = 0.35f,
    /** Alpha of past (already-sung) words within the active line. */
    val passedWordAlpha: Float = 1.0f,
    /** Use the expressive accent color for the active word's glow instead of white. */
    val useAccentForWordGlow: Boolean = false,
    /** Base alpha for the active word glow. */
    val activeWordGlowAlpha: Float = 0.75f,
    /** Theme-specific accent color override. If null, default accent is used. */
    val themeAccentColor: Color? = null,
    /** Whether the line immediately after the active line should be tinted with accent color. */
    val tintNextLineWithAccent: Boolean = false,
    /** Whether the active line should use a left-to-right progress fill animation. */
    val activeLineProgressFill: Boolean = false,
    /** Enable Apple Music-style vertical word lift effect on active words. */
    val wordLiftEffect: Boolean = false,
    /** Enable wobble transform animation on active words (Flow style). */
    val wordWobbleEffect: Boolean = false,
    /** Enable vertical word glide animation on active words (Halo style). */
    val wordGlideEffect: Boolean = false,
    /** Selective glow bloom: full white glow for words >= 5 chars, faint for shorter (Flow style). */
    val selectiveLongWordGlow: Boolean = false,
    /** Apply subtle dark drop shadow on inactive/dim text runs (Flow style). */
    val inactiveTextDropShadow: Boolean = false,
    /** Render translated lyrics at smaller font size (Halo style). */
    val smallerTranslatedSize: Boolean = false,

    // ── Romanized / Translated ─────────────────────────────────────────────
    /** Font style for the romanized/translated text. If null, uses default. */
    val romanizedFontStyle: FontStyle? = null,
    /** Alpha for the romanized text. */
    val romanizedTextAlpha: Float = 0.5f,
    /** Background container alpha for romanized text. 0.0f = no background. */
    val romanizedBackgroundAlpha: Float = 0.0f,
    /** Alpha for the translated text. */
    val translatedTextAlpha: Float = 0.5f,

    // ── Loader ─────────────────────────────────────────────────────────────
    /** Enable vertical floating animation for the music note loader. */
    val loaderUseFloatAnim: Boolean = false,
    /** Enable scale breathing animation for the music note loader. */
    val loaderUseScaleAnim: Boolean = false,
    /** Enable opacity breathing animation for the music note loader. */
    val loaderUseOpacityPulse: Boolean = false,
    /** Enable 3000ms breathing pulse animation for the music note loader (Flow style). */
    val loaderUseBreathingPulse: Boolean = false,
    /** Base glow radius for the music note loader in pixels. */
    val loaderGlowRadiusPx: Float = 0f,
    /** Theme-specific accent color override for the loader's glow. */
    val loaderGlowColorOverride: Color? = null,
    /** Whether to draw the loader as a hollow outline instead of a filled shape. */
    val loaderDrawOutline: Boolean = false,

    // ── Scroll ─────────────────────────────────────────────────────────────
    /** Lookahead (ms) added to currentPositionMs for the auto-scroll target
     *  index. The horizontal syllable sweep still uses the real position. */
    val scrollLookaheadMs: Long = 0L,
    /** Vertical anchor fraction: active line sits this far from the top of
     *  the viewport (0.0 = very top, 0.5 = center). Null = use default. */
    val scrollAnchorFraction: Float? = null,
    /** Auto-scroll animation duration (ms). Null = use default band. */
    val scrollDurationMs: Float? = null,
    /** Auto-scroll easing. Null = use default LYRIC_EASE. */
    val scrollEasing: Easing? = null,

    // ── Transitions ────────────────────────────────────────────────────────
    /** Duration (ms) for opacity/blur transitions on inactive lines. */
    val transitionDurationMs: Int = 420,
    /** Duration (ms) for alpha transition when a line becomes previous. */
    val previousAlphaDurationMs: Int? = null,
    /** Duration (ms) for blur transition when a line becomes previous. */
    val previousBlurDurationMs: Int? = null,
    /** Duration (ms) for scale transition. */
    val scaleDurationMs: Int? = null,
    /** Delay (ms) before blur/opacity transition starts. */
    val transitionDelayMs: Int = 0,
    /** Easing for blur/alpha transitions. */
    val transitionEasing: Easing = FastOutSlowInEasing,
)

private val FlowEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1.0f)

fun LyricsStyle.toThemeSpec(): LyricsThemeSpec = when (this) {
    LyricsStyle.DEFAULT -> LyricsThemeSpec(
        activeLineAlpha = 1.0f,
        previousLineAlpha = 0.50f,
        futureLineAlpha = 0.50f,
        futureAlphaFalloff = listOf(0.50f, 0.40f, 0.30f, 0.22f),
        blurPreviousLines = false,
        blurRadiusPx = 0f,
        blurFutureLines = false,
        activeLineScale = 1.0f,
        inactiveScaleFalloff = listOf(0.97f),
        useSpringForScale = true,
        fontFamily = null,
        activeFontWeight = FontWeight.Bold,
        inactiveFontWeight = FontWeight.Bold,
        lineHeightMultiplier = 1.20f,
        fontSizeMultiplier = 1.0f,
        glowActiveWord = true,
        activeLineGlowRadiusPx = 16f,
        wordLiftEffect = false,
        loaderDrawOutline = false,
        transitionDurationMs = 420,
        transitionEasing = FastOutSlowInEasing,
    )

    LyricsStyle.FLOW -> LyricsThemeSpec(
        activeLineAlpha = 1.0f,
        previousLineAlpha = 0.5f,
        futureLineAlpha = 0.65f,
        futureAlphaFalloff = listOf(0.65f, 0.45f, 0.30f),
        blurPreviousLines = true,
        blurRadiusPx = 12f,
        blurFutureLines = false,
        activeLineScale = 1.0f,
        inactiveScaleFalloff = listOf(0.84f, 0.78f, 0.74f),
        useSpringForScale = false,
        scaleDurationMs = 300,
        activeFontWeight = FontWeight(650),
        inactiveFontWeight = FontWeight(650),
        lineHeightMultiplier = 1.38f,
        fontSizeMultiplier = 1.0f,
        glowActiveWord = true,
        selectiveLongWordGlow = true,
        wordGlowRadiusPx = 14f,
        wordWobbleEffect = true,
        futureWordAlpha = 0.45f,
        passedWordAlpha = 1.0f,
        useAccentForWordGlow = false,
        activeWordGlowAlpha = 0.75f,
        inactiveTextDropShadow = true,
        romanizedTextAlpha = 0.5f,
        romanizedBackgroundAlpha = 0.0f,
        translatedTextAlpha = 0.5f,
        themeAccentColor = null,
        loaderUseBreathingPulse = true,
        loaderUseFloatAnim = false,
        loaderUseOpacityPulse = false,
        loaderGlowRadiusPx = 0f,
        scrollLookaheadMs = 600L,
        scrollAnchorFraction = 0.45f,
        scrollDurationMs = 400f,
        scrollEasing = FastOutSlowInEasing,
        transitionDurationMs = 300,
        transitionDelayMs = 0,
        transitionEasing = FlowEasing,
    )

    LyricsStyle.VELVET -> LyricsThemeSpec(
        activeLineAlpha = 1.0f,
        previousLineAlpha = 0.7f,
        futureLineAlpha = 0.7f,
        futureAlphaFalloff = listOf(0.7f, 0.6f, 0.5f),
        blurPreviousLines = false,
        blurRadiusPx = 0f,
        blurFutureLines = false,
        activeLineScale = 1.05f,
        inactiveScaleFalloff = listOf(1.0f),
        activeFontWeight = FontWeight.SemiBold,
        inactiveFontWeight = FontWeight.Medium,
        lineHeightMultiplier = 1.35f,
        glowActiveWord = true,
        wordGlowRadiusPx = 10f,
        futureWordAlpha = 0.6f,
        passedWordAlpha = 0.8f,
        useAccentForWordGlow = true,
        activeWordGlowAlpha = 0.4f,
        tintNextLineWithAccent = true,
        activeLineProgressFill = true,
        romanizedTextAlpha = 0.7f,
        romanizedBackgroundAlpha = 0.05f,
        translatedTextAlpha = 0.7f,
        themeAccentColor = Color(0xFFD49E9B),
        loaderUseOpacityPulse = true,
        loaderGlowRadiusPx = 16f,
        loaderGlowColorOverride = Color(0xFFD49E9B),
        transitionDurationMs = 500,
        transitionDelayMs = 0,
        transitionEasing = FlowEasing,
    )

    LyricsStyle.HALO -> LyricsThemeSpec(
        activeLineAlpha = 1.0f,
        previousLineAlpha = 0.0f,
        futureLineAlpha = 0.20f,
        futureAlphaFalloff = listOf(0.20f),
        blurPreviousLines = true,
        blurRadiusPx = 5.0f,
        blurFutureLines = true,
        futureBlurFalloffPx = listOf(2.0f, 3.5f, 5.0f, 6.5f, 8.0f, 9.5f),
        activeLineScale = 1.0f,
        inactiveScaleFalloff = listOf(0.92f),
        useSpringForScale = false,
        scaleDurationMs = 600,
        fontFamily = FontFamily.Default,
        activeFontWeight = FontWeight.Black,
        inactiveFontWeight = FontWeight.Black,
        lineHeightMultiplier = 1.38f,
        fontSizeMultiplier = 1.05f,
        letterSpacing = (-0.7).sp,
        glowActiveWord = false,
        wordGlowRadiusPx = 0f,
        activeLineGlowRadiusPx = 0f,
        futureWordAlpha = 0.20f,
        passedWordAlpha = 1.0f,
        useAccentForWordGlow = false,
        wordLiftEffect = false,
        wordGlideEffect = true,
        romanizedTextAlpha = 0.20f,
        romanizedBackgroundAlpha = 0.0f,
        translatedTextAlpha = 0.20f,
        smallerTranslatedSize = true,
        themeAccentColor = null,
        tintNextLineWithAccent = false,
        activeLineProgressFill = false,
        loaderUseScaleAnim = true,
        loaderGlowRadiusPx = 0f,
        scrollLookaheadMs = 600L,
        scrollAnchorFraction = 0.45f,
        scrollDurationMs = 400f,
        scrollEasing = FastOutSlowInEasing,
        transitionDurationMs = 500,
        previousAlphaDurationMs = 900,
        previousBlurDurationMs = 250,
        transitionDelayMs = 0,
        transitionEasing = FastOutSlowInEasing,
    )

}
