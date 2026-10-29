package com.lastwave.app.ui.player.automix

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lastwave.app.playback.automix.api.DeckBlendTelemetry

@Composable
fun CrossDeckArtworkStage(
    currentArtworkUrl: String?,
    telemetry: DeckBlendTelemetry,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isBlending = telemetry.isBlendingActive
    val blendProgress = telemetry.clampedProgress

    // Spring-damped depth scale and perspective rotation
    val springSpec = spring<Float>(
        dampingRatio = 0.82f,
        stiffness = 380f,
    )

    val outgoingScale by animateFloatAsState(
        targetValue = if (isBlending) 1.0f - (blendProgress * 0.12f) else 1.0f,
        animationSpec = springSpec,
        label = "outgoingScale",
    )
    val incomingScale by animateFloatAsState(
        targetValue = if (isBlending) 0.88f + (blendProgress * 0.12f) else 0.88f,
        animationSpec = springSpec,
        label = "incomingScale",
    )

    var dragOffset by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .draggable(
                state = rememberDraggableState { delta -> dragOffset += delta },
                orientation = Orientation.Horizontal,
                onDragStopped = {
                    if (dragOffset < -120f) onSwipeNext()
                    else if (dragOffset > 120f) onSwipePrevious()
                    dragOffset = 0f
                }
            )
    ) {
        // 1. Incoming Deck Artwork (Pre-warmed on ARMED, scales up on BLENDING)
        val incomingArtworkUrl = telemetry.incomingTrackPreview?.artworkUrl
        if (incomingArtworkUrl != null && telemetry.isArmedOrBlending) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = incomingScale
                        scaleY = incomingScale
                        alpha = if (isBlending) blendProgress else 0.0f
                    }
                    .clip(RoundedCornerShape(24.dp))
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(incomingArtworkUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Incoming Track Artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 2. Outgoing Deck Artwork (Scales down and dissolves)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = outgoingScale
                    scaleY = outgoingScale
                    alpha = if (isBlending) (1.0f - blendProgress).coerceIn(0f, 1f) else 1.0f
                    translationX = dragOffset
                }
                .clip(RoundedCornerShape(24.dp))
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(currentArtworkUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "Current Track Artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
