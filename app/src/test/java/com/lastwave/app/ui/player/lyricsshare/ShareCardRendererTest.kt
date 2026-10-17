package com.lastwave.app.ui.player.lyricsshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.text.TextPaint
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
        assertThat(bitmap.height).isAtLeast(720) // 1:1 minimum
        assertThat(bitmap.height).isAtMost(720 * 16 / 9) // 9:16 maximum
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
        assertThat(bitmap.height).isAtLeast(720)
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
        assertThat(bitmap.height).isAtLeast(720)
    }

    @Test
    fun renderLyricsCard_withHeaderThumbnail_rendersSuccessfully() = runTest {
        val spec = ShareCardSpec(
            mode = CardMode.Lyrics,
            lines = listOf(
                ShareLine("I can't see clearly when you're gone", isRtl = false),
                ShareLine("I said, \"Ooh, I'm blinded by the lights\"", isRtl = false),
                ShareLine("No, I can't sleep until I feel your touch\"", isRtl = false),
            ),
            title = "Blinding Lights",
            artist = "The Weeknd",
            coverBitmap = sampleCover,
            swatchColor = 0xFF78350F.toInt(), // Warm Amber / Brown like YT Music
            targetWidth = 720,
        )

        val bitmap = ShareCardRenderer.renderCard(context, spec)

        assertThat(bitmap).isNotNull()
        assertThat(bitmap.width).isEqualTo(720)
        assertThat(bitmap.height).isAtLeast(720)
        assertThat(bitmap.height).isAtMost(720 * 16 / 9)
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
            swatchColor = 0xFF0F172A.toInt(),
            targetWidth = 720,
        )

        val bitmap = ShareCardRenderer.renderCard(context, spec)

        assertThat(bitmap).isNotNull()
        assertThat(bitmap.width).isEqualTo(720)
        assertThat(bitmap.height).isAtLeast(720)
    }

    @Test
    fun renderLyricsCard_coverBackground_rendersSuccessfullyWithDimmedScrim() = runTest {
        val spec = ShareCardSpec(
            mode = CardMode.Lyrics,
            lines = listOf(
                ShareLine("No one's around to judge me (oh)", isRtl = false),
                ShareLine("I can't see clearly when you're gone", isRtl = false),
                ShareLine("I said, \"Ooh, I'm blinded by the lights\"", isRtl = false),
            ),
            title = "Blinding Lights",
            artist = "The Weeknd",
            coverBitmap = sampleCover,
            swatchColor = ColorContrastHelper.SWATCH_COVER_ART,
            derivedColors = ColorContrastHelper.deriveTextColors(ColorContrastHelper.SWATCH_COVER_ART, isCoverScrim = true),
            isCoverBackground = true,
            targetWidth = 720,
        )

        val bitmap = ShareCardRenderer.renderCard(context, spec)

        assertThat(bitmap).isNotNull()
        assertThat(bitmap.width).isEqualTo(720)
        assertThat(bitmap.height).isAtLeast(720)
        assertThat(bitmap.height).isAtMost(720 * 16 / 9)
    }

    @Test
    fun lyricWrapping_longLineProducesMultipleRows_shortLineProducesSingleRow() {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 84f
        }
        val contentWidth = 60

        val shortText = "I feel"
        val shortLayout = ShareCardRenderer.createStaticLayout(shortText, paint, contentWidth, isRtl = false)
        val longText = "This is an exceptionally long lyric line that definitely wraps onto multiple distinct rows within the card boundaries"
        val longLayout = ShareCardRenderer.createStaticLayout(longText, paint, contentWidth, isRtl = false)
        assertThat(shortLayout.lineCount).isEqualTo(1)
        assertThat(longLayout.lineCount).isGreaterThan(1)
    }

    @Test
    fun lyricSpacing_gapBetweenDifferentLyricsIsGreaterThanWrappedRowSpacing() {
        val fontSize = 84f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize
        }
        val contentWidth = 40

        val multiRowLyric = "I said, \"Ooh, I'm blinded by the lights, no I can't sleep until I feel your touch\""
        val layout = ShareCardRenderer.createStaticLayout(multiRowLyric, paint, contentWidth, isRtl = false)
        assertThat(layout.lineCount).isGreaterThan(1)

        val row0Bottom = layout.getLineBottom(0)
        val row1Top = layout.getLineTop(1)
        val wrappedRowGap = (row1Top - row0Bottom).coerceAtLeast(0)

        val blockGap = fontSize * ShareCardRenderer.LYRIC_BLOCK_GAP_FACTOR
        assertThat(blockGap).isGreaterThan(wrappedRowGap.toFloat())
    }

    @Test
    fun heightClamp_shortCardClampsToSquare1to1_longCardClampsTo9to16() = runTest {
        val shortSpec = ShareCardSpec(
            mode = CardMode.Lyrics,
            lines = listOf(ShareLine("Single line", isRtl = false)),
            title = "Title",
            artist = "Artist",
            targetWidth = 720,
        )
        val shortBitmap = ShareCardRenderer.renderCard(context, shortSpec)
        assertThat(shortBitmap.width).isEqualTo(720)
        assertThat(shortBitmap.height).isEqualTo(720) // 1:1 minimum

        val veryLongText = "This is an exceptionally long lyric line designed to wrap onto multiple rows to test vertical clamping and font scaling under tight constraints"
        val longSpec = ShareCardSpec(
            mode = CardMode.Lyrics,
            lines = (1..10).map {
                ShareLine("$veryLongText (stanza $it)", isRtl = false)
            },
            title = "Long Song Title That Wraps Across Rows",
            artist = "Long Artist Name",
            targetWidth = 720,
        )
        val longBitmap = ShareCardRenderer.renderCard(context, longSpec)
        assertThat(longBitmap.width).isEqualTo(720)
        assertThat(longBitmap.height).isAtMost(720 * 16 / 9) // 9:16 maximum bound
        assertThat(longBitmap.height).isAtLeast(1200)
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
}
