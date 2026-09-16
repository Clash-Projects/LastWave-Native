package com.lastwave.app.ui.common

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale

/** Shared, restrained Material 3 Expressive motion language for the app. */
object ExpressiveMotion {
    const val Quick = 160
    const val Standard = 300
    const val Emphasized = 440

    // Spatial springs: overshoot and bounce for layout and scale transitions
    fun <T> spatialFast() = spring<T>(
        dampingRatio = 0.5f, // high bounce
        stiffness = Spring.StiffnessMediumLow,
    )

    fun <T> spatialDefault() = spring<T>(
        dampingRatio = 0.65f, // moderate bounce
        stiffness = Spring.StiffnessLow,
    )

    fun <T> spatialSlow() = spring<T>(
        dampingRatio = 0.7f, // gentle bounce
        stiffness = Spring.StiffnessVeryLow,
    )

    // Legacy alias
    fun <T> spatialSpring() = spatialDefault<T>()

    // Effects springs: no overshoot (for alpha, color)
    fun <T> effectsFast() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    fun <T> effectsDefault() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow,
    )

    fun <T> effectsSlow() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessVeryLow,
    )

    // Legacy alias
    fun <T> smoothSpring() = effectsDefault<T>()

    fun forwardEnter(): EnterTransition =
        fadeIn(effectsDefault()) +
            slideInHorizontally(spatialDefault()) { it / 10 } +
            scaleIn(spatialFast(), initialScale = 0.96f)

    fun forwardExit(): ExitTransition =
        fadeOut(effectsFast()) +
            scaleOut(effectsDefault(), targetScale = 0.985f)

    fun backEnter(): EnterTransition =
        fadeIn(effectsDefault()) +
            slideInHorizontally(spatialDefault()) { -it / 12 } +
            scaleIn(spatialFast(), initialScale = 0.96f)

    fun backExit(): ExitTransition =
        fadeOut(effectsFast()) +
            slideOutHorizontally(spatialDefault()) { it / 12 } +
            scaleOut(effectsDefault(), targetScale = 0.985f)
}

/**
 * Tactile spring press feedback modifier conforming to Material 3 Expressive.
 * Subtly squishes the component when held and springs back dynamically on release.
 */
@Composable
fun Modifier.expressivePressScale(
    interactionSource: MutableInteractionSource,
    targetScale: Float = 0.96f,
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) targetScale else 1f,
        animationSpec = ExpressiveMotion.spatialFast(),
        label = "expressivePressScale",
    )
    return this.scale(scale)
}

/**
 * Clickable surface with expressive spring-based bounce feedback.
 */
@Composable
fun Modifier.expressiveBounceClickable(
    enabled: Boolean = true,
    targetScale: Float = 0.96f,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this
        .expressivePressScale(interactionSource, targetScale)
        .clickable(
            interactionSource = interactionSource,
            indication = ripple(),
            enabled = enabled,
            onClick = onClick,
        )
}
