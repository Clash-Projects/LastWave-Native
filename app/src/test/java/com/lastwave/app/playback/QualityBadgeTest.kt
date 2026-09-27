package com.lastwave.app.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QualityBadgeTest {

    @Test
    fun losslessDepthAndRateIsFlacSlashForm() {
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(
                    isLossless = true,
                    bitDepth = 24,
                    samplingRateKHz = 44.1,
                    audioCodec = "LOSSLESS",
                    bitrateKbps = 2116,
                ),
            ),
        ).isEqualTo("24/44.1kHz")
    }

    @Test
    fun cdFlacIsSixteenFortyOne() {
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(
                    isLossless = true,
                    bitDepth = 16,
                    samplingRateKHz = 44.1,
                    audioCodec = "FLAC",
                    bitrateKbps = 1411,
                ),
            ),
        ).isEqualTo("16/44.1kHz")
    }

    @Test
    fun hiResNinetySix() {
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(
                    isLossless = true,
                    bitDepth = 24,
                    samplingRateKHz = 96.0,
                    audioCodec = "HI-RES FLAC",
                ),
            ),
        ).isEqualTo("24/96kHz")
    }

    @Test
    fun flacCodecWithoutLosslessFlagStillShowsDepthRate() {
        // Decoder path used to publish codec=FLAC + 1411 kbps with isLossless=false,
        // which rendered as the truncated "FLAC 1411 k…" pill.
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(
                    isLossless = false,
                    audioCodec = "FLAC",
                    bitrateKbps = 1411,
                    samplingRateKHz = 44.1,
                    bitDepth = 16,
                ),
            ),
        ).isEqualTo("16/44.1kHz")
    }

    @Test
    fun infersCdDepthFromPcmBitrateWhenTagsOmitBitDepth() {
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(
                    isLossless = true,
                    audioCodec = "FLAC",
                    bitrateKbps = 1411,
                    samplingRateKHz = 44.1,
                ),
            ),
        ).isEqualTo("16/44.1kHz")
    }

    @Test
    fun atmosNeverShowsFlacRate() {
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(
                    isLossless = true,
                    audioCodec = "DOLBY ATMOS",
                    bitDepth = 24,
                    samplingRateKHz = 48.0,
                    bitrateKbps = 768,
                ),
            ),
        ).isEqualTo("ATMOS")
        assertThat(spatialIndicatorLabel("DOLBY ATMOS")).isEqualTo("ATMOS")
        assertThat(spatialIndicatorLabel("SPATIAL AUDIO")).isEqualTo("SPATIAL")
    }

    @Test
    fun spatialAudioBadge() {
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(audioCodec = "SPATIAL AUDIO", isLossless = true, bitDepth = 24, samplingRateKHz = 48.0),
            ),
        ).isEqualTo("SPATIAL")
    }

    @Test
    fun opusKeepsKbpsForm() {
        assertThat(
            qualityBadgeLabel(
                MusicPlayerState(
                    audioCodec = "OPUS",
                    bitrateKbps = 160,
                    isLossless = false,
                ),
            ),
        ).isEqualTo("OPUS 160 kbps")
    }

    @Test
    fun intermediateQualityBadgesForAllTiers() {
        // 16-bit 48 kHz
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 16, samplingRateKHz = 48.0)),
        ).isEqualTo("16/48kHz")

        // 24-bit 44.1 kHz
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 24, samplingRateKHz = 44.1)),
        ).isEqualTo("24/44.1kHz")

        // 24-bit 48 kHz
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 24, samplingRateKHz = 48.0)),
        ).isEqualTo("24/48kHz")

        // 24-bit 88.2 kHz
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 24, samplingRateKHz = 88.2)),
        ).isEqualTo("24/88.2kHz")

        // 24-bit 96 kHz
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 24, samplingRateKHz = 96.0)),
        ).isEqualTo("24/96kHz")

        // 24-bit 176.4 kHz
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 24, samplingRateKHz = 176.4)),
        ).isEqualTo("24/176.4kHz")

        // 24-bit 192 kHz
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 24, samplingRateKHz = 192.0)),
        ).isEqualTo("24/192kHz")

        // 32-bit 384 kHz
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, bitDepth = 32, samplingRateKHz = 384.0)),
        ).isEqualTo("32/384kHz")
    }

    @Test
    fun infersHiRes24BitWhenOmittedInTags() {
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, samplingRateKHz = 96.0)),
        ).isEqualTo("24/96kHz")
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, samplingRateKHz = 88.2)),
        ).isEqualTo("24/88.2kHz")
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, samplingRateKHz = 176.4)),
        ).isEqualTo("24/176.4kHz")
        assertThat(
            qualityBadgeLabel(MusicPlayerState(isLossless = true, samplingRateKHz = 192.0)),
        ).isEqualTo("24/192kHz")
    }

    @Test
    fun parsesQualityFromFormattedCodecString() {
        assertThat(
            qualityBadgeLabel(MusicPlayerState(audioCodec = "24-BIT / 96k", isLossless = true)),
        ).isEqualTo("24/96kHz")
        assertThat(
            qualityBadgeLabel(MusicPlayerState(audioCodec = "24-BIT / 48k", isLossless = true)),
        ).isEqualTo("24/48kHz")
        assertThat(
            qualityBadgeLabel(MusicPlayerState(audioCodec = "16-BIT / 48k", isLossless = true)),
        ).isEqualTo("16/48kHz")
        assertThat(
            qualityBadgeLabel(MusicPlayerState(audioCodec = "24-BIT / 44.1k", isLossless = true)),
        ).isEqualTo("24/44.1kHz")
    }

    @Test
    fun detailedQualityBadgeFormats() {
        assertThat(formatDetailedQualityBadge(24, 192.0)).isEqualTo("24-BIT / 192k")
        assertThat(formatDetailedQualityBadge(24, 176.4)).isEqualTo("24-BIT / 176.4k")
        assertThat(formatDetailedQualityBadge(24, 96.0)).isEqualTo("24-BIT / 96k")
        assertThat(formatDetailedQualityBadge(24, 88.2)).isEqualTo("24-BIT / 88.2k")
        assertThat(formatDetailedQualityBadge(24, 48.0)).isEqualTo("24-BIT / 48k")
        assertThat(formatDetailedQualityBadge(24, 44.1)).isEqualTo("24-BIT / 44.1k")
        assertThat(formatDetailedQualityBadge(16, 48.0)).isEqualTo("16-BIT / 48k")
        assertThat(formatDetailedQualityBadge(16, 44.1)).isEqualTo("16-BIT / 44.1k")
        assertThat(formatDetailedQualityBadge(null, 96.0)).isEqualTo("24-BIT / 96k")
        assertThat(formatDetailedQualityBadge(null, 44.1)).isEqualTo("16-BIT / 44.1k")
        assertThat(formatDetailedQualityBadge(null, null, isAtmos = true)).isEqualTo("DOLBY ATMOS")
    }
}

