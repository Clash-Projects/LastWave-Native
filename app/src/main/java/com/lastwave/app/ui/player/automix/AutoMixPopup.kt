package com.lastwave.app.ui.player.automix

import android.os.SystemClock
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.PlaybackProgressState
import com.lastwave.app.playback.automix.api.BlendPhase
import com.lastwave.app.playback.automix.api.DeckBlendTelemetry
import com.lastwave.app.playback.automix.api.MixView
import com.lastwave.app.playback.automix.api.TransitionStyle
import com.lastwave.app.ui.common.ExpressiveMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive

private const val POST_ROLL_MS = 3_000L

internal fun PlayableTrack.autoMixIdentity() = "$videoId\u0000$title\u0000$artist"

@Stable
internal class AutoMixPopupState {
    var isOpen by mutableStateOf(false)
        private set
    var badgeBounds by mutableStateOf(Rect.Zero)
    var completedTelemetry by mutableStateOf<DeckBlendTelemetry?>(null)
    var completedTrack by mutableStateOf<String?>(null)
    var completedAtMs by mutableLongStateOf(0L)
    var observedCompletion by mutableStateOf<MixView?>(null)

    fun shownTelemetry(live: DeckBlendTelemetry, trackKey: String): DeckBlendTelemetry {
        val completed = completedTelemetry
        return if (completed != null && completedTrack == trackKey &&
            live.phase != BlendPhase.BLENDING &&
            SystemClock.uptimeMillis() - completedAtMs < POST_ROLL_MS
        ) completed else live
    }

    fun canOpen(live: DeckBlendTelemetry, progress: PlaybackProgressState, trackKey: String): Boolean {
        val shown = shownTelemetry(live, trackKey)
        val view = shown.view ?: return false
        return when (shown.phase) {
            BlendPhase.ARMED, BlendPhase.PRE_ANALYZING -> {
                val duration = progress.durationMs.takeIf { it > 0L } ?: view.outgoing.durationMs
                duration > 0L && duration - progress.positionMs in 0L..25_000L
            }
            BlendPhase.BLENDING -> {
                val outgoingPosition = view.cueOutMs + shown.clampedProgress * view.overlapMs.toDouble()
                view.outgoing.durationMs - outgoingPosition.toLong() in 0L..25_000L
            }
            BlendPhase.MIX_COMPLETED -> shown === completedTelemetry ||
                (observedCompletion != view && trackKey.endsWith("\u0000${view.incoming.title}\u0000${view.incoming.artist}"))
            BlendPhase.IDLE -> false
        }
    }

    fun open(live: DeckBlendTelemetry, progress: PlaybackProgressState, trackKey: String): Boolean {
        if (!canOpen(live, progress, trackKey)) return false
        isOpen = true
        return true
    }

    fun dismiss() { isOpen = false }

    fun clearCompleted() {
        completedTelemetry = null
        completedTrack = null
        completedAtMs = 0L
    }
}

@Composable
internal fun AutoMixPopupHost(
    ui: AutoMixPopupState,
    telemetry: DeckBlendTelemetry,
    progressState: StateFlow<PlaybackProgressState>,
    trackKey: String,
    isPlayerOpen: Boolean,
) {
    LaunchedEffect(telemetry.phase, telemetry.view, trackKey) {
        if (telemetry.phase == BlendPhase.BLENDING) {
            ui.observedCompletion = null
            ui.clearCompleted()
        }
        if (ui.completedTrack != null && ui.completedTrack != trackKey) ui.clearCompleted()
        if (telemetry.phase == BlendPhase.MIX_COMPLETED && telemetry.view != null &&
            ui.observedCompletion != telemetry.view &&
            trackKey.endsWith("\u0000${telemetry.view.incoming.title}\u0000${telemetry.view.incoming.artist}")
        ) {
            // Playback discards its completed view after 1.5 s; the UI keeps its own post-roll.
            val view = telemetry.view
            val incomingEnd = view.cueInMs + (view.overlapMs * view.tempoRatio).toLong()
            val elapsed = (progressState.value.positionMs - incomingEnd).coerceIn(0L, POST_ROLL_MS)
            ui.observedCompletion = view
            ui.completedTelemetry = telemetry
            ui.completedTrack = trackKey
            ui.completedAtMs = SystemClock.uptimeMillis() - elapsed
        }
    }
    LaunchedEffect(ui.completedAtMs) {
        if (ui.completedAtMs > 0L) {
            delay((POST_ROLL_MS - (SystemClock.uptimeMillis() - ui.completedAtMs)).coerceAtLeast(0L))
            ui.clearCompleted()
            ui.dismiss()
        }
    }
    val availableFlow = remember(telemetry, progressState, trackKey, ui.completedTelemetry, ui.completedAtMs) {
        progressState.map { ui.canOpen(telemetry, it, trackKey) }.distinctUntilChanged()
    }
    val available by availableFlow.collectAsStateWithLifecycle(
        initialValue = ui.canOpen(telemetry, progressState.value, trackKey),
    )
    LaunchedEffect(available, isPlayerOpen) {
        if (!available || !isPlayerOpen) ui.dismiss()
    }

    val shown = ui.shownTelemetry(telemetry, trackKey)
    var lastShown by remember { mutableStateOf<DeckBlendTelemetry?>(null) }
    var lastCompletedAtMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(shown, ui.isOpen, ui.completedAtMs) {
        if (ui.isOpen && shown.view != null) {
            lastShown = shown
            lastCompletedAtMs = ui.completedAtMs
        }
    }
    val transition = updateTransition(ui.isOpen && available && isPlayerOpen, label = "AutoMixPopup")
    val reveal by transition.animateFloat(
        transitionSpec = { ExpressiveMotion.spatialDefault() },
        label = "badgeExpansion",
    ) { if (it) 1f else 0f }
    if (!transition.currentState && !transition.targetState && !transition.isRunning) return
    val activeShown = shown.takeIf { it.view != null && available && ui.isOpen }
    val presented = activeShown ?: lastShown ?: return
    val presentedCompletedAtMs = if (activeShown != null) ui.completedAtMs else lastCompletedAtMs

    Popup(
        popupPositionProvider = remember {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ) = IntOffset.Zero
            }
        },
        onDismissRequest = ui::dismiss,
        properties = PopupProperties(focusable = true, clippingEnabled = false, usePlatformDefaultWidth = false),
    ) {
        val scheme = MaterialTheme.colorScheme
        Box(
            Modifier.fillMaxSize()
                .background(scheme.scrim.copy(alpha = 0.64f * reveal.coerceIn(0f, 1f)))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = ui::dismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                var panelBounds by remember { mutableStateOf(Rect.Zero) }
                Box(
                    Modifier.width(minOf(maxWidth, 560.dp)).height(minOf(maxHeight, 640.dp))
                        .onGloballyPositioned {
                            panelBounds = Rect(it.positionOnScreen(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                        },
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize()
                            .graphicsLayer {
                                val origin = ui.badgeBounds.takeUnless { it.isEmpty } ?: panelBounds
                                val startX = (origin.width / panelBounds.width.coerceAtLeast(1f)).coerceIn(0.08f, 1f)
                                val startY = (origin.height / panelBounds.height.coerceAtLeast(1f)).coerceIn(0.04f, 1f)
                                scaleX = startX + (1f - startX) * reveal
                                scaleY = startY + (1f - startY) * reveal
                                translationX = (origin.center.x - panelBounds.center.x) * (1f - reveal)
                                translationY = (origin.center.y - panelBounds.center.y) * (1f - reveal)
                                alpha = reveal.coerceIn(0f, 1f)
                            }
                            .pointerInput(Unit) { detectTapGestures { } }
                            .semantics { paneTitle = "AutoMix" },
                        shape = RoundedCornerShape(28.dp),
                        color = scheme.surfaceContainerLowest,
                    ) {
                        Column {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("AUTOMIX", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = ui::dismiss) {
                                    Icon(Icons.Filled.Close, contentDescription = "Close AutoMix")
                                }
                            }
                            AutoMixPopupContent(
                                telemetry = presented,
                                progressState = progressState,
                                completedAtMs = presentedCompletedAtMs,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoMixPopupContent(
    telemetry: DeckBlendTelemetry,
    progressState: StateFlow<PlaybackProgressState>,
    completedAtMs: Long,
    modifier: Modifier,
) {
    val view = telemetry.view ?: return
    val progress by progressState.collectAsStateWithLifecycle()
    var nowMs by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(completedAtMs) {
        if (telemetry.phase == BlendPhase.MIX_COMPLETED && completedAtMs > 0L) {
            while (isActive) {
                withFrameNanos { nowMs = SystemClock.uptimeMillis() }
            }
        }
    }
    val head = when (telemetry.phase) {
        BlendPhase.BLENDING -> view.cueOutMs + telemetry.clampedProgress * view.overlapMs.toDouble()
        BlendPhase.MIX_COMPLETED -> view.cueOutMs + view.overlapMs +
            (if (completedAtMs > 0L) (nowMs - completedAtMs).coerceIn(0L, POST_ROLL_MS) else 0L).toDouble()
        else -> progress.positionMs.toDouble()
    }
    val kind = when (view.style) {
        TransitionStyle.HARMONIC_BEATMIX -> "Beat blend"
        TransitionStyle.SPECTRAL_SWEEP -> "Bass exchange"
        TransitionStyle.PERCEPTUAL_FADE -> "Smooth fade"
    }
    val seconds = ((view.cueOutMs - progress.positionMs) / 1_000L).coerceAtLeast(0L)
    val status = when (telemetry.phase) {
        BlendPhase.BLENDING -> "Live \u2022 $kind"
        BlendPhase.MIX_COMPLETED -> "Mix complete"
        else -> "$kind in ${seconds / 60}:${"%02d".format(seconds % 60)}"
    }
    AutoMixDeckView(view = view, playheadMs = head, modifier = modifier, status = status)
}
