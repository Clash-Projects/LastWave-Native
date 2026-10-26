package com.lastwave.app.ui.feed.cards

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.lastwave.app.ui.theme.Md3SchemeBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CommunityPlaylistTintTest {
    @Test
    fun brightCoversBecomeDarkAndFaint() {
        for (input in listOf(0xFFFFFAEE, 0xFFFFCC88, 0xFFAAFFDD, 0xFFE0BBFF)) {
            assertDarkAndFaint(tintFromColor(input.toInt()))
        }
    }

    @Test
    fun darkCoversAreNotBrightened() {
        for (input in listOf(0xFF000000, 0xFF1A2026, 0xFF180000, 0xFF151019)) {
            val tint = tintFromColor(input.toInt())
            assertDarkAndFaint(tint)
            assertTrue(lightness(tint) <= lightness(Color(input.toInt())) + 1f / 255f)
        }
    }

    @Test
    fun greysStayNeutralIncludingBlackAndWhite() {
        for (input in listOf(0xFF000000, 0xFF101010, 0xFF808080, 0xFFFFFFFF)) {
            val tint = tintFromColor(input.toInt())
            assertDarkAndFaint(tint)
            assertEquals(tint.red, tint.green, 0f)
            assertEquals(tint.green, tint.blue, 0f)
        }
    }

    @Test
    fun saturatedCoversRetainTheirHueWithoutTheirIntensity() {
        val red = tintFromColor(0xFFFF0000.toInt())
        val green = tintFromColor(0xFF00FF00.toInt())
        val blue = tintFromColor(0xFF0000FF.toInt())
        val purple = tintFromColor(0xFFFF00FF.toInt())
        for (tint in listOf(red, green, blue, purple)) assertDarkAndFaint(tint)
        assertTrue(red.red > red.green && red.green == red.blue)
        assertTrue(green.green > green.red && green.red == green.blue)
        assertTrue(blue.blue > blue.red && blue.red == blue.green)
        assertTrue(purple.red == purple.blue && purple.red > purple.green)
    }

    @Test
    fun brightestAllowedTintKeepsTitleContrastAcrossDarkSchemes() {
        // Use the full requested bounds, beyond the helper's rounding headroom.
        val brightestTint = (0 until 360).map { Color.hsl(it.toFloat(), 0.45f, 0.16f) }
            .maxBy { it.luminance() }
        val schemes = listOf(
            Md3SchemeBuilder.buildLastWaveDarkScheme(amoled = false),
            Md3SchemeBuilder.buildLastWaveDarkScheme(amoled = true),
            Md3SchemeBuilder.buildMonochromeDarkScheme(amoled = false),
            Md3SchemeBuilder.buildMonochromeDarkScheme(amoled = true),
        ) + listOf("#FF0000", "#FFFF00", "#00FF00", "#00FFFF", "#0000FF", "#FF00FF")
            .flatMap { seed -> listOf(false, true).map { Md3SchemeBuilder.buildDarkScheme(seed, it) } }
        for (scheme in schemes) {
            val glass = scheme.surfaceContainerHigh.copy(alpha = 0.76f)
            val tintedGlass = brightestTint.copy(alpha = 0.22f).compositeOver(glass)
            // The existing highlight is strongest at the gradient's start.
            val background = scheme.onSurface.copy(alpha = 0.035f)
                .compositeOver(tintedGlass).compositeOver(scheme.background)
            val contrast = (scheme.onSurface.luminance() + 0.05f) / (background.luminance() + 0.05f)
            assertTrue("Title contrast was $contrast:1", contrast >= 4.5f)
        }
    }

    private fun assertDarkAndFaint(color: Color) {
        val high = maxOf(color.red, color.green, color.blue)
        val low = minOf(color.red, color.green, color.blue)
        val lightness = lightness(color)
        val saturation = if (high == low) 0f else (high - low) / (1f - abs(2f * lightness - 1f))
        assertTrue("Lightness was $lightness", lightness <= 0.16f)
        assertTrue("Saturation was $saturation", saturation <= 0.45f)
        assertEquals(1f, color.alpha, 0f)
    }

    private fun lightness(color: Color): Float =
        (maxOf(color.red, color.green, color.blue) + minOf(color.red, color.green, color.blue)) / 2f
}
