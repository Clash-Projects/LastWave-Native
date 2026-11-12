package com.lastwave.app.playback.automix.engine

import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.automix.analysis.AcousticSignature
import com.lastwave.app.playback.automix.analysis.AutoMixCamelotClassifier
import com.lastwave.app.playback.automix.analysis.HarmonicCompatibilityTier
import com.lastwave.app.playback.automix.analysis.VocalPresenceGateDetector
import com.lastwave.app.playback.automix.api.AutoMixSettings
import com.lastwave.app.playback.automix.api.TransitionDurationMode
import com.lastwave.app.playback.automix.api.TransitionStyle

data class SplicePlan(
    val cueOutStartMs: Long,
    val cueInStartMs: Long,
    val durationMs: Long,
    val transitionStyle: TransitionStyle,
    val isBypass: Boolean = false,
    val bypassReason: String? = null,
    val pitchShiftSemitones: Int = 0,
    val requiresPhraseLoop: Boolean = false,
    val loopBarsCount: Int = 0,
)

object SpliceTrajectorySynthesizer {

    /**
     * Synthesizes an optimal splice plan between outgoing track and incoming track.
     * Duration is configurable between 12s and 30s.
     */
    fun synthesizePlan(
        outgoingTrack: PlayableTrack,
        outgoingSignature: AcousticSignature?,
        incomingTrack: PlayableTrack,
        incomingSignature: AcousticSignature?,
        settings: AutoMixSettings,
        isShuffleActive: Boolean = false,
        isMusicVideoOrLive: Boolean = false,
        outgoingDurationMs: Long? = null,
        incomingDurationMs: Long? = null,
    ): SplicePlan {
        val outDur = outgoingDurationMs?.takeIf { it > 0L }
            ?: outgoingTrack.durationMs?.takeIf { it > 0L }
            ?: (outgoingSignature?.durationMs ?: 180_000L)
        val inDur = incomingDurationMs?.takeIf { it > 0L }
            ?: incomingTrack.durationMs?.takeIf { it > 0L }
            ?: (incomingSignature?.durationMs ?: 180_000L)

        // 2. Short Track Guard:
        // Tracks < 15 seconds are bypassed
        if (outDur < 15_000L || inDur < 15_000L) {
            return SplicePlan(
                cueOutStartMs = maxOf(0L, outDur - 2_000L),
                cueInStartMs = 0L,
                durationMs = 2_000L,
                transitionStyle = TransitionStyle.PERCEPTUAL_FADE,
                isBypass = true,
                bypassReason = "Short-track duration guard (<15s)",
            )
        }

        // 3. Music Video or Live Broadcast Guard:
        if (isMusicVideoOrLive) {
            return SplicePlan(
                cueOutStartMs = maxOf(0L, outDur - 3_000L),
                cueInStartMs = 0L,
                durationMs = 3_000L,
                transitionStyle = TransitionStyle.PERCEPTUAL_FADE,
                isBypass = true,
                bypassReason = "Live broadcast or video stream guard",
            )
        }

        // 4. Calculate Base Target Duration (Auto vs Configurable 12s..30s)
        val targetBaseDurationMs = when (settings.durationMode) {
            TransitionDurationMode.AUTO -> 12_000L
            TransitionDurationMode.MANUAL -> settings.targetDurationSeconds.coerceIn(12, 30) * 1_000L
        }

        // 5. Harmonic Compatibility Evaluation
        val harmonicMatch = if (outgoingSignature != null && incomingSignature != null) {
            val tempoDelta = if (outgoingSignature.beatGrid.bpm.value > 0f) {
                ((incomingSignature.beatGrid.bpm.value - outgoingSignature.beatGrid.bpm.value) / outgoingSignature.beatGrid.bpm.value) * 100.0f
            } else 0f
            AutoMixCamelotClassifier.evaluateCompatibility(
                outgoingSignature.camelotKey,
                incomingSignature.camelotKey,
                tempoDelta,
            )
        } else null

        // Scale duration based on harmonic compatibility
        val durationMultiplier = harmonicMatch?.allowedDurationMultiplier ?: 1.0f
        var plannedDurationMs = (targetBaseDurationMs * durationMultiplier).toLong()
            .coerceIn(8_000L, 30_000L)

        // Ensure duration never exceeds 40% of the total track length
        plannedDurationMs = minOf(plannedDurationMs, (outDur * 0.40f).toLong()).coerceAtLeast(4_000L)

        // 6. Anchor Cue-Out and Cue-In to Beat Grids
        var cueOutStartMs = maxOf(0L, outDur - plannedDurationMs)
        var cueInStartMs = 0L

        if (incomingSignature != null) {
            // Trim at most brief lead-in silence (max 1.5s), never skipping into the song intro
            cueInStartMs = incomingSignature.beatGrid.firstDownbeatMs.coerceIn(0L, 1_500L)
        }

        // 7. Vocal Protection Rule Check
        var requiresLoop = false
        var loopBars = 0

        if (outgoingSignature != null && incomingSignature != null) {
            val hasCollision = VocalPresenceGateDetector.hasVocalCollision(
                outgoingMask = outgoingSignature.outroVocalMask,
                incomingMask = incomingSignature.introVocalMask,
                spliceStartOutgoingMs = cueOutStartMs,
                spliceStartIncomingMs = cueInStartMs,
                spliceDurationMs = plannedDurationMs,
            )

            if (hasCollision) {
                val incomingHasVocalsAtStart = incomingSignature.introVocalMask.isVocalActiveAt(cueInStartMs)
                if (incomingHasVocalsAtStart) {
                    requiresLoop = true
                    loopBars = 8
                } else {
                    // Shorten duration to exit cleanly before vocal clash
                    plannedDurationMs = (plannedDurationMs * 0.65f).toLong().coerceAtLeast(8_000L)
                    cueOutStartMs = maxOf(0L, outDur - plannedDurationMs)
                }
            }
        }

        // 8. Determine Transition Style
        val style = when {
            requiresLoop -> TransitionStyle.LOOP_DROP
            harmonicMatch?.tier == HarmonicCompatibilityTier.PERFECT_LOCK -> TransitionStyle.HARMONIC_BEATMIX
            settings.spectralFilterSweepsEnabled -> TransitionStyle.SPECTRAL_SWEEP
            else -> TransitionStyle.PERCEPTUAL_FADE
        }

        return SplicePlan(
            cueOutStartMs = cueOutStartMs,
            cueInStartMs = cueInStartMs,
            durationMs = plannedDurationMs,
            transitionStyle = style,
            isBypass = false,
            pitchShiftSemitones = harmonicMatch?.suggestedPitchShiftSemitones ?: 0,
            requiresPhraseLoop = requiresLoop,
            loopBarsCount = loopBars,
        )
    }
}
