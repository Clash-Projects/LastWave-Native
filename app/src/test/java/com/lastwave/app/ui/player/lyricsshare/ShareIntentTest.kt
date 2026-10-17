package com.lastwave.app.ui.player.lyricsshare

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.util.FileExportHelper
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ShareIntentTest {

    private lateinit var context: Context
    private lateinit var fileExportHelper: FileExportHelper
    private lateinit var sampleBitmap: Bitmap

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        fileExportHelper = FileExportHelper(context)
        sampleBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFFC9FB00.toInt())
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(FileProvider::class)
    }

    @Test
    fun shareImage_constructsValidSendIntentWithFileProviderStreamAndFlags() = runTest {
        mockkStatic(FileProvider::class)
        val expectedUri = Uri.parse("content://${context.packageName}.provider/shared/lyrics_cards/test.png")
        every { FileProvider.getUriForFile(any(), any(), any()) } returns expectedUri

        val result = fileExportHelper.shareImage(
            activityContext = context,
            bitmap = sampleBitmap,
            title = "Test Song",
        )

        assertThat(result.isSuccess).isTrue()

        val nextIntent = shadowOf(context as android.app.Application).nextStartedActivity
        assertThat(nextIntent).isNotNull()
        assertThat(nextIntent.action).isEqualTo(Intent.ACTION_CHOOSER)

        val targetIntent = nextIntent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertThat(targetIntent).isNotNull()
        assertThat(targetIntent!!.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(targetIntent.type).isEqualTo("image/png")

        val streamUri = targetIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertThat(streamUri).isEqualTo(expectedUri)
        assertThat(streamUri!!.scheme).isEqualTo("content")
        assertThat(streamUri.authority).isEqualTo("${context.packageName}.provider")

        // Read URI permission flag must be present
        val flags = targetIntent.flags
        assertThat(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    }

    @Test
    fun saveImageToGallery_insertsIntoMediaStoreAndReturnsContentUri() = runTest {
        val result = fileExportHelper.saveImageToGallery(
            bitmap = sampleBitmap,
            title = "Saved Lyrics Card",
        )

        assertThat(result.isSuccess).isTrue()
        val uri = result.getOrNull()
        assertThat(uri).isNotNull()
        assertThat(uri!!.scheme).isEqualTo("content")
    }
}
