package com.lastwave.app.playback

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.lastwave.app.data.local.musicHapticsConfig
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.data.lyrics.LyricsResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import android.media.MediaCodecList
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.ts.AdtsExtractor
import com.lastwave.app.R
import com.lastwave.app.data.download.Fmp4IndexBuilder
import com.lastwave.app.data.plugin.SegmentedMpdBuilder
import coil.imageLoader
import coil.request.ImageRequest
import com.lastwave.app.data.discover.DiscoverRepository
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.CacheStoragePolicy
import com.lastwave.app.data.local.AdaptiveCacheEvictor
import com.lastwave.app.data.local.EqualizerPreferences
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.ConfirmedUnplayableMediaException
import com.lastwave.app.data.music.YouTubeAudioStream
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YOUTUBE_WEB_USER_AGENT
import com.lastwave.app.data.lossless.LosslessAudioStream
import com.lastwave.app.data.lossless.LosslessMusicApi
import com.lastwave.app.data.network.NetworkMonitor
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.plugin.ModuleDrmFactory
import com.lastwave.app.data.plugin.SegmentedDashBridge
import com.lastwave.app.data.plugin.stableCacheKey
import com.lastwave.app.data.recommend.RecommendationRepository
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import com.lastwave.app.playback.resolve.MetadataLog
import com.lastwave.app.playback.resolve.PlaybackStartupLog
import com.lastwave.app.playback.resolve.StreamCache
import com.lastwave.app.playback.resolve.StreamResolveCoordinator
import com.lastwave.app.playback.resolve.TrackIdentity
import com.lastwave.app.playback.resolve.playbackIdentity
import com.lastwave.app.playback.resolve.toYouTubeAudioStream
import com.lastwave.app.playback.usb.UsbExclusivePrefs
import com.lastwave.app.widget.WidgetUpdater
import kotlinx.coroutines.flow.first
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.isActive
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random


internal fun MusicPlayer.initializeCastImpl(): Boolean = runCatching {
        if (castPlayback == null) {
            val castContext = com.google.android.gms.cast.framework.CastContext.getSharedInstance(appContext)
            castPlayback = com.lastwave.app.playback.cast.CastPlayback(appContext, this, applicationScope, castContext)
            castPlayback?.initialize()
        }
        true
    }.getOrElse {
        android.util.Log.w("MusicPlayer", "Google Cast unavailable", it)
        false
    }

internal fun MusicPlayer.prepareForCast(): MusicPlayerState {
        val snapshot = _state.value
        pendingRestoredSession = null
        cancelPendingPlaybackResolution()
        queueEnrichmentJob?.cancel()
        discoverQueueLoadJob?.cancel()
        radioQueueLoadJob?.cancel()
        cancelCrossfade()
        if (playerDelegate.isInitialized()) {
            player.stop()
            player.clearMediaItems()
        }
        if (snapshot.current != null) ensureForegroundService()
        musicHapticsController.setCastingOrAutoActive(true)
        // While casting, the volume UI shows/controls the cast device.
        _state.update { it.copy(isCasting = true, appVolume = castVolume) }
        return snapshot
    }

internal suspend fun MusicPlayer.resolveCastStream(track: PlayableTrack): ResolvedStream =
        track.playbackUrl?.let { url ->
            val mime = track.playbackMimeType ?: withContext(Dispatchers.IO) {
                if (url.startsWith("content://")) appContext.contentResolver.getType(Uri.parse(url)) else null
            } ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                url.substringBefore('?').substringAfterLast('.').lowercase(),
            ) ?: "audio/mpeg"
            ResolvedStream(url, mime, null, null, url)
        } ?: resolveTrackAudioStreamWithRetry(track, track.videoId, allowLossless = true)

internal fun MusicPlayer.castBuffering(playing: Boolean) {
        _state.update { it.copy(isCasting = true, isPlaying = playing, isBuffering = true, error = null) }
    }

internal fun MusicPlayer.castError(message: String) {
        _state.update { it.copy(isPlaying = false, isBuffering = false, error = message) }
    }

internal fun MusicPlayer.updateCastState(playing: Boolean, buffering: Boolean, position: Long, duration: Long, speed: Float) {
        val validPos = position.coerceAtLeast(0)
        val validDur = duration.coerceAtLeast(0)
        playheadPosMs = validPos
        playheadWallMs = SystemClock.elapsedRealtime()
        _state.update { it.copy(
            isCasting = true,
            isPlaying = playing,
            isBuffering = buffering,
            positionMs = validPos,
            durationMs = if (validDur > 0) validDur else it.durationMs,
            speed = speed.takeIf { rate -> rate in 0.5f..2f } ?: it.speed
        ) }
        persistPlaybackSession()
    }

    /**
     * Cast device volume reported by the receiver. Kept separate from the
     * local in-app volume: it used to overwrite and PERSIST [appVolume], so
     * after one cast session (receivers often sit at 30-50%) local playback
     * stayed attenuated and the phone's max volume was no longer loud.
     */
internal fun MusicPlayer.onCastVolumeChanged(volume: Float) = onMain {
        val clamped = volume.coerceIn(0f, 1f)
        castVolume = clamped
        if (isCasting) _state.update { it.copy(appVolume = clamped) }
    }

internal fun MusicPlayer.castTrackEnded() {
        if (_state.value.repeatMode == Player.REPEAT_MODE_ONE) {
            castPlayback?.load(_state.value.copy(positionMs = 0))
        } else {
            next()
        }
    }

internal fun MusicPlayer.finishCasting() {
        musicHapticsController.setCastingOrAutoActive(false)
        // Back to local output: show/use the local in-app volume again.
        _state.update { it.copy(isCasting = false, isPlaying = false, isBuffering = false, appVolume = appVolume) }
        persistPlaybackSession()
    }
