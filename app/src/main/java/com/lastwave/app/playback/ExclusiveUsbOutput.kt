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
import android.os.SystemClock
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
 * SoundDrop session around the decent-player usbdevfs driver (MIT).
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
    private val configLock = Any()
    private val stateLock = Any()

    @Volatile private var wanted = false
    @Volatile private var active = false
    @Volatile private var clockMatched = false
    @Volatile private var hardwareVolume = false
    @Volatile private var configuredRateHz = 0
    @Volatile private var listeningGain = 1f
    @Volatile private var softwareGainValue = 1f
    @Volatile private var lastHardwareRate = 0
    @Volatile private var isDsdStream = false

    private val usbAudio = UsbAudioDevice.getInstance(appContext)
    private var stream: UsbAudioStream? = null
    private val pcmLock = Object()
    private val pcmQueue = ArrayDeque<QueuedPcm>()
    // Arrays the writer has finished with (guarded by pcmLock). Media3 hands over
    // equal-sized chunks, so steady playback reuses these instead of allocating
    // a new array per chunk. The native write copies and releases them in-call.
    private val spareFloats = ArrayDeque<FloatArray>()
    private val spareBytes = ArrayDeque<ByteArray>()
    @Volatile private var queuedBytes = 0
    @Volatile private var flushRequested = false
    @Volatile private var writerStop = false
    private var writer: Thread? = null
    private var connection: UsbDeviceConnection? = null
    private var sourceEncoding = 0
    private var activeWireBits = 0
    private var activeChannels = 2
    /** Gain applied to the previous chunk; volume changes ramp from it. */
    @Volatile private var lastPcmGain = 1f
    private var useFloatWrite = false
    @Volatile private var startMediaTimeUs = 0L
    @Volatile private var startMediaTimeNeedsInit = true
    @Volatile private var mediaTimeBaseFrames = 0L
    @Volatile private var lastWriteElapsedMs = 0L
    @Volatile private var writingInProgress = false
    @Volatile private var paused = false
    private var pendingVolume = 1f
    private var featureVolume: UacFeatureVolume? = null
    private var clockRechecked = false
    private var volumeReceiverRegistered = false
    /** Last exclusive open failure, for the signal-path dialog. Cleared on
     *  the next successful start so a stale reason never shows. */
    @Volatile var lastFailureReason: String? = null
        private set
    @Volatile private var lastFailureDefinitive = false
    @Volatile private var lastClockWatchdogMs = 0L
    @Volatile private var lastAppliedListening = Float.NaN
    private var volumeProbed = false
    @Volatile private var lastNonMaxListeningGain = Float.NaN
    @Volatile private var ignoreStreamMusicMax = false
    /** Fork setting: never let the DAC's Feature Unit own the listening level. */
    @Volatile private var forceSoftwareVolume = false

    private val sessionBlockedDevices = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var currentDeviceKey: String? = null
    @Volatile private var sessionSummary: String = ""

    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val volumePrefs =
        appContext.getSharedPreferences("lastwave_exclusive_usb", Context.MODE_PRIVATE)

    init {
        clearBlockedDevices()
        lastNonMaxListeningGain = volumePrefs.getFloat(KEY_LAST_NON_MAX_GAIN, Float.NaN)
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VOLUME_CHANGED_ACTION) return
            val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC)
            if (streamType != AudioManager.STREAM_MUSIC) return
            val extraVol = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1).takeIf { it >= 0 }
            syncListeningGain(extraVol)
        }
    }

    fun setWanted(enabled: Boolean) {
        if (wanted == enabled) {
            if (enabled) {
                synchronized(stateLock) {
                    ensureVolumeObserverLocked()
                    syncListeningGainLocked()
                }
            }
            return
        }
        synchronized(configLock) {
            if (wanted == enabled) return
            wanted = enabled
            if (!enabled) {
                teardownLocked(closeDevice = true)
            } else {
                clearBlockedDevices()
                synchronized(stateLock) {
                    ensureVolumeObserverLocked()
                    syncListeningGainLocked()
                }
            }
        }
    }

    fun isWanted(): Boolean = wanted

    fun canOpen(): Boolean {
        val manager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val device = manager.deviceList.values.firstOrNull { dev ->
            (0 until dev.interfaceCount).any {
                dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }
        } ?: return false
        if (!manager.hasPermission(device)) return false
        return blockReasonFor(device.vendorId, device.productId) == null
    }

    fun wasLastFailureDefinitive(): Boolean = lastFailureDefinitive

    /**
     * Software (PCM-scaled) volume instead of the DAC's hardware Feature Unit.
     * Switching to software parks the hardware level at 0 dB first so the two
     * attenuations never stack. Switching back takes effect at the next start.
     */
    fun setForceSoftwareVolume(value: Boolean) {
        synchronized(stateLock) {
            if (forceSoftwareVolume == value) return
            forceSoftwareVolume = value
            if (value && active && hardwareVolume) {
                val fv = featureVolume
                hardwareVolume = false
                lastAppliedListening = Float.NaN
                applyVolumeLocked()
                runCatching { fv?.setNormalized(1f) }
                Log.i(TAG, "volume forced to software; Feature Unit parked at 0 dB")
            }
        }
    }

    fun isForceSoftwareVolume(): Boolean = forceSoftwareVolume

    /** True when the attached DAC exposes a usable hardware volume (even if not used). */
    fun hasHardwareVolumeControl(): Boolean = active && featureVolume != null && (hardwareVolume || featureVolumeAttached)

    @Volatile private var featureVolumeAttached = false

    fun isActive(): Boolean = active

    fun isClockMatched(): Boolean = active && clockMatched

    fun isClockFallbackActive(): Boolean = active && currentFallbackNegotiated

    fun usesHardwareVolume(): Boolean = active && hardwareVolume

    /**
     * PCM software gain for exclusive output: the Media3 volume, times the
     * curved STREAM_MUSIC level when no Feature Unit carries it.
     */
    fun softwareGain(): Float = if (isDsdStream) 1f else if (!active) 1f else softwareGainValue

    fun currentRateHz(): Int = if (active) configuredRateHz else 0

    fun activeWireBits(): Int = if (active) activeWireBits else 0

    fun lastHardwareRateHz(): Int = lastHardwareRate

    fun supportedHardwareRatesHz(): List<Int> = usbAudio.querySupportedSampleRates()

    @Volatile private var platformRatesHz: List<Int> = emptyList()

    /** Platform-reported USB DAC rates (`AudioDeviceInfo.sampleRates`), used when descriptor queries are empty. */
    fun setPlatformSupportedRatesHz(rates: List<Int>) {
        platformRatesHz = rates.filter { it > 0 }.distinct()
    }

    fun framesWritten(): Long = stream?.framesWritten ?: 0L

    fun isPaused(): Boolean = paused

    /**
     * Pause/resume ISO writes without tearing down the USB session or
     * discarding already-decoded PCM in [pcmQueue]. [flush] clears the queue
     * on explicit seek/stop.
     */
    fun setPaused(value: Boolean) {
        val wasPaused = paused
        paused = value
        if (value) {
            lastWriteElapsedMs = 0L
        } else if (wasPaused && active) {
            verifyClockWatchdog(force = true)
        }
        synchronized(pcmLock) {
            pcmLock.notifyAll()
        }
    }

    /** True while the isochronous stream is still accepting PCM. */
    fun isStreamAlive(): Boolean = active && stream?.isAlive == true

    /**
     * True while PCM is actively queued/writing to the DAC or a USB write
     * completed within the last 750 ms. Prevents the UI seekbar from advancing
     * on wall-clock time when the USB pipeline is starved.
     */
    fun isStreamingAudio(): Boolean {
        if (!active || paused || stream?.isAlive != true) return false
        if (writingInProgress || queuedBytes > 0) return true
        val last = lastWriteElapsedMs
        return last > 0L && (SystemClock.elapsedRealtime() - last) in 0L..750L
    }

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
        synchronized(pcmLock) {
            mediaTimeBaseFrames = stream?.framesWritten ?: 0L
            startMediaTimeNeedsInit = true
            lastWriteElapsedMs = 0L
        }
    }

    fun syncListeningGain(explicitVol: Int? = null) {
        val stream = readStreamMusicGain(explicitVol) ?: return
        // While the attach-time 100% pin is held off with the remembered
        // level, keep ignoring pinned max readings. The next real sub-max
        // change (a volume key press) ends the hold and applies normally.
        if (ignoreStreamMusicMax && stream >= ATTACH_MAX_PIN_GAIN) return
        if (stream < ATTACH_MAX_PIN_GAIN) ignoreStreamMusicMax = false
        // Only a level chosen while listening through the DAC is its listening
        // level. Remembering every read stored the phone-speaker volume taken
        // at app start, and the next attach opened the DAC at ~-53 dB.
        if (active) rememberStreamGain(stream)
        val next = stream
        if (lastAppliedListening.isFinite() &&
            kotlin.math.abs(next - listeningGain) < 1e-4f
        ) {
            listeningGain = next
            return
        }
        synchronized(stateLock) {
            listeningGain = next
            applyVolumeLocked()
        }
    }

    /**
     * Opens the exclusive stream. [rateOverrideHz] carries a same-family
     * fallback rate (e.g. 44.1 kHz for an 88.2 kHz source) when the DAC
     * descriptor lacks the source rate: the stream opens at the supported
     * rate and the caller feeds already-converted PCM. Null keeps the
     * native source-rate behavior. The clock check below compares against
     * the actually-requested rate either way.
     */
    fun configure(
        format: Format,
        rateOverrideHz: Int? = null,
        bitDepthHint: Int? = null,
        isDsd: Boolean = false,
    ): Boolean {
        if (!wanted) return false
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW ||
            format.sampleRate <= 0 ||
            format.channelCount !in 1..MAX_CHANNELS
        ) {
            lastFailureDefinitive = true
            return false
        }
        val targetRate = rateOverrideHz?.takeIf { it > 0 } ?: format.sampleRate
        val floatSource = format.pcmEncoding == C.ENCODING_PCM_FLOAT || rateOverrideHz != null
        val sourceBits = sourceBitDepth(format.pcmEncoding)
        if (!floatSource && sourceBits == 0) return false
        val resolvedBits = when {
            targetRate > 192_000 -> 32
            targetRate > 48_000 -> 24
            bitDepthHint != null && bitDepthHint > 0 -> bitDepthHint
            sourceBits > 0 -> sourceBits
            rateOverrideHz != null -> 24
            else -> 16
        }
        synchronized(configLock) {
            if (!wanted) return false
            return runCatching {
                // Converted fallback PCM always arrives as Float32 from the
                // app resampler (same rule as float decoder output: 24-bit
                // alt). Native sources keep their own packing.
                configureLocked(
                    targetRate,
                    format.channelCount,
                    resolvedBits,
                    floatSource,
                    if (rateOverrideHz != null) C.ENCODING_PCM_FLOAT else format.pcmEncoding,
                    rateOverrideHz,
                    isDsd,
                )
            }.onFailure { error ->
                Log.w(TAG, "Exclusive USB configure failed", error)
                teardownLocked(closeDevice = true)
            }.getOrDefault(false)
        }
    }

    fun write(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        isFloatBuffer: Boolean = useFloatWrite,
    ): Boolean {
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
        // Queued unscaled: gain is applied by the writer as each chunk leaves
        // the queue, so a volume change is heard now, not ~3 s of queue later.
        val chunk = if (isFloatBuffer) {
            val count = size / Float.SIZE_BYTES
            val floats = synchronized(pcmLock) { spareFloats.removeFirstOrNull() }
                ?.takeIf { it.size == count } ?: FloatArray(count)
            buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
            buffer.position(buffer.limit())
            QueuedPcm(floats, null, 0, size)
        } else {
            val encoding = writeRawEncoding(sourceEncoding)
            if (encoding < 0) return false
            val bytes = synchronized(pcmLock) { spareBytes.removeFirstOrNull() }
                ?.takeIf { it.size == size } ?: ByteArray(size)
            buffer.get(bytes)
            QueuedPcm(null, bytes, encoding, size)
        }
        synchronized(pcmLock) {
            if (writerStop) return false
            pcmQueue.addLast(chunk)
            queuedBytes += size
            pcmLock.notifyAll()
        }
        return true
    }

    fun getCurrentPositionUs(): Long {
        val running = stream ?: return androidx.media3.exoplayer.audio.AudioSink.CURRENT_POSITION_NOT_SET
        if (!active || configuredRateHz <= 0 || startMediaTimeNeedsInit) {
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
        active && (queuedBytes > 0 || (!paused && stream?.isAlive == true))

    fun flush() {
        synchronized(pcmLock) {
            pcmQueue.clear()
            queuedBytes = 0
            flushRequested = true
            lastWriteElapsedMs = 0L
            mediaTimeBaseFrames = stream?.framesWritten ?: 0L
            startMediaTimeNeedsInit = true
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
        if (!active) return false
        if (running.isAlive) return false
        if (!running.start()) return false
        synchronized(stateLock) {
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
        currentFallbackNegotiated = false
        mediaTimeBaseFrames = stream?.framesWritten ?: 0L
    }

    fun reset() {
        synchronized(configLock) {
            teardownLocked(closeDevice = true)
        }
    }

    fun release() {
        synchronized(configLock) {
            wanted = false
            teardownLocked(closeDevice = true)
        }
    }

    fun setVolume(volume: Float) {
        val normalized = volume.coerceIn(0f, 1f)
        synchronized(stateLock) {
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
        rateOverrideHz: Int? = null,
        isDsd: Boolean = false,
    ): Boolean {
        val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val usbDevice = manager.deviceList.values.firstOrNull { dev ->
            (0 until dev.interfaceCount).any {
                dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }
        } ?: return failLocked("no USB audio device")
        if (!manager.hasPermission(usbDevice)) return failLocked("USB permission missing")
        blockReasonFor(usbDevice.vendorId, usbDevice.productId)?.let { reason ->
            return failLocked(
                "disabled for this DAC after a failed test ($reason) — turn USB Exclusive off and on to retry",
                definitive = true,
            )
        }
        val deviceKey = "${usbDevice.vendorId}:${usbDevice.productId}"

        val bits = if (sourceBits > 0) sourceBits else (if (floatSource) 24 else 16)
        val reuse = stream
        if (reuse != null &&
            reuse.isAlive &&
            currentDeviceKey == deviceKey &&
            configuredRateHz == sampleRate &&
            activeChannels == channelCount &&
            active &&
            sourceEncoding == pcmEncoding &&
            useFloatWrite == floatSource
        ) {
            val reported = runCatching { usbAudio.readSampleRate() }.getOrDefault(-1)
            if (reported <= 0 || reported == sampleRate) {
                if (reported > 0) {
                    lastHardwareRate = reported
                    clockMatched = rateOverrideHz == null
                }
                currentFallbackNegotiated = rateOverrideHz != null
                isDsdStream = isDsd
                lastClockWatchdogMs = SystemClock.elapsedRealtime()
                synchronized(stateLock) {
                    paused = false
                    startMediaTimeNeedsInit = true
                    mediaTimeBaseFrames = reuse.framesWritten
                    ensureVolumeObserverLocked()
                    syncListeningGainLocked()
                }
                return true
            }
            Log.w(TAG, "reuse rejected: DAC clock is ${reported}Hz, track needs ${sampleRate}Hz")
        }

        stopStreamLocked()
        usbAudio.closeDevice()
        connection = null
        currentDeviceKey = deviceKey
        val info = usbAudio.openDevice(usbDevice)
            ?: return failLocked("openDevice failed" + (usbAudio.lastOpenError?.let { ": $it" } ?: ""))
        val hwRates = if (isDsd) {
            usbAudio.queryRawSupportedSampleRates()
        } else {
            usbAudio.querySupportedSampleRates()
        }
        val knownRates = hwRates.ifEmpty { platformRatesHz }

        var effectiveRate = sampleRate
        var effectiveBits = bits
        var effectiveFloatSource = floatSource
        var effectiveEncoding = pcmEncoding
        var autoNegotiatedFallback = rateOverrideHz != null

        // DoP (DSD over PCM) streams MUST NOT be sample-rate converted: resampling corrupts the
        // 0x05/0xFA marker bytes and 1-bit DSD payload.
        if (isDsd && knownRates.isNotEmpty() && effectiveRate !in knownRates) {
            return failLocked(
                "DAC hardware descriptor lacks ${effectiveRate}Hz for DoP (supports $knownRates)",
                definitive = true,
            )
        }

        // If the USB Clock Source / Format descriptors (or platform AudioDeviceInfo
        // fallback) report supported rates and the requested rate (e.g. 88.2 / 176.4 /
        // 192 / 384 kHz on a 96 kHz-max DAC) is missing, switch before opening the stream.
        if (!isDsd && rateOverrideHz == null && knownRates.isNotEmpty() && effectiveRate !in knownRates) {
            val fallback = selectExclusiveRateFallback(effectiveRate, knownRates)
            if (fallback != null && fallback != effectiveRate) {
                Log.i(
                    TAG,
                    "DAC hardware descriptor lacks ${effectiveRate}Hz (supports $knownRates); " +
                        "switching exclusive clock to ${fallback}Hz for soxr resampling",
                )
                effectiveRate = fallback
                effectiveBits = 24
                effectiveFloatSource = true
                effectiveEncoding = C.ENCODING_PCM_FLOAT
                autoNegotiatedFallback = true
            }
        }

        // 16-bit sources ride a 24-bit (or wider) alt when the DAC has one.
        // Padding is lossless, so bit-perfect playback at full volume is
        // unchanged, but software volume (and any other gain stage) is then
        // applied with 8 extra bits instead of re-quantizing to 16 bits,
        // which raised the noise floor into audible hiss on sensitive IEMs.
        val requestBits = if (effectiveBits in 1..16) 24 else effectiveBits
        var (alt, wireBits) = usbAudio.findAltSetting(requestBits, effectiveRate, channelCount)
        var rateSetBefore = usbAudio.setSampleRate(effectiveRate)
        if (!usbAudio.setAltSetting(alt)) {
            val fallbackAlt = usbAudio.findAltSetting(24, effectiveRate, channelCount)
            if (fallbackAlt.first != alt && usbAudio.setAltSetting(fallbackAlt.first)) {
                alt = fallbackAlt.first
                wireBits = fallbackAlt.second
            } else {
                return failLocked("setAltSetting $alt failed", definitive = true)
            }
        }
        var reported = usbAudio.readSampleRate()
        if (!rateSetBefore || (reported > 0 && reported != effectiveRate)) {
            rateSetBefore = usbAudio.setSampleRate(effectiveRate)
            reported = usbAudio.readSampleRate()
        }
        var settleTries = 0
        while (reported > 0 && reported != effectiveRate && settleTries < CLOCK_SETTLE_TRIES) {
            Thread.sleep(CLOCK_SETTLE_STEP_MS)
            reported = usbAudio.readSampleRate()
            settleTries++
        }

        // If the DAC's internal clock still rejected or clamped the requested rate
        // (e.g. reported 44.1 / 48 / 96 kHz when asked for 88.2 / 176.4 / 192 kHz),
        // fall back to a rate the DAC's internal clock actually locks to.
        if (reported > 0 && reported != effectiveRate) {
            if (isDsd) {
                return failLocked(
                    "DAC internal clock rejected DoP ${effectiveRate}Hz (reported ${reported}Hz)",
                    definitive = true,
                )
            }
            val candidates = (knownRates + reported).distinct()
            val candidateRate = selectExclusiveRateFallback(sampleRate, candidates) ?: reported
            Log.w(
                TAG,
                "DAC internal clock reported ${reported}Hz instead of ${effectiveRate}Hz; " +
                    "negotiating fallback clock ${candidateRate}Hz",
            )
            val candidateSet = if (candidateRate != reported) {
                usbAudio.setSampleRate(candidateRate)
            } else {
                true
            }
            val afterFallback = usbAudio.readSampleRate().takeIf { it > 0 } ?: candidateRate
            effectiveRate = if (candidateSet && afterFallback == candidateRate) {
                candidateRate
            } else {
                afterFallback
            }
            effectiveBits = 24
            effectiveFloatSource = true
            effectiveEncoding = C.ENCODING_PCM_FLOAT
            autoNegotiatedFallback = true
            val updatedAlt = usbAudio.findAltSetting(effectiveBits, effectiveRate, channelCount)
            if (updatedAlt.first != alt) {
                if (usbAudio.setAltSetting(updatedAlt.first)) {
                    alt = updatedAlt.first
                    wireBits = updatedAlt.second
                }
            }
            reported = usbAudio.readSampleRate()
        } else if (!isDsd && !rateSetBefore && reported <= 0 && effectiveRate > 96_000) {
            // DAC rejected SET_CUR and does not answer GET_CUR: step down through
            // lower playable rates so a 96 kHz-max DAC never gets an unclocked 192/384k stream.
            val lowerPool = (knownRates + listOf(96_000, 48_000, 44_100))
                .filter { it in 1 until effectiveRate }
                .distinct()
            var remaining = lowerPool
            while (remaining.isNotEmpty()) {
                val candidate = selectExclusiveRateFallback(effectiveRate, remaining) ?: break
                if (usbAudio.setSampleRate(candidate)) {
                    Log.w(
                        TAG,
                        "DAC rejected ${effectiveRate}Hz without GET_CUR; stepped clock down to ${candidate}Hz",
                    )
                    effectiveRate = candidate
                    effectiveBits = 24
                    effectiveFloatSource = true
                    effectiveEncoding = C.ENCODING_PCM_FLOAT
                    autoNegotiatedFallback = true
                    rateSetBefore = true
                    val updatedAlt = usbAudio.findAltSetting(effectiveBits, effectiveRate, channelCount)
                    if (updatedAlt.first != alt && usbAudio.setAltSetting(updatedAlt.first)) {
                        alt = updatedAlt.first
                        wireBits = updatedAlt.second
                    }
                    break
                }
                remaining = remaining.filter { it != candidate }
            }
        }

        val usbSpeed = UsbAudioStream.nativeUsbSpeed(info.fd)
        var selectedOut = findIsoOutEndpoint(usbDevice, info.interfaceId, alt)
            ?: return failLocked("alt $alt has no isochronous OUT endpoint", definitive = true)
        var maxPacket = isoPacketBytes(selectedOut.maxPacketSize)
        var dataInterval = selectedOut.interval.coerceAtLeast(1)

        // Never start a stream whose endpoint wMaxPacketSize is smaller than one
        // nominal packet at (effectiveRate, wireBits): native submitPcmToUrbs
        // stops immediately (mute) when b > maxPacketSize.
        if (maxPacket > 0 && requiredIsoPacketBytes(effectiveRate, channelCount, wireBits, dataInterval, usbSpeed) > maxPacket) {
            val narrowerAlt = usbAudio.findAltSetting(16, effectiveRate, channelCount)
            val narrowerOut = if (narrowerAlt.first != alt) {
                findIsoOutEndpoint(usbDevice, info.interfaceId, narrowerAlt.first)
            } else {
                null
            }
            val narrowerMax = narrowerOut?.let { isoPacketBytes(it.maxPacketSize) } ?: 0
            val narrowerInterval = narrowerOut?.interval?.coerceAtLeast(1) ?: dataInterval
            if (narrowerOut != null &&
                (narrowerMax == 0 || requiredIsoPacketBytes(effectiveRate, channelCount, narrowerAlt.second, narrowerInterval, usbSpeed) <= narrowerMax) &&
                usbAudio.setAltSetting(narrowerAlt.first)
            ) {
                alt = narrowerAlt.first
                wireBits = narrowerAlt.second
                selectedOut = narrowerOut
                maxPacket = narrowerMax
                dataInterval = narrowerInterval
            } else {
                var ratePool = (knownRates + listOf(96_000, 48_000, 44_100))
                    .filter { it in 1 until effectiveRate }
                    .distinct()
                while (ratePool.isNotEmpty()) {
                    val candidate = selectExclusiveRateFallback(effectiveRate, ratePool) ?: break
                    val candAlt = usbAudio.findAltSetting(24, candidate, channelCount)
                    val candOut = findIsoOutEndpoint(usbDevice, info.interfaceId, candAlt.first)
                    val candMax = candOut?.let { isoPacketBytes(it.maxPacketSize) } ?: 0
                    val candInterval = candOut?.interval?.coerceAtLeast(1) ?: 1
                    val fits24 = candOut != null &&
                        (candMax == 0 || requiredIsoPacketBytes(candidate, channelCount, candAlt.second, candInterval, usbSpeed) <= candMax)
                    val alt16 = if (!fits24) usbAudio.findAltSetting(16, candidate, channelCount) else candAlt
                    val out16 = if (!fits24) findIsoOutEndpoint(usbDevice, info.interfaceId, alt16.first) else candOut
                    val max16 = out16?.let { isoPacketBytes(it.maxPacketSize) } ?: 0
                    val int16 = out16?.interval?.coerceAtLeast(1) ?: 1
                    val fits16 = !fits24 && out16 != null &&
                        (max16 == 0 || requiredIsoPacketBytes(candidate, channelCount, alt16.second, int16, usbSpeed) <= max16)
                    val pickedAlt = if (fits24) candAlt else alt16
                    val pickedOut = if (fits24) candOut else out16
                    val pickedMax = if (fits24) candMax else max16
                    val pickedInt = if (fits24) candInterval else int16
                    if ((fits24 || fits16) && pickedOut != null) {
                        rateSetBefore = usbAudio.setSampleRate(candidate)
                        if (usbAudio.setAltSetting(pickedAlt.first)) {
                            reported = usbAudio.readSampleRate()
                            Log.w(
                                TAG,
                                "Endpoint maxPacket=$maxPacket too small for ${effectiveRate}Hz/${wireBits}bit; " +
                                    "stepping down to ${candidate}Hz/${pickedAlt.second}bit",
                            )
                            effectiveRate = candidate
                            effectiveBits = 24
                            effectiveFloatSource = true
                            effectiveEncoding = C.ENCODING_PCM_FLOAT
                            autoNegotiatedFallback = true
                            alt = pickedAlt.first
                            wireBits = pickedAlt.second
                            selectedOut = pickedOut
                            maxPacket = pickedMax
                            dataInterval = pickedInt
                            break
                        }
                    }
                    ratePool = ratePool.filter { it != candidate }
                }
            }
        }
        val selected = (0 until usbDevice.interfaceCount)
            .map { usbDevice.getInterface(it) }
            .firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    it.interfaceSubclass == 2 &&
                    it.id == info.interfaceId &&
                    it.alternateSetting == alt &&
                    it.endpointCount > 0
            }
        val selectedFeedback = selected?.let { iface ->
            (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                        it.direction == UsbConstants.USB_DIR_IN
                }
        }
        val feedbackPacketSize = selectedFeedback?.maxPacketSize?.let { isoPacketBytes(it) }
            ?: info.feedbackPacketSize
        val feedbackInterval = selectedFeedback?.interval?.coerceAtLeast(1) ?: dataInterval
        val pollFeedback = selectedFeedback != null &&
            UsbAudioDevice.needsContinuousFeedback(usbDevice.vendorId, usbDevice.productId)
        if (pollFeedback) waitForClockValid()
        val created = UsbAudioStream(
            info.fd,
            info.interfaceId,
            selectedOut.address,
            selectedFeedback?.address ?: 0,
            effectiveRate,
            channelCount,
            wireBits,
            maxPacket,
            dataInterval,
            feedbackPacketSize,
            feedbackInterval,
            pollFeedback,
        )
        if (!created.isReady || !created.start()) {
            created.release()
            return failLocked("decent USB start failed")
        }
        connection = info.connection
        stream = created
        sourceEncoding = effectiveEncoding
        activeWireBits = wireBits
        activeChannels = channelCount.coerceAtLeast(1)
        lastPcmGain = softwareGain()
        useFloatWrite = effectiveFloatSource
        configuredRateHz = effectiveRate
        currentFallbackNegotiated = autoNegotiatedFallback
        isDsdStream = isDsd
        active = true
        lastFailureReason = null
        lastFailureDefinitive = false
        lastClockWatchdogMs = SystemClock.elapsedRealtime()
        paused = false
        startMediaTimeNeedsInit = true
        startMediaTimeUs = 0L
        mediaTimeBaseFrames = 0L
        if (reported > 0) {
            lastHardwareRate = reported
            clockMatched = reported == effectiveRate && !autoNegotiatedFallback
        } else {
            clockMatched = rateSetBefore && usbAudio.lastRateWriteTrusted && !autoNegotiatedFallback
        }
        clockRechecked = true
        val controlId = (0 until usbDevice.interfaceCount)
            .map { usbDevice.getInterface(it) }
            .firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 1
            }?.id ?: 0
        val volumeControl = UacFeatureVolume(info.connection, controlId)
        if (!forceSoftwareVolume) {
            featureVolume = volumeControl
            val attached = volumeControl.attach()
            featureVolumeAttached = attached
            hardwareVolume = attached
            if (!attached) {
                runCatching { volumeControl.parkUnityAndUnmute() }
            }
        } else {
            runCatching { volumeControl.parkUnityAndUnmute() }
            featureVolume = null
            featureVolumeAttached = false
            hardwareVolume = false
        }
        Log.i(
            TAG,
            "decent USB started ${usbDevice.productName} ${sampleRate}Hz " +
                "alt=$alt wireBits=$wireBits reported=$reported clockMatched=$clockMatched " +
                "hardwareVolume=$hardwareVolume",
        )

        synchronized(stateLock) {
            ensureVolumeObserverLocked()
            listeningGain = listeningGainForDac()
            lastAppliedListening = Float.NaN
            applyVolumeLocked()
        }
        Log.i(
            TAG,
            "exclusive volume hardware=$hardwareVolume listening=$listeningGain softwareGain=$softwareGainValue",
        )
        sessionSummary = "started rate=${effectiveRate}Hz alt=$alt wireBits=$wireBits reported=$reported " +
            "clockMatched=$clockMatched fallback=$autoNegotiatedFallback hardwareVolume=$hardwareVolume " +
            "maxPacket=$maxPacket interval=$dataInterval feedback=${selectedFeedback != null} feedbackPolled=$pollFeedback"
        startWriterLocked(created)
        return true
    }

    /** Bounded: a DAC that never reports a valid clock still gets the stream. */
    private fun waitForClockValid() {
        repeat(CLOCK_VALID_TRIES) {
            if (usbAudio.readClockValid()) return
            Thread.sleep(CLOCK_VALID_STEP_MS)
        }
        Log.w(TAG, "DAC clock not reported valid; starting stream anyway")
    }

    // ── Fork: safety guard + diagnostics ──

    private fun blockReasonFor(vendorId: Int, productId: Int): String? =
        sessionBlockedDevices[vendorId.toString() + ":" + productId]

    /** Forget every DAC the guard disabled. */
    fun clearBlockedDevices() {
        sessionBlockedDevices.clear()
        val prefs = volumePrefs ?: return
        val editor = prefs.edit()
        prefs.all?.keys?.filter { it.startsWith(KEY_BLOCK_PREFIX) }?.forEach { editor.remove(it) }
        editor.apply()
    }

    /**
     * The DAC is not consuming the stream at the rate it was configured for
     * and its clock was never confirmed: what it plays is garbage (loud noise
     * on a UAC1 dongle in the field). Stop now, remember the DAC for this session so the next
     * track does not blast it again, and let the sink fall back.
     */
    fun abortUnhealthy(reason: String) {
        synchronized(configLock) {
            if (!active) return
            val key = currentDeviceKey
            Log.w(TAG, "Exclusive USB aborted: $reason (device $key)")
            if (key != null) {
                sessionBlockedDevices[key] = reason
            }
            failLocked("stopped for safety: $reason")
        }
    }

    /** Text for the signal-path "copy USB diagnostic" button. */
    fun diagnosticReport(): String = buildString {
        append("SoundDrop USB diagnostic\n")
        append("active=$active wanted=$wanted rate=$configuredRateHz clockMatched=$clockMatched ")
        append("hardwareVolume=$hardwareVolume forceSoftwareVolume=$forceSoftwareVolume\n")
        if (sessionSummary.isNotEmpty()) append(sessionSummary).append('\n')
        lastFailureReason?.let { append("lastFailure=").append(it).append('\n') }
        append(runCatching { usbAudio.diagnosticReport() }.getOrDefault(""))
    }

    @Volatile private var currentFallbackNegotiated = false

    private fun recheckClockLocked() {
        if (clockRechecked) return
        val reported = usbAudio.readSampleRate()
        if (reported > 0) {
            lastHardwareRate = reported
            clockMatched = reported == configuredRateHz && !currentFallbackNegotiated
        }
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

    @Volatile private var cachedMaxVolume = -1
    @Volatile private var lastKnownStreamGain: Float = 1f
    @Volatile private var lastStreamGainReadMs = 0L

    private fun readStreamMusicGain(explicitVol: Int? = null): Float? {
        val manager = audioManager ?: return null
        val max = if (cachedMaxVolume > 0) cachedMaxVolume else {
            runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0).also {
                if (it > 0) cachedMaxVolume = it
            }
        }
        if (max <= 0) return null
        val now = SystemClock.elapsedRealtime()
        val vol = explicitVol ?: if (now - lastStreamGainReadMs < 1500L) {
            // Round, not truncate: 0.95999f * 25 must stay step 24, not drift to 23.
            kotlin.math.round(lastKnownStreamGain * max).toInt()
        } else {
            lastStreamGainReadMs = now
            runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(max)
        }
        val gain = vol.coerceIn(0, max).toFloat() / max
        lastKnownStreamGain = gain
        return gain
    }

    private fun rememberStreamGain(gain: Float?) {
        if (gain != null && gain in MIN_REMEMBERED_GAIN..ATTACH_MAX_PIN_GAIN) {
            lastNonMaxListeningGain = gain
            runCatching { volumePrefs.edit().putFloat(KEY_LAST_NON_MAX_GAIN, gain).apply() }
        }
    }

    private fun listeningGainForDac(): Float {
        val stream = readStreamMusicGain() ?: return listeningGain
        attachMaxPinFallbackGain(stream, lastNonMaxListeningGain)?.let { remembered ->
            ignoreStreamMusicMax = true
            Log.i(
                TAG,
                "STREAM_MUSIC pinned at max on attach; using remembered listening gain $remembered",
            )
            return remembered
        }
        ignoreStreamMusicMax = false
        return stream
    }

    private fun syncListeningGainLocked() {
        listeningGain = listeningGainForDac()
        applyVolumeLocked()
    }

    /**
     * Only the system listening level goes through the perceptual dB curve
     * (on the Feature Unit when there is one). Media3 volume — app slider,
     * ducks, fades — is a linear amplitude, as on AudioTrack, applied to PCM
     * on top. Curving the product made two 50% sliders -40 dB instead of -31.
     */
    private fun applyVolumeLocked() {
        val listening = listeningGain.coerceIn(0f, 1f)
        val unchanged = lastAppliedListening.isFinite() && kotlin.math.abs(listening - lastAppliedListening) < 1e-4f
        lastAppliedListening = listening
        if (hardwareVolume && !unchanged && featureVolume?.setNormalized(listening) != true) {
            hardwareVolume = false
            Log.w(TAG, "Feature Unit SET_CUR failed; falling back to perceptual software gain")
        }
        val app = pendingVolume.coerceIn(0f, 1f)
        softwareGainValue = if (hardwareVolume) app else UacFeatureVolume.perceptualLinearGain(listening) * app
    }

    private fun startWriterLocked(target: UsbAudioStream) {
        stopWriterLocked()
        writerStop = false
        flushRequested = false
        val thread = Thread({ writeLoop(target) }, "ExclusiveUsbWriter")
        writer = thread
        thread.start()
    }

    /**
     * Signal the writer thread to exit, drop queued PCM, and wait for it to
     * finish. The join is unbounded on purpose: a timeout here is what let the
     * old code release() the native context while the writer was still inside
     * write() — a use-after-free. Callers MUST stop the native stream first
     * (see [stopStreamLocked]) so a write() blocked on a full URB ring returns
     * and this join is guaranteed to terminate.
     */
    private fun stopWriterLocked() {
        writerStop = true
        synchronized(pcmLock) {
            pcmQueue.clear()
            queuedBytes = 0
            pcmLock.notifyAll()
        }
        writer?.join()
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
                    return@synchronized FLUSH_MARKER
                }
                if (paused || pcmQueue.isEmpty()) return@synchronized IDLE_MARKER
                val chunk = pcmQueue.removeFirst()
                queuedBytes = (queuedBytes - chunk.byteSize).coerceAtLeast(0)
                chunk
            } ?: break
            if (next === FLUSH_MARKER) {
                runCatching { target.flush() }
                mediaTimeBaseFrames = target.framesWritten
                continue
            }
            if (next === IDLE_MARKER) {
                runCatching { target.pump() }
                continue
            }
            if (!target.isAlive) break
            if (!verifyClockWatchdog(force = false)) break
            writingInProgress = true
            lastWriteElapsedMs = SystemClock.elapsedRealtime()
            try {
                writeScaled(target, next)
            } finally {
                writingInProgress = false
                lastWriteElapsedMs = SystemClock.elapsedRealtime()
            }
            synchronized(pcmLock) {
                next.floats?.let { if (spareFloats.size < MAX_SPARE_CHUNKS) spareFloats.addLast(it) }
                next.raw?.let { if (spareBytes.size < MAX_SPARE_CHUNKS) spareBytes.addLast(it) }
            }
        }
    }

    /** Writer thread only: applies the current software gain, then hands the chunk to USB. */
    private fun writeScaled(target: UsbAudioStream, chunk: QueuedPcm) {
        val rawBytes = chunk.raw
        if (isDsdStream && rawBytes != null) {
            target.writeRaw(rawBytes, chunk.encoding)
            return
        }
        val gain = softwareGain()
        val fromGain = lastPcmGain
        lastPcmGain = gain
        val channels = activeChannels
        // ~10ms glide on volume changes (removes zipper noise); a steady
        // gain takes exactly the previous constant-gain path.
        val rampFrames = (configuredRateHz / 100).coerceAtLeast(1)
        chunk.floats?.let { floats ->
            ExclusivePcmGain.applyRamped(floats, fromGain, gain, channels, rampFrames)
            target.write(floats)
            return
        }
        val bytes = rawBytes ?: return
        val attenuate = gain >= 0f && (gain < 1f - 1e-6f || fromGain < 1f - 1e-6f)
        if (attenuate && chunk.encoding == RAW_PCM16 && activeWireBits > 16) {
            // Scale into 24 bits instead of back into 16: same level, no
            // added quantization noise. The native layer passes 24-bit
            // through (or pads it to a 32-bit alt) losslessly.
            target.writeRaw(scalePcm16To24(bytes, fromGain, gain, channels, rampFrames), RAW_PCM24)
            return
        }
        if (attenuate) scalePcmRamped(bytes, chunk.encoding, fromGain, gain, channels, rampFrames)
        target.writeRaw(bytes, chunk.encoding)
    }

    private fun stopStreamLocked() {
        val running = stream
        // Stop the native stream FIRST. This flips running=false so a write()
        // blocked on a full URB ring returns (the native reap loop wakes roughly
        // every 200ms and breaks). Only then can stopWriterLocked()'s unbounded
        // join() terminate — joining before stop() would always time out and
        // leave the writer alive.
        runCatching { running?.stop() }
        stopWriterLocked()
        // The writer thread is now guaranteed dead, so releasing native state is
        // safe. Releasing it while the writer was still alive was a use-after-free.
        if (running != null) {
            runCatching { running.drainUrbs() }
            runCatching { running.release() }
        }
        stream = null
        isDsdStream = false
        synchronized(stateLock) {
            active = false
        }
    }

    private fun teardownLocked(closeDevice: Boolean) {
        stopStreamLocked()
        if (closeDevice) {
            runCatching { featureVolume?.setNormalized(1.0f) }
            runCatching { usbAudio.closeDevice() }
            connection = null
        }
        synchronized(stateLock) {
            featureVolume = null
            featureVolumeAttached = false
            volumeProbed = false
            ignoreStreamMusicMax = false
            hardwareVolume = false
            clockMatched = false
            currentFallbackNegotiated = false
            configuredRateHz = 0
            active = false
            softwareGainValue = 1f
            lastAppliedListening = Float.NaN
            startMediaTimeNeedsInit = true
            startMediaTimeUs = 0L
            mediaTimeBaseFrames = 0L
            paused = false
            if (closeDevice) unregisterVolumeObserverLocked()
        }
    }

    private class QueuedPcm(
        val floats: FloatArray?,
        val raw: ByteArray?,
        val encoding: Int,
        val byteSize: Int,
    )

    private fun verifyClockWatchdog(force: Boolean): Boolean {
        val expected = configuredRateHz
        if (!active || expected <= 0) return true
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastClockWatchdogMs < CLOCK_WATCHDOG_MS) return true
        lastClockWatchdogMs = now
        val reported = runCatching { usbAudio.readSampleRate() }.getOrDefault(-1)
        if (reported > 0 && reported != expected) {
            Log.w(TAG, "DAC clock drifted: expected=${expected}Hz reported=${reported}Hz; stopping exclusive stream")
            lastFailureReason = "DAC clock drifted to ${reported}Hz"
            lastFailureDefinitive = true
            clockMatched = false
            active = false
            writerStop = true
            runCatching { stream?.stop() }
            return false
        }
        return true
    }

    private fun failLocked(reason: String, definitive: Boolean = false): Boolean {
        Log.w(TAG, "Exclusive USB fail-open: $reason")
        lastFailureReason = reason
        lastFailureDefinitive = definitive
        teardownLocked(closeDevice = true)
        return false
    }

    internal companion object {
        const val TAG = "ExclusiveUsb"
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val KEY_LAST_NON_MAX_GAIN = "last_non_max_stream_gain"
        const val KEY_BLOCK_PREFIX = "blocked_dac_"
        const val PLL_SETTLE_MS = 50L
        const val CLOCK_VALID_TRIES = 40
        const val CLOCK_VALID_STEP_MS = 5L
        const val CLOCK_WATCHDOG_MS = 5_000L
        const val CLOCK_SETTLE_TRIES = 10
        const val CLOCK_SETTLE_STEP_MS = 20L
        const val RAW_PCM16 = 2
        const val RAW_PCM24 = 0x15
        const val RAW_PCM32 = 0x16
        const val MAX_QUEUED_BYTES = 512 * 1024
        const val MAX_CHANNELS = 8
        const val MAX_SPARE_CHUNKS = 4
        private val FLUSH_MARKER = QueuedPcm(null, null, -1, 0)
        private val IDLE_MARKER = QueuedPcm(null, null, -2, 0)

        fun isoPacketBytes(raw: Int): Int {
            if (raw <= 0) return 0
            val extra = (raw shr 11) and 0x3
            val size = raw and 0x7FF
            return if (extra > 0) size * (1 + extra) else raw
        }

        fun findIsoOutEndpoint(usbDevice: android.hardware.usb.UsbDevice, interfaceId: Int, alt: Int): android.hardware.usb.UsbEndpoint? {
            val selected = (0 until usbDevice.interfaceCount)
                .map { usbDevice.getInterface(it) }
                .firstOrNull {
                    it.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                        it.interfaceSubclass == 2 &&
                        it.id == interfaceId &&
                        it.alternateSetting == alt &&
                        it.endpointCount > 0
                } ?: return null
            return (0 until selected.endpointCount)
                .map { selected.getEndpoint(it) }
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                        it.direction == UsbConstants.USB_DIR_OUT
                }
        }

        internal fun requiredIsoPacketBytes(
            rateHz: Int,
            channels: Int,
            bitDepth: Int,
            interval: Int = 1,
            usbSpeed: Int = 3,
        ): Int {
            val bpf = ((bitDepth + 7) / 8).coerceAtLeast(1) * channels.coerceIn(1, 8)
            val safeInterval = interval.coerceAtLeast(1)
            val nominalPerPacket = if (usbSpeed == 2) {
                rateHz.toDouble() * safeInterval / 1000.0
            } else {
                val microframes = 1 shl (safeInterval - 1).coerceAtMost(15)
                rateHz.toDouble() * microframes / 8000.0
            }
            val frames = kotlin.math.ceil(nominalPerPacket).toInt().coerceAtLeast(1)
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

        /**
         * 16-bit LE PCM × gain -> packed 24-bit LE PCM, rounded at 24-bit
         * precision. Gain glides from [fromGain] to [toGain] over the first
         * [rampFrames] frames (constant when they are equal).
         */
        internal fun scalePcm16To24(
            src: ByteArray,
            fromGain: Float,
            toGain: Float,
            channels: Int,
            rampFrames: Int,
        ): ByteArray {
            val samples = src.size / 2
            val out = ByteArray(samples * 3)
            val a = fromGain.coerceIn(0f, 1f).toDouble() * 256.0
            val b = toGain.coerceIn(0f, 1f).toDouble() * 256.0
            val ch = channels.coerceAtLeast(1)
            var i = 0
            var o = 0
            var sampleIndex = 0
            while (i + 1 < src.size) {
                val frame = sampleIndex / ch
                val g = if (a == b || frame >= rampFrames) b else a + (b - a) * frame / rampFrames
                sampleIndex++
                val sample = ((src[i].toInt() and 0xFF) or (src[i + 1].toInt() shl 8)).toShort().toInt()
                val scaled = kotlin.math.round(sample * g).toInt().coerceIn(-0x800000, 0x7FFFFF)
                out[o] = (scaled and 0xFF).toByte()
                out[o + 1] = ((scaled shr 8) and 0xFF).toByte()
                out[o + 2] = ((scaled shr 16) and 0xFF).toByte()
                i += 2
                o += 3
            }
            return out
        }

        /**
         * [scalePcmBytes] with a glide from [fromGain] to [toGain] across the
         * first [rampFrames] frames, applied in short constant-gain blocks
         * (~0.7ms at 44.1k, far below audibility). Equal gains take the
         * plain constant path.
         */
        private fun scalePcmRamped(
            bytes: ByteArray,
            rawEncoding: Int,
            fromGain: Float,
            toGain: Float,
            channels: Int,
            rampFrames: Int,
        ) {
            val bytesPerSample = when (rawEncoding) {
                RAW_PCM16 -> 2
                RAW_PCM24 -> 3
                RAW_PCM32 -> 4
                else -> return
            }
            val frameBytes = bytesPerSample * channels.coerceAtLeast(1)
            val totalFrames = bytes.size / frameBytes
            if (fromGain == toGain || totalFrames == 0) {
                if (toGain < 1f - 1e-6f) scalePcmBytes(bytes, rawEncoding, toGain)
                return
            }
            val rampEnd = minOf(totalFrames, rampFrames)
            var frame = 0
            while (frame < totalFrames) {
                val inRamp = frame < rampEnd
                val n = if (inRamp) minOf(RAMP_BLOCK_FRAMES, rampEnd - frame) else totalFrames - frame
                val g = if (inRamp) {
                    fromGain + (toGain - fromGain) * ((frame + n / 2f) / rampFrames)
                } else {
                    toGain
                }
                if (g < 1f - 1e-6f) {
                    val start = frame * frameBytes
                    val end = (frame + n) * frameBytes
                    val segment = bytes.copyOfRange(start, end)
                    scalePcmBytes(segment, rawEncoding, g)
                    segment.copyInto(bytes, start)
                }
                frame += n
            }
        }

        private const val RAMP_BLOCK_FRAMES = 32

        private fun scalePcmBytes(bytes: ByteArray, rawEncoding: Int, gain: Float) {
            if (gain <= 0f) {
                bytes.fill(0)
                return
            }
            when (rawEncoding) {
                RAW_PCM16 -> {
                    var i = 0
                    while (i + 1 < bytes.size) {
                        val sample = ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8))
                            .toShort().toInt()
                        val scaled = (sample * gain + if (sample >= 0) 0.5f else -0.5f).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        bytes[i] = (scaled and 0xFF).toByte()
                        bytes[i + 1] = ((scaled shr 8) and 0xFF).toByte()
                        i += 2
                    }
                }
                RAW_PCM24 -> {
                    var i = 0
                    while (i + 2 < bytes.size) {
                        val b0 = bytes[i].toInt() and 0xFF
                        val b1 = bytes[i + 1].toInt() and 0xFF
                        val b2 = bytes[i + 2].toInt() and 0xFF
                        var sample = (b2 shl 16) or (b1 shl 8) or b0
                        if ((sample and 0x800000) != 0) {
                            sample = (sample shl 8) shr 8
                        }
                        val scaled = (sample * gain.toDouble() + if (sample >= 0) 0.5 else -0.5).toInt()
                            .coerceIn(-0x800000, 0x7FFFFF)
                        bytes[i] = (scaled and 0xFF).toByte()
                        bytes[i + 1] = ((scaled shr 8) and 0xFF).toByte()
                        bytes[i + 2] = ((scaled shr 16) and 0xFF).toByte()
                        i += 3
                    }
                }
                RAW_PCM32 -> {
                    var i = 0
                    while (i + 3 < bytes.size) {
                        val b0 = bytes[i].toInt() and 0xFF
                        val b1 = bytes[i + 1].toInt() and 0xFF
                        val b2 = bytes[i + 2].toInt() and 0xFF
                        val b3 = bytes[i + 3].toInt() and 0xFF
                        val sample = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                        val scaled = (sample.toDouble() * gain.toDouble()).toLong()
                            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                        bytes[i] = (scaled and 0xFF).toByte()
                        bytes[i + 1] = ((scaled shr 8) and 0xFF).toByte()
                        bytes[i + 2] = ((scaled shr 16) and 0xFF).toByte()
                        bytes[i + 3] = ((scaled shr 24) and 0xFF).toByte()
                        i += 4
                    }
                }
            }
        }
    }
}

/**
 * Gain to use when a raw STREAM_MUSIC read is the attach-time 100% pin.
 *
 * Android reports the media volume pinned at max the moment a USB DAC
 * attaches, while the user's real level is still the last non-max volume-key
 * press. Applying the pinned reading at full scale is what boosted the
 * volume to 100 on every remove/re-attach. The remembered level is what the
 * user hears on their next key press, so it wins while the read is pinned.
 * Null means the raw reading is trustworthy.
 */
internal fun attachMaxPinFallbackGain(streamGain: Float, rememberedGain: Float): Float? {
    if (streamGain < ATTACH_MAX_PIN_GAIN) return null
    if (!rememberedGain.isFinite()) return null
    if (rememberedGain < MIN_REMEMBERED_GAIN || rememberedGain > ATTACH_MAX_PIN_GAIN) return null
    return rememberedGain
}

/** Raw readings at/above this are the attach-time pin, not a chosen level. */
private const val ATTACH_MAX_PIN_GAIN = 0.999f

/** Quietest level remembered as the user's real listening gain. */
private const val MIN_REMEMBERED_GAIN = 0.01f
