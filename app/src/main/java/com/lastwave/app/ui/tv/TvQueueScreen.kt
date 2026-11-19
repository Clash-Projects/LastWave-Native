package com.lastwave.app.ui.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lastwave.app.R
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.theme.ArtworkShape

/**
 * TV queue: the upcoming tracks with the current one pinned in view. Focus
 * lands on the playing row; activating any row jumps the player to it via
 * MusicPlayer.seekToQueueItem.
 */
@Composable
fun TvQueueTab(
    viewModel: TvHomeViewModel,
    railHomeFocus: FocusRequester,
) {
    // Progress-free snapshot: the ~60ms position ticker must not recompose the queue.
    val snap by viewModel.musicPlayer.stateWithoutProgress.collectAsStateWithLifecycle()
    val chrome by viewModel.musicPlayer.chromeState.collectAsStateWithLifecycle()
    val queue = snap.queue
    val currentId = chrome.current?.videoId
    val currentIndex = queue.indexOfFirst { it.videoId == currentId }
    val listState = rememberLazyListState()
    val entry = remember { FocusRequester() }

    LaunchedEffect(queue.size, currentIndex) {
        withFrameNanos { }
        if (queue.isEmpty()) {
            runCatching { railHomeFocus.requestFocus() }
        } else {
            if (currentIndex > 0) runCatching { listState.scrollToItem(currentIndex) }
            runCatching { entry.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.tv_queue_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (queue.isEmpty()) {
            Text(
                text = stringResource(R.string.tv_queue_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(queue, key = { index, track -> "$index-${track.videoId}" }) { index, track ->
                    TvQueueRow(
                        track = track,
                        isCurrent = index == currentIndex,
                        indexLabel = (index + 1).toString(),
                        modifier = Modifier
                            // Queue changes glide: rows fade in and shift
                            // smoothly instead of snapping into place.
                            .animateItem()
                            .then(
                                if (index == currentIndex.coerceAtLeast(0)) {
                                    Modifier.focusRequester(entry)
                                } else {
                                    Modifier
                                },
                            ),
                        onClick = { viewModel.musicPlayer.seekToQueueItem(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvQueueRow(
    track: PlayableTrack,
    isCurrent: Boolean,
    indexLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    val rowBackground by animateColorAsState(
        targetValue = when {
            isFocused -> MaterialTheme.colorScheme.surfaceContainerHigh
            isCurrent -> MaterialTheme.colorScheme.surfaceContainerLow
            else -> androidx.compose.ui.graphics.Color.Transparent
        },
        animationSpec = tween(durationMillis = 150),
        label = "tvQueueRowBackground",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .tvLift(
                focused = isFocused,
                shape = RoundedCornerShape(16.dp),
                focusedScale = 1.015f,
                elevation = 5.dp,
            )
            .clip(RoundedCornerShape(16.dp))
            .background(rowBackground)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = indexLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(width = 28.dp, height = 20.dp),
        )
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(ArtworkShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (track.artworkUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(track.artworkUrl)
                        .size(160)
                        .crossfade(220)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(52.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCurrent) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = stringResource(R.string.tv_queue_now_playing),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
