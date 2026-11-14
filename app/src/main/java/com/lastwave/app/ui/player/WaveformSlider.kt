package com.lastwave.app.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lastwave.app.ui.common.LocalAmbientAnimationsActive
import kotlin.math.PI
import kotlin.math.pow
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
 * Samsung One UI 6 style waveform slider with LastWave artwork color extraction.
 *
 * Artwork color extraction:
 * - Wavy seekbar has 3 waves (tertiary, secondary, primary).
 * - Waveform seekbar has 2 waves:
 *   1. Foreground main wave & active base track: extracts primary tonal gradient.
 *   2. Background phantom wave (liquid depth): extracts secondary tonal gradient.
 * - Circular thumb knob: extracts primary tonal core with white outer ring and subtle glow halo.
 */
@Composable
fun WaveformSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)?,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.secondary,
    tertiaryColor: Color = MaterialTheme.colorScheme.tertiary,
    isTranslucent: Boolean = false,
    activeColor: Color? = null,
    inactiveColor: Color? = null,
    thumbColor: Color? = null,
    trackHeight: Dp = 44.dp,
    enabled: Boolean = true,
) {
    val range = valueRange.endInclusive - valueRange.start
    val fraction = if (range <= 0f) 0f
    else ((value - valueRange.start) / range).coerceIn(0f, 1f)

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val currentFraction = if (isDragging) dragFraction else fraction

    // Palette extraction engine aligned with WavySeekBar
    val basePrimary = activeColor ?: primaryColor
    val primary = remember(basePrimary) { basePrimary.ensureLuminousForDarkBackground(0.38f) }
    val secondary = remember(secondaryColor) { secondaryColor.ensureLuminousForDarkBackground(0.32f) }

    val activeColors = remember(primary, isTranslucent) {
        listOf(
            primary.shiftTonal(+0.20f, 0.90f).copy(alpha = if (isTranslucent) 0.98f else 0.95f),
            primary.shiftTonal(-0.05f, 1.15f).copy(alpha = if (isTranslucent) 0.92f else 0.88f),
        )
    }

    val phantomColors = remember(secondary, isTranslucent) {
        listOf(
            secondary.shiftTonal(+0.16f, 0.85f).copy(alpha = if (isTranslucent) 0.60f else 0.55f),
            secondary.shiftTonal(-0.08f, 1.15f).copy(alpha = if (isTranslucent) 0.45f else 0.40f),
        )
    }

    val knobCoreColor = remember(thumbColor, primary) {
        thumbColor ?: primary.shiftTonal(+0.06f, 1.05f)
    }

    val effectiveInactive = remember(inactiveColor, isTranslucent) {
        inactiveColor ?: Color.White.copy(alpha = if (isTranslucent) 0.24f else 0.22f)
    }

    // Phase animation for water flow
    val animationsActive = isPlaying && !isDragging &&
        LocalAmbientAnimationsActive.current && rememberPlayerWindowActive()
    val infiniteTransition = rememberInfiniteTransition(label = "wavePhase")
    val phase by if (animationsActive) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2f * PI.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "wavePhase"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    // Amplitude multiplier: smoothly collapses when paused or scrubbing
    val targetMultiplier = if (isPlaying && !isDragging) 1f else 0f
    val animatedMultiplier by animateFloatAsState(
        targetValue = targetMultiplier,
        animationSpec = tween(durationMillis = 350, easing = LinearEasing),
        label = "amplitudeAnim"
    )

    // Reusable Paths to avoid object allocations in draw phase
    val maskPath = remember { Path() }
    val rectPath = remember { Path() }
    val pillPath = remember { Path() }
    val mainWavePath = remember { Path() }
    val phantomWavePath = remember { Path() }

    Box(modifier = modifier.fillMaxWidth().height(trackHeight)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .pointerInput(valueRange, enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        val f = if (size.width > 0) (offset.x / size.width).coerceIn(0f, 1f) else 0f
                        onValueChange(valueRange.start + f * range)
                        onValueChangeFinished?.invoke()
                    }
                }
                .pointerInput(valueRange, enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragFraction = if (size.width > 0) (offset.x / size.width).coerceIn(0f, 1f) else 0f
                            onValueChange(valueRange.start + dragFraction * range)
                        },
                        onDragEnd = {
                            isDragging = false
                            onValueChangeFinished?.invoke()
                        },
                        onDragCancel = {
                            isDragging = false
                            onValueChangeFinished?.invoke()
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            if (size.width > 0) {
                                dragFraction = (dragFraction + dragAmount / size.width).coerceIn(0f, 1f)
                                onValueChange(valueRange.start + dragFraction * range)
                            }
                        }
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            val centerY = h / 2f
            val splitX = (currentFraction * w).coerceIn(0f, w)

            val baseH = 9.dp.toPx()
            val baseCornerRadius = CornerRadius(baseH / 2f, baseH / 2f)
            val topOfBase = centerY - baseH / 2f

            // Dynamic breathing amplitude based on base track height
            val baseAmplitude = 13.dp.toPx()
            val breathingFactor = 0.85f + 0.15f * sin(phase)
            val ampPx = baseAmplitude * breathingFactor * animatedMultiplier

            // --- 1. Draw Inactive Thick Base Track ---
            drawRoundRect(
                color = effectiveInactive,
                topLeft = Offset(0f, topOfBase),
                size = Size(w, baseH),
                cornerRadius = baseCornerRadius
            )

            // --- 2. Draw Active Base Track & Wave (Merged via Mask) ---
            if (splitX > 0f) {
                rectPath.reset()
                rectPath.addRect(Rect(baseH / 2f, -1000f, w, centerY))

                pillPath.reset()
                pillPath.addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = topOfBase,
                        right = w,
                        bottom = topOfBase + baseH,
                        cornerRadius = baseCornerRadius
                    )
                )

                maskPath.reset()
                maskPath.op(rectPath, pillPath, PathOperation.Union)

                if (ampPx > 0.1f) {
                    val frequency = 4.6f * PI.toFloat()
                    val step = 4f

                    // A. Calculate Phantom Wave (Background Secondary Liquid Layer)
                    phantomWavePath.reset()
                    phantomWavePath.moveTo(0f, topOfBase)
                    var px = 0f
                    val phantomPhase = phase - 1.2f
                    val phantomAmp = ampPx * 0.7f

                    while (px <= w + step) {
                        val currentX = px.coerceAtMost(w)
                        val originEase = smoothstep(0f, w * 0.15f, currentX)
                        val knobEase = 1f - smoothstep(splitX - (w * 0.15f), splitX, currentX)
                        val progressFactor = originEase * knobEase

                        val rawSin = sin((currentX / w * frequency) - phantomPhase) * 0.5f + 0.5f
                        val curvedSin = rawSin.pow(0.7f)

                        val waveHeight = curvedSin * phantomAmp * progressFactor
                        phantomWavePath.lineTo(currentX, topOfBase - waveHeight)
                        if (currentX == w) break
                        px += step
                    }
                    phantomWavePath.lineTo(w, topOfBase + baseH + 10f)
                    phantomWavePath.lineTo(0f, topOfBase + baseH + 10f)
                    phantomWavePath.close()

                    // B. Calculate Main Wave (Foreground Primary Liquid Layer)
                    mainWavePath.reset()
                    mainWavePath.moveTo(0f, topOfBase)
                    var mx = 0f

                    while (mx <= w + step) {
                        val currentX = mx.coerceAtMost(w)
                        val originEase = smoothstep(0f, w * 0.15f, currentX)
                        val knobEase = 1f - smoothstep(splitX - (w * 0.15f), splitX, currentX)
                        val progressFactor = originEase * knobEase

                        val rawSin = sin((currentX / w * frequency) - phase) * 0.5f + 0.5f
                        val curvedSin = rawSin.pow(0.7f)

                        val waveHeight = curvedSin * ampPx * progressFactor
                        mainWavePath.lineTo(currentX, topOfBase - waveHeight)
                        if (currentX == w) break
                        mx += step
                    }
                    mainWavePath.lineTo(w, topOfBase + baseH + 10f)
                    mainWavePath.lineTo(0f, topOfBase + baseH + 10f)
                    mainWavePath.close()
                } else {
                    phantomWavePath.reset()
                    phantomWavePath.addRect(Rect(0f, topOfBase, w, topOfBase + baseH + 10f))

                    mainWavePath.reset()
                    mainWavePath.addRect(Rect(0f, topOfBase, w, topOfBase + baseH + 10f))
                }

                val activeWidth = splitX.coerceAtLeast(1f)
                val primaryBrush = Brush.horizontalGradient(activeColors, 0f, activeWidth)
                val secondaryBrush = Brush.horizontalGradient(phantomColors, 0f, activeWidth)

                clipPath(maskPath) {
                    clipRect(left = 0f, right = splitX) {
                        // Background phantom layer (extracted secondary color)
                        drawPath(
                            path = phantomWavePath,
                            brush = secondaryBrush,
                            style = Fill
                        )

                        // Foreground primary layer (extracted primary color)
                        drawPath(
                            path = mainWavePath,
                            brush = primaryBrush,
                            style = Fill
                        )
                    }
                }
            }

            // --- 3. Circular Thumb Indicator ---
            val thumbOuterRadius = 10.dp.toPx()
            val thumbInnerRadius = 6.5.dp.toPx()
            val thumbY = centerY

            // Soft ambient glow halo
            drawCircle(
                color = knobCoreColor.copy(alpha = 0.35f),
                radius = thumbOuterRadius + 3.dp.toPx(),
                center = Offset(splitX, thumbY)
            )

            // Outer circle (clean white ring)
            drawCircle(
                color = Color.White.copy(alpha = 0.95f),
                radius = thumbOuterRadius,
                center = Offset(splitX, thumbY)
            )

            // Inner circle (vibrant extracted primary core)
            drawCircle(
                color = knobCoreColor,
                radius = thumbInnerRadius,
                center = Offset(splitX, thumbY)
            )

            // Crisp inner rim
            drawCircle(
                color = Color.White.copy(alpha = 0.50f),
                radius = thumbInnerRadius,
                center = Offset(splitX, thumbY),
                style = Stroke(1.dp.toPx())
            )
        }
    }
}

// Math interpolator to ensure continuous path bridging with base coordinates
private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val denom = edge1 - edge0
    if (denom == 0f) return 0f
    val t = ((x - edge0) / denom).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
