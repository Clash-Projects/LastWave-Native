package com.lastwave.app.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Bug: "volume boosts to 100 after removing and re-attaching the IEM".
 *
 * Android pins STREAM_MUSIC at max the moment a USB DAC attaches, while the
 * user's real level is the last non-max volume-key press. The exclusive
 * output must fall back to the remembered level instead of applying the
 * pinned 100% at full scale.
 */
class ExclusiveUsbAttachVolumeTest {

    @Test
    fun attachPinAtMaxFallsBackToRememberedLevel() {
        assertThat(attachMaxPinFallbackGain(streamGain = 1f, rememberedGain = 0.6f)).isEqualTo(0.6f)
    }

    @Test
    fun attachPinWithoutRememberedLevelTrustsTheReading() {
        assertThat(attachMaxPinFallbackGain(streamGain = 1f, rememberedGain = Float.NaN)).isNull()
    }

    @Test
    fun realVolumeKeyLevelsBelowMaxAreNeverOverridden() {
        assertThat(attachMaxPinFallbackGain(streamGain = 14f / 15f, rememberedGain = 0.6f)).isNull()
        assertThat(attachMaxPinFallbackGain(streamGain = 0.53f, rememberedGain = 0.6f)).isNull()
    }

    @Test
    fun silentReadingIsNotAPin() {
        assertThat(attachMaxPinFallbackGain(streamGain = 0f, rememberedGain = 0.6f)).isNull()
    }

    @Test
    fun rememberedLevelMustBeInTheStoredAudibleRange() {
        assertThat(attachMaxPinFallbackGain(streamGain = 1f, rememberedGain = 1f)).isNull()
        assertThat(attachMaxPinFallbackGain(streamGain = 1f, rememberedGain = 0.001f)).isNull()
    }
}
