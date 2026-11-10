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
val LocalMiniPlayerSnackbarClearance = staticCompositionLocalOf { 0.dp }


val LocalBottomNavSlot = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.MutableState<@androidx.compose.runtime.Composable () -> Unit>> {
    error("LocalBottomNavSlot not provided")
}

/** App-wide collapsed + maximized player layered over every navigation route. */
@Composable
fun PlayerHost(
    viewModel: PlayerViewModel = hiltViewModel(),
    hasBottomNavigation: Boolean = false,
    hideMiniPlayer: Boolean = false,
    /**
     * A screen built around a WebView (Spotify / YouTube sign-in). The mini player is
     * hidden and the liquid-glass backdrop stops capturing the page: re-recording a
     * WebView into an offscreen layer every frame (the mini player animates while
     * music plays) makes the page stutter and jitter on some GPUs/ROMs.
     */
    webContentScreen: Boolean = false,
    content: @Composable () -> Unit,
) {
    @Suppress("NAME_SHADOWING")
    val hideMiniPlayer = hideMiniPlayer || webContentScreen
    val state by viewModel.chromeState.collectAsStateWithLifecycle()
    val playbackState by viewModel.player.stateWithoutProgress.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var currentTab by rememberSaveable { mutableStateOf(FullPlayerTab.NOW_PLAYING) }
    var playlistTrack by remember { mutableStateOf<PlayableTrack?>(null) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val keepLyricsAwake = expanded && currentTab == FullPlayerTab.LYRICS && settings.keepLyricsScreenAwake
    val currentView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(keepLyricsAwake) {
        if (keepLyricsAwake) {
            currentView.keepScreenOn = true
        }
        onDispose {
            if (keepLyricsAwake) {
                currentView.keepScreenOn = false
            }
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
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
    LaunchedEffect(Unit) {
        com.lastwave.app.widget.wave.WaveWidgetNavigation.requests.collect { requestId ->
            if (requestId <= 0L) return@collect
            val hasTrack = viewModel.player.state.value.current != null ||
                withTimeoutOrNull(2_000L) {
                    viewModel.player.state.first { it.current != null }
                    true
                } == true
            if (hasTrack && com.lastwave.app.widget.wave.WaveWidgetNavigation.consume(requestId)) {
                currentTab = FullPlayerTab.NOW_PLAYING
                expanded = true
            }
        }
    }
    val miniPlayerVisible = state.current != null && !expanded && !settings.buttonNowPlayingEnabled && !hideMiniPlayer

    // Sibling pattern: ONE backdrop capturing feed content,
    // consumed by the sibling MiniPlayer. Unconditional remember keeps composition stable.
    val miniBackdropColor = MaterialTheme.colorScheme.background
    val miniBackdrop = rememberLayerBackdrop {
        drawRect(miniBackdropColor)
        drawContent()
    }
    val miniGlass = LocalLiquidGlass.current && isLiquidGlassBackdropSupported() && !webContentScreen

    // Player presentation (open / close): driven by sheet expansion progress (0..1).
    // The player slides smoothly from the bottom over the content like a native
    // sheet with spring physics (matching PixelMusic), while the page behind
    // receives a clean dimming scrim without blur or zoom distortions.
    val playerExpansion = remember { Animatable(if (expanded && state.current != null) 1f else 0f) }
    val expansionTarget = if (expanded && state.current != null) 1f else 0f
    LaunchedEffect(expansionTarget) {
        playerExpansion.animateTo(
            targetValue = expansionTarget,
            animationSpec = if (expansionTarget == 1f) PlayerSheetOpenSpec else PlayerSheetCloseSpec,
        )
    }

    val playerLayerShown by remember { derivedStateOf { playerExpansion.value > 0f } }
    val showMiniPlayer by remember { derivedStateOf { playerExpansion.value < 0.45f } }
    val showBottomNav by remember { derivedStateOf { state.current == null || playerExpansion.value < 0.625f } }
    // Mini player rides above the growing card for the first half of the morph.
    val miniOnTop by remember { derivedStateOf { playerExpansion.value > 0f && playerExpansion.value < 0.5f } }

    // Container-transform morph: the full player grows out of the mini player card
    // (PixelMusic-style). Bounds are captured only while the player is at rest so
    // the mini player's own follow-the-card translation can't feed back into them.
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    var miniCardBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val morphCardColor = MaterialTheme.colorScheme.surfaceContainerHigh

    val bottomNavSlot = remember { mutableStateOf<@Composable () -> Unit>({}) }
    LaunchedEffect(viewModel) {
        viewModel.toastMessage.collect { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    CompositionLocalProvider(
        LocalMusicPlayer provides viewModel.player,
        LocalAddToPlaylist provides requestAddToPlaylist,
        LocalMiniPlayerScrollClearance provides if (state.current != null && !settings.buttonNowPlayingEnabled && !hideMiniPlayer) 98.dp else 0.dp,
        LocalMiniPlayerSnackbarClearance provides if (miniPlayerVisible) {
            // Keep snackbars clear of the tray: its bottom lift plus its ~80dp card.
            (if (hasBottomNavigation) {
                if (LocalMd3NavBar.current) 84.dp else 80.dp
            } else {
                14.dp
            }) + 80.dp
        } else {
            0.dp
        },
        LocalBottomNavSlot provides bottomNavSlot,
        LocalLiquidGlassBackdrop provides (if (miniGlass) miniBackdrop else null),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { hostOrigin = it.boundsInRoot().topLeft },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        val p = playerExpansion.value.coerceIn(0f, 1f)
                        if (p > 0f) {
                            drawRect(Color.Black.copy(alpha = 0.45f * p))
                        }
                    },
            ) {
                Box(Modifier.fillMaxSize().liquidGlassSource(if (miniGlass) miniBackdrop else null)) {
                    content()
                }
            }
            if (state.current != null && !settings.buttonNowPlayingEnabled && !hideMiniPlayer && showMiniPlayer) {
                MiniPlayer(
                    state = state,
                    progressState = viewModel.progressState,
                    onExpand = { expanded = true },
                    onToggle = viewModel.player::togglePlayPause,
                    onPrevious = viewModel.player::previous,
                    onNext = viewModel.player::next,
                    onClose = viewModel.player::stopAndClear,
                    bottomPadding = if (hasBottomNavigation) {
                        if (LocalMd3NavBar.current) 84.dp else 80.dp
                    } else {
                        14.dp
                    },
                    edgeToEdge = !hasBottomNavigation,
                    backdrop = if (miniGlass) miniBackdrop else null,
                    onCardBounds = { rootBounds ->
                        if (playerExpansion.value == 0f) {
                            miniCardBounds = rootBounds.translate(-hostOrigin)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(if (miniOnTop) 1f else 0f)
                        .graphicsLayer {
                            val p = playerExpansion.value.coerceIn(0f, 1f)
                            alpha = (1f - p * 2.2f).coerceIn(0f, 1f)
                            // Follow the morphing card's top edge as it rises.
                            translationY = -(miniCardBounds?.top ?: 0f) * p
                        },
                )
            }

            // Sleep Timer Overlay Button (shown whenever timer is active, regardless of mini-player or button mode)
            if (!expanded && playbackState.sleepTimerRemainingMs != null) {
                DraggableSleepTimerOverlay(
                    sleepTimerRemainingMs = playbackState.sleepTimerRemainingMs,
                    onCycleTimer = { viewModel.player.cycleSleepTimer() },
                    onCloseTimer = { viewModel.player.setSleepTimerMinutes(0) },
                )
            }
            val currentTrack = state.current
            if (currentTrack != null && !expanded && settings.buttonNowPlayingEnabled) {
                DraggableNowPlayingButton(
                    track = currentTrack,
                    progressState = viewModel.progressState,
                    isPlaying = state.isPlaying,
                    onExpand = { expanded = true },
                    onClose = viewModel.player::stopAndClear,
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
            var isPlayerDragging by remember { mutableStateOf(false) }
            if (playerLayerShown && state.current != null) {
                val sheetDensity = LocalDensity.current
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = playerExpansion.value.coerceIn(0f, 1f)
                            // Start rect = mini player card; fallback (button mode / no mini)
                            // = zero-height strip at the bottom, i.e. a reveal from below.
                            val start = miniCardBounds
                                ?: androidx.compose.ui.geometry.Rect(0f, size.height, size.width, size.height)
                            val left = androidx.compose.ui.util.lerp(start.left, 0f, p)
                            val right = androidx.compose.ui.util.lerp(start.right, size.width, p)
                            val top = androidx.compose.ui.util.lerp(start.top, 0f, p)
                            val bottom = androidx.compose.ui.util.lerp(start.bottom, size.height, p)
                            val startRadius = minOf(start.height / 2f, PLAYER_SHEET_CORNER_RADIUS.toPx() + 8.dp.toPx())
                            // Content is anchored to the card's top edge and clipped to the card.
                            translationY = top
                            shape = PlayerMorphShape(left, right, bottom - top, androidx.compose.ui.util.lerp(startRadius, 0f, p))
                            clip = p < 1f
                        }
                        .drawBehind {
                            // Solid card surface visible until the full player fades in.
                            if (playerExpansion.value < 1f) drawRect(morphCardColor)
                        },
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val p = playerExpansion.value.coerceIn(0f, 1f)
                                // Full player: invisible until 25% expanded, opaque at 100%,
                                // with a 24dp upward settle (PixelMusic FullPlayerVisualState).
                                val contentAlpha = ((p - 0.25f) / 0.75f).coerceIn(0f, 1f)
                                alpha = contentAlpha
                                val settlePx = with(sheetDensity) { 24.dp.toPx() }
                                translationY = androidx.compose.ui.util.lerp(settlePx, 0f, contentAlpha)
                            },
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
                                onDragStatusChange = { isPlayerDragging = it },
                            )
                        }
                    }
                }
            }

            if (showBottomNav) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = if (state.current == null) 1f else (1f - playerExpansion.value * 1.6f).coerceIn(0f, 1f)
                            val e = if (state.current == null) 0f else playerExpansion.value.coerceIn(0f, 1f)
                            alpha = p
                            // Nav bar sinks off the bottom as the player card grows.
                            translationY = e * 96.dp.toPx()
                        },
                ) {
                    bottomNavSlot.value()
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
internal fun ExpandedPlayer(
    viewModel: PlayerViewModel,
    currentTab: FullPlayerTab,
    onTabChange: (FullPlayerTab) -> Unit,
    onRetryLyrics: () -> Unit,
    onCollapse: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onDragStatusChange: (Boolean) -> Unit = {},
) {
    val state by viewModel.fullPlayerState.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()
    val lyricsProviderStatuses by viewModel.lyricsProviderStatuses.collectAsStateWithLifecycle()
    val canvas by viewModel.canvasState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val likedTrackKeys by viewModel.likedTrackKeys.collectAsStateWithLifecycle()
    val currentTrack = state.current
    // Same test Liked Songs storage uses: normalized title|artist OR YouTube id.
    val isLiked = currentTrack != null &&
        currentTrack.toGeneratedTrack().isLikedIn(likedTrackKeys)
    val youTubeLiked by viewModel.youTubeLiked.collectAsStateWithLifecycle()
    val lyricsOffsetMs by viewModel.liveLyricsOffsetMs.collectAsStateWithLifecycle()
    FullPlayer(
        state = state,
        progressState = viewModel.progressState,
        player = viewModel.player,
        lyricsState = lyricsState,
        lyricsProviderStatuses = lyricsProviderStatuses,
        onProbeLyricsProviders = viewModel::probeLyricsProviders,
        wavySeekbarEnabled = settings.wavySeekbarEnabled,
        rotatingBackgroundEnabled = settings.rotatingBackgroundEnabled,
        lyricsRotatingBackgroundEnabled = settings.lyricsRotatingBackgroundEnabled,
        lyricsOffsetMs = lyricsOffsetMs,
        onSetLyricsOffsetMs = viewModel::setLyricsOffsetMs,
        lyricsFontScale = settings.lyricsFontScale,
        onSetLyricsFontScale = viewModel::setLyricsFontScale,
        lyricsButtonsAutoHideSeconds = settings.lyricsButtonsAutoHideSeconds,
        translationLanguage = settings.translationLanguage,
        canvas = canvas,
        canvasEnabled = settings.canvasEnabled,
        canvasFullBleedEnabled = settings.canvasFullBleed,
        canvasGradientBlurEnabled = settings.canvasGradientBlurEnabled,
        musicVideoEnabled = settings.musicVideoEnabled,
        showAutoEqInVolumePanel = settings.showAutoEqInVolumePanel,
        currentTab = currentTab,
        onTabChange = onTabChange,
        onRetryLyrics = onRetryLyrics,
        onCollapse = onCollapse,
        onOpenArtist = onOpenArtist,
        isLiked = isLiked,
        onToggleLiked = { currentTrack?.let(viewModel::toggleLiked) },
        onDoubleTapLike = { currentTrack?.let(viewModel::like) },
        youTubeLiked = youTubeLiked,
        onToggleYouTubeLike = { currentTrack?.let(viewModel::toggleYouTubeLike) },
        onDragStatusChange = onDragStatusChange,
        onSelectLyricsProvider = viewModel::selectLyricsProvider,
        onTryAnotherLyricsSource = viewModel::tryAnotherLyricsSource,
    )
}

@Composable
internal fun PlayerArtwork(
    track: PlayableTrack,
    modifier: Modifier,
    corner: androidx.compose.ui.unit.Dp,
    decodeSizePx: Int? = null,
    canvas: com.lastwave.app.data.canvas.CanvasArtwork? = null,
    isPlaying: Boolean = false,
    pausedForTransition: Boolean = false,
    transformations: List<coil.transform.Transformation> = emptyList(),
    onAspectRatioChanged: (Float) -> Unit = {},
) {
    // The sleeve cover and its canvas are stacked in the same bounds: the
    // cover is the placeholder while the video loads, then hands the pixels
    // over as the canvas's own alpha climbs. Without this the static art
    // stays at full opacity underneath the video and shows through wherever
    // the canvas is transparent.
    var sleeveCover by remember(canvas) { mutableFloatStateOf(0f) }
    Box(modifier.clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        ArtworkImage(
            name = track.title,
            artist = track.artist,
            embeddedUrl = track.artworkUrl,
            fallbackIcon = Icons.Filled.MusicNote,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha =
                        if (sleeveCover >= 0.95f) 0f else (1f - sleeveCover).coerceIn(0f, 1f)
                },
            decodeSizePx = decodeSizePx,
            transformations = transformations,
        )
        if (canvas != null) {
            CanvasArtworkPlayer(
                canvas = canvas,
                isPlaying = isPlaying,
                pausedForTransition = pausedForTransition,
                onAspectRatioChanged = onAspectRatioChanged,
                onCoverChanged = { sleeveCover = it },
                contentMode = CanvasContentMode.CROP,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** True for files copied by the Local songs importer (app-private storage). */
internal fun PlayableTrack.isImportedLocalFile(): Boolean =
    com.lastwave.app.data.generate.LocalAudioFormats.importedPathOrNull(playbackUrl) != null

// -- Player open/close presentation (PixelMusic-style bottom sheet transition) ---

internal val PlayerSheetOpenSpec = spring<Float>(
    dampingRatio = 0.88f,
    stiffness = 110f,
    visibilityThreshold = 0.001f,
)
internal val PlayerSheetCloseSpec = spring<Float>(
    dampingRatio = 0.92f,
    stiffness = 95f,
    visibilityThreshold = 0.001f,
)

/** Top corner radius of the sliding sheet in collapsed/transit state. */
internal val PLAYER_SHEET_CORNER_RADIUS = 28.dp

/**
 * Outline for the morphing player card: a rounded rect spanning [left]..[right]
 * horizontally and 0..[height] vertically (layer is already translated to the card top).
 */
internal data class PlayerMorphShape(
    val left: Float,
    val right: Float,
    val height: Float,
    val radius: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): Outline {
        val rect = androidx.compose.ui.geometry.Rect(left, 0f, right, height.coerceAtLeast(0f))
        val r = radius.coerceIn(0f, rect.minDimension / 2f)
        return if (r <= 0.5f) {
            Outline.Rectangle(rect)
        } else {
            Outline.Rounded(androidx.compose.ui.geometry.RoundRect(rect, CornerRadius(r, r)))
        }
    }
}
