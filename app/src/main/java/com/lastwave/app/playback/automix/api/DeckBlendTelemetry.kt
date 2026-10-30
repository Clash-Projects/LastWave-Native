package com.lastwave.app.playback.automix.api

import androidx.compose.runtime.Immutable

/**
 * Phase of the dual-deck transition lifecycle.
 */
enum class BlendPhase {
    IDLE,
    PRE_ANALYZING,
    ARMED,
    BLENDING,
    LOOP_LOCKED,
    MIX_COMPLETED,
}

/**
 * Aesthetic DSP and rhythmic transition style.
 */
enum class TransitionStyle {
    HARMONIC_BEATMIX,
    LOOP_DROP,
    SPECTRAL_SWEEP,
    PERCEPTUAL_FADE,
}

@Immutable
data class TrackChromeSummary(
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
)

/**
 * Real-time immutable telemetry consumed by the Expanded Player, Canvas Slider,
 * and Collapsed Mini-Player chrome.
 */
@Immutable
data class DeckBlendTelemetry(
    val phase: BlendPhase = BlendPhase.IDLE,
    val blendProgress: Float = 0.0f, // 0.0f..1.0f clamped strictly to Material 3 range
    val activeTransitionStyle: TransitionStyle = TransitionStyle.PERCEPTUAL_FADE,
    val syncedBpm: Float? = null,
    val harmonicKeyPair: String? = null, // e.g. "8A â†’ 9A"
    val spliceStartPositionMs: Long = 0L,
    val spliceDurationMs: Long = 5_000L, // 1,000ms up to 30,000ms
    val incomingTrackPreview: TrackChromeSummary? = null,
    val loopBarsActive: Int? = null,
    val isVocalProtected: Boolean = true,
) {
    val isBlendingActive: Boolean
        get() = phase == BlendPhase.BLENDING || phase == BlendPhase.LOOP_LOCKED

    val isArmedOrBlending: Boolean
        get() = phase == BlendPhase.ARMED || isBlendingActive

    val clampedProgress: Float
        get() = blendProgress.coerceIn(0.0f, 1.0f)

    companion object {
        val IDLE = DeckBlendTelemetry(
            phase = BlendPhase.IDLE,
            blendProgress = 0.0f,
            activeTransitionStyle = TransitionStyle.PERCEPTUAL_FADE,
            syncedBpm = null,
            harmonicKeyPair = null,
            spliceStartPositionMs = 0L,
            spliceDurationMs = 5_000L,
            incomingTrackPreview = null,
            loopBarsActive = null,
            isVocalProtected = true,
        )
    }
}
