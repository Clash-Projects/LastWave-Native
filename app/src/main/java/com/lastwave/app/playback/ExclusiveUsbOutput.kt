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
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbAudioStream
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LastWave session around the decent-player usbdevfs driver (MIT).
 *
 * PCM goes to isochronous URBs. AudioFlinger never sees the stream.
 *
 * Listening level comes from STREAM_MUSIC (volume keys). A Feature Unit that
 * changes GET_CUR keeps PCM untouched; otherwise PCM is software-scaled.
 * The gold clock check is the DAC GET_CUR rate, not a successful open.
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

    private val usbAudio = UsbAudioDevice.getInstance(appContext)
    private var stream: UsbAudioStream? = null
    private val pcmLock = Object()
    private val pcmQueue = ArrayDeque<QueuedPcm>()
    @Volatile private var queuedBytes = 0
    @Volatile private var flushRequested = false
    @Volatile private var writerStop = false
    private var writer: Thread? = null
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

    fun framesWritten(): Long = stream?.framesWritten ?: 0L

    fun isPaused(): Boolean = paused

    /**
     * Pause/resume ISO writes without tearing down the USB session.
     * [pause] on the Media3 sink used to no-op while exclusive, so the DAC
     * kept consuming PCM after the user pressed pause.
     */
    fun setPaused(value: Boolean) {
        paused = value
        synchronized(pcmLock) {
            if (value) {
                pcmQueue.clear()
                queuedBytes = 0
            }
            pcmLock.notifyAll()
        }
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
                "clock=${stream?.framesWritten} alive=${stream?.isAlive}",
        )
        mediaTimeBaseFrames = stream?.framesWritten ?: 0L
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
        val running = stream
        if (!wanted || !active || running == null || !running.isAlive) return false
        val size = buffer.remaining()
        // Full queue means "try again", like AudioTrack. Blocking here holds
        // ExoPlayer's playback thread, and that thread is what loads the next
        // network bytes. A forward seek then plays the buffered couple of
        // seconds and sticks on the loading spinner.
        synchronized(pcmLock) {
            if (queuedBytes > 0 && queuedBytes + size > MAX_QUEUED_BYTES) return false
        }
        if (startMediaTimeNeedsInit) {
            mediaTimeBaseFrames = running.framesWritten
            startMediaTimeUs = presentationTimeUs.coerceAtLeast(0L)
            startMediaTimeNeedsInit = false
        }
        recheckClockLocked()
        val chunk = if (useFloatWrite) {
            val floats = FloatArray(size / Float.SIZE_BYTES)
            buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
            buffer.position(buffer.limit())
            QueuedPcm(floats, null, 0, size)
        } else {
            val encoding = writeRawEncoding(sourceEncoding)
            if (encoding < 0) return false
            val bytes = ByteArray(size)
            buffer.get(bytes)
            QueuedPcm(null, bytes, encoding, size)
        }
        synchronized(pcmLock) {
            if (paused || writerStop) return false
            pcmQueue.addLast(chunk)
            queuedBytes += size
            pcmLock.notifyAll()
        }
        return true
    }

    fun getCurrentPositionUs(): Long {
        val running = stream ?: return androidx.media3.exoplayer.audio.AudioSink.CURRENT_POSITION_NOT_SET
        if (!active || configuredRateHz <= 0) {
            return androidx.media3.exoplayer.audio.AudioSink.CURRENT_POSITION_NOT_SET
        }
        if (startMediaTimeNeedsInit) {
            return androidx.media3.exoplayer.audio.AudioSink.CURRENT_POSITION_NOT_SET
        }
        val frames = (running.framesWritten - mediaTimeBaseFrames).coerceAtLeast(0L)
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
    fun hasPendingData(): Boolean =
        active && !paused && (queuedBytes > 0 || stream?.isAlive == true)

    fun flush() {
        synchronized(pcmLock) {
            pcmQueue.clear()
            queuedBytes = 0
            flushRequested = true
            // Seek only flushes the sink. ExoPlayer does not call play()
            // again, so a pause flag left set here means every later buffer
            // is refused and the DAC stays silent while the bar moves on.
            paused = false
            pcmLock.notifyAll()
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
        if (!running.start()) return false
        synchronized(lock) {
            paused = false
            mediaTimeBaseFrames = running.framesWritten
        }
        Log.i(TAG, "seek rearmed clock=${running.framesWritten} startUs=$startMediaTimeUs")
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
        synchronized(pcmLock) {
            pcmQueue.clear()
            queuedBytes = 0
            flushRequested = true
            paused = false
            pcmLock.notifyAll()
        }
        startMediaTimeNeedsInit = true
        startMediaTimeUs = 0L
        mediaTimeBaseFrames = stream?.framesWritten ?: 0L
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
            startMediaTimeNeedsInit = true
            mediaTimeBaseFrames = reuse.framesWritten
            ensureVolumeObserverLocked()
            syncListeningGainLocked()
            return true
        }

        stopStreamLocked()
        usbAudio.closeDevice()
        connection = null
        val info = usbAudio.openDevice(usbDevice) ?: return failLocked("openDevice failed")
        val (alt, wireBits) = usbAudio.findAltSettingForBitDepth(bits)
        usbAudio.setSampleRate(sampleRate)
        if (!usbAudio.setAltSetting(alt)) return failLocked("setAltSetting $alt failed")
        val selected = (0 until usbDevice.interfaceCount)
            .map { usbDevice.getInterface(it) }
            .firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    it.interfaceSubclass == 2 &&
                    it.alternateSetting == alt &&
                    it.endpointCount > 0
            }
        val maxPacket = selected?.let { iface ->
            (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                        it.direction == UsbConstants.USB_DIR_OUT
                }?.maxPacketSize
        } ?: info.maxPacketSize
        val created = UsbAudioStream(
            info.fd,
            info.interfaceId,
            info.endpointOutAddress,
            info.endpointFeedbackAddress,
            sampleRate,
            channelCount,
            wireBits,
            maxPacket,
        )
        if (!created.isReady || !created.start()) {
            created.release()
            return failLocked("decent USB start failed")
        }
        connection = info.connection
        stream = created
        sourceEncoding = pcmEncoding
        useFloatWrite = floatSource
        configuredRateHz = sampleRate
        active = true
        paused = false
        startMediaTimeNeedsInit = true
        startMediaTimeUs = 0L
        mediaTimeBaseFrames = 0L
        val reported = usbAudio.readSampleRate()
        clockMatched = reported == sampleRate
        clockRechecked = true
        val controlId = (0 until usbDevice.interfaceCount)
            .map { usbDevice.getInterface(it) }
            .firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 1
            }?.id ?: 0
        val volumeControl = UacFeatureVolume(info.connection, controlId)
        featureVolume = volumeControl
        hardwareVolume = volumeControl.attach()
        Log.i(
            TAG,
            "decent USB started ${usbDevice.productName} ${sampleRate}Hz " +
                "alt=$alt wireBits=$wireBits reported=$reported clockMatched=$clockMatched " +
                "hardwareVolume=$hardwareVolume",
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
        startWriterLocked(created)
        return true
    }

    private fun recheckClockLocked() {
        if (clockRechecked) return
        val reported = usbAudio.readSampleRate()
        clockMatched = reported == configuredRateHz && reported > 0
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
                val applied = featureVolume?.setNormalized(combined) == true
                if (!applied) {
                    hardwareVolume = false
                    softwareGainValue = combined
                    Log.w(TAG, "Feature Unit SET_CUR failed; falling back to software gain")
                }
                lastAppliedCombined = combined
            }
        } else {
            softwareGainValue = combined
            lastAppliedCombined = combined
        }
    }

    private fun startWriterLocked(target: UsbAudioStream) {
        stopWriterLocked()
        writerStop = false
        flushRequested = false
        val thread = Thread({ writeLoop(target) }, "ExclusiveUsbWriter")
        writer = thread
        thread.start()
    }

    private fun stopWriterLocked() {
        writerStop = true
        synchronized(pcmLock) {
            pcmQueue.clear()
            queuedBytes = 0
            pcmLock.notifyAll()
        }
        writer?.join(2_000)
        writer = null
    }

    private fun writeLoop(target: UsbAudioStream) {
        while (!writerStop) {
            val next = synchronized(pcmLock) {
                if (!writerStop && !flushRequested && (paused || pcmQueue.isEmpty())) {
                    pcmLock.wait(20)
                }
                if (writerStop) return@synchronized null
                if (flushRequested) {
                    flushRequested = false
                    pcmQueue.clear()
                    queuedBytes = 0
                    return@synchronized FLUSH_MARKER
                }
                if (paused || pcmQueue.isEmpty()) return@synchronized IDLE_MARKER
                val chunk = pcmQueue.removeFirst()
                queuedBytes = (queuedBytes - chunk.byteSize).coerceAtLeast(0)
                chunk
            } ?: break
            if (next === FLUSH_MARKER) {
                runCatching { target.flush() }
                // Native flush zeroes the frame counter. Rebase or ExoPlayer's
                // clock stays at the seek point and the loader stops fetching.
                mediaTimeBaseFrames = target.framesWritten
                continue
            }
            if (next === IDLE_MARKER) {
                runCatching { target.pump() }
                continue
            }
            if (!target.isAlive) break
            if (next.floats != null) target.write(next.floats)
            else if (next.raw != null) target.writeRaw(next.raw, next.encoding)
        }
    }

    private fun stopStreamLocked() {
        stopWriterLocked()
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
            runCatching { usbAudio.closeDevice() }
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

    private class QueuedPcm(
        val floats: FloatArray?,
        val raw: ByteArray?,
        val encoding: Int,
        val byteSize: Int,
    )

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
        const val MAX_QUEUED_BYTES = 512 * 1024
        val FLUSH_MARKER = QueuedPcm(null, null, -1, 0)
        val IDLE_MARKER = QueuedPcm(null, null, -2, 0)

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
