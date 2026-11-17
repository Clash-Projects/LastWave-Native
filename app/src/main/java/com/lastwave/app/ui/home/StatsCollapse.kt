package com.lastwave.app.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Height of the condensed stats row. */
internal val StatsCompactHeight: Dp = 68.dp

/**
 * How far the stats card has condensed, in pixels of height given up.
 * Scrolling the list up spends it before the list moves; scrolling down
 * gives it back once the list is at its top (Material's exit-until-collapsed).
 */
@Stable
internal class StatsHeaderCollapse(private val scope: CoroutineScope) {
    var rangePx by mutableFloatStateOf(0f)
        private set
    var offsetPx by mutableFloatStateOf(0f)
        private set

    /** 0 = full card, 1 = compact row. */
    val progress: Float get() = if (rangePx <= 0f) 0f else (offsetPx / rangePx).coerceIn(0f, 1f)

    private var settleJob: Job? = null

    fun updateRange(range: Float) {
        if (range == rangePx) return
        rangePx = range
        offsetPx = offsetPx.coerceIn(0f, range)
    }

    /** Applies a scroll delta (negative = condense) and returns the part it used. */
    fun consume(delta: Float): Float {
        settleJob?.cancel()
        val target = (offsetPx - delta).coerceIn(0f, rangePx)
        val used = offsetPx - target
        offsetPx = target
        return used
    }

    /** A gesture left the card half way: finish toward the nearer state. */
    fun settle() {
        val p = progress
        if (p <= 0f || p >= 1f) return
        animateTo(if (p > 0.5f) rangePx else 0f)
    }

    fun expand() = animateTo(0f)

    private fun animateTo(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(offsetPx, target, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ ->
                offsetPx = value
            }
        }
    }

    fun connection(listState: LazyListState): NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            // Down only once the list is back at its top; before that the list
            // scrolls and the card stays condensed. Runs before pull-to-refresh,
            // so a pull first opens the card and only then refreshes.
            val used = when {
                dy < 0f -> consume(dy)
                dy > 0f && !listState.canScrollBackward -> consume(dy)
                else -> 0f
            }
            return Offset(0f, used)
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            settle()
            return Velocity.Zero
        }
    }
}

@Composable
internal fun rememberStatsHeaderCollapse(): StatsHeaderCollapse {
    val scope = rememberCoroutineScope()
    return remember { StatsHeaderCollapse(scope) }
}

/**
 * The full stats card condensing into [compact] as [state] advances. The box
 * shrinks with the scroll while the card lifts, scales and fades out and the
 * compact row fades in at its bottom edge; all of it is read in layout and
 * draw, so scrolling never recomposes the card. Dragging on the header scrolls
 * too, so the whole screen moves as one surface.
 */
@Composable
internal fun CollapsingStatsHeader(
    state: StatsHeaderCollapse,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    expanded: @Composable () -> Unit,
    compact: @Composable () -> Unit,
) {
    // The compact row only exists once it shows, so its buttons never catch
    // taps meant for the full card underneath.
    val compactShown by remember { derivedStateOf { state.progress > 0.5f } }
    val dragState = rememberDraggableState { delta ->
        if (delta < 0f) {
            val used = state.consume(delta)
            listState.dispatchRawDelta(-(delta - used))
        } else {
            val listUsed = -listState.dispatchRawDelta(-delta)
            state.consume(delta - listUsed)
        }
    }
    Layout(
        content = {
            Box(
                Modifier.graphicsLayer {
                    val p = state.progress
                    alpha = (1f - p * 1.6f).coerceIn(0f, 1f)
                    val scale = 1f - 0.06f * p
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                },
            ) { expanded() }
            Box(
                Modifier.graphicsLayer {
                    val p = state.progress
                    alpha = ((p - 0.5f) / 0.5f).coerceIn(0f, 1f)
                    val scale = 0.94f + 0.06f * p
                    scaleX = scale
                    scaleY = scale
                },
            ) { if (compactShown) compact() }
        },
        modifier = modifier
            .clipToBounds()
            .draggable(dragState, Orientation.Vertical, onDragStopped = { state.settle() }),
    ) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val full = measurables[0].measure(loose)
        val compactHeight = StatsCompactHeight.roundToPx()
        val compactRow = measurables[1].measure(loose.copy(minHeight = compactHeight, maxHeight = compactHeight))
        state.updateRange((full.height - compactHeight).coerceAtLeast(0).toFloat())
        val height = (full.height - state.offsetPx).roundToInt().coerceIn(minOf(compactHeight, full.height), full.height)
        layout(constraints.maxWidth, height) {
            // The card drifts up at half the scroll speed while it fades: a
            // gentle parallax instead of being cut off by the shrinking box.
            full.place(0, -(state.offsetPx * 0.5f).roundToInt())
            compactRow.place(0, height - compactHeight)
        }
    }
}
