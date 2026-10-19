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
}
