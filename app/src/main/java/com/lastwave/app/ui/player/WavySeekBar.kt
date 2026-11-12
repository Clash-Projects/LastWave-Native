package com.lastwave.app.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import com.lastwave.app.ui.common.LocalAmbientAnimationsActive
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

private fun Color.shiftTonal(lightnessDelta: Float, saturationScale: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (red * 255).toInt().coerceIn(0, 255),
        (green * 255).toInt().coerceIn(0, 255),
        (blue * 255).toInt().coerceIn(0, 255),
        hsv,
    )
    val originalSaturation = hsv[1]
    hsv[1] = (hsv[1] * saturationScale).coerceIn(if (originalSaturation > 0.05f) 0.15f else 0f, 1f)
    hsv[2] = (hsv[2] + lightnessDelta).coerceIn(0.25f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Wavy progress track: three rolling gradient waves over the played part.
 * Draw only; the caller owns gestures, semantics and time labels. [fraction]
 * is read in the draw phase, so progress ticks redraw without recomposing.
 */
@Composable
internal fun WavyProgressTrack(
    fraction: () -> Float,
    isPlaying: Boolean,
    dragging: Boolean,
    isTranslucent: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    tertiaryColor: Color,
    modifier: Modifier = Modifier,
) {
    val primary = remember(primaryColor) { primaryColor.ensureLuminousForDarkBackground(0.38f) }
    val secondary = remember(secondaryColor) { secondaryColor.ensureLuminousForDarkBackground(0.32f) }
    val tertiary = remember(tertiaryColor) { tertiaryColor.ensureLuminousForDarkBackground(0.28f) }
    val inactiveColor = Color.White.copy(alpha = if (isTranslucent) 0.24f else 0.22f)
    val layer1 = remember(tertiary, isTranslucent) {
        listOf(
            tertiary.shiftTonal(+0.18f, 0.85f).copy(alpha = if (isTranslucent) 0.44f else 0.40f),
            tertiary.shiftTonal(-0.06f, 1.15f).copy(alpha = if (isTranslucent) 0.34f else 0.30f),
        )
    }
    val layer2 = remember(secondary, isTranslucent) {
        listOf(
            secondary.shiftTonal(-0.08f, 1.15f).copy(alpha = if (isTranslucent) 0.74f else 0.68f),
            secondary.shiftTonal(+0.18f, 0.85f).copy(alpha = if (isTranslucent) 0.64f else 0.58f),
        )
    }
    val baseline = remember(primary) { listOf(primary.shiftTonal(+0.20f, 0.90f), primary.shiftTonal(-0.05f, 1.15f)) }
    val layer3 = remember(baseline, isTranslucent) {
        listOf(
            baseline[0].copy(alpha = if (isTranslucent) 0.98f else 0.95f),
            baseline[1].copy(alpha = if (isTranslucent) 0.92f else 0.88f),
        )
    }
    val thumbColor = remember(primary) { primary.shiftTonal(+0.06f, 1.05f) }

    // Runs only while the waves are visible and moving; paused or dragging
    // draws the static pose. Each run starts from that pose, so resuming glides.
    val waveClockSeconds = remember { mutableFloatStateOf(0f) }
    val wavesActive = isPlaying && !dragging &&
        LocalAmbientAnimationsActive.current && rememberPlayerWindowActive()
    LaunchedEffect(wavesActive) {
        waveClockSeconds.floatValue = 0f
        if (!wavesActive) return@LaunchedEffect
        var startNanos = -1L
        while (true) {
            withFrameNanos { now ->
                if (startNanos < 0L) startNanos = now
                waveClockSeconds.floatValue = ((now - startNanos) % WAVE_CLOCK_WRAP_NANOS) / 1_000_000_000f
            }
            // Slow rolling waves look the same at 30 fps; every vsync (120 Hz) kept the GPU busy.
            delay(WAVE_FRAME_MS)
        }
    }

    // Waves flatten while scrubbing so the thumb position reads clearly.
    val amp1 by animateFloatAsState(if (dragging) 1.2f else 13f, tween(180), label = "Amp1")
    val amp2 by animateFloatAsState(if (dragging) 1.2f else 10f, tween(180), label = "Amp2")
    val amp3 by animateFloatAsState(if (dragging) 1.2f else 7.5f, tween(180), label = "Amp3")

    val contour1 = remember { Path() }
    val contour2 = remember { Path() }
    val contour3 = remember { Path() }

    Canvas(modifier = modifier) {
        val width = size.width
        val centerY = size.height / 2f + 7.dp.toPx()
        val thumbX = (fraction().coerceIn(0f, 1f) * width).coerceIn(0f, width)
        val trackPx = 4.5.dp.toPx()
        val half = trackPx / 2f
        val topY = centerY - half

        drawLine(inactiveColor, Offset(0f, centerY), Offset(width, centerY), trackPx, StrokeCap.Round)

        if (thumbX > 0f) {
            val transition = minOf(44.dp.toPx(), thumbX * 0.48f)
            val invTransition = if (transition > 0f) 1f / transition else 0f
            val step = 1.5.dp.toPx()
            fun smootherstep(t: Float): Float {
                val c = t.coerceIn(0f, 1f)
                return c * c * c * (c * (c * 6f - 15f) + 10f)
            }
            fun wave(path: Path, wavelengthDp: Float, amplitudeDp: Float, phase: Float) {
                val amplitude = amplitudeDp.dp.toPx()
                val k = (2 * PI / wavelengthDp.dp.toPx()).toFloat()
                path.reset()
                path.moveTo(0f, topY)
                var x = 0f
                while (x < thumbX) {
                    val envelope = if (invTransition > 0f) {
                        smootherstep(x * invTransition) * smootherstep((thumbX - x) * invTransition)
                    } else {
                        1f
                    }
                    path.lineTo(x, topY - (0.5f + 0.5f * sin(x * k - phase)) * amplitude * envelope)
                    x += step
                }
                path.lineTo(thumbX, topY)
            }

            val t = waveClockSeconds.floatValue
            wave(contour1, 160f, amp1, wavePhase(t, 2.4f) + 2.2f)
            wave(contour2, 125f, amp2, wavePhase(t, 1.8f) + 1.2f)
            wave(contour3, 95f, amp3, wavePhase(t, 1.3f))
            val activeWidth = thumbX.coerceAtLeast(1f)
            val join = StrokeJoin.Round

            // A rect clip: an anti-aliased path clip is masked on the CPU every frame.
            clipRect(left = -half, right = thumbX + half) {
                drawPath(contour1, Brush.horizontalGradient(layer1, 0f, activeWidth), style = Stroke(1.0.dp.toPx(), cap = StrokeCap.Round, join = join))
                drawPath(contour2, Brush.horizontalGradient(layer2, 0f, activeWidth), style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round, join = join))
                drawPath(contour3, Brush.horizontalGradient(layer3, 0f, activeWidth), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = join))
                drawLine(Brush.horizontalGradient(baseline, 0f, activeWidth), Offset(0f, centerY), Offset(thumbX, centerY), trackPx, StrokeCap.Round)
            }
        }

        val thumbRadius = 7.5.dp.toPx()
        val center = Offset(thumbX, centerY)
        drawCircle(thumbColor.copy(alpha = 0.35f), thumbRadius + 3.5.dp.toPx(), center)
        drawCircle(thumbColor, thumbRadius, center)
        drawCircle(Color.White.copy(alpha = 0.55f), thumbRadius, center, style = Stroke(1.2.dp.toPx()))
    }
}

/** 93.6 s: a whole number of 2.4 s, 1.8 s and 1.3 s periods, so wrapping is seamless. */
private const val WAVE_CLOCK_WRAP_NANOS = 93_600_000_000L
private const val WAVE_FRAME_MS = 33L

private fun wavePhase(seconds: Float, periodSeconds: Float): Float {
    val cycles = seconds / periodSeconds
    return (cycles - kotlin.math.floor(cycles)) * (2 * PI).toFloat()
}
