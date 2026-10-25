/*
 * Shimmer skeletons shaped like the real native shelf cards, shown while a
 * shelf (or a chip page) is loading. Uses the same pulse as FeedLoadingSkeleton.
 */
package com.lastwave.app.ui.feed.cards

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
private fun rememberShimmerPulse(): Float {
    val transition = rememberInfiniteTransition(label = "shelfSkeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
        label = "shelfSkeletonPulse",
    )
    return pulse
}

@Composable
private fun Modifier.shimmer(pulse: Float, radius: Dp) =
    this.clip(RoundedCornerShape(radius))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f * pulse + 0.3f))

/** Square-card skeleton row matching [ShelfSquareCard]. */
@Composable
fun ShelfSquareSkeletonRow(cardWidth: Dp = rememberSquareCardWidth(), count: Int = 4) {
    val pulse = rememberShimmerPulse()
    val coverHeight = rememberCoverHeight()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(CardTokens.cardGap),
        userScrollEnabled = false,
    ) {
        items(count) {
            Column {
                Box(Modifier.width(cardWidth).height(coverHeight).shimmer(pulse, CardTokens.coverCornerRadius))
                Spacer(Modifier.height(CardTokens.coverToTextGap))
                Box(Modifier.width(cardWidth * 0.7f).height(12.dp).shimmer(pulse, 4.dp))
                Spacer(Modifier.height(CardTokens.titleToInfoGap))
                Box(Modifier.width(cardWidth * 0.5f).height(10.dp).shimmer(pulse, 4.dp))
            }
        }
    }
}

/** Wide 16:9-card skeleton row matching [ShelfWideCard]. */
@Composable
fun ShelfWideSkeletonRow(cardWidth: Dp = rememberWideCardWidth(), count: Int = 3) {
    val pulse = rememberShimmerPulse()
    val coverHeight = rememberCoverHeight()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(CardTokens.cardGap),
        userScrollEnabled = false,
    ) {
        items(count) {
            Column {
                Box(Modifier.width(cardWidth).height(coverHeight).shimmer(pulse, CardTokens.coverCornerRadius))
                Spacer(Modifier.height(CardTokens.coverToTextGap))
                Box(Modifier.width(cardWidth * 0.7f).height(12.dp).shimmer(pulse, 4.dp))
                Spacer(Modifier.height(CardTokens.titleToInfoGap))
                Box(Modifier.width(cardWidth * 0.5f).height(10.dp).shimmer(pulse, 4.dp))
            }
        }
    }
}

/** Community-playlist skeleton matching [ShelfCommunityPlaylistCard]. */
@Composable
fun ShelfCommunitySkeletonRow(cardWidth: Dp, count: Int = 2) {
    val pulse = rememberShimmerPulse()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false,
    ) {
        items(count) {
            Column(Modifier.width(cardWidth)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(96.dp).shimmer(pulse, 14.dp))
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth(0.8f).height(14.dp).shimmer(pulse, 7.dp))
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth(0.55f).height(11.dp).shimmer(pulse, 5.dp))
                    }
                    Box(Modifier.size(34.dp).shimmer(pulse, 17.dp))
                }
                Spacer(Modifier.height(12.dp))
                repeat(3) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(40.dp).shimmer(pulse, 10.dp))
                        Column(Modifier.weight(1f)) {
                            Box(Modifier.fillMaxWidth(0.7f).height(11.dp).shimmer(pulse, 5.dp))
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth(0.45f).height(9.dp).shimmer(pulse, 4.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
