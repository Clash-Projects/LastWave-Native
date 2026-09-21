package com.lastwave.app.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExclusiveUsbSignalPathTest {

    private val dac = UsbDacInfo(
        name = "NICEHCK NK1 MAX",
        vendorId = 1,
        productId = 2,
        sampleRatesHz = listOf(44100, 48000, 96000),
        usbPermissionGranted = true,
        hasUsbPeripheral = true,
        deviceId = 7,
    )

    @Test
    fun goldRequiresVerifiedExclusiveUsbClock() {
        val report = evaluateSignalPath(exclusiveInput())
        assertThat(report.bitPerfect).isTrue()
        assertThat(report.checks).isNotEmpty()
        assertThat(report.checks.all { it.passed }).isTrue()
    }

    @Test
    fun mixerBitPerfectGrantIsNeverGold() {
        val report = evaluateSignalPath(
            exclusiveInput().copy(
                usbExclusiveActive = false,
                exclusiveClockMatched = false,
                exclusiveHardwareVolume = false,
                routeVerified = true,
                platformBitPerfectConfigured = true,
                systemVolume = 15,
                systemVolumeMax = 15,
                systemVolumeFixed = false,
            ),
        )
        assertThat(report.bitPerfect).isFalse()
        assertThat(report.checks.any { !it.passed }).isTrue()
    }

    @Test
    fun exclusiveWithoutClockMatchIsNeverGold() {
        val report = evaluateSignalPath(
            exclusiveInput().copy(
                exclusiveClockMatched = false,
                routeVerified = false,
            ),
        )
        assertThat(report.bitPerfect).isFalse()
    }

    @Test
    fun exclusiveHardwareVolumeCanPassBelowUnityAppGain() {
        val report = evaluateSignalPath(
            exclusiveInput().copy(
                appVolume = 0.4f,
                exclusiveHardwareVolume = true,
                systemVolume = 3,
                systemVolumeMax = 15,
            ),
        )
        assertThat(report.bitPerfect).isTrue()
    }

    @Test
    fun exclusiveSoftwareVolumeFailsWhenSystemNotMax() {
        val report = evaluateSignalPath(
            exclusiveInput().copy(
                exclusiveHardwareVolume = false,
                systemVolume = 8,
                systemVolumeMax = 15,
                systemVolumeFixed = false,
                appVolume = 8f / 15f,
            ),
        )
        assertThat(report.bitPerfect).isFalse()
    }

    @Test
    fun exclusiveSoftwareVolumeGoldOnlyAtSystemMax() {
        val report = evaluateSignalPath(
            exclusiveInput().copy(
                exclusiveHardwareVolume = false,
                systemVolume = 15,
                systemVolumeMax = 15,
                systemVolumeFixed = false,
                appVolume = 1f,
            ),
        )
        assertThat(report.bitPerfect).isTrue()
    }

    @Test
    fun exclusiveUsesDecodedOutputRateWhenSourceMetadataMissing() {
        val report = evaluateSignalPath(
            exclusiveInput().copy(
                sourceRateHz = null,
                sourceBitDepth = null,
                appOutputRateHz = 44100,
            ),
        )
        assertThat(report.bitPerfect).isTrue()
        assertThat(report.sourceRateHz).isEqualTo(44100)
        assertThat(report.appRateHz).isEqualTo(44100)
        assertThat(report.usbExclusiveActive).isTrue()
        assertThat(report.checks.all { it.passed }).isTrue()
    }

    @Test
    fun exclusiveClockDriftUsesFrameClockNotExoPosition() {
        val tracker = StreamHealthTracker()
        val rate = 176_400
        assertThat(tracker.sampleExclusive(0L, rate, 1_000L, true)).isNull()
        val first = tracker.sampleExclusive(rate.toLong(), rate, 2_000L, true)
        assertThat(first).isNotNull()
        assertThat(kotlin.math.abs(first!!)).isLessThan(5_000.0)
    }

    private fun exclusiveInput() = SignalPathInput(
        sourceLabel = "FLAC",
        sourceRateHz = 96000,
        sourceBitDepth = 24,
        isLossless = true,
        appOutputRateHz = 96000,
        platformMixerRateHz = 48000,
        dspBypassEnabled = true,
        crossfadeMixing = false,
        speed = 1f,
        appVolume = 1f,
        systemVolume = 8,
        systemVolumeMax = 15,
        systemVolumeFixed = false,
        dac = dac,
        routedToDac = true,
        routeVerified = true,
        driftPpm = null,
        glitchCount = 0,
        isPlaying = true,
        platformBitPerfectConfigured = false,
        usbExclusiveActive = true,
        exclusiveClockMatched = true,
        exclusiveHardwareVolume = true,
    )
}
