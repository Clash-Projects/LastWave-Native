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
    val appRateHz: Int,
    val platformRateHz: Int,
    val dacName: String?,
    val driftPpm: Double?,
    val glitchCount: Long,
    val isPlaying: Boolean,
    val usbExclusiveActive: Boolean = false,
) {
    companion object {
        fun initial() = SignalPathReport(
            checks = listOf(
                PathCheck(R.string.signal_stream, R.string.signal_detail_no_stream, passed = false),
            ),
            bitPerfect = false,
            sourceLabel = "—",
            sourceRateHz = null,
            appRateHz = 0,
            platformRateHz = 0,
            dacName = null,
            driftPpm = null,
            glitchCount = 0L,
            isPlaying = false,
            usbExclusiveActive = false,
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
    val bitDepth = i.sourceBitDepth?.takeIf { it > 0 }
    if (src != null) {
        if (bitDepth != null) {
            checks += PathCheck(
                R.string.signal_label_source,
                R.string.signal_detail_src,
                listOf(i.sourceLabel, bitDepth, src),
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
            passed = false,
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
    if (i.usbExclusiveActive) {
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
    when {
        !i.usbExclusiveActive -> checks += PathCheck(
            R.string.signal_label_output,
            R.string.signal_detail_route_unverified,
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

    return SignalPathReport(
        checks = checks,
        bitPerfect = i.usbExclusiveActive &&
            (i.exclusiveClockMatched || i.routeVerified) &&
            checks.all { it.passed },
        sourceLabel = i.sourceLabel,
        sourceRateHz = src,
        appRateHz = app ?: 0,
        platformRateHz = plat ?: 0,
        dacName = dac?.name,
        driftPpm = i.driftPpm,
        glitchCount = i.glitchCount,
        isPlaying = i.isPlaying,
        usbExclusiveActive = i.usbExclusiveActive,
    )
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
            // Seek (or a >1.5 s discontinuity): re-baseline, don't pollute PPM.
            if (posDelta < -250L) glitchCount++
            driftPpm = null
            return null
        }
        if (wallDelta >= 800L && posDelta <= 0L) glitchCount++
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
        if (!playing || rateHz <= 0 || framesWritten < 0L) {
            exclusiveOriginFrames = -1L
            return driftPpm
        }
        if (exclusiveOriginFrames < 0L || framesWritten < exclusiveOriginFrames) {
            exclusiveOriginFrames = framesWritten
            exclusiveOriginWallMs = wallMs
            return driftPpm
        }
        val wallDelta = wallMs - exclusiveOriginWallMs
        if (wallDelta < 800L) return driftPpm
        val audioMs = (framesWritten - exclusiveOriginFrames).toDouble() * 1000.0 / rateHz.toDouble()
        // Write() still in flight: frames have not caught wall yet. Keep the
        // last reading instead of publishing −1e6 PPM or wiping the estimate.
        if (audioMs < wallDelta * 0.25 && wallDelta < 4_000L) {
            return driftPpm
        }
        val instant = ((audioMs - wallDelta) / wallDelta * 1_000_000.0)
            .coerceIn(-50_000.0, 50_000.0)
        driftPpm = if (driftPpm == null) instant else driftPpm!! * 0.85 + instant * 0.15
        return driftPpm
    }
}
