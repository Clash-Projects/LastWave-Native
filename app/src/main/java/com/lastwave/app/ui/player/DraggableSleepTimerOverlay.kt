package com.lastwave.app.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.playback.MusicPlayer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/** Keep progress ticks out of the app-wide player host and scrolling content. */
@Composable
internal fun PlayerSleepTimerOverlay(player: MusicPlayer) {
    val remainingSeconds by remember(player) {
        player.state.map { it.sleepTimerRemainingMs?.div(1_000) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = player.state.value.sleepTimerRemainingMs?.div(1_000))
    DraggableSleepTimerOverlay(
        sleepTimerRemainingMs = remainingSeconds?.times(1_000),
        onCycleTimer = player::cycleSleepTimer,
        onCloseTimer = { player.setSleepTimerMinutes(0) },
    )
}

@Composable
fun DraggableSleepTimerOverlay(
    sleepTimerRemainingMs: Long?,
    onCycleTimer: () -> Unit,
    onCloseTimer: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = sleepTimerRemainingMs != null,
        enter = fadeIn(tween(250)) + scaleIn(tween(250)),
        exit = fadeOut(tween(250)) + scaleOut(tween(250)),
        modifier = modifier.fillMaxSize(),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val haptic = LocalHapticFeedback.current

            val remainingMs = sleepTimerRemainingMs ?: 0L
            val hours = (remainingMs / 3600000).toInt()
            val minutes = ((remainingMs % 3600000) / 60000).toInt()
            val seconds = ((remainingMs % 60000) / 1000).toInt()
            val timeText = if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }

            // Circle dynamically scales with content length:
            // Expands to 74dp for hours (e.g. 1:15:30) and 64dp for minutes/seconds (e.g. 24:59)
            val diameterDp = if (hours > 0) 74.dp else 64.dp
            val diameterPx = with(density) { diameterDp.toPx() }
            val screenWidthPx = with(density) { maxWidth.toPx() }
            val screenHeightPx = with(density) { maxHeight.toPx() }

            val minMarginX = with(density) { 12.dp.toPx() }
            val maxMarginX = (screenWidthPx - diameterPx - minMarginX).coerceAtLeast(minMarginX)
            val minMarginY = with(density) { 60.dp.toPx() }
            val maxMarginY = (screenHeightPx - diameterPx - with(density) { 110.dp.toPx() }).coerceAtLeast(minMarginY)

            // Initial position: center right
            var offsetX by remember { mutableFloatStateOf(maxMarginX) }
            var offsetY by remember { mutableFloatStateOf(screenHeightPx / 2f) }
            var isDragging by remember { mutableStateOf(false) }
            var dragDistance by remember { mutableFloatStateOf(0f) }
            var hoveringClose by remember { mutableStateOf(false) }

            // Drag-to-close target: bottom-center above mini-player/nav.
            // Shown only while dragging; dropping the pill on it cancels timer.
            val closeSizeDp = 64.dp
            val closeSizePx = with(density) { closeSizeDp.toPx() }
            val closeCenterX = screenWidthPx / 2f
            val closeCenterY = (screenHeightPx - with(density) { 190.dp.toPx() })
                .coerceIn(minMarginY, (screenHeightPx - closeSizePx / 2f).coerceAtLeast(minMarginY))
            val closeHitRadiusPx = with(density) { 72.dp.toPx() }
            val closeScale by animateFloatAsState(
                targetValue = if (hoveringClose) 1.18f else 1f,
                animationSpec = tween(150),
                label = "sleepTimerCloseScale",
            )

            // Track max duration for smooth circular remaining-time indicator
            var maxDurationMs by remember { mutableLongStateOf(remainingMs.coerceAtLeast(1L)) }
            LaunchedEffect(remainingMs) {
                if (remainingMs > maxDurationMs) {
                    maxDurationMs = remainingMs
                }
            }
            val progressFraction = (remainingMs.toFloat() / maxDurationMs.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
            val animatedProgress by animateFloatAsState(
                targetValue = progressFraction,
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                label = "sleepTimerProgress",
            )

            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .size(diameterDp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                isDragging = true
                                dragDistance = 0f
                            },
                            onDragEnd = {
                                isDragging = false
                                if (hoveringClose) {
                                    hoveringClose = false
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onCloseTimer()
                                } else if (dragDistance < 15f) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onCycleTimer()
                                }
                            },
                            onDragCancel = {
                                isDragging = false
                                hoveringClose = false
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragDistance += kotlin.math.hypot(dragAmount.x, dragAmount.y)
                                offsetX = (offsetX + dragAmount.x).coerceIn(minMarginX, maxMarginX)
                                offsetY = (offsetY + dragAmount.y).coerceIn(minMarginY, maxMarginY)
                                val pillCx = offsetX + diameterPx / 2f
                                val pillCy = offsetY + diameterPx / 2f
                                val dist = kotlin.math.hypot(pillCx - closeCenterX, pillCy - closeCenterY)
                                val hovering = dist <= closeHitRadiusPx
                                if (hovering && !hoveringClose) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                hoveringClose = hovering
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f),
                    shadowElevation = if (isDragging) 12.dp else 6.dp,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                            shape = CircleShape,
                        ),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Circular progress ring showing time remaining
                        CircularProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(3.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            strokeWidth = 3.dp,
                            strokeCap = StrokeCap.Round,
                        )

                        // Timer text and icon inside the ring: perfectly straight single horizontal line
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Timer,
                                contentDescription = "Sleep Timer",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(if (hours > 0) 12.dp else 14.dp),
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = if (hours > 0) 10.5.sp else 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFeatureSettings = "tnum",
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }

            // Drag-to-close target: appears only while dragging.
            // Drop the pill here to cancel the sleep timer.
            AnimatedVisibility(
                visible = isDragging,
                enter = fadeIn(tween(180)) + scaleIn(tween(180)),
                exit = fadeOut(tween(180)) + scaleOut(tween(180)),
            ) {
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (closeCenterX - closeSizePx / 2f).roundToInt(),
                                (closeCenterY - closeSizePx / 2f).roundToInt(),
                            )
                        }
                        .size(closeSizeDp)
                        .graphicsLayer {
                            scaleX = closeScale
                            scaleY = closeScale
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (hoveringClose) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f),
                        shadowElevation = if (hoveringClose) 12.dp else 6.dp,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .border(
                                width = if (hoveringClose) 2.dp else 1.dp,
                                color = if (hoveringClose) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                shape = CircleShape,
                            ),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Cancel sleep timer",
                                tint = if (hoveringClose) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
