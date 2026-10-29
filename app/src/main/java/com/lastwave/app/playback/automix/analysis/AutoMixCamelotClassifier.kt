package com.lastwave.app.playback.automix.analysis

import com.lastwave.app.playback.automix.api.CamelotKey
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class HarmonicCompatibilityTier {
    PERFECT_LOCK,      // Tier 1: Identical, +-1 step, or relative Major/Minor. Allows 12s-30s extended blend.
    ENERGY_OR_MODAL,   // Tier 2: +-2 steps or parallel mode. Pitch-shiftable if tempo delta <= 4%.
    DISSONANT,         // Tier 3: >2 steps. Scaled duration + bass cut to avoid clashing.
}

data class HarmonicMatchEvaluation(
    val tier: HarmonicCompatibilityTier,
    val score: Float,                    // 0.0f..1.0f
    val keyPairLabel: String,            // e.g. "8A → 9A"
    val suggestedPitchShiftSemitones: Int = 0,
    val allowedDurationMultiplier: Float = 1.0f,
)

object AutoMixCamelotClassifier {
    const val UNKNOWN_HARMONIC_DISTANCE_FACTOR = 0.65f

    // Mapping from (PitchClass: 0..11, isMinor) to Camelot code (1A..12A, 1B..12B)
    // Pitch class: 0=C, 1=C#/Db, 2=D, 3=D#/Eb, 4=E, 5=F, 6=F#/Gb, 7=G, 8=G#/Ab, 9=A, 10=A#/Bb, 11=B
    private val PITCH_CLASS_TO_CAMELOT_MAJOR = mapOf(
        11 to "1B", // B Major
        6 to "2B",  // F# Major
        1 to "3B",  // C# Major
        8 to "4B",  // G# Major
        3 to "5B",  // D# Major
        10 to "6B", // A# Major
        5 to "7B",  // F Major
        0 to "8B",  // C Major
        7 to "9B",  // G Major
        2 to "10B", // D Major
        9 to "11B", // A Major
        4 to "12B", // E Major
    )

    private val PITCH_CLASS_TO_CAMELOT_MINOR = mapOf(
        8 to "1A",  // G# Minor
        3 to "2A",  // D# Minor
        10 to "3A", // A# Minor
        5 to "4A",  // F Minor
        0 to "5A",  // C Minor
        7 to "6A",  // G Minor
        2 to "7A",  // D Minor
        9 to "8A",  // A Minor
        4 to "9A",  // E Minor
        11 to "10A",// B Minor
        6 to "11A", // F# Minor
        1 to "12A", // C# Minor
    )

    /**
     * Parses raw metadata key strings, normalizing spelled-out accidentals (e.g. "FSharp", "CSharp",
     * "BFlat", "A#", "Bb", ♯/♭) into a canonical CamelotKey.
     */
    fun parseToCamelotKey(rawKey: String?): CamelotKey {
        if (rawKey.isNullOrBlank()) return CamelotKey.UNKNOWN
        val clean = rawKey.trim()

        // Check if already in direct Camelot notation (e.g. "8A", "11B")
        val camelotRegex = Regex("""^([1-9]|1[0-2])[a-bA-B]$""")
        if (camelotRegex.matches(clean)) {
            val num = clean.filter { it.isDigit() }
            val letter = clean.last().uppercaseChar()
            return CamelotKey("$num$letter")
        }

        // Accidental and tonality normalization
        var root = clean.lowercase()
            .replace("sharp", "#")
            .replace("flat", "b")
            .replace("♯", "#")
            .replace("♭", "b")
            .replace("maj", " major")
            .replace("min", " minor")
            .replace("m", " minor")
            .trim()

        val isMinor = root.contains("minor") || root.endsWith("m")
        root = root.replace("minor", "").replace("major", "").trim()

        val pitchClass = when (root) {
            "c" -> 0
            "c#", "db" -> 1
            "d" -> 2
            "d#", "eb" -> 3
            "e" -> 4
            "f" -> 5
            "f#", "gb" -> 6
            "g" -> 7
            "g#", "ab" -> 8
            "a" -> 9
            "a#", "bb" -> 10
            "b" -> 11
            else -> return CamelotKey.UNKNOWN
        }

        val code = if (isMinor) {
            PITCH_CLASS_TO_CAMELOT_MINOR[pitchClass] ?: return CamelotKey.UNKNOWN
        } else {
            PITCH_CLASS_TO_CAMELOT_MAJOR[pitchClass] ?: return CamelotKey.UNKNOWN
        }

        return CamelotKey(code)
    }

    /**
     * Scores harmonic compatibility between outgoing key and incoming key.
     */
    fun evaluateCompatibility(
        keyOut: CamelotKey,
        keyIn: CamelotKey,
        tempoDeltaPercent: Float = 0.0f,
    ): HarmonicMatchEvaluation {
        if (!keyOut.isValid || !keyIn.isValid) {
            val label = "${if (keyOut.isValid) keyOut.toString() else "?"} → ${if (keyIn.isValid) keyIn.toString() else "?"}"
            return HarmonicMatchEvaluation(
                tier = HarmonicCompatibilityTier.DISSONANT,
                score = UNKNOWN_HARMONIC_DISTANCE_FACTOR,
                keyPairLabel = label,
                suggestedPitchShiftSemitones = 0,
                allowedDurationMultiplier = 0.75f,
            )
        }

        val pairLabel = "$keyOut → $keyIn"

        // 1. Identical key
        if (keyOut == keyIn) {
            return HarmonicMatchEvaluation(
                tier = HarmonicCompatibilityTier.PERFECT_LOCK,
                score = 1.0f,
                keyPairLabel = pairLabel,
                suggestedPitchShiftSemitones = 0,
                allowedDurationMultiplier = 1.25f, // Long extended blend allowed
            )
        }

        // 2. Relative Major/Minor (Same number, different letter: 8A <-> 8B)
        if (keyOut.number == keyIn.number && keyOut.letter != keyIn.letter) {
            return HarmonicMatchEvaluation(
                tier = HarmonicCompatibilityTier.PERFECT_LOCK,
                score = 0.95f,
                keyPairLabel = pairLabel,
                suggestedPitchShiftSemitones = 0,
                allowedDurationMultiplier = 1.15f,
            )
        }

        // Circular distance on the 12-hour Camelot wheel
        val numDiff = abs(keyOut.number - keyIn.number)
        val circularDist = minOf(numDiff, 12 - numDiff)

        // 3. Adjacent step (+-1 step on the wheel, same mode: 8A -> 9A or 8A -> 7A)
        if (circularDist == 1 && keyOut.letter == keyIn.letter) {
            return HarmonicMatchEvaluation(
                tier = HarmonicCompatibilityTier.PERFECT_LOCK,
                score = 0.90f,
                keyPairLabel = pairLabel,
                suggestedPitchShiftSemitones = 0,
                allowedDurationMultiplier = 1.0f,
            )
        }

        // 4. Energy Lift (+2 steps on wheel: 8A -> 10A) or Parallel Mode shift
        if (circularDist == 2 && keyOut.letter == keyIn.letter) {
            val shift = if (abs(tempoDeltaPercent) <= 4.0f) 1 else 0
            return HarmonicMatchEvaluation(
                tier = HarmonicCompatibilityTier.ENERGY_OR_MODAL,
                score = 0.80f,
                keyPairLabel = pairLabel,
                suggestedPitchShiftSemitones = shift,
                allowedDurationMultiplier = 0.90f,
            )
        }

        // 5. Parallel mode (e.g. C Minor (5A) to C Major (8B), circular distance is 3 between modes)
        if (keyOut.letter != keyIn.letter && circularDist == 3) {
            return HarmonicMatchEvaluation(
                tier = HarmonicCompatibilityTier.ENERGY_OR_MODAL,
                score = 0.75f,
                keyPairLabel = pairLabel,
                suggestedPitchShiftSemitones = 0,
                allowedDurationMultiplier = 0.85f,
            )
        }

        // 6. Dissonant (>2 steps apart)
        return HarmonicMatchEvaluation(
            tier = HarmonicCompatibilityTier.DISSONANT,
            score = 0.40f,
            keyPairLabel = pairLabel,
            suggestedPitchShiftSemitones = 0,
            allowedDurationMultiplier = 0.60f, // Faster blend with bass cut
        )
    }

    /**
     * Computes a 12-pitch-class chroma vector from 22.05 kHz mono PCM ring buffer
     * and maps the dominant pitch class to a CamelotKey if metadata is unavailable.
     */
    fun classifyChromaPcm(pcm: FloatArray, sampleRate: Float = 22050f): CamelotKey {
        if (pcm.size < 4096) return CamelotKey.UNKNOWN

        val chroma = FloatArray(12)
        val baseFreqs = floatArrayOf(
            130.81f, // C3
            138.59f, // C#3
            146.83f, // D3
            155.56f, // D#3
            164.81f, // E3
            174.61f, // F3
            185.00f, // F#3
            196.00f, // G3
            207.65f, // G#3
            220.00f, // A3
            233.08f, // A#3
            246.94f  // B3
        )

        // Simplified Goertzel energy accumulation across 2 octaves
        for (oct in 0..1) {
            val multiplier = 1 shl oct
            for (p in 0 until 12) {
                val f = baseFreqs[p] * multiplier
                val w = 2.0 * Math.PI * f / sampleRate
                var q1 = 0.0
                var q2 = 0.0
                val coeff = 2.0 * cos(w)
                val step = 4
                for (i in 0 until minOf(pcm.size, 8192) step step) {
                    val q0 = coeff * q1 - q2 + pcm[i]
                    q2 = q1
                    q1 = q0
                }
                val energy = q1 * q1 + q2 * q2 - q1 * q2 * coeff
                chroma[p] += energy.toFloat()
            }
        }

        // Find primary pitch class
        var maxIdx = 0
        var maxEnergy = chroma[0]
        for (i in 1 until 12) {
            if (chroma[i] > maxEnergy) {
                maxEnergy = chroma[i]
                maxIdx = i
            }
        }

        // Check minor third energy (idx + 3) vs major third (idx + 4)
        val minorThirdEnergy = chroma[(maxIdx + 3) % 12]
        val majorThirdEnergy = chroma[(maxIdx + 4) % 12]
        val isMinor = minorThirdEnergy > majorThirdEnergy

        val code = if (isMinor) {
            PITCH_CLASS_TO_CAMELOT_MINOR[maxIdx]
        } else {
            PITCH_CLASS_TO_CAMELOT_MAJOR[maxIdx]
        }

        return code?.let { CamelotKey(it) } ?: CamelotKey.UNKNOWN
    }
}
