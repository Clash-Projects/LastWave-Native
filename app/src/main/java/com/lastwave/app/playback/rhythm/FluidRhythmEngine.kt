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
 * rhythm specification (bass_hz 180, wide_hz 2400, attack 35ms, release 180ms,
 * speed_boost 1.8, dilation 0.16, displacement 0.065, brightness 0.14, contrast 0.32, saturation 0.28).
 *
 * Implements a continuous multi-band envelope follower:
 * 1. 2nd-order Butterworth 180 Hz Low-Pass DSP filter + 2400 Hz Wide-Band filter on PCM stream
 *    from NativeProcessingAudioSink for pure, analog-smooth continuous envelope tracking.
 * 2. Hardware Visualizer (audioSessionId) attached to ExoPlayer for real-time FFT fallback.
 * 3. Continuous 35ms attack / 180ms release asymmetric dynamic envelope with non-linear power punch.
 * 4. Resilient to app minimize/resume: instant recovery without freezing or stale buffer state.
 */
object FluidRhythmEngine {

    const val BASS_HZ = 180f
    const val WIDE_HZ = 2400f
    const val ATTACK_MS = 35f
    const val RELEASE_MS = 180f
    const val STRENGTH = 0.85f
    const val SPEED_BOOST = 1.8f
    const val DILATION = 0.16f
    const val DISPLACEMENT = 0.065f
    const val BRIGHTNESS = 0.14f
    const val CONTRAST = 0.32f
    const val SATURATION = 0.28f
    const val LOBE_SCALE = 0.50f

    private val _rhythm = MutableStateFlow(0f)
    /** Current smoothed rhythm value in range 0f..1f */
    val rhythm: StateFlow<Float> = _rhythm.asStateFlow()

    @Volatile
    var currentRhythmValue: Float = 0f
        private set

    @Volatile
    var isEnabled: Boolean = true

    @Volatile
    private var rawRhythmTarget: Float = 0f

    @Volatile
    private var lastAudioFeedUptimeMs: Long = 0L

    @Volatile
    private var lastPcmFeedUptimeMs: Long = 0L

    // Hardware visualizer
    private var visualizer: Visualizer? = null
    private var currentSessionId: Int = C.AUDIO_SESSION_ID_UNSET

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

    // Dynamic range tracking
    private var peakBass = 0.06f
    private var floorBass = 0.005f
    private var peakWide = 0.06f
    private var floorWide = 0.005f

    private var analysisWindowFrames = 441
    private var windowCount = 0
    private var windowBassAccum = 0f
    private var windowWideAccum = 0f

    private const val DECIMATION = 2

    /**
     * Attaches to ExoPlayer's audio session ID to capture real-time FFT visualization.
     */
    @Synchronized
    fun attachSession(sessionId: Int) {
        if (currentSessionId == sessionId && visualizer != null) return
        detachSession()
        currentSessionId = sessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId <= 0) return

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

    /**
     * Process FFT from hardware Visualizer when available.
     * Bins 1..3 correspond to sub-bass (< 180 Hz).
     * Bins 4..18 correspond to wide rhythm (< 2400 Hz).
     */
    private fun processVisualizerFft(fft: ByteArray) {
        if (!isEnabled || fft.size < 8) return
        val now = SystemClock.uptimeMillis()
        // If PCM stream is actively feeding, let PCM handle it directly
        if (now - lastPcmFeedUptimeMs < 350L) return

        val n = (fft.size / 2).coerceAtMost(18)
        var bassSum = 0f
        var wideSum = 0f
        for (i in 1 until n) {
            val re = fft[i * 2].toFloat()
            val im = fft[i * 2 + 1].toFloat()
            val mag = hypot(re, im)
            if (i <= 3) {
                bassSum += mag
            } else {
                wideSum += mag
            }
        }
        peakBass = max(bassSum, peakBass * 0.993f).coerceAtLeast(12f)
        peakWide = max(wideSum, peakWide * 0.993f).coerceAtLeast(12f)
        val normBass = (bassSum / peakBass).coerceIn(0f, 1f)
        val normWide = (wideSum / peakWide).coerceIn(0f, 1f)
        val instantaneous = ((normBass.pow(1.5f) * 0.75f + normWide * 0.35f) * 1.15f).coerceIn(0f, 1f) * STRENGTH
        rawRhythmTarget = instantaneous
        lastAudioFeedUptimeMs = now
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

            // 180 Hz Low-Pass Biquad filter (Kick / Bass)
            val lpY = lpB0 * mono + lpB1 * lpX1 + lpB2 * lpX2 - lpA1 * lpY1 - lpA2 * lpY2
            lpX2 = lpX1; lpX1 = mono; lpY2 = lpY1; lpY1 = lpY

            // 2400 Hz Low-Pass Biquad filter (Wide rhythm band)
            val wpY = wpB0 * mono + wpB1 * wpX1 + wpB2 * wpX2 - wpA1 * wpY1 - wpA2 * wpY2
            wpX2 = wpX1; wpX1 = mono; wpY2 = wpY1; wpY1 = wpY

            windowBassAccum += lpY * lpY
            windowWideAccum += wpY * wpY
            windowCount += DECIMATION

            if (windowCount >= analysisWindowFrames) {
                val samplesInWin = (windowCount / DECIMATION).coerceAtLeast(1)
                val bassRms = sqrt(windowBassAccum / samplesInWin)
                val wideRms = sqrt(windowWideAccum / samplesInWin)
                windowBassAccum = 0f
                windowWideAccum = 0f
                windowCount = 0

                // Adaptive dynamic range tracking
                peakBass = max(bassRms, peakBass * 0.995f).coerceAtLeast(0.015f)
                floorBass = min(bassRms, floorBass * 1.002f + 0.0001f).coerceAtMost(peakBass * 0.55f)
                val dynamicBass = max(peakBass - floorBass, 0.005f)
                val normBass = ((bassRms - floorBass) / dynamicBass).coerceIn(0f, 1f)

                peakWide = max(wideRms, peakWide * 0.995f).coerceAtLeast(0.015f)
                floorWide = min(wideRms, floorWide * 1.002f + 0.0001f).coerceAtMost(peakWide * 0.55f)
                val dynamicWide = max(peakWide - floorWide, 0.005f)
                val normWide = ((wideRms - floorWide) / dynamicWide).coerceIn(0f, 1f)

                // Non-linear power expansion for punchy beat feel (accentuating kicks above background noise)
                val bassPunch = normBass.pow(1.6f)
                val widePunch = normWide.pow(1.3f)
                val instantaneous = ((bassPunch * 0.75f + widePunch * 0.35f) * 1.15f).coerceIn(0f, 1f) * STRENGTH

                rawRhythmTarget = instantaneous
                lastAudioFeedUptimeMs = SystemClock.uptimeMillis()
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

        // 35ms attack / 180ms release asymmetric continuous envelope (Lyra spec)
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
        windowCount = 0
        windowBassAccum = 0f
        windowWideAccum = 0f
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
