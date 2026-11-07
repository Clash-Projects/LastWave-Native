package com.lastwave.app.playback.haptics

import androidx.media3.common.C
import androidx.media3.common.Format
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Receives detected musical transients from [MusicHapticsAnalyzer]. */
interface HapticPulseListener {
    /**
     * @param leadTimeMs how long until this pulse's audio is audible. PCM reaches
     *   the sink well before the speaker plays it, so the listener delays the
     *   vibration by this amount to land on the beat.
     */
    fun onHapticPulse(
        bassEnergy: Float,
        vocalEnergy: Float,
        isSharpTransient: Boolean,
        leadTimeMs: Long,
    )

    /** Seek / flush: any pulse still scheduled belongs to audio that will never play. */
    fun onAnalysisFlushed() {}
}

/**
 * Read-only PCM analyser fed straight from the player's audio sink.
 *
 * It used to be a Media3 AudioProcessor inside DefaultAudioSink, but Media3 1.2.1
 * drops custom processors whenever the sink outputs float, and LastWave always
 * hands its delegates float PCM, so the old tap never received a single sample.
 * The sink now calls [analyze] with every decoded buffer and its exact
 * presentation time, on the playback thread. The buffer is never modified.
 */
class MusicHapticsAnalyzer(
    private val pulseListener: HapticPulseListener
) {

    @Volatile
    var isAnalysisActive: Boolean = false

    // Current stream format
    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    private var sampleRate = 0
    private var bytesPerFrame = 0

    // Biquad low-pass (kick / sub-bass, 100 Hz)
    private var lpB0 = 1f; private var lpB1 = 0f; private var lpB2 = 0f
    private var lpA1 = 0f; private var lpA2 = 0f
    private var lpX1 = 0f; private var lpX2 = 0f
    private var lpY1 = 0f; private var lpY2 = 0f

    // Biquad band-pass (vocals / snare texture, 1.1 kHz, Q 0.85)
    private var bpB0 = 1f; private var bpB1 = 0f; private var bpB2 = 0f
    private var bpA1 = 0f; private var bpA2 = 0f
    private var bpX1 = 0f; private var bpX2 = 0f
    private var bpY1 = 0f; private var bpY2 = 0f

    // Envelopes
    private var fastBassEnv = 0f
    private var slowBassEnv = 0.01f
    private var fastVocalEnv = 0f
    private var slowVocalEnv = 0.01f
    // Auto-gain: slowly decaying peaks so quiet or loudness-normalised
    // tracks still drive the motor as hard as loud masters.
    private var bassPeak = 0.05f
    private var vocalPeak = 0.05f

    private var analysisWindowFrames = 480
    private var windowCount = 0
    private var windowBassAccum = 0f
    private var windowVocalAccum = 0f
    private var framesSinceBassTrigger = COUNTER_CAP
    private var framesSinceAnyTrigger = COUNTER_CAP
    private var minBassIntervalFrames = 5280
    private var minAnyIntervalFrames = 3840

    /**
     * @param presentationTimeUs media time of the buffer's first frame.
     * @param positionUs the sink's currently audible media position, or
     *   [Long.MIN_VALUE] when the clock has not started yet.
     */
    fun analyze(
        buffer: ByteBuffer,
        format: Format?,
        presentationTimeUs: Long,
        positionUs: () -> Long,
        playbackSpeed: () -> Float,
    ) {
        if (!isAnalysisActive || format == null || !buffer.hasRemaining()) return
        if (!ensureFormat(format)) return

        val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var frameIndex = 0L
        while (data.remaining() >= bytesPerFrame) {
            val mono = readMonoFrame(data)
            // 2x decimation: skip every other frame (filters are designed for sr/2).
            if (data.remaining() >= bytesPerFrame) data.position(data.position() + bytesPerFrame)
            frameIndex += DECIMATION

            val lpY = lpB0 * mono + lpB1 * lpX1 + lpB2 * lpX2 - lpA1 * lpY1 - lpA2 * lpY2
            lpX2 = lpX1; lpX1 = mono; lpY2 = lpY1; lpY1 = lpY
            val bpY = bpB0 * mono + bpB1 * bpX1 + bpB2 * bpX2 - bpA1 * bpY1 - bpA2 * bpY2
            bpX2 = bpX1; bpX1 = mono; bpY2 = bpY1; bpY1 = bpY

            windowBassAccum += lpY * lpY
            windowVocalAccum += bpY * bpY
            windowCount += DECIMATION
            framesSinceBassTrigger += DECIMATION
            framesSinceAnyTrigger += DECIMATION

            if (windowCount >= analysisWindowFrames) {
                val samples = (windowCount / DECIMATION).coerceAtLeast(1)
                val bassRms = sqrt(windowBassAccum / samples)
                val vocalRms = sqrt(windowVocalAccum / samples)
                windowBassAccum = 0f
                windowVocalAccum = 0f
                windowCount = 0
                evaluate(bassRms, vocalRms) {
                    leadTimeMs(presentationTimeUs, frameIndex, positionUs, playbackSpeed)
                }
            }
        }
    }

    fun flush() {
        resetState()
        // Only the bound player's analyser may drop the shared pulse queue.
        if (isAnalysisActive) pulseListener.onAnalysisFlushed()
    }

    fun reset() {
        resetState()
        encoding = C.ENCODING_INVALID
        // Only the bound player's analyser may drop the shared pulse queue.
        if (isAnalysisActive) pulseListener.onAnalysisFlushed()
    }

    private fun ensureFormat(format: Format): Boolean {
        val enc = format.pcmEncoding
        val ch = format.channelCount
        val sr = format.sampleRate
        if (enc == encoding && ch == channels && sr == sampleRate) return bytesPerFrame > 0
        encoding = enc
        channels = ch
        sampleRate = sr
        val bytesPerSample = when (enc) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
        if (bytesPerSample == 0 || ch <= 0 || sr < 8000) {
            bytesPerFrame = 0
            return false
        }
        bytesPerFrame = bytesPerSample * ch
        analysisWindowFrames = (sr * 0.010f).toInt().coerceAtLeast(128)
        minBassIntervalFrames = (sr * BASS_COOLDOWN_S).toInt()
        minAnyIntervalFrames = (sr * ANY_COOLDOWN_S).toInt()
        computeCoefficients(sr.toFloat() / DECIMATION)
        resetState()
        return true
    }

    private fun readMonoFrame(data: ByteBuffer): Float {
        var sum = 0f
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> repeat(channels) { sum += data.getFloat() }
            C.ENCODING_PCM_16BIT -> repeat(channels) { sum += data.getShort() / 32768f }
            C.ENCODING_PCM_32BIT -> repeat(channels) { sum += data.getInt() / 2147483648f }
            C.ENCODING_PCM_24BIT -> repeat(channels) {
                val b0 = data.get().toInt() and 0xFF
                val b1 = data.get().toInt() and 0xFF
                val b2 = data.get().toInt()
                sum += ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
            }
        }
        return (sum / channels).coerceIn(-1f, 1f)
    }

    private fun leadTimeMs(
        presentationTimeUs: Long,
        frameIndex: Long,
        positionUs: () -> Long,
        playbackSpeed: () -> Float,
    ): Long {
        val nowUs = runCatching { positionUs() }.getOrDefault(Long.MIN_VALUE)
        if (nowUs == Long.MIN_VALUE || presentationTimeUs == C.TIME_UNSET) return 0L
        // The onset is detected at the END of the analysis window it fell in
        // (after the fast envelope has risen), so stamp the pulse at the start
        // of that window: about 10 ms earlier, where the beat actually begins.
        val onsetFrame = frameIndex - analysisWindowFrames
        val pulseUs = presentationTimeUs + onsetFrame * 1_000_000L / sampleRate
        val speed = runCatching { playbackSpeed() }.getOrDefault(1f).coerceIn(0.25f, 4f)
        val leadMs = ((pulseUs - nowUs) / 1000f / speed).toLong()
        // Beyond this the clock is not trustworthy (AudioTrack buffers are < 1 s).
        return if (leadMs in 0..MAX_LEAD_MS) leadMs else 0L
    }

    private inline fun evaluate(bassRms: Float, vocalRms: Float, lead: () -> Long) {
        fastBassEnv = if (bassRms > fastBassEnv) fastBassEnv * 0.45f + bassRms * 0.55f
        else fastBassEnv * 0.85f + bassRms * 0.15f
        slowBassEnv = slowBassEnv * 0.96f + bassRms * 0.04f
        fastVocalEnv = if (vocalRms > fastVocalEnv) fastVocalEnv * 0.50f + vocalRms * 0.50f
        else fastVocalEnv * 0.88f + vocalRms * 0.12f
        slowVocalEnv = slowVocalEnv * 0.95f + vocalRms * 0.05f

        // Cap the cooldown counters so hours of silence can never overflow them.
        framesSinceBassTrigger = min(framesSinceBassTrigger, COUNTER_CAP)
        framesSinceAnyTrigger = min(framesSinceAnyTrigger, COUNTER_CAP)

        bassPeak = max(fastBassEnv, bassPeak * PEAK_DECAY).coerceAtLeast(MIN_PEAK)
        vocalPeak = max(fastVocalEnv, vocalPeak * PEAK_DECAY).coerceAtLeast(MIN_PEAK)

        val normBass = (fastBassEnv / bassPeak).coerceIn(0f, 1f)
        val normVocal = (fastVocalEnv / vocalPeak).coerceIn(0f, 1f)
        val bassRatio = fastBassEnv / max(slowBassEnv, 0.004f)
        val vocalRatio = fastVocalEnv / max(slowVocalEnv, 0.004f)

        // Relative (gain-independent) onset tests plus a small absolute floor
        // so silence and hiss never trigger.
        val isKick = framesSinceBassTrigger >= minBassIntervalFrames &&
            fastBassEnv > 0.006f && bassRatio > 1.30f && normBass > 0.45f
        val isAccent = framesSinceAnyTrigger >= minAnyIntervalFrames &&
            fastVocalEnv > 0.005f && vocalRatio > 1.28f && normVocal > 0.45f

        if (!isKick && !isAccent) return
        framesSinceAnyTrigger = 0
        if (isKick) framesSinceBassTrigger = 0
        pulseListener.onHapticPulse(
            bassEnergy = if (isKick) max(normBass, 0.55f) else normBass * 0.6f,
            vocalEnergy = normVocal,
            isSharpTransient = isKick && bassRatio > 1.55f,
            leadTimeMs = lead(),
        )
    }

    private fun computeCoefficients(rate: Float) {
        val fcLp = 100f.coerceAtMost(rate * 0.45f)
        val w0Lp = (2.0 * PI * fcLp / rate).toFloat()
        val cosLp = cos(w0Lp)
        val alphaLp = sin(w0Lp) / (2f * 0.7071f)
        val a0Lp = 1f + alphaLp
        lpB0 = ((1f - cosLp) * 0.5f) / a0Lp
        lpB1 = (1f - cosLp) / a0Lp
        lpB2 = lpB0
        lpA1 = (-2f * cosLp) / a0Lp
        lpA2 = (1f - alphaLp) / a0Lp

        val fcBp = 1100f.coerceAtMost(rate * 0.45f)
        val w0Bp = (2.0 * PI * fcBp / rate).toFloat()
        val cosBp = cos(w0Bp)
        val alphaBp = sin(w0Bp) / (2f * 0.85f)
        val a0Bp = 1f + alphaBp
        bpB0 = alphaBp / a0Bp
        bpB1 = 0f
        bpB2 = -alphaBp / a0Bp
        bpA1 = (-2f * cosBp) / a0Bp
        bpA2 = (1f - alphaBp) / a0Bp
    }

    private fun resetState() {
        lpX1 = 0f; lpX2 = 0f; lpY1 = 0f; lpY2 = 0f
        bpX1 = 0f; bpX2 = 0f; bpY1 = 0f; bpY2 = 0f
        fastBassEnv = 0f; slowBassEnv = 0.01f
        fastVocalEnv = 0f; slowVocalEnv = 0.01f
        windowBassAccum = 0f; windowVocalAccum = 0f; windowCount = 0
        framesSinceBassTrigger = COUNTER_CAP
        framesSinceAnyTrigger = COUNTER_CAP
    }

    private companion object {
        const val DECIMATION = 2
        const val MAX_LEAD_MS = 1_500L
        const val BASS_COOLDOWN_S = 0.110f
        const val ANY_COOLDOWN_S = 0.080f
        const val PEAK_DECAY = 0.9985f
        const val MIN_PEAK = 0.008f
        const val COUNTER_CAP = 1 shl 28
    }
}
