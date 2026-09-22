package com.lastwave.aeusb

import android.util.Log

/**
 * Exclusive USB output using minervarr/audio_engine [UsbAudioDriver] (AGPL-3).
 * The Android USB connection fd is duplicated inside native code. This object
 * does not take ownership of the original fd.
 */
class AeUsbEngine private constructor(private var nativeHandle: Long) {
    val isReady: Boolean
        get() = nativeHandle != 0L

    val isAlive: Boolean
        get() = nativeHandle != 0L && nativeIsStreaming(nativeHandle)

    val framesClock: Long
        get() = if (nativeHandle != 0L) nativeFramesPlayed(nativeHandle) else 0L

    fun write(pcm: FloatArray): Boolean {
        if (nativeHandle == 0L) return false
        return nativeWriteFloat(nativeHandle, pcm)
    }

    fun writeRaw(pcm: ByteArray, encoding: Int): Boolean {
        if (nativeHandle == 0L) return false
        return when (encoding) {
            2 -> {
                val samples = ShortArray(pcm.size / 2)
                var i = 0
                while (i < samples.size) {
                    val b = i * 2
                    samples[i] = ((pcm[b].toInt() and 0xff) or (pcm[b + 1].toInt() shl 8)).toShort()
                    i++
                }
                nativeWrite16(nativeHandle, samples)
            }
            0x15 -> nativeWrite24(nativeHandle, pcm)
            0x16 -> {
                val samples = IntArray(pcm.size / 4)
                var i = 0
                while (i < samples.size) {
                    val b = i * 4
                    samples[i] = (pcm[b].toInt() and 0xff) or
                        ((pcm[b + 1].toInt() and 0xff) shl 8) or
                        ((pcm[b + 2].toInt() and 0xff) shl 16) or
                        (pcm[b + 3].toInt() shl 24)
                    i++
                }
                nativeWrite32(nativeHandle, samples)
            }
            else -> false
        }
    }

    /** Drop queued PCM and the isochronous transfers still carrying the old position. */
    fun flush() {
        if (nativeHandle == 0L) return
        nativeSeek(nativeHandle)
    }

    fun setPaused(paused: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetPaused(nativeHandle, paused)
    }

    fun stop() = Unit

    fun drainUrbs(): Int = 0

    fun release() {
        if (nativeHandle == 0L) return
        nativeClose(nativeHandle)
        nativeHandle = 0L
        Log.i(TAG, "audio_engine USB driver closed")
    }

    companion object {
        private const val TAG = "AeUsb"

        init {
            System.loadLibrary("ae_usb")
        }

        fun open(fd: Int, sampleRate: Int, channels: Int, bits: Int): AeUsbEngine? {
            val handle = nativeOpen(fd)
            if (handle == 0L) return null
            val engine = AeUsbEngine(handle)
            if (!nativeConfigure(handle, sampleRate, channels, bits) || !nativeStart(handle)) {
                engine.release()
                return null
            }
            return engine
        }

        @JvmStatic private external fun nativeOpen(fd: Int): Long
        @JvmStatic private external fun nativeConfigure(handle: Long, rate: Int, channels: Int, bits: Int): Boolean
        @JvmStatic private external fun nativeStart(handle: Long): Boolean
    }

    private external fun nativeWrite16(handle: Long, pcm: ShortArray): Boolean
    private external fun nativeWrite24(handle: Long, pcm: ByteArray): Boolean
    private external fun nativeWrite32(handle: Long, pcm: IntArray): Boolean
    private external fun nativeWriteFloat(handle: Long, pcm: FloatArray): Boolean
    private external fun nativeSeek(handle: Long)
    private external fun nativeSetPaused(handle: Long, paused: Boolean)
    private external fun nativeClose(handle: Long)
    private external fun nativeIsStreaming(handle: Long): Boolean
    private external fun nativeFramesPlayed(handle: Long): Long
}
