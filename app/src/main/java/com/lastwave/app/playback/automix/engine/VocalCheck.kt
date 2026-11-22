package com.lastwave.app.playback.automix.engine

import com.lastwave.app.playback.automix.analysis.AcousticSignature
import com.lastwave.app.playback.automix.api.MixShape

/**
 * What a planned mix does to the vocals, from synced lyric lines, sampled
 * across the mix with the real gain curve:
 * - [clashMs]: both songs singing while both are clearly audible;
 * - [cutsLine]: the outgoing song turns down through a line, mid-word;
 * - [hidesEntry]: a new incoming line starts while that song is still faint.
 */
internal class VocalCheck(
    val clashMs: Double,
    val cutsLine: Boolean,
    val hidesEntry: Boolean,
    /** Both songs have lyric timing; otherwise the checks above only cover what is known. */
    val known: Boolean,
) {
    companion object {
        private const val STEPS = 128
        private const val AUDIBLE_GAIN = 0.35f
        private const val HALF_GAIN = 0.5f

        fun of(
            outgoing: AcousticSignature?,
            incoming: AcousticSignature?,
            cueOutMs: Double,
            cueInMs: Double,
            overlapMs: Double,
            ratio: Float,
            shape: MixShape,
        ): VocalCheck {
            val outLines = outgoing?.vocals
            val inLines = incoming?.vocals
            fun singing(lines: List<com.lastwave.app.playback.automix.analysis.VocalSpan>?, ms: Double) =
                lines?.any { it.startMs <= ms && ms < it.endMs } == true
            var clash = 0.0
            var outHalfMs: Double? = null
            var inHalfMs: Double? = null
            for (step in 0..STEPS) {
                val p = step / STEPS.toFloat()
                val (outGain, inGain) = MixCurve.gains(p, shape)
                val outMs = cueOutMs + p * overlapMs
                val inMs = cueInMs + p * overlapMs * ratio
                if (outHalfMs == null && outGain < HALF_GAIN) outHalfMs = outMs
                if (inHalfMs == null && inGain >= HALF_GAIN) inHalfMs = inMs
                if (outGain >= AUDIBLE_GAIN && inGain >= AUDIBLE_GAIN && singing(outLines, outMs) && singing(inLines, inMs)) {
                    clash += overlapMs / STEPS
                }
            }
            val cuts = outHalfMs?.let { singing(outLines, it) } == true
            val faintUntil = inHalfMs ?: (cueInMs + overlapMs * ratio)
            val hides = inLines?.any { it.startMs >= cueInMs && it.startMs < faintUntil } == true
            return VocalCheck(clash, cuts, hides, outLines != null && inLines != null)
        }
    }
}
