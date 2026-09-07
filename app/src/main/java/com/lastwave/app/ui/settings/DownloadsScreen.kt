@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lastwave.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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

private fun formatDate(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes.toDouble() / (1024 * 1024)
    return if (mb >= 1000) "%.1f GB".format(mb / 1024) else "%.1f MB".format(mb)
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

enum class DownloadTab(val label: String) {
    SONGS("Songs"),
    ARTISTS("Artists"),
    ALBUMS("Albums"),
}

enum class SongSortOption(val label: String) {
    RECENT("Recently Added"),
    OLDEST("Oldest First"),
    TITLE_AZ("Title (A-Z)"),
    ARTIST_AZ("Artist (A-Z)"),
    SIZE_DESC("File Size"),
}

enum class ArtistSortOption(val label: String) {
    NAME_AZ("Name (A-Z)"),
    MOST_SONGS("Most Songs"),
    STORAGE_SIZE("Storage Size"),
}

enum class AlbumSortOption(val label: String) {
    TITLE_AZ("Title (A-Z)"),
    ARTIST_AZ("Artist (A-Z)"),
    MOST_TRACKS("Most Tracks"),
    RECENT("Recently Added"),
}

sealed interface DownloadSubView {
    data object Root : DownloadSubView
    data class ArtistDetail(val artistName: String) : DownloadSubView
    data class AlbumDetail(val albumTitle: String, val artistName: String) : DownloadSubView
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: (() -> Unit)? = null,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val tracks by viewModel.downloadedTracks.collectAsStateWithLifecycle()
    val artists by viewModel.downloadedArtists.collectAsStateWithLifecycle()
    val albums by viewModel.downloadedAlbums.collectAsStateWithLifecycle()
    val totalBytes by viewModel.totalBytes.collectAsStateWithLifecycle()
    val activeDownloadsMap by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val downloadLyrics by viewModel.downloadLyrics.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val activeDownloads = activeDownloadsMap.values.filter { !it.isFinished && it.error == null }

    val haptic = LocalHapticFeedback.current
    val musicPlayer = LocalMusicPlayer.current
    val playbackState by musicPlayer.chromeState.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableStateOf(DownloadTab.SONGS) }
    var subViewStack by remember { mutableStateOf(listOf<DownloadSubView>(DownloadSubView.Root)) }
    val currentSubView = subViewStack.last()

    var searchQuery by remember { mutableStateOf("") }
    var songSort by remember { mutableStateOf(SongSortOption.RECENT) }
    var artistSort by remember { mutableStateOf(ArtistSortOption.NAME_AZ) }
    var albumSort by remember { mutableStateOf(AlbumSortOption.TITLE_AZ) }
    var filterLosslessOnly by remember { mutableStateOf(false) }
    var filterLyricsOnly by remember { mutableStateOf(false) }

    var showClearAllConfirm by remember { mutableStateOf(false) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    var trackToDelete by remember { mutableStateOf<DownloadedTrackEntity?>(null) }
    var showOptionsMenu by remember { mutableStateOf(false) }

    fun navigateTo(subView: DownloadSubView) {
        subViewStack = subViewStack + subView
    }

    fun popSubView(): Boolean {
        return if (subViewStack.size > 1) {
            subViewStack = subViewStack.dropLast(1)
            true
        } else {
            false
        }
    }

    // Intercept hardware back button when inside subviews
    BackHandler(enabled = subViewStack.size > 1) {
        popSubView()
    }

    val totalSizeText = formatBytes(totalBytes ?: 0L)

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 860.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            // Header configuration depending on current view depth
            val headerBack: (() -> Unit)? = when {
                subViewStack.size > 1 -> { { popSubView() } }
                onBack != null -> onBack
                else -> null
            }

            val headerTitle = when (currentSubView) {
                is DownloadSubView.Root -> "Downloads"
                is DownloadSubView.ArtistDetail -> currentSubView.artistName
                is DownloadSubView.AlbumDetail -> currentSubView.albumTitle
            }

            val headerSubtitle = when (currentSubView) {
                is DownloadSubView.Root -> "${tracks.size} songs \u2022 ${artists.size} artists \u2022 $totalSizeText"
                is DownloadSubView.ArtistDetail -> {
                    val aTracks = tracks.filter {
                        it.artist.equals(currentSubView.artistName, ignoreCase = true) ||
                            it.artist.contains(currentSubView.artistName, ignoreCase = true)
                    }
                    val aAlbums = aTracks.map { it.album.trim() }.filter { it.isNotBlank() && !it.equals("Singles", ignoreCase = true) }.distinct()
                    "${aTracks.size} songs \u2022 ${aAlbums.size} albums \u2022 ${formatBytes(aTracks.sumOf { it.fileSizeBytes })}"
                }
                is DownloadSubView.AlbumDetail -> {
                    val alTracks = tracks.filter {
                        it.album.trim().equals(currentSubView.albumTitle.trim(), ignoreCase = true) &&
                            (currentSubView.artistName.isBlank() ||
                                it.artist.equals(currentSubView.artistName, ignoreCase = true) ||
                                it.artist.contains(currentSubView.artistName, ignoreCase = true) ||
                                currentSubView.artistName.contains(it.artist, ignoreCase = true))
                    }
                    "${currentSubView.artistName} \u2022 ${alTracks.size} tracks \u2022 ${formatBytes(alTracks.sumOf { it.fileSizeBytes })}"
                }
            }

            ExpressiveHeader(
                title = headerTitle,
                subtitle = headerSubtitle,
                onBack = headerBack,
                actions = {
                    Box {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showOptionsMenu = true
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Options")
                        }
                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("Download Lyrics")
                                        Switch(
                                            checked = downloadLyrics,
                                            onCheckedChange = { viewModel.setDownloadLyrics(it) },
                                        )
                                    }
                                },
                                leadingIcon = { Icon(Icons.Filled.FormatQuote, contentDescription = null) },
                                onClick = { viewModel.setDownloadLyrics(!downloadLyrics) },
                            )
                            DropdownMenuItem(
                                text = { Text("Open in File Manager") },
                                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                                onClick = {
                                    showOptionsMenu = false
                                    viewModel.openInFileManager()
                                },
                            )
                            if (tracks.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Clear Download History Only") },
                                    leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
                                    onClick = {
                                        showOptionsMenu = false
                                        showClearHistoryConfirm = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete All Files & Free Space", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showOptionsMenu = false
                                        showClearAllConfirm = true
                                    },
                                )
                            }
                        }
                    }
                },
            )

            // Offline Mode Banner
            if (!isOnline) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CloudOff,
                            contentDescription = "Offline Mode",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Offline Mode \u2022 Playing downloaded local tracks",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            // Route content depending on subViewStack depth
            when (currentSubView) {
                is DownloadSubView.Root -> {
                    if (tracks.isEmpty() && activeDownloads.isEmpty()) {
                        EmptyDownloadsState()
                    } else {
                        // Segmented tabs: Songs | Artists | Albums
                        DownloadTabBar(
                            selectedTab = selectedTab,
                            onTabSelected = { selectedTab = it },
                            songCount = tracks.size,
                            artistCount = artists.size,
                            albumCount = albums.size,
                        )

                        // Search and filter controls
                        DownloadFilterControls(
                            searchQuery = searchQuery,
                            onSearchQueryChange = { searchQuery = it },
                            selectedTab = selectedTab,
                            songSort = songSort,
                            onSongSortChange = { songSort = it },
                            artistSort = artistSort,
                            onArtistSortChange = { artistSort = it },
                            albumSort = albumSort,
                            onAlbumSortChange = { albumSort = it },
                            filterLossless = filterLosslessOnly,
                            onToggleLossless = { filterLosslessOnly = !filterLosslessOnly },
                            filterLyrics = filterLyricsOnly,
                            onToggleLyrics = { filterLyricsOnly = !filterLyricsOnly },
                        )

                        when (selectedTab) {
                            DownloadTab.SONGS -> {
                                val filteredTracks = remember(tracks, searchQuery, songSort, filterLosslessOnly, filterLyricsOnly) {
                                    tracks
                                        .filter {
                                            if (searchQuery.isBlank()) true
                                            else it.title.contains(searchQuery, ignoreCase = true) ||
                                                it.artist.contains(searchQuery, ignoreCase = true) ||
                                                it.album.contains(searchQuery, ignoreCase = true)
                                        }
                                        .filter { if (filterLosslessOnly) it.isLossless else true }
                                        .filter { if (filterLyricsOnly) it.hasLyrics else true }
                                        .let { list ->
                                            when (songSort) {
                                                SongSortOption.RECENT -> list.sortedByDescending { it.downloadedAtMillis }
                                                SongSortOption.OLDEST -> list.sortedBy { it.downloadedAtMillis }
                                                SongSortOption.TITLE_AZ -> list.sortedBy { it.title.lowercase() }
                                                SongSortOption.ARTIST_AZ -> list.sortedBy { it.artist.lowercase() }
                                                SongSortOption.SIZE_DESC -> list.sortedByDescending { it.fileSizeBytes }
                                            }
                                        }
                                }

                                DownloadedSongsList(
                                    tracks = filteredTracks,
                                    activeDownloads = activeDownloads,
                                    playbackState = playbackState,
                                    totalSizeText = totalSizeText,
                                    onPlayTrack = { track -> viewModel.playTrack(track, filteredTracks) },
                                    onPlayNext = { viewModel.playNext(it) },
                                    onAddToQueue = { viewModel.addToQueue(it) },
                                    onPlayAll = { shuffled -> viewModel.playTracks(filteredTracks, startShuffled = shuffled) },
                                    onSelectArtist = { navigateTo(DownloadSubView.ArtistDetail(it)) },
                                    onSelectAlbum = { alb, art -> navigateTo(DownloadSubView.AlbumDetail(alb, art)) },
                                    onDeleteTrack = { trackToDelete = it },
                                    onCancelDownload = { viewModel.cancelDownload(it) },
                                    onOpenFileManager = { viewModel.openInFileManager() },
                                )
                            }
                            DownloadTab.ARTISTS -> {
                                val filteredArtists = remember(artists, searchQuery, artistSort) {
                                    artists
                                        .filter {
                                            if (searchQuery.isBlank()) true
                                            else it.name.contains(searchQuery, ignoreCase = true)
                                        }
                                        .let { list ->
                                            when (artistSort) {
                                                ArtistSortOption.NAME_AZ -> list.sortedBy { it.name.lowercase() }
                                                ArtistSortOption.MOST_SONGS -> list.sortedByDescending { it.tracks.size }
                                                ArtistSortOption.STORAGE_SIZE -> list.sortedByDescending { it.totalSizeBytes }
                                            }
                                        }
                                }

                                DownloadedArtistsList(
                                    artists = filteredArtists,
                                    onSelectArtist = { navigateTo(DownloadSubView.ArtistDetail(it.name)) },
                                    onPlayArtist = { artist -> viewModel.playTracks(artist.tracks) },
                                    onShuffleAll = { viewModel.playAll(startShuffled = true) },
                                )
                            }
                            DownloadTab.ALBUMS -> {
                                val filteredAlbums = remember(albums, searchQuery, albumSort) {
                                    albums
                                        .filter {
                                            if (searchQuery.isBlank()) true
                                            else it.title.contains(searchQuery, ignoreCase = true) ||
                                                it.artist.contains(searchQuery, ignoreCase = true)
                                        }
                                        .let { list ->
                                            when (albumSort) {
                                                AlbumSortOption.TITLE_AZ -> list.sortedBy { it.title.lowercase() }
                                                AlbumSortOption.ARTIST_AZ -> list.sortedBy { it.artist.lowercase() }
                                                AlbumSortOption.MOST_TRACKS -> list.sortedByDescending { it.tracks.size }
                                                AlbumSortOption.RECENT -> list.sortedByDescending { it.tracks.maxOfOrNull { t -> t.downloadedAtMillis } ?: 0L }
                                            }
                                        }
                                }

                                DownloadedAlbumsList(
                                    albums = filteredAlbums,
                                    onSelectAlbum = { navigateTo(DownloadSubView.AlbumDetail(it.title, it.artist)) },
                                    onPlayAlbum = { album -> viewModel.playTracks(album.tracks) },
                                    onShuffleAll = { viewModel.playAll(startShuffled = true) },
                                )
                            }
                        }
                    }
                }
                is DownloadSubView.ArtistDetail -> {
                    val artistTracks = remember(tracks, currentSubView.artistName) {
                        tracks.filter {
                            it.artist.equals(currentSubView.artistName, ignoreCase = true) ||
                                it.artist.contains(currentSubView.artistName, ignoreCase = true)
                        }.sortedByDescending { it.downloadedAtMillis }
                    }
                    val artistAlbums = remember(albums, currentSubView.artistName) {
                        albums.filter {
                            it.artist.equals(currentSubView.artistName, ignoreCase = true) ||
                                it.artist.contains(currentSubView.artistName, ignoreCase = true) ||
                                currentSubView.artistName.contains(it.artist, ignoreCase = true)
                        }
                    }

                    if (artistTracks.isEmpty() && tracks.isNotEmpty()) {
                        LaunchedEffect(Unit) {
                            popSubView()
                        }
                    }

                    ArtistDetailView(
                        artistName = currentSubView.artistName,
                        tracks = artistTracks,
                        albums = artistAlbums,
                        playbackState = playbackState,
                        onPlayTrack = { track -> viewModel.playTrack(track, artistTracks) },
                        onPlayNext = { viewModel.playNext(it) },
                        onAddToQueue = { viewModel.addToQueue(it) },
                        onPlayAll = { shuffled -> viewModel.playTracks(artistTracks, startShuffled = shuffled) },
                        onSelectArtist = { navigateTo(DownloadSubView.ArtistDetail(it)) },
                        onSelectAlbum = { alb -> navigateTo(DownloadSubView.AlbumDetail(alb, currentSubView.artistName)) },
                        onDeleteTrack = { trackToDelete = it },
                    )
                }
                is DownloadSubView.AlbumDetail -> {
                    val albumTracks = remember(tracks, currentSubView.albumTitle, currentSubView.artistName) {
                        tracks.filter {
                            it.album.trim().equals(currentSubView.albumTitle.trim(), ignoreCase = true) &&
                                (currentSubView.artistName.isBlank() ||
                                    it.artist.equals(currentSubView.artistName, ignoreCase = true) ||
                                    it.artist.contains(currentSubView.artistName, ignoreCase = true) ||
                                    currentSubView.artistName.contains(it.artist, ignoreCase = true))
                        }
                    }

                    if (albumTracks.isEmpty() && tracks.isNotEmpty()) {
                        LaunchedEffect(Unit) {
                            popSubView()
                        }
                    }

                    AlbumDetailView(
                        albumTitle = currentSubView.albumTitle,
                        artistName = currentSubView.artistName,
                        tracks = albumTracks,
                        playbackState = playbackState,
                        onPlayTrack = { track -> viewModel.playTrack(track, albumTracks) },
                        onPlayNext = { viewModel.playNext(it) },
                        onAddToQueue = { viewModel.addToQueue(it) },
                        onPlayAll = { shuffled -> viewModel.playTracks(albumTracks, startShuffled = shuffled) },
                        onSelectArtist = { navigateTo(DownloadSubView.ArtistDetail(it)) },
                        onSelectAlbum = { alb, art -> navigateTo(DownloadSubView.AlbumDetail(alb, art)) },
                        onDeleteTrack = { trackToDelete = it },
                    )
                }
            }
        }
    }

    // Delete single track dialog
    trackToDelete?.let { track ->
        AlertDialog(
            onDismissRequest = { trackToDelete = null },
            title = { Text("Delete \"${track.title}\"?") },
            text = {
                Text(
                    "You can delete the local audio file to free storage space, or remove it from history only.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTrack(track)
                        trackToDelete = null
                    },
                ) {
                    Text("Delete File", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            viewModel.deleteHistoryRecordOnly(track)
                            trackToDelete = null
                        },
                    ) {
                        Text("History Only")
                    }
                    TextButton(onClick = { trackToDelete = null }) { Text("Cancel") }
                }
            },
        )
    }

    // Clear history only confirm
    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = { Text("Clear Download History?") },
            text = { Text("This will clear the history list in the app. Your audio files in Music/LastWave will stay safely on your device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistoryOnly()
                        showClearHistoryConfirm = false
                    },
                ) {
                    Text("Clear History")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) { Text("Cancel") }
            },
        )
    }

    // Clear all files confirm
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("Delete All Downloaded Files?") },
            text = { Text("This will permanently delete all ${tracks.size} downloaded audio files from Music/LastWave and free up $totalSizeText of storage.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAll()
                        showClearAllConfirm = false
                    },
                ) {
                    Text("Delete All Files", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DownloadTabBar(
    selectedTab: DownloadTab,
    onTabSelected: (DownloadTab) -> Unit,
    songCount: Int,
    artistCount: Int,
    albumCount: Int,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DownloadTab.entries.forEach { tab ->
                val isSelected = selectedTab == tab
                val count = when (tab) {
                    DownloadTab.SONGS -> songCount
                    DownloadTab.ARTISTS -> artistCount
                    DownloadTab.ALBUMS -> albumCount
                }
                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                    label = "tabBg",
                )
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "tabText",
                )

                Surface(
                    onClick = { onTabSelected(tab) },
                    shape = RoundedCornerShape(16.dp),
                    color = bgColor,
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "${tab.label} ($count)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = textColor,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadFilterControls(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedTab: DownloadTab,
    songSort: SongSortOption,
    onSongSortChange: (SongSortOption) -> Unit,
    artistSort: ArtistSortOption,
    onArtistSortChange: (ArtistSortOption) -> Unit,
    albumSort: AlbumSortOption,
    onAlbumSortChange: (AlbumSortOption) -> Unit,
    filterLossless: Boolean,
    onToggleLossless: () -> Unit,
    filterLyrics: Boolean,
    onToggleLyrics: () -> Unit,
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Search bar + Sort menu button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = {
                    Text(
                        when (selectedTab) {
                            DownloadTab.SONGS -> "Search songs, artists, albums..."
                            DownloadTab.ARTISTS -> "Search downloaded artists..."
                            DownloadTab.ALBUMS -> "Search downloaded albums..."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = "Search", modifier = Modifier.size(20.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
            )

            // Sort dropdown button
            Box {
                FilledTonalButton(
                    onClick = { sortMenuExpanded = true },
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.height(52.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Sort,
                        contentDescription = "Sort",
                        modifier = Modifier.size(18.dp),
                    )
                }

                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false },
                ) {
                    when (selectedTab) {
                        DownloadTab.SONGS -> {
                            SongSortOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = option.label,
                                            fontWeight = if (songSort == option) FontWeight.Bold else FontWeight.Normal,
                                            color = if (songSort == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        )
                                    },
                                    onClick = {
                                        onSongSortChange(option)
                                        sortMenuExpanded = false
                                    },
                                )
                            }
                        }
                        DownloadTab.ARTISTS -> {
                            ArtistSortOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = option.label,
                                            fontWeight = if (artistSort == option) FontWeight.Bold else FontWeight.Normal,
                                            color = if (artistSort == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        )
                                    },
                                    onClick = {
                                        onArtistSortChange(option)
                                        sortMenuExpanded = false
                                    },
                                )
                            }
                        }
                        DownloadTab.ALBUMS -> {
                            AlbumSortOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = option.label,
                                            fontWeight = if (albumSort == option) FontWeight.Bold else FontWeight.Normal,
                                            color = if (albumSort == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        )
                                    },
                                    onClick = {
                                        onAlbumSortChange(option)
                                        sortMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Filter chips for Songs tab
        if (selectedTab == DownloadTab.SONGS) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !filterLossless && !filterLyrics,
                    onClick = {
                        if (filterLossless) onToggleLossless()
                        if (filterLyrics) onToggleLyrics()
                    },
                    label = { Text("All") },
                    shape = RoundedCornerShape(12.dp),
                )
                FilterChip(
                    selected = filterLossless,
                    onClick = onToggleLossless,
                    label = { Text("Lossless / FLAC") },
                    shape = RoundedCornerShape(12.dp),
                )
                FilterChip(
                    selected = filterLyrics,
                    onClick = onToggleLyrics,
                    label = { Text("With Lyrics (LRC)") },
                    shape = RoundedCornerShape(12.dp),
                )
            }
        }
    }
}

@Composable
private fun DownloadedSongsList(
    tracks: List<DownloadedTrackEntity>,
    activeDownloads: List<DownloadProgress>,
    playbackState: com.lastwave.app.playback.PlaybackChromeState,
    totalSizeText: String,
    onPlayTrack: (DownloadedTrackEntity) -> Unit,
    onPlayNext: (DownloadedTrackEntity) -> Unit,
    onAddToQueue: (DownloadedTrackEntity) -> Unit,
    onPlayAll: (Boolean) -> Unit,
    onSelectArtist: (String) -> Unit,
    onSelectAlbum: (String, String) -> Unit,
    onDeleteTrack: (DownloadedTrackEntity) -> Unit,
    onCancelDownload: (String) -> Unit,
    onOpenFileManager: () -> Unit,
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
        // Storage summary card
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Storage: $totalSizeText",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Saved in Music/LastWave/ \u2022 ${tracks.size} song(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(
                        onClick = onOpenFileManager,
                        shape = CircleShape,
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Files", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

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
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPlayAll(true)
                            },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Shuffle", style = MaterialTheme.typography.labelMedium)
                        }
                        FilledTonalButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPlayAll(false)
                            },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Play all", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Play", style = MaterialTheme.typography.labelMedium)
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
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPlayTrack(track)
                        },
                        onPlayNext = { onPlayNext(track) },
                        onAddToQueue = { onAddToQueue(track) },
                        onSelectArtist = { onSelectArtist(track.artist) },
                        onSelectAlbum = { if (track.album.isNotBlank()) onSelectAlbum(track.album, track.artist) },
                        onDelete = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
private fun DownloadedArtistsList(
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
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onShuffleAll()
                        },
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle all", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Shuffle All", style = MaterialTheme.typography.labelMedium)
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
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPlayArtist(artist)
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = "Play artist",
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
private fun DownloadedAlbumsList(
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
                        "Albums (${albums.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onShuffleAll()
                        },
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle all", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Shuffle All", style = MaterialTheme.typography.labelMedium)
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
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPlayAlbum(album)
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = "Play album",
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
private fun ArtistDetailView(
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
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPlayAll(true)
                            },
                            shape = CircleShape,
                        ) {
                            Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Shuffle")
                        }
                        FilledTonalButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPlayAll(false)
                            },
                            shape = CircleShape,
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play All")
                        }
                    }
                }
            }
        }

        // Albums by this artist
        if (albums.isNotEmpty()) {
            item {
                Text(
                    text = "Albums (${albums.size})",
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
                    items(albums, key = { it.title }) { album ->
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
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPlayTrack(track)
                },
                onPlayNext = { onPlayNext(track) },
                onAddToQueue = { onAddToQueue(track) },
                onSelectArtist = { onSelectArtist(track.artist) },
                onSelectAlbum = { if (track.album.isNotBlank()) onSelectAlbum(track.album) },
                onDelete = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDeleteTrack(track)
                },
            )
        }
    }
}

@Composable
private fun AlbumDetailView(
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
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPlayAll(true)
                            },
                            shape = CircleShape,
                        ) {
                            Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Shuffle")
                        }
                        FilledTonalButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPlayAll(false)
                            },
                            shape = CircleShape,
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play Album")
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
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPlayTrack(track)
                },
                onPlayNext = { onPlayNext(track) },
                onAddToQueue = { onAddToQueue(track) },
                onSelectArtist = { onSelectArtist(track.artist) },
                onSelectAlbum = { if (track.album.isNotBlank()) onSelectAlbum(track.album, track.artist) },
                onDelete = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDeleteTrack(track)
                },
            )
        }
    }
}

@Composable
private fun ActiveDownloadCard(
    download: DownloadProgress,
    onCancel: () -> Unit,
) {
    val progressFloat by animateFloatAsState(
        targetValue = download.progressPercent / 100f,
        label = "downloadProgress",
    )

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = liquidGlassContainerColor(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))),
        modifier = Modifier.fillMaxWidth().liquidGlassChrome(RoundedCornerShape(18.dp), LocalLiquidGlass.current),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = download.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (download.isWaitingForConnection) {
                            "${download.artist} \u2022 Waiting for connection \u2022 ${download.progressPercent}% saved"
                        } else {
                            "${download.artist} \u2022 ${download.formatBadge} \u2022 ${download.progressPercent}%"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel download", tint = MaterialTheme.colorScheme.error)
                }
            }

            LinearProgressIndicator(
                progress = { progressFloat },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )
        }
    }
}

@Composable
private fun DownloadedTrackCard(
    track: DownloadedTrackEntity,
    isPlaying: Boolean,
    position: GroupPosition,
    trackNumber: Int? = null,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onSelectArtist: () -> Unit,
    onSelectAlbum: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        shape = groupShape(position),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onPlay, onLongClick = { menuExpanded = true })
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Optional track number
            if (trackNumber != null) {
                Text(
                    text = "$trackNumber",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(24.dp),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.width(6.dp))
            }

            Box(Modifier.size(50.dp)) {
                ArtworkImage(
                    name = track.title,
                    artist = track.artist,
                    embeddedUrl = track.artworkUrl,
                    fallbackIcon = Icons.Filled.MusicNote,
                    modifier = Modifier.fillMaxSize().clip(ArtworkShape),
                )
                if (isPlaying) {
                    PlayingWaveBars(
                        Modifier.align(Alignment.BottomEnd).padding(3.dp).size(22.dp, 16.dp),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (track.isLossless) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = track.formatBadge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (track.isLossless) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                        )
                    }
                    if (track.hasLyrics) {
                        Spacer(Modifier.width(4.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Text(
                                text = "LRC",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.5.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onSelectArtist() },
                    )
                    if (track.album.isNotBlank() && !track.album.equals(track.title, ignoreCase = true)) {
                        Text(
                            text = " \u2022 ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = track.album,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { onSelectAlbum() },
                        )
                    }
                }

                Spacer(Modifier.height(1.dp))

                val sizeText = formatBytes(track.fileSizeBytes)
                val durationText = if (track.durationMs > 0) "${formatDuration(track.durationMs)} \u2022 " else ""
                Text(
                    text = "$durationText$sizeText \u2022 ${formatDate(track.downloadedAtMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Play button
            Surface(
                onClick = onPlay,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play offline track",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.width(2.dp))

            // More options menu
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Song options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Play") },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onPlay()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Play Next") },
                        leadingIcon = { Icon(Icons.Filled.PlaylistAdd, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onPlayNext()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Queue") },
                        leadingIcon = { Icon(Icons.Filled.QueueMusic, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onAddToQueue()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("View Artist (${track.artist})") },
                        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onSelectArtist()
                        },
                    )
                    if (track.album.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text("View Album (${track.album})") },
                            leadingIcon = { Icon(Icons.Filled.Album, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onSelectAlbum()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyDownloadsState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(88.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
            Text(
                "No Downloaded Songs",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Tap the 3-dot menu on any song and select \"Download (Max Quality)\". Lossless FLAC tracks and YouTube audio are saved directly to your device's Music/LastWave folder with embedded metadata & synchronized LRCLIB lyrics.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
