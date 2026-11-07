@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lastwave.app.playback.haptics

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Callback interface invoked when a musical transient or rhythmic texture spike is detected.
 * Invoked from a rate-limited analysis window (max 25 times per second).
 */
fun interface HapticPulseListener {
    /**
     * @param leadTimeMs how far ahead of the audible output this pulse's audio
     *   currently is. The tap runs when PCM is written into the AudioTrack, which
     *   is a few hundred ms before the speaker plays it, so the listener must
     *   delay the vibration by this amount to land on the beat.
     */
    fun onHapticPulse(
        bassEnergy: Float,
        vocalEnergy: Float,
        isSharpTransient: Boolean,
        leadTimeMs: Long,
    )

    /** Seek / track flush: any pulse still scheduled belongs to audio that will never play. */
    fun onAnalysisFlushed() {}
}

/**
 * Read-only view of the owning sink's playback clock. Called on the playback
 * thread (the same thread that drives [MusicHapticsAudioProcessor.queueInput]).
 */
interface HapticPlaybackClock {
    /** Media position currently audible, in us, or [Long.MIN_VALUE] when unknown. */
    fun currentPositionUs(): Long
    fun playbackSpeed(): Float = 1f
}

/**
 * Zero-allocation, read-only audio tap extending Media3 [BaseAudioProcessor].
 *
 * Guarantees zero audible signal mutation: copies incoming PCM bytes directly
 * to output unmodified and extracts real-time sub-bass and vocal transients on a
 * lightweight downmixed stream with zero heap allocations in the audio path.
 */
class MusicHapticsAudioProcessor(
    private val pulseListener: HapticPulseListener
) : BaseAudioProcessor() {

    @Volatile
    var isAnalysisActive: Boolean = false

    /** Clock of the sink this instance is installed in. One instance per sink. */
    @Volatile
    var playbackClock: HapticPlaybackClock? = null

    // Media-time anchor: position of the first frame queued after a hard flush,
    // plus every frame queued since. Lets a pulse be mapped to when it is heard.
    // Media3 also flushes processors on gapless reconfiguration (track change)
    // while the AudioTrack keeps playing, so only a real discontinuity (seek,
    // stop, sink flush) may re-anchor; otherwise the timeline is continuous.
    private var anchorPositionUs = Long.MIN_VALUE
    private var framesSinceAnchor = 0L
    private var sampleRateHz = 48_000
    private var bufferFrameCursor = 0L

    @Volatile
    private var timelineResetRequested = true

    /** Called on a seek / discontinuity so the next buffer re-anchors the timeline. */
    fun requestTimelineReset() {
        timelineResetRequested = true
    }

    /** Folds queued frames into the anchor so a sample-rate change keeps the timeline exact. */
    private fun foldTimeline() {
        if (anchorPositionUs != Long.MIN_VALUE && framesSinceAnchor > 0) {
            anchorPositionUs += framesSinceAnchor * 1_000_000L / sampleRateHz
        }
        framesSinceAnchor = 0L
    }

    // Biquad filter coefficients for Low-Pass (Bass: 95 Hz cutoff)
    private var lpB0 = 1f; private var lpB1 = 0f; private var lpB2 = 0f
    private var lpA1 = 0f; private var lpA2 = 0f
    private var lpX1 = 0f; private var lpX2 = 0f
    private var lpY1 = 0f; private var lpY2 = 0f

    // Biquad filter coefficients for Band-Pass (Vocals/Rhythm: 1,100 Hz center, Q = 0.85)
    private var bpB0 = 1f; private var bpB1 = 0f; private var bpB2 = 0f
    private var bpA1 = 0f; private var bpA2 = 0f
    private var bpX1 = 0f; private var bpX2 = 0f
    private var bpY1 = 0f; private var bpY2 = 0f

    // Envelope followers & adaptive threshold state (zero heap allocation)
    private var fastBassEnv = 0f
    private var slowBassEnv = 0.01f
    private var fastVocalEnv = 0f
    private var slowVocalEnv = 0.01f

    private var samplesSinceLastTrigger = 0
    private var minTriggerIntervalSamples = 1920 // ~40ms at 48kHz (max 25 Hz trigger rate)
    private var analysisWindowSamples = 480      // ~10ms window
    private var currentWindowCount = 0
    private var windowBassAccum = 0f
    private var windowVocalAccum = 0f

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_24BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_32BIT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val sr = inputAudioFormat.sampleRate.toFloat().coerceAtLeast(8000f)
        foldTimeline()
        sampleRateHz = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        minTriggerIntervalSamples = (sr * 0.042f).toInt() // 42ms cooldown (~24 Hz)
        analysisWindowSamples = (sr * 0.010f).toInt().coerceAtLeast(128)
        // The filters run on the 2x-decimated stream, so design them for that
        // rate; designing at the full rate doubled every cutoff (95->190 Hz bass).
        computeBiquadCoefficients(sr / DECIMATION)
        resetFilterState()
        return inputAudioFormat
    }

    private fun computeBiquadCoefficients(sampleRate: Float) {
        // 1. Low-Pass 2nd-order Butterworth at 95 Hz (Kick & Sub-Bass)
        val fcLp = 95f.coerceAtMost(sampleRate * 0.45f)
        val w0Lp = (2.0 * PI * fcLp / sampleRate).toFloat()
        val cosW0Lp = cos(w0Lp)
        val sinW0Lp = sin(w0Lp)
        val alphaLp = sinW0Lp / (2.0f * 0.7071f)
        val a0Lp = 1f + alphaLp
        lpB0 = ((1f - cosW0Lp) * 0.5f) / a0Lp
        lpB1 = (1f - cosW0Lp) / a0Lp
        lpB2 = ((1f - cosW0Lp) * 0.5f) / a0Lp
        lpA1 = (-2f * cosW0Lp) / a0Lp
        lpA2 = (1f - alphaLp) / a0Lp

        // 2. Band-Pass at 1,100 Hz, Q = 0.85 (Vocals & Snare/Synth Texture)
        val fcBp = 1100f.coerceAtMost(sampleRate * 0.45f)
        val w0Bp = (2.0 * PI * fcBp / sampleRate).toFloat()
        val cosW0Bp = cos(w0Bp)
        val sinW0Bp = sin(w0Bp)
        val alphaBp = sinW0Bp / (2.0f * 0.85f)
        val a0Bp = 1f + alphaBp
        bpB0 = alphaBp / a0Bp
        bpB1 = 0f
        bpB2 = -alphaBp / a0Bp
        bpA1 = (-2f * cosW0Bp) / a0Bp
        bpA2 = (1f - alphaBp) / a0Bp
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        // Always copy untouched audio bytes to output buffer first
        val outputBuffer = replaceOutputBuffer(remaining)
        val sliceForAnalysis = if (isAnalysisActive) inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN) else null

        outputBuffer.put(inputBuffer)
        outputBuffer.flip()

        if (timelineResetRequested || anchorPositionUs == Long.MIN_VALUE) {
            // First buffer after a hard flush: nothing queued has played yet, so
            // the sink's position is exactly this buffer's start media time.
            val pos = runCatching { playbackClock?.currentPositionUs() }.getOrNull() ?: Long.MIN_VALUE
            if (pos != Long.MIN_VALUE) {
                anchorPositionUs = pos
                framesSinceAnchor = 0L
                timelineResetRequested = false
            }
        }
        bufferFrameCursor = framesSinceAnchor

        if (sliceForAnalysis != null) {
            analyzePcmWindow(sliceForAnalysis)
        }
        framesSinceAnchor += remaining / bytesPerFrame().coerceAtLeast(1)
    }

    private fun bytesPerFrame(): Int {
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4 * channels
            C.ENCODING_PCM_24BIT -> 3 * channels
            else -> 2 * channels
        }
    }

    /** ms between now and when the frame at [bufferFrameCursor] becomes audible. */
    private fun currentLeadTimeMs(): Long {
        val clock = playbackClock ?: return 0L
        if (anchorPositionUs == Long.MIN_VALUE) return 0L
        val nowUs = runCatching { clock.currentPositionUs() }.getOrNull() ?: return 0L
        if (nowUs == Long.MIN_VALUE) return 0L
        val pulseUs = anchorPositionUs + bufferFrameCursor * 1_000_000L / sampleRateHz
        val speed = runCatching { clock.playbackSpeed() }.getOrDefault(1f).coerceIn(0.25f, 4f)
        val leadMs = ((pulseUs - nowUs) / 1000L / speed).toLong()
        if (leadMs > MAX_LEAD_MS || leadMs < -STALE_TOLERANCE_MS) {
            // Timeline drifted (missed discontinuity): heal on the next buffer.
            timelineResetRequested = true
            return 0L
        }
        return leadMs.coerceIn(0L, MAX_LEAD_MS)
    }

    private fun analyzePcmWindow(buffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val encoding = inputAudioFormat.encoding
        val bytesPerFrame = when (encoding) {
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4 * channels
            C.ENCODING_PCM_24BIT -> 3 * channels
            C.ENCODING_PCM_16BIT -> 2 * channels
            else -> 2 * channels
        }

        // Step by 2 frames (2x decimation) to keep CPU cost near zero
        val frameStep = DECIMATION
        val byteStep = bytesPerFrame * frameStep

        while (buffer.remaining() >= bytesPerFrame) {
            val monoSample: Float = when (encoding) {
                C.ENCODING_PCM_FLOAT -> {
                    var sum = 0f
                    for (c in 0 until channels) {
                        sum += buffer.getFloat()
                    }
                    (sum / channels).coerceIn(-1f, 1f)
                }
                C.ENCODING_PCM_16BIT -> {
                    var sum = 0f
                    for (c in 0 until channels) {
                        sum += buffer.getShort() / 32768f
                    }
                    (sum / channels).coerceIn(-1f, 1f)
                }
                C.ENCODING_PCM_24BIT -> {
                    var sum = 0f
                    for (c in 0 until channels) {
                        val b0 = buffer.get().toInt() and 0xFF
                        val b1 = buffer.get().toInt() and 0xFF
                        val b2 = buffer.get().toInt() // sign-extended high byte
                        val sampleInt = (b2 shl 16) or (b1 shl 8) or b0
                        sum += sampleInt / 8388608f
                    }
                    (sum / channels).coerceIn(-1f, 1f)
                }
                C.ENCODING_PCM_32BIT -> {
                    var sum = 0f
                    for (c in 0 until channels) {
                        sum += buffer.getInt() / 2147483648f
                    }
                    (sum / channels).coerceIn(-1f, 1f)
                }
                else -> 0f
            }

            // Skip remaining decimated frames if possible
            val skipBytes = byteStep - bytesPerFrame
            if (skipBytes > 0 && buffer.remaining() >= skipBytes) {
                buffer.position(buffer.position() + skipBytes)
            }

            // Apply Low-Pass Biquad (Bass)
            val lpY = lpB0 * monoSample + lpB1 * lpX1 + lpB2 * lpX2 - lpA1 * lpY1 - lpA2 * lpY2
            lpX2 = lpX1; lpX1 = monoSample
            lpY2 = lpY1; lpY1 = lpY

            // Apply Band-Pass Biquad (Vocal/Texture)
            val bpY = bpB0 * monoSample + bpB1 * bpX1 + bpB2 * bpX2 - bpA1 * bpY1 - bpA2 * bpY2
            bpX2 = bpX1; bpX1 = monoSample
            bpY2 = bpY1; bpY1 = bpY

            windowBassAccum += lpY * lpY
            windowVocalAccum += bpY * bpY
            currentWindowCount += frameStep
            samplesSinceLastTrigger += frameStep
            bufferFrameCursor += frameStep

            if (currentWindowCount >= analysisWindowSamples) {
                val invCount = frameStep.toFloat() / currentWindowCount.toFloat()
                val bassRms = sqrt(windowBassAccum * invCount)
                val vocalRms = sqrt(windowVocalAccum * invCount)

                windowBassAccum = 0f
                windowVocalAccum = 0f
                currentWindowCount = 0

                evaluateEnvelopeAndEmit(bassRms, vocalRms)
            }
        }
    }

    private fun evaluateEnvelopeAndEmit(bassRms: Float, vocalRms: Float) {
        // Fast attack, slow decay envelope trackers
        fastBassEnv = if (bassRms > fastBassEnv) {
            fastBassEnv * 0.45f + bassRms * 0.55f
        } else {
            fastBassEnv * 0.85f + bassRms * 0.15f
        }
        slowBassEnv = slowBassEnv * 0.96f + bassRms * 0.04f

        fastVocalEnv = if (vocalRms > fastVocalEnv) {
            fastVocalEnv * 0.50f + vocalRms * 0.50f
        } else {
            fastVocalEnv * 0.88f + vocalRms * 0.12f
        }
        slowVocalEnv = slowVocalEnv * 0.95f + vocalRms * 0.05f

        if (samplesSinceLastTrigger < minTriggerIntervalSamples) return

        val bassRatio = fastBassEnv / max(slowBassEnv, 0.018f)
        val vocalRatio = fastVocalEnv / max(slowVocalEnv, 0.015f)

        val isBassKick = fastBassEnv > 0.045f && bassRatio > 1.38f
        val isVocalAccent = fastVocalEnv > 0.035f && vocalRatio > 1.32f
        val isSustainedGroove = (fastBassEnv > 0.09f || fastVocalEnv > 0.07f) &&
            samplesSinceLastTrigger >= minTriggerIntervalSamples * 2

        if (isBassKick || isVocalAccent || isSustainedGroove) {
            samplesSinceLastTrigger = 0
            val normBass = min(1f, (fastBassEnv * 3.2f).coerceIn(0f, 1f))
            val normVocal = min(1f, (fastVocalEnv * 3.8f).coerceIn(0f, 1f))
            pulseListener.onHapticPulse(
                bassEnergy = normBass,
                vocalEnergy = normVocal,
                isSharpTransient = isBassKick || (bassRatio > 1.55f),
                leadTimeMs = currentLeadTimeMs(),
            )
        }
    }

    override fun onFlush() {
        resetFilterState()
        foldTimeline()
        // A real sink flush leaves the clock unset until the next buffer; a
        // gapless reconfiguration keeps it running and must not re-anchor.
        val clockPos = runCatching { playbackClock?.currentPositionUs() }.getOrNull() ?: Long.MIN_VALUE
        if (clockPos == Long.MIN_VALUE) {
            timelineResetRequested = true
            pulseListener.onAnalysisFlushed()
        }
    }

    override fun onReset() {
        resetFilterState()
        anchorPositionUs = Long.MIN_VALUE
        framesSinceAnchor = 0L
        timelineResetRequested = true
    }

    private companion object {
        const val DECIMATION = 2
        /** Beyond this the clock is not trustworthy (AudioTrack buffers are < 1 s). */
        const val MAX_LEAD_MS = 1_500L
        const val STALE_TOLERANCE_MS = 250L
    }

    private fun resetFilterState() {
        lpX1 = 0f; lpX2 = 0f; lpY1 = 0f; lpY2 = 0f
        bpX1 = 0f; bpX2 = 0f; bpY1 = 0f; bpY2 = 0f
        fastBassEnv = 0f; slowBassEnv = 0.01f
        fastVocalEnv = 0f; slowVocalEnv = 0.01f
        windowBassAccum = 0f; windowVocalAccum = 0f
        currentWindowCount = 0; samplesSinceLastTrigger = 0
    }
}
