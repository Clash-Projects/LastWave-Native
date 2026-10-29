package com.lastwave.app.playback.harmonicflow.engine

import com.lastwave.app.data.model.PlayableTrack
import com.lastwave.app.playback.harmonicflow.analysis.AcousticSignature
import com.lastwave.app.playback.harmonicflow.analysis.HarmonicCamelotClassifier
import com.lastwave.app.playback.harmonicflow.analysis.HarmonicCompatibilityTier
import com.lastwave.app.playback.harmonicflow.analysis.VocalPresenceGateDetector
import com.lastwave.app.playback.harmonicflow.api.HarmonicFlowSettings
import com.lastwave.app.playback.harmonicflow.api.TransitionDurationMode
import com.lastwave.app.playback.harmonicflow.api.TransitionStyle

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
        settings: HarmonicFlowSettings,
        isShuffleActive: Boolean = false,
        isMusicVideoOrLive: Boolean = false,
    ): SplicePlan {
        val outDur = outgoingTrack.durationMs ?: (outgoingSignature?.durationMs ?: 180_000L)
        val inDur = incomingTrack.durationMs ?: (incomingSignature?.durationMs ?: 180_000L)

        // 1. Same-Album Continuity Guard:
        // Automatically skips AutoDJ/Crossfade between consecutive tracks of the same album
        // in sequential order (trackN -> trackN+1 with Shuffle Off)
        if (settings.keepAlbumsContinuous && !isShuffleActive) {
            val sameAlbum = !outgoingTrack.album.isNullOrBlank() &&
                outgoingTrack.album.equals(incomingTrack.album, ignoreCase = true)
            val outTrackNum = outgoingTrack.trackNumber
            val inTrackNum = incomingTrack.trackNumber
            val isSequential = if (outTrackNum != null && inTrackNum != null) {
                inTrackNum == outTrackNum + 1
            } else {
                false
            }
            if (sameAlbum && isSequential) {
                return SplicePlan(
                    cueOutStartMs = outDur,
                    cueInStartMs = 0L,
                    durationMs = 0L,
                    transitionStyle = TransitionStyle.PERCEPTUAL_FADE,
                    isBypass = true,
                    bypassReason = "Same-album sequential continuity",
                )
            }
        }

        // 2. Short Track Guard:
        // Tracks < 45 seconds or where fade would exceed 35% of total duration are bypassed
        if (outDur < 45_000L || inDur < 45_000L) {
            return SplicePlan(
                cueOutStartMs = outDur - 2_000L,
                cueInStartMs = 0L,
                durationMs = 2_000L,
                transitionStyle = TransitionStyle.PERCEPTUAL_FADE,
                isBypass = true,
                bypassReason = "Short-track duration guard (<45s)",
            )
        }

        // 3. Music Video or Live Broadcast Guard:
        if (isMusicVideoOrLive) {
            return SplicePlan(
                cueOutStartMs = outDur - 3_000L,
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
            HarmonicCamelotClassifier.evaluateCompatibility(
                outgoingSignature.camelotKey,
                incomingSignature.camelotKey,
                tempoDelta,
            )
        } else null

        // Scale duration based on harmonic compatibility
        val durationMultiplier = harmonicMatch?.allowedDurationMultiplier ?: 1.0f
        var plannedDurationMs = (targetBaseDurationMs * durationMultiplier).toLong()
            .coerceIn(12_000L, 30_000L)

        // Ensure duration never exceeds 35% of the total track length
        plannedDurationMs = minOf(plannedDurationMs, (outDur * 0.35f).toLong())

        // 6. Anchor Cue-Out and Cue-In to Beat Grids
        var cueOutStartMs = maxOf(0L, outDur - plannedDurationMs)
        var cueInStartMs = 0L

        if (incomingSignature != null) {
            // Start incoming deck right at its first clean downbeat
            cueInStartMs = incomingSignature.beatGrid.firstDownbeatMs.coerceIn(0L, 12_000L)
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
                    plannedDurationMs = (plannedDurationMs * 0.65f).toLong().coerceAtLeast(12_000L)
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
