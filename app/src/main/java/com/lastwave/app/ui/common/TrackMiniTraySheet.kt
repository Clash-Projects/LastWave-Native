package com.lastwave.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.player.LocalAddToPlaylist
import com.lastwave.app.ui.player.LocalMusicPlayer
import com.lastwave.app.util.ArtistHelper

/**
 * Mini tray opened on long-press (deep press) of a track row on
 * Home / Feed, Search (TRACKS tab) and single track-list screens.
 *
 * Deliberately small: exactly the requested actions —
 * Play, Play next, Add to queue, Download, Add to playlist, Go to album, Go to artist.
 * Anything richer (mix, timer, cast, Last.fm, details…) stays in the full
 * [TrackContextMenuSheet] behind the overflow (3-dot) button.
 *
 * [onPlay] overrides the default single-track play so list screens can keep
 * their queue context (e.g. Home plays the whole visible queue from this
 * row, Feed plays its shelf from this index). When null, the tray just
 * plays [track] directly.
 */
data class TrackMiniTrayData(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val videoId: String? = null,
    val sourceLabel: String = "LastWave",
    val onPlay: (() -> Unit)? = null,
) {
    fun toPlayable(): PlayableTrack = PlayableTrack(
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        videoId = videoId?.takeIf(String::isNotBlank),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMiniTraySheet(
    data: TrackMiniTrayData,
    onDismiss: () -> Unit,
    downloadViewModel: DownloadMenuViewModel = hiltViewModel(),
    artistAlbumViewModel: ArtistAlbumMenuViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    val musicPlayer = LocalMusicPlayer.current
    val addToPlaylist = LocalAddToPlaylist.current
    val playable = remember(data) { data.toPlayable() }
    val activeDownloads by downloadViewModel.activeDownloads.collectAsStateWithLifecycle()
    val downloadQuality by downloadViewModel.downloadQuality.collectAsStateWithLifecycle()
    val downloadLabel = remember(downloadQuality) { downloadLabelForQuality(downloadQuality) }
    var isDownloaded by remember(data) { mutableStateOf(false) }

    LaunchedEffect(data) {
        isDownloaded = runCatching {
            downloadViewModel.checkStatus(data.title, data.artist)
        }.getOrDefault(TrackDownloadStatus.NOT_DOWNLOADED) == TrackDownloadStatus.DOWNLOADED
    }

    fun play() {
        if (data.onPlay != null) data.onPlay.invoke()
        else musicPlayer.play(playable, sourceLabel = data.sourceLabel)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        dragHandle = {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp),
            ) {}
        },
    ) {
        EdgeToEdgeDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .adaptiveContentWidth(maxWidth = 600.dp)
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 14.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp + safeDrawingBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header: artwork + title/artist so the tray reads as "for this track".
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(
                    name = data.title,
                    artist = data.artist,
                    embeddedUrl = data.artworkUrl,
                    fallbackIcon = Icons.Filled.MusicNote,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        data.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sub = listOfNotNull(
                        ArtistHelper.primaryArtist(data.artist).takeIf(String::isNotBlank),
                        data.album?.takeIf(String::isNotBlank),
                    ).joinToString(" · ")
                    if (sub.isNotBlank()) {
                        Text(
                            sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            val splitArtists = remember(data.artist) { ArtistHelper.splitArtists(data.artist) }
            val downloadKey = remember(data) {
                com.lastwave.app.data.download.TrackDownloadManager.makeDownloadKey(data.title, data.artist)
            }
            val isDownloading = activeDownloads[downloadKey]?.let { !it.isFinished } == true

            val rows = buildList<@Composable (GroupPosition) -> Unit> {
                // 1. Play
                add { pos -> MiniTrayRow(Icons.Filled.PlayArrow, "Play", position = pos, onClick = ::play) }
                // 2. Play next — inserts right after current, current song keeps playing
                add { pos ->
                    MiniTrayRow(Icons.Filled.QueuePlayNext, "Play next", position = pos) {
                        musicPlayer.playNext(playable)
                        onDismiss()
                    }
                }
                // 3. Add to queue
                add { pos ->
                    MiniTrayRow(Icons.Filled.QueueMusic, "Add to queue", position = pos) {
                        musicPlayer.addToQueue(playable)
                        onDismiss()
                    }
                }
                // 4. Download (status-aware label, same tiers as the full sheet)
                add { pos ->
                    when {
                        isDownloaded -> {
                            MiniTrayRow(Icons.Filled.CheckCircle, "Downloaded", position = pos) {
                                android.widget.Toast.makeText(
                                    context,
                                    "Track is already downloaded",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                                onDismiss()
                            }
                        }
                        isDownloading -> {
                            MiniTrayRow(Icons.Filled.Download, "Downloading…", position = pos) {
                                android.widget.Toast.makeText(
                                    context,
                                    "Download is in progress",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                                onDismiss()
                            }
                        }
                        else -> {
                            MiniTrayRow(Icons.Filled.Download, downloadLabel, position = pos) {
                                downloadViewModel.download(data.title, data.artist, data.album, data.artworkUrl)
                                onDismiss()
                            }
                        }
                    }
                }
                // 5. Add to playlist
                add { pos ->
                    MiniTrayRow(Icons.Filled.PlaylistAdd, "Add to playlist", position = pos) {
                        addToPlaylist(playable)
                        onDismiss()
                    }
                }
                // 6. Go to album (only when we know the album)
                if (!data.album.isNullOrBlank()) {
                    add { pos ->
                        val album = data.album
                        MiniTrayRow(Icons.Filled.Album, "Go to album", position = pos) {
                            artistAlbumViewModel.openAlbum(album, splitArtists.firstOrNull() ?: data.artist)
                            onDismiss()
                        }
                    }
                }
                // 7. Go to artist (one row per artist, like the full sheet)
                for (art in splitArtists) {
                    add { pos ->
                        MiniTrayRow(Icons.Filled.Person, "Go to artist ($art)", position = pos) {
                            artistAlbumViewModel.openArtist(art)
                            onDismiss()
                        }
                    }
                }
            }
            ExpressiveGroup(rowCount = rows.size) { index, position -> rows[index](position) }
        }
    }
}

@Composable
private fun MiniTrayRow(
    icon: ImageVector,
    label: String,
    position: GroupPosition = GroupPosition.SINGLE,
    onClick: () -> Unit,
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val scale = rememberGroupPressScale(interactionSource)
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = groupShape(position),
        interactionSource = interactionSource,
        modifier = Modifier.fillMaxWidth().scale(scale),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
