package com.lastwave.app.playback.automix.analysis

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A constant-tempo beat grid fitted to one analysed window. Times are track media ms. */
@Serializable
data class BeatGrid(
    val bpm: Float,
    /** A bar start inside the analysed window; every other bar is a whole number of bars away. */
    val downbeatMs: Double,
    /** 0..1: how many grid beats land on a real onset, halved when the two window halves disagree. */
    val confidence: Float,
    /** 0..1: how clearly one of the four beat phases is the bar start. */
    val barConfidence: Float,
) {
    val beatMs: Double get() = 60_000.0 / bpm
    val barMs: Double get() = beatMs * BEATS_PER_BAR

    fun downbeatAtOrAfter(timeMs: Double): Double =
        downbeatMs + ceil((timeMs - downbeatMs) / barMs - 1e-9) * barMs

    fun downbeatAtOrBefore(timeMs: Double): Double =
        downbeatMs + floor((timeMs - downbeatMs) / barMs + 1e-9) * barMs

    companion object {
        const val BEATS_PER_BAR = 4
    }
}

enum class EndingStyle {
    /** The master fades out: mixing should start where the fade does. */
    FADE_OUT,
    /** Full level until a hard stop: the last hit must stay audible. */
    COLD,
    /** Anything else, e.g. a ringing last chord. */
    NATURAL,
}

enum class IntroStyle { COLD, BUILD }

/** What the planner may rely on about one analysed stretch of a track. */
@Serializable
data class WindowAnalysis(
    val startMs: Long,
    val endMs: Long,
    val grid: BeatGrid?,
    val camelotKey: String?,
    val keyConfidence: Float,
    /** First / last moment above the silence floor, or null when the whole window is silent. */
    val audibleStartMs: Long?,
    val audibleEndMs: Long?,
    val ending: EndingStyle,
    val fadeStartMs: Long?,
    val intro: IntroStyle,
    /** Bar starts where a new section begins (energy shift across 4-bar groups), for cueing on phrases. */
    val phrases: List<PhraseMark> = emptyList(),
    /**
     * Waveform for display: one column per [BAND_COLUMN_MS], each packed as
     * low << 16 | mid << 8 | high, 0..255 relative to the window's loudest band.
     */
    val bands: List<Int> = emptyList(),
)

@Serializable
data class PhraseMark(val ms: Long, val strengthDb: Float)

const val BAND_COLUMN_MS = 100

/**
 * Tempo, beat phase, bar phase, key and level contour from decoded mono PCM.
 * Pure Kotlin on purpose: every number the planner trusts can be checked in a
 * JVM test against synthetic audio with a known answer.
 */
object TrackAnalyzer {
    private const val ONSET_FFT = 512
    private const val HOP = 128
    private const val CHROMA_FFT = 4096
    private const val MIN_BPM = 70.0
    private const val MAX_BPM = 180.0
    private const val BASS_CUTOFF_HZ = 150.0
    private const val MIN_ANALYSIS_SECONDS = 6
    private const val SILENCE_FLOOR_DB = -50.0
    private const val SILENCE_BELOW_PEAK_DB = 35.0
    private const val PHRASE_SPAN = 4
    private const val PHRASE_MIN_DB = 2.0
    private const val BAND_RANGE_DB = 48.0

    fun analyze(pcm: FloatArray, sampleRate: Int, startMs: Double): WindowAnalysis {
        val endMs = startMs + pcm.size * 1000.0 / sampleRate
        val onsets = onsetFrames(pcm, sampleRate)
        val frameMs = HOP * 1000.0 / sampleRate
        val frameCenterMs = { frame: Double -> startMs + (frame * HOP + ONSET_FFT / 2) * 1000.0 / sampleRate }
        // Flux jumps when a hit enters the window's tail, not its centre; reading
        // onsets at the centre put every beat ~17 ms early.
        val onsetMs = { frame: Double -> startMs + (frame * HOP + ONSET_FFT * 7 / 8) * 1000.0 / sampleRate }
        val level = LevelContour(onsets.rmsDb, frameMs)
        val audibleStart = level.firstAudible()?.let { frameCenterMs(it.toDouble()).toLong() }
        val audibleEnd = level.lastAudible()?.let { frameCenterMs(it.toDouble()).toLong() }
        val enough = pcm.size >= sampleRate * MIN_ANALYSIS_SECONDS
        val grid = if (enough) fitGrid(onsets, sampleRate.toDouble() / HOP)?.let { fit ->
            BeatGrid(
                bpm = fit.bpm.toFloat(),
                downbeatMs = onsetMs(fit.downbeatFrame),
                confidence = fit.confidence.toFloat(),
                barConfidence = fit.barConfidence.toFloat(),
            )
        } else {
            null
        }
        val key = if (enough) estimateKey(pcm, sampleRate) else null
        val fadeStartFrame = level.fadeOutStart()
        return WindowAnalysis(
            startMs = startMs.toLong(),
            endMs = endMs.toLong(),
            grid = grid,
            camelotKey = key?.first,
            keyConfidence = key?.second ?: 0f,
            audibleStartMs = audibleStart,
            audibleEndMs = audibleEnd,
            ending = when {
                fadeStartFrame != null -> EndingStyle.FADE_OUT
                level.endsCold() -> EndingStyle.COLD
                else -> EndingStyle.NATURAL
            },
            fadeStartMs = fadeStartFrame?.let { frameCenterMs(it.toDouble()).toLong() },
            intro = if (level.startsCold()) IntroStyle.COLD else IntroStyle.BUILD,
            phrases = grid?.let { phraseMarks(it, onsets.rmsDb, frameCenterMs) }.orEmpty(),
            bands = bandColumns(onsets.bandEnergy, frameMs),
        )
    }

    private class Onsets(
        val novelty: FloatArray,
        val bassNovelty: FloatArray,
        val rmsDb: FloatArray,
        /** Per frame: low (< 200 Hz), mid, high (> 2 kHz) linear energy. */
        val bandEnergy: Array<DoubleArray>,
    )

    /** Downbeats where the 4 bars after differ from the 4 before by at least [PHRASE_MIN_DB]. */
    private fun phraseMarks(grid: BeatGrid, rmsDb: FloatArray, frameMs: (Double) -> Double): List<PhraseMark> {
        if (rmsDb.isEmpty()) return emptyList()
        val first = frameMs(0.0)
        val last = frameMs((rmsDb.size - 1).toDouble())
        val bars = generateSequence(grid.downbeatAtOrAfter(first)) { it + grid.barMs }
            .takeWhile { it + grid.barMs <= last }
            .toList()
        val levels = bars.map { start ->
            val from = ((start - first) / (last - first) * (rmsDb.size - 1)).toInt().coerceIn(0, rmsDb.lastIndex)
            val until = ((start + grid.barMs - first) / (last - first) * (rmsDb.size - 1)).toInt().coerceIn(from + 1, rmsDb.size)
            var power = 0.0
            for (i in from until until) power += Math.pow(10.0, rmsDb[i] / 10.0)
            10.0 * log10(power / (until - from) + 1e-12)
        }
        return (PHRASE_SPAN until bars.size - PHRASE_SPAN + 1).mapNotNull { k ->
            val before = levels.subList(k - PHRASE_SPAN, k).average()
            val after = levels.subList(k, k + PHRASE_SPAN).average()
            val shift = abs(after - before)
            if (shift >= PHRASE_MIN_DB) PhraseMark(bars[k].toLong(), shift.toFloat()) else null
        }
    }

    private fun bandColumns(bandEnergy: Array<DoubleArray>, frameMs: Double): List<Int> {
        // Columns by time, not by whole frames: 9 frames are 104.5 ms at 11 kHz,
        // which drifted the drawn waveform ~2 s across a window.
        val frames = bandEnergy[0].size
        val columns = (frames * frameMs / BAND_COLUMN_MS).toInt()
        if (columns == 0) return emptyList()
        val sums = Array(3) { DoubleArray(columns) }
        val counts = IntArray(columns)
        for (frame in 0 until frames) {
            val column = (frame * frameMs / BAND_COLUMN_MS).toInt()
            if (column >= columns) break
            counts[column]++
            for (band in 0 until 3) sums[band][column] += bandEnergy[band][frame]
        }
        val db = Array(3) { band ->
            DoubleArray(columns) { column -> 10.0 * log10(sums[band][column] / counts[column].coerceAtLeast(1) + 1e-12) }
        }
        val top = db.maxOf { it.max() }
        return List(columns) { column ->
            var packed = 0
            for (band in 0 until 3) {
                val value = ((db[band][column] - (top - BAND_RANGE_DB)) / BAND_RANGE_DB * 255).roundToInt().coerceIn(0, 255)
                packed = packed or (value shl (16 - band * 8))
            }
            packed
        }
    }

    /** Log-magnitude spectral flux (all bins and the kick band), plus per-frame RMS. */
    private fun onsetFrames(pcm: FloatArray, sampleRate: Int): Onsets {
        val count = if (pcm.size < ONSET_FFT) 0 else (pcm.size - ONSET_FFT) / HOP + 1
        val novelty = FloatArray(count)
        val bass = FloatArray(count)
        val rmsDb = FloatArray(count)
        val bandEnergy = Array(3) { DoubleArray(count) }
        val lowBin = (200.0 * ONSET_FFT / sampleRate).toInt()
        val highBin = (2_000.0 * ONSET_FFT / sampleRate).toInt()
        val window = hann(ONSET_FFT)
        val re = DoubleArray(ONSET_FFT)
        val im = DoubleArray(ONSET_FFT)
        val previous = DoubleArray(ONSET_FFT / 2)
        var previousBassEnergy = 0.0
        val bassBin = (BASS_CUTOFF_HZ * ONSET_FFT / sampleRate).toInt().coerceAtLeast(1)
        for (frame in 0 until count) {
            val offset = frame * HOP
            var energy = 0.0
            for (i in 0 until ONSET_FFT) {
                val sample = pcm[offset + i].toDouble()
                energy += sample * sample
                re[i] = sample * window[i]
                im[i] = 0.0
            }
            rmsDb[frame] = (10.0 * log10(energy / ONSET_FFT + 1e-12)).toFloat()
            fft(re, im)
            var flux = 0.0
            var bassEnergy = 0.0
            for (bin in 1 until ONSET_FFT / 2) {
                val linear = hypot(re[bin], im[bin])
                val magnitude = ln(1.0 + 1_000.0 * linear)
                val rise = magnitude - previous[bin]
                if (frame > 0 && rise > 0) flux += rise
                previous[bin] = magnitude
                val power = linear * linear
                if (bin <= bassBin) bassEnergy += power
                bandEnergy[if (bin <= lowBin) 0 else if (bin <= highBin) 1 else 2][frame] += power
            }
            novelty[frame] = flux.toFloat()
            // Kick detection wants linear energy: log flux makes a snare's
            // broadband noise rise as much in the bass bins as a kick does.
            bass[frame] = (bassEnergy - previousBassEnergy).coerceAtLeast(0.0).toFloat()
            previousBassEnergy = bassEnergy
        }
        return Onsets(adaptiveThreshold(novelty), adaptiveThreshold(bass), rmsDb, bandEnergy)
    }

    /** Removes the local mean so sustained loud passages do not read as onsets. */
    private fun adaptiveThreshold(signal: FloatArray, radius: Int = 16): FloatArray {
        val out = FloatArray(signal.size)
        var sum = 0.0
        var lo = 0
        var hi = -1
        for (i in signal.indices) {
            while (hi < minOf(signal.lastIndex, i + radius)) sum += signal[++hi]
            while (lo < i - radius) sum -= signal[lo++]
            val mean = sum / (hi - lo + 1)
            out[i] = (signal[i] - mean).coerceAtLeast(0.0).toFloat()
        }
        return out
    }

    private class GridFit(val bpm: Double, val downbeatFrame: Double, val confidence: Double, val barConfidence: Double)

    private fun fitGrid(onsets: Onsets, framesPerSecond: Double): GridFit? {
        val novelty = onsets.novelty
        if (novelty.size < framesPerSecond * MIN_ANALYSIS_SECONDS || novelty.all { it == 0f }) return null
        val coarse = coarsePeriod(novelty, framesPerSecond) ?: return null
        // Fine tempo: the comb that collects the most onset energy within +-3 %.
        var bestPeriod = coarse
        var bestScore = -1.0
        var step = -30
        while (step <= 30) {
            val period = coarse * (1.0 + step / 1_000.0)
            val score = bestPhase(novelty, period, 0, novelty.size).second
            if (score > bestScore) {
                bestScore = score
                bestPeriod = period
            }
            step++
        }
        val (phase, _) = bestPhase(novelty, bestPeriod, 0, novelty.size)
        val beats = generateSequence(phase) { it + bestPeriod }.takeWhile { it < novelty.size - 1 }.toList()
        if (beats.size < 16) return null

        val hitRate = beatHitRate(novelty, beats)
        val half = novelty.size / 2
        val firstHalf = bestPhase(novelty, bestPeriod, 0, half).first
        val secondHalf = bestPhase(novelty, bestPeriod, half, novelty.size).first
        val drift = abs(wrap(firstHalf - secondHalf, bestPeriod))
        val stable = drift <= bestPeriod * 0.08
        // Random onsets still put a +-1 frame hit near ~30 % of any grid.
        var confidence = ((hitRate - 0.3) / 0.6).coerceIn(0.0, 1.0)
        if (!stable) confidence *= 0.5

        // Bar phase: the beat slot carrying the most kick energy starts the bar.
        val slotScores = DoubleArray(BeatGrid.BEATS_PER_BAR)
        beats.forEachIndexed { index, frame ->
            slotScores[index % BeatGrid.BEATS_PER_BAR] += peakNear(onsets.bassNovelty, frame)
        }
        val ranked = slotScores.indices.sortedByDescending { slotScores[it] }
        val top = slotScores[ranked[0]]
        val barConfidence = if (top <= 0.0) 0.0 else ((top - slotScores[ranked[1]]) / top).coerceIn(0.0, 1.0)
        return GridFit(
            bpm = 60.0 * framesPerSecond / bestPeriod,
            downbeatFrame = beats[ranked[0]],
            confidence = confidence,
            barConfidence = barConfidence,
        )
    }

    /** Autocorrelation peak under a log-normal tempo prior centred on 120 BPM (resolves half/double time). */
    private fun coarsePeriod(novelty: FloatArray, framesPerSecond: Double): Double? {
        val minLag = floor(60.0 * framesPerSecond / MAX_BPM).toInt().coerceAtLeast(2)
        val maxLag = ceil(60.0 * framesPerSecond / MIN_BPM).toInt()
        if (maxLag + 2 >= novelty.size) return null
        val weighted = DoubleArray(maxLag + 2)
        for (lag in minLag - 1..maxLag + 1) {
            var sum = 0.0
            for (t in 0 until novelty.size - lag) sum += novelty[t] * novelty[t + lag]
            val bpm = 60.0 * framesPerSecond / lag
            val octaves = log2(bpm / 120.0)
            weighted[lag] = sum / (novelty.size - lag) * kotlin.math.exp(-0.5 * octaves * octaves)
        }
        var best = minLag
        for (lag in minLag..maxLag) if (weighted[lag] > weighted[best]) best = lag
        if (weighted[best] <= 0.0) return null
        // Parabolic interpolation between neighbouring lags.
        val left = weighted[best - 1]
        val right = weighted[best + 1]
        val denominator = left - 2 * weighted[best] + right
        val offset = if (denominator == 0.0) 0.0 else (0.5 * (left - right) / denominator).coerceIn(-0.5, 0.5)
        return best + offset
    }

    /** Phase (frames, within one period) whose comb collects the most onset energy, and that mean energy. */
    private fun bestPhase(novelty: FloatArray, period: Double, from: Int, until: Int): Pair<Double, Double> {
        var bestPhase = 0.0
        var bestScore = -1.0
        var phase = 0.0
        while (phase < period) {
            var sum = 0.0
            var count = 0
            var frame = from + phase
            while (frame < until - 1) {
                sum += interpolate(novelty, frame)
                count++
                frame += period
            }
            val score = if (count == 0) 0.0 else sum / count
            if (score > bestScore) {
                bestScore = score
                bestPhase = from + phase
            }
            phase += 0.5
        }
        // Report the phase relative to frame 0 so the halves are comparable.
        return (bestPhase % period) to bestScore
    }

    private fun beatHitRate(novelty: FloatArray, beats: List<Double>): Double {
        val sorted = novelty.sortedArray()
        val threshold = sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.lastIndex)]
        if (threshold <= 0f) return 0.0
        return beats.count { peakNear(novelty, it) > threshold }.toDouble() / beats.size
    }

    private fun peakNear(signal: FloatArray, frame: Double): Double {
        val center = frame.roundToInt()
        var peak = 0f
        for (i in center - 1..center + 1) if (i in signal.indices && signal[i] > peak) peak = signal[i]
        return peak.toDouble()
    }

    private fun interpolate(signal: FloatArray, position: Double): Double {
        val index = position.toInt()
        if (index < 0 || index + 1 >= signal.size) return 0.0
        val fraction = position - index
        return signal[index] * (1 - fraction) + signal[index + 1] * fraction
    }

    private fun wrap(value: Double, period: Double): Double {
        var wrapped = value % period
        if (wrapped > period / 2) wrapped -= period
        if (wrapped < -period / 2) wrapped += period
        return wrapped
    }

    // Krumhansl-Kessler key profiles, index = interval above the tonic.
    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    /** Camelot code and 0..1 confidence from a per-frame-normalised chromagram. */
    private fun estimateKey(pcm: FloatArray, sampleRate: Int): Pair<String, Float>? {
        val chroma = DoubleArray(12)
        val window = hann(CHROMA_FFT)
        val re = DoubleArray(CHROMA_FFT)
        val im = DoubleArray(CHROMA_FFT)
        val frameChroma = DoubleArray(12)
        val pitchClassOfBin = IntArray(CHROMA_FFT / 2) { bin ->
            val hz = bin.toDouble() * sampleRate / CHROMA_FFT
            if (hz < 65.0 || hz > 2_100.0) -1 else ((12.0 * log2(hz / 440.0)).roundToInt() + 9).mod(12)
        }
        var offset = 0
        while (offset + CHROMA_FFT <= pcm.size) {
            for (i in 0 until CHROMA_FFT) {
                re[i] = pcm[offset + i] * window[i]
                im[i] = 0.0
            }
            fft(re, im)
            frameChroma.fill(0.0)
            for (bin in 1 until CHROMA_FFT / 2) {
                val pitchClass = pitchClassOfBin[bin]
                if (pitchClass >= 0) frameChroma[pitchClass] += hypot(re[bin], im[bin])
            }
            val total = frameChroma.sum()
            if (total > 1e-3) for (pc in 0 until 12) chroma[pc] += frameChroma[pc] / total
            offset += CHROMA_FFT / 2
        }
        if (chroma.sum() <= 0.0) return null

        data class Candidate(val code: String, val score: Double)
        val candidates = (0 until 12).flatMap { tonic ->
            listOf(false, true).mapNotNull { minor ->
                val profile = if (minor) MINOR else MAJOR
                val rotated = DoubleArray(12) { pc -> profile[(pc - tonic).mod(12)] }
                AutoMixCamelotClassifier.camelotFor(tonic, minor)?.let { Candidate(it, pearson(chroma, rotated)) }
            }
        }.sortedByDescending { it.score }
        val best = candidates.first()
        // Relative major/minor share a Camelot number and mix fine: only a different number competes.
        val number = best.code.dropLast(1)
        val rival = candidates.firstOrNull { it.code.dropLast(1) != number } ?: return null
        val confidence = if (best.score < 0.45) 0.0 else ((best.score - rival.score) / 0.1).coerceIn(0.0, 1.0)
        return best.code to confidence.toFloat()
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val meanA = a.average()
        val meanB = b.average()
        var numerator = 0.0
        var sumA = 0.0
        var sumB = 0.0
        for (i in a.indices) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            numerator += da * db
            sumA += da * da
            sumB += db * db
        }
        val denominator = sqrt(sumA * sumB)
        return if (denominator == 0.0) 0.0 else numerator / denominator
    }

    private fun hann(size: Int) = DoubleArray(size) { 0.5 - 0.5 * cos(2.0 * PI * it / size) }

    /** In-place iterative radix-2 FFT; size must be a power of two. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wRe = cos(angle)
            val wIm = sin(angle)
            var start = 0
            while (start < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until length / 2) {
                    val a = start + k
                    val b = a + length / 2
                    val tRe = re[b] * curRe - im[b] * curIm
                    val tIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                start += length
            }
            length = length shl 1
        }
    }

    /** Smoothed level per onset frame, for silence trimming and ending shape. */
    private class LevelContour(private val raw: FloatArray, frameMs: Double) {
        private val framesPerSecond = 1000.0 / frameMs
        private val smoothed: DoubleArray
        private val reference: Double
        private val floor: Double

        init {
            val radius = (framesPerSecond * 0.5).toInt()
            val power = DoubleArray(raw.size) { Math.pow(10.0, raw[it] / 10.0) }
            smoothed = DoubleArray(raw.size)
            var sum = 0.0
            var lo = 0
            var hi = -1
            for (i in raw.indices) {
                while (hi < minOf(raw.lastIndex, i + radius)) sum += power[++hi]
                while (lo < i - radius) sum -= power[lo++]
                smoothed[i] = 10.0 * log10(sum / (hi - lo + 1) + 1e-12)
            }
            floor = maxOf(SILENCE_FLOOR_DB, percentile(raw.map(Float::toDouble), 0.95) - SILENCE_BELOW_PEAK_DB)
            reference = percentile(smoothed.toList(), 0.9)
        }

        private fun percentile(values: List<Double>, fraction: Double): Double {
            if (values.isEmpty()) return -120.0
            val sorted = values.sorted()
            return sorted[(sorted.size * fraction).toInt().coerceAtMost(sorted.lastIndex)]
        }

        fun firstAudible(): Int? = raw.indices.firstOrNull { raw[it] >= floor }
        fun lastAudible(): Int? = raw.indices.lastOrNull { raw[it] >= floor }

        fun startsCold(): Boolean {
            val start = firstAudible() ?: return false
            val until = minOf(smoothed.size, start + (framesPerSecond * 1.5).toInt())
            return (start until until).any { smoothed[it] >= reference - 6.0 }
        }

        fun endsCold(): Boolean {
            val end = lastAudible() ?: return false
            val from = maxOf(0, end - framesPerSecond.toInt())
            return (from..end).any { smoothed[it] >= reference - 6.0 }
        }

        /** Where a steady fade-out begins, or null if the ending is not one. */
        fun fadeOutStart(): Int? {
            val end = lastAudible() ?: return null
            val start = (end downTo 0).firstOrNull { smoothed[it] >= reference - 3.0 } ?: return null
            if ((end - start) < framesPerSecond * 4) return null
            val nearEnd = maxOf(start, end - framesPerSecond.toInt())
            if (smoothed[nearEnd] > reference - 15.0) return null
            // A fade only goes down: a passage that climbs back 3 dB is a breakdown.
            var lowest = smoothed[start]
            for (i in start..end) {
                if (smoothed[i] > lowest + 3.0) return null
                if (smoothed[i] < lowest) lowest = smoothed[i]
            }
            return start
        }
    }
}
