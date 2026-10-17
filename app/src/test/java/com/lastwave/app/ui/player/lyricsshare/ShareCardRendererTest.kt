package com.lastwave.app.ui.player.lyricsshare

import android.content.Context
import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ShareCardRendererTest {

    private lateinit var context: Context
    private lateinit var sampleCover: Bitmap
    private lateinit var sampleBlur: Bitmap

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        sampleCover = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF2563EB.toInt())
        }
        sampleBlur = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF1E293B.toInt())
        }
    }

    @Test
    fun renderSongCard_withCoverArt_rendersSuccessfully() = runTest {
        val spec = ShareCardSpec(
            mode = CardMode.Song,
            lines = listOf(
                ShareLine("I feel it coming", isRtl = false),
                ShareLine("I feel it coming, babe", isRtl = false),
            ),
            title = "I Feel It Coming",
            artist = "The Weeknd ft. Daft Punk",
            coverBitmap = sampleCover,
            swatchColor = ColorContrastHelper.BRAND_LIME,
            targetWidth = 720,
        )

        val bitmap = ShareCardRenderer.renderCard(context, spec)

        assertThat(bitmap).isNotNull()
        assertThat(bitmap.width).isEqualTo(720)
        assertThat(bitmap.height).isAtLeast(720 * 5 / 4)
        assertThat(bitmap.height).isAtMost(720 * 16 / 9)
    }

    @Test
    fun renderSongCard_noArt_rendersWithFallback() = runTest {
        val spec = ShareCardSpec(
            mode = CardMode.Song,
            lines = listOf(
                ShareLine("No artwork line 1", isRtl = false),
                ShareLine("No artwork line 2", isRtl = false),
            ),
            title = "Acapella Song",
            artist = "Vocalist",
            coverBitmap = null,
            swatchColor = 0xFF12161A.toInt(),
            targetWidth = 720,
        )

        val bitmap = ShareCardRenderer.renderCard(context, spec)

        assertThat(bitmap).isNotNull()
        assertThat(bitmap.width).isEqualTo(720)
    }

    @Test
    fun renderLyricsCard_withBlurredBackground_rendersSuccessfully() = runTest {
        val spec = ShareCardSpec(
            mode = CardMode.Lyrics,
            lines = listOf(
                ShareLine("First verse line", isRtl = false),
                ShareLine("Second verse line", isRtl = false),
                ShareLine("Chorus climax line", isRtl = false),
            ),
            title = "Blinding Lights",
            artist = "The Weeknd",
            coverBitmap = sampleCover,
            blurredBackgroundBitmap = sampleBlur,
            swatchColor = ColorContrastHelper.BRAND_LIME,
            targetWidth = 720,
        )

        val bitmap = ShareCardRenderer.renderCard(context, spec)

        assertThat(bitmap).isNotNull()
        assertThat(bitmap.width).isEqualTo(720)
        assertThat(bitmap.height).isAtLeast(720 * 5 / 4)
    }

    @Test
    fun renderLyricsCard_fallbackBackdrop_rendersSuccessfully() = runTest {
        val spec = ShareCardSpec(
            mode = CardMode.Lyrics,
            lines = listOf(
                ShareLine("Solo line", isRtl = false),
            ),
            title = "Acoustic Solo",
            artist = "Guitarist",
            coverBitmap = null,
            blurredBackgroundBitmap = null,
            swatchColor = 0xFF0F172A.toInt(),
            targetWidth = 720,
        )

        val bitmap = ShareCardRenderer.renderCard(context, spec)

        assertThat(bitmap).isNotNull()
        assertThat(bitmap.width).isEqualTo(720)
    }

    @Test
    fun renderMultilingual_latinDevanagariArabicCjkAndLongLines() = runTest {
        val multilingualLines = listOf(
            ShareLine("Never gonna give you up (Latin)", isRtl = false),
            ShareLine("तू ही मेरी मंज़िल, पहचान भी तू (Devanagari)", isRtl = false),
            ShareLine("أراك عصي الدمع شيمتك الصبر (Arabic)", isRtl = true),
            ShareLine("夜に駆ける 沈むように溶けてゆくように (CJK)", isRtl = false),
            ShareLine(
                "This is an exceptionally long lyric line designed specifically to wrap onto multiple lines and test StaticLayout bounds handling without clipping or overflow",
                isRtl = false,
            ),
        )

        val spec = ShareCardSpec(
            mode = CardMode.Lyrics,
            lines = multilingualLines,
            title = "Global Harmony",
            artist = "World Artists Ensemble",
            coverBitmap = sampleCover,
            blurredBackgroundBitmap = sampleBlur,
            swatchColor = ColorContrastHelper.BRAND_LIME,
            targetWidth = 720,
        )

        val bitmap = ShareCardRenderer.renderCard(context, spec)

        assertThat(bitmap).isNotNull()
        assertThat(bitmap.width).isEqualTo(720)
        assertThat(bitmap.height).isAtLeast(720 * 5 / 4)
        assertThat(bitmap.height).isAtMost(720 * 16 / 9)
    }
}
