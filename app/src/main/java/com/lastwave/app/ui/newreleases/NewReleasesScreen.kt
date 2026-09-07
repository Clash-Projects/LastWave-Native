package com.lastwave.app.ui.newreleases

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveGroupTrackRow
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator
import com.lastwave.app.ui.common.GroupGap
import com.lastwave.app.ui.common.GroupPosition
import com.lastwave.app.ui.common.HeaderActionIcon
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.groupPositionFor
import com.lastwave.app.ui.common.groupShape
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance
import com.lastwave.app.ui.player.LocalMusicPlayer
import com.lastwave.app.ui.player.PlayingWaveBars

@Composable
fun NewReleasesScreen(
    onBack: () -> Unit = {},
    viewModel: NewReleasesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val musicPlayer = LocalMusicPlayer.current
    val playbackState by musicPlayer.chromeState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var menuTrack by remember { mutableStateOf<YouTubeMusicTrack?>(null) }

    // Infinite scroll trigger: when nearing bottom, load next batch
    val shouldLoadMore by remember(listState, state.tracks.size) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            state.tracks.isNotEmpty() && last >= state.tracks.size - 4
        }
    }
    LaunchedEffect(shouldLoadMore, state.tracks.size) {
        if (shouldLoadMore && !state.isLoading && !state.isLoadingMore && state.tracks.isNotEmpty()) {
            viewModel.loadMore()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 860.dp),
        ) {
            ExpressiveHeader(
                title = "New releases",
                subtitle = "Fresh drops and new songs",
                onBack = onBack,
                actions = {
                    HeaderActionIcon(Icons.Filled.Refresh, "Refresh", viewModel::refresh)
                    HeaderActionIcon(Icons.Filled.Shuffle, "Shuffle all", viewModel::shuffle)
                },
            )

            Box(Modifier.fillMaxSize().safeHorizontalContentPadding()) {
                Crossfade(
                    targetState = state.isLoading && state.tracks.isEmpty(),
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                    label = "newReleasesState",
                ) { isLoading ->
                    if (isLoading) {
                        val shimmer = shimmerBrush()
                        LazyColumn(
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 16.dp,
                                bottom = 24.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
                            ),
                            verticalArrangement = Arrangement.spacedBy(GroupGap),
                        ) {
                            items(12) { index ->
                                NewReleasesSkeletonRow(
                                    brush = shimmer,
                                    position = when (index) {
                                        0 -> GroupPosition.TOP
                                        11 -> GroupPosition.BOTTOM
                                        else -> GroupPosition.MIDDLE
                                    },
                                )
                            }
                        }
                    } else when {
                        state.error != null && state.tracks.isEmpty() -> {
                            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Filled.NewReleases,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(48.dp),
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Text("Couldn't load new releases", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        state.error.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                    Spacer(Modifier.height(18.dp))
                                    TextButton(onClick = viewModel::loadInitial) {
                                        Text("Retry")
                                    }
                                }
                            }
                        }

                        state.tracks.isEmpty() -> {
                            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Filled.NewReleases,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(44.dp),
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Text("No new releases found", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "Pull down or tap refresh to check again.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        else -> {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(
                                    top = 10.dp,
                                    bottom = 24.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
                                ),
                                verticalArrangement = Arrangement.spacedBy(GroupGap),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                item(key = "controls_header") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Button(
                                            onClick = viewModel::playAll,
                                            modifier = Modifier.weight(1f),
                                            shape = CircleShape,
                                        ) {
                                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Play all", fontWeight = FontWeight.Bold)
                                        }
                                        FilledTonalButton(
                                            onClick = viewModel::shuffle,
                                            modifier = Modifier.weight(1f),
                                            shape = CircleShape,
                                        ) {
                                            Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Shuffle", fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }

                                itemsIndexed(
                                    items = state.tracks,
                                    key = { index, track -> "${track.videoId}:$index" },
                                    contentType = { _, _ -> "new_release_track" },
                                ) { index, track ->
                                    val isPlayingThis = playbackState.isPlaying &&
                                        (playbackState.current?.videoId == track.videoId ||
                                            (playbackState.current?.title.equals(track.title, ignoreCase = true) &&
                                                playbackState.current?.artist.equals(track.artist, ignoreCase = true)))

                                    val subtitle = listOfNotNull(
                                        track.artist.takeIf(String::isNotBlank),
                                        track.album?.takeIf(String::isNotBlank),
                                    ).joinToString(" · ").ifBlank { "New release" }

                                    ExpressiveGroupTrackRow(
                                        title = track.title,
                                        subtitle = subtitle,
                                        position = groupPositionFor(index, state.tracks.size),
                                        isPlaying = isPlayingThis,
                                        onClick = { viewModel.playTrack(index) },
                                        onLongClick = { menuTrack = track },
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        leading = {
                                            Box(
                                                modifier = Modifier
                                                    .size(52.dp)
                                                    .clip(RoundedCornerShape(12.dp)),
                                            ) {
                                                ArtworkImage(
                                                    name = track.title,
                                                    artist = track.artist,
                                                    embeddedUrl = track.artworkUrl,
                                                    fallbackIcon = Icons.Filled.NewReleases,
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                                if (isPlayingThis) {
                                                    PlayingWaveBars(
                                                        modifier = Modifier
                                                            .align(Alignment.BottomEnd)
                                                            .padding(2.dp)
                                                            .size(24.dp, 18.dp),
                                                    )
                                                }
                                            }
                                        },
                                        trailing = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                track.durationSeconds?.let { duration ->
                                                    Text(
                                                        text = "%d:%02d".format(duration / 60, duration % 60),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { menuTrack = track },
                                                    modifier = Modifier.size(36.dp),
                                                ) {
                                                    Icon(
                                                        Icons.Filled.MoreVert,
                                                        contentDescription = "Track options",
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }

                                if (state.isLoadingMore) {
                                    item(key = "loading_more_indicator", contentType = "loading") {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(20.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            ExpressiveInlineLoadingIndicator()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    menuTrack?.let { track ->
        val trackIndex = state.tracks.indexOfFirst { it.videoId == track.videoId }
        TrackContextMenuSheet(
            target = TrackMenuTarget.Track(track.title, track.artist, ""),
            capabilities = TrackMenuCapabilities(showCopyActions = true, showDeleteScrobble = false),
            playableTrack = PlayableTrack(
                title = track.title,
                artist = track.artist,
                album = track.album,
                artworkUrl = track.artworkUrl,
                videoId = track.videoId.takeIf(String::isNotBlank),
            ),
            playbackSourceLabel = "New Releases",
            onPlayInLastWave = {
                if (trackIndex >= 0) viewModel.playTrack(trackIndex)
            },
            onDismiss = { menuTrack = null },
        )
    }
}

@Composable
private fun NewReleasesSkeletonRow(
    brush: Brush,
    position: GroupPosition,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = groupShape(position),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(brush),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.65f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush),
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.42f)
                        .height(11.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush),
                )
            }
        }
    }
}

@Composable
private fun shimmerBrush(): Brush {
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
    )
    val transition = rememberInfiniteTransition(label = "shimmerTransition")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerAnim",
    )
    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 300f, translateAnim - 300f),
        end = Offset(translateAnim, translateAnim),
    )
}
