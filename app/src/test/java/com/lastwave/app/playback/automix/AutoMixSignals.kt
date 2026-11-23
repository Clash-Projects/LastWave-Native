package com.lastwave.app.playback.automix

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic music with a known answer: tempo, bar phase, key and level contour. */
internal object AutoMixSignals {
    const val RATE = 11_025

    /**
     * Drums plus a chord pad. Kick on beat 1 (loud) and 3 (soft), snare on
     * 2 and 4; the pad cycles Am-F-C-G, diatonic to A minor / C major (8A/8B).
     */
    fun song(
        bpm: Double,
        seconds: Double,
        firstDownbeatS: Double = 0.0,
        leadSilenceS: Double = 0.0,
        tailSilenceS: Double = 0.0,
        fadeOutS: Double = 0.0,
        padLevel: Double = 0.06,
        seed: Int = 1,
    ): FloatArray {
        val total = ((leadSilenceS + seconds + tailSilenceS) * RATE).toInt()
        val out = FloatArray(total)
        val random = Random(seed)
        val musicStart = (leadSilenceS * RATE).toInt()
        val musicEnd = ((leadSilenceS + seconds) * RATE).toInt()
        val beat = 60.0 / bpm
        // Beats from the first downbeat, plus any pickup beats before it.
        var k = -kotlin.math.floor(firstDownbeatS / beat).toInt()
        while (true) {
            val t = leadSilenceS + firstDownbeatS + k * beat
            if (t >= leadSilenceS + seconds) break
            if (t >= leadSilenceS) {
                val slot = Math.floorMod(k, 4)
                when (slot) {
                    0 -> kick(out, t, 0.7, musicEnd)
                    2 -> kick(out, t, 0.35, musicEnd)
                    else -> snare(out, t, 0.22, random, musicEnd)
                }
            }
            k++
        }
        val chords = arrayOf(
            doubleArrayOf(110.0, 130.81, 164.81, 220.0),
            doubleArrayOf(87.31, 110.0, 130.81, 174.61),
            doubleArrayOf(130.81, 164.81, 196.0, 261.63),
            doubleArrayOf(98.0, 123.47, 146.83, 196.0),
        )
        for (i in musicStart until musicEnd) {
            val t = (i - musicStart).toDouble() / RATE
            val chord = chords[((t / 2.0).toInt()) % chords.size]
            var pad = 0.0
            for (f in chord) pad += sin(2 * PI * f * t)
            out[i] += (pad * padLevel / chord.size + random.nextDouble(-0.004, 0.004)).toFloat()
        }
        if (fadeOutS > 0) {
            val fadeStart = musicEnd - (fadeOutS * RATE).toInt()
            for (i in fadeStart until musicEnd) {
                val dB = -60.0 * (i - fadeStart) / (musicEnd - fadeStart)
                out[i] = (out[i] * Math.pow(10.0, dB / 20)).toFloat()
            }
        }
        return out
    }

    private fun kick(out: FloatArray, t: Double, amp: Double, end: Int) {
        val start = (t * RATE).toInt()
        for (i in 0 until (0.25 * RATE).toInt()) {
            val index = start + i
            if (index >= end) break
            val s = i.toDouble() / RATE
            out[index] += (amp * exp(-s / 0.08) * sin(2 * PI * (55.0 + 60.0 * exp(-s / 0.02)) * s)).toFloat()
        }
    }

    private fun snare(out: FloatArray, t: Double, amp: Double, random: Random, end: Int) {
        val start = (t * RATE).toInt()
        for (i in 0 until (0.12 * RATE).toInt()) {
            val index = start + i
            if (index >= end) break
            out[index] += (amp * exp(-i.toDouble() / RATE / 0.03) * random.nextDouble(-1.0, 1.0)).toFloat()
        }
    }

    fun noise(seconds: Double, seed: Int = 7): FloatArray {
        val random = Random(seed)
        return FloatArray((seconds * RATE).toInt()) { (random.nextDouble(-0.3, 0.3)).toFloat() }
    }

    fun sine(hz: Double, seconds: Double, rate: Int, amp: Double = 0.5): FloatArray =
        FloatArray((seconds * rate).toInt()) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }
}
