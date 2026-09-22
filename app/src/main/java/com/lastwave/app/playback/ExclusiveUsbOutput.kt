@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lastwave.app.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.lastwave.aeusb.AeUsbEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LastWave session around the audio_engine USB driver.
 *
 * PCM goes to isochronous URBs. AudioFlinger never sees the stream. Rate
 * switches use Java [android.hardware.usb.UsbDeviceConnection.setInterface]
 * (not native USBDEVFS_SETINTERFACE) and keep the same fd.
 *
 * Listening level comes from STREAM_MUSIC (volume keys). A verified UAC
 * Feature Unit keeps PCM untouched; otherwise PCM is software-scaled.
 */
@Singleton
class ExclusiveUsbOutput @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile private var wanted = false
    @Volatile private var active = false
    @Volatile private var clockMatched = false
    @Volatile private var hardwareVolume = false
    @Volatile private var configuredRateHz = 0
    @Volatile private var listeningGain = 1f
    @Volatile private var softwareGainValue = 1f

    private var stream: AeUsbEngine? = null
    private var connection: UsbDeviceConnection? = null
    private var sourceEncoding = 0
    private var useFloatWrite = false
    @Volatile private var startMediaTimeUs = 0L
    @Volatile private var startMediaTimeNeedsInit = true
    @Volatile private var mediaTimeBaseFrames = 0L
    @Volatile private var paused = false
    private var pendingVolume = 1f
    private var featureVolume: UacFeatureVolume? = null
    private var clockRechecked = false
    private var volumeReceiverRegistered = false
    @Volatile private var lastAppliedCombined = Float.NaN
    private var volumeProbed = false
    @Volatile private var lastNonMaxListeningGain = Float.NaN
    @Volatile private var ignoreStreamMusicMax = false

    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val volumePrefs =
        appContext.getSharedPreferences("lastwave_exclusive_usb", Context.MODE_PRIVATE)

    init {
        lastNonMaxListeningGain = volumePrefs.getFloat(KEY_LAST_NON_MAX_GAIN, Float.NaN)
        rememberStreamGain(readStreamMusicGain())
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VOLUME_CHANGED_ACTION) return
            val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC)
            if (streamType != AudioManager.STREAM_MUSIC) return
            syncListeningGain()
        }
    }

    fun setWanted(enabled: Boolean) {
        synchronized(lock) {
            if (wanted == enabled) {
                if (enabled) {
                    ensureVolumeObserverLocked()
                    syncListeningGainLocked()
                }
                return
            }
            wanted = enabled
            if (!enabled) {
                teardownLocked(closeDevice = true)
            } else {
                ensureVolumeObserverLocked()
                syncListeningGainLocked()
            }
        }
    }

    fun isWanted(): Boolean = wanted

    fun isActive(): Boolean = active

    fun isClockMatched(): Boolean = active && clockMatched

    fun usesHardwareVolume(): Boolean = active && hardwareVolume

    /**
     * PCM software gain for exclusive output. 1 when a verified Feature Unit
     * owns listening level; otherwise STREAM_MUSIC × Media3 volume.
     */
    fun softwareGain(): Float = if (!active || hardwareVolume) 1f else softwareGainValue

    fun currentRateHz(): Int = if (active) configuredRateHz else 0

    fun framesWritten(): Long = stream?.framesClock ?: 0L

    fun isPaused(): Boolean = paused

    /**
     * Pause/resume ISO writes without tearing down the USB session.
     * [pause] on the Media3 sink used to no-op while exclusive, so the DAC
     * kept consuming PCM after the user pressed pause.
     */
    fun setPaused(value: Boolean) {
        paused = value
        stream?.setPaused(value)
    }

    /** True while the isochronous stream is still accepting PCM. */
    fun isStreamAlive(): Boolean = active && stream?.isAlive == true

    /** Re-anchor the Media3 clock after an explicit seek. */
    fun noteSeek(positionUs: Long) {
        val timeUs = positionUs.coerceAtLeast(0L)
        val rate = configuredRateHz.coerceAtLeast(1)
        val targetFrames = timeUs * rate / C.MICROS_PER_SECOND
        Log.i(
            TAG,
            "seek timeUs=$timeUs targetFrames=$targetFrames rate=$rate " +
                "clock=${stream?.framesClock} alive=${stream?.isAlive}",
        )
        mediaTimeBaseFrames = stream?.framesClock ?: 0L
        startMediaTimeUs = timeUs
        startMediaTimeNeedsInit = false
    }

    fun syncListeningGain() {
        val stream = readStreamMusicGain() ?: return
        if (ignoreStreamMusicMax && stream >= 0.999f) return
        if (stream < 0.999f) ignoreStreamMusicMax = false
        val next = stream
        if (lastAppliedCombined.isFinite() &&
            kotlin.math.abs(next - listeningGain) < 1e-4f
        ) {
            listeningGain = next
            return
        }
        synchronized(lock) {
            listeningGain = next
            applyVolumeLocked()
        }
    }

    fun configure(format: Format): Boolean {
        if (!wanted) return false
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW ||
            format.sampleRate <= 0 ||
            format.channelCount !in 1..2
        ) {
            return false
        }
        val floatSource = format.pcmEncoding == C.ENCODING_PCM_FLOAT
        val sourceBits = sourceBitDepth(format.pcmEncoding)
        if (!floatSource && sourceBits == 0) return false
        synchronized(lock) {
            if (!wanted) return false
            return runCatching {
                configureLocked(format.sampleRate, format.channelCount, sourceBits, floatSource, format.pcmEncoding)
            }.onFailure { error ->
                Log.w(TAG, "Exclusive USB configure failed", error)
                teardownLocked(closeDevice = true)
            }.getOrDefault(false)
        }
    }

    fun write(buffer: ByteBuffer, presentationTimeUs: Long): Boolean {
        if (!buffer.hasRemaining()) return true
        if (paused) return false
        synchronized(lock) {
            val running = stream
            if (paused) return false
            if (!wanted || !active || running == null || !running.isAlive) {
                return false
            }
            val clock = running.framesClock
            if (startMediaTimeNeedsInit) {
                // Anchor once, to the first buffer. Later DASH chunks must
                // not rebase this or ExoPlayer waits after the first segment.
                mediaTimeBaseFrames = clock
                startMediaTimeUs = presentationTimeUs.coerceAtLeast(0L)
                startMediaTimeNeedsInit = false
            }
            recheckClockLocked()
            val remaining = buffer.remaining()
            if (useFloatWrite) {
                val floats = FloatArray(remaining / Float.SIZE_BYTES)
                val view = buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                view.get(floats)
                buffer.position(buffer.limit())
                return running.write(floats)
            }
            val encoding = writeRawEncoding(sourceEncoding)
            if (encoding < 0) return false
            val bytes = ByteArray(remaining)
            buffer.get(bytes)
            return running.writeRaw(bytes, encoding)
        }
    }

    fun getCurrentPositionUs(): Long {
        val running = stream ?: return androidx.media3.exoplayer.audio.AudioSink.CURRENT_POSITION_NOT_SET
        if (!active || configuredRateHz <= 0) {
            return androidx.media3.exoplayer.audio.AudioSink.CURRENT_POSITION_NOT_SET
        }
        if (startMediaTimeNeedsInit) {
            return androidx.media3.exoplayer.audio.AudioSink.CURRENT_POSITION_NOT_SET
        }
        val frames = (running.framesClock - mediaTimeBaseFrames).coerceAtLeast(0L)
        return startMediaTimeUs + frames * C.MICROS_PER_SECOND / configuredRateHz
    }

    /**
     * Writes are blocking and consume the Media3 buffer. Reporting "pending"
     * while the ISO stream is merely alive makes ExoPlayer wait to drain an
     * AudioTrack that does not exist — next-track / seek stalls for seconds.
     */
    /**
     * ExoPlayer treats "no pending data" as "the sink is idle" and drops to
     * BUFFERING between DASH chunks. While exclusive USB is playing, the
     * isochronous pipeline is that pending audio.
     */
    fun hasPendingData(): Boolean = active && !paused && stream?.isAlive == true

    fun flush() {
        synchronized(lock) {
            stream?.flush()
            // Seek only flushes the sink. ExoPlayer does not call play()
            // again, so a pause flag left set here means every later buffer
            // is refused and the DAC stays silent while the bar moves on.
            paused = false
            stream?.setPaused(false)
        }
    }

    /**
     * The stream stopped without the device being closed. [flush] discards
     * leftover URBs and marks it running again. [start] would zero the DAC
     * clock and make the sink position jump back to the beginning.
     */
    fun restartIfStopped(): Boolean {
        val running = stream ?: return false
        if (running.isAlive) return false
        running.flush()
        if (!running.isAlive) return false
        synchronized(lock) {
            paused = false
            mediaTimeBaseFrames = running.framesClock
        }
        Log.i(TAG, "seek rearmed clock=${running.framesClock} startUs=$startMediaTimeUs")
        return true
    }

    fun handleDiscontinuity() {
        // A DASH segment boundary is not a seek. Resetting the sink clock
        // here made ExoPlayer wait forever after the first ~5s chunk
        // ("keeps loading") while the DAC had already stopped.
    }

    /**
     * ExoPlayer sink reset between items. Keep the USB session so the next
     * configure can reuse the stream instead of closeDevice + Feature Unit
     * re-probe (seconds of control-transfer timeouts).
     */
    fun prepareForNextItem() {
        synchronized(lock) {
            stream?.flush()
            stream?.setPaused(false)
            paused = false
            startMediaTimeNeedsInit = true
            startMediaTimeUs = 0L
            mediaTimeBaseFrames = stream?.framesClock ?: 0L
        }
    }

    fun reset() {
        synchronized(lock) {
            teardownLocked(closeDevice = true)
        }
    }

    fun release() {
        synchronized(lock) {
            wanted = false
            teardownLocked(closeDevice = true)
        }
    }

    fun setVolume(volume: Float) {
        val normalized = volume.coerceIn(0f, 1f)
        synchronized(lock) {
            pendingVolume = normalized
            applyVolumeLocked()
        }
    }

    private fun configureLocked(
        sampleRate: Int,
        channelCount: Int,
        sourceBits: Int,
        floatSource: Boolean,
        pcmEncoding: Int,
    ): Boolean {
        val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val usbDevice = manager.deviceList.values.firstOrNull { dev ->
            (0 until dev.interfaceCount).any {
                dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }
        } ?: return failLocked("no USB audio device")
        if (!manager.hasPermission(usbDevice)) return failLocked("USB permission missing")

        val bits = if (floatSource || sourceBits == 0) 24 else sourceBits
        val reuse = stream
        if (reuse != null &&
            reuse.isAlive &&
            configuredRateHz == sampleRate &&
            active &&
            sourceEncoding == pcmEncoding
        ) {
            paused = false
            reuse.setPaused(false)
            startMediaTimeNeedsInit = true
            mediaTimeBaseFrames = reuse.framesClock
            ensureVolumeObserverLocked()
            syncListeningGainLocked()
            return true
        }

        stopStreamLocked()
        connection?.close()
        connection = null
        val opened = manager.openDevice(usbDevice) ?: return failLocked("openDevice failed")
        val created = AeUsbEngine.open(opened.fileDescriptor, sampleRate, channelCount, bits)
        if (created == null) {
            opened.close()
            return failLocked("audio_engine USB start failed")
        }
        connection = opened
        stream = created
        sourceEncoding = pcmEncoding
        useFloatWrite = floatSource
        configuredRateHz = sampleRate
        active = true
        paused = false
        startMediaTimeNeedsInit = true
        startMediaTimeUs = 0L
        mediaTimeBaseFrames = 0L
        clockRechecked = true
        clockMatched = true
        hardwareVolume = false
        featureVolume = null
        Log.i(
            TAG,
            "audio_engine USB started ${usbDevice.productName} ${sampleRate}Hz " +
                "${channelCount}ch srcBits=$sourceBits",
        )

        ensureVolumeObserverLocked()
        rememberStreamGain(readStreamMusicGain())
        listeningGain = listeningGainForDac()
        lastAppliedCombined = Float.NaN
        applyVolumeLocked()
        Log.i(
            TAG,
            "exclusive volume hardware=$hardwareVolume listening=$listeningGain softwareGain=$softwareGainValue",
        )
        return true
    }

    private fun recheckClockLocked() {
        clockMatched = true
        clockRechecked = true
    }

    private fun ensureVolumeObserverLocked() {
        if (volumeReceiverRegistered) return
        volumeReceiverRegistered = runCatching {
            ContextCompat.registerReceiver(
                appContext,
                volumeReceiver,
                IntentFilter(VOLUME_CHANGED_ACTION),
                ContextCompat.RECEIVER_EXPORTED,
            )
            true
        }.getOrDefault(false)
    }

    private fun unregisterVolumeObserverLocked() {
        if (!volumeReceiverRegistered) return
        runCatching { appContext.unregisterReceiver(volumeReceiver) }
        volumeReceiverRegistered = false
    }

    private fun readStreamMusicGain(): Float? {
        val manager = audioManager ?: return null
        val max = runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        if (max <= 0) return null
        val vol = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(max)
        val gain = vol.coerceIn(0, max).toFloat() / max
        rememberStreamGain(gain)
        return gain
    }

    private fun rememberStreamGain(gain: Float?) {
        if (gain != null && gain in 0.01f..0.999f) {
            lastNonMaxListeningGain = gain
            runCatching { volumePrefs.edit().putFloat(KEY_LAST_NON_MAX_GAIN, gain).apply() }
        }
    }

    /**
     * USB connect often reports STREAM_MUSIC at max while the DAC analog
     * path is still 0 dB. Prefer the last non-max key level so Feature Unit
     * SET_CUR matches what the user hears after the first volume press.
     */
    private fun listeningGainForDac(): Float {
        val stream = readStreamMusicGain() ?: listeningGain
        if (stream >= 0.999f && lastNonMaxListeningGain.isFinite() &&
            lastNonMaxListeningGain in 0.01f..0.999f
        ) {
            ignoreStreamMusicMax = true
            return lastNonMaxListeningGain
        }
        ignoreStreamMusicMax = false
        return stream
    }

    private fun syncListeningGainLocked() {
        listeningGain = listeningGainForDac()
        applyVolumeLocked()
    }

    private fun applyVolumeLocked() {
        val combined = (listeningGain * pendingVolume).coerceIn(0f, 1f)
        val unchanged = lastAppliedCombined.isFinite() && kotlin.math.abs(combined - lastAppliedCombined) < 1e-4f
        if (hardwareVolume) {
            softwareGainValue = 1f
            if (!unchanged) {
                featureVolume?.setNormalized(combined)
                lastAppliedCombined = combined
            }
        } else {
            softwareGainValue = combined
            lastAppliedCombined = combined
        }
    }

    private fun stopStreamLocked() {
        val running = stream ?: return
        runCatching { running.stop() }
        runCatching { running.drainUrbs() }
        runCatching { running.release() }
        stream = null
        active = false
    }

    private fun teardownLocked(closeDevice: Boolean) {
        stopStreamLocked()
        if (closeDevice) {
            runCatching { connection?.close() }
            connection = null
        }
        featureVolume = null
        volumeProbed = false
        ignoreStreamMusicMax = false
        hardwareVolume = false
        clockMatched = false
        configuredRateHz = 0
        active = false
        softwareGainValue = 1f
        lastAppliedCombined = Float.NaN
        startMediaTimeNeedsInit = true
        startMediaTimeUs = 0L
        mediaTimeBaseFrames = 0L
        paused = false
        if (closeDevice) unregisterVolumeObserverLocked()
    }

    private fun failLocked(reason: String): Boolean {
        Log.w(TAG, "Exclusive USB fail-open: $reason")
        teardownLocked(closeDevice = true)
        return false
    }

    private companion object {
        const val TAG = "ExclusiveUsb"
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val KEY_LAST_NON_MAX_GAIN = "last_non_max_stream_gain"
        const val PLL_SETTLE_MS = 50L
        const val CLOCK_VALID_TRIES = 40
        const val CLOCK_VALID_STEP_MS = 5L
        const val RAW_PCM16 = 2
        const val RAW_PCM24 = 0x15
        const val RAW_PCM32 = 0x16

        fun isoPacketBytes(raw: Int): Int {
            if (raw <= 0) return 0
            val extra = (raw shr 11) and 0x3
            val size = raw and 0x7FF
            return if (extra > 0) size * (1 + extra) else raw
        }

        fun minIsoPacketBytes(rateHz: Int, channels: Int, bitDepth: Int, interval: Int = 1): Int {
            val bpf = ((bitDepth + 7) / 8).coerceAtLeast(1) * channels.coerceIn(1, 8)
            val microframes = if (interval > 1) 1 shl (interval - 1).coerceAtMost(4) else 1
            val frames = ((rateHz + 7999) / 8000) * microframes + 1
            return frames * bpf
        }

        fun sourceBitDepth(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_16BIT, RAW_PCM16 -> 16
            C.ENCODING_PCM_24BIT, RAW_PCM24 -> 24
            C.ENCODING_PCM_32BIT, RAW_PCM32 -> 32
            else -> 0
        }

        fun writeRawEncoding(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_16BIT, RAW_PCM16 -> RAW_PCM16
            C.ENCODING_PCM_24BIT, RAW_PCM24 -> RAW_PCM24
            C.ENCODING_PCM_32BIT, RAW_PCM32 -> RAW_PCM32
            else -> -1
        }
    }
}
