package com.lastwave.app.ui.feed

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.lastwave.app.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistResult
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveGroupTrackRow
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.ExpressiveLoadingIndicator
import com.lastwave.app.ui.common.GroupGap
import com.lastwave.app.ui.common.groupPositionFor
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.common.TrackMiniTrayData
import com.lastwave.app.ui.common.TrackMiniTraySheet
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance

@Composable
fun FeedPlaylistDetailScreen(
    playlistId: String,
    onBack: () -> Unit,
    viewModel: FeedPlaylistDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(playlistId) {
        viewModel.load(playlistId)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 860.dp),
        ) {
        val playlist = (state as? FeedPlaylistDetailUiState.Success)?.playlist
        ExpressiveHeader(
            title = playlist?.title?.takeIf(String::isNotBlank) ?: "Playlist",
            subtitle = playlist?.author ?: "YouTube Music",
            onBack = onBack,
        )

        when (val current = state) {
            FeedPlaylistDetailUiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ExpressiveLoadingIndicator(message = stringResource(R.string.fpd_opening))
                }
            }

            is FeedPlaylistDetailUiState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = current.message,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(18.dp))
                    FilledTonalButton(onClick = { viewModel.load(playlistId) }) {
                        Text(stringResource(R.string.fpd_retry))
                    }
                }
            }

            is FeedPlaylistDetailUiState.Success -> {
                if (current.isLoadingMore) {
                    Text(stringResource(R.string.fpd_loading_more), modifier = Modifier.padding(horizontal = 16.dp))
                }
                if (current.loadError != null) {
                    FilledTonalButton(onClick = { viewModel.load(playlistId, force = true) }) {
                        Text(stringResource(R.string.fpd_incomplete))
                    }
                }
                PlaylistContent(
                    playlist = current.playlist,
                    onPlay = viewModel::playFrom,
                    onShuffle = viewModel::shuffle,
                    onSave = viewModel::saveToLibrary,
                    isSaving = current.isSaving,
                    savedToLibrary = current.savedToLibrary,
                    saveError = current.saveError,
                )
            }
        }
        }
    }
}

@Composable
private fun PlaylistContent(
    playlist: YouTubePlaylistResult,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    onSave: () -> Unit,
    isSaving: Boolean,
    savedToLibrary: Boolean,
    saveError: String?,
) {
    var menuTrack by remember { mutableStateOf<YouTubeMusicTrack?>(null) }
    // Long-press (deep press) opens the mini tray; 3-dot keeps the full sheet.
    var miniTrayIndex by remember { mutableStateOf<Int?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = 20.dp,
            bottom = 24.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(GroupGap),
    ) {
        item(key = "hero") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ArtworkImage(
                    name = playlist.title,
                    artist = playlist.author.orEmpty(),
                    embeddedUrl = playlist.artworkUrl ?: playlist.tracks.firstOrNull()?.artworkUrl,
                    fallbackIcon = if (playlist.id == "yt_liked") Icons.Filled.Favorite else Icons.Filled.MusicNote,
                    modifier = Modifier
                        .size(196.dp)
                        .clip(RoundedCornerShape(26.dp)),
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = playlist.title.ifBlank { "Playlist" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        playlist.author?.takeIf(String::isNotBlank),
                        "${playlist.tracks.size} tracks",
                    ).joinToString(" \u2022 "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { onPlay(0) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.home_play))
                    }
                    FilledTonalButton(
                        onClick = onShuffle,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Shuffle, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.feed_shuffle))
                    }
                }
                Spacer(Modifier.height(18.dp))
                FilledTonalButton(
                    onClick = onSave,
                    enabled = !isSaving && !savedToLibrary,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(when {
                        isSaving -> "Saving…"
                        savedToLibrary -> "Saved to library"
                        else -> "Save to library"
                    })
                }
                saveError?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "TRACKS",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        itemsIndexed(
            items = playlist.tracks,
            key = { index, track -> "${track.videoId}:$index" },
        ) { index, track ->
            ExpressiveGroupTrackRow(
                title = track.title,
                subtitle = track.artistAndAlbum(),
                position = groupPositionFor(index, playlist.tracks.size),
                onClick = { onPlay(index) },
                onLongClick = { miniTrayIndex = index },
                modifier = Modifier.padding(horizontal = 16.dp),
                leading = {
                    ArtworkImage(
                        name = track.title,
                        artist = track.artist,
                        embeddedUrl = track.artworkUrl,
                        fallbackIcon = Icons.Filled.MusicNote,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                },
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        track.durationSeconds?.let { duration ->
                            Text(
                                text = duration.toDurationLabel(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { menuTrack = track },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.fpd_track_options_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
            )
        }
    }

    menuTrack?.let { track ->
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
            playbackSourceLabel = playlist.title.ifBlank { "Mix" },
            onDismiss = { menuTrack = null },
        )
    }

    miniTrayIndex?.let { trayIndex ->
        playlist.tracks.getOrNull(trayIndex)?.let { track ->
            TrackMiniTraySheet(
                data = TrackMiniTrayData(
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    artworkUrl = track.artworkUrl,
                    videoId = track.videoId.takeIf(String::isNotBlank),
                    sourceLabel = playlist.title.ifBlank { "Mix" },
                    onPlay = { onPlay(trayIndex) },
                ),
                onDismiss = { miniTrayIndex = null },
            )
        }
    }
}

private fun YouTubeMusicTrack.artistAndAlbum(): String =
    listOfNotNull(artist.takeIf(String::isNotBlank), album?.takeIf(String::isNotBlank))
        .joinToString(" \u2022 ")
        .ifBlank { "YouTube Music" }

private fun Int.toDurationLabel(): String = "%d:%02d".format(this / 60, this % 60)
