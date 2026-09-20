package com.lastwave.app.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.lastwave.app.MainActivity
import com.lastwave.app.R
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import com.lastwave.app.data.lyrics.LyricsRepository
import com.lastwave.app.data.lyrics.LyricsResult
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.lossless.LosslessMusicApi
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.sync.Semaphore
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

data class DownloadProgress(
    val key: String,
    val title: String,
    val artist: String,
    val progressPercent: Int = 0,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val formatBadge: String = "AUDIO",
    val isWaitingForConnection: Boolean = false,
    val isFinished: Boolean = false,
    val error: String? = null,
)

private data class DownloadTransfer(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val contentType: String,
)

private data class DownloadRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1L
}

private data class ParsedContentRange(
    val start: Long,
    val endInclusive: Long,
    val total: Long,
)

private class DownloadHttpException(val statusCode: Int) :
    IOException("HTTP $statusCode downloading track")

private class DownloadProtocolException(message: String) : IOException(message)

private class DownloadInterruptedException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

@Singleton
class TrackDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val innerTube: InnerTubeMusicApi,
    private val artworkRepository: ArtworkRepository,
    private val lyricsRepository: LyricsRepository,
    private val audioTagWriter: AudioTagWriter,
    okHttpClient: OkHttpClient,
    private val downloadedTrackDao: DownloadedTrackDao,
    private val settingsPreferences: SettingsPreferences,
    private val applicationScope: CoroutineScope,
    private val moduleResolver: ModulePlaybackResolver,
    private val segBridge: SegmentedDashBridge,
    private val offlineLicense: ModuleOfflineLicense,
    private val moduleManager: ModuleManager,
    private val flacTranscoder: ModuleFlacTranscoder,
) {
    companion object {
        const val CHANNEL_ID = "lastwave_downloads"
        const val ACTION_CANCEL_DOWNLOAD = "com.lastwave.app.ACTION_CANCEL_DOWNLOAD"
        const val ACTION_RECONNECT_DOWNLOAD = "com.lastwave.app.ACTION_RECONNECT_DOWNLOAD"
        const val ACTION_VIEW_DOWNLOADS = "com.lastwave.app.ACTION_VIEW_DOWNLOADS"
        const val EXTRA_DOWNLOAD_KEY = "download_key"
        const val EXTRA_DOWNLOAD_TITLE = "download_title"
        const val EXTRA_DOWNLOAD_ARTIST = "download_artist"
        const val EXTRA_NAVIGATE_TO = "navigate_to"
        private const val PUBLIC_DIR_NAME = "LastWave"
        /** Legacy default kept for reading files downloaded before a custom folder was chosen. */
        private const val LEGACY_PUBLIC_DIR_NAME = "LastWave"
        // 128 KB keeps per-connection buffers out of the large-object heap:
        // 512 KB chunks fragmented ART heaps on low-RAM devices and a burst
        // of parallel ranges could OOM the process after a few downloads.
        private const val DOWNLOAD_BUFFER_SIZE = 128 * 1024 // 128 KB
        private const val PARALLEL_YOUTUBE_PARTS = 4
        // Bulk downloads run strictly one by one sequentially to avoid
        // stacking sockets, buffers and bitmap decodes.
        private const val MAX_CONCURRENT_DOWNLOADS = 1
        private const val MIN_PARALLEL_DOWNLOAD_BYTES = 2L * 1024 * 1024
        private const val MIN_VALID_AUDIO_BYTES = 1_024L
        private const val RECONNECT_POLL_INTERVAL_MS = 500L
        private const val RECONNECT_RETRY_BASE_DELAY_MS = 1_000L
        private const val RECONNECT_RETRY_MAX_DELAY_MS = 30_000L
        private const val DOWNLOAD_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
        private val CONTENT_RANGE_PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)

        fun makeDownloadKey(title: String, artist: String): String =
            "${artist.trim().lowercase()}_${title.trim().lowercase()}"
    }

    // Dedicated HTTP client with extended timeouts and bounded pooling.
    // Caps are deliberately low: each parallel range holds a socket plus a
    // read buffer, and unbounded pooling kept idle connections (and their
    // buffers) alive for minutes across successive downloads.
    private val downloadClient = okHttpClient.newBuilder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 24
            maxRequestsPerHost = 8
        })
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    // This class is constructed eagerly inside the Hilt graph. A system
    // service lookup that throws or returns null on a modified ROM would
    // otherwise fail every injection and take down app launch, so the
    // manager is resolved lazily and tolerated as absent.
    private val notificationManager: NotificationManager? by lazy {
        runCatching {
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }.getOrNull()
    }
    private val activeKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeUris = ConcurrentHashMap<String, Uri>()
    private val activeFiles = ConcurrentHashMap<String, File>()
    private val reconnectGenerations = ConcurrentHashMap<String, AtomicLong>()
    private val downloadSlots = Semaphore(MAX_CONCURRENT_DOWNLOADS)

    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notificationManager ?: return
        runCatching {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Download progress and status notifications"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }.onFailure { error ->
            android.util.Log.w("TrackDownloadManager", "Notification channel unavailable", error)
        }
    }

    fun makeDownloadKey(title: String, artist: String): String =
        "${artist.trim().lowercase()}_${title.trim().lowercase()}"

    /** Active download subfolder under Music/ (sanitized single segment). */
    private suspend fun currentDownloadDirName(): String = runCatching {
        sanitizeDownloadFolderName(settingsPreferences.settings.first().downloadFolder)
    }.getOrDefault(PUBLIC_DIR_NAME)

    /** All dirs to search for existing files: active folder first, then the
     *  legacy default so tracks downloaded before a folder change still resolve. */
    private fun downloadSearchDirs(preferred: String): List<File> {
        val dirs = mutableListOf<File>()
        dirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), preferred))
        if (preferred != LEGACY_PUBLIC_DIR_NAME) {
            dirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), LEGACY_PUBLIC_DIR_NAME))
        }
        return dirs
    }

    /** Strips featured/collaborating artists for folder names:
     *  "A feat. B", "A, B", "A & B", "A x B" -> "A". */
    private fun primaryArtistName(artist: String): String {
        var name = artist.trim()
        name = name.split(Regex("(?i)\\s+(feat\\.?|ft\\.?|featuring|with)\\s+.*$"))
            .firstOrNull()?.trim().orEmpty().ifBlank { name }
        name = name.split(',', ';', '&', '/', '、', '×')
            .firstOrNull()?.trim().orEmpty().ifBlank { name }
        name = name.split(Regex("(?i)\\s+x\\s+"))
            .firstOrNull()?.trim().orEmpty().ifBlank { name }
        return name.ifBlank { artist.trim() }
    }

    /**
     * Relative subfolder under Music/<dir>/ for a track, "" = flat.
     * Singles = missing/blank/"Singles" album (no release-type metadata exists
     * to distinguish EPs, so Artist/Album and Artist/Album+Singles coincide).
     */
    private fun downloadSubpath(
        artist: String,
        albumArtist: String? = null,
        album: String? = null,
        year: String? = null,
        structure: DownloadFolderStructure = DownloadFolderStructure.FLAT,
        useAlbumArtist: Boolean = true,
        primaryOnly: Boolean = true,
    ): String {
        if (structure == DownloadFolderStructure.FLAT) return ""
        var folderArtist = (if (useAlbumArtist) albumArtist?.takeIf { it.isNotBlank() } else null)
            ?: artist
        if (primaryOnly) folderArtist = primaryArtistName(folderArtist)
        folderArtist = sanitizeFilename(folderArtist.trim()).trim().ifBlank { "Unknown Artist" }
        val rawAlbum = album?.trim().orEmpty()
        val isSingle = rawAlbum.isBlank() || rawAlbum.equals("Singles", ignoreCase = true)
        val albumSeg = sanitizeFilename(rawAlbum).trim().ifBlank { "Singles" }
        val yearSeg = year?.filter { it.isDigit() }?.take(4)?.takeIf { it.length == 4 }
        val yearAlbumSeg = if (yearSeg != null && !isSingle) "[$yearSeg] $albumSeg" else albumSeg
        val segments = when (structure) {
            DownloadFolderStructure.FLAT -> emptyList()
            DownloadFolderStructure.ARTIST_ALBUM,
            DownloadFolderStructure.ARTIST_ALBUM_SINGLES ->
                if (isSingle) listOf(folderArtist, "Singles") else listOf(folderArtist, albumSeg)
            DownloadFolderStructure.ARTIST_YEAR_ALBUM ->
                if (isSingle) listOf(folderArtist, "Singles") else listOf(folderArtist, yearAlbumSeg)
            DownloadFolderStructure.ALBUM_ONLY ->
                listOf(if (isSingle) "Singles" else albumSeg)
            DownloadFolderStructure.YEAR_ALBUM ->
                listOf(if (isSingle) "Singles" else yearAlbumSeg)
            DownloadFolderStructure.ARTIST_ALBUM_SINGLES_FLAT ->
                if (isSingle) listOf(folderArtist) else listOf(folderArtist, albumSeg)
        }
        return segments.filter { it.isNotBlank() }.joinToString("/")
    }

    fun isDownloading(title: String, artist: String): Boolean {
        val key = makeDownloadKey(title, artist)
        val progress = _downloads.value[key]
        return activeKeys.contains(key) || (progress != null && !progress.isFinished && progress.error == null)
    }

    suspend fun isTrackDownloaded(title: String, artist: String): Boolean = withContext(Dispatchers.IO) {
        val key = makeDownloadKey(title, artist)
        val existing = runCatching {
            downloadedTrackDao.findByTrackKey(key)
                ?: downloadedTrackDao.findByTitleAndArtist(title.trim(), artist.trim())
        }.getOrNull()

        if (existing != null) {
            val fileStillPresent = when {
                existing.mediaStoreUri != null -> runCatching {
                    context.contentResolver.openInputStream(Uri.parse(existing.mediaStoreUri))?.use { }
                    true
                }.getOrDefault(false)
                else -> runCatching {
                    val f = File(existing.filePath)
                    f.exists() && f.length() > 0
                }.getOrDefault(false)
            }
            if (fileStillPresent) return@withContext true
        }

        // Check if file already exists in the download directories (active folder + legacy default, incl. subfolders).
        // The walk is guarded: on scoped-storage devices the public Music dir
        // can throw (or a concurrent download can churn it), and that must
        // degrade to "not found" rather than crashing the caller.
        val dirName = currentDownloadDirName()
        val candidateExtensions = listOf("flac", "m4a", "opus", "mp3", "webm")
        val sanitizedBase = sanitizeFilename("${artist.trim()} - ${title.trim()}")
        val candidateNames = candidateExtensions.map { "$sanitizedBase.$it" }.toSet()
        val foundOnDisk = runCatching {
            downloadSearchDirs(dirName).any { publicDir ->
                publicDir.exists() && publicDir.isDirectory &&
                    publicDir.walkTopDown().maxDepth(6).any { f ->
                        f.isFile && f.name in candidateNames && f.length() > 0
                    }
            }
        }.getOrDefault(false)
        if (foundOnDisk) {
            return@withContext true
        }

        false
    }

    fun cancelDownload(key: String) {
        activeKeys.remove(key)
        reconnectGenerations.remove(key)?.incrementAndGet()
        val job = activeJobs.remove(key)
        job?.cancel()

        // Clean up partial media entry/file
        activeUris.remove(key)?.let { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        activeFiles.remove(key)?.let { file ->
            runCatching { if (file.exists()) file.delete() }
        }

        notificationManager?.cancel(key.hashCode())
        _downloads.update { it - key }
    }

    fun cancelDownload(title: String, artist: String) {
        cancelDownload(makeDownloadKey(title, artist))
    }

    fun cancelAllDownloads() {
        val allKeys = (activeKeys + _downloads.value.keys).toSet()
        allKeys.forEach { key ->
            cancelDownload(key)
        }
    }

    fun reconnectDownload(key: String): Boolean {
        if (key !in activeKeys) return false
        reconnectGenerations[key]?.incrementAndGet() ?: return false
        return true
    }

    fun downloadTrack(
        title: String,
        artist: String,
        album: String? = null,
        artworkUrl: String? = null,
        year: String? = null,
    ): Boolean {
        val key = makeDownloadKey(title, artist)
        if (!activeKeys.add(key)) return false
        // A previous terminal result for the same song must not look like the
        // result of this new request to aggregate playlist observers.
        _downloads.update { it - key }
        reconnectGenerations[key] = AtomicLong()

        val job = applicationScope.launch(Dispatchers.IO) {
            // Bound the number of simultaneous downloads: each one holds
            // sockets, buffers and (during tagging) a decoded cover bitmap,
            // and unbounded overlap OOMed low-RAM devices after a few songs.
            // acquire() is cancellable; a cancel while queued must still
            // release the key so the slot never strands.
            try {
                downloadSlots.acquire()
            } catch (cancelled: CancellationException) {
                activeKeys.remove(key)
                activeJobs.remove(key)
                reconnectGenerations.remove(key)
                _downloads.update { it - key }
                return@launch
            }
            // Already downloaded? Skip re-downloading entirely rather than
            // re-fetching the file and inserting a duplicate DB row or (1).flac file.
            val existing = runCatching {
                downloadedTrackDao.findByTrackKey(key)
                    ?: downloadedTrackDao.findByTitleAndArtist(title.trim(), artist.trim())
            }.getOrNull()
            if (existing != null) {
                val fileStillPresent = when {
                    existing.mediaStoreUri != null -> runCatching {
                        context.contentResolver.openInputStream(Uri.parse(existing.mediaStoreUri))?.use { }
                        true
                    }.getOrDefault(false)
                    else -> runCatching {
                        val f = File(existing.filePath)
                        f.exists() && f.length() > 0
                    }.getOrDefault(false)
                }
                if (fileStillPresent) {
                    downloadSlots.release()
                    activeKeys.remove(key)
                    return@launch
                }
                // Row is stale (file was deleted outside the app) — fall through
                // and re-download; the unique trackKey index means the insert
                // below will REPLACE this row instead of duplicating it.
            } else {
                val dirName = currentDownloadDirName()
                val sanitizedBase = sanitizeFilename("${artist.trim()} - ${title.trim()}")
                val candidateNames = setOf("flac", "m4a", "opus", "mp3", "webm").map { "$sanitizedBase.$it" }.toSet()
                // Guarded: this ran outside any try/catch, so a filesystem
                // hiccup here used to escape the coroutine and kill the app.
                val found = runCatching {
                    downloadSearchDirs(dirName).any { publicDir ->
                        publicDir.exists() && publicDir.isDirectory &&
                            publicDir.walkTopDown().maxDepth(6).any { f ->
                                f.isFile && f.name in candidateNames && f.length() > 0
                            }
                    }
                }.getOrDefault(false)
                if (found) {
                    downloadSlots.release()
                    activeKeys.remove(key)
                    return@launch
                }
            }
            val notifId = key.hashCode()
            updateProgress(DownloadProgress(key = key, title = title, artist = artist, progressPercent = 0))
            // Notifications are best-effort: a PendingIntent/Notification
            // failure must never escape the coroutine and kill the app.
            runCatching {
                showDownloadNotification(notifId, key, title, artist, 0, false, "Preparing high-res stream...")
            }

            var destinationUri: Uri? = null
            var destinationFile: File? = null
            var tempDownloadFile: File? = null

            try {

                // Resolve missing metadata & cover art proactively
                var resolvedArtworkUrl = artworkUrl?.takeIf { ArtworkNormalizer.isRealImage(it) }
                var resolvedAlbum = album?.takeIf { it.isNotBlank() }
                var preloadedBestMatch: YouTubeMusicTrack? = null

                if (resolvedArtworkUrl == null || resolvedAlbum == null) {
                    preloadedBestMatch = runCatching {
                        innerTube.findBestMatch(title, artist, prefetchStreams = false)
                    }.getOrNull()
                    if (resolvedArtworkUrl == null) {
                        resolvedArtworkUrl = preloadedBestMatch?.artworkUrl?.takeIf { ArtworkNormalizer.isRealImage(it) }
                    }
                    if (resolvedAlbum == null) {
                        resolvedAlbum = preloadedBestMatch?.album?.takeIf { it.isNotBlank() }
                    }
                }

                val artworkFallback = if (resolvedArtworkUrl == null) {
                    async(Dispatchers.IO) {
                        artworkRepository.resolve(title, artist)
                        val cacheKey = ArtworkNormalizer.cacheKey(title, artist)
                        artworkRepository.resolved.value[cacheKey]
                            ?.takeIf { ArtworkNormalizer.isRealImage(it) }
                            ?: kotlinx.coroutines.withTimeoutOrNull(3_500L) {
                                artworkRepository.resolved.first { it.containsKey(cacheKey) }[cacheKey]
                            }?.takeIf { ArtworkNormalizer.isRealImage(it) }
                    }
                } else {
                    null
                }

                // 1. Resolve source — respect user's download quality preference (Lossless tiers or YouTube Music)
                val misc = runCatching { settingsPreferences.settings.first() }.getOrDefault(MiscSettings())
                // Custom SAF folder (e.g. SD card): resolve once per download.
                // A stale grant (revoked permission / removed card) fails fast
                // with an actionable message instead of silently filling
                // internal storage the user explicitly moved away from.
                val customTreeUri = SafTreeFiles.parseTreeUri(misc.downloadTreeUri)
                if (customTreeUri != null && !SafTreeFiles.hasPersistedAccess(context, misc.downloadTreeUri)) {
                    val staleMsg = "Download folder unavailable — reselect it in Downloads → Download location"
                    updateProgress(
                        DownloadProgress(key = key, title = title, artist = artist, error = staleMsg),
                    )
                    runCatching { showErrorNotification(notifId, key, title, artist, staleMsg) }
                    return@launch
                }
                var resolvedUrl: String? = null
                var mimeType = "audio/flac"
                var extension = "flac"
                var formatBadge = "24-BIT FLAC"
                var isLossless = false
                var durationMs = 0L
                var downloadHeaders = emptyMap<String, String>()
                var expectedContentLength: Long? = null
                var useParallelDownload = false

                // Dolby ON → request the Atmos mix (28); the Tidal DASH leg
                // below already saves it as .m4a. Otherwise honor the
                // download-quality setting (stereo tiers / YouTube).
                val downloadQuality =
                    if (misc.dolbyAtmosEnabled) LosslessMusicApi.QUALITY_DOLBY_ATMOS
                    else misc.downloadQuality
                val isYouTubeRequested = downloadQuality == LosslessMusicApi.QUALITY_YOUTUBE

                // 1. Provider module (.lwp engine): progressive clear FLAC/MP3 or segmented DASH (Dolby Atmos / Tidal Hi-Res)
                var moduleDescriptor: SegmentedStreamDescriptor? = null
                var moduleLicenseDeferred: Deferred<OfflineKeys?>? = null
                var isDashModuleDownload = false
                var dashInitUrl: String? = null
                var dashMediaTemplate: String? = null
                var dashSegmentCount = 0
                /** Real codec from the DASH manifest (`flac` / `mp4a.40.2` / `ec-3`). */
                var dashManifestCodec = ""
                /** Manifest really carries FLAC (so the .m4a wrapper should be unwrapped). */
                var dashIsFlacInMp4 = false
                var bytesReadTotal = 0L
                var totalBytesRecorded = -1L
                var downloadSucceeded = false

                if (!isYouTubeRequested) {
                    try {
                        val desc = runCatching {
                            moduleResolver.resolve(title, artist, downloadQuality)
                        }.getOrNull()
                        if (desc != null && desc.stream.baseUrl.isNotBlank()) {
                            val s = desc.stream
                            val isAtmos = s.codec.equals("atmos", ignoreCase = true)
                            if ((s.type == "progressive" || s.segments.isEmpty()) && !s.baseUrl.startsWith("data:application/dash+xml") && s.type != "dash_xml") {
                                resolvedUrl = s.baseUrl
                                downloadHeaders = desc.headers
                                mimeType = s.mimeType.ifBlank { "audio/flac" }
                                extension = when {
                                    s.codec.equals("mp3", ignoreCase = true) -> "mp3"
                                    s.codec.equals("aac", ignoreCase = true) -> "m4a"
                                    else -> "flac"
                                }
                                isLossless = !s.codec.equals("opus", ignoreCase = true) &&
                                    !s.codec.equals("mp3", ignoreCase = true) &&
                                    !s.codec.equals("aac", ignoreCase = true) &&
                                    !s.codec.contains("mp4a", ignoreCase = true)
                                formatBadge = if (isLossless) {
                                    if (s.bitDepth > 16 || s.sampleRate > 48000) "HI-RES FLAC" else "LOSSLESS FLAC"
                                } else s.codec.uppercase()
                                durationMs = desc.durationSec * 1000L
                            } else if (s.type == "dash_xml" || s.baseUrl.startsWith("data:application/dash+xml")) {
                                val parsedDash = parseTidalDashManifest(s.baseUrl)
                                if (parsedDash != null) {
                                    // Trust the manifest's own codec, not the descriptor's
                                    // assumption. Tidal answers HI_RES/LOSSLESS with
                                    // FLAC-in-MP4 but LOW/HIGH with plain AAC (mp4a),
                                    // and the old code labelled every DASH download as
                                    // FLAC and named it .m4a either way — so a lossless
                                    // request could be stored as AAC while claiming FLAC.
                                    val manifestCodec = parsedDash.codec.ifBlank { s.codec.lowercase() }
                                    val isAtmosStream = isAtmos ||
                                        manifestCodec.startsWith("ec-3") ||
                                        manifestCodec.startsWith("eac3") ||
                                        manifestCodec.startsWith("ac-3")
                                    val isFlacStream = manifestCodec.contains("flac")
                                    isDashModuleDownload = true
                                    dashInitUrl = parsedDash.initUrl
                                    dashMediaTemplate = parsedDash.mediaTemplate
                                    dashSegmentCount = parsedDash.segmentCount
                                    dashManifestCodec = manifestCodec
                                    // Unwrap whenever the container really holds FLAC,
                                    // including Atmos-flagged releases: this backend serves
                                    // their stereo 24/96 FLAC rendition (never E-AC-3 JOC),
                                    // and an .m4a holding FLAC reads as AAC to players.
                                    dashIsFlacInMp4 = isFlacStream
                                    resolvedUrl = parsedDash.initUrl
                                    downloadHeaders = desc.headers
                                    extension = "m4a"
                                    mimeType = "audio/mp4"
                                    formatBadge = when {
                                        isAtmosStream -> "DOLBY ATMOS"
                                        isFlacStream ->
                                            if (s.bitDepth > 16 || s.sampleRate > 48000) "24-BIT FLAC" else "CD LOSSLESS"
                                        manifestCodec.startsWith("mp4a.40.5") -> "HE-AAC"
                                        manifestCodec.startsWith("mp4a") -> "AAC 320"
                                        else -> "AAC"
                                    }
                                    isLossless = isAtmosStream || isFlacStream
                                    durationMs = desc.durationSec * 1000L
                                }
                            } else if (desc.drm != null && s.segments.isNotEmpty()) {
                                moduleDescriptor = desc
                                resolvedUrl = s.baseUrl
                                downloadHeaders = desc.headers
                                expectedContentLength = s.segments
                                    .mapNotNull { it.range.substringAfterLast("-").toLongOrNull() }
                                    .maxOrNull()?.plus(1)
                                useParallelDownload = true
                                extension = "m4a"
                                mimeType = "audio/mp4"
                                formatBadge = segBridge.audioBadge(desc)
                                isLossless = !s.codec.equals("opus", ignoreCase = true)
                                durationMs = desc.durationSec * 1000L
                                // Offline license in parallel with the bytes.
                                moduleLicenseDeferred = applicationScope.async(Dispatchers.IO) {
                                    runCatching { offlineLicense.acquire(desc) }.getOrNull()
                                }
                            }

                            if (resolvedUrl != null) {
                                val rawFile = File.createTempFile("dl_raw_", ".$extension", context.cacheDir)
                                tempDownloadFile = rawFile

                                if (isDashModuleDownload && dashInitUrl != null && dashMediaTemplate != null && dashSegmentCount > 0) {
                                    bytesReadTotal = downloadDashSegmentsToTempFile(
                                        downloadKey = key,
                                        notificationId = notifId,
                                        title = title,
                                        artist = artist,
                                        formatBadge = formatBadge,
                                        initUrl = dashInitUrl!!,
                                        mediaTemplate = dashMediaTemplate!!,
                                        segmentCount = dashSegmentCount,
                                        headers = downloadHeaders,
                                        target = rawFile,
                                    )
                                    totalBytesRecorded = bytesReadTotal
                                } else {
                                    var lastProgress = 0
                                    var lastNotifTime = 0L
                                    var lastUnknownProgressBytes = 0L
                                    val progressLock = Any()
                                    val transfer = downloadToTempFile(
                                        downloadKey = key,
                                        url = checkNotNull(resolvedUrl),
                                        target = rawFile,
                                        requestHeaders = downloadHeaders,
                                        expectedContentLength = expectedContentLength,
                                        useParallelRanges = useParallelDownload,
                                        onConnectionStateChanged = { isWaiting ->
                                            _downloads.value[key]?.let { current ->
                                                val updated = current.copy(isWaitingForConnection = isWaiting)
                                                updateProgress(updated)
                                                runCatching {
                                                    showDownloadNotification(
                                                        notificationId = notifId,
                                                        downloadKey = key,
                                                        title = title,
                                                        artist = artist,
                                                        progress = updated.progressPercent,
                                                        isIndeterminate = updated.totalBytes <= 0L,
                                                        badgeText = updated.formatBadge,
                                                        isWaitingForConnection = isWaiting,
                                                    )
                                                }
                                            }
                                        },
                                    ) { downloadedBytes, totalBytes ->
                                        synchronized(progressLock) {
                                            val now = android.os.SystemClock.uptimeMillis()
                                            if (totalBytes > 0) {
                                                val progress = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                                if (progress > lastProgress) {
                                                    lastProgress = progress
                                                    updateProgress(
                                                        DownloadProgress(
                                                            key = key, title = title, artist = artist,
                                                            progressPercent = progress,
                                                            bytesDownloaded = downloadedBytes,
                                                            totalBytes = totalBytes,
                                                            formatBadge = formatBadge,
                                                        ),
                                                    )
                                                    if (progress == 100 || now - lastNotifTime >= 250L) {
                                                        lastNotifTime = now
                                                        runCatching {
                                                            showDownloadNotification(notifId, key, title, artist, progress, false, formatBadge)
                                                        }
                                                    }
                                                }
                                            } else if (downloadedBytes - lastUnknownProgressBytes >= 1024 * 1024) {
                                                lastUnknownProgressBytes = downloadedBytes
                                                val mbDown = String.format("%.1f MB", downloadedBytes / (1024.0 * 1024.0))
                                                updateProgress(
                                                    DownloadProgress(
                                                        key = key, title = title, artist = artist,
                                                        progressPercent = 0,
                                                        bytesDownloaded = downloadedBytes,
                                                        totalBytes = -1L,
                                                        formatBadge = "$formatBadge • $mbDown",
                                                    ),
                                                )
                                                if (now - lastNotifTime >= 500L) {
                                                    lastNotifTime = now
                                                    runCatching {
                                                        showDownloadNotification(notifId, key, title, artist, 0, true, formatBadge)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    val contentType = transfer.contentType.lowercase()
                                    if (contentType.contains("webm")) {
                                        extension = "webm"
                                        mimeType = "audio/webm"
                                        formatBadge = "WEBM OPUS"
                                    } else if (contentType.contains("ogg") || contentType.contains("opus")) {
                                        extension = "opus"
                                        mimeType = "audio/ogg"
                                        formatBadge = "OPUS"
                                    } else if (contentType.contains("mpeg") || contentType.contains("mp3")) {
                                        extension = "mp3"
                                        mimeType = "audio/mpeg"
                                        formatBadge = "MP3"
                                    } else if (contentType.contains("mp4") || contentType.contains("m4a") || contentType.contains("aac")) {
                                        extension = "m4a"
                                        mimeType = "audio/mp4"
                                        formatBadge = "M4A AAC"
                                    }
                                    bytesReadTotal = transfer.bytesDownloaded
                                    totalBytesRecorded = transfer.totalBytes
                                }

                                if (!isDashModuleDownload && useParallelDownload && !hasExpectedContainer(rawFile, extension)) {
                                    throw IOException("Downloaded payload is not a valid ${extension.uppercase()} audio file")
                                }
                                downloadSucceeded = true
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (moduleError: Throwable) {
                        android.util.Log.w("TrackDownloadManager", "Module download failed for $title by $artist; falling back to YouTube Music", moduleError)
                        moduleLicenseDeferred?.cancel()
                        moduleLicenseDeferred = null
                        moduleDescriptor = null
                        isDashModuleDownload = false
                        resolvedUrl = null
                        tempDownloadFile?.let { runCatching { if (it.exists()) it.delete() } }
                        tempDownloadFile = null
                        downloadSucceeded = false
                    }
                }

                // 2. Fallback to YouTube Music if module was not requested or module download failed
                if (!downloadSucceeded) {
                    try {
                        updateProgress(
                            DownloadProgress(
                                key = key,
                                title = title,
                                artist = artist,
                                progressPercent = 0,
                                formatBadge = "YOUTUBE",
                            )
                        )
                        val bestMatch = preloadedBestMatch
                            ?: innerTube.findBestMatch(title, artist, prefetchStreams = false)
                        val videoId = bestMatch.videoId ?: throw IOException("No audio source found for $title")
                        if (resolvedArtworkUrl == null) {
                            resolvedArtworkUrl = bestMatch.artworkUrl?.takeIf { ArtworkNormalizer.isRealImage(it) }
                        }
                        if (resolvedAlbum == null) resolvedAlbum = bestMatch.album
                        val ytStream = innerTube.resolveDownloadStream(videoId)
                        resolvedUrl = ytStream.url
                        downloadHeaders = ytStream.requestHeaders
                        expectedContentLength = ytStream.contentLength
                            ?: runCatching { Uri.parse(ytStream.url).getQueryParameter("clen")?.toLongOrNull() }.getOrNull()
                        useParallelDownload = true
                        val rawMime = ytStream.mimeType.orEmpty().lowercase()
                        if (rawMime.contains("mp4") || rawMime.contains("m4a") || rawMime.contains("aac")) {
                            extension = "m4a"
                            mimeType = "audio/mp4"
                            formatBadge = "M4A AAC"
                        } else if (rawMime.contains("webm")) {
                            extension = "webm"
                            mimeType = "audio/webm"
                            formatBadge = "WEBM OPUS"
                        } else if (rawMime.contains("ogg") || rawMime.contains("opus")) {
                            extension = "opus"
                            mimeType = "audio/ogg"
                            formatBadge = "OPUS"
                        } else if (rawMime.contains("mpeg") || rawMime.contains("mp3")) {
                            extension = "mp3"
                            mimeType = "audio/mpeg"
                            formatBadge = "MP3"
                        } else {
                            extension = "m4a"
                            mimeType = "audio/mp4"
                            formatBadge = "AUDIO"
                        }
                        isLossless = false

                        val rawFile = File.createTempFile("dl_raw_", ".$extension", context.cacheDir)
                        tempDownloadFile = rawFile

                        var lastProgress = 0
                        var lastNotifTime = 0L
                        var lastUnknownProgressBytes = 0L
                        val progressLock = Any()
                        val transfer = downloadToTempFile(
                            downloadKey = key,
                            url = checkNotNull(resolvedUrl),
                            target = rawFile,
                            requestHeaders = downloadHeaders,
                            expectedContentLength = expectedContentLength,
                            useParallelRanges = useParallelDownload,
                            onConnectionStateChanged = { isWaiting ->
                                _downloads.value[key]?.let { current ->
                                    val updated = current.copy(isWaitingForConnection = isWaiting)
                                    updateProgress(updated)
                                    runCatching {
                                        showDownloadNotification(
                                            notificationId = notifId,
                                            downloadKey = key,
                                            title = title,
                                            artist = artist,
                                            progress = updated.progressPercent,
                                            isIndeterminate = updated.totalBytes <= 0L,
                                            badgeText = updated.formatBadge,
                                            isWaitingForConnection = isWaiting,
                                        )
                                    }
                                }
                            },
                        ) { downloadedBytes, totalBytes ->
                            synchronized(progressLock) {
                                val now = android.os.SystemClock.uptimeMillis()
                                if (totalBytes > 0) {
                                    val progress = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                    if (progress > lastProgress) {
                                        lastProgress = progress
                                        updateProgress(
                                            DownloadProgress(
                                                key = key, title = title, artist = artist,
                                                progressPercent = progress,
                                                bytesDownloaded = downloadedBytes,
                                                totalBytes = totalBytes,
                                                formatBadge = formatBadge,
                                            ),
                                        )
                                        if (progress == 100 || now - lastNotifTime >= 250L) {
                                            lastNotifTime = now
                                            runCatching {
                                                showDownloadNotification(notifId, key, title, artist, progress, false, formatBadge)
                                            }
                                        }
                                    }
                                } else if (downloadedBytes - lastUnknownProgressBytes >= 1024 * 1024) {
                                    lastUnknownProgressBytes = downloadedBytes
                                    val mbDown = String.format("%.1f MB", downloadedBytes / (1024.0 * 1024.0))
                                    updateProgress(
                                        DownloadProgress(
                                            key = key, title = title, artist = artist,
                                            progressPercent = 0,
                                            bytesDownloaded = downloadedBytes,
                                            totalBytes = -1L,
                                            formatBadge = "$formatBadge • $mbDown",
                                        ),
                                    )
                                    if (now - lastNotifTime >= 500L) {
                                        lastNotifTime = now
                                        runCatching {
                                            showDownloadNotification(notifId, key, title, artist, 0, true, formatBadge)
                                        }
                                    }
                                }
                            }
                        }
                        val contentType = transfer.contentType.lowercase()
                        if (contentType.contains("webm")) {
                            extension = "webm"
                            mimeType = "audio/webm"
                            formatBadge = "WEBM OPUS"
                        } else if (contentType.contains("ogg") || contentType.contains("opus")) {
                            extension = "opus"
                            mimeType = "audio/ogg"
                            formatBadge = "OPUS"
                        } else if (contentType.contains("mpeg") || contentType.contains("mp3")) {
                            extension = "mp3"
                            mimeType = "audio/mpeg"
                            formatBadge = "MP3"
                        } else if (contentType.contains("mp4") || contentType.contains("m4a") || contentType.contains("aac")) {
                            extension = "m4a"
                            mimeType = "audio/mp4"
                            formatBadge = "M4A AAC"
                        }
                        bytesReadTotal = transfer.bytesDownloaded
                        totalBytesRecorded = transfer.totalBytes

                        if (useParallelDownload && !hasExpectedContainer(rawFile, extension)) {
                            throw IOException("Downloaded payload is not a valid ${extension.uppercase()} audio file")
                        }
                        downloadSucceeded = true
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (ytError: Throwable) {
                        // When last YouTube download failed, skip it cleanly and move to next song
                        android.util.Log.w("TrackDownloadManager", "YouTube download failed for $title by $artist; skipping song", ytError)
                        tempDownloadFile?.let { runCatching { if (it.exists()) it.delete() } }
                        tempDownloadFile = null
                        updateProgress(
                            DownloadProgress(
                                key = key,
                                title = title,
                                artist = artist,
                                progressPercent = 0,
                                error = "Skipped: ${ytError.localizedMessage ?: "Stream unavailable"}",
                            ),
                        )
                        runCatching {
                            showErrorNotification(notifId, key, title, artist, "Skipped: stream unavailable")
                        }
                        return@launch
                    }
                }

                // 3. Proactively start lyrics lookup concurrently with the download
                val shouldDownloadLyrics = runCatching {
                    settingsPreferences.settings.first().downloadLyrics
                }.getOrDefault(true)

                val lyricsDeferred = if (shouldDownloadLyrics) {
                    async(Dispatchers.IO) {
                        runCatching {
                            lyricsRepository.getLyrics(
                                title = title,
                                artist = artist,
                                album = resolvedAlbum,
                                durationSeconds = null,
                            )
                        }.getOrNull()
                    }
                } else {
                    null
                }

                    val downloadedFile = tempDownloadFile ?: throw IOException("Downloaded file is missing")
                    var currentAudioFile = downloadedFile

                    // Losslessly remux WebM Opus into standard Ogg Opus for universal player & tag compatibility
                    if (extension == "webm") {
                        val opusFile = File.createTempFile("dl_remux_", ".opus", context.cacheDir)
                        if (WebmOpusRemuxer.remux(currentAudioFile, opusFile)) {
                            currentAudioFile.delete()
                            currentAudioFile = opusFile
                            tempDownloadFile = opusFile
                            extension = "opus"
                            mimeType = "audio/ogg"
                            formatBadge = "OPUS"
                        } else {
                            opusFile.delete()
                        }
                    }

                    // 2b. Tidal lossless DASH is FLAC carried inside MP4. Users who
                    // asked for FLAC expect a real .flac file, and an .m4a holding
                    // FLAC reads as "AAC" to players and tag tools. Unwrap the
                    // container losslessly — STREAMINFO from the dfLa box plus the
                    // raw frames from every mdat; nothing is decoded or re-encoded,
                    // so 24-bit hi-res stays bit-exact. On failure the .m4a is kept
                    // rather than losing a completed download.
                    if (dashIsFlacInMp4 && extension == "m4a") {
                        val nativeFlac = File.createTempFile("dl_flac_", ".flac", context.cacheDir)
                        if (Mp4FlacRemuxer.remux(currentAudioFile, nativeFlac)) {
                            runCatching { currentAudioFile.delete() }
                            currentAudioFile = nativeFlac
                            tempDownloadFile = nativeFlac
                            extension = "flac"
                            mimeType = "audio/flac"
                            isLossless = true
                        } else {
                            runCatching { nativeFlac.delete() }
                            android.util.Log.w(
                                "TrackDownloadManager",
                                "FLAC unwrap failed for $title by $artist (codec=$dashManifestCodec); keeping .m4a",
                            )
                        }
                    }

                    // 3a. Served clear? The box-walk proves no sample-encryption
                    // boxes anywhere: bytes are already pure playable audio.
                    // Skip transcode, license and sidecar entirely.
                    val moduleClear = moduleDescriptor?.takeIf { it.drm != null }?.let { desc ->
                        desc.stream.type != "progressive" &&
                            !Mp4EncryptionScanner.isEncrypted(currentAudioFile)
                    } == true

                    // 3b. Module DRM: transcode decrypted PCM into true FLAC so the
                    // stored file plays in any local player. Any failure falls
                    // through to the license-persisted encrypted path below.
                    var transcodedFlac: File? = null
                    val transcodeDesc = moduleDescriptor?.takeIf { it.drm != null && !moduleClear }?.takeIf { desc ->
                        // Extension decides; missing/unreachable policy keeps current behavior.
                        try {
                            moduleResolver.shouldTranscode(desc)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            true
                        }
                    }
                    if (transcodeDesc != null) {
                        val transResult = try {
                            flacTranscoder.transcodeToFlac(
                                sourceFile = currentAudioFile,
                                descriptor = transcodeDesc,
                                title = title,
                                artist = artist,
                                album = resolvedAlbum,
                                artworkUri = resolvedArtworkUrl,
                            )
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            null
                        }
                        transcodedFlac = transResult?.file
                        if (transcodedFlac != null) {
                            runCatching { currentAudioFile.delete() }
                            currentAudioFile = transcodedFlac
                            tempDownloadFile = transcodedFlac
                            moduleLicenseDeferred?.cancel()
                            extension = "flac"
                            mimeType = "audio/flac"
                            formatBadge = try {
                                moduleResolver.badgeFor(transcodeDesc, segBridge.audioBadge(transcodeDesc))
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Exception) {
                                segBridge.audioBadge(transcodeDesc)
                            }
                            isLossless = true
                        }
                    }

                    val safeFilename = sanitizeFilename("$artist - $title") + ".$extension"

                    // 4. Resolve exact audio duration from downloaded file
                    val durationRetriever = android.media.MediaMetadataRetriever()
                    try {
                        durationRetriever.setDataSource(currentAudioFile.absolutePath)
                        val durStr = durationRetriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        durStr?.toLongOrNull()?.takeIf { it > 0 }?.let { durationMs = it }
                        if (resolvedAlbum.isNullOrBlank()) {
                            resolvedAlbum = durationRetriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
                                ?.takeIf(String::isNotBlank)
                        }
                    } catch (_: Exception) {
                    } finally {
                        runCatching { durationRetriever.release() }
                    }

                    // 5. Complete lyrics resolution (using concurrent result or duration-assisted fallback)
                    var hasLyrics = false
                    var syncedLyrics: String? = null
                    var plainLyrics: String? = null
                    var lrcPath: String? = null

                    if (shouldDownloadLyrics) {
                        var lyricsRecord = lyricsDeferred?.await()
                        if (lyricsRecord !is LyricsResult.Success && durationMs > 0) {
                            lyricsRecord = runCatching {
                                lyricsRepository.getLyrics(
                                    title = title,
                                    artist = artist,
                                    album = resolvedAlbum,
                                    durationSeconds = (durationMs / 1000).toInt(),
                                )
                            }.getOrNull()
                        }

                        if (lyricsRecord is LyricsResult.Success) {
                            val lyrics = lyricsRecord
                            syncedLyrics = lyrics.lines.takeIf { lyrics.isSynced && it.isNotEmpty() }
                                ?.joinToString("\n") { line ->
                                    val timeMs = line.timeMs.coerceAtLeast(0L)
                                    String.format(java.util.Locale.ROOT, "[%02d:%02d.%02d]%s",
                                        timeMs / 60_000, timeMs / 1_000 % 60, timeMs / 10 % 100, line.text)
                                }
                            plainLyrics = lyrics.plainLyrics?.takeIf(String::isNotBlank)
                                ?: lyrics.lines.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.text }
                            hasLyrics = !(syncedLyrics.isNullOrBlank() && plainLyrics.isNullOrBlank())
                        }
                    }

                    if (resolvedArtworkUrl == null) {
                        resolvedArtworkUrl = artworkFallback?.await()
                    } else {
                        artworkFallback?.cancel()
                    }

                    // 4. Embed metadata, cover art AND lyrics directly into the
                    // downloaded audio file (container-aware: Vorbis comments +
                    // PICTURE for FLAC/Opus, Matroska tags for WebM,
                    // iTunes atoms for M4A, ID3v2.3 otherwise).
                    val metadataEmbedded = audioTagWriter.embedMetadata(
                        audioFile = currentAudioFile,
                        title = title,
                        artist = artist,
                        album = resolvedAlbum,
                        artworkUrl = resolvedArtworkUrl,
                        artworkFallbackUrl = preloadedBestMatch?.artworkUrl,
                        lyrics = if (shouldDownloadLyrics) (syncedLyrics ?: plainLyrics) else null,
                        year = year,
                    )
                    if (!metadataEmbedded) {
                        android.util.Log.w("TrackDownloadManager", "Could not safely embed audio metadata; preserving audio file")
                    }

                    // 5. Transfer tagged file to public storage / MediaStore
                    val dirName = sanitizeDownloadFolderName(misc.downloadFolder)
                    val subpath = downloadSubpath(
                        artist = artist,
                        album = resolvedAlbum,
                        year = year,
                        structure = misc.downloadStructure,
                        useAlbumArtist = misc.useAlbumArtistForFolders,
                        primaryOnly = misc.primaryArtistOnly,
                    )
                    val (destStream, uri, file) = openPublicOutputStream(
                        filename = safeFilename,
                        mimeType = mimeType,
                        title = title,
                        artist = artist,
                        album = resolvedAlbum,
                        year = year,
                        durationMs = durationMs,
                        dirName = dirName,
                        subpath = subpath,
                        treeUri = customTreeUri,
                    )
                    destinationUri = uri
                    destinationFile = file
                    if (uri != null) activeUris[key] = uri
                    if (file != null) activeFiles[key] = file

                    val taggedFileLength = currentAudioFile.length()
                    val copiedBytes = currentAudioFile.inputStream().use { input ->
                        destStream.use { output ->
                            val copied = input.copyTo(output, DOWNLOAD_BUFFER_SIZE)
                            output.flush()
                            if (output is FileOutputStream) output.fd.sync()
                            copied
                        }
                    }
                    if (copiedBytes != taggedFileLength) {
                        throw IOException("Public file copy truncated: received $copiedBytes of $taggedFileLength bytes")
                    }

                    // 6. Mark public MediaStore file as finished (IS_PENDING = 0)
                    finalizePublicFile(uri)
                    if (file != null) {
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(file.absolutePath),
                            arrayOf(mimeType),
                            null,
                        )
                    }

                    val publicMusicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), dirName)
                    val publicSubdir = if (subpath.isNotBlank()) File(publicMusicDir, subpath) else publicMusicDir
                    val expectedPublicFile = File(publicSubdir, safeFilename)
                    val finalPath = file?.absolutePath
                        ?: expectedPublicFile.takeIf { it.exists() && it.length() > 0 }?.absolutePath
                        ?: uri?.toString()
                        ?: expectedPublicFile.absolutePath


                // 7. Also write the sidecar .lrc companion file for players that read them
                if (shouldDownloadLyrics) {
                    val lyricsText = syncedLyrics ?: plainLyrics
                    if (!lyricsText.isNullOrBlank()) {
                        val lrcFilename = sanitizeFilename("$artist - $title") + ".lrc"
                        lrcPath = writePublicCompanionFile(lrcFilename, lyricsText, "text/plain", dirName, subpath, customTreeUri)
                    }
                }

                // 6. Persist to Room database
                val entity = DownloadedTrackEntity(
                    trackKey = key,
                    title = title,
                    artist = artist,
                    album = resolvedAlbum.orEmpty(),
                    artworkUrl = resolvedArtworkUrl,
                    filePath = finalPath,
                    mediaStoreUri = uri?.toString(),
                    fileSizeBytes = currentAudioFile.length(),
                    formatBadge = formatBadge,
                    durationMs = durationMs,
                    isLossless = isLossless,
                    hasLyrics = hasLyrics,
                    syncedLyrics = syncedLyrics,
                    plainLyrics = plainLyrics,
                    lrcFilePath = lrcPath,
                    downloadedAtMillis = System.currentTimeMillis(),
                )
                downloadedTrackDao.insert(entity)

                // 6b. Module DRM: persist offline keys + sidecar so the encrypted
                // file plays without network. Skipped when transcoding already
                // produced a plain FLAC, or the bytes arrived clear (3a).
                if (moduleDescriptor?.drm != null && transcodedFlac == null && !moduleClear) {
                    val drm = moduleDescriptor!!.drm!!
                    val keys = try {
                        moduleLicenseDeferred?.await()
                    } catch (_: Exception) {
                        null
                    } ?: throw IOException("Offline license refused by provider; retry while online")
                    val withKeys = moduleDescriptor!!.copy(drm = drm.copy(keySetIdB64 = keys.keySetIdB64))
                    moduleManager.writeOfflineSidecar(
                        title, artist,
                        OfflineSidecar(
                            descriptorJson = moduleManager.encodeDescriptor(withKeys),
                            keySetIdB64 = keys.keySetIdB64,
                            licenseUrl = drm.licenseUrl,
                            licenseExpiresAtMs = keys.licenseExpiresAtMs,
                            audioFilePath = finalPath,
                            mediaStoreUri = uri?.toString().orEmpty(),
                            bytes = currentAudioFile.length(),
                            downloadedAtMs = System.currentTimeMillis(),
                        ),
                    )
                }

                updateProgress(
                    DownloadProgress(
                        key = key,
                        title = title,
                        artist = artist,
                        progressPercent = 100,
                        bytesDownloaded = bytesReadTotal,
                        totalBytes = if (totalBytesRecorded > 0) totalBytesRecorded else bytesReadTotal,
                        formatBadge = formatBadge,
                        isFinished = true,
                    ),
                )

                runCatching { showCompletedNotification(notifId, title, artist, formatBadge) }
            } catch (cancelled: CancellationException) {
                // Cancelled by user — clean up partial file
                destinationUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                destinationFile?.let { runCatching { if (it.exists()) it.delete() } }
                notificationManager?.cancel(notifId)
                _downloads.update { it - key }
            } catch (oom: OutOfMemoryError) {
                // Memory pressure (parallel ranges + decoded cover art) used
                // to escape as an uncaught Error and kill the whole process.
                // Fail just this download instead so the app survives.
                android.util.Log.e("TrackDownloadManager", "Out of memory downloading $title", oom)
                destinationUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                destinationFile?.let { runCatching { if (it.exists()) it.delete() } }
                updateProgress(
                    DownloadProgress(
                        key = key,
                        title = title,
                        artist = artist,
                        error = "Not enough memory — close other apps and download one song at a time",
                    ),
                )
                runCatching {
                    showErrorNotification(notifId, key, title, artist, "Not enough memory — retry one song at a time")
                }
            } catch (error: Exception) {
                destinationUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                destinationFile?.let { runCatching { if (it.exists()) it.delete() } }
                updateProgress(
                    DownloadProgress(
                        key = key,
                        title = title,
                        artist = artist,
                        error = error.localizedMessage ?: error.message ?: "Download failed",
                    ),
                )
                runCatching {
                    showErrorNotification(notifId, key, title, artist, error.localizedMessage ?: "Failed")
                }
            } finally {
                runCatching { downloadSlots.release() }
                activeKeys.remove(key)
                activeJobs.remove(key)
                activeUris.remove(key)
                activeFiles.remove(key)
                reconnectGenerations.remove(key)
                tempDownloadFile?.let { runCatching { if (it.exists()) it.delete() } }
            }

        }
        activeJobs[key] = job
        return true
    }

    private suspend fun downloadToTempFile(
        downloadKey: String,
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        expectedContentLength: Long?,
        useParallelRanges: Boolean,
        onConnectionStateChanged: (Boolean) -> Unit,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadTransfer {
        val parallelLength = expectedContentLength
            ?.takeIf { useParallelRanges && it >= MIN_PARALLEL_DOWNLOAD_BYTES }
        if (parallelLength != null) {
            try {
                return downloadInParallel(
                    url = url,
                    target = target,
                    requestHeaders = requestHeaders,
                    totalLength = parallelLength,
                    downloadKey = downloadKey,
                    onConnectionStateChanged = onConnectionStateChanged,
                    onProgress = onProgress,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: DownloadProtocolException) {
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

    private suspend fun downloadInParallel(
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        totalLength: Long,
        downloadKey: String,
        onConnectionStateChanged: (Boolean) -> Unit,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadTransfer = coroutineScope {
        val partSize = (totalLength + PARALLEL_YOUTUBE_PARTS - 1L) / PARALLEL_YOUTUBE_PARTS
        val ranges = (0 until PARALLEL_YOUTUBE_PARTS).mapNotNull { index ->
            val start = index * partSize
            if (start >= totalLength) return@mapNotNull null
            DownloadRange(start, minOf(totalLength - 1L, start + partSize - 1L))
        }
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
            async {
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
            }
        }.awaitAll()

        val finalLength = downloadedBytes.get()
        if (finalLength != totalLength || target.length() != totalLength) {
            throw IOException("Parallel download truncated: received $finalLength of $totalLength bytes")
        }
        RandomAccessFile(target, "rw").use { it.fd.sync() }
        DownloadTransfer(
            bytesDownloaded = finalLength,
            totalBytes = totalLength,
            contentType = contentTypes.firstOrNull { it.isNotBlank() }.orEmpty(),
        )
    }

    private suspend fun downloadRange(
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

    private suspend fun downloadSingleStream(
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

    private suspend fun downloadSingleStreamAttempt(
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

    private data class ParsedDashManifest(
        val initUrl: String,
        val mediaTemplate: String,
        val segmentCount: Int,
        /**
         * The Representation's declared codec, e.g. `flac`, `mp4a.40.2`, `ec-3`.
         * Tidal serves AAC for the LOW/HIGH tiers and FLAC-in-MP4 for lossless,
         * so the manifest — not the descriptor's assumption — decides whether a
         * download really is lossless.
         */
        val codec: String = "",
    )

    private fun parseTidalDashManifest(baseUrl: String): ParsedDashManifest? = runCatching {
        val xmlStr = if (baseUrl.startsWith("data:application/dash+xml;base64,")) {
            val b64 = baseUrl.substringAfter("base64,")
            String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } else if (baseUrl.startsWith("<?xml")) {
            baseUrl
        } else {
            return@runCatching null
        }

        val initMatch = Regex("""initialization="([^"]+)"""").find(xmlStr) ?: return@runCatching null
        val initUrl = initMatch.groupValues[1].replace("&amp;", "&")

        val mediaMatch = Regex("""media="([^"]+)"""").find(xmlStr) ?: return@runCatching null
        val mediaTemplate = mediaMatch.groupValues[1].replace("&amp;", "&")

        val codec = Regex("""codecs="([^"]+)"""").find(xmlStr)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.lowercase()
            .orEmpty()

        var count = 0
        val sRegex = Regex("""<S\s+[^>]*>""")
        for (match in sRegex.findAll(xmlStr)) {
            val sTag = match.value
            val rMatch = Regex("""r="(\d+)"""").find(sTag)
            val r = rMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            count += 1 + r
        }
        if (count <= 0) count = 50

        ParsedDashManifest(
            initUrl = initUrl,
            mediaTemplate = mediaTemplate,
            segmentCount = count,
            codec = codec,
        )
    }.getOrNull()

    private suspend fun downloadDashSegmentsToTempFile(
        downloadKey: String,
        notificationId: Int,
        title: String,
        artist: String,
        formatBadge: String,
        initUrl: String,
        mediaTemplate: String,
        segmentCount: Int,
        headers: Map<String, String>,
        target: File,
    ): Long = withContext(Dispatchers.IO) {
        val totalParts = segmentCount + 1
        var completedParts = 0
        var totalBytesWritten = 0L
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
            for (segIndex in 1..segmentCount) {
                currentCoroutineContext().ensureActive()
                val segUrl = mediaTemplate.replace("\$Number\$", segIndex.toString())
                val segReq = Request.Builder()
                    .url(segUrl)
                    .apply {
                        headers.forEach { (k, v) -> addHeader(k, v) }
                        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                            addHeader("User-Agent", DOWNLOAD_USER_AGENT)
                        }
                    }
                    .build()

                downloadClient.newCall(segReq).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Failed to download DASH segment $segIndex: HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("Empty body for DASH segment $segIndex")
                    val copied = body.byteStream().copyTo(targetStream, DOWNLOAD_BUFFER_SIZE)
                    totalBytesWritten += copied
                }

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
            totalBytesWritten
        } finally {
            runCatching { targetStream.close() }
        }
    }

    private suspend fun <T> retryInterruptedTransfer(
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
                onConnectionStateChanged(true)
                try {
                    awaitRetryOpportunity(downloadKey, failureCount)
                } finally {
                    onConnectionStateChanged(false)
                }
            }
        }
    }

    private suspend fun awaitRetryOpportunity(downloadKey: String, failureCount: Int) {
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

    private fun hasUsableNetwork(): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching true
        val network = manager.activeNetwork ?: return@runCatching false
        val capabilities = manager.getNetworkCapabilities(network) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true)

    private fun IOException.isReconnectableTransferFailure(): Boolean {
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

    private fun buildDownloadRequest(
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

    private fun validateMediaResponse(response: Response, expectedCode: Int? = null) {
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

    private fun parseContentRange(header: String?): ParsedContentRange? {
        val match = header?.trim()?.let(CONTENT_RANGE_PATTERN::matchEntire) ?: return null
        return ParsedContentRange(
            start = match.groupValues[1].toLongOrNull() ?: return null,
            endInclusive = match.groupValues[2].toLongOrNull() ?: return null,
            total = match.groupValues[3].toLongOrNull() ?: return null,
        )
    }

    private fun truncateFile(file: File) {
        RandomAccessFile(file, "rw").use { it.setLength(0L) }
    }

    private fun hasExpectedContainer(file: File, extension: String): Boolean = runCatching {
        if (file.length() < 12L) return@runCatching false
        val header = ByteArray(12)
        RandomAccessFile(file, "r").use { input ->
            if (input.read(header) != header.size) return@runCatching false
        }
        when (extension.lowercase()) {
            "flac" -> header.copyOfRange(0, 4).contentEquals(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()))
            "m4a" -> header.copyOfRange(4, 8).contentEquals(byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()))
            "webm" -> header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))
            "opus" -> header.copyOfRange(0, 4).contentEquals(byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte()))
            "mp3" -> header.copyOfRange(0, 3).contentEquals(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte())) ||
                ((header[0].toInt() and 0xFF) == 0xFF && (header[1].toInt() and 0xE0) == 0xE0)
            else -> false
        }
    }.getOrDefault(false)

    private fun updateProgress(progress: DownloadProgress) {
        _downloads.update { it + (progress.key to progress) }
    }

    private fun openPublicOutputStream(
        filename: String,
        mimeType: String,
        title: String,
        artist: String,
        album: String?,
        year: String? = null,
        durationMs: Long = 0L,
        dirName: String = PUBLIC_DIR_NAME,
        subpath: String = "",
        treeUri: Uri? = null,
    ): Triple<java.io.OutputStream, Uri?, File?> {
        val resolver = context.contentResolver
        val safeDir = sanitizeDownloadFolderName(dirName)
        val safeSubpath = subpath.split('/').map { sanitizeFilename(it.trim()) }
            .filter { it.isNotBlank() }.joinToString("/")
        val audioRelativePath = if (safeSubpath.isNotBlank()) {
            "${Environment.DIRECTORY_MUSIC}/$safeDir/$safeSubpath"
        } else {
            "${Environment.DIRECTORY_MUSIC}/$safeDir"
        }

        // Custom SAF folder (SD card etc.): write straight into the user's
        // chosen tree, honoring the same artist/album subfolders. Any failure
        // throws so the download surfaces an error instead of silently
        // landing in internal storage.
        if (treeUri != null) {
            val segments = safeSubpath.split('/').filter { it.isNotBlank() }
            val audioMime = when {
                mimeType.contains("mp4") || mimeType.contains("m4a") || mimeType.contains("aac") -> "audio/mp4"
                mimeType.contains("flac") -> "audio/flac"
                mimeType.contains("mp3") || mimeType.contains("mpeg") -> "audio/mpeg"
                mimeType.contains("webm") -> "audio/webm"
                mimeType.contains("ogg") || mimeType.contains("opus") -> "audio/ogg"
                mimeType.contains("wav") -> "audio/x-wav"
                else -> "audio/mp4"
            }
            val safUri = SafTreeFiles.createFile(context, treeUri, segments, filename, audioMime)
                ?: throw IOException("Custom download folder unavailable — reselect it in Downloads → Download location")
            val stream = SafTreeFiles.openWriteStream(context, safUri)
                ?: throw IOException("Could not write to the custom download folder")
            return Triple(stream, safUri, null)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android MediaStore Audio only accepts standard audio MIME types
            val audioMime = when {
                mimeType.contains("mp4") || mimeType.contains("m4a") || mimeType.contains("aac") -> "audio/mp4"
                mimeType.contains("flac") -> "audio/flac"
                mimeType.contains("mp3") || mimeType.contains("mpeg") -> "audio/mpeg"
                mimeType.contains("webm") -> "audio/webm"
                mimeType.contains("ogg") || mimeType.contains("opus") -> "audio/ogg"
                mimeType.contains("wav") -> "audio/x-wav"
                else -> "audio/mp4"
            }

            // Remove any pre-existing entry with the same filename IN THE SAME
            // FOLDER to avoid Android appending (1), (2), etc. Scoped to the
            // relative path so identically-named tracks in other album folders survive.
            runCatching {
                resolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID),
                    "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
                    arrayOf(filename, "$audioRelativePath/"),
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val oldUri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                        runCatching { resolver.delete(oldUri, null, null) }
                    }
                }
            }

            val audioContentValues = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, filename)
                put(MediaStore.Audio.Media.MIME_TYPE, audioMime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, audioRelativePath)
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.ALBUM_ARTIST, artist)
                if (!album.isNullOrBlank()) put(MediaStore.Audio.Media.ALBUM, album)
                val yearInt = year?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
                if (yearInt != null && yearInt > 0) {
                    put(MediaStore.Audio.Media.YEAR, yearInt)
                }
                if (durationMs > 0) put(MediaStore.Audio.Media.DURATION, durationMs)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }

            val audioUri = runCatching { resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audioContentValues) }.getOrNull()
            if (audioUri != null) {
                val stream = resolver.openOutputStream(audioUri, "wt")
                    ?: resolver.openOutputStream(audioUri)
                if (stream != null) return Triple(stream, audioUri, null)
            }

            // Fallback 1: MediaStore.Downloads (pure download columns only)
            val downloadsRelativePath = if (safeSubpath.isNotBlank()) {
                "${Environment.DIRECTORY_DOWNLOADS}/$safeDir/Music/$safeSubpath"
            } else {
                "${Environment.DIRECTORY_DOWNLOADS}/$safeDir/Music"
            }
            val downloadContentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, if (mimeType.isNotBlank()) mimeType else "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, downloadsRelativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val downloadUri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, downloadContentValues) }.getOrNull()
            if (downloadUri != null) {
                val stream = resolver.openOutputStream(downloadUri, "wt")
                    ?: resolver.openOutputStream(downloadUri)
                if (stream != null) return Triple(stream, downloadUri, null)
            }

            // Fallback 2: Direct public / external app music directory
            val fallbackBase = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), safeDir).apply { if (!exists()) mkdirs() }
            val fallbackDir = if (safeSubpath.isNotBlank()) File(fallbackBase, safeSubpath) else fallbackBase
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            val fallbackFile = File(fallbackDir, filename)
            val stream = FileOutputStream(fallbackFile)
            return Triple(stream, null, fallbackFile)
        } else {
            // Android 9 and below
            val musicBase = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), safeDir)
            val musicDir = if (safeSubpath.isNotBlank()) File(musicBase, safeSubpath) else musicBase
            if (!musicDir.exists()) musicDir.mkdirs()
            val file = File(musicDir, filename)
            val stream = FileOutputStream(file)
            return Triple(stream, null, file)
        }
    }


    private fun finalizePublicFile(uri: Uri?) {
        // IS_PENDING only exists on MediaStore Uris — SAF document Uris are
        // already visible once the stream closes.
        if (uri != null && uri.authority == MediaStore.AUTHORITY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            runCatching { context.contentResolver.update(uri, contentValues, null, null) }
        }
    }

    private fun showDownloadNotification(
        notificationId: Int,
        downloadKey: String,
        title: String,
        artist: String,
        progress: Int,
        isIndeterminate: Boolean,
        badgeText: String,
        isWaitingForConnection: Boolean = false,
    ) {
        val cancelIntent = Intent(context, DownloadCancelReceiver::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
            putExtra(EXTRA_DOWNLOAD_KEY, downloadKey)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (isWaitingForConnection) "Waiting for connection" else "Downloading \"$title\"")
            .setContentText(
                if (isWaitingForConnection) "\"$title\" by $artist \u2022 $progress% saved"
                else "$artist \u2022 $badgeText ($progress%)",
            )
            .setProgress(100, progress, isIndeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(viewDownloadsPendingIntent(notificationId))

        if (isWaitingForConnection) {
            builder.addAction(
                android.R.drawable.ic_popup_sync,
                "Reconnect",
                reconnectPendingIntent(notificationId, downloadKey, title, artist),
            )
        }
        val notification = builder
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            .build()

        runCatching { notificationManager?.notify(notificationId, notification) }
    }

    private fun showCompletedNotification(
        notificationId: Int,
        title: String,
        artist: String,
        badgeText: String,
    ) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download Complete")
            .setContentText("\"$title\" by $artist ($badgeText)")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(viewDownloadsPendingIntent(notificationId))
            .build()

        runCatching { notificationManager?.notify(notificationId, notification) }
    }

    private fun showErrorNotification(
        notificationId: Int,
        downloadKey: String,
        title: String,
        artist: String,
        error: String,
    ) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download Failed")
            .setContentText("\"$title\" by $artist: $error")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(viewDownloadsPendingIntent(notificationId))
            .addAction(
                android.R.drawable.ic_popup_sync,
                "Reconnect",
                reconnectPendingIntent(notificationId, downloadKey, title, artist),
            )
            .build()

        runCatching { notificationManager?.notify(notificationId, notification) }
    }

    private fun viewDownloadsPendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_VIEW_DOWNLOADS
            putExtra(EXTRA_NAVIGATE_TO, "downloads")
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun reconnectPendingIntent(
        requestCode: Int,
        downloadKey: String,
        title: String,
        artist: String,
    ): PendingIntent {
        val intent = Intent(context, DownloadCancelReceiver::class.java).apply {
            action = ACTION_RECONNECT_DOWNLOAD
            putExtra(EXTRA_DOWNLOAD_KEY, downloadKey)
            putExtra(EXTRA_DOWNLOAD_TITLE, title)
            putExtra(EXTRA_DOWNLOAD_ARTIST, artist)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun writePublicCompanionFile(
        filename: String,
        content: String,
        mimeType: String,
        dirName: String = PUBLIC_DIR_NAME,
        subpath: String = "",
        treeUri: Uri? = null,
    ): String? {
        val safeDir = sanitizeDownloadFolderName(dirName)
        val safeSubpath = subpath.split('/').map { sanitizeFilename(it.trim()) }
            .filter { it.isNotBlank() }.joinToString("/")
        // Custom SAF folder: keep the .lrc next to its track in the same
        // tree. Lyrics are optional, so failures degrade to null.
        if (treeUri != null) {
            return runCatching {
                val segments = safeSubpath.split('/').filter { it.isNotBlank() }
                val fileUri = SafTreeFiles.createFile(context, treeUri, segments, filename, mimeType)
                    ?: return@runCatching null
                val stream = context.contentResolver.openOutputStream(fileUri, "wt")
                    ?: return@runCatching null
                stream.use { os ->
                    os.write(content.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                fileUri.toString()
            }.getOrNull()
        }
        // Mirror the audio subfolder so .lrc sits next to its track. Note the
        // audio lives under Music/ while companions live under Downloads/.
        val lrcRelativePath = if (safeSubpath.isNotBlank()) {
            "${Environment.DIRECTORY_DOWNLOADS}/$safeDir/Music/$safeSubpath"
        } else {
            "${Environment.DIRECTORY_DOWNLOADS}/$safeDir/Music"
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver

                // Remove pre-existing companion file with the same name IN THE
                // SAME FOLDER to prevent (1).lrc duplicates.
                runCatching {
                    resolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Downloads._ID),
                        "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
                        arrayOf(filename, "$lrcRelativePath/"),
                        null,
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndex(MediaStore.Downloads._ID)
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val oldUri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                            runCatching { resolver.delete(oldUri, null, null) }
                        }
                    }
                }

                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, lrcRelativePath)
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues) }.getOrNull()
                if (uri != null) {
                    resolver.openOutputStream(uri, "wt")?.use { os ->
                        os.write(content.toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                    uri.toString()
                } else {
                    val fallbackBase = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                        ?: File(context.filesDir, "lyrics").apply { if (!exists()) mkdirs() }
                    val fallbackDir = if (safeSubpath.isNotBlank()) File(fallbackBase, safeSubpath) else fallbackBase
                    if (!fallbackDir.exists()) fallbackDir.mkdirs()
                    val file = File(fallbackDir, filename)
                    file.writeText(content, Charsets.UTF_8)
                    file.absolutePath
                }
            } else {
                val musicBase = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), safeDir)
                val musicDir = if (safeSubpath.isNotBlank()) File(musicBase, safeSubpath) else musicBase
                if (!musicDir.exists()) musicDir.mkdirs()
                val file = File(musicDir, filename)
                file.writeText(content, Charsets.UTF_8)
                file.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }


    suspend fun deleteDownloadedTrack(track: DownloadedTrackEntity) = withContext(Dispatchers.IO) {
        downloadedTrackDao.delete(track)
        // Delete physical audio file
        if (!track.mediaStoreUri.isNullOrBlank()) {
            runCatching { context.contentResolver.delete(Uri.parse(track.mediaStoreUri), null, null) }
        }
        if (track.filePath.isNotBlank()) {
            runCatching {
                val f = File(track.filePath)
                if (f.exists()) f.delete()
            }
        }
        // Delete companion .lrc file if present
        if (!track.lrcFilePath.isNullOrBlank()) {
            if (track.lrcFilePath.startsWith("content://")) {
                runCatching { context.contentResolver.delete(Uri.parse(track.lrcFilePath), null, null) }
            } else {
                runCatching {
                    val lf = File(track.lrcFilePath)
                    if (lf.exists()) lf.delete()
                }
            }
        }
    }

    suspend fun clearAllDownloads() = withContext(Dispatchers.IO) {
        val all = downloadedTrackDao.getAllList()
        all.forEach { track ->
            if (!track.mediaStoreUri.isNullOrBlank()) {
                runCatching { context.contentResolver.delete(Uri.parse(track.mediaStoreUri), null, null) }
            }
            if (track.filePath.isNotBlank()) {
                runCatching {
                    val f = File(track.filePath)
                    if (f.exists()) f.delete()
                }
            }
            if (!track.lrcFilePath.isNullOrBlank()) {
                if (track.lrcFilePath.startsWith("content://")) {
                    runCatching { context.contentResolver.delete(Uri.parse(track.lrcFilePath), null, null) }
                } else {
                    runCatching {
                        val lf = File(track.lrcFilePath)
                        if (lf.exists()) lf.delete()
                    }
                }
            }
        }
        downloadedTrackDao.clearAll()
    }

    suspend fun syncDownloadsFromStorage() = withContext(Dispatchers.IO) {
        val existingEntities = downloadedTrackDao.getAllList().toMutableList()
        val existingPaths = existingEntities.map { it.filePath }.toMutableSet()
        val existingUris = existingEntities.mapNotNull { it.mediaStoreUri }.toMutableSet()
        val existingKeys = existingEntities.map { makeDownloadKey(it.title, it.artist) }.toMutableSet()
        val dirName = currentDownloadDirName()

        // 1. Scan download directories on device (active folder + legacy default, incl. subfolders)
        val musicDirs = downloadSearchDirs(dirName).filter { it.exists() && it.isDirectory }
        val seenDirs = mutableSetOf<String>()
        val audioExtensions = setOf("flac", "m4a", "mp3", "opus", "ogg", "webm")
        for (musicDir in musicDirs) {
            if (!seenDirs.add(musicDir.absolutePath)) continue
            val audioFiles = musicDir.walkTopDown().maxDepth(6)
                .filter { file -> file.isFile && file.extension.lowercase() in audioExtensions }
                .toList()

            for (file in audioFiles) {
                if (file.absolutePath in existingPaths) continue

                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.ifBlank { null } ?: file.nameWithoutExtension.substringAfter(" - ").ifBlank { file.nameWithoutExtension }
                    val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?.ifBlank { null } ?: file.nameWithoutExtension.substringBefore(" - ").ifBlank { "Unknown Artist" }
                    val trackKey = makeDownloadKey(title, artist)
                    if (trackKey in existingKeys) continue

                    val album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
                    val durStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val durMs = durStr?.toLongOrNull() ?: 0L
                    val bitRateStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    val bitrateKbps = bitRateStr?.toIntOrNull()?.let { it / 1000 }

                    val ext = file.extension.lowercase()
                    val badge = when (ext) {
                        "flac" -> "FLAC"
                        "m4a", "mp4", "aac" -> "M4A AAC"
                        "mp3" -> "320k MP3"
                        "opus", "ogg" -> "OPUS"
                        else -> "AUDIO"
                    }

                    val lrcFile = File(file.parentFile ?: musicDir, file.nameWithoutExtension + ".lrc")
                    val hasLyrics = lrcFile.exists() && lrcFile.length() > 0
                    val lrcText = if (hasLyrics) runCatching { lrcFile.readText() }.getOrNull() else null

                    val entity = DownloadedTrackEntity(
                        trackKey = trackKey,
                        title = title,
                        artist = artist,
                        album = album,
                        filePath = file.absolutePath,
                        fileSizeBytes = file.length(),
                        formatBadge = badge,
                        durationMs = durMs,
                        bitrateKbps = bitrateKbps,
                        isLossless = ext == "flac",
                        hasLyrics = hasLyrics,
                        syncedLyrics = if (lrcText?.contains("[") == true) lrcText else null,
                        plainLyrics = if (lrcText?.contains("[") != true) lrcText else null,
                        lrcFilePath = if (hasLyrics) lrcFile.absolutePath else null,
                        downloadedAtMillis = file.lastModified(),
                    )
                    downloadedTrackDao.insert(entity)
                    existingPaths.add(file.absolutePath)
                    existingKeys.add(trackKey)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (oom: OutOfMemoryError) {
                    // One corrupt/huge file's metadata must not abort the
                    // whole sync or kill the process — skip it.
                    android.util.Log.e("TrackDownloadManager", "Skipping file after OOM: ${file.name}", oom)
                } catch (e: Exception) {
                    android.util.Log.e("TrackDownloadManager", "Failed to import file: ${file.name}", e)
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }

        // 2. Query MediaStore for items in the download folders (active + legacy)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.DATE_ADDED,
            )
            val selection: String
            val selectionArgs: Array<String>
            if (dirName != LEGACY_PUBLIC_DIR_NAME) {
                selection = "(${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?)"
                selectionArgs = arrayOf("Music/$dirName%", "Music/$LEGACY_PUBLIC_DIR_NAME%")
            } else {
                selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
                selectionArgs = arrayOf("Music/$dirName%")
            }

            runCatching {
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                        if (uri.toString() in existingUris) continue

                        val title = cursor.getString(titleCol) ?: "Unknown Track"
                        val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                        val trackKey = makeDownloadKey(title, artist)
                        if (trackKey in existingKeys) continue

                        val album = cursor.getString(albumCol).orEmpty()
                        val durMs = cursor.getLong(durCol)
                        val size = cursor.getLong(sizeCol)
                        val mime = cursor.getString(mimeCol).orEmpty().lowercase()
                        val date = cursor.getLong(dateCol) * 1000L

                        val isFlac = mime.contains("flac")
                        val isM4a = mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac")
                        val isMp3 = mime.contains("mp3") || mime.contains("mpeg")
                        val isOpus = mime.contains("opus") || mime.contains("ogg")

                        val badge = when {
                            isFlac -> "FLAC"
                            isM4a -> "M4A AAC"
                            isMp3 -> "320k MP3"
                            isOpus -> "OPUS"
                            else -> "AUDIO"
                        }

                        val entity = DownloadedTrackEntity(
                            trackKey = trackKey,
                            title = title,
                            artist = artist,
                            album = album,
                            filePath = uri.toString(),
                            mediaStoreUri = uri.toString(),
                            fileSizeBytes = size,
                            formatBadge = badge,
                            durationMs = durMs,
                            isLossless = isFlac,
                            downloadedAtMillis = if (date > 0) date else System.currentTimeMillis(),
                        )
                downloadedTrackDao.insert(entity)
                        existingUris.add(uri.toString())
                        existingKeys.add(trackKey)
                    }
                }
            }
        }
    }

    private fun sanitizeFilename(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "track" }
}
