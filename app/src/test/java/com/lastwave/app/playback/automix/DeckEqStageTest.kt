package com.lastwave.app.playback.automix

import com.lastwave.app.playback.automix.engine.DeckEqStage
import com.lastwave.app.playback.automix.engine.EqAutomation
import com.lastwave.app.playback.automix.engine.MixCurve
import com.lastwave.app.playback.automix.api.MixShape
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

class DeckEqStageTest {
    private val rate = 48_000

    private fun buffer(samples: FloatArray): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * 4).order(ByteOrder.nativeOrder()).also {
            it.asFloatBuffer().put(samples)
        }

    private fun read(buffer: ByteBuffer): FloatArray {
        val floats = buffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        return FloatArray(floats.remaining()).also { floats.get(it) }
    }

    private fun rmsDb(samples: FloatArray, from: Int = 0, to: Int = samples.size): Double {
        var sum = 0.0
        for (i in from until to) sum += samples[i] * samples[i]
        return 20 * log10(sqrt(sum / (to - from)) + 1e-12)
    }

    /** Mono run of [seconds] in 10 ms buffers starting at media time 0. */
    private fun run(stage: DeckEqStage, input: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        val chunk = rate / 100
        var offset = 0
        while (offset < input.size) {
            val size = minOf(chunk, input.size - offset)
            val buf = buffer(input.copyOfRange(offset, offset + size))
            stage.process(buf, 1, rate, offset * 1_000_000L / rate)
            read(buf).copyInto(out, offset)
            offset += size
        }
        return out
    }

    @Test
    fun idleStage_isBitExact() {
        val input = AutoMixSignals.sine(50.0, 1.0, rate)
        assertArrayEquals(input, run(DeckEqStage(), input), 0f)
    }

    @Test
    fun cut_removesBassKeepsMidsAndLeavesTheSignalBeforeItUntouched() {
        val stage = DeckEqStage().apply { schedule(bass = EqAutomation(500_000, 1f, 0f, 100_000), treble = null) }
        val bass = AutoMixSignals.sine(50.0, 1.5, rate)
        val bassOut = run(stage, bass)
        assertArrayEquals(bass.copyOf(rate / 2), bassOut.copyOf(rate / 2), 0f)
        val cut = rmsDb(bassOut, rate, rate * 3 / 2) - rmsDb(bass, rate, rate * 3 / 2)
        assertTrue("50 Hz only $cut dB down", cut <= -15.0)

        val mids = AutoMixSignals.sine(1_000.0, 1.5, rate)
        val midStage = DeckEqStage().apply { schedule(bass = EqAutomation(500_000, 1f, 0f, 100_000), treble = null) }
        val midLoss = rmsDb(run(midStage, mids), rate, rate * 3 / 2) - rmsDb(mids, rate, rate * 3 / 2)
        assertEquals(0.0, midLoss, 0.3)
    }

    @Test
    fun restore_returnsToBitExactPassThrough() {
        val stage = DeckEqStage().apply { schedule(bass = EqAutomation(0, 0f, 0f, 1), treble = EqAutomation(0, 0f, 0f, 1)) }
        val input = AutoMixSignals.sine(60.0, 2.0, rate)
        run(stage, input.copyOf(rate / 2))
        stage.restore(rampUs = 100_000)
        val tail = input.copyOfRange(rate / 2, input.size)
        val out = FloatArray(tail.size)
        val chunk = rate / 100
        var offset = 0
        while (offset < tail.size) {
            val buf = buffer(tail.copyOfRange(offset, offset + chunk))
            stage.process(buf, 1, rate, 500_000L + offset * 1_000_000L / rate)
            read(buf).copyInto(out, offset)
            offset += chunk
        }
        // After the 100 ms ramp the stage is idle again.
        val settled = rate / 5
        assertArrayEquals(tail.copyOfRange(settled, tail.size), out.copyOfRange(settled, tail.size), 0f)
    }

    @Test
    fun unreportedTimebase_isPassedThrough() {
        val stage = DeckEqStage().apply { schedule(bass = EqAutomation(0, 0f, 0f, 1), treble = null) }
        val input = AutoMixSignals.sine(50.0, 0.1, rate)
        val buf = buffer(input)
        stage.process(buf, 1, rate, 1_000_000_000_000L)
        assertArrayEquals(input, read(buf), 0f)
    }

    @Test
    fun trebleCut_removesHighsKeepsMids() {
        val closed = { DeckEqStage().apply { schedule(bass = null, treble = EqAutomation(0, 0f, 0f, 1)) } }
        val highs = AutoMixSignals.sine(8_000.0, 1.0, rate)
        val highLoss = rmsDb(run(closed(), highs), rate / 2, rate) - rmsDb(highs, rate / 2, rate)
        assertTrue("8 kHz only $highLoss dB down", highLoss <= -10.0)
        val mids = AutoMixSignals.sine(1_000.0, 1.0, rate)
        assertEquals(0.0, rmsDb(run(closed(), mids), rate / 2, rate) - rmsDb(mids, rate / 2, rate), 0.5)
    }

    @Test
    fun mixCurve_neverOverloadsAndEndsClean() {
        for (shape in MixShape.values()) {
            val (out0, in0) = MixCurve.gains(0f, shape)
            val (out1, in1) = MixCurve.gains(1f, shape)
            assertEquals(1f, out0, 1e-6f)
            assertEquals(0f, in0, 1e-6f)
            assertEquals(0f, out1, 1e-6f)
            assertEquals(1f, in1, 1e-6f)
            var lastIn = 0f
            for (step in 0..100) {
                val (o, i) = MixCurve.gains(step / 100f, shape)
                val cap = if (shape == MixShape.BASS_SWAP) MixCurve.MAX_SUM_BASS_SWAP else MixCurve.MAX_SUM
                assertTrue("$shape sum ${o + i} at $step", o + i <= cap + 1e-5f)
                assertTrue("$shape incoming must only rise", i >= lastIn - 1e-6f)
                lastIn = i
            }
        }
    }
}
