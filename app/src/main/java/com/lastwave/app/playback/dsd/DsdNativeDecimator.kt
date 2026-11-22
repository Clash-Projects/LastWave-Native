package com.lastwave.app.playback.dsd

import com.lastwave.app.playback.NativeAudioEngine
import java.io.Closeable

/**
 * JNI bridge to `lastwave::dsd::DsdDecimator` in `liblastwave_audio.so`.
 *
 * Converts 1-bit DSD bitstreams (DSD64 / DSD128 / DSD256 / DSD512) into
 * interleaved 32-bit float PCM at a DAC-friendly rate (176.4 kHz or 192 kHz)
 * with Kaiser-windowed linear-phase anti-aliasing filtration.
 */
class DsdNativeDecimator(
    val channelCount: Int,
    val dsdSampleRateHz: Int,
    val lsbFirst: Boolean,
) : Closeable {
    val decimationFactor: Int = chooseDecimationFactor(dsdSampleRateHz)
    val outputSampleRateHz: Int = (dsdSampleRateHz / decimationFactor).coerceAtLeast(44_100)

    private var handle: Long = if (isAvailable && channelCount in 1..8) {
        try {
            nativeCreate(channelCount, decimationFactor, lsbFirst)
        } catch (_: LinkageError) {
            0L
        }
    } else {
        0L
    }

    val isReady: Boolean
        get() = handle != 0L

    fun maxOutputBytes(inputLengthBytes: Int, planar: Boolean, blockSizePerChannel: Int): Int {
        val stepBytes = (decimationFactor / 8).coerceAtLeast(1)
        val byteFrames = if (planar) blockSizePerChannel else inputLengthBytes / channelCount.coerceAtLeast(1)
        val maxFrames = (byteFrames + stepBytes) / stepBytes + 1
        return maxFrames * channelCount.coerceAtLeast(1) * 4
    }

    fun reset() {
        val h = handle
        if (h != 0L) nativeReset(h)
    }

    fun process(
        input: ByteArray,
        offset: Int,
        length: Int,
        planar: Boolean,
        blockSizePerChannel: Int,
        outputPcmBytes: ByteArray,
    ): Int {
        val h = handle
        if (h == 0L || length <= 0) return 0
        return nativeProcess(h, input, offset, length, planar, blockSizePerChannel, outputPcmBytes)
    }

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            nativeDestroy(h)
        }
    }

    private external fun nativeCreate(channelCount: Int, decimationFactor: Int, lsbFirst: Boolean): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeReset(handle: Long)
    private external fun nativeProcess(
        handle: Long,
        inputBytes: ByteArray,
        inputOffset: Int,
        inputLength: Int,
        planar: Boolean,
        blockSizePerChannel: Int,
        outputPcmBytes: ByteArray,
    ): Int

    companion object {
        val isAvailable: Boolean
            get() = NativeAudioEngine.libraryLoaded

        /**
         * Selects power-of-two decimation from `[8, 16, 32, 64, 128]` targeting
         * 176.4 kHz (for 44.1 kHz-family DSD) or 192 kHz (for 48 kHz-family DSD).
         */
        fun chooseDecimationFactor(dsdRateHz: Int): Int = when {
            dsdRateHz <= 0 -> 16
            dsdRateHz <= 1_800_000 -> 8
            dsdRateHz <= 4_000_000 -> 16   // DSD64 -> 176.4k / 192k
            dsdRateHz <= 8_000_000 -> 32   // DSD128 -> 176.4k / 192k
            dsdRateHz <= 16_000_000 -> 64  // DSD256 -> 176.4k / 192k
            else -> 128                    // DSD512 -> 176.4k / 192k
        }

        fun dsdBadgeForRate(dsdRateHz: Int): String = when {
            dsdRateHz >= 20_000_000 -> "DSD512"
            dsdRateHz >= 10_000_000 -> "DSD256"
            dsdRateHz >= 5_000_000 -> "DSD128"
            else -> "DSD64"
        }
    }
}
