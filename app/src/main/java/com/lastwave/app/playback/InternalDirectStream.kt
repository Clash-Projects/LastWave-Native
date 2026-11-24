/*
 * Copyright (C) 2026 LastWave Authors (https://github.com/Clash-Projects/LastWave-Native)
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This file is part of LastWave.
 *
 * LastWave is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * LastWave is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LastWave. If not, see <https://www.gnu.org/licenses/>.
 */

package com.lastwave.app.playback

import java.nio.ByteBuffer

/**
 * Thin JNI wrapper around the native exclusive (MMAP) AAudio stream in
 * DirectOutputStream.cpp. One instance owns one native stream.
 */
class InternalDirectStream : AutoCloseable {

    /** What an EXCLUSIVE open with no format request is granted on the current route. */
    data class Probe(
        val status: Int,
        val exclusive: Boolean,
        val mmap: Boolean,
        val sampleRateHz: Int,
        val channelCount: Int,
        val encoding: Int,
        val deviceId: Int,
        val framesPerBurst: Int,
        val bufferCapacityFrames: Int,
    ) {
        val usable: Boolean get() = status == 0 && exclusive && sampleRateHz > 0 && encoding >= 0
    }

    data class Info(
        val sampleRateHz: Int,
        val channelCount: Int,
        val encoding: Int,
        val deviceId: Int,
        val deviceBufferFrames: Int,
        val mmap: Boolean,
    )

    private var handle: Long = if (NATIVE_AVAILABLE) runCatching { nativeCreate() }.getOrDefault(0L) else 0L

    val isAvailable: Boolean get() = handle != 0L

    /** Returns [OPEN_OK] or one of the OPEN_* failure codes. */
    fun open(sampleRateHz: Int, channelCount: Int, encoding: Int): Int =
        if (handle == 0L) OPEN_BAD_ARGS
        else runCatching { nativeOpen(handle, sampleRateHz, channelCount, encoding) }.getOrDefault(OPEN_FAILED)

    fun closeStream() {
        if (handle != 0L) runCatching { nativeClose(handle) }
    }

    fun start(): Boolean = handle != 0L && runCatching { nativeStart(handle) }.getOrDefault(false)

    fun pause(): Boolean = handle != 0L && runCatching { nativePause(handle) }.getOrDefault(false)

    fun flush() {
        if (handle != 0L) runCatching { nativeFlush(handle) }
    }

    /**
     * Queues whole frames from [buffer] (position..limit, direct buffer only)
     * and advances its position by what was accepted. Returns frames accepted.
     */
    fun write(buffer: ByteBuffer, inputEncoding: Int, inputChannels: Int): Int {
        if (handle == 0L || !buffer.isDirect) return 0
        val bytesPerFrame = bytesPerSample(inputEncoding) * inputChannels
        if (bytesPerFrame <= 0) return 0
        val frames = buffer.remaining() / bytesPerFrame
        if (frames <= 0) return 0
        val accepted = runCatching {
            nativeWrite(handle, buffer, buffer.position(), frames, inputEncoding, inputChannels)
        }.getOrDefault(0)
        if (accepted > 0) buffer.position(buffer.position() + accepted * bytesPerFrame)
        return accepted
    }

    /** consumed frames, buffered frames, underruns, alive (0/1), started (0/1). */
    fun counters(): LongArray =
        if (handle == 0L) LongArray(5)
        else runCatching { nativeCounters(handle) }.getOrDefault(LongArray(5))

    fun info(): Info? {
        if (handle == 0L) return null
        val v = runCatching { nativeInfo(handle) }.getOrNull() ?: return null
        if (v.size < 6 || v[0] <= 0) return null
        return Info(v[0], v[1], v[2], v[3], v[4], v[5] == 1)
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) runCatching { nativeDestroy(h) }
    }

    companion object {
        const val ENC_I16 = 0
        const val ENC_I24 = 1
        const val ENC_I32 = 2
        const val ENC_FLOAT = 3

        const val OPEN_OK = 0
        const val OPEN_FAILED = -1
        const val OPEN_NOT_AAUDIO = -2
        const val OPEN_SHARED = -3
        const val OPEN_RATE_MISMATCH = -4
        const val OPEN_FORMAT_MISMATCH = -5
        const val OPEN_CHANNEL_MISMATCH = -6
        const val OPEN_BAD_ARGS = -7

        private val NATIVE_AVAILABLE: Boolean = runCatching {
            System.loadLibrary("lastwave_audio")
            true
        }.getOrDefault(false)

        /** True when liblastwave_audio.so (which hosts the exclusive stream) loaded. */
        val nativeLoaded: Boolean get() = NATIVE_AVAILABLE

        fun bytesPerSample(encoding: Int): Int = when (encoding) {
            ENC_I16 -> 2
            ENC_I24 -> 3
            ENC_I32, ENC_FLOAT -> 4
            else -> 0
        }

        fun bitsOf(encoding: Int): Int = when (encoding) {
            ENC_I16 -> 16
            ENC_I24 -> 24
            ENC_I32 -> 32
            ENC_FLOAT -> 32
            else -> 0
        }

        fun labelOf(encoding: Int): String = when (encoding) {
            ENC_I16 -> "16-bit"
            ENC_I24 -> "24-bit"
            ENC_I32 -> "32-bit int"
            ENC_FLOAT -> "32-bit float"
            else -> "?"
        }

        /** Probe needs no instance: it opens and closes a throwaway stream. */
        fun probe(channelCount: Int = 2): Probe? {
            if (!NATIVE_AVAILABLE) return null
            val v = runCatching { nativeProbe(channelCount) }.getOrNull() ?: return null
            if (v.size < 9) return null
            return Probe(v[0], v[1] == 1, v[2] == 1, v[3], v[4], v[5], v[6], v[7], v[8])
        }

        @JvmStatic private external fun nativeProbe(channelCount: Int): IntArray
        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeOpen(handle: Long, sampleRate: Int, channelCount: Int, encoding: Int): Int
        @JvmStatic private external fun nativeClose(handle: Long)
        @JvmStatic private external fun nativeStart(handle: Long): Boolean
        @JvmStatic private external fun nativePause(handle: Long): Boolean
        @JvmStatic private external fun nativeFlush(handle: Long)
        @JvmStatic private external fun nativeWrite(
            handle: Long,
            buffer: ByteBuffer,
            offsetBytes: Int,
            frames: Int,
            inputEncoding: Int,
            inputChannels: Int,
        ): Int
        @JvmStatic private external fun nativeCounters(handle: Long): LongArray
        @JvmStatic private external fun nativeInfo(handle: Long): IntArray
    }
}
