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
    @Volatile private var lastHardwareRate = 0

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
    private var activeWireBits = 0
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
    @Volatile private var lastAppliedCombined = Float.NaN
    private var volumeProbed = false
    @Volatile private var lastNonMaxListeningGain = Float.NaN
    @Volatile private var ignoreStreamMusicMax = false
    /** Fork setting: never let the DAC's Feature Unit own the listening level. */
    @Volatile private var forceSoftwareVolume = true

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
                clearBlockedDevices()
                ensureVolumeObserverLocked()
                syncListeningGainLocked()
            }
        }
    }

    fun isWanted(): Boolean = wanted

    /**
     * Software (PCM-scaled) volume instead of the DAC's hardware Feature Unit.
     * Switching to software parks the hardware level at 0 dB first so the two
     * attenuations never stack. Switching back takes effect at the next start.
     */
    fun setForceSoftwareVolume(value: Boolean) {
        synchronized(lock) {
            if (forceSoftwareVolume == value) return
            forceSoftwareVolume = value
            if (value && active && hardwareVolume) {
                runCatching { featureVolume?.setNormalized(1f) }
                hardwareVolume = false
                lastAppliedCombined = Float.NaN
                applyVolumeLocked()
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
     * PCM software gain for exclusive output. 1 when a verified Feature Unit
     * owns listening level; otherwise STREAM_MUSIC × Media3 volume.
     */
    fun softwareGain(): Float = if (!active || hardwareVolume) 1f else softwareGainValue

    fun currentRateHz(): Int = if (active) configuredRateHz else 0

    fun lastHardwareRateHz(): Int = lastHardwareRate

    fun supportedHardwareRatesHz(): List<Int> = usbAudio.querySupportedSampleRates()

    private fun pickPlayableHardwareRate(sourceHz: Int, supportedHz: List<Int>): Int? {
        val supported = supportedHz.filter { it > 0 }.toSet()
        if (supported.isEmpty()) return null
        if (sourceHz in supported) return sourceHz
        // When a DAC lacks a high-rate 44.1 kHz crystal (88.2 / 176.4 / 352.8 / 705.6 kHz),
        // prefer its native 48 kHz-family hardware crystal (96 / 192 / 384 / 48 kHz) where
        // USB High-Speed 125us microframes have exact integer frame counts (12 / 24 / 48 / 6).
        if (sourceHz > 44100 && sourceHz % 44100 == 0) {
            val family48 = supported.filter { it % 48000 == 0 }
            if (family48.isNotEmpty()) {
                val hiRes48 = family48.filter { it >= sourceHz }.minOrNull()
                    ?: family48.maxOrNull()
                if (hiRes48 != null) return hiRes48
            }
        }
        // 1. Highest supported integer divisor in the same clock family (e.g. 192 -> 96 -> 48 kHz)
        val divisors = supported.filter { it < sourceHz && sourceHz % it == 0 }
        divisors.maxOrNull()?.let { return it }
        // 2. Lowest supported integer multiple in the same clock family (e.g. 44.1 -> 88.2 kHz)
        val multiples = supported.filter { it > sourceHz && it % sourceHz == 0 }
        multiples.minOrNull()?.let { return it }
        // 3. Same clock family (44.1k vs 48k), closest to source
        val is441 = sourceHz % 44100 == 0
        val is48 = sourceHz % 48000 == 0
        val sameFamily = supported.filter { (is441 && it % 44100 == 0) || (is48 && it % 48000 == 0) }
        sameFamily.minByOrNull { kotlin.math.abs(it - sourceHz) }?.let { return it }
        // 4. Highest high-res rate <= sourceHz (e.g. 96 kHz or 48 kHz)
        val belowOrEqual = supported.filter { it <= sourceHz }
        belowOrEqual.maxOrNull()?.let { return it }
        return supported.minByOrNull { kotlin.math.abs(it - sourceHz) }
    }

    fun framesWritten(): Long = stream?.framesWritten ?: 0L

    fun isPaused(): Boolean = paused

    /**
     * Pause/resume ISO writes without tearing down the USB session or
     * discarding already-decoded PCM in [pcmQueue]. [flush] clears the queue
     * on explicit seek/stop.
     */
    fun setPaused(value: Boolean) {
        paused = value
        if (value) {
            lastWriteElapsedMs = 0L
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

    fun syncListeningGain() {
        val stream = readStreamMusicGain() ?: return
        // While the attach-time 100% pin is held off with the remembered
        // level, keep ignoring pinned max readings. The next real sub-max
        // change (a volume key press) ends the hold and applies normally.
        if (ignoreStreamMusicMax && stream >= ATTACH_MAX_PIN_GAIN) return
        if (stream < ATTACH_MAX_PIN_GAIN) ignoreStreamMusicMax = false
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
    ): Boolean {
        if (!wanted) return false
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW ||
            format.sampleRate <= 0 ||
            format.channelCount !in 1..2
        ) {
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
        synchronized(lock) {
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
        val chunk = if (isFloatBuffer) {
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
        currentFallbackNegotiated = false
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
        rateOverrideHz: Int? = null,
    ): Boolean {
        val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val usbDevice = manager.deviceList.values.firstOrNull { dev ->
            (0 until dev.interfaceCount).any {
                dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }
        } ?: return failLocked("no USB audio device")
        if (!manager.hasPermission(usbDevice)) return failLocked("USB permission missing")
        blockReasonFor(usbDevice.vendorId, usbDevice.productId)?.let { reason ->
            return failLocked("disabled for this DAC after a failed test ($reason) — turn USB Exclusive off and on to retry")
        }
        currentDeviceKey = "${usbDevice.vendorId}:${usbDevice.productId}"

        val bits = if (sourceBits > 0) sourceBits else (if (floatSource) 24 else 16)
        val reuse = stream
        if (reuse != null &&
            reuse.isAlive &&
            configuredRateHz == sampleRate &&
            active &&
            sourceEncoding == pcmEncoding &&
            useFloatWrite == floatSource
        ) {
            currentFallbackNegotiated = rateOverrideHz != null
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
        val info = usbAudio.openDevice(usbDevice)
            ?: return failLocked("openDevice failed" + (usbAudio.lastOpenError?.let { ": $it" } ?: ""))
        val hwRates = usbAudio.querySupportedSampleRates()

        var effectiveRate = sampleRate
        var effectiveBits = bits
        var effectiveFloatSource = floatSource
        var effectiveEncoding = pcmEncoding
        var autoNegotiatedFallback = rateOverrideHz != null

        // If the USB Clock Source / Format descriptors explicitly report the
        // DAC's supported rates and the requested rate (e.g. 88.2 / 176.4 / 352.8 kHz)
        // is not in that hardware table, immediately select the best supported rate.
        if (rateOverrideHz == null && hwRates.isNotEmpty() && effectiveRate !in hwRates) {
            val fallback = pickPlayableHardwareRate(effectiveRate, hwRates)
            if (fallback != null && fallback != effectiveRate) {
                Log.i(
                    TAG,
                    "DAC hardware descriptor lacks ${effectiveRate}Hz (supports $hwRates); " +
                        "switching exclusive clock to ${fallback}Hz for soxr resampling",
                )
                effectiveRate = fallback
                effectiveBits = 24
                effectiveFloatSource = true
                effectiveEncoding = C.ENCODING_PCM_FLOAT
                autoNegotiatedFallback = true
            }
        }

        var (alt, wireBits) = usbAudio.findAltSetting(effectiveBits, effectiveRate)
        var rateSetBefore = usbAudio.setSampleRate(effectiveRate)
        if (!usbAudio.setAltSetting(alt)) return failLocked("setAltSetting $alt failed")
        var reported = usbAudio.readSampleRate()
        if (!rateSetBefore || (reported > 0 && reported != effectiveRate)) {
            rateSetBefore = usbAudio.setSampleRate(effectiveRate)
            reported = usbAudio.readSampleRate()
        }

        // If the DAC's internal clock still rejected or clamped the requested rate
        // (e.g. reported 44.1 / 48 / 96 kHz when asked for 88.2 / 176.4 / 352.8 kHz),
        // fall back to a rate the DAC's internal clock actually locks to.
        if (reported > 0 && reported != effectiveRate) {
            val candidates = (hwRates + reported).distinct()
            val candidateRate = pickPlayableHardwareRate(sampleRate, candidates) ?: reported
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
            val updatedAlt = usbAudio.findAltSetting(effectiveBits, effectiveRate)
            if (updatedAlt.first != alt) {
                if (usbAudio.setAltSetting(updatedAlt.first)) {
                    alt = updatedAlt.first
                    wireBits = updatedAlt.second
                }
            }
            reported = usbAudio.readSampleRate()
        }

        val selected = (0 until usbDevice.interfaceCount)
            .map { usbDevice.getInterface(it) }
            .firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    it.interfaceSubclass == 2 &&
                    // Playback interface only: headset dongles also expose a
                    // microphone AS interface with the same alt numbers.
                    it.id == info.interfaceId &&
                    it.alternateSetting == alt &&
                    it.endpointCount > 0
            }
        val selectedOut = selected?.let { iface ->
            (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                        it.direction == UsbConstants.USB_DIR_OUT
                }
        }
        if (selectedOut == null) return failLocked("alt $alt has no isochronous OUT endpoint")
        val selectedFeedback = selected?.let { iface ->
            (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                        it.direction == UsbConstants.USB_DIR_IN
                }
        }
        val maxPacket = isoPacketBytes(selectedOut.maxPacketSize)
        val dataInterval = selectedOut.interval.coerceAtLeast(1)
        val feedbackPacketSize = selectedFeedback?.maxPacketSize?.let { isoPacketBytes(it) }
            ?: info.feedbackPacketSize
        val feedbackInterval = selectedFeedback?.interval?.coerceAtLeast(1) ?: dataInterval
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
        )
        if (!created.isReady || !created.start()) {
            created.release()
            return failLocked("decent USB start failed")
        }
        connection = info.connection
        stream = created
        sourceEncoding = effectiveEncoding
        activeWireBits = wireBits
        useFloatWrite = effectiveFloatSource
        configuredRateHz = effectiveRate
        currentFallbackNegotiated = autoNegotiatedFallback
        active = true
        lastFailureReason = null
        paused = false
        startMediaTimeNeedsInit = true
        startMediaTimeUs = 0L
        mediaTimeBaseFrames = 0L
        if (reported > 0) {
            lastHardwareRate = reported
            clockMatched = reported == effectiveRate && !autoNegotiatedFallback
        } else {
            clockMatched = rateSetBefore && !autoNegotiatedFallback
        }
        clockRechecked = true
        if (!forceSoftwareVolume) {
            val controlId = (0 until usbDevice.interfaceCount)
                .map { usbDevice.getInterface(it) }
                .firstOrNull {
                    it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 1
                }?.id ?: 0
            val volumeControl = UacFeatureVolume(info.connection, controlId)
            featureVolume = volumeControl
            val attached = volumeControl.attach()
            featureVolumeAttached = attached
            hardwareVolume = attached
        } else {
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

        ensureVolumeObserverLocked()
        rememberStreamGain(readStreamMusicGain())
        listeningGain = listeningGainForDac()
        lastAppliedCombined = Float.NaN
        applyVolumeLocked()
        Log.i(
            TAG,
            "exclusive volume hardware=$hardwareVolume listening=$listeningGain softwareGain=$softwareGainValue",
        )
        sessionSummary = "started rate=${effectiveRate}Hz alt=$alt wireBits=$wireBits reported=$reported " +
            "clockMatched=$clockMatched fallback=$autoNegotiatedFallback hardwareVolume=$hardwareVolume " +
            "maxPacket=$maxPacket interval=$dataInterval feedback=${selectedFeedback != null}"
        startWriterLocked(created)
        return true
    }

    // ── Fork: safety guard + diagnostics ──

    @Volatile private var currentDeviceKey: String? = null
    @Volatile private var sessionSummary: String = ""

    private fun blockReasonFor(vendorId: Int, productId: Int): String? =
        volumePrefs.getString("$KEY_BLOCK_PREFIX$vendorId:$productId", null)

    /** Forget every DAC the guard disabled (USB Exclusive toggled off then on). */
    fun clearBlockedDevices() {
        val editor = volumePrefs.edit()
        volumePrefs.all.keys.filter { it.startsWith(KEY_BLOCK_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }

    /**
     * The DAC is not consuming the stream at the rate it was configured for
     * and its clock was never confirmed: what it plays is garbage (loud noise
     * on a UAC1 dongle in the field). Stop now, remember the DAC so the next
     * track does not blast it again, and let the sink fall back.
     */
    fun abortUnhealthy(reason: String) {
        synchronized(lock) {
            if (!active) return
            val key = currentDeviceKey
            Log.w(TAG, "Exclusive USB aborted: $reason (device $key)")
            if (key != null) {
                runCatching { volumePrefs.edit().putString("$KEY_BLOCK_PREFIX$key", reason).apply() }
            }
            failLocked("stopped for safety: $reason")
        }
    }

    /** Text for the signal-path "copy USB diagnostic" button. */
    fun diagnosticReport(): String = buildString {
        append("LastWave USB diagnostic\n")
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

    private fun applyVolumeLocked() {
        val combined = (listeningGain * pendingVolume).coerceIn(0f, 1f)
        val unchanged = lastAppliedCombined.isFinite() && kotlin.math.abs(combined - lastAppliedCombined) < 1e-4f
        if (hardwareVolume) {
            softwareGainValue = 1f
            if (!unchanged) {
                val applied = featureVolume?.setNormalized(combined) == true
                if (!applied) {
                    hardwareVolume = false
                    softwareGainValue = UacFeatureVolume.perceptualLinearGain(combined)
                    Log.w(TAG, "Feature Unit SET_CUR failed; falling back to perceptual software gain")
                }
                lastAppliedCombined = combined
            }
        } else {
            softwareGainValue = UacFeatureVolume.perceptualLinearGain(combined)
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
            writingInProgress = true
            lastWriteElapsedMs = SystemClock.elapsedRealtime()
            try {
                if (next.floats != null) target.write(next.floats)
                else if (next.raw != null) target.writeRaw(next.raw, next.encoding)
            } finally {
                writingInProgress = false
                lastWriteElapsedMs = SystemClock.elapsedRealtime()
            }
        }
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
        active = false
    }

    private fun teardownLocked(closeDevice: Boolean) {
        stopStreamLocked()
        if (closeDevice) {
            runCatching { usbAudio.closeDevice() }
            connection = null
        }
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
        lastFailureReason = reason
        teardownLocked(closeDevice = true)
        return false
    }

    private companion object {
        const val TAG = "ExclusiveUsb"
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val KEY_LAST_NON_MAX_GAIN = "last_non_max_stream_gain"
        const val KEY_BLOCK_PREFIX = "blocked_dac_"
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
