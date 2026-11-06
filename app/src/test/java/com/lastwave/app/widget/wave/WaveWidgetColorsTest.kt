package com.lastwave.app.widget.wave

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WaveWidgetColorsTest {
    @Test
    fun derive_enforcesReadableForegroundsForDarkAndLightArtwork() {
        listOf(0xFF101014.toInt(), 0xFFF5E8D0.toInt(), 0xFF7755CC.toInt(), 0xFF187A55.toInt()).forEach { swatch ->
            listOf(false, true).forEach { dark ->
                val colors = WaveWidgetColors.derive(swatch, dark)
                assertThat(WaveColorMath.contrastRatio(colors.onBackground, colors.background)).isAtLeast(4.5)
                assertThat(WaveColorMath.contrastRatio(colors.muted, colors.background)).isAtLeast(4.5)
                assertThat(WaveColorMath.contrastRatio(colors.onAccent, colors.accent)).isAtLeast(4.5)
            }
        }
    }
}
