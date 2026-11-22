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


internal fun TrackDownloadManager.showDownloadNotification(
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

internal fun TrackDownloadManager.showCompletedNotification(
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

internal fun TrackDownloadManager.showErrorNotification(
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

internal fun TrackDownloadManager.viewDownloadsPendingIntent(requestCode: Int): PendingIntent {
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

internal fun TrackDownloadManager.reconnectPendingIntent(
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

