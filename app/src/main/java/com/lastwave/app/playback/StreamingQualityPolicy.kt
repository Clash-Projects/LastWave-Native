package com.lastwave.app.playback

import com.lastwave.app.data.local.CELLULAR_QUALITY_AUTO
import com.lastwave.app.data.lossless.LosslessMusicApi

/** What the quality policy needs to know about a resolved stream. */
internal data class StreamQualityInfo(
    val isLocal: Boolean = false,
    val isYouTube: Boolean = false,
    /** True when a YouTube rendition is a known audio format at or below 96 kbps. */
    val youtubeWithinDataSaver: Boolean = false,
    /** True when a YouTube rendition is a known audio format at or below 320 kbps. */
    val youtubeWithin320: Boolean = false,
    val isSpatial: Boolean = false,
    val providerTier: Int? = null,
    val isLossless: Boolean = false,
    val bitDepth: Int? = null,
    val samplingRateKHz: Double? = null,
    val bitrateKbps: Int? = null,
)

/**
 * Pure quality rules shared by startup, prepared-stream reuse, retry, preload
 * and network handoff. A selected tier is a ceiling, never a minimum.
 */
internal object StreamingQualityPolicy {
    const val DATA_SAVER_MAX_BPS = 96_000
    const val MP3_320_MAX_BPS = 320_000

    fun rank(tier: Int): Int = when (tier) {
        LosslessMusicApi.QUALITY_DATA_SAVER -> 0
        LosslessMusicApi.QUALITY_YOUTUBE -> 1
        LosslessMusicApi.QUALITY_MP3_320 -> 2
        LosslessMusicApi.QUALITY_CD_LOSSLESS -> 3
        LosslessMusicApi.QUALITY_HI_RES_96 -> 4
        LosslessMusicApi.QUALITY_MAX_HI_RES -> 5
        LosslessMusicApi.QUALITY_DOLBY_ATMOS -> 6
        else -> -1
    }

    /** The ceiling for the current default route. Auto on cellular is capped by the Wi-Fi choice. */
    fun profileFor(wifiQuality: Int, cellularQuality: Int, isCellular: Boolean): Int =
        if (isCellular && cellularQuality != CELLULAR_QUALITY_AUTO) cellularQuality else wifiQuality

    fun isAdaptive(cellularQuality: Int, isCellular: Boolean): Boolean =
        isCellular && cellularQuality == CELLULAR_QUALITY_AUTO

    /** Atmos falls back to Hi-Res stereo when this device cannot render it. */
    fun effective(selected: Int, atmosSupported: Boolean): Int =
        if (selected == LosslessMusicApi.QUALITY_DOLBY_ATMOS && !atmosSupported) {
            LosslessMusicApi.QUALITY_MAX_HI_RES
        } else {
            selected
        }

    /** YouTube is a provider choice and Data Saver is the floor, so neither adapts. */
    fun adapts(profile: Int): Boolean =
        profile != LosslessMusicApi.QUALITY_YOUTUBE && profile != LosslessMusicApi.QUALITY_DATA_SAVER

    /**
     * Tier for a link estimate. Moving up needs more headroom than staying
     * (hysteresis), and the result never exceeds [profile].
     */
    fun forBandwidth(profile: Int, bandwidthKbps: Int?, currentQuality: Int?): Int {
        if (!adapts(profile)) return profile
        val bandwidth = bandwidthKbps ?: return capTo(currentQuality ?: profile, profile)
        val tier = when (currentQuality) {
            LosslessMusicApi.QUALITY_DATA_SAVER ->
                if (bandwidth >= 2_000) LosslessMusicApi.QUALITY_MP3_320 else LosslessMusicApi.QUALITY_DATA_SAVER
            LosslessMusicApi.QUALITY_MP3_320 -> when {
                bandwidth >= 12_000 -> profile
                bandwidth < 700 -> LosslessMusicApi.QUALITY_DATA_SAVER
                else -> LosslessMusicApi.QUALITY_MP3_320
            }
            else -> when {
                bandwidth < 900 -> LosslessMusicApi.QUALITY_DATA_SAVER
                bandwidth >= (if (currentQuality == null) 12_000 else 8_000) -> profile
                else -> LosslessMusicApi.QUALITY_MP3_320
            }
        }
        return capTo(tier, profile)
    }

    /**
     * Throughput the player actually measured is the real internet speed.
     * Android's link estimate (on Wi-Fi, the radio link rate) only bounds it.
     */
    fun combineBandwidth(measuredKbps: Int?, linkKbps: Int?): Int? = when {
        measuredKbps != null && linkKbps != null -> minOf(measuredKbps, linkKbps)
        else -> measuredKbps ?: linkKbps
    }

    fun capTo(tier: Int, ceiling: Int): Int = if (rank(tier) > rank(ceiling)) ceiling else tier

    /** One step down from what is playing; null when already at the floor. */
    fun stepDown(currentTier: Int): Int? = when (currentTier) {
        LosslessMusicApi.QUALITY_DOLBY_ATMOS,
        LosslessMusicApi.QUALITY_MAX_HI_RES,
        LosslessMusicApi.QUALITY_HI_RES_96,
        LosslessMusicApi.QUALITY_CD_LOSSLESS -> LosslessMusicApi.QUALITY_MP3_320
        LosslessMusicApi.QUALITY_MP3_320,
        LosslessMusicApi.QUALITY_YOUTUBE -> LosslessMusicApi.QUALITY_DATA_SAVER
        else -> null
    }

    fun tierOf(stream: StreamQualityInfo): Int = when {
        stream.isSpatial -> LosslessMusicApi.QUALITY_DOLBY_ATMOS
        stream.isYouTube && stream.youtubeWithinDataSaver -> LosslessMusicApi.QUALITY_DATA_SAVER
        stream.isYouTube -> LosslessMusicApi.QUALITY_YOUTUBE
        stream.providerTier != null -> stream.providerTier
        stream.isLossless && ((stream.bitDepth ?: 0) > 16 || (stream.samplingRateKHz ?: 0.0) > 48.0) ->
            LosslessMusicApi.QUALITY_MAX_HI_RES
        stream.isLossless -> LosslessMusicApi.QUALITY_CD_LOSSLESS
        (stream.bitrateKbps ?: 0) in 1..96 -> LosslessMusicApi.QUALITY_DATA_SAVER
        else -> LosslessMusicApi.QUALITY_MP3_320
    }

    /**
     * Whether [stream] may play under [requested]. [effective] is the same
     * request after the Atmos capability check (see [effective]).
     */
    fun isAllowed(stream: StreamQualityInfo, requested: Int, effective: Int): Boolean {
        if (stream.isLocal) return true
        val tier = tierOf(stream)
        return when (requested) {
            LosslessMusicApi.QUALITY_YOUTUBE -> stream.isYouTube
            LosslessMusicApi.QUALITY_DATA_SAVER ->
                tier == LosslessMusicApi.QUALITY_DATA_SAVER && !stream.isLossless &&
                    (stream.isYouTube || (stream.bitrateKbps ?: 0) in 1..96)
            LosslessMusicApi.QUALITY_MP3_320 -> when {
                stream.isYouTube -> stream.youtubeWithin320
                stream.isLossless || stream.isSpatial || (stream.bitrateKbps ?: 0) > 320 -> false
                else -> rank(tier) in 0..rank(LosslessMusicApi.QUALITY_MP3_320)
            }
            else -> rank(tier) in 0..rank(effective)
        }
    }
}

/**
 * Buffer-pressure state machine for adaptive streaming. It only proposes a
 * tier; the player re-checks the live profile and transition state before
 * swapping.
 */
internal class AdaptiveQualityController(
    private val cooldownMs: Long = 15_000L,
    private val pressureHoldMs: Long = 3_000L,
    /** A swap takes a few seconds to line up, so pressure has to show while this much is left. */
    private val lowBufferMs: Long = 10_000L,
    private val healthyBufferMs: Long = 20_000L,
    private val healthyHoldMs: Long = 20_000L,
    private val repeatStallWindowMs: Long = 60_000L,
) {
    sealed interface Decision {
        data object Hold : Decision
        data class Switch(val quality: Int, val downshift: Boolean) : Decision
    }

    data class Sample(
        val nowMs: Long,
        val positionMs: Long,
        val bufferedAheadMs: Long,
        val remainingMs: Long,
        val isRebuffering: Boolean,
        val isPlaying: Boolean,
        val isLoading: Boolean,
        val currentTier: Int,
        val profile: Int,
        val bandwidthKbps: Int?,
    )

    private var pressureSinceMs = 0L
    private var pressureStartBufferMs = 0L
    private var healthySinceMs = 0L
    private var lastSwitchAtMs = 0L
    private var lastStallAtMs = 0L
    private var wasRebuffering = false

    fun reset() {
        pressureSinceMs = 0L
        healthySinceMs = 0L
        lastSwitchAtMs = 0L
        lastStallAtMs = 0L
        wasRebuffering = false
    }

    /** Called when a switch was actually scheduled. */
    fun onSwitch(nowMs: Long) {
        lastSwitchAtMs = nowMs
        pressureSinceMs = 0L
        healthySinceMs = 0L
    }

    fun cooldownComplete(nowMs: Long): Boolean =
        lastSwitchAtMs == 0L || nowMs - lastSwitchAtMs >= cooldownMs

    fun sample(s: Sample): Decision {
        if (!StreamingQualityPolicy.adapts(s.profile)) return Decision.Hold
        // A second stall inside the window is a pattern, not a blip.
        val stallStarted = s.isRebuffering && !wasRebuffering && s.positionMs >= 1_000L
        wasRebuffering = s.isRebuffering
        val repeatedStall = stallStarted && lastStallAtMs != 0L && s.nowMs - lastStallAtMs <= repeatStallWindowMs
        if (stallStarted) lastStallAtMs = s.nowMs

        // Draining towards a stall counts as pressure before the stall happens.
        val draining = s.isPlaying && s.isLoading && s.bufferedAheadMs < lowBufferMs && s.remainingMs > 10_000L
        val underPressure = (s.isRebuffering || draining) && s.positionMs >= 1_000L
        if (underPressure) {
            healthySinceMs = 0L
            // A short buffer that is still filling (track start, after a seek)
            // is the link keeping up, so the hold restarts from the new level.
            val filling = !s.isRebuffering && s.bufferedAheadMs > pressureStartBufferMs
            if (pressureSinceMs == 0L || filling) {
                pressureSinceMs = s.nowMs
                pressureStartBufferMs = s.bufferedAheadMs
            }
            val sustained = s.nowMs - pressureSinceMs >= pressureHoldMs
            if (!(sustained || repeatedStall) || !cooldownComplete(s.nowMs)) return Decision.Hold
            val next = StreamingQualityPolicy.stepDown(s.currentTier) ?: return Decision.Hold
            // Holds upgrades like a stall does: the link just failed to carry
            // this tier, whatever a stale speed sample says.
            lastStallAtMs = s.nowMs
            return Decision.Switch(StreamingQualityPolicy.capTo(next, s.profile), downshift = true)
        }
        pressureSinceMs = 0L

        if (!s.isPlaying || s.bufferedAheadMs < healthyBufferMs) {
            healthySinceMs = 0L
            return Decision.Hold
        }
        if (healthySinceMs == 0L) healthySinceMs = s.nowMs
        if (s.nowMs - healthySinceMs < healthyHoldMs || !cooldownComplete(s.nowMs)) return Decision.Hold
        // Stalls in the recent window block upgrades.
        if (lastStallAtMs != 0L && s.nowMs - lastStallAtMs <= repeatStallWindowMs) return Decision.Hold

        val target = StreamingQualityPolicy.forBandwidth(s.profile, s.bandwidthKbps, s.currentTier)
        if (StreamingQualityPolicy.rank(target) <= StreamingQualityPolicy.rank(s.currentTier)) return Decision.Hold
        return Decision.Switch(target, downshift = false)
    }
}
