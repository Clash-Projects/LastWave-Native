package com.lastwave.app.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.theme.LiquidGlassPreset
import com.lastwave.app.ui.theme.LiquidGlassSurface
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor

private val LikedColor = Color(0xFFE91E63) // Vibrant pink-red
private val UnlikedColor = Color.White.copy(alpha = 0.85f)
private val LikedBg = Color.White.copy(alpha = 0.92f)
private val UnlikedBg = Color.White.copy(alpha = 0.14f)

@Composable
internal fun PlayerLikeButton(
    liked: Boolean,
    onClick: () -> Unit,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = ExpressiveMotion.spatialFast(),
        label = "heartPress",
    )

    // Spring pop on like transition:
    val heartPopScale = remember { Animatable(1f) }
    LaunchedEffect(liked) {
        if (liked) {
            heartPopScale.snapTo(0.35f)
            heartPopScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.45f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        } else {
            heartPopScale.snapTo(1f)
        }
    }

    val fillProgress by animateFloatAsState(
        targetValue = if (liked) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "heartFill",
    )

    val background by animateColorAsState(
        targetValue = if (liked) LikedBg else UnlikedBg,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "heartBackground",
    )

    val foreground by animateColorAsState(
        targetValue = if (liked) LikedColor else UnlikedColor,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "heartForeground",
    )

    LiquidGlassSurface(
        glassModifier = Modifier.liquidGlassChrome(
            CircleShape,
            LocalLiquidGlass.current,
            LiquidGlassPreset.PlayerControls,
            interactionSource = interaction,
        ),
        onClick = onClick,
        interactionSource = interaction,
        shape = CircleShape,
        color = liquidGlassContainerColor(background),
        contentColor = foreground,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier
            .size(size)
            .semantics {
                selected = liked
                contentDescription = if (liked) "Unlike song" else "Like song"
            }
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            },
    ) {
        val iconSize = size * 0.52f
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (fillProgress < 1f) {
                Icon(
                    imageVector = Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    tint = UnlikedColor,
                    modifier = Modifier
                        .size(iconSize)
                        .graphicsLayer {
                            alpha = (1f - fillProgress).coerceIn(0f, 1f)
                            val s = (1f - 0.2f * fillProgress).coerceIn(0.8f, 1f)
                            scaleX = s
                            scaleY = s
                        },
                )
            }
            if (fillProgress > 0f) {
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = LikedColor,
                    modifier = Modifier
                        .size(iconSize)
                        .graphicsLayer {
                            alpha = fillProgress.coerceIn(0f, 1f)
                            val s = if (liked) heartPopScale.value else (0.8f + 0.2f * fillProgress)
                            scaleX = s
                            scaleY = s
                        },
                )
            }
        }
    }
}
