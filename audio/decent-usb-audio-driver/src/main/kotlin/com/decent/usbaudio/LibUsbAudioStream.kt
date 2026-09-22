package com.decent.usbaudio

import android.util.Log

/**
 * Bit-perfect USB exclusive transport. PCM is queued in a native ring and a
 * libusb thread submits the isochronous transfers. [flush] cancels those
 * transfers and drops PCM from the previous position.
 *
 * Clock, alternate setting, and feature-unit volume stay in [UsbAudioDevice].
 * This class only moves PCM.
 */
class LibUsbAudioStream(
    fd: Int,
    endpointOut: Int,
    endpointFeedback: Int,
    sampleRate: Int,
    channelCount: Int,
    bitDepth: Int,
    maxPacketSize: Int,
    packetInterval: Int,
) {
    private var nativeHandle: Long = nativeCreate(
        fd,
        endpointOut,
        endpointFeedback,
        sampleRate,
        channelCount,
        bitDepth,
        maxPacketSize,
        packetInterval,
    )

    val isReady: Boolean
        get() = nativeHandle != 0L

    val isAlive: Boolean
        get() = nativeHandle != 0L && nativeIsRunning(nativeHandle)

    /** Frames the DAC transfer callbacks have completed. Not cleared on seek. */
    val framesClock: Long
        get() = if (nativeHandle != 0L) nativeFrames(nativeHandle) else 0L

    fun start(): Boolean {
        if (nativeHandle == 0L) return false
        return nativeStart(nativeHandle)
    }

    fun write(pcm: FloatArray) {
        if (nativeHandle == 0L) return
        nativeWriteFloat(nativeHandle, pcm)
    }

    fun writeRaw(pcm: ByteArray, encoding: Int) {
        if (nativeHandle == 0L) return
        val bits = when (encoding) {
            2 -> 16
            0x15 -> 24
            0x16 -> 32
            else -> return
        }
        nativeWriteRaw(nativeHandle, pcm, bits)
    }

    fun flush() {
        if (nativeHandle == 0L) return
        nativeFlush(nativeHandle)
    }

    /** Stop accepting PCM and cancel in-flight transfers. Does not free the device. */
    fun stop() {
        if (nativeHandle == 0L) return
        nativeHalt(nativeHandle)
    }

    /** Transfers are already finished by [stop] or [flush]. */
    fun drainUrbs(): Int = 0

    fun setPaused(paused: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetPaused(nativeHandle, paused)
    }

    fun release() {
        if (nativeHandle == 0L) return
        nativeStop(nativeHandle)
        nativeHandle = 0L
        Log.i(TAG, "libusb stream released")
    }

    private external fun nativeCreate(
        fd: Int,
        endpointOut: Int,
        endpointFeedback: Int,
        sampleRate: Int,
        channelCount: Int,
        bitDepth: Int,
        maxPacketSize: Int,
        packetInterval: Int,
    ): Long

    private external fun nativeStart(handle: Long): Boolean
    private external fun nativeWriteRaw(handle: Long, pcm: ByteArray, inputBits: Int)
    private external fun nativeWriteFloat(handle: Long, pcm: FloatArray)
    private external fun nativeFlush(handle: Long)
    private external fun nativeHalt(handle: Long)
    private external fun nativeSetPaused(handle: Long, paused: Boolean)
    private external fun nativeStop(handle: Long)
    private external fun nativeIsRunning(handle: Long): Boolean
    private external fun nativeFrames(handle: Long): Long

    companion object {
        private const val TAG = "LibUsbUac"

        init {
            System.loadLibrary("decent_usb_audio")
        }
    }
}
