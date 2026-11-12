package com.lastwave.app.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Wraps an upstream [DataSource] to issue chunked HTTP Range requests (512 KB range chunks)
 * for YouTube / googlevideo media.
 *
 * 512 KB is InnerTubeX's limit for the token-free ANDROID_VR / TVHTML5_SIMPLY
 * clients (mediaRangeChunkSize); larger or open-ended ranges get served for a
 * while and then refused.
 *
 * When [refresher] is set, a chunk refused with 403/410 (URL expired or
 * revoked mid-song) is retried in place on a fresh URL for the SAME file —
 * same itag and byte length, so byte offsets stay valid — and playback never
 * sees an error. The loader runs ahead of the playhead, so the buffer covers
 * the re-resolve and there is no audible gap.
 *
 * This prevents YouTube's server-side bandwidth throttling on open-ended Range requests
 * and prevents HTTP 403 on client profiles (such as IOS) that strictly mandate bounded Range headers.
 *
 * End of input is reported only at the real end of the file: the requested
 * length, the total size from `Content-Range`, or an HTTP 416 past the last
 * byte. A failed chunk request (expired URL, dropped connection) is thrown as
 * an error instead, so playback retries / re-resolves the same song. Reporting
 * it as end of input made ExoPlayer treat the song as finished and skip to the
 * next one halfway through.
 */
class ChunkedRangeDataSource(
    private val upstream: DataSource,
    private val chunkSize: Long = DEFAULT_CHUNK_SIZE,
    private val refresher: StreamRefresher? = null,
) : DataSource {

    /** Replacement location for a refused googlevideo URL, or null when there is none. */
    fun interface StreamRefresher {
        fun refresh(failedUri: Uri): Replacement?
    }

    class Replacement(val uri: Uri, val requestHeaders: Map<String, String>)

    private var refreshesThisOpen = 0

    private var currentDataSpec: DataSpec? = null
    private var currentPosition: Long = 0L
    private var requestedLength: Long = C.LENGTH_UNSET.toLong()
    private var bytesReadTotal: Long = 0L
    private var currentChunkRemaining: Long = 0L
    private var isCurrentChunkOpen: Boolean = false
    private var chunked: Boolean = false
    /** Total resource size from Content-Range, or LENGTH_UNSET. */
    private var totalLength: Long = C.LENGTH_UNSET.toLong()

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (isCurrentChunkOpen) {
            runCatching { upstream.close() }
            isCurrentChunkOpen = false
            currentChunkRemaining = 0L
        }
        currentDataSpec = dataSpec
        currentPosition = dataSpec.position
        requestedLength = dataSpec.length
        bytesReadTotal = 0L
        totalLength = C.LENGTH_UNSET.toLong()
        refreshesThisOpen = 0

        val isYouTube = dataSpec.uri.host?.let { host ->
            host.contains("googlevideo.com") || host.contains("youtube.com")
        } == true

        chunked = isYouTube && (dataSpec.length == C.LENGTH_UNSET.toLong() || dataSpec.length > chunkSize)
        if (!chunked) {
            // Non-YouTube requests or small requests pass through directly
            return openUpstream(dataSpec)
        }

        openNextChunk()
        return if (requestedLength != C.LENGTH_UNSET.toLong()) {
            requestedLength
        } else if (totalLength != C.LENGTH_UNSET.toLong()) {
            (totalLength - dataSpec.position).coerceAtLeast(0L)
        } else {
            C.LENGTH_UNSET.toLong()
        }
    }

    private fun openNextChunk() {
        if (isCurrentChunkOpen) {
            runCatching { upstream.close() }
            isCurrentChunkOpen = false
            currentChunkRemaining = 0L
        }
        val spec = currentDataSpec ?: return
        val chunkLength = if (requestedLength != C.LENGTH_UNSET.toLong()) {
            val remaining = requestedLength - bytesReadTotal
            if (remaining <= 0) return
            minOf(chunkSize, remaining)
        } else if (totalLength != C.LENGTH_UNSET.toLong()) {
            val remaining = totalLength - currentPosition
            if (remaining <= 0) return
            minOf(chunkSize, remaining)
        } else {
            chunkSize
        }

        val chunkSpec = spec.buildUpon()
            .setPosition(currentPosition)
            .setLength(chunkLength)
            .build()

        val opened = openChunkRefreshingOnRejection(chunkSpec)
        currentChunkRemaining = if (opened != C.LENGTH_UNSET.toLong()) opened else chunkLength
        if (totalLength == C.LENGTH_UNSET.toLong()) {
            totalLength = parseContentRangeTotal(upstream.responseHeaders)
        }
    }

    /**
     * Opens [chunkSpec]; on 403/410 swaps in a fresh URL for the same file
     * (at most [MAX_REFRESHES_PER_OPEN] times) and re-requests the same bytes.
     */
    private fun openChunkRefreshingOnRejection(chunkSpec: DataSpec): Long {
        var spec = chunkSpec
        while (true) {
            try {
                return openUpstream(spec)
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                val refreshable = error.responseCode == 403 || error.responseCode == 410
                val activeRefresher = refresher
                if (!refreshable || activeRefresher == null || refreshesThisOpen >= MAX_REFRESHES_PER_OPEN) throw error
                refreshesThisOpen++
                val replacement = runCatching { activeRefresher.refresh(spec.uri) }.getOrNull() ?: throw error
                spec = spec.buildUpon()
                    .setUri(replacement.uri)
                    .setHttpRequestHeaders(spec.httpRequestHeaders + replacement.requestHeaders)
                    .build()
                // Later chunks of this open continue on the new URL.
                currentDataSpec = currentDataSpec?.buildUpon()
                    ?.setUri(replacement.uri)
                    ?.setHttpRequestHeaders(spec.httpRequestHeaders)
                    ?.build()
            }
        }
    }

    /** Opens the next chunk; false only when the file is genuinely finished. */
    private fun openNextChunkOrEnd(): Boolean {
        if (atEnd()) return false
        try {
            openNextChunk()
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            // 416: the range starts past the last byte - the real end of an
            // open-ended stream. Anything else is a failure, never an end.
            if (error.responseCode == 416 && requestedLength == C.LENGTH_UNSET.toLong()) return false
            throw error
        }
        return isCurrentChunkOpen && currentChunkRemaining > 0
    }

    private fun atEnd(): Boolean =
        (requestedLength != C.LENGTH_UNSET.toLong() && bytesReadTotal >= requestedLength) ||
            (totalLength != C.LENGTH_UNSET.toLong() && currentPosition >= totalLength)

    private fun openUpstream(dataSpec: DataSpec): Long {
        // DefaultDataSource assigns its active source before open() completes.
        // Even a failed open therefore needs close() before the next request.
        if (isCurrentChunkOpen) {
            runCatching { upstream.close() }
            isCurrentChunkOpen = false
            currentChunkRemaining = 0L
        }
        isCurrentChunkOpen = true
        return try {
            upstream.open(dataSpec)
        } catch (error: Exception) {
            runCatching { upstream.close() }
            isCurrentChunkOpen = false
            currentChunkRemaining = 0L
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        currentDataSpec ?: return C.RESULT_END_OF_INPUT
        if (!chunked) return upstream.read(buffer, offset, length)

        // Bounded: a chunk that opens but yields nothing twice in a row is
        // either the end (no size known) or a broken server response.
        var emptyChunks = 0
        while (true) {
            if (atEnd()) return C.RESULT_END_OF_INPUT
            if (currentChunkRemaining <= 0) {
                if (!openNextChunkOrEnd()) return C.RESULT_END_OF_INPUT
            }
            val bytesToRead = minOf(length.toLong(), currentChunkRemaining).toInt()
            val bytesRead = upstream.read(buffer, offset, bytesToRead)
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                // This chunk closed early (connection dropped) or exactly at
                // its end. Either way continue from the byte we actually have.
                currentChunkRemaining = 0L
                if (++emptyChunks >= 2) {
                    if (requestedLength == C.LENGTH_UNSET.toLong() && totalLength == C.LENGTH_UNSET.toLong()) {
                        return C.RESULT_END_OF_INPUT
                    }
                    throw IOException("Stream ended at byte $currentPosition before its declared length")
                }
                continue
            }
            currentPosition += bytesRead
            bytesReadTotal += bytesRead
            currentChunkRemaining -= bytesRead
            return bytesRead
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (isCurrentChunkOpen) {
            runCatching { upstream.close() }
            isCurrentChunkOpen = false
            currentChunkRemaining = 0L
        }
        currentDataSpec = null
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 512 * 1024L
        private const val MAX_REFRESHES_PER_OPEN = 2

        private val CONTENT_RANGE_TOTAL = Regex("""/\s*(\d+)\s*$""")

        /** "bytes 0-1048575/3456789" -> 3456789; LENGTH_UNSET when absent or "*". */
        internal fun parseContentRangeTotal(headers: Map<String, List<String>>): Long {
            val value = headers.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value?.firstOrNull()
                ?: return C.LENGTH_UNSET.toLong()
            return CONTENT_RANGE_TOTAL.find(value)?.groupValues?.get(1)?.toLongOrNull()
                ?: C.LENGTH_UNSET.toLong()
        }
    }
}
