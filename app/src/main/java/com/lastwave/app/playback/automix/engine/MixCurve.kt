package com.lastwave.app.playback.automix.engine

import com.lastwave.app.playback.automix.api.MixShape
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object MixCurve {
    /**
     * Cap on the summed amplitude of both decks. Equal power alone reaches
     * 1.41 at the midpoint, which clips two loud masters whose peaks line
     * up; 1.2 costs about 1.4 dB of loudness there for uncorrelated music.
     */
    const val MAX_SUM = 1.2f

    /** With the bass swapped only one deck carries the low end, where the biggest peaks live. */
    const val MAX_SUM_BASS_SWAP = 1.4f

    /** Gains (outgoing, incoming) at progress [p] in 0..1. */
    fun gains(p: Float, shape: MixShape): Pair<Float, Float> {
        val t = p.coerceIn(0f, 1f)
        val (outgoing, incoming) = when (shape) {
            MixShape.EQUAL_POWER -> cos(t * PI / 2).toFloat() to sin(t * PI / 2).toFloat()
            MixShape.HOLD_OUTGOING -> (1f - t * t * t * t) to sin(t * PI / 2).toFloat()
            MixShape.BASS_SWAP -> if (t < 0.5f) {
                1f to sin(t * PI).toFloat()
            } else {
                cos((t - 0.5f) * PI).toFloat() to 1f
            }
        }
        val cap = if (shape == MixShape.BASS_SWAP) MAX_SUM_BASS_SWAP else MAX_SUM
        val sum = incoming + outgoing
        val trim = if (sum > cap) cap / sum else 1f
        return outgoing * trim to incoming * trim
    }

    /** Progress at which the incoming deck becomes the one the UI shows. */
    fun handOverPoint(shape: MixShape): Float = when (shape) {
        MixShape.EQUAL_POWER, MixShape.BASS_SWAP -> 0.5f
        MixShape.HOLD_OUTGOING -> 0.85f
    }
}
