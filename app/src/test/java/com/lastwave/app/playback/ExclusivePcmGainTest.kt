package com.lastwave.app.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExclusivePcmGainTest {
    @Test
    fun fallbackFloatPcmReceivesSoftwareGainAtExclusiveWriterOnce() {
        val convertedPcm = floatArrayOf(0.5f, -0.5f, 1.0f, -1.0f)

        // Fallback conversion feeds this shared exclusive-writer stage. The
        // converter itself must leave samples unscaled to avoid gain squared.
        ExclusivePcmGain.apply(convertedPcm, 0.5f)

        assertThat(convertedPcm.toList()).containsExactly(0.25f, -0.25f, 0.5f, -0.5f).inOrder()
    }

    @Test
    fun unityGainPreservesConvertedFloatSamplesExactly() {
        val convertedPcm = floatArrayOf(0.25f, -0.75f, 1.0f)
        val original = convertedPcm.copyOf()

        ExclusivePcmGain.apply(convertedPcm, 1f)

        assertThat(convertedPcm.toList()).containsExactlyElementsIn(original.toList()).inOrder()
    }

    @Test
    fun volumeChangeGlidesAcrossRampInsteadOfStepping() {
        // 4 stereo frames of full-scale samples, ramp over the first 2 frames.
        val pcm = FloatArray(8) { 1f }

        ExclusivePcmGain.applyRamped(pcm, from = 1f, to = 0.5f, channels = 2, rampFrames = 2)

        // frame 0 = from, frame 1 = halfway, frames 2..3 = target; both channels equal.
        assertThat(pcm.toList()).containsExactly(1f, 1f, 0.75f, 0.75f, 0.5f, 0.5f, 0.5f, 0.5f).inOrder()
    }

    @Test
    fun steadyGainTakesConstantPath() {
        val ramped = floatArrayOf(0.5f, -0.5f, 0.25f, -0.25f)
        val constant = ramped.copyOf()

        ExclusivePcmGain.applyRamped(ramped, from = 0.5f, to = 0.5f, channels = 2, rampFrames = 441)
        ExclusivePcmGain.apply(constant, 0.5f)

        assertThat(ramped.toList()).containsExactlyElementsIn(constant.toList()).inOrder()
    }
}
