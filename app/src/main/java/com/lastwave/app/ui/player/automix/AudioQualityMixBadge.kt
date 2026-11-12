package com.lastwave.app.ui.player.automix

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lastwave.app.playback.automix.api.BlendPhase
import com.lastwave.app.playback.automix.api.DeckBlendTelemetry

@Composable
fun AudioQualityMixBadge(
    telemetry: DeckBlendTelemetry,
    standardQualityBadgeText: String,
    modifier: Modifier = Modifier,
) {
    val pillShape = RoundedCornerShape(12.dp)

    AnimatedContent(
        targetState = telemetry.phase,
        transitionSpec = {
            fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) togetherWith
                fadeOut(animationSpec = tween(250, easing = FastOutSlowInEasing))
        },
        label = "AudioQualityMixBadgeMorph",
        modifier = modifier,
    ) { phase ->
        when (phase) {
            BlendPhase.BLENDING, BlendPhase.LOOP_LOCKED -> {
                val bpmText = telemetry.syncedBpm?.let { "${it.toInt()} BPM" } ?: ""
                val mixingText = buildString {
                    append("AutoMix")
                    if (bpmText.isNotEmpty()) append(" \u2022 ").append(bpmText)
                }

                Box(
                    modifier = Modifier
                        .clip(pillShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = mixingText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }

            BlendPhase.MIX_COMPLETED -> {
                val bpmText = telemetry.syncedBpm?.let { " \u2022 ${it.toInt()} BPM" } ?: ""
                Box(
                    modifier = Modifier
                        .clip(pillShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Mixed ✓$bpmText",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }

            else -> {
                // Normal standard Audio Quality Badge
                if (standardQualityBadgeText.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(pillShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = standardQualityBadgeText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AnimatedWaveformBars() {
    val infiniteTransition = rememberInfiniteTransition(label = "WaveformBarTransition")

    val bar1Height by infiniteTransition.animateFloat(
        initialValue = 3f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bar1Height",
    )
    val bar2Height by infiniteTransition.animateFloat(
        initialValue = 11f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(350, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bar2Height",
    )
    val bar3Height by infiniteTransition.animateFloat(
        initialValue = 5f,
        targetValue = 13f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bar3Height",
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(14.dp),
    ) {
        val barColor = MaterialTheme.colorScheme.primary
        Box(Modifier.width(2.dp).height(bar1Height.dp).background(barColor, RoundedCornerShape(1.dp)))
        Box(Modifier.width(2.dp).height(bar2Height.dp).background(barColor, RoundedCornerShape(1.dp)))
        Box(Modifier.width(2.dp).height(bar3Height.dp).background(barColor, RoundedCornerShape(1.dp)))
    }
}
