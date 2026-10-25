/*
 * Central design tokens and sizing calculation for the YouTube Music style card system.
 * Uniform cover height across square and wide cards, small corner radius (4dp),
 * two-line fixed text container, and tighter feed spacing.
 */
package com.lastwave.app.ui.feed.cards

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dimensions calculated for feed cards given a viewport width.
 * Square and wide cards share the exact same [coverHeight], ensuring
 * visual consistency and identical total card height across all rows.
 */
data class CardDimensions(
    val screenWidth: Dp,
    val coverHeight: Dp,
    val squareWidth: Dp,
    val wideWidth: Dp,
    val totalHeight: Dp,
) {
    val totalSquareCardHeight: Dp get() = totalHeight
    val totalWideCardHeight: Dp get() = totalHeight
}

/**
 * Central design tokens for the YouTube Music style card system.
 * Governs cover proportions, corner radiuses, typography, and feed spacing.
 */
object CardTokens {
    /** Cover height is ~42% of screen width on phones, clamped for tablets/landscape. */
    const val COVER_HEIGHT_RATIO = 0.42f
    val MIN_COVER_HEIGHT: Dp = 132.dp
    val MAX_COVER_HEIGHT: Dp = 200.dp

    /** Small corner radius for covers (YTM-like, around 4dp). */
    val coverCornerRadius: Dp = 4.dp

    /** Consistent horizontal gap between cards in a feed row (10-12dp). */
    val cardGap: Dp = 12.dp

    /** Reduced vertical gap between feed sections (halved from legacy 40dp to 16dp). */
    val sectionGap: Dp = 16.dp

    /** Vertical gap between cover artwork and text container. */
    val coverToTextGap: Dp = 8.dp

    /** Fixed text area height for exactly two single lines (title + info). */
    val textAreaHeight: Dp = 42.dp

    /** Spacing between title and info line inside text container. */
    val titleToInfoGap: Dp = 2.dp

    /** Typography tokens: 14sp medium title, 12sp muted info. */
    val titleFontSize: TextUnit = 14.sp
    val titleLineHeight: TextUnit = 20.sp
    val infoFontSize: TextUnit = 12.sp
    val infoLineHeight: TextUnit = 16.sp
}

/**
 * Pure function to calculate card dimensions for a given screen width.
 * Wide card width is strictly `coverHeight * (16 / 9)`.
 */
fun calculateCardDimensions(
    screenWidth: Dp,
    coverHeightRatio: Float = CardTokens.COVER_HEIGHT_RATIO,
    minCoverHeight: Dp = CardTokens.MIN_COVER_HEIGHT,
    maxCoverHeight: Dp = CardTokens.MAX_COVER_HEIGHT,
    coverToTextGap: Dp = CardTokens.coverToTextGap,
    textAreaHeight: Dp = CardTokens.textAreaHeight,
): CardDimensions {
    val rawHeight = screenWidth * coverHeightRatio
    val coverHeight = rawHeight.coerceIn(minCoverHeight, maxCoverHeight)
    val squareWidth = coverHeight
    val wideWidth = (coverHeight.value * (16f / 9f)).dp
    val totalHeight = coverHeight + coverToTextGap + textAreaHeight

    return CardDimensions(
        screenWidth = screenWidth,
        coverHeight = coverHeight,
        squareWidth = squareWidth,
        wideWidth = wideWidth,
        totalHeight = totalHeight,
    )
}
