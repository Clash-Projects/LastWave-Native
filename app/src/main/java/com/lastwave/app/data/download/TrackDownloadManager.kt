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

@Singleton
class TrackDownloadManager @Inject constructor(
    @ApplicationContext internal val context: Context,
    internal val innerTube: InnerTubeMusicApi,
    internal val artworkRepository: ArtworkRepository,
    internal val lyricsRepository: LyricsRepository,
    internal val audioTagWriter: AudioTagWriter,
    okHttpClient: OkHttpClient,
    internal val downloadedTrackDao: DownloadedTrackDao,
    internal val settingsPreferences: SettingsPreferences,
    internal val applicationScope: CoroutineScope,
    internal val moduleResolver: ModulePlaybackResolver,
    internal val segBridge: SegmentedDashBridge,
    internal val offlineLicense: ModuleOfflineLicense,
    internal val moduleManager: ModuleManager,
    internal val flacTranscoder: ModuleFlacTranscoder,
    internal val losslessMusicApi: LosslessMusicApi,
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
        internal const val PUBLIC_DIR_NAME = "LastWave"
        /** Legacy default kept for reading files downloaded before a custom folder was chosen. */
        internal const val LEGACY_PUBLIC_DIR_NAME = "LastWave"
        // 128 KB keeps per-connection buffers out of the large-object heap:
        // 512 KB chunks fragmented ART heaps on low-RAM devices and a burst
        // of parallel ranges could OOM the process after a few downloads.
        internal const val DOWNLOAD_BUFFER_SIZE = 128 * 1024 // 128 KB
        internal const val PARALLEL_YOUTUBE_PARTS = 4
        // Up to 5 tracks download at once; per-download buffers stay small
        // (128 KB x 4 ranges) so this remains safe on low-RAM devices.
        internal const val MAX_CONCURRENT_DOWNLOADS = 5
        internal const val MAX_CONCURRENT_YOUTUBE_RESOLVES = 2
        internal const val RESOLVE_RETRY_DELAY_MS = 3_000L
        internal const val RESOLVE_RETRY_NETWORK_WAIT_MS = 15_000L
        /** Reconnectable transfer failures tolerated before the attempt is abandoned. */
        internal const val MAX_TRANSFER_RETRIES = 6
        /** Bound on each YouTube stream-resolve step so one candidate can't wedge a slot. */
        internal const val YT_STREAM_RESOLVE_TIMEOUT_MS = 20_000L
        /** Resolve + transfer passes before a YouTube download is given up. */
        internal const val YT_DOWNLOAD_ATTEMPTS = 3
        internal const val MIN_PARALLEL_DOWNLOAD_BYTES = 2L * 1024 * 1024
        /** googlevideo range limit (same as playback's ChunkedRangeDataSource). */
        internal const val YOUTUBE_RANGE_BYTES = 512L * 1024
        internal const val MIN_VALID_AUDIO_BYTES = 1_024L
        internal const val RECONNECT_POLL_INTERVAL_MS = 500L
        internal const val RECONNECT_RETRY_BASE_DELAY_MS = 1_000L
        internal const val RECONNECT_RETRY_MAX_DELAY_MS = 30_000L
        internal const val DOWNLOAD_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
        internal val CONTENT_RANGE_PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)

        fun makeDownloadKey(title: String, artist: String): String =
            "${artist.trim().lowercase()}_${title.trim().lowercase()}"

        const val SCAN_PREFS_NAME = "downloads_restore_prefs"
        const val KEY_DOWNLOADS_SCANNED = "downloads_scanned"
        internal const val HIDDEN_PREFS_NAME = "download_history_hidden"
        internal const val KEY_HIDDEN_REFS = "hidden_refs"
        internal const val SCAN_MARKER_FILE = "downloads_scanned.marker"

        fun isDownloadsScanned(context: Context): Boolean = runCatching {
            val prefs = context.getSharedPreferences(SCAN_PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_DOWNLOADS_SCANNED, false)) return@runCatching true
            val marker = File(context.noBackupFilesDir, SCAN_MARKER_FILE)
            marker.exists()
        }.getOrDefault(false)

        fun getRequiredAudioPermission(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
        internal val AUDIO_EXTENSIONS = setOf("flac", "m4a", "mp3", "opus", "ogg", "webm", "wav", "aac")
        internal val BRACKET_SONG_ID_REGEX = Regex("""\[([a-zA-Z0-9_-]{11})\]""")
        internal val PAREN_SONG_ID_REGEX = Regex("""\(([a-zA-Z0-9_-]{11})\)""")
        internal val TRAILING_SONG_ID_REGEX = Regex("""(?:^|[\s\-_])([a-zA-Z0-9_-]{11})$""")
        internal val EXACT_SONG_ID_REGEX = Regex("""^[a-zA-Z0-9_-]{11}$""")

        fun extractSongId(nameWithoutExt: String): String? {
            BRACKET_SONG_ID_REGEX.find(nameWithoutExt)?.let { return it.groupValues[1] }
            PAREN_SONG_ID_REGEX.find(nameWithoutExt)?.let { return it.groupValues[1] }
            if (EXACT_SONG_ID_REGEX.matches(nameWithoutExt)) return nameWithoutExt
            TRAILING_SONG_ID_REGEX.find(nameWithoutExt)?.let { return it.groupValues[1] }
            return null
        }

        fun cleanSongTitle(rawTitle: String, songId: String?): String {
            var title = rawTitle.trim()
            if (!songId.isNullOrBlank()) {
                title = title
                    .replace("[$songId]", "")
                    .replace("($songId)", "")
                    .replace(" - $songId", "")
                    .replace("_$songId", "")
                    .trim()
            }
            title = title.replace(BRACKET_SONG_ID_REGEX, "")
                .replace(PAREN_SONG_ID_REGEX, "")
                .trim()
            return title.ifBlank { rawTitle.trim() }
        }

        fun parseArtistAndTitleFromFilename(nameWithoutExt: String, songId: String?): Pair<String, String> {
            val cleanName = cleanSongTitle(nameWithoutExt, songId)
            return if (cleanName.contains(" - ")) {
                val artist = cleanName.substringBefore(" - ").trim().ifBlank { "Unknown Artist" }
                val title = cleanName.substringAfter(" - ").trim().ifBlank { cleanName }
                artist to title
            } else {
                "Unknown Artist" to cleanName.ifBlank { nameWithoutExt }
            }
        }
    }

    // Dedicated HTTP client with extended timeouts and bounded pooling.
    // Caps are deliberately low: each parallel range holds a socket plus a
    // read buffer, and unbounded pooling kept idle connections (and their
    // buffers) alive for minutes across successive downloads.
    internal val downloadClient = okHttpClient.newBuilder()
        .dispatcher(Dispatcher().apply {
            // 5 concurrent downloads x 4 ranges, usually on one googlevideo
            // host: a per-host cap of 8 queued ranges behind each other.
            maxRequests = 40
            maxRequestsPerHost = 24
        })
        .connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))
        .connectTimeout(20, TimeUnit.SECONDS)
        // A stalled socket is detected in 30s and resumed by the retry loop
        // instead of hanging the download for 2 minutes per stall.
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    // This class is constructed eagerly inside the Hilt graph. A system
    // service lookup that throws or returns null on a modified ROM would
    // otherwise fail every injection and take down app launch, so the
    // manager is resolved lazily and tolerated as absent.
    internal val notificationManager: NotificationManager? by lazy {
        runCatching {
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }.getOrNull()
    }
    internal val activeKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    internal val activeJobs = ConcurrentHashMap<String, Job>()
    /** Shared with [downloadTrack] so a wipe can freeze admission before it snapshots jobs. */
    internal val admissionLock = Any()
    @Volatile
    internal var downloadsWiping = false
    internal val activeUris = ConcurrentHashMap<String, Uri>()
    internal val activeFiles = ConcurrentHashMap<String, File>()
    internal val reconnectGenerations = ConcurrentHashMap<String, AtomicLong>()
    internal val downloadSlots = Semaphore(MAX_CONCURRENT_DOWNLOADS)
    internal val youtubeResolveGate = Semaphore(MAX_CONCURRENT_YOUTUBE_RESOLVES)
    internal val restoreMutex = Mutex()

    internal val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    @Volatile
    internal var hiddenHistoryCache: HiddenHistory? = null
    internal val hiddenRefsLock = Any()

    suspend fun restoreDownloadsAfterReinstall(): Boolean = restoreDownloadsAfterReinstallImpl()
    suspend fun syncDownloadsFromStorage() = syncDownloadsFromStorageImpl()
    suspend fun removeFromHistoryOnly(track: DownloadedTrackEntity) = removeFromHistoryOnlyImpl(track)
    suspend fun clearHistoryOnly() = clearHistoryOnlyImpl()
    suspend fun deleteDownloadedTrack(track: DownloadedTrackEntity) = deleteDownloadedTrackImpl(track)
    suspend fun finishConsentedDelete(track: DownloadedTrackEntity): Boolean = finishConsentedDeleteImpl(track)
    suspend fun clearAllDownloads() = clearAllDownloadsImpl()
    suspend fun clearAllDownloadsWithConsent(): ClearAllDownloadsResult = clearAllDownloadsWithConsentImpl()
    suspend fun finishConsentedClearAllDownloads(tracks: List<DownloadedTrackEntity>): Boolean = finishConsentedClearAllDownloadsImpl(tracks)

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
    internal suspend fun currentDownloadDirName(): String {
        val folder = try {
            settingsPreferences.settings.first().downloadFolder
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return PUBLIC_DIR_NAME
        }
        return runCatching { sanitizeDownloadFolderName(folder) }.getOrDefault(PUBLIC_DIR_NAME)
    }

    /** All dirs to search for existing files: active folder first, then the
     *  legacy default so tracks downloaded before a folder change still resolve. */
    internal fun downloadSearchDirs(preferred: String): List<File> {
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
        if (activeKeys.contains(key) || (progress != null && !progress.isFinished && progress.error == null)) return true
        // Stale callers may still pass a counter string ("15 ml listens") that can
        // never match the sanitized in-flight key; fall back to title match so the
        // UI doesn't offer a duplicate download.
        if (ArtistHelper.isPlayCountOrStat(artist)) {
            return _downloads.value.values.any {
                !it.isFinished && it.error == null && it.title.equals(title.trim(), ignoreCase = true)
            }
        }
        return false
    }

    suspend fun isTrackDownloaded(title: String, artist: String): Boolean = withContext(Dispatchers.IO) {
        val key = makeDownloadKey(title, artist)
        val existing = runCatching {
            downloadedTrackDao.findByTrackKey(key)
                ?: downloadedTrackDao.findByTitleAndArtist(title.trim(), artist.trim())
        }.getOrNull() ?: runCatching {
            // Stat-artist lookup could never match repaired rows; fall back to
            // title-only so stale callers still see the track as downloaded.
            if (ArtistHelper.isPlayCountOrStat(artist)) {
                downloadedTrackDao.getAllList().firstOrNull {
                    it.title.equals(title.trim(), ignoreCase = true)
                }
            } else null
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
        // Removed from history: the file may still be on disk, but offer
        // Download again so the user can bring it back into the list.
        if (existing == null && isHiddenFromHistory(key)) return@withContext false

        val onDisk = findDownloadOnDisk(title, artist) ?: return@withContext false
        existing != null || !isHiddenFromHistory(key, onDisk.absolutePath)
    }

    /**
     * Looks for "Artist - Title.ext" in the download folders (active + legacy,
     * incl. subfolders). Guarded: on scoped-storage devices the public Music
     * dir can throw, or a concurrent download can churn it, and that must
     * degrade to "not found" rather than crashing the caller.
     */
    private suspend fun findDownloadOnDisk(title: String, artist: String): File? {
        val dirName = currentDownloadDirName()
        return runCatching {
            val sanitizedBase = sanitizeFilename("${artist.trim()} - ${title.trim()}")
            val candidateNames = listOf("flac", "m4a", "opus", "mp3", "webm").map { "$sanitizedBase.$it" }.toSet()
            downloadSearchDirs(dirName).asSequence()
                .filter { it.exists() && it.isDirectory }
                .flatMap { it.walkTopDown().maxDepth(6) }
                .firstOrNull { f -> f.isFile && f.name in candidateNames && f.length() > 0 }
        }.getOrNull()
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
        videoId: String? = null,
        durationMs: Long? = null,
    ) {
        val key = makeDownloadKey(title, artist)
        // Admission and job registration share [admissionLock] with the wipe so a
        // worker cannot start, or publish its handle, after the wipe has snapshotted.
        synchronized(admissionLock) {
            if (downloadsWiping) return
            if (!activeKeys.add(key)) return
            reconnectGenerations[key] = AtomicLong()
            val job = applicationScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                runDownloadJob(key, title, artist, album, artworkUrl, year, videoId, durationMs)
            }
            activeJobs[key] = job
            job.start()
        }
    }

    private suspend fun CoroutineScope.runDownloadJob(
        key: String,
        title: String,
        artist: String,
        album: String?,
        artworkUrl: String?,
        year: String?,
        videoId: String?,
        durationMs: Long?,
    ) {
            // Bound the number of simultaneous downloads: each one holds
            // sockets, buffers and (during tagging) a decoded cover bitmap,
            // and unbounded overlap OOMed low-RAM devices after a few songs.
            // acquire() is cancellable; a cancel while queued must still
            // release the key so the slot never strands.
            try {
                downloadSlots.acquire()
            } catch (cancelled: CancellationException) {
                forgetDownloadKey(key)
                return
            }
            if (downloadsWiping) {
                abandonAcquiredDownload(key)
                return
            }
            // Already downloaded? Skip re-downloading entirely rather than
            // re-fetching the file and inserting a duplicate DB row or (1).flac file.
            val existing = try {
                downloadedTrackDao.findByTrackKey(key)
                    ?: downloadedTrackDao.findByTitleAndArtist(title.trim(), artist.trim())
            } catch (cancelled: CancellationException) {
                abandonAcquiredDownload(key)
                return
            } catch (_: Exception) {
                null
            }
            if (downloadsWiping) {
                abandonAcquiredDownload(key)
                return
            }
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
                    forgetDownloadKey(key)
                    return
                }
                // Row is stale (file was deleted outside the app) — fall through
                // and re-download; the unique trackKey index means the insert
                // below will REPLACE this row instead of duplicating it.
            } else {
                val found = findDownloadOnDisk(title, artist)
                if (found != null) {
                    downloadSlots.release()
                    forgetDownloadKey(key)
                    // Removed from history but the file is still here: bring
                    // the existing file back instead of downloading it again.
                    if (unhideKey(key, found.absolutePath)) {
                        try {
                            syncDownloadsFromStorage()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            android.util.Log.w("TrackDownloadManager", "Couldn't restore hidden download $key", error)
                        }
                    }
                    return
                }
            }
            val notifId = key.hashCode()
            updateProgress(DownloadProgress(key = key, title = title, artist = artist, progressPercent = 0))
            // Notifications are best-effort: a PendingIntent/Notification
            // failure must never escape the coroutine and kill the app.
            runCatching {
                showDownloadNotification(notifId, key, title, artist, 0, false, "Preparing high-res stream...")
            }
            // Live stage text: tier failures used to be invisible until the
            // terminal skip. Stage updates are progress-only except on major
            // transitions, so notifications don't spam per tier.
            fun updateStage(stage: String, notify: Boolean = false) {
                updateProgress(
                    DownloadProgress(
                        key = key, title = title, artist = artist,
                        progressPercent = 0, formatBadge = stage,
                    ),
                )
                if (notify) {
                    runCatching {
                        showDownloadNotification(notifId, key, title, artist, 0, false, stage)
                    }
                }
            }

            var destinationUri: Uri? = null
            var destinationFile: File? = null
            var tempDownloadFile: File? = null

            try {
                ensureDownloadStillAllowed()
                // Never let counter strings ("15 ml listens", "Track 16", "10K views")
                // leak into filenames, tags or the DB. Callers on fixed code paths
                // already pass clean values (AlbumDetailScreen safeArtist + hardened
                // InnerTube parsing), this is the safety net for stale callers and
                // for the already-queued stat-artist downloads.
                val rawTitle = title.trim()
                val rawArtistIsStat = ArtistHelper.isPlayCountOrStat(artist)
                var safeArtist = artist.trim().takeUnless { ArtistHelper.isPlayCountOrStat(it) }?.ifBlank { null }
                var safeAlbumInput = album?.trim()?.takeUnless { ArtistHelper.isPlayCountOrStat(it) }?.takeIf { it.isNotBlank() }
                // If the artist was a counter but the album title is known (album
                // download case), recover the real artist via search before any
                // network/stream work so tagging, filenames and DB stay correct.
                if (safeArtist.isNullOrBlank() && rawTitle.isNotBlank()) {
                    val recoveryQueryArtist = safeAlbumInput.orEmpty()
                    val recovered = runCatching {
                        if (recoveryQueryArtist.isNotBlank()) {
                            innerTube.findBestMatch(rawTitle, recoveryQueryArtist, prefetchStreams = false)
                        } else {
                            innerTube.findBestMatch(rawTitle, "", prefetchStreams = false)
                        }
                    }.getOrNull()
                    val recoveredArtist = recovered?.artist?.trim()
                        ?.takeUnless { ArtistHelper.isPlayCountOrStat(it) }
                        ?.takeIf { it.isNotBlank() }
                    if (recoveredArtist != null) {
                        safeArtist = recoveredArtist
                        if (safeAlbumInput.isNullOrBlank()) {
                            safeAlbumInput = recovered.album?.trim()
                                ?.takeUnless { ArtistHelper.isPlayCountOrStat(it) }
                                ?.takeIf { it.isNotBlank() }
                        }
                    }
                }
                val effTitle = rawTitle.ifBlank { title.trim() }
                // Fall back to "Unknown Artist" only for display/filename; the DB
                // repair pass below will re-resolve it when network allows.
                val effArtist = safeArtist?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
                val effArtistForLookup = safeArtist?.takeIf { it.isNotBlank() } ?: ""
                val artistWasStat = rawArtistIsStat || safeArtist.isNullOrBlank()

                // Resolve missing metadata & cover art proactively
                var resolvedArtworkUrl = artworkUrl?.takeIf { ArtworkNormalizer.isRealImage(it) }
                var resolvedAlbum = safeAlbumInput?.takeIf { it.isNotBlank() }
                var preloadedBestMatch: YouTubeMusicTrack? = null

                if (!videoId.isNullOrBlank()) {
                    preloadedBestMatch = runCatching {
                        innerTube.fetchSongDetails(videoId)
                    }.getOrNull()
                }

                if (preloadedBestMatch == null && (resolvedArtworkUrl == null || resolvedAlbum == null)) {
                    preloadedBestMatch = runCatching {
                        innerTube.findBestMatch(effTitle, effArtistForLookup, prefetchStreams = false)
                    }.getOrNull()
                }

                if (preloadedBestMatch != null) {
                    val isPlausible = !videoId.isNullOrBlank() ||
                        TextMatch.isSafeTitleMatch(preloadedBestMatch.title, effTitle, effArtistForLookup)
                    if (!isPlausible) {
                        preloadedBestMatch = null
                    }
                }

                if (preloadedBestMatch != null) {
                    val cleanMatchArtist = preloadedBestMatch.artist
                        .takeUnless { ArtistHelper.isPlayCountOrStat(it) }
                    if (artistWasStat && cleanMatchArtist != null && cleanMatchArtist.isNotBlank()) {
                        safeArtist = cleanMatchArtist.trim()
                    }
                    if (resolvedArtworkUrl == null) {
                        resolvedArtworkUrl = preloadedBestMatch.artworkUrl?.takeIf { ArtworkNormalizer.isRealImage(it) }
                    }
                    if (resolvedAlbum == null) {
                        resolvedAlbum = preloadedBestMatch.album?.trim()
                            ?.takeUnless { ArtistHelper.isPlayCountOrStat(it) }
                            ?.takeIf { it.isNotBlank() }
                    }
                }
                if (resolvedAlbum != null) {
                    resolvedAlbum = resolvedAlbum?.trim()
                        ?.takeUnless { ArtistHelper.isPlayCountOrStat(it) }
                        ?.takeIf { it.isNotBlank() }
                }
                // Final sanitized values for tagging, filenames, folders and DB.
                // safeArtist may have been recovered above; effArtist was snapshotted
                // before, so recompute here.
                val finalTitle = effTitle
                val finalArtist = safeArtist?.trim()?.takeIf { it.isNotBlank() } ?: effArtist
                // DB key must never embed a counter string, otherwise one album
                // fans out into N artists/albums ("15 ml listens", "Track 3"...).
                val finalKey = makeDownloadKey(finalTitle, finalArtist)

                val artworkFallback = if (resolvedArtworkUrl == null) {
                    async(Dispatchers.IO) {
                        artworkRepository.resolve(finalTitle, finalArtist)
                        val cacheKey = ArtworkNormalizer.cacheKey(finalTitle, finalArtist)
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
                ensureDownloadStillAllowed()
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
                    return
                }
                var resolvedUrl: String? = null
                // Exact YouTube videoId that produced this download (when the
                // YouTube path was used) — feeds the thumbnail fallback and
                // honest logging at tagging time.
                var resolvedYtVideoId: String? = videoId?.takeIf { it.isNotBlank() }
                var mimeType = "audio/flac"
                var extension = "flac"
                // No depth claim until a stream actually resolves one: this
                // seed is what a progress notification shows, and "24-BIT" here
                // advertised a bit depth nothing had measured yet.
                var formatBadge = "FLAC"
                var isLossless = false
                var durationMs = 0L
                var downloadHeaders = emptyMap<String, String>()
                var expectedContentLength: Long? = null
                var useParallelDownload = false

                // Honor the download-quality setting (Dolby Atmos / stereo tiers / YouTube).
                // Do not force Dolby Atmos unless the user explicitly selected it as download quality.
                val requestedDownloadQuality = misc.downloadQuality
                val isYouTubeRequested = requestedDownloadQuality == LosslessMusicApi.QUALITY_YOUTUBE

                // 1. Provider module (.lwp engine): progressive clear FLAC/MP3 or segmented DASH (Dolby Atmos / Tidal Hi-Res)
                var moduleDescriptor: SegmentedStreamDescriptor? = null
                var moduleLicenseDeferred: Deferred<OfflineKeys?>? = null
                var isDashModuleDownload = false
                var dashInitUrl: String? = null
                var dashMediaTemplate: String? = null
                var dashSegmentCount = 0
                var dashStartNumber = 1
                /** Real codec from the DASH manifest (`flac` / `mp4a.40.2` / `ec-3`). */
                var dashManifestCodec = ""
                /** Manifest really carries FLAC (so the .m4a wrapper should be unwrapped). */
                var dashIsFlacInMp4 = false
                /** Quality badge to use if a progressive .m4a turns out to hold FLAC. */
                var flacInMp4Badge: String? = null
                var bytesReadTotal = 0L
                var totalBytesRecorded = -1L
                var downloadSucceeded = false
                // Playback demotes Atmos to stereo on incapable devices; do
                // the same here so an Atmos request still yields playable
                // stereo FLAC instead of an unplayable spatial file.
                val atmosCapable by lazy { isAtmosCapableDevice() }
                if (!isYouTubeRequested && losslessMusicApi.isCoolingDown) {
                    android.util.Log.i(
                        "TrackDownloadManager",
                        "Lossless backend cooling down; '$finalTitle' goes straight to YouTube",
                    )
                    updateStage("YouTube • lossless cooling down", notify = true)
                }

                if (!isYouTubeRequested && !losslessMusicApi.isCoolingDown) {
                    updateStage("Finding lossless stream…")
                    // Single source of truth: same tier order as streaming
                    // (LosslessMusicApi.getQualityAttemptOrder). Rules:
                    // - YouTube requested: strict, never reaches here.
                    // - Atmos (28): atmos -> hi-res -> CD -> 320, then YouTube. Only path with 28.
                    // - Hi-Res (27/7): step DOWN only, never up to Atmos. YouTube last.
                    // - Lower tiers: preferred first, then higher above, then lower. Never Atmos.
                    // Data Saver (96k) is skipped unless explicitly chosen (YouTube beats it).
                    val qualitiesToAttempt =
                        LosslessMusicApi.getQualityAttemptOrder(requestedDownloadQuality)
                    for (downloadQuality in qualitiesToAttempt) {
                    // Spatial-tier veto: if the device cannot render spatial audio,
                    // fallback to stereo tiers unless the user explicitly configured
                    // Download Quality = Dolby Atmos.
                    if (downloadQuality == LosslessMusicApi.QUALITY_DOLBY_ATMOS &&
                        !atmosCapable &&
                        requestedDownloadQuality != LosslessMusicApi.QUALITY_DOLBY_ATMOS
                    ) {
                        android.util.Log.i(
                            "TrackDownloadManager",
                            "Atmos veto for $finalTitle by $finalArtist: device cannot render spatial; trying stereo tiers",
                        )
                        continue
                    }
                    try {
                        val expectedDurationSec = durationMs?.takeIf { it > 0 }?.let { (it / 1000L).toInt() }
                            ?: preloadedBestMatch?.durationSeconds?.takeIf { it > 0 }
                        // One retry: a transient blip must not kill the tier.
                        var losslessStream: LosslessAudioStream? = null
                        for (resolveAttempt in 0..1) {
                            losslessStream = runCatching {
                                losslessMusicApi.resolveStream(
                                    title = finalTitle,
                                    artist = finalArtist,
                                    expectedDurationSeconds = expectedDurationSec,
                                    expectedAlbum = resolvedAlbum,
                                    preferredQuality = downloadQuality,
                                    isDownload = true,
                                )
                            }.getOrNull()
                            if (losslessStream != null) break
                            if (resolveAttempt == 0) delay(400)
                        }
                        if (losslessStream != null && losslessStream.url.isBlank()) losslessStream = null
                        // Duration guard: never store a different
                        // recording/edition under this song's name.
                        if (losslessStream != null && expectedDurationSec != null && expectedDurationSec > 0) {
                            val streamSec = losslessStream.durationSeconds
                            if (streamSec > 0 && kotlin.math.abs(streamSec - expectedDurationSec) > 45) {
                                android.util.Log.w(
                                    "TrackDownloadManager",
                                    "Lossless duration mismatch for $finalTitle by $finalArtist: want ${expectedDurationSec}s got ${streamSec}s; trying next tier",
                                )
                                losslessStream = null
                            }
                        }
                        if (losslessStream == null) {
                            android.util.Log.i(
                                "TrackDownloadManager",
                                "Lossless tier $downloadQuality missed for $finalTitle by $finalArtist; trying next tier",
                            )
                        }

                        if (losslessStream != null && losslessStream.url.isNotBlank()) {
                            if (isDashUrl(losslessStream.url, losslessStream.mimeType)) {
                                val parsedDash = parseTidalDashManifest(losslessStream.url)
                                if (parsedDash != null) {
                                    val manifestCodec = parsedDash.codec.ifBlank { "flac" }
                                    // A stereo rendition served for an Atmos
                                    // request is fine (flac codec); a genuine
                                    // spatial manifest on an incapable device
                                    // is unplayable — cascade to stereo tiers.
                                    if (!atmosCapable &&
                                        requestedDownloadQuality != LosslessMusicApi.QUALITY_DOLBY_ATMOS &&
                                        (manifestCodec.startsWith("ec-3") ||
                                            manifestCodec.startsWith("eac3") ||
                                            manifestCodec.startsWith("ac-3"))
                                    ) {
                                        android.util.Log.w(
                                            "TrackDownloadManager",
                                            "Spatial manifest veto for $finalTitle by $finalArtist on incapable device; cascading to stereo tiers",
                                        )
                                        continue
                                    }
                                    val isAtmosStream = losslessStream.formatId == LosslessMusicApi.QUALITY_DOLBY_ATMOS ||
                                        manifestCodec.startsWith("ec-3") ||
                                        manifestCodec.startsWith("eac3") ||
                                        manifestCodec.startsWith("ac-3")
                                    val isFlacStream = manifestCodec.contains("flac")
                                    isDashModuleDownload = true
                                    dashInitUrl = parsedDash.initUrl
                                    dashMediaTemplate = parsedDash.mediaTemplate
                                    dashSegmentCount = parsedDash.segmentCount
                                    dashStartNumber = parsedDash.startNumber
                                    dashManifestCodec = manifestCodec
                                    dashIsFlacInMp4 = isFlacStream
                                    resolvedUrl = parsedDash.initUrl
                                    extension = "m4a"
                                    mimeType = "audio/mp4"
                                    val rateKHz = if (losslessStream.samplingRate > 1000.0) losslessStream.samplingRate / 1000.0 else losslessStream.samplingRate
                                    val depth = losslessStream.bitDepth.takeIf { it > 0 }
                                    formatBadge = when {
                                        isAtmosStream -> "DOLBY ATMOS"
                                        losslessStream.formatId == LosslessMusicApi.QUALITY_MP3_320 -> "320 kbps"
                                        losslessStream.formatId == LosslessMusicApi.QUALITY_DATA_SAVER -> "96 kbps"
                                        isFlacStream -> formatDetailedQualityBadge(depth, rateKHz)
                                        manifestCodec.startsWith("mp4a.40.5") -> "96 kbps"
                                        manifestCodec.startsWith("mp4a") -> "320 kbps"
                                        else -> "AAC"
                                    }
                                    isLossless = isAtmosStream || isFlacStream
                                    durationMs = (losslessStream.durationSeconds * 1000L).takeIf { it > 0 } ?: 0L
                                }
                            } else {
                                val isAtmosStream = losslessStream.formatId == LosslessMusicApi.QUALITY_DOLBY_ATMOS ||
                                    losslessStream.audioCodecOverride == "DOLBY ATMOS"
                                resolvedUrl = losslessStream.url
                                mimeType = losslessStream.mimeType.ifBlank { if (isAtmosStream) "audio/mp4" else "audio/flac" }
                                extension = when {
                                    mimeType.contains("mp3", ignoreCase = true) -> "mp3"
                                    isAtmosStream || mimeType.contains("mp4", ignoreCase = true) || mimeType.contains("m4a", ignoreCase = true) -> "m4a"
                                    mimeType.contains("aac", ignoreCase = true) -> "m4a"
                                    mimeType.contains("opus", ignoreCase = true) || mimeType.contains("ogg", ignoreCase = true) -> "opus"
                                    else -> "flac"
                                }
                                isLossless = !extension.equals("mp3", ignoreCase = true) &&
                                    !extension.equals("m4a", ignoreCase = true) &&
                                    !extension.equals("opus", ignoreCase = true) &&
                                    losslessStream.formatId != LosslessMusicApi.QUALITY_MP3_320 &&
                                    losslessStream.formatId != LosslessMusicApi.QUALITY_DATA_SAVER &&
                                    !mimeType.contains("aac", ignoreCase = true)
                                val rateKHz = if (losslessStream.samplingRate > 1000.0) losslessStream.samplingRate / 1000.0 else losslessStream.samplingRate
                                val depth = losslessStream.bitDepth.takeIf { it > 0 }
                                formatBadge = when {
                                    isAtmosStream -> "DOLBY ATMOS"
                                    losslessStream.formatId == LosslessMusicApi.QUALITY_MP3_320 -> "320 kbps"
                                    losslessStream.formatId == LosslessMusicApi.QUALITY_DATA_SAVER -> "96 kbps"
                                    isLossless -> formatDetailedQualityBadge(depth, rateKHz)
                                    else -> if (extension.equals("mp3", ignoreCase = true)) "MP3" else "AAC"
                                }
                                // Lossless tiers served as .m4a are FLAC in an MP4
                                // wrapper. The file is checked after download.
                                if (!isAtmosStream &&
                                    losslessStream.formatId != LosslessMusicApi.QUALITY_MP3_320 &&
                                    losslessStream.formatId != LosslessMusicApi.QUALITY_DATA_SAVER
                                ) {
                                    flacInMp4Badge = formatDetailedQualityBadge(depth, rateKHz)
                                    // Show the expected lossless quality in the progress
                                    // notification; it falls back to AAC if the check fails.
                                    if (extension == "m4a") formatBadge = flacInMp4Badge ?: formatBadge
                                }
                                durationMs = (losslessStream.durationSeconds * 1000L).takeIf { it > 0 } ?: 0L
                            }
                        }

                        val desc = if (resolvedUrl != null) null else runCatching {
                            moduleResolver.resolve(finalTitle, finalArtist, downloadQuality)
                        }.getOrNull()
                        if (desc != null && desc.stream.baseUrl.isNotBlank()) {
                            val s = desc.stream
                            val isAtmos = s.codec.equals("atmos", ignoreCase = true)
                            if ((s.type == "progressive" || s.segments.isEmpty()) && !isDashUrl(s.baseUrl, s.mimeType) && s.type != "dash_xml") {
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
                                val rateKHz = if (s.sampleRate > 1000) s.sampleRate / 1000.0 else s.sampleRate.toDouble()
                                val depth = s.bitDepth?.takeIf { it > 0 }
                                formatBadge = if (isLossless) {
                                    formatDetailedQualityBadge(depth, rateKHz)
                                } else s.codec.uppercase()
                                durationMs = desc.durationSec * 1000L
                            } else if (s.type == "dash_xml" || isDashUrl(s.baseUrl, s.mimeType)) {
                                val parsedDash = parseTidalDashManifest(s.baseUrl)
                                if (parsedDash != null) {
                                    // Trust the manifest's own codec, not the descriptor's
                                    // assumption. Tidal answers HI_RES/LOSSLESS with
                                    // FLAC-in-MP4 but LOW/HIGH with plain AAC (mp4a),
                                    // and the old code labelled every DASH download as
                                    // FLAC and named it .m4a either way — so a lossless
                                    // request could be stored as AAC while claiming FLAC.
                                    val manifestCodec = parsedDash.codec.ifBlank { s.codec.lowercase() }
                                    if (!atmosCapable &&
                                        requestedDownloadQuality != LosslessMusicApi.QUALITY_DOLBY_ATMOS &&
                                        (manifestCodec.startsWith("ec-3") ||
                                            manifestCodec.startsWith("eac3") ||
                                            manifestCodec.startsWith("ac-3"))
                                    ) {
                                        android.util.Log.w(
                                            "TrackDownloadManager",
                                            "Spatial module manifest veto for $finalTitle by $finalArtist on incapable device; cascading to stereo tiers",
                                        )
                                        continue
                                    }
                                    val isAtmosStream = isAtmos ||
                                        manifestCodec.startsWith("ec-3") ||
                                        manifestCodec.startsWith("eac3") ||
                                        manifestCodec.startsWith("ac-3")
                                    val isFlacStream = manifestCodec.contains("flac")
                                    isDashModuleDownload = true
                                    dashInitUrl = parsedDash.initUrl
                                    dashMediaTemplate = parsedDash.mediaTemplate
                                    dashSegmentCount = parsedDash.segmentCount
                                    dashStartNumber = parsedDash.startNumber
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
                                    val rateKHz = if (s.sampleRate > 1000) s.sampleRate / 1000.0 else s.sampleRate.toDouble()
                                    val depth = s.bitDepth?.takeIf { it > 0 }
                                    formatBadge = when {
                                        isAtmosStream -> "DOLBY ATMOS"
                                        isFlacStream -> formatDetailedQualityBadge(depth, rateKHz)
                                        manifestCodec.startsWith("mp4a.40.5") -> "96 kbps"
                                        manifestCodec.startsWith("mp4a") -> "320 kbps"
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
                                isLossless = !s.codec.equals("opus", ignoreCase = true) &&
                                    !s.codec.equals("mp3", ignoreCase = true) &&
                                    !s.codec.equals("aac", ignoreCase = true) &&
                                    !s.codec.contains("mp4a", ignoreCase = true)
                                durationMs = desc.durationSec * 1000L
                                // Offline license in parallel with the bytes.
                                moduleLicenseDeferred = applicationScope.async(Dispatchers.IO) {
                                    runCatching { offlineLicense.acquire(desc) }.getOrNull()
                                }
                            }
                        }

                        if (resolvedUrl != null) {
                            val rawFile = File.createTempFile("dl_raw_", ".$extension", context.cacheDir)
                                tempDownloadFile = rawFile

                                if (isDashModuleDownload && dashInitUrl != null && dashMediaTemplate != null && dashSegmentCount > 0) {
                                    val dashResult = downloadDashSegmentsToTempFile(
                                        downloadKey = key,
                                        notificationId = notifId,
                                        title = title,
                                        artist = artist,
                                        formatBadge = formatBadge,
                                        initUrl = dashInitUrl!!,
                                        mediaTemplate = dashMediaTemplate!!,
                                        segmentCount = dashSegmentCount,
                                        startNumber = dashStartNumber,
                                        headers = downloadHeaders,
                                        target = rawFile,
                                    )
                                    bytesReadTotal = dashResult.bytesWritten
                                    totalBytesRecorded = bytesReadTotal
                                    // A truncated segment list used to be kept
                                    // as a successful (short) file. Fail loudly
                                    // instead so the next tier or YouTube gets
                                    // a chance — unless enough audio arrived
                                    // that the manifest simply over-declared.
                                    if (dashResult.stoppedEarly) {
                                        val minBytes = if (durationMs > 0) {
                                            (durationMs / 1000L) * 16_000L
                                        } else {
                                            256_000L
                                        }
                                        if (bytesReadTotal < minBytes) {
                                            throw IOException(
                                                "Incomplete DASH download for $title by $artist: " +
                                                    "${dashResult.completedSegments}/${dashResult.expectedSegments} segments",
                                            )
                                        }
                                        android.util.Log.w(
                                            "TrackDownloadManager",
                                            "DASH over-declared segments for $title by $artist " +
                                                "(${dashResult.completedSegments}/${dashResult.expectedSegments}); keeping ${bytesReadTotal} bytes",
                                        )
                                    }
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
                                        formatBadge = flacInMp4Badge ?: "M4A AAC"
                                    }
                                    bytesReadTotal = transfer.bytesDownloaded
                                    totalBytesRecorded = transfer.totalBytes
                                }

                                if (!isDashModuleDownload) {
                                    val detected = detectValidContainer(rawFile)
                                    if (detected != null) {
                                        extension = detected
                                        mimeType = when (detected) {
                                            "m4a" -> "audio/mp4"
                                            "webm" -> "audio/webm"
                                            "opus" -> "audio/ogg"
                                            "mp3" -> "audio/mpeg"
                                            "flac" -> "audio/flac"
                                            else -> mimeType
                                        }
                                    } else if (useParallelDownload && !hasExpectedContainer(rawFile, extension)) {
                                        throw IOException("Downloaded payload is not a valid ${extension.uppercase()} audio file")
                                    }
                                }
                                downloadSucceeded = true
                            }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (moduleError: Throwable) {
                        android.util.Log.w("TrackDownloadManager", "Download (quality=$downloadQuality) failed for $title by $artist; trying next tier or YouTube", moduleError)
                        moduleLicenseDeferred?.cancel()
                        moduleLicenseDeferred = null
                        moduleDescriptor = null
                        isDashModuleDownload = false
                        resolvedUrl = null
                        tempDownloadFile?.let { runCatching { if (it.exists()) it.delete() } }
                        tempDownloadFile = null
                        downloadSucceeded = false
                        // Reset state for potential retry at lower quality
                        mimeType = "audio/flac"
                        extension = "flac"
                        // Depth is unknown again until the retry resolves one.
                        formatBadge = "FLAC"
                        isLossless = false
                        durationMs = 0L
                        downloadHeaders = emptyMap()
                        expectedContentLength = null
                        useParallelDownload = false
                        dashInitUrl = null
                        dashMediaTemplate = null
                        dashSegmentCount = 0
                        dashStartNumber = 1
                        dashManifestCodec = ""
                        dashIsFlacInMp4 = false
                        flacInMp4Badge = null
                        bytesReadTotal = 0L
                        totalBytesRecorded = -1L
                        continue
                    }
                    if (downloadSucceeded) break
                    } // end quality retry loop
                }

                // 2. Fallback to YouTube Music if module was not requested or module download failed
                // A failed YouTube transfer (URL expired, refused mid-file, bad
                // payload) is retried on a fresh resolve: reporting the stream
                // cools its client down, so the next pass lands on another one.
                // It used to skip the song after the first stream failed.
                var ytAttempt = 0
                var failedYtStream: com.lastwave.app.data.music.YouTubeAudioStream? = null
                while (!downloadSucceeded) {
                    ytAttempt++
                    failedYtStream = null
                    try {
                        updateStage("Finding YouTube stream…", notify = true)
                        val lookupArtist = safeArtist?.trim()?.takeIf { it.isNotBlank() } ?: ""
                        // Same path as playback (SimpMusic/Metrolist/ArchiveTune style):
                        // explicit videoId first, then cached/broad search candidates.
                        // Never single-shot strict match — search more, try each.
                        // Budgeted so one wedged track cannot hold a
                        // download slot and stall the batch queue.
                        val ytResolveBudgetMs = 60_000L
                        val candidateTracks = linkedMapOf<String, YouTubeMusicTrack>()
                        val isPlausibleTrack: (YouTubeMusicTrack) -> Boolean = { track ->
                            track.videoId.isNotBlank() && (track.videoId == videoId || TextMatch.isSafeTitleMatch(track.title, finalTitle, lookupArtist))
                        }
                        videoId?.takeIf { it.isNotBlank() }?.let { candidateTracks[it] =
                            preloadedBestMatch?.takeIf { match -> match.videoId == it }
                                ?: YouTubeMusicTrack(videoId = it, title = finalTitle, artist = finalArtist)
                        }
                        preloadedBestMatch?.takeIf(isPlausibleTrack)?.let { match ->
                            candidateTracks.putIfAbsent(match.videoId, match)
                        }
                        // Broad search is capped inside (10 merged candidates);
                        // the 60s resolve budget below bounds the slow path.
                        runCatching {
                            innerTube.findDownloadCandidates(finalTitle, lookupArtist)
                        }.getOrDefault(emptyList()).filter(isPlausibleTrack).forEach { track ->
                            candidateTracks.putIfAbsent(track.videoId, track)
                        }
                        // Last resort: legacy single strict match (kept for metadata only).
                        if (candidateTracks.isEmpty()) {
                            runCatching {
                                innerTube.findBestMatch(finalTitle, lookupArtist, prefetchStreams = false)
                            }.getOrNull()?.takeIf(isPlausibleTrack)?.let { track ->
                                candidateTracks.putIfAbsent(track.videoId, track)
                            }
                        }
                        if (candidateTracks.isEmpty()) throw IOException("No YouTube match found for $finalTitle by $finalArtist")
                        var pickedStream: com.lastwave.app.data.music.YouTubeAudioStream? = null
                        var actualVideoId: String? = null
                        var chosenTrack: YouTubeMusicTrack? = null
                        var lastResolveError: Throwable? = null
                        // Parallel resolves queue on one local cipher until their budgets
                        // expire, and bursts of player requests invite the bot check.
                        // Only resolving is limited here; transfers still run in parallel.
                        youtubeResolveGate.acquire()
                        try {
                            val ytFallbackStartMs = android.os.SystemClock.elapsedRealtime()
                            for (resolveRound in 0..1) {
                                for ((candidateId, candidateTrack) in candidateTracks) {
                                    if (android.os.SystemClock.elapsedRealtime() - ytFallbackStartMs > ytResolveBudgetMs) {
                                        android.util.Log.w(
                                            "TrackDownloadManager",
                                            "YouTube resolve budget exhausted for $finalTitle by $finalArtist; skipping remaining candidates",
                                        )
                                        break
                                    }
                                    // 1. Same waterfall playback uses (cache + multi-client direct URLs).
                                    val playbackStream = try {
                                        kotlinx.coroutines.withTimeoutOrNull(YT_STREAM_RESOLVE_TIMEOUT_MS) {
                                            innerTube.peekCachedStream(candidateId)
                                                ?: innerTube.resolveAudioStream(candidateId)
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        lastResolveError = error
                                        null
                                    }
                                    // 2. Opus preference: if playback picked AAC (or failed),
                                    // ask NewPipe for the WebM/Opus rendition, which is
                                    // remuxed below into a fully tagged .opus file.
                                    val opusStream = playbackStream?.takeIf { it.isOpusStream() } ?: try {
                                        kotlinx.coroutines.withTimeoutOrNull(YT_STREAM_RESOLVE_TIMEOUT_MS) {
                                            innerTube.resolveDownloadStream(candidateId)
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        lastResolveError = error
                                        null
                                    }
                                    // An HLS playlist is not a file: saving it wrote the playlist
                                    // text as the "song". Only progressive streams can be downloaded.
                                    pickedStream = listOfNotNull(
                                        opusStream?.takeIf { it.isOpusStream() },
                                        playbackStream,
                                        opusStream,
                                    ).firstOrNull { !it.isAdaptive }
                                    if (pickedStream != null) {
                                        actualVideoId = candidateId
                                        resolvedYtVideoId = candidateId
                                        chosenTrack = candidateTrack
                                        break
                                    }
                                }
                                if (pickedStream != null ||
                                    android.os.SystemClock.elapsedRealtime() - ytFallbackStartMs > ytResolveBudgetMs
                                ) {
                                    break
                                }
                                // One more pass for a dropped connection or a brief failure.
                                if (resolveRound == 0) awaitNetworkForResolveRetry()
                            }
                        } finally {
                            youtubeResolveGate.release()
                        }
                        val resolvedStream = pickedStream
                            ?: throw (lastResolveError ?: IOException("No playable YouTube stream for $finalTitle by $finalArtist"))
                        failedYtStream = resolvedStream
                        if (resolvedArtworkUrl == null) {
                            resolvedArtworkUrl = (chosenTrack?.artworkUrl ?: preloadedBestMatch?.artworkUrl)
                                ?.takeIf { ArtworkNormalizer.isRealImage(it) }
                        }
                        if (resolvedAlbum == null) resolvedAlbum = (chosenTrack?.album ?: preloadedBestMatch?.album)?.trim()
                            ?.takeUnless { ArtistHelper.isPlayCountOrStat(it) }
                            ?.takeIf { it.isNotBlank() }
                        val ytStream = resolvedStream
                        resolvedUrl = ytStream.url
                        // The URL is bound to the client that minted it: send that
                        // client's User-Agent, as playback does, or googlevideo 403s.
                        downloadHeaders = ytStream.requestHeaders.let { headers ->
                            if (headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) headers
                            else headers + ("User-Agent" to ytStream.userAgent)
                        }
                        expectedContentLength = ytStream.contentLength
                            ?: runCatching { Uri.parse(ytStream.url).getQueryParameter("clen")?.toLongOrNull() }.getOrNull()
                        if (durationMs <= 0L) {
                            val candidateMs = pickedStream?.durationMs
                                ?: (chosenTrack?.durationSeconds?.takeIf { it > 0 }?.times(1000L))
                                ?: (preloadedBestMatch?.durationSeconds?.takeIf { it > 0 }?.times(1000L))
                            if (candidateMs != null && candidateMs > 0L) {
                                durationMs = candidateMs
                            }
                        }
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

                        val detected = detectValidContainer(rawFile)
                        if (detected != null) {
                            // SimpMusic/Metrolist style: accept whatever valid
                            // audio YouTube served (opus/webm/m4a/mp3) — remux
                            // below normalizes it. Never fail on mime mismatch.
                            extension = detected
                            mimeType = when (detected) {
                                "m4a" -> "audio/mp4"
                                "webm" -> "audio/webm"
                                "opus" -> "audio/ogg"
                                "mp3" -> "audio/mpeg"
                                "flac" -> "audio/flac"
                                else -> mimeType
                            }
                        } else if (rawFile.length() < MIN_VALID_AUDIO_BYTES || !hasExpectedContainer(rawFile, extension)) {
                            // Only reject when the payload is not audio at all
                            // (HTML error page / truncated). A valid container
                            // with an unexpected extension is kept.
                            if (detectValidContainer(rawFile) == null && rawFile.length() < MIN_VALID_AUDIO_BYTES) {
                                throw IOException("Downloaded payload is not valid audio (${rawFile.length()} bytes)")
                            }
                        }
                        downloadSucceeded = true
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (ytError: Throwable) {
                        tempDownloadFile?.let { runCatching { if (it.exists()) it.delete() } }
                        tempDownloadFile = null
                        val rejected = failedYtStream
                        if (rejected != null && ytAttempt < YT_DOWNLOAD_ATTEMPTS) {
                            android.util.Log.w(
                                "TrackDownloadManager",
                                "YouTube transfer failed for $title by $artist (attempt $ytAttempt, ${rejected.clientProfile}); re-resolving",
                                ytError,
                            )
                            runCatching { innerTube.reportPlaybackFailure(rejected.videoId, rejected) }
                            continue
                        }
                        // Surface the real reason (no match vs no stream vs
                        // network) so a playable song never shows a generic skip.
                        val reason = ytError.localizedMessage?.takeIf { it.isNotBlank() }
                            ?: "Stream unavailable"
                        val shortReason = if (reason.length > 90) reason.take(90) + "…" else reason
                        android.util.Log.w("TrackDownloadManager", "YouTube download failed for $title by $artist: $reason", ytError)
                        tempDownloadFile?.let { runCatching { if (it.exists()) it.delete() } }
                        tempDownloadFile = null
                        updateProgress(
                            DownloadProgress(
                                key = key,
                                title = title,
                                artist = artist,
                                progressPercent = 0,
                                error = "Skipped: $shortReason",
                            ),
                        )
                        runCatching {
                            showErrorNotification(notifId, key, title, artist, "Skipped: $shortReason")
                        }
                        return
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
                                title = finalTitle,
                                artist = finalArtist,
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
                    // Addon streams can also be progressive FLAC-in-MP4 (served
                    // as audio/mp4), so check the file and not only the manifest.
                    val progressiveFlacInMp4 = !dashIsFlacInMp4 && extension == "m4a" &&
                        flacInMp4Badge != null && Mp4FlacRemuxer.isMp4Flac(currentAudioFile)
                    if (progressiveFlacInMp4) {
                        isLossless = true
                        formatBadge = flacInMp4Badge ?: "FLAC"
                    } else if (!dashIsFlacInMp4 && extension == "m4a" && flacInMp4Badge != null) {
                        // Lossless tier, but the file really is AAC.
                        isLossless = false
                        formatBadge = "M4A AAC"
                    }
                    if ((dashIsFlacInMp4 || progressiveFlacInMp4) && extension == "m4a") {
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
                                title = finalTitle,
                                artist = finalArtist,
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

                    val safeFilename = sanitizeFilename("$finalArtist - $finalTitle") + ".$extension"

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
                                    title = finalTitle,
                                    artist = finalArtist,
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
                        // await() outside runCatching used to fail the whole
                        // download when the artwork fetch itself threw.
                        resolvedArtworkUrl = runCatching { artworkFallback?.await() }.getOrNull()
                    } else {
                        artworkFallback?.cancel()
                    }
                    if (resolvedArtworkUrl == null) {
                        // Last resort (SimpMusic/Metrolist style): prefer YouTube Music thumbnail if available,
                        // otherwise the YouTube thumbnail for the exact downloaded video.
                        val ytMusicThumb = preloadedBestMatch?.artworkUrl?.takeIf {
                            ArtworkNormalizer.isRealImage(it) && ArtworkNormalizer.isYtMusicThumbnail(it)
                        }
                        resolvedArtworkUrl = ytMusicThumb
                            ?: preloadedBestMatch?.artworkUrl?.takeIf { ArtworkNormalizer.isRealImage(it) }
                            ?: resolvedYtVideoId?.takeIf { it.isNotBlank() }?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
                    }

                    // 4. Embed metadata, cover art AND lyrics directly into the
                    // downloaded audio file (container-aware: Vorbis comments +
                    // PICTURE for FLAC/Opus, Matroska tags for WebM,
                    // iTunes atoms for M4A, ID3v2.3 otherwise).
                    val metadataEmbedded = audioTagWriter.embedMetadata(
                        audioFile = currentAudioFile,
                        title = finalTitle,
                        artist = finalArtist,
                        album = resolvedAlbum,
                        artworkUrl = resolvedArtworkUrl,
                        artworkFallbackUrl = preloadedBestMatch?.artworkUrl,
                        lyrics = if (shouldDownloadLyrics) (syncedLyrics ?: plainLyrics) else null,
                        year = year,
                    )
                    if (!metadataEmbedded) {
                        android.util.Log.w("TrackDownloadManager", "Could not safely embed audio metadata; preserving audio file")
                    }

                    // 4b. Finalize fragmented MP4 indexing (Atmos & AAC .m4a):
                    // Patch duration into mvhd/mdhd and append Movie Fragment Random
                    // Access (mfra) index so ExoPlayer and external players can seek.
                    if (extension.equals("m4a", ignoreCase = true) || extension.equals("mp4", ignoreCase = true)) {
                        Fmp4IndexBuilder.finalizeFmp4(currentAudioFile, durationMs)
                    }

                    // 5. Transfer tagged file to public storage / MediaStore
                    val dirName = sanitizeDownloadFolderName(misc.downloadFolder)
                    val subpath = downloadSubpath(
                        artist = finalArtist,
                        album = resolvedAlbum,
                        year = year,
                        structure = misc.downloadStructure,
                        useAlbumArtist = misc.useAlbumArtistForFolders,
                        primaryOnly = misc.primaryArtistOnly,
                    )
                    ensureDownloadStillAllowed()
                    val (destStream, uri, file) = openPublicOutputStream(
                        filename = safeFilename,
                        mimeType = mimeType,
                        title = finalTitle,
                        artist = finalArtist,
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


                // 7. Also write the sidecar .lrc companion file for players that read them.
                // Stop before creating lyrics or the database row if a wipe has
                // cancelled this worker; the wipe joins us before it deletes rows.
                ensureDownloadStillAllowed()
                if (shouldDownloadLyrics) {
                    val lyricsText = syncedLyrics ?: plainLyrics
                    if (!lyricsText.isNullOrBlank()) {
                        val lrcFilename = sanitizeFilename("$finalArtist - $finalTitle") + ".lrc"
                        lrcPath = writePublicCompanionFile(lrcFilename, lyricsText, "text/plain", dirName, subpath, customTreeUri)
                    }
                }

                // 6. Persist to Room database (finalKey never embeds counters,
                // so one album can no longer fan out into N artists/albums).
                val entity = DownloadedTrackEntity(
                    trackKey = finalKey,
                    title = finalTitle,
                    artist = finalArtist,
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
                unhideFromHistory(entity)

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
                        finalTitle, finalArtist,
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
                        title = finalTitle,
                        artist = finalArtist,
                        progressPercent = 100,
                        bytesDownloaded = bytesReadTotal,
                        totalBytes = if (totalBytesRecorded > 0) totalBytesRecorded else bytesReadTotal,
                        formatBadge = formatBadge,
                        isFinished = true,
                    ),
                )

                // Surface missing extras honestly instead of silently:
                // the file is fine, but the user sees what didn't attach.
                val missingExtras = buildList {
                    if (shouldDownloadLyrics && !hasLyrics) add("no lyrics")
                    if (resolvedArtworkUrl == null) add("no art")
                    if (!metadataEmbedded) add("untagged")
                }
                if (missingExtras.isNotEmpty()) {
                    android.util.Log.w(
                        "TrackDownloadManager",
                        "Download complete with missing extras for $finalTitle by $finalArtist: ${missingExtras.joinToString()}",
                    )
                }
                val completedBadge = if (missingExtras.isEmpty()) formatBadge else {
                    "$formatBadge • ${missingExtras.joinToString(" + ")}"
                }
                runCatching { showCompletedNotification(notifId, finalTitle, finalArtist, completedBadge) }
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

    internal fun updateProgress(progress: DownloadProgress) {
        _downloads.update { it + (progress.key to progress) }
    }

    sealed interface DeleteDownloadResult {
        object Deleted : DeleteDownloadResult

        /** The file belongs to another app; the user has to confirm the delete. */
        class NeedsUserConsent(val request: android.content.IntentSender) : DeleteDownloadResult

        object Failed : DeleteDownloadResult
    }

    sealed interface ClearAllDownloadsResult {
        object Cleared : ClearAllDownloadsResult

        data class NeedsUserConsent(
            val request: android.content.IntentSender,
            val tracks: List<DownloadedTrackEntity>,
        ) : ClearAllDownloadsResult

        object Failed : ClearAllDownloadsResult
    }

}
