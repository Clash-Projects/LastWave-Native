package com.lastwave.app.playback.automix

import com.lastwave.app.playback.automix.analysis.AutoMixCamelotClassifier
import com.lastwave.app.playback.automix.analysis.HarmonicCompatibilityTier
import com.lastwave.app.playback.automix.api.CamelotKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoMixCamelotClassifierTest {

    @Test
    fun parseToCamelotKey_normalizesDirectCamelotCodes() {
        val key8A = AutoMixCamelotClassifier.parseToCamelotKey("8A")
        assertEquals(8, key8A.number)
        assertEquals('A', key8A.letter)
        assertTrue(key8A.isValid)

        val key11B = AutoMixCamelotClassifier.parseToCamelotKey("11b")
        assertEquals(11, key11B.number)
        assertEquals('B', key11B.letter)
        assertTrue(key11B.isValid)
    }

    @Test
    fun parseToCamelotKey_normalizesSpelledOutAccidentalsAndSymbols() {
        // FSharp Minor -> 11A (F# Minor)
        val fSharpMin = AutoMixCamelotClassifier.parseToCamelotKey("FSharp Minor")
        assertEquals(CamelotKey("11A"), fSharpMin)

        // CSharp Major -> 3B (C# Major)
        val cSharpMaj = AutoMixCamelotClassifier.parseToCamelotKey("CSharp Major")
        assertEquals(CamelotKey("3B"), cSharpMaj)

        // BFlat Minor -> 3A (A#/Bb Minor)
        val bFlatMin = AutoMixCamelotClassifier.parseToCamelotKey("BFlat Minor")
        assertEquals(CamelotKey("3A"), bFlatMin)

        // Unicode sharps and flats
        val fSharpUnicode = AutoMixCamelotClassifier.parseToCamelotKey("F♯m")
        assertEquals(CamelotKey("11A"), fSharpUnicode)

        val bFlatUnicode = AutoMixCamelotClassifier.parseToCamelotKey("B♭ min")
        assertEquals(CamelotKey("3A"), bFlatUnicode)
    }

    @Test
    fun parseToCamelotKey_handlesUnknownAndInvalidGracefully() {
        val unknown = AutoMixCamelotClassifier.parseToCamelotKey("InvalidKeyNotationXYZ")
        assertFalse(unknown.isValid)
        assertEquals(CamelotKey.UNKNOWN, unknown)

        val empty = AutoMixCamelotClassifier.parseToCamelotKey("")
        assertEquals(CamelotKey.UNKNOWN, empty)

        val nullKey = AutoMixCamelotClassifier.parseToCamelotKey(null)
        assertEquals(CamelotKey.UNKNOWN, nullKey)
    }

    @Test
    fun evaluateCompatibility_tier1IdenticalAndRelativeKeys() {
        val key8A = CamelotKey("8A") // A Minor
        val key8B = CamelotKey("8B") // C Major

        // Exact match
        val same = AutoMixCamelotClassifier.evaluateCompatibility(key8A, key8A)
        assertEquals(HarmonicCompatibilityTier.PERFECT_LOCK, same.tier)
        assertEquals(1.0f, same.score, 0.001f)

        // Relative major/minor (same number, different letter)
        val relative = AutoMixCamelotClassifier.evaluateCompatibility(key8A, key8B)
        assertEquals(HarmonicCompatibilityTier.PERFECT_LOCK, relative.tier)
        assertEquals(0.95f, relative.score, 0.001f)
    }

    @Test
    fun evaluateCompatibility_tier1AdjacentWheelKeys() {
        val key8A = CamelotKey("8A")
        val key9A = CamelotKey("9A") // +1 step
        val key7A = CamelotKey("7A") // -1 step

        val plus1 = AutoMixCamelotClassifier.evaluateCompatibility(key8A, key9A)
        assertEquals(HarmonicCompatibilityTier.PERFECT_LOCK, plus1.tier)
        assertEquals(0.90f, plus1.score, 0.001f)

        val minus1 = AutoMixCamelotClassifier.evaluateCompatibility(key8A, key7A)
        assertEquals(HarmonicCompatibilityTier.PERFECT_LOCK, minus1.tier)
        assertEquals(0.90f, minus1.score, 0.001f)
    }

    @Test
    fun evaluateCompatibility_tier2EnergyLift() {
        val key8A = CamelotKey("8A")
        val key10A = CamelotKey("10A") // +2 steps (Energy lift)

        val evaluation = AutoMixCamelotClassifier.evaluateCompatibility(key8A, key10A, tempoDeltaPercent = 2.0f)
        assertEquals(HarmonicCompatibilityTier.ENERGY_OR_MODAL, evaluation.tier)
        assertEquals(0.80f, evaluation.score, 0.001f)
        assertEquals(1, evaluation.suggestedPitchShiftSemitones)
    }

    @Test
    fun evaluateCompatibility_tier3DissonantKeys() {
        val key8A = CamelotKey("8A")
        val key2A = CamelotKey("2A") // 6 steps apart (tritone / dissonant)

        val evaluation = AutoMixCamelotClassifier.evaluateCompatibility(key8A, key2A)
        assertEquals(HarmonicCompatibilityTier.DISSONANT, evaluation.tier)
        assertTrue(evaluation.score <= 0.60f)
    }

    @Test
    fun evaluateCompatibility_unknownKeyUsesFallbackFactor() {
        val valid = CamelotKey("8A")
        val unknown = CamelotKey.UNKNOWN

        val eval = AutoMixCamelotClassifier.evaluateCompatibility(valid, unknown)
        assertEquals(HarmonicCompatibilityTier.DISSONANT, eval.tier)
        assertEquals(AutoMixCamelotClassifier.UNKNOWN_HARMONIC_DISTANCE_FACTOR, eval.score, 0.001f)
    }
}
