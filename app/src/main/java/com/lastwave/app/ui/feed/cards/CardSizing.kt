/*
 * Responsive card sizing for native shelf cards. Widths derive from the
 * available viewport so ~2.3 cards peek on phones and scale up on tablets /
 * landscape. Pure Dp math — no recomposition cost beyond configuration.
 */
package com.lastwave.app.ui.feed.cards

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

import androidx.compose.runtime.remember

/**
 * Remembers the unified [CardDimensions] for the current viewport width.
 */
@Composable
fun rememberCardDimensions(): CardDimensions {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val peekWidth = with(LocalDensity.current) { 20.toDp() }
    return remember(screenWidth, peekWidth) {
        // Two square covers plus a peek must fit; capping the shared cover height here keeps
        // wide cards exactly as tall as square ones on narrow screens (no row jitter).
        val fitsTwoCards = (screenWidth - 48.dp - CardTokens.cardGap * 2 - peekWidth) / 2
        calculateCardDimensions(
            screenWidth,
            minCoverHeight = minOf(CardTokens.MIN_COVER_HEIGHT, fitsTwoCards),
            maxCoverHeight = minOf(CardTokens.MAX_COVER_HEIGHT, fitsTwoCards),
        )
    }
}

/** Unified cover height shared by square and wide cards. */
@Composable
fun rememberCoverHeight(): Dp = rememberCardDimensions().coverHeight

/** Square card width: two full cards with a small third-cover peek on phones. */
@Composable
fun rememberSquareCardWidth(): Dp {
    return rememberCardDimensions().squareWidth
}

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

/** Podcast episode card width: large 16:9 card ~80% of screen width, clamped ~260-400dp, next card peeking. */
@Composable
fun rememberPodcastEpisodeCardWidth(): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    return (screenWidth * 0.80f).coerceIn(260.dp, 400.dp)
}


/** Card width that follows the shown artwork, plus the callback the artwork reports into. */
internal class ArtworkCardWidth(val width: Dp, val onAspect: (Float) -> Unit)

/**
 * The shelf flag and the URL only guess the cover shape (a song flagged 16:9 may resolve to
 * square album art and vice versa). Once the image has loaded, its real aspect ratio picks
 * the slot: wide art gets the 16:9 width, anything else the square width, at one shared
 * height. Until then [defaultWidth] is used.
 */
@Composable
internal fun rememberArtworkCardWidth(
    artworkUrl: String?,
    coverHeight: Dp,
    defaultWidth: Dp,
): ArtworkCardWidth {
    val aspect = remember(artworkUrl) { mutableStateOf<Float?>(null) }
    val known = aspect.value
    val target = when {
        known == null -> defaultWidth
        known >= WIDE_ART_MIN_ASPECT -> (coverHeight.value * (16f / 9f)).dp
        else -> coverHeight
    }
    val width by animateDpAsState(target, tween(220), label = "ArtworkCardWidth")
    return remember(width) { ArtworkCardWidth(width) { aspect.value = it } }
}

/** 16:9 is 1.78 and 4:3 letterboxed thumbnails 1.33; anything from 1.5 up counts as wide. */
private const val WIDE_ART_MIN_ASPECT = 1.5f
