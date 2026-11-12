package com.lastwave.app.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Gain law for the two-player blend.
 *
 * Equal power: [incomingGain]² + [outgoingGain]² == 1 for every point in the
 * window, so the perceived level is flat from the first sample to the last.
 * The previous pair — `(1-p)²` and `p²` — summed to 0.5 at the midpoint, a
 * −12 dB hole that reads as the music dropping out rather than as a blend.
 *
 * The functions are pure and normalized to 0..1 so they can be unit-tested
 * without Android or a player.
 */
internal object CrossfadeCurve {

    /**
     * The shortest window worth calling a fade.
     *
     * [CrossfadeSchedule] used to floor the computed overlap at 1 ms, which
     * turned "the tick arrived one millisecond before the end" into a
     * one-frame blend reported and logged as a successful crossfade. Below
     * this the natural advance is both honest and better; at or above it a
     * real ramp still happens.
     */
    const val MIN_OVERLAP_MS = 250L

    /** Outgoing fader at [progress] (0 = full, 1 = silent). */
    fun outgoingGain(progress: Float): Float =
        cos(progress.coerceIn(0f, 1f) * HALF_PI)

    /** Incoming fader at [progress] (0 = silent, 1 = full). */
    fun incomingGain(progress: Float): Float =
        sin(progress.coerceIn(0f, 1f) * HALF_PI)

    /**
     * A single trim applied to both decks while they overlap.
     *
     * Equal-power gains sum to 1.41 at the midpoint in amplitude, so two loud
     * masters peaking on the same beat pass full scale and are clipped into a
     * crackle. `1/sqrt(in + out)` holds the worst case at 1.19 instead, at a
     * cost of at most 1.5 dB through the middle of the blend. Outside the
     * overlap the sum is already 1, so the trim is exactly 1 and the blend
     * starts and ends at the user's real level.
     */
    fun headroom(incomingGain: Float, outgoingGain: Float): Float {
        val sum = incomingGain.coerceIn(0f, 1f) + outgoingGain.coerceIn(0f, 1f)
        return if (sum <= 1f) 1f else 1f / sqrt(sum)
    }

    private val HALF_PI = (PI / 2.0).toFloat()
}
