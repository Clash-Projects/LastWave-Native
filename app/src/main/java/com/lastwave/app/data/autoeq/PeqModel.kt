package com.lastwave.app.data.autoeq

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Filter shapes understood by the native EQ (ids match ParametricEq.h). */
enum class PeqFilterType(val nativeId: Int, val code: String, val hasGain: Boolean) {
    PEAKING(0, "PK", true),
    LOW_SHELF(1, "LSC", true),
    HIGH_SHELF(2, "HSC", true),
    LOW_PASS(3, "LPQ", false),
    HIGH_PASS(4, "HPQ", false),
    ;

    companion object {
        fun fromCode(code: String): PeqFilterType? = when (code.uppercase()) {
            "PK", "PEQ", "PEAK", "PEAKING" -> PEAKING
            "LSC", "LS", "LSQ", "LOWSHELF" -> LOW_SHELF
            "HSC", "HS", "HSQ", "HIGHSHELF" -> HIGH_SHELF
            "LP", "LPQ", "LOWPASS" -> LOW_PASS
            "HP", "HPQ", "HIGHPASS" -> HIGH_PASS
            else -> null
        }
    }
}

@Serializable
data class PeqFilter(
    val type: PeqFilterType = PeqFilterType.PEAKING,
    val frequencyHz: Double = 1000.0,
    val gainDb: Double = 0.0,
    val q: Double = 0.7,
    val enabled: Boolean = true,
)

@Serializable
data class PeqSettings(
    val preampDb: Double = 0.0,
    val filters: List<PeqFilter> = emptyList(),
) {
    val isFlat: Boolean
        get() = kotlin.math.abs(preampDb) < 0.001 && filters.none {
            it.enabled && (!it.type.hasGain || kotlin.math.abs(it.gainDb) >= 0.001)
        }

    companion object {
        const val MAX_FILTERS = 20
    }
}

/** Biquad magnitude math, identical to the RBJ designs in ParametricEq.cpp. */
object PeqMath {
    const val DEFAULT_SAMPLE_RATE = 48_000.0

    fun filterDb(filter: PeqFilter, frequency: Double, sampleRate: Double = DEFAULT_SAMPLE_RATE): Double {
        if (!filter.enabled) return 0.0
        if (filter.type.hasGain && kotlin.math.abs(filter.gainDb) < 0.001) return 0.0
        if (filter.frequencyHz >= sampleRate * 0.49) return 0.0
        val c = coefficients(filter, sampleRate)
        val w = 2.0 * PI * frequency.coerceIn(1.0, sampleRate * 0.49) / sampleRate
        val cos1 = cos(w)
        val sin1 = sin(w)
        val cos2 = cos(2 * w)
        val sin2 = sin(2 * w)
        val nr = c[0] + c[1] * cos1 + c[2] * cos2
        val ni = -c[1] * sin1 - c[2] * sin2
        val dr = 1.0 + c[3] * cos1 + c[4] * cos2
        val di = -c[3] * sin1 - c[4] * sin2
        val mag = sqrt((nr * nr + ni * ni) / max(dr * dr + di * di, 1e-24))
        return 20.0 * log10(max(mag, 1e-12))
    }

    /** Total response in dB (preamp included). */
    fun responseDb(settings: PeqSettings, frequency: Double, sampleRate: Double = DEFAULT_SAMPLE_RATE): Double =
        settings.preampDb + settings.filters.sumOf { filterDb(it, frequency, sampleRate) }

    fun filtersDb(filters: List<PeqFilter>, frequency: Double, sampleRate: Double = DEFAULT_SAMPLE_RATE): Double =
        filters.sumOf { filterDb(it, frequency, sampleRate) }

    /** AutoEQ's rule: negative preamp equal to the largest boost, rounded down to 0.1 dB. */
    fun autoPreampDb(filters: List<PeqFilter>, grid: DoubleArray = LOG_GRID): Double {
        val maxBoost = grid.maxOf { filtersDb(filters, it) }
        if (maxBoost <= 0.0) return 0.0
        return -(kotlin.math.ceil(maxBoost * 10.0) / 10.0)
    }

    /** b0, b1, b2, a1, a2 normalised by a0. */
    private fun coefficients(filter: PeqFilter, sampleRate: Double): DoubleArray {
        val f = filter.frequencyHz.coerceIn(10.0, sampleRate * 0.49)
        val w = 2.0 * PI * f / sampleRate
        val cs = cos(w)
        val alpha = sin(w) / (2.0 * max(filter.q, 0.05))
        val a = 10.0.pow(filter.gainDb / 40.0)
        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double
        when (filter.type) {
            PeqFilterType.LOW_SHELF -> {
                val k = 2 * sqrt(a) * alpha
                b0 = a * ((a + 1) - (a - 1) * cs + k)
                b1 = 2 * a * ((a - 1) - (a + 1) * cs)
                b2 = a * ((a + 1) - (a - 1) * cs - k)
                a0 = (a + 1) + (a - 1) * cs + k
                a1 = -2 * ((a - 1) + (a + 1) * cs)
                a2 = (a + 1) + (a - 1) * cs - k
            }
            PeqFilterType.HIGH_SHELF -> {
                val k = 2 * sqrt(a) * alpha
                b0 = a * ((a + 1) + (a - 1) * cs + k)
                b1 = -2 * a * ((a - 1) + (a + 1) * cs)
                b2 = a * ((a + 1) + (a - 1) * cs - k)
                a0 = (a + 1) - (a - 1) * cs + k
                a1 = 2 * ((a - 1) - (a + 1) * cs)
                a2 = (a + 1) - (a - 1) * cs - k
            }
            PeqFilterType.LOW_PASS -> {
                b0 = (1 - cs) / 2
                b1 = 1 - cs
                b2 = (1 - cs) / 2
                a0 = 1 + alpha
                a1 = -2 * cs
                a2 = 1 - alpha
            }
            PeqFilterType.HIGH_PASS -> {
                b0 = (1 + cs) / 2
                b1 = -(1 + cs)
                b2 = (1 + cs) / 2
                a0 = 1 + alpha
                a1 = -2 * cs
                a2 = 1 - alpha
            }
            PeqFilterType.PEAKING -> {
                b0 = 1 + alpha * a
                b1 = -2 * cs
                b2 = 1 - alpha * a
                a0 = 1 + alpha / a
                a1 = -2 * cs
                a2 = 1 - alpha / a
            }
        }
        return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /** 1/12-octave grid, 20 Hz .. 20.48 kHz (same as the bundled target deltas). */
    val LOG_GRID: DoubleArray = DoubleArray(121) { 20.0 * 2.0.pow(it / 12.0) }

    /** Low shelf used for the bass adjustment (same shape as AutoEQ's 105 Hz shelf). */
    fun bassShelfDb(gainDb: Double, frequency: Double): Double =
        filterDb(PeqFilter(PeqFilterType.LOW_SHELF, 105.0, gainDb, 0.7), frequency)

    /** Tilt around 1 kHz in dB per octave. */
    fun tiltDb(dbPerOctave: Double, frequency: Double): Double =
        dbPerOctave * kotlin.math.ln(frequency / 1000.0) / kotlin.math.ln(2.0)

    fun roundTo(value: Double, step: Double): Double = floor(value / step + 0.5) * step
}

/** Reads and writes the AutoEQ / Equalizer APO "ParametricEQ.txt" format. */
object ParametricEqText {
    private val PREAMP = Regex("""^\s*Preamp:\s*([-+]?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
    private val FILTER = Regex(
        """^\s*Filter\s*\d*\s*:\s*(ON|OFF)\s+([A-Z]+)\s+Fc\s+([-+]?\d+(?:\.\d+)?)\s*Hz""" +
            """(?:\s+Gain\s+([-+]?\d+(?:\.\d+)?)\s*dB)?(?:\s+Q\s+([-+]?\d+(?:\.\d+)?))?""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): PeqSettings? {
        var preamp = 0.0
        val filters = mutableListOf<PeqFilter>()
        for (line in text.lineSequence()) {
            PREAMP.find(line)?.let { preamp = it.groupValues[1].toDouble() }
            val match = FILTER.find(line) ?: continue
            val type = PeqFilterType.fromCode(match.groupValues[2]) ?: continue
            val fc = match.groupValues[3].toDoubleOrNull() ?: continue
            val gain = match.groupValues[4].toDoubleOrNull() ?: 0.0
            val q = match.groupValues[5].toDoubleOrNull() ?: 0.7071
            filters += PeqFilter(
                type = type,
                frequencyHz = fc,
                gainDb = gain,
                q = q,
                enabled = match.groupValues[1].equals("ON", ignoreCase = true),
            )
            if (filters.size >= PeqSettings.MAX_FILTERS) break
        }
        if (filters.isEmpty()) return null
        return PeqSettings(preampDb = preamp, filters = filters)
    }

    fun format(settings: PeqSettings): String = buildString {
        appendLine("Preamp: %.1f dB".format(java.util.Locale.ROOT, settings.preampDb))
        settings.filters.forEachIndexed { index, f ->
            append("Filter ${index + 1}: ${if (f.enabled) "ON" else "OFF"} ${f.type.code} ")
            append("Fc %.0f Hz".format(java.util.Locale.ROOT, f.frequencyHz))
            if (f.type.hasGain) append(" Gain %.1f dB".format(java.util.Locale.ROOT, f.gainDb))
            appendLine(" Q %.2f".format(java.util.Locale.ROOT, f.q))
        }
    }
}
