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
import androidx.compose.ui.graphics.graphicsLayer

/** Shared, restrained Material 3 Expressive motion language for the app. */
object ExpressiveMotion {
    const val Quick = 160
    const val Standard = 300
    const val Emphasized = 440

    // Spatial springs: overshoot and bounce for layout and scale transitions
    fun <T> spatialFast() = spring<T>(
        dampingRatio = 0.75f,
        stiffness = Spring.StiffnessMedium, // 1500f: immediate, tactile button feedback (~180ms)
    )

    fun <T> spatialDefault() = spring<T>(
        dampingRatio = 0.8f,
        stiffness = 650f, // Snappy & fluid Material 3 expressive motion (~280ms)
    )

    fun <T> spatialSlow() = spring<T>(
        dampingRatio = 0.85f,
        stiffness = Spring.StiffnessMediumLow, // 400f
    )

    // Legacy alias
    fun <T> spatialSpring() = spatialDefault<T>()

    // Effects springs: no overshoot (for alpha, color)
    fun <T> effectsFast() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium, // 1500f
    )

    fun <T> effectsDefault() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 700f, // Clean fade without sluggish trailing (~250ms)
    )

    fun <T> effectsSlow() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow, // 400f
    )

    // Legacy alias
    fun <T> smoothSpring() = effectsDefault<T>()

    // Screen transitions in the same language as the player open/close
    // morph: the incoming screen grows into place from slightly smaller while
    // the one it covers recedes a touch; going back reverses it. A short
    // horizontal drift keeps the forward/back direction readable.
    fun forwardEnter(): EnterTransition =
        fadeIn(effectsDefault()) +
            scaleIn(spatialDefault(), initialScale = 0.92f) +
            slideInHorizontally(spatialDefault()) { it / 24 }

    fun forwardExit(): ExitTransition =
        fadeOut(effectsDefault()) +
            scaleOut(effectsDefault(), targetScale = 0.95f)

    fun backEnter(): EnterTransition =
        fadeIn(effectsDefault()) +
            scaleIn(spatialDefault(), initialScale = 0.95f)

    fun backExit(): ExitTransition =
        fadeOut(effectsFast()) +
            scaleOut(effectsDefault(), targetScale = 0.92f) +
            slideOutHorizontally(spatialDefault()) { it / 24 }
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
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
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
