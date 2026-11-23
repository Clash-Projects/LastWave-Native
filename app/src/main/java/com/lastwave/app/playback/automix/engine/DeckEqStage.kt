package com.lastwave.app.playback.automix.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One EQ band's level over media time: [from] until [atUs], then linear to [to] over [rampUs]. 1 = open. */
class EqAutomation(val atUs: Long, val from: Float, val to: Float, val rampUs: Long) {
    fun levelAt(timeUs: Long): Float = when {
        timeUs <= atUs -> from
        timeUs >= atUs + rampUs -> to
        else -> from + (to - from) * ((timeUs - atUs).toFloat() / rampUs.coerceAtLeast(1L))
    }

    val settlesOpen: Boolean get() = to >= 1f
}

/**
 * Per-deck DJ EQ for AutoMix, run on the playback thread over the deck's
 * Float32 output: a bass filter and a treble filter that sweep, so a mix can
 * bring the next song in on its mids and hand the low end over on a downbeat.
 * Two kick drums or two bass lines stacking is what made overlaps muddy and
 * pushed summed peaks toward clipping.
 *
 * Each band is one 2nd-order Butterworth filter whose cutoff glides
 * log-wise: bass high-pass 10 Hz (inaudible) to 150 Hz (bass gone), treble
 * low-pass 20 kHz to 2.5 kHz. Sweeping a single filter never cancels;
 * blending dry and filtered copies did, below the cutoff.
 *
 * Idle (no schedule) it returns before touching a sample, so ordinary
 * playback stays bit-identical, and it fades in and out of the filtered path
 * over [ENGAGE_MS] so engaging never clicks. Times are the deck's media time,
 * so a schedule survives seeks and pre-buffered audio lands on the right side.
 */
class DeckEqStage {
    private class Command(val bass: EqAutomation?, val treble: EqAutomation?, val restoreUs: Long = NONE)

    private val command = AtomicReference<Command?>(null)
    private var seen: Command? = null
    private var bass: EqAutomation? = null
    private var treble: EqAutomation? = null
    private var bassLevel = 1f
    private var trebleLevel = 1f
    /** 0 = dry signal, 1 = filtered path; slewed so engaging and releasing never step. */
    private var engagement = 0f
    private var rate = 0
    private val highPass = Biquad()
    private val lowPass = Biquad()

    /** Replaces both bands' automation; a null band stays open. */
    fun schedule(bass: EqAutomation?, treble: EqAutomation?) {
        command.set(Command(bass, treble))
    }

    /** Opens both bands from wherever they are now, without a step. */
    fun restore(rampUs: Long = 200_000L) {
        command.set(Command(null, null, rampUs))
    }

    /** Immediate pass-through; only for a deck that is already silent. */
    fun clear() {
        command.set(null)
    }

    /** A seek or flush discards the filter history, never the schedule. */
    fun resetFilter() {
        highPass.reset()
        lowPass.reset()
    }

    /** Processes interleaved native-order Float32 in place. [mediaUs] is the first frame's media time. */
    fun process(output: ByteBuffer, channelCount: Int, sampleRate: Int, mediaUs: Long) {
        val requested = command.get()
        if (requested !== seen) {
            seen = requested
            when {
                requested == null -> {
                    bass = null
                    treble = null
                    bassLevel = 1f
                    trebleLevel = 1f
                    engagement = 0f
                }
                requested.restoreUs != NONE -> {
                    bass = EqAutomation(mediaUs, bassLevel, 1f, requested.restoreUs)
                    treble = EqAutomation(mediaUs, trebleLevel, 1f, requested.restoreUs)
                }
                else -> {
                    bass = requested.bass
                    treble = requested.treble
                }
            }
        }
        if (bass == null && treble == null) return
        // An offset the sink never reported would put every buffer past the
        // automation; pass through instead of cutting for no reason.
        if (channelCount !in 1..2 || sampleRate <= 0 || mediaUs !in 0L..MAX_MEDIA_US) return
        if (sampleRate != rate) {
            rate = sampleRate
            highPass.invalidate()
            lowPass.invalidate()
            resetFilter()
        }
        val samples = output.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val frames = samples.remaining() / channelCount
        val base = samples.position()
        val usPerFrame = 1_000_000.0 / sampleRate
        val engageStep = 1f / (ENGAGE_MS * sampleRate / 1000f)
        for (frame in 0 until frames) {
            val timeUs = mediaUs + (frame * usPerFrame).toLong()
            val bassNow = bass?.levelAt(timeUs) ?: 1f
            val trebleNow = treble?.levelAt(timeUs) ?: 1f
            val wanted = if (needsFilter(bass, bassNow, timeUs) || needsFilter(treble, trebleNow, timeUs)) 1f else 0f
            engagement = if (wanted > engagement) minOf(wanted, engagement + engageStep) else maxOf(wanted, engagement - engageStep)
            if (frame % COEFFICIENT_BLOCK == 0) {
                highPass.tune(sampleRate, BASS_OPEN_HZ * (BASS_CUT_HZ / BASS_OPEN_HZ).pow(1.0 - bassNow), highPass = true)
                lowPass.tune(sampleRate, TREBLE_OPEN_HZ * (TREBLE_CUT_HZ / TREBLE_OPEN_HZ).pow(1.0 - trebleNow), highPass = false)
            }
            for (channel in 0 until channelCount) {
                val index = base + frame * channelCount + channel
                val x = samples.get(index).toDouble()
                // Filters run even while dry so they are settled when a cut begins.
                val y = lowPass.process(channel, highPass.process(channel, x))
                if (engagement > 0f) samples.put(index, (x + engagement * (y - x)).toFloat())
            }
        }
        val endUs = mediaUs + (frames * usPerFrame).toLong()
        bassLevel = bass?.levelAt(endUs) ?: 1f
        trebleLevel = treble?.levelAt(endUs) ?: 1f
        // Both bands open again after their automation: back to idle and bit-exact.
        val settled = listOfNotNull(bass, treble).all { it.settlesOpen && endUs >= it.atUs + it.rampUs }
        if (settled && engagement == 0f) {
            bass = null
            treble = null
            if (command.compareAndSet(requested, null)) seen = null
            resetFilter()
        }
    }

    /** Untouched until a band's own automation starts moving, or while it sits closed. */
    private fun needsFilter(automation: EqAutomation?, level: Float, timeUs: Long): Boolean {
        automation ?: return false
        if (level < 1f) return true
        return !automation.settlesOpen && timeUs >= automation.atUs
    }

    private class Biquad {
        private var b0 = 1.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0
        private var tunedHz = Double.NaN
        private val z1 = DoubleArray(2)
        private val z2 = DoubleArray(2)

        fun invalidate() {
            tunedHz = Double.NaN
        }

        fun reset() {
            z1.fill(0.0)
            z2.fill(0.0)
        }

        fun tune(sampleRate: Int, cutoffHz: Double, highPass: Boolean) {
            val hz = cutoffHz.coerceAtMost(sampleRate * 0.45)
            if (hz == tunedHz) return
            tunedHz = hz
            val w0 = 2.0 * PI * hz / sampleRate
            val alpha = sin(w0) / sqrt(2.0)
            val cosW0 = cos(w0)
            val a0 = 1.0 + alpha
            if (highPass) {
                b0 = (1.0 + cosW0) / 2.0 / a0
                b1 = -(1.0 + cosW0) / a0
            } else {
                b0 = (1.0 - cosW0) / 2.0 / a0
                b1 = (1.0 - cosW0) / a0
            }
            b2 = b0
            a1 = -2.0 * cosW0 / a0
            a2 = (1.0 - alpha) / a0
        }

        fun process(channel: Int, x: Double): Double {
            val y = b0 * x + z1[channel]
            z1[channel] = b1 * x - a1 * y + z2[channel]
            z2[channel] = b2 * x - a2 * y
            return y
        }
    }

    private companion object {
        const val BASS_OPEN_HZ = 10.0
        const val BASS_CUT_HZ = 150.0
        const val TREBLE_OPEN_HZ = 20_000.0
        const val TREBLE_CUT_HZ = 2_500.0
        const val ENGAGE_MS = 20f
        const val COEFFICIENT_BLOCK = 32
        const val NONE = Long.MIN_VALUE
        /** Six hours: anything later is a timebase error, not a song. */
        const val MAX_MEDIA_US = 6L * 3_600_000_000L
    }
}
