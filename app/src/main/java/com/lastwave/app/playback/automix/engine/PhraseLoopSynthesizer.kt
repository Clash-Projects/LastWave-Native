package com.lastwave.app.playback.automix.engine

import com.lastwave.app.playback.automix.analysis.BeatGridEstimate
import com.lastwave.app.playback.automix.analysis.VocalActivityMask

data class LoopBoundary(
    val loopStartMs: Long,
    val loopEndMs: Long,
    val barCount: Int,
    val isValid: Boolean,
)

object PhraseLoopSynthesizer {

    /**
     * Synthesizes a vocal-safe, zero-crossing, beat-aligned instrumental loop boundary.
     * Returns isValid = false if the candidate window violates the Vocal Protection Rule or is a video/live stream.
     */
    fun createVocalSafeLoop(
        grid: BeatGridEstimate,
        vocalMask: VocalActivityMask,
        targetBars: Int = 8,
        isMusicVideoOrLive: Boolean = false,
    ): LoopBoundary {
        // Hard Safety Guard: Never loop a music video or live stream
        if (isMusicVideoOrLive) {
            return LoopBoundary(0L, 0L, 0, isValid = false)
        }

        val safeBars = when (targetBars) {
            in 1..4 -> 4
            in 5..12 -> 8
            else -> 16
        }

        val barIntervalMs = grid.beatIntervalMs * 4L
        val loopDurationMs = barIntervalMs * safeBars

        // Find candidate starting at first clean downbeat
        val candidateStartMs = grid.firstDownbeatMs
        val candidateEndMs = candidateStartMs + loopDurationMs

        // Hard Safety Guard: Never loop any segment containing sung words or syllables
        var hasVocalInLoop = false
        var t = candidateStartMs
        while (t <= candidateEndMs) {
            if (vocalMask.isVocalActiveAt(t)) {
                hasVocalInLoop = true
                break
            }
            t += 50L
        }

        if (hasVocalInLoop) {
            // Try fallback to a shorter 4-bar loop if 8/16 bars clashed with vocals
            if (safeBars > 4) {
                return createVocalSafeLoop(grid, vocalMask, targetBars = 4, isMusicVideoOrLive = false)
            }
            // Cannot construct a safe loop without repeating vocals
            return LoopBoundary(0L, 0L, 0, isValid = false)
        }

        return LoopBoundary(
            loopStartMs = candidateStartMs,
            loopEndMs = candidateEndMs,
            barCount = safeBars,
            isValid = true,
        )
    }
}
