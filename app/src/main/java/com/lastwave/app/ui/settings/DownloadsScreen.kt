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


enum class DownloadTab(val label: String) {
    SONGS("Songs"),
    ARTISTS("Artists"),
    ALBUMS("Albums"),
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
    val songSort by viewModel.songSort.collectAsStateWithLifecycle()
    val artistSort by viewModel.artistSort.collectAsStateWithLifecycle()
    val albumSort by viewModel.albumSort.collectAsStateWithLifecycle()
    val downloadFolder by viewModel.downloadFolder.collectAsStateWithLifecycle()
    val downloadStructure by viewModel.downloadStructure.collectAsStateWithLifecycle()
    val useAlbumArtistForFolders by viewModel.useAlbumArtistForFolders.collectAsStateWithLifecycle()
    val primaryArtistOnly by viewModel.primaryArtistOnly.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val activeDownloads = activeDownloadsMap.values.filter { !it.isFinished && it.error == null }

    val haptic = LocalHapticFeedback.current
    val musicPlayer = LocalMusicPlayer.current
    val playbackState by musicPlayer.chromeState.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableStateOf(DownloadTab.SONGS) }
    var subViewStack by remember { mutableStateOf(listOf<DownloadSubView>(DownloadSubView.Root)) }
    val currentSubView = subViewStack.last()

    var searchQuery by remember { mutableStateOf("") }
    // Replaced by the DataStore-backed ViewModel state above so these values
    // survive navigation. Original screen-local state kept for upstream review:
    // var songSort by remember { mutableStateOf(SongSortOption.RECENT) }
    // var artistSort by remember { mutableStateOf(ArtistSortOption.NAME_AZ) }
    // var albumSort by remember { mutableStateOf(AlbumSortOption.TITLE_AZ) }
    var filterLosslessOnly by remember { mutableStateOf(false) }
    var filterLyricsOnly by remember { mutableStateOf(false) }

    var showClearAllConfirm by remember { mutableStateOf(false) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    var trackToDelete by remember { mutableStateOf<DownloadedTrackEntity?>(null) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var showOrganizationDialog by remember { mutableStateOf(false) }
    var showLocationDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val downloadTreeUri by viewModel.downloadTreeUri.collectAsStateWithLifecycle()
    val hasCustomLocation = downloadTreeUri.isNotBlank()
    // Human-readable custom label; null when on the default location or the
    // grant was lost (SD card removed / permission revoked).
    val customLocationLabel = remember(context, downloadTreeUri) {
        com.lastwave.app.data.download.SafTreeFiles.describeLocation(context, downloadTreeUri)
    }
    val customLocationStale = hasCustomLocation && customLocationLabel == null
    val storageNoun = customLocationLabel ?: "Music/$downloadFolder"
    val locationPicker =
        rememberLauncherForActivityResult(PersistableOpenDocumentTree()) { uri: android.net.Uri? ->
            if (uri != null) {
                if (com.lastwave.app.data.download.SafTreeFiles.takePersistedAccess(context, uri)) {
                    viewModel.setDownloadTreeUri(uri.toString())
                }
            }
        }

    // Files saved by another install or build of the app need the user's OK
    // before Android lets us delete them.
    var trackAwaitingDeleteConsent by remember { mutableStateOf<DownloadedTrackEntity?>(null) }
    val deleteConsentLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        trackAwaitingDeleteConsent?.let { track ->
            viewModel.onDeleteConsentResult(track, result.resultCode == android.app.Activity.RESULT_OK)
        }
        trackAwaitingDeleteConsent = null
    }
    val deleteFailedMessage = stringResource(com.lastwave.app.R.string.dl_delete_failed)
    LaunchedEffect(viewModel) {
        viewModel.deleteEvents.collect { event ->
            when (event) {
                is DownloadsViewModel.DeleteEvent.NeedsConsent -> {
                    trackAwaitingDeleteConsent = event.track
                    runCatching {
                        deleteConsentLauncher.launch(
                            androidx.activity.result.IntentSenderRequest.Builder(event.request).build(),
                        )
                    }.onFailure {
                        trackAwaitingDeleteConsent = null
                        android.widget.Toast.makeText(context, deleteFailedMessage, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                DownloadsViewModel.DeleteEvent.Failed ->
                    android.widget.Toast.makeText(context, deleteFailedMessage, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

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

    val totalSizeText = formatBytes(totalBytes ?: 0L)

    PredictiveBackScreen(
        enabled = subViewStack.size > 1,
        onBack = { popSubView() },
    ) {
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
                    if (activeDownloads.isNotEmpty()) {
                        FilledTonalButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                viewModel.cancelAllDownloads()
                            },
                            colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(com.lastwave.app.R.string.dl_cancel_all, activeDownloads.size), style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Box {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                showOptionsMenu = true
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(com.lastwave.app.R.string.dl_options_cd))
                        }
                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false },
                        ) {
                            if (activeDownloads.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(com.lastwave.app.R.string.dl_cancel_all_confirm, activeDownloads.size), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showOptionsMenu = false
                                        viewModel.cancelAllDownloads()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(stringResource(com.lastwave.app.R.string.dl_lyrics_row))
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
                                text = { Text(stringResource(com.lastwave.app.R.string.dl_open_manager)) },
                                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                                onClick = {
                                    showOptionsMenu = false
                                    viewModel.openInFileManager()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(com.lastwave.app.R.string.dl_folder))
                                        Text(
                                            "Music/$downloadFolder",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                                onClick = {
                                    showOptionsMenu = false
                                    showFolderDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(com.lastwave.app.R.string.dl_location))
                                        Text(
                                            customLocationLabel
                                                ?: if (customLocationStale) {
                                                    stringResource(com.lastwave.app.R.string.dl_location_unavailable_short)
                                                } else {
                                                    stringResource(
                                                        com.lastwave.app.R.string.dl_location_default,
                                                        downloadFolder,
                                                    )
                                                },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (customLocationStale) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                },
                                leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                                onClick = {
                                    showOptionsMenu = false
                                    showLocationDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(com.lastwave.app.R.string.dl_org))
                                        Text(
                                            if (downloadStructure == com.lastwave.app.data.local.DownloadFolderStructure.FLAT) {
                                                stringResource(
                                                    com.lastwave.app.R.string.dl_menu_flat,
                                                    downloadFolder,
                                                )
                                            } else {
                                                stringResource(
                                                    com.lastwave.app.R.string.dl_menu_structured,
                                                    stringResource(downloadStructure.shortLabelRes),
                                                    downloadFolder,
                                                )
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                                onClick = {
                                    showOptionsMenu = false
                                    showOrganizationDialog = true
                                },
                            )
                            if (tracks.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(com.lastwave.app.R.string.dl_clear_history_only)) },
                                    leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
                                    onClick = {
                                        showOptionsMenu = false
                                        showClearHistoryConfirm = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(com.lastwave.app.R.string.dl_delete_all_files), color = MaterialTheme.colorScheme.error) },
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
                            contentDescription = stringResource(com.lastwave.app.R.string.dl_offline_cd),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(com.lastwave.app.R.string.dl_offline_banner),
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
                            // Previously: onSongSortChange = { songSort = it },
                            onSongSortChange = viewModel::setSongSort,
                            artistSort = artistSort,
                            // Previously: onArtistSortChange = { artistSort = it },
                            onArtistSortChange = viewModel::setArtistSort,
                            albumSort = albumSort,
                            // Previously: onAlbumSortChange = { albumSort = it },
                            onAlbumSortChange = viewModel::setAlbumSort,
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
                                    downloadFolder = downloadFolder,
                                    downloadLocationLabel = customLocationLabel,
                                    downloadStructure = downloadStructure,
                                    onPlayTrack = { track -> viewModel.playTrack(track, filteredTracks) },
                                    onPlayNext = { viewModel.playNext(it) },
                                    onAddToQueue = { viewModel.addToQueue(it) },
                                    onPlayAll = { shuffled -> viewModel.playTracks(filteredTracks, startShuffled = shuffled) },
                                    onSelectArtist = { navigateTo(DownloadSubView.ArtistDetail(it)) },
                                    onSelectAlbum = { alb, art -> navigateTo(DownloadSubView.AlbumDetail(alb, art)) },
                                    onDeleteTrack = { trackToDelete = it },
                                    onCancelDownload = { viewModel.cancelDownload(it) },
                                    onOpenFileManager = { viewModel.openInFileManager() },
                                    onChangeFolder = { showFolderDialog = true },
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
            title = { Text(stringResource(com.lastwave.app.R.string.dl_delete_title, track.title)) },
            text = {
                Text(
                    stringResource(com.lastwave.app.R.string.dl_delete_text),
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
                    Text(stringResource(com.lastwave.app.R.string.dl_delete_file), color = MaterialTheme.colorScheme.error)
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
                        Text(stringResource(com.lastwave.app.R.string.dl_history_only))
                    }
                    TextButton(onClick = { trackToDelete = null }) { Text(stringResource(com.lastwave.app.R.string.common_cancel)) }
                }
            },
        )
    }

    // Clear history only confirm
    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = { Text(stringResource(com.lastwave.app.R.string.dl_clear_history_title)) },
            text = { Text(stringResource(com.lastwave.app.R.string.dl_clear_history, storageNoun)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistoryOnly()
                        showClearHistoryConfirm = false
                    },
                ) {
                    Text(stringResource(com.lastwave.app.R.string.dl_clear_history_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) { Text(stringResource(com.lastwave.app.R.string.common_cancel)) }
            },
        )
    }

    // Clear all files confirm
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text(stringResource(com.lastwave.app.R.string.dl_delete_all_title)) },
            text = { Text(stringResource(com.lastwave.app.R.string.dl_delete_all, tracks.size, storageNoun, totalSizeText)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAll()
                        showClearAllConfirm = false
                    },
                ) {
                    Text(stringResource(com.lastwave.app.R.string.dl_delete_all_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) { Text(stringResource(com.lastwave.app.R.string.common_cancel)) }
            },
        )
    }

    if (showFolderDialog) {
        DownloadFolderDialog(
            currentFolder = downloadFolder,
            onDismiss = { showFolderDialog = false },
            onConfirm = { name ->
                viewModel.setDownloadFolder(name)
                showFolderDialog = false
            },
        )
    }

    if (showLocationDialog) {
        DownloadLocationDialog(
            currentDescription = customLocationLabel
                ?: stringResource(
                    com.lastwave.app.R.string.dl_location_default,
                    downloadFolder,
                ),
            isCustom = hasCustomLocation,
            isUsable = !customLocationStale,
            onChoose = {
                showLocationDialog = false
                locationPicker.launch(
                    runCatching { android.net.Uri.parse(downloadTreeUri) }.getOrNull(),
                )
            },
            onReset = {
                viewModel.clearDownloadTreeUri()
                showLocationDialog = false
            },
            onDismiss = { showLocationDialog = false },
        )
    }

    if (showOrganizationDialog) {
        FolderOrganizationDialog(
            currentStructure = downloadStructure,
            useAlbumArtist = useAlbumArtistForFolders,
            primaryOnly = primaryArtistOnly,
            downloadFolder = downloadFolder,
            onDismiss = { showOrganizationDialog = false },
            onStructureChange = { viewModel.setDownloadStructure(it) },
            onUseAlbumArtistChange = { viewModel.setUseAlbumArtistForFolders(it) },
            onPrimaryOnlyChange = { viewModel.setPrimaryArtistOnly(it) },
        )
    }
}
}
