package com.lastwave.app.playback

import android.net.Uri
import androidx.media3.common.C
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

    @Test fun multipleChunksReadContinuouslyWhenRequestedLengthUnset() {
        val totalSize = 10
        val upstream = MultiChunkUpstream(totalSize)
        val source = ChunkedRangeDataSource(upstream, chunkSize = 4)
        val spec = DataSpec.Builder().setUri("https://media.googlevideo.com/videoplayback").build()
        val opened = source.open(spec)
        assertEquals(C.LENGTH_UNSET.toLong(), opened)
        val buffer = ByteArray(10)
        var totalBytesRead = 0
        while (totalBytesRead < totalSize) {
            val read = source.read(buffer, totalBytesRead, totalSize - totalBytesRead)
            if (read == C.RESULT_END_OF_INPUT) break
            totalBytesRead += read
        }
        assertEquals(totalSize, totalBytesRead)
        source.close()
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

    private class MultiChunkUpstream(private val totalBytes: Int) : DataSource {
        private var open = false
        private var currentPos = 0L
        private var currentLength = 0L
        private var chunkRemaining = 0L
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long {
            check(!open) { "Previous source was not closed" }
            open = true
            currentPos = dataSpec.position
            currentLength = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                minOf(dataSpec.length, (totalBytes - currentPos).coerceAtLeast(0L))
            } else {
                (totalBytes - currentPos).coerceAtLeast(0L)
            }
            if (currentPos >= totalBytes) throw IOException("EOF")
            chunkRemaining = currentLength
            return currentLength
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (chunkRemaining <= 0) return C.RESULT_END_OF_INPUT
            val bytesToRead = minOf(length.toLong(), chunkRemaining).toInt()
            for (i in 0 until bytesToRead) {
                buffer[offset + i] = (currentPos + i).toByte()
            }
            currentPos += bytesToRead
            chunkRemaining -= bytesToRead
            return bytesToRead
        }
        override fun getUri(): Uri? = null
        override fun close() { open = false }
    }
}
