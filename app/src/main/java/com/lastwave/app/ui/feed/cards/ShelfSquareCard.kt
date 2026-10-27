/*
 * SquareCard: square cover + title + info line. Default native shelf card.
 */
package com.lastwave.app.ui.feed.cards

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp

/**
 * Square cover + title (1 line) + info line (1 line). The default native shelf card —
 * used for songs, albums and playlists with uniform cover height and two-line text.
 */
@Composable
fun ShelfSquareCard(
    title: String,
    infoLine: String,
    artworkUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onPlayClick: (() -> Unit)? = null,
    fallbackIcon: ImageVector = Icons.Filled.MusicNote,
    cardWidth: Dp = rememberSquareCardWidth(),
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
            )
        }
        Spacer(Modifier.height(CardTokens.coverToTextGap))
        CardTextArea(title = title, infoLine = infoLine)
    }
}
