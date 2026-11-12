package com.lastwave.app.data.autoeq

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class AutoEqTest {

    private val hd600 = """
        Preamp: -6.3 dB
        Filter 1: ON LSC Fc 105 Hz Gain 6.5 dB Q 0.70
        Filter 2: ON PK Fc 125 Hz Gain -2.7 dB Q 0.55
        Filter 3: ON PK Fc 8445 Hz Gain 3.3 dB Q 1.61
        Filter 4: ON PK Fc 522 Hz Gain 0.7 dB Q 1.02
        Filter 5: ON PK Fc 1298 Hz Gain -1.2 dB Q 2.14
        Filter 6: ON HSC Fc 10000 Hz Gain -3.1 dB Q 0.70
        Filter 7: ON PK Fc 3158 Hz Gain -1.8 dB Q 3.67
        Filter 8: ON PK Fc 2166 Hz Gain 0.9 dB Q 3.32
        Filter 9: ON PK Fc 6639 Hz Gain 2.2 dB Q 5.82
        Filter 10: ON PK Fc 5433 Hz Gain -1.2 dB Q 5.70
    """.trimIndent()

    @Test
    fun parsesAutoEqParametricFile() {
        val settings = ParametricEqText.parse(hd600)!!
        assertThat(settings.preampDb).isWithin(1e-9).of(-6.3)
        assertThat(settings.filters).hasSize(10)
        assertThat(settings.filters.first()).isEqualTo(PeqFilter(PeqFilterType.LOW_SHELF, 105.0, 6.5, 0.70))
        assertThat(settings.filters[5].type).isEqualTo(PeqFilterType.HIGH_SHELF)
        // Round trip through the writer.
        assertThat(ParametricEqText.parse(ParametricEqText.format(settings))).isEqualTo(settings)
    }

    @Test
    fun ignoresGarbageAndKeepsOffFilters() {
        val text = "Preamp: -1 dB\nFilter 1: OFF PK Fc 1000 Hz Gain 3 dB Q 1\nhello\nFilter 2: ON XX Fc 1 Hz"
        val settings = ParametricEqText.parse(text)!!
        assertThat(settings.filters).hasSize(1)
        assertThat(settings.filters[0].enabled).isFalse()
        assertThat(ParametricEqText.parse("nothing here")).isNull()
    }

    @Test
    fun biquadMagnitudesMatchTheirDesign() {
        val pk = PeqFilter(PeqFilterType.PEAKING, 1000.0, 6.0, 1.0)
        assertThat(PeqMath.filterDb(pk, 1000.0)).isWithin(0.01).of(6.0)
        assertThat(PeqMath.filterDb(pk, 50.0)).isWithin(0.1).of(0.0)
        val ls = PeqFilter(PeqFilterType.LOW_SHELF, 105.0, 6.5, 0.7)
        assertThat(PeqMath.filterDb(ls, 20.0)).isWithin(0.4).of(6.5)
        assertThat(PeqMath.filterDb(ls, 5000.0)).isWithin(0.1).of(0.0)
        val hs = PeqFilter(PeqFilterType.HIGH_SHELF, 10000.0, -3.0, 0.7)
        assertThat(PeqMath.filterDb(hs, 19000.0)).isWithin(0.4).of(-3.0)
        assertThat(PeqMath.filterDb(hs, 500.0)).isWithin(0.1).of(0.0)
        // Disabled or flat filters contribute nothing.
        assertThat(PeqMath.filterDb(pk.copy(enabled = false), 1000.0)).isEqualTo(0.0)
        assertThat(PeqSettings(0.0, listOf(pk.copy(gainDb = 0.0))).isFlat).isTrue()
    }

    @Test
    fun autoPreampMatchesAutoEqWithinATenth() {
        val settings = ParametricEqText.parse(hd600)!!
        assertThat(PeqMath.autoPreampDb(settings.filters)).isWithin(0.15).of(-6.3)
    }

    @Test
    fun fitterReproducesAPublishedProfile() {
        val profile = ParametricEqText.parse(hd600)!!
        val desired = DoubleArray(PeqMath.LOG_GRID.size) { PeqMath.filtersDb(profile.filters, PeqMath.LOG_GRID[it]) }
        val fitted = PeqFitter.fit(desired)
        assertThat(fitted.filters).hasSize(10)
        assertThat(PeqFitter.rmsError(fitted, desired)).isLessThan(0.5)
        assertThat(fitted.preampDb).isAtMost(0.0)
    }

    @Test
    fun fitterFollowsABassAndTiltChange() {
        val profile = ParametricEqText.parse(hd600)!!
        val grid = PeqMath.LOG_GRID
        val desired = DoubleArray(grid.size) {
            PeqMath.filtersDb(profile.filters, grid[it]) + PeqMath.bassShelfDb(4.0, grid[it]) + PeqMath.tiltDb(-0.5, grid[it])
        }
        val fitted = PeqFitter.fit(desired)
        assertThat(PeqFitter.rmsError(fitted, desired)).isLessThan(0.8)
        // Bass really went up at 30 Hz compared to the original profile.
        val before = PeqMath.filtersDb(profile.filters, 30.0)
        val after = PeqMath.filtersDb(fitted.filters, 30.0)
        assertThat(after - before).isGreaterThan(3.0)
    }

    @Test
    fun parsesIndexLines() {
        val md = """
            # Index
            - [1MORE Aero (ANC Off)](./HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)) by HypetheSonics on GRAS RA0045
            - [Sennheiser HD 600](./oratory1990/over-ear/Sennheiser%20HD%20600) by oratory1990
            - [AKG K371](./crinacle/GRAS%2043AG-7%20over-ear/AKG%20K371) by crinacle on GRAS 43AG-7
            not a line
        """.trimIndent()
        val entries = AutoEqIndexParser.parse(md)
        assertThat(entries).hasSize(3)
        val aero = entries[0]
        assertThat(aero.name).isEqualTo("1MORE Aero (ANC Off)")
        assertThat(aero.source).isEqualTo("HypetheSonics")
        assertThat(aero.rig).isEqualTo("GRAS RA0045")
        assertThat(aero.folderName).isEqualTo("1MORE Aero (ANC Off)")
        assertThat(aero.formFactor).isEqualTo(FormFactor.IN_EAR)
        val hd = entries[1]
        assertThat(hd.rig).isNull()
        assertThat(hd.formFactor).isEqualTo(FormFactor.OVER_EAR)
        assertThat(AutoEqIndexParser.encodeSegment("${hd.folderName} ParametricEQ.txt"))
            .isEqualTo("Sennheiser%20HD%20600%20ParametricEQ.txt")
        val found = AutoEqIndexParser.search(entries, "hd 600")
        assertThat(found.map { it.name }).containsExactly("Sennheiser HD 600")
        assertThat(AutoEqIndexParser.search(entries, "crinacle").single().name).isEqualTo("AKG K371")
    }

    @Test
    fun parsesMeasurementCsv() {
        val csv = "frequency,raw,smoothed,error,error_smoothed,equalization,parametric_eq,fixed_band_eq,equalized_raw,equalized_smoothed,target\n" +
            "20.00,-7.88,-7.89,-11.31,-11.32,6.00,6.29,2.78,-1.88,-1.89,3.43\n" +
            "20.20,-7.85,-7.85,-11.31,-11.31,6.00,6.29,2.86,-1.85,-1.85,3.46\n" +
            "20.40,-7.80,-7.85,-11.31,-11.31,6.00,6.29,2.86,-1.85,-1.85,3.50\n"
        val m = AutoEqIndexParser.parseMeasurementCsv(csv)!!
        assertThat(m.frequencies.toList()).containsExactly(20.0, 20.2, 20.4).inOrder()
        assertThat(m.raw[0]).isWithin(1e-9).of(-7.88)
        assertThat(m.target[2]).isWithin(1e-9).of(3.50)
    }

    @Test
    fun logGridMatchesTheBundledTargets() {
        assertThat(PeqMath.LOG_GRID).hasLength(121)
        assertThat(PeqMath.LOG_GRID.first()).isWithin(1e-9).of(20.0)
        assertThat(abs(PeqMath.LOG_GRID[120] - 20480.0)).isLessThan(0.01)
    }

    @Test
    fun bundledTargetsAreOnTheSameGrid() {
        val file = java.io.File("src/main/assets/autoeq_targets.json")
        val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(AutoEqTargetFile.serializer(), file.readText())
        assertThat(parsed.frequencies).hasSize(PeqMath.LOG_GRID.size)
        parsed.frequencies.forEachIndexed { i, f -> assertThat(f).isWithin(0.01).of(PeqMath.LOG_GRID[i]) }
        assertThat(parsed.targets.size).isAtLeast(10)
        parsed.targets.forEach { t ->
            assertThat(t.deltaOverEar).hasSize(121)
            assertThat(t.deltaInEar).hasSize(121)
        }
        // The over-ear reference has no delta against itself.
        assertThat(parsed.targets.first { it.id == "harman_oe_2018" }.deltaOverEar.maxOf { abs(it) }).isLessThan(0.01)
    }
}
