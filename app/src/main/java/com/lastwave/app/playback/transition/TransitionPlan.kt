package com.lastwave.app.playback.transition

/**
 * Concrete, validated execution plan produced by [SmartTransitionSelector].
 *
 * @property type The chosen transition mode.
 * @property durationMs Duration of the transition fade/ramp in milliseconds.
 * @property silenceGapMs Duration of intentional silence between outgoing and incoming tracks (used in IMPACT_DROP).
 * @property outgoingBassCutDb EQ attenuation applied to the outgoing track's low-frequency bands (≤ 0.0 dB).
 * @property hookStartMs Seek point for looping the hook section (used in HOOK_BRIDGE).
 * @property hookEndMs End point for looping the hook section (used in HOOK_BRIDGE).
 * @property isFallback True if this plan represents a safety fallback to standard crossfade.
 */
data class TransitionPlan(
    val type: TransitionType,
    val durationMs: Long,
    val silenceGapMs: Long = 0L,
    val outgoingBassCutDb: Float = 0f,
    val hookStartMs: Long? = null,
    val hookEndMs: Long? = null,
    val isFallback: Boolean = false
) {
    init {
        require(durationMs >= 0L) { "durationMs cannot be negative: $durationMs" }
        require(silenceGapMs >= 0L) { "silenceGapMs cannot be negative: $silenceGapMs" }
        require(outgoingBassCutDb <= 0f) { "outgoingBassCutDb must be non-positive: $outgoingBassCutDb" }
    }

    companion object {
        /**
         * Creates a zero-risk fallback plan using LastWave's default equal-power crossfade behavior.
         */
        fun fallback(durationMs: Long): TransitionPlan = TransitionPlan(
            type = TransitionType.FALLBACK_CROSSFADE,
            durationMs = durationMs.coerceAtLeast(0L),
            silenceGapMs = 0L,
            outgoingBassCutDb = 0f,
            isFallback = true
        )
    }
}
