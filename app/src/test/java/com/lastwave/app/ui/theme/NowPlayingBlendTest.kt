package com.lastwave.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NowPlayingBlendTest {
    private fun saturation(color: Color): Float = FloatArray(3).also {
        ColorUtils.colorToHSL(color.toArgb(), it)
    }[1]

    @Test
    fun grayscaleArtworkStaysGrayscaleAcrossEveryDecorativeRole() {
        for (dark in listOf(true, false)) {
            val scheme = Md3SchemeBuilder.buildArtworkScheme("#777777", amoled = false, dark = dark)
            listOf(
                scheme.primary, scheme.onPrimary, scheme.primaryContainer, scheme.onPrimaryContainer,
                scheme.secondary, scheme.tertiary, scheme.inversePrimary, scheme.surfaceTint,
                scheme.background, scheme.onBackground, scheme.surface, scheme.onSurface,
                scheme.surfaceContainer, scheme.surfaceVariant, scheme.outline,
            ).forEach { assertThat(saturation(it)).isEqualTo(0f) }
        }
    }

    @Test
    fun singleHueArtworkDoesNotInventTertiaryHue() {
        val scheme = Md3SchemeBuilder.buildArtworkScheme("#1E3A8A", amoled = false, dark = true)
        val hues = listOf(scheme.primary, scheme.secondary, scheme.tertiary).map {
            FloatArray(3).also { hsl -> ColorUtils.colorToHSL(it.toArgb(), hsl) }[0]
        }
        assertThat(hues.max() - hues.min()).isLessThan(5f)
    }

    @Test
    fun mutedArtworkDoesNotGainSaturation() {
        val scheme = Md3SchemeBuilder.buildArtworkScheme("#657585", amoled = false, dark = true)
        assertThat(saturation(scheme.primary)).isAtMost(0.16f)
    }
    }

    @Test
    fun accentsKeepReadableContrast() {
        for (dark in listOf(true, false)) {
            for (seed in listOf("#E03030", "#777777", "#1E3A8A")) {
                val scheme = Md3SchemeBuilder.buildArtworkScheme(seed, amoled = false, dark = dark)
                assertThat(Md3SchemeBuilder.contrast(scheme.primary, scheme.onPrimary)).isAtLeast(4.5)
                assertThat(Md3SchemeBuilder.contrast(scheme.primaryContainer, scheme.onPrimaryContainer)).isAtLeast(4.5)
            }
        }
    }

    @Test
    fun amoledBlackIsNeverTinted() {
        val scheme = Md3SchemeBuilder.buildArtworkScheme("#E03030", amoled = true, dark = true)
        assertThat(scheme.background).isEqualTo(Color.Black)
        assertThat(scheme.surface).isEqualTo(Color.Black)
    }
}
