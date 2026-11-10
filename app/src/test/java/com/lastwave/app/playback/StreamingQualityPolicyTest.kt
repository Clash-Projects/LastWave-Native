package com.lastwave.app.playback

import com.lastwave.app.data.lossless.LosslessMusicApi.Companion.QUALITY_CD_LOSSLESS
import com.lastwave.app.data.lossless.LosslessMusicApi.Companion.QUALITY_DATA_SAVER
import com.lastwave.app.data.lossless.LosslessMusicApi.Companion.QUALITY_DOLBY_ATMOS
import com.lastwave.app.data.lossless.LosslessMusicApi.Companion.QUALITY_MAX_HI_RES
import com.lastwave.app.data.lossless.LosslessMusicApi.Companion.QUALITY_MP3_320
import com.lastwave.app.data.lossless.LosslessMusicApi.Companion.QUALITY_YOUTUBE
import com.lastwave.app.data.lossless.LosslessMusicApi.Companion.getQualityAttemptOrder
import com.lastwave.app.playback.AdaptiveQualityController.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingQualityPolicyTest {
    private val hiResFlac = StreamQualityInfo(providerTier = QUALITY_MAX_HI_RES, isLossless = true, bitDepth = 24, samplingRateKHz = 44.1)
    private val cdFlac = StreamQualityInfo(providerTier = QUALITY_CD_LOSSLESS, isLossless = true, bitDepth = 16, samplingRateKHz = 44.1)
    private val provider320 = StreamQualityInfo(providerTier = QUALITY_MP3_320, bitrateKbps = 320)
    private val provider96 = StreamQualityInfo(providerTier = QUALITY_DATA_SAVER, bitrateKbps = 96)
    private val atmos = StreamQualityInfo(providerTier = QUALITY_DOLBY_ATMOS, isSpatial = true)
    private val youtubeOpus160 = StreamQualityInfo(isYouTube = true, youtubeWithin320 = true, bitrateKbps = 160)
    private val youtubeOpus73 = StreamQualityInfo(isYouTube = true, youtubeWithinDataSaver = true, youtubeWithin320 = true, bitrateKbps = 73)
    private val youtubeUnknownHls = StreamQualityInfo(isYouTube = true, bitrateKbps = 0)
    private val local = StreamQualityInfo(isLocal = true, isLossless = true, bitDepth = 24, samplingRateKHz = 192.0)

    private fun allowed(stream: StreamQualityInfo, requested: Int, atmosOk: Boolean = false) =
        StreamingQualityPolicy.isAllowed(stream, requested, StreamingQualityPolicy.effective(requested, atmosOk))

    @Test fun wifiAndCellularProfilesAreIndependent() {
        assertEquals(QUALITY_MAX_HI_RES, StreamingQualityPolicy.profileFor(QUALITY_MAX_HI_RES, QUALITY_DATA_SAVER, isCellular = false))
        assertEquals(QUALITY_DATA_SAVER, StreamingQualityPolicy.profileFor(QUALITY_MAX_HI_RES, QUALITY_DATA_SAVER, isCellular = true))
        assertEquals(QUALITY_YOUTUBE, StreamingQualityPolicy.profileFor(QUALITY_YOUTUBE, QUALITY_MP3_320, isCellular = false))
    }

    @Test fun cellularAutoAdaptsUnderTheWifiChoiceAndOnlyOnCellular() {
        val auto = com.lastwave.app.data.local.CELLULAR_QUALITY_AUTO
        assertEquals(QUALITY_MAX_HI_RES, StreamingQualityPolicy.profileFor(QUALITY_MAX_HI_RES, auto, isCellular = true))
        assertEquals(QUALITY_MP3_320, StreamingQualityPolicy.profileFor(QUALITY_MP3_320, auto, isCellular = true))
        assertTrue(StreamingQualityPolicy.isAdaptive(auto, isCellular = true))
        assertFalse(StreamingQualityPolicy.isAdaptive(auto, isCellular = false))
        assertFalse(StreamingQualityPolicy.isAdaptive(QUALITY_MP3_320, isCellular = true))
    }

    @Test fun atmosNeedsToggleAndDeviceSupport() {
        assertEquals(QUALITY_DOLBY_ATMOS, StreamingQualityPolicy.effective(QUALITY_DOLBY_ATMOS, atmosSupported = true))
        assertEquals(QUALITY_MAX_HI_RES, StreamingQualityPolicy.effective(QUALITY_DOLBY_ATMOS, atmosSupported = false))
        assertFalse(allowed(atmos, QUALITY_DOLBY_ATMOS, atmosOk = false))
        assertTrue(allowed(atmos, QUALITY_DOLBY_ATMOS, atmosOk = true))
        assertFalse(allowed(atmos, QUALITY_MAX_HI_RES, atmosOk = true))
    }

    @Test fun dataSaverIsARealCap() {
        assertTrue(allowed(youtubeOpus73, QUALITY_DATA_SAVER))
        assertTrue(allowed(provider96, QUALITY_DATA_SAVER))
        assertFalse(allowed(youtubeOpus160, QUALITY_DATA_SAVER))
        assertFalse(allowed(youtubeUnknownHls, QUALITY_DATA_SAVER))
        assertFalse(allowed(provider320, QUALITY_DATA_SAVER))
        assertFalse(allowed(cdFlac, QUALITY_DATA_SAVER))
        assertFalse(allowed(StreamQualityInfo(providerTier = QUALITY_DATA_SAVER, bitrateKbps = 128), QUALITY_DATA_SAVER))
    }

    @Test fun mp3320NeverAllowsLosslessOrAboveCap() {
        assertTrue(allowed(provider320, QUALITY_MP3_320))
        assertTrue(allowed(youtubeOpus160, QUALITY_MP3_320))
        assertTrue(allowed(provider96, QUALITY_MP3_320))
        assertFalse(allowed(cdFlac, QUALITY_MP3_320))
        assertFalse(allowed(hiResFlac, QUALITY_MP3_320))
        assertFalse(allowed(atmos, QUALITY_MP3_320, atmosOk = true))
        assertFalse(allowed(youtubeUnknownHls, QUALITY_MP3_320))
        assertFalse(allowed(StreamQualityInfo(providerTier = QUALITY_MP3_320, bitrateKbps = 448), QUALITY_MP3_320))
    }

    @Test fun youtubeProfileOnlyAcceptsYoutube() {
        assertTrue(allowed(youtubeOpus160, QUALITY_YOUTUBE))
        assertFalse(allowed(provider320, QUALITY_YOUTUBE))
        assertFalse(allowed(hiResFlac, QUALITY_YOUTUBE))
        assertTrue(getQualityAttemptOrder(QUALITY_YOUTUBE).isEmpty())
    }

    @Test fun hiResAcceptsAnythingUpToItsCeiling() {
        assertTrue(allowed(hiResFlac, QUALITY_MAX_HI_RES))
        assertTrue(allowed(cdFlac, QUALITY_MAX_HI_RES))
        assertTrue(allowed(youtubeOpus160, QUALITY_MAX_HI_RES))
        assertFalse(allowed(hiResFlac, QUALITY_CD_LOSSLESS))
    }

    @Test fun localAndOfflineAlwaysPlay() {
        assertTrue(allowed(local, QUALITY_DATA_SAVER))
        assertTrue(allowed(local, QUALITY_YOUTUBE))
    }

    @Test fun providerAttemptOrderNeverExceedsLossyCaps() {
        assertEquals(listOf(QUALITY_MP3_320, QUALITY_DATA_SAVER), getQualityAttemptOrder(QUALITY_MP3_320))
        assertEquals(listOf(QUALITY_DATA_SAVER), getQualityAttemptOrder(QUALITY_DATA_SAVER))
        assertFalse(QUALITY_DOLBY_ATMOS in getQualityAttemptOrder(QUALITY_MAX_HI_RES))
    }

    @Test fun cachedStreamSelectionRespectsActiveProfile() {
        // A stream prepared on Wi-Fi Hi-Res must not be reused after a handoff to a 320 cellular profile.
        val preparedOnWifi = listOf(hiResFlac, youtubeOpus160)
        val reusableOnCellular = preparedOnWifi.filter { allowed(it, QUALITY_MP3_320) }
        assertEquals(listOf(youtubeOpus160), reusableOnCellular)
        assertTrue(preparedOnWifi.none { allowed(it, QUALITY_DATA_SAVER) })
    }

    @Test fun measuredSpeedBeatsOptimisticLinkRate() {
        assertEquals(3_000, StreamingQualityPolicy.combineBandwidth(measuredKbps = 3_000, linkKbps = 400_000))
        assertEquals(20_000, StreamingQualityPolicy.combineBandwidth(measuredKbps = 50_000, linkKbps = 20_000))
        assertEquals(400_000, StreamingQualityPolicy.combineBandwidth(measuredKbps = null, linkKbps = 400_000))
        assertEquals(null, StreamingQualityPolicy.combineBandwidth(measuredKbps = null, linkKbps = null))
    }

    @Test fun adaptiveFollowsSpeedBetweenDataSaverAndTheNetworkChoice() {
        val wifi = StreamingQualityPolicy.profileFor(QUALITY_MAX_HI_RES, QUALITY_MP3_320, isCellular = false)
        val cellular = StreamingQualityPolicy.profileFor(QUALITY_MAX_HI_RES, QUALITY_MP3_320, isCellular = true)
        fun pick(profile: Int, measured: Int?, link: Int?) =
            StreamingQualityPolicy.forBandwidth(profile, StreamingQualityPolicy.combineBandwidth(measured, link), null)
        // Fast Wi-Fi: as high as the Wi-Fi choice, never above it.
        assertEquals(QUALITY_MAX_HI_RES, pick(wifi, measured = 80_000, link = 400_000))
        // A fast radio link with slow internet is not fast.
        assertEquals(QUALITY_MP3_320, pick(wifi, measured = 3_000, link = 400_000))
        assertEquals(QUALITY_DATA_SAVER, pick(wifi, measured = 500, link = 400_000))
        // Cellular never goes above its own choice, however fast.
        assertEquals(QUALITY_MP3_320, pick(cellular, measured = 100_000, link = 100_000))
        assertEquals(QUALITY_DATA_SAVER, pick(cellular, measured = 400, link = 20_000))
    }

    @Test fun bandwidthTargetHasHysteresisAndStaysUnderProfile() {
        // Cold start needs clear headroom for lossless.
        assertEquals(QUALITY_MP3_320, StreamingQualityPolicy.forBandwidth(QUALITY_MAX_HI_RES, 10_000, currentQuality = null))
        assertEquals(QUALITY_MAX_HI_RES, StreamingQualityPolicy.forBandwidth(QUALITY_MAX_HI_RES, 15_000, currentQuality = null))
        // Already on lossless: stay there down to 8 Mbps.
        assertEquals(QUALITY_MAX_HI_RES, StreamingQualityPolicy.forBandwidth(QUALITY_MAX_HI_RES, 9_000, QUALITY_MAX_HI_RES))
        // From 320, going back up needs 12 Mbps.
        assertEquals(QUALITY_MP3_320, StreamingQualityPolicy.forBandwidth(QUALITY_MAX_HI_RES, 9_000, QUALITY_MP3_320))
        assertEquals(QUALITY_DATA_SAVER, StreamingQualityPolicy.forBandwidth(QUALITY_MAX_HI_RES, 500, QUALITY_MP3_320))
        // Never above the profile ceiling.
        assertEquals(QUALITY_MP3_320, StreamingQualityPolicy.forBandwidth(QUALITY_MP3_320, 100_000, QUALITY_MP3_320))
        assertEquals(QUALITY_CD_LOSSLESS, StreamingQualityPolicy.forBandwidth(QUALITY_CD_LOSSLESS, 100_000, null))
        // YouTube and Data Saver do not adapt.
        assertEquals(QUALITY_YOUTUBE, StreamingQualityPolicy.forBandwidth(QUALITY_YOUTUBE, 300, null))
        assertEquals(QUALITY_DATA_SAVER, StreamingQualityPolicy.forBandwidth(QUALITY_DATA_SAVER, 100_000, null))
    }
}

class AdaptiveQualityControllerTest {
    private fun sample(
        now: Long,
        buffered: Long = 30_000L,
        rebuffering: Boolean = false,
        playing: Boolean = !rebuffering,
        loading: Boolean = true,
        tier: Int = QUALITY_MAX_HI_RES,
        profile: Int = QUALITY_MAX_HI_RES,
        bandwidth: Int? = 50_000,
    ) = AdaptiveQualityController.Sample(
        nowMs = now, positionMs = 30_000L, bufferedAheadMs = buffered, remainingMs = 120_000L,
        isRebuffering = rebuffering, isPlaying = playing, isLoading = loading,
        currentTier = tier, profile = profile, bandwidthKbps = bandwidth,
    )

    @Test fun drainingBufferDownshiftsBeforeAStall() {
        val c = AdaptiveQualityController()
        assertEquals(Decision.Hold, c.sample(sample(1_000L, buffered = 2_000L)))
        assertEquals(Decision.Hold, c.sample(sample(3_000L, buffered = 1_500L)))
        assertEquals(Decision.Switch(QUALITY_MP3_320, downshift = true), c.sample(sample(4_100L, buffered = 1_000L)))
    }

    @Test fun shrinkingBufferDownshiftsWhileThereIsStillTimeToSwap() {
        val c = AdaptiveQualityController()
        assertEquals(Decision.Hold, c.sample(sample(1_000L, buffered = 9_000L)))
        assertEquals(Decision.Hold, c.sample(sample(2_500L, buffered = 8_200L)))
        assertEquals(Decision.Switch(QUALITY_MP3_320, downshift = true), c.sample(sample(4_100L, buffered = 7_400L)))
    }

    @Test fun shortBufferThatKeepsFillingIsNotPressure() {
        val c = AdaptiveQualityController()
        var buffered = 500L
        for (now in 1_000L..12_000L step 500L) {
            assertEquals(Decision.Hold, c.sample(sample(now, buffered = buffered)))
            buffered += 300L
        }
    }

    @Test fun briefDipDoesNotSwitch() {
        val c = AdaptiveQualityController()
        c.sample(sample(1_000L, buffered = 2_000L))
        assertEquals(Decision.Hold, c.sample(sample(2_000L, buffered = 25_000L)))
        assertEquals(Decision.Hold, c.sample(sample(4_500L, buffered = 2_000L)))
    }

    @Test fun secondStallInWindowDownshiftsImmediately() {
        val c = AdaptiveQualityController()
        c.sample(sample(1_000L, rebuffering = true))
        c.sample(sample(1_500L))
        assertEquals(
            Decision.Switch(QUALITY_MP3_320, downshift = true),
            c.sample(sample(20_000L, rebuffering = true)),
        )
    }

    @Test fun cooldownBlocksBackToBackSwitches() {
        val c = AdaptiveQualityController(cooldownMs = 15_000L)
        c.onSwitch(1_000L)
        c.sample(sample(2_000L, buffered = 1_000L, tier = QUALITY_MP3_320))
        assertEquals(Decision.Hold, c.sample(sample(6_000L, buffered = 1_000L, tier = QUALITY_MP3_320)))
        assertEquals(
            Decision.Switch(QUALITY_DATA_SAVER, downshift = true),
            c.sample(sample(16_500L, buffered = 1_000L, tier = QUALITY_MP3_320)),
        )
    }

    @Test fun upgradeNeedsSustainedHealthyBufferAndHeadroom() {
        val c = AdaptiveQualityController()
        assertEquals(Decision.Hold, c.sample(sample(1_000L, tier = QUALITY_MP3_320)))
        assertEquals(Decision.Hold, c.sample(sample(15_000L, tier = QUALITY_MP3_320)))
        assertEquals(
            Decision.Switch(QUALITY_MAX_HI_RES, downshift = false),
            c.sample(sample(21_500L, tier = QUALITY_MP3_320)),
        )
        // A thin buffer resets the healthy window.
        val d = AdaptiveQualityController()
        d.sample(sample(1_000L, tier = QUALITY_MP3_320))
        d.sample(sample(10_000L, buffered = 5_000L, tier = QUALITY_MP3_320, loading = false))
        assertEquals(Decision.Hold, d.sample(sample(21_500L, tier = QUALITY_MP3_320)))
        // Not enough link headroom keeps the current tier.
        val e = AdaptiveQualityController()
        e.sample(sample(1_000L, tier = QUALITY_MP3_320, bandwidth = 5_000))
        assertEquals(Decision.Hold, e.sample(sample(30_000L, tier = QUALITY_MP3_320, bandwidth = 5_000)))
    }

    @Test fun recentStallBlocksUpgrade() {
        val c = AdaptiveQualityController()
        c.sample(sample(1_000L, rebuffering = true, tier = QUALITY_MP3_320))
        c.sample(sample(2_000L, tier = QUALITY_MP3_320))
        assertEquals(Decision.Hold, c.sample(sample(30_000L, tier = QUALITY_MP3_320)))
        assertEquals(
            Decision.Switch(QUALITY_MAX_HI_RES, downshift = false),
            c.sample(sample(62_000L, tier = QUALITY_MP3_320)),
        )
    }

    @Test fun drainDownshiftHoldsUpgradesLikeAStall() {
        val c = AdaptiveQualityController()
        c.sample(sample(1_000L, buffered = 6_000L))
        assertEquals(Decision.Switch(QUALITY_MP3_320, downshift = true), c.sample(sample(4_100L, buffered = 5_000L)))
        c.onSwitch(4_100L)
        c.sample(sample(10_000L, tier = QUALITY_MP3_320))
        assertEquals(Decision.Hold, c.sample(sample(50_000L, tier = QUALITY_MP3_320)))
        assertEquals(
            Decision.Switch(QUALITY_MAX_HI_RES, downshift = false),
            c.sample(sample(65_000L, tier = QUALITY_MP3_320)),
        )
    }

    @Test fun upgradeStaysUnderProfileCeiling() {
        val c = AdaptiveQualityController()
        c.sample(sample(1_000L, tier = QUALITY_DATA_SAVER, profile = QUALITY_MP3_320))
        assertEquals(
            Decision.Switch(QUALITY_MP3_320, downshift = false),
            c.sample(sample(21_500L, tier = QUALITY_DATA_SAVER, profile = QUALITY_MP3_320)),
        )
        val d = AdaptiveQualityController()
        d.sample(sample(1_000L, tier = QUALITY_MP3_320, profile = QUALITY_MP3_320))
        assertEquals(Decision.Hold, d.sample(sample(30_000L, tier = QUALITY_MP3_320, profile = QUALITY_MP3_320)))
    }

    @Test fun youtubeAndDataSaverProfilesNeverAdapt() {
        val c = AdaptiveQualityController()
        repeat(10) { i ->
            assertEquals(Decision.Hold, c.sample(sample(1_000L + i * 1_000L, rebuffering = true, tier = QUALITY_YOUTUBE, profile = QUALITY_YOUTUBE)))
            assertEquals(Decision.Hold, c.sample(sample(1_000L + i * 1_000L, buffered = 500L, tier = QUALITY_DATA_SAVER, profile = QUALITY_DATA_SAVER)))
        }
    }

    @Test fun floorHasNowhereToGo() {
        val c = AdaptiveQualityController()
        c.sample(sample(1_000L, buffered = 500L, tier = QUALITY_DATA_SAVER))
        assertEquals(Decision.Hold, c.sample(sample(10_000L, buffered = 500L, tier = QUALITY_DATA_SAVER)))
    }
}
