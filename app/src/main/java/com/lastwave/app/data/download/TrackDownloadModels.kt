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

internal data class DownloadTransfer(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val contentType: String,
)

internal data class DownloadRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1L
}

internal data class ParsedContentRange(
    val start: Long,
    val endInclusive: Long,
    val total: Long,
)

internal class DownloadHttpException(val statusCode: Int) :
    IOException("HTTP $statusCode downloading track")

internal class DownloadProtocolException(message: String) : IOException(message)

internal class DownloadInterruptedException(message: String, cause: Throwable? = null) :
    IOException(message, cause)


internal data class HiddenDownload(val key: String, val refs: Set<String>) {
fun encode(): String = (listOf(key) + refs).joinToString("\n")
companion object {
fun of(track: DownloadedTrackEntity, extraRefs: Collection<String> = emptyList()) = HiddenDownload(
key = track.trackKey,
refs = (
listOfNotNull(
track.filePath.takeIf { it.isNotBlank() },
track.mediaStoreUri?.takeIf { it.isNotBlank() },
) + extraRefs.filter { it.isNotBlank() }
).toSet(),
)
fun decode(raw: String): HiddenDownload {
val parts = raw.split("\n")
return HiddenDownload(parts.first(), parts.drop(1).filter { it.isNotBlank() }.toSet())
}
}
}
internal class HiddenHistory(val entries: List<HiddenDownload>) {
val keys: Set<String> = entries.mapTo(mutableSetOf()) { it.key }
val refs: Set<String> = entries.flatMapTo(mutableSetOf()) { it.refs }
}


internal const val CHANNEL_ID = "lastwave_downloads"
internal const val ACTION_CANCEL_DOWNLOAD = "com.lastwave.app.ACTION_CANCEL_DOWNLOAD"
internal const val ACTION_RECONNECT_DOWNLOAD = "com.lastwave.app.ACTION_RECONNECT_DOWNLOAD"
internal const val ACTION_VIEW_DOWNLOADS = "com.lastwave.app.ACTION_VIEW_DOWNLOADS"
internal const val EXTRA_DOWNLOAD_KEY = "download_key"
internal const val EXTRA_DOWNLOAD_TITLE = "download_title"
internal const val EXTRA_DOWNLOAD_ARTIST = "download_artist"
internal const val EXTRA_NAVIGATE_TO = "navigate_to"
internal const val PUBLIC_DIR_NAME = "LastWave"
internal const val LEGACY_PUBLIC_DIR_NAME = "LastWave"
internal const val DOWNLOAD_BUFFER_SIZE = 128 * 1024
internal const val PARALLEL_YOUTUBE_PARTS = 4
internal const val MAX_CONCURRENT_DOWNLOADS = 5
internal const val MAX_CONCURRENT_YOUTUBE_RESOLVES = 2
internal const val RESOLVE_RETRY_DELAY_MS = 3_000L
internal const val RESOLVE_RETRY_NETWORK_WAIT_MS = 15_000L
internal const val MAX_TRANSFER_RETRIES = 6
internal const val YT_STREAM_RESOLVE_TIMEOUT_MS = 20_000L
internal const val YT_DOWNLOAD_ATTEMPTS = 3
internal const val MIN_PARALLEL_DOWNLOAD_BYTES = 2L * 1024 * 1024
internal const val YOUTUBE_RANGE_BYTES = 512L * 1024
internal const val MIN_VALID_AUDIO_BYTES = 1_024L
internal const val RECONNECT_POLL_INTERVAL_MS = 500L
internal const val RECONNECT_RETRY_BASE_DELAY_MS = 1_000L
internal const val RECONNECT_RETRY_MAX_DELAY_MS = 30_000L
internal const val DOWNLOAD_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
internal val CONTENT_RANGE_PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
internal const val SCAN_PREFS_NAME = "downloads_restore_prefs"
internal const val KEY_DOWNLOADS_SCANNED = "downloads_scanned"
internal const val HIDDEN_PREFS_NAME = "download_history_hidden"
internal const val KEY_HIDDEN_REFS = "hidden_refs"
internal const val SCAN_MARKER_FILE = "downloads_scanned.marker"
internal val AUDIO_EXTENSIONS = setOf("flac", "m4a", "mp3", "opus", "ogg", "webm", "wav", "aac")

internal fun makeDownloadKey(title: String, artist: String): String =
    "${artist.trim().lowercase()}_${title.trim().lowercase()}"

internal fun isDownloadsScanned(context: Context): Boolean = runCatching {
    val prefs = context.getSharedPreferences(SCAN_PREFS_NAME, Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_DOWNLOADS_SCANNED, false)) return@runCatching true
    val marker = java.io.File(context.noBackupFilesDir, SCAN_MARKER_FILE)
    marker.exists()
}.getOrDefault(false)

internal fun getRequiredAudioPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }


internal val BRACKET_SONG_ID_REGEX = Regex("""\[([a-zA-Z0-9_-]{11})\]""")
internal val PAREN_SONG_ID_REGEX = Regex("""\(([a-zA-Z0-9_-]{11})\)""")
internal val TRAILING_SONG_ID_REGEX = Regex("""(?:^|[\s\-_])([a-zA-Z0-9_-]{11})$""")
internal val EXACT_SONG_ID_REGEX = Regex("""^[a-zA-Z0-9_-]{11}$""")

internal fun extractSongId(nameWithoutExt: String): String? {
    BRACKET_SONG_ID_REGEX.find(nameWithoutExt)?.let { return it.groupValues[1] }
    PAREN_SONG_ID_REGEX.find(nameWithoutExt)?.let { return it.groupValues[1] }
    if (EXACT_SONG_ID_REGEX.matches(nameWithoutExt)) return nameWithoutExt
    TRAILING_SONG_ID_REGEX.find(nameWithoutExt)?.let { return it.groupValues[1] }
    return null
}

internal fun cleanSongTitle(rawTitle: String, songId: String?): String {
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

internal fun parseArtistAndTitleFromFilename(nameWithoutExt: String, songId: String?): Pair<String, String> {
    val cleanName = cleanSongTitle(nameWithoutExt, songId)
    return if (cleanName.contains(" - ")) {
        val artist = cleanName.substringBefore(" - ").trim().ifBlank { "Unknown Artist" }
        val title = cleanName.substringAfter(" - ").trim().ifBlank { cleanName }
        artist to title
    } else {
        "Unknown Artist" to cleanName.ifBlank { nameWithoutExt }
    }
}
