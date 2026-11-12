@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.lastwave.app.ui.playlist

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lastwave.app.R
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.LocalAudioFormats
import com.lastwave.app.data.localmusic.LocalMusicLibrary
import com.lastwave.app.data.localmusic.LocalOfflineRepository
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.toPlayableTrack
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.EdgeToEdgeDialogWindow
import com.lastwave.app.ui.common.ExpressiveGroupTrackRow
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.common.GroupGap
import com.lastwave.app.ui.common.GroupPosition
import com.lastwave.app.ui.common.OverflowMenuButton
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.groupPositionFor
import com.lastwave.app.ui.settings.formatBytes
import com.lastwave.app.ui.settings.formatDuration
import com.lastwave.app.ui.shell.FloatingNavDefaults
import com.lastwave.app.ui.theme.ExpressivePillShape
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

internal enum class LocalHubTab(val labelRes: Int) {
    SONGS(R.string.local_tab_songs),
    ALBUMS(R.string.local_tab_albums),
    ARTISTS(R.string.local_tab_artists),
    FOLDERS(R.string.local_tab_folders),
    PLAYLISTS(R.string.local_tab_playlists),
}

internal enum class LocalSortMode(val labelRes: Int) {
    TITLE(R.string.local_sort_title),
    ARTIST(R.string.local_sort_artist),
    ALBUM(R.string.local_sort_album),
    TRACK_NUMBER(R.string.local_sort_track_number),
    ADDED(R.string.local_sort_added),
    FORMAT(R.string.local_sort_format),
}

internal sealed interface LocalDetailRoute {
    data class Album(val key: String, val title: String, val artist: String) : LocalDetailRoute
    data class Artist(val key: String, val name: String) : LocalDetailRoute
    data class Folder(val key: String, val title: String) : LocalDetailRoute
    data object OfflineLikes : LocalDetailRoute
    data class OfflinePlaylistDetail(val playlistId: String) : LocalDetailRoute
}

internal data class LocalAlbumGroup(
    val key: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val tracks: List<GeneratedTrack>,
)

internal data class LocalArtistGroup(
    val key: String,
    val name: String,
    val artworkUrl: String?,
    val albumCount: Int,
    val tracks: List<GeneratedTrack>,
)

internal data class LocalFolderGroup(
    val key: String,
    val title: String,
    val subtitle: String,
    val safUri: Uri?,
    val tracks: List<GeneratedTrack>,
)

@HiltViewModel
class LocalMusicHubViewModel @Inject constructor(
    private val library: LocalMusicLibrary,
    private val offlineRepository: LocalOfflineRepository,
    val musicPlayer: MusicPlayer,
) : ViewModel() {
    val importState = library.state
    val folders = library.folders
    val trackFolders = library.trackFolders
    val autoScanEnabled = library.autoScanEnabled
    val filterNonMusicAudio = library.filterNonMusicAudio
    val offlineLikedUrls = offlineRepository.likedUrls
    val offlinePlaylists = offlineRepository.playlists

    private val _storageBytes = MutableStateFlow(0L)
    val storageBytes: StateFlow<Long> = _storageBytes.asStateFlow()

    fun refreshStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            _storageBytes.value = library.storageBytes()
        }
    }

    fun hasAudioPermission(): Boolean = library.hasAudioPermission()

    fun setAutoScanEnabled(enabled: Boolean) {
        library.setAutoScanEnabled(enabled)
    }

    fun setFilterNonMusicAudio(enabled: Boolean) {
        library.setFilterNonMusicAudio(enabled)
    }

    fun addFolder(uri: Uri) = library.addFolderAndImport(uri)

    fun rescan() = library.rescanAll()

    fun forgetFolder(uri: Uri) = library.forgetFolder(uri)

    fun acknowledgeReport() = library.acknowledgeReport()

    fun folderLabel(uri: Uri): String = library.folderLabel(uri)

    fun toggleOfflineLike(track: GeneratedTrack) {
        viewModelScope.launch {
            offlineRepository.toggleLike(track.url)
        }
    }

    fun setAllOfflineLiked(tracks: List<GeneratedTrack>, liked: Boolean) {
        viewModelScope.launch {
            offlineRepository.setAllLiked(tracks.map(GeneratedTrack::url), liked)
        }
    }

    fun createOfflinePlaylist(name: String, initialTracks: List<GeneratedTrack> = emptyList()) {
        viewModelScope.launch {
            offlineRepository.createPlaylist(name, initialTracks.map(GeneratedTrack::url))
        }
    }

    fun renameOfflinePlaylist(playlistId: String, newName: String) {
        viewModelScope.launch {
            offlineRepository.renamePlaylist(playlistId, newName)
        }
    }

    fun deleteOfflinePlaylist(playlistId: String) {
        viewModelScope.launch {
            offlineRepository.deletePlaylist(playlistId)
        }
    }

    fun addTracksToOfflinePlaylist(playlistId: String, tracks: List<GeneratedTrack>) {
        viewModelScope.launch {
            offlineRepository.addTracksToPlaylist(playlistId, tracks.map(GeneratedTrack::url))
        }
    }

    fun removeTrackFromOfflinePlaylist(playlistId: String, url: String) {
        viewModelScope.launch {
            offlineRepository.removeTrackFromPlaylist(playlistId, url)
        }
    }

    fun removeTracksFromLibrary(tracks: List<GeneratedTrack>) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            val urls = tracks.map(GeneratedTrack::url)
            library.removeTracks(urls)
            offlineRepository.removeTracks(urls)
        }
    }

    fun playTracks(
        tracks: List<GeneratedTrack>,
        startIndex: Int,
        sourceLabel: String,
        shuffled: Boolean = false,
    ) {
        if (tracks.isEmpty()) return
        val clamped = startIndex.coerceIn(0, tracks.lastIndex)
        musicPlayer.playQueue(
            tracks = tracks.map(GeneratedTrack::toPlayableTrack),
            startIndex = clamped,
            sourceLabel = sourceLabel,
            startShuffled = shuffled,
            enableRadioContinuation = false,
        )
    }

    fun playNext(tracks: List<GeneratedTrack>) {
        tracks.asReversed().forEach { track ->
            musicPlayer.playNext(track.toPlayableTrack())
        }
    }

    fun addToQueue(tracks: List<GeneratedTrack>) {
        tracks.forEach { track ->
            musicPlayer.addToQueue(track.toPlayableTrack())
        }
    }
}

@Composable
fun LocalMusicHub(
    playlist: SavedPlaylist,
    onBack: () -> Unit,
    viewModel: LocalMusicHubViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()

    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val safFolders by viewModel.folders.collectAsStateWithLifecycle()
    val trackFolders by viewModel.trackFolders.collectAsStateWithLifecycle()
    val autoScanEnabled by viewModel.autoScanEnabled.collectAsStateWithLifecycle()
    val filterNonMusic by viewModel.filterNonMusicAudio.collectAsStateWithLifecycle()
    val offlineLikedUrls by viewModel.offlineLikedUrls.collectAsStateWithLifecycle()
    val offlinePlaylists by viewModel.offlinePlaylists.collectAsStateWithLifecycle()
    val storageBytes by viewModel.storageBytes.collectAsStateWithLifecycle()
    val playbackState by viewModel.musicPlayer.listPlaybackState.collectAsStateWithLifecycle()

    var activeTab by rememberSaveable(playlist.id) { mutableStateOf(LocalHubTab.SONGS) }
    var sortMode by rememberSaveable(playlist.id) { mutableStateOf(LocalSortMode.TITLE) }
    var sortAscending by rememberSaveable(playlist.id) { mutableStateOf(true) }
    var isGridView by rememberSaveable(playlist.id) { mutableStateOf(false) }
    var isSearchActive by rememberSaveable(playlist.id) { mutableStateOf(false) }
    var searchQuery by rememberSaveable(playlist.id) { mutableStateOf("") }
    var detailRoute by remember { mutableStateOf<LocalDetailRoute?>(null) }
    var selectedUrls by remember { mutableStateOf(emptySet<String>()) }

    var hasAudioPermission by remember { mutableStateOf(checkAudioPermission(context)) }
    var showSourcesSheet by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var createPlaylistSeedTracks by remember { mutableStateOf<List<GeneratedTrack>>(emptyList()) }
    var renamingPlaylist by remember { mutableStateOf<LocalOfflineRepository.OfflinePlaylist?>(null) }
    var deletingPlaylist by remember { mutableStateOf<LocalOfflineRepository.OfflinePlaylist?>(null) }
    var batchOfflinePlaylistTargetTracks by remember { mutableStateOf<List<GeneratedTrack>?>(null) }
    var menuTrack by remember { mutableStateOf<GeneratedTrack?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasAudioPermission = granted || checkAudioPermission(context)
        if (hasAudioPermission) {
            viewModel.setAutoScanEnabled(true)
            viewModel.rescan()
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) viewModel.addFolder(uri)
    }

    LifecycleResumeEffect(Unit) {
        hasAudioPermission = checkAudioPermission(context)
        onPauseOrDispose {}
    }

    LaunchedEffect(importState is LocalMusicLibrary.ImportState.Finished, playlist.tracks.size) {
        viewModel.refreshStorage()
    }

    val tracksByNormUrl = remember(playlist.tracks) {
        playlist.tracks.associateBy { LocalOfflineRepository.normalizeUrl(it.url) }
    }

    val albumGroups = remember(playlist.tracks) {
        buildAlbumGroups(playlist.tracks)
    }

    val artistGroups = remember(playlist.tracks) {
        buildArtistGroups(playlist.tracks)
    }

    val folderGroups = remember(playlist.tracks, safFolders, trackFolders) {
        buildFolderGroups(
            tracks = playlist.tracks,
            safFolders = safFolders,
            trackFolders = trackFolders,
            labelResolver = viewModel::folderLabel,
        )
    }

    val offlineLikedTracks = remember(playlist.tracks, offlineLikedUrls) {
        playlist.tracks.filter { LocalOfflineRepository.normalizeUrl(it.url) in offlineLikedUrls }
    }

    val currentDetailTracks: List<GeneratedTrack> = remember(
        detailRoute,
        playlist.tracks,
        albumGroups,
        artistGroups,
        folderGroups,
        offlineLikedTracks,
        offlinePlaylists,
        tracksByNormUrl,
    ) {
        when (val route = detailRoute) {
            is LocalDetailRoute.Album -> {
                albumGroups.firstOrNull { it.key == route.key }?.tracks.orEmpty()
            }
            is LocalDetailRoute.Artist -> {
                artistGroups.firstOrNull { it.key == route.key }?.tracks.orEmpty()
            }
            is LocalDetailRoute.Folder -> {
                folderGroups.firstOrNull { it.key == route.key }?.tracks.orEmpty()
            }
            LocalDetailRoute.OfflineLikes -> offlineLikedTracks
            is LocalDetailRoute.OfflinePlaylistDetail -> {
                val target = offlinePlaylists.firstOrNull { it.id == route.playlistId }
                target?.trackUrls.orEmpty().mapNotNull { url ->
                    tracksByNormUrl[LocalOfflineRepository.normalizeUrl(url)]
                }
            }
            null -> emptyList()
        }
    }

    val displayedSongs = remember(
        playlist.tracks,
        currentDetailTracks,
        detailRoute,
        sortMode,
        sortAscending,
        searchQuery,
    ) {
        val base = if (detailRoute != null) currentDetailTracks else playlist.tracks
        val filtered = if (searchQuery.isBlank()) {
            base
        } else {
            val q = searchQuery.trim()
            base.filter { t ->
                t.name.contains(q, ignoreCase = true) ||
                    t.artist.contains(q, ignoreCase = true) ||
                    (t.album?.contains(q, ignoreCase = true) == true)
            }
        }
        val preservePlaylistOrder = detailRoute is LocalDetailRoute.OfflinePlaylistDetail &&
            sortMode == LocalSortMode.ADDED &&
            sortAscending
        if (preservePlaylistOrder) {
            filtered
        } else if (detailRoute is LocalDetailRoute.Album && sortMode == LocalSortMode.TITLE && sortAscending) {
            // Albums default nicely by disc + track number when user hasn't picked a custom sort override
            filtered.sortedWith(
                compareBy<GeneratedTrack>(
                    { it.discNumber ?: Int.MAX_VALUE },
                    { it.trackNumber ?: Int.MAX_VALUE },
                    { it.name.lowercase() },
                ),
            )
        } else {
            sortLocalTracks(filtered, sortMode, sortAscending)
        }
    }

    val filteredAlbums = remember(albumGroups, searchQuery, sortAscending, sortMode) {
        val q = searchQuery.trim()
        val matched = if (q.isEmpty()) albumGroups else albumGroups.filter {
            it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true)
        }
        val sorted = when (sortMode) {
            LocalSortMode.ARTIST -> matched.sortedBy { it.artist.lowercase() }
            else -> matched.sortedBy { it.title.lowercase() }
        }
        if (sortAscending) sorted else sorted.reversed()
    }

    val filteredArtists = remember(artistGroups, searchQuery, sortAscending) {
        val q = searchQuery.trim()
        val matched = if (q.isEmpty()) artistGroups else artistGroups.filter {
            it.name.contains(q, ignoreCase = true)
        }
        val sorted = matched.sortedBy { it.name.lowercase() }
        if (sortAscending) sorted else sorted.reversed()
    }

    val filteredFolders = remember(folderGroups, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) folderGroups else folderGroups.filter {
            it.title.contains(q, ignoreCase = true) || it.subtitle.contains(q, ignoreCase = true)
        }
    }

    val filteredOfflinePlaylists = remember(offlinePlaylists, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) offlinePlaylists else offlinePlaylists.filter {
            it.name.contains(q, ignoreCase = true)
        }
    }

    val activeDetailOfflinePlaylist = remember(detailRoute, offlinePlaylists) {
        val id = (detailRoute as? LocalDetailRoute.OfflinePlaylistDetail)?.playlistId ?: return@remember null
        offlinePlaylists.firstOrNull { it.id == id }
    }

    LaunchedEffect(detailRoute, activeDetailOfflinePlaylist, offlinePlaylists) {
        if (detailRoute is LocalDetailRoute.OfflinePlaylistDetail && activeDetailOfflinePlaylist == null) {
            detailRoute = null
        }
    }

    val detailTitle = when (val route = detailRoute) {
        is LocalDetailRoute.Album -> route.title
        is LocalDetailRoute.Artist -> route.name
        is LocalDetailRoute.Folder -> route.title
        LocalDetailRoute.OfflineLikes -> stringResource(R.string.local_offline_likes)
        is LocalDetailRoute.OfflinePlaylistDetail -> activeDetailOfflinePlaylist?.name ?: stringResource(R.string.local_offline_playlists)
        null -> playlist.title
    }

    val sourceLabel = if (detailRoute == null) playlist.title else detailTitle

    val showScrolledHeader by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 140
        }
    }

    val selectionActive = selectedUrls.isNotEmpty()
    val runningImport = importState is LocalMusicLibrary.ImportState.Scanning ||
        importState is LocalMusicLibrary.ImportState.Copying

    BackHandler(enabled = selectionActive || detailRoute != null) {
        if (selectionActive) {
            selectedUrls = emptySet()
        } else {
            detailRoute = null
            searchQuery = ""
            isSearchActive = false
        }
    }

    val selectedTracks = remember(selectedUrls, playlist.tracks) {
        playlist.tracks.filter { it.url in selectedUrls }
    }

    fun toggleSelection(track: GeneratedTrack) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        selectedUrls = if (track.url in selectedUrls) {
            selectedUrls - track.url
        } else {
            selectedUrls + track.url
        }
    }

    fun requestAutoScanWithPermission() {
        if (hasAudioPermission) {
            viewModel.setAutoScanEnabled(true)
            viewModel.rescan()
        } else {
            permissionLauncher.launch(requiredAudioPermission())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Subtle ambient top tint using Material primary glow
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                ),
        )

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp,
                bottom = FloatingNavDefaults.contentBottomPadding() + if (selectionActive) 84.dp else 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(GroupGap),
            modifier = Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 860.dp)
                .align(Alignment.TopCenter),
        ) {
            // 1. Header Hero
            item(key = "local_hero") {
                LocalHeroHeader(
                    detailRoute = detailRoute,
                    title = detailTitle,
                    tracks = if (detailRoute != null) currentDetailTracks else playlist.tracks,
                    safFolderCount = safFolders.size,
                    storageBytes = storageBytes,
                    playbackIsActiveFromSource = playbackState.isPlaying && playbackState.sourceLabel == sourceLabel,
                    onPlayAll = {
                        viewModel.playTracks(displayedSongs, startIndex = 0, sourceLabel = sourceLabel, shuffled = false)
                    },
                    onShuffleAll = {
                        val idx = displayedSongs.indices.randomOrNull() ?: 0
                        viewModel.playTracks(displayedSongs, startIndex = idx, sourceLabel = sourceLabel, shuffled = true)
                    },
                    onRenameOfflinePlaylist = activeDetailOfflinePlaylist?.let { pl ->
                        { renamingPlaylist = pl }
                    },
                    onDeleteOfflinePlaylist = activeDetailOfflinePlaylist?.let { pl ->
                        { deletingPlaylist = pl }
                    },
                )
            }

            // 2. Permission CTA (if auto-scan enabled or library empty without audio permission)
            if (detailRoute == null && !hasAudioPermission && (autoScanEnabled || playlist.tracks.isEmpty())) {
                item(key = "local_permission_banner") {
                    LocalPermissionCard(
                        onGrantPermission = { requestAutoScanWithPermission() },
                        onPickFolder = { folderPicker.launch(null) },
                    )
                }
            }

            // 3. Scan / Indexing Progress or Report
            if (detailRoute == null && importState !is LocalMusicLibrary.ImportState.Idle) {
                item(key = "local_import_banner") {
                    LocalImportStatusCard(
                        state = importState,
                        onAcknowledge = viewModel::acknowledgeReport,
                    )
                }
            }

            // 4. Expandable Search Bar
            if (isSearchActive) {
                item(key = "local_search_field") {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                stringResource(R.string.local_search_hint),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = null)
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Transparent,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                }
            }

            // 5. Artist Albums Strip inside Artist Detail
            if (detailRoute is LocalDetailRoute.Artist) {
                val artistAlbums = albumGroups.filter { album ->
                    album.tracks.any { t ->
                        artistKeyOf(t) == (detailRoute as LocalDetailRoute.Artist).key
                    }
                }
                if (artistAlbums.isNotEmpty()) {
                    item(key = "artist_albums_strip") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.local_tab_albums),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(vertical = 4.dp),
                            ) {
                                items(artistAlbums, key = { it.key }) { album ->
                                    LocalSquareGridCard(
                                        title = album.title,
                                        subtitle = pluralStringCountSongs(album.tracks.size),
                                        trackName = album.tracks.firstOrNull()?.name ?: album.title,
                                        trackArtist = album.artist,
                                        artworkUrl = album.artworkUrl,
                                        badge = null,
                                        fallbackIcon = Icons.Filled.Album,
                                        modifier = Modifier.width(136.dp),
                                        onClick = {
                                            detailRoute = LocalDetailRoute.Album(album.key, album.title, album.artist)
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }

            // 6. Main Hub Tab Pills Row
            if (detailRoute == null) {
                item(key = "local_tabs") {
                    LocalTabPillsRow(
                        activeTab = activeTab,
                        counts = mapOf(
                            LocalHubTab.SONGS to playlist.tracks.size,
                            LocalHubTab.ALBUMS to albumGroups.size,
                            LocalHubTab.ARTISTS to artistGroups.size,
                            LocalHubTab.FOLDERS to folderGroups.size,
                            LocalHubTab.PLAYLISTS to (offlinePlaylists.size + 1),
                        ),
                        onSelect = { tab ->
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            activeTab = tab
                        },
                    )
                }
            }

            // 7. Sort + View Toggle Bar (for track lists & Albums/Artists)
            if (detailRoute != null || activeTab in setOf(LocalHubTab.SONGS, LocalHubTab.ALBUMS, LocalHubTab.ARTISTS)) {
                item(key = "local_sort_bar") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    showSortMenu = true
                                },
                                shape = ExpressivePillShape,
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text(
                                        text = stringResource(sortMode.labelRes),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Icon(
                                        imageVector = if (sortAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                                shape = RoundedCornerShape(20.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                val availableSorts = if (detailRoute == null && activeTab != LocalHubTab.SONGS) {
                                    listOf(LocalSortMode.TITLE, LocalSortMode.ARTIST)
                                } else {
                                    LocalSortMode.entries
                                }
                                availableSorts.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(mode.labelRes)) },
                                        leadingIcon = {
                                            if (sortMode == mode) {
                                                Icon(
                                                    imageVector = if (sortAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        },
                                        onClick = {
                                            if (sortMode == mode) {
                                                sortAscending = !sortAscending
                                            } else {
                                                sortMode = mode
                                                sortAscending = true
                                            }
                                            showSortMenu = false
                                        },
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (detailRoute == null) {
                                FilledTonalIconButton(
                                    onClick = { showSourcesSheet = true },
                                    modifier = Modifier.size(38.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Tune,
                                        contentDescription = stringResource(R.string.local_manage_sources_cd),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            FilledTonalIconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    isGridView = !isGridView
                                },
                                modifier = Modifier.size(38.dp),
                            ) {
                                Icon(
                                    imageVector = if (isGridView) Icons.Filled.FormatListBulleted else Icons.Filled.Apps,
                                    contentDescription = stringResource(
                                        if (isGridView) R.string.local_view_list_cd else R.string.local_view_grid_cd,
                                    ),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            // 8. Body Content
            when {
                detailRoute != null -> {
                    if (displayedSongs.isEmpty()) {
                        item(key = "detail_empty") {
                            LocalEmptyState(
                                message = if (detailRoute == LocalDetailRoute.OfflineLikes && searchQuery.isBlank()) {
                                    stringResource(R.string.local_empty_offline_likes)
                                } else if (searchQuery.isNotBlank()) {
                                    stringResource(R.string.local_empty_filtered)
                                } else {
                                    stringResource(R.string.local_empty_tracks)
                                },
                            )
                        }
                    } else if (isGridView) {
                        val rows = displayedSongs.chunked(2)
                        itemsIndexed(rows, key = { idx, _ -> "detail_grid_$idx" }) { rowIdx, pair ->
                            LocalSongGridRow(
                                tracks = pair,
                                rowOffset = rowIdx * 2,
                                allTracks = displayedSongs,
                                playbackCurrent = playbackState.current,
                                isPlaying = playbackState.isPlaying,
                                offlineLikedUrls = offlineLikedUrls,
                                selectedUrls = selectedUrls,
                                selectionActive = selectionActive,
                                onTrackClick = { index, track ->
                                    if (selectionActive) toggleSelection(track)
                                    else viewModel.playTracks(displayedSongs, index, sourceLabel)
                                },
                                onTrackLongClick = ::toggleSelection,
                                onToggleLike = viewModel::toggleOfflineLike,
                                onMenu = { menuTrack = it },
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = displayedSongs,
                            key = { index, t -> "detail_${t.url}_$index" },
                            contentType = { _, _ -> "local_track_row" },
                        ) { index, track ->
                            val trackPlaying = playbackState.isPlaying &&
                                playbackState.current?.title.equals(track.name, ignoreCase = true) &&
                                playbackState.current?.artist.equals(track.artist, ignoreCase = true)
                            LocalTrackListRow(
                                track = track,
                                position = groupPositionFor(index, displayedSongs.size),
                                showTrackNumber = detailRoute is LocalDetailRoute.Album,
                                isPlaying = trackPlaying,
                                isLiked = LocalOfflineRepository.normalizeUrl(track.url) in offlineLikedUrls,
                                isSelected = track.url in selectedUrls,
                                selectionActive = selectionActive,
                                onClick = {
                                    if (selectionActive) toggleSelection(track)
                                    else viewModel.playTracks(displayedSongs, index, sourceLabel)
                                },
                                onLongClick = { toggleSelection(track) },
                                onToggleLike = { viewModel.toggleOfflineLike(track) },
                                onMenu = { menuTrack = track },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }

                activeTab == LocalHubTab.SONGS -> {
                    if (displayedSongs.isEmpty()) {
                        item(key = "songs_empty") {
                            LocalEmptyState(
                                message = if (searchQuery.isNotBlank()) stringResource(R.string.local_empty_filtered)
                                else stringResource(R.string.local_empty_tracks),
                                primaryActionLabel = if (searchQuery.isBlank()) stringResource(R.string.local_choose_folder) else null,
                                onPrimaryAction = if (searchQuery.isBlank()) { { folderPicker.launch(null) } } else null,
                            )
                        }
                    } else if (isGridView) {
                        val rows = displayedSongs.chunked(2)
                        itemsIndexed(rows, key = { idx, _ -> "songs_grid_$idx" }) { rowIdx, pair ->
                            LocalSongGridRow(
                                tracks = pair,
                                rowOffset = rowIdx * 2,
                                allTracks = displayedSongs,
                                playbackCurrent = playbackState.current,
                                isPlaying = playbackState.isPlaying,
                                offlineLikedUrls = offlineLikedUrls,
                                selectedUrls = selectedUrls,
                                selectionActive = selectionActive,
                                onTrackClick = { index, track ->
                                    if (selectionActive) toggleSelection(track)
                                    else viewModel.playTracks(displayedSongs, index, playlist.title)
                                },
                                onTrackLongClick = ::toggleSelection,
                                onToggleLike = viewModel::toggleOfflineLike,
                                onMenu = { menuTrack = it },
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = displayedSongs,
                            key = { index, t -> "song_${t.url}_$index" },
                            contentType = { _, _ -> "local_track_row" },
                        ) { index, track ->
                            val trackPlaying = playbackState.isPlaying &&
                                playbackState.current?.title.equals(track.name, ignoreCase = true) &&
                                playbackState.current?.artist.equals(track.artist, ignoreCase = true)
                            LocalTrackListRow(
                                track = track,
                                position = groupPositionFor(index, displayedSongs.size),
                                showTrackNumber = false,
                                isPlaying = trackPlaying,
                                isLiked = LocalOfflineRepository.normalizeUrl(track.url) in offlineLikedUrls,
                                isSelected = track.url in selectedUrls,
                                selectionActive = selectionActive,
                                onClick = {
                                    if (selectionActive) toggleSelection(track)
                                    else viewModel.playTracks(displayedSongs, index, playlist.title)
                                },
                                onLongClick = { toggleSelection(track) },
                                onToggleLike = { viewModel.toggleOfflineLike(track) },
                                onMenu = { menuTrack = track },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }

                activeTab == LocalHubTab.ALBUMS -> {
                    if (filteredAlbums.isEmpty()) {
                        item(key = "albums_empty") {
                            LocalEmptyState(
                                message = if (searchQuery.isNotBlank()) stringResource(R.string.local_empty_filtered)
                                else stringResource(R.string.local_empty_tracks),
                            )
                        }
                    } else if (isGridView) {
                        val rows = filteredAlbums.chunked(2)
                        itemsIndexed(rows, key = { idx, _ -> "albums_grid_$idx" }) { _, pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                pair.forEach { album ->
                                    LocalSquareGridCard(
                                        title = album.title,
                                        subtitle = "${album.artist} • ${pluralStringCountSongs(album.tracks.size)}",
                                        trackName = album.tracks.firstOrNull()?.name ?: album.title,
                                        trackArtist = album.artist,
                                        artworkUrl = album.artworkUrl,
                                        badge = null,
                                        fallbackIcon = Icons.Filled.Album,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            detailRoute = LocalDetailRoute.Album(album.key, album.title, album.artist)
                                        },
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = filteredAlbums,
                            key = { _, a -> "album_${a.key}" },
                            contentType = { _, _ -> "local_album_row" },
                        ) { index, album ->
                            val firstTrack = album.tracks.firstOrNull()
                            ExpressiveGroupTrackRow(
                                title = album.title,
                                subtitle = "${album.artist} • ${pluralStringCountSongs(album.tracks.size)}",
                                position = groupPositionFor(index, filteredAlbums.size),
                                onClick = {
                                    detailRoute = LocalDetailRoute.Album(album.key, album.title, album.artist)
                                },
                                leading = {
                                    ArtworkImage(
                                        name = firstTrack?.name ?: album.title,
                                        artist = album.artist,
                                        embeddedUrl = album.artworkUrl,
                                        fallbackIcon = Icons.Filled.Album,
                                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)),
                                    )
                                },
                                trailing = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForwardIos,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.size(15.dp),
                                    )
                                },
                            )
                        }
                    }
                }

                activeTab == LocalHubTab.ARTISTS -> {
                    if (filteredArtists.isEmpty()) {
                        item(key = "artists_empty") {
                            LocalEmptyState(
                                message = if (searchQuery.isNotBlank()) stringResource(R.string.local_empty_filtered)
                                else stringResource(R.string.local_empty_tracks),
                            )
                        }
                    } else if (isGridView) {
                        val rows = filteredArtists.chunked(2)
                        itemsIndexed(rows, key = { idx, _ -> "artists_grid_$idx" }) { _, pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                pair.forEach { artist ->
                                    LocalSquareGridCard(
                                        title = artist.name,
                                        subtitle = "${artist.albumCount} albums • ${pluralStringCountSongs(artist.tracks.size)}",
                                        trackName = artist.tracks.firstOrNull()?.name ?: artist.name,
                                        trackArtist = artist.name,
                                        artworkUrl = artist.artworkUrl,
                                        badge = null,
                                        fallbackIcon = Icons.Filled.Person,
                                        circularArtwork = true,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            detailRoute = LocalDetailRoute.Artist(artist.key, artist.name)
                                        },
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = filteredArtists,
                            key = { _, a -> "artist_${a.key}" },
                            contentType = { _, _ -> "local_artist_row" },
                        ) { index, artist ->
                            val firstTrack = artist.tracks.firstOrNull()
                            ExpressiveGroupTrackRow(
                                title = artist.name,
                                subtitle = "${artist.albumCount} albums • ${pluralStringCountSongs(artist.tracks.size)}",
                                position = groupPositionFor(index, filteredArtists.size),
                                onClick = {
                                    detailRoute = LocalDetailRoute.Artist(artist.key, artist.name)
                                },
                                leading = {
                                    ArtworkImage(
                                        name = firstTrack?.name ?: artist.name,
                                        artist = artist.name,
                                        embeddedUrl = artist.artworkUrl,
                                        fallbackIcon = Icons.Filled.Person,
                                        modifier = Modifier.size(52.dp).clip(CircleShape),
                                    )
                                },
                                trailing = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForwardIos,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.size(15.dp),
                                    )
                                },
                            )
                        }
                    }
                }

                activeTab == LocalHubTab.FOLDERS -> {
                    item(key = "folders_actions") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            FilledTonalButton(
                                onClick = { folderPicker.launch(null) },
                                enabled = !runningImport,
                                shape = ExpressivePillShape,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.local_choose_folder), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = viewModel::rescan,
                                enabled = !runningImport,
                                shape = ExpressivePillShape,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.local_rescan), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            FilledTonalIconButton(
                                onClick = { showSourcesSheet = true },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.local_manage_sources_cd), modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    if (filteredFolders.isEmpty()) {
                        item(key = "folders_empty") {
                            LocalEmptyState(
                                message = stringResource(R.string.local_no_folder),
                                primaryActionLabel = stringResource(R.string.local_choose_folder),
                                onPrimaryAction = { folderPicker.launch(null) },
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = filteredFolders,
                            key = { _, f -> "folder_${f.key}" },
                        ) { index, folder ->
                            ExpressiveGroupTrackRow(
                                title = folder.title,
                                subtitle = "${folder.subtitle} • ${pluralStringCountSongs(folder.tracks.size)}",
                                position = groupPositionFor(index, filteredFolders.size),
                                onClick = {
                                    detailRoute = LocalDetailRoute.Folder(folder.key, folder.title)
                                },
                                leading = {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Filled.FolderOpen,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            )
                                        }
                                    }
                                },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        folder.safUri?.let { uri ->
                                            IconButton(
                                                onClick = { viewModel.forgetFolder(uri) },
                                                enabled = !runningImport,
                                            ) {
                                                Icon(
                                                    Icons.Filled.Close,
                                                    contentDescription = stringResource(R.string.local_forget_folder),
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForwardIos,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.size(15.dp),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }

                activeTab == LocalHubTab.PLAYLISTS -> {
                    item(key = "offline_likes_card") {
                        Surface(
                            onClick = { detailRoute = LocalDetailRoute.OfflineLikes },
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(52.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(26.dp))
                                    }
                                }
                                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                    Text(
                                        stringResource(R.string.local_offline_likes),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    Text(
                                        "${pluralStringCountSongs(offlineLikedTracks.size)} • ${stringResource(R.string.local_offline_likes_sub)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForwardIos,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }

                    item(key = "offline_playlists_header") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.local_offline_playlists),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            FilledTonalButton(
                                onClick = {
                                    createPlaylistSeedTracks = emptyList()
                                    showCreatePlaylistDialog = true
                                },
                                shape = ExpressivePillShape,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.local_new_playlist))
                            }
                        }
                    }

                    if (filteredOfflinePlaylists.isEmpty()) {
                        item(key = "offline_playlists_empty") {
                            LocalEmptyState(
                                message = if (searchQuery.isNotBlank()) stringResource(R.string.local_empty_filtered)
                                else stringResource(R.string.local_empty_offline_playlists),
                                primaryActionLabel = if (searchQuery.isBlank()) stringResource(R.string.local_new_playlist) else null,
                                onPrimaryAction = if (searchQuery.isBlank()) {
                                    {
                                        createPlaylistSeedTracks = emptyList()
                                        showCreatePlaylistDialog = true
                                    }
                                } else null,
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = filteredOfflinePlaylists,
                            key = { _, pl -> "offline_pl_${pl.id}" },
                        ) { index, offlinePl ->
                            val firstTrack = offlinePl.trackUrls.firstNotNullOfOrNull {
                                tracksByNormUrl[LocalOfflineRepository.normalizeUrl(it)]
                            }
                            ExpressiveGroupTrackRow(
                                title = offlinePl.name,
                                subtitle = pluralStringCountSongs(offlinePl.trackUrls.size),
                                position = groupPositionFor(index, filteredOfflinePlaylists.size),
                                onClick = {
                                    detailRoute = LocalDetailRoute.OfflinePlaylistDetail(offlinePl.id)
                                },
                                leading = {
                                    ArtworkImage(
                                        name = firstTrack?.name ?: offlinePl.name,
                                        artist = firstTrack?.artist ?: "",
                                        embeddedUrl = firstTrack?.artworkUrl,
                                        fallbackIcon = Icons.AutoMirrored.Filled.QueueMusic,
                                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)),
                                    )
                                },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { renamingPlaylist = offlinePl }) {
                                            Icon(
                                                Icons.Filled.Edit,
                                                contentDescription = stringResource(R.string.local_rename_playlist),
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                        IconButton(onClick = { deletingPlaylist = offlinePl }) {
                                            Icon(
                                                Icons.Filled.DeleteOutline,
                                                contentDescription = stringResource(R.string.local_delete_playlist),
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // Fixed Top Bar
        val topBarColor by animateColorAsState(
            targetValue = if (showScrolledHeader || detailRoute != null) {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
            } else {
                Color.Transparent
            },
            animationSpec = tween(ExpressiveMotion.Quick),
            label = "localTopBarColor",
        )

        Surface(
            color = topBarColor,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .adaptiveContentWidth(maxWidth = 860.dp)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        when {
                            selectionActive -> selectedUrls = emptySet()
                            detailRoute != null -> {
                                detailRoute = null
                                searchQuery = ""
                                isSearchActive = false
                            }
                            else -> onBack()
                        }
                    },
                ) {
                    Icon(
                        imageVector = if (selectionActive) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.pd_back_cd),
                    )
                }

                AnimatedVisibility(
                    visible = showScrolledHeader || selectionActive,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                ) {
                    Text(
                        text = if (selectionActive) {
                            stringResource(R.string.local_selected_count, selectedUrls.size)
                        } else {
                            detailTitle
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!showScrolledHeader && !selectionActive) {
                    Spacer(Modifier.weight(1f))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selectionActive) {
                        val visibleUrls = displayedSongs.map(GeneratedTrack::url).toSet()
                        TextButton(
                            onClick = {
                                selectedUrls = if (selectedUrls.containsAll(visibleUrls) && visibleUrls.isNotEmpty()) {
                                    emptySet()
                                } else {
                                    visibleUrls
                                }
                            },
                        ) {
                            Text(stringResource(R.string.local_select_all_cd))
                        }
                    } else {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                isSearchActive = !isSearchActive
                                if (!isSearchActive) searchQuery = ""
                            },
                        ) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = stringResource(R.string.pd_search_cd),
                                tint = if (isSearchActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (detailRoute == null) {
                            IconButton(onClick = { showSourcesSheet = true }) {
                                Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.local_manage_sources_cd))
                            }
                        }
                    }
                }
            }
        }

        // Multi-Select Floating Action Bar
        AnimatedVisibility(
            visible = selectionActive,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp)
                .padding(bottom = FloatingNavDefaults.contentBottomPadding() + 8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                tonalElevation = 6.dp,
                shadowElevation = 10.dp,
                modifier = Modifier.fillMaxWidth().adaptiveContentWidth(maxWidth = 640.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LocalSelectionActionChip(
                        icon = Icons.Filled.PlayArrow,
                        label = stringResource(R.string.local_play_next),
                        onClick = {
                            viewModel.playNext(selectedTracks)
                            Toast.makeText(context, "${selectedTracks.size} queued next", Toast.LENGTH_SHORT).show()
                            selectedUrls = emptySet()
                        },
                    )
                    LocalSelectionActionChip(
                        icon = Icons.Filled.Favorite,
                        label = stringResource(R.string.local_like_offline),
                        onClick = {
                            viewModel.setAllOfflineLiked(selectedTracks, true)
                            selectedUrls = emptySet()
                        },
                    )
                    LocalSelectionActionChip(
                        icon = Icons.Filled.PlaylistAdd,
                        label = stringResource(R.string.local_tab_playlists),
                        onClick = { batchOfflinePlaylistTargetTracks = selectedTracks },
                    )
                    LocalSelectionActionChip(
                        icon = Icons.Filled.DeleteOutline,
                        label = stringResource(R.string.local_remove_from_library),
                        danger = true,
                        onClick = {
                            val activePlId = (detailRoute as? LocalDetailRoute.OfflinePlaylistDetail)?.playlistId
                            if (activePlId != null) {
                                selectedTracks.forEach { t ->
                                    viewModel.removeTrackFromOfflinePlaylist(activePlId, t.url)
                                }
                            } else if (detailRoute == LocalDetailRoute.OfflineLikes) {
                                viewModel.setAllOfflineLiked(selectedTracks, false)
                            } else {
                                viewModel.removeTracksFromLibrary(selectedTracks)
                            }
                            selectedUrls = emptySet()
                        },
                    )
                }
            }
        }
    }

    // Track Context Menu Sheet
    menuTrack?.let { track ->
        val activePlId = (detailRoute as? LocalDetailRoute.OfflinePlaylistDetail)?.playlistId
        TrackContextMenuSheet(
            target = TrackMenuTarget.Track(track.name, track.artist, track.url),
            capabilities = TrackMenuCapabilities(showCopyActions = true, showDeleteScrobble = false),
            playableTrack = track.toPlayableTrack(),
            playbackSourceLabel = sourceLabel,
            onDismiss = { menuTrack = null },
            onRemoveFromPlaylist = when {
                activePlId != null -> {
                    { viewModel.removeTrackFromOfflinePlaylist(activePlId, track.url) }
                }
                detailRoute == LocalDetailRoute.OfflineLikes -> {
                    { viewModel.setAllOfflineLiked(listOf(track), false) }
                }
                else -> {
                    { viewModel.removeTracksFromLibrary(listOf(track)) }
                }
            },
        )
    }

    // Folders & Auto-Scan Settings Bottom Sheet
    if (showSourcesSheet) {
        LocalSourcesBottomSheet(
            autoScanEnabled = autoScanEnabled,
            filterNonMusic = filterNonMusic,
            hasPermission = hasAudioPermission,
            runningImport = runningImport,
            safFolders = safFolders,
            storageBytes = storageBytes,
            folderLabel = viewModel::folderLabel,
            onDismiss = { showSourcesSheet = false },
            onToggleAutoScan = { wantOn ->
                if (wantOn && !hasAudioPermission) {
                    permissionLauncher.launch(requiredAudioPermission())
                } else {
                    viewModel.setAutoScanEnabled(wantOn)
                    if (wantOn) viewModel.rescan()
                }
            },
            onToggleFilterNonMusic = viewModel::setFilterNonMusicAudio,
            onPickFolder = { folderPicker.launch(null) },
            onRescan = viewModel::rescan,
            onForgetFolder = viewModel::forgetFolder,
        )
    }

    // Batch Add to Offline Playlist Bottom Sheet
    batchOfflinePlaylistTargetTracks?.let { tracksToAdd ->
        BatchOfflinePlaylistSheet(
            tracks = tracksToAdd,
            playlists = offlinePlaylists,
            onDismiss = { batchOfflinePlaylistTargetTracks = null },
            onPickPlaylist = { playlistId ->
                viewModel.addTracksToOfflinePlaylist(playlistId, tracksToAdd)
                Toast.makeText(context, "Added ${tracksToAdd.size} tracks", Toast.LENGTH_SHORT).show()
                batchOfflinePlaylistTargetTracks = null
                selectedUrls = emptySet()
            },
            onRequestCreateNew = {
                createPlaylistSeedTracks = tracksToAdd
                batchOfflinePlaylistTargetTracks = null
                showCreatePlaylistDialog = true
            },
        )
    }

    // Create Offline Playlist Dialog
    if (showCreatePlaylistDialog) {
        var nameInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                showCreatePlaylistDialog = false
                createPlaylistSeedTracks = emptyList()
            },
            title = { Text(stringResource(R.string.local_new_playlist)) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text(stringResource(R.string.local_playlist_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = nameInput.isNotBlank(),
                    onClick = {
                        viewModel.createOfflinePlaylist(nameInput.trim(), createPlaylistSeedTracks)
                        showCreatePlaylistDialog = false
                        createPlaylistSeedTracks = emptyList()
                        selectedUrls = emptySet()
                    },
                ) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCreatePlaylistDialog = false
                        createPlaylistSeedTracks = emptyList()
                    },
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // Rename Offline Playlist Dialog
    renamingPlaylist?.let { target ->
        var nameInput by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renamingPlaylist = null },
            title = { Text(stringResource(R.string.local_rename_playlist)) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text(stringResource(R.string.local_playlist_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = nameInput.isNotBlank(),
                    onClick = {
                        viewModel.renameOfflinePlaylist(target.id, nameInput.trim())
                        renamingPlaylist = null
                    },
                ) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingPlaylist = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // Delete Offline Playlist Confirmation
    deletingPlaylist?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingPlaylist = null },
            title = { Text(stringResource(R.string.local_delete_playlist)) },
            text = { Text(stringResource(R.string.local_delete_playlist_confirm, target.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteOfflinePlaylist(target.id)
                        if ((detailRoute as? LocalDetailRoute.OfflinePlaylistDetail)?.playlistId == target.id) {
                            detailRoute = null
                        }
                        deletingPlaylist = null
                    },
                ) {
                    Text(stringResource(R.string.local_delete_playlist), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingPlaylist = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun LocalHeroHeader(
    detailRoute: LocalDetailRoute?,
    title: String,
    tracks: List<GeneratedTrack>,
    safFolderCount: Int,
    storageBytes: Long,
    playbackIsActiveFromSource: Boolean,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onRenameOfflinePlaylist: (() -> Unit)?,
    onDeleteOfflinePlaylist: (() -> Unit)?,
) {
    val totalMs = remember(tracks) { tracks.sumOf { (it.durationMs ?: 0L).coerceAtLeast(0L) } }
    val representativeTrack = tracks.firstOrNull { !it.artworkUrl.isNullOrBlank() }
        ?: tracks.firstOrNull()

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (detailRoute != null) {
                val artModifier = Modifier
                    .size(92.dp)
                    .clip(if (detailRoute is LocalDetailRoute.Artist) CircleShape else RoundedCornerShape(20.dp))
                when (detailRoute) {
                    LocalDetailRoute.OfflineLikes -> Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(92.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(40.dp))
                        }
                    }
                    is LocalDetailRoute.Folder -> Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(92.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(40.dp))
                        }
                    }
                    else -> ArtworkImage(
                        name = representativeTrack?.name ?: title,
                        artist = representativeTrack?.artist ?: "",
                        embeddedUrl = representativeTrack?.artworkUrl,
                        fallbackIcon = when (detailRoute) {
                            is LocalDetailRoute.Album -> Icons.Filled.Album
                            is LocalDetailRoute.Artist -> Icons.Filled.Person
                            else -> Icons.AutoMirrored.Filled.QueueMusic
                        },
                        modifier = artModifier,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                val subtitleParts = buildList {
                    add(pluralStringCountSongs(tracks.size))
                    if (totalMs > 0L) add(formatTotalDuration(totalMs))
                    if (detailRoute == null && safFolderCount > 0) add("$safFolderCount folders")
                    if (detailRoute == null && storageBytes > 0L) add(formatBytes(storageBytes))
                }
                Text(
                    text = subtitleParts.joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (onRenameOfflinePlaylist != null && onDeleteOfflinePlaylist != null) {
                Row {
                    IconButton(onClick = onRenameOfflinePlaylist) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.local_rename_playlist))
                    }
                    IconButton(onClick = onDeleteOfflinePlaylist) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = stringResource(R.string.local_delete_playlist))
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onPlayAll,
                enabled = tracks.isNotEmpty(),
                shape = ExpressivePillShape,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(
                    imageVector = if (playbackIsActiveFromSource) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (playbackIsActiveFromSource) R.string.pd_playing else R.string.pd_play),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            FilledTonalButton(
                onClick = onShuffleAll,
                enabled = tracks.isNotEmpty(),
                shape = ExpressivePillShape,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Shuffle",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun LocalTabPillsRow(
    activeTab: LocalHubTab,
    counts: Map<LocalHubTab, Int>,
    onSelect: (LocalHubTab) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(LocalHubTab.entries, key = { it.name }) { tab ->
            val selected = tab == activeTab
            val count = counts[tab] ?: 0
            Surface(
                onClick = { onSelect(tab) },
                shape = ExpressivePillShape,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(tab.labelRes),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    )
                    if (count > 0) {
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalTrackListRow(
    track: GeneratedTrack,
    position: GroupPosition,
    showTrackNumber: Boolean,
    isPlaying: Boolean,
    isLiked: Boolean,
    isSelected: Boolean,
    selectionActive: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleLike: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val codec = remember(track.url) { formatBadgeForTrack(track) }
    val subtitleParts = buildList {
        if (showTrackNumber && track.trackNumber != null) {
            add("#${track.trackNumber}")
        }
        if (track.artist.isNotBlank()) add(track.artist)
        if (!showTrackNumber && !track.album.isNullOrBlank()) add(track.album)
        track.durationMs?.takeIf { it > 0L }?.let { add(formatDuration(it)) }
    }

    ExpressiveGroupTrackRow(
        title = track.name,
        subtitle = subtitleParts.joinToString(" • ").ifBlank { track.artist },
        position = position,
        isPlaying = isPlaying || isSelected,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        leading = {
            Box(modifier = Modifier.size(52.dp)) {
                ArtworkImage(
                    name = track.name,
                    artist = track.artist,
                    embeddedUrl = track.artworkUrl,
                    fallbackIcon = if (isPlaying) Icons.Filled.GraphicEq else Icons.Filled.MusicNote,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                )
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                } else if (isPlaying) {
                    com.lastwave.app.ui.player.PlayingWaveBars(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).size(24.dp, 18.dp),
                    )
                }
            }
        },
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (codec != null) {
                    LocalCodecPill(codec)
                }
                if (!selectionActive) {
                    IconButton(onClick = onToggleLike, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = stringResource(R.string.local_like_offline),
                            tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    OverflowMenuButton(onClick = onMenu)
                }
            }
        },
    )
}

@Composable
private fun LocalSongGridRow(
    tracks: List<GeneratedTrack>,
    rowOffset: Int,
    allTracks: List<GeneratedTrack>,
    playbackCurrent: com.lastwave.app.playback.PlayableTrack?,
    isPlaying: Boolean,
    offlineLikedUrls: Set<String>,
    selectedUrls: Set<String>,
    selectionActive: Boolean,
    onTrackClick: (Int, GeneratedTrack) -> Unit,
    onTrackLongClick: (GeneratedTrack) -> Unit,
    onToggleLike: (GeneratedTrack) -> Unit,
    onMenu: (GeneratedTrack) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        tracks.forEachIndexed { localIdx, track ->
            val index = rowOffset + localIdx
            val playingThis = isPlaying &&
                playbackCurrent?.title.equals(track.name, ignoreCase = true) &&
                playbackCurrent?.artist.equals(track.artist, ignoreCase = true)
            val liked = LocalOfflineRepository.normalizeUrl(track.url) in offlineLikedUrls
            val selected = track.url in selectedUrls
            LocalSquareGridCard(
                title = track.name,
                subtitle = track.artist,
                trackName = track.name,
                trackArtist = track.artist,
                artworkUrl = track.artworkUrl,
                badge = formatBadgeForTrack(track),
                fallbackIcon = if (playingThis) Icons.Filled.GraphicEq else Icons.Filled.MusicNote,
                selected = selected,
                liked = liked,
                onClick = { onTrackClick(index, track) },
                onLongClick = { onTrackLongClick(track) },
                onToggleLike = { onToggleLike(track) },
                onMenu = if (!selectionActive) { { onMenu(track) } } else null,
                modifier = Modifier.weight(1f),
            )
        }
        if (tracks.size == 1) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun LocalSquareGridCard(
    title: String,
    subtitle: String,
    trackName: String,
    trackArtist: String,
    artworkUrl: String?,
    badge: String?,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    circularArtwork: Boolean = false,
    selected: Boolean = false,
    liked: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onToggleLike: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(if (circularArtwork) CircleShape else RoundedCornerShape(14.dp)),
            ) {
                ArtworkImage(
                    name = trackName,
                    artist = trackArtist,
                    embeddedUrl = artworkUrl,
                    fallbackIcon = fallbackIcon,
                    modifier = Modifier.fillMaxSize(),
                )
                if (badge != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Black.copy(alpha = 0.68f),
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                if (selected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                } else if (onToggleLike != null) {
                    IconButton(
                        onClick = onToggleLike,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .size(32.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                    ) {
                        Icon(
                            imageVector = if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = null,
                            tint = if (liked) MaterialTheme.colorScheme.primary else Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onMenu != null) {
                    IconButton(onClick = onMenu, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalCodecPill(label: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun LocalSelectionActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LocalPermissionCard(
    onGrantPermission: () -> Unit,
    onPickFolder: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.local_permission_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.local_permission_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onGrantPermission, modifier = Modifier.weight(1f), shape = ExpressivePillShape) {
                    Text(stringResource(R.string.local_grant_permission), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = onPickFolder, modifier = Modifier.weight(1f), shape = ExpressivePillShape) {
                    Text(stringResource(R.string.local_choose_folder), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun LocalImportStatusCard(
    state: LocalMusicLibrary.ImportState,
    onAcknowledge: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (state) {
                is LocalMusicLibrary.ImportState.Scanning -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.local_scanning, state.filesFound),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is LocalMusicLibrary.ImportState.Copying -> {
                    val fraction = if (state.total > 0) state.done.toFloat() / state.total else 0f
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.local_copying, state.done, state.total, state.currentName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                is LocalMusicLibrary.ImportState.Finished -> {
                    val r = state.report
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (r.outOfSpace) {
                                Text(
                                    stringResource(R.string.local_report_space),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                Text(
                                    stringResource(R.string.local_report_summary, r.added, r.updated, r.unchanged),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (r.unsupported.isNotEmpty()) {
                                val total = r.unsupported.values.sum()
                                Text(
                                    pluralStringResource(
                                        R.plurals.local_report_unsupported,
                                        total,
                                        total,
                                        r.unsupported.keys.sorted().joinToString(", ") { ".$it" },
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (r.failed > 0) {
                                Text(
                                    stringResource(R.string.local_report_failed, r.failed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (r.unreachableFolders > 0) {
                                Text(
                                    stringResource(R.string.local_report_unreachable, r.unreachableFolders),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        IconButton(onClick = onAcknowledge, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                LocalMusicLibrary.ImportState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun LocalEmptyState(
    message: String,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(44.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (primaryActionLabel != null && onPrimaryAction != null) {
            FilledTonalButton(onClick = onPrimaryAction, shape = ExpressivePillShape) {
                Text(primaryActionLabel)
            }
        }
    }
}

@Composable
private fun LocalSourcesBottomSheet(
    autoScanEnabled: Boolean,
    filterNonMusic: Boolean,
    hasPermission: Boolean,
    runningImport: Boolean,
    safFolders: List<Uri>,
    storageBytes: Long,
    folderLabel: (Uri) -> String,
    onDismiss: () -> Unit,
    onToggleAutoScan: (Boolean) -> Unit,
    onToggleFilterNonMusic: (Boolean) -> Unit,
    onPickFolder: () -> Unit,
    onRescan: () -> Unit,
    onForgetFolder: (Uri) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        EdgeToEdgeDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .adaptiveContentWidth(maxWidth = 640.dp)
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.local_sources_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                stringResource(R.string.local_autoscan_title),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                if (hasPermission) stringResource(R.string.local_autoscan_sub)
                                else stringResource(R.string.local_permission_title),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = autoScanEnabled, onCheckedChange = onToggleAutoScan)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                stringResource(R.string.local_filter_non_music_title),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                stringResource(R.string.local_filter_non_music_sub),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = filterNonMusic, onCheckedChange = onToggleFilterNonMusic)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onPickFolder,
                    enabled = !runningImport,
                    shape = ExpressivePillShape,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.local_choose_folder), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = onRescan,
                    enabled = !runningImport,
                    shape = ExpressivePillShape,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.local_rescan), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (safFolders.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(safFolders, key = { it.toString() }) { folder ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    folderLabel(folder),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { onForgetFolder(folder) },
                                    enabled = !runningImport,
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.local_forget_folder),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (storageBytes > 0L) {
                Text(
                    stringResource(R.string.local_storage_used, formatBytes(storageBytes)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun BatchOfflinePlaylistSheet(
    tracks: List<GeneratedTrack>,
    playlists: List<LocalOfflineRepository.OfflinePlaylist>,
    onDismiss: () -> Unit,
    onPickPlaylist: (String) -> Unit,
    onRequestCreateNew: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        EdgeToEdgeDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .adaptiveContentWidth(maxWidth = 640.dp)
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.local_add_to_offline_playlist),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        pluralStringCountSongs(tracks.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                FilledTonalButton(onClick = onRequestCreateNew, shape = ExpressivePillShape) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.local_new_playlist))
                }
            }

            if (playlists.isEmpty()) {
                Text(
                    stringResource(R.string.local_empty_offline_playlists),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(playlists, key = { it.id }) { pl ->
                        Surface(
                            onClick = { onPickPlaylist(pl.id) },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(pl.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        pluralStringCountSongs(pl.trackUrls.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun checkAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, requiredAudioPermission()) == PackageManager.PERMISSION_GRANTED

private fun requiredAudioPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

private fun sortLocalTracks(
    tracks: List<GeneratedTrack>,
    mode: LocalSortMode,
    ascending: Boolean,
): List<GeneratedTrack> {
    val base = when (mode) {
        LocalSortMode.TITLE -> tracks.sortedBy { it.name.lowercase() }
        LocalSortMode.ARTIST -> tracks.sortedWith(compareBy({ it.artist.lowercase() }, { it.name.lowercase() }))
        LocalSortMode.ALBUM -> tracks.sortedWith(
            compareBy(
                { it.album?.lowercase().orEmpty() },
                { it.discNumber ?: Int.MAX_VALUE },
                { it.trackNumber ?: Int.MAX_VALUE },
                { it.name.lowercase() },
            ),
        )
        LocalSortMode.TRACK_NUMBER -> tracks.sortedWith(
            compareBy(
                { it.discNumber ?: Int.MAX_VALUE },
                { it.trackNumber ?: Int.MAX_VALUE },
                { it.name.lowercase() },
            ),
        )
        LocalSortMode.ADDED -> tracks
        LocalSortMode.FORMAT -> tracks.sortedWith(
            compareBy(
                { formatBadgeForTrack(it).orEmpty() },
                { it.name.lowercase() },
            ),
        )
    }
    return if (ascending) base else base.reversed()
}

private fun albumKeyOf(track: GeneratedTrack): String {
    val album = track.album?.trim().takeIf { !it.isNullOrBlank() } ?: "unknown_album"
    return album.lowercase()
}

private fun artistKeyOf(track: GeneratedTrack): String {
    val artist = track.albumArtist?.trim().takeIf { !it.isNullOrBlank() }
        ?: track.artist.trim().ifBlank { "unknown_artist" }
    return artist.lowercase()
}

private fun buildAlbumGroups(tracks: List<GeneratedTrack>): List<LocalAlbumGroup> {
    val grouped = linkedMapOf<String, MutableList<GeneratedTrack>>()
    for (track in tracks) {
        grouped.getOrPut(albumKeyOf(track)) { mutableListOf() }.add(track)
    }
    return grouped.map { (key, list) ->
        val sorted = list.sortedWith(
            compareBy(
                { it.discNumber ?: Int.MAX_VALUE },
                { it.trackNumber ?: Int.MAX_VALUE },
                { it.name.lowercase() },
            ),
        )
        val displayTitle = sorted.firstNotNullOfOrNull { it.album?.trim()?.takeIf(String::isNotBlank) } ?: "Unknown Album"
        val displayArtist = sorted.firstNotNullOfOrNull {
            it.albumArtist?.trim()?.takeIf(String::isNotBlank) ?: it.artist.trim().takeIf(String::isNotBlank)
        } ?: "Unknown Artist"
        val art = sorted.firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) }
        LocalAlbumGroup(
            key = key,
            title = displayTitle,
            artist = displayArtist,
            artworkUrl = art,
            tracks = sorted,
        )
    }.sortedBy { it.title.lowercase() }
}

private fun buildArtistGroups(tracks: List<GeneratedTrack>): List<LocalArtistGroup> {
    val grouped = linkedMapOf<String, MutableList<GeneratedTrack>>()
    for (track in tracks) {
        grouped.getOrPut(artistKeyOf(track)) { mutableListOf() }.add(track)
    }
    return grouped.map { (key, list) ->
        val sorted = list.sortedBy { it.name.lowercase() }
        val displayName = sorted.firstNotNullOfOrNull {
            it.albumArtist?.trim()?.takeIf(String::isNotBlank) ?: it.artist.trim().takeIf(String::isNotBlank)
        } ?: "Unknown Artist"
        val albumCount = sorted.map { albumKeyOf(it) }.distinct().size
        val art = sorted.firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) }
        LocalArtistGroup(
            key = key,
            name = displayName,
            artworkUrl = art,
            albumCount = albumCount,
            tracks = sorted,
        )
    }.sortedBy { it.name.lowercase() }
}

private fun buildFolderGroups(
    tracks: List<GeneratedTrack>,
    safFolders: List<Uri>,
    trackFolders: Map<String, String>,
    labelResolver: (Uri) -> String,
): List<LocalFolderGroup> {
    val safByStr = safFolders.associateBy { it.toString() }
    val grouped = linkedMapOf<String, MutableList<GeneratedTrack>>()
    for (track in tracks) {
        val safKey = trackFolders[track.url]?.takeIf { it in safByStr }
        val groupKey = when {
            safKey != null -> "saf:$safKey"
            track.url.startsWith("/") || track.url.startsWith("file://") -> {
                val parent = File(track.url.removePrefix("file://")).parentFile?.absolutePath ?: "device"
                "dir:$parent"
            }
            else -> "mediastore"
        }
        grouped.getOrPut(groupKey) { mutableListOf() }.add(track)
    }
    // Include registered empty SAF folders as well so user can manage/forget them
    for (saf in safFolders) {
        grouped.getOrPut("saf:$saf") { mutableListOf() }
    }
    return grouped.map { (key, list) ->
        when {
            key.startsWith("saf:") -> {
                val uriStr = key.removePrefix("saf:")
                val uri = safByStr[uriStr]
                val label = uri?.let(labelResolver) ?: uriStr
                val shortName = label.substringAfterLast('/').ifBlank { label }
                LocalFolderGroup(
                    key = key,
                    title = shortName,
                    subtitle = label,
                    safUri = uri,
                    tracks = list,
                )
            }
            key.startsWith("dir:") -> {
                val dirPath = key.removePrefix("dir:")
                val shortName = File(dirPath).name.ifBlank { dirPath }
                LocalFolderGroup(
                    key = key,
                    title = shortName,
                    subtitle = dirPath,
                    safUri = null,
                    tracks = list,
                )
            }
            else -> LocalFolderGroup(
                key = key,
                title = "Device Audio",
                subtitle = "MediaStore",
                safUri = null,
                tracks = list,
            )
        }
    }.sortedBy { it.title.lowercase() }
}

internal fun formatBadgeForTrack(track: GeneratedTrack): String? {
    return when (val ext = LocalAudioFormats.extensionOf(track.url)) {
        "" -> null
        "dsf", "dff" -> "DSD"
        "aif", "aiff", "aifc" -> "AIFF"
        "ec3", "eac3" -> "E-AC-3"
        else -> ext.uppercase()
    }
}

private fun formatTotalDuration(totalMs: Long): String {
    val totalMinutes = (totalMs / 60_000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    val mins = totalMinutes % 60L
    return if (hours > 0L) "${hours}h ${mins}m" else "$totalMinutes min"
}

private fun pluralStringCountSongs(count: Int): String =
    if (count == 1) "1 song" else "$count songs"
