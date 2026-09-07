package com.lastwave.app.playback

import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Real-signal feed for the analog VU meter, built to IEC 60268-17 / ANSI
 * C16.5-1942 ballistics (the genuine VU spec, not a peak meter):
 *
 *  - Full-wave-rectified-style RMS detection, integrated across chunks
 *    (a real movement integrates; transients barely deflect it).
 *  - Envelope follower with symmetric τ = 65 ms attack AND release, which
 *    is exactly 300 ms to 99% (1−e^(−300/65) = 0.99) with matching fall.
 *    Same recipe as DSP Concepts' IEC 60268-17 meter mode and JUCE's
 *    RMS ballistics filter.
 *  - A one-pole 180 Hz lowpass splits the bass band so kicks punch while
 *    mids/highs only add body (75/25 mix).
 *  - A slow peak follower auto-references each track so peaks park at 90%
 *    of the sweep; the 1–1.5% overshoot lives in the UI needle spring.
 *
 * [NativePcmAudioProcessor] calls [onProcessedPcm] with every chunk of
 * decoded Float32 PCM (pre-volume, full scale). Published at ~20 Hz.
 *
 * Plain Kotlin object (not Hilt): the processor is constructed manually and
 * the UI collects the flow directly. Never throws — metering must not break
 * playback. When no data flows (paused, fallback sink, Cast) the flow goes
 * stale and the UI falls back to its simulated groove.
 */
object AudioLevelMonitor {

    data class Levels(
        val bass: Float = 0f,
        val energy: Float = 0f,
        val updatedMs: Long = 0L,
    )

    private const val BASS_CUTOFF_HZ = 180f
    /** Hard peak ceiling: 90% of the needle sweep, just past 0 dB. */
    private const val PEAK_CEILING = 0.90f
    private const val PEAK_FLOOR = 0.02f
    /** Per 50 ms publish tick: 0.996^20 ≈ 0.92/s, i.e. ~8%/s release. */
    private const val PEAK_DECAY = 0.996f
    private const val PUBLISH_MS = 50L
    /**
     * IEC 60268-17 envelope coefficient per 50 ms tick:
     * α = 1−e^(−50/65) ≈ 0.537 — symmetric attack/release, i.e. 300 ms
     * to 99% rise with matching fall.
     */
    private const val VU_ALPHA = 0.537f

    private val _levels = MutableStateFlow(Levels())
    val levels: StateFlow<Levels> = _levels.asStateFlow()

    @Volatile private var lpY = 0f
    // Mean-square accumulators across chunks between 50 ms publish ticks:
    // a real movement integrates energy, it does not track chunk peaks.
    @Volatile private var accBassSq = 0.0
    @Volatile private var accEngSq = 0.0
    @Volatile private var accN = 0L
    @Volatile private var vuBass = 0f
    @Volatile private var vuEnergy = 0f
    @Volatile private var peakBass = PEAK_FLOOR
    @Volatile private var peakEnergy = PEAK_FLOOR
    @Volatile private var lastPublishMs = 0L

    fun onProcessedPcm(output: ByteBuffer, frameCount: Int, channelCount: Int, sampleRateHz: Int) {
        if (frameCount <= 0 || channelCount !in 1..2 || sampleRateHz <= 0) return
        try {
            val fb = output.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
            val totalFloats = frameCount * channelCount
            if (fb.capacity() < totalFloats) return
            // Bound cost: at most ~512 sampled frames per call.
            val step = maxOf(1, frameCount / 512)
            val alpha = (1f - exp(-2f * PI.toFloat() * BASS_CUTOFF_HZ / sampleRateHz.toFloat()))
                .coerceIn(0.001f, 1f)
            var y = lpY
            var sumSq = 0.0
            var lpSumSq = 0.0
            var n = 0
            var f = 0
            while (f < frameCount) {
                val base = f * channelCount
                val x = if (channelCount == 2) {
                    (fb.get(base) + fb.get(base + 1)) * 0.5f
                } else {
                    fb.get(base)
                }
                y += alpha * (x - y)
                sumSq += (x * x).toDouble()
                lpSumSq += (y * y).toDouble()
                n++
                f += step
            }
            if (n == 0) return
            lpY = y
            accBassSq += lpSumSq
            accEngSq += sumSq
            accN += n
            maybePublish()
        } catch (_: Throwable) {
            // Metering must never break playback.
        }
    }

    private fun maybePublish() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPublishMs < PUBLISH_MS || accN == 0L) return
        lastPublishMs = now
        val rmsBass = sqrt(accBassSq / accN).toFloat().coerceIn(0f, 2f)
        val rmsEng = sqrt(accEngSq / accN).toFloat().coerceIn(0f, 2f)
        accBassSq = 0.0
        accEngSq = 0.0
        accN = 0L
        // IEC 60268-17 ballistics, symmetric: one-pole τ = 65 ms both ways.
        vuBass += (rmsBass - vuBass) * VU_ALPHA
        vuEnergy += (rmsEng - vuEnergy) * VU_ALPHA
        // Slow peak follower (instant up, ~8%/s down): auto-gain so each
        // track's peaks land on the ceiling — quiet and loud masters
        // read the same, and the needle can never peg past the edge.
        peakBass = maxOf(vuBass, peakBass * PEAK_DECAY, PEAK_FLOOR)
        peakEnergy = maxOf(vuEnergy, peakEnergy * PEAK_DECAY, PEAK_FLOOR)
        val normBass = (vuBass / peakBass).coerceIn(0f, 1f)
        val normEnergy = (vuEnergy / peakEnergy).coerceIn(0f, 1f)
        // Bass leads (kicks punch hardest), broadband adds body so
        // vocals/hats move the needle too — at lower weight.
        val mixed = (normBass * 0.75f + normEnergy * 0.25f).coerceIn(0f, 1f)
        _levels.value = Levels(
            bass = mixed * PEAK_CEILING,
            energy = normEnergy * PEAK_CEILING,
            updatedMs = now,
        )
    }

    fun reset() {
        lpY = 0f
        accBassSq = 0.0
        accEngSq = 0.0
        accN = 0L
        vuBass = 0f
        vuEnergy = 0f
        peakBass = PEAK_FLOOR
        peakEnergy = PEAK_FLOOR
    }
}
