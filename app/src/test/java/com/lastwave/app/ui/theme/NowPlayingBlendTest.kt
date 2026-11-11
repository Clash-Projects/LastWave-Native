package com.lastwave.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NowPlayingBlendTest {
    private val base = Md3SchemeBuilder.buildDarkScheme("#3060E0", amoled = false) // wallpaper blue
    private val art = Md3SchemeBuilder.buildDarkScheme("#E03030", amoled = false) // artwork red

    private fun dist(a: Color, b: Color) = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)

    @Test
    fun baseThemeStaysInTheLead() {
        val blended = Md3SchemeBuilder.blendNowPlaying(base, art)
        assertThat(dist(blended.primary, base.primary)).isLessThan(dist(blended.primary, art.primary))
        assertThat(dist(blended.background, base.background)).isLessThan(dist(blended.background, art.background))
    }

    @Test
    fun accentsKeepReadableContrast() {
        val blended = Md3SchemeBuilder.blendNowPlaying(base, art)
        assertThat(Md3SchemeBuilder.contrast(blended.primary, blended.onPrimary)).isAtLeast(4.5)
        assertThat(Md3SchemeBuilder.contrast(blended.primaryContainer, blended.onPrimaryContainer)).isAtLeast(4.5)
    }

    @Test
    fun amoledBlackIsNeverTinted() {
        val amoledBase = Md3SchemeBuilder.buildDarkScheme("#3060E0", amoled = true)
        val blended = Md3SchemeBuilder.blendNowPlaying(amoledBase, art)
        assertThat(blended.background).isEqualTo(Color.Black)
        assertThat(blended.surface).isEqualTo(Color.Black)
    }
}
