@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.lastwave.app.ui.player

import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lastwave.app.ui.common.PredictiveBackScreen
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.isTabletOrWideScreen
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.data.playlist.LIKED_SONGS_MODE
import com.lastwave.app.data.local.LyricsAnimation
import com.lastwave.app.data.local.LyricsUiVersion
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlaybackChromeState
import com.lastwave.app.playback.qualityBadgeLabel
import com.lastwave.app.playback.spatialIndicatorLabel
import com.lastwave.app.playback.PlaybackProgressState
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.common.PlaylistCover
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.LiquidGlassSurface
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor
import com.lastwave.app.ui.theme.liquidGlassSource
import com.lastwave.app.ui.theme.isLiquidGlassBackdropSupported
import com.lastwave.app.ui.theme.LocalLiquidGlassBackdrop
import com.lastwave.app.ui.theme.LocalLiquidGlassOverlayBackdrop
import com.lastwave.app.ui.theme.LiquidGlassPreset
import com.lastwave.app.ui.theme.BackdropBlur
import com.lastwave.app.ui.theme.Backdrop
import com.lastwave.app.ui.theme.rememberLayerBackdrop
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs

enum class FullPlayerTab {
    NOW_PLAYING,
    LYRICS,
    QUEUE
}

val LocalMusicPlayer = staticCompositionLocalOf<MusicPlayer> {
    error("MusicPlayer is only available inside PlayerHost")
}

val LocalAddToPlaylist = staticCompositionLocalOf<(PlayableTrack) -> Unit> {
    error("Add-to-playlist is only available inside PlayerHost")
}

/**
 * Extra list-end clearance needed while the collapsed player overlays a
 * screen. The Material 3 bar is about 86dp tall; 88dp clears it without
 * leaving a large empty band at the end of short lists.
 */
val LocalMiniPlayerScrollClearance = staticCompositionLocalOf { 0.dp }

@HiltViewModel
class PlayerViewModel @Inject constructor(
    val player: MusicPlayer,
    private val playlistRepository: PlaylistRepository,
    private val lyricsRepository: LyricsRepository,
    private val settingsPreferences: com.lastwave.app.data.local.SettingsPreferences,
    val navigator: com.lastwave.app.ui.navigation.ArtistAlbumNavigator,
    val genreExplorer: com.lastwave.app.ui.genres.GenreExplorer,
    val mixLauncher: com.lastwave.app.ui.generate.MixLauncher,
    private val ytMusicLibraryManager: com.lastwave.app.data.ytmusic.YtMusicLibraryManager,
    private val likedSongsManager: com.lastwave.app.data.playlist.LikedSongsManager,
    private val scrobbleRepository: com.lastwave.app.data.repository.ScrobbleRepository,
    private val downloadManager: com.lastwave.app.data.download.TrackDownloadManager,
    private val routeNavigator: com.lastwave.app.ui.navigation.AppRouteNavigator,
) : ViewModel() {
    val navEvents = navigator.events
    val state = player.state
    val chromeState = player.chromeState
    val progressState = player.progressState
    val fullPlayerState = player.state
        .map { it.copy(positionMs = 0L, bufferedPositionMs = 0L) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, player.state.value.copy(positionMs = 0L, bufferedPositionMs = 0L))
    val settings: StateFlow<com.lastwave.app.data.local.MiscSettings> = settingsPreferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.lastwave.app.data.local.MiscSettings())
    val activeDownloads = downloadManager.downloads
        .map { map -> map.values.filter { !it.isFinished && it.error == null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun navigateToDownloads() {
        routeNavigator.navigateTo(com.lastwave.app.ui.navigation.Screen.Downloads.route)
    }
    private val _customPlaylists = MutableStateFlow<List<SavedPlaylist>>(emptyList())
    val customPlaylists = _customPlaylists.asStateFlow()
    private var customPlaylistsLoaded = false
    val likedTrackKeys = likedSongsManager.likedTrackKeys

    fun openArtist(name: String, browseId: String? = null) {
        navigator.openArtist(name, browseId)
    }

    fun openAlbum(title: String, artist: String = "", browseId: String? = null) {
        navigator.openAlbum(title, artist, browseId)
    }

    private val _lyricsState = MutableStateFlow<LyricsUiState>(LyricsUiState.Idle)
    val lyricsState = _lyricsState.asStateFlow()

    private var currentTrackLyricsKey: String? = null
    private var lyricsJob: Job? = null

    private companion object {
        /** Spinner only appears when loading actually takes time; cache
         *  hits resolve well inside this window with no flash. */
        const val LOADING_SPINNER_DELAY_MS = 250L
    }

    init {
        viewModelScope.launch {
            playlistRepository.changes.collect {
                if (customPlaylistsLoaded) refreshCustomPlaylists()
            }
        }
        viewModelScope.launch {
            ytMusicLibraryManager.playlists.collect {
                if (customPlaylistsLoaded) refreshCustomPlaylists()
            }
        }
        viewModelScope.launch {
            combine(
                player.chromeState.map { it.current }.distinctUntilChanged(),
                settingsPreferences.settings.map { it.wordByWordLyrics }.distinctUntilChanged(),
            ) { track, wordByWord -> track to wordByWord }.collect { (track, wordByWord) ->
                val key = track?.let { "${it.artist}|${it.title}|$wordByWord" }
                if (key != currentTrackLyricsKey) {
                    currentTrackLyricsKey = key
                    if (track != null) {
                        loadLyrics(track, forceRefresh = false)
                    } else {
                        lyricsJob?.cancel()
                        _lyricsState.value = LyricsUiState.Idle
                    }
                }
            }
        }
    }

    private suspend fun refreshCustomPlaylists() {
        try {
            _customPlaylists.value = (playlistRepository.getAll()
                .filter { it.mode == "custom" || it.mode == LIKED_SONGS_MODE }
                .sortedByDescending { it.mode == LIKED_SONGS_MODE } + ytMusicLibraryManager.playlists.value)
                .distinctBy { it.id }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            customPlaylistsLoaded = false
            android.util.Log.e("PlayerViewModel", "Couldn't refresh playlists", error)
        }
    }

    fun prepareCustomPlaylists() {
        if (customPlaylistsLoaded) return
        customPlaylistsLoaded = true
        viewModelScope.launch { refreshCustomPlaylists() }
    }

    fun loadLyrics(track: PlayableTrack, forceRefresh: Boolean = false) {
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            // Hold the previous track's lyrics instead of flashing the
            // spinner on every change: cache hits resolve in milliseconds,
            // so the indicator only appears when loading actually takes time.
            val loadingIndicator = launch {
                delay(LOADING_SPINNER_DELAY_MS)
                _lyricsState.value = LyricsUiState.Loading
            }
            try {
                val durationSeconds = if (player.state.value.durationMs > 0) {
                    (player.state.value.durationMs / 1000).toInt()
                } else null

                val result = try {
                    lyricsRepository.getLyrics(
                        track.title, track.artist, track.album, durationSeconds, forceRefresh,
                        wordByWord = settingsPreferences.settings.first().wordByWordLyrics,
                        onPartialResult = { partial ->
                            withContext(Dispatchers.Main.immediate) {
                                coroutineContext.ensureActive()
                                publishLyrics(partial)
                            }
                        },
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    LyricsResult.Error(error.message ?: "Couldn't load lyrics")
                }
                coroutineContext.ensureActive()
                if (result is LyricsResult.Success || _lyricsState.value !is LyricsUiState.Success) {
                    publishLyrics(result)
                }
            } finally {
                loadingIndicator.cancel()
            }
        }
    }

    private fun publishLyrics(result: LyricsResult) {
        when (result) {
            is LyricsResult.Success -> {
                _lyricsState.value = LyricsUiState.Success(
                    lines = result.lines,
                    isSynced = result.isSynced,
                    isWordSynced = result.isWordSynced,
                    plainLyrics = result.plainLyrics,
                    isInstrumental = result.isInstrumental,
                    source = result.source,
                )
            }
            is LyricsResult.Empty -> {
                _lyricsState.value = LyricsUiState.Empty
            }
            is LyricsResult.Error -> {
                _lyricsState.value = LyricsUiState.Error(result.message)
            }
        }
    }

    fun retryLyrics() {
        val track = player.state.value.current ?: return
        loadLyrics(track, forceRefresh = true)
    }

    fun addToPlaylists(
        playlistIds: Set<Long>,
        duplicatePlaylistIds: Set<Long>,
        track: PlayableTrack,
    ) {
        if (playlistIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val generatedTrack = track.toGeneratedTrack()
                var remoteChanged = false
                playlistIds.forEach { playlistId ->
                    val allowDuplicate = playlistId in duplicatePlaylistIds
                    if (playlistId < 0L) {
                        val added = ytMusicLibraryManager.addTrack(playlistId, generatedTrack, allowDuplicate)
                        remoteChanged = remoteChanged || added
                    } else {
                        playlistRepository.addTrack(
                            id = playlistId,
                            track = generatedTrack,
                            allowDuplicate = allowDuplicate,
                        )
                    }
                }
                if (remoteChanged) {
                    ytMusicLibraryManager.refresh()
                    refreshCustomPlaylists()
                }
            }
        }
    }

    suspend fun findDuplicatePlaylistIds(playlistIds: Set<Long>, track: PlayableTrack): Set<Long> {
        val generatedTrack = track.toGeneratedTrack()
        val localDuplicates = _customPlaylists.value
            .filter { playlist ->
                playlist.id in playlistIds &&
                    playlist.remotePlaylistId == null &&
                    playlist.tracks.any {
                        it.key == generatedTrack.key ||
                            (it.name.equals(generatedTrack.name, ignoreCase = true) && it.artist.equals(generatedTrack.artist, ignoreCase = true))
                    }
            }
            .mapTo(mutableSetOf(), SavedPlaylist::id)
        val remoteIds = playlistIds.filterTo(mutableSetOf()) { it < 0L }
        return localDuplicates + ytMusicLibraryManager.findDuplicatePlaylistIds(remoteIds, generatedTrack)
    }

    suspend fun findCachedDuplicatePlaylistIds(playlistIds: Set<Long>, track: PlayableTrack): Set<Long> {
        val generatedTrack = track.toGeneratedTrack()
        val remoteIds = playlistIds.filterTo(mutableSetOf()) { it < 0L }
        return ytMusicLibraryManager.findCachedDuplicatePlaylistIds(remoteIds, generatedTrack)
    }

    fun createPlaylistAndAdd(title: String, track: PlayableTrack) {
        if (title.isBlank()) return
        viewModelScope.launch {
            runCatching {
                val playlist = playlistRepository.createCustom(title)
                playlistRepository.addTrack(playlist.id, track.toGeneratedTrack())
            }
        }
    }

    fun toggleLiked(track: PlayableTrack) {
        viewModelScope.launch {
            val loved = likedSongsManager.toggle(track.toGeneratedTrack())
            mirrorLastFmLove(track, loved)
        }
    }

    fun like(track: PlayableTrack) {
        viewModelScope.launch {
            likedSongsManager.like(track.toGeneratedTrack())
            mirrorLastFmLove(track, loved = true)
        }
    }

    private suspend fun mirrorLastFmLove(track: PlayableTrack, loved: Boolean) {
        when (val result = scrobbleRepository.setTrackLoved(track.artist, track.title, loved)) {
            com.lastwave.app.data.repository.ScrobbleRepository.Result.Success,
            com.lastwave.app.data.repository.ScrobbleRepository.Result.NoSessionKey -> Unit
            is com.lastwave.app.data.repository.ScrobbleRepository.Result.Failed ->
                android.util.Log.w("PlayerViewModel", "Last.fm love sync failed: ${result.message}")
        }
    }
}

/** App-wide collapsed + maximized player layered over every navigation route. */
@Composable
fun PlayerHost(
    viewModel: PlayerViewModel = hiltViewModel(),
    hasBottomNavigation: Boolean = false,
    content: @Composable () -> Unit,
) {
    val state by viewModel.chromeState.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var currentTab by rememberSaveable { mutableStateOf(FullPlayerTab.NOW_PLAYING) }
    var playlistTrack by remember { mutableStateOf<PlayableTrack?>(null) }
    val requestAddToPlaylist = remember(viewModel) {
        { track: PlayableTrack ->
            viewModel.prepareCustomPlaylists()
            playlistTrack = track
        }
    }
    val trackKey = state.current?.let { it.videoId ?: "${it.artist}|${it.title}" }
    LaunchedEffect(trackKey) {
        if (trackKey == null) {
            expanded = false
            currentTab = FullPlayerTab.NOW_PLAYING
        }
    }
    LaunchedEffect(expanded) {
        if (!expanded) {
            currentTab = FullPlayerTab.NOW_PLAYING
        }
    }
    LaunchedEffect(Unit) {
        viewModel.navEvents.collect {
            expanded = false
            currentTab = FullPlayerTab.NOW_PLAYING
        }
    }
    LaunchedEffect(Unit) {
        viewModel.mixLauncher.requests.collect {
            expanded = false
            currentTab = FullPlayerTab.NOW_PLAYING
        }
    }
    LaunchedEffect(Unit) {
        viewModel.genreExplorer.pendingGenre.collect { genre ->
            if (genre != null) {
                expanded = false
                currentTab = FullPlayerTab.NOW_PLAYING
            }
        }
    }
    val miniPlayerVisible = state.current != null && !expanded

    CompositionLocalProvider(
        LocalMusicPlayer provides viewModel.player,
        LocalAddToPlaylist provides requestAddToPlaylist,
        LocalMiniPlayerScrollClearance provides if (state.current != null) 88.dp else 0.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            content()
            if (miniPlayerVisible) {
                MiniPlayer(
                    state = state,
                    progressState = viewModel.progressState,
                    onExpand = { expanded = true },
                    onToggle = viewModel.player::togglePlayPause,
                    onPrevious = viewModel.player::previous,
                    onNext = viewModel.player::next,
                    onClose = viewModel.player::stopAndClear,
                    bottomPadding = if (hasBottomNavigation) 92.dp else 12.dp,
                    edgeToEdge = !hasBottomNavigation,
                    backdrop = null,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            if (activeDownloads.isNotEmpty() && !expanded) {
                val latestProgress = activeDownloads.firstOrNull()?.progressPercent ?: 0
                com.lastwave.app.ui.download.DraggableDownloadOverlay(
                    activeDownloadsCount = activeDownloads.size,
                    latestProgressPercent = latestProgress,
                    onOpenDownloads = { viewModel.navigateToDownloads() },
                )
            }
            AnimatedVisibility(
                visible = expanded && state.current != null,
                enter = slideInVertically(
                    animationSpec = ExpressiveMotion.smoothSpring(),
                    initialOffsetY = { it },
                ) + fadeIn(tween(180)),
                exit = slideOutVertically(
                    animationSpec = ExpressiveMotion.smoothSpring(),
                    targetOffsetY = { it },
                ) + fadeOut(tween(150)),
            ) {
                PredictiveBackScreen(
                    enabled = expanded && state.current != null && currentTab == FullPlayerTab.NOW_PLAYING,
                    onBack = { expanded = false },
                ) {
                    ExpandedPlayer(
                        viewModel = viewModel,
                        currentTab = currentTab,
                        onTabChange = { currentTab = it },
                        onRetryLyrics = viewModel::retryLyrics,
                        onCollapse = { expanded = false },
                        onOpenArtist = { artist ->
                            expanded = false
                            viewModel.openArtist(artist)
                        },
                    )
                }
            }
            playlistTrack?.let { track ->
                AddToPlaylistDialogHost(
                    viewModel = viewModel,
                    track = track,
                    onDismiss = { playlistTrack = null },
                    onAdd = { playlistIds, duplicatePlaylistIds ->
                        viewModel.addToPlaylists(playlistIds, duplicatePlaylistIds, track)
                        playlistTrack = null
                    },
                    onFindDuplicates = { playlistIds ->
                        viewModel.findDuplicatePlaylistIds(playlistIds, track)
                    },
                    onFindCachedDuplicates = { playlistIds ->
                        viewModel.findCachedDuplicatePlaylistIds(playlistIds, track)
                    },
                    onCreate = { title ->
                        viewModel.createPlaylistAndAdd(title, track)
                        playlistTrack = null
                    },
                )
            }
        }
    }
}

@Composable
private fun ExpandedPlayer(
    viewModel: PlayerViewModel,
    currentTab: FullPlayerTab,
    onTabChange: (FullPlayerTab) -> Unit,
    onRetryLyrics: () -> Unit,
    onCollapse: () -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    val state by viewModel.fullPlayerState.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val likedTrackKeys by viewModel.likedTrackKeys.collectAsStateWithLifecycle()
    val currentTrack = state.current
    val isLiked = currentTrack != null &&
        "${currentTrack.title}|${currentTrack.artist}".lowercase() in likedTrackKeys
    FullPlayer(
        state = state,
        progressState = viewModel.progressState,
        player = viewModel.player,
        lyricsState = lyricsState,
        lyricsUiVersion = settings.lyricsUiVersion,
        lyricsAnimation = settings.lyricsAnimation,
        wavySeekbarEnabled = settings.wavySeekbarEnabled,
        currentTab = currentTab,
        onTabChange = onTabChange,
        onRetryLyrics = onRetryLyrics,
        onCollapse = onCollapse,
        onOpenArtist = onOpenArtist,
        isLiked = isLiked,
        onToggleLiked = { currentTrack?.let(viewModel::toggleLiked) },
        onDoubleTapLike = { currentTrack?.let(viewModel::like) },
    )
}

@Composable
private fun AddToPlaylistDialogHost(
    viewModel: PlayerViewModel,
    track: PlayableTrack,
    onDismiss: () -> Unit,
    onAdd: (Set<Long>, Set<Long>) -> Unit,
    onFindDuplicates: suspend (Set<Long>) -> Set<Long>,
    onFindCachedDuplicates: suspend (Set<Long>) -> Set<Long>,
    onCreate: (String) -> Unit,
) {
    val playlists by viewModel.customPlaylists.collectAsStateWithLifecycle()
    AddToPlaylistDialog(
        track = track,
        playlists = playlists,
        onDismiss = onDismiss,
        onAdd = onAdd,
        onFindDuplicates = onFindDuplicates,
        onFindCachedDuplicates = onFindCachedDuplicates,
        onCreate = onCreate,
    )
}

@Composable
internal fun AnimatedPlayPauseIcon(isPlaying: Boolean, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = isPlaying,
        transitionSpec = {
            (fadeIn(tween(ExpressiveMotion.Quick)) +
                scaleIn(ExpressiveMotion.spatialSpring(), initialScale = 0.7f)) togetherWith
                (fadeOut(tween(ExpressiveMotion.Quick)) +
                    scaleOut(tween(ExpressiveMotion.Quick), targetScale = 0.7f))
        },
        label = "playPauseIcon",
        modifier = modifier,
    ) { playing ->
        Icon(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = "Play or pause",
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun MiniPlayer(
    state: PlaybackChromeState,
    progressState: StateFlow<PlaybackProgressState>,
    onExpand: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    edgeToEdge: Boolean,
    backdrop: Backdrop? = LocalLiquidGlassBackdrop.current,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val track = state.current ?: return
    // Liquid Glass dressing for the floating mini player (no-op when the
    // experimental setting is off — see ui/theme/LiquidGlass.kt).
    val liquidGlass = LocalLiquidGlass.current
    val barInteraction = remember { MutableInteractionSource() }
    var dragX by remember(track.videoId, track.title) { mutableFloatStateOf(0f) }
    var dragY by remember(track.videoId, track.title) { mutableFloatStateOf(0f) }
    val shownX by animateFloatAsState(dragX, ExpressiveMotion.spatialSpring(), label = "miniPlayerX")
    val shownY by animateFloatAsState(dragY, ExpressiveMotion.spatialSpring(), label = "miniPlayerY")
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val isTablet = isTabletOrWideScreen()
    val shape = if (edgeToEdge && !isTablet) {
        RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    } else {
        RoundedCornerShape(32.dp)
    }
    val positionedModifier = if (edgeToEdge && !isTablet) {
        modifier.fillMaxWidth()
    } else {
        modifier
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            )
            .padding(horizontal = 14.dp)
            .padding(bottom = if (isTablet && edgeToEdge) 14.dp else bottomPadding)
            .widthIn(max = 680.dp)
            .fillMaxWidth()
    }
    Box(
        modifier = positionedModifier
            .graphicsLayer {
                translationX = shownX
                translationY = shownY.coerceAtLeast(0f)
                alpha = (1f - (abs(shownX) + shownY.coerceAtLeast(0f)) / (threshold * 4f)).coerceIn(0.55f, 1f)
            }
            .pointerInput(track.videoId, track.title) {
                detectDragGestures(
                    onDragCancel = { dragX = 0f; dragY = 0f },
                    onDragEnd = {
                        when {
                            dragY > threshold -> onClose()
                            dragY < -threshold -> onExpand()
                            dragX < -threshold -> onNext()
                            dragX > threshold -> onPrevious()
                        }
                        dragX = 0f
                        dragY = 0f
                    },
                ) { change, amount ->
                    change.consume()
                    if (abs(dragX + amount.x) > abs(dragY + amount.y)) dragX += amount.x
                    else dragY += amount.y
                }
            }
            .clickable(interactionSource = barInteraction, indication = null, onClick = onExpand),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = shape,
            color = liquidGlassContainerColor(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                enabled = liquidGlass,
                backdrop = backdrop,
            ),
            tonalElevation = if (edgeToEdge || liquidGlass) 0.dp else 6.dp,
            shadowElevation = if (edgeToEdge || liquidGlass) 0.dp else 12.dp,
            modifier = Modifier.fillMaxWidth().liquidGlassChrome(shape, liquidGlass, LiquidGlassPreset.MiniPlayer, backdrop, interactionSource = barInteraction),
        ) {
            Column(
                modifier = if (edgeToEdge) {
                    Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    )
                } else {
                    Modifier
                },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(56.dp)) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            PlayerArtwork(track, Modifier.fillMaxSize(), 18.dp)
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(
                            track.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            track.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Surface(
                        onClick = onToggle,
                        shape = CircleShape,
                        color = if (liquidGlass) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                            else MaterialTheme.colorScheme.primary,
                        contentColor = if (liquidGlass) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(48.dp)
                            .liquidGlassChrome(CircleShape, liquidGlass, LiquidGlassPreset.FloatingControls, backdrop),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (state.isBuffering) {
                                ExpressiveInlineLoadingIndicator(
                                    size = 24.dp,
                                    color = androidx.compose.material3.LocalContentColor.current,
                                    strokeWidth = 2.5.dp,
                                )
                            } else {
                                AnimatedPlayPauseIcon(state.isPlaying, Modifier.size(27.dp))
                            }
                        }
                    }
                    IconButton(
                        onClick = onNext,
                        enabled = state.queueSize > 1,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(44.dp)
                            .liquidGlassChrome(CircleShape, liquidGlass, LiquidGlassPreset.FloatingControls, backdrop)
                            .clip(CircleShape)
                            .background(liquidGlassContainerColor(MaterialTheme.colorScheme.secondaryContainer, backdrop = backdrop)),
                    ) {
                        Icon(
                            Icons.Filled.SkipNext,
                            "Next",
                            tint = if (state.queueSize > 1) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                }
                MiniPlayerProgress(progressState)
            }
        }
    }
}

@Composable
private fun MiniPlayerProgress(progressState: StateFlow<PlaybackProgressState>) {
    val state by progressState.collectAsStateWithLifecycle()
    val progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(3.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // Scale an already measured layer instead of changing its width and
        // forcing the mini-player through measure/layout on every ticker tick.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = progress.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
fun PlayingWaveBars(
    modifier: Modifier = Modifier,
    waveColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    shape: Shape = CircleShape,
) {
    val transition = rememberInfiniteTransition(label = "miniArtworkWave")
    val first = transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 480, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "miniWaveFirst",
    )
    val second = transition.animateFloat(
        initialValue = 0.95f,
        targetValue = 0.30f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 640, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "miniWaveSecond",
    )
    val third = transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 530, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "miniWaveThird",
    )

    val content: @Composable () -> Unit = {
        // Fixed small badge content so it can never expand to fill the cover.
        // Outer Box is 26x22 (16+5+5, 12+5+5) — always tiny, bottom-end aligned by caller.
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .padding(horizontal = 5.dp, vertical = 5.dp)
                .size(16.dp, 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Canvas(
                Modifier.fillMaxSize(),
            ) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas
            val barCount = 3
            val barWidth = (size.width / 5.2f).coerceAtLeast(1.5f)
            val barGap = barWidth * 0.9f
            val totalContentWidth = barCount * barWidth + (barCount - 1) * barGap
            val startX = ((size.width - totalContentWidth) / 2f).coerceAtLeast(0f)

            repeat(barCount) { index ->
                val fraction = when (index) {
                    0 -> first.value
                    1 -> second.value
                    else -> third.value
                }
                val barHeight = (size.height * fraction).coerceIn(minOf(barWidth, size.height), size.height)
                drawRoundRect(
                    color = waveColor,
                    topLeft = androidx.compose.ui.geometry.Offset(
                        x = startX + index * (barWidth + barGap),
                        y = size.height - barHeight,
                    ),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
            }
        }
    }

    if (containerColor == Color.Transparent) {
        Box(
            modifier = modifier.defaultMinSize(minWidth = 24.dp, minHeight = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    } else {
        Surface(
            shape = shape,
            color = containerColor,
            modifier = modifier.defaultMinSize(minWidth = 26.dp, minHeight = 22.dp),
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToPlaylistDialog(
    track: PlayableTrack,
    playlists: List<SavedPlaylist>,
    onDismiss: () -> Unit,
    onAdd: (Set<Long>, Set<Long>) -> Unit,
    onFindDuplicates: suspend (Set<Long>) -> Set<Long>,
    onFindCachedDuplicates: suspend (Set<Long>) -> Set<Long>,
    onCreate: (String) -> Unit,
) {
    val sanitizedPlaylists = remember(playlists) { playlists.distinctBy { it.id } }
    var newPlaylistName by remember(track) { mutableStateOf("") }
    var selectedPlaylistIds by remember(track) { mutableStateOf(emptySet<Long>()) }
    var duplicateConfirmation by remember(track) { mutableStateOf<Set<Long>?>(null) }
    var duplicatePlaylistIds by remember(track) { mutableStateOf(emptySet<Long>()) }
    var knownDuplicatePlaylistIds by remember(track) { mutableStateOf(emptySet<Long>()) }
    var isCheckingDuplicates by remember(track) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val trackKey = remember(track.title, track.artist) { track.toGeneratedTrack().key }
    val selectedPlaylists = sanitizedPlaylists.filter { it.id in selectedPlaylistIds }
    val playlistListMaxHeight = (LocalConfiguration.current.screenHeightDp.dp - 360.dp)
        .coerceIn(180.dp, 410.dp)

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

    duplicateConfirmation?.let { duplicates ->
        val duplicatePlaylists = sanitizedPlaylists.filter { it.id in duplicates }
        if (duplicatePlaylists.size == 1) {
            val playlist = duplicatePlaylists.first()
            AlertDialog(
                onDismissRequest = { duplicateConfirmation = null },
                title = { Text("Song already in playlist") },
                text = {
                    Text(
                        "${track.title} by ${track.artist} is already present in ${playlist.title}. " +
                            "You can leave the playlist unchanged or add another copy.",
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
                        Text("Add anyway")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { duplicateConfirmation = null }) {
                        Text("Leave unchanged")
                    }
                },
            )
            return
        }

        val missingCount = selectedPlaylistIds.size - duplicates.size
        AlertDialog(
            onDismissRequest = { duplicateConfirmation = null },
            title = {
                Text("${duplicates.size} playlists already have this song")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Existing copies stay unchanged. Select only the playlists where you want another copy.",
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
                            duplicatePlaylistIds.isNotEmpty() -> "Add anyway"
                            missingCount > 0 -> "Add missing"
                            else -> "Leave unchanged"
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { duplicateConfirmation = null }) {
                    Text("Leave unchanged")
                }
            },
        )
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.liquidGlassChrome(
            androidx.compose.material3.BottomSheetDefaults.ExpandedShape,
            LocalLiquidGlass.current,
            LiquidGlassPreset.ModalSheet,
            LocalLiquidGlassOverlayBackdrop.current,
        ),
        containerColor = liquidGlassContainerColor(
            MaterialTheme.colorScheme.surfaceContainer,
            backdrop = LocalLiquidGlassOverlayBackdrop.current,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .adaptiveContentWidth(maxWidth = 640.dp)
                .align(Alignment.CenterHorizontally)
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(27.dp))
                    }
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        "Add to playlist",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (selectedPlaylistIds.isEmpty()) "Choose one or more playlists" else "${selectedPlaylistIds.size} selected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (sanitizedPlaylists.isEmpty()) {
                Text(
                    "No playlists yet. Create your first playlist below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = playlistListMaxHeight),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
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
                        val rowColor by animateColorAsState(
                            targetValue = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            animationSpec = tween(ExpressiveMotion.Quick),
                            label = "playlistSelectionColor",
                        )
                        Surface(
                            onClick = {
                                selectedPlaylistIds = if (selected) {
                                    selectedPlaylistIds - playlist.id
                                } else {
                                    selectedPlaylistIds + playlist.id
                                }
                            },
                            shape = RoundedCornerShape(18.dp),
                            color = rowColor,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PlaylistCover(
                                    playlist = playlist,
                                    modifier = Modifier.size(52.dp),
                                    cornerRadius = 14.dp,
                                )
                                Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
                                    Text(
                                        playlist.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        when {
                                            isCheckingDuplicates && selected -> "$countLabel · Checking contents…"
                                            alreadyAdded -> "$countLabel · Already added"
                                            else -> countLabel
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (alreadyAdded) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Checkbox(checked = selected, onCheckedChange = null)
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = newPlaylistName,
                onValueChange = { newPlaylistName = it },
                label = { Text("New playlist name") },
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        enabled = newPlaylistName.isNotBlank(),
                        onClick = {
                            val cleanName = newPlaylistName.trim()
                            val existingPlaylist = sanitizedPlaylists.firstOrNull {
                                it.mode == "custom" && it.title.equals(cleanName, ignoreCase = true)
                            }
                            if (existingPlaylist == null) {
                                onCreate(cleanName)
                            } else {
                                selectedPlaylistIds = setOf(existingPlaylist.id)
                                requestAdd(setOf(existingPlaylist.id))
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Create playlist and add track")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { requestAdd(selectedPlaylistIds) },
                    enabled = selectedPlaylistIds.isNotEmpty() && !isCheckingDuplicates,
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
                            selectedPlaylistIds.isEmpty() -> "Add"
                            isCheckingDuplicates -> "Checking"
                            else -> "Add to ${selectedPlaylists.size}"
                        },
                    )
                }
            }
        }
    }
}

private enum class SeekDirection { REWIND, FORWARD }

@Composable
private fun FullPlayer(
    state: MusicPlayerState,
    progressState: StateFlow<PlaybackProgressState>,
    player: MusicPlayer,
    lyricsState: LyricsUiState,
    lyricsUiVersion: LyricsUiVersion = LyricsUiVersion.MODERN,
    lyricsAnimation: LyricsAnimation = LyricsAnimation.APPLE_FLUID,
    wavySeekbarEnabled: Boolean = true,
    currentTab: FullPlayerTab,
    onTabChange: (FullPlayerTab) -> Unit,
    onRetryLyrics: () -> Unit,
    onCollapse: () -> Unit,
    onOpenArtist: (String) -> Unit = {},
    isLiked: Boolean = false,
    onToggleLiked: () -> Unit = {},
    onDoubleTapLike: () -> Unit = {},
) {
    val track = state.current ?: return
    var lyricsFullscreen by remember(currentTab) { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(view, lyricsFullscreen) {
        val fullscreenActive = lyricsFullscreen
        val activity = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val previousBehavior = controller?.systemBarsBehavior
        if (fullscreenActive) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreenActive) {
                controller?.show(WindowInsetsCompat.Type.systemBars())
                previousBehavior?.let { controller?.systemBarsBehavior = it }
            }
        }
    }
    var showTrackMenu by remember(track.videoId, track.title) { mutableStateOf(false) }
    var artworkDragX by remember(track.videoId, track.title) { mutableFloatStateOf(0f) }
    var dismissDragY by remember(track.videoId, track.title) { mutableFloatStateOf(0f) }
    var isDismissDragging by remember { mutableStateOf(false) }
    var seekOverlayDirection by remember(track.videoId, track.title) { mutableStateOf<SeekDirection?>(null) }
    var seekOverlaySeconds by remember(track.videoId, track.title) { mutableIntStateOf(0) }
    var lastTapTimestamp by remember(track.videoId, track.title) { mutableLongStateOf(0L) }
    var lastTapSide by remember(track.videoId, track.title) { mutableStateOf<SeekDirection?>(null) }
    var lastLikeTapTimestamp by remember(track.videoId, track.title) { mutableLongStateOf(0L) }
    var seekResetJob by remember(track.videoId, track.title) { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val shownArtworkX by animateFloatAsState(
        artworkDragX,
        ExpressiveMotion.spatialSpring(),
        label = "fullPlayerArtworkX",
    )
    val shownDismissY by animateFloatAsState(
        targetValue = dismissDragY,
        animationSpec = if (isDismissDragging) snap() else ExpressiveMotion.spatialSpring(),
        label = "fullPlayerDismissY",
    )
    val swipeThreshold = with(LocalDensity.current) { 88.dp.toPx() }

    // Dominant cover-art color for the ambient background glow. Extracted
    // once per track through the shared Coil loader (normally a cache hit)
    // with Palette; any failure leaves the standard surface gradient.
    val context = LocalContext.current
    var ambientPrimary by remember(track.videoId, track.artworkUrl, track.title, track.artist) { mutableStateOf<Color?>(null) }
    var ambientSecondary by remember(track.videoId, track.artworkUrl, track.title, track.artist) { mutableStateOf<Color?>(null) }
    var ambientTertiary by remember(track.videoId, track.artworkUrl, track.title, track.artist) { mutableStateOf<Color?>(null) }
    LaunchedEffect(track.videoId, track.artworkUrl, track.title, track.artist) {
        val url = track.artworkUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .size(128)
                    .build()
                val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                    ?: return@runCatching
                val palette = Palette.from(bitmap).clearFilters().generate()
                val p = palette.vibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch
                val s = palette.lightVibrantSwatch ?: palette.darkVibrantSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch
                val t = palette.darkVibrantSwatch ?: palette.darkMutedSwatch ?: palette.dominantSwatch
                if (p != null) ambientPrimary = Color(p.rgb)
                if (s != null) ambientSecondary = Color(s.rgb)
                if (t != null) ambientTertiary = Color(t.rgb)
            }
        }
    }
    val ambientColor by animateColorAsState(
        targetValue = ambientPrimary ?: MaterialTheme.colorScheme.primary,
        animationSpec = tween(700),
        label = "playerAmbientColor",
    )
    val ambientCompanion by animateColorAsState(
        targetValue = ambientSecondary ?: androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.tertiary, ambientColor, 0.42f),
        animationSpec = tween(700),
        label = "playerAmbientCompanion",
    )
    val ambientDeep by animateColorAsState(
        targetValue = ambientTertiary ?: androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.secondary, ambientColor, 0.25f),
        animationSpec = tween(700),
        label = "playerAmbientDeep",
    )

    fun Modifier.playerVerticalSwipe(enabled: Boolean): Modifier = if (!enabled) this else pointerInput(track.videoId, track.title, currentTab) {
        detectVerticalDragGestures(
            onDragStart = { isDismissDragging = true },
            onDragCancel = {
                isDismissDragging = false
                dismissDragY = 0f
            },
            onDragEnd = {
                isDismissDragging = false
                when {
                    currentTab != FullPlayerTab.NOW_PLAYING && dismissDragY > swipeThreshold -> onTabChange(FullPlayerTab.NOW_PLAYING)
                    currentTab == FullPlayerTab.NOW_PLAYING && dismissDragY < -swipeThreshold -> onTabChange(FullPlayerTab.QUEUE)
                    currentTab == FullPlayerTab.NOW_PLAYING && dismissDragY > swipeThreshold -> onCollapse()
                }
                dismissDragY = 0f
            },
        ) { change, amount ->
            val updatedDrag = if (currentTab != FullPlayerTab.NOW_PLAYING) {
                (dismissDragY + amount).coerceAtLeast(0f)
            } else {
                dismissDragY + amount
            }
            if (updatedDrag != dismissDragY) change.consume()
            dismissDragY = updatedDrag
        }
    }

    val playerBackdrop = if (isLiquidGlassBackdropSupported()) rememberLayerBackdrop() else null
    CompositionLocalProvider(
        LocalLiquidGlassBackdrop provides playerBackdrop,
        LocalLiquidGlassOverlayBackdrop provides playerBackdrop,
    ) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = shownDismissY.coerceAtLeast(0f)
                val playerHeight = size.height.coerceAtLeast(1f)
                alpha = (1f - shownDismissY.coerceAtLeast(0f) / (playerHeight * 1.5f)).coerceIn(0.72f, 1f)
            }
            .playerVerticalSwipe(enabled = currentTab == FullPlayerTab.NOW_PLAYING),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val bgWidth = constraints.maxWidth.toFloat()
            val bgHeight = constraints.maxHeight.toFloat()
            val bgMaxDimension = maxOf(bgWidth, bgHeight, 1f)

            Box(Modifier.matchParentSize().liquidGlassSource(playerBackdrop)) {
            // Apple Music: Full-bleed scaled & deeply blurred artwork.
            // Dark veil in BOTH light and dark mode: the full player sits on
            // a dark scrim (see header comment below), so lyrics/controls use
            // the white overlay palette. A surface veil turns light mode into
            // near-white wash (white-on-white lyrics + dead cover tint).
            BackdropBlur(
                radius = 36.dp,
                modifier = Modifier.fillMaxSize(),
                veil = Color.Black,
                veilAlpha = 0.52f,
            ) {
                PlayerArtwork(
                    track = track,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.35f
                            scaleY = 1.35f
                            alpha = 0.9f
                        },
                    corner = 0.dp,
                    decodeSizePx = 200,
                )
            }

            // Apple Music: Vibrant chromatic ambient mesh blobs
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            0f to ambientColor.copy(alpha = 0.58f),
                            0.45f to ambientColor.copy(alpha = 0.22f),
                            1f to Color.Transparent,
                            center = androidx.compose.ui.geometry.Offset(
                                bgWidth * 0.25f,
                                bgHeight * 0.20f,
                            ),
                            radius = bgMaxDimension * 0.85f,
                        ),
                    ),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            0f to ambientCompanion.copy(alpha = 0.52f),
                            0.50f to ambientCompanion.copy(alpha = 0.20f),
                            1f to Color.Transparent,
                            center = androidx.compose.ui.geometry.Offset(
                                bgWidth * 0.88f,
                                bgHeight * 0.65f,
                            ),
                            radius = bgMaxDimension * 0.78f,
                        ),
                    ),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            0f to ambientDeep.copy(alpha = 0.42f),
                            0.55f to ambientDeep.copy(alpha = 0.14f),
                            1f to Color.Transparent,
                            center = androidx.compose.ui.geometry.Offset(
                                bgWidth * 0.15f,
                                bgHeight * 0.82f,
                            ),
                            radius = bgMaxDimension * 0.70f,
                        ),
                    ),
            )

            // Apple Music: Contrast scrim gradient (ensures text & controls are clear while preserving vibrant colors)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color.Black.copy(alpha = 0.35f),
                            0.28f to Color.Black.copy(alpha = 0.15f),
                            0.65f to Color.Black.copy(alpha = 0.40f),
                            1.00f to Color.Black.copy(alpha = 0.72f),
                        ),
                    ),
            )
            // Subtle edge vignette
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            0f to Color.Transparent,
                            0.65f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.30f),
                            center = androidx.compose.ui.geometry.Offset(
                                bgWidth * 0.50f,
                                bgHeight * 0.40f,
                            ),
                            radius = bgMaxDimension * 0.80f,
                        ),
                    ),
            )
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // ── Header: slimmer, calmer, premium ─────────────────────
                if (!lyricsFullscreen) Box(
                    Modifier
                        .fillMaxWidth()
                        .adaptiveContentWidth(maxWidth = 640.dp)
                        .height(52.dp)
                        .padding(horizontal = 20.dp)
                        .playerVerticalSwipe(enabled = currentTab != FullPlayerTab.NOW_PLAYING),
                ) {
                    // FullPlayer sits on a dark blurred-artwork scrim in both light and
                    // dark mode, so foreground must use the white overlay palette —
                    // never MaterialTheme onSurface (near-black in light mode).
                    IconButton(
                        onClick = {
                            if (currentTab != FullPlayerTab.NOW_PLAYING) {
                                onTabChange(FullPlayerTab.NOW_PLAYING)
                            } else {
                                onCollapse()
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(44.dp)
                            .clip(CircleShape)
                            .liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.FloatingControls)
                            .background(
                                liquidGlassContainerColor(Color.White.copy(alpha = 0.14f)),
                            ),
                    ) {
                        Icon(
                            if (currentTab != FullPlayerTab.NOW_PLAYING) Icons.Filled.ArrowBack else Icons.Filled.ExpandMore,
                            if (currentTab != FullPlayerTab.NOW_PLAYING) "Back to player" else "Minimize player",
                            modifier = Modifier.size(26.dp),
                            tint = Color.White.copy(alpha = 0.94f),
                        )
                    }
                    Column(
                        Modifier.align(Alignment.Center).padding(horizontal = 96.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            when (currentTab) {
                                FullPlayerTab.NOW_PLAYING -> "NOW PLAYING"
                                FullPlayerTab.LYRICS -> "LYRICS"
                                FullPlayerTab.QUEUE -> "PLAYING QUEUE"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.95f),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.9.sp,
                        )
                        Text(
                            if (currentTab == FullPlayerTab.LYRICS) {
                                "${track.title} • ${track.artist}"
                            } else {
                                state.sourceLabel.takeIf { it.isNotBlank() } ?: "LastWave"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.70f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = { showTrackMenu = true },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(44.dp)
                            .clip(CircleShape)
                            .liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.FloatingControls)
                            .background(
                                liquidGlassContainerColor(Color.White.copy(alpha = 0.14f)),
                            ),
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            "Song options",
                            modifier = Modifier.size(22.dp),
                            tint = Color.White.copy(alpha = 0.94f),
                        )
                    }
                }

                if (!lyricsFullscreen) Spacer(Modifier.height(6.dp))

                AnimatedContent(
                    targetState = currentTab,
                    modifier = Modifier.weight(1f).adaptiveContentWidth(maxWidth = 680.dp),
                    transitionSpec = {
                        if (targetState != FullPlayerTab.NOW_PLAYING) {
                            (slideInVertically(animationSpec = ExpressiveMotion.smoothSpring()) { it / 6 } +
                                fadeIn(tween(ExpressiveMotion.Standard))) togetherWith
                                (slideOutVertically(animationSpec = tween(ExpressiveMotion.Quick)) { -it / 6 } +
                                    fadeOut(tween(ExpressiveMotion.Quick)))
                        } else {
                            (slideInVertically(animationSpec = ExpressiveMotion.smoothSpring()) { -it / 6 } +
                                fadeIn(tween(ExpressiveMotion.Standard))) togetherWith
                                (slideOutVertically(animationSpec = tween(ExpressiveMotion.Quick)) { it / 6 } +
                                    fadeOut(tween(ExpressiveMotion.Quick)))
                        }
                    },
                    label = "playerTabContent",
                ) { tab ->
                    when (tab) {
                        FullPlayerTab.LYRICS -> {
                            PredictiveBackScreen(
                                enabled = currentTab == FullPlayerTab.LYRICS,
                                backgroundColor = Color.Transparent,
                                onBack = {
                                    if (lyricsFullscreen) {
                                        lyricsFullscreen = false
                                    } else {
                                        onTabChange(FullPlayerTab.NOW_PLAYING)
                                    }
                                },
                            ) {
                                if (lyricsUiVersion == LyricsUiVersion.MODERN) {
                                    ModernLyricsPanel(
                                        state = state,
                                        player = player,
                                        lyricsState = lyricsState,
                                        progressState = progressState,
                                        wavySeekbarEnabled = wavySeekbarEnabled,
                                        onRetry = onRetryLyrics,
                                        onToggleFullscreen = { lyricsFullscreen = !lyricsFullscreen },
                                        isFullscreen = lyricsFullscreen,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .adaptiveContentWidth(maxWidth = 720.dp),
                                    )
                                } else {
                                    LyricsPanel(
                                        state = state,
                                        progressState = progressState,
                                        player = player,
                                        lyricsState = lyricsState,
                                        lyricsAnimation = lyricsAnimation,
                                        wavySeekbarEnabled = wavySeekbarEnabled,
                                        onRetry = onRetryLyrics,
                                        onToggleFullscreen = { lyricsFullscreen = !lyricsFullscreen },
                                        isFullscreen = lyricsFullscreen,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .adaptiveContentWidth(maxWidth = 720.dp),
                                    )
                                }
                            }
                        }

                        FullPlayerTab.QUEUE -> {
                            PredictiveBackScreen(
                                enabled = currentTab == FullPlayerTab.QUEUE,
                                backgroundColor = Color.Transparent,
                                onBack = { onTabChange(FullPlayerTab.NOW_PLAYING) },
                            ) {
                                QueuePanel(
                                    state,
                                    player,
                                    Modifier
                                        .fillMaxSize()
                                        .adaptiveContentWidth(maxWidth = 720.dp)
                                        .padding(horizontal = 20.dp),
                                )
                            }
                        }

                        FullPlayerTab.NOW_PLAYING -> {
                                // ── Standard layout (lifted and balanced) ──────
                                Column(
                                    Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = 18.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    BoxWithConstraints(
                                        modifier = Modifier.fillMaxWidth().weight(1f),
                                        contentAlignment = BiasAlignment(0f, -0.55f),
                                    ) {
                                        val artworkSize = (minOf(maxWidth, maxHeight) - 6.dp)
                                            .coerceAtLeast(0.dp)
                                            .coerceAtMost(370.dp)

                                        val glowAlpha by animateFloatAsState(
                                            targetValue = if (state.isPlaying) 0.65f else 0.35f,
                                            animationSpec = tween(600),
                                            label = "artworkGlowAlpha",
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(artworkSize + 28.dp)
                                                .graphicsLayer {
                                                    translationX = shownArtworkX * 0.7f
                                                    alpha = glowAlpha
                                                }
                                                .background(
                                                    Brush.radialGradient(
                                                        0.0f to ambientColor.copy(alpha = 0.50f),
                                                        0.50f to ambientCompanion.copy(alpha = 0.22f),
                                                        1.0f to Color.Transparent,
                                                    ),
                                                    shape = CircleShape,
                                                ),
                                        )

                                        val artworkPlayingScale by animateFloatAsState(
                                            targetValue = if (state.isPlaying) 1.0f else 0.88f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMediumLow,
                                            ),
                                            label = "artworkPlayingScale",
                                        )

                                        Surface(
                                            shape = RoundedCornerShape(32.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.88f),
                                            tonalElevation = 6.dp,
                                            shadowElevation = if (state.isPlaying) 28.dp else 12.dp,
                                            modifier = Modifier
                                                .size(artworkSize)
                                                .graphicsLayer {
                                                    scaleX = artworkPlayingScale
                                                    scaleY = artworkPlayingScale
                                                    translationX = shownArtworkX
                                                    rotationZ = shownArtworkX / 80f
                                                }
                                                .pointerInput(track.videoId, track.title) {
                                                    awaitEachGesture {
                                                        val down = awaitFirstDown(requireUnconsumed = false)
                                                        var isDrag = false
                                                        val touchSlop = viewConfiguration.touchSlop
                                                        val initialX = down.position.x
                                                        val initialY = down.position.y

                                                        while (true) {
                                                            val event = awaitPointerEvent()
                                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break

                                                            if (!change.pressed) {
                                                                if (isDrag) {
                                                                    when {
                                                                        artworkDragX < -swipeThreshold -> player.next()
                                                                        artworkDragX > swipeThreshold -> player.previous()
                                                                    }
                                                                    artworkDragX = 0f
                                                                } else {
                                                                    val now = SystemClock.elapsedRealtime()
                                                                    val side = when {
                                                                        initialX < size.width * 0.34f -> SeekDirection.REWIND
                                                                        initialX > size.width * 0.66f -> SeekDirection.FORWARD
                                                                        else -> null
                                                                    }

                                                                    if (side == null) {
                                                                        // The center third owns Like only. Clear any pending
                                                                        // side sequence so it can never complete a seek.
                                                                        lastTapSide = null
                                                                        lastTapTimestamp = 0L
                                                                        if (lastLikeTapTimestamp != 0L && now - lastLikeTapTimestamp < 450L) {
                                                                            lastLikeTapTimestamp = 0L
                                                                            onDoubleTapLike()
                                                                        } else {
                                                                            lastLikeTapTimestamp = now
                                                                        }
                                                                    } else {
                                                                        // Preserve the existing edge double-tap seek behavior.
                                                                        // An edge tap cannot complete a center Like sequence.
                                                                        lastLikeTapTimestamp = 0L
                                                                        if (lastTapSide != side) {
                                                                            seekResetJob?.cancel()
                                                                            seekOverlayDirection = null
                                                                            lastTapSide = side
                                                                            lastTapTimestamp = now
                                                                        } else if (now - lastTapTimestamp < 450L) {
                                                                            val newSeconds = if (seekOverlayDirection == side) seekOverlaySeconds + 5 else 5
                                                                            seekOverlaySeconds = newSeconds
                                                                            seekOverlayDirection = side
                                                                            lastTapTimestamp = now
                                                                            val deltaMs = if (side == SeekDirection.FORWARD) 5_000L else -5_000L
                                                                            val newPos = (player.state.value.positionMs + deltaMs).coerceIn(0L, player.state.value.durationMs.coerceAtLeast(0L))
                                                                            player.seekTo(newPos)

                                                                            seekResetJob?.cancel()
                                                                            seekResetJob = coroutineScope.launch {
                                                                                delay(700L)
                                                                                seekOverlayDirection = null
                                                                                lastTapSide = null
                                                                            }
                                                                        } else {
                                                                            lastTapTimestamp = now
                                                                            lastTapSide = side
                                                                            seekOverlayDirection = null
                                                                        }
                                                                    }
                                                                }
                                                                break
                                                            }

                                                            if (change.isConsumed) {
                                                                artworkDragX = 0f
                                                                lastLikeTapTimestamp = 0L
                                                                lastTapTimestamp = 0L
                                                                lastTapSide = null
                                                                break
                                                            }

                                                            val dx = change.position.x - initialX
                                                            val dy = change.position.y - initialY
                                                            if (!isDrag) {
                                                                if (kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                                                                    isDrag = true
                                                                    lastLikeTapTimestamp = 0L
                                                                    lastTapTimestamp = 0L
                                                                    lastTapSide = null
                                                                    change.consume()
                                                                }
                                                            } else {
                                                                change.consume()
                                                                artworkDragX = dx
                                                            }
                                                        }
                                                    }
                                                },
                                        ) {
                                            Box(Modifier.fillMaxSize()) {
                                                PlayerArtwork(track, Modifier.fillMaxSize(), 32.dp)

                                                androidx.compose.animation.AnimatedVisibility(
                                                    visible = seekOverlayDirection == SeekDirection.REWIND,
                                                    enter = fadeIn(tween(100)) + scaleIn(ExpressiveMotion.spatialSpring(), initialScale = 0.88f),
                                                    exit = fadeOut(tween(200)),
                                                    modifier = Modifier
                                                        .align(Alignment.CenterStart)
                                                        .fillMaxHeight()
                                                        .fillMaxWidth(0.5f),
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .clip(RoundedCornerShape(topStart = 32.dp, bottomStart = 32.dp, topEnd = 120.dp, bottomEnd = 120.dp))
                                                            .background(Color.Black.copy(alpha = 0.58f)),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Column(
                                                            horizontalAlignment = Alignment.CenterHorizontally,
                                                            verticalArrangement = Arrangement.Center,
                                                        ) {
                                                            Surface(
                                                                shape = CircleShape,
                                                                color = Color.White.copy(alpha = 0.22f),
                                                                modifier = Modifier.size(52.dp),
                                                            ) {
                                                                Box(contentAlignment = Alignment.Center) {
                                                                    Icon(
                                                                        Icons.Filled.FastRewind,
                                                                        contentDescription = "Seek rewind",
                                                                        tint = Color.White,
                                                                        modifier = Modifier.size(28.dp),
                                                                    )
                                                                }
                                                            }
                                                            Spacer(Modifier.height(6.dp))
                                                            Text(
                                                                "-${seekOverlaySeconds}s",
                                                                style = MaterialTheme.typography.titleMedium,
                                                                fontWeight = FontWeight.ExtraBold,
                                                                color = Color.White,
                                                            )
                                                        }
                                                    }
                                                }

                                                androidx.compose.animation.AnimatedVisibility(
                                                    visible = seekOverlayDirection == SeekDirection.FORWARD,
                                                    enter = fadeIn(tween(100)) + scaleIn(ExpressiveMotion.spatialSpring(), initialScale = 0.88f),
                                                    exit = fadeOut(tween(200)),
                                                    modifier = Modifier
                                                        .align(Alignment.CenterEnd)
                                                        .fillMaxHeight()
                                                        .fillMaxWidth(0.5f),
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .clip(RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp, topStart = 120.dp, bottomStart = 120.dp))
                                                            .background(Color.Black.copy(alpha = 0.58f)),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Column(
                                                            horizontalAlignment = Alignment.CenterHorizontally,
                                                            verticalArrangement = Arrangement.Center,
                                                        ) {
                                                            Surface(
                                                                shape = CircleShape,
                                                                color = Color.White.copy(alpha = 0.22f),
                                                                modifier = Modifier.size(52.dp),
                                                            ) {
                                                                Box(contentAlignment = Alignment.Center) {
                                                                    Icon(
                                                                        Icons.Filled.FastForward,
                                                                        contentDescription = "Seek forward",
                                                                        tint = Color.White,
                                                                        modifier = Modifier.size(28.dp),
                                                                    )
                                                                }
                                                            }
                                                            Spacer(Modifier.height(6.dp))
                                                            Text(
                                                                "+${seekOverlaySeconds}s",
                                                                style = MaterialTheme.typography.titleMedium,
                                                                fontWeight = FontWeight.ExtraBold,
                                                                color = Color.White,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .graphicsLayer { translationY = -8.dp.toPx() },
                                    ) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Text(
                                                    track.title,
                                                    style = MaterialTheme.typography.headlineSmall.copy(
                                                        letterSpacing = (-0.35).sp,
                                                        fontWeight = FontWeight.ExtraBold,
                                                    ),
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    modifier = Modifier
                                                        .weight(1f, fill = false)
                                                        .basicMarquee(iterations = Int.MAX_VALUE),
                                                )
                                                spatialIndicatorLabel(state.audioCodec)?.let { SpatialAudioChip(it) }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            val splitArtists = remember(track.artist) {
                                                com.lastwave.app.util.ArtistHelper.splitArtists(track.artist)
                                            }
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(0.dp),
                                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                            ) {
                                                splitArtists.forEachIndexed { index, artName ->
                                                    Text(
                                                        text = artName,
                                                        style = MaterialTheme.typography.titleMedium.copy(
                                                            fontSize = 17.sp,
                                                            fontWeight = FontWeight.Medium,
                                                        ),
                                                        color = Color.White.copy(alpha = 0.72f),
                                                        modifier = Modifier
                                                            .clickable(
                                                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                                                indication = null,
                                                            ) { onOpenArtist(artName) },
                                                    )
                                                    if (index < splitArtists.lastIndex) {
                                                        Text(
                                                            text = ", ",
                                                            style = MaterialTheme.typography.titleMedium.copy(
                                                                fontSize = 17.sp,
                                                                fontWeight = FontWeight.Normal,
                                                            ),
                                                            color = Color.White.copy(alpha = 0.50f),
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Spacer(Modifier.width(12.dp))

                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            val likeInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                            val isLikePressed by likeInteraction.collectIsPressedAsState()
                                            val likeScale by animateFloatAsState(
                                                targetValue = if (isLikePressed) 0.78f else 1.0f,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                ),
                                                label = "likeScale",
                                            )
                                            LiquidGlassSurface(
                                                glassModifier = Modifier.liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.PlayerControls, interactionSource = likeInteraction),
                                                onClick = onToggleLiked,
                                                interactionSource = likeInteraction,
                                                shape = CircleShape,
                                                color = liquidGlassContainerColor(if (isLiked) {
                                                    Color.White.copy(alpha = 0.92f)
                                                } else {
                                                    Color.White.copy(alpha = 0.14f)
                                                }),
                                                contentColor = if (isLiked) {
                                                    Color(0xFFE91E63)
                                                } else {
                                                    Color.White.copy(alpha = 0.85f)
                                                },
                                                tonalElevation = 0.dp,
                                                shadowElevation = 0.dp,
                                                modifier = Modifier
                                                    .size(46.dp)
                                                    .graphicsLayer {
                                                        scaleX = likeScale
                                                        scaleY = likeScale
                                                    },
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                                        contentDescription = if (isLiked) "Unlike song" else "Like song",
                                                        modifier = Modifier.size(24.dp),
                                                    )
                                                }
                                            }
                                            val lyricsInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                            val isLyricsPressed by lyricsInteraction.collectIsPressedAsState()
                                            val lyricsScale by animateFloatAsState(
                                                targetValue = if (isLyricsPressed) 0.82f else 1.0f,
                                                animationSpec = ExpressiveMotion.spatialSpring(),
                                                label = "lyricsScale",
                                            )
                                            LiquidGlassSurface(
                                                glassModifier = Modifier.liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.PlayerControls, interactionSource = lyricsInteraction),
                                                onClick = { onTabChange(FullPlayerTab.LYRICS) },
                                                interactionSource = lyricsInteraction,
                                                shape = CircleShape,
                                                color = liquidGlassContainerColor(Color.White.copy(alpha = 0.14f)),
                                                contentColor = Color.White.copy(alpha = 0.90f),
                                                tonalElevation = 0.dp,
                                                shadowElevation = 0.dp,
                                                modifier = Modifier
                                                    .size(46.dp)
                                                    .graphicsLayer {
                                                        scaleX = lyricsScale
                                                        scaleY = lyricsScale
                                                    },
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        Icons.Filled.FormatQuote,
                                                        contentDescription = "Show lyrics",
                                                        modifier = Modifier.size(24.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    SeekBar(
                                        progressState = progressState,
                                        isPlaying = state.isPlaying,
                                        trackKey = track.videoId ?: "${track.artist}|${track.title}",
                                        wavyEnabled = wavySeekbarEnabled,
                                        onSeek = player::seekTo,
                                        // Background is always a dark scrim: use the white
                                        // overlay palette so time labels + waves stay
                                        // readable in light mode too.
                                        isTranslucent = true,
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    MainControls(state, player, isTranslucent = true)
                                    }
                                    Spacer(Modifier.height(24.dp))
                                    PlayerUtilityControls(state, player, isTranslucent = true)
                                }
                        }
                    }
                }
                state.error?.takeUnless { lyricsFullscreen }?.let { message ->
                    Surface(
                        onClick = player::retry,
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.ErrorOutline, null, modifier = Modifier.size(21.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(
                                    message,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "Tap to retry",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                                )
                            }
                            IconButton(
                                onClick = player::clearError,
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Filled.Close, "Dismiss error", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    if (showTrackMenu) {
        TrackContextMenuSheet(
            target = TrackMenuTarget.Track(track.title, track.artist, ""),
            capabilities = TrackMenuCapabilities(
                showCopyActions = true,
                showDeleteScrobble = false,
            ),
            playableTrack = track,
            onDismiss = { showTrackMenu = false },
            onPlayInLastWave = { player.play(track, sourceLabel = state.sourceLabel) },
        )
    }
}
}

@Composable
internal fun PlayerProgressSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource,
) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.20f else 0.12f)
    val range = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val fraction = ((value - valueRange.start) / range).coerceIn(0f, 1f)

    // Playback progress is time, not directional content: pin to LTR so the
    // Material Slider's touch mapping always matches the LTR custom drawing,
    // even in RTL locales (Arabic, …) where Slider would otherwise mirror.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier.drawBehind {
            val inset = 10.dp.toPx()
            val startX = inset
            val endX = (size.width - inset).coerceAtLeast(startX)
            val activeEndX = startX + ((endX - startX) * fraction)
            val centerY = size.height / 2f
            drawLine(
                color = inactive,
                start = androidx.compose.ui.geometry.Offset(startX, centerY),
                end = androidx.compose.ui.geometry.Offset(endX, centerY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            if (activeEndX > startX) {
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            primary.copy(alpha = if (enabled) 1f else 0.42f),
                            tertiary.copy(alpha = if (enabled) 0.92f else 0.36f),
                        ),
                        startX = startX,
                        endX = activeEndX,
                    ),
                    start = androidx.compose.ui.geometry.Offset(startX, centerY),
                    end = androidx.compose.ui.geometry.Offset(activeEndX, centerY),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        },
        colors = SliderDefaults.colors(
            thumbColor = primary,
            activeTrackColor = Color.Transparent,
            inactiveTrackColor = Color.Transparent,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
            disabledThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.34f),
            disabledActiveTrackColor = Color.Transparent,
            disabledInactiveTrackColor = Color.Transparent,
            disabledActiveTickColor = Color.Transparent,
            disabledInactiveTickColor = Color.Transparent,
        ),
    )
    }
}

@Composable
private fun SeekBar(
    progressState: StateFlow<PlaybackProgressState>,
    isPlaying: Boolean,
    trackKey: String?,
    wavyEnabled: Boolean = true,
    onSeek: (Long) -> Unit,
    isTranslucent: Boolean = false,
) {
    val progress by progressState.collectAsStateWithLifecycle()

    if (wavyEnabled) {
        WavySeekBar(
            positionMs = progress.positionMs,
            durationMs = progress.durationMs,
            isPlaying = isPlaying,
            onSeek = onSeek,
            isTranslucent = isTranslucent,
            trackKey = trackKey,
        )
        return
    }

    // Current-gesture fraction only; null = finger off, show live position.
    // Nullable (never a stale 0f) so a press without movement seeks nowhere
    // and a gesture that ends without onValueChangeFinished can't pin the bar.
    val seekInteraction = remember(trackKey) { MutableInteractionSource() }
    val frameworkDragging by seekInteraction.collectIsDraggedAsState()
    var dragFraction by remember(trackKey) { mutableStateOf<Float?>(null) }
    // Heal a gesture that ended without the finished callback: drop the dead
    // value, resume live position. Never seeks; commit is only below.
    LaunchedEffect(frameworkDragging, trackKey) {
        if (!frameworkDragging) dragFraction = null
    }

    val boundedDurationMs = progress.durationMs.coerceAtLeast(0L)
    val currentFraction = if (boundedDurationMs > 0L) {
        (progress.positionMs.toDouble() / boundedDurationMs.toDouble()).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val fraction = (dragFraction ?: currentFraction).coerceIn(0f, 1f)
    val shownMs = dragFraction?.let {
        (it * boundedDurationMs).toLong().coerceIn(0L, boundedDurationMs)
    } ?: progress.positionMs.coerceIn(0L, boundedDurationMs)

    val primaryColor = if (isTranslucent) Color.White else MaterialTheme.colorScheme.primary
    val inactiveColor = if (isTranslucent) {
        Color.White.copy(alpha = 0.20f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    }
    val textColor = if (isTranslucent) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f)

    // Same RTL pin as above: custom Canvas draws LTR, invisible Slider must match it.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(44.dp)) {
                val width = size.width
                val height = size.height
                val centerY = height / 2f
                val trackHeightPx = 14.dp.toPx()
                val cornerRadius = CornerRadius(trackHeightPx / 2f, trackHeightPx / 2f)
                val thumbWidthPx = 5.dp.toPx()
                val thumbHeightPx = 38.dp.toPx()
                val thumbClearancePx = 9.dp.toPx()
                val rawThumbCenterX = fraction * width
                val thumbCenterX = if (width > thumbWidthPx) {
                    rawThumbCenterX.coerceIn(thumbWidthPx / 2f, width - thumbWidthPx / 2f)
                } else {
                    width / 2f
                }
                val activeEndX = (thumbCenterX - thumbClearancePx).coerceIn(0f, width)
                val inactiveStartX = (thumbCenterX + thumbClearancePx).coerceIn(0f, width)

                // Thick active capsule ending before the vertical thumb.
                if (activeEndX > 0f) {
                    drawRoundRect(
                        color = primaryColor,
                        topLeft = Offset(0f, centerY - trackHeightPx / 2f),
                        size = Size(activeEndX, trackHeightPx),
                        cornerRadius = cornerRadius,
                    )
                }

                // Thick inactive capsule starting after the vertical thumb.
                if (inactiveStartX < width) {
                    drawRoundRect(
                        color = inactiveColor,
                        topLeft = Offset(inactiveStartX, centerY - trackHeightPx / 2f),
                        size = Size(width - inactiveStartX, trackHeightPx),
                        cornerRadius = cornerRadius,
                    )
                }

                // Small endpoint marker from the reference design.
                val endpointX = width - trackHeightPx / 2f
                if (inactiveStartX < endpointX) {
                    drawCircle(
                        color = primaryColor.copy(alpha = 0.86f),
                        radius = 2.dp.toPx(),
                        center = Offset(endpointX, centerY),
                    )
                }

                // Tall vertical pill thumb with clear space on both sides.
                val thumbX = thumbCenterX - thumbWidthPx / 2f
                val thumbCornerRadius = CornerRadius(thumbWidthPx / 2f, thumbWidthPx / 2f)

                drawRoundRect(
                    color = primaryColor,
                    topLeft = Offset(thumbX, centerY - thumbHeightPx / 2f),
                    size = Size(thumbWidthPx, thumbHeightPx),
                    cornerRadius = thumbCornerRadius,
                )
            }

            // Invisible Material interaction layer: custom visuals, reliable seeking semantics.
            Slider(
                value = fraction,
                onValueChange = { dragFraction = it },
                onValueChangeFinished = {
                    // Commit only this gesture's value; no value = no seek.
                    val target = dragFraction?.let {
                        (it * boundedDurationMs).toLong().coerceIn(0L, boundedDurationMs)
                    }
                    dragFraction = null
                    if (target != null && boundedDurationMs > 0L) {
                        onSeek(target)
                    }
                },
                valueRange = 0f..1f,
                enabled = boundedDurationMs > 0L,
                interactionSource = seekInteraction,
                modifier = Modifier.fillMaxSize(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.Transparent,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                    disabledThumbColor = Color.Transparent,
                    disabledActiveTrackColor = Color.Transparent,
                    disabledInactiveTrackColor = Color.Transparent,
                    disabledActiveTickColor = Color.Transparent,
                    disabledInactiveTickColor = Color.Transparent,
                ),
            )
        }

        Spacer(Modifier.height(2.dp))

        // Time labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(shownMs),
                style = if (isTranslucent) MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium) else MaterialTheme.typography.labelMedium,
                color = textColor,
            )
            Text(
                text = "−${formatTime((boundedDurationMs - shownMs).coerceAtLeast(0))}",
                style = if (isTranslucent) MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium) else MaterialTheme.typography.labelMedium,
                color = textColor,
            )
        }
    }
    }
}

@Composable
private fun MainControls(state: MusicPlayerState, player: MusicPlayer, isTranslucent: Boolean = false) {
    val prevInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPrevPressed by prevInteraction.collectIsPressedAsState()
    val prevScale by animateFloatAsState(if (isPrevPressed) 0.85f else 1.0f, ExpressiveMotion.spatialSpring(), label = "prevScale")

    val playInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPlayPressed by playInteraction.collectIsPressedAsState()
    val playScale by animateFloatAsState(
        targetValue = if (isPlayPressed) 0.88f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "playScale",
    )

    val nextInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isNextPressed by nextInteraction.collectIsPressedAsState()
    val nextScale by animateFloatAsState(if (isNextPressed) 0.85f else 1.0f, ExpressiveMotion.spatialSpring(), label = "nextScale")

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (isTranslucent) 18.dp else 16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiquidGlassSurface(
            glassModifier = Modifier.liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.PlayerControls, interactionSource = prevInteraction),
            onClick = player::previous,
            interactionSource = prevInteraction,
            shape = CircleShape,
            color = liquidGlassContainerColor(if (isTranslucent) Color.White.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.40f)),
            contentColor = if (isTranslucent) Color.White.copy(alpha = 0.94f) else MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier
                .size(if (isTranslucent) 54.dp else 58.dp)
                .graphicsLayer {
                    scaleX = prevScale
                    scaleY = prevScale
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(if (isTranslucent) 28.dp else 31.dp))
            }
        }
        LiquidGlassSurface(
            glassModifier = Modifier.liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.FloatingControls, interactionSource = playInteraction),
            onClick = player::togglePlayPause,
            interactionSource = playInteraction,
            shape = CircleShape,
            color = liquidGlassContainerColor(if (isTranslucent) Color.White else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)),
            contentColor = if (LocalLiquidGlass.current) {
                if (isTranslucent) Color.White else MaterialTheme.colorScheme.primary
            } else if (isTranslucent) Color.Black else MaterialTheme.colorScheme.onPrimary,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier
                .size(if (isTranslucent) 72.dp else 76.dp)
                .graphicsLayer {
                    scaleX = playScale
                    scaleY = playScale
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (state.isBuffering) {
                    ExpressiveInlineLoadingIndicator(
                        size = if (isTranslucent) 28.dp else 30.dp,
                        color = androidx.compose.material3.LocalContentColor.current,
                        strokeWidth = 3.dp,
                    )
                } else {
                    AnimatedPlayPauseIcon(state.isPlaying, Modifier.size(if (isTranslucent) 36.dp else 39.dp))
                }
            }
        }
        LiquidGlassSurface(
            glassModifier = Modifier.liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.PlayerControls, interactionSource = nextInteraction),
            onClick = player::next,
            interactionSource = nextInteraction,
            shape = CircleShape,
            color = liquidGlassContainerColor(if (isTranslucent) Color.White.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.40f)),
            contentColor = if (isTranslucent) Color.White.copy(alpha = 0.94f) else MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier
                .size(if (isTranslucent) 54.dp else 58.dp)
                .graphicsLayer {
                    scaleX = nextScale
                    scaleY = nextScale
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.SkipNext, "Next", Modifier.size(if (isTranslucent) 28.dp else 31.dp))
            }
        }
    }
}

@Composable
private fun PlayerModeButton(
    active: Boolean,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    background: Color,
    foreground: Color,
    iconSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f, ExpressiveMotion.spatialSpring(), label = "modePress",
    )
    val container by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.86f)
        else liquidGlassContainerColor(background), label = "modeContainer",
    )
    val content by animateColorAsState(
        if (active) MaterialTheme.colorScheme.onPrimaryContainer else foreground, label = "modeContent",
    )
    LiquidGlassSurface(
glassModifier = Modifier.liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.PlayerControls, interactionSource = interaction),
        onClick = onClick,
        shape = CircleShape,
        color = container,
        contentColor = content,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .semantics {
                selected = active
                stateDescription = description
            },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(iconSize))
            if (active) {
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp)
                        .size(4.dp).background(content, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun PlayerUtilityControls(state: MusicPlayerState, player: MusicPlayer, isTranslucent: Boolean = false) {
    var showSignalPath by remember { mutableStateOf(false) }
    val signalPath by player.signalPath.collectAsStateWithLifecycle()
    val usbDac by player.usbDacState.collectAsStateWithLifecycle()
    val edgeButtonBackground = if (isTranslucent) {
        Color.White.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.40f)
    }
    val edgeButtonContent = if (isTranslucent) {
        Color.White.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f)
    }
    val qualityButtonBackground = if (isTranslucent) {
        Color.White.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
    }
    val qualityButtonContent = if (isTranslucent) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (isTranslucent) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerModeButton(
            active = state.shuffleEnabled,
            description = if (state.shuffleEnabled) "Shuffle on" else "Shuffle off",
            icon = Icons.Filled.Shuffle,
            onClick = player::toggleShuffle,
            background = edgeButtonBackground,
            foreground = edgeButtonContent,
            iconSize = if (isTranslucent) 19.dp else 20.dp,
            modifier = Modifier.weight(1f).height(if (isTranslucent) 44.dp else 48.dp),
        )
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = liquidGlassContainerColor(qualityButtonBackground),
            contentColor = qualityButtonContent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            onClick = { showSignalPath = true },
            modifier = Modifier.weight(1.8f).height(if (isTranslucent) 44.dp else 48.dp)
                .liquidGlassChrome(RoundedCornerShape(24.dp), LocalLiquidGlass.current, LiquidGlassPreset.PlayerControls),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.HighQuality,
                    null,
                    tint = if (isTranslucent) Color.White.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    qualityBadgeLabel(state),
                    style = if (isTranslucent) MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp) else MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = when {
                        signalPath.bitPerfect -> Color(0xFFE6C15A)
                        isTranslucent -> Color.White.copy(alpha = 0.90f)
                        else -> Color.Unspecified
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
        }
        PlayerModeButton(
            active = state.repeatMode != Player.REPEAT_MODE_OFF,
            description = when (state.repeatMode) {
                Player.REPEAT_MODE_ONE -> "Repeat one"
                Player.REPEAT_MODE_ALL -> "Repeat all"
                else -> "Repeat off"
            },
            icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            onClick = player::cycleRepeatMode,
            background = edgeButtonBackground,
            foreground = edgeButtonContent,
            iconSize = if (isTranslucent) 19.dp else 20.dp,
            modifier = Modifier.weight(1f).height(if (isTranslucent) 44.dp else 48.dp),
        )
    }
    if (showSignalPath) {
        val dac = usbDac.dac
        SignalPathDialog(
            report = signalPath,
            needsUsbPermission = dac != null && dac.hasUsbPeripheral && !dac.usbPermissionGranted,
            onRequestUsbAccess = player::requestUsbPermission,
            onDismiss = { showSignalPath = false },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun QueuePanel(state: MusicPlayerState, player: MusicPlayer, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    // Stable keys so animateItem() can animate moves instead of treating
    // every shifted row as a new item. Duplicates get occurrence suffixes.
    val queueKeys = remember(state.queue) {
        val counts = mutableMapOf<String, Int>()
        state.queue.map { item ->
            val base = item.videoId ?: item.playbackUrl
                ?: "${item.artist}|${item.title}|${item.album}"
            val n = counts.getOrDefault(base, 0)
            counts[base] = n + 1
            "$base#$n"
        }
    }
    // Abort a stale drag if the queue itself changes underneath us
    // (track ended, clear-upcoming, fresh playQueue, …).
    LaunchedEffect(state.queue.size) {
        if (draggingIndex >= state.queue.size) {
            draggingIndex = -1
            dragOffsetY = 0f
        }
    }
    // Follow the now-playing song in long queues: auto-scroll on every track
    // change (and on first open), unless the user is dragging or has manually
    // scrolled within the last few seconds. The eye button still jumps on demand.
    var userScrollHoldUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) userScrollHoldUntil = System.currentTimeMillis() + 3_000L
    }
    LaunchedEffect(state.currentIndex, state.queue.size) {
        if (draggingIndex != -1 || state.queue.isEmpty()) return@LaunchedEffect
        if (System.currentTimeMillis() < userScrollHoldUntil) return@LaunchedEffect
        val target = state.currentIndex.coerceIn(0, state.queue.size - 1)
        runCatching { listState.animateScrollToItem(target) }
    }
    // True upcoming play order (shuffle/repeat aware). Under shuffle the list
    // below stays in playlist order, so without this strip you can never tell
    // what plays next — and Play-next adds look "lost".
    val shuffleUpcoming = remember(state.queue, state.currentIndex, state.shuffleEnabled, state.repeatMode) {
        if (state.shuffleEnabled) runCatching { player.peekUpcomingIndices(3) }.getOrDefault(emptyList())
        else emptyList()
    }.filter { it in state.queue.indices && it != state.currentIndex }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Up next",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    if (state.isEndlessQueue) {
                        "Unlimited songs · ${state.currentIndex.coerceAtLeast(0) + 1} playing"
                    } else {
                        "${state.queue.size} songs · ${state.currentIndex.coerceAtLeast(0) + 1} playing"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.70f),
                )
                if (state.queue.size > 1) {
                    Text(
                        stringResource(com.lastwave.app.R.string.queue_rearrange_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                }
            }
            IconButton(
                onClick = {
                    userScrollHoldUntil = 0L
                    scope.launch { listState.animateScrollToItem(state.currentIndex.coerceAtLeast(0)) }
                },
                enabled = state.currentIndex >= 0 && state.currentIndex < state.queue.size,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.14f)),
            ) {
                Icon(
                    Icons.Filled.Visibility,
                    "Scroll to current song",
                    tint = Color.White.copy(alpha = 0.90f),
                )
            }

        }
        if (shuffleUpcoming.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "Up next in shuffle order",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.60f),
                    fontWeight = FontWeight.Bold,
                )
                shuffleUpcoming.forEachIndexed { order, queueIndex ->
                    val item = state.queue[queueIndex]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { player.seekToQueueItem(queueIndex) }
                            .padding(horizontal = 4.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${order + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(20.dp),
                        )
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(
                                item.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.92f),
                            )
                            Text(
                                item.artist,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.60f),
                            )
                        }
                        Text(
                            "#${queueIndex + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.40f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(state.queue, key = { index, _ -> queueKeys.getOrNull(index) ?: index.toString() }) { index, item ->
                val isCurrent = index == state.currentIndex
                val isDragging = index == draggingIndex
                val stableKey = queueKeys.getOrNull(index) ?: index.toString()
                val dismissState = androidx.compose.material3.rememberSwipeToDismissBoxState(
                    confirmValueChange = { dismissValue ->
                        if (dismissValue != androidx.compose.material3.SwipeToDismissBoxValue.Settled) {
                            player.removeQueueItem(index)
                            true
                        } else {
                            false
                        }
                    }
                )
                androidx.compose.material3.SwipeToDismissBox(
                    state = dismissState,
                    modifier = Modifier.animateItem(),
                    backgroundContent = {
                        val alignment = if (dismissState.dismissDirection == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd
                        Box(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                            contentAlignment = alignment
                        ) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    },
                    content = {
                LiquidGlassSurface(
                    glassModifier = Modifier.liquidGlassChrome(RoundedCornerShape(20.dp), LocalLiquidGlass.current),
                    onClick = { player.seekToQueueItem(index) },
                    shape = RoundedCornerShape(20.dp),
                    color = liquidGlassContainerColor(
                        if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.86f),
                    ),
                    contentColor = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragging) dragOffsetY else 0f
                            shadowElevation = if (isDragging) 18f else 0f
                            val s = if (isDragging) 1.025f else 1f
                            scaleX = s
                            scaleY = s
                            alpha = if (isDragging) 0.96f else 1f
                        },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(30.dp),
                        )
                        PlayerArtwork(item, Modifier.size(50.dp), 13.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                item.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                            )
                            Text(
                                item.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (isCurrent) {
                            Icon(
                                Icons.Filled.GraphicEq,
                                "Currently playing",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Icon(
                            Icons.Filled.DragHandle,
                            stringResource(com.lastwave.app.R.string.queue_drag_hint),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDragging) 1f else 0.6f),
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(40.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isDragging) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                    else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                                )
                                .padding(8.dp)
                                .pointerInput(stableKey) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggingIndex = index
                                            dragOffsetY = 0f
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDragEnd = {
                                            draggingIndex = -1
                                            dragOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            draggingIndex = -1
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val source = draggingIndex
                                            if (source < 0) return@detectDragGesturesAfterLongPress
                                            dragOffsetY += dragAmount.y
                                            val layoutInfo = listState.layoutInfo
                                            val draggedInfo = layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.index == source }
                                                ?: return@detectDragGesturesAfterLongPress
                                            val draggedCenter = draggedInfo.offset + draggedInfo.size / 2 + dragOffsetY.toInt()
                                            val target = layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                                info.index != source &&
                                                    info.index in state.queue.indices &&
                                                    draggedCenter in info.offset..(info.offset + info.size)
                                            }?.index
                                            if (target != null && target != source) {
                                                player.moveQueueItem(source, target)
                                                // Keep the row glued under the finger across
                                                // the layout shift caused by the move.
                                                val targetInfo = layoutInfo.visibleItemsInfo
                                                    .firstOrNull { it.index == target }
                                                if (targetInfo != null) {
                                                    dragOffsetY += (draggedInfo.offset - targetInfo.offset).toFloat()
                                                }
                                                draggingIndex = target
                                                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                            }
                                            // Edge auto-scroll while dragging.
                                            val viewportStart = layoutInfo.viewportStartOffset
                                            val viewportEnd = layoutInfo.viewportEndOffset
                                            val edgeZone = 180
                                            when {
                                                draggedCenter < viewportStart + edgeZone ->
                                                    scope.launch { listState.scrollBy(-28f) }
                                                draggedCenter > viewportEnd - edgeZone ->
                                                    scope.launch { listState.scrollBy(28f) }
                                            }
                                        },
                                    )
                                },
                        )
                    }
                }
                }
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun PlayerArtwork(
    track: PlayableTrack,
    modifier: Modifier,
    corner: androidx.compose.ui.unit.Dp,
    decodeSizePx: Int? = null,
) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        ArtworkImage(
            name = track.title,
            artist = track.artist,
            embeddedUrl = track.artworkUrl,
            fallbackIcon = Icons.Filled.MusicNote,
            modifier = Modifier.fillMaxSize(),
            decodeSizePx = decodeSizePx,
        )
    }
}

internal fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun SpatialAudioChip(label: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color(0xFF00A3E0),
        contentColor = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.6.sp,
            ),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            maxLines = 1,
        )
    }
}

private fun PlayableTrack.toGeneratedTrack() = com.lastwave.app.data.generate.GeneratedTrack(
    name = title,
    artist = artist,
    artworkUrl = artworkUrl,
    album = album,
    url = videoId?.let { "https://music.youtube.com/watch?v=$it" }.orEmpty(),
)
