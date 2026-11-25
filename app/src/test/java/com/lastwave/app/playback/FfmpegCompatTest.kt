package com.lastwave.app.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FfmpegCompatTest {

    @Test
    fun `atmos capability requires genuine platform JOC or FFmpeg E-AC-3 decoder`() {
        assertThat(FfmpegCompat.isAtmosCapable(hasPlatformAtmos = false, ffmpegEac3 = false)).isFalse()
        assertThat(FfmpegCompat.isAtmosCapable(hasPlatformAtmos = true, ffmpegEac3 = false)).isTrue()
        assertThat(FfmpegCompat.isAtmosCapable(hasPlatformAtmos = false, ffmpegEac3 = true)).isTrue()
    }

    @Test
    fun `legacy eac3 and ac3 defer to FFmpeg when no platform Atmos HAL exists`() {
        assertThat(
            FfmpegCompat.shouldUsePlatformSpatialRenderer(
                mime = "audio/eac3",
                hasPlatformAtmos = false,
                ffmpegEac3 = true,
            ),
        ).isFalse()
        assertThat(
            FfmpegCompat.shouldUsePlatformSpatialRenderer(
                mime = "audio/ac3",
                hasPlatformAtmos = false,
                ffmpegEac3 = true,
            ),
        ).isFalse()
    }

    @Test
    fun `genuine spatial mimes use platform renderer and legacy eac3 uses platform when HAL present or FFmpeg absent`() {
        assertThat(
            FfmpegCompat.shouldUsePlatformSpatialRenderer(
                mime = "audio/eac3-joc",
                hasPlatformAtmos = false,
                ffmpegEac3 = true,
            ),
        ).isTrue()
        assertThat(
            FfmpegCompat.shouldUsePlatformSpatialRenderer(
                mime = "audio/eac3",
                hasPlatformAtmos = true,
                ffmpegEac3 = true,
            ),
        ).isTrue()
        assertThat(
            FfmpegCompat.shouldUsePlatformSpatialRenderer(
                mime = "audio/eac3",
                hasPlatformAtmos = false,
                ffmpegEac3 = false,
            ),
        ).isTrue()
        assertThat(
            FfmpegCompat.shouldUsePlatformSpatialRenderer(
                mime = "audio/flac",
                hasPlatformAtmos = true,
                ffmpegEac3 = true,
            ),
        ).isFalse()
    }
}
