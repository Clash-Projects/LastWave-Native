package com.lastwave.app.ui.tv.focus

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Hardware-accelerated modifier that applies a smooth D-pad focus highlight:
 * - Direct GPU layer scaling (1.05f) without recomposition
 * - Z-index elevation so focused items never clip beneath neighbors
 * - High-contrast crisp border with smooth transition
 * - Composable factory avoiding composed {} overhead for low-RAM TV devices
 */
@Composable
fun Modifier.tvFocusHighlight(
    scale: Float = 1.05f,
    borderColor: Color? = null,
    borderWidth: Dp = 2.5.dp,
    shape: Shape = RoundedCornerShape(16.dp),
    onFocusChange: ((Boolean) -> Unit)? = null,
): Modifier {
    var isFocused by remember { mutableStateOf(false) }
    val activeBorderColor = borderColor ?: MaterialTheme.colorScheme.primary

    val animatedScale by animateFloatAsState(
        targetValue = if (isFocused) scale else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "tvFocusScale",
    )

    val animatedBorderColor by animateColorAsState(
        targetValue = if (isFocused) activeBorderColor else Color.Transparent,
        animationSpec = tween(durationMillis = 150),
        label = "tvFocusBorder",
    )

    return this
        .onFocusChanged {
            if (isFocused != it.isFocused) {
                isFocused = it.isFocused
                onFocusChange?.invoke(it.isFocused)
            }
        }
        .zIndex(if (isFocused) 10f else 0f)
        .graphicsLayer {
            scaleX = animatedScale
            scaleY = animatedScale
        }
        .border(
            width = borderWidth,
            color = animatedBorderColor,
            shape = shape,
        )
}

/**
 * TV helper that ensures focused items inside lazy layouts (LazyColumn, LazyRow)
 * align smoothly at a predictable viewport fraction (e.g. 20-30% from the container leading edge)
 * rather than jumping abruptly at the edges.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PositionFocusedItemInLazyLayout(
    parentFraction: Float = 0.3f,
    childFraction: Float = 0.0f,
    content: @Composable () -> Unit,
) {
    val bringIntoViewSpec = remember(parentFraction, childFraction) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float {
                val targetOffset = parentFraction * containerSize - (childFraction * size)
                return offset - targetOffset
            }
        }
    }

    CompositionLocalProvider(
        LocalBringIntoViewSpec provides bringIntoViewSpec,
        content = content,
    )
}
