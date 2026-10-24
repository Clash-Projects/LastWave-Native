package com.lastwave.app.ui.search.khinsider

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.R
import com.lastwave.app.data.khinsider.KhAlbum
import com.lastwave.app.data.khinsider.KhFormat
import com.lastwave.app.data.khinsider.KhInsiderParser
import com.lastwave.app.data.khinsider.KhTrack
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveLoadingIndicator
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance
import com.lastwave.app.ui.player.LocalMusicPlayer

/**
 * Full-screen KHInsider album drawn over the Search screen (not a dialog
 * window, so the player, its playlist picker and the track menu stay on top):
 * cover, format choice, play / shuffle, add the whole album to a playlist,
 * download it, and the track list with a menu per track.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KhInsiderAlbumOverlay(
    albumId: String,
    fallbackTitle: String,
    fallbackArtwork: String?,
    onDismiss: () -> Unit,
    viewModel: KhInsiderAlbumViewModel = hiltViewModel(key = "khinsider_album"),
) {
    BackHandler(onBack = onDismiss)
    LaunchedEffect(albumId) { viewModel.open(albumId) }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val format by viewModel.format.collectAsStateWithLifecycle()
    val saved by viewModel.downloadedPages.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val chrome by LocalMusicPlayer.current.chromeState.collectAsStateWithLifecycle()
    val playingUrl = chrome.current?.playbackUrl?.takeIf { KhInsiderParser.isPlaybackUri(it) }
    var menuTrack by remember { mutableStateOf<KhTrack?>(null) }
    var showPlaylistPicker by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.khinsider_back))
                }
                Text(
                    state.album?.name ?: fallbackTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            val album = state.album
            when {
                album != null -> AlbumContent(
                    album = album,
                    fallbackArtwork = fallbackArtwork,
                    format = format,
                    playingUrl = playingUrl,
                    downloadState = { index, track ->
                        when {
                            track.pagePath in saved -> KhTrackDownload.Saved
                            else -> downloads[viewModel.downloadKey(track)]?.let { p ->
                                when {
                                    p.error != null -> KhTrackDownload.Failed(p.error)
                                    p.isFinished -> KhTrackDownload.Saved
                                    else -> KhTrackDownload.Running(p.progressPercent)
                                }
                            } ?: KhTrackDownload.None
                        }
                    },
                    onFormat = viewModel::setFormat,
                    onPlay = { index, shuffled -> viewModel.play(index, shuffled) },
                    onMenu = { menuTrack = it },
                    onAddToPlaylist = { showPlaylistPicker = true },
                    onDownloadAlbum = viewModel::downloadAlbum,
                    onDownloadTrack = viewModel::downloadTrack,
                )
                state.error != null -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.khinsider_error, state.error.orEmpty()),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { viewModel.open(albumId, force = true) }) {
                        Text(stringResource(R.string.khinsider_retry))
                    }
                }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ExpressiveLoadingIndicator()
                }
            }
        }
    }

    menuTrack?.let { track ->
        val album = state.album
        val playable = viewModel.playable(track)
        if (album != null && playable != null) {
            TrackContextMenuSheet(
                target = TrackMenuTarget.Track(track.name, album.name, KhInsiderParser.trackPageUrl(track.pagePath)),
                capabilities = TrackMenuCapabilities(showCopyActions = true, showDeleteScrobble = false),
                playableTrack = playable,
                playbackSourceLabel = KhInsiderAlbumViewModel.SOURCE_LABEL,
                onPlayInLastWave = { viewModel.play(album.tracks.indexOf(track).coerceAtLeast(0)) },
                onDismiss = { menuTrack = null },
            )
        }
    }

    if (showPlaylistPicker) {
        val playlists by viewModel.playlists.collectAsStateWithLifecycle()
        KhPlaylistPickerSheet(
            playlists = playlists,
            onPick = { viewModel.addAlbumToPlaylist(it); showPlaylistPicker = false },
            onCreate = { viewModel.createPlaylistWithAlbum(it); showPlaylistPicker = false },
            onDismiss = { showPlaylistPicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AlbumContent(
    album: KhAlbum,
    fallbackArtwork: String?,
    format: KhFormat,
    playingUrl: String?,
    downloadState: (Int, KhTrack) -> KhTrackDownload,
    onFormat: (KhFormat) -> Unit,
    onPlay: (index: Int, shuffled: Boolean) -> Unit,
    onMenu: (KhTrack) -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownloadAlbum: () -> Unit,
    onDownloadTrack: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 32.dp + LocalMiniPlayerScrollClearance.current),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArtworkImage(
                        name = album.name,
                        artist = album.name,
                        embeddedUrl = album.coverUrl ?: fallbackArtwork,
                        fallbackIcon = Icons.Filled.SportsEsports,
                        modifier = Modifier.size(120.dp).clip(RoundedCornerShape(16.dp)),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            album.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val meta = listOfNotNull(
                            album.platforms.takeIf { it.isNotEmpty() }?.joinToString(", "),
                            album.year,
                            album.type,
                        ).joinToString(" · ")
                        if (meta.isNotBlank()) {
                            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        (album.developer ?: album.publisher)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            stringResource(R.string.khinsider_track_count, album.tracks.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.khinsider_format_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    KhFormat.entries.forEachIndexed { i, option ->
                        SegmentedButton(
                            selected = format == option,
                            onClick = { onFormat(option) },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = KhFormat.entries.size),
                        ) { Text(option.name) }
                    }
                }
                if (format == KhFormat.FLAC && !album.hasFlac) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.khinsider_no_flac),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onPlay(0, false) }, enabled = album.tracks.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.khinsider_play))
                    }
                    FilledTonalButton(onClick = { onPlay(0, true) }, enabled = album.tracks.size > 1, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Shuffle, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.khinsider_shuffle))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onAddToPlaylist, enabled = album.tracks.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.khinsider_add_album), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = onDownloadAlbum, enabled = album.tracks.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.khinsider_download_album), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        val multiDisc = album.tracks.mapNotNull { it.disc }.distinct().size > 1
        itemsIndexed(album.tracks, key = { _, t -> t.pagePath }) { index, track ->
            val playing = playingUrl == KhInsiderParser.playbackUri(track.pagePath)
            val accent = playing
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = { onPlay(index, false) }, onLongClick = { onMenu(track) })
                    .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildString {
                        if (multiDisc && track.disc != null) append("${track.disc}-")
                        append(track.number ?: (index + 1))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(40.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        track.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (accent) FontWeight.Bold else FontWeight.Normal,
                        color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    track.durationSeconds?.let { secs ->
                        Text(
                            "%d:%02d".format(secs / 60, secs % 60),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                when (val dl = downloadState(index, track)) {
                    KhTrackDownload.Saved -> Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = stringResource(R.string.khinsider_saved),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp).size(20.dp),
                    )
                    is KhTrackDownload.Running -> CircularProgressIndicator(
                        progress = { dl.percent / 100f },
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(horizontal = 12.dp).size(20.dp),
                    )
                    is KhTrackDownload.Failed -> IconButton(onClick = { onDownloadTrack(index) }) {
                        Icon(Icons.Filled.ErrorOutline, contentDescription = dl.message, tint = MaterialTheme.colorScheme.error)
                    }
                    KhTrackDownload.None -> IconButton(onClick = { onDownloadTrack(index) }) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = stringResource(R.string.khinsider_download_track),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                com.lastwave.app.ui.common.OverflowMenuButton(onClick = { onMenu(track) })
            }
        }
        item(key = "attribution") {
            Text(
                stringResource(R.string.khinsider_attribution),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
