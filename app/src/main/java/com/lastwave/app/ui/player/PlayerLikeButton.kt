package com.lastwave.app.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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

@Composable
internal fun PlayerLikeButton(liked: Boolean, onClick: () -> Unit, size: Dp) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.9f else 1f, ExpressiveMotion.spatialFast(), label = "heartPress",
    )
    val fill by animateFloatAsState(
        if (liked) 1f else 0f, ExpressiveMotion.effectsDefault(), label = "heartFill",
    )
    val background by animateColorAsState(
        if (liked) MaterialTheme.colorScheme.primaryContainer else Color.White.copy(alpha = 0.14f),
        ExpressiveMotion.effectsDefault(), label = "heartBackground",
    )
    val foreground by animateColorAsState(
        if (liked) MaterialTheme.colorScheme.onPrimaryContainer else Color.White.copy(alpha = 0.85f),
        ExpressiveMotion.effectsDefault(), label = "heartForeground",
    )
    LiquidGlassSurface(
        glassModifier = Modifier.liquidGlassChrome(CircleShape, LocalLiquidGlass.current,
            LiquidGlassPreset.PlayerControls, interactionSource = interaction),
        onClick = onClick,
        interactionSource = interaction,
        shape = CircleShape,
        color = liquidGlassContainerColor(background),
        contentColor = foreground,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.size(size).semantics {
            selected = liked
            contentDescription = if (liked) "Unlike song" else "Like song"
        }
            .graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                modifier = Modifier.size(size * 0.52f).graphicsLayer { alpha = 1f - fill })
            Icon(Icons.Filled.Favorite, contentDescription = null,
                modifier = Modifier.size(size * 0.52f).graphicsLayer {
                    alpha = fill
                    scaleX = 0.8f + 0.2f * fill
                    scaleY = scaleX
                })
        }
    }
}
