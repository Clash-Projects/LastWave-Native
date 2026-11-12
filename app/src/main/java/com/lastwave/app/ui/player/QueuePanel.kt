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
import com.lastwave.app.data.artwork.ArtworkNormalizer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.toGeneratedTrack
import com.lastwave.app.playback.resolve.MetadataLog
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ArtworkViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.scale
import kotlinx.coroutines.isActive
import java.nio.IntBuffer
import kotlin.time.Duration.Companion.seconds
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs


internal var isQueueLockedState by mutableStateOf(false)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun QueuePanel(
    state: MusicPlayerState,
    player: MusicPlayer,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val isQueueLocked = isQueueLockedState
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var dragStartOffsetY by remember { mutableFloatStateOf(0f) }
    var menuIndex by remember { mutableStateOf<Int?>(null) }
    var edgeScrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun cancelEdgeScroll() {
        edgeScrollJob?.cancel()
        edgeScrollJob = null
    }
    DisposableEffect(Unit) {
        onDispose { cancelEdgeScroll() }
    }

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
    val currentQueue by rememberUpdatedState(state.queue)
    val currentQueueKeys by rememberUpdatedState(queueKeys)
    fun moveDraggedItem() {
        val key = draggingKey ?: return
        val source = currentQueueKeys.indexOf(key)
        val visible = listState.layoutInfo.visibleItemsInfo
        val draggedInfo = visible.firstOrNull { it.key == key } ?: return
        if (source !in currentQueue.indices || draggedInfo.index != source) return
        val center = dragStartOffsetY + dragOffsetY + draggedInfo.size / 2f
        val target = visible.firstOrNull { info ->
            info.index != source && info.index in currentQueue.indices &&
                center >= info.offset && center <= info.offset + info.size &&
                (if (info.index > source) center > info.offset + info.size / 2f
                else center < info.offset + info.size / 2f)
        } ?: return
        player.moveQueueItem(source, target.index)
        runCatching { haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }
    }
    // Abort a stale drag if the queue itself changes underneath us
    // (track ended, clear-upcoming, fresh playQueue, …).
    LaunchedEffect(queueKeys) {
        if (draggingKey != null && draggingKey !in queueKeys) {
            cancelEdgeScroll()
            draggingKey = null
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
        if (draggingKey != null || state.queue.isEmpty()) return@LaunchedEffect
        if (System.currentTimeMillis() < userScrollHoldUntil) return@LaunchedEffect
        val target = state.currentIndex.coerceIn(0, state.queue.size - 1)
        runCatching { listState.animateScrollToItem(target) }
    }
    // True upcoming play order (shuffle/repeat aware). Under shuffle the list
    // below stays in playlist order, so without this strip you can never tell
    // what plays next â€” and Play-next adds look "lost".
    val shuffleUpcoming = remember(state.queue, state.currentIndex, state.shuffleEnabled, state.repeatMode) {
        if (state.shuffleEnabled) runCatching { player.peekUpcomingIndices(3) }.getOrDefault(emptyList())
        else emptyList()
    }.filter { it in state.queue.indices && it != state.currentIndex }

    var recentlyRemoved by remember { mutableStateOf<Pair<PlayableTrack, Int>?>(null) }
    var undoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    Box(modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
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
                if (state.queue.isNotEmpty()) {
                    Text(
                        "${state.currentIndex.coerceAtLeast(0) + 1} playing",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.70f),
                    )
                }
                if (state.isEndlessQueue) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AllInclusive,
                            contentDescription = "Unlimited queue",
                            tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            "Unlimited",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.55f),
                        )
                    }
                }
            }
            IconButton(
                onClick = {
                    isQueueLockedState = !isQueueLockedState
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (isQueueLocked) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                        else Color.White.copy(alpha = 0.14f)
                    ),
            ) {
                Icon(
                    imageVector = if (isQueueLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = if (isQueueLocked) "Unlock queue" else "Lock queue",
                    tint = if (isQueueLocked) MaterialTheme.colorScheme.primary
                    else Color.White.copy(alpha = 0.90f),
                )
            }
            Spacer(Modifier.width(8.dp))
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
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    player.clearQueueKeepCurrent()
                },
                enabled = state.queue.size > 1 && !isQueueLocked,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.14f)),
            ) {
                Icon(
                    imageVector = Icons.Filled.ClearAll,
                    contentDescription = "Clear queue except playing song",
                    tint = if (state.queue.size > 1 && !isQueueLocked) Color.White.copy(alpha = 0.90f) else Color.White.copy(alpha = 0.35f),
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
                        Spacer(Modifier.width(6.dp))
                        PlayerArtwork(item, Modifier.size(36.dp), 8.dp)
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
            modifier = Modifier
                .fillMaxSize()
                .clip(TopCurvedSectionClipShape(horizontalInset = 0.dp, cornerRadius = 28.dp)),
            contentPadding = PaddingValues(bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(GroupGap),
        ) {
            itemsIndexed(
                state.queue,
                key = { index, _ -> queueKeys.getOrNull(index) ?: index.toString() },
                contentType = { _, _ -> "queue_item" },
            ) { index, item ->
                val currentIndex by rememberUpdatedState(index)
                val isCurrent = index == state.currentIndex
                val stableKey = queueKeys.getOrNull(index) ?: index.toString()
                val isDragging = stableKey == draggingKey
                val rowOffsetY by animateFloatAsState(
                    targetValue = if (isDragging) {
                        // Reordering changes the layout origin; the row must stay under the finger.
                        dragStartOffsetY + dragOffsetY - (listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == stableKey }?.offset ?: dragStartOffsetY.toInt())
                    } else 0f,
                    animationSpec = if (isDragging) snap() else ExpressiveMotion.spatialDefault(),
                    label = "queueDragOffset",
                )
                val position = groupPositionFor(index, state.queue.size)
                val itemShape = groupShape(position)

                val dismissState = androidx.compose.material3.rememberSwipeToDismissBoxState(
                    confirmValueChange = { dismissValue ->
                        if (dismissValue == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) {
                            val resolvedIndex = currentQueueKeys.indexOf(stableKey).takeIf { it >= 0 } ?: currentIndex
                            if (resolvedIndex in currentQueue.indices) {
                                val removedTrack = currentQueue[resolvedIndex]
                                player.removeQueueItem(resolvedIndex)
                                recentlyRemoved = removedTrack to resolvedIndex
                                undoJob?.cancel()
                                undoJob = scope.launch {
                                    delay(3000L)
                                    recentlyRemoved = null
                                }
                                haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                                true
                            } else {
                                false
                            }
                        } else {
                            false
                        }
                    }
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragging) 10f else 0f)
                        .animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = null,
                            placementSpec = if (isDragging) null else ExpressiveMotion.spatialDefault(),
                        )
                        .graphicsLayer {
                            translationY = rowOffsetY
                            shadowElevation = if (isDragging) 18f else 0f
                            val s = if (isDragging) 1.025f else 1f
                            scaleX = s
                            scaleY = s
                            alpha = if (isDragging) 0.96f else 1f
                        },
                ) {
                    androidx.compose.material3.SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = !isQueueLocked && draggingKey == null,
                        modifier = Modifier.fillMaxWidth(),
                        backgroundContent = {
                            if (dismissState.dismissDirection == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .clip(itemShape)
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(horizontal = 24.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Icon(
                                        Icons.Filled.DeleteOutline,
                                        contentDescription = stringResource(com.lastwave.app.R.string.player_delete_cd),
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        },
                        content = {
                            LiquidGlassSurface(
                                glassModifier = Modifier.liquidGlassChrome(itemShape, LocalLiquidGlass.current),
                                onClick = { player.seekToQueueItem(currentQueueKeys.indexOf(stableKey).takeIf { it >= 0 } ?: currentIndex) },
                                shape = itemShape,
                                color = if (isCurrent) {
                                    if (LocalLiquidGlass.current) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                                    else MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    if (LocalLiquidGlass.current) MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.70f)
                                    else MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                                contentColor = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth(),
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
                                    IconButton(
                                        onClick = { menuIndex = currentQueueKeys.indexOf(stableKey).takeIf { it >= 0 } ?: currentIndex },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.MoreVert,
                                            contentDescription = stringResource(com.lastwave.app.R.string.player_menu_cd),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (!isQueueLocked) {
                                        Icon(
                                            Icons.Filled.DragHandle,
                                            stringResource(com.lastwave.app.R.string.queue_drag_hint),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDragging) 1f else 0.6f),
                                            modifier = Modifier
                                                .padding(start = 8.dp)
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(
                                                    if (isDragging) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                                    else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                                                )
                                                .padding(8.dp)
                                                .pointerInput(stableKey) {
                                                    detectDragGestures(
                                                        onDragStart = {
                                                            val info = listState.layoutInfo.visibleItemsInfo
                                                                .firstOrNull { it.key == stableKey } ?: return@detectDragGestures
                                                            draggingKey = stableKey
                                                            dragStartOffsetY = info.offset.toFloat()
                                                            dragOffsetY = 0f
                                                            cancelEdgeScroll()
                                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        },
                                                        onDragEnd = {
                                                            cancelEdgeScroll()
                                                            draggingKey = null
                                                            dragOffsetY = 0f
                                                        },
                                                        onDragCancel = {
                                                            cancelEdgeScroll()
                                                            draggingKey = null
                                                            dragOffsetY = 0f
                                                        },
                                                        onDrag = { change, dragAmount ->
                                                            change.consume()
                                                            if (draggingKey != stableKey) return@detectDragGestures
                                                            dragOffsetY += dragAmount.y
                                                            moveDraggedItem()
                                                            val layoutInfo = listState.layoutInfo
                                                            val draggedInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.key == stableKey }
                                                                ?: return@detectDragGestures
                                                            val draggedCenter = dragStartOffsetY + dragOffsetY + draggedInfo.size / 2f

                                                            val viewportStart = runCatching { layoutInfo.viewportStartOffset }.getOrDefault(0)
                                                            val viewportEnd = runCatching { layoutInfo.viewportEndOffset }.getOrDefault(0)
                                                            if (viewportEnd <= viewportStart) {
                                                                cancelEdgeScroll()
                                                                return@detectDragGestures
                                                            }
                                                            val edgeZone = 180
                                                            val direction = when {
                                                                draggedCenter < viewportStart + edgeZone -> -1f
                                                                draggedCenter > viewportEnd - edgeZone -> 1f
                                                                else -> 0f
                                                            }
                                                            if (direction == 0f) {
                                                                cancelEdgeScroll()
                                                            } else if (edgeScrollJob?.isActive != true) {
                                                                edgeScrollJob = scope.launch {
                                                                    while (isActive) {
                                                                        androidx.compose.runtime.withFrameNanos { }
                                                                        val scrolled = listState.scrollBy(direction * 10f)
                                                                        moveDraggedItem()
                                                                        if (scrolled == 0f) break
                                                                    }
                                                                }
                                                            }
                                                        },
                                                    )
                                                },
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    AnimatedVisibility(
        visible = recentlyRemoved != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 16.dp + bottomPadding)
            .padding(horizontal = 20.dp),
    ) {
        val removed = recentlyRemoved
        if (removed != null) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.95f),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "\"${removed.first.title}\" removed",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    TextButton(
                        onClick = {
                            val (track, index) = removed
                            player.insertQueueItem(index, track)
                            recentlyRemoved = null
                            undoJob?.cancel()
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.inversePrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "Undo",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

    menuIndex?.let { index ->
        val itemTrack = state.queue.getOrNull(index)
        if (itemTrack != null) {
            com.lastwave.app.ui.common.TrackContextMenuSheet(
                target = com.lastwave.app.ui.common.TrackMenuTarget.Track(itemTrack.title, itemTrack.artist, itemTrack.album ?: ""),
                capabilities = com.lastwave.app.ui.common.TrackMenuCapabilities(showCopyActions = true),
                playableTrack = itemTrack,
                onDismiss = { menuIndex = null },
                onRemoveFromQueue = if (isQueueLocked) null else ({ player.removeQueueItem(index) })
            )
        } else {
            menuIndex = null
        }
    }
}
