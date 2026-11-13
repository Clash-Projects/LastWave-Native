package com.lastwave.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EqPresetNameTest {
    private val flat = List(EQ_BAND_FREQS_HZ.size) { 0f }
    private val settings = EqualizerSettings(
        userPresets = listOf(EqPreset("Custom 1", flat), EqPreset("Car", flat)),
    )

    @Test
    fun `rejects blank, built-in, duplicate and overlong names`() {
        assertNotNull(eqPresetNameProblem("   ", settings))
        assertNotNull(eqPresetNameProblem("rock", settings))
        assertNotNull(eqPresetNameProblem("Custom", settings))
        assertNotNull(eqPresetNameProblem("car", settings))
        assertNotNull(eqPresetNameProblem("x".repeat(MAX_EQ_PRESET_NAME_LENGTH + 1), settings))
        assertNull(eqPresetNameProblem("Studio Monitors", settings))
    }

    @Test
    fun `a preset may keep its own name when renamed`() {
        assertNull(eqPresetNameProblem("Car", settings, renaming = "Car"))
        assertNotNull(eqPresetNameProblem("Car", settings, renaming = "Custom 1"))
    }

    @Test
    fun `names cannot break the stored format and the suggestion skips taken names`() {
        assertEquals("a b", cleanEqPresetName(" a\tb\n"))
        assertEquals("Custom 2", nextEqPresetName(settings))
    }
}
