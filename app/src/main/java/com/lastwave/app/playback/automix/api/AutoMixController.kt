package com.lastwave.app.playback.automix.api

import kotlinx.coroutines.flow.StateFlow

/**
 * Public reactive contract exposing AutoMix state to the playback service and UI layers.
 */
interface AutoMixController {
    /** Live immutable snapshot of the dual-deck transition state. */
    val telemetry: StateFlow<DeckBlendTelemetry>

    /** Cancels an ongoing or armed blend, restoring unity gain to primary deck. */
    fun cancelTransition(revertToPrimary: Boolean = true)

    /** Immediately commits the secondary deck as primary and finishes the blend. */
    fun forceCommitTransition()

    /** Triggers an immediate DJ-style phrase-locked transition. */
    fun triggerImmediateTransition(customDurationMs: Long? = null)

    /** Notifies the controller of a user seek event during a transition. */
    fun handleSeekEvent(targetPositionMs: Long)

    /** Suspends transition execution if bit-perfect, exclusive USB, or synchronized listening is active. */
    fun updateHardwareBypassState(isHardwareBypassActive: Boolean)
}
