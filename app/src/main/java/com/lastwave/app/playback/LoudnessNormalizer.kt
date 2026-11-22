package com.lastwave.app.playback

import androidx.media3.common.Format
import androidx.media3.extractor.metadata.flac.VorbisComment
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import kotlin.math.log10
import kotlin.math.pow

/**
 * Loudness-normalization mode. [OFF] (the default) leaves the signal
 * untouched; [TRACK] matches every track's own loudness; [ALBUM] keeps
 * intentional inter-track dynamics by using the album value, falling back
 * to the track value when the album value is absent (and vice versa).
 */
enum class LoudnessMode(val id: String) {
    OFF("off"),
    TRACK("track"),
    ALBUM("album");

    companion object {
        fun fromId(id: String?): LoudnessMode =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: OFF
    }
}

/** ReplayGain tag keys (case-insensitive on read). */
private const val KEY_TRACK_GAIN = "REPLAYGAIN_TRACK_GAIN"
private const val KEY_ALBUM_GAIN = "REPLAYGAIN_ALBUM_GAIN"
private const val KEY_TRACK_PEAK = "REPLAYGAIN_TRACK_PEAK"
private const val KEY_ALBUM_PEAK = "REPLAYGAIN_ALBUM_PEAK"

/** Target level, in LUFS, normalization aims for. */
const val LOUDNESS_TARGET_LUFS = -14f

/**
 * ReplayGain 2.0 gains bring a track to -18 LUFS. Without this offset tagged
 * files would play 4 dB quieter than measured streams at [LOUDNESS_TARGET_LUFS].
 */
private const val REPLAYGAIN_REFERENCE_LUFS = -18f

/** Hard clamp applied to the final correction, in dB. */
const val LOUDNESS_GAIN_CLAMP_DB = 15f

/** Allowed user preamp range, in dB. */
const val LOUDNESS_PREAMP_MAX_DB = 12f

/**
 * Parsed ReplayGain-1.0 values. Gains are in dB (adjustment that brings the
 * track/album to the reference level); peaks are linear amplitudes where
 * 1.0 equals 0 dBFS. Any field may be absent when the file carries no tags.
 */
data class ReplayGainTags(
    val trackGainDb: Float? = null,
    val albumGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
) {
    val hasGain: Boolean get() = trackGainDb != null || albumGainDb != null
}

/**
 * Pure ReplayGain-1.0 gain arithmetic: tag parsing plus the standard
 * correction computation (stored gain + preamp, clamped, peak-guarded).
 *
 * Only gain/peak tag math lives here — no loudness measurement, no filters,
 * no resampling. With no tags, or when [LoudnessMode.OFF] is selected, the
 * correction is exactly 0 dB so the signal passes through unchanged.
 */
object LoudnessNormalizer {

    /**
     * Parses a gain string such as "-7.5 dB" or "3.0". Returns null for
     * missing, non-numeric, non-finite, or implausible (> +/-51 dB) input.
     */
    fun parseDbString(value: String?): Float? {
        if (value.isNullOrBlank()) return null
        var text = value.trim().lowercase()
        if (text.endsWith("db")) text = text.removeSuffix("db").trim()
        val parsed = text.toFloatOrNull() ?: return null
        if (!parsed.isFinite()) return null
        if (parsed < -51f || parsed > 51f) return null
        return parsed
    }

    /**
     * Parses a peak string. The standard form is a linear amplitude such as
     * "0.831" (1.0 = 0 dBFS, values above 1.0 indicate over-peak); a dB form
     * such as "-1.6 dB" is also accepted and converted. Returns null for
     * missing, non-numeric, non-positive, or implausible input.
     */
    fun parsePeakString(value: String?): Float? {
        if (value.isNullOrBlank()) return null
        val text = value.trim()
        if (text.lowercase().endsWith("db")) {
            val db = parseDbString(text) ?: return null
            val linear = 10f.pow(db / 20f)
            if (!linear.isFinite() || linear <= 0f || linear > 100f) return null
            return linear
        }
        val linear = text.toFloatOrNull() ?: return null
        if (!linear.isFinite() || linear <= 0f || linear > 100f) return null
        return linear
    }

    /**
     * Parses ReplayGain tags from a plain key/value map (file tags, cached
     * metadata). Keys match case-insensitively; unknown keys are ignored.
     */
    fun parseFromMap(tags: Map<String, String>): ReplayGainTags {
        if (tags.isEmpty()) return ReplayGainTags()
        val normalized = HashMap<String, String>(tags.size)
        for ((key, value) in tags) {
            val clean = key.trim().uppercase()
            if (clean.isNotEmpty() && !normalized.containsKey(clean)) {
                normalized[clean] = value
            }
        }
        return ReplayGainTags(
            trackGainDb = parseDbString(normalized[KEY_TRACK_GAIN]),
            albumGainDb = parseDbString(normalized[KEY_ALBUM_GAIN]),
            trackPeak = parsePeakString(normalized[KEY_TRACK_PEAK]),
            albumPeak = parsePeakString(normalized[KEY_ALBUM_PEAK]),
        )
    }

    /**
     * Extracts ReplayGain tags from an ExoPlayer [Format]'s container
     * metadata where the demuxer exposes it: Vorbis comments (FLAC, Opus,
     * Vorbis) carry the REPLAYGAIN_* keys directly; ID3 TXXX frames carry
     * the key in the frame description. Returns empty tags when nothing is
     * exposed rather than null, so callers can feed the result straight
     * into [gainForTags].
     */
    fun parseFromFormat(format: Format?): ReplayGainTags {
        val metadata = format?.metadata ?: return ReplayGainTags()
        if (metadata.length() == 0) return ReplayGainTags()
        val tags = HashMap<String, String>()
        for (index in 0 until metadata.length()) {
            when (val entry = metadata.get(index)) {
                is VorbisComment -> {
                    val key = entry.key.trim()
                    if (key.isNotEmpty() && !tags.containsKey(key)) {
                        tags[key] = entry.value
                    }
                }
                is TextInformationFrame -> {
                    val key = if (entry.id == "TXXX") entry.description.orEmpty() else entry.id
                    val value = entry.value?.ifBlank { entry.values.firstOrNull().orEmpty() }.orEmpty()
                    if (key.isNotBlank() && value.isNotBlank() && !tags.containsKey(key)) {
                        tags[key] = value
                    }
                }
                else -> Unit
            }
        }
        return parseFromMap(tags)
    }

    /**
     * Computes the correction for parsed tags under [mode].
     *
     * TRACK uses the track gain (falling back to album gain); ALBUM uses
     * the album gain (falling back to track gain). The stored gain targets
     * the ReplayGain reference, so the correction is that gain moved to
     * [LOUDNESS_TARGET_LUFS], plus [preampDb], clamped to +/- [LOUDNESS_GAIN_CLAMP_DB];
     * [applyPeakGuard] then lowers it if the corrected peak would exceed
     * 0 dBFS while no limiter is engaged. Returns 0 dB when normalization
     * is off or no usable gain tag is present.
     */
    fun gainForTags(
        tags: ReplayGainTags?,
        mode: LoudnessMode,
        preampDb: Float = 0f,
        limiterEngaged: Boolean = false,
    ): Float {
        if (mode == LoudnessMode.OFF || tags == null || !tags.hasGain) return 0f
        val selectedGain = when (mode) {
            LoudnessMode.ALBUM -> tags.albumGainDb ?: tags.trackGainDb
            else -> tags.trackGainDb ?: tags.albumGainDb
        } ?: return 0f
        val selectedPeak = when (mode) {
            LoudnessMode.ALBUM -> tags.albumPeak ?: tags.trackPeak
            else -> tags.trackPeak ?: tags.albumPeak
        }
        val preamp = sanitizePreamp(preampDb)
        val referenceOffset = LOUDNESS_TARGET_LUFS - REPLAYGAIN_REFERENCE_LUFS
        val clamped = (selectedGain + referenceOffset + preamp).coerceIn(-LOUDNESS_GAIN_CLAMP_DB, LOUDNESS_GAIN_CLAMP_DB)
        return applyPeakGuard(clamped, selectedPeak, limiterEngaged)
    }

    /**
     * Computes the correction from a measured integrated loudness:
     * gain = target - measured, plus [preampDb], clamped to +/-
     * [LOUDNESS_GAIN_CLAMP_DB], then peak-guarded. Returns 0 dB for
     * non-finite input.
     */
    fun gainForMeasuredLufs(
        measuredLufs: Float,
        targetLufs: Float = LOUDNESS_TARGET_LUFS,
        preampDb: Float = 0f,
        peakLinear: Float? = null,
        limiterEngaged: Boolean = false,
    ): Float {
        if (!measuredLufs.isFinite() || !targetLufs.isFinite()) return 0f
        val clamped = (targetLufs - measuredLufs + sanitizePreamp(preampDb))
            .coerceIn(-LOUDNESS_GAIN_CLAMP_DB, LOUDNESS_GAIN_CLAMP_DB)
        return applyPeakGuard(clamped, peakLinear, limiterEngaged)
    }

    /**
     * Peak-limit guard: when the corrected peak (linear peak times the
     * applied linear gain) would exceed 0 dBFS, the gain is lowered to the
     * highest value that keeps the peak at or below full scale. Skipped
     * when [limiterEngaged] is true or no valid peak is available. The
     * guard only ever reduces gain, never raises it.
     */
    fun applyPeakGuard(gainDb: Float, peakLinear: Float?, limiterEngaged: Boolean): Float {
        if (limiterEngaged) return gainDb
        val peak = peakLinear ?: return gainDb
        if (!peak.isFinite() || peak <= 0f) return gainDb
        if (!gainDb.isFinite()) return 0f
        val ceilingDb = 20f * log10(1f / peak)
        if (!ceilingDb.isFinite()) return gainDb
        return minOf(gainDb, ceilingDb)
    }

    private fun sanitizePreamp(preampDb: Float): Float =
        if (preampDb.isFinite()) {
            preampDb.coerceIn(-LOUDNESS_PREAMP_MAX_DB, LOUDNESS_PREAMP_MAX_DB)
        } else {
            0f
        }
}
