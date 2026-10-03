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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
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

            val widthDp = 72.dp
            val heightDp = 72.dp
            val widthPx = with(density) { widthDp.toPx() }
            val heightPx = with(density) { heightDp.toPx() }
            val screenWidthPx = with(density) { maxWidth.toPx() }
            val screenHeightPx = with(density) { maxHeight.toPx() }

            val minMarginX = with(density) { 12.dp.toPx() }
            val maxMarginX = (screenWidthPx - widthPx - minMarginX).coerceAtLeast(minMarginX)
            val minMarginY = with(density) { 60.dp.toPx() }
            val maxMarginY = (screenHeightPx - heightPx - with(density) { 110.dp.toPx() }).coerceAtLeast(minMarginY)

            // Initial position: center right
            var offsetX by remember { mutableFloatStateOf(maxMarginX) }
            var offsetY by remember { mutableFloatStateOf(screenHeightPx / 2f) }
            var isDragging by remember { mutableStateOf(false) }
            var dragDistance by remember { mutableFloatStateOf(0f) }

            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .sizeIn(minWidth = widthDp, minHeight = heightDp)
                    .aspectRatio(1f)
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
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.95f),
                    shadowElevation = if (isDragging) 12.dp else 6.dp,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f),
                            shape = CircleShape,
                        ),
                ) {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(10.dp).fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Timer,
                            contentDescription = "Sleep Timer",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                        if (sleepTimerRemainingMs != null) {
                            Spacer(modifier = Modifier.height(2.dp))
                            val minutes = (sleepTimerRemainingMs / 60000).toInt()
                            val seconds = ((sleepTimerRemainingMs % 60000) / 1000).toInt()
                            Text(
                                text = String.format("%02d:%02d", minutes, seconds),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
