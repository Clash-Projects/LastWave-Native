package com.lastwave.app.playback

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Format
import com.lastwave.app.data.local.EQ_BAND_FREQS_HZ
import com.lastwave.app.data.local.EQ_MAX_GAIN_DB
import com.lastwave.app.data.local.EqualizerPreferences
import com.lastwave.app.data.local.EqualizerPresets
import com.lastwave.app.data.local.EqualizerSettings
import com.lastwave.app.data.local.SettingsPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Last-resort Android AudioFX layer for devices where native decoded-PCM DSP
 * cannot run. It is mutually exclusive with [NativeProcessingAudioSink]: the
 * platform effects are released whenever native Float32 processing is active,
 * preventing doubled EQ, gain, or limiting.
 *
 * Loudness normalization (ReplayGain tags via [LoudnessNormalizer]) is applied
 * here as a uniform tone-stage offset on top of the EQ curve. It stays OFF by
 * default and is bypassed whenever bit-perfect or USB-exclusive output is
 * active. Per-track feed contract for the player (read-only, no player
 * restructuring required): on track change, call [setReplayGainFromFormat]
 * with the new track's ExoPlayer [Format] (or [setReplayGainTags] with tags
 * parsed from file metadata via [LoudnessNormalizer.parseFromMap]); when the
 * direct USB path takes over, call [setUsbExclusiveActive].
 */
@Singleton
class AudioEffectsEngine @Inject constructor(
    equalizerPreferences: EqualizerPreferences,
    settingsPreferences: SettingsPreferences,
    applicationScope: CoroutineScope,
    // Optional so manually constructed standby engines keep compiling without
    // the prefs binding; null simply leaves loudness normalization OFF.
    loudnessPrefs: LoudnessPrefs? = null,
) {
    private val effectMutex = Mutex()
    private val applyRequests = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var requestedSessionId = C.AUDIO_SESSION_ID_UNSET
    @Volatile private var fallbackRequired = false
    @Volatile private var equalizerSettings = EqualizerSettings()
    @Volatile private var studioClarityEnabled = false
    @Volatile private var bitPerfectActive = false
    @Volatile private var usbExclusiveActive = false
    @Volatile private var loudnessMode: LoudnessMode = LoudnessMode.OFF
    @Volatile private var loudnessPreampDb = 0f
    @Volatile private var replayGainTags: ReplayGainTags? = null
    @Volatile private var loudnessGainDb = 0f

    private var attachedSessionId = C.AUDIO_SESSION_ID_UNSET
    private var toneEffect: ToneEffect? = null

    init {
        applicationScope.launch(Dispatchers.Default) {
            for (ignored in applyRequests) {
                effectMutex.withLock { applyInternal() }
            }
        }
        applicationScope.launch(Dispatchers.Default) {
            equalizerPreferences.settings.collect { settings ->
                equalizerSettings = settings
                requestApply()
            }
        }
        applicationScope.launch(Dispatchers.Default) {
            settingsPreferences.settings.collect { settings ->
                studioClarityEnabled = settings.isStudioMasterClarityEnabled
                requestApply()
            }
        }
        applicationScope.launch(Dispatchers.Default) {
            loudnessPrefs?.settings?.collect { settings ->
                loudnessMode = settings.mode
                loudnessPreampDb = settings.preampDb
                refreshLoudnessGain()
                requestApply()
            }
        }
    }

    /** Follows ExoPlayer whenever Android creates or replaces its audio session. */
    fun attach(audioSessionId: Int) {
        requestedSessionId = audioSessionId.takeIf { it != C.AUDIO_SESSION_ID_UNSET }
            ?: C.AUDIO_SESSION_ID_UNSET
        requestApply()
    }

    /** Enables AudioFX only after both native output tiers have failed. */
    fun setFallbackRequired(required: Boolean) {
        if (fallbackRequired == required) return
        fallbackRequired = required
        requestApply()
    }

    /** Dynamically suppresses AudioFX when Bit-Perfect mode is active on a FLAC/Lossless track. */
    fun setBitPerfectActive(active: Boolean) {
        if (bitPerfectActive == active) return
        bitPerfectActive = active
        requestApply()
    }

    /** Suppresses all processing (including loudness gain) while the direct USB path owns output. */
    fun setUsbExclusiveActive(active: Boolean) {
        if (usbExclusiveActive == active) return
        usbExclusiveActive = active
        requestApply()
    }

    /**
     * Feeds the current track's ReplayGain tags. Intended to be called by the
     * player on track change; passing null clears the correction.
     */
    fun setReplayGainTags(tags: ReplayGainTags?) {
        replayGainTags = tags
        refreshLoudnessGain()
        requestApply()
    }

    /**
     * Convenience feed that extracts ReplayGain tags from an ExoPlayer audio
     * [Format]'s container metadata. Safe to call with null or with formats
     * that expose no tags: the correction then falls back to 0 dB.
     */
    fun setReplayGainFromFormat(format: Format?) {
        val sampleMime = format?.sampleMimeType?.lowercase().orEmpty()
        val isAtmos = sampleMime.contains("eac3") || sampleMime.contains("ec-3") ||
            sampleMime.contains("ac-3") || sampleMime.contains("ac3")
        val parsedTags = LoudnessNormalizer.parseFromFormat(format)
        val finalTags = if (parsedTags == null && isAtmos) {
            // Dolby Atmos masters target -27 to -31 LUFS (dialogue normalization).
            // Provide +6 dB loudness compensation so Atmos tracks match stereo listening levels.
            ReplayGainTags(trackGainDb = 6.0f, trackPeak = 0.89f)
        } else {
            parsedTags
        }
        setReplayGainTags(finalTags)
    }

    fun detach() {
        requestedSessionId = C.AUDIO_SESSION_ID_UNSET
        fallbackRequired = false
        requestApply()
    }

    private fun requestApply() {
        applyRequests.trySend(Unit)
    }

    private fun applyInternal() {
        val targetSessionId = requestedSessionId
        if (targetSessionId != attachedSessionId) {
            releaseAllInternal()
            attachedSessionId = targetSessionId
        }
        if (bitPerfectActive || usbExclusiveActive || !fallbackRequired || attachedSessionId == C.AUDIO_SESSION_ID_UNSET) {
            releaseAllInternal()
            return
        }

        val userEqEnabled = equalizerSettings.enabled
        val loudnessActive = loudnessMode != LoudnessMode.OFF && loudnessGainDb != 0f
        val needsTone = userEqEnabled || studioClarityEnabled || loudnessActive
        if (needsTone) {
            applyToneEffect(buildCombinedCurve(userEqEnabled, loudnessActive))
        } else {
            releaseToneInternal()
        }
    }

    private fun refreshLoudnessGain() {
        // The fallback stage has no true peak limiter of its own, so the
        // peak guard always stays engaged here (limiterEngaged = false).
        loudnessGainDb = LoudnessNormalizer.gainForTags(
            tags = replayGainTags,
            mode = loudnessMode,
            preampDb = loudnessPreampDb,
            limiterEngaged = false,
        )
    }

    private fun buildCombinedCurve(userEqEnabled: Boolean, loudnessActive: Boolean): FloatArray =
        FloatArray(EQ_BAND_FREQS_HZ.size) { index ->
            val studioGain = if (studioClarityEnabled) {
                EqualizerPresets.STUDIO_MASTER.gainsDb.getOrElse(index) { 0f }
            } else 0f
            val userGain = if (userEqEnabled) {
                equalizerSettings.gainsDb.getOrElse(index) { 0f }
            } else 0f
            val combined = studioGain + userGain
            val base = if (combined.isFinite()) combined.coerceIn(-EQ_MAX_GAIN_DB, EQ_MAX_GAIN_DB) else 0f
            if (!loudnessActive || loudnessGainDb == 0f) {
                base
            } else {
                val total = base + loudnessGainDb
                if (total.isFinite()) {
                    total.coerceIn(-LOUDNESS_STAGE_MAX_GAIN_DB, LOUDNESS_STAGE_MAX_GAIN_DB)
                } else {
                    base
                }
            }
        }

    private fun applyToneEffect(gainsDb: FloatArray) {
        var effect = toneEffect
        if (effect == null || !effect.hasControl()) {
            releaseToneInternal()
            effect = createToneEffect(attachedSessionId)
            toneEffect = effect
        }
        if (effect?.apply(gainsDb) == true) return

        if (effect is ModernDynamicsEffect) {
            Log.w(TAG, "DynamicsProcessing rejected this device; using legacy Equalizer")
            releaseToneInternal()
            val legacy = LegacyEqualizerEffect.create(attachedSessionId)
            toneEffect = legacy
            if (legacy?.apply(gainsDb) != true) {
                Log.w(TAG, "Platform Equalizer is unavailable on this audio route")
                releaseToneInternal()
            }
        } else {
            Log.w(TAG, "Platform Equalizer failed on this audio route")
            releaseToneInternal()
        }
    }

    private fun createToneEffect(audioSessionId: Int): ToneEffect? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ModernDynamicsEffect.create(audioSessionId)?.let { return it }
        }
        return LegacyEqualizerEffect.create(audioSessionId)
    }

    private fun releaseAllInternal() {
        releaseToneInternal()
    }

    private fun releaseToneInternal() {
        toneEffect?.release()
        toneEffect = null
    }

    private interface ToneEffect {
        fun hasControl(): Boolean
        fun apply(gainsDb: FloatArray): Boolean
        fun release()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private class ModernDynamicsEffect private constructor(
        private val processor: DynamicsProcessing,
    ) : ToneEffect {
        override fun hasControl(): Boolean = runCatching { processor.hasControl() }.getOrDefault(false)

        override fun apply(gainsDb: FloatArray): Boolean = runCatching {
            val headroomDb = calculateHeadroomDb(gainsDb)
            processor.setInputGainAllChannelsTo(headroomDb)
            val preEq = DynamicsProcessing.Eq(true, true, EQ_BAND_FREQS_HZ.size)
            EQ_BAND_FREQS_HZ.forEachIndexed { index, frequency ->
                preEq.setBand(
                    index,
                    DynamicsProcessing.EqBand(true, frequency.toFloat(), gainsDb.getOrElse(index) { 0f }),
                )
            }
            processor.setPreEqAllChannelsTo(preEq)
            processor.setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(
                    true,
                    true,
                    0,
                    1.0f,
                    120.0f,
                    12.0f,
                    -1.0f,
                    0.0f,
                ),
            )
            processor.enabled = true
        }.onFailure { Log.w(TAG, "DynamicsProcessing apply failed", it) }.isSuccess

        override fun release() {
            runCatching {
                processor.enabled = false
                processor.release()
            }
        }

        companion object {
            fun create(audioSessionId: Int): ModernDynamicsEffect? = runCatching {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    true,
                    EQ_BAND_FREQS_HZ.size,
                    false,
                    0,
                    false,
                    0,
                    true,
                ).build()
                ModernDynamicsEffect(DynamicsProcessing(EFFECT_PRIORITY, audioSessionId, config))
            }.onFailure { Log.w(TAG, "DynamicsProcessing creation failed", it) }.getOrNull()
        }
    }

    private class LegacyEqualizerEffect private constructor(
        private val equalizer: Equalizer,
    ) : ToneEffect {
        override fun hasControl(): Boolean = runCatching { equalizer.hasControl() }.getOrDefault(false)

        override fun apply(gainsDb: FloatArray): Boolean = runCatching {
            val range = equalizer.bandLevelRange
            val minimumMb = range.first().toInt()
            val maximumMb = range.last().toInt()
            val headroomDb = calculateHeadroomDb(gainsDb)
            for (band in 0 until equalizer.numberOfBands.toInt()) {
                val centerHz = equalizer.getCenterFreq(band.toShort()) / MILLIHERTZ_PER_HZ
                val gainDb = interpolateCurve(centerHz, gainsDb) + headroomDb
                val levelMb = (gainDb * MILLIBELS_PER_DB).roundToInt().coerceIn(minimumMb, maximumMb)
                equalizer.setBandLevel(band.toShort(), levelMb.toShort())
            }
            equalizer.enabled = true
        }.onFailure { Log.w(TAG, "Legacy Equalizer apply failed", it) }.isSuccess

        override fun release() {
            runCatching {
                equalizer.enabled = false
                equalizer.release()
            }
        }

        companion object {
            fun create(audioSessionId: Int): LegacyEqualizerEffect? = runCatching {
                LegacyEqualizerEffect(Equalizer(EFFECT_PRIORITY, audioSessionId))
            }.onFailure { Log.w(TAG, "Legacy Equalizer creation failed", it) }.getOrNull()
        }
    }

    private companion object {
        const val TAG = "FallbackAudioFX"
        const val EFFECT_PRIORITY = 0
        const val MILLIHERTZ_PER_HZ = 1_000
        const val MILLIBELS_PER_DB = 100f
        const val MIN_BOOST_PERCENT = 100
        const val MAX_BOOST_PERCENT = 200
        const val MAX_BOOST_MILLIBELS = 602
        const val MAX_PRE_LIMITER_BOOST_DB = 1.0f
        // Platform-safe per-band ceiling once the uniform loudness offset is
        // added on top of the EQ curve.
        const val LOUDNESS_STAGE_MAX_GAIN_DB = 15f

        fun calculateHeadroomDb(gainsDb: FloatArray): Float {
            val maximumBoost = (gainsDb.maxOrNull() ?: 0f).coerceAtLeast(0f)
            return -(maximumBoost - MAX_PRE_LIMITER_BOOST_DB).coerceAtLeast(0f)
        }

        fun interpolateCurve(hz: Int, gainsDb: FloatArray): Float {
            if (gainsDb.size != EQ_BAND_FREQS_HZ.size) return 0f
            if (hz <= EQ_BAND_FREQS_HZ.first()) return gainsDb.first()
            if (hz >= EQ_BAND_FREQS_HZ.last()) return gainsDb.last()
            for (index in 0 until EQ_BAND_FREQS_HZ.lastIndex) {
                val low = EQ_BAND_FREQS_HZ[index]
                val high = EQ_BAND_FREQS_HZ[index + 1]
                if (hz in low..high) {
                    val position = (ln(hz.toFloat()) - ln(low.toFloat())) /
                        (ln(high.toFloat()) - ln(low.toFloat()))
                    return gainsDb[index] + (gainsDb[index + 1] - gainsDb[index]) * position
                }
            }
            return 0f
        }
    }
}
