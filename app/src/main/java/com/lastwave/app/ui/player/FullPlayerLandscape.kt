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
import com.lastwave.app.ui.common.playingMarquee
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
internal fun FullPlayerLandscape(
    track: PlayableTrack,
    state: MusicPlayerState,
    player: MusicPlayer,
    progressState: StateFlow<PlaybackProgressState>?,
    wavySeekbarEnabled: Boolean,
    showSleeveCanvas: Boolean,
    canvas: com.lastwave.app.data.canvas.CanvasArtwork?,
    ambientColor: Color,
    ambientCompanion: Color,
    ambientDeep: Color,
    isLiked: Boolean,
    onToggleLiked: () -> Unit,
    onDoubleTapLike: () -> Unit,
    youTubeLiked: Boolean?,
    onToggleYouTubeLike: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onTabChange: (FullPlayerTab) -> Unit,
    shownDismissY: Float,
    swipeThreshold: Float,
    onOpenSignalPath: () -> Unit = {},
    onOpenAudioControls: () -> Unit = {},
    musicVideoEnabled: Boolean = true,
    showAutoEqInVolumePanel: Boolean = true,
    isVideoActive: Boolean = false,
    onCloseVideo: () -> Unit = {},
    onEnterFullscreen: () -> Unit = {},
    onToggleVideo: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val windowActive = rememberPlayerWindowActive()
    var isArtworkDragging by remember { mutableStateOf(false) }
    var artworkDragX by remember { mutableFloatStateOf(0f) }
    val shownArtworkX by animateFloatAsState(
        targetValue = artworkDragX,
        animationSpec = if (isArtworkDragging) snap() else ExpressiveMotion.spatialDefault(),
        label = "landscapeArtworkDragX",
    )
    var seekOverlaySeconds by remember { mutableIntStateOf(5) }
    var seekOverlayDirection by remember { mutableStateOf<SeekDirection?>(null) }
    var lastTapSide by remember { mutableStateOf<SeekDirection?>(null) }
    var lastTapTimestamp by remember { mutableLongStateOf(0L) }
    var lastLikeTapTimestamp by remember { mutableLongStateOf(0L) }
    var seekResetJob by remember { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var canvasAspect by remember { mutableFloatStateOf(1f) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left Column: Large Album Artwork & Canvas with gestures
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            val artworkSize = (minOf(maxWidth, maxHeight) - 16.dp)
                .coerceAtLeast(0.dp)
                .coerceAtMost(480.dp)

            val glowAlpha by animateFloatAsState(
                targetValue = if (state.isPlaying) 0.65f else 0.35f,
                animationSpec = tween(600),
                label = "landscapeArtworkGlowAlpha",
            )
            Box(
                modifier = Modifier
                    .size(artworkSize + 36.dp)
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
                targetValue = if (state.isPlaying) 1.0f else 0.92f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "landscapeArtworkPlayingScale",
            )

            Surface(
                shape = RoundedCornerShape(24.dp),
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
                                            lastTapSide = null
                                            lastTapTimestamp = 0L
                                            if (lastLikeTapTimestamp != 0L && now - lastLikeTapTimestamp < 450L) {
                                                lastLikeTapTimestamp = 0L
                                                onDoubleTapLike()
                                            } else {
                                                lastLikeTapTimestamp = now
                                            }
                                        } else {
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
                                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
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
            ) {
                if (isVideoActive) {
                    SyncedCoverVideoPlayer(
                        track = track,
                        musicPlayer = player,
                        onCloseVideo = onCloseVideo,
                        onEnterFullscreen = onEnterFullscreen,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    PlayerArtwork(
                        track = track,
                        modifier = Modifier.fillMaxSize(),
                        corner = 24.dp,
                        canvas = if (showSleeveCanvas) canvas else null,
                        isPlaying = state.isPlaying,
                        pausedForTransition = shownDismissY > 1f,
                        onAspectRatioChanged = { canvasAspect = it },
                    )
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

        // Right Column: Controls & Metadata
        Column(
            modifier = Modifier
                .weight(1.15f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
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
                        style = MaterialTheme.typography.headlineMedium.copy(
                            letterSpacing = (-0.35).sp,
                            fontWeight = FontWeight.ExtraBold,
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .playingMarquee(state.isPlaying && windowActive),
                    )
                    Spacer(Modifier.height(4.dp))
                    val splitArtists = remember(track.artist) {
                        com.lastwave.app.util.ArtistHelper.splitArtists(track.artist)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .playingMarquee(state.isPlaying && windowActive),
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
                                        onClick = hapticClick { onOpenArtist(artName) },
                                    ),
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

                Spacer(Modifier.width(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerLikeButton(
                        liked = isLiked,
                        onClick = hapticClick(if (isLiked) HapticFeedbackType.ToggleOff else HapticFeedbackType.Confirm, onToggleLiked),
                        size = 50.dp,
                    )
                    val lyricsInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val isLyricsPressed by lyricsInteraction.collectIsPressedAsState()
                    val lyricsScale by animateFloatAsState(
                        targetValue = if (isLyricsPressed) 0.82f else 1.0f,
                        animationSpec = ExpressiveMotion.spatialSpring(),
                        label = "landscapeLyricsScale",
                    )
                    LiquidGlassSurface(
                        glassModifier = Modifier.liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.PlayerControls, interactionSource = lyricsInteraction),
                        onClick = hapticClick { onTabChange(FullPlayerTab.LYRICS) },
                        interactionSource = lyricsInteraction,
                        shape = CircleShape,
                        color = liquidGlassContainerColor(Color.White.copy(alpha = 0.14f)),
                        contentColor = Color.White.copy(alpha = 0.90f),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        modifier = Modifier
                            .size(50.dp)
                            .graphicsLayer {
                                scaleX = lyricsScale
                                scaleY = lyricsScale
                            },
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.FormatQuote,
                                contentDescription = "Show lyrics",
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            SeekBar(
                progressState = progressState ?: player.progressState,
                isPlaying = state.isPlaying,
                trackKey = track.videoId ?: "${track.artist}|${track.title}",
                wavyEnabled = wavySeekbarEnabled,
                onSeek = player::seekTo,
                isTranslucent = LocalLiquidGlass.current,
                fallbackDurationMs = track.durationMs ?: state.durationMs,
                primaryColor = ambientColor,
                secondaryColor = ambientCompanion,
                tertiaryColor = ambientDeep,
                telemetry = state.deckBlendTelemetry,
            )

            Spacer(Modifier.height(0.dp))

            val signalPath by player.signalPath.collectAsStateWithLifecycle()
            AppleMusicQualityBadge(
                state = state,
                signalPath = signalPath,
                isTranslucent = LocalLiquidGlass.current,
                onClick = onOpenSignalPath,
                modifier = Modifier.offset(y = (-2).dp),
            )
            Spacer(Modifier.height(14.dp))

            MainControls(state, player, isTranslucent = LocalLiquidGlass.current)

            Spacer(Modifier.height(30.dp))

            PlayerUtilityControls(
                state = state,
                player = player,
                isTranslucent = LocalLiquidGlass.current,
                onOpenAudioControls = onOpenAudioControls,
                isVideoActive = isVideoActive,
                musicVideoEnabled = musicVideoEnabled,
                onToggleVideo = onToggleVideo,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
