package com.lastwave.app.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.PlaybackProgressState
import com.lastwave.app.ui.common.ArtworkImage
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * Experimental "Button Now Playing": a draggable floating button that replaces
 * the bottom mini player card when enabled. Shows the song's album art in a circle
 * with an interactive progress ring around it. Clicking opens the full player screen.
 */
@Composable
fun DraggableNowPlayingButton(
    track: PlayableTrack,
    progressState: StateFlow<PlaybackProgressState>?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit,
) {
    val liveProgress by progressState?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(PlaybackProgressState(0L, 0L)) }

    val progressFraction = remember(liveProgress.positionMs, liveProgress.durationMs) {
        val dur = liveProgress.durationMs.takeIf { it > 0 } ?: track.durationMs ?: 0L
        if (dur > 0) (liveProgress.positionMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
        else 0f
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(250)) + scaleIn(tween(250)),
        exit = fadeOut(tween(250)) + scaleOut(tween(250)),
        modifier = modifier.fillMaxSize(),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val haptic = LocalHapticFeedback.current

            val buttonSizeDp = 64.dp
            val buttonSizePx = with(density) { buttonSizeDp.toPx() }
            val screenWidthPx = with(density) { maxWidth.toPx() }
            val screenHeightPx = with(density) { maxHeight.toPx() }

            val minMarginX = with(density) { 14.dp.toPx() }
            val maxMarginX = (screenWidthPx - buttonSizePx - minMarginX).coerceAtLeast(minMarginX)
            val minMarginY = with(density) { 70.dp.toPx() }
            val maxMarginY = (screenHeightPx - buttonSizePx - with(density) { 96.dp.toPx() }).coerceAtLeast(minMarginY)

            // Initial placement: bottom right above bottom nav
            var offsetX by remember { mutableFloatStateOf(maxMarginX) }
            var offsetY by remember { mutableFloatStateOf(maxMarginY - with(density) { 20.dp.toPx() }) }
            var isDragging by remember { mutableStateOf(false) }
            var dragDistance by remember { mutableFloatStateOf(0f) }

            val infiniteTransition = rememberInfiniteTransition(label = "now_playing_btn_pulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1.0f,
                targetValue = if (isPlaying) 1.05f else 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "btn_pulse",
            )

            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .size(buttonSizeDp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onExpand,
                    )
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                isDragging = true
                                dragDistance = 0f
                            },
                            onDragEnd = {
                                isDragging = false
                                if (dragDistance < 15f) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onExpand()
                                }
                            },
                            onDragCancel = {
                                isDragging = false
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragDistance += kotlin.math.hypot(dragAmount.x, dragAmount.y)
                                offsetX = (offsetX + dragAmount.x).coerceIn(minMarginX, maxMarginX)
                                offsetY = (offsetY + dragAmount.y).coerceIn(minMarginY, maxMarginY)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Floating bubble container
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f),
                    shadowElevation = if (isDragging) 16.dp else 10.dp,
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .size(buttonSizeDp)
                        .graphicsLayer {
                            scaleX = if (isDragging) 1.1f else pulseScale
                            scaleY = if (isDragging) 1.1f else pulseScale
                        }
                        .clip(CircleShape)
                        .border(
                            width = 2.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary,
                                ),
                            ),
                            shape = CircleShape,
                        ),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Circular track progress slider around cover art
                        CircularProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(2.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            strokeWidth = 3.5.dp,
                        )

                        // Album artwork in center
                        Box(
                            modifier = Modifier
                                .size(buttonSizeDp - 14.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center,
                        ) {
                            ArtworkImage(
                                name = track.title,
                                artist = track.artist,
                                embeddedUrl = track.artworkUrl,
                                fallbackIcon = Icons.Filled.MusicNote,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}
