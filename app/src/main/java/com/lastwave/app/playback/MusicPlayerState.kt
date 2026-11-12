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

/** What kind of media a queue entry is. Songs keep the scrobble / radio /
 *  lyrics behaviour; podcast episodes are excluded from all of it and get
 *  resume progress instead. */
@Serializable
enum class MediaType {
    SONG,
    PODCAST_EPISODE,
}

@Serializable
@androidx.compose.runtime.Immutable
data class PlayableTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val videoId: String? = null,
    /** Stable library id when one exists. Never a title or artist string. */
    val internalTrackId: String? = null,
    val playbackUrl: String? = null,
    val playbackMimeType: String? = null,
    val durationMs: Long? = null,
    val mediaType: MediaType = MediaType.SONG,
    /** Podcast show id (MPSPPLâ€¦) when this entry is a podcast episode. */
    val showId: String? = null,
    val trackNumber: Int? = null,
)

@Serializable
internal data class PersistedPlaybackSession(
    val version: Int = 2,
    val queue: List<PlayableTrack>,
    val currentIndex: Int,
    val positionMs: Long,
    val sourceLabel: String = "LastWave",
    val isEndlessQueue: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val durationMs: Long = 0L,
    /** Exact shuffle play order (queue indices), so a restart never reshuffles. */
    val shuffleOrder: List<Int> = emptyList(),
)

@androidx.compose.runtime.Immutable
data class MusicPlayerState(
    val connected: Boolean = true,
    val current: PlayableTrack? = null,
    val queue: List<PlayableTrack> = emptyList(),
    val currentIndex: Int = -1,
    val sourceLabel: String = "LastWave",
    val isEndlessQueue: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val bufferedPositionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val bitrateKbps: Int? = null,
    val audioCodec: String? = null,
    val isLossless: Boolean = false,
    val bitDepth: Int? = null,
    val samplingRateKHz: Double? = null,
    val sleepTimerRemainingMs: Long? = null,
    val error: String? = null,
    val isJamActive: Boolean = false,
    val deckBlendTelemetry: com.lastwave.app.playback.automix.api.DeckBlendTelemetry = com.lastwave.app.playback.automix.api.DeckBlendTelemetry.IDLE,
    val isCrossfading: Boolean = false,
    /** Set while a better or lighter stream of the playing song is being lined up. */
    val qualitySwitch: QualitySwitch? = null,
    val appVolume: Float = 1f,
    val isCasting: Boolean = false,
)


class QualitySwitch(val upgrade: Boolean, val title: String)

/**
 * Playback fields used by list rows and collapsed player chrome. Unlike
 * [MusicPlayerState], this does not contain the 60 ms position ticker, so a
 * playing track no longer invalidates every visible track list 16 times/sec.
 */
@androidx.compose.runtime.Immutable
data class PlaybackChromeState(
    val current: PlayableTrack? = null,
    val sourceLabel: String = "LastWave",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val queueSize: Int = 0,
    val shuffleEnabled: Boolean = false,
    val deckBlendTelemetry: com.lastwave.app.playback.automix.api.DeckBlendTelemetry = com.lastwave.app.playback.automix.api.DeckBlendTelemetry.IDLE,
    val isCrossfading: Boolean = false,
)

/**
 * The only playback fields browse screens (Home, Statistics, Playlists,
 * album/artist pages, search) read to mark the playing row. Unlike
 * [PlaybackChromeState] it leaves out automix telemetry (blend progress is
 * rewritten every few ms during a mix), buffering and queue size, so those
 * no longer recompose every visible list while the user scrolls.
 */
@androidx.compose.runtime.Immutable
data class ListPlaybackState(
    val current: PlayableTrack? = null,
    val sourceLabel: String = "LastWave",
    val isPlaying: Boolean = false,
    val shuffleEnabled: Boolean = false,
)

/**
 * Copy without the fields the progress ticker rewrites every ~60ms. The
 * sleep-timer countdown rides on the same ticker; it is floored to whole
 * seconds (what every timer label shows) so an armed timer no longer
 * recomposes the full player and lyrics 16 times a second.
 */
internal fun MusicPlayerState.withoutProgress(): MusicPlayerState {
    val sleep = sleepTimerRemainingMs?.let { (it / 1000L) * 1000L }
    return if (positionMs == 0L && bufferedPositionMs == 0L && sleep == sleepTimerRemainingMs) this
    else copy(positionMs = 0L, bufferedPositionMs = 0L, sleepTimerRemainingMs = sleep)
}

// Keep fields owned elsewhere (automix, crossfade, quality switch, jam,
// volume, cast) when rebuilding the whole state. Use inside _state.update so
// they come from the latest value. Without qualitySwitch here the next
// refresh() erased the "Upgrading to…" pill right after it was set.
internal fun MusicPlayerState.withSubsystemStateFrom(live: MusicPlayerState): MusicPlayerState =
    copy(
        deckBlendTelemetry = live.deckBlendTelemetry,
        isCrossfading = live.isCrossfading,
        qualitySwitch = live.qualitySwitch,
        isJamActive = live.isJamActive,
        appVolume = live.appVolume,
        isCasting = live.isCasting,
    )

/** The small, frequently changing state consumed only by progress UI. */
@androidx.compose.runtime.Immutable
data class PlaybackProgressState(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)



internal const val EXCLUSIVE_DRIFT_ABORT_PPM = 20_000.0
internal const val EXCLUSIVE_DRIFT_ABORT_TICKS = 2
internal const val YOUTUBE_PROMOTE_BUDGET_MS = 12_000L
internal const val MISSING_ARTIST_METADATA_TIMEOUT_MS = 1_200L
internal const val YT_RESOLVE_TOTAL_TIMEOUT_MS = 30_000L
internal const val RECOVERY_RESOLVE_TIMEOUT_MS = 35_000L
internal const val SEAMLESS_URL_REFRESH_TIMEOUT_MS = 12_000L
internal const val MAX_REFRESHED_URL_ENTRIES = 64
internal const val DISCOVER_QUEUE_BATCH_SIZE = 16
internal const val DISCOVER_QUEUE_REFILL_THRESHOLD = 8
internal const val RADIO_QUEUE_BATCH_SIZE = 35
internal const val RADIO_QUEUE_REFILL_THRESHOLD = 14
internal const val POSITION_PERSIST_INTERVAL_MS = 5_000L
internal const val MAX_PERSISTED_QUEUE_SIZE = 200
internal const val RESTORED_PREVIOUS_TRACKS = 50
internal const val PLAYBACK_PREFERENCES_NAME = "lastwave_playback_session"
internal const val PLAYBACK_SESSION_KEY = "active_session"
internal const val KEY_VOLUME_MANAGED = "bitperfect_volume_managed"
internal const val KEY_VOLUME_SAVED = "bitperfect_volume_saved"
internal const val TICKER_PERSIST_INTERVAL_MS = 2_000L
internal const val BACKGROUND_TICKER_CADENCE_MS = 250L
internal const val APP_VOLUME_PERSIST_DEBOUNCE_MS = 400L
internal const val PODCAST_PROGRESS_INTERVAL_MS = 10_000L
internal const val PODCAST_FINISHED_RATIO = 0.95
internal const val SEEK_SETTLE_WINDOW_MS = 400L
internal const val SIGNAL_PATH_TICK_MS = 1_000L
internal const val MAX_PLAYBACK_RETRIES = 3
internal const val LOSSLESS_STARTUP_WAIT_MS = 8_000L
internal const val LOSSLESS_BACKGROUND_BUDGET_MS = 30_000L
internal const val HOT_SWAP_PROBE_SPEED = 4f
internal const val HOT_SWAP_PROBE_TIMEOUT_MS = 8_000L


typealias ResolvedStream = MusicPlayer.ResolvedStream

internal const val CROSSFADE_ARM_LEAD_MS = 10_000L
internal const val CROSSFADE_PREP_REFRESH_LEAD_MS = 30_000L
internal const val CROSSFADE_LOG_THROTTLE_MS = 5_000L
internal const val CROSSFADE_RESOLVE_RETRY_MS = 8_000L
internal val PERMANENT_PLAYBACK_ERROR_CODES = setOf(
    androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
    androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
)

internal fun selectExclusiveRateFallback(sourceHz: Int?, supportedHz: List<Int>): Int? =
    MusicPlayer.selectExclusiveRateFallback(sourceHz, supportedHz)
