package com.lastwave.app.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ChunkedRangeDataSourceTest {
    @Test fun failedChunkOpenIsClosedBeforeRetry() {
        val upstream = FailingUpstream()
        val source = ChunkedRangeDataSource(upstream, chunkSize = 4)
        val spec = DataSpec.Builder().setUri("https://media.googlevideo.com/videoplayback").build()
        try {
            source.open(spec)
            fail("Expected the original request failure")
        } catch (expected: IOException) {
            assertEquals("request failed", expected.message)
        }
        assertFalse(upstream.open)
        assertEquals(1, upstream.closeCount)
        source.open(spec)
        assertTrue(upstream.open)
        source.close()
        assertFalse(upstream.open)
    }

    @Test fun failedPassThroughOpenIsAlsoClosed() {
        val upstream = FailingUpstream()
        val source = ChunkedRangeDataSource(upstream)
        val spec = DataSpec.Builder().setUri("https://example.test/audio").build()
        try {
            source.open(spec)
            fail("Expected the original request failure")
        } catch (_: IOException) { }
        assertFalse(upstream.open)
        source.open(spec)
        source.close()
        assertEquals(2, upstream.closeCount)
    }

    private class FailingUpstream : DataSource {
        var open = false
        var closeCount = 0
        private var first = true
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long {
            check(!open) { "Previous source was not closed" }
            open = true
            if (first) {
                first = false
                throw IOException("request failed")
            }
            return dataSpec.length
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun getUri(): Uri? = null
        override fun close() { open = false; closeCount++ }
    }
}
