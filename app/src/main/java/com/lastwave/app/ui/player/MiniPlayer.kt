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
            contentDescription = stringResource(com.lastwave.app.R.string.player_play_pause_cd),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun MiniPlayer(
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
    /** Root bounds of the visible card (after padding), for the open/close morph. */
    onCardBounds: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val track = state.current ?: return
    // Haptics for every mini-player control and swipe gesture.
    val miniHaptic = LocalHapticFeedback.current
    @Suppress("NAME_SHADOWING")
    val onToggle: () -> Unit = { miniHaptic.performHapticFeedback(HapticFeedbackType.ContextClick); onToggle() }
    @Suppress("NAME_SHADOWING")
    val onNext: () -> Unit = { miniHaptic.performHapticFeedback(HapticFeedbackType.ContextClick); onNext() }
    @Suppress("NAME_SHADOWING")
    val onPrevious: () -> Unit = { miniHaptic.performHapticFeedback(HapticFeedbackType.ContextClick); onPrevious() }
    @Suppress("NAME_SHADOWING")
    val onExpand: () -> Unit = { miniHaptic.performHapticFeedback(HapticFeedbackType.ContextClick); onExpand() }
    @Suppress("NAME_SHADOWING")
    val onClose: () -> Unit = { miniHaptic.performHapticFeedback(HapticFeedbackType.GestureEnd); onClose() }
    // MiniPlayer: single glass card sampling sibling feed content.
    // Luminance loop (5x5 avg, 0.3..0.8, tween 500, 1s).
    val liquidGlass = LocalLiquidGlass.current
    val isGlass = liquidGlass && isLiquidGlassBackdropSupported() && backdrop != null
    val layer = rememberGraphicsLayer()
    val luminance = 0.5f // Fixed luminance to avoid heavy GPU readback which causes stuttering
    val barInteraction = remember { MutableInteractionSource() }
    var isMiniDragging by remember(track.videoId, track.title) { mutableStateOf(false) }
    var dragX by remember(track.videoId, track.title) { mutableFloatStateOf(0f) }
    var dragY by remember(track.videoId, track.title) { mutableFloatStateOf(0f) }
    val shownX by animateFloatAsState(
        targetValue = dragX,
        animationSpec = if (isMiniDragging) snap() else ExpressiveMotion.spatialDefault(),
        label = "miniPlayerX",
    )
    val shownY by animateFloatAsState(
        targetValue = dragY,
        animationSpec = if (isMiniDragging) snap() else ExpressiveMotion.spatialDefault(),
        label = "miniPlayerY",
    )
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val isTablet = isTabletOrWideScreen()
    val shape = if (edgeToEdge && !isTablet) RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp) else RoundedCornerShape(100)
    val positionedModifier = if (isGlass && edgeToEdge && !isTablet) {
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
            .then(
                if (onCardBounds != null) {
                    Modifier.onGloballyPositioned { onCardBounds(it.boundsInRoot()) }
                } else {
                    Modifier
                },
            )
            .graphicsLayer {
                translationX = shownX
                translationY = shownY.coerceAtLeast(0f)
                alpha = (1f - (abs(shownX) + shownY.coerceAtLeast(0f)) / (threshold * 4f)).coerceIn(0.55f, 1f)
            }
            .pointerInput(track.videoId, track.title) {
                detectDragGestures(
                    onDragStart = { isMiniDragging = true },
                    onDragCancel = {
                        isMiniDragging = false
                        dragX = 0f
                        dragY = 0f
                    },
                    onDragEnd = {
                        isMiniDragging = false
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
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isGlass) {
            Surface(
                shape = shape,
                // Transparent card when glass (glass draws scrim), 95% primaryContainer otherwise to match theme color.
                color = Color.Transparent,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                modifier = Modifier.fillMaxWidth().then(
                    if (backdrop != null) {
                        Modifier.liquidGlass(backdrop, layer, luminance, shape)
                    } else Modifier
                )
                .clickable(interactionSource = barInteraction, indication = null, onClick = onExpand),
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
                                PlayerArtwork(
                                    track = track,
                                    modifier = Modifier.fillMaxSize(),
                                    corner = 18.dp,
                                )
                            }
                        }
                        val miniTitleColor = if (LocalIsDarkTheme.current) Color.White else Color.Black
                        val miniArtistColor = if (LocalIsDarkTheme.current) Color.White.copy(alpha = 0.7f)
                            else Color.Black.copy(alpha = 0.7f)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(
                                track.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = miniTitleColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                            )
                            Text(
                                track.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = miniArtistColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                            )
                        }
                        // Inner controls sit plain on the glass card (no nested glass).
                        Surface(
                            onClick = onToggle,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(48.dp),
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
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
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
        } else {
            // Material 3 (non-Liquid Glass) Mini-Player
            val m3ArtworkCornerPercent by animateIntAsState(
                targetValue = if (state.isPlaying) 50 else 28,
                animationSpec = ExpressiveMotion.spatialDefault(),
                label = "m3MiniArtworkCorner",
            )
            val m3PlayPauseCornerPercent by animateIntAsState(
                targetValue = if (state.isPlaying) 32 else 50,
                animationSpec = ExpressiveMotion.spatialDefault(),
                label = "m3MiniPlayPauseCorner",
            )

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clickable(interactionSource = barInteraction, indication = null, onClick = onExpand),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 10.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Concentric Left Geometry: 54.dp artwork nesting inside the 72.dp capsule curve
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(percent = m3ArtworkCornerPercent))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        PlayerArtwork(
                            track = track,
                            modifier = Modifier.fillMaxSize(),
                            corner = 0.dp,
                        )
                    }

                    // Typography Hierarchy: 15.sp bold title (-0.2.sp letterSpacing) + 13.sp medium artist (0.78 alpha) + 1.dp space
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = track.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.2).sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = track.artist,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                        )
                    }

                    // Clean solid play/pause button without outer progress indicator
                    Surface(
                        onClick = onToggle,
                        shape = RoundedCornerShape(percent = m3PlayPauseCornerPercent),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(42.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (state.isBuffering) {
                                ExpressiveInlineLoadingIndicator(
                                    size = 20.dp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                AnimatedPlayPauseIcon(state.isPlaying, Modifier.size(22.dp))
                            }
                        }
                    }

                    // Skip Next IconButton with an outline stroke
                    IconButton(
                        onClick = onNext,
                        enabled = state.queueSize > 1,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(40.dp)
                            .border(
                                width = 1.2.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                                shape = CircleShape,
                            )
                            .clip(CircleShape),
                    ) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = "Next",
                            modifier = Modifier.size(22.dp),
                            tint = if (state.queueSize > 1) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MiniPlayerProgress(progressState: StateFlow<PlaybackProgressState>) {
    val progressFractionState = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(progressState) {
        progressState.collect { s ->
            val p = if (s.durationMs > 0) s.positionMs.toFloat() / s.durationMs else 0f
            progressFractionState.floatValue = p
        }
    }
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
                    scaleX = progressFractionState.floatValue.coerceIn(0f, 1f)
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
        // Outer Box is 26x22 (16+5+5, 12+5+5) â€” always tiny, bottom-end aligned by caller.
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
