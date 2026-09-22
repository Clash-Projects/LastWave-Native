package com.lastwave.app.playback.transition

/**
 * Supported transition handoff types between tracks.
 */
enum class TransitionType(
    val displayName: String,
    val description: String,
    val isAvailableForStreaming: Boolean
) {
    /**
     * Standard equal-power sine/cosine crossfade.
     * Default state, and 100% fail-open fallback whenever metadata is missing or errors occur.
     */
    FALLBACK_CROSSFADE(
        displayName = "Crossfade",
        description = "Standard equal-power crossfade between outgoing and incoming tracks.",
        isAvailableForStreaming = true
    ),

    /**
     * Enhanced crossfade with outgoing bass attenuation (-6dB to -8dB) below 160 Hz.
     * Eliminates low-end mud while incoming track drops cleanly.
     */
    SMOOTH_BLEND(
        displayName = "Smooth Blend",
        description = "Equal-power crossfade with dynamic low-end bass dip on the outgoing track.",
        isAvailableForStreaming = true
    ),

    /**
     * Rapid volume cutoff on outgoing track (150ms), followed by a short silence gap (300-500ms),
     * dropping the incoming track cleanly on beat 1. Ideal for dramatic tempo or genre shifts.
     */
    IMPACT_DROP(
        displayName = "Impact Drop",
        description = "Rapid cut and brief dramatic pause before incoming track drops.",
        isAvailableForStreaming = true
    ),

    /**
     * Loops the current track's hook section (2-4 seconds) and fades it into the incoming track.
     * Strictly guarded to seekable/downloaded tracks to avoid streaming rebuffering.
     */
    HOOK_BRIDGE(
        displayName = "Hook Bridge",
        description = "Seamless bridge looping the chorus/hook section before transitioning.",
        isAvailableForStreaming = false
    )
}
