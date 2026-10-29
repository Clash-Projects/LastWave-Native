package com.lastwave.app.ui.player.automix

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.lastwave.app.playback.automix.api.BlendPhase
import com.lastwave.app.playback.automix.api.DeckBlendTelemetry
import kotlin.math.sin

@Composable
fun AutoMixWaveSeekbarCanvas(
    positionMs: Long,
    durationMs: Long,
    telemetry: DeckBlendTelemetry,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalDuration = maxOf(durationMs, 1L)
    val rawProgress = (positionMs.toFloat() / totalDuration.toFloat()).coerceIn(0.0f, 1.0f)

    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0.0f) }
    val displayProgress = if (isDragging) dragProgress else rawProgress

    // Smooth wave amplitude animation: flattens to 0f when paused or idle
    val targetAmplitude = if (isPlaying && telemetry.isBlendingActive) 1.0f else 0.0f
    val waveAmplitude = remember { Animatable(0f) }

    LaunchedEffect(targetAmplitude) {
        waveAmplitude.animateTo(
            targetValue = targetAmplitude,
            animationSpec = tween(durationMillis = 350, easing = LinearEasing),
        )
    }

    // Continuous wave phase motion tied to BPM
    val bpm = telemetry.syncedBpm ?: 120f
    val cycleDurationMs = ((60f / bpm) * 1000f).toInt().coerceIn(300, 1500)
    val infiniteTransition = rememberInfiniteTransition(label = "HarmonicWavePhase")
    val phaseOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = cycleDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phaseOffset",
    )

    // Rainbow / Iridescent Spectral Shader Colors
    val rainbowColors = listOf(
        Color(0xFFFF007F), // Neon Rose
        Color(0xFFFF7F00), // Coral Orange
        Color(0xFFFFD700), // Gold
        Color(0xFF00FF7F), // Emerald Mint
        Color(0xFF00CFFF), // Electric Cyan
        Color(0xFF7F00FF), // Violet
        Color(0xFFFF007F), // Wrap back
    )

    val activeTrackColor = MaterialTheme.colorScheme.primary
    val inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)
    val horizonGlowColor = Color(0xFF00E5FF).copy(alpha = 0.45f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(totalDuration) {
                detectTapGestures { offset ->
                    val clamped = (offset.x / size.width.toFloat()).coerceIn(0.0f, 1.0f)
                    val targetMs = (clamped * totalDuration).toLong()
                    onSeek(targetMs)
                }
            }
            .pointerInput(totalDuration) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragProgress = (offset.x / size.width.toFloat()).coerceIn(0.0f, 1.0f)
                    },
                    onDragEnd = {
                        isDragging = false
                        val targetMs = (dragProgress * totalDuration).toLong()
                        onSeek(targetMs)
                    },
                    onDragCancel = { isDragging = false },
                    onDrag = { change, _ ->
                        dragProgress = (change.position.x / size.width.toFloat()).coerceIn(0.0f, 1.0f)
                    }
                )
            }
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val trackHeight = 4.dp.toPx()
        val thumbRadius = 7.dp.toPx()

        // 1. Inactive Background Track
        drawRoundRect(
            color = inactiveTrackColor,
            topLeft = Offset(0f, centerY - (trackHeight / 2f)),
            size = Size(width, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f),
        )

        // 2. Armed Splice Horizon Preview Marker
        if (telemetry.phase == BlendPhase.ARMED && telemetry.spliceStartPositionMs > 0L) {
            val spliceStartNorm = (telemetry.spliceStartPositionMs.toFloat() / totalDuration.toFloat()).coerceIn(0.0f, 1.0f)
            val markerX = spliceStartNorm * width
            val markerWidth = width - markerX
            if (markerWidth > 0f) {
                drawRoundRect(
                    color = horizonGlowColor,
                    topLeft = Offset(markerX, centerY - (trackHeight / 2f) - 1.dp.toPx()),
                    size = Size(markerWidth, trackHeight + 2.dp.toPx()),
                    cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f),
                )
            }
        }

        // 3. Active Track: Normal solid line or Rainbow-Iridescent Wave
        val progressX = (displayProgress * width).coerceIn(0f, width)

        if (waveAmplitude.value > 0.01f && progressX > 10f) {
            // Draw iridescent sine wave
            val wavePath = Path()
            val amp = waveAmplitude.value * 6.dp.toPx()
            val numSteps = (progressX / 4f).toInt().coerceAtLeast(10)

            wavePath.moveTo(0f, centerY)
            for (i in 0..numSteps) {
                val x = (i.toFloat() / numSteps.toFloat()) * progressX
                val angle = (x / width) * (4f * Math.PI.toFloat()) + phaseOffset
                val y = centerY + sin(angle) * amp
                wavePath.lineTo(x, y)
            }

            val rainbowBrush = Brush.horizontalGradient(
                colors = rainbowColors,
                startX = 0f,
                endX = progressX,
            )

            drawPath(
                path = wavePath,
                brush = rainbowBrush,
                style = Stroke(width = trackHeight + 1.dp.toPx(), cap = StrokeCap.Round),
            )
        } else {
            // Standard smooth active bar
            if (progressX > 0f) {
                drawRoundRect(
                    color = activeTrackColor,
                    topLeft = Offset(0f, centerY - (trackHeight / 2f)),
                    size = Size(progressX, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f),
                )
            }
        }

        // 4. Thumb Indicator
        val thumbColor = if (telemetry.isBlendingActive) Color.White else activeTrackColor
        drawCircle(
            color = thumbColor,
            radius = thumbRadius,
            center = Offset(progressX, centerY),
        )
    }
}
