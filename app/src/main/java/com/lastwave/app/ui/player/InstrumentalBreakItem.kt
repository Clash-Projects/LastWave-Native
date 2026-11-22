package com.lastwave.app.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Instrumental break icon: music-note filled bottom-to-top over the instrumental gap.
 */
@Composable
fun InstrumentalBreakItem(
    durationMs: Long,
    currentPositionMs: Long,
    startTimeMs: Long,
    textColor: Color,
    inactiveAlpha: Float,
    drawOutline: Boolean = false,
    useBreathingPulse: Boolean = false,
    isActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val musicNotePath = remember {
        PathParser()
            .parsePathString(
                "M10 21q-1.65 0-2.825-1.175T6 17t1.175-2.825T10 13q.575 0 1.063.138t.937.412V4" +
                "q0-.425.288-.712T13 3h4q.425 0 .713.288T18 4v2q0 .425-.288.713T17 7h-3v10" +
                "q0 1.65-1.175 2.825T10 21"
            )
            .toPath()
    }

    val targetFillFraction = when {
        durationMs <= 0L -> 0f
        currentPositionMs <= startTimeMs -> 0f
        currentPositionMs >= startTimeMs + durationMs -> 1f
        else ->
            ((currentPositionMs - startTimeMs).toDouble() / durationMs.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
    }

    val fillFraction by animateFloatAsState(
        targetValue = targetFillFraction,
        animationSpec = spring(
            stiffness = Spring.StiffnessHigh,
            dampingRatio = Spring.DampingRatioNoBouncy,
        ),
        label = "instrumentalFill",
    )

    val pulseScale = if (useBreathingPulse && isActive) {
        val infiniteTransition = rememberInfiniteTransition(label = "instrumentalPulse")
        val scale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 0.90f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseScale",
        )
        scale
    } else {
        1.0f
    }

    Canvas(
        modifier = modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = pulseScale
                scaleY = pulseScale
                transformOrigin = TransformOrigin.Center
            }
    ) {
        val scaleX = size.width / 24f
        val scaleY = size.height / 24f
        val pivot = Offset.Zero

        withTransform(
            transformBlock = { scale(scaleX, scaleY, pivot) },
        ) {
            val drawStyle = if (drawOutline) Stroke(width = 1.5f) else Fill
            drawPath(path = musicNotePath, color = textColor.copy(alpha = inactiveAlpha), style = drawStyle)
        }

        if (fillFraction > 0f) {
            val clipTop = size.height * (1f - fillFraction)
            clipRect(
                left = 0f,
                top = clipTop,
                right = size.width,
                bottom = size.height,
            ) {
                withTransform(
                    transformBlock = { scale(scaleX, scaleY, pivot) },
                ) {
                    drawPath(path = musicNotePath, color = textColor)
                }
            }
        }
    }
}
