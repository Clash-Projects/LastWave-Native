package com.lastwave.app.data.lossless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NonLatinNormalizeTest {
    @Test
    fun japaneseTitlesSurviveNormalization() {
        assertEquals("夜に駆ける", LosslessMusicApi.normalizeText("夜に駆ける"))
        assertEquals("がらくた", LosslessMusicApi.normalizeText("がらくた"))
        assertTrue(LosslessMusicApi.normalizeText("アイドル (Idol)").startsWith("アイドル"))
    }

    @Test
    fun koreanAndIndicSurvive() {
        assertEquals("사랑", LosslessMusicApi.normalizeText("사랑"))
        assertEquals("तुम ही हो", LosslessMusicApi.normalizeText("तुम ही हो"))
    }

    @Test
    fun latinAccentsStillFolded() {
        assertEquals("cafe del mar", LosslessMusicApi.normalizeText("Café  del Mar!"))
        assertEquals("abc", LosslessMusicApi.normalizeText("ＡＢＣ"))
    }
}
