package com.lastwave.app.data.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lastwave.app.MainActivity
import com.lastwave.app.R
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import com.lastwave.app.data.lyrics.LyricsRepository
import com.lastwave.app.data.lyrics.LyricsResult
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.TextMatch
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.lossless.LosslessAudioStream
import com.lastwave.app.data.lossless.LosslessMusicApi
import android.media.AudioManager
import android.media.MediaCodecList
import com.lastwave.app.data.plugin.ModuleManager
import com.lastwave.app.data.plugin.ModuleOfflineLicense
import com.lastwave.app.data.plugin.ModulePlaybackResolver
import com.lastwave.app.data.plugin.OfflineKeys
import com.lastwave.app.data.plugin.OfflineSidecar
import com.lastwave.app.data.plugin.SegmentedDashBridge
import com.lastwave.app.data.plugin.SegmentedStreamDescriptor
import com.lastwave.app.data.local.DownloadFolderStructure
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.local.sanitizeDownloadFolderName
import com.lastwave.app.data.artwork.ArtworkNormalizer
import com.lastwave.app.data.artwork.ArtworkRepository
import com.lastwave.app.playback.formatDetailedQualityBadge
import com.lastwave.app.util.ArtistHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.io.IOException
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


internal suspend fun TrackDownloadManager.downloadToTempFile(
        downloadKey: String,
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        expectedContentLength: Long?,
        useParallelRanges: Boolean,
        onConnectionStateChanged: (Boolean) -> Unit,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadTransfer {
        // googlevideo answers large or open-ended ranges for a while and then
        // refuses them with 403 (InnerTubeX: ANDROID_VR / TVHTML5_SIMPLY need
        // <=512 KB, IOS bounded). Playback already reads in 512 KB pieces;
        // downloads asked for four multi-MB parts or one open-ended GET, which
        // is why YouTube downloads failed while the same song played.
        val isYouTubeMedia = isGoogleVideoUrl(url)
        val knownLength = expectedContentLength?.takeIf { it > 0L }
            ?: if (isYouTubeMedia) probeContentLength(url, requestHeaders) else null
        val rangeSize = when {
            knownLength == null -> null
            isYouTubeMedia -> YOUTUBE_RANGE_BYTES
            useParallelRanges && knownLength >= MIN_PARALLEL_DOWNLOAD_BYTES ->
                (knownLength + PARALLEL_YOUTUBE_PARTS - 1L) / PARALLEL_YOUTUBE_PARTS
            else -> null
        }
        if (knownLength != null && rangeSize != null) {
            try {
                return downloadInRanges(
                    url = url,
                    target = target,
                    requestHeaders = requestHeaders,
                    totalLength = knownLength,
                    rangeSize = rangeSize,
                    downloadKey = downloadKey,
                    onConnectionStateChanged = onConnectionStateChanged,
                    onProgress = onProgress,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                android.util.Log.w(
                    "TrackDownloadManager",
                    "Validated range download unavailable; using one stream",
                    error,
                )
                truncateFile(target)
            }
        }

        return downloadSingleStream(
            downloadKey = downloadKey,
            url = url,
            target = target,
            requestHeaders = requestHeaders,
            expectedContentLength = expectedContentLength,
            onConnectionStateChanged = onConnectionStateChanged,
            onProgress = onProgress,
        )
    }

    /**
     * Fetches [totalLength] bytes as consecutive [rangeSize] ranges, at most
     * [PARALLEL_YOUTUBE_PARTS] in flight, each written at its own offset and
     * retried on its own from the byte it reached.
     */
internal suspend fun TrackDownloadManager.downloadInRanges(
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        totalLength: Long,
        rangeSize: Long,
        downloadKey: String,
        onConnectionStateChanged: (Boolean) -> Unit,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadTransfer = coroutineScope {
        val ranges = generateSequence(0L) { it + rangeSize }
            .takeWhile { it < totalLength }
            .map { start -> DownloadRange(start, minOf(totalLength - 1L, start + rangeSize - 1L)) }
            .toList()
        val inFlight = Semaphore(PARALLEL_YOUTUBE_PARTS)
        RandomAccessFile(target, "rw").use { it.setLength(totalLength) }

        val downloadedBytes = AtomicLong(0L)
        val waitingRanges = AtomicInteger(0)
        val rangeConnectionStateChanged: (Boolean) -> Unit = { isWaiting ->
            val waitingCount = if (isWaiting) {
                waitingRanges.incrementAndGet()
            } else {
                waitingRanges.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
            }
            onConnectionStateChanged(waitingCount > 0)
        }
        val contentTypes = ranges.map { range ->
            async { inFlight.withPermit {
                var downloadedBytesInRange = 0L
                retryInterruptedTransfer(downloadKey, rangeConnectionStateChanged) {
                    downloadRange(
                        url = url,
                        target = target,
                        requestHeaders = requestHeaders,
                        range = DownloadRange(range.start + downloadedBytesInRange, range.endInclusive),
                        expectedTotal = totalLength,
                    ) { delta ->
                        downloadedBytesInRange += delta
                        onProgress(downloadedBytes.addAndGet(delta.toLong()), totalLength)
                    }
                }
            } }
        }.awaitAll()

        val finalLength = downloadedBytes.get()
        if (finalLength != totalLength || target.length() != totalLength) {
            throw IOException("Range download truncated: received $finalLength of $totalLength bytes")
        }
        RandomAccessFile(target, "rw").use { it.fd.sync() }
        DownloadTransfer(
            bytesDownloaded = finalLength,
            totalBytes = totalLength,
            contentType = contentTypes.firstOrNull { it.isNotBlank() }.orEmpty(),
        )
    }

internal suspend fun TrackDownloadManager.downloadRange(
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        range: DownloadRange,
        expectedTotal: Long,
        onBytesRead: (Int) -> Unit,
    ): String = suspendCancellableCoroutine { continuation ->
        val request = buildDownloadRequest(url, requestHeaders, range)
        val call = downloadClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val contentType = response.use { currentResponse ->
                        validateMediaResponse(currentResponse, expectedCode = 206)
                        val parsedRange = parseContentRange(currentResponse.header("Content-Range"))
                            ?: throw DownloadProtocolException("Missing Content-Range for parallel download")
                        if (parsedRange.start != range.start ||
                            parsedRange.endInclusive != range.endInclusive ||
                            parsedRange.total != expectedTotal
                        ) {
                            throw DownloadProtocolException("Mismatched Content-Range ${currentResponse.header("Content-Range")}")
                        }

                        val body = currentResponse.body
                            ?: throw DownloadProtocolException("Empty range response body")
                        val bodyLength = body.contentLength()
                        if (bodyLength > 0L && bodyLength != range.length) {
                            throw DownloadProtocolException("Range length mismatch: received $bodyLength of ${range.length} bytes")
                        }

                        RandomAccessFile(target, "rw").use { output ->
                            output.seek(range.start)
                            body.byteStream().use { input ->
                                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                                var remaining = range.length
                                while (remaining > 0L) {
                                    if (!continuation.isActive) throw CancellationException("Download cancelled")
                                    val read = try {
                                        input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    } catch (error: IOException) {
                                        throw DownloadInterruptedException("Connection interrupted while reading audio", error)
                                    }
                                    if (read < 0) {
                                        throw DownloadInterruptedException("Range truncated with $remaining bytes remaining")
                                    }
                                    output.write(buffer, 0, read)
                                    remaining -= read
                                    onBytesRead(read)
                                }
                            }
                        }
                        currentResponse.header("Content-Type").orEmpty()
                    }
                    if (continuation.isActive) continuation.resume(contentType)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

internal suspend fun TrackDownloadManager.downloadSingleStream(
        downloadKey: String,
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        expectedContentLength: Long?,
        onConnectionStateChanged: (Boolean) -> Unit,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadTransfer {
        val expectedLength = expectedContentLength?.takeIf { it > 0L }
        return retryInterruptedTransfer(downloadKey, onConnectionStateChanged) {
            if (expectedLength != null && target.length() > expectedLength) truncateFile(target)
            if (expectedLength != null && target.length() == expectedLength && expectedLength >= MIN_VALID_AUDIO_BYTES) {
                onProgress(expectedLength, expectedLength)
                return@retryInterruptedTransfer DownloadTransfer(expectedLength, expectedLength, "")
            }
            downloadSingleStreamAttempt(
                url = url,
                target = target,
                requestHeaders = requestHeaders,
                expectedContentLength = expectedLength,
                onProgress = onProgress,
            )
        }
    }

internal suspend fun TrackDownloadManager.downloadSingleStreamAttempt(
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        expectedContentLength: Long?,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadTransfer = suspendCancellableCoroutine { continuation ->
        val resumeOffset = target.length().coerceAtLeast(0L)
        val call = downloadClient.newCall(
            buildDownloadRequest(url, requestHeaders, range = null, resumeOffset = resumeOffset),
        )
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val transfer = response.use { currentResponse ->
                        validateMediaResponse(currentResponse)
                        val body = currentResponse.body
                            ?: throw DownloadProtocolException("Empty response body")
                        val responseLength = body.contentLength().takeIf { it > 0L }
                        val partialRange = if (currentResponse.code == 206) {
                            parseContentRange(currentResponse.header("Content-Range"))
                                ?: throw DownloadProtocolException("Missing Content-Range for partial response")
                        } else {
                            null
                        }
                        if (partialRange != null && (partialRange.start != resumeOffset ||
                                partialRange.endInclusive + 1L != partialRange.total)
                        ) {
                            throw DownloadProtocolException("Server returned an unexpected audio range")
                        }
                        if (partialRange != null && expectedContentLength != null &&
                            partialRange.total != expectedContentLength
                        ) {
                            throw DownloadProtocolException(
                                "Download size changed: expected $expectedContentLength, received ${partialRange.total}",
                            )
                        }

                        val totalLength = partialRange?.total
                            ?: responseLength
                            ?: expectedContentLength?.takeIf { it > 0L }
                            ?: -1L
                        if (responseLength != null && expectedContentLength != null &&
                            currentResponse.code == 200 && responseLength != expectedContentLength
                        ) {
                            throw DownloadProtocolException(
                                "Download size changed: expected $expectedContentLength, received $responseLength",
                            )
                        }

                        val append = resumeOffset > 0L && partialRange != null
                        var downloadedBytes = if (append) resumeOffset else 0L
                        FileOutputStream(target, append).use { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                                while (true) {
                                    if (!continuation.isActive) throw CancellationException("Download cancelled")
                                    val read = try {
                                        input.read(buffer)
                                    } catch (error: IOException) {
                                        throw DownloadInterruptedException("Connection interrupted while reading audio", error)
                                    }
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    downloadedBytes += read
                                    onProgress(downloadedBytes, totalLength)
                                }
                            }
                            output.flush()
                            output.fd.sync()
                        }
                        if (downloadedBytes < MIN_VALID_AUDIO_BYTES) {
                            throw DownloadProtocolException("Downloaded audio payload is too small")
                        }
                        if (totalLength > 0L && downloadedBytes != totalLength) {
                            if (downloadedBytes < totalLength) {
                                throw DownloadInterruptedException(
                                    "Download truncated: received $downloadedBytes of $totalLength bytes",
                                )
                            }
                            throw DownloadProtocolException(
                                "Download exceeded expected size: received $downloadedBytes of $totalLength bytes",
                            )
                        }

                        DownloadTransfer(
                            bytesDownloaded = downloadedBytes,
                            totalBytes = totalLength.takeIf { it > 0L } ?: downloadedBytes,
                            contentType = currentResponse.header("Content-Type").orEmpty(),
                        )
                    }
                    if (continuation.isActive) continuation.resume(transfer)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

internal data class ParsedDashManifest(
        val initUrl: String,
        val mediaTemplate: String,
        val segmentCount: Int,
        val startNumber: Int = 1,
        /**
         * The Representation's declared codec, e.g. `flac`, `mp4a.40.2`, `ec-3`.
         * Tidal serves AAC for the LOW/HIGH tiers and FLAC-in-MP4 for lossless,
         * so the manifest — not the descriptor's assumption — decides whether a
         * download really is lossless.
         */
        val codec: String = "",
    )

internal fun TrackDownloadManager.parseTidalDashManifest(baseUrl: String): ParsedDashManifest? = runCatching {
        val trimmed = baseUrl.trim()
        val xmlStr = when {
            trimmed.startsWith("data:application/dash+xml") -> {
                if (trimmed.contains("base64,")) {
                    val b64 = trimmed.substringAfter("base64,")
                    String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                } else {
                    java.net.URLDecoder.decode(trimmed.substringAfter(","), "UTF-8")
                }
            }
            trimmed.startsWith("<?xml") || trimmed.startsWith("<MPD") -> trimmed
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> {
                if (trimmed.contains(".mpd", ignoreCase = true) || trimmed.contains("dash", ignoreCase = true)) {
                    val req = Request.Builder().url(trimmed).get().build()
                    downloadClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
                    }
                } else ""
            }
            else -> return@runCatching null
        }
        if (xmlStr.isBlank()) return@runCatching null

        val initMatch = Regex("""initialization=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
            ?: Regex("""<Initialization\s+[^>]*sourceURL=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
            ?: Regex("""sourceURL=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
            ?: return@runCatching null
        var initUrl = initMatch.groupValues[1].replace("&amp;", "&")

        val mediaMatch = Regex("""media=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
            ?: Regex("""<SegmentTemplate\s+[^>]*media=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
            ?: Regex("""<SegmentURL\s+[^>]*media=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
            ?: return@runCatching null
        var mediaTemplate = mediaMatch.groupValues[1].replace("&amp;", "&")

        val baseMatch = Regex("""<BaseURL[^>]*>([^<]+)</BaseURL>""", RegexOption.IGNORE_CASE).find(xmlStr)
        val baseUrlPrefix = baseMatch?.groupValues?.get(1)?.trim()?.replace("&amp;", "&")
            ?: if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed.substringBeforeLast('/') + "/"
            } else null
        if (!baseUrlPrefix.isNullOrBlank()) {
            if (!initUrl.startsWith("http://", ignoreCase = true) && !initUrl.startsWith("https://", ignoreCase = true)) {
                initUrl = if (baseUrlPrefix.endsWith("/") || initUrl.startsWith("/")) {
                    "${baseUrlPrefix.trimEnd('/')}/${initUrl.trimStart('/')}"
                } else {
                    "$baseUrlPrefix$initUrl"
                }
            }
            if (!mediaTemplate.startsWith("http://", ignoreCase = true) && !mediaTemplate.startsWith("https://", ignoreCase = true)) {
                mediaTemplate = if (baseUrlPrefix.endsWith("/") || mediaTemplate.startsWith("/")) {
                    "${baseUrlPrefix.trimEnd('/')}/${mediaTemplate.trimStart('/')}"
                } else {
                    "$baseUrlPrefix$mediaTemplate"
                }
            }
        }
        if (!initUrl.startsWith("http://", ignoreCase = true) && !initUrl.startsWith("https://", ignoreCase = true)) {
            return@runCatching null
        }
        if (!mediaTemplate.startsWith("http://", ignoreCase = true) && !mediaTemplate.startsWith("https://", ignoreCase = true)) {
            return@runCatching null
        }

        val codec = Regex("""codecs=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.lowercase()
            .orEmpty()

        var count = 0
        val sRegex = Regex("""<S\s+[^>]*>""", RegexOption.IGNORE_CASE)
        for (match in sRegex.findAll(xmlStr)) {
            val sTag = match.value
            val rMatch = Regex("""r=["']?(\d+)["']?""", RegexOption.IGNORE_CASE).find(sTag)
            val r = rMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            count += 1 + r
        }
        if (count <= 0) {
            val durationMatch = Regex("""mediaPresentationDuration=["']PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?["']""", RegexOption.IGNORE_CASE).find(xmlStr)
            val segTemplateMatch = Regex("""<(?:SegmentTemplate|SegmentList)\b[^>]*>""", RegexOption.IGNORE_CASE).find(xmlStr)?.value
            val segDuration = segTemplateMatch?.let { Regex("""\bduration=["']([0-9.]+)["']""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toDoubleOrNull() } ?: 1.0
            val timescale = segTemplateMatch?.let { Regex("""\btimescale=["']([0-9.]+)["']""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toDoubleOrNull() } ?: 1.0
            if (durationMatch != null) {
                val hours = durationMatch.groupValues.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                val min = durationMatch.groupValues.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                val sec = durationMatch.groupValues.getOrNull(3)?.toDoubleOrNull() ?: 0.0
                val totalSec = hours * 3600.0 + min * 60.0 + sec
                val segSec = segDuration / timescale
                if (segSec > 0) {
                    count = Math.ceil(totalSec / segSec).toInt()
                }
            }
        }
        if (count <= 0) count = 50
        val startNumberMatch = Regex("""startNumber=["'](\d+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
        val startNumber = startNumberMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

        ParsedDashManifest(
            initUrl = initUrl,
            mediaTemplate = mediaTemplate,
            segmentCount = count,
            startNumber = startNumber,
            codec = codec,
        )
    }.getOrNull()

internal data class DashFetchResult(
        val bytesWritten: Long,
        val stoppedEarly: Boolean,
        val completedSegments: Int,
        val expectedSegments: Int,
    )

internal suspend fun TrackDownloadManager.downloadDashSegmentsToTempFile(
        downloadKey: String,
        notificationId: Int,
        title: String,
        artist: String,
        formatBadge: String,
        initUrl: String,
        mediaTemplate: String,
        segmentCount: Int,
        startNumber: Int = 1,
        headers: Map<String, String>,
        target: File,
    ): DashFetchResult = withContext(Dispatchers.IO) {
        val totalParts = segmentCount + 1
        var completedParts = 0
        var totalBytesWritten = 0L
        var stoppedEarly = false
        val targetStream = FileOutputStream(target, false)

        try {
            // 1. Download initialization segment (0.mp4) containing ftyp and moov boxes
            val initReq = Request.Builder()
                .url(initUrl)
                .apply {
                    headers.forEach { (k, v) -> addHeader(k, v) }
                    if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                        addHeader("User-Agent", DOWNLOAD_USER_AGENT)
                    }
                }
                .build()

            downloadClient.newCall(initReq).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Failed to download DASH init chunk: HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("Empty DASH init body")
                val copied = body.byteStream().copyTo(targetStream, DOWNLOAD_BUFFER_SIZE)
                totalBytesWritten += copied
            }
            completedParts++
            val initPercent = ((completedParts * 100) / totalParts).coerceIn(0, 100)
            updateProgress(
                DownloadProgress(
                    key = downloadKey, title = title, artist = artist,
                    progressPercent = initPercent,
                    bytesDownloaded = totalBytesWritten,
                    formatBadge = formatBadge,
                )
            )

            // 2. Download media segments in order and append directly to the file
            val endNumber = startNumber + segmentCount - 1
            for (segIndex in startNumber..endNumber) {
                currentCoroutineContext().ensureActive()
                val segUrl = mediaTemplate.replace(Regex("""\${'$'}Number(?:%0(\d+)d)?\${'$'}""")) { m ->
                    val pad = m.groupValues.getOrNull(1)?.toIntOrNull()
                    if (pad != null) segIndex.toString().padStart(pad, '0') else segIndex.toString()
                }
                val segReq = Request.Builder()
                    .url(segUrl)
                    .apply {
                        headers.forEach { (k, v) -> addHeader(k, v) }
                        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                            addHeader("User-Agent", DOWNLOAD_USER_AGENT)
                        }
                    }
                    .build()

                var stopThis = false
                downloadClient.newCall(segReq).execute().use { resp ->
                    if (resp.code == 404 && segIndex > startNumber) {
                        stopThis = true
                        stoppedEarly = true
                        return@use
                    }
                    if (!resp.isSuccessful) throw IOException("Failed to download DASH segment $segIndex: HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("Empty body for DASH segment $segIndex")
                    val copied = body.byteStream().copyTo(targetStream, DOWNLOAD_BUFFER_SIZE)
                    totalBytesWritten += copied
                }
                if (stopThis) break

                completedParts++
                val percent = ((completedParts * 100) / totalParts).coerceIn(0, 100)
                updateProgress(
                    DownloadProgress(
                        key = downloadKey, title = title, artist = artist,
                        progressPercent = percent,
                        bytesDownloaded = totalBytesWritten,
                        totalBytes = -1L,
                        formatBadge = formatBadge,
                    )
                )
                if (percent % 10 == 0 || segIndex == segmentCount) {
                    runCatching {
                        showDownloadNotification(notificationId, downloadKey, title, artist, percent, false, formatBadge)
                    }
                }
            }
            targetStream.flush()
            targetStream.fd.sync()
            DashFetchResult(
                bytesWritten = totalBytesWritten,
                stoppedEarly = stoppedEarly,
                completedSegments = completedParts - 1,
                expectedSegments = segmentCount,
            )
        } finally {
            runCatching { targetStream.close() }
        }
    }

internal suspend fun <T> TrackDownloadManager.retryInterruptedTransfer(
        downloadKey: String,
        onConnectionStateChanged: (Boolean) -> Unit,
        transfer: suspend () -> T,
    ): T {
        var failureCount = 0
        while (true) {
            try {
                return transfer()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                if (!error.isReconnectableTransferFailure()) throw error
                failureCount++
                // Bounded: an endlessly failing URL used to retry forever and
                // freeze the download (and its slot). Offline periods still
                // wait inside awaitRetryOpportunity, costing one attempt each.
                if (failureCount > MAX_TRANSFER_RETRIES) throw error
                onConnectionStateChanged(true)
                try {
                    awaitRetryOpportunity(downloadKey, failureCount)
                } finally {
                    onConnectionStateChanged(false)
                }
            }
        }
    }

internal suspend fun TrackDownloadManager.awaitRetryOpportunity(downloadKey: String, failureCount: Int) {
        val reconnectGeneration = reconnectGenerations[downloadKey]
            ?: throw CancellationException("Download cancelled")
        val observedGeneration = reconnectGeneration.get()
        val retryDelayMs = (RECONNECT_RETRY_BASE_DELAY_MS *
            (1L shl (failureCount - 1).coerceIn(0, 5)))
            .coerceAtMost(RECONNECT_RETRY_MAX_DELAY_MS)
        var elapsedMs = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            if (reconnectGeneration.get() != observedGeneration) return
            if (elapsedMs >= retryDelayMs && hasUsableNetwork()) return
            delay(RECONNECT_POLL_INTERVAL_MS)
            elapsedMs += RECONNECT_POLL_INTERVAL_MS
        }
    }

internal suspend fun TrackDownloadManager.awaitNetworkForResolveRetry() {
        delay(RESOLVE_RETRY_DELAY_MS)
        val waitStartMs = android.os.SystemClock.elapsedRealtime()
        while (!hasUsableNetwork() &&
            android.os.SystemClock.elapsedRealtime() - waitStartMs < RESOLVE_RETRY_NETWORK_WAIT_MS
        ) {
            delay(RECONNECT_POLL_INTERVAL_MS)
        }
    }

internal fun TrackDownloadManager.hasUsableNetwork(): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching true
        val network = manager.activeNetwork ?: return@runCatching false
        val capabilities = manager.getNetworkCapabilities(network) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true)

internal fun IOException.isReconnectableTransferFailure(): Boolean {
        if (this is DownloadProtocolException) return false
        if (this is DownloadHttpException) {
            return statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode in 500..599
        }
        return generateSequence<Throwable>(this) { it.cause }
            .take(8)
            .any {
                it is DownloadInterruptedException ||
                    it is EOFException ||
                    it is ConnectException ||
                    it is NoRouteToHostException ||
                    it is SocketException ||
                    it is UnknownHostException ||
                    it is InterruptedIOException
            }
    }

internal fun TrackDownloadManager.isGoogleVideoUrl(url: String): Boolean =
        runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("").let { host ->
            host == "googlevideo.com" || host.endsWith(".googlevideo.com")
        }

    /** Total size from a one-byte range request (`Content-Range: bytes 0-0/<total>`), or null. */
internal suspend fun TrackDownloadManager.probeContentLength(url: String, requestHeaders: Map<String, String>): Long? =
        withContext(Dispatchers.IO) {
            runCatching {
                downloadClient.newCall(buildDownloadRequest(url, requestHeaders, DownloadRange(0L, 0L))).execute().use { response ->
                    if (response.code != 206) return@use null
                    parseContentRange(response.header("Content-Range"))?.total?.takeIf { it > 0L }
                }
            }.getOrNull()
        }

internal fun TrackDownloadManager.buildDownloadRequest(
        url: String,
        requestHeaders: Map<String, String>,
        range: DownloadRange?,
        resumeOffset: Long = 0L,
    ): Request {
        val builder = Request.Builder().url(url)
        requestHeaders.forEach { (name, value) -> builder.header(name, value) }
        if (requestHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            builder.header("User-Agent", DOWNLOAD_USER_AGENT)
        }
        builder.header("Accept", "*/*")
        builder.header("Accept-Encoding", "identity")
        when {
            range != null -> builder.header("Range", "bytes=${range.start}-${range.endInclusive}")
            resumeOffset > 0L -> builder.header("Range", "bytes=$resumeOffset-")
        }
        return builder.build()
    }

internal fun TrackDownloadManager.validateMediaResponse(response: Response, expectedCode: Int? = null) {
        if (response.code != 200 && response.code != 206) {
            throw DownloadHttpException(response.code)
        }
        if (expectedCode != null && response.code != expectedCode) {
            throw DownloadProtocolException("Server does not support resumable byte ranges")
        }

        val contentType = response.header("Content-Type").orEmpty().lowercase()
        if (contentType.contains("text/html") ||
            contentType.contains("application/json") ||
            contentType.contains("text/plain")
        ) {
            throw DownloadProtocolException("Invalid download payload ($contentType)")
        }
    }

internal fun TrackDownloadManager.parseContentRange(header: String?): ParsedContentRange? {
        val match = header?.trim()?.let(CONTENT_RANGE_PATTERN::matchEntire) ?: return null
        return ParsedContentRange(
            start = match.groupValues[1].toLongOrNull() ?: return null,
            endInclusive = match.groupValues[2].toLongOrNull() ?: return null,
            total = match.groupValues[3].toLongOrNull() ?: return null,
        )
    }

internal fun TrackDownloadManager.truncateFile(file: File) {
        RandomAccessFile(file, "rw").use { it.setLength(0L) }
    }

internal fun TrackDownloadManager.isDashUrl(url: String, mime: String = ""): Boolean {
        val trimmed = url.trimStart()
        return trimmed.startsWith("data:application/dash+xml") ||
            trimmed.startsWith("<?xml") ||
            trimmed.startsWith("<MPD") ||
            url.contains(".mpd", ignoreCase = true) ||
            mime.contains("dash", ignoreCase = true)
    }

internal fun TrackDownloadManager.detectValidContainer(file: File): String? = runCatching {
        if (file.length() < 12L) return@runCatching null
        val header = ByteArray(12)
        RandomAccessFile(file, "r").use { input ->
            if (input.read(header) != header.size) return@runCatching null
        }
        when {
            header.copyOfRange(0, 4).contentEquals(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())) -> "flac"
            header.copyOfRange(4, 8).contentEquals(byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())) -> "m4a"
            header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())) -> "webm"
            header.copyOfRange(0, 4).contentEquals(byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())) -> "opus"
            header.copyOfRange(0, 3).contentEquals(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte())) ||
                ((header[0].toInt() and 0xFF) == 0xFF && (header[1].toInt() and 0xE0) == 0xE0) -> "mp3"
            else -> null
        }
    }.getOrNull()

internal fun TrackDownloadManager.hasExpectedContainer(file: File, extension: String): Boolean = runCatching {
        if (file.length() < 12L) return@runCatching false
        val detected = detectValidContainer(file) ?: return@runCatching false
        val ext = extension.lowercase()
        when (ext) {
            "flac" -> detected == "flac"
            "m4a" -> detected == "m4a"
            "webm" -> detected == "webm"
            "opus" -> detected == "opus" || detected == "webm"
            "ogg" -> detected == "opus"
            "mp3" -> detected == "mp3"
            else -> false
        }
    }.getOrDefault(false)

    /**
     * Mirrors playback's spatial check (`MusicPlayer.isSpatialAudioSupportedOnDevice`):
     * hardware decoder, bundled FFmpeg decoder, or Android S_V2+ Spatializer.
     */
internal fun TrackDownloadManager.isAtmosCapableDevice(): Boolean = runCatching {
        val decoder = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { type ->
                type.equals("audio/eac3-joc", ignoreCase = true) ||
                    type.equals("audio/eac3", ignoreCase = true) ||
                    type.equals("audio/ac3", ignoreCase = true)
            }
        }
        val ffmpeg = com.lastwave.app.playback.FfmpegCompat.isUsable && runCatching {
            androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable() &&
                androidx.media3.decoder.ffmpeg.FfmpegLibrary.supportsFormat(androidx.media3.common.MimeTypes.AUDIO_E_AC3)
        }.getOrDefault(false)
        if (decoder || ffmpeg) return@runCatching true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            val am = context.getSystemService(AudioManager::class.java)
            val spatializer = am?.spatializer
            if (spatializer != null && (spatializer.isAvailable || spatializer.isEnabled)) {
                return@runCatching true
            }
        }
        false
    }.getOrDefault(false)

