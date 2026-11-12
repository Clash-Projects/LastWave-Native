package com.lastwave.app.playback.automix.analysis

import com.lastwave.app.data.lyrics.LyricLine
import kotlin.math.abs

data class VocalSegment(
    val startMs: Long,
    val endMs: Long,
)

data class VocalActivityMask(
    val windowStartMs: Long,
    val windowDurationMs: Long,
    val vocalSegments: List<VocalSegment>,
) {
    fun isVocalActiveAt(timeMs: Long): Boolean {
        return vocalSegments.any { timeMs in it.startMs..it.endMs }
    }

    val hasActiveVocals: Boolean
        get() = vocalSegments.isNotEmpty()
}

object VocalPresenceGateDetector {
    const val MAX_ALLOWED_VOCAL_OVERLAP_MS = 350L

    /**
     * Builds a VocalActivityMask from audio spectral energy in vocal band (300 Hz - 3.4 kHz)
     * merged with synchronized lyric timestamps if present.
     */
    fun createVocalMask(
        windowStartMs: Long,
        windowDurationMs: Long,
        pcm: FloatArray,
        sampleRate: Float = 22050f,
        lyrics: List<LyricLine>? = null,
    ): VocalActivityMask {
        val detectedSegments = mutableListOf<VocalSegment>()

        // 1. Incorporate synced lyrics if present
        if (!lyrics.isNullOrEmpty()) {
            val windowEndMs = windowStartMs + windowDurationMs
            val relevantLines = lyrics.filter { line ->
                line.timeMs in (windowStartMs - 2000L)..windowEndMs
            }

            for (i in relevantLines.indices) {
                val current = relevantLines[i]
                val nextStart = relevantLines.getOrNull(i + 1)?.timeMs
                // Approximate line duration: 3.5s default or until next line
                val lineDuration = if (nextStart != null && nextStart > current.timeMs) {
                    minOf(nextStart - current.timeMs, 6_000L)
                } else {
                    3_500L
                }
                val start = maxOf(windowStartMs, current.timeMs)
                val end = minOf(windowEndMs, current.timeMs + lineDuration)
                if (end > start) {
                    detectedSegments.add(VocalSegment(start, end))
                }
            }
        }

        // 2. Perform spectral vocal band estimation on PCM
        val spectralSegments = estimateSpectralVocalSegments(windowStartMs, windowDurationMs, pcm, sampleRate)
        detectedSegments.addAll(spectralSegments)

        // Merge contiguous and overlapping segments
        val merged = mergeSegments(detectedSegments)
        return VocalActivityMask(
            windowStartMs = windowStartMs,
            windowDurationMs = windowDurationMs,
            vocalSegments = merged,
        )
    }

    /**
     * Strict Vocal Protection Rule:
     * Calculates the total vocal-on-vocal overlap in milliseconds.
     * Returns true if overlap exceeds MAX_ALLOWED_VOCAL_OVERLAP_MS (350 ms).
     */
    fun hasVocalCollision(
        outgoingMask: VocalActivityMask,
        incomingMask: VocalActivityMask,
        spliceStartOutgoingMs: Long,
        spliceStartIncomingMs: Long,
        spliceDurationMs: Long,
    ): Boolean {
        if (!outgoingMask.hasActiveVocals || !incomingMask.hasActiveVocals) return false

        var totalOverlapMs = 0L
        val stepMs = 50L

        var t = 0L
        while (t < spliceDurationMs) {
            val outTime = spliceStartOutgoingMs + t
            val inTime = spliceStartIncomingMs + t

            val outVocal = outgoingMask.isVocalActiveAt(outTime)
            val inVocal = incomingMask.isVocalActiveAt(inTime)

            if (outVocal && inVocal) {
                totalOverlapMs += stepMs
                if (totalOverlapMs > MAX_ALLOWED_VOCAL_OVERLAP_MS) {
                    return true
                }
            }
            t += stepMs
        }

        return false
    }

    private fun estimateSpectralVocalSegments(
        windowStartMs: Long,
        windowDurationMs: Long,
        pcm: FloatArray,
        sampleRate: Float,
    ): List<VocalSegment> {
        val segments = mutableListOf<VocalSegment>()
        if (pcm.isEmpty()) return segments

        // 100ms analysis frame
        val frameSamples = (sampleRate * 0.100f).toInt()
        val numFrames = pcm.size / frameSamples
        var inVocal = false
        var vocalStartMs = 0L

        for (f in 0 until numFrames) {
            val frameOffset = f * frameSamples
            var vocalBandEnergy = 0.0f
            var totalEnergy = 0.0f

            // Approximate 300Hz - 3.4kHz vocal band energy
            for (i in 0 until frameSamples step 2) {
                val s = pcm[frameOffset + i]
                val sq = s * s
                totalEnergy += sq
                // Simple difference filter as crude vocal band emphasis
                val prev = if (i > 0) pcm[frameOffset + i - 1] else 0f
                val diff = s - prev
                vocalBandEnergy += diff * diff
            }

            val vocalRatio = if (totalEnergy > 1e-5f) vocalBandEnergy / totalEnergy else 0f
            val isFrameVocal = vocalRatio > 0.45f && totalEnergy > 0.005f
            val currentMs = windowStartMs + (f * 100L)

            if (isFrameVocal && !inVocal) {
                inVocal = true
                vocalStartMs = currentMs
            } else if (!isFrameVocal && inVocal) {
                inVocal = false
                if (currentMs - vocalStartMs >= 300L) { // minimum vocal burst 300ms
                    segments.add(VocalSegment(vocalStartMs, currentMs))
                }
            }
        }

        if (inVocal) {
            segments.add(VocalSegment(vocalStartMs, windowStartMs + windowDurationMs))
        }

        return segments
    }

    private fun mergeSegments(segments: List<VocalSegment>): List<VocalSegment> {
        if (segments.isEmpty()) return emptyList()
        val sorted = segments.sortedBy { it.startMs }
        val merged = mutableListOf<VocalSegment>()
        var current = sorted[0]

        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.startMs <= current.endMs + 100L) {
                current = VocalSegment(current.startMs, maxOf(current.endMs, next.endMs))
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }
}
