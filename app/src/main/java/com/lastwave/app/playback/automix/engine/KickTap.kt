package com.lastwave.app.playback.automix.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.math.exp

/**
 * Kick onsets in a deck's own playback media time, taken from the PCM the
 * deck is actually rendering. The offline grid comes from a separate decode
 * whose timeline can sit tens of milliseconds off playback (AAC priming vs
 * Opus pre-skip); both decks measured here share one clock and one detector,
 * so their difference is the flam a listener hears.
 *
 * Audio thread writes, main thread reads. Off unless [enabled].
 */
class KickTap {
    @Volatile var enabled = false
        set(value) {
            if (value && !field) count.set(0)
            field = value
        }

    private val onsets = AtomicLongArray(CAPACITY)
    private val count = AtomicInteger(0)
    private var rate = 0
    private var lowPassCoefficient = 0.0
    private var lowPassed = 0.0
    private var blockEnergy = 0.0
    private var blockFill = 0
    private var blockSize = 0
    private var fast = 0.0
    private var slow = 0.0
    private var fastDecay = 0.0
    private var slowDecay = 0.0
    private var lastOnsetUs = Long.MIN_VALUE
    /** Blocks left before onsets count: right after a reset the slow average is 0 and any sound reads as a kick. */
    private var warmupBlocks = 0
    private var blockStartUs = 0L

    /** Onsets (media us) at or after [sinceUs], oldest first. */
    fun onsetsSince(sinceUs: Long): LongArray {
        val total = count.get()
        val available = minOf(total, CAPACITY)
        val result = ArrayList<Long>(available)
        for (i in total - available until total) {
            val value = onsets.get(i % CAPACITY)
            if (value >= sinceUs) result += value
        }
        return result.toLongArray()
    }

    fun reset() {
        lowPassed = 0.0
        blockEnergy = 0.0
        blockFill = 0
        fast = 0.0
        slow = 0.0
        lastOnsetUs = Long.MIN_VALUE
        warmupBlocks = (SLOW_MS / BLOCK_MS).toInt()
    }

    fun process(output: ByteBuffer, channelCount: Int, sampleRate: Int, mediaUs: Long) {
        if (!enabled || channelCount !in 1..2 || sampleRate <= 0 || mediaUs < 0) return
        if (sampleRate != rate) configure(sampleRate)
        val samples = output.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val frames = samples.remaining() / channelCount
        val base = samples.position()
        for (frame in 0 until frames) {
            var mono = 0.0
            for (channel in 0 until channelCount) mono += samples.get(base + frame * channelCount + channel)
            mono /= channelCount
            lowPassed += lowPassCoefficient * (mono - lowPassed)
            if (blockFill == 0) blockStartUs = mediaUs + frame * 1_000_000L / sampleRate
            blockEnergy += lowPassed * lowPassed
            if (++blockFill == blockSize) closeBlock()
        }
    }

    private fun closeBlock() {
        val energy = blockEnergy / blockSize
        blockEnergy = 0.0
        blockFill = 0
        fast = fast * fastDecay + energy * (1 - fastDecay)
        val rising = fast > slow * ONSET_RATIO && fast > MIN_ENERGY
        slow = slow * slowDecay + energy * (1 - slowDecay)
        if (warmupBlocks > 0) {
            warmupBlocks--
            return
        }
        // MIN_VALUE means none yet; subtracting it would overflow and block every onset.
        if (rising && (lastOnsetUs == Long.MIN_VALUE || blockStartUs - lastOnsetUs > REFRACTORY_US)) {
            lastOnsetUs = blockStartUs
            val index = count.getAndIncrement()
            onsets.set(index % CAPACITY, blockStartUs)
        }
    }

    private fun configure(sampleRate: Int) {
        rate = sampleRate
        lowPassCoefficient = 1.0 - exp(-2.0 * Math.PI * LOW_PASS_HZ / sampleRate)
        blockSize = (sampleRate * BLOCK_MS / 1000).coerceAtLeast(1)
        fastDecay = exp(-BLOCK_MS / FAST_MS)
        slowDecay = exp(-BLOCK_MS / SLOW_MS)
        reset()
    }

    private companion object {
        const val CAPACITY = 64
        const val LOW_PASS_HZ = 150.0
        const val BLOCK_MS = 5
        const val FAST_MS = 10.0
        const val SLOW_MS = 300.0
        const val ONSET_RATIO = 2.5
        const val MIN_ENERGY = 1e-5
        const val REFRACTORY_US = 150_000L
    }
}

/** How far a deck's real kicks sit from its analysed beat grid. */
object KickTiming {
    private const val MIN_KICKS = 4
    private const val RECENT_KICKS = 16

    /**
     * Median offset (ms, within half a beat) of the latest kicks from their
     * nearest grid beats, or null when there are too few or they disagree by
     * more than an eighth of a beat (not a steady kick, so no evidence).
     */
    fun offsetMs(onsetsUs: LongArray?, grid: com.lastwave.app.playback.automix.analysis.BeatGrid): Double? {
        if (onsetsUs == null || onsetsUs.size < MIN_KICKS) return null
        val beat = grid.beatMs
        val offsets = onsetsUs.takeLast(RECENT_KICKS).map { us ->
            val raw = (us / 1000.0 - grid.downbeatMs) % beat
            val wrapped = (raw + beat) % beat
            if (wrapped > beat / 2) wrapped - beat else wrapped
        }.sorted()
        val median = offsets[offsets.size / 2]
        val spread = offsets.map { kotlin.math.abs(it - median) }.sorted()[offsets.size / 2]
        return if (spread <= beat / 8) median else null
    }
}
