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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.R
import com.lastwave.app.playback.toPlayableTrack
import com.lastwave.app.ui.playlist.PlaylistViewModel
import com.lastwave.app.ui.playlist.PlaylistSortMode

/**
 * TV Library — the phone Playlists tab (Room-backed saved playlists via the
 * shared [PlaylistViewModel]), rebuilt for the remote:
 *
 * - OK on a playlist expands it in place into its tracks,
 * - OK on a track (or the "Play all" row) queues the whole playlist and
 *   starts from that track via [com.lastwave.app.playback.MusicPlayer.playDiscoverQueue].
 *
 * Everything heavy (Room reads) stays inside the ViewModel; this screen only
 * renders state, keeping the TV pane lag-free.
 */
@Composable
fun TvLibraryTab(
    viewModel: TvHomeViewModel,
    railHomeFocus: FocusRequester,
) {
    val playlistsViewModel: PlaylistViewModel = hiltViewModel()
    val state by playlistsViewModel.uiState.collectAsStateWithLifecycle()
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    val entry = remember { FocusRequester() }

    // Room re-read whenever the tab is opened — a playlist just saved on the
    // phone side shows up the next time this tab mounts.
    LaunchedEffect(Unit) {
        playlistsViewModel.load()
    }

    // Initial focus: first playlist row, or the rail when the library is empty.
    LaunchedEffect(state.playlists.size) {
        withFrameNanos { }
        if (state.playlists.isEmpty()) {
            runCatching { railHomeFocus.requestFocus() }
        } else {
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
            text = stringResource(R.string.tv_library_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (state.playlists.isNotEmpty()) {
            // ArchiveTune ChipsRow grammar: sort pills above the list. The
            // first pill is the pane's focus entry target.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TvSortPill(
                    label = stringResource(R.string.tv_library_sort_recent),
                    selected = state.sortMode == PlaylistSortMode.DATE_DESC,
                    onClick = { playlistsViewModel.setSortMode(PlaylistSortMode.DATE_DESC) },
                    modifier = Modifier.focusRequester(entry),
                )
                TvSortPill(
                    label = stringResource(R.string.tv_library_sort_oldest),
                    selected = state.sortMode == PlaylistSortMode.DATE_ASC,
                    onClick = { playlistsViewModel.setSortMode(PlaylistSortMode.DATE_ASC) },
                )
                TvSortPill(
                    label = stringResource(R.string.tv_library_sort_az),
                    selected = state.sortMode == PlaylistSortMode.NAME,
                    onClick = { playlistsViewModel.setSortMode(PlaylistSortMode.NAME) },
                )
                TvSortPill(
                    label = stringResource(R.string.tv_library_sort_tracks),
                    selected = state.sortMode == PlaylistSortMode.TRACK_COUNT,
                    onClick = { playlistsViewModel.setSortMode(PlaylistSortMode.TRACK_COUNT) },
                )
            }
        }
        if (state.playlists.isEmpty()) {
            Text(
                text = stringResource(R.string.tv_library_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(
                    state.playlists,
                    key = { _, playlist -> playlist.id },
                    contentType = { _, _ -> "playlist" },
                ) { index, playlist ->
                    val expanded = expandedId == playlist.id
                    // Playlist entries fade/shift smoothly on load and reorder.
                    Column(modifier = Modifier.animateItem()) {
                        fun playFrom(trackIndex: Int) {
                            val tracks = playlist.tracks.map { it.toPlayableTrack() }
                            if (tracks.isNotEmpty()) {
                                viewModel.musicPlayer.playDiscoverQueue(tracks, startIndex = trackIndex)
                            }
                        }
                        TvLibraryRow(
                            title = playlist.title,
                            subtitle = if (playlist.tracks.size == 1) {
                                stringResource(R.string.tv_library_one_track)
                            } else {
                                stringResource(R.string.tv_library_track_count, playlist.tracks.size)
                            },
                            icon = Icons.Rounded.LibraryMusic,
                            expanded = expanded,
                            modifier = Modifier,
                            onClick = {
                                if (expanded) {
                                    expandedId = null
                                } else {
                                    expandedId = playlist.id
                                    // YouTube-only playlists arrive as
                                    // metadata-only summaries (no songs) — the
                                    // phone fetches them on open and they
                                    // stream into the shared list. Same here.
                                    if (playlist.tracks.isEmpty() && playlist.id < 0L) {
                                        playlistsViewModel.loadDetail(playlist.id)
                                    }
                                }
                            },
                        )
                        if (expanded) {
                            Spacer(modifier = Modifier.size(4.dp))
                            if (playlist.tracks.isEmpty() && state.isDetailLoading) {
                                // YouTube playlist still fetching its songs.
                                TvLibraryTrackRow(
                                    indexLabel = "",
                                    title = stringResource(R.string.tv_library_loading_songs),
                                    artist = "",
                                    onClick = {},
                                )
                            }
                            TvLibraryRow(
                                title = stringResource(R.string.tv_library_play_all),
                                subtitle = "",
                                icon = Icons.Rounded.PlayArrow,
                                expanded = false,
                                onClick = { playFrom(0) },
                            )
                            playlist.tracks.forEachIndexed { trackIndex, track ->
                                TvLibraryTrackRow(
                                    indexLabel = (trackIndex + 1).toString(),
                                    title = track.name,
                                    artist = track.artist,
                                    onClick = { playFrom(trackIndex) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Focusable playlist/playlist-action row, styled after the Queue rows. */
@Composable
private fun TvLibraryRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
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
            .background(
                if (isFocused) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                }
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Track row inside an expanded playlist — OK plays the playlist from here. */
@Composable
private fun TvLibraryTrackRow(
    indexLabel: String,
    title: String,
    artist: String,
    onClick: () -> Unit,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 44.dp)
            .tvLift(
                focused = isFocused,
                shape = RoundedCornerShape(12.dp),
                focusedScale = 1.01f,
                elevation = 6.dp,
            )
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isFocused) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                }
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = indexLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** ArchiveTune ChipsRow grammar: pill that carries focus, selected and idle states. */
@Composable
private fun TvSortPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    val pillColor by animateColorAsState(
        targetValue = when {
            isFocused -> MaterialTheme.colorScheme.secondaryContainer
            selected -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(durationMillis = 150),
        label = "tvSortPillColor",
    )
    val labelColor by animateColorAsState(
        targetValue = when {
            isFocused -> MaterialTheme.colorScheme.onSecondaryContainer
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 150),
        label = "tvSortPillLabel",
    )
    Box(
        modifier = modifier
            .tvLift(focused = isFocused, shape = RoundedCornerShape(20.dp), focusedScale = 1.05f, elevation = 5.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(pillColor)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = labelColor,
            maxLines = 1,
        )
    }
}
