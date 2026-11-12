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
import kotlinx.coroutines.FlowPreview
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
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
import com.lastwave.app.playback.isLocalTrack
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


@HiltViewModel
class PlayerViewModel @Inject constructor(
    val player: MusicPlayer,
    private val playlistRepository: PlaylistRepository,
    private val lyricsRepository: LyricsRepository,
    private val canvasRepository: com.lastwave.app.data.canvas.CanvasRepository,
    private val settingsPreferences: com.lastwave.app.data.local.SettingsPreferences,
    val navigator: com.lastwave.app.ui.navigation.ArtistAlbumNavigator,
    val genreExplorer: com.lastwave.app.ui.genres.GenreExplorer,
    val mixLauncher: com.lastwave.app.ui.generate.MixLauncher,
    private val ytMusicLibraryManager: com.lastwave.app.data.ytmusic.YtMusicLibraryManager,
    private val trackLikeController: com.lastwave.app.data.playlist.TrackLikeController,
    private val downloadManager: com.lastwave.app.data.download.TrackDownloadManager,
    private val routeNavigator: com.lastwave.app.ui.navigation.AppRouteNavigator,
    private val localMusicLibrary: com.lastwave.app.data.localmusic.LocalMusicLibrary,
    private val localOfflineRepository: com.lastwave.app.data.localmusic.LocalOfflineRepository,
) : ViewModel() {
    val navEvents = navigator.events
    val state = player.state
    val chromeState = player.chromeState
    val progressState = player.progressState
    /**
     * Sampled copy for the collapsed player's 3 dp bar / ring. Collecting the
     * 60 ms [progressState] from composition scheduled a Compose frame 15x/s
     * even when the sampled value had not changed.
     */
    @OptIn(FlowPreview::class)
    val miniProgressState: StateFlow<PlaybackProgressState> = player.progressState
        .sample(250)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), player.progressState.value)
    val fullPlayerState = player.state
        .map { it.withoutProgress() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, player.state.value.withoutProgress())
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
    val offlinePlaylists = localOfflineRepository.playlists
    private var customPlaylistsLoaded = false
    val likedTrackKeys = trackLikeController.likedTrackKeys

    // Account controls project the same state as the heart. A second status read
    // could arrive after a tap and make the next request perform the opposite action.
    val youTubeLiked: StateFlow<Boolean?> = combine(
        player.chromeState,
        likedTrackKeys,
        trackLikeController.youTubeConnection,
    ) { state, keys, _ ->
        state.current?.takeIf(trackLikeController::canUseYouTubeLike)
            ?.let { trackLikeController.isLiked(it, keys) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun toggleYouTubeLike(track: PlayableTrack) = toggleLiked(track)

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage = _toastMessage.asSharedFlow()

    private val _liveLyricsOffsetMs = MutableStateFlow<Long?>(null)
    val liveLyricsOffsetMs: StateFlow<Long> = combine(
        _liveLyricsOffsetMs,
        settingsPreferences.settings.map { it.lyricsOffsetMs },
    ) { override, pref -> override ?: pref }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    fun openArtist(name: String, browseId: String? = null) {
        navigator.openArtist(name, browseId)
    }

    fun openAlbum(title: String, artist: String = "", browseId: String? = null) {
        navigator.openAlbum(title, artist, browseId)
    }

    fun setLyricsOffsetMs(offsetMs: Long) {
        val clamped = offsetMs.coerceIn(-10000L, 10000L)
        _liveLyricsOffsetMs.value = clamped
        viewModelScope.launch {
            settingsPreferences.setLyricsOffsetMs(clamped)
        }
    }

    fun setLyricsFontScale(scale: Float) {
        viewModelScope.launch {
            settingsPreferences.setLyricsFontScale(scale)
        }
    }

    private val _lyricsState = MutableStateFlow<LyricsUiState>(LyricsUiState.Idle)
    val lyricsState = _lyricsState.asStateFlow()

    private val _lyricsProviderStatuses = MutableStateFlow<Map<com.lastwave.app.data.local.LyricsProvider, LyricsSyncType>>(emptyMap())
    val lyricsProviderStatuses = _lyricsProviderStatuses.asStateFlow()
    private var probeLyricsJob: Job? = null

    private var currentTrackLyricsKey: String? = null
    private var lyricsJob: Job? = null
    private var spotLyricsOverride: Pair<com.lastwave.app.data.local.LyricsProvider?, Set<com.lastwave.app.data.local.LyricsProvider>> = Pair(null, emptySet())

    private val _canvasState = MutableStateFlow<com.lastwave.app.data.canvas.CanvasArtwork?>(null)
    val canvasState = _canvasState.asStateFlow()
    private var currentCanvasTrackKey: String? = null
    private var canvasJob: Job? = null

    private companion object {
        /** Spinner only appears when loading actually takes time; cache
         *  hits resolve well inside this window with no flash. */
        const val LOADING_SPINNER_DELAY_MS = 250L
        /** Next-track canvas warm-up waits this long after the current track's
         *  lookup resolves. Rapid skips cancel the job inside this window, so a
         *  prefetch never spends network/decoder time on a track the user has
         *  already left behind. */
        const val CANVAS_PREFETCH_DELAY_MS = 4_000L
    }

    init {
        player.restoreSessionIfNeeded()
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
                val key = track?.let { "${it.videoId ?: ""}|${it.artist}|${it.title}|$wordByWord" }
                if (key != currentTrackLyricsKey) {
                    currentTrackLyricsKey = key
                    spotLyricsOverride = Pair(null, emptySet())
                    probeLyricsJob?.cancel()
                    _lyricsProviderStatuses.value = emptyMap()
                    if (track != null) {
                        loadLyrics(track, forceRefresh = false)
                    } else {
                        lyricsJob?.cancel()
                        _lyricsState.value = LyricsUiState.Idle
                    }
                }
            }
        }
        viewModelScope.launch {
            combine(
                player.chromeState.map { it.current }.distinctUntilChanged(),
                settingsPreferences.settings.map { it.canvasEnabled to it.canvasOverCellular }.distinctUntilChanged(),
            ) { track, (enabled, cellular) -> Triple(track, enabled, cellular) }.collect { (track, enabled, cellular) ->
                val key = track?.let { "${it.videoId ?: ""}|${it.artist}|${it.title}|$enabled|$cellular" }
                if (key != currentCanvasTrackKey) {
                    currentCanvasTrackKey = key
                    canvasJob?.cancel()
                    if (track != null && enabled && track.mediaType != com.lastwave.app.playback.MediaType.PODCAST_EPISODE) {
                        _canvasState.value = canvasRepository.cached(track)
                        canvasJob = viewModelScope.launch {
                            waitUntilAudible(track)
                            MetadataLog.delayed("canvas", track.videoId)
                            delay(MetadataLog.SETTLE_MS)
                            if (currentCanvasTrackKey != key) {
                                MetadataLog.skipped("canvas", track.videoId)
                                return@launch
                            }
                            MetadataLog.started("canvas", track.videoId)
                            val result = canvasRepository.canvasFor(track, cellularAllowed = cellular)
                            // A skip during the lookup cancels this job; only the
                            // winner publishes.
                            if (currentCanvasTrackKey != key) return@launch
                            _canvasState.value = result
                            // Warm the next track's cache entry while this one plays,
                            // so a skip usually lands on an in-memory hit instead of a
                            // cold network waterfall. Chained in this same job, strictly
                            // after the current lookup: the repository serializes on one
                            // mutex, so a standalone prefetch job could otherwise hold
                            // the lock inside a blocking HTTP call right when the next
                            // track needs it. The delay means rapid skips cancel before
                            // any prefetch traffic starts.
                            val upcoming = player.state.value.let { snapshot ->
                                if (snapshot.shuffleEnabled) {
                                    // Engine shuffle order is not visible here; warming
                                    // the wrong track would only spend lookups.
                                    null
                                } else {
                                    snapshot.queue.getOrNull(snapshot.currentIndex + 1)
                                }
                            }?.takeIf {
                                it.videoId != track.videoId || it.title != track.title || it.artist != track.artist
                            } ?: return@launch
                            delay(CANVAS_PREFETCH_DELAY_MS)
                            if (currentCanvasTrackKey != key) return@launch
                            // Result discarded: canvasFor caches it, which is the
                            // whole point. Never published to _canvasState here.
                            canvasRepository.canvasFor(upcoming, cellularAllowed = cellular)
                        }
                    } else {
                        _canvasState.value = null
                    }
                }
            }
        }
    }

    private suspend fun refreshCustomPlaylists() {
        try {
            val local = playlistRepository.getAll()
                .filter { it.mode == "custom" || it.mode == LIKED_SONGS_MODE }
                .sortedByDescending { it.mode == LIKED_SONGS_MODE }
            val remote = ytMusicLibraryManager.playlists.value.filterNot { r ->
                local.any { l ->
                    (l.remotePlaylistId != null && l.remotePlaylistId == r.remotePlaylistId) ||
                    l.title.equals(r.title, ignoreCase = true)
                }
            }
            _customPlaylists.value = (local + remote).distinctBy { it.id }
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

    /**
     * Holds lyrics and canvas fetches until this track is actually playing.
     * The state at subscription still describes the previous song, which is
     * not buffering, so the first value is skipped.
     */
    private suspend fun waitUntilAudible(track: PlayableTrack) {
        withTimeoutOrNull(8_000L) {
            player.state.drop(1).first { state ->
                val same = track.videoId.isNullOrBlank() || state.current?.videoId == track.videoId
                same && state.isPlaying && !state.isBuffering && state.error == null
            }
        }
    }

    fun selectLyricsProvider(provider: com.lastwave.app.data.local.LyricsProvider) {
        val track = player.state.value.current ?: return
        spotLyricsOverride = Pair(provider, emptySet())
        loadLyrics(track, forceRefresh = true, overrideProvider = provider, excludedProviders = emptySet())
    }

    fun tryAnotherLyricsSource() {
        val track = player.state.value.current ?: return
        val currentSource = (_lyricsState.value as? LyricsUiState.Success)?.source
        val currentProvider = com.lastwave.app.data.lyrics.providerForSource(currentSource)
        val newExcluded = if (currentProvider != null) {
            spotLyricsOverride.second + currentProvider
        } else {
            spotLyricsOverride.second
        }
        spotLyricsOverride = Pair(null, newExcluded)
        loadLyrics(track, forceRefresh = true, overrideProvider = null, excludedProviders = newExcluded)
    }

    fun probeLyricsProviders() {
        val track = player.state.value.current ?: return
        probeLyricsJob?.cancel()
        val durationSeconds = if (player.state.value.durationMs > 0) {
            (player.state.value.durationMs / 1000).toInt()
        } else {
            track.durationMs?.takeIf { it > 0 }?.let { (it / 1000).toInt() }
        }
        probeLyricsJob = viewModelScope.launch {
            lyricsRepository.probeAllProviders(
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationSeconds = durationSeconds,
                videoId = track.videoId,
                onStatusUpdated = { provider, status ->
                    _lyricsProviderStatuses.update { current ->
                        current + (provider to status)
                    }
                },
            )
        }
    }

    fun loadLyrics(
        track: PlayableTrack,
        forceRefresh: Boolean = false,
        overrideProvider: com.lastwave.app.data.local.LyricsProvider? = spotLyricsOverride.first,
        excludedProviders: Set<com.lastwave.app.data.local.LyricsProvider> = spotLyricsOverride.second,
    ) {
        val knownStatuses = lyricsRepository.getKnownProviderStatuses(track.title, track.artist, track.videoId)
        if (knownStatuses.isNotEmpty()) {
            _lyricsProviderStatuses.update { current -> current + knownStatuses }
        }
        if (track.mediaType == com.lastwave.app.playback.MediaType.PODCAST_EPISODE) {
            // Podcast episodes have no lyrics: skip every provider instead of
            // burning a lookup on a title that can never match.
            lyricsJob?.cancel()
            lyricsJob = null
            _lyricsState.value = LyricsUiState.Idle
            return
        }
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            // Lyrics hit other external providers. Starting
            // them in the same moment as youtubei/player steals connections
            // from the stream extract. Wait until this track is actually
            // playing, then fetch.
            waitUntilAudible(track)
            MetadataLog.delayed("lyrics", track.videoId)
            delay(MetadataLog.SETTLE_MS)
            MetadataLog.started("lyrics", track.videoId)
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
                    val resolvedLocalUrl = com.lastwave.app.data.generate.LocalAudioFormats
                        .importedPathOrNull(track.playbackUrl)
                    val resolvedLrc = resolvedLocalUrl?.let { localMusicLibrary.lrcUriFor(it) }
                        ?: resolvedLocalUrl?.let(com.lastwave.app.data.generate.LocalAudioFormats::lrcPathFor)
                    lyricsRepository.getLyrics(
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        durationSeconds = durationSeconds
                            ?: track.durationMs?.takeIf { it > 0 }?.let { (it / 1000).toInt() },
                        forceRefresh = forceRefresh,
                        wordByWord = settingsPreferences.settings.first().wordByWordLyrics,
                        videoId = track.videoId,
                        localLrcPath = resolvedLrc,
                        localAudioUrl = track.playbackUrl.takeIf { track.isLocalTrack() },
                        overrideProvider = overrideProvider,
                        excludedProviders = excludedProviders,
                        onPartialResult = { partial ->
                            withContext(Dispatchers.Main.immediate) {
                                coroutineContext.ensureActive()
                                publishLyrics(partial, wanted = track)
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
                    publishLyrics(result, wanted = track)
                }
            } finally {
                loadingIndicator.cancel()
            }
        }
    }

    private fun publishLyrics(result: LyricsResult, wanted: PlayableTrack? = null) {
        // A superseded fetch (user skipped while it was in flight) must
        // never paint the previous song's lyrics over the new one. The job
        // cancel covers most of it; this covers the already-posted tail.
        if (wanted != null) {
            val current = player.state.value.current
            val same = if (!wanted.videoId.isNullOrBlank() || !current?.videoId.isNullOrBlank()) {
                !wanted.videoId.isNullOrBlank() && wanted.videoId == current?.videoId
            } else {
                current != null && current.title.equals(wanted.title, ignoreCase = true) &&
                    current.artist.equals(wanted.artist, ignoreCase = true)
            }
            if (!same) return
        }
        when (result) {
            is LyricsResult.Success -> {
                val prov = com.lastwave.app.data.lyrics.providerForSource(result.source)
                if (prov != null) {
                    val status = when {
                        result.isWordSynced -> LyricsSyncType.WORD_SYNC
                        result.isSynced && result.lines.isNotEmpty() -> LyricsSyncType.LINE_SYNC
                        !result.plainLyrics.isNullOrBlank() || result.lines.isNotEmpty() -> LyricsSyncType.NO_SYNC
                        else -> LyricsSyncType.NO_LYRICS
                    }
                    _lyricsProviderStatuses.update { it + (prov to status) }
                }
                // Single funnel for everything the views draw, mirroring the
                // reference lyrics clients: one provider
                // timestamp stays one drawn row. The timeline is de-overlapped
                // once so word fill, line focus and auto-scroll all read the
                // same edge-to-edge clock, then provider fragments are merged
                // into whitespace-true words so spacing and punctuation render
                // as authored. Rows are never merged or re-timed here â€” that
                // grouping is what broke line-by-line sync (two sung lines
                // shown as one, focus arriving late). Word-sync rows render
                // word-by-word; rows without syllables fall back to
                // line-by-line focus on the same clock.
                // Credit / provider / metadata rows are dropped and speaker
                // labels become duet roles before anything is drawn; only
                // whole rows or leading labels go, timing is never touched.
                val lines = if (result.isSynced && result.lines.isNotEmpty() && !result.isInstrumental) {
                    com.lastwave.app.ui.player.normalizeWordSpacing(
                        com.lastwave.app.data.lyrics.LyricsRepository.normalizeLyricTiming(
                            com.lastwave.app.data.lyrics.LyricsSanitizer.sanitize(result.lines),
                        ),
                    )
                } else result.lines.filterNot {
                    com.lastwave.app.data.lyrics.LyricsSanitizer.shouldSuppress(it.text)
                }
                _lyricsState.value = LyricsUiState.Success(
                    lines = lines,
                    isSynced = result.isSynced,
                    isWordSynced = result.isWordSynced || lines.any { it.hasSyllables },
                    plainLyrics = com.lastwave.app.data.lyrics.LyricsSanitizer.sanitizePlain(result.plainLyrics),
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
                var anyAdded = false
                playlistIds.forEach { playlistId ->
                    val allowDuplicate = playlistId in duplicatePlaylistIds
                    if (playlistId < 0L) {
                        val added = ytMusicLibraryManager.addTrack(playlistId, generatedTrack, allowDuplicate)
                        remoteChanged = remoteChanged || added
                        anyAdded = anyAdded || added
                    } else {
                        anyAdded = playlistRepository.addTrack(
                            id = playlistId,
                            track = generatedTrack,
                            allowDuplicate = allowDuplicate,
                        ) != null || anyAdded
                    }
                }
                if (remoteChanged) {
                    ytMusicLibraryManager.refresh()
                    refreshCustomPlaylists()
                }
                _toastMessage.emit(if (anyAdded) "Added to playlist" else "Couldn't add to playlist")
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
                _toastMessage.emit("Added to playlist")
            }
        }
    }

    fun addToOfflinePlaylists(playlistIds: Set<String>, track: PlayableTrack) {
        val url = track.playbackUrl?.takeIf { it.isNotBlank() } ?: return
        if (playlistIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                var added = false
                playlistIds.forEach { playlistId ->
                    if (localOfflineRepository.addTracksToPlaylist(playlistId, listOf(url)) > 0) {
                        added = true
                    }
                }
                _toastMessage.emit(if (added) "Added to offline playlist" else "Already in offline playlist")
            }
        }
    }

    fun createOfflinePlaylistAndAdd(title: String, track: PlayableTrack) {
        val clean = title.trim()
        val url = track.playbackUrl?.takeIf { it.isNotBlank() } ?: return
        if (clean.isBlank()) return
        viewModelScope.launch {
            runCatching {
                localOfflineRepository.createPlaylist(clean, listOf(url))
                _toastMessage.emit("Added to offline playlist")
            }
        }
    }

    fun toggleLiked(track: PlayableTrack) {
        viewModelScope.launch {
            try {
                val liked = trackLikeController.toggle(track)
                _toastMessage.emit(if (liked) "Added to Liked Songs" else "Removed from Liked Songs")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                android.util.Log.e("PlayerViewModel", "Like toggle failed", error)
                // emit, not tryEmit: this flow has no buffer, so tryEmit was always dropped.
                _toastMessage.emit("Couldn't update Liked Songs")
            }
        }
    }

    fun like(track: PlayableTrack) {
        viewModelScope.launch {
            try {
                trackLikeController.like(track)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                android.util.Log.e("PlayerViewModel", "Like failed", error)
                _toastMessage.emit("Couldn't update Liked Songs")
            }
        }
    }
}
