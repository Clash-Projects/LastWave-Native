package com.lastwave.app.playback

import android.media.AudioManager
import android.media.AudioTrack
import com.lastwave.app.R

/**
 * Verified USB-DAC signal-path model.
 *
 * A matching nominal mixer rate does not prove bit-perfect output. USB mixer
 * configuration and actual routing must be distinguished from device preference.
 */

/** A USB audio peripheral visible to the platform mixer. */
data class UsbDacInfo(
    val name: String,
    val vendorId: Int = -1,
    val productId: Int = -1,
    /** Sample rates from the platform audio descriptor; empty = undisclosed. */
    val sampleRatesHz: List<Int> = emptyList(),
    val channelCounts: List<Int> = emptyList(),
    /** Direct USB access granted via [UsbDacMonitor.requestPermission]. */
    val usbPermissionGranted: Boolean = false,
    /** True when a USB audio peripheral exists on the USB bus itself. */
    val hasUsbPeripheral: Boolean = false,
    /** [android.media.AudioDeviceInfo.getId] used for preferred-device routing. */
    val deviceId: Int = -1,
)

/** Raw inputs for [evaluateSignalPath]; snapshot on the main thread. */
data class SignalPathInput(
    val sourceLabel: String,
    val sourceRateHz: Int?,
    val sourceBitDepth: Int?,
    val isLossless: Boolean,
    /** Actual AudioTrack rate the app opened (0 = unresolved). */
    val appOutputRateHz: Int,
    /** Platform mixer rate ([AudioTrack.getNativeOutputSampleRate]). */
    val platformMixerRateHz: Int,
    val dspBypassEnabled: Boolean,
    val crossfadeMixing: Boolean,
    val speed: Float,
    /** Effective app gain reaching AudioTrack (1 = unity; <1 during duck/fade). */
    val appVolume: Float,
    val systemVolume: Int,
    val systemVolumeMax: Int,
    /** True when volume is fixed/hardware-controlled (no digital scaling). */
    val systemVolumeFixed: Boolean,
    val dac: UsbDacInfo?,
    val routedToDac: Boolean,
    /** True only when the platform granted BIT_PERFECT on the DAC route. */
    val routeVerified: Boolean,
    val driftPpm: Double?,
    val glitchCount: Long,
    val isPlaying: Boolean,
    val platformBitPerfectConfigured: Boolean = false,
    /** True while usbdevfs exclusive output owns the DAC clock. */
    val usbExclusiveActive: Boolean = false,
    /** GET_CUR sample rate matches the requested exclusive rate. */
    val exclusiveClockMatched: Boolean = false,
    /** UAC Feature Unit volume is in use (PCM payload unscaled). */
    val exclusiveHardwareVolume: Boolean = false,
    /** Last exclusive open failure, if any (openDevice/alt-setting/etc). */
    val exclusiveFailureReason: String? = null,
    /** True when exclusive USB resampled through soxr because DAC clock unsupported. */
    val clockFallbackResampled: Boolean = false,
    /** Fork: AAudio exclusive (MMAP) stream on the phone's own output, when playing. */
    val internalDirect: InternalDirectSession? = null,
    /** Fork: the user asked for the internal direct output on this route. */
    val internalDirectWanted: Boolean = false,
    /** Fork: why the internal direct output did not engage, when wanted. */
    val internalDirectFailure: InternalDirectReason? = null,
    /** Bit-Perfect (or a bypass mode) requested by the user: the badge says "Mixer" when it is not achieved. */
    val bypassRequested: Boolean = false,
    /** USB wire bit depth negotiated (e.g. 16, 24, 32). */
    val wireBits: Int = 0,
)

/**
 * One measured check. Label/detail are string-resource IDs resolved in the UI
 * ([SignalPathDialog]) so the whole signal-path screen follows the app
 * language; [detailArgs] are plain numbers/names needing no translation.
 */
data class PathCheck(
    val labelRes: Int,
    val detailRes: Int,
    val detailArgs: List<Any> = emptyList(),
    val passed: Boolean,
)

data class SignalPathReport(
    val checks: List<PathCheck>,
    val bitPerfect: Boolean,
    val sourceLabel: String,
    val sourceRateHz: Int?,
    val sourceBitDepth: Int? = null,
    val appRateHz: Int,
    val platformRateHz: Int,
    val dacName: String?,
    val driftPpm: Double?,
    val glitchCount: Long,
    val isPlaying: Boolean,
    val usbExclusiveActive: Boolean = false,
    val clockFallbackResampled: Boolean = false,
    /** Fork: samples reach the HAL via the internal exclusive stream. */
    val internalDirectActive: Boolean = false,
    /**
     * Fork verdict for the internal route: every check passed, so the decoded
     * samples enter the HAL buffer unchanged. Deliberately NOT [bitPerfect]:
     * a vendor DSP after the HAL cannot be verified from Android.
     */
    val directToHal: Boolean = false,
    val internalSession: InternalDirectSession? = null,
    val bypassRequested: Boolean = false,
    // Poweramp pipeline fields:
    val outputEngineName: String = "Android AudioTrack",
    val outputRateHz: Int = 0,
    val outputBits: Int = 16,
    val outputFlags: List<String> = emptyList(),
    val outputLatencyMs: Int = 50,
    val volumeMode: String = "Software Volume",
    val volumeDetail: String = "0.0 dB",
    val dspSummary: String = "Float32 / PCM",
    val fidelityScore: Int = 100,
    val dspScore: String = "—",
) {
    companion object {
        fun initial() = SignalPathReport(
            checks = listOf(
                PathCheck(R.string.signal_stream, R.string.signal_detail_no_stream, passed = false),
            ),
            bitPerfect = false,
            sourceLabel = "—",
            sourceRateHz = null,
            sourceBitDepth = null,
            appRateHz = 0,
            platformRateHz = 0,
            dacName = null,
            driftPpm = null,
            glitchCount = 0L,
            isPlaying = false,
            usbExclusiveActive = false,
            clockFallbackResampled = false,
        )
    }
}

/** Platform mixer rate for music streams; 0 when unreadable. */
fun AudioManager.mixerRateHz(): Int = runCatching {
    AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
}.getOrDefault(0)

fun evaluateSignalPath(i: SignalPathInput): SignalPathReport {
    val checks = mutableListOf<PathCheck>()

    // 1 — Source format. Exclusive usbdevfs is clocked by the decoded PCM
    // rate, so a missing container/tag rate still uses the exclusive output
    // rate rather than failing gold as "unknown".
    val src = i.sourceRateHz?.takeIf { it > 0 }
        ?: i.appOutputRateHz.takeIf { it > 0 && i.usbExclusiveActive }
    val labelBitDepth = Regex("""(?:^|[^\d])(16|24|32)\s*(?:[-_]bit)?\s*[/]""", RegexOption.IGNORE_CASE)
        .find(i.sourceLabel)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val rawBitDepth = labelBitDepth ?: i.sourceBitDepth?.takeIf { it > 0 }
    val bitDepth = when {
        src != null && src > 192_000 -> 32
        src != null && src > 48_000 -> 24
        rawBitDepth != null && rawBitDepth > 16 -> rawBitDepth
        i.sourceLabel.contains("HI-RES", ignoreCase = true) || i.sourceLabel.contains("HI_RES", ignoreCase = true) -> 24
        rawBitDepth != null -> rawBitDepth
        else -> null
    }
    val sourceLabel = if (bitDepth != null && src != null && src > 48_000 && i.sourceLabel.startsWith("16/")) {
        i.sourceLabel.replaceFirst("16/", "$bitDepth/")
    } else {
        i.sourceLabel
    }
    if (src != null) {
        if (bitDepth != null) {
            checks += PathCheck(
                R.string.signal_label_source,
                R.string.signal_detail_src,
                listOf(sourceLabel, bitDepth, src),
                passed = true,
            )
        } else {
            checks += PathCheck(
                R.string.signal_label_source,
                R.string.signal_detail_src_nobit,
                listOf(i.sourceLabel, src),
                passed = true,
            )
        }
    } else {
        checks += PathCheck(
            R.string.signal_label_source,
            R.string.signal_detail_src_unknown,
            passed = false,
        )
    }

    // 2 — App resampler (libsoxr stage inside the native pipeline).
    val app = i.appOutputRateHz.takeIf { it > 0 }
    if (src != null && app != null) {
        if (app == src) {
            checks += PathCheck(
                R.string.signal_label_resampler,
                R.string.signal_detail_resample_bypass,
                listOf(src),
                passed = true,
            )
        } else if (i.usbExclusiveActive && i.clockFallbackResampled) {
            checks += PathCheck(
                R.string.signal_label_resampler,
                R.string.signal_detail_resample_dac_unsupported,
                listOf(src, app),
                passed = false,
            )
        } else if (i.internalDirect != null) {
            checks += PathCheck(
                R.string.signal_label_resampler,
                R.string.signal_detail_internal_resampled,
                listOf(src, app),
                passed = false,
            )
        } else {
            checks += PathCheck(
                R.string.signal_label_resampler,
                R.string.signal_detail_resample_active,
                listOf(src, app),
                passed = false,
            )
        }
    } else {
        checks += PathCheck(
            R.string.signal_label_resampler,
            R.string.signal_detail_resample_unresolved,
            passed = false,
        )
    }

    // 3 — DSP chain (native DSP + AudioFX + crossfade mixer).
    if (!i.dspBypassEnabled) {
        checks += PathCheck(
            R.string.signal_label_dsp,
            R.string.signal_detail_dsp_off,
            passed = false,
        )
    } else if (i.crossfadeMixing) {
        checks += PathCheck(
            R.string.signal_label_dsp,
            R.string.signal_detail_dsp_crossfade,
            passed = false,
        )
    } else {
        checks += PathCheck(
            R.string.signal_label_dsp,
            R.string.signal_detail_dsp_bypass,
            passed = true,
        )
    }

    // 4 — Tempo/pitch (resampling by definition when != 1x).
    if (i.speed == 1f) {
        checks += PathCheck(
            R.string.signal_label_tempo,
            R.string.signal_detail_tempo_ok,
            passed = true,
        )
    } else {
        checks += PathCheck(
            R.string.signal_label_tempo,
            R.string.signal_detail_tempo_active,
            listOf(i.speed.toString()),
            passed = false,
        )
    }

    // 5 — App gain staging (fades, ducking and software volume all scale here).
    // Under Poweramp definition, volume override does not fail bit-perfect.
    if (i.usbExclusiveActive && i.exclusiveHardwareVolume) {
        checks += PathCheck(
            R.string.signal_label_appvol,
            R.string.signal_detail_usb_hw_vol,
            passed = true,
        )
    } else if (i.appVolume == 1f) {
        checks += PathCheck(
            R.string.signal_label_appvol,
            R.string.signal_detail_gain_unity,
            passed = true,
        )
    } else {
        checks += PathCheck(
            R.string.signal_label_appvol,
            R.string.signal_detail_gain_scaled,
            listOf(i.appVolume.toString()),
            passed = true,
        )
    }

    // 6 — System gain staging (digital attenuation happens before the DAC,
    // unless the route uses fixed/hardware volume, which never scales PCM).
    if (i.usbExclusiveActive && i.exclusiveHardwareVolume) {
        checks += PathCheck(
            R.string.signal_label_sysvol,
            R.string.signal_detail_usb_hw_vol,
            passed = true,
        )
    } else if (i.usbExclusiveActive) {
        checks += PathCheck(
            R.string.signal_label_sysvol,
            R.string.signal_detail_usb_sysvol_unused,
            passed = true,
        )
    } else if (i.systemVolumeFixed) {
        checks += PathCheck(
            R.string.signal_label_sysvol,
            R.string.signal_detail_sysvol_hw,
            passed = true,
        )
    } else if (i.systemVolumeMax > 0 && i.systemVolume >= i.systemVolumeMax) {
        checks += PathCheck(
            R.string.signal_label_sysvol,
            R.string.signal_detail_sysvol_max,
            passed = true,
        )
    } else if (i.systemVolumeMax > 0) {
        checks += PathCheck(
            R.string.signal_label_sysvol,
            R.string.signal_detail_sysvol_scaled,
            listOf(i.systemVolume, i.systemVolumeMax),
            passed = false,
        )
    } else {
        checks += PathCheck(
            R.string.signal_label_sysvol,
            R.string.signal_detail_unreadable,
            passed = false,
        )
    }

    // 7 — Platform mixer. Gold requires exclusive usbdevfs; an Android
    // mixer BIT_PERFECT grant is never treated as verified bit-perfect.
    val plat = i.platformMixerRateHz.takeIf { it > 0 }
    val internal = i.internalDirect
    if (internal != null) {
        checks += when {
            internal.backend == InternalBackend.DIRECT_TRACK && internal.directVerified -> PathCheck(
                R.string.signal_label_mixer,
                R.string.signal_detail_internal_mixer_direct_track,
                passed = true,
            )
            internal.backend == InternalBackend.DIRECT_TRACK -> PathCheck(
                R.string.signal_label_mixer,
                R.string.signal_detail_internal_mixer_direct_unverified,
                passed = false,
            )
            internal.mmap -> PathCheck(
                R.string.signal_label_mixer,
                R.string.signal_detail_internal_mixer_bypassed,
                passed = true,
            )
            else -> PathCheck(
                R.string.signal_label_mixer,
                R.string.signal_detail_internal_mixer_bypassed_nommap,
                passed = true,
            )
        }
    } else if (i.usbExclusiveActive) {
        checks += PathCheck(
            R.string.signal_label_mixer,
            R.string.signal_detail_usb_exclusive,
            passed = true,
        )
    } else if (i.platformBitPerfectConfigured) {
        checks += PathCheck(
            R.string.signal_label_mixer,
            R.string.signal_detail_mixer_not_exclusive,
            passed = false,
        )
    } else if (app != null && plat != null) {
        if (plat == app) {
            checks += PathCheck(
                R.string.signal_label_mixer,
                R.string.signal_detail_bit_perfect_unavailable,
                listOf(plat),
                passed = false,
            )
        } else {
            checks += PathCheck(
                R.string.signal_label_mixer,
                R.string.signal_detail_mixer_src,
                listOf(app, plat),
                passed = false,
            )
        }
    } else {
        checks += PathCheck(
            R.string.signal_label_mixer,
            R.string.signal_detail_mixer_unreadable,
            passed = false,
        )
    }

    // Fork — internal direct output: the route is the phone itself, so the
    // USB DAC rows below do not apply. Report the wire format the endpoint
    // actually runs at and whether it can carry every source bit.
    if (internal != null) {
        val deviceFormat = InternalDirectStream.labelOf(internal.deviceEncoding)
        val routeLabel = internal.routeName.ifBlank { internal.route.name.lowercase() }
        val sourceFormat = "${internal.sourceBits}-bit"
        when {
            !internal.formatConverted -> checks += PathCheck(
                R.string.signal_label_output,
                R.string.signal_detail_internal_route,
                listOf(routeLabel, internal.deviceRateHz, deviceFormat),
                passed = true,
            )
            internal.formatLossless -> checks += PathCheck(
                R.string.signal_label_output,
                R.string.signal_detail_internal_route_widened,
                listOf(routeLabel, internal.deviceRateHz, sourceFormat, deviceFormat),
                passed = true,
            )
            else -> checks += PathCheck(
                R.string.signal_label_output,
                R.string.signal_detail_internal_route_lossy,
                listOf(routeLabel, internal.deviceRateHz, deviceFormat),
                passed = false,
            )
        }
        if (internal.route == InternalRoute.BLUETOOTH) {
            // The bypass ends at the DSP: the Bluetooth codec re-encodes after it.
            checks += PathCheck(
                R.string.signal_label_bt_codec,
                R.string.signal_detail_bt_codec,
                passed = false,
            )
        }
        return finishReport(i, checks, src, app, plat, dac = i.dac)
    }
    if (!i.usbExclusiveActive && i.internalDirectFailure != null) {
        checks += PathCheck(
            R.string.signal_label_internal,
            internalFailureDetail(i.internalDirectFailure),
            passed = false,
        )
    }

    // 8 — Output route must be the USB DAC.
    val dac = i.dac
    when {
        dac == null -> checks += PathCheck(
            R.string.signal_label_output,
            R.string.signal_detail_no_dac,
            passed = false,
        )
        !i.routedToDac -> checks += PathCheck(
            R.string.signal_label_output,
            R.string.signal_detail_not_routed,
            listOf(dac.name),
            passed = false,
        )
        i.usbExclusiveActive ->
            checks += PathCheck(
                R.string.signal_label_output,
                R.string.signal_detail_usb_exclusive_route,
                listOf(dac.name),
                passed = true,
            )
        app != null && dac.sampleRatesHz.isNotEmpty() && app in dac.sampleRatesHz ->
            checks += PathCheck(
                R.string.signal_label_output,
                R.string.signal_detail_usb_ok_accepts,
                listOf(dac.name, app),
                passed = true,
            )
        app != null && dac.sampleRatesHz.isNotEmpty() ->
            checks += PathCheck(
                R.string.signal_label_output,
                R.string.signal_detail_usb_ok_rejects,
                listOf(dac.name, app),
                passed = false,
            )
        else -> checks += PathCheck(
            R.string.signal_label_output,
            R.string.signal_detail_usb_ok_plain,
            listOf(dac.name),
            passed = false,
        )
    }

    // 9 — Exclusive clock verification. Mixer routing is never gold.
    // When exclusive failed to start, name the reason (device/permission/
    // alt-setting/stream start) instead of the generic unverified line so
    // the failure is diagnosable from the dialog alone.
    when {
        !i.usbExclusiveActive && i.exclusiveFailureReason != null -> checks += PathCheck(
            R.string.signal_label_output,
            R.string.signal_detail_exclusive_failed,
            listOf(i.exclusiveFailureReason),
            passed = false,
        )
        !i.usbExclusiveActive -> checks += PathCheck(
            R.string.signal_label_output,
            R.string.signal_detail_route_unverified,
            passed = false,
        )
        i.clockFallbackResampled -> checks += PathCheck(
            R.string.signal_label_output,
            R.string.signal_detail_usb_clock_fallback,
            listOf(src ?: 0, app ?: 0),
            passed = false,
        )
        i.exclusiveClockMatched || i.routeVerified -> checks += PathCheck(
            R.string.signal_label_output,
            R.string.signal_detail_usb_clock_ok,
            passed = true,
        )
        else -> checks += PathCheck(
            R.string.signal_label_output,
            R.string.signal_detail_usb_clock_mismatch,
            passed = false,
        )
    }

    return finishReport(i, checks, src, app, plat, dac)
}

private fun finishReport(
    i: SignalPathInput,
    checks: List<PathCheck>,
    src: Int?,
    app: Int?,
    plat: Int?,
    dac: UsbDacInfo?,
): SignalPathReport {
    val internal = i.internalDirect
    val isBp = internal == null &&
        i.usbExclusiveActive &&
        !i.clockFallbackResampled &&
        (i.exclusiveClockMatched || i.routeVerified) &&
        checks.all { it.passed }

    val engine = when {
        i.usbExclusiveActive -> "USB Exclusive Output"
        internal != null -> "Direct HAL (AAudio MMAP)"
        i.platformBitPerfectConfigured -> "Android Bit-Perfect Output"
        else -> "Android AudioTrack"
    }

    val outRate = when {
        i.usbExclusiveActive -> app ?: src ?: 48000
        internal != null -> internal.deviceRateHz
        else -> plat ?: app ?: 48000
    }

    val outBits = when {
        i.wireBits > 0 -> i.wireBits
        i.sourceBitDepth != null && i.sourceBitDepth > 0 -> i.sourceBitDepth
        internal != null -> internal.sourceBits
        else -> 32
    }

    val flags = mutableListOf<String>()
    if (src != null && app != null && src == app) flags += "FOLLOW_SR"
    flags += "TRACK_PLAYBACK"
    if (!i.exclusiveHardwareVolume) flags += "NEEDS_VOL_UI"
    flags += "PIPELINE64"
    if (isBp) flags += "PERFECTBITPERFECT"
    if (i.usbExclusiveActive) flags += "EXCLUSIVE"
    if (internal != null) flags += "DIRECT_HAL"
    if (i.isLossless) flags += "LOSSLESS"

    val volMode = when {
        i.usbExclusiveActive && i.exclusiveHardwareVolume -> "Hardware Volume"
        i.usbExclusiveActive -> "Software Volume"
        i.systemVolumeFixed -> "Hardware Volume"
        else -> "System Volume"
    }

    val volDetail = when {
        i.usbExclusiveActive && i.exclusiveHardwareVolume -> "Hardware Volume • 0.0 dB"
        i.appVolume == 1f -> "0.0 dB (Unity Gain)"
        else -> {
            val db = 20.0 * kotlin.math.log10(i.appVolume.toDouble().coerceAtLeast(0.0001))
            String.format(java.util.Locale.ROOT, "%.1f dB (%.3fx)", db, i.appVolume)
        }
    }

    val dspSum = if (i.dspBypassEnabled && !i.crossfadeMixing && i.speed == 1f) {
        "Float64/Float32 • No DSP"
    } else {
        "Float32 • Active DSP"
    }

    val fidelity = when {
        isBp -> 100
        i.usbExclusiveActive -> 85
        internal != null -> 80
        else -> 65
    }

    val dspScore = if (i.dspBypassEnabled && !i.crossfadeMixing && i.speed == 1f) "—" else "21"

    return SignalPathReport(
        checks = checks,
        bitPerfect = isBp,
        sourceLabel = i.sourceLabel,
        sourceRateHz = src,
        sourceBitDepth = i.sourceBitDepth,
        appRateHz = app ?: 0,
        platformRateHz = plat ?: 0,
        dacName = dac?.name,
        driftPpm = i.driftPpm,
        glitchCount = i.glitchCount,
        isPlaying = i.isPlaying,
        usbExclusiveActive = i.usbExclusiveActive,
        clockFallbackResampled = i.clockFallbackResampled || internal?.resampled == true,
        internalDirectActive = internal != null,
        directToHal = internal != null && checks.all { it.passed },
        internalSession = internal,
        bypassRequested = i.bypassRequested,
        outputEngineName = engine,
        outputRateHz = outRate,
        outputBits = outBits,
        outputFlags = flags,
        outputLatencyMs = if (i.usbExclusiveActive) 50 else 75,
        volumeMode = volMode,
        volumeDetail = volDetail,
        dspSummary = dspSum,
        fidelityScore = fidelity,
        dspScore = dspScore,
    )
}

/** One sentence per refusal reason, shown as the "Internal direct output" row. */
fun internalFailureDetail(reason: InternalDirectReason): Int = when (reason) {
    InternalDirectReason.BLUETOOTH -> R.string.internal_reason_bluetooth
    InternalDirectReason.USB -> R.string.internal_reason_usb
    InternalDirectReason.EXCLUSIVE_REFUSED -> R.string.internal_reason_exclusive_refused
    InternalDirectReason.NO_AAUDIO -> R.string.internal_reason_no_aaudio
    InternalDirectReason.ANDROID_TOO_OLD -> R.string.internal_reason_android_old
    InternalDirectReason.NATIVE_MISSING -> R.string.internal_reason_native_missing
    InternalDirectReason.CHECKING -> R.string.internal_reason_checking
    InternalDirectReason.AVAILABLE -> R.string.internal_reason_available
    InternalDirectReason.ACTIVE -> R.string.internal_reason_active
    InternalDirectReason.MIXED_BY_ANDROID -> R.string.internal_reason_mixed
}

/**
 * Playback-clock health from ExoPlayer position vs wall clock. Measures the
 * effective stream rate (reveals resampling/speed anomalies) and counts
 * glitches (backward jumps/stalls while playing). Timing-neutral: it never
 * touches audio, it only observes.
 */
class StreamHealthTracker {
    var driftPpm: Double? = null
        private set
    var glitchCount: Long = 0L
        private set

    private var lastPositionMs = -1L
    private var lastWallMs = 0L
    private var exclusiveOriginFrames = -1L
    private var exclusiveOriginWallMs = 0L

    fun reset() {
        driftPpm = null
        lastPositionMs = -1L
        lastWallMs = 0L
        exclusiveOriginFrames = -1L
        exclusiveOriginWallMs = 0L
    }

    /**
     * Returns the smoothed drift in PPM, or null until enough data exists.
     * Seeks re-baseline silently; backward jumps/stalls while playing count
     * as glitches.
     */
    fun sample(positionMs: Long, wallMs: Long, playing: Boolean): Double? {
        if (!playing || positionMs < 0) {
            lastPositionMs = -1L
            if (!playing) driftPpm = null
            return driftPpm
        }
        if (lastPositionMs < 0) {
            lastPositionMs = positionMs
            lastWallMs = wallMs
            return driftPpm
        }
        val wallDelta = wallMs - lastWallMs
        val posDelta = positionMs - lastPositionMs
        lastPositionMs = positionMs
        lastWallMs = wallMs
        if (wallDelta < 400L || wallDelta > 3_000L) return driftPpm
        if (kotlin.math.abs(posDelta - wallDelta) > 1_500L) {
            // DASH/FLAC timelines jump by seconds while audio keeps playing.
            // Re-baseline (lastPosition already moved) but keep the last PPM.
            // Clearing it here left lossless stuck on "measuring…".
            if (posDelta < -250L) glitchCount++
            return driftPpm
        }
        if (posDelta <= 0L) {
            if (wallDelta >= 800L) glitchCount++
            return driftPpm
        }
        val instant = (posDelta - wallDelta).toDouble() / wallDelta * 1_000_000.0
        driftPpm = if (driftPpm == null) instant else driftPpm!! * 0.85 + instant * 0.15
        return driftPpm
    }

    /**
     * Exclusive usbdevfs clock vs wall. Do not reuse [sample]: lossless
     * handleBuffer blocks for seconds inside USB write(), so the Java-visible
     * frame count jumps >1.5 s and the ExoPlayer seek detector stuck drift
     * on "measuring…". Compare cumulative frames to wall from a baseline.
     */
    fun sampleExclusive(framesWritten: Long, rateHz: Int, wallMs: Long, playing: Boolean): Double? {
        if (!playing) {
            exclusiveOriginFrames = -1L
            driftPpm = null
            return null
        }
        if (rateHz <= 0 || framesWritten < 0L) {
            exclusiveOriginFrames = -1L
            return driftPpm
        }
        if (exclusiveOriginFrames < 0L || framesWritten < exclusiveOriginFrames) {
            exclusiveOriginFrames = framesWritten
            exclusiveOriginWallMs = wallMs
            return driftPpm
        }
        val wallDelta = wallMs - exclusiveOriginWallMs
        if (wallDelta < 500L) return driftPpm
        val audioMs = (framesWritten - exclusiveOriginFrames).toDouble() * 1000.0 / rateHz.toDouble()
        if (audioMs < 200.0) return driftPpm
        val instant = ((audioMs - wallDelta) / wallDelta * 1_000_000.0)
            .coerceIn(-50_000.0, 50_000.0)
        driftPpm = if (driftPpm == null) instant else driftPpm!! * 0.85 + instant * 0.15
        return driftPpm
    }
}
