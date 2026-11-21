package com.lastwave.app.playback.automix

import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.automix.AutoMixSignals.RATE
import com.lastwave.app.playback.automix.analysis.AcousticSignature
import com.lastwave.app.playback.automix.analysis.TrackAnalyzer
import com.lastwave.app.playback.automix.api.AutoMixSettings
import com.lastwave.app.playback.automix.engine.BassSwapStage
import com.lastwave.app.playback.automix.engine.MixCurve
import com.lastwave.app.playback.automix.engine.MixPlan
import com.lastwave.app.playback.automix.engine.PlanInput
import com.lastwave.app.playback.automix.engine.TransitionPlanner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Offline transition renderer: analyses two synthetic songs exactly as the
 * app does, plans the mix, renders it with the real gain curve and bass
 * stage, and writes the result to build/automix-renders for listening. The
 * assertions are the objective half of "does it sound right".
 */
class AutoMixRenderTest {

    private class Rendered(val pcm: FloatArray, val plan: MixPlan, val startOutMs: Double)

    @Test
    fun beatmix_staysOnTheBeatWithoutClippingOrDipping() {
        val outgoing = AutoMixSignals.song(124.0, 60.0, firstDownbeatS = 0.2, tailSilenceS = 1.5, seed = 3)
        val incoming = AutoMixSignals.song(126.0, 60.0, firstDownbeatS = 0.45, leadSilenceS = 0.3, seed = 4)
        val rendered = render(outgoing, incoming, "beatmix")
        val plan = rendered.plan
        assertTrue("expected a beatmix, got ${plan.style}", plan.beatLocked)

        // Every outgoing beat inside the overlap must meet an incoming beat (ground-truth grids).
        val outBeat = 60_000.0 / 124
        val inBeat = 60_000.0 / 126
        var t = 200.0 + Math.ceil((plan.cueOutMs - 200.0) / outBeat) * outBeat
        while (t < plan.endOutMs) {
            val inMs = plan.incomingTimeFor(t.toLong()) - 750.0
            val phase = ((inMs % inBeat) + inBeat) % inBeat
            val error = minOf(phase, inBeat - phase)
            assertTrue("beat at ${t.toLong()}ms is ${error.toInt()}ms off", error <= 20.0)
            t += outBeat
        }
        assertHealthy(rendered)
    }

    @Test
    fun beatlessMaterial_fadesCleanly() {
        val outgoing = AutoMixSignals.noise(60.0, seed = 5).also { pcm -> for (i in pcm.indices) pcm[i] *= 0.5f }
        val incoming = AutoMixSignals.song(120.0, 60.0, padLevel = 0.2, seed = 6)
        val rendered = render(outgoing, incoming, "fade")
        assertFalse(rendered.plan.beatLocked)
        assertHealthy(rendered)
    }

    private fun assertHealthy(rendered: Rendered) {
        val peak = rendered.pcm.maxOf { abs(it) }
        assertTrue("summed peak $peak clips", peak <= 1f)
        // About one bar per window: shorter ones swing 14 dB between kick and snare blocks.
        val window = RATE * 2
        val hop = RATE / 4
        val levels = (0..(rendered.pcm.size - window) / hop).map { index ->
            var sum = 0.0
            for (i in index * hop until index * hop + window) sum += rendered.pcm[i] * rendered.pcm[i]
            20 * log10(sqrt(sum / window) + 1e-9)
        }
        val median = levels.sorted()[levels.size / 2]
        val worst = levels.min()
        assertTrue("level dips ${"%.1f".format(median - worst)} dB below the median", median - worst <= 4.5)
    }

    private fun render(outgoing: FloatArray, incoming: FloatArray, name: String): Rendered {
        val outDuration = outgoing.size * 1000L / RATE
        val inDuration = incoming.size * 1000L / RATE
        val window = 40 * RATE
        val outro = TrackAnalyzer.analyze(outgoing.copyOfRange(outgoing.size - window, outgoing.size), RATE, (outgoing.size - window) * 1000.0 / RATE)
        val intro = TrackAnalyzer.analyze(incoming.copyOf(window), RATE, 0.0)
        val plan = TransitionPlanner.plan(
            PlanInput(
                outgoing = PlayableTrack(title = "out", artist = "a"),
                incoming = PlayableTrack(title = "in", artist = "b"),
                outgoingDurationMs = outDuration,
                incomingDurationMs = inDuration,
                outgoingSignature = AcousticSignature(key = "o", durationMs = outDuration, intro = null, outro = outro, vocals = null),
                incomingSignature = AcousticSignature(key = "i", durationMs = inDuration, intro = intro, outro = null, vocals = null),
                settings = AutoMixSettings(autoMixEnabled = true),
                userSpeed = 1f,
                userPitch = 1f,
            ),
        ) as MixPlan

        val outStage = BassSwapStage()
        val inStage = BassSwapStage()
        plan.bassSwapAtMs?.let { at ->
            outStage.schedule(at * 1000, 1f, 0f, plan.bassSwapRampMs * 1000)
            inStage.schedule(plan.incomingTimeFor(at) * 1000, 0f, 1f, (plan.bassSwapRampMs * 1000 * plan.tempoRatio).toLong())
        }
        val startOut = plan.cueOutMs - 4_000.0
        val endOut = plan.endOutMs + 4_000.0
        val frames = ((endOut - startOut) * RATE / 1000).toInt()
        val mix = FloatArray(frames)
        val chunk = RATE / 100
        var frame = 0
        while (frame < frames) {
            val size = minOf(chunk, frames - frame)
            val tOut = startOut + frame * 1000.0 / RATE
            val outBlock = FloatArray(size) { sampleAt(outgoing, tOut + it * 1000.0 / RATE) }
            val inStartMs = plan.incomingTimeFor(tOut.toLong()).toDouble().coerceAtLeast(0.0)
            val inBlock = FloatArray(size) { sampleAt(incoming, inStartMs + it * 1000.0 * plan.tempoRatio / RATE) }
            outStage.processMono(outBlock, RATE, (tOut * 1000).toLong())
            inStage.processMono(inBlock, (RATE / plan.tempoRatio).toInt(), (inStartMs * 1000).toLong())
            val progress = ((inStartMs - plan.cueInMs) / (plan.overlapMs * plan.tempoRatio)).toFloat()
            val (outGain, inGain) = if (tOut < plan.cueOutMs) 1f to 0f else MixCurve.gains(progress, plan.shape)
            for (i in 0 until size) mix[frame + i] = outBlock[i] * outGain + inBlock[i] * inGain
            frame += size
        }
        writeWav(File("build/automix-renders/$name.wav"), mix)
        return Rendered(mix, plan, startOut)
    }

    private fun BassSwapStage.processMono(block: FloatArray, rate: Int, mediaUs: Long) {
        val buffer = ByteBuffer.allocateDirect(block.size * 4).order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(block)
        process(buffer, 1, rate, mediaUs)
        buffer.asFloatBuffer().get(block)
    }

    private fun sampleAt(pcm: FloatArray, ms: Double): Float {
        val position = ms * RATE / 1000
        val index = position.toInt()
        if (index < 0 || index + 1 >= pcm.size) return 0f
        val fraction = (position - index).toFloat()
        return pcm[index] * (1 - fraction) + pcm[index + 1] * fraction
    }

    private fun writeWav(file: File, pcm: FloatArray) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val data = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            data.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
            data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
            data.put("data".toByteArray()).putInt(pcm.size * 2)
            for (sample in pcm) data.putShort((sample.coerceIn(-1f, 1f) * 32_767).toInt().toShort())
            raf.write(data.array())
        }
    }
}
