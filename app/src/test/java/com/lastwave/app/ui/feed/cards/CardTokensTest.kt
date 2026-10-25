package com.lastwave.app.ui.feed.cards

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for CardTokens and the pure [calculateCardDimensions] function.
 * Validates viewport responsiveness, min/max clamping, 16:9 wide width relation,
 * and guaranteed identical total height between square and wide cards.
 */
class CardTokensTest {

    @Test
    fun tokensMatchDesignRequirements() {
        assertThat(CardTokens.coverCornerRadius).isEqualTo(4.dp)
        assertThat(CardTokens.cardGap).isEqualTo(12.dp)
        assertThat(CardTokens.sectionGap).isEqualTo(16.dp)
        assertThat(CardTokens.textAreaHeight).isEqualTo(42.dp)
        assertThat(CardTokens.coverToTextGap).isEqualTo(8.dp)
        assertThat(CardTokens.titleToInfoGap).isEqualTo(2.dp)
    }

    @Test
    fun standardPhoneCalculatesExpectedDimensions() {
        val dims360 = calculateCardDimensions(360.dp)
        // 360 * 0.42 = 151.2dp
        assertThat(dims360.coverHeight.value).isWithin(0.01f).of(151.2f)
        assertThat(dims360.squareWidth).isEqualTo(dims360.coverHeight)
        // wide width = coverHeight * 16 / 9
        assertThat(dims360.wideWidth.value).isWithin(0.02f).of(151.2f * 16f / 9f)
        assertThat(dims360.totalSquareCardHeight).isEqualTo(dims360.totalWideCardHeight)
        assertThat(dims360.totalHeight.value).isWithin(0.01f).of(151.2f + 8f + 42f)

        val dims412 = calculateCardDimensions(412.dp)
        // 412 * 0.42 = 173.04dp
        assertThat(dims412.coverHeight.value).isWithin(0.01f).of(173.04f)
        assertThat(dims412.squareWidth).isEqualTo(dims412.coverHeight)
        assertThat(dims412.wideWidth.value).isWithin(0.02f).of(173.04f * 16f / 9f)
        assertThat(dims412.totalSquareCardHeight).isEqualTo(dims412.totalWideCardHeight)
    }

    @Test
    fun smallScreenRespectsMinimumClamp() {
        // 280 * 0.42 = 117.6dp, should clamp to 132dp
        val dims = calculateCardDimensions(280.dp)
        assertThat(dims.coverHeight).isEqualTo(132.dp)
        assertThat(dims.squareWidth).isEqualTo(132.dp)
        assertThat(dims.wideWidth.value).isWithin(0.02f).of(132f * 16f / 9f)
        assertThat(dims.totalSquareCardHeight).isEqualTo(dims.totalWideCardHeight)
        assertThat(dims.totalHeight).isEqualTo(132.dp + 8.dp + 42.dp)
    }

    @Test
    fun largeScreenAndTabletRespectMaximumClamp() {
        // 768 * 0.42 = 322.56dp -> clamped to 200dp
        val tabletPortrait = calculateCardDimensions(768.dp)
        assertThat(tabletPortrait.coverHeight).isEqualTo(200.dp)
        assertThat(tabletPortrait.squareWidth).isEqualTo(200.dp)
        assertThat(tabletPortrait.wideWidth.value).isWithin(0.02f).of(200f * 16f / 9f)
        assertThat(tabletPortrait.totalSquareCardHeight).isEqualTo(tabletPortrait.totalWideCardHeight)

        // 1200 * 0.42 = 504dp -> clamped to 200dp
        val tabletLandscape = calculateCardDimensions(1200.dp)
        assertThat(tabletLandscape.coverHeight).isEqualTo(200.dp)
        assertThat(tabletLandscape.squareWidth).isEqualTo(200.dp)
        assertThat(tabletLandscape.wideWidth.value).isWithin(0.02f).of(200f * 16f / 9f)
        assertThat(tabletLandscape.totalSquareCardHeight).isEqualTo(tabletLandscape.totalWideCardHeight)
    }

    @Test
    fun wideWidthAlwaysEqualsCoverHeightTimes16Over9() {
        val testWidths = listOf(320.dp, 360.dp, 390.dp, 412.dp, 480.dp, 600.dp, 840.dp, 1024.dp)
        for (w in testWidths) {
            val dims = calculateCardDimensions(w)
            val expectedWide = dims.coverHeight.value * (16f / 9f)
            assertThat(abs(dims.wideWidth.value - expectedWide)).isLessThan(0.02f)
            assertThat(dims.squareWidth).isEqualTo(dims.coverHeight)
            assertThat(dims.totalSquareCardHeight).isEqualTo(dims.totalWideCardHeight)
            assertThat(dims.totalHeight).isEqualTo(dims.coverHeight + CardTokens.coverToTextGap + CardTokens.textAreaHeight)
        }
    }
}
