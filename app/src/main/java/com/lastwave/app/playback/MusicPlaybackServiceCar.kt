package com.lastwave.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState as PlatformPlaybackState
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.media.MediaBrowserServiceCompat
import androidx.media.VolumeProviderCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.lastwave.app.MainShellActivity
import com.lastwave.app.R
import com.lastwave.app.data.local.ScrobblerPreferences
import com.lastwave.app.data.local.ScrobblerSettings
import com.lastwave.app.data.playlist.LIKED_SONGS_MODE
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.data.playlist.TrackLikeController
import com.lastwave.app.data.repository.ScrobbleRepository
import com.lastwave.app.data.repository.ThemeRepository
import com.lastwave.app.playback.resolve.MetadataLog

import com.lastwave.app.widget.ActiveMediaSessionHolder
import com.lastwave.app.widget.WidgetUpdater
import com.lastwave.app.widget.wave.WavePlaylistShortcut
import com.lastwave.app.widget.wave.WaveWidgetActions
import com.lastwave.app.widget.wave.WaveWidgetActionReceiver
import com.lastwave.app.widget.wave.WaveWidgetUpdater
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs


import android.app.Service

internal fun MusicPlaybackService.setCurrentTrackLiked(liked: Boolean) {
        val track = musicPlayer.state.value.current ?: return
        if (trackLikeController.isLiked(track) == liked) return
        scope.launch {
            runCatching { trackLikeController.toggle(track) }
                .onFailure { error -> android.util.Log.w("MusicPlaybackService", "Music card like failed", error) }
        }
    }

internal fun MusicPlaybackService.setOriginOsLoopMode(loopMode: Int) {
        val (shuffleEnabled, repeatMode) = OriginOsMusicCard.playModesOf(loopMode) ?: return
        musicPlayer.setShuffleEnabled(shuffleEnabled)
        musicPlayer.setRepeatMode(repeatMode)
    }

internal fun MusicPlaybackService.notifyChildrenChangedSafely(parentId: String) {
        runCatching { notifyChildrenChanged(parentId) }
    }


internal fun MusicPlaybackService.isAllowedMediaClient(clientPackageName: String, clientUid: Int): Boolean = runCatching {
        // System_server sometimes proxies browse connections (notably some
        // Android Auto ROMs); always allow it.
        if (clientUid == android.os.Process.SYSTEM_UID || clientUid == android.os.Process.myUid()) return@runCatching true
        // Android Auto projection & automotive packages
        if (clientPackageName == "com.google.android.projection.gearhead" ||
            clientPackageName == "com.google.android.carprojection" ||
            clientPackageName == "com.google.android.apps.auto.repl" ||
            clientPackageName == "com.google.android.googlequicksearchbox"
        ) return@runCatching true
        val packages = packageManager.getPackagesForUid(clientUid)
        if (packages.isNullOrEmpty()) return@runCatching true
        packages.contains(clientPackageName)
    }.getOrDefault(true)

    /**
     * OriginOS music card / Origin Island lyrics: once per track, fetch the
     * synced lyrics (same cached repository the lyrics screen uses) and hand
     * them over as whole-song LRC through the session extras, resending
     * while the track stays current. The media id matches the metadata one.
     */
internal fun MusicPlaybackService.publishOriginOsLyrics(state: MusicPlayerState) {
        if (!OriginOsMusicCard.isSupportedDevice()) return
        val track = state.current
        val mediaId = track?.let { OriginOsMusicCard.mediaIdOf(state.currentIndex, it) }
        if (mediaId == originLyricsMediaId) return
        originLyricsMediaId = mediaId
        originLyricsJob?.cancel()
        if (track == null || mediaId == null) return
        originLyricsJob = scope.launch {
            val result = runCatching {
                lyricsRepository.getLyrics(
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationSeconds = (state.durationMs.takeIf { it > 0 } ?: track.durationMs)
                        ?.takeIf { it > 0 }?.let { (it / 1000).toInt() },
                    wordByWord = false,
                    videoId = track.videoId,
                    localLrcPath = com.lastwave.app.data.generate.LocalAudioFormats
                        .importedPathOrNull(track.playbackUrl)
                        ?.let(com.lastwave.app.data.generate.LocalAudioFormats::lrcPathFor),
                )
            }.getOrNull() as? com.lastwave.app.data.lyrics.LyricsResult.Success ?: return@launch
            if (!result.isSynced) return@launch
            val lrc = OriginOsMusicCard.lrcOf(result.lines) ?: return@launch
            // Never setExtras with an empty bundle: that wipes what the card has.
            val extras = OriginOsMusicCard.lyricsExtras(mediaId, lrc)
            while (isActive) {
                runCatching { mediaSession?.setExtras(extras) }
                delay(OriginOsMusicCard.LYRICS_RESEND_INTERVAL_MS)
            }
        }
    }

internal fun MusicPlaybackService.playCarMediaId(mediaId: String) {
        scope.launch {
            val played = runCatching {
                withContext(Dispatchers.IO) { androidAutoLibrary.playMediaId(mediaId, musicPlayer) }
            }.onFailure { error ->
                android.util.Log.w("MusicPlaybackService", "Android Auto playback request failed", error)
            }.getOrDefault(false)
            if (!played) publishCarPlaybackError("That item is no longer available")
        }
    }

internal fun MusicPlaybackService.playCarSearch(query: String) {
        scope.launch {
            val played = runCatching {
                withContext(Dispatchers.IO) {
                    if (query.isBlank()) {
                        // "Hey Google, play music on LastWave": no title given.
                        androidAutoLibrary.playAnything(musicPlayer)
                    } else {
                        androidAutoLibrary.playSearch(query, musicPlayer)
                    }
                }
            }.onFailure { error ->
                android.util.Log.w("MusicPlaybackService", "Voice search failed", error)
            }.getOrDefault(false)
            if (!played) {
                publishCarPlaybackError("No playable tracks found")
                demoteIfIdle()
            }
        }
    }

    /** Drops the foreground notification promoted for a request that played nothing. */
internal fun MusicPlaybackService.demoteIfIdle() {
        val state = musicPlayer.state.value
        if (state.current != null || state.isPlaying || state.isBuffering || !isPlaybackForeground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(Service.STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        isPlaybackForeground = false
    }

internal fun MusicPlaybackService.resolveCarSearchQuery(query: String?, extras: Bundle?): String {
        query?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
        return listOfNotNull(
            extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
            extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
            extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
        ).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
    }

internal fun MusicPlaybackService.publishCarPlaybackError(message: String) {
        systemStateSignature = ""
        runCatching {
            mediaSession?.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_ERROR, musicPlayer.state.value.positionMs, 0f)
                    .setErrorMessage(message)
                    .build(),
            )
        }
    }

internal fun MusicPlaybackService.publishCarBrowseState(state: MusicPlayerState) {
        val signature = state.queue.joinToString(separator = "|") {
            "${it.videoId.orEmpty()}:${it.title}:${it.artist}"
        }
        if (signature == carBrowseQueueSignature) return
        carBrowseQueueSignature = signature
        notifyCarLibraryChanged(AndroidAutoMediaLibrary.QUEUE_ID)
        notifyChildrenChangedSafely(OriginOsMusicCard.LIST_CURRENT)
    }

internal fun MusicPlaybackService.notifyCarLibraryChanged(parentId: String) {
        runCatching {
            notifyChildrenChanged(AndroidAutoMediaLibrary.ROOT_ID)
            notifyChildrenChanged(parentId)
        }
    }
