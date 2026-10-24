/*
 * WideCard: 16:9 thumbnail + title + info line ("Channel • 73M views").
 */
package com.lastwave.app.ui.feed.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Wide 16:9 video card. Used when a two-row item declares a rectangular
 * aspect ratio (recommended music videos, video shelves).
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
    cardWidth: Dp = 232.dp,
) {
    Column(
        modifier = modifier
            .width(cardWidth)
            .cardPressable(onClick = onClick, onLongClick = onLongClick),
    ) {
        CardArtwork(
            title = title,
            subtitle = infoLine,
            artworkUrl = artworkUrl,
            fallbackIcon = fallbackIcon,
            modifier = Modifier
                .width(cardWidth)
                .aspectRatio(16f / 9f),
            cornerRadius = 16.dp,
        )
        Spacer(Modifier.height(8.dp))
        CardTitle(text = title, maxLines = 2, modifier = Modifier.padding(horizontal = 4.dp).fillMaxWidth())
        Spacer(Modifier.height(1.dp))
        CardInfoLine(text = infoLine, modifier = Modifier.padding(horizontal = 4.dp).fillMaxWidth())
    }
}
