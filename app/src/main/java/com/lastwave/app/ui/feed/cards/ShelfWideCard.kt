/*
 * WideCard: 16:9 thumbnail + title + info line ("Channel • 73M views").
 */
package com.lastwave.app.ui.feed.cards

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lastwave.app.R

/**
 * Wide 16:9 video card. Used when a two-row item declares a rectangular
 * aspect ratio (recommended music videos, video shelves).
 */
@OptIn(ExperimentalFoundationApi::class)
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
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = modifier
            .width(cardWidth)
            .cardPressable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .width(cardWidth)
                .aspectRatio(16f / 9f),
        ) {
            CardArtwork(
                title = title,
                subtitle = infoLine,
                artworkUrl = artworkUrl,
                fallbackIcon = fallbackIcon,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 16.dp,
            )
            if (onPlayClick != null) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = 8.dp)
                        .size(36.dp)
                        .combinedClickable(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onPlayClick()
                            },
                            onLongClick = onLongClick?.let { tray ->
                                {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    tray()
                                }
                            },
                        ),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.feed_play_title_cd, title),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        CardTitle(text = title, maxLines = 2, modifier = Modifier.padding(horizontal = 4.dp).fillMaxWidth())
        Spacer(Modifier.height(1.dp))
        CardInfoLine(text = infoLine, modifier = Modifier.padding(horizontal = 4.dp).fillMaxWidth())
    }
}
