package com.lastwave.app.playback.automix.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Per-deck bass hand-over for AutoMix, run on the playback thread over the
 * deck's Float32 output. A DJ swaps the low end on a downbeat so two kick
 * drums and two bass lines never stack: that doubling is what made overlaps
 * muddy and pushed summed peaks toward clipping.
 *
 * It is a filter sweep, not a blend: one 2nd-order Butterworth high-pass
 * whose cutoff glides from [OPEN_HZ] (inaudible) to [CUT_HZ] (bass gone).
 * Mixing dry and high-passed copies instead cancelled the deep bass midway,
 * because the high-pass is near 180 degrees out of phase below its cutoff.
 *
 * Idle (no schedule) it returns before touching a sample, so ordinary
 * playback stays bit-identical; it fades in and out of the filtered path
 * over [ENGAGE_MS] so engaging never clicks. Times are the deck's media time,
 * so a schedule survives seeks and pre-buffered audio lands on the right side.
 */
class BassSwapStage {
    private class Ramp(val atUs: Long, val from: Float, val to: Float, val rampUs: Long) {
        /** 1 = full bass, 0 = cut. */
        fun bassAt(timeUs: Long): Float = when {
            timeUs <= atUs -> from
            timeUs >= atUs + rampUs -> to
            else -> from + (to - from) * ((timeUs - atUs).toFloat() / rampUs)
        }
    }

    /** Written by the main thread; [RESTORE] is resolved against the audio thread's live level. */
    private val command = AtomicReference<Ramp?>(null)
    private var seen: Ramp? = null
    private var active: Ramp? = null
    private var bass = 1f
    /** 0 = dry signal, 1 = filtered path; slewed so engaging and releasing never step. */
    private var engagement = 0f
    private var rate = 0
    private var coefficientsFor = Float.NaN
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private val z1 = DoubleArray(2)
    private val z2 = DoubleArray(2)

    /** Bass at [from] until [atMediaUs], then a linear move to [to] over [rampUs]. */
    fun schedule(atMediaUs: Long, from: Float, to: Float, rampUs: Long) {
        command.set(Ramp(atMediaUs, from, to, rampUs.coerceAtLeast(1L)))
    }

    /** Brings the bass back from wherever it is now, without a step. */
    fun restore(rampUs: Long = 200_000L) {
        command.set(Ramp(RESTORE, Float.NaN, 1f, rampUs))
    }

    /** Immediate pass-through; only for a deck that is already silent. */
    fun clear() {
        command.set(null)
    }

    /** A seek or flush discards the filter history, never the schedule. */
    fun resetFilter() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    /** Processes interleaved native-order Float32 in place. [mediaUs] is the first frame's media time. */
    fun process(output: ByteBuffer, channelCount: Int, sampleRate: Int, mediaUs: Long) {
        val requested = command.get()
        if (requested !== seen) {
            seen = requested
            active = when {
                requested == null -> null.also {
                    bass = 1f
                    engagement = 0f
                }
                requested.atUs == RESTORE -> Ramp(mediaUs, bass, 1f, requested.rampUs)
                else -> requested
            }
        }
        val ramp = active ?: return
        // An offset the sink never reported would put every buffer past the
        // ramp; pass through instead of cutting the bass for no reason.
        if (channelCount !in 1..2 || sampleRate <= 0 || mediaUs !in 0L..MAX_MEDIA_US) return
        if (sampleRate != rate) {
            rate = sampleRate
            coefficientsFor = Float.NaN
            resetFilter()
        }
        val samples = output.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val frames = samples.remaining() / channelCount
        val base = samples.position()
        val usPerFrame = 1_000_000.0 / sampleRate
        val engageStep = 1f / (ENGAGE_MS * sampleRate / 1000f)
        for (frame in 0 until frames) {
            val timeUs = mediaUs + (frame * usPerFrame).toLong()
            val level = ramp.bassAt(timeUs)
            // Untouched until the deck's own cut begins; then the filter fades in at the open cutoff.
            val wanted = if (level >= 1f && (timeUs < ramp.atUs || ramp.to >= 1f)) 0f else 1f
            engagement = if (wanted > engagement) {
                minOf(wanted, engagement + engageStep)
            } else {
                maxOf(wanted, engagement - engageStep)
            }
            if (frame % COEFFICIENT_BLOCK == 0) updateCoefficients(level)
            for (channel in 0 until channelCount) {
                val index = base + frame * channelCount + channel
                val x = samples.get(index).toDouble()
                val y = b0 * x + z1[channel]
                z1[channel] = b1 * x - a1 * y + z2[channel]
                z2[channel] = b2 * x - a2 * y
                if (engagement > 0f) samples.put(index, (x + engagement * (y - x)).toFloat())
            }
        }
        bass = ramp.bassAt(mediaUs + (frames * usPerFrame).toLong())
        // A finished restore drops back to idle so playback is bit-exact again.
        if (ramp.to >= 1f && bass >= 1f && engagement == 0f && mediaUs >= ramp.atUs) {
            active = null
            if (command.compareAndSet(requested, null)) seen = null
            resetFilter()
        }
    }

    /** Bass level 1..0 maps to a cutoff gliding log-wise from [OPEN_HZ] to [CUT_HZ]. */
    private fun updateCoefficients(level: Float) {
        if (level == coefficientsFor) return
        coefficientsFor = level
        val cutoff = OPEN_HZ * (CUT_HZ / OPEN_HZ).pow(1.0 - level)
        val w0 = 2.0 * PI * cutoff / rate
        val alpha = sin(w0) / sqrt(2.0)
        val cosW0 = cos(w0)
        val a0 = 1.0 + alpha
        b0 = (1.0 + cosW0) / 2.0 / a0
        b1 = -(1.0 + cosW0) / a0
        b2 = b0
        a1 = -2.0 * cosW0 / a0
        a2 = (1.0 - alpha) / a0
    }

    private companion object {
        const val OPEN_HZ = 10.0
        const val CUT_HZ = 150.0
        const val ENGAGE_MS = 20f
        const val COEFFICIENT_BLOCK = 32
        const val RESTORE = Long.MIN_VALUE
        /** Six hours: anything later is a timebase error, not a song. */
        const val MAX_MEDIA_US = 6L * 3_600_000_000L
    }
}
