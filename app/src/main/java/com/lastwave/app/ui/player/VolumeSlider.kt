package com.lastwave.app.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.lastwave.app.ui.theme.LiquidGlassPreset
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.LocalLiquidGlassBackdrop
import com.lastwave.app.ui.theme.isLiquidGlassBackdropSupported
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor
import kotlin.math.roundToInt

/**
 * In-app volume slider for the player.
 *
 * Characteristics:
 * - Centered, bounded width ("little wide not too wide").
 * - Supports 3 visual paradigms:
 *   1. Liquid Glass: Refracting glass chrome, frosted container and luminous accents.
 *   2. Frost Glass: Translucent white capsule tracks matching the player scrim overlay.
 *   3. Compose M3: High-contrast MaterialTheme color tokens for standard non-glass mode.
 * - Hardware rocker & in-app bi-directional sync with [AudioManager.STREAM_MUSIC].
 * - Interactive left (mute/unmute) and right (max) volume shortcut buttons.
 */
@Composable
fun VolumeSlider(
    modifier: Modifier = Modifier,
    isTranslucent: Boolean = false,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
) {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    val minVol = remember(audioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { audioManager?.getStreamMinVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: 0
        } else {
            0
        }
    }
    val maxVol = remember(audioManager) {
        maxOf(1, runCatching { audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: 15)
    }

    var currentVolume by remember(audioManager) {
        mutableIntStateOf(
            runCatching { audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: (maxVol / 2)
        )
    }
    var savedMuteVolume by remember { mutableIntStateOf(maxOf(1, maxVol / 3)) }
    var isInteracting by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    // Listen to hardware volume button clicks and external volume changes
    DisposableEffect(context, audioManager) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                    val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                    if (streamType == AudioManager.STREAM_MUSIC || streamType == -1) {
                        val updated = runCatching {
                            audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC)
                        }.getOrNull()
                        if (updated != null && !isInteracting) {
                            currentVolume = updated
                        }
                    }
                }
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        context.registerReceiver(receiver, filter)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    // Refresh volume on composition
    LaunchedEffect(audioManager) {
        val vol = runCatching { audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        if (vol != null) currentVolume = vol
    }

    val volumeRange = (maxVol - minVol).coerceAtLeast(1)
    val liveFraction = ((currentVolume - minVol).toFloat() / volumeRange.toFloat()).coerceIn(0f, 1f)
    val displayFraction = (dragFraction ?: liveFraction).coerceIn(0f, 1f)

    // Visual style arbitration: Liquid Glass vs Frost Glass vs Compose M3
    val isGlassActive = LocalLiquidGlass.current &&
        isLiquidGlassBackdropSupported() &&
        LocalLiquidGlassBackdrop.current != null

    val activeTrackColor = when {
        isGlassActive -> Color.White.copy(alpha = 0.95f)
        isTranslucent -> Color.White
        else -> primaryColor
    }
    val inactiveTrackColor = when {
        isGlassActive -> Color.White.copy(alpha = 0.16f)
        isTranslucent -> Color.White.copy(alpha = 0.20f)
        else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.60f)
    }
    val thumbColor = when {
        isGlassActive -> Color.White
        isTranslucent -> Color.White
        else -> primaryColor
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
                        if (currentVolume > minVol) {
                            savedMuteVolume = currentVolume
                            currentVolume = minVol
                            runCatching {
                                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, minVol, 0)
                            }
                        } else {
                            val target = savedMuteVolume.coerceIn(minVol + 1, maxVol)
                            currentVolume = target
                            runCatching {
                                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (displayFraction <= 0.02f) Icons.Filled.VolumeMute else Icons.Filled.VolumeDown,
                    contentDescription = if (displayFraction <= 0.02f) "Unmute" else "Mute",
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
                        dragFraction = fraction
                        val target = (minVol + fraction * volumeRange).roundToInt().coerceIn(minVol, maxVol)
                        if (target != currentVolume) {
                            currentVolume = target
                            runCatching {
                                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                            }
                        }
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
                        currentVolume = maxVol
                        runCatching {
                            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
                        }
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
