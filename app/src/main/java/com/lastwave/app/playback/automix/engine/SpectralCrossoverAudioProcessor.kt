package com.lastwave.app.playback.automix.engine

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 AudioProcessor bridging real-time PCM audio buffers into the C++20 AutoMix DSP engine.
 */
class SpectralCrossoverAudioProcessor : AudioProcessor {

    companion object {
        init {
            runCatching {
                System.loadLibrary("lastwave_audio")
            }
        }
    }

    private var inputAudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat = AudioFormat.NOT_SET

    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    @Volatile var isEnabled: Boolean = false
    @Volatile var blendProgress: Float = 0.0f
    @Volatile var applySpectralSweeps: Boolean = true
    @Volatile var tempoRatioAlpha: Float = 1.0f
    @Volatile var tempoRatioBeta: Float = 1.0f

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT
        ) {
            return AudioFormat.NOT_SET
        }

        this.inputAudioFormat = inputAudioFormat
        // Normalize output to stereo float PCM
        this.outputAudioFormat = AudioFormat(
            inputAudioFormat.sampleRate,
            2,
            C.ENCODING_PCM_FLOAT,
        )

        nativeInit(inputAudioFormat.sampleRate.toFloat())
        return if (isEnabled) outputAudioFormat else AudioFormat.NOT_SET
    }

    override fun isActive(): Boolean = isEnabled && inputAudioFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val sampleRate = inputAudioFormat.sampleRate.toFloat()
        val numFrames = if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            remaining / (inputAudioFormat.channelCount * 2)
        } else {
            remaining / (inputAudioFormat.channelCount * 4)
        }

        val requiredBytes = numFrames * 2 * 4 // Stereo float (8 bytes per frame)
        if (buffer.capacity() < requiredBytes) {
            buffer = ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }

        // Float conversion if source is PCM16
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            val shorts = inputBuffer.asShortBuffer()
            val floats = buffer.asFloatBuffer()
            val channels = inputAudioFormat.channelCount
            for (i in 0 until numFrames) {
                val sL = shorts.get() / 32768.0f
                val sR = if (channels > 1) shorts.get() / 32768.0f else sL
                floats.put(sL)
                floats.put(sR)
            }
            inputBuffer.position(inputBuffer.position() + remaining)
        } else {
            // Direct copy for float
            buffer.put(inputBuffer)
        }
        buffer.flip()

        if (outputBuffer.capacity() < requiredBytes) {
            outputBuffer = ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }

        // Apply native C++ DSP processing
        nativeSetTempoRatio(tempoRatioAlpha, tempoRatioBeta)
        nativeProcessStereo(
            alphaBuf = buffer,
            betaBuf = null,
            outBuf = outputBuffer,
            numFrames = numFrames,
            progress = blendProgress,
            applySpectralSweeps = applySpectralSweeps,
        )

        outputBuffer.limit(requiredBytes)
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer == AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        nativeReset()
    }

    override fun reset() {
        flush()
        buffer = AudioProcessor.EMPTY_BUFFER
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
    }

    // Native JNI functions
    private external fun nativeInit(sampleRate: Float)
    private external fun nativeReset()
    private external fun nativeSetTempoRatio(ratioAlpha: Float, ratioBeta: Float)
    private external fun nativeProcessStereo(
        alphaBuf: ByteBuffer?,
        betaBuf: ByteBuffer?,
        outBuf: ByteBuffer,
        numFrames: Int,
        progress: Float,
        applySpectralSweeps: Boolean,
    )
    private external fun nativeEstimateLufs(pcmBuf: ByteBuffer, numFrames: Int): Float
}
