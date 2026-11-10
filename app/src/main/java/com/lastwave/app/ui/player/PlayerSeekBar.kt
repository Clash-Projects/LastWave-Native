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


internal enum class SeekDirection { REWIND, FORWARD }

@Composable
internal fun PlayerProgressSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    tertiaryColor: Color = MaterialTheme.colorScheme.tertiary,
) {
    val primary = remember(primaryColor) {
        primaryColor.ensureLuminousForDarkBackground(0.38f)
    }
    val tertiary = remember(tertiaryColor) {
        tertiaryColor.ensureLuminousForDarkBackground(0.28f)
    }
    val inactive = Color.White.copy(alpha = if (enabled) 0.24f else 0.14f)
    val range = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val fraction = ((value - valueRange.start) / range).coerceIn(0f, 1f)
    val thumbColor = if (enabled) primary else Color.White.copy(alpha = 0.34f)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier = modifier.height(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val centerY = size.height / 2f

                val trackHeightPx = 14.dp.toPx()
                val cornerRadius = CornerRadius(
                    trackHeightPx / 2f,
                    trackHeightPx / 2f,
                )
                val thumbWidthPx = 5.dp.toPx()
                val thumbHeightPx = 38.dp.toPx()
                val thumbClearancePx = 9.dp.toPx()

                val rawThumbCenterX = fraction * width
                val thumbCenterX = if (width > thumbWidthPx) {
                    rawThumbCenterX.coerceIn(
                        thumbWidthPx / 2f,
                        width - thumbWidthPx / 2f,
                    )
                } else {
                    width / 2f
                }

                val activeEndX = (thumbCenterX - thumbClearancePx)
                    .coerceIn(0f, width)
                val inactiveStartX = (thumbCenterX + thumbClearancePx)
                    .coerceIn(0f, width)

                // Thick active track.
                if (activeEndX > 0f) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                primary.copy(alpha = if (enabled) 1f else 0.42f),
                                tertiary.copy(alpha = if (enabled) 0.95f else 0.36f),
                            ),
                            startX = 0f,
                            endX = activeEndX,
                        ),
                        topLeft = Offset(
                            0f,
                            centerY - trackHeightPx / 2f,
                        ),
                        size = Size(activeEndX, trackHeightPx),
                        cornerRadius = cornerRadius,
                    )
                }

                // Thick inactive track.
                if (inactiveStartX < width) {
                    drawRoundRect(
                        color = inactive,
                        topLeft = Offset(
                            inactiveStartX,
                            centerY - trackHeightPx / 2f,
                        ),
                        size = Size(
                            width - inactiveStartX,
                            trackHeightPx,
                        ),
                        cornerRadius = cornerRadius,
                    )
                }

                // Endpoint marker.
                val endpointX = width - trackHeightPx / 2f
                if (inactiveStartX < endpointX) {
                    drawCircle(
                        color = thumbColor.copy(alpha = 0.90f),
                        radius = 2.dp.toPx(),
                        center = Offset(endpointX, centerY),
                    )
                }

                // Vertical thumb with a subtle shadow.
                val thumbX = thumbCenterX - thumbWidthPx / 2f
                val thumbCornerRadius = CornerRadius(
                    thumbWidthPx / 2f,
                    thumbWidthPx / 2f,
                )
                val shadowExtra = 2.dp.toPx()

                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.35f),
                    topLeft = Offset(
                        thumbX - shadowExtra / 2f,
                        centerY - thumbHeightPx / 2f - shadowExtra / 2f,
                    ),
                    size = Size(
                        thumbWidthPx + shadowExtra,
                        thumbHeightPx + shadowExtra,
                    ),
                    cornerRadius = CornerRadius(
                        (thumbWidthPx + shadowExtra) / 2f,
                        (thumbWidthPx + shadowExtra) / 2f,
                    ),
                )

                drawRoundRect(
                    color = thumbColor,
                    topLeft = Offset(
                        thumbX,
                        centerY - thumbHeightPx / 2f,
                    ),
                    size = Size(thumbWidthPx, thumbHeightPx),
                    cornerRadius = thumbCornerRadius,
                )
            }

            // Transparent Material Slider preserves seeking and accessibility.
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange,
                enabled = enabled,
                interactionSource = interactionSource,
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
    }
}

@Composable
internal fun SeekBar(
    progressState: StateFlow<PlaybackProgressState>,
    isPlaying: Boolean,
    trackKey: String?,
    wavyEnabled: Boolean = true,
    onSeek: (Long) -> Unit,
    isTranslucent: Boolean = false,
    fallbackDurationMs: Long = 0L,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.secondary,
    tertiaryColor: Color = MaterialTheme.colorScheme.tertiary,
    telemetry: com.lastwave.app.playback.automix.api.DeckBlendTelemetry = com.lastwave.app.playback.automix.api.DeckBlendTelemetry(),
) {
    val progress by progressState.collectAsStateWithLifecycle()
    val effectiveDurationMs = if (progress.durationMs > 0L) progress.durationMs else fallbackDurationMs.coerceAtLeast(0L)

    if (wavyEnabled) {
        WavySeekBar(
            positionMs = progress.positionMs,
            durationMs = effectiveDurationMs,
            isPlaying = isPlaying,
            onSeek = onSeek,
            isTranslucent = isTranslucent,
            trackKey = trackKey,
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            tertiaryColor = tertiaryColor,
        )
        return
    }

    // Current-gesture fraction only; null = finger off, show live position.
    // Nullable (never a stale 0f) so a press without movement seeks nowhere
    // and a gesture that ends without onValueChangeFinished can't pin the bar.
    val seekInteraction = remember(trackKey) { MutableInteractionSource() }
    var isInteracting by remember(trackKey) { mutableStateOf(false) }
    LaunchedEffect(seekInteraction, trackKey) {
        var dragCount = 0
        var pressCount = 0
        seekInteraction.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> dragCount++
                is DragInteraction.Stop, is DragInteraction.Cancel -> dragCount = maxOf(0, dragCount - 1)
                is PressInteraction.Press -> pressCount++
                is PressInteraction.Release, is PressInteraction.Cancel -> pressCount = maxOf(0, pressCount - 1)
            }
            isInteracting = dragCount > 0 || pressCount > 0
        }
    }
    var dragFraction by remember(trackKey) { mutableStateOf<Float?>(null) }
    var lastSeekFraction by remember(trackKey) { mutableStateOf<Float?>(null) }
    // Heal: framework reports finger lifted or cancelled but the finished callback
    // never ran -> drop the dead value and resume live position.
    LaunchedEffect(isInteracting, trackKey) {
        if (!isInteracting) {
            delay(120L)
            dragFraction = null
            lastSeekFraction = null
        }
    }
    // Watchdog: if dragFraction was set by a tap/cancelled gesture while not interacting,
    // ensure it never permanently wedges the seekbar.
    LaunchedEffect(dragFraction, isInteracting, trackKey) {
        if (dragFraction != null && !isInteracting) {
            delay(250L)
            dragFraction = null
            lastSeekFraction = null
        }
    }

    val boundedDurationMs = effectiveDurationMs.coerceAtLeast(0L)
    val currentFraction = if (boundedDurationMs > 0L) {
        (progress.positionMs.toDouble() / boundedDurationMs.toDouble()).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val fraction = (dragFraction ?: currentFraction).coerceIn(0f, 1f)
    val shownMs = dragFraction?.let {
        (it * boundedDurationMs).toLong().coerceIn(0L, boundedDurationMs)
    } ?: progress.positionMs.coerceIn(0L, boundedDurationMs)

    val isGlassActive = LocalLiquidGlass.current &&
        isLiquidGlassBackdropSupported() &&
        LocalLiquidGlassBackdrop.current != null
    val effectivePrimary = when {
        isGlassActive -> Color.White.copy(alpha = 0.95f)
        isTranslucent -> Color.White
        else -> primaryColor.ensureLuminousForDarkBackground(0.38f)
    }
    val inactiveColor = when {
        isGlassActive -> Color.White.copy(alpha = 0.20f)
        isTranslucent -> Color.White.copy(alpha = 0.24f)
        else -> Color.White.copy(alpha = 0.24f)
    }
    val textColor = when {
        isGlassActive -> Color.White.copy(alpha = 0.90f)
        isTranslucent -> Color.White.copy(alpha = 0.85f)
        else -> Color.White.copy(alpha = 0.82f)
    }

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
                        color = effectivePrimary,
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
                        color = effectivePrimary.copy(alpha = 0.90f),
                        radius = 2.dp.toPx(),
                        center = Offset(endpointX, centerY),
                    )
                }

                // Tall vertical pill thumb with clear space on both sides.
                val thumbX = thumbCenterX - thumbWidthPx / 2f
                val thumbCornerRadius = CornerRadius(thumbWidthPx / 2f, thumbWidthPx / 2f)

                // Subtle dark drop-shadow behind thumb to guarantee 100% boundary separation on any artwork
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.35f),
                    topLeft = Offset(thumbX - 1.dp.toPx(), centerY - thumbHeightPx / 2f - 1.dp.toPx()),
                    size = Size(thumbWidthPx + 2.dp.toPx(), thumbHeightPx + 2.dp.toPx()),
                    cornerRadius = CornerRadius((thumbWidthPx + 2.dp.toPx()) / 2f, (thumbWidthPx + 2.dp.toPx()) / 2f),
                )

                drawRoundRect(
                    color = effectivePrimary,
                    topLeft = Offset(thumbX, centerY - thumbHeightPx / 2f),
                    size = Size(thumbWidthPx, thumbHeightPx),
                    cornerRadius = thumbCornerRadius,
                )
            }

            // Invisible Material interaction layer: custom visuals, reliable seeking semantics.
            Slider(
                value = fraction,
                onValueChange = {
                    dragFraction = it
                    lastSeekFraction = it
                },
                onValueChangeFinished = {
                    // Commit only this gesture's value; no value = no seek.
                    val target = (lastSeekFraction ?: dragFraction)?.let {
                        (it * boundedDurationMs).toLong().coerceIn(0L, boundedDurationMs)
                    }
                    dragFraction = null
                    lastSeekFraction = null
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
        val currentSecond = shownMs / 1000L
        val remainingSecond = (boundedDurationMs - shownMs).coerceAtLeast(0) / 1000L
        val formattedElapsed = remember(currentSecond) { formatTime(currentSecond * 1000L) }
        val formattedRemaining = remember(remainingSecond) { "\u2212${formatTime(remainingSecond * 1000L)}" }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formattedElapsed,
                style = if (isTranslucent) MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium) else MaterialTheme.typography.labelMedium,
                color = textColor,
            )
            Text(
                text = formattedRemaining,
                style = if (isTranslucent) MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium) else MaterialTheme.typography.labelMedium,
                color = textColor,
            )
        }
    }
    }
}

internal fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(total / 60, total % 60)
}
