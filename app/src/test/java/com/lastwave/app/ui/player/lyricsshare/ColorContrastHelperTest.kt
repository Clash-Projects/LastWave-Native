package com.lastwave.app.ui.player.lyricsshare

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ColorContrastHelperTest {

    @Test
    fun relativeLuminance_pureWhiteAndBlack() {
        val whiteLum = ColorContrastHelper.relativeLuminance(ColorContrastHelper.PURE_WHITE)
        val blackLum = ColorContrastHelper.relativeLuminance(0xFF000000.toInt())

        assertThat(whiteLum).isWithin(0.01).of(1.0)
        assertThat(blackLum).isWithin(0.001).of(0.0)
    }

    @Test
    fun contrastRatio_whiteOnBlack_isNearMax21() {
        val ratio = ColorContrastHelper.contrastRatio(0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        assertThat(ratio).isAtLeast(20.0)
    }

    @Test
    fun deriveTextColors_onPureBlack_meetsWcagRequirements() {
        val derived = ColorContrastHelper.deriveTextColors(ColorContrastHelper.PURE_BLACK)

        assertThat(derived.isDarkBackground).isTrue()

        // Lyrics contrast must be >= 7.0:1 (Enhanced Contrast)
        val lyricsContrast = ColorContrastHelper.contrastRatio(derived.lyricsTextColor, derived.backgroundColor)
        assertThat(lyricsContrast).isAtLeast(7.0)

        // Secondary & Brand text contrast must be >= 4.5:1
        val secondaryContrast = ColorContrastHelper.contrastRatio(derived.secondaryTextColor, derived.backgroundColor)
        assertThat(secondaryContrast).isAtLeast(4.5)

        val brandContrast = ColorContrastHelper.contrastRatio(derived.brandTextColor, derived.backgroundColor)
        assertThat(brandContrast).isAtLeast(4.5)
    }

    @Test
    fun deriveTextColors_onPureWhite_meetsWcagRequirements() {
        val derived = ColorContrastHelper.deriveTextColors(ColorContrastHelper.PURE_WHITE)

        assertThat(derived.isDarkBackground).isFalse()

        val lyricsContrast = ColorContrastHelper.contrastRatio(derived.lyricsTextColor, derived.backgroundColor)
        assertThat(lyricsContrast).isAtLeast(7.0)

        val secondaryContrast = ColorContrastHelper.contrastRatio(derived.secondaryTextColor, derived.backgroundColor)
        assertThat(secondaryContrast).isAtLeast(4.5)

        val brandContrast = ColorContrastHelper.contrastRatio(derived.brandTextColor, derived.backgroundColor)
        assertThat(brandContrast).isAtLeast(4.5)
    }

    @Test
    fun deriveTextColors_onBrandLime_meetsWcagRequirements() {
        val derived = ColorContrastHelper.deriveTextColors(ColorContrastHelper.BRAND_LIME)

        val lyricsContrast = ColorContrastHelper.contrastRatio(derived.lyricsTextColor, derived.backgroundColor)
        assertThat(lyricsContrast).isAtLeast(7.0)

        val secondaryContrast = ColorContrastHelper.contrastRatio(derived.secondaryTextColor, derived.backgroundColor)
        assertThat(secondaryContrast).isAtLeast(4.5)

        val brandContrast = ColorContrastHelper.contrastRatio(derived.brandTextColor, derived.backgroundColor)
        assertThat(brandContrast).isAtLeast(4.5)
    }

    @Test
    fun deriveTextColors_onDarkSlate_meetsWcagRequirements() {
        val darkSlate = 0xFF1E293B.toInt()
        val derived = ColorContrastHelper.deriveTextColors(darkSlate)

        val lyricsContrast = ColorContrastHelper.contrastRatio(derived.lyricsTextColor, derived.backgroundColor)
        assertThat(lyricsContrast).isAtLeast(7.0)

        val secondaryContrast = ColorContrastHelper.contrastRatio(derived.secondaryTextColor, derived.backgroundColor)
        assertThat(secondaryContrast).isAtLeast(4.5)

        val brandContrast = ColorContrastHelper.contrastRatio(derived.brandTextColor, derived.backgroundColor)
        assertThat(brandContrast).isAtLeast(4.5)
    }

    @Test
    fun deriveTextColors_onWarmBrownAndSaturatedColors_meetsWcagRequirements() {
        // Deep amber/brown like YouTube Music screenshot
        val colors = listOf(
            0xFF78350F.toInt(), // Warm Amber / Brown
            0xFFDC2626.toInt(), // Vibrant Crimson / Red
            0xFF2563EB.toInt(), // Vivid Blue
            0xFF059669.toInt(), // Emerald Green
            0xFF7C3AED.toInt(), // Purple
        )

        for (c in colors) {
            val derived = ColorContrastHelper.deriveTextColors(c)
            val lyricsContrast = ColorContrastHelper.contrastRatio(derived.lyricsTextColor, derived.backgroundColor)
            assertThat(lyricsContrast).isAtLeast(7.0)

            val secondaryContrast = ColorContrastHelper.contrastRatio(derived.secondaryTextColor, derived.backgroundColor)
            assertThat(secondaryContrast).isAtLeast(4.5)

            val brandContrast = ColorContrastHelper.contrastRatio(derived.brandTextColor, derived.backgroundColor)
            assertThat(brandContrast).isAtLeast(4.5)
        }
    }

    @Test
    fun deriveTextColors_coverScrim_meetsWcagRequirements() {
        val derived = ColorContrastHelper.deriveTextColors(
            backgroundColor = ColorContrastHelper.SWATCH_COVER_ART,
            isCoverScrim = true,
        )

        assertThat(derived.isDarkBackground).isTrue()

        val lyricsContrast = ColorContrastHelper.contrastRatio(derived.lyricsTextColor, derived.backgroundColor)
        assertThat(lyricsContrast).isAtLeast(7.0)

        val secondaryContrast = ColorContrastHelper.contrastRatio(derived.secondaryTextColor, derived.backgroundColor)
        assertThat(secondaryContrast).isAtLeast(4.5)

        val brandContrast = ColorContrastHelper.contrastRatio(derived.brandTextColor, derived.backgroundColor)
        assertThat(brandContrast).isAtLeast(4.5)
    }

    @Test
    fun getSelectScreenGradientColors_returnsRichHueGradient() {
        val red = 0xFFEF4444.toInt()
        val (top, bottom) = ColorContrastHelper.getSelectScreenGradientColors(red)

        // Neither top nor bottom should be black or grey
        val topLum = ColorContrastHelper.relativeLuminance(top)
        val bottomLum = ColorContrastHelper.relativeLuminance(bottom)

        assertThat(topLum).isGreaterThan(0.01)
        assertThat(bottomLum).isGreaterThan(topLum) // bottom is slightly lighter shade of the same hue
    }

    @Test
    fun getSelectedPillTextColor_hasHighContrastOnWhite() {
        val red = 0xFFEF4444.toInt()
        val textColor = ColorContrastHelper.getSelectedPillTextColor(red)
        val contrastOnWhite = ColorContrastHelper.contrastRatio(textColor, ColorContrastHelper.PURE_WHITE)

        assertThat(contrastOnWhite).isAtLeast(7.0)
    }

    @Test
    fun extractSwatches_alwaysReturns8DistinctSwatches() {
        val swatches = ColorContrastHelper.extractSwatches(
            artBitmap = null,
            themeAccentColor = 0xFF38BDF8.toInt(),
        )

        assertThat(swatches).hasSize(8)

        val ids = swatches.map { it.id }
        assertThat(ids).containsExactly(
            "song_vibrant",
            "muted",
            "dominant",
            "dark",
            "white",
            "black",
            "accent",
            "brand",
        ).inOrder()

        val brandSwatch = swatches.first { it.id == "brand" }
        assertThat(brandSwatch.color).isEqualTo(ColorContrastHelper.BRAND_LIME)

        val accentSwatch = swatches.first { it.id == "accent" }
        assertThat(accentSwatch.color).isEqualTo(0xFF38BDF8.toInt())
    }

    @Test
    fun extractSwatches_withBitmap_extractsColorsSafely() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFFFF5722.toInt())

        val swatches = ColorContrastHelper.extractSwatches(
            artBitmap = bitmap,
            themeAccentColor = 0xFF38BDF8.toInt(),
        )

        assertThat(swatches).hasSize(8)
    }
}
