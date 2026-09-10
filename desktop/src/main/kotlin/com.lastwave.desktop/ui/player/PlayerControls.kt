package com.lastwave.desktop.ui.player

import com.lastwave.desktop.ui.main.seekBar
import com.lastwave.desktop.ui.main.PlayerButton
import compose
import kotlin.random.Random
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.gesture.drag.DragInteraction
import androidx.compose.foundation.gesture.drag.dragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.paint.PaintStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.px
import androidx.compose.ui.unit.size

@Composable
fun PlayerControls(
    currentProgress: Float,
    targetProgress: Float?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Float -> Unit),
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit
) {
    val dragInteraction = rememberDragInteraction()
    val sliderValue by animateScrollingOrDragAsProgress(
        targetValue = currentProgress,
        dragInteraction = dragInteraction
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Album art
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(
                    Brush.radialGradient(
                        center = androidx.compose.ui.graphics.Offset(0.5, 0.5),
                        colors = listOf(
                            Color(0xFFBB86FC),
                            Color(0xFF03DAC6)
                        )
                    )
                )
        ) {
            Image(
                painter = rememberImagePainter(
                    data = null,
                    scaling = ImageScaling.Fit
                ),
                contentDescription = "Album art",
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(16.dp))

        // Track info
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Midnight City",
                style = Typography.h6,
                color = Color(0xFFFFFFFF),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(2.dp))

            Text(
                text = "M83",
                color = Color(0xFFB0B0C4),
                style = Caption
            )
        }

        Spacer(Modifier.height(24.dp))

        // Progress slider
        seekBar(
            progress = currentProgress,
            onSeek = onSeek
        )

        Spacer(Modifier.height(16.dp))

        // Playback controls row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerButton(
                icon = "skip_previous",
                onClick = onSeekBackward
            )
            Spacer(Modifier.width(24.dp))
            PlayerButton(
                icon = if (isPlaying) "pause" else "play_arrow",
                onClick = onPlayPause
            )
            Spacer(Modifier.width(24.dp))
            PlayerButton(
                icon = "skip_next",
                onClick = onSeekForward
            )
        }

        Spacer(Modifier.height(16.dp))

        // Progress time labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "0:00",
                color = Color(0xFF7F8C8D),
                style = Caption
            )

            Text(
                text = formatDuration((currentProgress * 200).toInt()),
                color = Color(0xFF7F8C8D),
                style = Caption
            )
        }
    }
}

@Composable
fun formatDuration(minutes: Int): String {
    val mins = minutes / 60
    val secs = minutes % 60
    return "$mins:${secs % 60}%02d".format()
}