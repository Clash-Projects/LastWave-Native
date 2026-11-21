package com.lastwave.app.playback.automix.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Per-deck bass hand-over for AutoMix, run on the playback thread over the
 * deck's Float32 output. A DJ swaps the low end on a downbeat so two kick
 * drums and two bass lines never stack: that doubling is what made overlaps
 * muddy and pushed summed peaks toward clipping.
 *
 * Output is `g*x + (1-g)*HP(x)` with a 2nd-order Butterworth high-pass, so
 * g = 1 is the untouched signal and g = 0 is a clean 12 dB/oct bass cut. The
 * mix never notches the crossover: HP is +90 degrees there, not 180.
 *
 * Idle (no schedule) it returns before touching a sample, so ordinary
 * playback stays bit-identical. Times are the deck's media time, so a
 * schedule survives seeks and pre-buffered audio lands on the right side.
 */
class BassSwapStage {
    private class Ramp(val atUs: Long, val from: Float, val to: Float, val rampUs: Long) {
        fun gainAt(timeUs: Long): Float = when {
            timeUs <= atUs -> from
            timeUs >= atUs + rampUs -> to
            else -> from + (to - from) * ((timeUs - atUs).toFloat() / rampUs)
        }
    }

    /** Written by the main thread; [RESTORE] is resolved against the audio thread's live gain. */
    private val command = AtomicReference<Ramp?>(null)
    private var seen: Ramp? = null
    private var active: Ramp? = null
    private var gain = 1f
    private var configuredRate = 0
    private var b0 = 0.0
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
                requested == null -> null.also { gain = 1f }
                requested.atUs == RESTORE -> Ramp(mediaUs, gain, 1f, requested.rampUs)
                else -> requested
            }
        }
        val ramp = active ?: return
        // An offset the sink never reported would put every buffer past the
        // ramp; pass through instead of cutting the bass for no reason.
        if (channelCount !in 1..2 || sampleRate <= 0 || mediaUs !in 0L..MAX_MEDIA_US) return
        if (sampleRate != configuredRate) configure(sampleRate)
        val samples = output.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val frames = samples.remaining() / channelCount
        val base = samples.position()
        val usPerFrame = 1_000_000.0 / sampleRate
        for (frame in 0 until frames) {
            val g = ramp.gainAt(mediaUs + (frame * usPerFrame).toLong())
            for (channel in 0 until channelCount) {
                val index = base + frame * channelCount + channel
                val x = samples.get(index).toDouble()
                // Runs even at g = 1 so the filter is settled when the cut starts.
                val y = b0 * x + z1[channel]
                z1[channel] = b1 * x - a1 * y + z2[channel]
                z2[channel] = b2 * x - a2 * y
                if (g < 1f) samples.put(index, (g * x + (1 - g) * y).toFloat())
            }
        }
        gain = ramp.gainAt(mediaUs + (frames * usPerFrame).toLong())
        // A finished restore drops back to idle so playback is bit-exact again.
        if (ramp.to == 1f && gain >= 1f && mediaUs >= ramp.atUs) {
            active = null
            if (command.compareAndSet(requested, null)) seen = null
        }
    }

    private fun configure(sampleRate: Int) {
        configuredRate = sampleRate
        val w0 = 2.0 * PI * CUTOFF_HZ / sampleRate
        val alpha = sin(w0) / (2.0 * (1.0 / sqrt(2.0)))
        val cosW0 = cos(w0)
        val a0 = 1.0 + alpha
        b0 = (1.0 + cosW0) / 2.0 / a0
        b1 = -(1.0 + cosW0) / a0
        b2 = (1.0 + cosW0) / 2.0 / a0
        a1 = -2.0 * cosW0 / a0
        a2 = (1.0 - alpha) / a0
        resetFilter()
    }

    private companion object {
        const val CUTOFF_HZ = 130.0
        const val RESTORE = Long.MIN_VALUE
        /** Six hours: anything later is a timebase error, not a song. */
        const val MAX_MEDIA_US = 6L * 3_600_000_000L
    }
}
