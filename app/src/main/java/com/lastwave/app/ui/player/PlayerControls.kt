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
internal fun MainControls(state: MusicPlayerState, player: MusicPlayer, isTranslucent: Boolean = false) {
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

    // Adaptive transport row: fixed 58+188+58dp used to overflow narrow
    // screens (320dp class + parent padding), clipping the side buttons so
    // Next looked crushed against the edge. The pill now flexes between a
    // legible floor and its design width; prev/next never clip on any
    // screen size or Android version.
    val sideSize = if (isTranslucent) 54.dp else 58.dp
    val gapSize = if (isTranslucent) 18.dp else 16.dp
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val pillWidth = (maxWidth - sideSize * 2 - gapSize * 2)
            .coerceIn(112.dp, if (isTranslucent) 180.dp else 188.dp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(gapSize, Alignment.CenterHorizontally),
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
                .size(sideSize)
                .graphicsLayer {
                    scaleX = prevScale
                    scaleY = prevScale
                },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(if (isTranslucent) 28.dp else 31.dp))
            }
        }
        val pillShape = RoundedCornerShape(100)
        // Single source of truth for "glass is actually refracting": the
        // container helper returns Transparent only when setting + device
        // capability + backdrop all hold. The old contentColor checked only
        // the *setting*, so setting-ON with an inactive effect (incapable
        // device, missing backdrop) rendered a white icon on the solid-white
        // fallback below â€” the "whole white" play button. Gating content on
        // the same active flag closes that mismatch for every path.
        val isPillGlassActive = LocalLiquidGlass.current &&
            isLiquidGlassBackdropSupported() &&
            LocalLiquidGlassBackdrop.current != null
        LiquidGlassSurface(
            glassModifier = Modifier.liquidGlassChrome(pillShape, LocalLiquidGlass.current, LiquidGlassPreset.FloatingControls, interactionSource = playInteraction),
            onClick = player::togglePlayPause,
            interactionSource = playInteraction,
            shape = pillShape,
            // Fallback matches the sibling prev/next buttons (14% white veil,
            // never solid white): on the full player's dark scrim the white
            // icon stays readable with or without refraction.
            color = liquidGlassContainerColor(if (isTranslucent) Color.White.copy(alpha = 0.14f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)),
            contentColor = if (isPillGlassActive) {
                if (isTranslucent) Color.White else MaterialTheme.colorScheme.primary
            } else if (isTranslucent) Color.White.copy(alpha = 0.94f) else MaterialTheme.colorScheme.onPrimary,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier
                .width(pillWidth)
                .height(if (isTranslucent) 56.dp else 60.dp)
                .graphicsLayer {
                    scaleX = playScale
                    scaleY = playScale
                },
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (state.isBuffering) {
                    ExpressiveInlineLoadingIndicator(
                        size = if (isTranslucent) 28.dp else 30.dp,
                        color = androidx.compose.material3.LocalContentColor.current,
                        strokeWidth = 3.dp,
                    )
                } else {
                    AnimatedPlayPauseIcon(state.isPlaying, Modifier.size(if (isTranslucent) 28.dp else 30.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (state.isPlaying) "Pause" else "Play",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = androidx.compose.material3.LocalContentColor.current
                    )
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
                .size(sideSize)
                .graphicsLayer {
                    scaleX = nextScale
                    scaleY = nextScale
                },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.SkipNext, "Next", Modifier.size(if (isTranslucent) 28.dp else 31.dp))
            }
        }
        }
    }
}

@Composable
internal fun PlayerModeButton(
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
internal fun PlayerUtilityControls(
    state: MusicPlayerState,
    player: MusicPlayer,
    isTranslucent: Boolean = false,
    onOpenAudioControls: () -> Unit = {},
    isVideoActive: Boolean = false,
    musicVideoEnabled: Boolean = true,
    showAutoEqInVolumePanel: Boolean = true,
    onToggleVideo: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
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

    Row(
        modifier = modifier.fillMaxWidth(),
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
        val isCustomAudio = state.speed != 1f || state.pitch != 1f
        if (musicVideoEnabled && onToggleVideo != null) {
            DividedUtilityPill(
                isTranslucent = isTranslucent,
                background = edgeButtonBackground,
                foreground = edgeButtonContent,
                isCustomAudio = isCustomAudio,
                isVideoActive = isVideoActive,
                onOpenAudioControls = onOpenAudioControls,
                onToggleVideo = onToggleVideo,
                modifier = Modifier.weight(1f).height(if (isTranslucent) 44.dp else 48.dp),
            )
        } else {
            PlayerModeButton(
                active = isCustomAudio,
                description = "Audio controls",
                icon = if (isCustomAudio) Icons.Filled.Tune else Icons.Filled.VolumeUp,
                onClick = onOpenAudioControls,
                background = edgeButtonBackground,
                foreground = edgeButtonContent,
                iconSize = if (isTranslucent) 19.dp else 20.dp,
                modifier = Modifier.weight(1f).height(if (isTranslucent) 44.dp else 48.dp),
            )
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
}

/**
 * Apple-style divided utility pill preserving the exact outer pill shape:
 * [ (Speaker / Audio) | (Video / EQ) ]
 * Left half opens audio controls sheet, right half toggles synchronized video.
 */
@Composable
internal fun DividedUtilityPill(
    isTranslucent: Boolean,
    background: Color,
    foreground: Color,
    isCustomAudio: Boolean,
    isVideoActive: Boolean,
    onOpenAudioControls: () -> Unit,
    onToggleVideo: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val container = liquidGlassContainerColor(background)
    val speakerInteraction = remember { MutableInteractionSource() }
    val videoInteraction = remember { MutableInteractionSource() }
    val speakerPressed by speakerInteraction.collectIsPressedAsState()
    val videoPressed by videoInteraction.collectIsPressedAsState()
    val speakerScale by animateFloatAsState(
        if (speakerPressed) 0.88f else 1f, ExpressiveMotion.spatialSpring(), label = "speakerScale",
    )
    val videoScale by animateFloatAsState(
        if (videoPressed) 0.88f else 1f, ExpressiveMotion.spatialSpring(), label = "videoScale",
    )

    LiquidGlassSurface(
        onClick = {},
        glassModifier = Modifier.liquidGlassChrome(
            CircleShape,
            LocalLiquidGlass.current,
            LiquidGlassPreset.PlayerControls,
        ),
        shape = CircleShape,
        color = container,
        contentColor = foreground,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: Speaker / Audio Controls
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = speakerInteraction,
                        indication = null,
                        onClick = onOpenAudioControls,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.VolumeUp,
                    contentDescription = "Audio Controls",
                    tint = if (isCustomAudio) MaterialTheme.colorScheme.primary else foreground,
                    modifier = Modifier
                        .size(if (isTranslucent) 18.dp else 19.dp)
                        .graphicsLayer {
                            scaleX = speakerScale
                            scaleY = speakerScale
                        },
                )
                if (isCustomAudio) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 4.dp)
                            .size(3.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }

            // Divider: Subtle vertical line separating the two controls
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(18.dp)
                    .background(foreground.copy(alpha = 0.22f)),
            )

            // Right: Video Toggle
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = videoInteraction,
                        indication = null,
                        onClick = { onToggleVideo?.invoke() },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.SmartDisplay,
                    contentDescription = if (isVideoActive) "Switch to album art" else "Watch music video",
                    tint = if (isVideoActive) MaterialTheme.colorScheme.primary else foreground,
                    modifier = Modifier
                        .size(if (isTranslucent) 18.dp else 19.dp)
                        .graphicsLayer {
                            scaleX = videoScale
                            scaleY = videoScale
                        },
                )
                if (isVideoActive) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 4.dp)
                            .size(3.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
internal fun DeckWaveform(
    deckLabel: String,
    channelColor: Color,
    secondaryColor: Color,
    trackTitle: String,
    trackArtist: String,
    isPlaying: Boolean,
    isBlending: Boolean,
    progress: Float,
    isDeckA: Boolean,
    phase: Float,
    cueMs: Long? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Deck Channel Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = channelColor.copy(alpha = 0.22f),
                    ) {
                        Text(
                            text = deckLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp,
                            ),
                            color = channelColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        text = if (trackTitle.isNotBlank()) "$trackTitle \u2022 $trackArtist" else "Standby Deck Empty",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (isDeckA && isBlending && progress >= 0.65f) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFFDDD6FE).copy(alpha = 0.25f),
                    ) {
                        Text(
                            text = "ECHO FREEZE TAIL",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFFDDD6FE),
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                } else if (!isDeckA && (cueMs ?: 0L) in 100L..15000L) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFFFDE68A).copy(alpha = 0.25f),
                    ) {
                        Text(
                            text = "KICK CUE +${cueMs}ms",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFFFDE68A),
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }

            // Real-time Canvas Waveform: 24 animated frequency spectrum bars + continuous curve
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.25f)),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val barCount = 24
                    val spacing = 3.dp.toPx()
                    val barWidth = ((w - spacing * (barCount + 1)) / barCount).coerceAtLeast(2f)

                    val wavePath = androidx.compose.ui.graphics.Path()
                    val echoPath = if (isDeckA && isBlending && progress >= 0.65f) androidx.compose.ui.graphics.Path() else null

                    for (i in 0 until barCount) {
                        val norm = i.toFloat() / (barCount - 1).toFloat()
                        // Wave physics: bass harmonic + mid harmonic + treble shimmer
                        val bassHarmonic = sin((phase * 2f + norm * 3.14159f).toDouble()).toFloat()
                        val trebleHarmonic = cos((phase * 3.5f + norm * 6.28318f).toDouble()).toFloat() * 0.4f
                        var barAmp = (0.45f + 0.35f * bassHarmonic + 0.20f * trebleHarmonic).coerceIn(0.08f, 1.0f)

                        if (!isPlaying) {
                            barAmp *= 0.15f
                        } else if (isDeckA) {
                            if (isBlending) {
                                // HPF sweeps 30Hz -> 900Hz: lower bars get attenuated
                                val hpfCutoffNorm = (progress * 0.45f).coerceIn(0f, 0.45f)
                                if (norm < hpfCutoffNorm) {
                                    barAmp *= (norm / hpfCutoffNorm.coerceAtLeast(0.01f)).coerceIn(0.1f, 1f)
                                }
                                // Midpoint downbeat bass-swap: attenuate bass (norm < 0.25) by -18dB (0.125f)
                                if (norm < 0.25f && progress >= 0.50f) {
                                    barAmp *= 0.125f
                                }
                                // Overall volume attenuation as transition progresses
                                barAmp *= (1f - progress * 0.70f).coerceAtLeast(0.05f)
                            }
                        } else {
                            // Deck B
                            if (!isBlending) {
                                // Standby prebuffer: subtle pulse
                                barAmp *= 0.30f
                            } else {
                                // LPF opens 1200Hz -> 20000Hz over first 40%
                                val lpfOpenNorm = if (progress <= 0.40f) (0.25f + (progress / 0.40f) * 0.75f) else 1.0f
                                if (norm > lpfOpenNorm) {
                                    barAmp *= (1.0f - (norm - lpfOpenNorm) * 2f).coerceIn(0.05f, 1f)
                                }
                                // Midpoint downbeat bass-swap: duck bass by -18dB until 0.50, then snap to 0dB punch!
                                if (norm < 0.25f) {
                                    if (progress < 0.50f) {
                                        barAmp *= 0.125f
                                    } else {
                                        barAmp *= 1.25f
                                    }
                                }
                                barAmp *= (0.30f + progress * 0.70f).coerceIn(0.1f, 1.0f)
                            }
                        }

                        val barHeight = (barAmp * (h - 8.dp.toPx())).coerceAtLeast(3.dp.toPx())
                        val x = spacing + i * (barWidth + spacing)
                        val y = h - barHeight - 4.dp.toPx()

                        // Draw bar with gradient
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(secondaryColor, channelColor),
                                startY = y,
                                endY = h,
                            ),
                            topLeft = Offset(x, y),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                        )

                        // Build continuous wave curve
                        val peakX = x + barWidth / 2f
                        if (i == 0) {
                            wavePath.moveTo(peakX, y)
                        } else {
                            wavePath.lineTo(peakX, y)
                        }

                        // Build echo freeze envelope
                        if (echoPath != null) {
                            val echoY = y - 4.dp.toPx() * (1f - norm)
                            if (i == 0) echoPath.moveTo(peakX, echoY) else echoPath.lineTo(peakX, echoY)
                        }
                    }

                    // Stroke wave curve
                    drawPath(
                        path = wavePath,
                        color = secondaryColor.copy(alpha = 0.85f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                        ),
                    )

                    // Schroeder Echo Freeze shimmer tail
                    if (echoPath != null) {
                        val echoAlpha = (0.55f * ((progress - 0.65f) / 0.35f)).coerceIn(0f, 0.60f)
                        drawPath(
                            path = echoPath,
                            color = Color(0xFFDDD6FE).copy(alpha = echoAlpha),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 3.5.dp.toPx(),
                                cap = StrokeCap.Round,
                            ),
                        )
                    }

                    // Draw kick cue marker on Deck B
                    if (!isDeckA && (cueMs ?: 0L) in 100L..15000L) {
                        val cueFraction = ((cueMs!!.toFloat()) / 15000f).coerceIn(0.05f, 0.95f)
                        val markerX = cueFraction * w
                        drawLine(
                            color = Color(0xFFFDE68A),
                            start = Offset(markerX, 0f),
                            end = Offset(markerX, h),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }

            // DSP Telemetry Badges Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isDeckA) {
                    val hpfHz = if (isBlending) {
                        (30.0 * Math.pow(30.0, progress.toDouble())).roundToInt()
                    } else 30
                    Text(
                        text = "HPF: ${hpfHz}Hz (Q=1.35)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (isBlending && progress >= 0.50f) "Bass: -18dB Cut" else "Bass: 0dB Unity",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isBlending && progress >= 0.50f) Color(0xFFF87171) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isBlending && progress >= 0.50f) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(
                        text = if (isBlending && progress >= 0.65f) "Tail: 55% Freeze" else "Tail: Dry",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isBlending && progress >= 0.65f) Color(0xFFC084FC) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isBlending && progress >= 0.65f) FontWeight.Bold else FontWeight.Normal,
                    )
                } else {
                    val lpfHz = if (isBlending) {
                        if (progress <= 0.40f) {
                            (1200.0 * Math.pow(20000.0 / 1200.0, (progress / 0.40).toDouble())).roundToInt()
                        } else 20000
                    } else 20000
                    Text(
                        text = if (isBlending && progress <= 0.40f) "LPF: ${lpfHz}Hz" else "LPF: 20kHz Open",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (isBlending) (if (progress < 0.50f) "Bass: -18dB Duck" else "Bass: 0dB Punch!") else "Bass: Ducked",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isBlending && progress >= 0.50f) Color(0xFF34D399) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isBlending && progress >= 0.50f) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(
                        text = if ((cueMs ?: 0L) > 0L) "Cue: +${cueMs}ms" else "Cue: Beat 1",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFDE68A),
                    )
                }
            }
        }
    }
}

/**
 * SimpMusic's YouTube Music like button: a check when the song is liked on the
 * connected account, a plus when it is not, and nothing at all when there is
 * no account (or no YouTube id) — [liked] is null then. It toggles ONLY the
 * YouTube Music like; the heart beside it is the local Liked Songs.
 */
@Composable
internal fun YouTubeLikeButton(liked: Boolean?, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp) {
    if (liked == null) return
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    LiquidGlassSurface(
        glassModifier = Modifier.liquidGlassChrome(CircleShape, LocalLiquidGlass.current, LiquidGlassPreset.PlayerControls, interactionSource = interaction),
        onClick = onClick,
        interactionSource = interaction,
        shape = CircleShape,
        color = liquidGlassContainerColor(Color.White.copy(alpha = 0.14f)),
        contentColor = Color.White.copy(alpha = if (liked) 1f else 0.85f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.size(size),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                if (liked) androidx.compose.material.icons.Icons.Filled.CheckCircle
                else androidx.compose.material.icons.Icons.Outlined.AddCircleOutline,
                contentDescription = if (liked) "Remove from YouTube Liked" else "Add to YouTube Liked",
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}
