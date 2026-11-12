package com.lastwave.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArtworkSchemeTest {
    private fun hex(c: Color) = "#%02X%02X%02X".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
    private fun spread(c: Color) = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)

    @Test
    fun accentsFollowTheCoverHueAndStayReadable() {
        val (dark, light) = Md3SchemeBuilder.buildArtworkSchemes("#1E3A8A", null, amoled = false)
        for (scheme in listOf(dark, light)) {
            assertThat(hueDelta(Md3SchemeBuilder.hueOf(hex(scheme.primary)), Md3SchemeBuilder.hueOf("#1E3A8A"))).isAtMost(3)
            assertThat(Md3SchemeBuilder.contrast(scheme.primary, scheme.onPrimary)).isAtLeast(4.5)
            assertThat(Md3SchemeBuilder.contrast(scheme.primaryContainer, scheme.onPrimaryContainer)).isAtLeast(4.5)
        }
    }

    @Test
    fun vividCoverGivesMoreVividAccentsThanMutedCover() {
        val vivid = Md3SchemeBuilder.buildArtworkSchemes("#E02040", null, amoled = false).first
        val muted = Md3SchemeBuilder.buildArtworkSchemes("#8A6A70", null, amoled = false).first
        assertThat(spread(vivid.primary)).isGreaterThan(spread(muted.primary))
    }

    @Test
    fun secondCoverColourBecomesTheTertiary() {
        val dark = Md3SchemeBuilder.buildArtworkSchemes("#1E3A8A", "#D08020", amoled = false).first
        assertThat(hueDelta(Md3SchemeBuilder.hueOf(hex(dark.tertiary)), Md3SchemeBuilder.hueOf("#D08020"))).isAtMost(3)
    }

    @Test
    fun greyCoverIsMonochromeAndAmoledStaysBlack() {
        val grey = Md3SchemeBuilder.buildArtworkSchemes("#777777", null, amoled = false).first
        assertThat(spread(grey.primary)).isEqualTo(0f)
        val amoled = Md3SchemeBuilder.buildArtworkSchemes("#1E3A8A", null, amoled = true).first
        assertThat(amoled.background).isEqualTo(Color.Black)
    }
}
