package com.lastwave.app.ui.moods

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.google.common.truth.Truth.assertThat
import kotlin.math.pow
import org.junit.Test

class MoodTileColorsTest {
    @Test
    fun neutralCategoriesHaveStableVariedColours() {
        val colours = (0..50).map { moodTileColors(0xFF555555, "category:$it") }
        assertThat(colours.map { it.accent }.distinct().size).isAtLeast(6)
        assertThat(moodTileColors(0xFF555555, "category:12"))
            .isEqualTo(moodTileColors(0xFF555555, "category:12"))
    }

    @Test
    fun saturatedYtmColoursKeepTheirHue() {
        val red = moodTileColors(0xFFFF0000, "same").accent
        val green = moodTileColors(0xFF00FF00, "same").accent
        val blue = moodTileColors(0xFF0000FF, "same").accent
        assertThat(red.red).isGreaterThan(red.green)
        assertThat(red.red).isGreaterThan(red.blue)
        assertThat(green.green).isGreaterThan(green.red)
        assertThat(green.green).isGreaterThan(green.blue)
        assertThat(blue.blue).isGreaterThan(blue.red)
        assertThat(blue.blue).isGreaterThan(blue.green)
    }

    @Test
    fun whiteLabelsHaveReadableContrastAcrossEveryHueAndNeutralFallback() {
        val sources = (0 until 360 step 5).map { Color.hsv(it.toFloat(), 1f, 1f) }
        sources.forEachIndexed { index, source ->
            val argb = (0xFF000000L or ((source.red * 255).toLong() shl 16) or
                ((source.green * 255).toLong() shl 8) or (source.blue * 255).toLong())
            assertReadable(moodTileColors(argb, "hue:$index"))
        }
        for (source in listOf(0L, 0xFF000000L, 0xFFFFFFFFL, 0xFF777777L)) {
            for (index in 0..20) assertReadable(moodTileColors(source, "neutral:$index"))
        }
    }

    @Test
    fun homePastelsKeepStableCategoryColoursAndTranslucentSurfaces() {
        val colours = (0..50).map { homeMoodTileColors(0xFF555555, "category:$it") }
        assertThat(colours.map { it.accent }.distinct().size).isAtLeast(6)
        assertThat(homeMoodTileColors(0xFF555555, "category:12"))
            .isEqualTo(homeMoodTileColors(0xFF555555, "category:12"))
        colours.forEach {
            assertThat(it.surfaceTop.alpha).isLessThan(1f)
            assertThat(it.surfaceBottom.alpha).isLessThan(1f)
        }
    }

    @Test
    fun homePastelLabelsStayReadableOnLightAndDarkBackgrounds() {
        val sources = (0 until 360 step 5).map { Color.hsv(it.toFloat(), 1f, 1f) } +
            listOf(Color.Black, Color.White, Color.Gray)
        sources.forEachIndexed { index, source ->
            val argb = (0xFF000000L or ((source.red * 255).toLong() shl 16) or
                ((source.green * 255).toLong() shl 8) or (source.blue * 255).toLong())
            val colours = homeMoodTileColors(argb, "hue:$index")
            for (background in listOf(Color.Black, Color.White)) {
                for (surface in listOf(colours.surfaceTop, colours.surfaceBottom)) {
                    val composed = surface.compositeOver(background)
                    val contrast = (luminance(composed) + 0.05) / (luminance(colours.content) + 0.05)
                    assertThat(contrast).isAtLeast(4.5)
                }
            }
        }
    }

    private fun assertReadable(colours: MoodTileColors) {
        for (surface in listOf(
            colours.surfaceTop,
            colours.surfaceBottom,
            colours.accent.copy(alpha = 0.06f).compositeOver(colours.surfaceTop),
        )) {
            assertThat(1.05 / (luminance(surface) + 0.05)).isAtLeast(4.5)
            assertThat(surface.alpha).isEqualTo(1f)
        }
    }

    private fun luminance(color: Color): Double {
        fun linear(value: Float): Double = if (value <= 0.04045f) value / 12.92
            else ((value + 0.055) / 1.055).pow(2.4)
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
}
