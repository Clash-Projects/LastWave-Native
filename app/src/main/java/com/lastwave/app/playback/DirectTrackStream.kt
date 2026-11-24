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

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The UAPP "HiRes Direct" recipe, with public APIs only.
 *
 * A plain music AudioTrack in 24-bit packed PCM at the source rate. On
 * Qualcomm-based phones (seen on a HONOR Win via dumpsys) the vendor audio
 * policy routes such a track to the `direct_pcm_out` profile: an
 * AUDIO_OUTPUT_FLAG_DIRECT output that AudioFlinger never mixes, available on
 * the speaker, the jack, Bluetooth A2DP (encoded in the DSP) and more, at
 * 44.1–384 kHz. Apps cannot request that flag themselves; the policy grants it.
 *
 * Whether it was granted is tested, not assumed: AudioTrack refuses playback
 * rate changes on direct/offloaded tracks (INVALID_OPERATION), while a mixed
 * track accepts them. [probeDirect] runs that test before playback starts.
 */
@RequiresApi(Build.VERSION_CODES.S)
class DirectTrackStream(private val attributes: AudioAttributes) {

    private var track: AudioTrack? = null
    var encoding: Int = -1
        private set
    var sampleRateHz: Int = 0
        private set
    var channelCount: Int = 0
        private set
    private var bytesPerFrame = 0

    /** Converted bytes AudioTrack has not taken yet (never more than one input buffer). */
    private var pending: ByteBuffer? = null
    private var scratch: ByteBuffer? = null
    private var bytesWritten = 0L
    private var lastHead = 0L
    private var headWraps = 0L
    @Volatile private var dead = false
    @Volatile private var routedDeviceId: Int? = null
    private val routingListener = android.media.AudioRouting.OnRoutingChangedListener { router ->
        // The track follows Android to a new device (Bluetooth on/off, jack…).
        // The new output may be mixed: declare the stream lost so the sink
        // renegotiates and re-verifies instead of claiming "direct".
        val id = runCatching { router.routedDevice?.id }.getOrNull()
        val previous = routedDeviceId
        routedDeviceId = id
        if (previous != null && id != previous) {
            Log.i(TAG, "rerouted $previous -> $id: direct status must be re-verified")
            dead = true
        }
    }

    fun open(rateHz: Int, channels: Int, wireEncoding: Int): Boolean {
        release()
        val androidEncoding = androidEncodingOf(wireEncoding) ?: return false
        val mask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> return false
        }
        return try {
            val format = AudioFormat.Builder()
                .setSampleRate(rateHz)
                .setEncoding(androidEncoding)
                .setChannelMask(mask)
                .build()
            val frameBytes = InternalDirectStream.bytesPerSample(wireEncoding) * channels
            val minBytes = AudioTrack.getMinBufferSize(rateHz, mask, androidEncoding)
            if (minBytes <= 0) return false
            // ~500 ms: a music player, not a game. Large buffers also keep the
            // track off the FAST mixer path, which would skew the direct test.
            val size = maxOf(minBytes * 4, rateHz / 2 * frameBytes)
            val created = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                .build()
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                created.release()
                return false
            }
            track = created
            encoding = wireEncoding
            sampleRateHz = rateHz
            channelCount = channels
            bytesPerFrame = frameBytes
            dead = false
            routedDeviceId = null
            runCatching { created.addOnRoutingChangedListener(routingListener, Handler(Looper.getMainLooper())) }
            resetCounters()
            true
        } catch (error: Exception) {
            Log.w(TAG, "AudioTrack ${rateHz}Hz enc=$wireEncoding refused", error)
            release()
            false
        }
    }

    /**
     * True = Android refused a playback-rate change, i.e. the track sits on a
     * DIRECT (or offloaded) output. False = the change was accepted: the track
     * is mixed. Null = inconclusive. Must run before [play].
     */
    fun probeDirect(): Boolean? {
        val target = track ?: return null
        return try {
            target.playbackParams = PlaybackParams().setSpeed(1.01f).setPitch(1.0f)
            target.playbackParams = PlaybackParams().setSpeed(1.0f).setPitch(1.0f)
            false
        } catch (_: IllegalArgumentException) {
            true
        } catch (_: IllegalStateException) {
            true
        } catch (_: Throwable) {
            null
        }
    }

    fun routedDeviceName(): String? = runCatching { track?.routedDevice?.productName?.toString() }.getOrNull()

    fun routedDeviceType(): Int? = runCatching { track?.routedDevice?.type }.getOrNull()

    fun isAlive(): Boolean = track != null && !dead && track?.state == AudioTrack.STATE_INITIALIZED

    /**
     * Queues [src] (position..limit, interleaved PCM in [srcEncoding]). Returns
     * true once [src] is fully taken; converted leftovers are kept in [pending]
     * and pushed on the next call or by [hasPendingData].
     */
    fun write(src: ByteBuffer, srcEncoding: Int, srcChannels: Int): Boolean {
        val target = track ?: return false
        if (dead) return false
        if (!drainPending()) return false
        if (!src.hasRemaining()) return true
        if (srcChannels != channelCount) return false
        if (srcEncoding == encoding) {
            val written = target.write(src, src.remaining(), AudioTrack.WRITE_NON_BLOCKING)
            if (written < 0) {
                onWriteError(written)
                return false
            }
            bytesWritten += written
            return !src.hasRemaining()
        }
        val out = convert(src, srcEncoding, encoding, scratchFor(src.remaining(), srcEncoding))
            ?: return false
        pending = out
        drainPending()
        return true
    }

    fun play() {
        runCatching { track?.play() }
    }

    fun pause() {
        runCatching { track?.pause() }
    }

    /** Drops everything queued (seek/stop). Restores the play state it had. */
    fun flush() {
        val target = track ?: return
        val wasPlaying = runCatching { target.playState == AudioTrack.PLAYSTATE_PLAYING }.getOrDefault(false)
        runCatching { target.pause() }
        runCatching { target.flush() }
        pending = null
        resetCounters()
        if (wasPlaying) runCatching { target.play() }
    }

    /** Frames actually presented, from the playback head (32-bit, wrap-safe). */
    fun playedFrames(): Long {
        val target = track ?: return 0L
        val head = runCatching { target.playbackHeadPosition.toLong() and 0xFFFF_FFFFL }.getOrDefault(lastHead)
        if (head < lastHead) headWraps += 1L shl 32
        lastHead = head
        return headWraps + head
    }

    fun writtenFrames(): Long = if (bytesPerFrame > 0) bytesWritten / bytesPerFrame else 0L

    fun hasPendingData(): Boolean {
        drainPending()
        return pending != null || writtenFrames() > playedFrames()
    }

    fun underruns(): Long = runCatching { track?.underrunCount?.toLong() ?: 0L }.getOrDefault(0L)

    fun release() {
        val target = track
        track = null
        pending = null
        if (target != null) {
            runCatching { target.removeOnRoutingChangedListener(routingListener) }
            runCatching { target.pause() }
            runCatching { target.flush() }
            runCatching { target.release() }
        }
        resetCounters()
    }

    private fun resetCounters() {
        bytesWritten = 0L
        lastHead = 0L
        headWraps = 0L
    }

    private fun drainPending(): Boolean {
        val target = track ?: return false
        val buffered = pending ?: return true
        if (buffered.hasRemaining()) {
            val written = target.write(buffered, buffered.remaining(), AudioTrack.WRITE_NON_BLOCKING)
            if (written < 0) {
                onWriteError(written)
                return false
            }
            bytesWritten += written
        }
        if (buffered.hasRemaining()) return false
        pending = null
        return true
    }

    private fun onWriteError(code: Int) {
        Log.w(TAG, "AudioTrack write error $code")
        if (code == AudioTrack.ERROR_DEAD_OBJECT) dead = true
    }

    private fun scratchFor(srcBytes: Int, srcEncoding: Int): ByteBuffer {
        val srcSample = InternalDirectStream.bytesPerSample(srcEncoding).coerceAtLeast(1)
        val need = srcBytes / srcSample * InternalDirectStream.bytesPerSample(encoding)
        val existing = scratch
        if (existing != null && existing.capacity() >= need) return existing
        return ByteBuffer.allocateDirect(maxOf(need, 64 * 1024)).order(ByteOrder.LITTLE_ENDIAN).also { scratch = it }
    }

    companion object {
        private const val TAG = "DirectTrack"

        fun androidEncodingOf(wireEncoding: Int): Int? = when (wireEncoding) {
            InternalDirectStream.ENC_I16 -> AudioFormat.ENCODING_PCM_16BIT
            InternalDirectStream.ENC_I24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            InternalDirectStream.ENC_I32 -> AudioFormat.ENCODING_PCM_32BIT
            InternalDirectStream.ENC_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
            else -> null
        }

        /**
         * Wire encoding to ask for, UAPP-style: 24-bit packed for everything
         * up to 24 real bits (16-bit is widened, losslessly), true 32-bit
         * integer kept as is. Float (lossy codecs) is rounded to 24-bit.
         */
        fun wireEncodingFor(srcEncoding: Int, effectiveBits: Int): Int = when (srcEncoding) {
            InternalDirectStream.ENC_I32 ->
                if (effectiveBits <= 24) InternalDirectStream.ENC_I24 else InternalDirectStream.ENC_I32
            else -> InternalDirectStream.ENC_I24
        }

        /**
         * Converts all of [src] into [dst] (cleared first) and returns [dst]
         * flipped for reading, or null for an unsupported pair. Little endian.
         */
        fun convert(src: ByteBuffer, srcEncoding: Int, dstEncoding: Int, dst: ByteBuffer): ByteBuffer? {
            val input = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            dst.clear()
            when {
                srcEncoding == InternalDirectStream.ENC_I16 && dstEncoding == InternalDirectStream.ENC_I24 -> {
                    while (input.remaining() >= 2) {
                        val s = input.short.toInt()
                        dst.put(0.toByte())
                        dst.put(s.toByte())
                        dst.put((s shr 8).toByte())
                    }
                }
                srcEncoding == InternalDirectStream.ENC_I32 && dstEncoding == InternalDirectStream.ENC_I24 -> {
                    while (input.remaining() >= 4) {
                        val s = input.int
                        dst.put((s shr 8).toByte())
                        dst.put((s shr 16).toByte())
                        dst.put((s shr 24).toByte())
                    }
                }
                srcEncoding == InternalDirectStream.ENC_FLOAT && dstEncoding == InternalDirectStream.ENC_I24 -> {
                    while (input.remaining() >= 4) {
                        val v = Math.round(input.float.toDouble() * 8_388_608.0)
                            .coerceIn(-8_388_608L, 8_388_607L).toInt()
                        dst.put(v.toByte())
                        dst.put((v shr 8).toByte())
                        dst.put((v shr 16).toByte())
                    }
                }
                srcEncoding == dstEncoding -> dst.put(input)
                else -> return null
            }
            src.position(src.limit())
            dst.flip()
            return dst
        }
    }
}
