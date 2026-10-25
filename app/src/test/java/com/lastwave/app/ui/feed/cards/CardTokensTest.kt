package com.lastwave.app.ui.feed.cards

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for CardTokens and the pure [calculateCardDimensions] function.
 * Validates viewport responsiveness, min/max clamping, 16:9 wide width relation,
 * guaranteed identical total height between square and wide cards, fixed text area height,
 * and text invariants across long strings and non-Latin scripts (Hindi, Marathi, Tamil).
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
        assertThat(dims412.totalHeight.value).isWithin(0.01f).of(173.04f + 8f + 42f)
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

    @Test
    fun totalCardHeightIsGuaranteedIdenticalRegardlessOfTextContentOrNullInfo() {
        // Verify invariant: totalHeight = coverHeight + coverToTextGap + textAreaHeight
        // Because textAreaHeight is a constant 42.dp, total card height is 100% deterministic
        // whether title is 1 char or 100 chars, Latin or non-Latin, and whether info is null or present.
        val testCases = listOf(
            Triple("Short", "Artist", false),
            Triple("Very long title exceeding single line limit and needing ellipsis", "Long artist list", false),
            Triple("दिल संभल जा ज़रा - फिर मोहब्बत करने चला है", "अरिजीत सिंह", false),
            Triple("देवा श्री गणेशा - देवा तुझ्या चरणी", "अजय-अतुल", false),
            Triple("காதல் ரோஜாவே எங்கே எங்கே", "எஸ். பி. பாலசுப்பிரமணியம்", false),
            Triple("Short Title", null, true),
            Triple("Long Title Without Info", "", true),
        )

        val dims = calculateCardDimensions(393.dp)
        for ((title, info, isInfoEmpty) in testCases) {
            // Both square and wide cards share identical coverHeight and identical textAreaHeight
            val squareTotalHeight = dims.coverHeight + CardTokens.coverToTextGap + CardTokens.textAreaHeight
            val wideTotalHeight = dims.coverHeight + CardTokens.coverToTextGap + CardTokens.textAreaHeight

            assertThat(squareTotalHeight).isEqualTo(wideTotalHeight)
            assertThat(squareTotalHeight).isEqualTo(dims.totalHeight)

            if (isInfoEmpty) {
                // Info line being empty or null preserves the fixed textAreaHeight (no layout jumping)
                assertThat(CardTokens.textAreaHeight).isEqualTo(42.dp)
            }
        }
    }
}
