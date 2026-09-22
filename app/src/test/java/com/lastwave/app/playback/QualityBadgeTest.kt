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
}
