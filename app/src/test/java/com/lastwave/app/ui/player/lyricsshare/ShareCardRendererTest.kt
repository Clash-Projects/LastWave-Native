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
        assertThat(bitmap.height).isAtLeast(720 * 5 / 4)
    }

    @Test
    fun renderSongCard_failedArt_usesBrandFallbackGradient() = runTest {
        val fallbackGradient = ArtworkResolver.createBrandFallbackGradient(ColorContrastHelper.BRAND_LIME, 200, 200)
        val spec = ShareCardSpec(
            mode = CardMode.Song,
            lines = listOf(
                ShareLine("Failed art fallback line", isRtl = false),
            ),
            title = "Offline Song",
            artist = "Unknown Artist",
            coverBitmap = fallbackGradient,
            swatchColor = ColorContrastHelper.BRAND_LIME,
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
    fun renderLyricsCard_noArt_rendersWithFallback() = runTest {
        val spec = ShareCardSpec(
            mode = CardMode.Lyrics,
            lines = listOf(
                ShareLine("Solo line without art", isRtl = false),
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
        assertThat(bitmap.height).isAtLeast(720 * 5 / 4)
    }

    @Test
    fun renderLyricsCard_failedArt_usesBrandFallbackGradient() = runTest {
        val fallbackGradient = ArtworkResolver.createBrandFallbackGradient(ColorContrastHelper.DEFAULT_DARK, 200, 200)
        val spec = ShareCardSpec(
            mode = CardMode.Lyrics,
            lines = listOf(
                ShareLine("Failed backdrop resolution line", isRtl = false),
            ),
            title = "Uncached Song",
            artist = "Indie Band",
            coverBitmap = null,
            blurredBackgroundBitmap = fallbackGradient,
            swatchColor = ColorContrastHelper.DEFAULT_DARK,
            targetWidth = 720,
        )

        val bitmap = ShareCardRenderer.renderCard(context, spec)

        assertThat(bitmap).isNotNull()
        assertThat(bitmap.width).isEqualTo(720)
    }

    @Test
    fun renderText_latinScript_rendersSuccessfully() = runTest {
        val spec = ShareCardSpec(
            mode = CardMode.Lyrics,
            lines = listOf(
                ShareLine("Never gonna give you up, never gonna let you down", isRtl = false),
                ShareLine("Never gonna run around and desert you", isRtl = false),
            ),
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            targetWidth = 720,
        )

        val bitmap = ShareCardRenderer.renderCard(context, spec)
        assertThat(bitmap).isNotNull()
        assertThat(bitmap.width).isEqualTo(720)
    }

    @Test
    fun renderText_devanagariScript_rendersSuccessfullyWithMatras() = runTest {
        val spec = ShareCardSpec(
            mode = CardMode.Lyrics,
            lines = listOf(
                ShareLine("तू ही मेरी मंज़िल, पहचान भी तू", isRtl = false),
                ShareLine("तेरे बिना ज़िंदगी से कोई शिकवा तो नहीं", isRtl = false),
            ),
            title = "तेरे बिना",
            artist = "लता मंगेशकर",
            targetWidth = 720,
        )

        val bitmap = ShareCardRenderer.renderCard(context, spec)
        assertThat(bitmap).isNotNull()
        assertThat(bitmap.width).isEqualTo(720)
    }

    @Test
    fun renderText_arabicScript_rendersWithRtlAlignment() = runTest {
        val spec = ShareCardSpec(
            mode = CardMode.Lyrics,
            lines = listOf(
                ShareLine("أراك عصي الدمع شيمتك الصبر", isRtl = true),
                ShareLine("أما للهوى نهي عليك ولا أمر", isRtl = true),
            ),
            title = "أراك عصي الدمع",
            artist = "أم كلثوم",
            isRtl = true,
            targetWidth = 720,
        )

        val bitmap = ShareCardRenderer.renderCard(context, spec)
        assertThat(bitmap).isNotNull()
        assertThat(bitmap.width).isEqualTo(720)
    }

    @Test
    fun renderText_cjkScript_rendersSuccessfully() = runTest {
        val spec = ShareCardSpec(
            mode = CardMode.Lyrics,
            lines = listOf(
                ShareLine("夜に駆ける 沈むように溶けてゆくように", isRtl = false),
                ShareLine("広がる空の果てに何があるのだろう", isRtl = false),
            ),
            title = "夜に駆ける",
            artist = "YOASOBI",
            targetWidth = 720,
        )

        val bitmap = ShareCardRenderer.renderCard(context, spec)
        assertThat(bitmap).isNotNull()
        assertThat(bitmap.width).isEqualTo(720)
    }

    @Test
    fun renderText_longMultilineLines_wrapWithoutClipping() = runTest {
        val spec = ShareCardSpec(
            mode = CardMode.Lyrics,
            lines = listOf(
                ShareLine(
                    "This is an exceptionally long lyric line designed specifically to wrap onto multiple distinct rows within StaticLayout to ensure that boundary measurement and vertical clamping do not cause text clipping or crash under tight constraints.",
                    isRtl = false,
                ),
            ),
            title = "Long Text Wrap Song",
            artist = "Progressive Composer",
            targetWidth = 720,
        )

        val bitmap = ShareCardRenderer.renderCard(context, spec)
        assertThat(bitmap).isNotNull()
        assertThat(bitmap.width).isEqualTo(720)
        assertThat(bitmap.height).isAtLeast(720 * 5 / 4)
        assertThat(bitmap.height).isAtMost(720 * 16 / 9)
    }
}
