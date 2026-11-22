package com.lastwave.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.ui.theme.Md3SchemeBuilder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ArtworkAccentPickTest {
    private fun rgb(hex: Long) = (hex or 0xFF000000).toInt()

    @Test
    fun blackAndWhiteCoverWithTinyRedLogoIsMonochrome() {
        val seed = pickArtworkAccent(
            listOf(
                rgb(0x101010) to 5000, // black
                rgb(0xF0F0F0) to 3000, // white
                rgb(0x777777) to 1500, // grey
                rgb(0xE02020) to 60,   // small red logo
            ),
        )!!.seed
        assertThat(Md3SchemeBuilder.isAchromatic(seed)).isTrue()
    }

    @Test
    fun dominantColourWinsOverNoise() {
        val seed = pickArtworkAccent(
            listOf(
                rgb(0x1E3A8A) to 6000, // deep blue sleeve
                rgb(0x0A0A0A) to 2000,
                rgb(0x22DD22) to 40,   // green artefact
            ),
        )!!.seed
        assertThat(seed).isEqualTo("#1E3A8A")
    }

    @Test
    fun largerAreaBeatsSlightlyMoreVividSmallArea() {
        val seed = pickArtworkAccent(
            listOf(
                rgb(0x8A5A3C) to 5000, // muted brown, most of the cover
                rgb(0xFF3060) to 800,  // vivid pink accent
            ),
        )!!.seed
        assertThat(seed).isEqualTo("#8A5A3C")
    }

    @Test
    fun secondColourIsADifferentHueNotAShadeOfTheSeed() {
        val accent = pickArtworkAccent(
            listOf(
                rgb(0x1E3A8A) to 5000, // blue
                rgb(0x2A4FB0) to 2500, // lighter blue: same hue
                rgb(0xD08020) to 1500, // orange
            ),
        )!!
        assertThat(accent.seed).isEqualTo("#1E3A8A")
        assertThat(accent.second).isEqualTo("#D08020")
    }

    @Test
    fun emptyPaletteGivesNoSeed() {
        assertThat(pickArtworkAccent(emptyList())).isNull()
    }
}
