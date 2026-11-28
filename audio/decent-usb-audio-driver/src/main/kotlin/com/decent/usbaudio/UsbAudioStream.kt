package com.decent.usbaudio

import android.util.Log

/**
 * JNI handle for a direct USB audio output stream.
 *
 * This class is the Kotlin counterpart of the native [UsbAudioContext] defined
 * in [usb-audio-output.cpp]. It sends PCM data directly to a USB Audio Class 2.0
 * DAC via Linux usbdevfs isochronous transfers, bypassing the entire Android
 * audio stack (AudioFlinger, AudioTrack, AAudio).
 *
 * Each URB spans eight endpoint service intervals. The configured duration
 * controls how many audio URBs can be queued.
 *
 * Rate transition lifecycle (per USB Audio Class 2.0 spec, verified via xHCI ftrace):
 * ```
 * stream.stop()           // stop accepting writes
 * stream.release(closeDevice) // drain transfers before releasing the native context
 * // Kotlin layer does: close and reopen the connection, then set the rate and alternate setting.
 * newStream = UsbAudioStream(fd, ...)
 * newStream.start()
 * newStream.write(pcm)    // pipeline fills naturally
 * ```
 *
 * @param fd               File descriptor from [android.hardware.usb.UsbDeviceConnection.getFileDescriptor]
 * @param interfaceId      Audio streaming interface number (typically 1)
 * @param endpointOut      Isochronous OUT endpoint address (e.g. 0x01)
 * @param endpointFeedback Feedback IN endpoint address (e.g. 0x81), or 0 if unused
 * @param sampleRate       Initial sample rate in Hz
 * @param channelCount     Number of channels (1=mono, 2=stereo)
 * @param bitDepth         Bits per sample (16, 24, or 32)
 * @param maxPacketSize    Max packet size from endpoint descriptor
 * @param continuousFeedback Poll [endpointFeedback] while streaming (see [UsbAudioDevice.needsContinuousFeedback])
 */
class UsbAudioStream(
        fd: Int,
        interfaceId: Int,
        endpointOut: Int,
        endpointFeedback: Int,
        sampleRate: Int,
        channelCount: Int,
        bitDepth: Int,
        maxPacketSize: Int,
        dataInterval: Int,
        feedbackPacketSize: Int,
        feedbackInterval: Int,
        continuousFeedback: Boolean = false,
        bufferMs: Int = 80,
) {

    /** Native UsbAudioContext pointer. Exposed for NativeAudioEngine which
     *  shares the same USB context for direct submitPcmToUrbs calls. */
    var nativeHandle: Long = 0L
        private set

    init {
        nativeHandle = nativeUsbAudioCreate(
                fd, interfaceId, endpointOut, endpointFeedback,
                sampleRate, channelCount, bitDepth, maxPacketSize,
                dataInterval, feedbackPacketSize, feedbackInterval, continuousFeedback, bufferMs,
        )
        if (nativeHandle == 0L) {
            Log.e(TAG, "nativeUsbAudioCreate returned 0 — check logcat for native errors")
        }
    }

    /** True when the native context was created successfully. */
    val isReady: Boolean
        get() = nativeHandle != 0L

    /** True when the stream is actively running (after start, before stop/error). */
    val isAlive: Boolean
        get() = nativeHandle != 0L && nativeIsRunning(nativeHandle)

    /** Total frames written to USB since last start(). Used for position tracking. */
    val framesWritten: Long
        get() = if (nativeHandle != 0L) nativeGetFramesWritten(nativeHandle) else 0L

    /**
     * Select alternate setting on the USB streaming interface.
     * This determines the active format (bit depth) of the endpoint.
     *
     * @param altSetting Alternate setting number (1-4 typically)
     * @return True on success
     */
    fun setAltSetting(altSetting: Int): Boolean {
        if (nativeHandle == 0L) return false
        return nativeUsbAudioSetAltSetting(nativeHandle, altSetting)
    }

    /**
     * Configure the DAC's sample rate via UAC2 SET_CUR control request.
     *
     * @param sampleRateHz  Target sample rate (e.g. 44100, 96000)
     * @param clockSourceId UAC2 Clock Source entity ID. Pass 0 to skip
     *                      (some DACs auto-detect from the data stream).
     * @return True on success
     */
    fun setSampleRate(sampleRateHz: Int, clockSourceId: Int = 0): Boolean {
        if (nativeHandle == 0L) return false
        return nativeUsbAudioSetSampleRate(nativeHandle, sampleRateHz, clockSourceId)
    }

    /**
     * Start the USB audio stream. The pipeline fills naturally with
     * data from subsequent [write] calls.
     */
    fun start(): Boolean {
        if (nativeHandle == 0L) return false
        return nativeUsbAudioStart(nativeHandle)
    }

    /**
     * Write interleaved float32 PCM to the USB DAC.
     *
     * The native layer converts to the target bit depth, splits into
     * URBs of eight endpoint service intervals each.
     * This call blocks when the pipeline is full (natural backpressure
     * matching the DAC's clock rate).
     *
     * @param pcmBuffer Interleaved float PCM; length = frameCount * channelCount
     */
    fun write(pcmBuffer: FloatArray) {
        if (nativeHandle == 0L) return
        nativeUsbAudioWrite(nativeHandle, pcmBuffer)
    }

    /**
     * Write raw integer PCM bytes directly to the USB DAC (no float conversion).
     *
     * The native layer pads the input bit depth to the DAC's bit depth using
     * lossless integer operations (shift left, zero-fill LSBs). This is the
     * true bit-perfect path — zero float math in the entire pipeline.
     *
     * @param pcmBuffer Raw PCM bytes (interleaved, little-endian)
     * @param encoding  Media3 PCM encoding constant (C.ENCODING_PCM_16BIT, etc.)
     */
    fun writeRaw(pcmBuffer: ByteArray, encoding: Int) {
        if (nativeHandle == 0L) return
        val inputBitDepth = when (encoding) {
            2 -> 16   // C.ENCODING_PCM_16BIT
            0x15 -> 24 // C.ENCODING_PCM_24BIT
            0x16 -> 32 // C.ENCODING_PCM_32BIT
            else -> return
        }
        nativeUsbAudioWriteRaw(nativeHandle, pcmBuffer, inputBitDepth)
    }

    /** Apply a new duration on the writer thread that owns the URB queue. */
    fun setBufferDuration(bufferMs: Int) {
        if (nativeHandle != 0L) nativeSetBufferDuration(nativeHandle, bufferMs)
    }

    /**
     * Stop accepting new writes. Does NOT drain the pipeline.
     * Call [release] after the writer has exited.
     */
    fun stop() {
        if (nativeHandle == 0L) return
        nativeUsbAudioStop(nativeHandle)
    }

    /**
     * Reset the frame accumulator and residual buffer. Called on seek/flush
     * to prevent packet size jitter and boundary pops after a discontinuity.
     */
    fun flush() {
        if (nativeHandle == 0L) return
        nativeFlush(nativeHandle)
    }

    /** Reap finished transfers while the player is loading the next audio. */
    fun pump() {
        if (nativeHandle == 0L) return
        nativePump(nativeHandle)
    }

    /** Drain transfers before freeing their buffers. Close the device if the drain fails. */
    fun release(closeDevice: () -> Unit) {
        if (nativeHandle == 0L) return
        if (!nativeDrainUrbs(nativeHandle)) {
            Log.w(TAG, "USB drain incomplete, closing the device before freeing buffers")
            closeDevice()
        }
        nativeUsbAudioDestroy(nativeHandle)
        nativeHandle = 0L
        Log.i(TAG, "UsbAudioStream released")
    }

    // JNI declarations

    private external fun nativeUsbAudioCreate(
            fd: Int, interfaceId: Int, endpointOut: Int, endpointFeedback: Int,
            sampleRate: Int, channelCount: Int, bitDepth: Int, maxPacketSize: Int,
            dataInterval: Int, feedbackPacketSize: Int, feedbackInterval: Int,
            continuousFeedback: Boolean, bufferMs: Int,
    ): Long

    private external fun nativeSetBufferDuration(handle: Long, bufferMs: Int)
    private external fun nativeUsbAudioSetAltSetting(handle: Long, altSetting: Int): Boolean
    private external fun nativeUsbAudioSetSampleRate(handle: Long, sampleRateHz: Int, clockSourceId: Int): Boolean
    private external fun nativeUsbAudioStart(handle: Long): Boolean
    private external fun nativeUsbAudioWrite(handle: Long, pcmBuffer: FloatArray)
    private external fun nativeUsbAudioWriteRaw(handle: Long, pcmBuffer: ByteArray, inputBitDepth: Int)
    private external fun nativeUsbAudioStop(handle: Long)
    private external fun nativeFlush(handle: Long)
    private external fun nativePump(handle: Long)
    private external fun nativeDrainUrbs(handle: Long): Boolean
    private external fun nativeUsbAudioDestroy(handle: Long)
    private external fun nativeIsRunning(handle: Long): Boolean
    private external fun nativeGetFramesWritten(handle: Long): Long

    companion object {
        private const val TAG = "UsbAudioStream"

        init {
            System.loadLibrary("decent_usb_audio")
        }

        /**
         * Re-attaches the default Linux kernel driver (snd-usb-audio) to the specified USB interface.
         * @return 0 on success, negative on error
         */
        @JvmStatic
        external fun nativeUsbAttachKernelDriver(fd: Int, interfaceNum: Int): Int

        /**
         * Perform a USB port reset on the device. Resets the DAC's clock state.
         * The fd remains valid but all interface claims are released.
         * @return 0 on success, negative on error
         */
        @JvmStatic
        external fun nativeUsbReset(fd: Int): Int

        @JvmStatic
        external fun nativeUsbSpeed(fd: Int): Int

        /** "full" (USB 1.1, 1 ms frames), "high" (USB 2.0, 125 µs microframes)… */
        fun usbSpeedName(fd: Int): String = when (runCatching { nativeUsbSpeed(fd) }.getOrDefault(-1)) {
            1 -> "low"
            2 -> "full"
            3 -> "high"
            4 -> "wireless"
            5 -> "super"
            6 -> "super+"
            else -> "unknown"
        }
    }
}
