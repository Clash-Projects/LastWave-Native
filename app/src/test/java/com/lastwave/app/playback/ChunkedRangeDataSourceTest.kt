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

    @Test fun failedChunkMidStreamIsAnErrorNotTheEndOfTheSong() {
        // Second chunk request fails (expired URL / dropped connection): the
        // song must not be reported as finished, or playback skips ahead.
        val upstream = MultiChunkUpstream(totalBytes = 10, failOpenAt = 4L)
        val source = ChunkedRangeDataSource(upstream, chunkSize = 4)
        val spec = DataSpec.Builder().setUri("https://media.googlevideo.com/videoplayback").build()
        source.open(spec)
        val buffer = ByteArray(10)
        assertEquals(4, source.read(buffer, 0, 10))
        try {
            source.read(buffer, 4, 6)
            fail("Expected the chunk failure to surface")
        } catch (expected: IOException) {
            assertEquals("chunk failed", expected.message)
        }
        source.close()
    }

    @Test fun stopsAtContentRangeTotalWithoutOverRequesting() {
        val upstream = MultiChunkUpstream(totalBytes = 10, sendContentRange = true)
        val source = ChunkedRangeDataSource(upstream, chunkSize = 4)
        val spec = DataSpec.Builder().setUri("https://media.googlevideo.com/videoplayback").build()
        assertEquals(10L, source.open(spec))
        val buffer = ByteArray(16)
        var total = 0
        while (true) {
            val read = source.read(buffer, total, buffer.size - total)
            if (read == C.RESULT_END_OF_INPUT) break
            total += read
        }
        assertEquals(10, total)
        assertEquals(3, upstream.openCount)
        source.close()
    }

    @Test fun parsesContentRangeTotal() {
        assertEquals(3456789L, ChunkedRangeDataSource.parseContentRangeTotal(mapOf("content-range" to listOf("bytes 0-1048575/3456789"))))
        assertEquals(C.LENGTH_UNSET.toLong(), ChunkedRangeDataSource.parseContentRangeTotal(mapOf("Content-Range" to listOf("bytes 0-10/*"))))
        assertEquals(C.LENGTH_UNSET.toLong(), ChunkedRangeDataSource.parseContentRangeTotal(emptyMap()))
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

    private class MultiChunkUpstream(
        private val totalBytes: Int,
        private val failOpenAt: Long = -1L,
        private val sendContentRange: Boolean = false,
    ) : DataSource {
        var openCount = 0
        private var open = false
        private var currentPos = 0L
        private var currentLength = 0L
        private var chunkRemaining = 0L
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long {
            check(!open) { "Previous source was not closed" }
            if (dataSpec.position == failOpenAt) throw IOException("chunk failed")
            open = true
            openCount++
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
        override fun getResponseHeaders(): Map<String, List<String>> =
            if (sendContentRange) mapOf("Content-Range" to listOf("bytes $currentPos-${currentPos + currentLength - 1}/$totalBytes")) else emptyMap()
        override fun close() { open = false }
    }
}
