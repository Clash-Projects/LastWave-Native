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


internal fun TrackDownloadManager.openPublicOutputStream(
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


internal fun TrackDownloadManager.finalizePublicFile(uri: Uri?) {
        // IS_PENDING only exists on MediaStore Uris — SAF document Uris are
        // already visible once the stream closes.
        if (uri != null && uri.authority == MediaStore.AUTHORITY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            runCatching { context.contentResolver.update(uri, contentValues, null, null) }
        }
    }

internal fun TrackDownloadManager.writePublicCompanionFile(
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


    /**
     * One-time self-heal for libraries corrupted before counter-string
     * hardening ("15 ml listens", "Track 16", ... as artist, one album fanned
     * out into N single-track albums). Runs offline-first inside
     * [syncDownloadsFromStorage]: groups by album to restore the dominant real
     * artist without network, then best-effort re-resolves leftovers via
     * InnerTube. Never deletes user files, only fixes DB rows (and best-effort
     * file tags). Safe to run on every sync; no-ops when clean.
     */
suspend fun TrackDownloadManager.repairCorruptedDownloadMetadata() = withContext(Dispatchers.IO) {
        val all = runCatching { downloadedTrackDao.getAllList() }.getOrNull().orEmpty()
        if (all.isEmpty()) return@withContext
        val corrupted = all.filter { ArtistHelper.isPlayCountOrStat(it.artist) }
        if (corrupted.isEmpty()) return@withContext

        // Offline pass: within each album, adopt the dominant non-stat artist.
        // This reunites "one album -> N artists/albums" without any network.
        val byAlbum = all.groupBy { it.album.trim().lowercase() }
        var fixedOffline = 0
        for (corrupt in corrupted.toList()) {
            val albumKey = corrupt.album.trim().lowercase()
            if (albumKey.isBlank() || albumKey == "singles") continue
            val siblings = byAlbum[albumKey].orEmpty()
            val dominant = siblings.map { it.artist.trim() }
                .filter { it.isNotBlank() && !ArtistHelper.isPlayCountOrStat(it) }
                .groupBy { it.lowercase() }
                .maxByOrNull { it.value.size }
                ?.value?.firstOrNull()
            if (dominant.isNullOrBlank()) continue
            val newKey = makeDownloadKey(corrupt.title, dominant)
            val clash = runCatching { downloadedTrackDao.findByTrackKey(newKey) }.getOrNull()
            try {
                if (clash != null && clash.id != corrupt.id) {
                    // Good row already holds the correct key; drop the dupe.
                    runCatching { downloadedTrackDao.delete(corrupt) }
                } else {
                    runCatching {
                        downloadedTrackDao.insert(
                            corrupt.copy(artist = dominant, trackKey = newKey),
                        )
                    }
                    // Best-effort file retag so external players / re-imports
                    // don't resurrect the counter string.
                    runCatching {
                        val f = File(corrupt.filePath)
                        if (f.exists() && f.isFile) {
                            audioTagWriter.embedMetadata(
                                audioFile = f,
                                title = corrupt.title,
                                artist = dominant,
                                album = corrupt.album.takeIf { it.isNotBlank() },
                                artworkUrl = corrupt.artworkUrl,
                                artworkFallbackUrl = null,
                                lyrics = corrupt.syncedLyrics ?: corrupt.plainLyrics,
                                year = null,
                            )
                        }
                    }
                }
                fixedOffline++
            } catch (_: Exception) { }
        }

        // Network pass for leftovers (no dominant sibling, or Singles/blank album).
        val remaining = runCatching { downloadedTrackDao.getAllList() }.getOrNull().orEmpty()
            .filter { ArtistHelper.isPlayCountOrStat(it.artist) }
            .take(25)
        for (corrupt in remaining) {
            try {
                val albumHint = corrupt.album.trim()
                    .takeUnless { ArtistHelper.isPlayCountOrStat(it) }.orEmpty()
                val match = runCatching {
                    if (albumHint.isNotBlank()) {
                        innerTube.findBestMatchOrNull(corrupt.title, albumHint, prefetchStreams = false)
                    } else {
                        innerTube.findBestMatchOrNull(corrupt.title, "", prefetchStreams = false)
                    }
                }.getOrNull() ?: continue
                val cleanArtist = match.artist.trim()
                    .takeUnless { ArtistHelper.isPlayCountOrStat(it) }
                    ?.takeIf { it.isNotBlank() } ?: continue
                val cleanAlbum = match.album?.trim()
                    ?.takeUnless { ArtistHelper.isPlayCountOrStat(it) }
                    ?.takeIf { it.isNotBlank() } ?: corrupt.album
                // Don't adopt a different song's metadata: title must still match.
                if (!match.title.equals(corrupt.title, ignoreCase = true) &&
                    match.title.lowercase().none { it in corrupt.title.lowercase() } &&
                    corrupt.title.lowercase().none { it in match.title.lowercase() }
                ) continue
                val newKey = makeDownloadKey(corrupt.title, cleanArtist)
                val clash = runCatching { downloadedTrackDao.findByTrackKey(newKey) }.getOrNull()
                if (clash != null && clash.id != corrupt.id) {
                    runCatching { downloadedTrackDao.delete(corrupt) }
                } else {
                    runCatching {
                        downloadedTrackDao.insert(
                            corrupt.copy(artist = cleanArtist, album = cleanAlbum, trackKey = newKey),
                        )
                    }
                    runCatching {
                        val f = File(corrupt.filePath)
                        if (f.exists() && f.isFile) {
                            audioTagWriter.embedMetadata(
                                audioFile = f,
                                title = corrupt.title,
                                artist = cleanArtist,
                                album = cleanAlbum.takeIf { it.isNotBlank() },
                                artworkUrl = corrupt.artworkUrl,
                                artworkFallbackUrl = match.artworkUrl,
                                lyrics = corrupt.syncedLyrics ?: corrupt.plainLyrics,
                                year = null,
                            )
                        }
                    }
                }
            } catch (_: Exception) { }
        }
    }

fun TrackDownloadManager.isDownloadsScanned(): Boolean = TrackDownloadManager.isDownloadsScanned(context)

internal fun TrackDownloadManager.markDownloadsScanned() {
        runCatching {
            context.getSharedPreferences(SCAN_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DOWNLOADS_SCANNED, true)
                .apply()
        }
        runCatching {
            val marker = File(context.noBackupFilesDir, SCAN_MARKER_FILE)
            if (!marker.exists()) {
                marker.parentFile?.mkdirs()
                marker.createNewFile()
            }
        }
    }

fun TrackDownloadManager.getRequiredAudioPermission(): String = TrackDownloadManager.getRequiredAudioPermission()

fun TrackDownloadManager.hasStoragePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, getRequiredAudioPermission()) == PackageManager.PERMISSION_GRANTED

internal suspend fun TrackDownloadManager.restoreDownloadsAfterReinstallImpl(): Boolean = withContext(Dispatchers.IO) {
        if (isDownloadsScanned()) return@withContext true

        restoreMutex.withLock {
            if (isDownloadsScanned()) return@withContext true

            val dirName = currentDownloadDirName()
            val hasPermission = hasStoragePermission()
            if (!hasPermission) {
                // If storage permission isn't granted yet, don't prompt just for this.
                // Leave the flag unset and retry on a later launch, unless the folder
                // is already directly readable on this device (e.g. legacy/custom storage).
                val canDirectlyRead = runCatching {
                    downloadSearchDirs(dirName).any { dir ->
                        dir.exists() && dir.canRead() && dir.listFiles()?.any { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS } == true
                    }
                }.getOrDefault(false)
                if (!canDirectlyRead) {
                    return@withContext false
                }
            }

            val existingEntities = runCatching { downloadedTrackDao.getAllList() }.getOrDefault(emptyList()).toMutableList()
            val existingPaths = existingEntities.map { it.filePath }.toMutableSet()
            val existingUris = existingEntities.mapNotNull { it.mediaStoreUri }.toMutableSet()
            val existingKeys = existingEntities.map { it.trackKey }.toMutableSet()
            val existingSongIds = mutableSetOf<String>()

            for (entity in existingEntities) {
                extractSongId(File(entity.filePath).nameWithoutExtension)?.let { existingSongIds.add(it) }
                extractSongId(entity.title)?.let { existingSongIds.add(it) }
                entity.artworkUrl?.let { url ->
                    if (url.contains("i.ytimg.com/vi/")) {
                        val id = url.substringAfter("i.ytimg.com/vi/").substringBefore("/")
                        if (id.length == 11) existingSongIds.add(id)
                    }
                }
            }

            // 1. Scan public download directories on device (active folder + legacy default, incl. subfolders)
            val musicDirs = downloadSearchDirs(dirName).filter { it.exists() && it.isDirectory }
            val seenDirs = mutableSetOf<String>()
            for (musicDir in musicDirs) {
                if (!seenDirs.add(musicDir.absolutePath)) continue
                val audioFiles = runCatching {
                    musicDir.walkTopDown().maxDepth(6)
                        .filter { file ->
                            file.isFile &&
                                file.extension.lowercase() in AUDIO_EXTENSIONS &&
                                !file.name.lowercase().endsWith(".part") &&
                                !file.name.lowercase().endsWith(".tmp") &&
                                !file.name.lowercase().endsWith(".crdownload") &&
                                !file.name.lowercase().endsWith(".download") &&
                                !file.name.lowercase().contains(".part.") &&
                                file.length() >= MIN_VALID_AUDIO_BYTES
                        }
                        .toList()
                }.getOrNull().orEmpty()

                for (file in audioFiles) {
                    if (file.absolutePath in existingPaths) continue
                    if (activeFiles.values.any { it.absolutePath == file.absolutePath }) continue

                    val songId = extractSongId(file.nameWithoutExtension)
                    if (songId != null && songId in existingSongIds) continue

                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.absolutePath)
                        val metaTitle = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                            ?.trim()?.takeIf { it.isNotBlank() }
                        val metaArtist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            ?.trim()?.takeIf { it.isNotBlank() }
                        val metaAlbum = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
                            ?.trim().orEmpty()

                        val (parsedArtist, parsedTitle) = parseArtistAndTitleFromFilename(file.nameWithoutExtension, songId)

                        val rawTitle = metaTitle ?: parsedTitle
                        val cleanTitle = cleanSongTitle(rawTitle, songId)
                        val title = cleanTitle.ifBlank { file.nameWithoutExtension }

                        var artist = metaArtist?.takeUnless { ArtistHelper.isPlayCountOrStat(it) }
                        if (artist.isNullOrBlank()) {
                            val parentName = file.parentFile?.name.orEmpty()
                            artist = parentName.takeUnless {
                                it.isBlank() || it.equals("Singles", ignoreCase = true) ||
                                    it.equals(dirName, ignoreCase = true) ||
                                    it.equals(LEGACY_PUBLIC_DIR_NAME, ignoreCase = true) ||
                                    it.equals("Music", ignoreCase = true) ||
                                    ArtistHelper.isPlayCountOrStat(it)
                            } ?: parsedArtist.takeUnless { ArtistHelper.isPlayCountOrStat(it) }
                            ?: "Unknown Artist"
                        }
                        artist = artist.trim().ifBlank { "Unknown Artist" }

                        val trackKey = makeDownloadKey(title, artist)
                        if (trackKey in existingKeys) continue
                        if (activeKeys.contains(trackKey)) continue

                        val album = metaAlbum.takeUnless { ArtistHelper.isPlayCountOrStat(it) }.orEmpty()
                        val durStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        val durMs = durStr?.toLongOrNull() ?: 0L
                        val bitRateStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        val bitrateKbps = bitRateStr?.toIntOrNull()?.let { it / 1000 }

                        val ext = file.extension.lowercase()
                        val isFlac = ext == "flac"
                        val badge = when (ext) {
                            "flac" -> "FLAC"
                            "m4a", "mp4", "aac" -> "M4A AAC"
                            "mp3" -> if (bitrateKbps != null && bitrateKbps >= 300) "320k MP3" else "MP3"
                            "opus", "ogg" -> "OPUS"
                            "wav" -> "WAV"
                            else -> "AUDIO"
                        }

                        // Extract embedded cover art or fall back to songId / local image
                        var artworkUrl: String? = null
                        val pictureBytes = retriever.embeddedPicture
                        if (pictureBytes != null && pictureBytes.isNotEmpty()) {
                            try {
                                val coversDir = File(context.filesDir, "download_covers").apply { mkdirs() }
                                val coverFile = File(coversDir, "${trackKey.hashCode().toUInt().toString(16)}.jpg")
                                if (!coverFile.exists() || coverFile.length() == 0L) {
                                    coverFile.writeBytes(pictureBytes)
                                }
                                artworkUrl = Uri.fromFile(coverFile).toString()
                            } catch (_: Exception) { }
                        }
                        if (artworkUrl == null && !songId.isNullOrBlank()) {
                            artworkUrl = "https://i.ytimg.com/vi/$songId/hqdefault.jpg"
                        }
                        if (artworkUrl == null) {
                            val companionCover = File(file.parentFile ?: musicDir, "${file.nameWithoutExtension}.jpg")
                                .takeIf { it.exists() && it.length() > 0 }
                                ?: File(file.parentFile ?: musicDir, "cover.jpg").takeIf { it.exists() && it.length() > 0 }
                            if (companionCover != null) {
                                artworkUrl = Uri.fromFile(companionCover).toString()
                            }
                        }

                        // Check companion .lrc lyrics file
                        val lrcFile = File(file.parentFile ?: musicDir, file.nameWithoutExtension + ".lrc")
                        val altLrcFile = File(file.parentFile ?: musicDir, sanitizeFilename("$artist - $title") + ".lrc")
                        val effectiveLrc = when {
                            lrcFile.exists() && lrcFile.length() > 0 -> lrcFile
                            altLrcFile.exists() && altLrcFile.length() > 0 -> altLrcFile
                            else -> null
                        }
                        val hasLyrics = effectiveLrc != null
                        val lrcText = effectiveLrc?.let { runCatching { it.readText() }.getOrNull() }

                        val entity = DownloadedTrackEntity(
                            trackKey = trackKey,
                            title = title,
                            artist = artist,
                            album = album,
                            artworkUrl = artworkUrl,
                            filePath = file.absolutePath,
                            fileSizeBytes = file.length(),
                            formatBadge = badge,
                            durationMs = durMs,
                            bitrateKbps = bitrateKbps,
                            isLossless = isFlac,
                            hasLyrics = hasLyrics,
                            syncedLyrics = if (lrcText?.contains("[") == true) lrcText else null,
                            plainLyrics = if (lrcText?.contains("[") != true) lrcText else null,
                            lrcFilePath = effectiveLrc?.absolutePath,
                            downloadedAtMillis = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
                        )
                        downloadedTrackDao.insert(entity)
                        existingPaths.add(file.absolutePath)
                        existingKeys.add(trackKey)
                        if (songId != null) existingSongIds.add(songId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (oom: OutOfMemoryError) {
                        android.util.Log.e("TrackDownloadManager", "Skipping file after OOM: ${file.name}", oom)
                    } catch (e: Exception) {
                        android.util.Log.e("TrackDownloadManager", "Failed to restore file: ${file.name}", e)
                    } finally {
                        runCatching { retriever.release() }
                    }
                }
            }

            // 2. Query MediaStore for audio items in the download folders (active + legacy)
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
                    MediaStore.Audio.Media.DISPLAY_NAME,
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
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                            if (uri.toString() in existingUris) continue
                            if (activeUris.values.any { it.toString() == uri.toString() }) continue

                            val displayName = cursor.getString(nameCol).orEmpty()
                            val songId = extractSongId(displayName.substringBeforeLast('.'))
                            if (songId != null && songId in existingSongIds) continue

                            val rawTitle = cursor.getString(titleCol) ?: displayName.substringBeforeLast('.').ifBlank { "Unknown Track" }
                            val rawArtist = cursor.getString(artistCol) ?: "Unknown Artist"
                            val rawAlbum = cursor.getString(albumCol).orEmpty()

                            val cleanTitle = cleanSongTitle(rawTitle, songId)
                            val title = cleanTitle.ifBlank { "Unknown Track" }
                            val artist = rawArtist.trim()
                                .takeUnless { ArtistHelper.isPlayCountOrStat(it) }
                                ?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
                            val trackKey = makeDownloadKey(title, artist)
                            if (trackKey in existingKeys) continue
                            if (activeKeys.contains(trackKey)) continue

                            val album = rawAlbum.trim().takeUnless { ArtistHelper.isPlayCountOrStat(it) }.orEmpty()
                            val durMs = cursor.getLong(durCol)
                            val size = cursor.getLong(sizeCol)
                            if (size < MIN_VALID_AUDIO_BYTES) continue

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

                            var artworkUrl: String? = null
                            val retriever = android.media.MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(context, uri)
                                val pictureBytes = retriever.embeddedPicture
                                if (pictureBytes != null && pictureBytes.isNotEmpty()) {
                                    val coversDir = File(context.filesDir, "download_covers").apply { mkdirs() }
                                    val coverFile = File(coversDir, "${trackKey.hashCode().toUInt().toString(16)}.jpg")
                                    if (!coverFile.exists() || coverFile.length() == 0L) {
                                        coverFile.writeBytes(pictureBytes)
                                    }
                                    artworkUrl = Uri.fromFile(coverFile).toString()
                                }
                            } catch (_: Exception) {
                            } finally {
                                runCatching { retriever.release() }
                            }
                            if (artworkUrl == null && !songId.isNullOrBlank()) {
                                artworkUrl = "https://i.ytimg.com/vi/$songId/hqdefault.jpg"
                            }

                            val entity = DownloadedTrackEntity(
                                trackKey = trackKey,
                                title = title,
                                artist = artist,
                                album = album,
                                artworkUrl = artworkUrl,
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
                            if (songId != null) existingSongIds.add(songId)
                        }
                    }
                }
            }

            // Only mark as successfully scanned when the complete routine finishes without uncaught error
            markDownloadsScanned()
            true
        }
    }

internal suspend fun TrackDownloadManager.syncDownloadsFromStorageImpl() = withContext(Dispatchers.IO) {
        runCatching { restoreDownloadsAfterReinstall() }
        runCatching { repairCorruptedDownloadMetadata() }
        val existingEntities = downloadedTrackDao.getAllList().toMutableList()
        // Storage scans can discover new downloads, but must also reconcile
        // rows whose files were removed outside LastWave (for example in a
        // file manager). Otherwise Room keeps showing a song that no longer
        // exists on disk forever.
        val missingTracks = existingEntities.filter { recordedAudioAvailability(it) == false }
        for (track in missingTracks) {
            // The audio is gone; drop its companion lyrics file too.
            runCatching { deleteFileIfRecorded(track.lrcFilePath) }
            forgetOfflineSidecar(track)
            downloadedTrackDao.delete(track)
        }
        existingEntities.removeAll(missingTracks.toSet())
        val existingPaths = existingEntities.map { it.filePath }.toMutableSet()
        val existingUris = existingEntities.mapNotNull { it.mediaStoreUri }.toMutableSet()
        val existingKeys = existingEntities.map { makeDownloadKey(it.title, it.artist) }.toMutableSet()
        // Songs removed with "History only" keep their file; don't re-import them.
        val hidden = hiddenHistory()
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
                if (file.absolutePath in hidden.refs) continue

                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val rawTitle = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.ifBlank { null } ?: file.nameWithoutExtension.substringAfter(" - ").ifBlank { file.nameWithoutExtension }
                    val rawArtist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?.ifBlank { null } ?: file.nameWithoutExtension.substringBefore(" - ").ifBlank { "Unknown Artist" }
                    val rawAlbum = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
                    // Never import counter strings as metadata; fall back to
                    // filename/folder rather than "15 ml listens".
                    val title = rawTitle.trim().takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
                    var artist = rawArtist.trim().takeUnless { ArtistHelper.isPlayCountOrStat(it) }
                    if (artist.isNullOrBlank()) {
                        val parentName = file.parentFile?.name.orEmpty()
                        artist = parentName.takeUnless {
                            it.isBlank() || it.equals("Singles", ignoreCase = true) ||
                                it.equals(dirName, ignoreCase = true) ||
                                it.equals("Music", ignoreCase = true) ||
                                ArtistHelper.isPlayCountOrStat(it)
                        } ?: file.nameWithoutExtension.substringBefore(" - ")
                            .takeUnless { ArtistHelper.isPlayCountOrStat(it) }
                        ?: "Unknown Artist"
                    }
                    artist = artist!!.trim().ifBlank { "Unknown Artist" }
                    val trackKey = makeDownloadKey(title, artist)
                    if (trackKey in existingKeys) continue
                    if (trackKey in hidden.keys) continue

                    val album = rawAlbum.trim().takeUnless { ArtistHelper.isPlayCountOrStat(it) }.orEmpty()
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
                        if (uri.toString() in hidden.refs) continue

                        val rawTitle = cursor.getString(titleCol) ?: "Unknown Track"
                        val rawArtist = cursor.getString(artistCol) ?: "Unknown Artist"
                        val rawAlbum = cursor.getString(albumCol).orEmpty()
                        val title = rawTitle.trim().takeIf { it.isNotBlank() } ?: "Unknown Track"
                        val artist = rawArtist.trim()
                            .takeUnless { ArtistHelper.isPlayCountOrStat(it) }
                            ?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
                        val trackKey = makeDownloadKey(title, artist)
                        if (trackKey in existingKeys) continue
                        if (trackKey in hidden.keys) continue

                        val album = rawAlbum.trim()
                            .takeUnless { ArtistHelper.isPlayCountOrStat(it) }.orEmpty()
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

    /**
     * Returns true if any recorded audio reference is still readable, false
     * only when every reference is confirmed missing, and null if access could
     * not be determined (for example a revoked SAF permission).
     */
internal fun TrackDownloadManager.recordedAudioAvailability(track: DownloadedTrackEntity): Boolean? {
        val references = listOfNotNull(
            track.mediaStoreUri?.takeIf { it.isNotBlank() },
            track.filePath.takeIf { it.isNotBlank() },
        ).distinct()
        if (references.isEmpty()) return false

        var accessUnknown = false
        for (reference in references) {
            val presence = if (reference.startsWith("content://")) {
                runCatching { contentUriPresence(Uri.parse(reference)) }.getOrNull()
            } else {
                filePresence(File(reference))
            }
            when (presence) {
                true -> return true
                null -> accessUnknown = true
                false -> Unit
            }
        }
        return if (accessUnknown) null else false
    }

    /** Like [contentUriPresence] for plain paths; null while the volume is unmounted or unreadable. */
internal fun TrackDownloadManager.filePresence(file: File): Boolean? = try {
        val volumeState = Environment.getExternalStorageState(file)
        val onUnmountedVolume = volumeState != Environment.MEDIA_UNKNOWN &&
            volumeState != Environment.MEDIA_MOUNTED &&
            volumeState != Environment.MEDIA_MOUNTED_READ_ONLY
        when {
            file.exists() -> true
            // An SD card or USB drive that isn't mounted yet looks like every
            // file on it was deleted; never prune on that.
            onUnmountedVolume -> null
            !hasStoragePermission() &&
                file.absolutePath.startsWith(Environment.getExternalStorageDirectory().absolutePath) -> null
            // A removed card can report MEDIA_UNKNOWN; its /storage/<id> root
            // is then missing or empty.
            storageVolumeRootMissing(file) -> null
            else -> false
        }
    } catch (_: SecurityException) {
        null
    }

internal fun TrackDownloadManager.storageVolumeRootMissing(file: File): Boolean {
        val segments = file.absolutePath.split('/').filter { it.isNotEmpty() }
        if (segments.size < 2 || segments[0] != "storage") return false
        // Primary storage (/storage/emulated, /storage/self) is always present.
        if (segments[1] == "emulated" || segments[1] == "self") return false
        val root = File("/storage/${segments[1]}")
        return !root.isDirectory || root.list().isNullOrEmpty()
    }

internal fun com.lastwave.app.data.music.YouTubeAudioStream.isOpusStream(): Boolean {
        val m = mimeType.orEmpty().lowercase()
        return m.contains("webm") || m.contains("ogg") ||
            codec.orEmpty().contains("opus", ignoreCase = true)
    }

internal fun TrackDownloadManager.sanitizeFilename(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "track" }
