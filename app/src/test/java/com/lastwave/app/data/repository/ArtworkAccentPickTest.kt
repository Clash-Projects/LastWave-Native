package com.lastwave.app.data.repository

import android.graphics.Bitmap
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
        )!!
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
        )!!
        assertThat(seed).isEqualTo("#1E3A8A")
    }

    @Test
    fun largerAreaBeatsSlightlyMoreVividSmallArea() {
        val seed = pickArtworkAccent(
            listOf(
                rgb(0x8A5A3C) to 5000, // muted brown, most of the cover
                rgb(0xFF3060) to 800,  // vivid pink accent
            ),
        )!!
        assertThat(seed).isEqualTo("#8A5A3C")
    }

    @Test
    fun emptyPaletteGivesNoSeed() {
        assertThat(pickArtworkAccent(emptyList())).isNull()
    }

    @Test
    fun dominantShadedFamilyBeatsSmallFlatLogo() {
        val seed = pickArtworkAccent(listOf(
            rgb(0x143268) to 2200,
            rgb(0x23467E) to 2200,
            rgb(0x305B98) to 2200,
            rgb(0xE02020) to 3000,
        ))!!
        assertThat(Md3SchemeBuilder.hueOf(seed)).isIn(210..230)
    }

    @Test
    fun populationWinsEvenWhenMinorityIsMuchMoreSaturated() {
        val seed = pickArtworkAccent(listOf(rgb(0x607D9B) to 5000, rgb(0xFF2020) to 4000))
        assertThat(seed).isEqualTo("#607D9B")
    }

    @Test
    fun deepNavyIsNotDiscardedInFavourOfRedLogo() {
        val seed = pickArtworkAccent(listOf(rgb(0x061A30) to 8000, rgb(0xE02020) to 600))
        assertThat(seed).isEqualTo("#061A30")
    }

    @Test
    fun nearBlackAndNearWhiteCompressionTintHasNoHue() {
        val seed = pickArtworkAccent(listOf(rgb(0x080208) to 5000, rgb(0xFEFAF9) to 4000))!!
        assertThat(Md3SchemeBuilder.isAchromatic(seed)).isTrue()
    }

    @Test
    fun scatteredColourNoiseDoesNotRecolourGreyCover() {
        val seed = pickArtworkAccent(listOf(
            rgb(0x777777) to 8500,
            rgb(0xFF2020) to 500,
            rgb(0x20FF20) to 500,
            rgb(0x2020FF) to 500,
        ))!!
        assertThat(Md3SchemeBuilder.isAchromatic(seed)).isTrue()
    }

    @Test
    fun transparentPixelsCannotContributeHiddenColour() {
        val pixels = IntArray(100) { if (it < 90) 0x00FF0000 else rgb(0x1E3A8A) }
        val bitmap = Bitmap.createBitmap(pixels, 10, 10, Bitmap.Config.ARGB_8888)
        try {
            assertThat(Md3SchemeBuilder.hueOf(extractArtworkAccent(bitmap)!!)).isIn(220..230)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun fullyTransparentImageHasNoSeed() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            assertThat(extractArtworkAccent(bitmap)).isNull()
        } finally {
            bitmap.recycle()
        }
    }
}
