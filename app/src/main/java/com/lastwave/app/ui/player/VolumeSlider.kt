package com.lastwave.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.ui.theme.LiquidGlassPreset
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.LocalLiquidGlassBackdrop
import com.lastwave.app.ui.theme.isLiquidGlassBackdropSupported
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor
import kotlin.math.roundToInt

/**
 * Independent in-app playback volume slider for the player.
 *
 * Characteristics:
 * - Operates strictly on app-internal playback volume (0% - 100%), completely
 *   independent from the device's hardware master volume [android.media.AudioManager.STREAM_MUSIC].
 * - Supports 3 visual paradigms:
 *   1. Liquid Glass: Refracting glass chrome, frosted container and luminous accents.
 *   2. Frost Glass: Translucent white capsule tracks matching the player scrim overlay.
 *   3. Compose M3: High-contrast MaterialTheme color tokens for standard non-glass mode.
 * - Interactive left (mute/unmute) and right (max 100%) shortcut buttons.
 */
@Composable
fun VolumeSlider(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    isTranslucent: Boolean = false,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
) {
    var savedMuteVolume by remember { mutableFloatStateOf(if (volume > 0.05f) volume else 0.75f) }
    var isInteracting by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val displayFraction = (dragFraction ?: volume).coerceIn(0f, 1f)

    // Visual style arbitration: Liquid Glass vs Frost Glass vs Compose M3
    val isGlassActive = LocalLiquidGlass.current &&
        isLiquidGlassBackdropSupported() &&
        LocalLiquidGlassBackdrop.current != null

    val activeTrackColor = when {
        isGlassActive -> Color.White.copy(alpha = 0.95f)
        isTranslucent -> Color.White
        else -> primaryColor.ensureLuminousForDarkBackground(0.38f)
    }
    val inactiveTrackColor = when {
        isGlassActive -> Color.White.copy(alpha = 0.20f)
        isTranslucent -> Color.White.copy(alpha = 0.24f)
        else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.60f)
    }
    val thumbColor = when {
        isGlassActive -> Color.White
        isTranslucent -> Color.White
        else -> primaryColor.ensureLuminousForDarkBackground(0.38f)
    }
    val iconColor = when {
        isGlassActive -> Color.White.copy(alpha = 0.90f)
        isTranslucent -> Color.White.copy(alpha = 0.85f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f)
    }

    val interactionSource = remember { MutableInteractionSource() }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        val containerShape = RoundedCornerShape(100)
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(44.dp)
                .then(
                    if (isGlassActive) {
                        Modifier.liquidGlassChrome(
                            containerShape,
                            LocalLiquidGlass.current,
                            LiquidGlassPreset.PlayerControls,
                            interactionSource = interactionSource,
                        )
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (isGlassActive) {
                        Modifier.background(
                            liquidGlassContainerColor(Color.White.copy(alpha = 0.08f)),
                            containerShape,
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Mute / Restore shortcut button
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable {
                        if (volume > 0.001f) {
                            savedMuteVolume = volume
                            onVolumeChange(0f)
                        } else {
                            val target = savedMuteVolume.coerceIn(0.15f, 1f)
                            onVolumeChange(target)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (displayFraction <= 0.001f) Icons.Filled.VolumeMute else Icons.Filled.VolumeDown,
                    contentDescription = if (displayFraction <= 0.001f) "Unmute" else "Mute",
                    tint = iconColor,
                    modifier = Modifier.size(20.dp),
                )
            }

            // Slider Track Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxWidth().height(44.dp)) {
                    val width = size.width
                    val height = size.height
                    val centerY = height / 2f
                    val trackHeightPx = 10.dp.toPx()
                    val cornerRadius = CornerRadius(trackHeightPx / 2f, trackHeightPx / 2f)
                    val thumbWidthPx = 5.dp.toPx()
                    val thumbHeightPx = 24.dp.toPx()
                    val thumbClearancePx = 6.dp.toPx()
                    val rawThumbCenterX = displayFraction * width
                    val thumbCenterX = if (width > thumbWidthPx) {
                        rawThumbCenterX.coerceIn(thumbWidthPx / 2f, width - thumbWidthPx / 2f)
                    } else {
                        width / 2f
                    }
                    val activeEndX = (thumbCenterX - thumbClearancePx).coerceIn(0f, width)
                    val inactiveStartX = (thumbCenterX + thumbClearancePx).coerceIn(0f, width)

                    // Active track capsule
                    if (activeEndX > 0f) {
                        drawRoundRect(
                            color = activeTrackColor,
                            topLeft = Offset(0f, centerY - trackHeightPx / 2f),
                            size = Size(activeEndX, trackHeightPx),
                            cornerRadius = cornerRadius,
                        )
                    }

                    // Inactive track capsule
                    if (inactiveStartX < width) {
                        drawRoundRect(
                            color = inactiveTrackColor,
                            topLeft = Offset(inactiveStartX, centerY - trackHeightPx / 2f),
                            size = Size(width - inactiveStartX, trackHeightPx),
                            cornerRadius = cornerRadius,
                        )
                    }

                    // Small endpoint dot matching seekbar reference
                    val endpointX = width - trackHeightPx / 2f
                    if (inactiveStartX < endpointX) {
                        drawCircle(
                            color = activeTrackColor.copy(alpha = 0.86f),
                            radius = 1.5.dp.toPx(),
                            center = Offset(endpointX, centerY),
                        )
                    }

                    // Vertical pill thumb
                    val thumbX = thumbCenterX - thumbWidthPx / 2f
                    val thumbCorner = CornerRadius(thumbWidthPx / 2f, thumbWidthPx / 2f)
                    drawRoundRect(
                        color = thumbColor,
                        topLeft = Offset(thumbX, centerY - thumbHeightPx / 2f),
                        size = Size(thumbWidthPx, thumbHeightPx),
                        cornerRadius = thumbCorner,
                    )
                }

                Slider(
                    value = displayFraction,
                    onValueChange = { fraction ->
                        isInteracting = true
                        val clamped = fraction.coerceIn(0f, 1f)
                        dragFraction = clamped
                        onVolumeChange(clamped)
                    },
                    onValueChangeFinished = {
                        isInteracting = false
                        dragFraction = null
                    },
                    valueRange = 0f..1f,
                    interactionSource = interactionSource,
                    modifier = Modifier.fillMaxSize(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.Transparent,
                        activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent,
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent,
                        disabledThumbColor = Color.Transparent,
                        disabledActiveTrackColor = Color.Transparent,
                        disabledInactiveTrackColor = Color.Transparent,
                        disabledActiveTickColor = Color.Transparent,
                        disabledInactiveTickColor = Color.Transparent,
                    ),
                )
            }

            // Max Volume shortcut button
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable {
                        onVolumeChange(1f)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.VolumeUp,
                    contentDescription = "Max volume",
                    tint = iconColor,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
fun VolumeSlider(
    player: MusicPlayer,
    modifier: Modifier = Modifier,
    isTranslucent: Boolean = false,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
) {
    val state by player.state.collectAsStateWithLifecycle()
    VolumeSlider(
        volume = state.appVolume,
        onVolumeChange = player::setAppVolume,
        modifier = modifier,
        isTranslucent = isTranslucent,
        primaryColor = primaryColor,
    )
}
