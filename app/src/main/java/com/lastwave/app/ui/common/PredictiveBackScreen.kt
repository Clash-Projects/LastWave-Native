package com.lastwave.app.ui.common

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/**
 * Wraps a pushed screen (Settings / Search / Discover / Genres — any
 * NavHost destination reached by navigating on top of MainShell) with
 * Android's Predictive Back visuals: as the user drags the system back
 * gesture in from the edge, this screen scales down and its corners round
 * off — the same "lifting off the stack" treatment modern Google apps use.
 * Releasing past the gesture's commit point finishes the animation and
 * calls [onBack] (pop the stack); releasing early cancels and the screen
 * springs back to full size.
 *
 * Anchored to the edge the gesture actually started from, not the screen's
 * center: BackEventCompat reports which edge (LEFT/RIGHT) and how far down
 * the screen (touchY) the drag began, and the shrink's pivot is set to
 * that exact point via TransformOrigin. That's what makes it read as
 * "shrinking away from the corner/edge you dragged from" instead of
 * uniformly shrinking toward the middle of the screen — the same
 * touch-anchored feel the system's own predictive back uses, and it also
 * nudges the screen a few dp further toward that edge as it shrinks, on
 * top of the scale, for the same reason.
 *
 * This animates the CURRENT (exiting) screen only — since NavHost here
 * only keeps the active back-stack entry composed, there's no destination
 * screen underneath to reveal/blur during the gesture. A blurred preview
 * of the previous screen would need it kept composed alongside this one
 * (e.g. via a two-pane Crossfade or navigation-compose 2.8+'s built-in
 * predictive-back transitions) — noted as a follow-up if that fuller
 * effect is wanted; this still gives real, system-driven progress-based
 * animation rather than a canned pop transition.
 *
 * Falls back gracefully pre-Android 13 / wherever predictive back isn't
 * available: PredictiveBackHandler's Flow simply completes without
 * meaningful per-frame progress, so [onBack] still fires correctly — just
 * without the scale/round animation playing first.
 */
@Composable
fun PredictiveBackScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val gestureScope = rememberCoroutineScope()
    var resetJob by remember { mutableStateOf<Job?>(null) }

    // Which edge the gesture started from (BackEventCompat.EDGE_LEFT/RIGHT)
    // and how far down the screen, as a 0..1 fraction of height — together
    // these place the shrink's pivot at the actual corner the finger is
    // dragging from, instead of a fixed corner or the screen center.
    var edge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    var touchYFraction by remember { mutableFloatStateOf(0.5f) }
    var containerHeightPx by remember { mutableFloatStateOf(1f) }

    // This is the ONLY back handler for the wrapped screen: every pushed
    // destination must NOT register its own BackHandler on top of this one.
    // Two handlers for one press made back behavior order-dependent (double
    // pops that skipped destinations, or presses that appeared to do
    // nothing) — see the detail screens, whose toolbar buttons still pop
    // directly while the gesture path lives here.
    PredictiveBackHandler(enabled = enabled) { backEvents ->
        resetJob?.cancelAndJoin()
        resetJob = null
        try {
            backEvents.collect { event ->
                edge = event.swipeEdge
                touchYFraction = (event.touchY / containerHeightPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                progress.snapTo(event.progress)
            }
            // Gesture committed (or a legacy button press, whose flow simply
            // completes with no per-frame events) — reset the visuals, then
            // pop exactly once.
            progress.snapTo(0f)
            onBack()
        } finally {
            // Gesture cancelled mid-drag: the handler coroutine is cancelled
            // here, so the spring-back runs in the screen's own scope instead
            // of suspending inside a cancelled one.
            if (progress.value > 0f) {
                resetJob = gestureScope.launch {
                    progress.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                }
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .then(if (backgroundColor != Color.Transparent) Modifier.background(backgroundColor) else Modifier)
            .onGloballyPositioned { coords -> containerHeightPx = coords.size.height.toFloat() },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = progress.value
                    val pivotX = if (edge == BackEventCompat.EDGE_RIGHT) 1f else 0f
                    val edgeSign = if (edge == BackEventCompat.EDGE_RIGHT) 1f else -1f
                    val scale = 1f - 0.12f * p
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(pivotX, touchYFraction)
                    translationX = edgeSign * 28.dp.toPx() * p
                    alpha = 1f - 0.25f * p
                    shape = RoundedCornerShape(lerp(0.dp, 32.dp, p))
                    // Clipping a full-screen LazyColumn at rest forces extra GPU
                    // work on every frame. It is only visually needed while a
                    // predictive-back gesture has actually rounded the corners.
                    clip = p > 0f
                    shadowElevation = if (p > 0f) 24f else 0f
                },
        ) {
            content()
        }
    }
}
