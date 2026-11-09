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


    /**
     * Removes the row but keeps the file. The file is remembered so the next
     * storage scan doesn't import it straight back.
     */
internal suspend fun TrackDownloadManager.removeFromHistoryOnlyImpl(track: DownloadedTrackEntity) = withContext(Dispatchers.IO) {
        hideFromHistory(listOf(track))
        downloadedTrackDao.delete(track)
    }

internal suspend fun TrackDownloadManager.clearHistoryOnlyImpl() = withContext(Dispatchers.IO) {
        hideFromHistory(downloadedTrackDao.getAllList())
        downloadedTrackDao.clearAll()
    }

    /** One hidden download: its track key plus the file path / content URI it was stored at. */
internal fun TrackDownloadManager.hiddenHistory(): HiddenHistory {
        hiddenHistoryCache?.let { return it }
        val loaded = HiddenHistory(
            runCatching {
                context.getSharedPreferences(HIDDEN_PREFS_NAME, Context.MODE_PRIVATE)
                    .getStringSet(KEY_HIDDEN_REFS, emptySet())
                    .orEmpty()
                    .map(HiddenDownload::decode)
            }.getOrDefault(emptyList()),
        )
        hiddenHistoryCache = loaded
        return loaded
    }

internal fun TrackDownloadManager.saveHiddenHistory(entries: Collection<HiddenDownload>) {
        hiddenHistoryCache = HiddenHistory(entries.toList())
        runCatching {
            context.getSharedPreferences(HIDDEN_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_HIDDEN_REFS, entries.mapTo(mutableSetOf()) { it.encode() })
                .commit()
        }
    }

internal fun TrackDownloadManager.hideFromHistory(tracks: List<DownloadedTrackEntity>) {
        if (tracks.isEmpty()) return
        // Store the real file path too: the folder scan matches files by
        // path, and a MediaStore row only records its content:// URI.
        val hidden = tracks.map { track ->
            val realPath = track.mediaStoreUri?.let(::filePathForMediaStoreUri)
            HiddenDownload.of(track, listOfNotNull(realPath))
        }
        synchronized(hiddenRefsLock) {
            saveHiddenHistory(hiddenHistory().entries + hidden)
        }
    }

    /** True if [key] or the file at [path] was removed with "History only". */
internal fun TrackDownloadManager.isHiddenFromHistory(key: String, path: String? = null): Boolean {
        val hidden = hiddenHistory()
        return key in hidden.keys || (path != null && path in hidden.refs)
    }

    /** A fresh download of a hidden song should show up again. */
internal fun TrackDownloadManager.unhideFromHistory(track: DownloadedTrackEntity) {
        val refs = HiddenDownload.of(track).refs
        unhideWhere { it.key == track.trackKey || it.refs.any(refs::contains) }
    }

    /** Returns true if [key] or the file at [path] was hidden. */
internal fun TrackDownloadManager.unhideKey(key: String, path: String? = null): Boolean =
        unhideWhere { it.key == key || (path != null && path in it.refs) }

internal fun TrackDownloadManager.unhideWhere(match: (HiddenDownload) -> Boolean): Boolean =
        synchronized(hiddenRefsLock) {
            val entries = hiddenHistory().entries
            val kept = entries.filterNot(match)
            if (kept.size == entries.size) return false
            saveHiddenHistory(kept)
            true
        }

internal suspend fun TrackDownloadManager.deleteDownloadedTrackImpl(track: DownloadedTrackEntity): TrackDownloadManager.DeleteDownloadResult = withContext(Dispatchers.IO) {
        // Only remove the row after the recorded media has been deleted. This
        // keeps failed deletes retryable and avoids sync re-importing the song.
        if (deleteRecordedDownload(track)) {
            forgetOfflineSidecar(track)
            downloadedTrackDao.delete(track)
            return@withContext TrackDownloadManager.DeleteDownloadResult.Deleted
        }
        consentDeleteRequest(track)?.let { return@withContext TrackDownloadManager.DeleteDownloadResult.NeedsUserConsent(it) }
        TrackDownloadManager.DeleteDownloadResult.Failed
    }

    /** Call after the user approved the system delete prompt. */
internal suspend fun TrackDownloadManager.finishConsentedDeleteImpl(track: DownloadedTrackEntity): Boolean = withContext(Dispatchers.IO) {
        if (!deleteRecordedDownload(track)) return@withContext false
        forgetOfflineSidecar(track)
        downloadedTrackDao.delete(track)
        true
    }

    /** DRM module downloads keep a descriptor sidecar; drop it with the audio. */
internal fun TrackDownloadManager.forgetOfflineSidecar(track: DownloadedTrackEntity) {
        runCatching { moduleManager.deleteOfflineSidecar(track.title, track.artist) }
    }

    /**
     * Only used when the direct delete failed. Our own downloads delete without
     * a prompt; files we don't own (saved before a reinstall, by another build
     * such as release vs debug, or copied in by a file manager/PC) can't be
     * deleted directly on Android 11+, so MediaStore asks the user instead.
     * Android 10 and lower have no such prompt and the delete just fails.
     */
internal fun TrackDownloadManager.consentDeleteRequest(track: DownloadedTrackEntity): android.content.IntentSender? {
        return consentDeleteRequest(listOf(track))
    }

internal fun TrackDownloadManager.consentDeleteRequest(tracks: List<DownloadedTrackEntity>): android.content.IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val uris = tracks.flatMap { track ->
            listOfNotNull(track.mediaStoreUri, track.filePath)
                .filter { it.isNotBlank() }
                .distinct()
                .mapNotNull { ref ->
                    if (ref.startsWith("content://")) {
                        Uri.parse(ref).takeIf { it.authority == MediaStore.AUTHORITY }
                    } else {
                        ref.takeIf { File(it).exists() }?.let(::mediaStoreUriForPath)
                    }
                }
                .distinct()
                .filter { contentUriPresence(it) != false }
        }.distinct()
        if (uris.isEmpty()) return null
        return try {
            MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("TrackDownloadManager", "Couldn't build bulk download delete request", error)
            null
        }
    }

internal fun TrackDownloadManager.mediaStoreUriForPath(path: String): Uri? = try {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.MediaColumns.DATA} = ?",
            arrayOf(path),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
            } else {
                null
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

internal fun TrackDownloadManager.filePathForMediaStoreUri(uriString: String): String? = try {
        val uri = Uri.parse(uriString)
        if (uri.authority != MediaStore.AUTHORITY) {
            null
        } else {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?.takeIf { it.isNotBlank() }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /**
     * Cancels in-flight downloads, waits until those workers finish, then deletes
     * each persisted audio file, lyric file, and content URI before removing that
     * row. A missing file is success. Permission errors and failed deletes keep
     * the row so a later wipe can retry, and they fail this call.
     * New [downloadTrack] calls are refused until the guard is released.
     */
internal suspend fun TrackDownloadManager.clearAllDownloadsImpl() {
        val failed = clearAllDownloadsAttempt()
        if (failed.isNotEmpty()) {
            throw IOException("Failed to delete ${failed.size} downloaded track(s)")
        }
    }

    /**
     * Clears tracked downloads and, when Android blocks deleting legacy media,
     * returns one system confirmation request for the remaining MediaStore rows.
     */
internal suspend fun TrackDownloadManager.clearAllDownloadsWithConsentImpl(): TrackDownloadManager.ClearAllDownloadsResult {
        val failed = clearAllDownloadsAttempt()
        if (failed.isEmpty()) return TrackDownloadManager.ClearAllDownloadsResult.Cleared
        val request = consentDeleteRequest(failed)
            ?: return TrackDownloadManager.ClearAllDownloadsResult.Failed
        return TrackDownloadManager.ClearAllDownloadsResult.NeedsUserConsent(request, failed)
    }

    /** Finish deleting only the rows included in the user's system confirmation. */
internal suspend fun TrackDownloadManager.finishConsentedClearAllDownloadsImpl(tracks: List<DownloadedTrackEntity>): Boolean =
        withWipingDownloads {
            var allDeleted = true
            for (track in tracks) {
                currentCoroutineContext().ensureActive()
                if (deleteRecordedDownload(track)) {
                    forgetOfflineSidecar(track)
                    downloadedTrackDao.delete(track)
                } else {
                    allDeleted = false
                }
            }
            allDeleted
        }

internal suspend fun TrackDownloadManager.clearAllDownloadsAttempt(): List<DownloadedTrackEntity> = withWipingDownloads {
        val tracks = downloadedTrackDao.getAllList()
        val failed = ArrayList<DownloadedTrackEntity>(tracks.size)
        for (track in tracks) {
            currentCoroutineContext().ensureActive()
            if (deleteRecordedDownload(track)) {
                forgetOfflineSidecar(track)
                downloadedTrackDao.delete(track)
            } else {
                failed += track
            }
        }
        failed
    }

internal suspend fun <T> TrackDownloadManager.withWipingDownloads(block: suspend () -> T): T {
        val jobs = synchronized(admissionLock) {
            downloadsWiping = true
            activeJobs.values.toList()
        }
        // Ask workers to stop before the first suspension point so a caller
        // cancelled immediately after this still cannot race a new insert in.
        jobs.forEach { it.cancel() }
        try {
            return withContext(Dispatchers.IO) {
                // Existing cancellation also drops partial MediaStore rows and files.
                cancelAllDownloads()
                jobs.forEach { job ->
                    job.cancel()
                    job.join()
                }
                block()
            }
        } finally {
            synchronized(admissionLock) {
                downloadsWiping = false
            }
        }
    }

internal suspend fun TrackDownloadManager.ensureDownloadStillAllowed() {
        currentCoroutineContext().ensureActive()
        if (downloadsWiping) throw CancellationException("Downloads are being cleared")
    }

    /** Queued worker cancelled before it acquired a download slot. */
internal fun TrackDownloadManager.forgetDownloadKey(key: String) {
        activeKeys.remove(key)
        activeJobs.remove(key)
        reconnectGenerations.remove(key)
        _downloads.update { it - key }
    }

    /** Worker that already holds a slot must release it exactly once. */
internal fun TrackDownloadManager.abandonAcquiredDownload(key: String) {
        runCatching { downloadSlots.release() }
        activeUris.remove(key)?.let { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        activeFiles.remove(key)?.let { file ->
            runCatching { if (file.exists()) file.delete() }
        }
        forgetDownloadKey(key)
    }

internal fun TrackDownloadManager.deleteRecordedDownload(track: DownloadedTrackEntity): Boolean {
        val uriDeleted = deleteContentUriIfRecorded(track.mediaStoreUri)
        // MediaStore downloads record the same content:// URI in both fields.
        val audioDeleted = track.filePath == track.mediaStoreUri || deleteFileIfRecorded(track.filePath)
        val lyricsDeleted = deleteFileIfRecorded(track.lrcFilePath)
        if (!uriDeleted || !audioDeleted || !lyricsDeleted) {
            android.util.Log.w(
                "TrackDownloadManager",
                "Kept download row for ${track.title} by ${track.artist}; deletion can be retried",
            )
        }
        return uriDeleted && audioDeleted && lyricsDeleted
    }

internal fun TrackDownloadManager.deleteFileIfRecorded(path: String?): Boolean {
        if (path.isNullOrBlank()) return true
        if (path.startsWith("content://")) return deleteContentUriIfRecorded(path)
        val file = File(path)
        if (!file.exists()) return true
        return try {
            file.delete() || !file.exists()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (security: SecurityException) {
            android.util.Log.w("TrackDownloadManager", "Couldn't delete download file $path", security)
            false
        }
    }

internal fun TrackDownloadManager.deleteContentUriIfRecorded(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return true
        val uri = try {
            Uri.parse(uriString)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        try {
            if (context.contentResolver.delete(uri, null, null) > 0) return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: FileNotFoundException) {
            return true
        } catch (security: SecurityException) {
            // MediaStore also throws this for ids that no longer exist, so
            // only treat it as a failure if the item is still there.
            if (!contentUriStillPresent(uri)) return true
            android.util.Log.w("TrackDownloadManager", "Couldn't delete download uri $uriString", security)
            return false
        } catch (error: Exception) {
            android.util.Log.w("TrackDownloadManager", "Download uri delete failed for $uriString", error)
        }
        return !contentUriStillPresent(uri)
    }

    /** True unless the URI is confirmed gone, so the row stays retryable. */
internal fun TrackDownloadManager.contentUriStillPresent(uri: Uri): Boolean = contentUriPresence(uri) != false

    /**
     * Whether a content URI still points at audio: true if present, false if
     * confirmed gone, null if it can't be told (no permission, provider error).
     */
internal fun TrackDownloadManager.contentUriPresence(uri: Uri): Boolean? = try {
        if (uri.authority == MediaStore.AUTHORITY) {
            // Opening a deleted MediaStore id throws SecurityException, so ask
            // the index directly. Without audio permission other apps' items
            // are invisible, so an empty result proves nothing.
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { cursor ->
                    when {
                        cursor.count > 0 -> true
                        hasStoragePermission() -> false
                        else -> null
                    }
                }
        } else {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: FileNotFoundException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: SecurityException) {
        null
    } catch (_: Exception) {
        null
    }

