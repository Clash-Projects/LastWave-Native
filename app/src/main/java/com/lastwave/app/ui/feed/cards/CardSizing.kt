/*
 * Responsive card sizing for native shelf cards. Widths derive from the
 * available viewport so ~2.3 cards peek on phones and scale up on tablets /
 * landscape. Pure Dp math — no recomposition cost beyond configuration.
 */
package com.lastwave.app.ui.feed.cards

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

import androidx.compose.runtime.remember

/**
 * Remembers the unified [CardDimensions] for the current viewport width.
 */
@Composable
fun rememberCardDimensions(): CardDimensions {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    return remember(screenWidth) {
        calculateCardDimensions(screenWidth)
    }
}

/** Unified cover height shared by square and wide cards. */
@Composable
fun rememberCoverHeight(): Dp = rememberCardDimensions().coverHeight

/** Square card width: equals coverHeight for 1:1 aspect ratio. */
@Composable
fun rememberSquareCardWidth(): Dp = rememberCardDimensions().squareWidth

/** Wide 16:9 card width: equals coverHeight * 16/9. */
@Composable
fun rememberWideCardWidth(): Dp = rememberCardDimensions().wideWidth

/** Featured big-square card width: ~1.7 cards peek on phones. */
@Composable
fun rememberFeaturedCardWidth(): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    return when {
        screenWidth < 360.dp -> (screenWidth * 0.56f).coerceAtLeast(180.dp)
        screenWidth < 600.dp -> (screenWidth * 0.54f).coerceAtMost(260.dp)
        screenWidth < 840.dp -> (screenWidth * 0.36f).coerceAtMost(300.dp)
        else -> (screenWidth * 0.24f).coerceAtMost(320.dp)
    }
}

/** Community playlist card width: a wide glass card, ~1.3 peek on phones. */
@Composable
fun rememberCommunityCardWidth(): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    return when {
        screenWidth < 360.dp -> (screenWidth * 0.78f).coerceAtLeast(260.dp)
        screenWidth < 600.dp -> (screenWidth * 0.76f).coerceAtMost(340.dp)
        screenWidth < 840.dp -> (screenWidth * 0.5f).coerceAtMost(380.dp)
        else -> (screenWidth * 0.34f).coerceAtMost(400.dp)
    }
}

/** Podcast episode card width: large 16:9 card ~88% of screen width, clamped ~280-420dp, next card peeking. */
@Composable
fun rememberPodcastEpisodeCardWidth(): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    return (screenWidth * 0.88f).coerceIn(280.dp, 420.dp)
}

