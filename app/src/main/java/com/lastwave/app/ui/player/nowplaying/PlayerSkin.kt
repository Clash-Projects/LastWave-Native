package com.lastwave.app.ui.player.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.player.hapticClick
import com.lastwave.app.ui.theme.LiquidGlassPreset
import com.lastwave.app.ui.theme.LiquidGlassSurface
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor

/**
 * The two looks of the new player. Glass follows the app-wide Liquid Glass
 * setting; on devices that can't refract, the glass helpers already fall back to
 * the same translucent fills, so no third state is needed here.
 */
internal enum class PlayerSkin { Material, Glass }

@Composable
internal fun currentPlayerSkin(): PlayerSkin =
    if (LocalLiquidGlass.current) PlayerSkin.Glass else PlayerSkin.Material

/** Colour roles for player chrome, taken from the song's scheme. */
@Immutable
internal data class PlayerSkinColors(
    val background: Color,
    val accent: Color,
    val content: Color,
    val contentMuted: Color,
    val container: Color,
    val activeContainer: Color,
    val onActive: Color,
    val playContainer: Color,
    val onPlay: Color,
)

/**
 * Material uses the scheme's tonal roles directly. Glass keeps white-on-veil
 * chrome over the blurred cover and takes only the accents from the scheme.
 */
internal fun playerSkinColors(skin: PlayerSkin, scheme: ColorScheme): PlayerSkinColors = when (skin) {
    PlayerSkin.Material -> PlayerSkinColors(
        background = scheme.surface,
        accent = scheme.primary,
        content = scheme.onSurface,
        contentMuted = scheme.onSurfaceVariant,
        container = scheme.surfaceContainerHigh,
        activeContainer = scheme.secondaryContainer,
        onActive = scheme.onSecondaryContainer,
        playContainer = scheme.primary,
        onPlay = scheme.onPrimary,
    )
    PlayerSkin.Glass -> PlayerSkinColors(
        background = scheme.surfaceContainerLowest,
        accent = scheme.primary,
        content = Color.White.copy(alpha = 0.94f),
        contentMuted = Color.White.copy(alpha = 0.72f),
        container = Color.White.copy(alpha = 0.14f),
        activeContainer = Color.White.copy(alpha = 0.26f),
        onActive = scheme.primary,
        playContainer = Color.White.copy(alpha = 0.18f),
        onPlay = Color.White,
    )
}

/** Every role eases to the next song's colour instead of snapping on skip. */
@Composable
internal fun animatedPlayerSkinColors(skin: PlayerSkin, scheme: ColorScheme): PlayerSkinColors {
    val target = remember(skin, scheme) { playerSkinColors(skin, scheme) }
    return PlayerSkinColors(
        background = animateRole(target.background),
        accent = animateRole(target.accent),
        content = animateRole(target.content),
        contentMuted = animateRole(target.contentMuted),
        container = animateRole(target.container),
        activeContainer = animateRole(target.activeContainer),
        onActive = animateRole(target.onActive),
        playContainer = animateRole(target.playContainer),
        onPlay = animateRole(target.onPlay),
    )
}

@Composable
private fun animateRole(target: Color): Color =
    animateColorAsState(target, tween(SONG_COLOR_FADE_MS), label = "playerRole").value

private const val SONG_COLOR_FADE_MS = 700

/**
 * One button for both skins: refracting glass chrome under [PlayerSkin.Glass],
 * a plain tonal surface under [PlayerSkin.Material]. Press scale and haptic are
 * shared so both skins feel the same under the thumb.
 */
@Composable
internal fun SkinButton(
    skin: PlayerSkin,
    onClick: () -> Unit,
    container: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    glassPreset: LiquidGlassPreset = LiquidGlassPreset.PlayerControls,
    haptic: HapticFeedbackType = HapticFeedbackType.ContextClick,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = ExpressiveMotion.spatialSpring(),
        label = "skinButtonPress",
    )
    val glass = skin == PlayerSkin.Glass
    LiquidGlassSurface(
        onClick = hapticClick(haptic, onClick),
        glassModifier = if (glass) {
            Modifier.liquidGlassChrome(shape, true, glassPreset, interactionSource = interaction)
        } else {
            Modifier
        },
        interactionSource = interaction,
        shape = shape,
        color = if (glass) liquidGlassContainerColor(container) else container,
        contentColor = contentColor,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        content = content,
    )
}
