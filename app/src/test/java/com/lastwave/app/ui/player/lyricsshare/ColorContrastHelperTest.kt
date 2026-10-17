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
    fun deriveTextColors_onPureBlack_meetsWcagAaRequirements() {
        val derived = ColorContrastHelper.deriveTextColors(ColorContrastHelper.PURE_BLACK)

        assertThat(derived.isDarkBackground).isTrue()

        // Lyrics contrast must be >= 4.5:1 (WCAG AA)
        val lyricsContrast = ColorContrastHelper.contrastRatio(derived.lyricsTextColor, derived.backgroundColor)
        assertThat(lyricsContrast).isAtLeast(4.5)

        // Secondary text contrast must be >= 3.0:1
        val secondaryContrast = ColorContrastHelper.contrastRatio(derived.secondaryTextColor, derived.backgroundColor)
        assertThat(secondaryContrast).isAtLeast(3.0)
    }

    @Test
    fun deriveTextColors_onPureWhite_meetsWcagAaRequirements() {
        val derived = ColorContrastHelper.deriveTextColors(ColorContrastHelper.PURE_WHITE)

        assertThat(derived.isDarkBackground).isFalse()

        val lyricsContrast = ColorContrastHelper.contrastRatio(derived.lyricsTextColor, derived.backgroundColor)
        assertThat(lyricsContrast).isAtLeast(4.5)

        val secondaryContrast = ColorContrastHelper.contrastRatio(derived.secondaryTextColor, derived.backgroundColor)
        assertThat(secondaryContrast).isAtLeast(3.0)
    }

    @Test
    fun deriveTextColors_onBrandLime_meetsWcagAaRequirements() {
        // Brand lime is 0xFFC9FB00, high luminance (light background)
        val derived = ColorContrastHelper.deriveTextColors(ColorContrastHelper.BRAND_LIME)

        assertThat(derived.isDarkBackground).isFalse()

        val lyricsContrast = ColorContrastHelper.contrastRatio(derived.lyricsTextColor, derived.backgroundColor)
        assertThat(lyricsContrast).isAtLeast(4.5)

        val secondaryContrast = ColorContrastHelper.contrastRatio(derived.secondaryTextColor, derived.backgroundColor)
        assertThat(secondaryContrast).isAtLeast(3.0)
    }

    @Test
    fun deriveTextColors_onDarkSlate_meetsWcagAaRequirements() {
        val darkSlate = 0xFF1E293B.toInt()
        val derived = ColorContrastHelper.deriveTextColors(darkSlate)

        assertThat(derived.isDarkBackground).isTrue()

        val lyricsContrast = ColorContrastHelper.contrastRatio(derived.lyricsTextColor, derived.backgroundColor)
        assertThat(lyricsContrast).isAtLeast(4.5)

        val secondaryContrast = ColorContrastHelper.contrastRatio(derived.secondaryTextColor, derived.backgroundColor)
        assertThat(secondaryContrast).isAtLeast(3.0)
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

        // Brand Lime and Accent swatch colors present
        val brandSwatch = swatches.first { it.id == "brand" }
        assertThat(brandSwatch.color).isEqualTo(ColorContrastHelper.BRAND_LIME)

        val accentSwatch = swatches.first { it.id == "accent" }
        assertThat(accentSwatch.color).isEqualTo(0xFF38BDF8.toInt())
    }

    @Test
    fun extractSwatches_withBitmap_extractsColorsSafely() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFFFF5722.toInt()) // Deep orange

        val swatches = ColorContrastHelper.extractSwatches(
            artBitmap = bitmap,
            themeAccentColor = 0xFF38BDF8.toInt(),
        )

        assertThat(swatches).hasSize(8)
    }
}
