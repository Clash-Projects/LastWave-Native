package com.lastwave.app.playback.automix.analysis

import com.lastwave.app.playback.automix.api.Bpm
import com.lastwave.app.playback.automix.api.Millis
import kotlin.math.abs
import kotlin.math.roundToInt

data class PhraseBoundary(
    val introDropMs: Long,
    val outroBreakdownMs: Long,
    val silenceTailMs: Long,
)

data class BeatGridEstimate(
    val bpm: Bpm,
    val beatIntervalMs: Long,
    val firstDownbeatMs: Long,
    val downbeatAnchorsMs: List<Long>,  // 4-bar and 8-bar downbeat timestamps
    val phraseBoundary: PhraseBoundary,
)

object DownbeatGridEstimator {

    /**
     * Estimates beat grid, downbeat anchors, and structural phrase boundaries from mono PCM buffer.
     */
    fun estimateGrid(
        pcm: FloatArray,
        sampleRate: Float = 22050f,
        durationMs: Long,
        metadataBpm: Float? = null,
    ): BeatGridEstimate {
        val calculatedBpm = metadataBpm?.takeIf { it in 45f..240f }
            ?: detectSpectralBpm(pcm, sampleRate)
            ?: 120.0f

        val safeBpm = Bpm(calculatedBpm)
        val beatIntervalMs = ((60.0 / safeBpm.value) * 1000.0).toLong().coerceIn(250L, 1500L)
        val barIntervalMs = beatIntervalMs * 4L

        // Detect onset of the first significant downbeat
        val firstDownbeatMs = detectFirstDownbeat(pcm, sampleRate, beatIntervalMs)

        // Generate 4-bar downbeat anchors across the duration
        val anchors = mutableListOf<Long>()
        var cursor = firstDownbeatMs
        while (cursor < durationMs) {
            anchors.add(cursor)
            cursor += barIntervalMs
        }

        // Structural phrase detection (intro drop typically 16 or 32 bars in, outro breakdown in final 20-40s)
        val introDropMs = minOf(firstDownbeatMs + (barIntervalMs * 4L), durationMs / 4L)
        val outroBreakdownMs = maxOf(durationMs - (barIntervalMs * 8L), durationMs / 2L)
        val silenceTailMs = detectSilenceTail(pcm, sampleRate, durationMs)

        return BeatGridEstimate(
            bpm = safeBpm,
            beatIntervalMs = beatIntervalMs,
            firstDownbeatMs = firstDownbeatMs,
            downbeatAnchorsMs = anchors,
            phraseBoundary = PhraseBoundary(
                introDropMs = introDropMs,
                outroBreakdownMs = outroBreakdownMs,
                silenceTailMs = silenceTailMs,
            )
        )
    }

    /**
     * Checks if two BPMs are harmonically equivalent through half-time or double-time.
     */
    fun isHarmonicallyEquivalent(bpmA: Float, bpmB: Float, tolerancePercent: Float = 4.0f): Boolean {
        if (bpmA <= 0f || bpmB <= 0f) return false
        val tol = tolerancePercent / 100.0f

        // 1:1 match
        if (abs(bpmA - bpmB) / bpmB <= tol) return true
        // 1:2 (half-time: e.g. 70 bpm vs 140 bpm)
        if (abs(bpmA * 2.0f - bpmB) / bpmB <= tol) return true
        // 2:1 (double-time: e.g. 140 bpm vs 70 bpm)
        if (abs(bpmA - bpmB * 2.0f) / (bpmB * 2.0f) <= tol) return true

        return false
    }

    private fun detectSpectralBpm(pcm: FloatArray, sampleRate: Float): Float? {
        if (pcm.isEmpty()) return null

        // Onset energy envelope extraction
        val hopSize = (sampleRate * 0.010f).toInt() // 10ms hops
        val numFrames = pcm.size / hopSize
        if (numFrames < 100) return null

        val onsets = FloatArray(numFrames)
        var prevEnergy = 0.0f

        for (f in 0 until numFrames) {
            var energy = 0.0f
            val start = f * hopSize
            for (i in 0 until hopSize) {
                val s = pcm[start + i]
                energy += s * s
            }
            val diff = energy - prevEnergy
            onsets[f] = if (diff > 0.0f) diff else 0.0f
            prevEnergy = energy
        }

        // Autocorrelation over onset curve for lag 60..200 BPM
        // 60 BPM = 1000ms = 100 frames; 200 BPM = 300ms = 30 frames
        var bestCorr = 0.0f
        var bestLag = 50 // default ~120 bpm (500ms)

        for (lag in 30..100) {
            var corr = 0.0f
            for (i in 0 until (numFrames - lag)) {
                corr += onsets[i] * onsets[i + lag]
            }
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }

        val detectedPeriodSec = bestLag * 0.010f
        if (detectedPeriodSec <= 0f) return null
        val detectedBpm = 60.0f / detectedPeriodSec
        return detectedBpm.coerceIn(50.0f, 220.0f)
    }

    private fun detectFirstDownbeat(pcm: FloatArray, sampleRate: Float, beatIntervalMs: Long): Long {
        val windowSamples = minOf((sampleRate * 10.0f).toInt(), pcm.size) // search first 10 seconds
        val hop = (sampleRate * 0.020f).toInt()

        var maxEnergy = 0.0f
        var maxSampleIdx = 0

        for (i in 0 until windowSamples - hop step hop) {
            var energy = 0.0f
            for (j in 0 until hop) {
                val s = pcm[i + j]
                energy += s * s
            }
            if (energy > maxEnergy) {
                maxEnergy = energy
                maxSampleIdx = i
            }
        }

        val estimatedMs = ((maxSampleIdx.toDouble() / sampleRate.toDouble()) * 1000.0).toLong()
        // Quantize to closest beat grid point
        return if (beatIntervalMs > 0L) {
            val beatNum = (estimatedMs.toDouble() / beatIntervalMs.toDouble()).roundToInt()
            (beatNum * beatIntervalMs).coerceAtLeast(0L)
        } else {
            estimatedMs.coerceAtLeast(0L)
        }
    }

    private fun detectSilenceTail(pcm: FloatArray, sampleRate: Float, durationMs: Long): Long {
        val checkWindow = minOf((sampleRate * 5.0f).toInt(), pcm.size) // check last 5 seconds
        val startIdx = pcm.size - checkWindow
        val hop = (sampleRate * 0.050f).toInt()

        for (i in pcm.size - hop downTo startIdx step hop) {
            var energy = 0.0f
            for (j in 0 until hop) {
                val s = pcm[i + j]
                energy += s * s
            }
            val rms = kotlin.math.sqrt(energy / hop)
            if (rms > 0.01f) {
                // Non-silence found
                val nonSilenceMs = ((i.toDouble() / sampleRate.toDouble()) * 1000.0).toLong()
                return maxOf(0L, durationMs - nonSilenceMs)
            }
        }
        return 0L
    }
}
