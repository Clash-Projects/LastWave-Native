package com.lastwave.app.playback.transition

import kotlin.math.abs

/**
 * Pure Kotlin, deterministic rule engine that selects the most musical and seamless
 * transition handoff between the current (outgoing) track and next (incoming) track.
 *
 * Invariant Guarantees:
 * 1. If [enabled] is false -> returns [TransitionType.FALLBACK_CROSSFADE].
 * 2. If metadata for either track is missing -> returns [TransitionType.FALLBACK_CROSSFADE].
 * 3. Never throws an exception; any boundary condition safely returns standard crossfade.
 */
object SmartTransitionSelector {

    // Decision thresholds
    const val MAX_BPM_DIFF_SMOOTH = 6.0f
    const val MIN_BPM_DIFF_IMPACT = 12.0f
    const val MAX_ENERGY_DIFF_SMOOTH = 0.20f
    const val MIN_ENERGY_DIFF_IMPACT = 0.40f
    const val MAX_LOUDNESS_DIFF_SMOOTH_DB = 3.0f

    // Parameter defaults
    const val DEFAULT_IMPACT_DROP_FADE_OUT_MS = 150L
    const val DEFAULT_IMPACT_DROP_GAP_MS = 400L
    const val DEFAULT_BASS_CUT_DB = -8.0f

    /**
     * Determines the optimal [TransitionPlan] based on track characteristics and user settings.
     *
     * @param outgoing Metadata for the currently playing track approaching its end.
     * @param incoming Metadata for the track queued to play next.
     * @param crossfadeDurationMs User's configured crossfade duration in milliseconds (from settings).
     * @param enabled Whether Smart Transitions is toggled on by the user.
     * @return Validated [TransitionPlan] ready for execution.
     */
    fun choose(
        outgoing: TrackTransitionMetadata?,
        incoming: TrackTransitionMetadata?,
        crossfadeDurationMs: Long,
        enabled: Boolean
    ): TransitionPlan {
        if (!enabled || outgoing == null || incoming == null) {
            return TransitionPlan.fallback(crossfadeDurationMs)
        }

        // 1. Hook Bridge Eligibility:
        // Must be seekable (downloaded/local), have valid hook boundaries, and close energy
        if (outgoing.isSeekable && outgoing.hasHook) {
            val hookStart = outgoing.hookStartMs!!
            val hookEnd = outgoing.hookEndMs!!
            val hookDurationMs = hookEnd - hookStart

            if (hookDurationMs in 1500L..8000L) {
                val energyDiff = if (outgoing.hasEnergy && incoming.hasEnergy) {
                    abs(outgoing.energy!! - incoming.energy!!)
                } else 0f

                if (energyDiff <= 0.35f) {
                    return TransitionPlan(
                        type = TransitionType.HOOK_BRIDGE,
                        durationMs = hookDurationMs.coerceIn(2000L, 5000L),
                        silenceGapMs = 0L,
                        outgoingBassCutDb = -4.0f,
                        hookStartMs = hookStart,
                        hookEndMs = hookEnd,
                        isFallback = false
                    )
                }
            }
        }

        // 2. Metric Differentials
        val bpmDiff = if (outgoing.hasBpm && incoming.hasBpm) {
            abs(outgoing.bpm!! - incoming.bpm!!)
        } else null

        val energyDiff = if (outgoing.hasEnergy && incoming.hasEnergy) {
            abs(outgoing.energy!! - incoming.energy!!)
        } else null

        val loudnessDiff = if (outgoing.loudnessDb != null && incoming.loudnessDb != null) {
            abs(outgoing.loudnessDb - incoming.loudnessDb)
        } else null

        // 3. Impact Drop: Large tempo gap OR large energy gap
        val isTempoMismatch = bpmDiff != null && bpmDiff >= MIN_BPM_DIFF_IMPACT
        val isEnergyMismatch = energyDiff != null && energyDiff >= MIN_ENERGY_DIFF_IMPACT

        if (isTempoMismatch || isEnergyMismatch) {
            return TransitionPlan(
                type = TransitionType.IMPACT_DROP,
                durationMs = DEFAULT_IMPACT_DROP_FADE_OUT_MS,
                silenceGapMs = DEFAULT_IMPACT_DROP_GAP_MS,
                outgoingBassCutDb = 0f,
                isFallback = false
            )
        }

        // 4. Smooth Blend: Compatible tempo, compatible energy, and matched loudness
        val isTempoCompatible = bpmDiff != null && bpmDiff <= MAX_BPM_DIFF_SMOOTH
        val isEnergyCompatible = energyDiff != null && energyDiff <= MAX_ENERGY_DIFF_SMOOTH
        val isLoudnessCompatible = loudnessDiff == null || loudnessDiff <= MAX_LOUDNESS_DIFF_SMOOTH_DB

        if (isTempoCompatible && isEnergyCompatible && isLoudnessCompatible) {
            return TransitionPlan(
                type = TransitionType.SMOOTH_BLEND,
                durationMs = crossfadeDurationMs.coerceAtLeast(1000L),
                silenceGapMs = 0L,
                outgoingBassCutDb = DEFAULT_BASS_CUT_DB,
                isFallback = false
            )
        }

        // 5. Default/Ambiguous: Fallback to standard crossfade
        return TransitionPlan.fallback(crossfadeDurationMs)
    }
}
