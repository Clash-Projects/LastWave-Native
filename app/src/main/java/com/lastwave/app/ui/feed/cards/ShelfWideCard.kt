/*
 * WideCard: 16:9 thumbnail + title + info line ("Channel • 73M views").
 */
package com.lastwave.app.ui.feed.cards

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp

/**
 * Wide 16:9 video card. Used when an item declares a rectangular aspect ratio.
 * Shares identical cover height and two-line text with square cards.
 */
@Composable
fun ShelfWideCard(
    title: String,
    infoLine: String,
    artworkUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onPlayClick: (() -> Unit)? = null,
    fallbackIcon: ImageVector = Icons.Filled.PlayCircle,
    cardWidth: Dp = rememberWideCardWidth(),
) {
    val coverHeight = rememberCoverHeight()
    Column(
        modifier = modifier
            .width(cardWidth)
            .cardPressable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .width(cardWidth)
                .height(coverHeight),
        ) {
            CardArtwork(
                title = title,
                subtitle = infoLine,
                artworkUrl = artworkUrl,
                fallbackIcon = fallbackIcon,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = CardTokens.coverCornerRadius,
            )
        }
        Spacer(Modifier.height(CardTokens.coverToTextGap))
        CardTextArea(title = title, infoLine = infoLine)
    }
}
