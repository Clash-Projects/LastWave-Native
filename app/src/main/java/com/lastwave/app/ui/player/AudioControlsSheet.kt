@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.lastwave.app.ui.player

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import com.lastwave.app.ui.theme.M3ExpressiveShape

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
import com.lastwave.app.playback.haptics.MusicHapticsEngineMode
import com.lastwave.app.playback.haptics.MusicHapticsIntensity
import com.lastwave.app.playback.haptics.MusicHapticsMode
import androidx.compose.material.icons.filled.Info
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioControlsSheet(
    player: MusicPlayer,
    state: MusicPlayerState,
    onDismiss: () -> Unit,
    onOpenAutoEq: () -> Unit = {},
    showAutoEq: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = LocalHapticFeedback.current
    var isLocked by remember { mutableStateOf(true) }
    var currentSpeed by remember(state.speed) { mutableFloatStateOf(state.speed) }
    var currentPitch by remember(state.pitch) { mutableFloatStateOf(state.pitch) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. Header Row: Clean, premium title without fake jargon or green dots
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column {
                        Text(
                            text = "Audio & Playback",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Volume, seamless transitions & speed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // 2. In-App Volume Card: Independent playback volume
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = if (state.appVolume <= 0.001f) Icons.Filled.VolumeMute else Icons.Filled.VolumeUp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "In-App Volume",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                text = if (state.appVolume <= 0.001f) "Muted" else "${(state.appVolume * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    VolumeSlider(
                        volume = state.appVolume,
                        onVolumeChange = player::setAppVolume,
                        isTranslucent = false,
                        primaryColor = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // 3. Slowed & Pitch Section Card
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Speed & Pitch",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (isLocked) "Tempo & pitch linked" else "Independent tempo and pitch stretch",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(
                                selected = isLocked,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    isLocked = !isLocked
                                    if (isLocked) {
                                        currentPitch = currentSpeed
                                        player.setPlaybackParameters(currentSpeed, currentSpeed)
                                    }
                                },
                                label = {
                                    Text(
                                        if (isLocked) "Linked" else "Free",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                    )
                                },
                                border = null,
                                shape = RoundedCornerShape(16.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .clickable {
                                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                        currentSpeed = 1.0f
                                        currentPitch = 1.0f
                                        player.setPlaybackParameters(1.0f, 1.0f)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.RestartAlt,
                                    contentDescription = "Reset speed and pitch",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }

                    // Speed Slider
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Playback Speed",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            ) {
                                Text(
                                    text = "${String.format(java.util.Locale.US, "%.2f", currentSpeed)}x",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Slider(
                            value = currentSpeed,
                            onValueChange = { value ->
                                val rounded = ((value * 100).roundToInt() / 100f).coerceIn(0.50f, 1.50f)
                                currentSpeed = rounded
                                if (isLocked) {
                                    currentPitch = rounded
                                    player.setPlaybackParameters(rounded, rounded)
                                } else {
                                    player.setPlaybackParameters(rounded, currentPitch)
                                }
                            },
                            valueRange = 0.50f..1.50f,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // Pitch Slider
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Pitch",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                            ) {
                                Text(
                                    text = "${String.format(java.util.Locale.US, "%.2f", currentPitch)}x",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Slider(
                            value = currentPitch,
                            onValueChange = { value ->
                                val rounded = ((value * 100).roundToInt() / 100f).coerceIn(0.50f, 1.50f)
                                currentPitch = rounded
                                if (isLocked) {
                                    currentSpeed = rounded
                                    player.setPlaybackParameters(rounded, rounded)
                                } else {
                                    player.setPlaybackParameters(currentSpeed, rounded)
                                }
                            },
                            valueRange = 0.50f..1.50f,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.secondary,
                                activeTrackColor = MaterialTheme.colorScheme.secondary,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // 5. Equalizer & Acoustic Tuning Card
            if (showAutoEq) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.GraphicEq,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Column {
                                Text(
                                    text = "Acoustic Tuning & EQ",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "AutoEQ correction, target curves & PEQ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Button(
                            onClick = {
                                onDismiss()
                                onOpenAutoEq()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Headphones,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Open AutoEQ / Parametric EQ",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            val hapticsConfig by player.hapticsController.config.collectAsStateWithLifecycle()
            val isUsbActive = player.isUsbExclusiveActive
            val isHardwareAvailable = player.hapticsController.isHardwareHapticGeneratorAvailable()
            if (hapticsConfig.enabled) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = QualityGlyphs.MusicHaptics,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "Music Haptics",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = if (isUsbActive && hapticsConfig.enabled) "Paused (USB DAC Exclusive active)"
                                    else if (hapticsConfig.enabled) "${hapticsConfig.mode.label} \u2022 ${hapticsConfig.intensity.label}"
                                    else "Physical rhythm synchronization via haptic motor",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isUsbActive && hapticsConfig.enabled) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(
                            checked = hapticsConfig.enabled,
                            onCheckedChange = { enabled ->
                                val next = hapticsConfig.copy(enabled = enabled)
                                player.updateMusicHapticsConfig(next)
                                if (enabled) {
                                    player.hapticsController.triggerPreviewPulse()
                                }
                            },
                            thumbContent = if (hapticsConfig.enabled) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                            } else null,
                            modifier = Modifier.semantics { contentDescription = "Music Haptics" },
                        )
                    }

                    if (hapticsConfig.enabled) {
                        if (isUsbActive) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text(
                                        text = "Haptics suspended while Bit-Perfect USB DAC bypass is active",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Focus mode",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(
                                modifier = Modifier.selectableGroup(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                MusicHapticsMode.entries.forEach { mode ->
                                    val isSelected = hapticsConfig.mode == mode
                                    val shape = MaterialTheme.shapes.large
                                    Surface(
                                        shape = shape,
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(shape)
                                            .selectable(
                                                selected = isSelected,
                                                role = Role.RadioButton,
                                                onClick = {
                                                    player.updateMusicHapticsConfig(hapticsConfig.copy(mode = mode))
                                                },
                                            ),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .heightIn(min = 48.dp)
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Icon(
                                                imageVector = when (mode) {
                                                    MusicHapticsMode.WHOLE_SONG -> Icons.Filled.Audiotrack
                                                    MusicHapticsMode.BASS_AND_BEATS -> Icons.Filled.GraphicEq
                                                    MusicHapticsMode.VOCALS_ONLY -> Icons.Outlined.Mic
                                                },
                                                contentDescription = null,
                                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(24.dp),
                                            )
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                Text(
                                                    text = when (mode) {
                                                        MusicHapticsMode.WHOLE_SONG -> "Whole song"
                                                        MusicHapticsMode.BASS_AND_BEATS -> "Bass & Beats"
                                                        MusicHapticsMode.VOCALS_ONLY -> "Vocals only"
                                                    },
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                                )
                                                if (isSelected) {
                                                    Text(
                                                        text = when (mode) {
                                                            MusicHapticsMode.WHOLE_SONG -> "Full-range taps and rhythmic textures"
                                                            MusicHapticsMode.BASS_AND_BEATS -> "Deep bass & drum hits · 40–130 Hz"
                                                            MusicHapticsMode.VOCALS_ONLY -> "Lead vocal phrasing · 300–3,200 Hz"
                                                        },
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                            RadioButton(selected = isSelected, onClick = null)
                                        }
                                    }
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Intensity",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                MusicHapticsIntensity.entries.forEachIndexed { index, intensity ->
                                    val isSelected = hapticsConfig.intensity == intensity
                                    val shape = when (index) {
                                        0 -> M3ExpressiveShape.GroupLeft
                                        MusicHapticsIntensity.entries.lastIndex -> M3ExpressiveShape.GroupRight
                                        else -> M3ExpressiveShape.GroupMiddle
                                    }
                                    Surface(
                                        shape = shape,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .heightIn(min = 48.dp)
                                            .clip(shape)
                                            .selectable(
                                                selected = isSelected,
                                                role = Role.RadioButton,
                                                onClick = {
                                                    player.updateMusicHapticsConfig(hapticsConfig.copy(intensity = intensity))
                                                    player.hapticsController.triggerPreviewPulse(intensity)
                                                },
                                            ),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                                        ) {
                                            if (isSelected) {
                                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                            }
                                            Text(
                                                text = intensity.label,
                                                style = MaterialTheme.typography.labelLarge,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                        }
                                    }
                                }
                            }
                            Text(
                                text = when (hapticsConfig.intensity) {
                                    MusicHapticsIntensity.LIGHT -> "Subtle pulses for relaxed listening."
                                    MusicHapticsIntensity.MEDIUM -> "Balanced pulses for everyday listening."
                                    MusicHapticsIntensity.STRONG -> "Full-strength pulses for bass drops."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        var engineMenuExpanded by remember { mutableStateOf(false) }
                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.large)
                                    .clickable { engineMenuExpanded = true }
                                    .heightIn(min = 48.dp)
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 12.dp).size(24.dp),
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Engine routing", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = when (hapticsConfig.engineMode) {
                                            MusicHapticsEngineMode.AUTO -> if (isHardwareAvailable) "Native for whole song; DSP for filters" else "Precision DSP active"
                                            MusicHapticsEngineMode.DSP_PRECISION -> "Precision DSP active"
                                            MusicHapticsEngineMode.HARDWARE_NATIVE -> if (isHardwareAvailable) "Android native haptics" else "Hardware unavailable · using DSP"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        hapticsConfig.engineMode.displayLabel(),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            DropdownMenu(expanded = engineMenuExpanded, onDismissRequest = { engineMenuExpanded = false }) {
                                MusicHapticsEngineMode.entries.forEach { engine ->
                                    DropdownMenuItem(
                                        text = { Text(engine.displayLabel()) },
                                        enabled = engine != MusicHapticsEngineMode.HARDWARE_NATIVE || isHardwareAvailable,
                                        trailingIcon = if (engine == hapticsConfig.engineMode) {
                                            { Icon(Icons.Filled.Check, contentDescription = null) }
                                        } else null,
                                        onClick = {
                                            player.updateMusicHapticsConfig(hapticsConfig.copy(engineMode = engine))
                                            engineMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.BatterySaver,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp).size(24.dp),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "Pause on battery saver",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "Suspend haptics to save power",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = hapticsConfig.pauseOnBatterySaver,
                                onCheckedChange = { pause ->
                                    player.updateMusicHapticsConfig(hapticsConfig.copy(pauseOnBatterySaver = pause))
                                },
                                thumbContent = if (hapticsConfig.pauseOnBatterySaver) {
                                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                                } else null,
                                modifier = Modifier.semantics { contentDescription = "Pause on battery saver" },
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

private fun MusicHapticsEngineMode.displayLabel(): String = when (this) {
    MusicHapticsEngineMode.AUTO -> "Auto"
    MusicHapticsEngineMode.DSP_PRECISION -> "DSP"
    MusicHapticsEngineMode.HARDWARE_NATIVE -> "Hardware"
}
