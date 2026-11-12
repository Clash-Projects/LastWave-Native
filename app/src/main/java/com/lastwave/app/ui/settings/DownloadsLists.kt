@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lastwave.app.ui.settings

import com.lastwave.app.ui.common.PredictiveBackScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.download.DownloadProgress
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.GroupPosition
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.groupPositionFor
import com.lastwave.app.ui.common.groupShape
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance
import com.lastwave.app.ui.player.LocalMusicPlayer
import com.lastwave.app.ui.player.PlayingWaveBars
import com.lastwave.app.ui.shell.FloatingNavDefaults
import com.lastwave.app.ui.theme.ArtworkShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@Composable
internal fun DownloadedSongsList(
    tracks: List<DownloadedTrackEntity>,
    activeDownloads: List<DownloadProgress>,
    playbackState: com.lastwave.app.playback.PlaybackChromeState,
    totalSizeText: String,
    downloadFolder: String,
    downloadLocationLabel: String? = null,
    downloadStructure: com.lastwave.app.data.local.DownloadFolderStructure,
    onPlayTrack: (DownloadedTrackEntity) -> Unit,
    onPlayNext: (DownloadedTrackEntity) -> Unit,
    onAddToQueue: (DownloadedTrackEntity) -> Unit,
    onPlayAll: (Boolean) -> Unit,
    onSelectArtist: (String) -> Unit,
    onSelectAlbum: (String, String) -> Unit,
    onDeleteTrack: (DownloadedTrackEntity) -> Unit,
    onCancelDownload: (String) -> Unit,
    onOpenFileManager: () -> Unit,
    onChangeFolder: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    LazyColumn(
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = 6.dp,
            bottom = FloatingNavDefaults.contentBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Active ongoing downloads
        if (activeDownloads.isNotEmpty()) {
            item {
                Text(
                    "Downloading Now (${activeDownloads.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }

            items(activeDownloads, key = { it.key }) { download ->
                ActiveDownloadCard(
                    download = download,
                    onCancel = { onCancelDownload(download.key) },
                )
            }
        }

        // Action header (Play All / Shuffle)
        if (tracks.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Songs (${tracks.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onPlayAll(true)
                            },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(Icons.Filled.Shuffle, contentDescription = stringResource(com.lastwave.app.R.string.dl_shuffle_cd), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(com.lastwave.app.R.string.dl_shuffle_cd), style = MaterialTheme.typography.labelMedium)
                        }
                        FilledTonalButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onPlayAll(false)
                            },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(com.lastwave.app.R.string.dl_play_all_cd), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(com.lastwave.app.R.string.dl_play_cd), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            itemsIndexed(
                items = tracks,
                key = { _, item -> item.id },
            ) { index, track ->
                val isPlayingThis = playbackState.isPlaying &&
                    playbackState.current?.title.equals(track.title, ignoreCase = true) &&
                    playbackState.current?.artist.equals(track.artist, ignoreCase = true)

                Box(Modifier.animateItem()) {
                    DownloadedTrackCard(
                        track = track,
                        isPlaying = isPlayingThis,
                        position = groupPositionFor(index, tracks.size),
                        onPlay = {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onPlayTrack(track)
                        },
                        onPlayNext = { onPlayNext(track) },
                        onAddToQueue = { onAddToQueue(track) },
                        onSelectArtist = { onSelectArtist(track.artist) },
                        onSelectAlbum = { if (track.album.isNotBlank()) onSelectAlbum(track.album, track.artist) },
                        onDelete = {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onDeleteTrack(track)
                        },
                    )
                }
            }
        }

        if (tracks.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(36.dp),
                        )
                        Text(
                            "No matching downloaded songs",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DownloadedArtistsList(
    artists: List<DownloadedArtist>,
    onSelectArtist: (DownloadedArtist) -> Unit,
    onPlayArtist: (DownloadedArtist) -> Unit,
    onShuffleAll: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    LazyColumn(
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = 6.dp,
            bottom = FloatingNavDefaults.contentBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (artists.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Artists (${artists.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onShuffleAll()
                        },
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Icon(Icons.Filled.Shuffle, contentDescription = stringResource(com.lastwave.app.R.string.dl_shuffle_all_cd), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(com.lastwave.app.R.string.dl_shuffle_all), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            itemsIndexed(
                items = artists,
                key = { _, item -> item.name },
            ) { index, artist ->
                Card(
                    shape = groupShape(groupPositionFor(index, artists.size)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectArtist(artist) },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(50.dp)) {
                            ArtworkImage(
                                name = artist.name,
                                artist = artist.name,
                                embeddedUrl = artist.artworkUrl,
                                fallbackIcon = Icons.Filled.Person,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape),
                            )
                        }

                        Spacer(Modifier.width(14.dp))

                        Column(Modifier.weight(1f)) {
                            Text(
                                text = artist.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            val albumText = if (artist.albumCount > 0) "${artist.albumCount} album(s) \u2022 " else ""
                            Text(
                                text = "$albumText${artist.tracks.size} song(s) \u2022 ${formatBytes(artist.totalSizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onPlayArtist(artist)
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(com.lastwave.app.R.string.dl_play_artist_cd),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (artists.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(36.dp),
                        )
                        Text(
                            "No downloaded artists found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DownloadedAlbumsList(
    albums: List<DownloadedAlbum>,
    onSelectAlbum: (DownloadedAlbum) -> Unit,
    onPlayAlbum: (DownloadedAlbum) -> Unit,
    onShuffleAll: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    LazyColumn(
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = 6.dp,
            bottom = FloatingNavDefaults.contentBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (albums.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(com.lastwave.app.R.string.dl_albums_count, albums.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onShuffleAll()
                        },
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Icon(Icons.Filled.Shuffle, contentDescription = stringResource(com.lastwave.app.R.string.dl_shuffle_all_cd), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(com.lastwave.app.R.string.dl_shuffle_all), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            itemsIndexed(
                items = albums,
                key = { _, item -> "${item.title}_${item.artist}" },
            ) { index, album ->
                Card(
                    shape = groupShape(groupPositionFor(index, albums.size)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectAlbum(album) },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(54.dp)) {
                            ArtworkImage(
                                name = album.title,
                                artist = album.artist,
                                embeddedUrl = album.artworkUrl,
                                fallbackIcon = Icons.Filled.Album,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                        }

                        Spacer(Modifier.width(14.dp))

                        Column(Modifier.weight(1f)) {
                            Text(
                                text = album.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "${album.artist} \u2022 ${album.tracks.size} track(s) \u2022 ${formatBytes(album.totalSizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onPlayAlbum(album)
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(com.lastwave.app.R.string.dl_play_album_cd),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (albums.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.Album,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(36.dp),
                        )
                        Text(
                            "No downloaded albums found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ArtistDetailView(
    artistName: String,
    tracks: List<DownloadedTrackEntity>,
    albums: List<DownloadedAlbum>,
    playbackState: com.lastwave.app.playback.PlaybackChromeState,
    onPlayTrack: (DownloadedTrackEntity) -> Unit,
    onPlayNext: (DownloadedTrackEntity) -> Unit,
    onAddToQueue: (DownloadedTrackEntity) -> Unit,
    onPlayAll: (Boolean) -> Unit,
    onSelectArtist: (String) -> Unit = {},
    onSelectAlbum: (String) -> Unit,
    onDeleteTrack: (DownloadedTrackEntity) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val totalSize = tracks.sumOf { it.fileSizeBytes }
    val latestArtwork = tracks.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl

    LazyColumn(
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = 8.dp,
            bottom = FloatingNavDefaults.contentBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Artist Hero Card
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(100.dp)) {
                        ArtworkImage(
                            name = artistName,
                            artist = artistName,
                            embeddedUrl = latestArtwork,
                            fallbackIcon = Icons.Filled.Person,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .shadow(8.dp, CircleShape),
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = artistName,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = "${tracks.size} downloaded song(s) \u2022 ${albums.size} album(s) \u2022 ${formatBytes(totalSize)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onPlayAll(true)
                            },
                            shape = CircleShape,
                        ) {
                            Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(com.lastwave.app.R.string.feed_shuffle))
                        }
                        FilledTonalButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onPlayAll(false)
                            },
                            shape = CircleShape,
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(com.lastwave.app.R.string.dl_play_all_btn))
                        }
                    }
                }
            }
        }

        // Albums by this artist
        if (albums.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(com.lastwave.app.R.string.dl_albums_count, albums.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }

            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    itemsIndexed(albums, key = { index, album -> "${album.artist}_${album.title}_$index" }) { _, album ->
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier
                                .width(130.dp)
                                .clickable { onSelectAlbum(album.title) },
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(114.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                ) {
                                    ArtworkImage(
                                        name = album.title,
                                        artist = album.artist,
                                        embeddedUrl = album.artworkUrl,
                                        fallbackIcon = Icons.Filled.Album,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = album.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${album.tracks.size} tracks",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Songs by this artist
        item {
            Text(
                text = "Songs (${tracks.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }

        itemsIndexed(
            items = tracks,
            key = { _, item -> item.id },
        ) { index, track ->
            val isPlayingThis = playbackState.isPlaying &&
                playbackState.current?.title.equals(track.title, ignoreCase = true) &&
                playbackState.current?.artist.equals(track.artist, ignoreCase = true)

            DownloadedTrackCard(
                track = track,
                isPlaying = isPlayingThis,
                position = groupPositionFor(index, tracks.size),
                onPlay = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onPlayTrack(track)
                },
                onPlayNext = { onPlayNext(track) },
                onAddToQueue = { onAddToQueue(track) },
                onSelectArtist = { onSelectArtist(track.artist) },
                onSelectAlbum = { if (track.album.isNotBlank()) onSelectAlbum(track.album) },
                onDelete = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onDeleteTrack(track)
                },
            )
        }
    }
}

@Composable
internal fun AlbumDetailView(
    albumTitle: String,
    artistName: String,
    tracks: List<DownloadedTrackEntity>,
    playbackState: com.lastwave.app.playback.PlaybackChromeState,
    onPlayTrack: (DownloadedTrackEntity) -> Unit,
    onPlayNext: (DownloadedTrackEntity) -> Unit,
    onAddToQueue: (DownloadedTrackEntity) -> Unit,
    onPlayAll: (Boolean) -> Unit,
    onSelectArtist: (String) -> Unit,
    onSelectAlbum: (String, String) -> Unit = { _, _ -> },
    onDeleteTrack: (DownloadedTrackEntity) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val totalSize = tracks.sumOf { it.fileSizeBytes }
    val totalDuration = tracks.sumOf { it.durationMs }
    val latestArtwork = tracks.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl

    LazyColumn(
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = 8.dp,
            bottom = FloatingNavDefaults.contentBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Album Hero Card
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(150.dp)) {
                        ArtworkImage(
                            name = albumTitle,
                            artist = artistName,
                            embeddedUrl = latestArtwork,
                            fallbackIcon = Icons.Filled.Album,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(18.dp))
                                .shadow(10.dp, RoundedCornerShape(18.dp)),
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = albumTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = "By $artistName",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onSelectArtist(artistName) },
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = "${tracks.size} tracks \u2022 ${formatDuration(totalDuration)} \u2022 ${formatBytes(totalSize)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onPlayAll(true)
                            },
                            shape = CircleShape,
                        ) {
                            Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(com.lastwave.app.R.string.feed_shuffle))
                        }
                        FilledTonalButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onPlayAll(false)
                            },
                            shape = CircleShape,
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(com.lastwave.app.R.string.dl_play_album_btn))
                        }
                    }
                }
            }
        }

        // Track List
        item {
            Text(
                text = "Tracks (${tracks.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }

        itemsIndexed(
            items = tracks,
            key = { _, item -> item.id },
        ) { index, track ->
            val isPlayingThis = playbackState.isPlaying &&
                playbackState.current?.title.equals(track.title, ignoreCase = true) &&
                playbackState.current?.artist.equals(track.artist, ignoreCase = true)

            DownloadedTrackCard(
                track = track,
                trackNumber = index + 1,
                isPlaying = isPlayingThis,
                position = groupPositionFor(index, tracks.size),
                onPlay = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onPlayTrack(track)
                },
                onPlayNext = { onPlayNext(track) },
                onAddToQueue = { onAddToQueue(track) },
                onSelectArtist = { onSelectArtist(track.artist) },
                onSelectAlbum = { if (track.album.isNotBlank()) onSelectAlbum(track.album, track.artist) },
                onDelete = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onDeleteTrack(track)
                },
            )
        }
    }
}
