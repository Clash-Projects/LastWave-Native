@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lastwave.app.playback

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.os.Build
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer

/**
 * Runs the optional native Float32 DSP in front of Media3 while keeping an
 * independent, conservative [DefaultAudioSink] ready as the fail-open path.
 * Devices that reject Float32 AudioTrack still receive the native DSP result;
 * Media3 performs only the final Float32-to-PCM16 conversion. If native/JNI
 * processing itself fails, the untouched source buffer is retried through the
 * plain platform PCM path.
 *
 * Bit-Perfect clean path: when requested, decoded PCM of ANY depth
 * (16/24/32-bit integer or Float) is handed to the platform sink untouched —
 * no Float conversion, no libsoxr resampler, no DSP container, no AudioFX.
 * The source sample rate therefore drives the opened AudioTrack directly.
 */
class NativeProcessingAudioSink(
    private val enhancedDelegate: DefaultAudioSink,
    private val fallbackDelegate: DefaultAudioSink,
    private val processor: NativePcmAudioProcessor,
    private val onPlatformEffectsRequired: (Boolean) -> Unit = {},
    private val usbOutput: UsbBitPerfectOutput? = null,
    private val exclusiveUsb: ExclusiveUsbOutput? = null,
) : AudioSink {
    private var activeDelegate: DefaultAudioSink = fallbackDelegate
    // Read on the main thread for the signal-path verdict, written on the
    // renderer thread — volatile so bypass/stale reads never go stale.
    @Volatile private var processingActive = false
    private var floatOutputDisabled = false
    private var nativePathDisabled = false
    private var playing = false
    @Volatile private var bitPerfectRequested = false
    // Generation tracking so the verdict can separate bitPerfectRequested
    // from bitPerfectActuallyActive: the flag only takes effect when the sink
    // next configures (next track). A mid-track toggle leaves stale config.
    @Volatile private var bitPerfectAtConfigure = false
    @Volatile private var hasConfigured = false
    @Volatile private var exclusiveWanted = false
    @Volatile private var usbExclusive = false
    @Volatile private var exclusiveStartFailed = false
    @Volatile private var exclusiveEnded = false

    private var configuredFormat: Format? = null
    private var configuredBufferSize = 0
    private var configuredOutputChannels: IntArray? = null
    private var processedFormat: Format? = null
    private var outputChannelCount = 2

    private var pendingInputLimit = 0
    private var pendingOutput: ByteBuffer? = null
    @Volatile
    private var lastVolume = 1f
    // Buffer already gain-scaled for the current direct-passthrough buffer
    // (see handleDirectPassthrough). Renderer thread only.
    private var lastGainBuffer: ByteBuffer? = null
    private var pendingPresentationTimeUs = 0L
    private var pendingAccessUnitCount = 0
    private var pendingOutputFrameCount = 0
    private var nextOutputPresentationTimeUs = 0L

    private var endOfStreamQueued = false
    private var endOfStreamOutput: ByteBuffer? = null

    override fun setListener(listener: AudioSink.Listener) {
        enhancedDelegate.setListener(listener)
        fallbackDelegate.setListener(listener)
    }

    override fun setClock(clock: Clock) {
        enhancedDelegate.setClock(clock)
        fallbackDelegate.setClock(clock)
    }

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int {
        val fallbackSupport = fallbackDelegate.getFormatSupport(format)
        if (nativePathDisabled || !processor.isAvailable || !canProcess(format)) {
            return fallbackSupport
        }
        val floatSupport = enhancedDelegate.getFormatSupport(asFloatProbeFormat(format))
        if (!floatOutputDisabled && floatSupport != AudioSink.SINK_FORMAT_UNSUPPORTED) {
            return floatSupport
        }
        val processedPcm16Support = fallbackDelegate.getFormatSupport(asFloatProbeFormat(format))
        return if (processedPcm16Support != AudioSink.SINK_FORMAT_UNSUPPORTED) {
            processedPcm16Support
        } else fallbackSupport
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        AudioOffloadSupport.DEFAULT_UNSUPPORTED

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        if (usbExclusive) {
            exclusiveUsb?.getCurrentPositionUs() ?: AudioSink.CURRENT_POSITION_NOT_SET
        } else {
            activeDelegate.getCurrentPositionUs(sourceEnded)
        }

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        configuredFormat = format
        configuredBufferSize = specifiedBufferSize
        configuredOutputChannels = outputChannels?.clone()
        clearPending()
        clearEndOfStream()
        processingActive = false
        processedFormat = null
        hasConfigured = true
        exclusiveStartFailed = false
        exclusiveEnded = false

        if (tryConfigureExclusiveUsb(format)) {
            bitPerfectAtConfigure = true
            return
        }

        if (bitPerfectRequested) {
            Log.i(
                TAG,
                "BIT-PERFECT REQUEST srcRate=${format.sampleRate} " +
                    "srcEnc=${format.pcmEncoding} srcCh=${format.channelCount}",
            )
            if (tryConfigureBitPerfectDirect(format, specifiedBufferSize, outputChannels)) {
                bitPerfectAtConfigure = true
                return
            }
            bitPerfectAtConfigure = false
            Log.w(
                TAG,
                "BIT-PERFECT compat path: direct ${format.pcmEncoding}/${format.sampleRate} Hz " +
                    "unavailable; DSP-bypassed conversion follows (verdict must fail)",
            )
        } else {
            bitPerfectAtConfigure = false
        }

        if (!nativePathDisabled && processor.isAvailable && canProcess(format) &&
            tryConfigureNativePath(format, outputChannels)
        ) {
            return
        }
        configureFallback(format, specifiedBufferSize, outputChannels)
    }

    /**
     * Dedicated Bit-Perfect clean path for every PCM depth: the decoder's
     * buffer reaches AudioTrack with no Float conversion, no SRC and no DSP.
     * Returns false when the device cannot open the source format directly —
     * the caller then falls through to the DSP-bypassed compatibility path,
     * which the signal-path verdict must NOT report as bit-perfect.
     */
    private fun tryConfigureBitPerfectDirect(
        format: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ): Boolean {
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW ||
            format.sampleRate <= 0 ||
            format.channelCount !in 1..2 ||
            format.pcmEncoding !in SUPPORTED_ENCODINGS
        ) {
            return false
        }
        return try {
            if (format.pcmEncoding == C.ENCODING_PCM_FLOAT) {
                // Float sources need a Float AudioTrack to stay bit-exact;
                // the PCM16-only fallback would quantize them.
                if (floatOutputDisabled ||
                    enhancedDelegate.getFormatSupport(format) == AudioSink.SINK_FORMAT_UNSUPPORTED
                ) {
                    return false
                }
                safeResetProcessor()
                // Platform BIT_PERFECT preference must be active BEFORE the
                // AudioTrack opens — setting it after configure() leaves the
                // first track on the shared mixer (192k -> 48k hijack).
                configureUsbOutput(format, format.pcmEncoding, outputChannels)
                enhancedDelegate.configure(format, specifiedBufferSize, outputChannels)
                if (activeDelegate !== enhancedDelegate) safeFlush(fallbackDelegate)
                activeDelegate = enhancedDelegate
                processingActive = false
                processedFormat = null
                lastGainBuffer = null
                syncDelegateVolume()
                notifyPlatformEffectsRequired(false)
                if (playing) enhancedDelegate.play()
            } else {
                // 16/24/32-bit integer PCM straight through in its native
                // packing: the source rate opens the AudioTrack, so no
                // LastWave resampler runs at any depth.
                safeResetProcessor()
                configureUsbOutput(format, format.pcmEncoding, outputChannels)
                fallbackDelegate.configure(format, specifiedBufferSize, outputChannels)
                if (activeDelegate !== fallbackDelegate) safeFlush(enhancedDelegate)
                activeDelegate = fallbackDelegate
                processingActive = false
                processedFormat = null
                lastGainBuffer = null
                syncDelegateVolume()
                notifyPlatformEffectsRequired(false)
                if (playing) fallbackDelegate.play()
            }
            Log.i(
                TAG,
                "BIT-PERFECT OUTPUT ACTUAL rate=${format.sampleRate} enc=${format.pcmEncoding} " +
                    "ch=${format.channelCount} DSP=false SRC=false softwareGain=false active=true",
            )
            true
        } catch (error: Exception) {
            Log.w(TAG, "Bit-Perfect direct configure failed", error)
            false
        } catch (error: LinkageError) {
            Log.w(TAG, "Bit-Perfect direct configure linkage failed", error)
            false
        }
    }

    private fun tryConfigureNativePath(format: Format, outputChannels: IntArray?): Boolean {
        val outputFormat = try {
            processor.reset()
            processor.setTrimFrameCount(format.encoderDelay, format.encoderPadding)
            processor.configure(AudioProcessor.AudioFormat(format))
        } catch (error: Exception) {
            disableNativePath("Native DSP configuration failed", error)
            return false
        } catch (error: LinkageError) {
            disableNativePath("Native DSP configuration linkage failed", error)
            return false
        }

        if (outputFormat == AudioProcessor.AudioFormat.NOT_SET ||
            outputFormat.sampleRate <= 0 ||
            outputFormat.channelCount !in 1..2 ||
            outputFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            disableNativePath("Native DSP rejected decoded PCM", null)
            return false
        }

        val floatFormat = asFloatFormat(format, outputFormat)
        processedFormat = floatFormat
        outputChannelCount = outputFormat.channelCount
        try {
            processor.flush()
        } catch (error: Exception) {
            disableNativePath("Native DSP activation failed", error)
            return false
        } catch (error: LinkageError) {
            disableNativePath("Native DSP activation linkage failed", error)
            return false
        }

        if (!floatOutputDisabled &&
            enhancedDelegate.getFormatSupport(floatFormat) != AudioSink.SINK_FORMAT_UNSUPPORTED
        ) {
            try {
                enhancedDelegate.configure(floatFormat, 0, outputChannels)
                if (activeDelegate !== enhancedDelegate) safeFlush(fallbackDelegate)
                activeDelegate = enhancedDelegate
                processingActive = true
                configureUsbOutput(floatFormat, C.ENCODING_PCM_FLOAT, outputChannels)
                lastGainBuffer = null
                syncDelegateVolume()
                notifyPlatformEffectsRequired(false)
                if (playing) enhancedDelegate.play()
                return true
            } catch (error: Exception) {
                disableFloatOutput("Float32 AudioTrack configuration failed", error)
            } catch (error: LinkageError) {
                disableFloatOutput("Float32 AudioTrack configuration linkage failed", error)
            }
        }

        return try {
            // Float output is deliberately fed into a float-disabled sink.
            // Media3's ToInt16PcmAudioProcessor performs the final conversion,
            // while all native DSP remains 32-bit floating point.
            fallbackDelegate.configure(floatFormat, 0, outputChannels)
            if (activeDelegate !== fallbackDelegate) safeFlush(enhancedDelegate)
            activeDelegate = fallbackDelegate
            processingActive = true
            configureUsbOutput(floatFormat, C.ENCODING_PCM_16BIT, outputChannels)
            lastGainBuffer = null
            syncDelegateVolume()
            notifyPlatformEffectsRequired(false)
            if (playing) fallbackDelegate.play()
            Log.i(TAG, "Using native Float32 DSP with PCM16 AudioTrack compatibility output")
            true
        } catch (error: Exception) {
            disableNativePath("Processed PCM16 compatibility configuration failed", error)
            false
        } catch (error: LinkageError) {
            disableNativePath("Processed PCM16 compatibility linkage failed", error)
            false
        }
    }

    private fun configureFallback(
        format: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        processingActive = false
        clearPending()
        clearEndOfStream()
        processedFormat = null
        safeResetProcessor()
        fallbackDelegate.configure(format, specifiedBufferSize, outputChannels)
        if (activeDelegate !== fallbackDelegate) safeFlush(enhancedDelegate)
        activeDelegate = fallbackDelegate
        // Request the mixer format actually sent to AudioTrack (the source
        // encoding here), not a hardcoded PCM16 that can never match a
        // 24-bit direct stream on read-back.
        configureUsbOutput(format, format.pcmEncoding, outputChannels)
        lastGainBuffer = null
        syncDelegateVolume()
        notifyPlatformEffectsRequired(true)
        if (playing) fallbackDelegate.play()
    }

    override fun play() {
        playing = true
        if (usbExclusive) {
            exclusiveUsb?.setPaused(false)
            return
        }
        if (!processingActive) {
            activeDelegate.play()
            return
        }
        try {
            activeDelegate.play()
        } catch (error: Exception) {
            if (!recoverProcessingPath("Processed output play failed", error)) throw error
            activeDelegate.play()
        } catch (error: LinkageError) {
            if (!recoverProcessingPath("Processed output play linkage failed", error)) throw error
            activeDelegate.play()
        }
    }

    override fun handleDiscontinuity() {
        clearPending()
        clearEndOfStream()
        if (usbExclusive) {
            exclusiveUsb?.handleDiscontinuity()
            return
        }
        if (!processingActive) {
            activeDelegate.handleDiscontinuity()
            return
        }
        try {
            processor.flush()
            activeDelegate.handleDiscontinuity()
        } catch (error: Exception) {
            if (!recoverProcessingPath("Processed discontinuity handling failed", error)) throw error
            activeDelegate.handleDiscontinuity()
        } catch (error: LinkageError) {
            if (!recoverProcessingPath("Processed discontinuity linkage failed", error)) throw error
            activeDelegate.handleDiscontinuity()
        }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        maybeAdoptExclusiveUsb()
        if (usbExclusive) {
            if (exclusiveUsb?.isPaused() == true || !playing) {
                return false
            }
            val fmt = configuredFormat
            val gain = exclusiveUsb?.softwareGain() ?: lastVolume
            val needsGain = gain < 1f - 1e-6f && gain >= 0f && buffer.hasRemaining() &&
                fmt?.sampleMimeType == MimeTypes.AUDIO_RAW
            if (needsGain && fmt != null) {
                if (lastGainBuffer !== buffer) {
                    scalePcmInPlace(buffer, buffer.position(), buffer.limit(), fmt.pcmEncoding, gain)
                    lastGainBuffer = buffer
                }
            } else {
                lastGainBuffer = null
            }
            val ok = exclusiveUsb?.write(buffer, presentationTimeUs) == true
            if (ok) {
                exclusiveEnded = false
                return true
            }
            // Queue full: the DAC is still running. Return false so ExoPlayer
            // retries and keeps loading the network. Tearing the session down
            // here is what left the bar moving with the pause control spinning.
            if (exclusiveUsb?.isStreamAlive() == true) {
                return false
            }
            // A seek discards in-flight URBs. The first write after that can
            // fail once; tearing the USB session down here is what left the
            // DAC silent while the seek bar kept moving.
            if (exclusiveUsb?.restartIfStopped() == true) {
                return false
            }
            exclusiveStartFailed = true
            leaveExclusiveUsb(configureAndroid = true)
        }
        if (!processingActive) {
            return handleDirectPassthrough(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        try {
            return handleProcessedBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        } catch (error: Exception) {
            if (!recoverProcessingPath("Native/processed buffer failed", error)) throw error
        } catch (error: LinkageError) {
            if (!recoverProcessingPath("Native/processed buffer linkage failed", error)) throw error
        }
        if (!processingActive) {
            return fallbackDelegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        return try {
            handleProcessedBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        } catch (retryError: Exception) {
            if (!switchToPlatformFallback("Processed PCM16 retry failed", retryError)) throw retryError
            fallbackDelegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        } catch (retryError: LinkageError) {
            if (!switchToPlatformFallback("Processed PCM16 retry linkage failed", retryError)) throw retryError
            fallbackDelegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
    }

    /**
     * Bit-perfect direct path with workable volume. At unity gain the buffer
     * passes untouched (bit-exact). Below unity — app volume, ducking, fades —
     * samples are scaled in software first, because a granted BIT_PERFECT
     * mixer bypass ignores AudioTrack volume: without this the volume keys
     * would go dead the moment raw output engages. Any scaling is observed
     * via [currentVolume], so the signal-path verdict honestly fails while
     * attenuated and passes again at unity.
     */
    private fun handleDirectPassthrough(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val gain = lastVolume
        val needsGain = bitPerfectRequested && bitPerfectAtConfigure && hasConfigured &&
            gain < 1f - 1e-6f && gain >= 0f && buffer.hasRemaining()
        if (!needsGain) {
            lastGainBuffer = null
            return activeDelegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        // Media3 retries the SAME buffer object when the delegate partially
        // consumes it. Scale once per buffer identity: re-scaling the tail on
        // retry would compound attenuation.
        if (lastGainBuffer !== buffer) {
            val fmt = configuredFormat
            if (fmt == null || fmt.sampleMimeType != MimeTypes.AUDIO_RAW) {
                return activeDelegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            }
            scalePcmInPlace(buffer, buffer.position(), buffer.limit(), fmt.pcmEncoding, gain)
            lastGainBuffer = buffer
        }
        return activeDelegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    /** Linear gain on interleaved PCM, in place, with proper rounding/clipping. */
    private fun scalePcmInPlace(
        buffer: ByteBuffer,
        startPos: Int,
        endPos: Int,
        media3Encoding: Int,
        gain: Float,
    ) {
        if (gain <= 0f) {
            var pos = startPos
            while (pos < endPos) {
                buffer.put(pos, 0.toByte())
                pos++
            }
            return
        }
        when (media3Encoding) {
            C.ENCODING_PCM_16BIT -> {
                var pos = startPos
                while (pos + 1 < endPos) {
                    val sample = buffer.getShort(pos).toInt()
                    val scaled = (sample * gain + if (sample >= 0) 0.5f else -0.5f).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    buffer.putShort(pos, scaled.toShort())
                    pos += 2
                }
            }
            C.ENCODING_PCM_24BIT -> {
                var pos = startPos
                while (pos + 2 < endPos) {
                    val b0 = buffer.get(pos).toInt() and 0xFF
                    val b1 = buffer.get(pos + 1).toInt() and 0xFF
                    val b2 = buffer.get(pos + 2).toInt()
                    var sample = (b2 shl 16) or (b1 shl 8) or b0
                    if (sample and 0x800000 != 0) sample -= 0x1000000
                    val scaled = (sample * gain + if (sample >= 0) 0.5f else -0.5f).toInt()
                        .coerceIn(-0x800000, 0x7FFFFF)
                    buffer.put(pos, (scaled and 0xFF).toByte())
                    buffer.put(pos + 1, ((scaled shr 8) and 0xFF).toByte())
                    buffer.put(pos + 2, ((scaled shr 16) and 0xFF).toByte())
                    pos += 3
                }
            }
            C.ENCODING_PCM_32BIT -> {
                var pos = startPos
                while (pos + 3 < endPos) {
                    val sample = buffer.getInt(pos)
                    val scaled = (sample * gain.toDouble()).toLong()
                        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                    buffer.putInt(pos, scaled.toInt())
                    pos += 4
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                var pos = startPos
                while (pos + 3 < endPos) {
                    buffer.putFloat(pos, (buffer.getFloat(pos) * gain).coerceIn(-1f, 1f))
                    pos += 4
                }
            }
            else -> Unit
        }
    }

    private fun handleProcessedBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (pendingOutput == null) {
            if (!buffer.hasRemaining()) return true
            processor.queueInput(buffer.duplicate())
            pendingInputLimit = buffer.limit()
            val output = processor.getOutput()
            pendingOutput = output
            pendingPresentationTimeUs = presentationTimeUs
            pendingAccessUnitCount = encodedAccessUnitCount
            pendingOutputFrameCount = output.remaining() /
                (Float.SIZE_BYTES * outputChannelCount)
        }

        val output = pendingOutput ?: return true
        if (output.hasRemaining()) {
            activeDelegate.handleBuffer(output, pendingPresentationTimeUs, pendingAccessUnitCount)
        }
        if (output.hasRemaining()) return false

        nextOutputPresentationTimeUs = pendingPresentationTimeUs +
            pendingOutputFrameCount * MICROS_PER_SECOND / processor.nativeOutputSampleRate
        if (buffer.position() < pendingInputLimit) {
            buffer.position(pendingInputLimit)
        }
        clearPending()
        return true
    }

    override fun playToEndOfStream() {
        if (usbExclusive) {
            exclusiveEnded = true
            return
        }
        if (!processingActive) {
            activeDelegate.playToEndOfStream()
            return
        }
        try {
            if (!endOfStreamQueued) {
                processor.queueEndOfStream()
                endOfStreamOutput = processor.getOutput()
                endOfStreamQueued = true
            }
            val tail = endOfStreamOutput
            if (tail?.hasRemaining() == true) {
                activeDelegate.handleBuffer(tail, nextOutputPresentationTimeUs, 1)
                if (tail.hasRemaining()) return
            }
            activeDelegate.playToEndOfStream()
        } catch (error: Exception) {
            if (!recoverProcessingPath("Processed end-of-stream failed", error)) throw error
            activeDelegate.playToEndOfStream()
        } catch (error: LinkageError) {
            if (!recoverProcessingPath("Processed end-of-stream linkage failed", error)) throw error
            activeDelegate.playToEndOfStream()
        }
    }

    override fun isEnded(): Boolean =
        if (usbExclusive) {
            exclusiveEnded
        } else {
            activeDelegate.isEnded() &&
                pendingOutput?.hasRemaining() != true &&
                endOfStreamOutput?.hasRemaining() != true
        }

    override fun hasPendingData(): Boolean =
        if (usbExclusive) {
            !exclusiveEnded && (exclusiveUsb?.hasPendingData() == true)
        } else {
            pendingOutput?.hasRemaining() == true ||
                endOfStreamOutput?.hasRemaining() == true ||
                activeDelegate.hasPendingData()
        }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        enhancedDelegate.setPlaybackParameters(playbackParameters)
        fallbackDelegate.setPlaybackParameters(playbackParameters)
    }

    override fun getPlaybackParameters(): PlaybackParameters =
        activeDelegate.getPlaybackParameters()

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        // Silence-skipping deletes samples — invisible to the signal-path
        // verdict, so it must be enforced here rather than merely observed.
        val effective = if (bitPerfectRequested && skipSilenceEnabled) {
            Log.w(TAG, "BIT-PERFECT: skip-silence ignored (would remove samples)")
            false
        } else {
            skipSilenceEnabled
        }
        enhancedDelegate.setSkipSilenceEnabled(effective)
        fallbackDelegate.setSkipSilenceEnabled(effective)
    }

    override fun getSkipSilenceEnabled(): Boolean =
        activeDelegate.getSkipSilenceEnabled()

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        usbOutput?.setAttributes(audioAttributes.audioAttributesV21.audioAttributes)
        enhancedDelegate.setAudioAttributes(audioAttributes)
        fallbackDelegate.setAudioAttributes(audioAttributes)
    }

    override fun getAudioAttributes(): AudioAttributes? =
        activeDelegate.getAudioAttributes()

    override fun setAudioSessionId(audioSessionId: Int) {
        enhancedDelegate.setAudioSessionId(audioSessionId)
        fallbackDelegate.setAudioSessionId(audioSessionId)
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        // Aux sends (reverb etc.) recolor PCM and are invisible to the
        // verdict — never attach them while Bit-Perfect is requested. The app
        // never sets an aux effect, so dropping the call keeps the chain none.
        if (bitPerfectRequested) return
        enhancedDelegate.setAuxEffectInfo(auxEffectInfo)
        fallbackDelegate.setAuxEffectInfo(auxEffectInfo)
    }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        usbOutput?.setDevice(audioDeviceInfo)
        enhancedDelegate.setPreferredDevice(audioDeviceInfo)
        fallbackDelegate.setPreferredDevice(audioDeviceInfo)
    }

    /**
     * USB-DAC mode: request the track's native rate so the sink opens its
     * AudioTrack without app-side resampling. Null restores default
     * behavior. Takes effect when the sink next configures (next track).
     */
    fun setOutputSampleRateOverride(sampleRateHz: Int?) {
        runCatching { processor.setOutputSampleRateOverride(sampleRateHz) }
    }

    /** Actual rate the sink configured, 0 when unresolved. */
    fun currentOutputSampleRateHz(): Int =
        if (usbExclusive) {
            exclusiveUsb?.currentRateHz() ?: 0
        } else if (processingActive) {
            processedFormat?.sampleRate ?: 0
        } else {
            configuredFormat?.sampleRate ?: 0
        }

    fun syncExclusiveUsb(wanted: Boolean) {
        val wasWanted = exclusiveWanted
        exclusiveWanted = wanted
        if (wanted && !wasWanted) {
            exclusiveStartFailed = false
        } else if (!wanted && usbExclusive) {
            leaveExclusiveUsb(configureAndroid = hasConfigured && configuredFormat != null)
        }
    }

    fun notifyExclusiveUsbMayStart() {
        exclusiveWanted = true
        exclusiveStartFailed = false
    }

    fun setBitPerfectRequested(enabled: Boolean) {
        if (bitPerfectRequested == enabled) return
        bitPerfectRequested = enabled
        usbOutput?.setEnabled(enabled)
        if (enabled) {
            // Push the verdict-invisible modifiers to their neutral state
            // immediately: a mid-track toggle must not leave a previously
            // enabled skip/aux attached until the next configure. (Aux sends
            // are additionally dropped in setAuxEffectInfo while requested.)
            setSkipSilenceEnabled(false)
        }
        Log.i(TAG, "BIT-PERFECT requested=$enabled (takes effect on next configure)")
    }

    fun isPlatformBitPerfectConfigured(): Boolean = usbOutput?.isConfigured() == true

    /**
     * True only when the ACTIVE AudioTrack configuration is the untouched
     * direct path — requested AND configured direct AND still direct (no
     * runtime recovery rerouted through conversion since).
     */
    fun isBitPerfectBypassActive(): Boolean =
        usbExclusive ||
            (bitPerfectRequested && hasConfigured && bitPerfectAtConfigure && !processingActive)

    fun isExclusiveUsbActive(): Boolean = usbExclusive

    /**
     * True when the toggle changed after the active configuration was built
     * (mid-track toggle): the verdict must fail until the next track
     * reconfigures, instead of claiming bypass for pre-toggle audio.
     */
    fun isBitPerfectConfigStale(): Boolean =
        !usbExclusive && hasConfigured && (bitPerfectRequested != bitPerfectAtConfigure)

    private fun tryConfigureExclusiveUsb(format: Format): Boolean {
        if (!exclusiveWanted || exclusiveStartFailed) return false
        val session = exclusiveUsb ?: return false
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW ||
            format.sampleRate <= 0 ||
            format.channelCount !in 1..2 ||
            format.pcmEncoding !in SUPPORTED_ENCODINGS
        ) {
            return false
        }
        // Release AudioTrack before claiming the USB interface. While the
        // mixer still owns the DAC, libusb cannot detach snd-usb-audio and
        // exclusive start fails closed onto the shared 48 kHz track.
        runCatching { enhancedDelegate.pause() }
        runCatching { fallbackDelegate.pause() }
        runCatching { enhancedDelegate.flush() }
        runCatching { fallbackDelegate.flush() }
        val started = runCatching { session.configure(format) }.getOrDefault(false)
        if (!started) {
            exclusiveStartFailed = true
            usbExclusive = false
            if (playing) {
                runCatching { activeDelegate.play() }
            }
            return false
        }
        enterExclusiveUsb()
        processingActive = false
        processedFormat = null
        lastGainBuffer = null
        usbOutput?.setFormat(null)
        activeDelegate = fallbackDelegate
        syncDelegateVolume()
        notifyPlatformEffectsRequired(false)
        Log.i(
            TAG,
            "EXCLUSIVE USB OUTPUT rate=${format.sampleRate} enc=${format.pcmEncoding} " +
                "ch=${format.channelCount} AudioTrack=idle",
        )
        return true
    }

    private fun maybeAdoptExclusiveUsb() {
        if (!exclusiveWanted || usbExclusive || exclusiveStartFailed || !hasConfigured) return
        val format = configuredFormat ?: return
        if (tryConfigureExclusiveUsb(format)) {
            bitPerfectAtConfigure = true
        }
    }

    private fun enterExclusiveUsb() {
        usbExclusive = true
        exclusiveWanted = true
        exclusiveStartFailed = false
        exclusiveEnded = false
    }

    private fun leaveExclusiveUsb(configureAndroid: Boolean) {
        usbExclusive = false
        exclusiveEnded = false
        runCatching { exclusiveUsb?.reset() }
        if (!configureAndroid) return
        val format = configuredFormat ?: return
        if (bitPerfectRequested &&
            tryConfigureBitPerfectDirect(format, configuredBufferSize, configuredOutputChannels)
        ) {
            bitPerfectAtConfigure = true
            return
        }
        configureFallback(format, configuredBufferSize, configuredOutputChannels)
        bitPerfectAtConfigure = false
    }

    private fun configureUsbOutput(format: Format, media3Encoding: Int, channels: IntArray?) {
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW || format.sampleRate <= 0) {
            usbOutput?.setFormat(null)
            return
        }
        val count = channels?.size ?: format.channelCount
        val mask = when (count) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> { usbOutput?.setFormat(null); return }
        }
        // Media3 C.ENCODING_* ints are NOT android.media.AudioFormat ints
        // (e.g. Media3 24-bit != Android 24-bit-packed). Passing them through
        // raw builds a wrong mixer request (24-bit source asked as 8-bit),
        // so BIT_PERFECT never matches and the mixer hijacks to 48 kHz.
        val androidEncoding = androidEncodingFor(media3Encoding)
        if (androidEncoding == 0) {
            Log.w(TAG, "BIT-PERFECT mixer request skipped: no Android encoding for Media3 $media3Encoding")
            usbOutput?.setFormat(null)
            return
        }
        val pcm = runCatching {
            AudioFormat.Builder().setSampleRate(format.sampleRate)
                .setEncoding(androidEncoding).setChannelMask(mask).build()
        }.getOrNull()
        usbOutput?.setFormat(pcm)
    }

    /**
     * Maps Media3 PCM encoding to the platform AudioFormat encoding used by
     * AudioTrack and AudioMixerAttributes. Returns 0 when the device cannot
     * represent the source depth directly (caller fails closed).
     */
    private fun androidEncodingFor(media3Encoding: Int): Int = when (media3Encoding) {
        C.ENCODING_PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
        C.ENCODING_PCM_24BIT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AudioFormat.ENCODING_PCM_24BIT_PACKED
        } else {
            0
        }
        C.ENCODING_PCM_32BIT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioFormat.ENCODING_PCM_32BIT
        } else {
            0
        }
        C.ENCODING_PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
        else -> 0
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        enhancedDelegate.setOutputStreamOffsetUs(outputStreamOffsetUs)
        fallbackDelegate.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun enableTunnelingV21() {
        enhancedDelegate.enableTunnelingV21()
        fallbackDelegate.enableTunnelingV21()
    }

    override fun disableTunneling() {
        enhancedDelegate.disableTunneling()
        fallbackDelegate.disableTunneling()
    }

    override fun setOffloadMode(offloadMode: Int) {
        enhancedDelegate.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
        fallbackDelegate.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
    }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {
        enhancedDelegate.setOffloadDelayPadding(delayInFrames, paddingInFrames)
        fallbackDelegate.setOffloadDelayPadding(delayInFrames, paddingInFrames)
    }

    override fun setVolume(volume: Float) {
        // Recorded: Media3's audio-focus manager scales this on transient
        // duck, bypassing ExoPlayer.getVolume. Bit-perfect verification must
        // observe the gain that actually reaches AudioTrack, not the request.
        lastVolume = volume
        exclusiveUsb?.setVolume(volume)
        // Exclusive usbdevfs never uses AudioTrack volume. Feature Unit
        // volume (when present) keeps PCM untouched. Mixer BIT_PERFECT
        // ignores AudioTrack volume, so the direct path pins platform gain
        // at unity and scales in software only when exclusive is off.
        val forwarded = if (usbExclusive ||
            (bitPerfectRequested && bitPerfectAtConfigure && !processingActive)
        ) 1f else volume
        enhancedDelegate.setVolume(forwarded)
        fallbackDelegate.setVolume(forwarded)
    }

    /** Last gain reaching the output (1 = unity). */
    fun currentVolume(): Float =
        if (usbExclusive) exclusiveUsb?.softwareGain() ?: lastVolume else lastVolume

    /**
     * Reconciles platform gain after a (re)configure: unity on the direct
     * bypass (software gain in handleDirectPassthrough owns it), otherwise
     * the last requested volume.
     */
    private fun syncDelegateVolume() {
        val forwarded = if (usbExclusive ||
            (bitPerfectRequested && bitPerfectAtConfigure && !processingActive)
        ) 1f else lastVolume
        runCatching { enhancedDelegate.setVolume(forwarded) }
        runCatching { fallbackDelegate.setVolume(forwarded) }
    }

    override fun pause() {
        playing = false
        if (usbExclusive) {
            exclusiveUsb?.setPaused(true)
            return
        }
        activeDelegate.pause()
    }

    override fun flush() {
        clearPending()
        clearEndOfStream()
        exclusiveEnded = false
        if (usbExclusive) {
            exclusiveUsb?.flush()
            return
        }
        if (processingActive) {
            try {
                processor.flush()
                activeDelegate.flush()
                return
            } catch (error: Exception) {
                if (!recoverProcessingPath("Processed flush failed", error)) throw error
            } catch (error: LinkageError) {
                if (!recoverProcessingPath("Processed flush linkage failed", error)) throw error
            }
        }
        activeDelegate.flush()
    }

    override fun reset() {
        usbOutput?.setFormat(null)
        if (usbExclusive && exclusiveWanted) {
            exclusiveUsb?.prepareForNextItem()
            exclusiveEnded = false
        } else {
            exclusiveUsb?.reset()
            usbExclusive = false
            exclusiveEnded = false
            exclusiveStartFailed = false
        }
        clearPending()
        clearEndOfStream()
        configuredFormat = null
        configuredBufferSize = 0
        configuredOutputChannels = null
        processedFormat = null
        processingActive = false
        playing = false
        hasConfigured = false
        bitPerfectAtConfigure = usbExclusive
        notifyPlatformEffectsRequired(false)
        safeResetProcessor()
        safeReset(enhancedDelegate)
        fallbackDelegate.reset()
        activeDelegate = fallbackDelegate
    }

    override fun release() {
        usbOutput?.setFormat(null)
        if (usbExclusive) runCatching { exclusiveUsb?.reset() }
        usbExclusive = false
        exclusiveEnded = false
        exclusiveStartFailed = false
        clearPending()
        clearEndOfStream()
        processedFormat = null
        processingActive = false
        playing = false
        hasConfigured = false
        bitPerfectAtConfigure = false
        notifyPlatformEffectsRequired(false)
        safeResetProcessor()
        try {
            enhancedDelegate.release()
        } catch (error: Exception) {
            Log.w(TAG, "Enhanced sink release failed", error)
        } catch (error: LinkageError) {
            Log.w(TAG, "Enhanced sink release linkage failed", error)
        }
        fallbackDelegate.release()
    }

    private fun recoverProcessingPath(reason: String, error: Throwable): Boolean =
        if (activeDelegate === enhancedDelegate && switchToProcessedPcm16(reason, error)) {
            true
        } else {
            switchToPlatformFallback(reason, error)
        }

    private fun switchToProcessedPcm16(reason: String, error: Throwable): Boolean {
        val format = configuredFormat ?: return false
        val floatFormat = processedFormat ?: return false
        disableFloatOutput(reason, error)
        clearPending()
        clearEndOfStream()
        safeFlush(enhancedDelegate)
        return try {
            processor.flush()
            // A runtime output recovery retries the current untouched source
            // buffer, so encoder delay must not be removed a second time.
            processor.beginStream(0, format.encoderPadding)
            fallbackDelegate.flush()
            fallbackDelegate.configure(floatFormat, 0, configuredOutputChannels)
            activeDelegate = fallbackDelegate
            processingActive = true
            configureUsbOutput(floatFormat, C.ENCODING_PCM_16BIT, configuredOutputChannels)
            notifyPlatformEffectsRequired(false)
            if (playing) fallbackDelegate.play()
            Log.w(TAG, "Recovered with native Float32 DSP and PCM16 AudioTrack output")
            true
        } catch (fallbackError: Exception) {
            Log.w(TAG, "Processed PCM16 recovery failed", fallbackError)
            false
        } catch (fallbackError: LinkageError) {
            Log.w(TAG, "Processed PCM16 recovery linkage failed", fallbackError)
            false
        }
    }

    private fun switchToPlatformFallback(reason: String, error: Throwable): Boolean {
        val format = configuredFormat ?: return false
        disableNativePath(reason, error)
        processingActive = false
        clearPending()
        clearEndOfStream()
        processedFormat = null
        safeResetProcessor()
        safeFlush(enhancedDelegate)
        return try {
            fallbackDelegate.flush()
            fallbackDelegate.configure(
                format,
                configuredBufferSize,
                configuredOutputChannels,
            )
            activeDelegate = fallbackDelegate
            notifyPlatformEffectsRequired(true)
            configureUsbOutput(format, format.pcmEncoding, configuredOutputChannels)
            if (playing) fallbackDelegate.play()
            true
        } catch (fallbackError: Exception) {
            Log.e(TAG, "PCM16 fallback configuration failed", fallbackError)
            false
        } catch (fallbackError: LinkageError) {
            Log.e(TAG, "PCM16 fallback linkage failed", fallbackError)
            false
        }
    }

    private fun disableFloatOutput(reason: String, error: Throwable) {
        floatOutputDisabled = true
        Log.w(TAG, "$reason; keeping native DSP through PCM16 compatibility output", error)
        PlaybackDiagnostics.counter(PlaybackDiagnostics.nativeFallbacks, TAG, reason)
    }

    private fun disableNativePath(reason: String, error: Throwable?) {
        nativePathDisabled = true
        if (error != null) {
            Log.w(TAG, "$reason; locking this player to conservative platform PCM", error)
        } else {
            Log.w(TAG, "$reason; locking this player to conservative platform PCM")
        }
        PlaybackDiagnostics.counter(PlaybackDiagnostics.nativeFallbacks, TAG, reason)
        safeResetProcessor()
    }

    private fun notifyPlatformEffectsRequired(required: Boolean) {
        try {
            onPlatformEffectsRequired(required)
        } catch (error: Exception) {
            Log.w(TAG, "Platform effect routing notification failed", error)
        } catch (error: LinkageError) {
            Log.w(TAG, "Platform effect routing linkage failed", error)
        }
    }

    private fun safeResetProcessor() {
        try {
            processor.reset()
        } catch (error: Exception) {
            Log.w(TAG, "Native processor reset failed", error)
        } catch (error: LinkageError) {
            Log.w(TAG, "Native processor reset linkage failed", error)
        }
    }

    private fun safeFlush(delegate: DefaultAudioSink) {
        try {
            delegate.flush()
        } catch (error: Exception) {
            Log.w(TAG, "Inactive audio sink flush failed", error)
        } catch (error: LinkageError) {
            Log.w(TAG, "Inactive audio sink flush linkage failed", error)
        }
    }

    private fun safeReset(delegate: DefaultAudioSink) {
        try {
            delegate.reset()
        } catch (error: Exception) {
            Log.w(TAG, "Enhanced audio sink reset failed", error)
        } catch (error: LinkageError) {
            Log.w(TAG, "Enhanced audio sink reset linkage failed", error)
        }
    }

    private fun canProcess(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.AUDIO_RAW &&
            format.sampleRate > 0 &&
            format.channelCount in 1..2 &&
            format.pcmEncoding in SUPPORTED_ENCODINGS

    private fun asFloatProbeFormat(format: Format): Format =
        format.buildUpon()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setSampleRate(processor.outputSampleRateFor(format.sampleRate))
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setEncoderDelay(0)
            .setEncoderPadding(0)
            .build()

    private fun asFloatFormat(
        format: Format,
        outputFormat: AudioProcessor.AudioFormat,
    ): Format = format.buildUpon()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setSampleRate(outputFormat.sampleRate)
        .setChannelCount(outputFormat.channelCount)
        .setPcmEncoding(outputFormat.encoding)
        .setEncoderDelay(0)
        .setEncoderPadding(0)
        .build()

    private fun clearPending() {
        pendingInputLimit = 0
        pendingOutput = null
        lastGainBuffer = null
        pendingPresentationTimeUs = 0L
        pendingAccessUnitCount = 0
        pendingOutputFrameCount = 0
    }

    private fun clearEndOfStream() {
        endOfStreamQueued = false
        endOfStreamOutput = null
        nextOutputPresentationTimeUs = 0L
    }

    private companion object {
        const val TAG = "NativeAudioSink"
        const val MICROS_PER_SECOND = 1_000_000L
        val SUPPORTED_ENCODINGS = setOf(
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT,
        )
    }
}
