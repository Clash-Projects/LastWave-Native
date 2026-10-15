package com.lastwave.app.ui.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.ui.focus.focusProperties
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.player.LyricsUiState
import com.lastwave.app.ui.player.PlayerViewModel
import com.lastwave.app.ui.tv.components.TvSafeArea
import com.lastwave.app.ui.tv.focus.tvFocusHighlight
import java.util.Locale

enum class TvPlayerTab(val label: String, val icon: ImageVector) {
    NOW_PLAYING("Player", Icons.Default.MusicNote),
    QUEUE("Queue", Icons.AutoMirrored.Filled.QueueMusic),
    LYRICS("Lyrics", Icons.Default.Mic),
}

@Composable
fun TvPlayerOverlay(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val chromeState by viewModel.chromeState.collectAsStateWithLifecycle()
    val playerState by viewModel.player.state.collectAsStateWithLifecycle()
    val progressState by viewModel.progressState.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()

    var activeTab by rememberSaveable { mutableStateOf(TvPlayerTab.NOW_PLAYING) }
    val playFocusRequester = remember { FocusRequester() }
    val topTabsRequester = remember { FocusRequester() }
    val middleContentRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { playFocusRequester.requestFocus() }
    }

    val track = chromeState.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F172A),
                        Color(0xFF070B14),
                        Color(0xFF020408),
                    )
                )
            ),
    ) {

        TvSafeArea {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Top Header: Label, Mode Switcher Tabs, and Close Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .focusProperties {
                            down = if (activeTab == TvPlayerTab.NOW_PLAYING) {
                                playFocusRequester
                            } else {
                                middleContentRequester
                            }
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "NOW PLAYING",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            fontSize = 14.sp,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )

                    // Mode Switcher Tabs (Player, Queue, Lyrics)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TvPlayerTab.entries.forEach { tab ->
                            val isSelected = activeTab == tab
                            val badgeCount = if (tab == TvPlayerTab.QUEUE && playerState.queue.isNotEmpty()) {
                                " (${playerState.queue.size})"
                            } else ""
                            TvTabButton(
                                label = "${tab.label}$badgeCount",
                                icon = tab.icon,
                                isSelected = isSelected,
                                onClick = { activeTab = tab },
                                modifier = if (isSelected) Modifier.focusRequester(topTabsRequester) else Modifier,
                            )
                        }
                    }

                    TvTransportButton(
                        icon = Icons.Default.Close,
                        contentDescription = "Close player",
                        onClick = onClose,
                        buttonSize = 44.dp,
                    )
                }

                // Middle Content: Varies based on active tab
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 12.dp),
                ) {
                    when (activeTab) {
                        TvPlayerTab.NOW_PLAYING -> {
                            TvNowPlayingMiddle(track = track)
                        }

                        TvPlayerTab.QUEUE -> {
                            TvQueueMiddle(
                                currentTrack = track,
                                queue = playerState.queue,
                                currentIndex = playerState.currentIndex,
                                focusRequester = middleContentRequester,
                                topRequester = topTabsRequester,
                                bottomRequester = playFocusRequester,
                                onSelectTrack = { index ->
                                    viewModel.player.seekToQueueItem(index)
                                },
                            )
                        }

                        TvPlayerTab.LYRICS -> {
                            TvLyricsMiddle(
                                track = track,
                                lyricsState = lyricsState,
                                currentPositionMs = progressState.positionMs,
                                focusRequester = middleContentRequester,
                                topRequester = topTabsRequester,
                                bottomRequester = playFocusRequester,
                                onSeekTo = { ms -> viewModel.player.seekTo(ms) },
                                onRetry = {
                                    track?.let { viewModel.loadLyrics(it, forceRefresh = true) }
                                },
                            )
                        }
                    }
                }

                // Bottom: Progress bar + Transport Controls
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Progress Bar
                    val positionMs = progressState.positionMs
                    val durationMs = progressState.durationMs
                    val progressFraction = if (durationMs > 0L) {
                        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    Column(modifier = Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = formatTime(positionMs),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = formatTime(durationMs),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Transport Controls Row (D-pad navigable with generous sizing)
                    val likedTrackKeys by viewModel.likedTrackKeys.collectAsStateWithLifecycle(emptySet())
                    val isLiked = track != null && likedTrackKeys.contains(
                        "${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusProperties {
                                up = if (activeTab == TvPlayerTab.NOW_PLAYING) {
                                    topTabsRequester
                                } else {
                                    middleContentRequester
                                }
                            },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Like / Favorite button
                        TvTransportButton(
                            icon = if (isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (isLiked) "Unlike song" else "Like song",
                            isActive = isLiked,
                            activeTint = Color(0xFFE91E63),
                            onClick = {
                                if (track != null) {
                                    viewModel.toggleLiked(track)
                                }
                            },
                            buttonSize = 48.dp,
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        // Shuffle button
                        TvTransportButton(
                            icon = Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            isActive = chromeState.shuffleEnabled,
                            onClick = { viewModel.player.toggleShuffle() },
                            buttonSize = 48.dp,
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        // Fast Rewind 10s
                        TvTransportButton(
                            icon = Icons.Default.FastRewind,
                            contentDescription = "Rewind 10 seconds",
                            onClick = {
                                viewModel.player.seekTo((positionMs - 10000L).coerceAtLeast(0L))
                            },
                            buttonSize = 48.dp,
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        // Previous
                        TvTransportButton(
                            icon = Icons.Default.SkipPrevious,
                            contentDescription = "Previous track",
                            onClick = { viewModel.player.previous() },
                            buttonSize = 54.dp,
                        )

                        Spacer(modifier = Modifier.width(20.dp))

                        // Play / Pause (Hero focal button with initial focus requester)
                        TvTransportButton(
                            icon = if (chromeState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (chromeState.isPlaying) "Pause" else "Play",
                            onClick = { viewModel.player.togglePlayPause() },
                            buttonSize = 68.dp,
                            isHero = true,
                            modifier = Modifier.focusRequester(playFocusRequester),
                        )

                        Spacer(modifier = Modifier.width(20.dp))

                        // Next
                        TvTransportButton(
                            icon = Icons.Default.SkipNext,
                            contentDescription = "Next track",
                            onClick = { viewModel.player.next() },
                            buttonSize = 54.dp,
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        // Fast Forward 10s
                        TvTransportButton(
                            icon = Icons.Default.FastForward,
                            contentDescription = "Forward 10 seconds",
                            onClick = {
                                viewModel.player.seekTo((positionMs + 10000L).coerceAtMost(durationMs))
                            },
                            buttonSize = 48.dp,
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        // Repeat
                        val repeatMode = playerState.repeatMode
                        val repeatIcon = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat
                        val isRepeatActive = repeatMode != Player.REPEAT_MODE_OFF
                        TvTransportButton(
                            icon = repeatIcon,
                            contentDescription = "Repeat mode",
                            isActive = isRepeatActive,
                            onClick = { viewModel.player.cycleRepeatMode() },
                            buttonSize = 48.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvNowPlayingMiddle(
    track: PlayableTrack?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        // Large Artwork Card
        Box(
            modifier = Modifier
                .fillMaxHeight(0.85f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (!track?.artworkUrl.isNullOrBlank()) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val request = remember(track.artworkUrl) {
                    coil.request.ImageRequest.Builder(context)
                        .data(track.artworkUrl)
                        .size(480, 480)
                        .precision(coil.size.Precision.EXACT)
                        .allowHardware(false)
                        .crossfade(false)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(80.dp),
                )
            }
        }

        // Track Details
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = track?.title ?: "No track playing",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = track?.artist.orEmpty(),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 22.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (!track?.album.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = track?.album.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TvQueueMiddle(
    currentTrack: PlayableTrack?,
    queue: List<PlayableTrack>,
    currentIndex: Int,
    onSelectTrack: (Int) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    topRequester: FocusRequester? = null,
    bottomRequester: FocusRequester? = null,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(currentIndex) {
        if (queue.isNotEmpty() && currentIndex in queue.indices) {
            runCatching {
                listState.animateScrollToItem((currentIndex - 1).coerceAtLeast(0))
            }
        }
    }

    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        // Left Column: Compact track info
        Column(
            modifier = Modifier
                .width(240.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (!currentTrack?.artworkUrl.isNullOrBlank()) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val request = remember(currentTrack.artworkUrl) {
                        coil.request.ImageRequest.Builder(context)
                            .data(currentTrack.artworkUrl)
                            .size(240, 240)
                            .precision(coil.size.Precision.EXACT)
                            .allowHardware(false)
                            .crossfade(false)
                            .build()
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = currentTrack.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = currentTrack?.title ?: "Playing Queue",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = currentTrack?.artist.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "${queue.size} songs in queue",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Right Column: Scrollable Queue list
        if (queue.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No songs in queue",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                itemsIndexed(queue) { index, item ->
                    val isCurrent = index == currentIndex
                    val itemModifier = when {
                        index == 0 && focusRequester != null && topRequester != null -> {
                            Modifier
                                .focusRequester(focusRequester)
                                .focusProperties { up = topRequester }
                        }
                        index == queue.lastIndex && bottomRequester != null -> {
                            Modifier.focusProperties { down = bottomRequester }
                        }
                        else -> Modifier
                    }
                    TvQueueItem(
                        index = index + 1,
                        track = item,
                        isCurrent = isCurrent,
                        onClick = { onSelectTrack(index) },
                        modifier = itemModifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvQueueItem(
    index: Int,
    track: PlayableTrack,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val containerColor = when {
        isFocused -> MaterialTheme.colorScheme.primaryContainer
        isCurrent -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }

    val contentColor = when {
        isFocused -> MaterialTheme.colorScheme.onPrimaryContainer
        isCurrent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .tvFocusHighlight(scale = 1.02f, shape = RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (isCurrent) {
            Icon(
                imageVector = Icons.Default.Equalizer,
                contentDescription = "Currently Playing",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text(
                text = "$index",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(20.dp),
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (!track.artworkUrl.isNullOrBlank()) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val request = remember(track.artworkUrl) {
                    coil.request.ImageRequest.Builder(context)
                        .data(track.artworkUrl)
                        .size(96, 96)
                        .precision(coil.size.Precision.EXACT)
                        .allowHardware(false)
                        .crossfade(false)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isCurrent || isFocused) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 14.sp,
                ),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TvLyricsMiddle(
    track: PlayableTrack?,
    lyricsState: LyricsUiState,
    currentPositionMs: Long,
    onSeekTo: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    topRequester: FocusRequester? = null,
    bottomRequester: FocusRequester? = null,
) {
    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        // Left Column: Compact track info
        Column(
            modifier = Modifier
                .width(240.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (!track?.artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = track.artworkUrl,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = track?.title ?: "Lyrics",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = track?.artist.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Right Column: Lyrics View
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            when (lyricsState) {
                LyricsUiState.Idle, LyricsUiState.Loading -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "Loading lyrics...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is LyricsUiState.Error -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            text = "Couldn't load lyrics for this song",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TvTabButton(
                            label = "Retry",
                            icon = Icons.Default.Refresh,
                            isSelected = true,
                            onClick = onRetry,
                        )
                    }
                }

                LyricsUiState.Empty -> {
                    Text(
                        text = "No lyrics available for this song",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is LyricsUiState.Success -> {
                    if (lyricsState.isSynced && lyricsState.lines.isNotEmpty()) {
                        val lyricsListState = rememberLazyListState()
                        val activeLineIndex = remember(lyricsState.lines, currentPositionMs) {
                            val idx = lyricsState.lines.indexOfLast { it.timeMs <= currentPositionMs }
                            idx.coerceAtLeast(0)
                        }

                        LaunchedEffect(activeLineIndex) {
                            runCatching {
                                lyricsListState.animateScrollToItem(
                                    (activeLineIndex - 2).coerceAtLeast(0)
                                )
                            }
                        }

                        LazyColumn(
                            state = lyricsListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            itemsIndexed(lyricsState.lines) { index, line ->
                                val isActive = index == activeLineIndex
                                val interactionSource = remember { MutableInteractionSource() }
                                val isFocused by interactionSource.collectIsFocusedAsState()

                                val lineModifier = when {
                                    index == 0 && focusRequester != null && topRequester != null -> {
                                        Modifier
                                            .focusRequester(focusRequester)
                                            .focusProperties { up = topRequester }
                                    }
                                    index == lyricsState.lines.lastIndex && bottomRequester != null -> {
                                        Modifier.focusProperties { down = bottomRequester }
                                    }
                                    else -> Modifier
                                }

                                Text(
                                    text = line.text.ifBlank { "♪" },
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontSize = if (isActive || isFocused) 26.sp else 20.sp,
                                        fontWeight = if (isActive || isFocused) FontWeight.Bold else FontWeight.Medium,
                                    ),
                                    color = when {
                                        isActive -> MaterialTheme.colorScheme.primary
                                        isFocused -> MaterialTheme.colorScheme.onPrimaryContainer
                                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                    },
                                    modifier = lineModifier
                                        .fillMaxWidth()
                                        .tvFocusHighlight(scale = 1.02f, shape = RoundedCornerShape(8.dp))
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isFocused) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                        .clickable(
                                            interactionSource = interactionSource,
                                            indication = null,
                                            onClick = { onSeekTo(line.timeMs) },
                                        )
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    } else if (!lyricsState.plainLyrics.isNullOrBlank()) {
                        val plainLines = remember(lyricsState.plainLyrics) {
                            lyricsState.plainLyrics.lines()
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(plainLines) { _, lineText ->
                                Text(
                                    text = lineText,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "Instrumental or no lyrics available",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvTabButton(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Surface(
        color = when {
            isFocused -> MaterialTheme.colorScheme.primaryContainer
            isSelected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .tvFocusHighlight(scale = 1.06f, shape = RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = when {
                    isFocused -> MaterialTheme.colorScheme.onPrimaryContainer
                    isSelected -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = when {
                    isFocused -> MaterialTheme.colorScheme.onPrimaryContainer
                    isSelected -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun TvTransportButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    activeTint: Color? = null,
    isHero: Boolean = false,
    buttonSize: Dp = 48.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val containerColor = when {
        isHero && isFocused -> MaterialTheme.colorScheme.primary
        isHero -> MaterialTheme.colorScheme.primaryContainer
        isFocused -> MaterialTheme.colorScheme.surfaceVariant
        isActive && activeTint != null -> activeTint.copy(alpha = 0.25f)
        isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else -> Color.Transparent
    }

    val iconColor = when {
        isHero && isFocused -> MaterialTheme.colorScheme.onPrimary
        isHero -> MaterialTheme.colorScheme.onPrimaryContainer
        isFocused -> MaterialTheme.colorScheme.primary
        isActive && activeTint != null -> activeTint
        isActive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = modifier
            .size(buttonSize)
            .tvFocusHighlight(
                scale = 1.15f,
                shape = CircleShape,
            )
            .clip(CircleShape)
            .background(containerColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(if (isHero) 36.dp else 26.dp),
        )
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}
