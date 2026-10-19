package com.lastwave.app.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Fork: verdict rules for the internal (no USB DAC) exclusive AAudio output. */
class InternalDirectSignalPathTest {

    private fun session(
        sourceRate: Int = 96_000,
        sourceEnc: Int = InternalDirectStream.ENC_I24,
        sourceBits: Int = 24,
        deviceRate: Int = 96_000,
        deviceEnc: Int = InternalDirectStream.ENC_I24,
        resampled: Boolean = false,
        formatConverted: Boolean = false,
        formatLossless: Boolean = true,
    ) = InternalDirectSession(
        route = InternalRoute.WIRED,
        routeName = "Headphones",
        sourceRateHz = sourceRate,
        sourceEncoding = sourceEnc,
        sourceBits = sourceBits,
        deviceRateHz = deviceRate,
        deviceEncoding = deviceEnc,
        deviceChannels = 2,
        resampled = resampled,
        formatConverted = formatConverted,
        formatLossless = formatLossless,
        mmap = true,
        deviceBufferFrames = 960,
    )

    private fun input(internal: InternalDirectSession?) = SignalPathInput(
        sourceLabel = "FLAC",
        sourceRateHz = internal?.sourceRateHz ?: 96_000,
        sourceBitDepth = internal?.sourceBits ?: 24,
        isLossless = true,
        appOutputRateHz = internal?.deviceRateHz ?: 96_000,
        platformMixerRateHz = 48_000,
        dspBypassEnabled = true,
        crossfadeMixing = false,
        speed = 1f,
        appVolume = 1f,
        systemVolume = 15,
        systemVolumeMax = 15,
        systemVolumeFixed = false,
        dac = null,
        routedToDac = false,
        routeVerified = false,
        driftPpm = null,
        glitchCount = 0,
        isPlaying = true,
        internalDirect = internal,
        internalDirectWanted = true,
        bypassRequested = true,
    )

    @Test
    fun exactInternalPathIsDirectToHalButNeverBitPerfect() {
        val report = evaluateSignalPath(input(session()))
        assertThat(report.internalDirectActive).isTrue()
        assertThat(report.directToHal).isTrue()
        // Vendor DSP after the HAL is unverifiable: gold stays USB-only.
        assertThat(report.bitPerfect).isFalse()
        assertThat(report.checks.all { it.passed }).isTrue()
    }

    @Test
    fun resampledInternalPathFailsResamplerAndFlagsBadge() {
        val report = evaluateSignalPath(
            input(session(deviceRate = 48_000, deviceEnc = InternalDirectStream.ENC_FLOAT, resampled = true)),
        )
        assertThat(report.directToHal).isFalse()
        assertThat(report.clockFallbackResampled).isTrue()
        assertThat(report.checks.first { it.labelRes == com.lastwave.app.R.string.signal_label_resampler }.passed)
            .isFalse()
    }

    @Test
    fun truncatingFormatIsReportedAsLossy() {
        val report = evaluateSignalPath(
            input(session(deviceEnc = InternalDirectStream.ENC_I16, formatConverted = true, formatLossless = false)),
        )
        assertThat(report.directToHal).isFalse()
        assertThat(report.checks.first { it.labelRes == com.lastwave.app.R.string.signal_label_output }.passed)
            .isFalse()
    }

    @Test
    fun attenuatedSystemVolumeIsNotDirectToHal() {
        val report = evaluateSignalPath(input(session()).copy(systemVolume = 7))
        assertThat(report.directToHal).isFalse()
    }

    @Test
    fun refusalShowsAsFailingRowWithoutInternalSession() {
        val report = evaluateSignalPath(
            input(null).copy(internalDirectFailure = InternalDirectReason.BLUETOOTH),
        )
        assertThat(report.internalDirectActive).isFalse()
        val row = report.checks.first { it.labelRes == com.lastwave.app.R.string.signal_label_internal }
        assertThat(row.passed).isFalse()
        assertThat(row.detailRes).isEqualTo(com.lastwave.app.R.string.internal_reason_bluetooth)
    }

    @Test
    fun losslessWideningRules() {
        val i16 = InternalDirectStream.ENC_I16
        val i24 = InternalDirectStream.ENC_I24
        val i32 = InternalDirectStream.ENC_I32
        val f32 = InternalDirectStream.ENC_FLOAT
        assertThat(InternalDirectOutput.isLossless(i16, 16, i24)).isTrue()
        assertThat(InternalDirectOutput.isLossless(i24, 24, f32)).isTrue()
        assertThat(InternalDirectOutput.isLossless(i24, 24, i16)).isFalse()
        // FFmpeg hands 24-bit FLAC over as 32-bit words: real depth decides.
        assertThat(InternalDirectOutput.isLossless(i32, 24, i24)).isTrue()
        assertThat(InternalDirectOutput.isLossless(i32, 32, f32)).isFalse()
        assertThat(InternalDirectOutput.isLossless(f32, 32, i32)).isFalse()
        assertThat(InternalDirectOutput.effectiveBits(i32, 24)).isEqualTo(24)
        assertThat(InternalDirectOutput.effectiveBits(i32, null)).isEqualTo(32)
        assertThat(InternalDirectOutput.effectiveBits(i16, 24)).isEqualTo(16)
    }

    @Test
    fun routeClassification() {
        assertThat(InternalDirectOutput.classify(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
            .isEqualTo(InternalRoute.BLUETOOTH)
        assertThat(InternalDirectOutput.classify(android.media.AudioDeviceInfo.TYPE_BLE_HEADSET))
            .isEqualTo(InternalRoute.BLUETOOTH)
        assertThat(InternalDirectOutput.classify(android.media.AudioDeviceInfo.TYPE_USB_HEADSET))
            .isEqualTo(InternalRoute.USB)
        assertThat(InternalDirectOutput.classify(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
            .isEqualTo(InternalRoute.SPEAKER)
        assertThat(InternalDirectOutput.classify(android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
            .isEqualTo(InternalRoute.WIRED)
    }

    private fun trackSession(route: InternalRoute, verified: Boolean) = session(
        sourceRate = 44_100,
        sourceEnc = InternalDirectStream.ENC_I16,
        sourceBits = 16,
        deviceRate = 44_100,
        deviceEnc = InternalDirectStream.ENC_I24,
        formatConverted = true,
        formatLossless = true,
    ).copy(route = route, backend = InternalBackend.DIRECT_TRACK, directVerified = verified, mmap = false)

    @Test
    fun verifiedDirectTrackOnJackIsDirectToHal() {
        val report = evaluateSignalPath(input(trackSession(InternalRoute.WIRED, verified = true)))
        assertThat(report.directToHal).isTrue()
        assertThat(report.bitPerfect).isFalse()
    }

    @Test
    fun unverifiedDirectTrackNeverClaimsTheBypass() {
        val report = evaluateSignalPath(input(trackSession(InternalRoute.WIRED, verified = false)))
        assertThat(report.directToHal).isFalse()
        assertThat(report.checks.first { it.labelRes == com.lastwave.app.R.string.signal_label_mixer }.passed)
            .isFalse()
    }

    @Test
    fun bluetoothBypassStillReportsTheCodec() {
        val report = evaluateSignalPath(input(trackSession(InternalRoute.BLUETOOTH, verified = true)))
        assertThat(report.internalDirectActive).isTrue()
        assertThat(report.checks.first { it.labelRes == com.lastwave.app.R.string.signal_label_mixer }.passed)
            .isTrue()
        assertThat(report.checks.first { it.labelRes == com.lastwave.app.R.string.signal_label_bt_codec }.passed)
            .isFalse()
        assertThat(report.directToHal).isFalse()
    }

    @Test
    fun wireEncodingFollowsUappRecipe() {
        assertThat(DirectTrackStream.wireEncodingFor(InternalDirectStream.ENC_I16, 16)).isEqualTo(InternalDirectStream.ENC_I24)
        assertThat(DirectTrackStream.wireEncodingFor(InternalDirectStream.ENC_I24, 24)).isEqualTo(InternalDirectStream.ENC_I24)
        assertThat(DirectTrackStream.wireEncodingFor(InternalDirectStream.ENC_I32, 24)).isEqualTo(InternalDirectStream.ENC_I24)
        assertThat(DirectTrackStream.wireEncodingFor(InternalDirectStream.ENC_I32, 32)).isEqualTo(InternalDirectStream.ENC_I32)
        assertThat(DirectTrackStream.wireEncodingFor(InternalDirectStream.ENC_FLOAT, 32)).isEqualTo(InternalDirectStream.ENC_I24)
    }

    private fun le(size: Int) = java.nio.ByteBuffer.allocate(size).order(java.nio.ByteOrder.LITTLE_ENDIAN)

    @Test
    fun sixteenBitWidensExactlyTo24() {
        val src = le(8).putShort(32767).putShort(-32768).putShort(1).putShort(-1).flip() as java.nio.ByteBuffer
        val out = DirectTrackStream.convert(src, InternalDirectStream.ENC_I16, InternalDirectStream.ENC_I24, le(64))!!
        assertThat(src.hasRemaining()).isFalse()
        assertThat(out.remaining()).isEqualTo(12)
        val expected = intArrayOf(32767 * 256, -32768 * 256, 256, -256)
        for (e in expected) {
            val b0 = out.get().toInt() and 0xFF
            val b1 = out.get().toInt() and 0xFF
            val b2 = out.get().toInt()
            assertThat(b0 or (b1 shl 8) or (b2 shl 16)).isEqualTo(e)
        }
    }

    @Test
    fun thirtyTwoBitWordsWith24BitContentKeepEveryBit() {
        val src = le(8).putInt(0x12345600).putInt(0xFEDCBA00.toInt()).flip() as java.nio.ByteBuffer
        val out = DirectTrackStream.convert(src, InternalDirectStream.ENC_I32, InternalDirectStream.ENC_I24, le(64))!!
        val bytes = ByteArray(out.remaining()).also { out.get(it) }
        assertThat(bytes.map { it.toInt() and 0xFF }).containsExactly(0x56, 0x34, 0x12, 0xBA, 0xDC, 0xFE).inOrder()
    }

    @Test
    fun floatIsRoundedAndClippedTo24() {
        val src = le(12).putFloat(1.5f).putFloat(-1.0f).putFloat(0.5f).flip() as java.nio.ByteBuffer
        val out = DirectTrackStream.convert(src, InternalDirectStream.ENC_FLOAT, InternalDirectStream.ENC_I24, le(64))!!
        val values = (0 until 3).map {
            val b0 = out.get().toInt() and 0xFF
            val b1 = out.get().toInt() and 0xFF
            val b2 = out.get().toInt()
            b0 or (b1 shl 8) or (b2 shl 16)
        }
        assertThat(values).containsExactly(8_388_607, -8_388_608, 4_194_304).inOrder()
    }

    @Test
    fun usbFallbackThroughDirectTrackReportsDirectWithoutCodecRow() {
        val report = evaluateSignalPath(input(trackSession(InternalRoute.USB, verified = true)))
        assertThat(report.internalDirectActive).isTrue()
        assertThat(report.directToHal).isTrue()
        assertThat(report.checks.none { it.labelRes == com.lastwave.app.R.string.signal_label_bt_codec }).isTrue()
    }

    // ---- MMAP rate fallback order -----------------------------------------

    @Test
    fun fallbackRatesPrefer48kHzOverTheDeviceNativeRate() {
        // A 44.1-primary HAL probes at 44100. The device still opens 48 kHz
        // (the one rate every HAL accepts), so it must be tried BEFORE the
        // probe rate — otherwise a 96 kHz source gets resampled DOWN to
        // 44.1 for no reason the user asked for.
        assertThat(InternalDirectOutput.fallbackRates(96_000, 44_100))
            .containsExactly(48_000, 44_100)
            .inOrder()
    }

    @Test
    fun fallbackRatesSkipTheSourceRateAndDuplicates() {
        // 48 was refused at the source rate: asking again is pointless.
        assertThat(InternalDirectOutput.fallbackRates(48_000, 48_000)).isEmpty()
        assertThat(InternalDirectOutput.fallbackRates(48_000, 44_100)).containsExactly(44_100)
        // Probe already at 48: one attempt, not two.
        assertThat(InternalDirectOutput.fallbackRates(96_000, 48_000)).containsExactly(48_000)
        // 44.1 refused but 48 available (48-primary HAL): up, never down.
        assertThat(InternalDirectOutput.fallbackRates(44_100, 48_000)).containsExactly(48_000)
        // No probe rate at all: 48 is still worth the try.
        assertThat(InternalDirectOutput.fallbackRates(44_100, 0)).containsExactly(48_000)
        // A garbage source never reaches the loop.
        assertThat(InternalDirectOutput.fallbackRates(0, 44_100)).isEmpty()
    }
    @Test
    fun toggleStaysReachableUnlessTheDeviceCanNeverBypass() {
        fun availability(reason: InternalDirectReason) =
            InternalDirectAvailability(InternalRoute.SPEAKER, "Speaker", reason)
        // Route- or format-specific results must not lock the toggle once it is turned off.
        listOf(
            InternalDirectReason.AVAILABLE,
            InternalDirectReason.ACTIVE,
            InternalDirectReason.CHECKING,
            InternalDirectReason.BLUETOOTH,
            InternalDirectReason.USB,
            InternalDirectReason.EXCLUSIVE_REFUSED,
            InternalDirectReason.NO_AAUDIO,
            InternalDirectReason.MIXED_BY_ANDROID,
        ).forEach { assertThat(availability(it).canEnable).isTrue() }
        assertThat(availability(InternalDirectReason.ANDROID_TOO_OLD).canEnable).isFalse()
        assertThat(availability(InternalDirectReason.NATIVE_MISSING).canEnable).isFalse()
    }
}
