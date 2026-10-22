package com.lastwave.app.ui.playlist

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.lastwave.app.data.favorites.FavoriteKind
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.lastwave.app.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.data.playlist.LIKED_SONGS_MODE
import com.lastwave.app.data.playlist.isYouTubeOnly
import com.lastwave.app.playback.toPlayableTrack
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.PlaylistCover
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.shell.FloatingNavDefaults
import com.lastwave.app.ui.theme.ExpressivePillShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Faithful port of playlist.js's saved-playlists screen (§4): card list
 * backed by Room via PlaylistViewModel, expand/collapse with lazy track
 * rendering, the "just generated" regenerate bar, export bottom sheet
 * (CSV/M3U), Generate Similar, and delete — plus the shared track context
 * menu (§1.7) with Copy + Delete Scrobble enabled (this screen's full
 * capability set, matching the original's playlist.js menu exactly).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    onOpenPlaylist: (Long) -> Unit = {},
    onOpenGenerator: () -> Unit = {},
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val musicPlayer = com.lastwave.app.ui.player.LocalMusicPlayer.current
    val playbackState by musicPlayer.chromeState.collectAsStateWithLifecycle()
    val addToPlaylist = com.lastwave.app.ui.player.LocalAddToPlaylist.current
    var searchQuery by remember { mutableStateOf("") }
    var coverEditorPlaylistId by remember { mutableStateOf<Long?>(null) }
    var coverPickerPlaylistId by remember { mutableStateOf<Long?>(null) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val playlistId = coverPickerPlaylistId
        if (uri != null && playlistId != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.setCustomCover(playlistId, uri.toString())
        }
        coverPickerPlaylistId = null
    }

    // Re-reads from Room whenever this tab regains visibility — this is how
    // a playlist just saved by Generate shows up here without polling.
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }

    var menuTarget by remember { mutableStateOf<Pair<Long, GeneratedTrack>?>(null) }
    var selectedPlaylists by remember { mutableStateOf(setOf<Long>()) }
    var showMultipleDeleteConfirm by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.PLAYLISTS) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 860.dp)
                .align(Alignment.TopCenter),
        ) {
            var sortMenuExpanded by remember { mutableStateOf(false) }
            ExpressiveHeader(
                title = if (selectedPlaylists.isNotEmpty()) stringResource(R.string.pl_selected_count, selectedPlaylists.size) else stringResource(R.string.pl_title),
                subtitle = if (filter != LibraryFilter.PLAYLISTS) null else if (selectedPlaylists.isNotEmpty()) stringResource(R.string.pl_select_more_hint) else if (state.playlists.any { it.isYouTubeOnly && it.remoteTrackCount == null }) {
                    stringResource(R.string.pl_count_only, state.playlists.size)
                } else {
                    stringResource(R.string.pl_count_tracks, state.playlists.size, state.playlists.sumOf { it.remoteTrackCount ?: it.tracks.size })
                },
                actions = {
                    if (selectedPlaylists.isNotEmpty()) {
                        IconButton(
                            onClick = { showMultipleDeleteConfirm = true },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.pl_delete_selected_cd), tint = MaterialTheme.colorScheme.error)
                        }
                        IconButton(
                            onClick = { selectedPlaylists = setOf() },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.pl_clear_selection_cd))
                        }
                    } else if (filter == LibraryFilter.PLAYLISTS) {
                        IconButton(
                            onClick = viewModel::openCreateDialog,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.pl_create_cd))
                        }
                        Box {
                            Surface(
                                onClick = { sortMenuExpanded = true },
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                tonalElevation = 1.dp,
                                modifier = Modifier.heightIn(min = 34.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Sort,
                                        contentDescription = stringResource(R.string.pl_sort_cd),
                                        modifier = Modifier.size(15.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        stringResource(R.string.pl_sort_label),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false },
                                shape = RoundedCornerShape(22.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = 4.dp,
                                shadowElevation = 10.dp,
                                modifier = Modifier.padding(vertical = 4.dp),
                            ) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.pl_sort_newest)) }, onClick = { viewModel.setSortMode(PlaylistSortMode.DATE_DESC); sortMenuExpanded = false })
                                DropdownMenuItem(text = { Text(stringResource(R.string.pl_sort_oldest)) }, onClick = { viewModel.setSortMode(PlaylistSortMode.DATE_ASC); sortMenuExpanded = false })
                                DropdownMenuItem(text = { Text(stringResource(R.string.pl_sort_name)) }, onClick = { viewModel.setSortMode(PlaylistSortMode.NAME); sortMenuExpanded = false })
                                DropdownMenuItem(text = { Text(stringResource(R.string.pl_sort_track_count)) }, onClick = { viewModel.setSortMode(PlaylistSortMode.TRACK_COUNT); sortMenuExpanded = false })
                            }
                        }
                    }
                },
            )

            // Search Bar + Generator button matching Downloads tab design
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            "Search playlists...",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = "Search", modifier = Modifier.size(20.dp))
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
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

                FilledTonalButton(
                    onClick = onOpenGenerator,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.height(52.dp),
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = "Generator",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Generator",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            val filteredPlaylists = remember(state.playlists, searchQuery) {
                if (searchQuery.isBlank()) state.playlists
                else state.playlists.filter {
                    it.title.contains(searchQuery, ignoreCase = true) ||
                        it.tracks.any { t -> t.name.contains(searchQuery, ignoreCase = true) || t.artist.contains(searchQuery, ignoreCase = true) }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                LibraryFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option; selectedPlaylists = setOf() },
                        label = { Text(stringResource(option.labelRes)) },
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            ) {
                when {
                    filter == LibraryFilter.ALBUMS -> FavoritesList(FavoriteKind.ALBUM)
                    filter == LibraryFilter.ARTISTS -> FavoritesList(FavoriteKind.ARTIST)
                    state.isLoading && state.playlists.isEmpty() && !state.isGenerating -> LoadingState()
                    filteredPlaylists.isEmpty() && !state.isGenerating -> EmptyState()
                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = FloatingNavDefaults.contentBottomPadding()),
                        verticalArrangement = Arrangement.spacedBy(com.lastwave.app.ui.common.GroupGap),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.isGenerating) {
                            item(key = "generationProgress", contentType = "generationProgress") {
                                com.lastwave.app.ui.common.GenerationProgressCard(message = state.generatingMessage)
                            }
                        }

                        if (state.justSavedBannerVisible) {
                            item(key = "banner") {
                                LaunchedEffect(state.justSavedBannerVisible) {
                                    kotlinx.coroutines.delay(3000)
                                    viewModel.dismissJustSavedBanner()
                                }
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "Playlist saved!",
                                        modifier = Modifier.padding(14.dp),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }

                        itemsIndexed(
                            items = filteredPlaylists,
                            key = { _, playlist -> "pl_${playlist.id}_${playlist.remotePlaylistId ?: "local"}" },
                        ) { index, playlist ->
                            val isNewest = playlist.id == state.newestId
                            Box(Modifier.animateItem()) {
                                PlaylistCard(
                                    playlist = playlist,
                                    isNewest = isNewest,
                                    position = com.lastwave.app.ui.common.groupPositionFor(index, filteredPlaylists.size),
                                    isRegenerating = state.regeneratingId == playlist.id,
                                    currentTrack = playbackState.current,
                                    isPlaying = playbackState.isPlaying,
                                    playbackSource = playbackState.sourceLabel,
                                    onClick = {
                                        if (selectedPlaylists.isNotEmpty()) {
                                            selectedPlaylists = if (selectedPlaylists.contains(playlist.id)) selectedPlaylists - playlist.id else selectedPlaylists + playlist.id
                                        } else {
                                            onOpenPlaylist(playlist.id)
                                        }
                                    },
                                    onExport = { viewModel.openExportSheet(playlist.id) },
                                    onRename = { viewModel.requestRename(playlist.id) },
                                    onEditCover = { coverEditorPlaylistId = playlist.id },
                                    onTogglePin = { viewModel.togglePinned(playlist.id) },
                                    onDownload = { viewModel.downloadPlaylist(playlist.id) },
                                    onRegenerate = {
                                        viewModel.regenerate(playlist.id) { newId ->
                                            onOpenPlaylist(newId)
                                        }
                                    },
                                    onDelete = { viewModel.requestDelete(playlist.id) },
                                    onMakeLocal = { viewModel.makeLocal(playlist.id) },
                                    onPlay = {
                                        musicPlayer.playQueue(
                                            playlist.tracks.map(GeneratedTrack::toPlayableTrack),
                                            startIndex = 0,
                                            sourceLabel = playlist.title,
                                        )
                                    },
                                    isSelected = selectedPlaylists.contains(playlist.id),
                                    onLongClick = {
                                        selectedPlaylists = if (selectedPlaylists.contains(playlist.id)) selectedPlaylists - playlist.id else selectedPlaylists + playlist.id
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showMultipleDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showMultipleDeleteConfirm = false },
                title = { Text(stringResource(R.string.pl_delete_multi_title)) },
                text = { Text(stringResource(R.string.pl_delete_multi_text, selectedPlaylists.size)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteMultiple(selectedPlaylists)
                            selectedPlaylists = setOf()
                            showMultipleDeleteConfirm = false
                        }
                    ) {
                        Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showMultipleDeleteConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
                },
            )
        }

        state.toastMessage?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(3000)
                viewModel.dismissToast()
            }
            Surface(
                shape = ExpressivePillShape,
                color = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = FloatingNavDefaults.contentBottomPadding() + 12.dp),
            ) {
                Text(msg, color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }

    // ── Export bottom sheet (§4.6) ──
    state.exportSheetForPlaylistId?.let { id ->
        ExportBottomSheet(
            onDismiss = viewModel::dismissExportSheet,
            onSaveCsv = { viewModel.exportSave(id, ExportFormat.CSV) },
            onSaveM3u = { viewModel.exportSave(id, ExportFormat.M3U) },
            onShareCsv = { viewModel.exportShare(id, ExportFormat.CSV) },
            onShareM3u = { viewModel.exportShare(id, ExportFormat.M3U) },
        )
    }

    // ── Delete confirm dialog ──
    if (state.deleteConfirmForPlaylistId != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteConfirm,
            title = { Text(stringResource(R.string.pl_delete_single_title)) },
            text = { Text(stringResource(R.string.pl_delete_undone)) },
            confirmButton = { TextButton(onClick = viewModel::confirmDelete) { Text(stringResource(R.string.common_delete)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissDeleteConfirm) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    // ── Delete-scrobble authorization-required dialog ──
    if (state.createDialogVisible) {
        var title by remember(state.createDialogVisible) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = viewModel::dismissCreateDialog,
            title = { Text(stringResource(R.string.pl_create_title)) },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.pl_name_label)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.createCustomPlaylist(title) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.common_create)) }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissCreateDialog) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    state.renamePlaylistId?.let { playlistId ->
        val playlist = state.playlists.firstOrNull { it.id == playlistId }
        var title by remember(playlistId) { mutableStateOf(playlist?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = viewModel::dismissRename,
            title = { Text(stringResource(R.string.pl_rename_title)) },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.pl_name_label)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.renamePlaylist(title) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.common_rename)) }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissRename) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    coverEditorPlaylistId?.let { playlistId ->
        state.playlists.firstOrNull { it.id == playlistId }?.let { playlist ->
            AlertDialog(
                onDismissRequest = { coverEditorPlaylistId = null },
                title = { Text(stringResource(R.string.pl_cover_title)) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PlaylistCover(playlist = playlist, modifier = Modifier.size(132.dp), cornerRadius = 26.dp)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            if (playlist.customCoverUri.isNullOrBlank()) {
                                stringResource(R.string.pl_cover_auto_desc)
                            } else {
                                stringResource(R.string.pl_cover_custom_desc)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            coverPickerPlaylistId = playlist.id
                            coverEditorPlaylistId = null
                            coverPicker.launch(arrayOf("image/*"))
                        },
                    ) {
                        Text(if (playlist.customCoverUri.isNullOrBlank()) stringResource(R.string.pl_choose_image) else stringResource(R.string.pl_change_image))
                    }
                },
                dismissButton = {
                    Row {
                        if (!playlist.customCoverUri.isNullOrBlank()) {
                            TextButton(
                                onClick = {
                                    viewModel.setCustomCover(playlist.id, null)
                                    coverEditorPlaylistId = null
                                },
                            ) { Text(stringResource(R.string.pl_use_automatic)) }
                        }
                        TextButton(onClick = { coverEditorPlaylistId = null }) { Text(stringResource(R.string.common_done)) }
                    }
                },
            )
        }
    }

    if (state.deleteScrobbleAuthRequired) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteScrobbleAuthRequired,
            title = { Text(stringResource(R.string.pl_scrobble_auth_title)) },
            text = { Text(stringResource(R.string.pl_scrobble_auth_text)) },
            confirmButton = { TextButton(onClick = viewModel::dismissDeleteScrobbleAuthRequired) { Text(stringResource(R.string.pl_scrobble_auth_ok)) } },
        )
    }

    // ── Shared track context menu (§1.7) ──
    menuTarget?.let { (playlistId, track) ->
        TrackContextMenuSheet(
            target = TrackMenuTarget.Track(track.name, track.artist, track.url),
            capabilities = TrackMenuCapabilities(showCopyActions = true, showDeleteScrobble = true),
            playbackSourceLabel = state.playlists.firstOrNull { it.id == playlistId }?.title ?: "Playlists",
            onDismiss = { menuTarget = null },
            onDeleteScrobble = { name, artist -> viewModel.deleteScrobble(name, artist) },
            onRefreshArtwork = { viewModel.refreshArtwork(track.name, track.artist) },
        )
    }
}

@Composable
private fun CountPill(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.heightIn(min = 34.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxHeight()) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        com.lastwave.app.ui.common.ExpressiveLoadingIndicator()
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = ExpressivePillShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(96.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.pl_empty), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.pl_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlaylistCard(
    playlist: SavedPlaylist,
    isNewest: Boolean,
    position: com.lastwave.app.ui.common.GroupPosition,
    isRegenerating: Boolean,
    currentTrack: com.lastwave.app.playback.PlayableTrack?,
    isPlaying: Boolean,
    playbackSource: String,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onEditCover: () -> Unit,
    onTogglePin: () -> Unit,
    onDownload: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onMakeLocal: () -> Unit,
    onPlay: () -> Unit,
    isSelected: Boolean = false,
    onLongClick: () -> Unit = {},
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val isThisPlaylistPlaying = isPlaying && playbackSource == playlist.title
    var menuExpanded by remember { mutableStateOf(false) }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // Hoisted above the tracks-empty branch: remember calls must run
    // unconditionally so slot-table positions stay stable when YTM sync
    // flips tracks between empty and non-empty.
    val playInteractionSource = remember { MutableInteractionSource() }
    val isPlayPressed by playInteractionSource.collectIsPressedAsState()
    val playScale by animateFloatAsState(
        targetValue = if (isPlayPressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "playScale",
    )
    val cardScale by animateFloatAsState(
        targetValue = if (isPressed) 0.975f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "cardPress",
    )
    val cardBackground by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            isThisPlaylistPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(350),
        label = "cardBg",
    )

    Card(
        shape = com.lastwave.app.ui.common.groupShape(position),
        colors = CardDefaults.cardColors(containerColor = cardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onClick()
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                )
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(60.dp)) {
                PlaylistCover(playlist = playlist, modifier = Modifier.fillMaxSize(), cornerRadius = 14.dp)
                if (isThisPlaylistPlaying) {
                    com.lastwave.app.ui.player.PlayingWaveBars(
                        Modifier.align(Alignment.BottomEnd).padding(4.dp).size(24.dp, 18.dp),
                    )
                }
                if (isRegenerating) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.62f),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator(
                                size = 26.dp,
                                color = androidx.compose.ui.graphics.Color.White,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        playlist.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (playlist.isPinned) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = stringResource(R.string.pl_pinned_cd),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    if (playlist.isYouTubeOnly) {
                        "YouTube Music • ${playlist.remoteTrackCount?.let { stringResource(R.string.pl_yt_tracks, it) } ?: stringResource(R.string.pl_yt_connected)}"
                    } else {
                        stringResource(R.string.pl_subtitle_local, playlist.tracks.size, formatDate(playlist.createdAtMillis))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Quick play button
            if (playlist.tracks.isNotEmpty()) {
                Surface(
                    onClick = onPlay,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    interactionSource = playInteractionSource,
                    modifier = Modifier
                        .size(38.dp)
                        .graphicsLayer {
                            scaleX = playScale
                            scaleY = playScale
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.pl_play_cd),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
            }

            // More options menu
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.pl_options_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    shape = RoundedCornerShape(22.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 4.dp,
                    shadowElevation = 10.dp,
                ) {
                    DropdownMenuItem(
                        text = { Text(if (playlist.isPinned) stringResource(R.string.pl_unpin) else stringResource(R.string.pl_pin)) },
                        leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                        onClick = { onTogglePin(); menuExpanded = false },
                    )
                    val isLocalSongs = playlist.mode == com.lastwave.app.data.playlist.LOCAL_SONGS_MODE
                    if (!isLocalSongs) DropdownMenuItem(
                        text = { Text(stringResource(R.string.pl_download_all)) },
                        leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                        onClick = { onDownload(); menuExpanded = false },
                    )
                    if (playlist.isYouTubeOnly) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pl_make_local)) },
                            leadingIcon = { Icon(Icons.Filled.BookmarkAdd, contentDescription = null) },
                            onClick = { onMakeLocal(); menuExpanded = false },
                        )
                    } else {
                        if (playlist.mode != LIKED_SONGS_MODE && !isLocalSongs) {
                            DropdownMenuItem(
                                text = { Text(if (isRegenerating) stringResource(R.string.pl_regenerating) else stringResource(R.string.pl_regenerate)) },
                                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                                enabled = !isRegenerating,
                                onClick = { onRegenerate(); menuExpanded = false },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pl_change_cover)) },
                            leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                            onClick = { onEditCover(); menuExpanded = false },
                        )
                        if (playlist.mode != LIKED_SONGS_MODE) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_rename)) },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = { onRename(); menuExpanded = false },
                            )
                        }
                    }
                    if (!playlist.isYouTubeOnly) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_export)) },
                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                            onClick = { onExport(); menuExpanded = false },
                        )
                    }
                    if (!isLocalSongs) DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { onDelete(); menuExpanded = false },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExportBottomSheet(
    onDismiss: () -> Unit,
    onSaveCsv: () -> Unit,
    onSaveM3u: () -> Unit,
    onShareCsv: () -> Unit,
    onShareM3u: () -> Unit,
) {
    var selected by remember { mutableStateOf(ExportFormat.CSV) }
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.pl_export_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            FormatOptionRow(
                title = "CSV",
                description = stringResource(R.string.pl_format_csv_desc),
                selected = selected == ExportFormat.CSV,
                onClick = { selected = ExportFormat.CSV },
            )
            Spacer(Modifier.height(8.dp))
            FormatOptionRow(
                title = "M3U",
                description = stringResource(R.string.pl_format_m3u_desc),
                selected = selected == ExportFormat.M3U,
                onClick = { selected = ExportFormat.M3U },
            )

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_cancel)) }
                TextButton(
                    onClick = { if (selected == ExportFormat.CSV) onSaveCsv() else onSaveM3u() },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.common_save)) }
                TextButton(
                    onClick = { if (selected == ExportFormat.CSV) onShareCsv() else onShareM3u() },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.common_share)) }
            }
        }
    }
}

@Composable
private fun FormatOptionRow(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))
