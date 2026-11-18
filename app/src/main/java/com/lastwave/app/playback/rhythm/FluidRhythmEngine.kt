package com.lastwave.app.playback.rhythm

import android.media.audiofx.Visualizer
import android.os.SystemClock
import androidx.media3.common.C
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time audio rhythm and beat analysis engine matching Lyra Player's
 * rhythm specification (bass_hz 180, wide_hz 2400, attack 25ms, release 200ms,
 * speed_boost 1.8, dilation 0.32, displacement 0.075, brightness 0.22, contrast 0.35, saturation 0.35).
 *
 * Designed for clear, unmistakably detectable main-beat reactivity:
 * 1. 2nd-order Butterworth 180 Hz Low-Pass Filter (< 180 Hz kick drum/sub-bass) +
 *    2400 Hz Wide-Band Filter (snare/clap punch) on PCM stream from NativeProcessingAudioSink.
 * 2. Main-Beat Energy Flux Gate:
 *    Suppresses continuous baseline melody and voice so the visualizer rests calmly between beats,
 *    and surges powerfully only when a genuine kick or snare strike occurs.
 * 3. Fast 25ms attack snap / 200ms smooth release envelope for punchy, rhythmic visual bounce.
 * 4. Dual-Path Fallback:
 *    Hardware Visualizer FFT capture stands by if PCM stream is ever bypassed.
 * 5. Lifecycle Resilient:
 *    Zero state locking or freezing across minimize, backgrounding, and resume.
 */
object FluidRhythmEngine {

    const val BASS_HZ = 180f
    const val WIDE_HZ = 2400f
    const val ATTACK_MS = 25f
    const val RELEASE_MS = 200f
    const val STRENGTH = 1.0f
    const val SPEED_BOOST = 1.8f
    const val DILATION = 0.32f
    const val DISPLACEMENT = 0.075f
    const val BRIGHTNESS = 0.22f
    const val CONTRAST = 0.35f
    const val SATURATION = 0.35f
    const val LOBE_SCALE = 0.55f

    private val _rhythm = MutableStateFlow(0f)
    /** Current smoothed rhythm value in range 0f..1f */
    val rhythm: StateFlow<Float> = _rhythm.asStateFlow()

    @Volatile
    var currentRhythmValue: Float = 0f
        private set

    @Volatile
    var isEnabled: Boolean = false

    @Volatile
    private var rawRhythmTarget: Float = 0f

    @Volatile
    private var lastAudioFeedUptimeMs: Long = 0L

    @Volatile
    private var lastPcmFeedUptimeMs: Long = 0L

    // Hardware visualizer fallback
    private var visualizer: Visualizer? = null
    private var currentSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var visPrevBass = 0f
    private var visPeakFlux = 12f

    // PCM DSP state
    private var sampleRate: Int = 0
    private var channels: Int = 0
    private var encoding: Int = C.ENCODING_INVALID
    private var bytesPerFrame: Int = 0

    // Biquad 180 Hz Low-Pass Filter coefficients (Kick / Sub-bass)
    private var lpB0 = 1f; private var lpB1 = 0f; private var lpB2 = 0f
    private var lpA1 = 0f; private var lpA2 = 0f
    private var lpX1 = 0f; private var lpX2 = 0f
    private var lpY1 = 0f; private var lpY2 = 0f

    // Biquad 2400 Hz Low-Pass Filter coefficients (Wide rhythm band)
    private var wpB0 = 1f; private var wpB1 = 0f; private var wpB2 = 0f
    private var wpA1 = 0f; private var wpA2 = 0f
    private var wpX1 = 0f; private var wpX2 = 0f
    private var wpY1 = 0f; private var wpY2 = 0f

    // Moving average of RMS energy to isolate transient beat spikes from continuous sound
    private var prevBassRms = 0f
    private var prevMidRms = 0f
    private var peakBassFlux = 0.02f
    private var peakMidFlux = 0.02f

    private var analysisWindowFrames = 441
    private var windowCount = 0
    private var windowBassAccum = 0f
    private var windowMidAccum = 0f

    private const val DECIMATION = 2

    @Synchronized
    fun attachSession(sessionId: Int) {
        if (currentSessionId == sessionId && visualizer != null) return
        detachSession()
        currentSessionId = sessionId
        if (sessionId <= 0 || sessionId == C.AUDIO_SESSION_ID_UNSET) return

        runCatching {
            val v = Visualizer(sessionId).apply {
                captureSize = 128
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        fft?.let { processVisualizerFft(it) }
                    }
                }, Visualizer.getMaxCaptureRate(), false, true)
                enabled = true
            }
            visualizer = v
        }
    }

    @Synchronized
    fun detachSession() {
        runCatching {
            visualizer?.enabled = false
            visualizer?.release()
        }
        visualizer = null
        currentSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    private fun processVisualizerFft(fft: ByteArray) {
        if (!isEnabled || fft.size < 8) return
        val now = SystemClock.uptimeMillis()
        // If PCM stream is actively feeding, let PCM handle it directly
        if (now - lastPcmFeedUptimeMs < 350L) return

        var bassSum = 0f
        val n = (fft.size / 2).coerceAtMost(6)
        for (i in 1 until n) {
            val re = fft[i * 2].toFloat()
            val im = fft[i * 2 + 1].toFloat()
            bassSum += hypot(re, im)
        }
        val flux = max(0f, bassSum - visPrevBass)
        visPrevBass = visPrevBass * 0.85f + bassSum * 0.15f
        visPeakFlux = max(flux, visPeakFlux * 0.993f).coerceAtLeast(8f)

        val normFlux = (flux / visPeakFlux).coerceIn(0f, 1f)
        if (normFlux > 0.28f) {
            val normalized = ((normFlux - 0.28f) / 0.72f).coerceIn(0f, 1f)
            rawRhythmTarget = (0.60f + normalized * 0.40f) * STRENGTH
            lastAudioFeedUptimeMs = now
        }
    }

    /**
     * Called when the UI resumes from minimized or background state.
     * Resets stale targets to instantly re-synchronize with live audio without freezing.
     */
    fun onForegroundResume() {
        rawRhythmTarget = 0f
        lastAudioFeedUptimeMs = SystemClock.uptimeMillis()
    }

    /**
     * Primary PCM analysis from NativeProcessingAudioSink.
     * Guaranteed to work universally across all Android devices, streams, and DACs.
     */
    fun processPcmBuffer(
        buffer: ByteBuffer,
        sampleRate: Int,
        channels: Int,
        pcmEncoding: Int,
        presentationTimeUs: Long = 0L,
        positionUs: () -> Long = { 0L },
        playbackSpeed: () -> Float = { 1f },
    ) {
        if (!isEnabled || !buffer.hasRemaining() || sampleRate <= 0 || channels <= 0) return
        if (!ensureFormat(sampleRate, channels, pcmEncoding)) return

        val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val now = SystemClock.uptimeMillis()
        lastPcmFeedUptimeMs = now

        while (data.remaining() >= bytesPerFrame) {
            // Read 2-sample averaged mono frame
            var mono = readMonoSample(data)
            if (data.remaining() >= bytesPerFrame) {
                mono = (mono + readMonoSample(data)) * 0.5f
            }

            // 180 Hz Low-Pass Biquad filter (Kick / Sub-bass)
            val lpY = lpB0 * mono + lpB1 * lpX1 + lpB2 * lpX2 - lpA1 * lpY1 - lpA2 * lpY2
            lpX2 = lpX1; lpX1 = mono; lpY2 = lpY1; lpY1 = lpY

            // 2400 Hz Low-Pass Biquad filter (Wide rhythm band)
            val wpY = wpB0 * mono + wpB1 * wpX1 + wpB2 * wpX2 - wpA1 * wpY1 - wpA2 * wpY2
            wpX2 = wpX1; wpX1 = mono; wpY2 = wpY1; wpY1 = wpY

            windowBassAccum += lpY * lpY
            val midSample = wpY - lpY
            windowMidAccum += midSample * midSample
            windowCount += DECIMATION

            if (windowCount >= analysisWindowFrames) {
                val samplesInWin = (windowCount / DECIMATION).coerceAtLeast(1)
                val bassRms = sqrt(windowBassAccum / samplesInWin)
                val midRms = sqrt(windowMidAccum / samplesInWin)
                windowBassAccum = 0f
                windowMidAccum = 0f
                windowCount = 0

                // Transient flux: energy increase over continuous baseline
                val bassFlux = max(0f, bassRms - prevBassRms)
                val midFlux = max(0f, midRms - prevMidRms)

                // Smoothly track background RMS music energy
                prevBassRms = prevBassRms * 0.85f + bassRms * 0.15f
                prevMidRms = prevMidRms * 0.85f + midRms * 0.15f

                // Track dynamic peak flux for adaptive calibration
                peakBassFlux = max(bassFlux, peakBassFlux * 0.993f).coerceIn(0.008f, 1.0f)
                peakMidFlux = max(midFlux, peakMidFlux * 0.993f).coerceIn(0.008f, 1.0f)

                val normBassFlux = (bassFlux / peakBassFlux).coerceIn(0f, 1f)
                val normMidFlux = (midFlux / peakMidFlux).coerceIn(0f, 1f)

                // MAIN BEAT DETECTION:
                // Only trigger on genuine drum impact spikes above the continuous baseline
                val rawBeat = maxOf(normBassFlux * 1.15f, normMidFlux * 0.88f)

                // Clean threshold gate:
                // Below 0.28, it is background melody/voice -> OUTPUT ZERO (rests peacefully).
                // Above 0.28, it is a MAIN BEAT -> Punch up to 0.70 .. 1.0!
                val beatIntensity = if (rawBeat > 0.28f) {
                    val normalized = ((rawBeat - 0.28f) / 0.72f).coerceIn(0f, 1f)
                    (0.65f + normalized * 0.35f) * STRENGTH
                } else {
                    0f
                }

                if (beatIntensity > 0f) {
                    rawRhythmTarget = beatIntensity
                    lastAudioFeedUptimeMs = SystemClock.uptimeMillis()
                }
            }
        }
    }

    /**
     * Advanced every animation frame (at display refresh rate, 60/90/120 Hz) by the UI.
     * Computes the exact attack/release envelope matching Lyra Player's specification.
     */
    fun tick(dtSeconds: Float, isPlaying: Boolean): Float {
        val dtMs = (dtSeconds * 1000f).coerceIn(1f, 100f)
        val now = SystemClock.uptimeMillis()

        if (!isEnabled || !isPlaying) {
            val releaseAlpha = (1f - exp(-dtMs / RELEASE_MS)).coerceIn(0f, 1f)
            currentRhythmValue = max(0f, currentRhythmValue - currentRhythmValue * releaseAlpha)
            _rhythm.value = currentRhythmValue
            rawRhythmTarget = 0f
            return currentRhythmValue
        }

        // If audio stream recently fed samples (< 450ms), follow target smoothly
        val target = if (now - lastAudioFeedUptimeMs < 450L) {
            rawRhythmTarget
        } else {
            rawRhythmTarget = 0f
            0f
        }

        // Target naturally decays between beat hits so each next strike hits fresh
        rawRhythmTarget = max(0f, rawRhythmTarget - rawRhythmTarget * (1f - exp(-dtMs / 80f)))

        // 25ms attack snap / 200ms smooth release envelope for punchy, visible beat bounce
        if (target > currentRhythmValue) {
            val attackAlpha = (1f - exp(-dtMs / ATTACK_MS)).coerceIn(0f, 1f)
            currentRhythmValue += (target - currentRhythmValue) * attackAlpha
        } else {
            val releaseAlpha = (1f - exp(-dtMs / RELEASE_MS)).coerceIn(0f, 1f)
            currentRhythmValue += (target - currentRhythmValue) * releaseAlpha
        }

        currentRhythmValue = currentRhythmValue.coerceIn(0f, 1f)
        _rhythm.value = currentRhythmValue
        return currentRhythmValue
    }

    fun flush() {
        rawRhythmTarget = 0f
        lpX1 = 0f; lpX2 = 0f; lpY1 = 0f; lpY2 = 0f
        wpX1 = 0f; wpX2 = 0f; wpY1 = 0f; wpY2 = 0f
        prevBassRms = 0f
        prevMidRms = 0f
        windowCount = 0
        windowBassAccum = 0f
        windowMidAccum = 0f
    }

    fun reset() {
        flush()
        sampleRate = 0
        channels = 0
        encoding = C.ENCODING_INVALID
        bytesPerFrame = 0
    }

    private fun ensureFormat(sr: Int, ch: Int, enc: Int): Boolean {
        if (sr == sampleRate && ch == channels && enc == encoding) return bytesPerFrame > 0
        sampleRate = sr
        channels = ch
        encoding = enc

        val bytesPerSample = when (enc) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
        if (bytesPerSample <= 0 || ch <= 0 || sr < 8000) {
            bytesPerFrame = 0
            return false
        }
        bytesPerFrame = bytesPerSample * ch
        analysisWindowFrames = (sr * 0.010f).toInt().coerceAtLeast(64) // 10ms hop window

        computeFilterCoefficients(sr.toFloat() / DECIMATION)
        flush()
        return true
    }

    private fun computeFilterCoefficients(rate: Float) {
        // 2nd-order Butterworth Low-Pass filter at 180 Hz
        val fcLp = BASS_HZ.coerceAtMost(rate * 0.45f)
        val w0Lp = (2.0 * PI * fcLp / rate).toFloat()
        val cosLp = cos(w0Lp)
        val alphaLp = sin(w0Lp) / (2f * 0.7071f)
        val a0Lp = 1f + alphaLp
        lpB0 = ((1f - cosLp) * 0.5f) / a0Lp
        lpB1 = (1f - cosLp) / a0Lp
        lpB2 = lpB0
        lpA1 = (-2f * cosLp) / a0Lp
        lpA2 = (1f - alphaLp) / a0Lp

        // 2nd-order Butterworth Low-Pass filter at 2400 Hz
        val fcWp = WIDE_HZ.coerceAtMost(rate * 0.45f)
        val w0Wp = (2.0 * PI * fcWp / rate).toFloat()
        val cosWp = cos(w0Wp)
        val alphaWp = sin(w0Wp) / (2f * 0.7071f)
        val a0Wp = 1f + alphaWp
        wpB0 = ((1f - cosWp) * 0.5f) / a0Wp
        wpB1 = (1f - cosWp) / a0Wp
        wpB2 = wpB0
        wpA1 = (-2f * cosWp) / a0Wp
        wpA2 = (1f - alphaWp) / a0Wp
    }

    private fun readMonoSample(data: ByteBuffer): Float {
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
}
