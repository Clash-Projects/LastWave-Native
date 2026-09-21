@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lastwave.app.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * LastWave session around the vendor usbdevfs driver.
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

    private var stream: UsbAudioStream? = null
    private var usb: UsbAudioDevice? = null
    private var sourceEncoding = 0
    private var useFloatWrite = false
    private var startMediaTimeUs = 0L
    private var startMediaTimeNeedsInit = true
    private var pendingVolume = 1f
    private var featureVolume: UacFeatureVolume? = null
    private var clockRechecked = false
    private var volumeReceiverRegistered = false
    private var lastAppliedCombined = Float.NaN

    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

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

    fun syncListeningGain() {
        synchronized(lock) { syncListeningGainLocked() }
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
        synchronized(lock) {
            val running = stream
            if (!wanted || !active || running == null || !running.isAlive) {
                return false
            }
            if (startMediaTimeNeedsInit) {
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
                running.write(floats)
                return running.isAlive
            }
            val encoding = writeRawEncoding(sourceEncoding)
            if (encoding < 0) return false
            val bytes = ByteArray(remaining)
            buffer.get(bytes)
            running.writeRaw(bytes, encoding)
            return running.isAlive
        }
    }

    fun getCurrentPositionUs(): Long {
        synchronized(lock) {
            val running = stream ?: return androidx.media3.exoplayer.audio.AudioSink.CURRENT_POSITION_NOT_SET
            if (!active || startMediaTimeNeedsInit || configuredRateHz <= 0) {
                return androidx.media3.exoplayer.audio.AudioSink.CURRENT_POSITION_NOT_SET
            }
            return startMediaTimeUs + running.framesWritten * C.MICROS_PER_SECOND / configuredRateHz
        }
    }

    fun hasPendingData(): Boolean {
        val running = stream
        return active && running != null && running.isAlive
    }

    fun flush() {
        synchronized(lock) {
            stream?.flush()
            startMediaTimeNeedsInit = true
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
        val device = UsbAudioDevice.getInstance(appContext)
        usb = device
        val usbDevice = device.findUsbAudioDevice() ?: return failLocked("no USB audio device")
        if (!device.hasPermission(usbDevice)) return failLocked("USB permission missing")
        val info = device.openDevice(usbDevice) ?: return failLocked("openDevice failed")

        val depthHint = if (floatSource || sourceBits == 0) info.bestBitDepth else sourceBits
        val (alt, bits) = device.findAltSettingForBitDepth(depthHint)

        val reuse = stream
        if (reuse != null &&
            reuse.isAlive &&
            configuredRateHz == sampleRate &&
            active &&
            sourceEncoding == pcmEncoding
        ) {
            ensureVolumeObserverLocked()
            syncListeningGainLocked()
            return true
        }

        stopStreamLocked()
        if (!device.setAltSetting(0)) {
            Log.w(TAG, "alt 0 before SET_CUR failed; continuing")
        }
        device.setSampleRate(sampleRate)
        waitForClock(device)
        device.setAltSetting(0)
        if (!device.setAltSetting(alt)) {
            return failLocked("setAltSetting($alt) failed")
        }
        Thread.sleep(PLL_SETTLE_MS)

        val endpoints = device.endpointsForAlt(alt)
        val epOut = endpoints?.first ?: info.endpointOutAddress
        val epFb = (endpoints?.second ?: info.endpointFeedbackAddress).let { if (it < 0) 0 else it }
        val packet = endpoints?.third ?: info.maxPacketSize
        if (epOut < 0 || packet <= 0) return failLocked("no ISO OUT endpoint for alt $alt")

        val created = UsbAudioStream(
            info.fd,
            info.interfaceId,
            epOut,
            epFb,
            sampleRate,
            channelCount,
            bits,
            packet,
        )
        if (!created.isReady) {
            created.release()
            return failLocked("native UsbAudioStream create failed")
        }
        if (!created.start()) {
            created.release()
            return failLocked("UsbAudioStream start failed")
        }

        stream = created
        sourceEncoding = pcmEncoding
        useFloatWrite = floatSource
        configuredRateHz = sampleRate
        active = true
        startMediaTimeNeedsInit = true
        clockRechecked = false
        val reported = device.readSampleRate()
        clockMatched = reported == sampleRate
        Log.i(
            TAG,
            "exclusive USB started ${info.deviceName} ${sampleRate}Hz ${channelCount}ch " +
                "srcBits=$sourceBits dacBits=$bits alt=$alt GET_CUR=$reported clockMatched=$clockMatched",
        )

        ensureVolumeObserverLocked()
        syncListeningGainLocked()
        val volume = UacFeatureVolume(info.connection, info.controlInterfaceId)
        hardwareVolume = volume.attach()
        featureVolume = if (hardwareVolume) volume else null
        applyVolumeLocked()
        Log.i(
            TAG,
            "exclusive volume hardware=$hardwareVolume listening=$listeningGain softwareGain=$softwareGainValue",
        )
        return true
    }

    private fun waitForClock(device: UsbAudioDevice) {
        repeat(CLOCK_VALID_TRIES) {
            if (device.readClockValid()) return
            Thread.sleep(CLOCK_VALID_STEP_MS)
        }
    }

    private fun recheckClockLocked() {
        if (clockMatched || clockRechecked) return
        clockRechecked = true
        val reported = usb?.readSampleRate() ?: return
        if (reported == configuredRateHz) {
            clockMatched = true
            Log.i(TAG, "GET_CUR matched $reported Hz after first write")
        }
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

    private fun syncListeningGainLocked() {
        val manager = audioManager ?: return
        val max = runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        if (max <= 0) return
        val vol = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(max)
        listeningGain = vol.coerceIn(0, max).toFloat() / max
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
        val device = usb
        if (closeDevice && device != null) {
            runCatching { device.setAltSetting(0) }
            runCatching { device.closeDevice() }
        }
        usb = null
        featureVolume = null
        hardwareVolume = false
        clockMatched = false
        configuredRateHz = 0
        active = false
        softwareGainValue = 1f
        lastAppliedCombined = Float.NaN
        startMediaTimeNeedsInit = true
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
        const val PLL_SETTLE_MS = 50L
        const val CLOCK_VALID_TRIES = 20
        const val CLOCK_VALID_STEP_MS = 5L
        const val RAW_PCM16 = 2
        const val RAW_PCM24 = 0x15
        const val RAW_PCM32 = 0x16

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
