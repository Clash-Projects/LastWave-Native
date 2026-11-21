package com.lastwave.app.playback.automix.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class MixShape {
    /** Constant-power crossfade. */
    EQUAL_POWER,
    /** The outgoing song keeps its level and ends on its own (cold endings). */
    HOLD_OUTGOING,
}

object MixCurve {
    /**
     * Cap on the summed amplitude of both decks. Equal power alone reaches
     * 1.41 at the midpoint, which clips two loud masters whose peaks line
     * up; 1.2 costs about 1.4 dB of loudness there for uncorrelated music.
     */
    const val MAX_SUM = 1.2f

    /** Gains (outgoing, incoming) at progress [p] in 0..1. */
    fun gains(p: Float, shape: MixShape): Pair<Float, Float> {
        val t = p.coerceIn(0f, 1f)
        val incoming = sin(t * PI / 2).toFloat()
        val outgoing = when (shape) {
            MixShape.EQUAL_POWER -> cos(t * PI / 2).toFloat()
            MixShape.HOLD_OUTGOING -> 1f - t * t * t * t
        }
        val sum = incoming + outgoing
        val trim = if (sum > MAX_SUM) MAX_SUM / sum else 1f
        return outgoing * trim to incoming * trim
    }

    /** Progress at which the incoming deck becomes the one the UI shows. */
    fun handOverPoint(shape: MixShape): Float = when (shape) {
        MixShape.EQUAL_POWER -> 0.5f
        MixShape.HOLD_OUTGOING -> 0.85f
    }
}
