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
internal fun FullPlayer(
    state: MusicPlayerState,
    progressState: StateFlow<PlaybackProgressState>,
    player: MusicPlayer,
    lyricsState: LyricsUiState,
    wavySeekbarEnabled: Boolean = true,
    rotatingBackgroundEnabled: Boolean = true,
    lyricsRotatingBackgroundEnabled: Boolean = false,
    lyricsOffsetMs: Long = 0L,
    onSetLyricsOffsetMs: ((Long) -> Unit)? = null,
    lyricsFontScale: Float = 1.0f,
    onSetLyricsFontScale: ((Float) -> Unit)? = null,
    /** 0 = floating lyrics buttons never auto-hide; 2..10 = idle seconds. */
    lyricsButtonsAutoHideSeconds: Int = 5,
    /** Language the lyric translate button targets; blank = app language. */
    translationLanguage: String = "",
    canvas: com.lastwave.app.data.canvas.CanvasArtwork? = null,
    canvasEnabled: Boolean = true,
    canvasFullBleedEnabled: Boolean = true,
    canvasGradientBlurEnabled: Boolean = false,
    musicVideoEnabled: Boolean = true,
    showAutoEqInVolumePanel: Boolean = true,
    currentTab: FullPlayerTab,
    onTabChange: (FullPlayerTab) -> Unit,
    onRetryLyrics: () -> Unit,
    onCollapse: () -> Unit,
    onOpenArtist: (String) -> Unit = {},
    isLiked: Boolean = false,
    onToggleLiked: () -> Unit = {},
    onDoubleTapLike: () -> Unit = {},
    youTubeLiked: Boolean? = null,
    onToggleYouTubeLike: () -> Unit = {},
    onDragStatusChange: (Boolean) -> Unit = {},
    lyricsProviderStatuses: Map<com.lastwave.app.data.local.LyricsProvider, LyricsSyncType> = emptyMap(),
    onProbeLyricsProviders: (() -> Unit)? = null,
    onSelectLyricsProvider: ((com.lastwave.app.data.local.LyricsProvider) -> Unit)? = null,
    onTryAnotherLyricsSource: (() -> Unit)? = null,
) {
    val track = state.current ?: return
    var showLyricsOffsetDialog by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val isTablet = isTabletOrWideScreen()
    val isTabletLandscape = isLandscape && (isTablet || configuration.screenWidthDp >= 600)
    val isCanvasActive = canvasEnabled && canvas != null
    val syncedVideoVm: SyncedVideoViewModel = hiltViewModel()
    val showSyncedVideo by syncedVideoVm.isVideoActive.collectAsStateWithLifecycle()
    val syncedVideoState by syncedVideoVm.uiState.collectAsStateWithLifecycle()
    val canWatchVideo = !syncedVideoVm.isVideoUnavailableFor(
        title = track.title,
        artist = track.artist,
        videoId = track.videoId,
        durationMs = track.durationMs,
        state = syncedVideoState,
    )
    val onToggleSyncedVideo: (() -> Unit)? = if (canWatchVideo || showSyncedVideo) {
        { syncedVideoVm.setVideoActive(!showSyncedVideo) }
    } else null
    val isVideoActive = showSyncedVideo && musicVideoEnabled
    LaunchedEffect(track.videoId, track.title, track.durationMs, musicVideoEnabled) {
        if (!musicVideoEnabled || track.artworkUrl?.startsWith("file://") == true) return@LaunchedEffect
        // Waits out rapid skips, so only a song the user stays on spends the lookup.
        delay(VIDEO_PRELOAD_DELAY_MS)
        syncedVideoVm.preloadVideo(track.title, track.artist, track.videoId, track.durationMs)
    }
    // Full bleed works wherever the player uses the single-column layout:
    // phones and tablets in portrait. Only tablet landscape (two columns,
    // cover beside the controls) keeps the boxed sleeve.
    val fullBleedBlocked = isTablet && isLandscape
    val showFullBleed = canvasFullBleedEnabled && !isVideoActive && !fullBleedBlocked
    val showSleeveCanvas = isCanvasActive && !showFullBleed && !isVideoActive
    // activeCanvas is the single gate every render branch below keys off, so it also
    // carries the Animated Album Canvas toggle. The ViewModel nulls canvasState
    // asynchronously, so without this a disabled toggle still paints one frame of
    // motion artwork over the cover.
    val activeCanvas = remember(canvas, showFullBleed, canvasEnabled) {
        if (!canvasEnabled) {
            null
        } else {
            val tall = canvas?.tallUrl
            if (showFullBleed && canvas != null && !tall.isNullOrBlank()) {
                canvas.copy(url = tall)
            } else {
                canvas
            }
        }
    }
    var canvasAspect by remember(activeCanvas?.url) { mutableFloatStateOf(0f) }
    // How much of the hero the canvas currently covers â€” its own fade-in alpha
    // times the presentation alpha. The static hero fades out in step with it,
    // so the cover never shows through the video (its bottom fade, its 320 ms
    // reveal, the repaint window on a track change) and the two never fight for
    // the same pixels. A disposed canvas reports 0, so the hero returns the
    // moment a track without a canvas starts.
    var canvasCover by remember { mutableFloatStateOf(0f) }
    var lyricsFullscreen by remember(currentTab) { mutableStateOf(false) }
    var lyricsControlsVisible by remember(currentTab) { mutableStateOf(true) }
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
    var isVideoFullscreen by remember(track.videoId, track.title) { mutableStateOf(false) }
    val videoFullscreenActive = isVideoFullscreen && isVideoActive
    DisposableEffect(view, videoFullscreenActive) {
        val activity = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val previousBehavior = controller?.systemBarsBehavior
        val originalOrientation = activity?.requestedOrientation
        if (videoFullscreenActive) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (videoFullscreenActive) {
                controller?.show(WindowInsetsCompat.Type.systemBars())
                previousBehavior?.let { controller?.systemBarsBehavior = it }
                if (originalOrientation != null) {
                    activity?.requestedOrientation = originalOrientation
                }
            }
        }
    }
    var showTrackMenu by remember(track.videoId, track.title) { mutableStateOf(false) }
    var showSignalPath by remember { mutableStateOf(false) }
    var showAudioControls by remember { mutableStateOf(false) }
    var showAutoEq by remember { mutableStateOf(false) }
    val signalPath by player.signalPath.collectAsStateWithLifecycle()
    val usbDac by player.usbDacState.collectAsStateWithLifecycle()
    var isArtworkDragging by remember(track.videoId, track.title) { mutableStateOf(false) }
    var artworkDragX by remember(track.videoId, track.title) { mutableFloatStateOf(0f) }
    var dismissDragY by remember(track.videoId, track.title) { mutableFloatStateOf(0f) }
    var isDismissDragging by remember { mutableStateOf(false) }
    LaunchedEffect(isDismissDragging) { onDragStatusChange(isDismissDragging) }
    var seekOverlayDirection by remember(track.videoId, track.title) { mutableStateOf<SeekDirection?>(null) }
    var seekOverlaySeconds by remember(track.videoId, track.title) { mutableIntStateOf(0) }
    var lastTapTimestamp by remember(track.videoId, track.title) { mutableLongStateOf(0L) }
    var lastTapSide by remember(track.videoId, track.title) { mutableStateOf<SeekDirection?>(null) }
    var lastLikeTapTimestamp by remember(track.videoId, track.title) { mutableLongStateOf(0L) }
    var seekResetJob by remember(track.videoId, track.title) { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val shownArtworkX by animateFloatAsState(
        targetValue = artworkDragX,
        animationSpec = if (isArtworkDragging) snap() else ExpressiveMotion.spatialDefault(),
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
    val artworkViewModel: ArtworkViewModel = hiltViewModel()
    val artworkKey = remember(track.title, track.artist) { ArtworkNormalizer.cacheKey(track.title, track.artist) }
    val onlineAmbientUrl by remember(artworkKey) {
        artworkViewModel.resolved.map { it[artworkKey] }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = artworkViewModel.resolved.value[artworkKey])
    // Local songs: the cover embedded in the user's file wins over online art.
    val resolvedAmbientUrl = if (track.artworkUrl?.startsWith("file://") == true) null else onlineAmbientUrl

    var ambientPrimary by remember(track.videoId, track.artworkUrl, track.title, track.artist) { mutableStateOf<Color?>(null) }
    var ambientSecondary by remember(track.videoId, track.artworkUrl, track.title, track.artist) { mutableStateOf<Color?>(null) }
    var ambientTertiary by remember(track.videoId, track.artworkUrl, track.title, track.artist) { mutableStateOf<Color?>(null) }
    LaunchedEffect(track.videoId, track.artworkUrl, track.title, track.artist, resolvedAmbientUrl) {
        val url = resolvedAmbientUrl?.takeIf { it.isNotBlank() }
            ?: ArtworkNormalizer.upscaleYoutubeArtwork(track.artworkUrl)?.takeIf { it.isNotBlank() }
            ?: return@LaunchedEffect
        val cached = ambientPaletteCache.get(url)
        if (cached != null) {
            ambientPrimary = cached.first
            ambientSecondary = cached.second
            ambientTertiary = cached.third
            return@LaunchedEffect
        }
        withContext(Dispatchers.Default) {
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
                val cP = p?.let { Color(it.rgb) }
                val cS = s?.let { Color(it.rgb) }
                val cT = t?.let { Color(it.rgb) }
                if (cP != null) ambientPrimary = cP
                if (cS != null) ambientSecondary = cS
                if (cT != null) ambientTertiary = cT
                if (cP != null || cS != null || cT != null) {
                    ambientPaletteCache.put(
                        url,
                        Triple(
                            cP ?: Color.Transparent,
                            cS ?: Color.Transparent,
                            cT ?: Color.Transparent,
                        ),
                    )
                }
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
                var collapsing = false
                when {
                    currentTab != FullPlayerTab.NOW_PLAYING && dismissDragY > swipeThreshold -> onTabChange(FullPlayerTab.NOW_PLAYING)
                    currentTab == FullPlayerTab.NOW_PLAYING && dismissDragY < -swipeThreshold -> onTabChange(FullPlayerTab.QUEUE)
                    currentTab == FullPlayerTab.NOW_PLAYING && dismissDragY > swipeThreshold -> {
                        collapsing = true
                        onCollapse()
                    }
                }
                // Collapsing keeps the dragged offset: the close morph carries
                // the player away from there instead of it springing back first.
                if (!collapsing) dismissDragY = 0f
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

    // Unconditional remember keeps composition stable; usage gated by glass flag.
    // Single backdrop, sibling source below â€” never nest glass inside its own capture.
    val playerBackdrop = rememberLayerBackdrop()
    val fullGlass = LocalLiquidGlass.current && isLiquidGlassBackdropSupported()

    if (videoFullscreenActive) {
        BackHandler { isVideoFullscreen = false }
        FullscreenLandscapeVideoStage(
            track = track,
            musicPlayer = player,
            onMinimize = { isVideoFullscreen = false },
            onCloseVideo = {
                isVideoFullscreen = false
                syncedVideoVm.setVideoActive(false)
            },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    CompositionLocalProvider(
        LocalLiquidGlassBackdrop provides if (fullGlass) playerBackdrop else null,
        LocalLiquidGlassOverlayBackdrop provides if (fullGlass) playerBackdrop else null,
        LocalIsDarkTheme provides true,
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
            var heroBottomPx by remember { mutableFloatStateOf(0f) }

            val isLyricsTab = currentTab == FullPlayerTab.LYRICS
            val showLyricsFluid = lyricsRotatingBackgroundEnabled && rotatingBackgroundEnabled
            // Drives the Now Playing <-> Lyrics backdrop hand-over: the fluid
            // layer fades out while the full-bleed cover backdrop fades in when
            // lyrics rotating background is disabled. When enabled, the fluid
            // background stays active across tabs.
            val lyricsVeil by animateFloatAsState(
                targetValue = if (isLyricsTab && !showLyricsFluid) 1f else 0f,
                animationSpec = tween(350),
                label = "lyricsVeil",
            )
            Box(Modifier.matchParentSize().liquidGlassSource(if (fullGlass) playerBackdrop else null)) {
                FluidArtworkBackground(
                    track = track,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = (1f - lyricsVeil).coerceIn(0f, 1f) },
                    extraBlur = isLyricsTab,
                    rotatingBackgroundEnabled = if (isLyricsTab) showLyricsFluid else rotatingBackgroundEnabled,
                    lyricsRotatingBackgroundEnabled = lyricsRotatingBackgroundEnabled,
                    fallback = {
                        val staticBlurTransform = remember(currentTab) {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                                listOf(
                                    BlurTransformation(
                                        radius = if (currentTab == FullPlayerTab.LYRICS) 25 else 18,
                                        maxDimension = 100,
                                    )
                                )
                            } else {
                                emptyList()
                            }
                        }
                        PlayerArtwork(
                            track = track,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = 1.35f
                                    scaleY = 1.35f
                                    alpha = 0.9f
                                }
                                .then(
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        if (currentTab == FullPlayerTab.LYRICS) {
                                            Modifier.blur(36.dp)
                                        } else {
                                            Modifier.blur(20.dp)
                                        }
                                    } else {
                                        Modifier
                                    }
                                ),
                            corner = 0.dp,
                            decodeSizePx = 200,
                            transformations = staticBlurTransform,
                        )
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (isLyricsTab) 0.40f else 0.52f)))
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.radialGradient(
                                        0f to ambientColor.copy(alpha = 0.58f),
                                        0.45f to ambientColor.copy(alpha = 0.22f),
                                        1f to Color.Transparent,
                                        center = androidx.compose.ui.geometry.Offset(bgWidth * 0.25f, bgHeight * 0.20f),
                                        radius = bgMaxDimension * 0.85f,
                                    )
                                )
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.radialGradient(
                                        0f to ambientCompanion.copy(alpha = 0.52f),
                                        0.50f to ambientCompanion.copy(alpha = 0.20f),
                                        1f to Color.Transparent,
                                        center = androidx.compose.ui.geometry.Offset(bgWidth * 0.88f, bgHeight * 0.65f),
                                        radius = bgMaxDimension * 0.78f,
                                    )
                                )
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.radialGradient(
                                        0f to ambientDeep.copy(alpha = 0.42f),
                                        0.55f to ambientDeep.copy(alpha = 0.14f),
                                        1f to Color.Transparent,
                                        center = androidx.compose.ui.geometry.Offset(bgWidth * 0.15f, bgHeight * 0.82f),
                                        radius = bgMaxDimension * 0.70f,
                                    )
                                )
                        )
                    }
                )
                // Dynamic ambient lyrics gradient overlay: subtly shifts line-by-line strictly
                // using the album cover art palette, preserving zero text visibility issues.
                if (showLyricsFluid) {
                    LyricsDynamicAmbientBackdrop(
                        isLyricsTab = isLyricsTab,
                        lyricsState = lyricsState,
                        progressState = progressState,
                        lyricsOffsetMs = lyricsOffsetMs,
                        ambientColor = ambientColor,
                        ambientCompanion = ambientCompanion,
                        ambientDeep = ambientDeep,
                        bgWidth = bgWidth,
                        bgHeight = bgHeight,
                        bgMaxDimension = bgMaxDimension,
                    )
                }
                // Contrast scrim gradient (ensures text & controls are clear while preserving vibrant colors)
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.00f to Color.Black.copy(alpha = 0.35f),
                                0.28f to Color.Black.copy(alpha = 0.15f),
                                0.65f to Color.Black.copy(alpha = 0.40f),
                                1.00f to Color.Black.copy(alpha = 0.72f),
                            )
                        )
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
                if (showFullBleed) {
                    val isTallCanvas = remember(activeCanvas, canvasAspect, canvasGradientBlurEnabled) {
                        if (canvasGradientBlurEnabled || activeCanvas == null) false
                        else if (canvasAspect > 0f) {
                            // True vertical video (9:16 is ~0.56, 3:4 is 0.75; <= 0.72 is tall vertical)
                            canvasAspect <= 0.72f
                        } else {
                            // Spotify Canvas is natively 9:16 vertical by specification
                            activeCanvas.source == com.lastwave.app.data.canvas.CanvasSource.SPOTIFY
                        }
                    }

                    // The hero is the cover's full width, cropped top-aligned. On a
                    // phone the area above the title is taller than wide, so the
                    // square cover keeps its whole height. A portrait tablet is
                    // much wider for its height: that area came out wider than
                    // tall and cut off the bottom of the cover. Never let the
                    // hero be shorter than it is wide (capped at 85% of the
                    // screen) so a square cover fits edge to edge and its lower
                    // part fades under the title, exactly like on a phone.
                    val heroHeight = with(LocalDensity.current) {
                        val measured = if (heroBottomPx > 0f) heroBottomPx else bgHeight * 0.54f
                        maxOf(measured, minOf(bgWidth, bgHeight * 0.85f)).toDp()
                    }
                    val heroAlpha by animateFloatAsState(
                        targetValue = if (currentTab == FullPlayerTab.NOW_PLAYING) 1f else 0f,
                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                        label = "heroAlpha",
                    )
                    // Static cover first, canvas on top: the hero is the
                    // placeholder while the video loads, and hands the pixels
                    // over as the canvas's own alpha climbs to full.
                    val heroCover =
                        if (canvasCover >= 0.95f) 0f else (1f - canvasCover).coerceIn(0f, 1f)
                    StaticArtworkHero(
                        name = track.title,
                        artist = track.artist,
                        embeddedUrl = track.artworkUrl,
                        resolvedUrl = resolvedAmbientUrl,
                        bottomFade = 0.38f,
                        alpha = heroAlpha * heroCover,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .height(heroHeight),
                    )
                    if (activeCanvas != null) {
                        CanvasArtworkPlayer(
                            canvas = activeCanvas,
                            isPlaying = state.isPlaying,
                            contentMode = CanvasContentMode.CROP,
                            alignPortraitTop = true,
                            bottomFade = if (isTallCanvas) 0f else 0.38f,
                            presentationAlpha = { heroAlpha },
                            onCoverChanged = { canvasCover = it },
                            onAspectRatioChanged = { canvasAspect = it },
                            pausedForTransition = shownDismissY > 1f || currentTab != FullPlayerTab.NOW_PLAYING,
                            modifier = if (isTallCanvas) {
                                Modifier.fillMaxSize()
                            } else {
                                Modifier
                                    .align(Alignment.TopStart)
                                    .fillMaxWidth()
                                    .height(heroHeight)
                            },
                        )

                        // For true full-bleed canvas: a subtle contrast gradient scrim so top header and
                        // bottom playback controls/title remain 100% legible without blurring the video
                        if (isTallCanvas) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = canvasCover * heroAlpha }
                                    .background(
                                        Brush.verticalGradient(
                                            0.00f to Color.Black.copy(alpha = 0.32f),
                                            0.12f to Color.Transparent,
                                            0.55f to Color.Transparent,
                                            0.78f to Color.Black.copy(alpha = 0.36f),
                                            1.00f to Color.Black.copy(alpha = 0.72f),
                                        )
                                    )
                            )
                        }
                    }
                }

                // Lyrics tab backdrop (ArchiveTune / Apple Music style):
                // Deep dark base (#050505), blurred artwork (46.dp, 62% alpha),
                // 3-stop vertical gradient of the extracted album art colors (0.88, 0.76, 0.96 alpha),
                // subtle 18% black dim, and a 28% bottom scrim.
                if (lyricsVeil > 0.01f) {
                    val colors = remember(ambientColor, ambientCompanion, ambientDeep) {
                        listOf(ambientColor, ambientCompanion, ambientDeep)
                    }
                    val appleMusicBackgroundBrush = remember(colors) {
                        Brush.verticalGradient(
                            listOf(
                                colors.getOrElse(0) { AppleMusicFallbackGradient[0] }.copy(alpha = 0.88f),
                                colors.getOrElse(1) { AppleMusicFallbackGradient[1] }.copy(alpha = 0.76f),
                                colors.getOrElse(2) { AppleMusicFallbackGradient[2] }.copy(alpha = 0.96f),
                            ),
                        )
                    }
                    val appleMusicBottomScrim = remember {
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.28f),
                            ),
                        )
                    }

                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = lyricsVeil }) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(AppleMusicFallbackGradient.last())
                        )
                        PlayerArtwork(
                            track = track,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = 1.35f
                                    scaleY = 1.35f
                                    alpha = 0.62f
                                }
                                .then(
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        Modifier.blur(46.dp)
                                    } else {
                                        Modifier
                                    }
                                ),
                            corner = 0.dp,
                            decodeSizePx = 200,
                            transformations = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                                listOf(BlurTransformation(radius = 25, maxDimension = 100))
                            } else {
                                emptyList()
                            },
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(appleMusicBackgroundBrush)
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.18f))
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(appleMusicBottomScrim)
                        )
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // â”€â”€ Header: slimmer, calmer, premium â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                val showHeader = !lyricsFullscreen && (currentTab != FullPlayerTab.LYRICS || lyricsControlsVisible || showLyricsOffsetDialog || showTrackMenu)
                AnimatedVisibility(
                    visible = showHeader,
                    enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                    exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isTabletLandscape) {
                                    Modifier.widthIn(max = 1200.dp).fillMaxWidth()
                                } else {
                                    Modifier.adaptiveContentWidth(maxWidth = 640.dp)
                                }
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .padding(horizontal = if (isTabletLandscape) 32.dp else 20.dp)
                                .playerVerticalSwipe(enabled = currentTab != FullPlayerTab.NOW_PLAYING),
                        ) {
                            // FullPlayer sits on a dark blurred-artwork scrim in both light and
                            // dark mode, so foreground must use the white overlay palette â€”
                            // never MaterialTheme onSurface (near-black in light mode).
                            LiquidGlassSurface(
                                onClick = {
                                    if (currentTab != FullPlayerTab.NOW_PLAYING) {
                                        onTabChange(FullPlayerTab.NOW_PLAYING)
                                    } else {
                                        onCollapse()
                                    }
                                },
                                shape = CircleShape,
                                color = liquidGlassContainerColor(Color.White.copy(alpha = 0.14f)),
                                contentColor = Color.White.copy(alpha = 0.94f),
                                glassModifier = Modifier.liquidGlassChrome(
                                    CircleShape,
                                    LocalLiquidGlass.current,
                                    LiquidGlassPreset.FloatingControls,
                                ),
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .size(44.dp),
                            ) {
                                Icon(
                                    if (currentTab != FullPlayerTab.NOW_PLAYING) Icons.Filled.ArrowBack else Icons.Filled.ExpandMore,
                                    if (currentTab != FullPlayerTab.NOW_PLAYING) "Back to player" else "Minimize player",
                                    modifier = Modifier.size(26.dp),
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
                            Row(
                                modifier = Modifier.align(Alignment.CenterEnd),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (currentTab == FullPlayerTab.LYRICS && onSetLyricsOffsetMs != null) {
                                    LiquidGlassSurface(
                                        onClick = { showLyricsOffsetDialog = true },
                                        shape = CircleShape,
                                        color = if (lyricsOffsetMs != 0L) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                        else liquidGlassContainerColor(Color.White.copy(alpha = 0.14f)),
                                        contentColor = if (lyricsOffsetMs != 0L) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.94f),
                                        glassModifier = Modifier.liquidGlassChrome(
                                            CircleShape,
                                            LocalLiquidGlass.current,
                                            LiquidGlassPreset.FloatingControls,
                                        ),
                                        modifier = Modifier.size(44.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Timer,
                                            contentDescription = stringResource(com.lastwave.app.R.string.player_lyrics_offset_cd),
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                }
                                LiquidGlassSurface(
                                    onClick = { showTrackMenu = true },
                                    shape = CircleShape,
                                    color = liquidGlassContainerColor(Color.White.copy(alpha = 0.14f)),
                                    contentColor = Color.White.copy(alpha = 0.94f),
                                    glassModifier = Modifier.liquidGlassChrome(
                                        CircleShape,
                                        LocalLiquidGlass.current,
                                        LiquidGlassPreset.FloatingControls,
                                    ),
                                    modifier = Modifier.size(44.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.MoreVert,
                                        "Song options",
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }

                AnimatedContent(
                    targetState = currentTab,
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (isTabletLandscape) {
                                Modifier.widthIn(max = 1200.dp).fillMaxWidth()
                            } else {
                                Modifier.adaptiveContentWidth(maxWidth = 680.dp)
                            }
                        ),
                    transitionSpec = {
                        // Matched fade durations keep both layers visible
                        // throughout the crossfade so the background never
                        // flashes through between cover and canvas/lyrics.
                        // Same language as the player open/close: the panel
                        // rises and grows into place like a card while the
                        // one it covers recedes, and the reverse going back.
                        val dur = 280
                        val ease = tween<Float>(dur, easing = FastOutSlowInEasing)
                        if (targetState != FullPlayerTab.NOW_PLAYING) {
                            (slideInVertically(tween(dur, easing = FastOutSlowInEasing)) { it / 8 } +
                                scaleIn(ease, initialScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 1f)) +
                                fadeIn(tween(dur))) togetherWith
                                (scaleOut(ease, targetScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 0f)) +
                                    fadeOut(tween(dur)))
                        } else {
                            (scaleIn(ease, initialScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 0f)) +
                                fadeIn(tween(dur))) togetherWith
                                (slideOutVertically(tween(dur, easing = FastOutSlowInEasing)) { it / 8 } +
                                    scaleOut(ease, targetScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 1f)) +
                                    fadeOut(tween(dur)))
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
                                ModernLyricsPanel(
                                    state = state,
                                    player = player,
                                    lyricsState = lyricsState,
                                    lyricsProviderStatuses = lyricsProviderStatuses,
                                    onProbeLyricsProviders = onProbeLyricsProviders,
                                    progressState = progressState,
                                    wavySeekbarEnabled = wavySeekbarEnabled,
                                    lyricsOffsetMs = lyricsOffsetMs,
                                    lyricsFontScale = lyricsFontScale,
                                    onLyricsFontScaleChange = onSetLyricsFontScale ?: {},
                                    controlsAutoHideSeconds = lyricsButtonsAutoHideSeconds,
                                    controlsVisibleState = lyricsControlsVisible,
                                    onControlsVisibleChange = { lyricsControlsVisible = it },
                                    translationLanguage = translationLanguage,
                                    onRetry = onRetryLyrics,
                                    onSelectLyricsProvider = onSelectLyricsProvider,
                                    onTryAnotherLyricsSource = onTryAnotherLyricsSource,
                                    onToggleFullscreen = { lyricsFullscreen = !lyricsFullscreen },
                                    isFullscreen = lyricsFullscreen,
                                    onOpenLyricsOffset = { showLyricsOffsetDialog = true },
                                    primaryColor = ambientColor,
                                    secondaryColor = ambientCompanion,
                                    tertiaryColor = ambientDeep,
                                    resolvedArtworkUrl = resolvedAmbientUrl,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .adaptiveContentWidth(maxWidth = if (isTabletLandscape) 960.dp else 720.dp),
                                )
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
                                        .adaptiveContentWidth(maxWidth = if (isTabletLandscape) 960.dp else 720.dp)
                                        .padding(horizontal = 12.dp),
                                )
                            }
                        }

                        FullPlayerTab.NOW_PLAYING -> {
                            if (isTabletLandscape) {
                                FullPlayerLandscape(
                                    track = track,
                                    state = state,
                                    player = player,
                                    progressState = progressState,
                                    wavySeekbarEnabled = wavySeekbarEnabled,
                                    showSleeveCanvas = showSleeveCanvas,
                                    canvas = activeCanvas ?: canvas,
                                    ambientColor = ambientColor,
                                    ambientCompanion = ambientCompanion,
                                    ambientDeep = ambientDeep,
                                    isLiked = isLiked,
                                    onToggleLiked = onToggleLiked,
                                    onDoubleTapLike = onDoubleTapLike,
                                    youTubeLiked = youTubeLiked,
                                    onToggleYouTubeLike = onToggleYouTubeLike,
                                    onOpenArtist = onOpenArtist,
                                    onTabChange = onTabChange,
                                    shownDismissY = shownDismissY,
                                    swipeThreshold = swipeThreshold,
                                    onOpenSignalPath = { showSignalPath = true },
                                    onOpenAudioControls = { showAudioControls = true },
                                    musicVideoEnabled = musicVideoEnabled,
                                    isVideoActive = isVideoActive,
                                    onCloseVideo = { syncedVideoVm.setVideoActive(false) },
                                    onEnterFullscreen = { isVideoFullscreen = true },
                                    onToggleVideo = onToggleSyncedVideo,
                                )
                            } else {
                                // â”€â”€ Standard layout (lifted and balanced) â”€â”€â”€â”€â”€â”€
                                Column(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = if (isVideoActive) 0.dp else 20.dp)
                                        .padding(bottom = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    val hasArtwork = !track.artworkUrl.isNullOrBlank() || !resolvedAmbientUrl.isNullOrBlank() || activeCanvas != null
                                    val sleeveAlpha by animateFloatAsState(
                                        targetValue = if (showFullBleed && hasArtwork) 0f else 1f,
                                        animationSpec = tween(350),
                                        label = "sleeveAlpha",
                                    )

                                    BoxWithConstraints(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .onGloballyPositioned { coordinates ->
                                                val rootPos = coordinates.positionInRoot()
                                                val bottom = rootPos.y + coordinates.size.height
                                                if (bottom > 0f && bottom != heroBottomPx) {
                                                    heroBottomPx = bottom
                                                }
                                            }
                                            .pointerInput(track.videoId, track.title, isVideoActive) {
                                                if (isVideoActive) return@pointerInput
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
                                                                isArtworkDragging = false
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
                                                            isArtworkDragging = false
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
                                                                isArtworkDragging = true
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
                                        contentAlignment = if (isVideoActive) Alignment.Center else BiasAlignment(0f, if (isTablet) -0.15f else -0.55f),
                                    ) {
                                        val artworkSize = (minOf(maxWidth, maxHeight) - 6.dp)
                                            .coerceAtLeast(0.dp)
                                            .coerceAtMost(if (isTablet) 460.dp else 370.dp)

                                        if (sleeveAlpha > 0.001f) {
                                            if (isVideoActive) {
                                                val videoGlowAlpha by animateFloatAsState(
                                                    targetValue = if (state.isPlaying) 0.55f else 0.25f,
                                                    animationSpec = tween(600),
                                                    label = "videoGlowAlpha",
                                                )
                                                // Ambient glow matching playing track colors behind the 16:9 video
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .aspectRatio(16f / 9f)
                                                        .graphicsLayer {
                                                             translationX = shownArtworkX * 0.7f
                                                             alpha = videoGlowAlpha * sleeveAlpha
                                                        }
                                                        .background(
                                                            Brush.radialGradient(
                                                                0.0f to ambientColor.copy(alpha = 0.50f),
                                                                0.60f to ambientCompanion.copy(alpha = 0.22f),
                                                                1.0f to Color.Transparent,
                                                            ),
                                                        ),
                                                )

                                                Surface(
                                                    shape = RoundedCornerShape(0.dp),
                                                    color = Color.Black,
                                                    border = null,
                                                    tonalElevation = 0.dp,
                                                    shadowElevation = 0.dp,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .aspectRatio(16f / 9f)
                                                        .graphicsLayer {
                                                            translationX = shownArtworkX
                                                            rotationZ = shownArtworkX / 80f
                                                            alpha = sleeveAlpha
                                                        },
                                                ) {
                                                    SyncedCoverVideoPlayer(
                                                        track = track,
                                                        musicPlayer = player,
                                                        onCloseVideo = { syncedVideoVm.setVideoActive(false) },
                                                        onEnterFullscreen = { isVideoFullscreen = true },
                                                        modifier = Modifier.fillMaxSize(),
                                                    )
                                                }
                                            } else {
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
                                                        alpha = glowAlpha * sleeveAlpha
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
                                                shape = RoundedCornerShape(22.dp),
                                                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.88f * sleeveAlpha),
                                                tonalElevation = 6.dp * sleeveAlpha,
                                                shadowElevation = if (state.isPlaying) 28.dp * sleeveAlpha else 12.dp * sleeveAlpha,
                                                modifier = Modifier
                                                    .size(artworkSize)
                                                    .graphicsLayer {
                                                        scaleX = artworkPlayingScale
                                                        scaleY = artworkPlayingScale
                                                        translationX = shownArtworkX
                                                        rotationZ = shownArtworkX / 80f
                                                        alpha = sleeveAlpha
                                                    },
                                            ) {
                                                PlayerArtwork(
                                                    track = track,
                                                    modifier = Modifier.fillMaxSize(),
                                                    corner = 22.dp,
                                                    canvas = if (showSleeveCanvas) canvas else null,
                                                    isPlaying = state.isPlaying,
                                                    pausedForTransition = shownDismissY > 1f || currentTab != FullPlayerTab.NOW_PLAYING,
                                                    onAspectRatioChanged = { canvasAspect = it },
                                                )
                                            }
                                            }
                                        }

                                        androidx.compose.animation.AnimatedVisibility(
                                            visible = seekOverlayDirection == SeekDirection.REWIND,
                                            enter = fadeIn(tween(100)) + scaleIn(ExpressiveMotion.spatialSpring(), initialScale = 0.88f),
                                            exit = fadeOut(tween(200)),
                                            modifier = Modifier
                                                .align(Alignment.CenterStart)
                                                .fillMaxWidth(0.42f)
                                                .height(130.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(RoundedCornerShape(topStart = 32.dp, bottomStart = 32.dp, topEnd = 72.dp, bottomEnd = 72.dp))
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
                                                                contentDescription = stringResource(com.lastwave.app.R.string.player_seek_back_cd),
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
                                                .fillMaxWidth(0.42f)
                                                .height(130.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp, topStart = 72.dp, bottomStart = 72.dp))
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
                                                                contentDescription = stringResource(com.lastwave.app.R.string.player_seek_fwd_cd),
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
                                    Spacer(Modifier.height(4.dp))
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = if (isVideoActive) 20.dp else 0.dp)
                                            .graphicsLayer { translationY = -14.dp.toPx() },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                track.title,
                                                style = MaterialTheme.typography.headlineSmall.copy(
                                                    letterSpacing = (-0.35).sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                ),
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .basicMarquee(iterations = Int.MAX_VALUE),
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            val splitArtists = remember(track.artist) {
                                                com.lastwave.app.util.ArtistHelper.splitArtists(track.artist)
                                            }
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .basicMarquee(iterations = Int.MAX_VALUE),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                splitArtists.forEachIndexed { index, artName ->
                                                    Text(
                                                        text = artName,
                                                        style = MaterialTheme.typography.titleMedium.copy(
                                                            fontSize = 17.sp,
                                                            fontWeight = FontWeight.Medium,
                                                        ),
                                                        color = Color.White.copy(alpha = 0.72f),
                                                        maxLines = 1,
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
                                                            maxLines = 1,
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Spacer(Modifier.width(12.dp))

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
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
                                            YouTubeLikeButton(youTubeLiked, onToggleYouTubeLike, size = 46.dp)
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
                                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        Icons.Filled.FormatQuote,
                                                        contentDescription = stringResource(com.lastwave.app.R.string.player_show_lyrics_cd),
                                                        modifier = Modifier.size(24.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    SeekBar(
                                        progressState = progressState,
                                        isPlaying = state.isPlaying,
                                        trackKey = track.videoId ?: "${track.artist}|${track.title}",
                                        wavyEnabled = wavySeekbarEnabled,
                                        onSeek = player::seekTo,
                                        isTranslucent = LocalLiquidGlass.current || showFullBleed,
                                        fallbackDurationMs = track.durationMs ?: state.durationMs,
                                        primaryColor = ambientColor,
                                        secondaryColor = ambientCompanion,
                                        tertiaryColor = ambientDeep,
                                        telemetry = state.deckBlendTelemetry,
                                    )
                                    Spacer(Modifier.height(0.dp))
                                    AppleMusicQualityBadge(
                                        state = state,
                                        signalPath = signalPath,
                                        isTranslucent = LocalLiquidGlass.current || showFullBleed,
                                        onClick = { showSignalPath = true },
                                        modifier = Modifier.offset(y = (-2).dp),
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    MainControls(state, player, isTranslucent = LocalLiquidGlass.current || showFullBleed)
                                    Spacer(Modifier.height(30.dp))
                                    PlayerUtilityControls(
                                        state = state,
                                        player = player,
                                        isTranslucent = LocalLiquidGlass.current || showFullBleed,
                                        onOpenAudioControls = { showAudioControls = true },
                                        isVideoActive = isVideoActive,
                                        musicVideoEnabled = musicVideoEnabled,
                                        onToggleVideo = onToggleSyncedVideo,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                                }
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
                                    maxLines = 2,
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
            canWatchVideo = canWatchVideo,
            onWatchVideo = if (canWatchVideo) ({ syncedVideoVm.setVideoActive(true) }) else null,
        )
    }

    if (showLyricsOffsetDialog && onSetLyricsOffsetMs != null) {
        LyricsOffsetDialog(
            currentMs = lyricsOffsetMs,
            onSelect = onSetLyricsOffsetMs,
            onDismiss = { showLyricsOffsetDialog = false },
        )
    }
    if (showSignalPath) {
        val dac = usbDac.dac
        val internalAvailability by player.internalDirectAvailability.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { player.refreshInternalDirectAvailability() }
        SignalPathDialog(
            report = signalPath,
            needsUsbPermission = dac != null && dac.hasUsbPeripheral && !dac.usbPermissionGranted,
            onRequestUsbAccess = player::requestUsbPermission,
            onDismiss = { showSignalPath = false },
            internalAvailability = internalAvailability,
            internalEnabled = player.isInternalDirectPrefEnabled,
            internalUnderruns = if (signalPath.internalDirectActive) player.internalDirectUnderruns() else null,
            usbDiagnostic = if (dac != null) player::usbDiagnosticReport else null,
        )
    }
    if (showAudioControls) {
        AudioControlsSheet(
            player = player,
            state = state,
            onDismiss = { showAudioControls = false },
            onOpenAutoEq = { showAutoEq = true },
            showAutoEq = showAutoEqInVolumePanel,
        )
    }
    if (showAutoEq) {
        com.lastwave.app.ui.settings.autoeq.AutoEqDialog(onDismiss = { showAutoEq = false })
    }
}
}
