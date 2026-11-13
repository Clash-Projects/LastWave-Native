package com.lastwave.app.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Decides compact vs expanded chrome from list scroll deltas. Direction must hold for
 * [thresholdPx] before the state flips, so fling tails and finger jitter never toggle it.
 */
internal class ScrollCollapseTracker(private val thresholdPx: Float) {
    var collapsed = false
        private set
    private var travel = 0f

    /**
     * [consumedY] is what the list (and anything nested in it) scrolled, negative towards
     * the list end. [availableY] is what nobody consumed: positive means the list is
     * already at its top, which always expands.
     */
    fun onScroll(consumedY: Float, availableY: Float): Boolean {
        if (availableY > 0f) return reset()
        if (consumedY == 0f) return collapsed
        if (travel * consumedY < 0f) travel = 0f
        travel += consumedY
        if (abs(travel) >= thresholdPx) {
            collapsed = travel < 0f
            travel = 0f
        }
        return collapsed
    }

    fun reset(): Boolean {
        travel = 0f
        collapsed = false
        return false
    }
}

/**
 * Scroll-driven collapse of the nav bar + mini player. Everything reads the single
 * [progress] (0 expanded, 1 compact) from layout/draw lambdas, never in composition.
 */
@Stable
internal class ChromeCollapseState(thresholdPx: Float) {
    private val tracker = ScrollCollapseTracker(thresholdPx)
    internal val animatable = Animatable(0f)
    internal var collapsed by mutableStateOf(false)
        private set

    val progress: () -> Float = { animatable.value }

    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            collapsed = tracker.onScroll(consumed.y, available.y)
            return Offset.Zero
        }
    }

    fun expand() {
        collapsed = tracker.reset()
    }
}

internal val LocalChromeCollapse = staticCompositionLocalOf<ChromeCollapseState?> { null }

// Critically damped: interrupting mid-way keeps the current velocity with no overshoot.
// The fine threshold stops the last frame snapping a visible fraction of a dp.
private val ChromeCollapseSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = 0.001f,
)

/** [enabled] is false off the main tabs; the chrome then snaps back to expanded. */
@Composable
internal fun rememberChromeCollapseState(enabled: Boolean): ChromeCollapseState {
    val thresholdPx = with(LocalDensity.current) { 28.dp.toPx() }
    val state = remember { ChromeCollapseState(thresholdPx) }
    LaunchedEffect(state, enabled) {
        state.expand()
        if (!enabled) {
            state.animatable.snapTo(0f)
            return@LaunchedEffect
        }
        snapshotFlow { state.collapsed }.collect { collapsed ->
            // A new animateTo preempts the running one and starts from its velocity.
            launch { state.animatable.animateTo(if (collapsed) 1f else 0f, ChromeCollapseSpec) }
        }
    }
    return state
}

// Compact row geometry, shared by the nav pill and the mini player that docks beside it.
internal val CompactPillSize = 56.dp
internal val CompactRowStartMargin = 14.dp
internal val CompactRowBottomMargin = 12.dp
internal val CompactRowGap = 8.dp

private fun navBarFade(progress: Float) = (progress / 0.5f).coerceIn(0f, 1f)

/**
 * The full bar shrinks towards its start edge and fades out over the first half.
 * Once invisible it is left unplaced, so the compact player row under it takes the touches.
 */
internal fun Modifier.collapsingNavBar(progress: () -> Float): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val pivotX = if (layoutDirection == LayoutDirection.Ltr) 0f else 1f
        layout(placeable.width, placeable.height) {
            if (navBarFade(progress()) < 1f) {
                placeable.placeWithLayer(0, 0) {
                    val t = navBarFade(progress())
                    alpha = 1f - t
                    scaleX = lerp(1f, 0.7f, t)
                    scaleY = scaleX
                    transformOrigin = TransformOrigin(pivotX, 1f)
                }
            }
        }
    }

/** Positions and sizes the compact nav pill; it grows in as the bar fades and is unplaced while expanded. */
internal fun Modifier.collapsedNavPill(progress: () -> Float): Modifier = this
    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
    .padding(start = CompactRowStartMargin, bottom = CompactRowBottomMargin)
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            if (progress() > 0f) {
                placeable.placeWithLayer(0, 0) {
                    val t = ((progress() - 0.3f) / 0.5f).coerceIn(0f, 1f)
                    alpha = t
                    scaleX = lerp(0.6f, 1f, t)
                    scaleY = scaleX
                }
            }
        }
    }
    .size(CompactPillSize)

/** Square box whose side follows [progress]; measured in the layout phase only. */
internal fun Modifier.collapseSquare(expanded: Dp, compact: Dp, progress: () -> Float): Modifier =
    layout { measurable, _ ->
        val side = lerp(expanded.toPx(), compact.toPx(), progress()).roundToInt()
        val placeable = measurable.measure(Constraints.fixed(side, side))
        layout(side, side) { placeable.place(0, 0) }
    }

/** Fixed height that follows [progress]. */
internal fun Modifier.collapseHeight(expanded: Dp, compact: Dp, progress: () -> Float): Modifier =
    layout { measurable, constraints ->
        val height = lerp(expanded.toPx(), compact.toPx(), progress()).roundToInt()
        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
        layout(placeable.width, height) { placeable.place(0, 0) }
    }

/** Equal top and bottom padding that follows [progress]. */
internal fun Modifier.collapseVerticalPadding(expanded: Dp, compact: Dp, progress: () -> Float): Modifier =
    layout { measurable, constraints ->
        val pad = lerp(expanded.toPx(), compact.toPx(), progress()).roundToInt()
        val placeable = measurable.measure(constraints.offset(vertical = -2 * pad))
        layout(placeable.width, constraints.constrainHeight(placeable.height + 2 * pad)) {
            placeable.place(0, pad)
        }
    }
