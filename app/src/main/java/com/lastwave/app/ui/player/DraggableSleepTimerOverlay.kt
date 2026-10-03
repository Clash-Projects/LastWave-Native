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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun DraggableSleepTimerOverlay(
    sleepTimerRemainingMs: Long?,
    onCycleTimer: () -> Unit,
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
                                if (dragDistance < 15f) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onCycleTimer()
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
        }
    }
}
