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
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
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
import androidx.compose.runtime.setValue
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
import com.lastwave.app.data.playlist.LOCAL_SONGS_MODE
import com.lastwave.app.data.playlist.isYouTubeOnly
import com.lastwave.app.playback.toPlayableTrack
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.PlaylistCover
import com.lastwave.app.ui.common.TopCurvedSectionClipShape
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
    onOpenFeedPlaylist: (String) -> Unit = {},
    onOpenGenerator: () -> Unit = {},
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val musicPlayer = com.lastwave.app.ui.player.LocalMusicPlayer.current
    val playbackState by musicPlayer.listPlaybackState.collectAsStateWithLifecycle()
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
                subtitle = if (selectedPlaylists.isNotEmpty()) {
                    stringResource(R.string.pl_select_more_hint)
                } else {
                    val totalPlaylistsCount = state.playlists.size + (if (state.isSpotifySyncEnabled) state.spotifyPlaylists.size else 0)
                    val hasUnknownSpotifyTracks = state.isSpotifySyncEnabled &&
                        state.spotifyPlaylists.any { it.id != "liked" && it.trackCount == null }
                    if (state.playlists.any { it.isYouTubeOnly && it.remoteTrackCount == null } || hasUnknownSpotifyTracks) {
                        stringResource(R.string.pl_count_only, totalPlaylistsCount)
                    } else {
                        val totalTracks = state.playlists.sumOf { it.remoteTrackCount ?: it.tracks.size } +
                            (if (state.isSpotifySyncEnabled) state.spotifyPlaylists.sumOf { it.trackCount ?: 0 } else 0)
                        stringResource(R.string.pl_count_tracks, totalPlaylistsCount, totalTracks)
                    }
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
                    } else {
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

            val localSongsPlaylist = remember(state.playlists, searchQuery) {
                state.playlists.firstOrNull {
                    it.mode == LOCAL_SONGS_MODE && it.matchesPlaylistSearch(searchQuery)
                }
            }

            val filteredPlaylists = remember(state.playlists, searchQuery) {
                state.playlists.filter {
                    it.mode != LOCAL_SONGS_MODE && it.matchesPlaylistSearch(searchQuery)
                }
            }

            val filteredSpotifyPlaylists = remember(state.spotifyPlaylists, searchQuery) {
                if (searchQuery.isBlank()) state.spotifyPlaylists
                else state.spotifyPlaylists.filter {
                    it.name.contains(searchQuery, ignoreCase = true)
                }
            }

            val effectiveSpotify = if (state.isSpotifySyncEnabled) filteredSpotifyPlaylists else emptyList()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            ) {
                when {
                    state.isLoading && state.playlists.isEmpty() && !state.isGenerating -> LoadingState()
                    localSongsPlaylist == null && filteredPlaylists.isEmpty() && !state.isGenerating && effectiveSpotify.isEmpty() -> EmptyState()
                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = FloatingNavDefaults.contentBottomPadding()),
                        verticalArrangement = Arrangement.spacedBy(com.lastwave.app.ui.common.GroupGap),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(TopCurvedSectionClipShape(horizontalInset = 16.dp, cornerRadius = 28.dp)),
                    ) {
                        if (localSongsPlaylist != null) {
                            val hasPlaylistsBelow = state.isGenerating ||
                                state.justSavedBannerVisible ||
                                effectiveSpotify.isNotEmpty() ||
                                filteredPlaylists.isNotEmpty()
                            item(key = "local_songs_hero", contentType = "local_songs_hero") {
                                Box(
                                    modifier = Modifier
                                        .animateItem()
                                        .then(if (hasPlaylistsBelow) Modifier.padding(bottom = 13.dp) else Modifier),
                                ) {
                                    LocalSongsHeroCard(
                                        playlist = localSongsPlaylist,
                                        isPlaying = playbackState.isPlaying,
                                        playbackSource = playbackState.sourceLabel,
                                        onClick = { onOpenPlaylist(localSongsPlaylist.id) },
                                        onEditCover = { coverEditorPlaylistId = localSongsPlaylist.id },
                                        onPlay = {
                                            musicPlayer.playQueue(
                                                localSongsPlaylist.tracks.map(GeneratedTrack::toPlayableTrack),
                                                startIndex = 0,
                                                sourceLabel = localSongsPlaylist.title,
                                                enableRadioContinuation = false,
                                            )
                                        },
                                    )
                                }
                            }
                        }

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

                        val groupedPlaylistCount = effectiveSpotify.size + filteredPlaylists.size

                        if (effectiveSpotify.isNotEmpty()) {
                            itemsIndexed(
                                items = effectiveSpotify,
                                key = { _, pl -> "sp_${pl.id}" },
                            ) { index, pl ->
                                Box(Modifier.animateItem()) {
                                    SpotifyPlaylistCard(
                                        playlist = pl,
                                        position = com.lastwave.app.ui.common.groupPositionFor(index, groupedPlaylistCount),
                                        onClick = {
                                            val routeId = if (pl.id == "liked") "sp_liked" else "sp_${pl.id}"
                                            onOpenFeedPlaylist(routeId)
                                        },
                                    )
                                }
                            }
                        }

                        itemsIndexed(
                            items = filteredPlaylists,
                            key = { _, playlist -> "pl_${playlist.id}_${playlist.remotePlaylistId ?: "local"}" },
                        ) { index, playlist ->
                            val isNewest = playlist.id == state.newestId
                            val globalIndex = effectiveSpotify.size + index
                            Box(Modifier.animateItem()) {
                                PlaylistCard(
                                    playlist = playlist,
                                    isNewest = isNewest,
                                    position = com.lastwave.app.ui.common.groupPositionFor(globalIndex, groupedPlaylistCount),
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

private fun SavedPlaylist.matchesPlaylistSearch(query: String): Boolean =
    query.isBlank() ||
        title.contains(query, ignoreCase = true) ||
        tracks.any { t ->
            t.name.contains(query, ignoreCase = true) ||
                t.artist.contains(query, ignoreCase = true)
        }
