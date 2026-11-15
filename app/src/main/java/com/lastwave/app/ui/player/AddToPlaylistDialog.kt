@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.lastwave.app.ui.player

import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import com.lastwave.app.data.playlist.isLikedIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.combinedClickable
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lastwave.app.ui.common.PredictiveBackScreen
import com.lastwave.app.playback.haptics.MusicHapticsConfig
import com.lastwave.app.playback.haptics.MusicHapticsEngineMode
import com.lastwave.app.playback.haptics.MusicHapticsIntensity
import com.lastwave.app.playback.haptics.MusicHapticsMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.lerp
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.isTabletOrWideScreen
import com.lastwave.app.ui.theme.LocalMd3NavBar
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import androidx.media3.common.Player
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.lyrics.LyricsRepository
import com.lastwave.app.data.lyrics.LyricsResult
import com.lastwave.app.data.lyrics.LyricsSyncType
import kotlinx.coroutines.flow.update
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.data.playlist.LIKED_SONGS_MODE
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlaybackChromeState
import com.lastwave.app.playback.formatSampleRateKHz
import com.lastwave.app.playback.withoutProgress
import com.lastwave.app.playback.isSpatialAudioCodec
import com.lastwave.app.playback.qualityBadgeLabel
import com.lastwave.app.playback.SignalPathReport
import com.lastwave.app.playback.PlaybackProgressState
import androidx.hilt.navigation.compose.hiltViewModel
import com.lastwave.app.data.artwork.ArtworkNormalizer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.toGeneratedTrack
import com.lastwave.app.playback.resolve.MetadataLog
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ArtworkViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import androidx.compose.material.icons.filled.Timer
import com.lastwave.app.data.localmusic.LocalOfflineRepository
import com.lastwave.app.playback.isLocalTrack
import com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.common.PlaylistCover
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.common.GroupGap
import com.lastwave.app.ui.common.TopCurvedSectionClipShape
import com.lastwave.app.ui.common.groupPositionFor
import com.lastwave.app.ui.common.groupShape
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.LocalIsDarkTheme
import com.lastwave.app.ui.theme.LiquidGlassSurface
import com.lastwave.app.ui.theme.liquidGlass
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor
import com.lastwave.app.ui.theme.liquidGlassSource
import com.lastwave.app.ui.theme.isLiquidGlassBackdropSupported
import com.lastwave.app.ui.theme.LocalLiquidGlassBackdrop
import com.lastwave.app.ui.theme.LocalLiquidGlassOverlayBackdrop
import com.lastwave.app.ui.theme.LiquidGlassPreset
import com.lastwave.app.ui.theme.Backdrop
import com.lastwave.app.ui.theme.rememberLayerBackdrop
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.scale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.IntBuffer
import kotlin.time.Duration.Companion.seconds
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt


@Composable
internal fun AddToPlaylistDialogHost(
    viewModel: PlayerViewModel,
    track: PlayableTrack,
    onDismiss: () -> Unit,
    onAdd: (Set<Long>, Set<Long>) -> Unit,
    onFindDuplicates: suspend (Set<Long>) -> Set<Long>,
    onFindCachedDuplicates: suspend (Set<Long>) -> Set<Long>,
    onCreate: (String) -> Unit,
) {
    // A device song used to get an offline-only sheet, which hid the user's own
    // playlists even though those keep local files (PlaylistRepository.addTrack).
    // It now gets the same sheet, with its offline playlists as a second section.
    val isDeviceSong = track.videoId.isNullOrBlank() && track.isLocalTrack()
    val offlinePlaylists by viewModel.offlinePlaylists.collectAsStateWithLifecycle()
    val allPlaylists by viewModel.customPlaylists.collectAsStateWithLifecycle()
    // An imported file can't live in a YouTube Music playlist (no videoId).
    val playlists = remember(allPlaylists, track) {
        if (track.isImportedLocalFile()) allPlaylists.filterNot { it.remotePlaylistId != null } else allPlaylists
    }
    AddToPlaylistDialog(
        track = track,
        playlists = playlists,
        onDismiss = onDismiss,
        onAdd = onAdd,
        onFindDuplicates = onFindDuplicates,
        onFindCachedDuplicates = onFindCachedDuplicates,
        // Creating from a device song still makes an offline playlist, as before.
        onCreate = if (isDeviceSong) {
            { name ->
                viewModel.createOfflinePlaylistAndAdd(name, track)
                onDismiss()
            }
        } else {
            onCreate
        },
        offlinePlaylists = if (isDeviceSong) offlinePlaylists else null,
        onAddOffline = { ids -> viewModel.addToOfflinePlaylists(ids, track) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToPlaylistDialog(
    track: PlayableTrack,
    playlists: List<SavedPlaylist>,
    onDismiss: () -> Unit,
    onAdd: (Set<Long>, Set<Long>) -> Unit,
    onFindDuplicates: suspend (Set<Long>) -> Set<Long>,
    onFindCachedDuplicates: suspend (Set<Long>) -> Set<Long>,
    onCreate: (String) -> Unit,
    /** Offline playlists to offer too (device songs only); null hides the section. */
    offlinePlaylists: List<LocalOfflineRepository.OfflinePlaylist>? = null,
    onAddOffline: (Set<String>) -> Unit = {},
) {
    val sanitizedPlaylists = remember(playlists) { playlists.distinctBy { it.id } }
    var newPlaylistName by remember(track) { mutableStateOf("") }
    var selectedPlaylistIds by remember(track) { mutableStateOf(emptySet<Long>()) }
    var selectedOfflineIds by remember(track) { mutableStateOf(emptySet<String>()) }
    var duplicateConfirmation by remember(track) { mutableStateOf<Set<Long>?>(null) }
    var duplicatePlaylistIds by remember(track) { mutableStateOf(emptySet<Long>()) }
    var knownDuplicatePlaylistIds by remember(track) { mutableStateOf(emptySet<Long>()) }
    var isCheckingDuplicates by remember(track) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val trackKey = remember(track.title, track.artist) { track.toGeneratedTrack().key }
    val trackUrlNorm = remember(track.playbackUrl) {
        track.playbackUrl?.let(LocalOfflineRepository.Companion::normalizeUrl)
    }
    val selectedCount = selectedPlaylistIds.size + selectedOfflineIds.size
    val playlistListMaxHeight = (LocalConfiguration.current.screenHeightDp.dp - 300.dp)
        .coerceIn(200.dp, 460.dp)

    LaunchedEffect(sanitizedPlaylists.map(SavedPlaylist::id)) {
        selectedPlaylistIds = selectedPlaylistIds.intersect(sanitizedPlaylists.mapTo(mutableSetOf(), SavedPlaylist::id))
    }

    LaunchedEffect(trackKey, sanitizedPlaylists.map(SavedPlaylist::id)) {
        runCatching {
            val remoteIds = sanitizedPlaylists
                .filterTo(mutableListOf()) { it.remotePlaylistId != null }
                .mapTo(mutableSetOf(), SavedPlaylist::id)
            knownDuplicatePlaylistIds = knownDuplicatePlaylistIds + onFindCachedDuplicates(remoteIds)
        }
    }

    fun requestAdd(playlistIds: Set<Long>) {
        if (playlistIds.isEmpty() || isCheckingDuplicates) return
        val immediateDuplicates = playlistIds.filter { id ->
            id in knownDuplicatePlaylistIds ||
                sanitizedPlaylists.firstOrNull { it.id == id }?.tracks?.any {
                    it.key == trackKey ||
                        (it.name.equals(track.title, ignoreCase = true) && it.artist.equals(track.artist, ignoreCase = true))
                } == true
        }.toSet()
        if (immediateDuplicates.isNotEmpty()) {
            duplicatePlaylistIds = emptySet()
            duplicateConfirmation = immediateDuplicates
            return
        }
        scope.launch {
            runCatching {
                isCheckingDuplicates = true
                val duplicates = onFindDuplicates(playlistIds)
                isCheckingDuplicates = false
                knownDuplicatePlaylistIds = (knownDuplicatePlaylistIds - playlistIds) + duplicates
                if (duplicates.isEmpty()) {
                    onAdd(playlistIds, emptySet())
                } else {
                    duplicatePlaylistIds = emptySet()
                    duplicateConfirmation = duplicates
                }
            }.onFailure {
                isCheckingDuplicates = false
                onAdd(playlistIds, emptySet())
            }
        }
    }

    // Offline playlists have no duplicate step: add them at once, then let the
    // regular playlists run their check (whose onAdd closes the sheet).
    fun submit() {
        if (selectedOfflineIds.isNotEmpty()) {
            onAddOffline(selectedOfflineIds)
            selectedOfflineIds = emptySet()
        }
        if (selectedPlaylistIds.isNotEmpty()) requestAdd(selectedPlaylistIds) else onDismiss()
    }

    duplicateConfirmation?.let { duplicates ->
        val duplicatePlaylists = sanitizedPlaylists.filter { it.id in duplicates }
        if (duplicatePlaylists.size == 1) {
            val playlist = duplicatePlaylists.first()
            AlertDialog(
                onDismissRequest = { duplicateConfirmation = null },
                title = { Text(stringResource(com.lastwave.app.R.string.add_dup_single_title)) },
                text = {
                    Text(
                        stringResource(com.lastwave.app.R.string.add_dup_single_text, track.title, track.artist, playlist.title),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onAdd(selectedPlaylistIds, setOf(playlist.id))
                            duplicateConfirmation = null
                        },
                    ) {
                        Text(stringResource(com.lastwave.app.R.string.add_dup_add_anyway))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { duplicateConfirmation = null }) {
                        Text(stringResource(com.lastwave.app.R.string.add_dup_leave))
                    }
                },
            )
            return
        }

        val missingCount = selectedPlaylistIds.size - duplicates.size
        AlertDialog(
            onDismissRequest = { duplicateConfirmation = null },
            title = {
                Text(stringResource(com.lastwave.app.R.string.add_dup_multi_title, duplicates.size))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(com.lastwave.app.R.string.add_dup_multi_text),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(
                            duplicatePlaylists,
                            key = { index, playlist -> "${playlist.id}_${playlist.remotePlaylistId ?: ""}_$index" },
                        ) { _, playlist ->
                            val addAgain = playlist.id in duplicatePlaylistIds
                            val canAddAnother = playlist.mode != LIKED_SONGS_MODE
                            Surface(
                                onClick = {
                                    duplicatePlaylistIds = if (addAgain) {
                                        duplicatePlaylistIds - playlist.id
                                    } else {
                                        duplicatePlaylistIds + playlist.id
                                    }
                                },
                                enabled = canAddAnother,
                                shape = RoundedCornerShape(14.dp),
                                color = if (addAgain) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            playlist.title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            if (canAddAnother) "Add another copy" else "Already liked",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Checkbox(checked = addAgain, onCheckedChange = null, enabled = canAddAnother)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAdd(selectedPlaylistIds, duplicatePlaylistIds)
                        duplicateConfirmation = null
                    },
                ) {
                    Text(
                        when {
                            duplicatePlaylistIds.isNotEmpty() -> stringResource(com.lastwave.app.R.string.add_dup_add_anyway)
                            missingCount > 0 -> stringResource(com.lastwave.app.R.string.add_dup_add_missing)
                            else -> stringResource(com.lastwave.app.R.string.add_dup_leave)
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { duplicateConfirmation = null }) {
                    Text(stringResource(com.lastwave.app.R.string.add_dup_leave))
                }
            },
        )
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        // Opaque sheet: player controls / quality pill behind must not bleed through.
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        scrimColor = Color.Black.copy(alpha = 0.55f),
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
        var showCreateDialog by remember { mutableStateOf(false) }

        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text(stringResource(com.lastwave.app.R.string.add_create_title)) },
                text = {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        label = { Text(stringResource(com.lastwave.app.R.string.pl_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = newPlaylistName.isNotBlank(),
                        onClick = {
                            val cleanName = newPlaylistName.trim()
                            // A device song's create makes an offline playlist, so it
                            // never folds into a same-named regular one.
                            val existingPlaylist = if (offlinePlaylists != null) null else sanitizedPlaylists.firstOrNull {
                                it.mode == "custom" && it.title.equals(cleanName, ignoreCase = true)
                            }
                            if (existingPlaylist == null) {
                                onCreate(cleanName)
                            } else {
                                selectedPlaylistIds = setOf(existingPlaylist.id)
                                requestAdd(setOf(existingPlaylist.id))
                            }
                            showCreateDialog = false
                        },
                    ) {
                        Text(stringResource(com.lastwave.app.R.string.add_create_and_add))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) { Text(stringResource(com.lastwave.app.R.string.common_cancel)) }
                },
            )
        }

        com.lastwave.app.ui.common.EdgeToEdgeDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .adaptiveContentWidth(maxWidth = 640.dp)
                .align(Alignment.CenterHorizontally)
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header: what is being added, and a small way to start a new playlist.
            Row(
                modifier = Modifier.padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Add to playlist",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${track.title} • ${track.artist}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                androidx.compose.material3.FilledTonalButton(
                    onClick = { showCreateDialog = true },
                    contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .height(36.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(com.lastwave.app.R.string.local_new_playlist), style = MaterialTheme.typography.labelLarge)
                }
            }

            val hasOffline = !offlinePlaylists.isNullOrEmpty()
            if (sanitizedPlaylists.isEmpty() && !hasOffline) {
                Text(
                    "No playlists yet. Tap New playlist to make one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = playlistListMaxHeight),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (hasOffline && sanitizedPlaylists.isNotEmpty()) {
                        item(key = "header_playlists") { PlaylistSectionLabel("Your playlists") }
                    }
                    itemsIndexed(
                        sanitizedPlaylists,
                        key = { index, playlist -> "${playlist.id}_${playlist.remotePlaylistId ?: ""}_$index" },
                    ) { _, playlist ->
                        val selected = playlist.id in selectedPlaylistIds
                        val alreadyAdded = playlist.id in knownDuplicatePlaylistIds ||
                            playlist.tracks.any {
                                it.key == trackKey ||
                                    (it.name.equals(track.title, ignoreCase = true) && it.artist.equals(track.artist, ignoreCase = true))
                            }
                        val trackCount = playlist.remoteTrackCount
                            ?: playlist.tracks.size.takeIf { playlist.remotePlaylistId == null }
                        val countLabel = when (trackCount) {
                            null -> "Track count unavailable"
                            1 -> "1 track"
                            else -> "$trackCount tracks"
                        }
                        PlaylistPickRow(
                            selected = selected,
                            title = playlist.title,
                            subtitle = when {
                                isCheckingDuplicates && selected -> "$countLabel • Checking contents..."
                                alreadyAdded -> "$countLabel • Already added"
                                else -> countLabel
                            },
                            highlightSubtitle = alreadyAdded,
                            onClick = {
                                selectedPlaylistIds = if (selected) {
                                    selectedPlaylistIds - playlist.id
                                } else {
                                    selectedPlaylistIds + playlist.id
                                }
                            },
                        ) {
                            PlaylistCover(playlist = playlist, modifier = Modifier.size(48.dp), cornerRadius = 12.dp)
                        }
                    }
                    if (hasOffline) {
                        item(key = "header_offline") {
                            PlaylistSectionLabel(stringResource(com.lastwave.app.R.string.local_offline_playlists))
                        }
                        items(offlinePlaylists.orEmpty(), key = { "offline_${it.id}" }) { playlist ->
                            val selected = playlist.id in selectedOfflineIds
                            val alreadyAdded = trackUrlNorm != null && playlist.trackUrls.any {
                                LocalOfflineRepository.normalizeUrl(it) == trackUrlNorm
                            }
                            val count = playlist.trackUrls.size
                            val countText = if (count == 1) "1 track" else "$count tracks"
                            PlaylistPickRow(
                                selected = selected,
                                title = playlist.name,
                                subtitle = if (alreadyAdded) "$countText • Already added" else countText,
                                highlightSubtitle = alreadyAdded,
                                onClick = {
                                    selectedOfflineIds = if (selected) selectedOfflineIds - playlist.id else selectedOfflineIds + playlist.id
                                },
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.QueueMusic, contentDescription = null)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(com.lastwave.app.R.string.common_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { submit() },
                    enabled = selectedCount > 0 && !isCheckingDuplicates,
                ) {
                    if (isCheckingDuplicates) {
                        ExpressiveInlineLoadingIndicator(
                            size = 18.dp,
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            selectedCount == 0 -> "Add"
                            isCheckingDuplicates -> "Checking"
                            else -> "Add to $selectedCount"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
    )
}

/** One selectable playlist: cover, title, count line and a checkbox. */
@Composable
private fun PlaylistPickRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    highlightSubtitle: Boolean,
    onClick: () -> Unit,
    cover: @Composable () -> Unit,
) {
    val rowColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = tween(ExpressiveMotion.Quick),
        label = "playlistSelectionColor",
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = rowColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            cover()
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (highlightSubtitle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Checkbox(checked = selected, onCheckedChange = null)
        }
    }
}
