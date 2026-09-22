package com.lastwave.app.playback

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
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
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import coil.imageLoader
import coil.request.ImageRequest
import com.lastwave.app.data.discover.DiscoverRepository
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.EqualizerPreferences
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.ConfirmedUnplayableMediaException
import com.lastwave.app.data.music.YouTubeAudioStream
import com.lastwave.app.data.music.YOUTUBE_WEB_USER_AGENT
import com.lastwave.app.data.lossless.LosslessAudioStream
import com.lastwave.app.data.lossless.LosslessMusicApi
import com.lastwave.app.data.plugin.ModuleDrmFactory
import com.lastwave.app.data.plugin.SegmentedDashBridge
import com.lastwave.app.data.plugin.stableCacheKey
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

@Serializable
data class PlayableTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val videoId: String? = null,
    val playbackUrl: String? = null,
    val playbackMimeType: String? = null,
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
)

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
    val bitrateKbps: Int? = null,
    val audioCodec: String? = null,
    val isLossless: Boolean = false,
    val bitDepth: Int? = null,
    val samplingRateKHz: Double? = null,
    val sleepTimerRemainingMs: Long? = null,
    val error: String? = null,
)

/**
 * Playback fields used by list rows and collapsed player chrome. Unlike
 * [MusicPlayerState], this does not contain the 60 ms position ticker, so a
 * playing track no longer invalidates every visible track list 16 times/sec.
 */
data class PlaybackChromeState(
    val current: PlayableTrack? = null,
    val sourceLabel: String = "LastWave",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val queueSize: Int = 0,
)

/** The small, frequently changing state consumed only by progress UI. */
data class PlaybackProgressState(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/**
 * Process-wide native ExoPlayer engine. A foreground service publishes its
 * platform MediaSession/notification while this object owns the actual
 * queue, ensuring the app UI and system controls always operate on the same
 * player instance.
 */
@OptIn(UnstableApi::class)
@Singleton
class MusicPlayer @Inject constructor(
    @ApplicationContext context: Context,
    private val innerTube: InnerTubeMusicApi,
    private val losslessMusicApi: LosslessMusicApi,
    private val moduleDrmFactory: ModuleDrmFactory,
    private val moduleManager: com.lastwave.app.data.plugin.ModuleManager,
    private val offlineLicense: com.lastwave.app.data.plugin.ModuleOfflineLicense,
    private val segBridge: SegmentedDashBridge,
    private val settingsPreferences: SettingsPreferences,
    private val equalizerPreferences: EqualizerPreferences,
    private val discoverRepository: DiscoverRepository,
    private val nativeAudioEngine: dagger.Lazy<NativeAudioEngine>,
    private val audioEffectsEngine: AudioEffectsEngine,
    private val applicationScope: CoroutineScope,
    private val downloadedTrackDao: dagger.Lazy<com.lastwave.app.data.local.db.DownloadedTrackDao>,
    private val usbDacMonitor: UsbDacMonitor,
    private val exclusiveUsbOutput: ExclusiveUsbOutput,
    private val songPlayStatsRepository: dagger.Lazy<com.lastwave.app.data.repository.SongPlayStatsRepository>,
) {
    private val appContext = context.applicationContext
    private val streamResolutionWakeLock by lazy {
        (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LastWave:StreamResolutionWakeLock",
        )?.apply { setReferenceCounted(false) }
    }
    private var castPlayback: com.lastwave.app.playback.cast.CastPlayback? = null
    private val isCasting: Boolean get() = castPlayback?.active == true

    fun initializeCast(): Boolean = runCatching {
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

    internal fun prepareForCast(): MusicPlayerState {
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
        return snapshot
    }

    internal suspend fun resolveCastStream(track: PlayableTrack): ResolvedStream =
        track.playbackUrl?.let { url ->
            val mime = track.playbackMimeType ?: withContext(Dispatchers.IO) {
                if (url.startsWith("content://")) appContext.contentResolver.getType(Uri.parse(url)) else null
            } ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                url.substringBefore('?').substringAfterLast('.').lowercase(),
            ) ?: "audio/mpeg"
            ResolvedStream(url, mime, null, null, url)
        } ?: resolveTrackAudioStreamWithRetry(track, track.videoId, allowLossless = true)

    internal fun castBuffering(playing: Boolean) {
        _state.update { it.copy(isPlaying = playing, isBuffering = true, error = null) }
    }

    internal fun castError(message: String) {
        _state.update { it.copy(isPlaying = false, isBuffering = false, error = message) }
    }

    internal fun updateCastState(playing: Boolean, buffering: Boolean, position: Long, duration: Long, speed: Float) {
        _state.update { it.copy(isPlaying = playing, isBuffering = buffering,
            positionMs = position.coerceAtLeast(0), durationMs = duration.coerceAtLeast(0),
            speed = speed.takeIf { rate -> rate in 0.5f..2f } ?: it.speed) }
        persistPlaybackSession()
    }

    internal fun castTrackEnded() {
        if (_state.value.repeatMode == Player.REPEAT_MODE_ONE) {
            castPlayback?.load(_state.value.copy(positionMs = 0))
        } else {
            next()
        }
    }

    internal fun finishCasting() {
        _state.update { it.copy(isPlaying = false, isBuffering = false) }
        persistPlaybackSession()
    }
    private val playbackPreferences = appContext.getSharedPreferences(
        PLAYBACK_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val persistenceJson = Json { ignoreUnknownKeys = true }
    private var lastPersistedSignature = ""
    private var playbackPersistenceJob: Job? = null
    private var pendingRestoredSession: PersistedPlaybackSession? = null
    @Volatile private var persistenceGeneration = 0L
    private val playbackPersistenceLock = Any()
    private var ticker: Job? = null
    private var playRequest: Job? = null
    private val playRequestGeneration = AtomicLong()
    private var queueEnrichmentJob: Job? = null
    private var preloadJob: Job? = null
    private var currentTrackCacheJob: Job? = null
    private val resolutionRequests = ConcurrentHashMap<List<Any?>, Pair<Long, Deferred<ResolvedStream>>>()
    private var discoverQueueLoadJob: Job? = null
    private var discoverQueueActive = false
    private var radioQueueLoadJob: Job? = null
    private var radioQueueActive = false
    private val radioUsedSeeds = ConcurrentHashMap.newKeySet<String>()
    private var unavailableSkipJob: Job? = null
    private val unavailableMediaIds = mutableSetOf<String>()
    /**
     * Explicit listening history (mediaIdKeys, oldest first) so Previous
     * under shuffle returns the song actually heard. ExoPlayer's internal
     * shuffle permutation is rebuilt on toggle, crossfade handoff and queue
     * edits, so previousMediaItemIndex rarely points at the last-heard song.
     * Keys (not indices) survive queue insertions/removals; stale keys are
     * skipped on pop. Main-thread only.
     */
    private val playHistory = ArrayDeque<String>()
    /** Guards the near-end late-preload so it fires once per upcoming item. */
    private var latePreloadKey: String? = null
    /**
     * Debounce for the natural-end auto-advance safety net: ExoPlayer can
     * emit STATE_ENDED repeatedly (plus the ticker watchdog) for the same
     * stuck item. Without this, handleNaturalTrackEnd would re-launch
     * resolveAndPlayQueueItem every 60ms, churning generations.
     */
    private var lastAutoAdvanceKey: String? = null
    private var lastAutoAdvanceAtMs = 0L
    private var sleepTimerDeadlineMs: Long? = null
    private var sleepTimerStep = 0
    @Volatile
    private var crossfadeEnabled = false
    @Volatile
    private var crossfadeDurationMs = 5_000L
    private var activePlayer: ExoPlayer? = null
    private var secondaryPlayer: ExoPlayer? = null
    private var secondaryNativeEngine: NativeAudioEngine? = null
    private var secondaryEffects: AudioEffectsEngine? = null
    private var outgoingPlayer: ExoPlayer? = null
    private var overlapDurationMs = 0L
    private var standbyQueue: List<MediaItem> = emptyList()
    private var standbyIndex = C.INDEX_UNSET
    private val _state = MutableStateFlow(MusicPlayerState())
    val state: StateFlow<MusicPlayerState> = _state.asStateFlow()
    val chromeState: StateFlow<PlaybackChromeState> = state
        .map {
            PlaybackChromeState(
                current = it.current,
                sourceLabel = it.sourceLabel,
                isPlaying = it.isPlaying,
                isBuffering = it.isBuffering,
                queueSize = it.queue.size,
            )
        }
        .distinctUntilChanged()
        .stateIn(applicationScope, SharingStarted.Eagerly, PlaybackChromeState())
    val progressState: StateFlow<PlaybackProgressState> = state
        .map { PlaybackProgressState(positionMs = it.positionMs, durationMs = it.durationMs) }
        .distinctUntilChanged()
        .stateIn(applicationScope, SharingStarted.Eagerly, PlaybackProgressState())

    private var errorRetryCount = 0
    private var retryMediaId: String? = null
    private val losslessBypassMediaIds = ConcurrentHashMap.newKeySet<String>()
    // Written by the settings collector, read on the main thread.
    @Volatile
    private var bitPerfectEnabled = false
    /**
     * Whether the native layer actually honors the current Bit-Perfect
     * request (read back after every [updateBitPerfectState], not just the
     * DataStore wish). The verdict ANDs this with the sinks' direct-path
     * state so the badge can never claim Bit-Perfect on a pref alone.
     */
    @Volatile
    private var nativeBitPerfectApplied = false
    /** Previous system level saved when DAC mode auto-maxed it; -1 = untouched. */
    private var savedSystemVolume = -1
    private var dacVolumeManaged = false
    private val audioManager: AudioManager? by lazy {
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    /** Every ExoPlayer audio sink, so DAC routing/rate follow player rebuilds. */
    private val audioSinks = CopyOnWriteArrayList<NativeProcessingAudioSink>()
    /** True while the usbdevfs exclusive session owns output. */
    @Volatile private var usbExclusiveSinkActive = false
    @Volatile private var usbExclusivePrefEnabled = false
    /** AudioDeviceInfo id currently requested via setPreferredDevice, if any. */
    private var routedDacDeviceId: Int? = null
    private val healthTracker = StreamHealthTracker()
    private var lastHealthTrackKey: String? = null
    private var lastSignalPathMs = 0L
    /**
     * Last explicit seek target + when it was issued. ExoPlayer applies seeks
     * asynchronously: for a few hundred ms after seekTo() it still reports
     * the pre-seek position, which the ticker would flash onto the slider
     * (jump back, then jump forward). [settleSeekPosition] masks those stale
     * reads with the target until the window expires or the track changes.
     */
    @Volatile private var lastSeekTargetMs = -1L
    @Volatile private var lastSeekAtElapsedMs = 0L
    @Volatile private var playheadPosMs = 0L
    @Volatile private var playheadWallMs = 0L
    @Volatile private var playheadMoving = false
    @Volatile private var playheadKey: String? = null
    private val _signalPath = MutableStateFlow(SignalPathReport.initial())
    /** Verified signal-path report; BIT-PERFECT shows only when all checks pass. */
    val signalPath: StateFlow<SignalPathReport> = _signalPath.asStateFlow()
    val usbDacState: StateFlow<UsbDacMonitor.State> = usbDacMonitor.state
    private val resolvingMediaIds = ConcurrentHashMap<String, Long>()
    private val preparedStreams = ConcurrentHashMap<String, ResolvedStream>()
    /**
     * Known track durations (ms) keyed by MediaItem customCacheKey and by
     * mediaId/videoId. ExoPlayer reports TIME_UNSET until it has parsed
     * enough of a throttled progressive stream to infer duration — for some
     * YouTube WebM/MP4 URLs that takes 30-40s, during which the progress bar
     * sat frozen at 0 with seeking disabled. Seeding the resolve-time
     * duration here (approxDurationMs / lossless durationSeconds / the exact
     * player duration once learned) keeps progress + seek alive from t=0.
     */
    private val knownDurations = ConcurrentHashMap<String, Long>()

    private val mediaCache: Cache by lazy {
        val cacheDir = java.io.File(appContext.cacheDir, "media_stream_cache")
        // Bounded playback buffer, not an offline library. LRU eviction keeps
        // recent rewind/next-track data while preventing multi-GB growth.
        val evictor = LeastRecentlyUsedCacheEvictor(MEDIA_STREAM_CACHE_BYTES)
        val dbProvider = StandaloneDatabaseProvider(appContext)
        SimpleCache(cacheDir, evictor, dbProvider)
    }

    private val cacheDataSourceFactory: CacheDataSource.Factory by lazy {
        val httpUpstream = DefaultHttpDataSource.Factory()
            .setUserAgent(YOUTUBE_WEB_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)
            .setContentTypePredicate(HttpDataSource.REJECT_PAYWALL_TYPES)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "audio/*,*/*;q=0.8",
                    "Accept-Encoding" to "identity",
                ),
            )
        val defaultDataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(appContext, httpUpstream)
        CacheDataSource.Factory()
            .setCache(mediaCache)
            .setUpstreamDataSourceFactory(defaultDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * Feeds SongPlayStatsRepository — the real "how much did you actually
     * listen to this" + skip signal that LocalTasteSuggestionEngine scores
     * on. Called right before onMediaItemTransition overwrites [_state],
     * since that's the last moment the outgoing track's position is known.
     * AUTO transition = the track played out naturally; anything else
     * (SEEK, via next()/previous()/tapping the queue) with < 85% played is
     * counted as a skip.
     */
    private fun recordLocalListenSignal(reason: Int) {
        val previousTrack = _state.value.current ?: return
        val previousPositionMs = _state.value.positionMs
        val previousDurationMs = _state.value.durationMs
        if (previousPositionMs <= 0L) return
        val playedRatio = if (previousDurationMs > 0L) {
            (previousPositionMs.toDouble() / previousDurationMs.toDouble()).coerceIn(0.0, 1.0)
        } else 1.0
        val completedNaturally = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || playedRatio >= 0.85
        val wasSkip = !completedNaturally
        applicationScope.launch(Dispatchers.IO) {
            val repo = songPlayStatsRepository.get()
            repo.recordListenedMs(
                title = previousTrack.title,
                artist = previousTrack.artist,
                videoId = previousTrack.videoId,
                artworkUrl = previousTrack.artworkUrl,
                listenedMs = previousPositionMs,
                completed = completedNaturally,
            )
            if (wasSkip) {
                repo.recordSkip(previousTrack.title, previousTrack.artist, previousTrack.videoId)
            }
        }
    }

    private val listener: Player.Listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player === this@MusicPlayer.player) refresh(player)
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (isCasting) return
            // Ignore stale outgoing (crossfade) player callbacks: only the
            // active player owns auto-advance. Outgoing ENDED is expected
            // after a handoff and is cleaned by cancelCrossfade().
            if (player !== this@MusicPlayer.player) return
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($playbackState)"
            }
            android.util.Log.i("MusicPlayer", "Playback state changed: $stateName, isPlaying=${player.isPlaying}, currentPos=${player.currentPosition}ms / ${player.duration}ms, bufferedPos=${player.bufferedPosition}ms, track='${_state.value.current?.title}'")
            if (playbackState == Player.STATE_BUFFERING) {
                android.util.Log.w("MusicPlayer", "Track BUFFERING / STALLED: '${_state.value.current?.title}' at ${player.currentPosition}ms (buffered=${player.bufferedPosition}ms)")
            }
            // Natural-end safety net: ExoPlayer auto-advances while a next
            // window exists, but when the queue truly ends — or the next
            // placeholder failed to open and ExoPlayer gave up — playback
            // parks in STATE_ENDED with isPlaying=false and never moves.
            // Users saw "song is over, never skips to next". Force a
            // lossless-first advance; the call is debounced and a no-op
            // when ExoPlayer already moved on.
            if (playbackState == Player.STATE_ENDED) {
                android.util.Log.i("MusicPlayer", "Playback STATE_ENDED for '${_state.value.current?.title}' -> advancing to next")
                onMain { handleNaturalTrackEnd() }
            }
            refresh(player)
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            outgoingPlayer?.playWhenReady = isPlaying
            if (isPlaying) {
                unavailableSkipJob?.cancel()
                unavailableSkipJob = null
                player.currentMediaItem?.mediaId?.let(unavailableMediaIds::remove)
            }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (isCasting) return
            recordLocalListenSignal(reason)
            // New item owns the clock from here: drop any seek-settle mask
            // from the previous track so it can't pin this one.
            lastSeekTargetMs = -1L
            // Natural advances (track end, repeat-all wrap, crossfade
            // handoff) are the only transitions the explicit next()/queue-tap
            // paths don't record — manual seeks arrive as SEEK, not AUTO.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                recordHistory(_state.value.current?.mediaIdKey())
            }
            if (mediaItem != null) {
                losslessBypassMediaIds.retainAll(setOf(mediaItem.mediaId))
                if (retryMediaId != mediaItem.mediaId) {
                    retryMediaId = mediaItem.mediaId
                    errorRetryCount = 0
                }
                val currentIndex = player.currentMediaItemIndex
                val currentTrack = mediaItem.toPlayableTrack()
                val currentQueue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toPlayableTrack() }
                _state.update {
                    it.copy(
                        current = currentTrack,
                        currentIndex = currentIndex,
                        positionMs = player.currentPosition.coerceAtLeast(0L).let { raw ->
                            if (raw > 1_500L) 0L else raw
                        },
                        bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L),
                        durationMs = effectiveDuration(player.duration, player, it.durationMs),
                        queue = if (currentQueue.isNotEmpty()) currentQueue else it.queue,
                        isBuffering = true,
                        error = null,
                        // New item owns its badge (same reason as the
                        // resolveAndPlayQueueItem reset above).
                        audioCodec = null,
                        bitrateKbps = null,
                        isLossless = false,
                        bitDepth = null,
                        samplingRateKHz = null,
                    )
                }
                mediaItem.localConfiguration
                    ?.customCacheKey
                    ?.let(preparedStreams::get)
                    ?.let(::publishResolvedQuality)
                if (outgoingPlayer == null) cancelCrossfade()
                // Queue placeholders are intentionally non-playable until
                // their signed stream has been resolved. Resolve an item
                // before Media3 can attempt to open its lastwave:// URI.
                val prepared = mediaItem.localConfiguration
                    ?.customCacheKey
                    ?.let(preparedStreams::get)
                if (mediaItem.localConfiguration?.uri?.scheme == "lastwave" || prepared?.isExpired() == true) {
                    // During lazy-player construction a restored queue is
                    // installed before the lazy value is published. Defer
                    // resolution until the first explicit playback action.
                    if (playerDelegate.isInitialized()) {
                        resolveAndPlayQueueItem(currentIndex)
                    }
                    return
                }
                if (currentTrack.playbackUrl != null) {
                    applicationScope.launch(Dispatchers.IO) {
                        publishLocalTrackQuality(currentTrack)
                    }
                }
                updateBitPerfectState()
                enrichUpcomingQueue(currentIndex)
                extendDiscoverQueueIfNeeded(currentIndex)
                extendRadioQueueIfNeeded(currentIndex)
                val nextIndex = if (player.shuffleModeEnabled) {
                    player.nextMediaItemIndex
                } else {
                    currentIndex + 1
                }
                if (nextIndex != C.INDEX_UNSET && nextIndex in 0 until player.mediaItemCount) {
                    preloadNextTrack(nextIndex, player.getMediaItemAt(nextIndex).toPlayableTrack())
                }
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            if (isCasting) return
            cancelCrossfade()
            resolutionRequests.clear()
            val currentTrack = _state.value.current
            val currentPos = player.currentPosition.coerceAtLeast(0)
            android.util.Log.e("MusicPlayer", "Playback ERROR on track '${currentTrack?.title}': code=${error.errorCodeName} (${error.errorCode}), msg=${error.message}, pos=${currentPos}ms", error)
            // A track that demonstrably played must never be auto-skipped as
            // "unavailable": mid-stream failures (throttled/rotated URLs,
            // network blips) are transient, while genuinely dead tracks fail
            // before producing audible playback. Holding with tap-to-retry
            // stops one bad stretch from eating the whole queue 3s at a time.
            val playedAudibly = currentPos >= MIN_AUDIBLE_PLAYBACK_MS
            val trackVideoId = currentTrack?.videoId
            val failedIndex = player.currentMediaItemIndex
            val failedMediaId = player.currentMediaItem?.mediaId
            val selectedMediaId = currentTrack?.mediaIdKey()
            val selectedResolution = selectedMediaId?.let(resolvingMediaIds::get)
            if (selectedResolution == playRequestGeneration.get() &&
                (selectedMediaId != failedMediaId || player.currentMediaItem?.localConfiguration?.uri?.scheme == "lastwave")
            ) return
            val rejectedStream = player.currentMediaItem
                ?.localConfiguration
                ?.customCacheKey
                ?.let(preparedStreams::get)
            val customCacheKey = player.currentMediaItem?.localConfiguration?.customCacheKey
            val failedLocalStream = player.currentMediaItem?.localConfiguration?.uri?.scheme in setOf("file", "content")
            val failedLosslessStream = !failedLocalStream && (rejectedStream?.isLossless
                ?: customCacheKey?.startsWith("lossless:")
                ?: _state.value.isLossless)
            val rejectedYouTubeCandidate = rejectedStream?.youtubeCandidate
            val videoId = rejectedYouTubeCandidate?.videoId ?: trackVideoId
            val httpStatus = error.httpStatusCodeOrNull()

            if (currentTrack?.playbackUrl != null && failedMediaId?.startsWith("local:") == true) {
                _state.update { it.copy(error = error.message ?: "Local file playback error (${error.errorCodeName})", isPlaying = false, isBuffering = false) }
                scheduleUnavailableMediaSkip(failedIndex, failedMediaId, failure = error, allowAutoSkip = !playedAudibly)
                return
            }

            rejectedStream?.let {
                logStreamEvent(
                    stage = "player-error",
                    stream = it,
                    retry = errorRetryCount,
                    httpStatus = httpStatus,
                    error = error,
                )
            } ?: currentTrack?.let { logResolutionFailure(it, "player-error", errorRetryCount, error) }

            if (failedLosslessStream) {
                if (failedMediaId != null && (errorRetryCount > 0 || !isRetryablePlaybackFailure(error))) {
                    losslessBypassMediaIds += failedMediaId
                }
            } else if (!failedLocalStream && !videoId.isNullOrBlank()) {
                if (rejectedYouTubeCandidate == null) innerTube.invalidateCache(videoId)
                innerTube.reportPlaybackFailure(videoId, rejectedYouTubeCandidate)
            }

            val confirmedUnplayable = isExplicitlyUnplayableFailure(error)

            if (currentTrack != null &&
                errorRetryCount < MAX_PLAYBACK_RETRIES &&
                (failedLocalStream || failedLosslessStream || confirmedUnplayable || isRetryablePlaybackFailure(error))
            ) {
                errorRetryCount++
                val retry = errorRetryCount
                val generation = playRequestGeneration.incrementAndGet()
                playRequest?.cancel()
                playRequest = applicationScope.launch(Dispatchers.IO) {
                    var retryResolutionFailure: Throwable? = null
                    try {
                        rejectedStream?.cacheKey?.let { cacheKey ->
                            runCatching { mediaCache.removeResource(cacheKey) }
                            preparedStreams.remove(cacheKey)
                        }
                        val retryDelayMs = if (failedLocalStream) 0L else playbackRetryDelayMs(error, retry)
                        if (retryDelayMs > 0L) delay(retryDelayMs)
                        currentCoroutineContext().ensureActive()
                        val updated = currentTrack.copy(
                            playbackUrl = null,
                            playbackMimeType = null,
                        )
                        val stream = resolveTrackAudioStream(
                            track = updated,
                            allowLocalDownloads = false,
                            videoId = videoId,
                            allowLossless = failedMediaId !in losslessBypassMediaIds,
                            excludedLosslessUrls = if (failedLosslessStream) {
                                setOfNotNull(rejectedStream?.url)
                            } else emptySet(),
                        )
                        withContext(Dispatchers.Main.immediate) {
                            if (generation != playRequestGeneration.get() ||
                                player.currentMediaItemIndex != failedIndex ||
                                player.currentMediaItem?.mediaId != failedMediaId
                            ) {
                                return@withContext
                            }
                            if (failedIndex in 0 until player.mediaItemCount) {
                                registerPreparedStream(stream)
                                publishResolvedQuality(stream)
                                applyDacRoutingFor(dacRateFor(stream))
                                logStreamEvent("player-retry", stream, retry = retry)
                                cacheCurrentTrackStream(stream)
                                player.replaceMediaItem(failedIndex, updated.toMediaItem(stream))
                                lastSeekTargetMs = currentPos
                                lastSeekAtElapsedMs = SystemClock.elapsedRealtime()
                                player.seekTo(failedIndex, currentPos)
                                player.prepare()
                                player.play()
                                preloadNextQueueItem(failedIndex)
                            }
                        }
                        return@launch
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (e: Throwable) {
                        retryResolutionFailure = e
                        logResolutionFailure(currentTrack, "player-retry-resolve", retry, e)
                    }
                    withContext(Dispatchers.Main.immediate) {
                        if (generation != playRequestGeneration.get() ||
                            player.currentMediaItemIndex != failedIndex ||
                            player.currentMediaItem?.mediaId != failedMediaId
                        ) {
                            return@withContext
                        }
                        _state.update { it.copy(error = error.message ?: "Playback error (${error.errorCodeName})", isBuffering = false) }
                        scheduleUnavailableMediaSkip(
                            failedIndex = failedIndex,
                            failedMediaId = failedMediaId,
                            expectedGeneration = generation,
                            failure = retryResolutionFailure ?: error,
                            allowAutoSkip = !playedAudibly,
                        )
                    }
                }
                _state.update { it.copy(isBuffering = true, error = null) }
                return
            }

            _state.update { it.copy(error = error.message ?: "Playback error (${error.errorCodeName})", isBuffering = false) }
            scheduleUnavailableMediaSkip(failedIndex, failedMediaId, failure = error, allowAutoSkip = !playedAudibly)
        }
    }

    // USB exclusive output is engaged from [applyDacRoutingFor] while
    // Bit-Perfect (or the USB Exclusive toggle) is on and a DAC is granted.
    // The sink graph is always NativeProcessingAudioSink so exclusive can
    // start mid-session without rebuilding ExoPlayer.
    private fun createPlayer(
        engineProvider: () -> NativeAudioEngine?,
        effects: AudioEffectsEngine,
        handleAudioFocus: Boolean,
    ): ExoPlayer {
        val resolving = ResolvingDataSource.Factory(cacheDataSourceFactory) { dataSpec ->
            val placeholder = dataSpec.uri.takeIf { it.scheme == "lastwave" }
            val resolvedPlaceholder = placeholder?.let { uri ->
                val videoId = uri.lastPathSegment.takeIf { uri.host == "youtube" }
                val title = uri.getQueryParameter("title").orEmpty()
                val artist = uri.getQueryParameter("artist").orEmpty()
                val track = _state.value.queue.firstOrNull {
                    if (videoId != null) it.videoId == videoId else it.title == title && it.artist == artist
                } ?: PlayableTrack(title = title, artist = artist, videoId = videoId)
                // Media3 can open the next item before its transition callback.
                // Resolve queue placeholders on its loader thread as well.
                val bypassLossless = track.mediaIdKey() in losslessBypassMediaIds
                // An already-prepared stream for this track skips the blocking
                // re-resolve entirely (worst-case loader stall was 30-90s).
                val preparedHit = findPreparedStreamFor(track, videoId, bypassLossless)
                if (preparedHit != null) {
                    android.util.Log.i("MusicPlayer", "[MEDIA3] loader prepared-hit '${track.title}' key=${preparedHit.cacheKey}")
                    preparedHit
                } else {
                    runCatching {
                        runBlocking(Dispatchers.IO) {
                            resolveTrackAudioStreamWithRetry(track, track.videoId, allowLossless = !bypassLossless).also { resolved ->
                                logStreamEvent("loader-prepared", resolved, retry = 0)
                                android.util.Log.i("MusicPlayer", "[MEDIA3] loader resolved '${track.title}' key=${resolved.cacheKey} codec=${resolved.audioCodec}")
                                applicationScope.launch(Dispatchers.Main.immediate) {
                                    registerPreparedStream(resolved)
                                    publishResolvedQuality(resolved)
                                }
                            }
                        }
                    }.getOrElse { failure ->
                        losslessBypassMediaIds += track.mediaIdKey()
                        logResolutionFailure(track, "loader-resolve", 0, failure)
                        null
                    }
                }
            }
            if (placeholder != null && resolvedPlaceholder == null) {
                throw java.io.IOException("Unable to resolve stream for ${placeholder.getQueryParameter("title") ?: placeholder}")
            }
            val stream = resolvedPlaceholder ?: dataSpec.key?.let(preparedStreams::get)
                ?: preparedStreams.values.firstOrNull { it.url == dataSpec.uri.toString() }
            if (stream?.isExpired() == true) {
                throw java.io.IOException("Signed stream expired before open")
            }
            when {
                resolvedPlaceholder != null -> dataSpec.buildUpon()
                    .setUri(resolvedPlaceholder.url)
                    .setKey(resolvedPlaceholder.cacheKey)
                    .build()
                    .withRequestHeaders(resolvedPlaceholder.requestHeaders)
                stream != null -> dataSpec.withRequestHeaders(stream.requestHeaders)
                else -> dataSpec
            }
        }
        val baseLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ if (handleAudioFocus) 45_000 else 15_000,
                /* maxBufferMs = */ if (handleAudioFocus) 120_000 else 30_000,
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 1_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(15_000, true)
            .build()
        val renderersFactory = object : DefaultRenderersFactory(appContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink {
                val fallbackSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(false)
                    .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                    .build()
                val engine = runCatching(engineProvider).getOrNull()
                val enhancedSink = try {
                    DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(true)
                        .setEnableAudioTrackPlaybackParams(false)
                        .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                        .build()
                } catch (error: Exception) {
                    android.util.Log.w("MusicPlayer", "Enhanced audio sink unavailable; using PCM16", error)
                    effects.setFallbackRequired(true)
                    DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(false)
                        .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                        .build()
                } catch (error: LinkageError) {
                    android.util.Log.w("MusicPlayer", "Enhanced audio sink linkage failed; using PCM16", error)
                    effects.setFallbackRequired(true)
                    DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(false)
                        .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                        .build()
                }
                if (engine?.isAvailable == true) {
                    effects.setFallbackRequired(false)
                } else {
                    effects.setFallbackRequired(true)
                }
                val processorEngine = engine ?: nativeAudioEngine.get()
                return NativeProcessingAudioSink(
                    enhancedDelegate = enhancedSink,
                    fallbackDelegate = fallbackSink,
                    processor = NativePcmAudioProcessor(processorEngine),
                    onPlatformEffectsRequired = effects::setFallbackRequired,
                    usbOutput = if (handleAudioFocus) UsbBitPerfectOutput(audioManager) else null,
                    exclusiveUsb = exclusiveUsbOutput,
                ).also { sink ->
                    sink.setBitPerfectRequested(bitPerfectEnabled || usbExclusivePrefEnabled)
                    sink.syncExclusiveUsb(exclusiveUsbWanted())
                    audioSinks.add(sink)
                    runCatching {
                        routedDacDeviceId?.let { id -> findOutputDevice(id)?.let(sink::setPreferredDevice) }
                    }
                }
            }
        }.apply {
            // FFmpeg-first decoding, the Poweramp/VLC model: every codec the
            // bundled GPL build supports (FLAC 24/96-192, Opus, AAC, MP3,
            // Vorbis) decodes through the same battle-tested software path on
            // every device, eliminating per-OEM platform codec bugs that
            // surface as noise/distortion. setEnableDecoderFallback keeps the
            // platform decoder for anything FFmpeg rejects.
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true)
            setEnableAudioTrackPlaybackParams(false)
            setMediaCodecSelector(accurateAudioMediaCodecSelector)
        }

        return ExoPlayer.Builder(appContext, renderersFactory)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(appContext).setDataSourceFactory(resolving)
                    // Module-resolved items carry their Widevine session in
                    // the prepared stream; the session manager (and license
                    // envelope) comes from the module via ModuleDrmFactory.
                    // Everything else plays clear as before.
                    .setDrmSessionManagerProvider { mediaItem ->
                        val stream = mediaItem.localConfiguration?.customCacheKey
                            ?.let(preparedStreams::get)
                        val descriptor = stream?.segmentedDrm
                        if (descriptor?.drm == null) DrmSessionManager.DRM_UNSUPPORTED
                        else moduleDrmFactory.sessionManagerFor(descriptor)
                    },
            )
            .setLoadControl(baseLoadControl)
            .build().apply {
                // Feed ReplayGain container tags into loudness normalization.
                // Additive listener: never touches playback, 0 dB without tags.
                addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    override fun onAudioInputFormatChanged(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        format: androidx.media3.common.Format,
                    ) {
                        runCatching { effects.setReplayGainFromFormat(format) }
                        val rateHz = format.sampleRate
                        val sampleMime = format.sampleMimeType?.lowercase().orEmpty()
                        val detectedCodec = when {
                            sampleMime.contains("eac3") || sampleMime.contains("ec-3") ||
                                sampleMime.contains("ac-3") || sampleMime.contains("ac3") -> "DOLBY ATMOS"
                            sampleMime.contains("mha1") || sampleMime.contains("mhm1") -> "SPATIAL AUDIO"
                            sampleMime.contains("opus") -> "OPUS"
                            sampleMime.contains("flac") -> "FLAC"
                            sampleMime.contains("mp4a") || sampleMime.contains("aac") -> "AAC"
                            sampleMime.contains("mp3") || sampleMime.contains("mpeg") -> "MP3"
                            else -> null
                        }
                        val bitrate = format.bitrate.takeIf { it > 0 }?.let { (it + 500) / 1000 }
                        val depth = when (format.pcmEncoding) {
                            C.ENCODING_PCM_8BIT -> 8
                            C.ENCODING_PCM_16BIT -> 16
                            C.ENCODING_PCM_24BIT -> 24
                            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
                            else -> null
                        }
                        _state.update { snapshot ->
                            var updated = snapshot
                            if (rateHz > 0) {
                                val kHz = rateHz / 1000.0
                                if (updated.samplingRateKHz != kHz) updated = updated.copy(samplingRateKHz = kHz)
                            }
                            if (depth != null && updated.bitDepth == null) {
                                updated = updated.copy(bitDepth = depth)
                            }
                            if (isSpatialAudioCodec(detectedCodec)) {
                                updated = updated.copy(audioCodec = detectedCodec, isLossless = false)
                            } else if (!isSpatialAudioCodec(updated.audioCodec) &&
                                (updated.audioCodec == null || updated.audioCodec == "AUDIO") &&
                                detectedCodec != null
                            ) {
                                updated = updated.copy(
                                    audioCodec = detectedCodec,
                                    bitrateKbps = updated.bitrateKbps ?: bitrate ?: if (detectedCodec == "OPUS") 160 else null,
                                    isLossless = updated.isLossless || detectedCodec == "FLAC",
                                )
                            }
                            android.util.Log.i("MusicPlayer", "AudioInputFormatChanged: mime=${format.sampleMimeType}, rate=${rateHz}Hz, bitrate=${format.bitrate}, detectedCodec=$detectedCodec -> qualityPill=[codec=${updated.audioCodec}, bitrate=${updated.bitrateKbps}kbps, rate=${updated.samplingRateKHz}kHz]")
                            updated
                        }
                    }
                })
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
                        .build(),
                    handleAudioFocus,
                )
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
                addListener(object : Player.Listener {
                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        effects.attach(audioSessionId)
                    }
                })
            }
    }

    private val playerDelegate: Lazy<ExoPlayer> = lazy {
        createPlayer({ nativeAudioEngine.get() }, audioEffectsEngine, true)
            .also { restoredPlayer ->
                activePlayer = restoredPlayer
                restoredPlayer.addListener(listener)
                // A persisted queue is UI state, not a reason to touch the
                // device's codec/audio stack during process launch. Hydrate
                // ExoPlayer only when an actual player operation first asks
                // for it. The renderer, sink, DSP and fallback graph above is
                // otherwise identical on every device.
                val restoredSession = pendingRestoredSession
                pendingRestoredSession = null
                if (restoredSession != null && restoredSession.queue.isNotEmpty()) {
                    val restoredIndex = restoredSession.currentIndex.coerceIn(restoredSession.queue.indices)
                    restoredPlayer.setMediaItems(
                        restoredSession.queue.map(PlayableTrack::toMediaItem),
                        restoredIndex,
                        restoredSession.positionMs.coerceAtLeast(0),
                    )
                    restoredPlayer.shuffleModeEnabled = restoredSession.shuffleEnabled
                    restoredPlayer.repeatMode = restoredSession.repeatMode.takeIf {
                        it in Player.REPEAT_MODE_OFF..Player.REPEAT_MODE_ALL
                    } ?: Player.REPEAT_MODE_OFF
                    // A persisted tempo stretch resamples by definition — it
                    // must not survive into a Bit-Perfect session.
                    val restoredSpeed = restoredSession.speed.coerceIn(0.5f, 2f)
                    restoredPlayer.setPlaybackSpeed(if (bitPerfectEnabled) 1f else restoredSpeed)
                    restoredPlayer.pause()
                }
            }
    }

    private val player: ExoPlayer
        get() = activePlayer ?: playerDelegate.value

    init {
        runCatching { restorePlaybackSession() }.getOrElse { error ->
            // A corrupt session or OEM media-stack failure must not become a
            // permanent launch-crash loop. Discard only the resumable session.
            android.util.Log.e("MusicPlayer", "Playback restore disabled", error)
            _state.value = MusicPlayerState()
            clearPersistedPlaybackSession()
            false
        }
        ticker = applicationScope.launch(Dispatchers.Main.immediate) {
            var lastTickerPersistMs = 0L
            while (true) {
                // The ticker is the sole driver of progress-bar updates: it
                // must never die (one escaping exception used to kill the
                // 60ms cadence permanently, leaving the bar to update only on
                // rare player events) and every path must reach the delay
                // below, including crossfade handoffs and track mismatches.
                var cadenceMs = 500L
                try {
                    val usbAlive = !exclusiveUsbOutput.isActive() || exclusiveUsbOutput.isStreamAlive()
                    val playingNow = _state.value.isPlaying && !exclusiveUsbOutput.isPaused() && usbAlive
                    val playhead = advancePlayhead(playingNow)
                    if (playhead != _state.value.positionMs) {
                        _state.update { it.copy(positionMs = playhead) }
                    }
                    if (!isCasting && exclusiveUsbOutput.isActive()) {
                        // Never touch ExoPlayer here. Its playback thread holds
                        // the player lock inside the blocking USB write, so a
                        // currentPosition read froze the seek bar after the
                        // first buffer and left drift on "measuring…".
                        publishExclusiveDrift()
                        cadenceMs = if (exclusiveUsbOutput.isPaused()) 250L else 60L
                    } else if (isCasting) {
                        val remaining = sleepTimerDeadlineMs?.minus(SystemClock.elapsedRealtime())
                        if (remaining != null && remaining <= 0) {
                            sleepTimerDeadlineMs = null
                            sleepTimerStep = 0
                            pause()
                        }
                        _state.update { it.copy(sleepTimerRemainingMs = remaining?.coerceAtLeast(0)) }
                        cadenceMs = 500L
                    } else if (_state.value.current != null && playerDelegate.isInitialized()) {
                        val remaining = sleepTimerDeadlineMs?.minus(SystemClock.elapsedRealtime())
                        if (remaining != null && remaining <= 0) {
                            sleepTimerDeadlineMs = null
                            sleepTimerStep = 0
                            player.pause()
                        }
                        if (player.currentMediaItem?.mediaId != _state.value.current?.mediaIdKey()) {
                            _state.update { it.copy(sleepTimerRemainingMs = remaining?.coerceAtLeast(0)) }
                            cadenceMs = 60L
                        } else {
                            val dur = effectiveDuration(player.duration, player, _state.value.durationMs)
                            val pos = _state.value.positionMs
                            val buf = player.bufferedPosition.coerceAtLeast(0)
                            val sleepRemaining = remaining?.coerceAtLeast(0)

                            if (updateCrossfade(pos)) {
                                cadenceMs = 60L
                            } else {

                    // Second-chance preload: the track-start preload may have
                    // failed, been skipped (paused then) or resolved too slowly.
                    // Without this, the natural transition lands on an
                    // unresolved placeholder -> audible gap, then an error and
                    // an auto-skip to the following song ("glitch then skips").
                    // Fires once per upcoming item inside the last 30s.
                    if (player.isPlaying && dur > 0L) {
                        val remainingMs = dur - pos
                        if (remainingMs in 1..30_000L && preloadJob?.isActive != true) {
                            val upcomingIndex = player.nextMediaItemIndex
                            if (upcomingIndex != C.INDEX_UNSET &&
                                upcomingIndex in 0 until player.mediaItemCount &&
                                upcomingIndex != player.currentMediaItemIndex
                            ) {
                                val upcomingItem = player.getMediaItemAt(upcomingIndex)
                                if (upcomingItem.localConfiguration?.uri?.scheme == "lastwave" &&
                                    upcomingItem.mediaId != latePreloadKey
                                ) {
                                    latePreloadKey = upcomingItem.mediaId
                                    preloadNextQueueItem(player.currentMediaItemIndex)
                                }
                            }
                        }
                    }

                    // End-of-track watchdog: onPlaybackStateChanged(ENDED)
                    // already forces a lossless-first advance, but events can
                    // be missed while the ticker is the only observer (e.g.
                    // crossfade handoff, OEM binder stalls). If ExoPlayer is
                    // parked at ENDED — or pinned READY at the duration tail
                    // with playWhenReady and a next window — drive the same
                    // debounced lossless-first advance. BUFFERING is
                    // deliberately excluded: the next lossless resolve may
                    // still be in flight and must never be skipped for speed.
                    if (player.playbackState == Player.STATE_ENDED) {
                        handleNaturalTrackEnd()
                    } else if (player.duration > 0L &&
                        // Pinned-tail advance only on the TRUE ExoPlayer
                        // duration: a seeded/approximate denominator can sit
                        // below the real end and would otherwise "advance"
                        // mid-track on any transient pause (freeze, then jump
                        // to the next song). Genuine ends still arrive via the
                        // STATE_ENDED branch above.
                        pos >= player.duration - END_OF_TRACK_STALL_THRESHOLD_MS &&
                        player.playWhenReady &&
                        !player.isPlaying &&
                        player.playbackState == Player.STATE_READY &&
                        _state.value.error == null &&
                        player.nextMediaItemIndex != C.INDEX_UNSET
                    ) {
                        handleNaturalTrackEnd()
                    }

                    // Stream-health sampling: effective clock drift + glitch
                    // watch, 1 Hz while playing. Feeds the signal-path popup.
                    val tickerNow = SystemClock.elapsedRealtime()
                    val healthPlaying = player.isPlaying || _state.value.isPlaying
                    if (healthPlaying && tickerNow - lastSignalPathMs >= SIGNAL_PATH_TICK_MS) {
                        lastSignalPathMs = tickerNow
                        val exclusiveRate = exclusiveUsbOutput.currentRateHz()
                        if (exclusiveUsbOutput.isActive() && exclusiveRate > 0) {
                            healthTracker.sampleExclusive(
                                exclusiveUsbOutput.framesWritten(),
                                exclusiveRate,
                                tickerNow,
                                true,
                            )
                        } else {
                            healthTracker.sample(pos, tickerNow, true)
                        }
                        updateSignalPath()
                    }

                    val previous = _state.value
                    val unchanged = !_state.value.isPlaying &&
                        previous.positionMs == pos &&
                        previous.bufferedPositionMs == buf &&
                        previous.durationMs == dur &&
                        previous.sleepTimerRemainingMs == sleepRemaining
                    if (!unchanged) {
                                _state.update {
                                    it.copy(
                                        positionMs = pos,
                                        bufferedPositionMs = buf,
                                        durationMs = dur,
                                        sleepTimerRemainingMs = sleepRemaining,
                                    )
                                }
                                // Session persistence rebuilds a queue slice every call —
                                // throttling it from every tick to 2s removes constant
                                // main-thread allocation with zero UX difference (the
                                // signature already buckets positions at 5s).
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastTickerPersistMs >= TICKER_PERSIST_INTERVAL_MS) {
                                    lastTickerPersistMs = now
                                    persistPlaybackSession()
                                }
                            }
                            // Preserve lazy player startup when there is no
                            // restored or active queue. The short-circuit
                            // avoids touching ExoPlayer.
                            cadenceMs = if (_state.value.isPlaying) 60L else 500L
                            }
                        }
                    }
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    android.util.Log.w("MusicPlayer", "Progress ticker recovered", error)
                    cadenceMs = 500L
                }
                delay(cadenceMs)
            }
        }

        applicationScope.launch {
            settingsPreferences.settings.collect { settings ->
                crossfadeEnabled = settings.crossfadeEnabled
                crossfadeDurationMs = settings.crossfadeSeconds.coerceIn(1, 12) * 1000L
                val wasBitPerfect = bitPerfectEnabled
                bitPerfectEnabled = settings.isBitPerfectEnabled
                updateBitPerfectState()
                if (bitPerfectEnabled && settings.isStudioMasterClarityEnabled) {
                    // Self-heal: both must never be on — Bit-Perfect wins.
                    settingsPreferences.setStudioMasterClarity(false)
                }
                if (bitPerfectEnabled && !wasBitPerfect) {
                    // Freshly engaged: ask for direct USB access right away so
                    // the DAC route is usable without hunting for the dialog.
                    maybeRequestUsbPermission()
                }
                onMain {
                    applyDacRoutingFor(currentSourceRateHz())
                    updateSignalPath()
                }
                if (playerDelegate.isInitialized()) {
                    onMain {
                        if (!crossfadeEnabled || bitPerfectEnabled) cancelCrossfade()
                        if (bitPerfectEnabled) {
                            // Bulletproofing: tempo stretch resamples and any
                            // leftover fade gain scales samples — both defeat
                            // bit-perfect, so engaging the mode resets them.
                            if (runCatching { player.playbackParameters.speed }.getOrDefault(1f) != 1f) {
                                runCatching { player.setPlaybackSpeed(1f) }
                            }
                            if (runCatching { player.volume }.getOrDefault(1f) != 1f) {
                                runCatching { player.volume = 1f }
                            }
                        }
                    }
                }
            }
        }

        applicationScope.launch {
            usbDacMonitor.state.collect {
                if (bitPerfectEnabled || usbExclusivePrefEnabled) {
                    maybeRequestUsbPermission()
                }
                onMain {
                    applyDacRoutingFor(currentSourceRateHz())
                    updateSignalPath()
                }
            }
        }

        applicationScope.launch {
            UsbExclusivePrefs.enabledFlow(appContext).collect { enabled ->
                usbExclusivePrefEnabled = enabled
                if (enabled) maybeRequestUsbPermission()
                onMain {
                    applyDacRoutingFor(currentSourceRateHz())
                    updateSignalPath()
                }
            }
        }

        applicationScope.launch {
            discoverRepository.feed.collect { feed ->
                if (discoverQueueActive) appendMissingDiscoverTracks(feed.map(GeneratedTrack::toPlayableTrack))
            }
        }
    }

    fun play(
        track: PlayableTrack,
        sourceLabel: String = "LastWave",
        startRadio: Boolean = (sourceLabel == "Search" || sourceLabel == "YouTube Music" || sourceLabel == "Spotify Link" || sourceLabel == "Shared Song"),
    ) {
        pendingRestoredSession = null
        disableDiscoverQueue()
        disableRadioQueue()
        queueEnrichmentJob?.cancel()
        unavailableSkipJob?.cancel()
        unavailableMediaIds.clear()
        playHistory.clear()
        latePreloadKey = null
        radioQueueActive = startRadio
        startResolvedQueuePlayback(
            tracks = listOf(track),
            selectedIndex = 0,
            startPositionMs = 0L,
            sourceLabel = sourceLabel,
            endlessDiscover = false,
        )
        if (startRadio) {
            _state.update { it.copy(isEndlessQueue = true) }
            startRadioQueue(track)
        }
    }

    fun playQueue(
        tracks: List<PlayableTrack>,
        startIndex: Int = 0,
        sourceLabel: String = "LastWave",
        startShuffled: Boolean = false,
    ) {
        disableRadioQueue()
        playQueueInternal(tracks, startIndex, endlessDiscover = false, sourceLabel = sourceLabel, startShuffled = startShuffled)
    }

    fun playDiscoverQueue(tracks: List<PlayableTrack>, startIndex: Int = 0) {
        disableRadioQueue()
        playQueueInternal(tracks, startIndex, endlessDiscover = true, sourceLabel = "Discover", startShuffled = false)
    }

    private fun playQueueInternal(
        tracks: List<PlayableTrack>,
        startIndex: Int,
        endlessDiscover: Boolean,
        sourceLabel: String = if (endlessDiscover) "Discover" else "LastWave",
        startShuffled: Boolean = false,
    ) {
        if (tracks.isEmpty()) return
        pendingRestoredSession = null
        discoverQueueLoadJob?.cancel()
        discoverQueueActive = endlessDiscover
        disableRadioQueue()
        val selectedIndex = startIndex.coerceIn(tracks.indices)
        playRequest?.cancel()
        queueEnrichmentJob?.cancel()
        unavailableSkipJob?.cancel()
        unavailableMediaIds.clear()
        // Fresh queue context: previous-queue history no longer applies.
        // (Same-queue navigations via startResolvedQueuePlayback keep it.)
        playHistory.clear()
        latePreloadKey = null

        startResolvedQueuePlayback(
            tracks = tracks,
            selectedIndex = selectedIndex,
            startPositionMs = 0L,
            sourceLabel = sourceLabel,
            endlessDiscover = endlessDiscover,
            startShuffled = startShuffled,
        )
    }

    private fun startResolvedQueuePlayback(
        tracks: List<PlayableTrack>,
        selectedIndex: Int,
        startPositionMs: Long,
        sourceLabel: String,
        endlessDiscover: Boolean,
        startShuffled: Boolean = false,
    ) {
        val selectedTrack = tracks[selectedIndex].withYoutubeArtwork()
        if (isCasting) {
            onMain {
                cancelPendingPlaybackResolution()
                ensureForegroundService()
                _state.value = _state.value.copy(current = selectedTrack, queue = tracks,
                    currentIndex = selectedIndex, positionMs = startPositionMs, durationMs = 0,
                    sourceLabel = sourceLabel, isEndlessQueue = endlessDiscover,
                    shuffleEnabled = startShuffled, error = null)
                castPlayback?.load(_state.value)
            }
            return
        }
        warmArtwork(selectedTrack)
        val generation = playRequestGeneration.incrementAndGet()
        playRequest?.cancel()
        preloadJob?.cancel()
        onMain {
            if (generation != playRequestGeneration.get()) return@onMain
            ensureForegroundService()
            cancelCrossfade()
            losslessBypassMediaIds.clear()
            errorRetryCount = 0
            retryMediaId = null
            if (playerDelegate.isInitialized()) {
                player.stop()
                player.clearMediaItems()
            }
            _state.value = MusicPlayerState(
                current = selectedTrack,
                queue = tracks,
                currentIndex = selectedIndex,
                sourceLabel = sourceLabel,
                isEndlessQueue = endlessDiscover || radioQueueActive,
                isBuffering = true,
                isPlaying = true,
                positionMs = startPositionMs.coerceAtLeast(0L),
                shuffleEnabled = if (playerDelegate.isInitialized()) player.shuffleModeEnabled else startShuffled,
                repeatMode = player.repeatMode,
            )
            persistPlaybackSession()
        }
        playRequest = applicationScope.launch(Dispatchers.IO) {
            try {
                val resolved = if (selectedTrack.playbackUrl == null) {
                    resolveTrackAudioStreamWithRetry(
                        track = selectedTrack,
                        videoId = selectedTrack.videoId,
                        allowLossless = selectedTrack.mediaIdKey() !in losslessBypassMediaIds,
                    )
                } else {
                    null
                }
                currentCoroutineContext().ensureActive()
                if (generation != playRequestGeneration.get()) return@launch
                withContext(Dispatchers.Main.immediate) {
                    if (generation != playRequestGeneration.get()) return@withContext
                    if (startShuffled) player.shuffleModeEnabled = true
                    resolved?.let {
                        registerPreparedStream(it)
                        publishResolvedQuality(it)
                        applyDacRoutingFor(dacRateFor(it))
                        logStreamEvent("player-prepare", it, retry = 0)
                        cacheCurrentTrackStream(it)
                    }
                    if (selectedTrack.playbackUrl != null) {
                        applicationScope.launch(Dispatchers.IO) { publishLocalTrackQuality(selectedTrack) }
                    }
                    val mediaItems = tracks.mapIndexed { index, track ->
                        track.toMediaItem(if (index == selectedIndex) resolved else null)
                    }
                    player.setMediaItems(mediaItems, selectedIndex, startPositionMs.coerceAtLeast(0L))
                    player.prepare()
                    player.play()
                    enrichUpcomingQueue(selectedIndex)
                    if (endlessDiscover) {
                        appendMissingDiscoverTracks(discoverRepository.getCachedFeed().map(GeneratedTrack::toPlayableTrack))
                    }
                    extendDiscoverQueueIfNeeded(selectedIndex)
                    extendRadioQueueIfNeeded(selectedIndex)
                    preloadNextQueueItem(selectedIndex)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (generation != playRequestGeneration.get()) return@launch
                logResolutionFailure(selectedTrack, "resolve-before-prepare", 0, error)
                if (selectedTrack.mediaIdKey() !in losslessBypassMediaIds) {
                    losslessBypassMediaIds += selectedTrack.mediaIdKey()
                    val ytFallback = try {
                        resolveYoutubeTrackAudioStream(selectedTrack, selectedTrack.videoId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        null
                    }
                    if (ytFallback != null && generation == playRequestGeneration.get()) {
                        withContext(Dispatchers.Main.immediate) {
                            if (generation != playRequestGeneration.get()) return@withContext
                            registerPreparedStream(ytFallback)
                            publishResolvedQuality(ytFallback)
                            cacheCurrentTrackStream(ytFallback)
                            val mediaItems = tracks.mapIndexed { index, track ->
                                track.toMediaItem(if (index == selectedIndex) ytFallback else null)
                            }
                            player.setMediaItems(mediaItems, selectedIndex, startPositionMs.coerceAtLeast(0L))
                            player.prepare()
                            player.play()
                            enrichUpcomingQueue(selectedIndex)
                            if (endlessDiscover) {
                                appendMissingDiscoverTracks(discoverRepository.getCachedFeed().map(GeneratedTrack::toPlayableTrack))
                            }
                            extendDiscoverQueueIfNeeded(selectedIndex)
                            extendRadioQueueIfNeeded(selectedIndex)
                            preloadNextQueueItem(selectedIndex)
                        }
                        return@launch
                    }
                }
                withContext(Dispatchers.Main.immediate) {
                    if (generation == playRequestGeneration.get()) {
                        unavailableMediaIds += selectedTrack.mediaIdKey()
                        val nextIndex = if (startShuffled) {
                            tracks.indices
                                .filter { tracks[it].mediaIdKey() !in unavailableMediaIds }
                                .randomOrNull()
                        } else {
                            val ordered = (selectedIndex + 1 until tracks.size) +
                                if (player.repeatMode == Player.REPEAT_MODE_ALL) {
                                    0 until selectedIndex
                                } else {
                                    emptyList()
                                }
                            ordered.firstOrNull { tracks[it].mediaIdKey() !in unavailableMediaIds }
                        }
                        if (nextIndex != null) {
                            playRequest = null
                            startResolvedQueuePlayback(
                                tracks = tracks,
                                selectedIndex = nextIndex,
                                startPositionMs = 0L,
                                sourceLabel = sourceLabel,
                                endlessDiscover = endlessDiscover,
                                startShuffled = startShuffled,
                            )
                        } else {
                            val isOffline = isNetworkException(error)
                            val userMessage = if (isOffline) {
                                "Track not available offline"
                            } else {
                                error.message ?: "Unable to resolve audio"
                            }
                            _state.update {
                                it.copy(
                                    isPlaying = false,
                                    isBuffering = false,
                                    error = userMessage,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun playNext(track: PlayableTrack) {
        applicationScope.launch {
            val enriched = runCatching { matchMetadata(track) }.getOrDefault(track)
            withContext(Dispatchers.Main.immediate) {
                if (isCasting) {
                    _state.update { snapshot ->
                        val queue = snapshot.queue.toMutableList()
                        queue.add((snapshot.currentIndex + 1).coerceIn(0, queue.size), enriched)
                        snapshot.copy(queue = queue)
                    }
                    persistPlaybackSession()
                    return@withContext
                }
                val index = (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
                player.addMediaItem(index, enriched.toMediaItem())
                // Under shuffle the insert lands at a random permutation spot;
                // pin it directly after the current track so it truly plays next.
                placeInsertedIndexInShuffleOrder(index, last = false)
            }
        }
    }

    fun addToQueue(track: PlayableTrack) {
        applicationScope.launch {
            val enriched = runCatching { matchMetadata(track) }.getOrDefault(track)
            withContext(Dispatchers.Main.immediate) {
                if (isCasting) {
                    _state.update { it.copy(queue = it.queue + enriched) }
                    persistPlaybackSession()
                } else {
                    player.addMediaItem(enriched.toMediaItem())
                    // Under shuffle the append lands at a random permutation
                    // spot; pin it at the end of the actual play order.
                    placeInsertedIndexInShuffleOrder(player.mediaItemCount - 1, last = true)
                }
            }
        }
    }

    /** Adds the varied continuation loaded for a search-started track.
     * A stale response can never modify a newer playback queue. */
    fun appendSearchRecommendations(seed: PlayableTrack, tracks: List<PlayableTrack>) {
        if (tracks.isEmpty()) return
        onMain {
            val current = player.currentMediaItem?.toPlayableTrack() ?: return@onMain
            val sameSeed = if (!seed.videoId.isNullOrBlank()) {
                seed.videoId == current.videoId
            } else {
                seed.title.equals(current.title, ignoreCase = true) &&
                    seed.artist.equals(current.artist, ignoreCase = true)
            }
            if (!sameSeed || _state.value.sourceLabel != "Search") return@onMain

            val seenQueueKeys = (0 until player.mediaItemCount).mapTo(mutableSetOf()) {
                player.getMediaItemAt(it).toPlayableTrack().queueKey()
            }
            val seenTitles = (0 until player.mediaItemCount).mapTo(mutableSetOf()) {
                player.getMediaItemAt(it).toPlayableTrack().searchQueueTitleKey()
            }
            val fresh = tracks.filter { track ->
                track.title.isNotBlank() && track.artist.isNotBlank() &&
                    seenQueueKeys.add(track.queueKey()) &&
                    seenTitles.add(track.searchQueueTitleKey())
            }
            if (fresh.isEmpty()) return@onMain

            player.addMediaItems(fresh.map(PlayableTrack::toMediaItem))
            refresh(player)
            val currentIndex = player.currentMediaItemIndex
            enrichUpcomingQueue(currentIndex)
            val nextIndex = if (player.shuffleModeEnabled) player.nextMediaItemIndex else currentIndex + 1
            if (nextIndex != C.INDEX_UNSET && nextIndex in 0 until player.mediaItemCount) {
                preloadNextTrack(nextIndex, player.getMediaItemAt(nextIndex).toPlayableTrack())
            }
        }
    }

    fun resume() = onMain {
        exclusiveUsbOutput.setPaused(false)
        if (isCasting) {
            castPlayback?.play()
            return@onMain
        }
        ensureForegroundService()
        if (retryInterruptedPlayback()) return@onMain
        val pendingCurrent = _state.value.current
        if (player.mediaItemCount == 0 && pendingCurrent != null) {
            val q = _state.value.queue.ifEmpty { listOf(pendingCurrent) }
            val idx = _state.value.currentIndex.coerceIn(q.indices)
            startResolvedQueuePlayback(
                tracks = q,
                selectedIndex = idx,
                startPositionMs = _state.value.positionMs,
                sourceLabel = _state.value.sourceLabel,
                endlessDiscover = _state.value.isEndlessQueue,
                startShuffled = _state.value.shuffleEnabled,
            )
            return@onMain
        }
        val currentItem = player.currentMediaItem
        val currentPrepared = currentItem
            ?.localConfiguration
            ?.customCacheKey
            ?.let(preparedStreams::get)
        if (currentItem?.localConfiguration?.uri?.scheme == "lastwave" || currentPrepared?.isExpired() == true) {
            resolveAndPlayQueueItem(player.currentMediaItemIndex)
            return@onMain
        }
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.prepare()
        }
        player.play()
    }

    fun pause() {
        cancelPendingPlaybackResolution()
        onMain {
            exclusiveUsbOutput.setPaused(true)
            if (isCasting) castPlayback?.pause()
            if (playerDelegate.isInitialized()) player.pause()
            _state.update { it.copy(isPlaying = false, isBuffering = false) }
        }
    }

    fun togglePlayPause() = onMain {
        if (isCasting) {
            if (_state.value.isPlaying || _state.value.isBuffering) pause() else resume()
            return@onMain
        }
        if (playRequest?.isActive == true && _state.value.isBuffering) {
            pause()
            return@onMain
        }
        val holdingUsb = exclusiveUsbOutput.isActive() && !exclusiveUsbOutput.isPaused()
        val playing = _state.value.isPlaying ||
            (playerDelegate.isInitialized() && (player.isPlaying || player.playWhenReady)) ||
            holdingUsb
        if (playing) pause() else resume()
    }

    @MainThread
    private fun retryInterruptedPlayback(): Boolean {
        val snapshot = _state.value
        val track = snapshot.current ?: return false
        if (snapshot.error == null || track.playbackUrl != null) return false
        errorRetryCount = 0
        val queue = snapshot.queue.ifEmpty { listOf(track) }
        startResolvedQueuePlayback(
            tracks = queue,
            selectedIndex = snapshot.currentIndex.coerceIn(queue.indices),
            startPositionMs = snapshot.positionMs,
            sourceLabel = snapshot.sourceLabel,
            endlessDiscover = snapshot.isEndlessQueue,
            startShuffled = snapshot.shuffleEnabled,
        )
        return true
    }
    fun seekTo(positionMs: Long) = onMain {
        if (isCasting) {
            castPlayback?.seek(positionMs.coerceAtLeast(0))
            return@onMain
        }
        cancelCrossfade()
        val target = positionMs.coerceAtLeast(0)
        lastSeekTargetMs = target
        lastSeekAtElapsedMs = SystemClock.elapsedRealtime()
        exclusiveUsbOutput.noteSeek(target * 1_000L)
        player.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    /**
     * Masks pre-seek position reads with the seek target while ExoPlayer
     * lands the seek. Self-healing: the window expires on its own and any
     * track change clears it, so a failed seek can only pin the display for
     * [SEEK_SETTLE_WINDOW_MS], never wedge it.
     */
    private fun settleSeekPosition(rawPosMs: Long): Long {
        val target = lastSeekTargetMs
        if (target < 0L) return rawPosMs
        if (SystemClock.elapsedRealtime() - lastSeekAtElapsedMs > SEEK_SETTLE_WINDOW_MS) {
            lastSeekTargetMs = -1L
            return rawPosMs
        }
        return target
    }

    private fun exclusiveAwarePositionMs(fallbackMs: Long): Long {
        if (!exclusiveUsbOutput.isActive()) return fallbackMs
        val us = exclusiveUsbOutput.getCurrentPositionUs()
        if (us == androidx.media3.exoplayer.audio.AudioSink.CURRENT_POSITION_NOT_SET) return fallbackMs
        return (us / 1_000L).coerceAtLeast(0L)
    }

    /**
     * Seek bar clock. ExoPlayer's position stays at 0 in normal playback and
     * at the end in bit-perfect playback, so the bar follows wall time while
     * the track is actually playing, and a seek target when the user scrubs.
     */
    private fun advancePlayhead(playing: Boolean): Long {
        val now = SystemClock.elapsedRealtime()
        val key = _state.value.current?.let { track ->
            track.videoId?.takeIf { it.isNotBlank() } ?: "${track.title}|${track.artist}"
        }
        val seekAge = now - lastSeekAtElapsedMs
        if (lastSeekTargetMs >= 0L && seekAge in 0..SEEK_SETTLE_WINDOW_MS) {
            playheadPosMs = lastSeekTargetMs
            playheadWallMs = now
            playheadKey = key
            playheadMoving = playing
            return playheadPosMs
        }
        if (key != playheadKey) {
            playheadKey = key
            playheadPosMs = 0L
            playheadWallMs = now
            playheadMoving = playing
            return 0L
        }
        if (!playing) {
            if (playheadMoving) {
                playheadPosMs += (now - playheadWallMs).coerceAtLeast(0L)
                playheadMoving = false
            }
            playheadWallMs = now
            return playheadPosMs
        }
        if (!playheadMoving) {
            playheadWallMs = now
            playheadMoving = true
        }
        val pos = playheadPosMs + (now - playheadWallMs).coerceAtLeast(0L)
        val dur = _state.value.durationMs
        return if (dur > 0L) pos.coerceAtMost(dur) else pos
    }

    private fun publishExclusiveDrift() {
        if (exclusiveUsbOutput.isPaused()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSignalPathMs < SIGNAL_PATH_TICK_MS) return
        lastSignalPathMs = now
        val rate = exclusiveUsbOutput.currentRateHz()
        if (rate > 0) {
            healthTracker.sampleExclusive(
                exclusiveUsbOutput.framesWritten(),
                rate,
                now,
                true,
            )
        }
        _signalPath.value = _signalPath.value.copy(
            driftPpm = healthTracker.driftPpm,
            glitchCount = healthTracker.glitchCount,
            isPlaying = _state.value.isPlaying,
        )
    }

    @MainThread
    private fun cancelCrossfade() {
        if (!playerDelegate.isInitialized()) return
        outgoingPlayer = null
        val standby = if (player === secondaryPlayer) playerDelegate.value else secondaryPlayer
        standby?.stop()
        standby?.clearMediaItems()
        standbyQueue = emptyList()
        standbyIndex = C.INDEX_UNSET
        player.volume = 1f
    }

    @MainThread
    private fun updateCrossfade(positionMs: Long): Boolean {
        if (!crossfadeEnabled || bitPerfectEnabled) return false
        outgoingPlayer?.let { outgoing ->
            val progress = (positionMs.toFloat() / overlapDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            if (progress >= 1f || outgoing.playbackState == Player.STATE_ENDED || outgoing.playerError != null) {
                cancelCrossfade()
            } else {
                val angle = progress * (Math.PI / 2.0)
                player.volume = kotlin.math.sin(angle).toFloat()
                outgoing.volume = kotlin.math.cos(angle).toFloat()
                outgoing.playWhenReady = player.isPlaying
            }
            return false
        }
        if (!player.isPlaying || player.repeatMode == Player.REPEAT_MODE_ONE) return false
        // Time the handoff off the TRUE container duration only. A seeded
        // (approximate) duration can undershoot the real end and would fire
        // the handoff early: the old track keeps playing while the new-track
        // state sits frozen at 0:00, then jumps. Unknown duration means no
        // crossfade; the natural advance still works via STATE_ENDED.
        val trueDurationMs = player.duration.takeIf { it > 0L } ?: return false
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex == player.currentMediaItemIndex) return false
        val fadeMs = minOf((crossfadeDurationMs * player.playbackParameters.speed).toLong(), trueDurationMs / 3)
        val remainingMs = trueDurationMs - positionMs
        if (remainingMs <= 0L || remainingMs > fadeMs + 15_000L) return false
        val nextItem = player.getMediaItemAt(nextIndex)
        if (nextItem.localConfiguration?.uri?.scheme == "lastwave") return false
        val stream = nextItem.localConfiguration?.customCacheKey?.let(preparedStreams::get)
        if (stream?.isExpired() == true) return false

        val standby = if (player === secondaryPlayer) playerDelegate.value else {
            secondaryPlayer ?: run {
                val engine = NativeAudioEngine(settingsPreferences, equalizerPreferences, applicationScope)
                val effects = AudioEffectsEngine(equalizerPreferences, settingsPreferences, applicationScope)
                secondaryNativeEngine = engine
                secondaryEffects = effects
                createPlayer({ engine }, effects, false).also { secondaryPlayer = it }
            }
        }
        if (standbyIndex != nextIndex || standbyQueue.size != player.mediaItemCount ||
            standbyQueue.getOrNull(nextIndex) != nextItem
        ) {
            standbyQueue = (0 until player.mediaItemCount).map(player::getMediaItemAt)
            standbyIndex = nextIndex
            standby.volume = 0f
            standby.setAudioAttributes(player.audioAttributes, false)
            standby.pause()
            standby.setMediaItems(standbyQueue, nextIndex, 0L)
            standby.prepare()
        }
        if (remainingMs > fadeMs || standby.playbackState != Player.STATE_READY) return false
        // A queue edit during preparation must never start a stale next track.
        if (standbyQueue.indices.any { standbyQueue[it] != player.getMediaItemAt(it) }) {
            cancelCrossfade()
            return false
        }
        val outgoing = player
        val shuffleOrder = mutableListOf<Int>()
        val timeline = outgoing.currentTimeline
        var index = timeline.getFirstWindowIndex(outgoing.shuffleModeEnabled)
        while (index != C.INDEX_UNSET) {
            shuffleOrder.add(index)
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, outgoing.shuffleModeEnabled)
        }
        standby.setShuffleOrder(DefaultShuffleOrder(shuffleOrder.toIntArray(), Random.nextLong()))
        standby.shuffleModeEnabled = outgoing.shuffleModeEnabled
        standby.repeatMode = outgoing.repeatMode
        standby.playbackParameters = outgoing.playbackParameters
        overlapDurationMs = minOf(fadeMs, remainingMs,
            standby.duration.takeIf { it > 0L }?.div(3) ?: fadeMs).coerceAtLeast(1L)
        outgoing.removeListener(listener)
        outgoing.setAudioAttributes(outgoing.audioAttributes, false)
        outgoing.repeatMode = Player.REPEAT_MODE_OFF
        outgoing.shuffleModeEnabled = false
        outgoing.removeMediaItems(outgoing.currentMediaItemIndex + 1, outgoing.mediaItemCount)
        outgoingPlayer = outgoing
        activePlayer = standby
        standby.addListener(listener)
        standby.setAudioAttributes(standby.audioAttributes, true)
        standby.play()
        listener.onMediaItemTransition(standby.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        refresh(standby)
        return true
    }

    private fun updateBitPerfectState() {
        // Bit-Perfect applies to every stream (Lossless, YouTube Music, local downloads)
        // Completely bypassing native DSP and Android AudioFX processing.
        val effectiveBitPerfect = bitPerfectEnabled
        val primaryOk = runCatching {
            val engine = nativeAudioEngine.get()
            engine.setBitPerfect(effectiveBitPerfect)
            engine.isAvailable && engine.isBitPerfectActive() == effectiveBitPerfect
        }.getOrDefault(false)
        audioEffectsEngine.setBitPerfectActive(effectiveBitPerfect)
        val secondaryOk = secondaryNativeEngine?.let { engine ->
            runCatching {
                engine.setBitPerfect(effectiveBitPerfect)
                engine.isAvailable && engine.isBitPerfectActive() == effectiveBitPerfect
            }.getOrDefault(false)
        } ?: true
        secondaryEffects?.setBitPerfectActive(effectiveBitPerfect)
        nativeBitPerfectApplied = primaryOk && secondaryOk
        android.util.Log.i(
            "MusicPlayer",
            "BIT-PERFECT REQUEST enabled=$effectiveBitPerfect nativeApplied=$nativeBitPerfectApplied " +
                "(primary=$primaryOk secondary=$secondaryOk)",
        )
        // Volume settles here (not only on DAC route/resolve events) so a
        // legacy auto-max restore owed by older builds is settled promptly
        // when the toggle flips, with or without a USB DAC attached.
        manageDacSystemVolume(effectiveBitPerfect)
    }

    /** Forwards USB-access permission requests to [UsbDacMonitor]. */
    fun requestUsbPermission() = usbDacMonitor.requestPermission()

    /** Device key already prompted for USB access (deny = no nagging). */
    private var usbPermissionPromptedKey: String? = null

    /**
     * Asks for direct USB access when Bit-Perfect is on and a USB audio
     * peripheral is present but not yet granted. Once per device: a deny is
     * respected until the DAC is detached (which clears the key via null).
     * No peripheral / already granted / toggle off = silent no-op.
     */
    private fun maybeRequestUsbPermission() {
        if (!bitPerfectEnabled && !usbExclusivePrefEnabled) return
        val dac = usbDacMonitor.state.value.dac ?: run {
            usbPermissionPromptedKey = null
            return
        }
        if (!dac.hasUsbPeripheral || dac.usbPermissionGranted) return
        val key = "${dac.vendorId}:${dac.productId}:${dac.name}"
        if (usbPermissionPromptedKey == key) return
        usbPermissionPromptedKey = key
        runCatching { usbDacMonitor.requestPermission() }
    }

    private fun findOutputDevice(deviceId: Int): AudioDeviceInfo? = runCatching {
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull { it.id == deviceId }
    }.getOrNull()

    private fun currentSourceRateHz(): Int? =
        _state.value.samplingRateKHz?.times(1000.0)?.toInt()?.takeIf { it > 0 }

    private fun dacRateFor(resolved: ResolvedStream): Int? =
        resolved.samplingRateKHz?.times(1000.0)?.toInt()?.takeIf { it > 0 }
            ?: currentSourceRateHz()

    private fun exclusiveUsbWanted(): Boolean {
        if (isCasting) return false
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
        if (!bitPerfectEnabled && !usbExclusivePrefEnabled) return false
        // E-AC-3 JOC / 360 RA is multichannel. Exclusive USB is stereo PCM
        // only — keep Android's decoder+mixer so Atmos actually plays.
        if (isSpatialAudioCodec(_state.value.audioCodec)) return false
        val dac = usbDacMonitor.state.value.dac
        return dac?.hasUsbPeripheral == true && dac.usbPermissionGranted
    }

    /**
     * Routes ExoPlayer output to the USB DAC at the track's native rate.
     * Always active when a DAC is present (it can only improve delivery);
     * the Bit-Perfect toggle decides bypass, volume policy and verdict.
     * The SOURCE rate always drives the output-stream request (44.1/48/
     * 88.2/96/176.4/192/352.8/384 kHz) — never a fixed 48 kHz — so no
     * LastWave resampler runs when the route accepts the source format.
     */
    private fun applyDacRoutingFor(sourceRateHz: Int?) {
        val dac = usbDacMonitor.state.value.dac
        val exclusiveWanted = exclusiveUsbWanted()
        exclusiveUsbOutput.setWanted(exclusiveWanted)
        audioSinks.forEach { sink ->
            sink.setBitPerfectRequested(bitPerfectEnabled || exclusiveWanted)
            sink.syncExclusiveUsb(exclusiveWanted)
        }
        val exclusive = exclusiveUsbOutput.isActive()
        usbExclusiveSinkActive = exclusive
        runCatching { audioEffectsEngine.setUsbExclusiveActive(exclusive) }
        runCatching { secondaryEffects?.setUsbExclusiveActive(exclusive) }
        val device = if (!exclusive && dac != null && dac.deviceId > 0 && (sourceRateHz ?: 0) > 0) {
            findOutputDevice(dac.deviceId)
        } else {
            null
        }
        routedDacDeviceId = device?.id
        audioSinks.forEach { sink ->
            runCatching { sink.setPreferredDevice(if (exclusive) null else device) }
            runCatching { sink.setOutputSampleRateOverride(sourceRateHz) }
        }
        android.util.Log.i(
            "MusicPlayer",
            "BIT-PERFECT OUTPUT REQUEST srcRate=$sourceRateHz " +
                "dac=${dac?.name} routed=${device != null} exclusive=$exclusive " +
                "wanted=$exclusiveWanted bitPerfect=$bitPerfectEnabled",
        )
        usbDacMonitor.setRouteRequested(exclusive || device != null)
        exclusiveUsbOutput.syncListeningGain()
        manageDacSystemVolume(bitPerfectEnabled || exclusiveWanted)
    }

    /**
     * Volume stays workable in Bit-Perfect: the app never forces the system
     * level to MAX, so the keys always do something and there is no ear-blast
     * on engage. Below-unity gain is applied in software on the direct sink
     * path (a granted BIT_PERFECT bypass ignores AudioTrack volume), and the
     * signal-path verdict honestly reports scaled output until unity — MAX
     * (or a hardware-volume route) is still the only bit-exact state.
     * A manual change mid-session is respected and never overwritten.
     *
     * The persisted session below only settles restores owed by older builds
     * that used to auto-max; this build never strands the level at max.
     */
    private fun manageDacSystemVolume(engaged: Boolean) {
        if (engaged) return
        val manager = audioManager ?: return
        if (dacVolumeManaged || persistedVolumeManaged()) {
            dacVolumeManaged = false
            val saved = savedSystemVolume.takeIf { it >= 0 } ?: persistedSavedVolume()
            val max = runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
            val current = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
            if (saved in 0 until max && current == max) {
                runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }
            }
            savedSystemVolume = -1
            persistVolumeSession(saved = -1, managed = false)
        }
    }

    private fun volumeSessionPrefs() =
        appContext.getSharedPreferences("lastwave_bitperfect_volume", Context.MODE_PRIVATE)

    private fun persistedVolumeManaged(): Boolean = runCatching {
        volumeSessionPrefs().getBoolean(KEY_VOLUME_MANAGED, false)
    }.getOrDefault(false)

    private fun persistedSavedVolume(): Int = runCatching {
        volumeSessionPrefs().getInt(KEY_VOLUME_SAVED, -1)
    }.getOrDefault(-1)

    private fun persistVolumeSession(saved: Int, managed: Boolean) {
        runCatching {
            volumeSessionPrefs().edit()
                .putInt(KEY_VOLUME_SAVED, saved)
                .putBoolean(KEY_VOLUME_MANAGED, managed)
                .apply()
        }
    }

    /** Rebuilds the verified signal-path report; main thread (reads player). */
    private fun updateSignalPath() {
        val snapshot = _state.value
        val trackKey = snapshot.current?.let { track ->
            track.videoId?.takeIf { id -> id.isNotBlank() }
                ?: "${track.title}|${track.artist}"
        }
        if (trackKey != lastHealthTrackKey) {
            lastHealthTrackKey = trackKey
            healthTracker.reset()
        }
        val initialized = playerDelegate.isInitialized()
        val exclusive = exclusiveUsbOutput.isActive() || audioSinks.any { sink ->
            runCatching { sink.isExclusiveUsbActive() }.getOrDefault(false)
        }
        val exclusiveRate = exclusiveUsbOutput.currentRateHz()
        val appRateHz = if (exclusive && exclusiveRate > 0) {
            exclusiveRate
        } else {
            audioSinks.firstNotNullOfOrNull { sink ->
                runCatching { sink.currentOutputSampleRateHz() }.getOrNull()?.takeIf { it > 0 }
            } ?: 0
        }
        val sourceRateHz = snapshot.samplingRateKHz?.times(1000.0)?.toInt()?.takeIf { it > 0 }
            ?: appRateHz.takeIf { exclusive && it > 0 }
        val platformRateHz = runCatching { audioManager?.mixerRateHz() }.getOrNull() ?: 0
        val speed = if (initialized) {
            runCatching { player.playbackParameters.speed }.getOrDefault(snapshot.speed)
        } else {
            snapshot.speed
        }
        val playerUnity = if (initialized) {
            runCatching { player.volume == 1f }.getOrDefault(true)
        } else {
            true
        }
        // Effective gain is what the sinks forwarded to AudioTrack: ducking
        // scales it without touching player.volume, so observe the sinks.
        val appVolume = if (initialized) {
            val sinkVolume = audioSinks.minOfOrNull { sink ->
                runCatching { sink.currentVolume() }.getOrDefault(1f)
            } ?: 1f
            minOf(if (playerUnity) 1f else 0f, sinkVolume)
        } else {
            1f
        }
        val sysMax = runCatching {
            audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        }.getOrNull() ?: 0
        val sysVol = runCatching {
            audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC)
        }.getOrNull() ?: 0
        val sysFixed = runCatching {
            audioManager?.isVolumeFixed()
        }.getOrNull() == true
        val dac = usbDacMonitor.state.value.dac
        val srcLabel = snapshot.audioCodec
            ?: if (snapshot.isLossless) "LOSSLESS" else "Audio"
        usbExclusiveSinkActive = exclusive
        val sinkDirect = exclusive || audioSinks.any { sink ->
            runCatching { sink.isBitPerfectBypassActive() }.getOrDefault(false)
        }
        val sinkStale = !exclusive && audioSinks.any { sink ->
            runCatching { sink.isBitPerfectConfigStale() }.getOrDefault(false)
        }
        val dspBypassActuallyActive =
            exclusive || (bitPerfectEnabled && nativeBitPerfectApplied && sinkDirect && !sinkStale)
        val mixerBypassGranted = audioSinks.any {
            runCatching { it.isPlatformBitPerfectConfigured() }.getOrDefault(false)
        }
        val routedRequested = exclusive ||
            (routedDacDeviceId != null && routedDacDeviceId == dac?.deviceId)
        val hardwareVolume = exclusive && exclusiveUsbOutput.usesHardwareVolume()
        val exclusiveAppVolume = if (exclusive && hardwareVolume) {
            1f
        } else if (exclusive) {
            exclusiveUsbOutput.softwareGain()
        } else {
            appVolume
        }
        exclusiveUsbOutput.syncListeningGain()
        _signalPath.value = evaluateSignalPath(
            SignalPathInput(
                sourceLabel = srcLabel,
                sourceRateHz = sourceRateHz,
                sourceBitDepth = snapshot.bitDepth?.takeIf { it > 0 },
                isLossless = snapshot.isLossless,
                appOutputRateHz = appRateHz,
                platformMixerRateHz = platformRateHz,
                platformBitPerfectConfigured = mixerBypassGranted,
                dspBypassEnabled = dspBypassActuallyActive,
                crossfadeMixing = outgoingPlayer != null,
                speed = speed,
                appVolume = exclusiveAppVolume,
                systemVolume = sysVol,
                systemVolumeMax = sysMax,
                systemVolumeFixed = sysFixed || hardwareVolume,
                dac = dac,
                routedToDac = routedRequested,
                routeVerified = exclusive && exclusiveUsbOutput.isClockMatched(),
                driftPpm = healthTracker.driftPpm,
                glitchCount = healthTracker.glitchCount,
                isPlaying = snapshot.isPlaying,
                usbExclusiveActive = exclusive,
                exclusiveClockMatched = exclusive && exclusiveUsbOutput.isClockMatched(),
                exclusiveHardwareVolume = hardwareVolume,
            ),
        )
    }

    fun seekToQueueItem(index: Int) = onMain {
        if (isCasting) {
            val snapshot = _state.value
            if (index in snapshot.queue.indices) {
                _state.value = snapshot.copy(current = snapshot.queue[index], currentIndex = index,
                    positionMs = 0, durationMs = 0, error = null)
                castPlayback?.load(_state.value)
            }
            return@onMain
        }
        val snapshot = _state.value
        // Manual queue jump to a different track: the departing song becomes
        // "previously heard" for shuffle-Previous. previous() navigates via
        // resolveAndPlayQueueItem/playPendingQueueItem directly so popping
        // history never re-records the song we just left.
        if (index in snapshot.queue.indices && index != snapshot.currentIndex) {
            recordHistory(snapshot.current?.mediaIdKey())
        }
        if (index in 0 until player.mediaItemCount) {
            resolveAndPlayQueueItem(index)
        } else {
            playPendingQueueItem(index, _state.value)
        }
    }
    fun previous() = onMain {
        if (isCasting) {
            if (_state.value.positionMs > 5_000) seekTo(0)
            else seekToQueueItem(previousQueueIndex(_state.value))
            return@onMain
        }
        cancelCrossfade()
        val pendingState = _state.value
        if (player.currentPosition > 5_000) {
            player.seekTo(0)
        } else {
            // Under shuffle, ExoPlayer's permutation previous is almost never
            // the song just heard (rebuilt on toggle/handoff/edits) — walk
            // the explicit listening history first.
            val historyIndex = if (pendingState.shuffleEnabled) popHistoryIndex(pendingState) else null
            val index = historyIndex
                ?: player.previousMediaItemIndex.takeIf { it != C.INDEX_UNSET }
                ?: previousQueueIndex(pendingState)
            index.takeIf { it != C.INDEX_UNSET }?.let {
                if (it in 0 until player.mediaItemCount) resolveAndPlayQueueItem(it)
                else playPendingQueueItem(it, pendingState)
            }
        }
    }
    fun next() = onMain {
        if (isCasting) {
            seekToQueueItem(nextQueueIndex(_state.value))
            return@onMain
        }
        val pendingState = _state.value
        recordHistory(pendingState.current?.mediaIdKey())
        val index = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }
            ?: nextQueueIndex(pendingState)
        index.takeIf { it != C.INDEX_UNSET }?.let {
            if (it in 0 until player.mediaItemCount) resolveAndPlayQueueItem(it)
            else playPendingQueueItem(it, pendingState)
        }
    }

    private fun recordHistory(mediaIdKey: String?) {
        if (mediaIdKey.isNullOrBlank()) return
        if (playHistory.lastOrNull() == mediaIdKey) return
        playHistory.addLast(mediaIdKey)
        while (playHistory.size > MAX_PLAY_HISTORY) playHistory.removeFirst()
    }

    /**
     * Newest history entry that still exists in [snapshot]'s queue and isn't
     * the current track, resolved to its present queue index (or null).
     */
    private fun popHistoryIndex(snapshot: MusicPlayerState): Int? {
        val currentKey = snapshot.current?.mediaIdKey()
        while (playHistory.isNotEmpty()) {
            val key = playHistory.removeLast()
            if (key == currentKey) continue
            val index = snapshot.queue.indexOfFirst { it.mediaIdKey() == key }
            if (index >= 0) return index
        }
        return null
    }

    /**
     * Next queue indices in true playback order (shuffle/repeat aware) for
     * queue UI. Must be called on the main thread; falls back to logical
     * queue order when the engine isn't initialized yet.
     */
    fun peekUpcomingIndices(limit: Int = 3): List<Int> {
        val safeLimit = limit.coerceIn(1, 10)
        if (playerDelegate.isInitialized() && !player.currentTimeline.isEmpty) {
            val out = mutableListOf<Int>()
            var next = player.currentTimeline.getNextWindowIndex(
                player.currentMediaItemIndex, player.repeatMode, player.shuffleModeEnabled,
            )
            var guard = 0
            while (next != C.INDEX_UNSET && out.size < safeLimit && guard++ < player.mediaItemCount + 2) {
                if (next == player.currentMediaItemIndex) break
                if (next in 0 until player.mediaItemCount &&
                    player.getMediaItemAt(next).mediaId !in unavailableMediaIds
                ) {
                    out.add(next)
                }
                next = player.currentTimeline.getNextWindowIndex(next, player.repeatMode, player.shuffleModeEnabled)
            }
            return out
        }
        val snapshot = _state.value
        if (snapshot.queue.isEmpty() || snapshot.currentIndex !in snapshot.queue.indices) return emptyList()
        return ((snapshot.currentIndex + 1) until minOf(snapshot.currentIndex + 1 + safeLimit, snapshot.queue.size)).toList()
    }

    /**
     * Under shuffle, an inserted timeline item lands at a random permutation
     * spot — "Play next" wouldn't play next and "Add to queue" wouldn't play
     * last. Splice [index] into the live shuffle permutation instead:
     * [last] = false puts it directly after the current track, true appends
     * it at the end of the play order. Main thread only.
     */
    @MainThread
    private fun placeInsertedIndexInShuffleOrder(index: Int, last: Boolean) {
        if (!player.shuffleModeEnabled) return
        val timeline = player.currentTimeline
        if (timeline.isEmpty || index !in 0 until player.mediaItemCount) return
        val order = mutableListOf<Int>()
        var cursor = timeline.getFirstWindowIndex(true)
        var guard = 0
        while (cursor != C.INDEX_UNSET && guard++ < player.mediaItemCount + 1) {
            order.add(cursor)
            cursor = timeline.getNextWindowIndex(cursor, Player.REPEAT_MODE_OFF, true)
        }
        if (index !in order) return
        order.remove(index)
        if (last) {
            order.add(index)
        } else {
            val at = (order.indexOf(player.currentMediaItemIndex) + 1).coerceIn(0, order.size)
            order.add(at, index)
        }
        player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), Random.nextLong()))
    }

    private fun nextQueueIndex(state: MusicPlayerState): Int {
        val queue = state.queue
        if (queue.isEmpty()) return C.INDEX_UNSET
        val start = (state.currentIndex + 1).coerceAtLeast(0)
        val ordered = if (state.shuffleEnabled) queue.indices.shuffled() else {
            (start until queue.size) + if (state.repeatMode == Player.REPEAT_MODE_ALL) (0 until start) else emptyList()
        }
        return ordered.firstOrNull { it != state.currentIndex && queue[it].mediaIdKey() !in unavailableMediaIds }
            ?: C.INDEX_UNSET
    }

    private fun previousQueueIndex(state: MusicPlayerState): Int {
        val queue = state.queue
        if (queue.isEmpty()) return C.INDEX_UNSET
        val start = state.currentIndex - 1
        val ordered = (start downTo 0) + if (state.repeatMode == Player.REPEAT_MODE_ALL) (queue.lastIndex downTo 0) else emptyList()
        return ordered.firstOrNull { queue[it].mediaIdKey() !in unavailableMediaIds }
            ?: C.INDEX_UNSET
    }

    @MainThread
    private fun playPendingQueueItem(index: Int, pendingState: MusicPlayerState) {
        if (index !in pendingState.queue.indices) return
        if (index in 0 until player.mediaItemCount) {
            resolveAndPlayQueueItem(index)
        } else {
            startResolvedQueuePlayback(
                tracks = pendingState.queue,
                selectedIndex = index,
                startPositionMs = 0L,
                sourceLabel = pendingState.sourceLabel,
                endlessDiscover = pendingState.isEndlessQueue,
                startShuffled = pendingState.shuffleEnabled,
            )
        }
    }

    @MainThread
    private fun resolveAndPlayQueueItem(index: Int) {
        if (index !in 0 until player.mediaItemCount) {
            playPendingQueueItem(index, _state.value)
            return
        }
        cancelCrossfade()
        ensureForegroundService()
        val generation = playRequestGeneration.incrementAndGet()
        playRequest?.cancel()
        preloadJob?.cancel()
        unavailableSkipJob?.cancel()
        unavailableSkipJob = null
        val mediaItem = player.getMediaItemAt(index)
        val prepared = mediaItem.localConfiguration?.customCacheKey?.let(preparedStreams::get)
        if (mediaItem.localConfiguration?.uri?.scheme != "lastwave" && prepared?.isExpired() != true) {
            // Already resolved: publish quality synchronously so the badge is
            // correct from the first frame (no transition may fire for a
            // same-item play to republish it later).
            mediaItem.localConfiguration?.customCacheKey
                ?.let(preparedStreams::get)
                ?.let(::publishResolvedQuality)
            player.seekToDefaultPosition(index)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
            preloadNextQueueItem(index)
            return
        }

        val track = mediaItem.toPlayableTrack()
        val expectedMediaId = mediaItem.mediaId
        resolvingMediaIds[expectedMediaId] = generation
        // Screen-off continuity: do NOT player.pause() here. Pausing drops
        // ExoPlayer's WAKE_MODE_NETWORK wake/wifi lock and lets refresh()
        // flip state to not-playing, which releases the service WifiLock —
        // then a locked-screen resolve stalls until unlock. The placeholder
        // at [index] has no audible output yet, and when jumping from a
        // different playing index keeping playback running avoids a silent
        // gap; the seek below cuts over once the lossless-first stream is
        // ready.
        _state.update {
            it.copy(
                current = track,
                currentIndex = index,
                positionMs = 0L,
                bufferedPositionMs = 0L,
                durationMs = 0L,
                isPlaying = true,
                isBuffering = true,
                error = null,
                // New track owns its badge: clear quality so the pill never
                // shows the previous song's format, and never lets a stale
                // explicit badge shield a generic publish via the
                // same-track guard in publishResolvedQuality.
                audioCodec = null,
                bitrateKbps = null,
                isLossless = false,
                bitDepth = null,
                samplingRateKHz = null,
            )
        }
        playRequest = applicationScope.launch(Dispatchers.IO) {
            try {
                val resolved = resolveTrackAudioStreamWithRetry(
                    track = track,
                    videoId = track.videoId,
                    allowLossless = expectedMediaId !in losslessBypassMediaIds,
                )
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.Main.immediate) {
                    if (generation != playRequestGeneration.get() ||
                        index !in 0 until player.mediaItemCount ||
                        player.getMediaItemAt(index).mediaId != expectedMediaId
                    ) {
                        return@withContext
                    }
                    registerPreparedStream(resolved)
                    publishResolvedQuality(resolved)
                    applyDacRoutingFor(dacRateFor(resolved))
                    logStreamEvent("queue-prepare", resolved, retry = 0)
                    cacheCurrentTrackStream(resolved)
                    player.replaceMediaItem(index, track.toMediaItem(resolved))
                    player.seekToDefaultPosition(index)
                    player.prepare()
                    player.play()
                    enrichUpcomingQueue(index)
                    extendDiscoverQueueIfNeeded(index)
                    preloadNextQueueItem(index)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                logResolutionFailure(track, "queue-resolve", 0, error)
                if (expectedMediaId !in losslessBypassMediaIds) {
                    losslessBypassMediaIds += expectedMediaId
                    val ytFallback = try {
                        resolveYoutubeTrackAudioStream(track, track.videoId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        null
                    }
                    if (ytFallback != null && generation == playRequestGeneration.get()) {
                        withContext(Dispatchers.Main.immediate) {
                            if (generation != playRequestGeneration.get() ||
                                index !in 0 until player.mediaItemCount ||
                                player.getMediaItemAt(index).mediaId != expectedMediaId
                            ) {
                                return@withContext
                            }
                            registerPreparedStream(ytFallback)
                            publishResolvedQuality(ytFallback)
                            applyDacRoutingFor(dacRateFor(ytFallback))
                            logStreamEvent("queue-prepare-yt-fallback", ytFallback, retry = 0)
                            cacheCurrentTrackStream(ytFallback)
                            player.replaceMediaItem(index, track.toMediaItem(ytFallback))
                            player.seekToDefaultPosition(index)
                            player.prepare()
                            player.play()
                            enrichUpcomingQueue(index)
                            extendDiscoverQueueIfNeeded(index)
                            preloadNextQueueItem(index)
                        }
                        return@launch
                    }
                }
                withContext(Dispatchers.Main.immediate) {
                    if (generation == playRequestGeneration.get()) {
                        _state.update {
                            it.copy(
                                isPlaying = false,
                                isBuffering = false,
                                error = error.message ?: "Unable to resolve audio",
                            )
                        }
                        scheduleUnavailableMediaSkip(index, expectedMediaId, generation, error, allowAutoSkip = true)
                    }
                }
            } finally {
                resolvingMediaIds.remove(expectedMediaId, generation)
            }
        }
    }

    private fun cancelPendingPlaybackResolution() {
        playRequestGeneration.incrementAndGet()
        playRequest?.cancel()
        playRequest = null
        preloadJob?.cancel()
        preloadJob = null
        currentTrackCacheJob?.cancel()
        currentTrackCacheJob = null
        unavailableSkipJob?.cancel()
        unavailableSkipJob = null
    }

    fun toggleShuffle() = setShuffleEnabled(!state.value.shuffleEnabled)

    fun setShuffleEnabled(enabled: Boolean) = onMain {
        if (isCasting) {
            _state.update { it.copy(shuffleEnabled = enabled) }
            persistPlaybackSession()
            return@onMain
        }
        if (player.shuffleModeEnabled == enabled) return@onMain
        cancelCrossfade()
        player.shuffleModeEnabled = enabled
        preloadNextQueueItem(player.currentMediaItemIndex)
        _state.update { it.copy(shuffleEnabled = enabled) }
        persistPlaybackSession()
    }

    fun cycleRepeatMode() = setRepeatMode(
        when (state.value.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        },
    )

    fun setRepeatMode(mode: Int) = onMain {
        val supportedMode = when (mode) {
            Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL -> mode
            else -> Player.REPEAT_MODE_OFF
        }
        if (isCasting) {
            _state.update { it.copy(repeatMode = supportedMode) }
            persistPlaybackSession()
            return@onMain
        }
        if (player.repeatMode == supportedMode) return@onMain
        player.repeatMode = supportedMode
        _state.update { it.copy(repeatMode = supportedMode) }
        persistPlaybackSession()
    }
    fun cycleSpeed() = onMain {
        if (isCasting) {
            val next = when {
                _state.value.speed < 1f -> 1f
                _state.value.speed < 1.25f -> 1.25f
                _state.value.speed < 1.5f -> 1.5f
                _state.value.speed < 2f -> 2f
                else -> 0.75f
            }
            castPlayback?.setSpeed(next)
            return@onMain
        }
        cancelCrossfade()
        val next = when {
            player.playbackParameters.speed < 1f -> 1f
            player.playbackParameters.speed < 1.25f -> 1.25f
            player.playbackParameters.speed < 1.5f -> 1.5f
            player.playbackParameters.speed < 2f -> 2f
            else -> 0.75f
        }
        player.setPlaybackSpeed(next)
    }
    fun cycleSleepTimer() = onMain {
        setSleepTimerMinutes(SLEEP_TIMER_MINUTES[(sleepTimerStep + 1) % SLEEP_TIMER_MINUTES.size])
    }

    fun setSleepTimerMinutes(minutes: Int) = onMain {
        if (minutes < 0) return@onMain
        sleepTimerStep = SLEEP_TIMER_MINUTES.indexOf(minutes).coerceAtLeast(0)
        sleepTimerDeadlineMs = minutes.takeIf { it > 0 }
            ?.let { SystemClock.elapsedRealtime() + it * 60_000L }
        _state.update {
            it.copy(sleepTimerRemainingMs = sleepTimerDeadlineMs?.minus(SystemClock.elapsedRealtime()))
        }
    }
    fun clearUpcoming() = onMain {
        if (isCasting) {
            _state.update { it.copy(queue = it.queue.take(it.currentIndex + 1), isEndlessQueue = false) }
            persistPlaybackSession()
            return@onMain
        }
        cancelCrossfade()
        disableDiscoverQueue()
        disableRadioQueue()
        val current = player.currentMediaItemIndex
        if (current >= 0 && current + 1 < player.mediaItemCount) {
            player.removeMediaItems(current + 1, player.mediaItemCount)
        }
    }
    /**
     * Stops playback and tears down the service.
     *
     * @param clearSession When true (the default — matches every existing
     *   caller's prior behavior), the persisted queue/track in
     *   SharedPreferences is wiped along with the in-memory state, so the
     *   next launch starts with no player. Pass false when the stop is
     *   incidental (e.g. the task was swiped from Recents while nothing was
     *   playing) and the last session should still be restorable the next
     *   time the app opens.
     */
    fun stopAndClear(clearSession: Boolean = true) = onMain {
        if (isCasting) castPlayback?.disconnect()
        cancelCrossfade()
        resolutionRequests.values.forEach { it.second.cancel() }
        resolutionRequests.clear()
        cancelPendingPlaybackResolution()
        queueEnrichmentJob?.cancel()
        disableDiscoverQueue()
        disableRadioQueue()
        unavailableSkipJob?.cancel()
        sleepTimerDeadlineMs = null
        sleepTimerStep = 0
        player.stop()
        player.clearMediaItems()
        preparedStreams.clear()
        _state.value = MusicPlayerState()
        if (clearSession) clearPersistedPlaybackSession()
        applicationScope.launch(Dispatchers.IO) { WidgetUpdater.clear(appContext) }
        appContext.stopService(Intent(appContext, MusicPlaybackService::class.java))
    }
    fun removeQueueItem(index: Int) = onMain {
        if (isCasting) {
            val snapshot = _state.value
            if (index !in snapshot.queue.indices) return@onMain
            val queue = snapshot.queue.toMutableList().apply { removeAt(index) }
            if (queue.isEmpty()) {
                stopAndClear()
                return@onMain
            }
            val currentIndex = (snapshot.currentIndex - if (index < snapshot.currentIndex) 1 else 0)
                .coerceIn(queue.indices)
            _state.value = snapshot.copy(queue = queue, currentIndex = currentIndex, current = queue[currentIndex])
            if (index == snapshot.currentIndex) seekToQueueItem(currentIndex)
            persistPlaybackSession()
            return@onMain
        }
        cancelCrossfade()
        if (index in 0 until player.mediaItemCount) player.removeMediaItem(index)
    }
    fun moveQueueItem(fromIndex: Int, toIndex: Int) = onMain {
        if (fromIndex == toIndex) return@onMain
        if (isCasting) {
            val snapshot = _state.value
            if (fromIndex !in snapshot.queue.indices || toIndex !in snapshot.queue.indices) return@onMain
            val queue = snapshot.queue.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
            val currentIndex = when (snapshot.currentIndex) {
                fromIndex -> toIndex
                in minOf(fromIndex, toIndex)..maxOf(fromIndex, toIndex) ->
                    if (fromIndex < toIndex) snapshot.currentIndex - 1 else snapshot.currentIndex + 1
                else -> snapshot.currentIndex
            }.coerceIn(queue.indices)
            _state.value = snapshot.copy(queue = queue, currentIndex = currentIndex, current = queue[currentIndex])
            persistPlaybackSession()
            return@onMain
        }
        if (fromIndex in 0 until player.mediaItemCount && toIndex in 0 until player.mediaItemCount) {
            player.moveMediaItem(fromIndex, toIndex)
        }
    }
    fun clearError() = _state.update { it.copy(error = null) }
    fun retry() = onMain {
        val snapshot = _state.value
        val currentTrack = snapshot.current ?: return@onMain
        val queue = snapshot.queue.ifEmpty { listOf(currentTrack) }
        unavailableMediaIds.clear()
        errorRetryCount = 0
        resolutionRequests.clear()
        startResolvedQueuePlayback(
            tracks = queue,
            selectedIndex = snapshot.currentIndex.coerceIn(queue.indices),
            startPositionMs = snapshot.positionMs,
            sourceLabel = snapshot.sourceLabel,
            endlessDiscover = snapshot.isEndlessQueue,
            startShuffled = snapshot.shuffleEnabled,
        )
    }

    /**
     * Keeps Last.fm's canonical display naming while attaching the exact
     * YouTube Music identity, album and high-resolution catalog artwork.
     */
    private suspend fun matchMetadata(track: PlayableTrack): PlayableTrack {
        if (!track.videoId.isNullOrBlank() && !track.artworkUrl.isNullOrBlank()) return track
        track.videoId?.takeIf(String::isNotBlank)?.let { videoId ->
            return track.copy(
                artworkUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            )
        }
        val match = innerTube.findBestMatch(track.title, track.artist, prefetchStreams = false)
        return track.copy(
            title = track.title.ifBlank { match.title },
            artist = track.artist.ifBlank { match.artist },
            album = track.album?.takeIf(String::isNotBlank) ?: match.album,
            artworkUrl = match.artworkUrl?.takeIf(String::isNotBlank)
                ?: track.artworkUrl?.takeIf(String::isNotBlank),
            videoId = track.videoId ?: match.videoId,
        )
    }

    private fun warmArtwork(track: PlayableTrack) {
        val url = track.withYoutubeArtwork().artworkUrl?.takeIf(String::isNotBlank) ?: return
        appContext.imageLoader.enqueue(ImageRequest.Builder(appContext).data(url).size(512).build())
    }

    private fun enrichUpcomingQueue(currentIndex: Int) {
        queueEnrichmentJob?.cancel()
        queueEnrichmentJob = applicationScope.launch {
            val targetIndices = withContext(Dispatchers.Main.immediate) {
                val list = mutableListOf<Int>()
                for (i in (currentIndex + 1) until minOf(currentIndex + 3, player.mediaItemCount)) {
                    list.add(i)
                }
                if (player.shuffleModeEnabled) {
                    val next = player.nextMediaItemIndex
                    if (next != C.INDEX_UNSET && next !in list && next in 0 until player.mediaItemCount) {
                        list.add(0, next)
                    }
                }
                list
            }
            data class PendingEnrich(val index: Int, val original: PlayableTrack, val expectedMediaId: String)
            val pending = targetIndices.mapNotNull { index ->
                val original = withContext(Dispatchers.Main.immediate) {
                    if (index >= player.mediaItemCount) null else player.getMediaItemAt(index).toPlayableTrack()
                } ?: return@mapNotNull null
                if (original.playbackUrl != null || (!original.videoId.isNullOrBlank() && !original.artworkUrl.isNullOrBlank())) return@mapNotNull null
                PendingEnrich(
                    index = index,
                    original = original,
                    expectedMediaId = original.videoId ?: "query:${original.artist.lowercase()}|${original.title.lowercase()}",
                )
            }
            if (pending.isEmpty()) return@launch
            coroutineScope {
                pending.forEach { item ->
                    launch(Dispatchers.IO) {
                        val enriched = try {
                            matchMetadata(item.original)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            return@launch
                        }
                        val expectedMediaId = item.expectedMediaId
                        val index = item.index
                        withContext(Dispatchers.Main.immediate) {
                            val queuedItem = if (index in 0 until player.mediaItemCount) player.getMediaItemAt(index) else null
                            if (index != player.currentMediaItemIndex && queuedItem?.mediaId == expectedMediaId) {
                                val prepared = queuedItem.localConfiguration
                                    ?.customCacheKey
                                    ?.let(preparedStreams::get)
                                    ?.takeUnless { it.isExpired() }
                                    ?.takeIf { stream ->
                                        val streamVideoId = stream.youtubeCandidate?.videoId
                                        streamVideoId == null || enriched.videoId == null || streamVideoId == enriched.videoId
                                    }
                                player.replaceMediaItem(index, enriched.toMediaItem(prepared))
                            }
                        }
                    }
                }
            }
        }
    }


    /**
     * Pre-resolves a useful opening window of the upcoming track into the disk
     * cache. This reduces transition stalls without downloading the full track
     * or competing indefinitely with current playback.
     */
    @MainThread
    private fun preloadNextQueueItem(currentIndex: Int) {
        val nextIndex = if (player.shuffleModeEnabled) player.nextMediaItemIndex else currentIndex + 1
        if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until player.mediaItemCount) return
        val nextItem = player.getMediaItemAt(nextIndex)
        if (nextItem.localConfiguration?.uri?.scheme != "lastwave") return
        preloadNextTrack(nextIndex, nextItem.toPlayableTrack())
        // Desktop-style +2 neighbor prefetch
        val nextNext = nextIndex + 1
        if (nextNext in 0 until player.mediaItemCount) {
            val nn = player.getMediaItemAt(nextNext)
            if (nn.localConfiguration?.uri?.scheme == "lastwave") {
                preloadNeighborTrack(nextNext, nn.toPlayableTrack())
            }
        }
    }

    private var neighborPreloadJob: Job? = null

    /** Prefetch +2 neighbor (fire-and-forget, no byte cache). */
    private fun preloadNeighborTrack(index: Int, track: PlayableTrack?) {
        if (track == null || track.playbackUrl != null) return
        warmArtwork(track)
        val key = track.queueKey()
        neighborPreloadJob?.cancel()
        neighborPreloadJob = applicationScope.launch(Dispatchers.IO) {
            delay(NEXT_TRACK_PREFETCH_DELAY_MS * 2) // slightly after +1
            if (!_state.value.isPlaying) return@launch
            val resolved = runCatching {
                resolveTrackAudioStreamWithRetry(track, track.videoId, allowLossless = true)
            }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main.immediate) {
                val q = (if (index in 0 until player.mediaItemCount) player.getMediaItemAt(index).toPlayableTrack() else null)
                    ?: return@withContext
                if (q.queueKey() != key || index == player.currentMediaItemIndex) return@withContext
                registerPreparedStream(resolved)
                player.replaceMediaItem(index, q.toMediaItem(resolved))
            }
        }
    }

    private fun preloadNextTrack(nextIndex: Int, nextTrack: PlayableTrack?) {
        if (nextTrack == null) return
        if (nextTrack.playbackUrl != null) return
        warmArtwork(nextTrack)
        val expectedQueueKey = nextTrack.queueKey()
        preloadJob?.cancel()
        preloadJob = applicationScope.launch(Dispatchers.IO) {
            delay(NEXT_TRACK_PREFETCH_DELAY_MS)
            if (!_state.value.isPlaying) return@launch
            // WithRetry acquires the resolution wake lock so a locked screen
            // can't stall the next-track resolve, and retries once on
            // transient IO (4.0.0 behavior). Still lossless-first.
            val resolved = runCatching {
                resolveTrackAudioStreamWithRetry(nextTrack, nextTrack.videoId, allowLossless = true)
            }.onFailure { logResolutionFailure(nextTrack, "next-preload", 0, it) }
                .getOrNull() ?: return@launch

            val installed = withContext(Dispatchers.Main.immediate) {
                val queuedTrack = (if (nextIndex in 0 until player.mediaItemCount) {
                    player.getMediaItemAt(nextIndex).toPlayableTrack()
                } else null) ?: return@withContext false
                if (queuedTrack.queueKey() != expectedQueueKey || nextIndex == player.currentMediaItemIndex) {
                    return@withContext false
                }
                val resolvedVideoId = resolved.youtubeCandidate?.videoId
                if (resolvedVideoId != null && queuedTrack.videoId != null &&
                    resolvedVideoId != queuedTrack.videoId
                ) {
                    return@withContext false
                }
                registerPreparedStream(resolved)
                player.replaceMediaItem(nextIndex, queuedTrack.toMediaItem(resolved))
                logStreamEvent("next-prepared", resolved, retry = 0)
                true
            }
            if (!installed) return@launch

            val dataSpec = DataSpec.Builder()
                .setUri(Uri.parse(resolved.url))
                .setPosition(0)
                .setLength(NEXT_TRACK_PREFETCH_BYTES)
                .setKey(resolved.cacheKey)
                .build()
                .withRequestHeaders(resolved.requestHeaders)

            runCatching {
                val cacheWriter = CacheWriter(
                    cacheDataSourceFactory.createDataSource(),
                    dataSpec,
                    null,
                    null,
                )
                val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                    if (cause is CancellationException) cacheWriter.cancel()
                }
                try {
                    cacheWriter.cache()
                } finally {
                    cancellationHandle?.dispose()
                }
            }.onFailure { logResolutionFailure(nextTrack, "next-cache", 0, it) }
        }
    }

    /**
     * Progressively caches the current stream ahead of playback under one
     * stable [ResolvedStream.cacheKey].
     *
     * Startup stays streaming-immediate: playback is prepared/started first
     * and this job begins only after [CURRENT_TRACK_CACHE_START_DELAY_MS],
     * then fills bounded [CURRENT_TRACK_CACHE_CHUNK_BYTES] windows with
     * [CURRENT_TRACK_CACHE_CHUNK_DELAY_MS] yields so it never competes as a
     * full-track predownload. Playback reads go through the same
     * CacheDataSource key (see [createPlayer]'s ResolvingDataSource), so
     * backward seeks hit ranges ExoPlayer already buffered and forward seeks
     * increasingly hit progressively cached ranges locally. YouTube fallback
     * resolution/playback is untouched; this job only adds cache.
     */
    private fun cacheCurrentTrackStream(stream: ResolvedStream?) {
        currentTrackCacheJob?.cancel()
        if (stream == null) return
        val uri = Uri.parse(stream.url)
        if (uri.scheme !in setOf("http", "https")) return
        val cacheKey = stream.cacheKey
        val requestHeaders = stream.requestHeaders
        currentTrackCacheJob = applicationScope.launch(Dispatchers.IO) {
            // Let ExoPlayer open the stream and buffer the opening window
            // first; background caching must never delay audibility.
            delay(CURRENT_TRACK_CACHE_START_DELAY_MS)
            if (!_state.value.isPlaying) return@launch
            currentCoroutineContext().ensureActive()
            var offset = 0L
            while (isActive) {
                currentCoroutineContext().ensureActive()
                // Skip windows ExoPlayer already cached while playing so we
                // extend ahead of playback instead of re-downloading it.
                var skippedWindows = 0
                while (isActive && skippedWindows < CURRENT_TRACK_CACHE_MAX_SKIP_WINDOWS &&
                    runCatching { mediaCache.isCached(cacheKey, offset, CURRENT_TRACK_CACHE_CHUNK_BYTES) }.getOrDefault(false)
                ) {
                    offset += CURRENT_TRACK_CACHE_CHUNK_BYTES
                    skippedWindows++
                    if (offset >= CURRENT_TRACK_CACHE_MAX_BYTES) return@launch
                }
                if (offset >= CURRENT_TRACK_CACHE_MAX_BYTES) return@launch
                currentCoroutineContext().ensureActive()
                val dataSpec = DataSpec.Builder()
                    .setUri(uri)
                    .setPosition(offset)
                    .setLength(CURRENT_TRACK_CACHE_CHUNK_BYTES)
                    .setKey(cacheKey)
                    .build()
                    .withRequestHeaders(requestHeaders)
                try {
                    cacheSingleChunk(dataSpec)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // Upstream range failure (e.g. rotated signed URL) must
                    // never break playback; ExoPlayer keeps streaming and the
                    // next resolve restarts this job with the same stable key.
                    break
                }
                offset += CURRENT_TRACK_CACHE_CHUNK_BYTES
                if (offset >= CURRENT_TRACK_CACHE_MAX_BYTES) break
                delay(CURRENT_TRACK_CACHE_CHUNK_DELAY_MS)
            }
        }
    }

    /** Caches one bounded [dataSpec] window; cancellable via the parent job. */
    private suspend fun cacheSingleChunk(dataSpec: DataSpec) {
        val cacheWriter = CacheWriter(
            cacheDataSourceFactory.createDataSource(),
            dataSpec,
            null,
            null,
        )
        val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) cacheWriter.cancel()
        }
        try {
            cacheWriter.cache()
        } finally {
            cancellationHandle?.dispose()
        }
    }

    /** Keeps a Discover-started queue supplied before its loaded tail is reached. */
    private fun extendDiscoverQueueIfNeeded(currentIndex: Int) {
        if (!discoverQueueActive || discoverQueueLoadJob?.isActive == true) return
        discoverQueueLoadJob = applicationScope.launch {
            try {
                val shouldLoad = withContext(Dispatchers.Main.immediate) {
                    discoverQueueActive &&
                        currentIndex >= 0 &&
                        player.mediaItemCount - currentIndex - 1 <= DISCOVER_QUEUE_REFILL_THRESHOLD
                }
                if (!shouldLoad) return@launch

                val batch = runCatching {
                    discoverRepository.nextBatch(DISCOVER_QUEUE_BATCH_SIZE)
                }.onFailure { error ->
                    android.util.Log.d("MusicPlayer", "Discover queue refill failed", error)
                }.getOrDefault(emptyList())
                appendMissingDiscoverTracks(batch.map(GeneratedTrack::toPlayableTrack))
            } finally {
                discoverQueueLoadJob = null
            }
        }
    }

    private fun appendMissingDiscoverTracks(tracks: List<PlayableTrack>) {
        if (tracks.isEmpty()) return
        onMain {
            val known = (0 until player.mediaItemCount).map {
                player.getMediaItemAt(it).toPlayableTrack().queueKey()
            }.toSet()
            val fresh = tracks.filterNot { it.queueKey() in known }
            if (fresh.isNotEmpty()) {
                player.addMediaItems(fresh.map(PlayableTrack::toMediaItem))
            }
        }
    }

    private fun disableDiscoverQueue() {
        discoverQueueActive = false
        discoverQueueLoadJob?.cancel()
        discoverQueueLoadJob = null
    }

    private fun disableRadioQueue() {
        radioQueueActive = false
        radioQueueLoadJob?.cancel()
        radioQueueLoadJob = null
        radioUsedSeeds.clear()
    }

    private fun isDisallowedRadioTitle(titleLower: String): Boolean {
        val keywords = listOf(
            "mashup", "mash up", "mash-up",
            "jukebox", "juke box",
            "mega mix", "megamix",
            "non stop", "nonstop", "non-stop",
            "all songs", "top songs", "audio jukebox",
            "full album", "full songs", "compilation",
            "slowed + reverb", "slowed and reverb", "slowed reverb",
            "bass boosted", "8d audio",
        )
        return keywords.any { titleLower.contains(it) }
    }

    private fun isSameCoreSong(titleLower: String, seedTitleLower: String): Boolean {
        if (titleLower == seedTitleLower) return true
        val cleanTitle = titleLower.replace(Regex("[^a-zA-Z0-9 ]"), "").trim()
        val cleanSeed = seedTitleLower.replace(Regex("[^a-zA-Z0-9 ]"), "").trim()
        if (cleanTitle == cleanSeed) return true
        if (cleanTitle.startsWith(cleanSeed) &&
            (cleanTitle.contains("remix") || cleanTitle.contains("lofi") ||
                cleanTitle.contains("version") || cleanTitle.contains("cover") ||
                cleanTitle.contains("reprise") || cleanTitle.contains("acoustic"))
        ) {
            return true
        }
        return false
    }

    private fun startRadioQueue(seed: PlayableTrack) {
        radioQueueLoadJob?.cancel()
        radioUsedSeeds.clear()
        seed.videoId?.takeIf(String::isNotBlank)?.let { radioUsedSeeds.add(it) }

        radioQueueLoadJob = applicationScope.launch(Dispatchers.IO) {
            try {
                val seedVideoId = seed.videoId?.takeIf(String::isNotBlank)
                    ?: innerTube.findBestMatchOrNull(seed.title, seed.artist, prefetchStreams = false)?.videoId
                    ?: runCatching { innerTube.fetchCharts().firstOrNull()?.videoId }.getOrNull()
                    ?: return@launch

                radioUsedSeeds.add(seedVideoId)
                // Smoothly handles both YouTube Music connected and
                // accountless states: fetchRelatedSongs works with or without
                // cookies (InnerTube falls back to anonymous). When it comes
                // back empty (offline / guest with no seed match), public
                // charts + home songs keep the endless queue alive.
                val relatedPrimary = runCatching {
                    innerTube.fetchRelatedSongs(seedVideoId, limit = RADIO_QUEUE_BATCH_SIZE, prefetchStreams = false)
                }.getOrDefault(emptyList())
                val related = relatedPrimary.ifEmpty {
                    if (!radioQueueActive) return@launch
                    runCatching { innerTube.fetchCharts().take(RADIO_QUEUE_BATCH_SIZE) }
                        .getOrDefault(emptyList())
                        .ifEmpty {
                            runCatching { innerTube.fetchHomeSongs().take(RADIO_QUEUE_BATCH_SIZE) }
                                .getOrDefault(emptyList())
                        }
                }
                if (related.isEmpty() || !radioQueueActive) return@launch

                val seedTitleLower = seed.title.trim().lowercase()
                val seedArtistLower = seed.artist.trim().lowercase()

                val fresh = related.mapNotNull { yt ->
                    val title = yt.title.trim()
                    val titleLower = title.lowercase()
                    val artist = yt.artist.trim()
                    val artistLower = artist.lowercase()

                    val isSameTrack = yt.videoId == seedVideoId ||
                        (titleLower == seedTitleLower && artistLower == seedArtistLower) ||
                        isSameCoreSong(titleLower, seedTitleLower)
                    val isJunk = isDisallowedRadioTitle(titleLower)

                    if (isSameTrack || isJunk || title.isBlank() || artist.isBlank()) {
                        null
                    } else {
                        PlayableTrack(
                            title = title,
                            artist = artist,
                            album = yt.album,
                            artworkUrl = yt.artworkUrl,
                            videoId = yt.videoId,
                        )
                    }
                }

                if (fresh.isEmpty() || !radioQueueActive) return@launch

                withContext(Dispatchers.Main.immediate) {
                    if (!radioQueueActive || !playerDelegate.isInitialized()) return@withContext
                    val current = player.currentMediaItem?.toPlayableTrack()
                    val stillCurrentSeed = current?.videoId == seedVideoId ||
                        (current?.title.equals(seed.title, ignoreCase = true) && current?.artist.equals(seed.artist, ignoreCase = true))
                    if (!stillCurrentSeed) return@withContext

                    val existingVideoIds = (0 until player.mediaItemCount).mapNotNullTo(mutableSetOf()) {
                        player.getMediaItemAt(it).toPlayableTrack().videoId
                    }
                    val existingKeys = (0 until player.mediaItemCount).mapTo(mutableSetOf()) {
                        player.getMediaItemAt(it).toPlayableTrack().queueKey()
                    }

                    val toAdd = fresh.filter {
                        (it.videoId == null || it.videoId !in existingVideoIds) && it.queueKey() !in existingKeys
                    }

                    if (toAdd.isNotEmpty()) {
                        player.addMediaItems(toAdd.map(PlayableTrack::toMediaItem))
                        refresh(player)
                        _state.update { it.copy(isEndlessQueue = true) }
                        enrichUpcomingQueue(player.currentMediaItemIndex)
                        val nextIndex = player.currentMediaItemIndex + 1
                        if (nextIndex in 0 until player.mediaItemCount) {
                            preloadNextTrack(nextIndex, player.getMediaItemAt(nextIndex).toPlayableTrack())
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                android.util.Log.d("MusicPlayer", "Failed to start radio queue", e)
            }
        }
    }

    private fun extendRadioQueueIfNeeded(currentIndex: Int) {
        if (!radioQueueActive || radioQueueLoadJob?.isActive == true) return
        val currentCount = player.mediaItemCount
        if (currentIndex < 0 || currentCount - currentIndex - 1 > RADIO_QUEUE_REFILL_THRESHOLD) return

        radioQueueLoadJob = applicationScope.launch(Dispatchers.IO) {
            try {
                val currentQueue = withContext(Dispatchers.Main.immediate) {
                    if (!playerDelegate.isInitialized()) emptyList()
                    else (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toPlayableTrack() }
                }
                if (currentQueue.isEmpty() || !radioQueueActive) return@launch

                val nextSeed = currentQueue
                    .drop(currentIndex.coerceAtLeast(0))
                    .firstOrNull { it.videoId != null && it.videoId !in radioUsedSeeds }
                    ?: currentQueue.firstOrNull { it.videoId != null && it.videoId !in radioUsedSeeds }

                val seedVideoId = nextSeed?.videoId
                    ?: currentQueue.getOrNull(currentIndex)?.let { track ->
                        innerTube.findBestMatchOrNull(track.title, track.artist, prefetchStreams = false)?.videoId
                    }
                    ?: return@launch

                radioUsedSeeds.add(seedVideoId)
                // Same connected/accountless contract as startRadioQueue:
                // related radio first, public charts/home as the offline pad.
                val relatedPrimaryExtend = runCatching {
                    innerTube.fetchRelatedSongs(seedVideoId, limit = RADIO_QUEUE_BATCH_SIZE, prefetchStreams = false)
                }.getOrDefault(emptyList())
                val related = relatedPrimaryExtend.ifEmpty {
                    if (!radioQueueActive) return@launch
                    runCatching { innerTube.fetchCharts().take(RADIO_QUEUE_BATCH_SIZE) }
                        .getOrDefault(emptyList())
                        .ifEmpty {
                            runCatching { innerTube.fetchHomeSongs().take(RADIO_QUEUE_BATCH_SIZE) }
                                .getOrDefault(emptyList())
                        }
                }
                if (related.isEmpty() || !radioQueueActive) return@launch

                val knownVideoIds = currentQueue.mapNotNullTo(mutableSetOf()) { it.videoId }
                val knownTitleArtists = currentQueue.mapTo(mutableSetOf()) {
                    "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}"
                }
                val currentSeedTitle = nextSeed?.title?.trim()?.lowercase().orEmpty()

                val fresh = related.mapNotNull { yt ->
                    val title = yt.title.trim()
                    val titleLower = title.lowercase()
                    val artist = yt.artist.trim()
                    val artistLower = artist.lowercase()

                    val isSameTrack = yt.videoId in knownVideoIds ||
                        "$titleLower|$artistLower" in knownTitleArtists ||
                        (currentSeedTitle.isNotBlank() && isSameCoreSong(titleLower, currentSeedTitle))
                    val isJunk = isDisallowedRadioTitle(titleLower)

                    if (isSameTrack || isJunk || title.isBlank() || artist.isBlank()) {
                        null
                    } else {
                        PlayableTrack(
                            title = title,
                            artist = artist,
                            album = yt.album,
                            artworkUrl = yt.artworkUrl,
                            videoId = yt.videoId,
                        )
                    }
                }

                if (fresh.isEmpty() || !radioQueueActive) return@launch

                withContext(Dispatchers.Main.immediate) {
                    if (!radioQueueActive || !playerDelegate.isInitialized()) return@withContext
                    val existingKeys = (0 until player.mediaItemCount).mapTo(mutableSetOf()) {
                        player.getMediaItemAt(it).toPlayableTrack().queueKey()
                    }
                    val toAdd = fresh.filter { it.queueKey() !in existingKeys }
                    if (toAdd.isNotEmpty()) {
                        player.addMediaItems(toAdd.map(PlayableTrack::toMediaItem))
                        refresh(player)
                        _state.update { it.copy(isEndlessQueue = true) }
                        enrichUpcomingQueue(currentIndex)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                android.util.Log.d("MusicPlayer", "Failed to extend radio queue", e)
            }
        }
    }

    /**
     * Natural-end auto-advance safety net. Called from
     * [Player.Listener.onPlaybackStateChanged] when ExoPlayer parks in
     * STATE_ENDED, and from the progress ticker when playback stalls at the
     * track tail without advancing.
     *
     * Lossless-first is never skipped: the advance always goes through
     * [resolveAndPlayQueueItem], which tries the lossless/provider-module
     * stream first (unless that exact mediaId already proved lossless-dead
     * and sits in [losslessBypassMediaIds]) and only then falls back to
     * YouTube. No YouTube-only shortcut lives on this path.
     */
    @MainThread
    private fun handleNaturalTrackEnd() {
        if (isCasting) return
        if (!playerDelegate.isInitialized()) return
        if (player.mediaItemCount == 0) return
        // An explicit user action or error-recovery path already owns the
        // next transition — never steal it. This keeps manual next(),
        // queue-taps and unavailable-skips authoritative.
        if (playRequest?.isActive == true) return
        if (unavailableSkipJob?.isActive == true) return
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex == C.INDEX_UNSET || currentIndex !in 0 until player.mediaItemCount) return
        // REPEAT_ONE loops the same window by itself; if ExoPlayer still
        // surfaced ENDED (e.g. transient source hiccup at the loop point),
        // restart the same track instead of advancing.
        if (player.repeatMode == Player.REPEAT_MODE_ONE) {
            if (player.playbackState == Player.STATE_ENDED) {
                runCatching {
                    player.seekTo(0)
                    player.prepare()
                    player.play()
                }
            }
            return
        }
        // Only force-advance when genuinely stuck at/after the end:
        // ENDED, or READY+playWhenReady pinned at the duration tail with a
        // next window available (auto-advance failed to fire). While
        // BUFFERING we deliberately wait — the next lossless resolve may
        // still be in flight and must not be skipped for speed. The pinned
        // branch trusts only the TRUE container duration: a seeded estimate
        // below the real end would skip mid-track on a transient pause.
        // Learn the exact duration into the seed cache as a side effect; the
        // stuck check below uses only the true container duration.
        effectiveDuration(player.duration, player, _state.value.durationMs)
        val trueDurMs = player.duration
        val pos = runCatching { player.currentPosition }.getOrDefault(0L)
        val stuckAtEnd = player.playbackState == Player.STATE_ENDED ||
            (
                trueDurMs > 0L &&
                    pos >= trueDurMs - END_OF_TRACK_STALL_THRESHOLD_MS &&
                    player.playWhenReady &&
                    !player.isPlaying &&
                    player.playbackState == Player.STATE_READY &&
                    _state.value.error == null
                )
        if (!stuckAtEnd) return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val key = "$mediaId|$currentIndex|${player.mediaItemCount}|${player.shuffleModeEnabled}|${player.repeatMode}"
        val now = SystemClock.elapsedRealtime()
        if (key == lastAutoAdvanceKey && now - lastAutoAdvanceAtMs < AUTO_ADVANCE_DEBOUNCE_MS) return
        lastAutoAdvanceKey = key
        lastAutoAdvanceAtMs = now

        val timeline = player.currentTimeline
        if (timeline.isEmpty) return
        var nextIndex = timeline.getNextWindowIndex(currentIndex, player.repeatMode, player.shuffleModeEnabled)
        if (nextIndex == C.INDEX_UNSET) {
            // Queue end: endless queues refill asynchronously — kick them
            // and let the ticker retry once items land. Repeat-all wrap is
            // already covered by the timeline above; this is only the
            // belt-and-braces fallback if the timeline disagrees.
            if (discoverQueueActive || radioQueueActive) {
                extendDiscoverQueueIfNeeded(currentIndex)
                extendRadioQueueIfNeeded(currentIndex)
                return
            }
            if (player.repeatMode == Player.REPEAT_MODE_ALL && player.mediaItemCount > 0) {
                nextIndex = 0
            } else {
                _state.update { it.copy(isPlaying = false, isBuffering = false) }
                persistPlaybackSession()
                return
            }
        }
        if (nextIndex == currentIndex || nextIndex !in 0 until player.mediaItemCount) return
        // Never land on a track already proven unavailable in this session.
        var guard = 0
        while (guard++ < player.mediaItemCount) {
            if (nextIndex == C.INDEX_UNSET || nextIndex == currentIndex) {
                nextIndex = C.INDEX_UNSET
                break
            }
            val candidateId = if (nextIndex in 0 until player.mediaItemCount) {
                player.getMediaItemAt(nextIndex).mediaId
            } else null
            if (candidateId != null && candidateId !in unavailableMediaIds) break
            nextIndex = timeline.getNextWindowIndex(nextIndex, player.repeatMode, player.shuffleModeEnabled)
        }
        if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until player.mediaItemCount) {
            _state.update { it.copy(isPlaying = false, isBuffering = false) }
            persistPlaybackSession()
            return
        }
        resolveAndPlayQueueItem(nextIndex)
    }

    @MainThread
    private fun scheduleUnavailableMediaSkip(
        failedIndex: Int,
        failedMediaId: String?,
        expectedGeneration: Long = playRequestGeneration.get(),
        failure: Throwable,
        allowAutoSkip: Boolean = true,
    ) {
        if (failure is CancellationException || expectedGeneration != playRequestGeneration.get()) return
        if (!allowAutoSkip) {
            player.pause()
            _state.update { it.copy(isPlaying = false, isBuffering = false, error = "Playback interrupted. Tap play to retry.") }
            return
        }
        if (failedIndex == C.INDEX_UNSET || failedMediaId == null) return
        unavailableSkipJob?.cancel()
        unavailableSkipJob = applicationScope.launch(Dispatchers.Main.immediate) {
            yield()
            val snapshot = _state.value
            val hasTimeline = !player.currentTimeline.isEmpty
            val failedItemStillQueued = if (hasTimeline) {
                failedIndex in 0 until player.mediaItemCount && player.getMediaItemAt(failedIndex).mediaId == failedMediaId
            } else {
                snapshot.queue.getOrNull(failedIndex)?.mediaIdKey() == failedMediaId
            }
            if (expectedGeneration != playRequestGeneration.get() ||
                !failedItemStillQueued || _state.value.currentIndex != failedIndex || player.isPlaying
            ) {
                unavailableSkipJob = null
                return@launch
            }

            // A resolver or Media3 load has completed with a failure; advance
            // only while the same failed item is still selected.
            unavailableMediaIds += failedMediaId
            if (!hasTimeline) {
                unavailableSkipJob = null
                val nextIndex = nextQueueIndex(snapshot)
                if (nextIndex == C.INDEX_UNSET) {
                    player.stop()
                    _state.update { it.copy(isPlaying = false, isBuffering = false, error = "Track unavailable") }
                } else {
                    playPendingQueueItem(nextIndex, snapshot)
                }
                return@launch
            }
            val timeline = player.currentTimeline
            val repeatMode = if (player.repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            var nextIndex = timeline.getNextWindowIndex(failedIndex, repeatMode, player.shuffleModeEnabled)
            var visited = 0
            while (nextIndex != C.INDEX_UNSET && visited < player.mediaItemCount) {
                if (nextIndex == failedIndex) {
                    nextIndex = C.INDEX_UNSET
                    break
                }
                if (player.getMediaItemAt(nextIndex).mediaId !in unavailableMediaIds) break
                nextIndex = timeline.getNextWindowIndex(nextIndex, repeatMode, player.shuffleModeEnabled)
                visited++
            }
            if (visited >= player.mediaItemCount) nextIndex = C.INDEX_UNSET
            unavailableSkipJob = null
            if (nextIndex == C.INDEX_UNSET) {
                player.stop()
                _state.update {
                    it.copy(isPlaying = false, isBuffering = false, error = "Track unavailable")
                }
                return@launch
            }
            ensureForegroundService()
            _state.update { it.copy(error = null, isBuffering = true) }
            resolveAndPlayQueueItem(nextIndex)
        }
    }

    private fun onMain(action: () -> Unit) {
        applicationScope.launch(Dispatchers.Main.immediate) { action() }
    }

    data class ResolvedStream(
        val url: String,
        val mimeType: String,
        val bitrateKbps: Int?,
        val audioCodec: String?,
        val cacheKey: String,
        val requestHeaders: Map<String, String> = emptyMap(),
        val isLossless: Boolean = false,
        val bitDepth: Int? = null,
        val samplingRateKHz: Double? = null,
        val youtubeCandidate: YouTubeAudioStream? = null,
        val durationMs: Long? = null,
        /** Provider chunk descriptor; when set, playback is DASH+Widevine. */
        val segmentedDrm: com.lastwave.app.data.plugin.SegmentedStreamDescriptor? = null,
    )

    private fun isNetworkException(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is java.net.UnknownHostException ||
                cause is java.net.ConnectException ||
                cause is java.net.SocketTimeoutException ||
                cause is java.net.NoRouteToHostException ||
                (cause is java.io.IOException && cause.message?.contains("Unable to resolve host", ignoreCase = true) == true)
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun cleanTrackTitle(raw: String): String =
        raw.replace(Regex("""(?i)\s*[\(\[](feat\.|ft\.|official\s*(music)?\s*video|audio|lyrics|remastered?|hd|4k|visualizer)[^\)\]]*[\)\]]"""), "")
            .replace(Regex("""(?i)\s*-\s*(official\s*(music)?\s*video|audio|lyrics|remastered?).*"""), "")
            .trim()

    private fun cleanTrackArtist(raw: String): String =
        raw.split(Regex("""(?i)\s*(,|&|feat\.|ft\.|/|with)\s*""")).firstOrNull()?.trim() ?: raw.trim()

    private fun sanitizeFilename(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "track" }

    private suspend fun checkAndBuildLocalStream(
        targetUrl: String,
        displayTitle: String,
        displayArtist: String,
        badge: String? = null,
        isLosslessTrack: Boolean? = null,
        knownBitrate: Int? = null,
        fallbackMime: String? = null,
    ): ResolvedStream? {
        val uri = if (targetUrl.startsWith("/") && !targetUrl.startsWith("file://")) {
            Uri.fromFile(File(targetUrl))
        } else {
            Uri.parse(targetUrl)
        }

        val isAccessible = when {
            targetUrl.startsWith("content://") -> runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { it.read() != -1 } == true
            }.getOrDefault(false)
            else -> runCatching {
                val file = if (targetUrl.startsWith("file://")) {
                    File(uri.path.orEmpty())
                } else {
                    File(targetUrl)
                }
                file.inputStream().use { it.read() != -1 }
            }.getOrDefault(false)
        }

        if (!isAccessible) return null

        // Module offline download: same bytes as streaming, keys in the
        // sidecar. Unusable here (no keys / unrenewable expiry) -> null so
        // playback falls through to streaming instead of failing on ciphertext.
        offlineModuleStream(
            targetUrl = targetUrl,
            uri = uri,
            displayTitle = displayTitle,
            displayArtist = displayArtist,
        )?.let { return it }

        val pathLower = (uri.path ?: targetUrl).lowercase()
        val mime = fallbackMime?.takeIf(String::isNotBlank) ?: when {
            pathLower.endsWith(".flac") -> "audio/flac"
            pathLower.endsWith(".m4a") || pathLower.endsWith(".mp4") || pathLower.endsWith(".aac") -> "audio/mp4"
            pathLower.endsWith(".opus") || pathLower.endsWith(".ogg") -> "audio/ogg"
            pathLower.endsWith(".mp3") -> "audio/mpeg"
            pathLower.endsWith(".webm") -> "audio/webm"
            pathLower.endsWith(".wav") -> "audio/x-wav"
            targetUrl.startsWith("content://") -> runCatching { appContext.contentResolver.getType(uri) }.getOrNull() ?: "audio/flac"
            else -> "audio/flac"
        }

        var bitrateKbps: Int? = knownBitrate
        var bitDepth: Int? = null
        var samplingRateKHz: Double? = null

        runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                if (targetUrl.startsWith("content://")) {
                    retriever.setDataSource(appContext, uri)
                } else {
                    retriever.setDataSource(uri.path ?: targetUrl.removePrefix("file://"))
                }
                if (bitrateKbps == null || bitrateKbps == 0) {
                    bitrateKbps = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        ?.toIntOrNull()?.let { it / 1000 }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    samplingRateKHz = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                        ?.toDoubleOrNull()?.let { it / 1000.0 }
                    bitDepth = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                        ?.toIntOrNull()
                }
            } finally {
                retriever.release()
            }
        }

        val resolvedBadge = badge ?: when {
            mime.contains("flac") -> if ((bitDepth ?: 0) > 16 || (samplingRateKHz ?: 0.0) > 48.0) "HI-RES FLAC" else "FLAC"
            mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "M4A AAC"
            mime.contains("opus") || mime.contains("ogg") -> "OPUS"
            mime.contains("mp3") || mime.contains("mpeg") -> "320k MP3"
            else -> "AUDIO"
        }

        val isLossless = isLosslessTrack ?: mime.contains("flac")
        val trackKey = "${displayArtist.lowercase()}_${displayTitle.lowercase()}"

        return ResolvedStream(
            url = uri.toString(),
            mimeType = mime,
            bitrateKbps = bitrateKbps,
            audioCodec = resolvedBadge,
            cacheKey = "local:$trackKey",
            isLossless = isLossless,
            bitDepth = bitDepth,
            samplingRateKHz = samplingRateKHz,
        )
    }

    /**
     * Offline playback for module downloads: local MPD over the downloaded
     * bytes + persisted CDM keys. Returns null unless this exact file has
     * usable keys (fresh or renewed), letting remote streaming take over.
     */
    private suspend fun offlineModuleStream(
        targetUrl: String,
        uri: Uri,
        displayTitle: String,
        displayArtist: String,
    ): ResolvedStream? {
        val sidecar = moduleManager.readOfflineSidecar(displayTitle, displayArtist) ?: return null
        if (sidecar.keySetIdB64.isBlank()) return null
        val filePath = when {
            targetUrl.startsWith("content://") -> null
            targetUrl.startsWith("file://") -> uri.path
            else -> targetUrl
        }
        val matches = (filePath != null && sidecar.audioFilePath == filePath) ||
            (sidecar.mediaStoreUri.isNotBlank() && sidecar.mediaStoreUri == targetUrl) ||
            (sidecar.bytes > 0 && filePath != null &&
                runCatching { File(filePath).length() == sidecar.bytes }.getOrDefault(false))
        if (!matches) return null
        val initialDesc = runCatching {
            persistenceJson.decodeFromString<com.lastwave.app.data.plugin.SegmentedStreamDescriptor>(
                sidecar.descriptorJson,
            )
        }.getOrNull()
        val drm = initialDesc?.drm ?: return null
        val readyDesc = initialDesc.copy(drm = drm.copy(keySetIdB64 = sidecar.keySetIdB64, licenseUrl = sidecar.licenseUrl))
        val finalDesc = if (System.currentTimeMillis() > sidecar.licenseExpiresAtMs) {
            val renewed = withTimeoutOrNull(OFFLINE_LICENSE_RENEW_TIMEOUT_MS) {
                offlineLicense.renew(readyDesc, sidecar.keySetIdB64)
            } ?: return null
            val updated = readyDesc.copy(
                drm = readyDesc.drm?.copy(keySetIdB64 = renewed.keySetIdB64),
            )
            moduleManager.writeOfflineSidecar(
                displayTitle, displayArtist,
                sidecar.copy(
                    keySetIdB64 = renewed.keySetIdB64,
                    licenseExpiresAtMs = renewed.licenseExpiresAtMs,
                    descriptorJson = moduleManager.encodeDescriptor(updated),
                ),
            )
            updated
        } else {
            readyDesc
        }
        var descriptor = finalDesc
        val s = descriptor.stream
        return ResolvedStream(
            url = segBridge.mpdUriForBase(descriptor, targetUrl).toString(),
            mimeType = MimeTypes.APPLICATION_MPD,
            bitrateKbps = s.bandwidth.takeIf { it > 0 }?.div(1000),
            audioCodec = segBridge.audioBadge(descriptor),
            cacheKey = "offline:${displayArtist.lowercase()}_${displayTitle.lowercase()}",
            isLossless = !s.codec.equals("opus", ignoreCase = true),
            bitDepth = s.bitDepth.takeIf { it > 0 },
            samplingRateKHz = s.sampleRate.takeIf { it > 0 }?.div(1000.0),
            // Seed the slider denominator: the MPD timeline alone may take a
            // while to parse, and without this the bar sat dead until then.
            durationMs = descriptor.durationSec.takeIf { it > 0 }?.times(1_000L),
            segmentedDrm = descriptor,
        )
    }

    private suspend fun resolveLocalDownloadedAudioStream(track: PlayableTrack): ResolvedStream? {
        val title = track.title.trim()
        val artist = track.artist.trim()

        // 1. If track already carries a playbackUrl (e.g. from Downloads screen), check it first
        track.playbackUrl?.takeIf(String::isNotBlank)?.let { preUrl ->
            val resolved = checkAndBuildLocalStream(
                targetUrl = preUrl,
                displayTitle = title,
                displayArtist = artist,
                fallbackMime = track.playbackMimeType,
            )
            if (resolved != null) return resolved
        }

        if (title.isBlank()) return null

        val cleanTitle = cleanTrackTitle(title)
        val cleanArtist = cleanTrackArtist(artist)
        val downloadFolder = runCatching {
            com.lastwave.app.data.local.sanitizeDownloadFolderName(settingsPreferences.settings.first().downloadFolder)
        }.getOrDefault(com.lastwave.app.data.local.DEFAULT_DOWNLOAD_FOLDER)
        val downloadDirs = listOf(downloadFolder, com.lastwave.app.data.local.DEFAULT_DOWNLOAD_FOLDER)
            .distinct()
            .map { File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), it) }
        val appMusicDir = appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC)

        // 2. Query Room database with multiple fallbacks
        val dao = runCatching { downloadedTrackDao.get() }.getOrNull()
        var downloaded: DownloadedTrackEntity? = null
        if (dao != null) {
            val key = "${artist.lowercase()}_${title.lowercase()}"
            val cleanKey = "${cleanArtist.lowercase()}_${cleanTitle.lowercase()}"
            downloaded = runCatching {
                dao.findByTrackKey(key)
                    ?: dao.findByTitleAndArtist(title, artist)
                    ?: (if (cleanKey != key) dao.findByTrackKey(cleanKey) else null)
                    ?: (if (cleanTitle != title || cleanArtist != artist) dao.findByTitleAndArtist(cleanTitle, cleanArtist) else null)
                    ?: dao.getAllList().firstOrNull { entity ->
                        val eTitle = entity.title.trim()
                        val eArtist = entity.artist.trim()
                        eTitle.equals(title, ignoreCase = true) && eArtist.equals(artist, ignoreCase = true) ||
                            cleanTrackTitle(eTitle).equals(cleanTitle, ignoreCase = true) &&
                            (cleanTrackArtist(eArtist).equals(cleanArtist, ignoreCase = true) ||
                             eArtist.contains(cleanArtist, ignoreCase = true) ||
                             cleanArtist.contains(eArtist, ignoreCase = true))
                    }
            }.getOrNull()
        }

        // 3. If entity found in DB, check its mediaStoreUri and filePath
        if (downloaded != null) {
            val candidates = mutableListOf<String>()
            downloaded.mediaStoreUri?.takeIf(String::isNotBlank)?.let { candidates.add(it) }
            downloaded.filePath.takeIf(String::isNotBlank)?.let { if (!candidates.contains(it)) candidates.add(it) }
            val fileName = File(downloaded.filePath).name
            findDownloadFileByName(downloadDirs, fileName)?.absolutePath
                ?.takeIf { !candidates.contains(it) }?.let { candidates.add(it) }

            for (candidate in candidates) {
                val resolved = checkAndBuildLocalStream(
                    targetUrl = candidate,
                    displayTitle = downloaded.title,
                    displayArtist = downloaded.artist,
                    badge = downloaded.formatBadge,
                    isLosslessTrack = downloaded.isLossless,
                    knownBitrate = downloaded.bitrateKbps,
                )
                if (resolved != null) {
                    if (candidate != downloaded.filePath && candidate.startsWith("/")) {
                        runCatching { dao?.insert(downloaded.copy(filePath = candidate)) }
                    }
                    return resolved
                }
            }
        }

        // 4. Fallback: Search physical download directories (Music/<folder> + legacy Music/LastWave)
        val candidateExtensions = listOf("flac", "m4a", "mp3", "opus", "ogg", "webm", "wav")
        val candidateBases = listOf(
            "$artist - $title",
            "$cleanArtist - $cleanTitle",
            "$artist - $cleanTitle",
            title,
            cleanTitle,
        ).map { sanitizeFilename(it) }.distinct()

        val searchDirs = (downloadDirs.filter { it.exists() } + listOfNotNull(appMusicDir?.takeIf { it.exists() })).distinctBy { it.absolutePath }
        // Index each dir once (organized subfolders can hold many files).
        val indexedByName = mutableMapOf<String, File>()
        for (dir in searchDirs) {
            runCatching {
                dir.walkTopDown().maxDepth(6).forEach { f ->
                    if (f.isFile && f.length() > 0) indexedByName.getOrPut(f.name) { f }
                }
            }
        }
        for (base in candidateBases) {
            for (ext in candidateExtensions) {
                val candidateFile = indexedByName["$base.$ext"] ?: continue
                val resolved = checkAndBuildLocalStream(
                    targetUrl = candidateFile.absolutePath,
                    displayTitle = title,
                    displayArtist = artist,
                )
                if (resolved != null) {
                    runCatching {
                        dao?.insert(
                            DownloadedTrackEntity(
                                trackKey = "${cleanArtist.lowercase()}_${cleanTitle.lowercase()}",
                                title = title,
                                artist = artist,
                                album = track.album.orEmpty(),
                                artworkUrl = track.artworkUrl,
                                filePath = candidateFile.absolutePath,
                                fileSizeBytes = candidateFile.length(),
                                formatBadge = ext.uppercase(),
                                isLossless = ext.equals("flac", ignoreCase = true),
                                downloadedAtMillis = candidateFile.lastModified(),
                            )
                        )
                    }
                    return resolved
                }
            }
        }

        return null
    }

    /** Finds a file by name under the given roots (top level first, then
     *  organized subfolders). Null when missing or empty. */
    private fun findDownloadFileByName(dirs: List<File>, fileName: String): File? {
        if (fileName.isBlank()) return null
        for (dir in dirs) {
            if (!dir.exists()) continue
            val direct = File(dir, fileName)
            if (direct.exists() && direct.length() > 0) return direct
            val nested = runCatching {
                dir.walkTopDown().maxDepth(6)
                    .firstOrNull { it.isFile && it.name == fileName && it.length() > 0 }
            }.getOrNull()
            if (nested != null) return nested
        }
        return null
    }

    private suspend fun resolveTrackAudioStream(
        track: PlayableTrack,
        videoId: String?,
        allowLossless: Boolean = true,
        excludedLosslessUrls: Set<String> = emptySet(),
        allowLocalDownloads: Boolean = true,
    ): ResolvedStream = withContext(Dispatchers.IO) {
        val misc = runCatching { settingsPreferences.settings.first() }.getOrDefault(MiscSettings())
        val key = listOf(track.title, track.artist, track.album, videoId, allowLossless, misc.losslessQuality, misc.dolbyAtmosEnabled, misc.preferLosslessStreaming, misc.preferProviderModules, excludedLosslessUrls, allowLocalDownloads)
        val now = SystemClock.elapsedRealtime()
        resolutionRequests.entries.removeIf { now - it.value.first > 60_000L }
        if (resolutionRequests.size >= 64) {
            resolutionRequests.entries.removeIf { it.value.second.isCompleted }
        }
        val request = resolutionRequests.computeIfAbsent(key) {
            now to applicationScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                resolveRemoteTrackAudioStream(track, videoId, allowLossless, misc, excludedLosslessUrls, allowLocalDownloads)
            }
        }
        try {
            request.second.await().also {
                if (it.isExpired()) throw java.io.IOException("Prepared stream expired")
            }
        } catch (error: Exception) {
            if (error !is CancellationException || request.second.isCancelled) {
                resolutionRequests.remove(key, request)
            }
            throw error
        }
    }

    private suspend fun resolveRemoteTrackAudioStream(
        track: PlayableTrack,
        videoId: String?,
        allowLossless: Boolean,
        misc: MiscSettings,
        excludedLosslessUrls: Set<String>,
        allowLocalDownloads: Boolean = true,
    ): ResolvedStream {
        val wantLossless = allowLossless &&
            misc.preferLosslessStreaming &&
            misc.losslessQuality != com.lastwave.app.data.lossless.LosslessMusicApi.QUALITY_YOUTUBE

        // Local download, YouTube, and lossless all fork at T=0. Local is
        // still awaited first (Issue #31: offline playback + saved data), but
        // it no longer blocks YouTube/lossless from staging in parallel.
        val forkStart = SystemClock.elapsedRealtime()
        val localDeferred = applicationScope.async(Dispatchers.IO) {
            if (allowLocalDownloads) runCatching { resolveLocalDownloadedAudioStream(track) }.getOrNull() else null
        }
        // Bounded by YOUTUBE_PROMOTE_BUDGET_MS below; typed errors (403/LOGIN/
        // timeout/cipher) surface from await() instead of being swallowed.
        val youtubeDeferred = applicationScope.async(Dispatchers.IO) {
            resolveYoutubeTrackAudioStream(track, videoId)
        }

        if (!wantLossless || (!videoId.isNullOrBlank() &&
                (track.artist.isBlank() || track.artist.equals("Unknown artist", ignoreCase = true)))
        ) {
            return try {
                localDeferred.await()?.also {
                    android.util.Log.i("MusicPlayer", "[PLAYBACK] local-download hit for '${track.title}' key=${it.cacheKey}")
                } ?: awaitYoutubeWithinBudget(youtubeDeferred, track, forkStart)
            } finally {
                youtubeDeferred.cancel()
                localDeferred.cancel()
            }
        }

        android.util.Log.i(
            "MusicPlayer",
            "resolveRemoteTrack: '${track.title}' by '${track.artist}' (wantLossless=$wantLossless, allowLossless=$allowLossless, preferLossless=${misc.preferLosslessStreaming}, isCoolingDown=${losslessMusicApi.isCoolingDown})",
        )

        // Skip the lossless attempt only while the backend is actively
        // cooling down from a recent failure (it would just burn the timeout
        // and fall back anyway). Deliberately NOT gated on isConfigured:
        // that is false on cold start before JNI loads and gating on it
        // skipped lossless entirely (d625587).
        val losslessAttempt = wantLossless && !losslessMusicApi.isCoolingDown
        // Direct backend resolution using APK embedded secrets / native secrets
        val losslessDeferred = applicationScope.async(Dispatchers.IO) {
            if (!losslessAttempt) null
            else runCatching { resolveLosslessTrackAudioStream(track, misc, excludedLosslessUrls) }.getOrNull()
        }
        return try {
            val localStream = localDeferred.await()
            if (localStream != null) {
                android.util.Log.i("MusicPlayer", "[PLAYBACK] local-download hit for '${track.title}' key=${localStream.cacheKey}")
                localStream
            } else {
                val losslessTimeoutMs = if (!videoId.isNullOrBlank()) 3_500L else 4_500L
                val losslessBudgetMs = losslessTimeoutMs - (SystemClock.elapsedRealtime() - forkStart)
                val losslessStream: ResolvedStream? = if (!losslessAttempt) {
                    null
                } else if (losslessBudgetMs <= 0L) {
                    if (losslessDeferred.isCompleted) losslessDeferred.await() else null
                } else {
                    withTimeoutOrNull(losslessBudgetMs) { losslessDeferred.await() }
                }

                if (losslessAttempt) {
                    if (losslessStream != null) {
                        android.util.Log.i("MusicPlayer", "[LOSSLESS] SUCCESS for '${track.title}': codec=${losslessStream.audioCodec}, bitrate=${losslessStream.bitrateKbps}kbps, rate=${losslessStream.samplingRateKHz}kHz")
                    } else {
                        android.util.Log.w("MusicPlayer", "[LOSSLESS] TIMED OUT or RETURNED NULL (${losslessTimeoutMs}ms budget from fork) for '${track.title}', taking YouTube fallback")
                    }
                }

                losslessStream
                    ?: runCatching { awaitYoutubeWithinBudget(youtubeDeferred, track, forkStart) }.getOrNull()
                    ?: runCatching { youtubeDeferred.await() }.getOrNull()
                    ?: runCatching { resolveYoutubeTrackAudioStream(track, videoId) }.getOrNull()
                    ?: resolveYoutubeTrackAudioStream(track, null)
            }
        } finally {
            youtubeDeferred.cancel()
            losslessDeferred.cancel()
            localDeferred.cancel()
        }
    }

    private suspend fun awaitYoutubeWithinBudget(
        youtubeDeferred: Deferred<ResolvedStream>,
        track: PlayableTrack,
        forkStart: Long,
    ): ResolvedStream {
        val budgetMs = YOUTUBE_PROMOTE_BUDGET_MS - (SystemClock.elapsedRealtime() - forkStart)
        val promoted = if (budgetMs <= 0L) {
            if (youtubeDeferred.isCompleted) youtubeDeferred.await() else null
        } else {
            withTimeoutOrNull(budgetMs) { youtubeDeferred.await() }
        }
        if (promoted == null) {
            val error = java.util.concurrent.TimeoutException(
                "YouTube promote budget expired after ${YOUTUBE_PROMOTE_BUDGET_MS}ms for '${track.title}'",
            )
            logResolutionFailure(track, "youtube-promote-budget", 0, error)
            throw error
        }
        android.util.Log.i(
            "MusicPlayer",
            "[PLAYBACK] promoted staged YouTube stream for '${track.title}' after ${SystemClock.elapsedRealtime() - forkStart}ms (codec=${promoted.audioCodec}, kbps=${promoted.bitrateKbps})",
        )
        return promoted
    }

    private suspend fun resolveLosslessTrackAudioStream(
        track: PlayableTrack,
        misc: MiscSettings,
        excludedLosslessUrls: Set<String> = emptySet(),
    ): ResolvedStream? {
        val effectiveQuality = if (misc.dolbyAtmosEnabled) LosslessMusicApi.QUALITY_DOLBY_ATMOS else misc.losslessQuality
        val stream = losslessMusicApi.resolveStream(
            title = track.title,
            artist = track.artist,
            expectedAlbum = track.album,
            preferredQuality = effectiveQuality,
            excludedUrls = excludedLosslessUrls,
        ) ?: return null

        if (stream.url.isBlank() || stream.url in excludedLosslessUrls) return null

        val isLossless = stream.formatId != LosslessMusicApi.QUALITY_MP3_320 &&
            stream.formatId != LosslessMusicApi.QUALITY_DATA_SAVER &&
            !stream.mimeType.contains("mp3", ignoreCase = true) &&
            !stream.mimeType.contains("aac", ignoreCase = true)

        val badge = when {
            stream.audioCodecOverride != null -> stream.audioCodecOverride
            stream.formatId == LosslessMusicApi.QUALITY_DOLBY_ATMOS -> "DOLBY ATMOS"
            stream.bitDepth > 16 || stream.samplingRate > 48.0 -> "HI-RES FLAC"
            stream.formatId == LosslessMusicApi.QUALITY_MP3_320 -> "MP3 320k"
            stream.formatId == LosslessMusicApi.QUALITY_DATA_SAVER -> "HE-AAC"
            else -> "LOSSLESS"
        }

        val playUrl: String
        val mimeType: String
        if (stream.url.startsWith("data:application/dash+xml;base64,")) {
            val xml = String(
                android.util.Base64.decode(stream.url.substringAfter("base64,"), android.util.Base64.DEFAULT),
                Charsets.UTF_8,
            )
            // Content-addressed manifest: a re-resolve (expiry refresh, error
            // retry) must never overwrite the file a playing item is still
            // opening/reading. A torn or swapped manifest corrupts the DASH
            // timeline — frozen/creeping position that later jumps while audio
            // plays from the wrong point. Same scheme as the segdrm bridge.
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(xml.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)
            val dir = File(appContext.cacheDir, "tidal_mpd").apply { mkdirs() }
            val prefix = "tidal_${stream.trackId}_${stream.formatId}_"
            val file = File(dir, "$prefix$digest.mpd")
            if (!file.exists()) {
                runCatching {
                    val tmp = File(dir, "${file.name}.tmp")
                    tmp.writeText(xml, Charsets.UTF_8)
                    if (!tmp.renameTo(file)) file.writeText(xml, Charsets.UTF_8)
                }.getOrElse {
                    file.writeText(xml, Charsets.UTF_8)
                }
                // Best-effort: drop superseded manifests for the same track so
                // rotated manifests can't accumulate without bound.
                runCatching {
                    dir.listFiles { f -> f.name.startsWith(prefix) && f.name != file.name }
                        ?.forEach { runCatching { it.delete() } }
                }
            }
            playUrl = Uri.fromFile(file).toString()
            mimeType = MimeTypes.APPLICATION_MPD
        } else {
            playUrl = stream.url
            mimeType = stream.mimeType.ifBlank { "audio/flac" }
        }

        return ResolvedStream(
            url = playUrl,
            mimeType = mimeType,
            bitrateKbps = stream.bitrateKbps,
            audioCodec = badge,
            cacheKey = "lossless:${track.mediaIdKey()}:${stream.formatId}",
            isLossless = isLossless,
            bitDepth = stream.bitDepth.takeIf { it > 0 },
            samplingRateKHz = stream.samplingRate.takeIf { it > 0.0 },
            durationMs = stream.durationSeconds.takeIf { it > 0 }?.times(1_000L),
        )
    }

    private suspend fun resolveYoutubeTrackAudioStream(
        track: PlayableTrack,
        videoId: String?,
    ): ResolvedStream {
        val canSearch = track.title.isNotBlank()
        val rejectedVideoIds = mutableSetOf<String>()
        var lastFailure: Throwable? = null
        var resolved: YouTubeAudioStream? = null
        // Single direct attempt (no retry): instant peek cache, else one resolve.
        // Desktop-style: if videoId is known, resolve directly without search.
        if (!videoId.isNullOrBlank()) {
            resolved = innerTube.peekCachedStream(videoId)
            if (resolved == null) {
                try {
                    resolved = innerTube.resolveAudioStream(videoId)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    lastFailure = failure
                    rejectedVideoIds += videoId
                }
            }
        }
        // Search-only attempts (the direct attempt above already ran). Two, not
        // three: each extra attempt re-ran the whole waterfall and multiplied a
        // slow network into a 60-90s wait before playback. Attempt 0 keeps the
        // artist for match quality; attempt 1 broadens the query by dropping it.
        var attempt = 0
        while (resolved == null && canSearch && attempt < 2) {
            try {
                val searchArtist = if (attempt == 0) track.artist else ""
                val targetVideoId = innerTube.findBestMatch(
                    title = track.title,
                    artist = searchArtist,
                    prefetchStreams = false,
                    excludedVideoIds = rejectedVideoIds,
                ).videoId
                rejectedVideoIds += targetVideoId
                resolved = innerTube.resolveAudioStream(targetVideoId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                lastFailure?.takeIf { it !== failure }?.let(failure::addSuppressed)
                lastFailure = failure
            }
            attempt++
        }
        val ytStream = resolved ?: throw (lastFailure ?: java.io.IOException(
            if (canSearch) "No playable match found"
            else "No video ID or search query available for track",
        ))
        val trueBitrate = ytStream.bitrate.takeIf { it > 0 }?.let { (it + 500) / 1_000 }
        val rawCodec = ytStream.codec?.substringBefore(',')?.trim()?.uppercase()?.ifBlank {
            ytStream.mimeType?.substringAfter("audio/")?.substringBefore(';')?.uppercase()?.ifBlank { "WEBM" } ?: "WEBM"
        } ?: "WEBM"
        val codec = when {
            rawCodec.contains("OPUS") || rawCodec == "WEBM" -> "OPUS"
            rawCodec.contains("M4A") || rawCodec.contains("MP4") || rawCodec.contains("MP4A") || rawCodec.contains("AAC") -> "AAC"
            else -> rawCodec
        }
        val cleanMime = ytStream.mimeType?.substringBefore(';')?.trim().orEmpty()
        return ResolvedStream(
            url = ytStream.url,
            mimeType = cleanMime,
            bitrateKbps = trueBitrate,
            audioCodec = codec,
            cacheKey = ytStream.mediaCacheKey,
            requestHeaders = ytStream.requestHeaders,
            isLossless = false,
            samplingRateKHz = ytStream.sampleRateHz?.let { it / 1_000.0 },
            youtubeCandidate = ytStream,
            // YouTube's approxDurationMs is the only trustworthy duration
            // until ExoPlayer parses the container (progressive WebM/MP4 over
            // throttled connections can report TIME_UNSET for 30s+). Seeding
            // it here keeps the progress bar alive from t=0; the ticker
            // prefers the exact player duration as soon as it is known.
            durationMs = ytStream.durationMs?.takeIf { it > 0 },
        )
    }

    private fun publishResolvedQuality(resolved: ResolvedStream) {
        android.util.Log.i(
            "MusicPlayer",
            "Quality Pill: publishResolvedQuality(codec=${resolved.audioCodec}, depth=${resolved.bitDepth}, rate=${resolved.samplingRateKHz}kHz, kbps=${resolved.bitrateKbps}, isLossless=${resolved.isLossless})",
        )
        val seedMs = resolved.durationMs ?: resolved.youtubeCandidate?.durationMs
        _state.update {
            // Never let a generic-unknown stream ("AUDIO"/"LOCAL AUDIO" with
            // only a measured bitrate, no depth/rate) clobber an explicit
            // quality the same track already published (lossless depth/rate
            // or an explicit YouTube OPUS/AAC). Late duplicate publishes
            // (retry, transition republish, local retriever fallback) used to
            // flip "24-bit / 192 kHz" to "AUDIO 1343 kbps". Honest explicit
            // downgrades (e.g. retry falling back to YouTube OPUS) still apply.
            val incomingExplicit = isExplicitQuality(
                resolved.audioCodec, resolved.bitDepth, resolved.samplingRateKHz,
            )
            val currentExplicit = isExplicitQuality(
                it.audioCodec, it.bitDepth, it.samplingRateKHz,
            )
            if (currentExplicit && !incomingExplicit) {
                it.copy(
                    durationMs = if (it.durationMs <= 0L) seedMs?.takeIf { ms -> ms > 0 } ?: it.durationMs else it.durationMs,
                )
            } else {
                it.copy(
                    bitrateKbps = resolved.bitrateKbps,
                    audioCodec = resolved.audioCodec,
                    isLossless = resolved.isLossless,
                    bitDepth = resolved.bitDepth,
                    samplingRateKHz = resolved.samplingRateKHz,
                    // Seed the progress denominator the moment the stream
                    // resolves instead of waiting for ExoPlayer to parse the
                    // container (which can lag 30-40s on throttled URLs and left
                    // the bar frozen at 0:00 with seeking disabled). The ticker
                    // swaps in the exact player duration once known.
                    durationMs = if (it.durationMs <= 0L) seedMs?.takeIf { ms -> ms > 0 } ?: it.durationMs else it.durationMs,
                )
            }
        }
        rememberKnownDuration(_state.value.current?.mediaIdKey(), seedMs)
        updateBitPerfectState()
        if (isSpatialAudioCodec(resolved.audioCodec)) {
            onMain { applyDacRoutingFor(currentSourceRateHz()) }
        }
    }

    /**
     * Explicit (honest) quality: a named format, or depth + rate that render
     * the resolution branch. Generic "AUDIO"/"LOCAL AUDIO"/blank with only a
     * measured bitrate is not explicit.
     */
    private fun isExplicitQuality(codec: String?, bitDepth: Int?, samplingRateKHz: Double?): Boolean {
        if (bitDepth != null && samplingRateKHz != null) return true
        val label = codec?.uppercase().orEmpty()
        return label.isNotBlank() && label != "AUDIO" && label != "LOCAL AUDIO"
    }

    private suspend fun resolveTrackAudioStreamWithRetry(
        track: PlayableTrack,
        videoId: String?,
        allowLossless: Boolean,
    ): ResolvedStream {
        runCatching { streamResolutionWakeLock?.acquire(60_000L) }
        try {
            var lastFailure: Throwable? = null
            repeat(2) { attempt ->
                try {
                    return resolveTrackAudioStream(track, videoId, allowLossless)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    lastFailure = error
                    if (attempt == 0 && error is java.io.IOException) {
                        delay(PLAYBACK_RETRY_BASE_DELAY_MS + Random.nextLong(PLAYBACK_RETRY_JITTER_MS + 1L))
                    } else {
                        throw error
                    }
                }
            }
            throw lastFailure ?: java.io.IOException("Unable to resolve audio")
        } finally {
            runCatching {
                if (streamResolutionWakeLock?.isHeld == true) streamResolutionWakeLock?.release()
            }
        }
    }

    private fun rememberKnownDuration(key: String?, durationMs: Long?) {
        if (key.isNullOrBlank()) return
        val ms = durationMs?.takeIf { it > 0 } ?: return
        knownDurations[key] = ms
        if (knownDurations.size > MAX_KNOWN_DURATIONS) {
            val activeKeys = if (playerDelegate.isInitialized()) {
                runCatching {
                    (0 until player.mediaItemCount).flatMapTo(mutableSetOf()) {
                        val item = player.getMediaItemAt(it)
                        setOfNotNull(item.mediaId, item.localConfiguration?.customCacheKey)
                    }
                }.getOrDefault(emptySet())
            } else {
                emptySet()
            }
            knownDurations.keys.filterNot(activeKeys::contains)
                .take(knownDurations.size - MAX_KNOWN_DURATIONS)
                .forEach(knownDurations::remove)
        }
    }

    /**
     * Best-known duration for the given item: exact ExoPlayer value when
     * available, otherwise the resolve-time seed, otherwise the previous UI
     * value. Never returns 0 while a seed exists, so a slow-to-parse stream
     * can't zero out (and freeze) the progress bar mid-track.
     */
    private fun effectiveDuration(
        playerDurationMs: Long,
        player: Player?,
        previousMs: Long,
    ): Long {
        val current = try {
            player?.currentMediaItem
        } catch (_: Exception) {
            null
        }
        if (playerDurationMs > 0) {
            rememberKnownDuration(current?.localConfiguration?.customCacheKey, playerDurationMs)
            rememberKnownDuration(current?.mediaId, playerDurationMs)
            return playerDurationMs
        }
        val seeded = current?.localConfiguration?.customCacheKey?.let(knownDurations::get)
            ?: current?.mediaId?.let(knownDurations::get)
        return seeded ?: previousMs
    }

    private fun findPreparedStreamFor(
        track: PlayableTrack,
        videoId: String?,
        bypassLossless: Boolean,
    ): ResolvedStream? {
        val matchVideoId = videoId ?: track.videoId
        val localKey = "${track.artist.trim().lowercase()}_${track.title.trim().lowercase()}"
        val losslessPrefix = "lossless:${track.mediaIdKey()}:"
        return preparedStreams.values.firstOrNull { stream ->
            if (stream.isExpired()) return@firstOrNull false
            val cacheKey = stream.cacheKey
            when {
                matchVideoId != null && stream.youtubeCandidate?.videoId == matchVideoId -> true
                (cacheKey.startsWith("local:") || cacheKey.startsWith("offline:")) &&
                    cacheKey.substringAfter(':') == localKey -> true
                !bypassLossless && cacheKey.startsWith(losslessPrefix) -> true
                else -> false
            }
        }
    }

    private fun registerPreparedStream(stream: ResolvedStream) {
        preparedStreams.entries.removeIf { it.value.isExpired() }
        preparedStreams[stream.cacheKey] = stream
        // Seed the progress denominator immediately: the exact player
        // duration may lag by tens of seconds on throttled streams.
        val seedMs = stream.durationMs ?: stream.youtubeCandidate?.durationMs
        rememberKnownDuration(stream.cacheKey, seedMs)
        rememberKnownDuration(stream.youtubeCandidate?.videoId, seedMs)
        if (preparedStreams.size <= MAX_PREPARED_STREAMS) return
        val activeKeys = if (playerDelegate.isInitialized()) {
            (0 until player.mediaItemCount).mapNotNullTo(mutableSetOf()) {
                player.getMediaItemAt(it).localConfiguration?.customCacheKey
            }
        } else {
            emptySet()
        }
        preparedStreams.keys
            .asSequence()
            .filterNot(activeKeys::contains)
            .take(preparedStreams.size - MAX_PREPARED_STREAMS)
            .forEach(preparedStreams::remove)
    }

    private fun logStreamEvent(
        stage: String,
        stream: ResolvedStream,
        retry: Int,
        httpStatus: Int? = null,
        error: Throwable? = null,
    ) {
        val candidate = stream.youtubeCandidate
        val expiry = when {
            candidate?.expiresAtEpochMs == null -> "unknown"
            candidate.expiresAtEpochMs <= System.currentTimeMillis() -> "expired"
            else -> "fresh"
        }
        PlaybackDiagnostics.event(
            "Stream",
            "stage=$stage videoId=${candidate?.videoId.orEmpty()} " +
                "client=${candidate?.clientProfile ?: if (stream.isLossless) "LOSSLESS" else "unknown"} " +
                "itag=${candidate?.itag ?: -1} mime=${stream.mimeType} expiry=$expiry " +
                "retry=$retry http=${httpStatus ?: 0} error=${error?.javaClass?.simpleName.orEmpty()}",
        )
    }

    private fun logResolutionFailure(
        track: PlayableTrack,
        stage: String,
        retry: Int,
        error: Throwable,
    ) {
        PlaybackDiagnostics.event(
            "Stream",
            "stage=$stage videoId=${track.videoId.orEmpty()} client=unresolved itag=-1 " +
                "mime=unknown expiry=unknown retry=$retry http=${error.httpStatusCodeOrNull() ?: 0} " +
                "error=${error.javaClass.simpleName}",
        )
        android.util.Log.e(
            "MusicPlayer",
            "Playback stream failure at $stage (${error.javaClass.simpleName})",
        )
    }

    private fun ResolvedStream.isExpired(now: Long = System.currentTimeMillis()): Boolean =
        youtubeCandidate?.expiresAtEpochMs?.let { it - now <= RESOLVED_URL_EXPIRY_MARGIN_MS } == true

    private fun Throwable.httpStatusCodeOrNull(): Int? = causeChain()
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()
        ?.responseCode

    private fun playbackRetryDelayMs(error: PlaybackException, retry: Int): Long {
        val status = error.httpStatusCodeOrNull()
        val transientHttp = status == 408 || status == 429 || (status != null && status in 500..599)
        val transientNetwork = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        if (!transientHttp && !transientNetwork) return 0L
        val exponential = PLAYBACK_RETRY_BASE_DELAY_MS * (1L shl (retry - 1).coerceAtMost(3))
        return exponential + Random.nextLong(PLAYBACK_RETRY_JITTER_MS + 1L)
    }

    private fun isRetryablePlaybackFailure(error: PlaybackException): Boolean {
        if (isUnsupportedMediaFailure(error)) return true
        val status = error.httpStatusCodeOrNull()
        if (status == 401 || status == 403 || status == 404 || status == 410 ||
            status == 408 || status == 429 || (status != null && status in 500..599)
        ) return true
        return error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
    }

    private fun publishLocalTrackQuality(track: PlayableTrack) {
        val url = track.playbackUrl ?: return
        val retriever = android.media.MediaMetadataRetriever()
        try {
            if (url.startsWith("content://")) {
                retriever.setDataSource(appContext, Uri.parse(url))
            } else {
                retriever.setDataSource(url.removePrefix("file://"))
            }
            val mime = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.lowercase().orEmpty()
            val bitrateStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)
            val bitrateKbps = bitrateStr?.toIntOrNull()?.let { it / 1000 }
            val sampleRateStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
            } else null
            val sampleRateKHz = sampleRateStr?.toDoubleOrNull()?.let { it / 1000.0 }
            val bitDepthStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
            } else null
            val bitDepth = bitDepthStr?.toIntOrNull()

            val isFlac = mime.contains("flac") || url.endsWith(".flac", ignoreCase = true)
            val isM4a = mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") || url.endsWith(".m4a", ignoreCase = true)
            val isOpus = mime.contains("opus") || mime.contains("ogg") || url.endsWith(".opus", ignoreCase = true)
            val isMp3 = mime.contains("mp3") || mime.contains("mpeg") || url.endsWith(".mp3", ignoreCase = true)

            val codec = when {
                isFlac && ((bitDepth ?: 0) > 16 || (sampleRateKHz ?: 0.0) > 48.0) -> "HI-RES FLAC"
                isFlac -> "FLAC"
                isM4a -> "AAC"
                isOpus -> "OPUS"
                isMp3 -> "MP3"
                else -> "LOCAL AUDIO"
            }

            _state.update {
                it.copy(
                    audioCodec = codec,
                    bitrateKbps = bitrateKbps,
                    bitDepth = bitDepth ?: if (isFlac) 16 else null,
                    samplingRateKHz = sampleRateKHz ?: if (isFlac) 44.1 else null,
                    // FLAC is lossless at every bit depth. Requiring >16 here
                    // marked CD-quality (16/44.1) FLAC — and any FLAC whose
                    // container omits BITS_PER_SAMPLE — as lossy, which pushed
                    // qualityLabel onto the kbps branch ("FLAC 1324 kbps")
                    // instead of showing the bit depth / sample rate.
                    isLossless = isFlac,
                )
            }
            updateBitPerfectState()
        } catch (_: Exception) {
            val isFlac = url.endsWith(".flac", ignoreCase = true)
            _state.update {
                it.copy(
                    audioCodec = if (isFlac) "FLAC" else "AUDIO",
                    bitDepth = if (isFlac) 16 else null,
                    samplingRateKHz = if (isFlac) 44.1 else null,
                    isLossless = isFlac,
                )
            }
            updateBitPerfectState()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun ensureForegroundService() {
        val intent = Intent(appContext, MusicPlaybackService::class.java)
        // Background-start restrictions (Android 12+) can reject this when
        // playback is triggered from widget/tile paths — that must never take
        // the app down; playback simply continues without foreground priority.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appContext.startForegroundService(intent)
            else appContext.startService(intent)
        }.onFailure {
            android.util.Log.w("MusicPlayer", "Foreground service start rejected", it)
        }
    }

    private fun restorePlaybackSession(): Boolean {
        val raw = playbackPreferences.getString(PLAYBACK_SESSION_KEY, null) ?: return false
        val session = runCatching {
            persistenceJson.decodeFromString<PersistedPlaybackSession>(raw)
        }.getOrElse {
            clearPersistedPlaybackSession()
            return false
        }
        val restoredQueue = session.queue
            .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
            .map {
                val isLocal = it.playbackUrl?.let { url ->
                    url.startsWith("/") || url.startsWith("content://") || url.startsWith("file://")
                } == true
                if (isLocal) it else it.copy(playbackUrl = null, playbackMimeType = null)
            }
        if (restoredQueue.isEmpty()) {
            clearPersistedPlaybackSession()
            return false
        }
        val restoredIndex = session.currentIndex.coerceIn(restoredQueue.indices)
        discoverQueueActive = session.isEndlessQueue && session.sourceLabel == "Discover"
        radioQueueActive = session.isEndlessQueue && session.sourceLabel != "Discover"
        _state.value = MusicPlayerState(
            current = restoredQueue[restoredIndex],
            queue = restoredQueue,
            currentIndex = restoredIndex,
            sourceLabel = session.sourceLabel,
            isEndlessQueue = session.isEndlessQueue,
            positionMs = session.positionMs.coerceAtLeast(0),
            shuffleEnabled = session.shuffleEnabled,
            repeatMode = session.repeatMode,
            speed = session.speed,
        )
        pendingRestoredSession = session.copy(
            queue = restoredQueue,
            currentIndex = restoredIndex,
        )
        return true
    }

    private fun isExplicitlyUnplayableFailure(error: Throwable): Boolean =
        error.causeChain().filterIsInstance<java.io.IOException>().firstOrNull() is ConfirmedUnplayableMediaException

    private fun isUnsupportedMediaFailure(error: Throwable): Boolean =
        error.causeChain().filterIsInstance<PlaybackException>().any {
            it.errorCode in PERMANENT_PLAYBACK_ERROR_CODES
        }

    private fun Throwable.causeChain(): Sequence<Throwable> =
        generateSequence(this) { it.cause }.take(12)

    private fun persistPlaybackSession() {
        val snapshot = _state.value
        val sourceQueue = snapshot.queue.ifEmpty {
            snapshot.current?.let(::listOf).orEmpty()
        }
        if (sourceQueue.isEmpty()) {
            clearPersistedPlaybackSession()
            return
        }
        val sourceIndex = snapshot.currentIndex.coerceIn(sourceQueue.indices)
        val startIndex = (sourceIndex - RESTORED_PREVIOUS_TRACKS).coerceAtLeast(0)
        val endIndex = minOf(sourceQueue.size, startIndex + MAX_PERSISTED_QUEUE_SIZE)
        val persistedQueue = sourceQueue.subList(startIndex, endIndex).map {
            val isLocal = it.playbackUrl?.let { url ->
                url.startsWith("/") || url.startsWith("content://") || url.startsWith("file://")
            } == true
            if (isLocal) it else it.copy(playbackUrl = null, playbackMimeType = null)
        }
        val persistedIndex = sourceIndex - startIndex
        val signature = buildString {
            append(persistedQueue.size).append('|')
            append(persistedIndex).append('|')
            append(persistedQueue[persistedIndex].queueKey()).append('|')
            append(snapshot.positionMs / POSITION_PERSIST_INTERVAL_MS).append('|')
            append(snapshot.sourceLabel).append('|')
            append(snapshot.isEndlessQueue).append('|')
            append(snapshot.shuffleEnabled).append('|')
            append(snapshot.repeatMode).append('|')
            append(snapshot.speed)
        }
        if (signature == lastPersistedSignature) return
        val session = PersistedPlaybackSession(
            queue = persistedQueue,
            currentIndex = persistedIndex,
            positionMs = snapshot.positionMs.coerceAtLeast(0),
            sourceLabel = snapshot.sourceLabel,
            isEndlessQueue = snapshot.isEndlessQueue,
            shuffleEnabled = snapshot.shuffleEnabled,
            repeatMode = snapshot.repeatMode,
            speed = snapshot.speed,
        )
        lastPersistedSignature = signature
        val generation = ++persistenceGeneration
        playbackPersistenceJob?.cancel()
        playbackPersistenceJob = applicationScope.launch(Dispatchers.IO) {
            val encoded = runCatching { persistenceJson.encodeToString(session) }.getOrNull()
                ?: return@launch
            synchronized(playbackPersistenceLock) {
                if (generation == persistenceGeneration) {
                    playbackPreferences.edit().putString(PLAYBACK_SESSION_KEY, encoded).apply()
                }
            }
        }
    }

    private fun clearPersistedPlaybackSession() {
        pendingRestoredSession = null
        persistenceGeneration++
        playbackPersistenceJob?.cancel()
        playbackPersistenceJob = null
        lastPersistedSignature = ""
        synchronized(playbackPersistenceLock) {
            playbackPreferences.edit().remove(PLAYBACK_SESSION_KEY).apply()
        }
    }


    @MainThread
    private fun refresh(player: Player) {
        if (isCasting) return
        val previous = _state.value
        val selectedMediaId = previous.current?.mediaIdKey()
        val selectionIsResolving = selectedMediaId != null &&
            resolvingMediaIds[selectedMediaId] == playRequestGeneration.get()
        if ((selectionIsResolving || unavailableSkipJob?.isActive == true) &&
            (player.currentMediaItemIndex != previous.currentIndex || player.currentMediaItem?.mediaId != selectedMediaId)
        ) return
        val queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toPlayableTrack() }
        val current = player.currentMediaItem?.toPlayableTrack()
        if (queue.isEmpty() && current == null && previous.current != null) {
            // ExoPlayer briefly reports an empty timeline while a selected
            // track is being resolved. Keep the logical queue available for
            // transport controls until the new timeline is installed.
            return
        }
        val sameTrack = current?.let { it.title == previous.current?.title && it.artist == previous.current?.artist } == true ||
            (current?.videoId != null && current.videoId == previous.current?.videoId)
        val rawBuffering = player.playbackState == Player.STATE_BUFFERING ||
            (player.playWhenReady && player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0)
        // Screen-off continuity: while the selected track is under explicit
        // lossless-first resolution, ExoPlayer reports not-playing (loader
        // blocked in runBlocking) and refresh() would downgrade the state —
        // releasing the service wake/wifi locks mid-resolve so a locked
        // screen stalls until unlock. Preserve the intended playing state.
        val rawPlaying = player.isPlaying
        val isBuffering = rawBuffering || (selectionIsResolving && previous.isBuffering)
        val isPlayingState = rawPlaying || (selectionIsResolving && previous.isPlaying)
        // Never zero out a known duration when ExoPlayer briefly reports
        // TIME_UNSET (buffering / container not parsed yet): that reset froze
        // the bar at 0:00 and disabled seeking until the next event.
        val dur = effectiveDuration(player.duration, player, previous.durationMs)
        val pos = previous.positionMs
        _state.value = MusicPlayerState(
            current = current,
            queue = queue,
            currentIndex = player.currentMediaItemIndex.takeIf { player.mediaItemCount > 0 } ?: -1,
            sourceLabel = previous.sourceLabel,
            isEndlessQueue = discoverQueueActive || radioQueueActive,
            isPlaying = isPlayingState,
            isBuffering = isBuffering,
            positionMs = pos,
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0),
            durationMs = dur,
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            speed = player.playbackParameters.speed,
            bitrateKbps = previous.bitrateKbps.takeIf { sameTrack },
            audioCodec = previous.audioCodec.takeIf { sameTrack },
            isLossless = previous.isLossless && sameTrack,
            bitDepth = previous.bitDepth.takeIf { sameTrack },
            samplingRateKHz = previous.samplingRateKHz.takeIf { sameTrack },
            sleepTimerRemainingMs = sleepTimerDeadlineMs?.minus(SystemClock.elapsedRealtime())?.coerceAtLeast(0),
            error = if (isPlayingState) null else previous.error,
        )
        persistPlaybackSession()
        updateSignalPath()
    }

    private companion object {
        const val YOUTUBE_PROMOTE_BUDGET_MS = 12_000L
        const val DISCOVER_QUEUE_BATCH_SIZE = 16
        const val DISCOVER_QUEUE_REFILL_THRESHOLD = 8
        const val RADIO_QUEUE_BATCH_SIZE = 25
        const val RADIO_QUEUE_REFILL_THRESHOLD = 6
        const val POSITION_PERSIST_INTERVAL_MS = 5_000L
        const val MAX_PERSISTED_QUEUE_SIZE = 200
        const val RESTORED_PREVIOUS_TRACKS = 50
        const val PLAYBACK_PREFERENCES_NAME = "lastwave_playback_session"
        const val PLAYBACK_SESSION_KEY = "active_session"
        /** Persisted Bit-Perfect volume session (survives process restarts). */
        const val KEY_VOLUME_MANAGED = "bitperfect_volume_managed"
        const val KEY_VOLUME_SAVED = "bitperfect_volume_saved"
        /** Ticker-driven session persistence cadence (explicit state changes persist immediately). */
        const val TICKER_PERSIST_INTERVAL_MS = 2_000L
        /** Masks pre-seek position reads with the seek target while ExoPlayer lands. */
        const val SEEK_SETTLE_WINDOW_MS = 400L
        /** Signal-path report + stream-health sampling cadence while playing. */
        const val SIGNAL_PATH_TICK_MS = 1_000L
        const val MAX_PLAYBACK_RETRIES = 3
        /** A failure at/after this position means the track audibly played,
         *  so it must hold with tap-to-retry instead of auto-skipping. */
        const val MIN_AUDIBLE_PLAYBACK_MS = 1_000L
        /** Tail window where a pinned READY+playWhenReady state counts as a
         *  missed natural advance and triggers the lossless-first watchdog. */
        const val END_OF_TRACK_STALL_THRESHOLD_MS = 750L
        /** Debounce so STATE_ENDED + ticker watchdog can't churn generations. */
        const val AUTO_ADVANCE_DEBOUNCE_MS = 3_000L
        const val PLAYBACK_RETRY_BASE_DELAY_MS = 350L
        const val PLAYBACK_RETRY_JITTER_MS = 250L
        const val MEDIA_STREAM_CACHE_BYTES = 64L * 1024 * 1024
        const val NEXT_TRACK_PREFETCH_BYTES = 1L * 1024 * 1024
        const val NEXT_TRACK_PREFETCH_DELAY_MS = 500L
        /** Delayed start keeps current-track caching off the startup path. */
        const val CURRENT_TRACK_CACHE_START_DELAY_MS = 6_000L
        /** Bounded windows: progressive ahead-cache, never a full predownload. */
        const val CURRENT_TRACK_CACHE_CHUNK_BYTES = 2L * 1024 * 1024
        const val CURRENT_TRACK_CACHE_CHUNK_DELAY_MS = 500L
        /** Safety cap so one hi-res FLAC cannot fill the whole stream cache. */
        const val CURRENT_TRACK_CACHE_MAX_BYTES = 48L * 1024 * 1024
        const val CURRENT_TRACK_CACHE_MAX_SKIP_WINDOWS = 64
        const val MAX_PREPARED_STREAMS = 256
        const val MAX_KNOWN_DURATIONS = 512
        /** Cap for the explicit shuffle-Previous listening history. */
        const val MAX_PLAY_HISTORY = 100
        const val RESOLVED_URL_EXPIRY_MARGIN_MS = 2 * 60 * 1000L
        /** Offline license renewal attempt before giving up to streaming. */
        const val OFFLINE_LICENSE_RENEW_TIMEOUT_MS = 8_000L
        val PERMANENT_PLAYBACK_ERROR_CODES = setOf(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
        val SLEEP_TIMER_MINUTES = intArrayOf(0, 15, 30, 60)
    }
}

private fun PlayableTrack.withYoutubeArtwork(): PlayableTrack =
    if (artworkUrl.isNullOrBlank() && !videoId.isNullOrBlank()) {
        copy(artworkUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
    } else this

private fun PlayableTrack.toMediaItem(resolved: MusicPlayer.ResolvedStream? = null): MediaItem {
    val playbackUri = if (resolved != null) {
        Uri.parse(resolved.url)
    } else if (playbackUrl?.isNotBlank() == true) {
        if (playbackUrl.startsWith("/")) {
            Uri.fromFile(java.io.File(playbackUrl))
        } else {
            Uri.parse(playbackUrl)
        }
    } else if (!videoId.isNullOrBlank()) {
        Uri.Builder().scheme("lastwave").authority("youtube").appendPath(videoId)
            .appendQueryParameter("title", title)
            .appendQueryParameter("artist", artist)
            .build()
    } else {
        Uri.Builder().scheme("lastwave").authority("search")
            .appendQueryParameter("title", title)
            .appendQueryParameter("artist", artist)
            .build()
    }
    val mediaIdKey = mediaIdKey()
    return MediaItem.Builder()
        .setMediaId(mediaIdKey)
        .setUri(playbackUri)
        .apply {
            (resolved?.mimeType ?: playbackMimeType)?.takeIf(String::isNotBlank)?.let(::setMimeType)
            resolved?.let {
                setCustomCacheKey(it.cacheKey)
                // Segmented provider module: MPD uri + Widevine config so the
                // stock DASH source decrypts chunk-by-chunk via MediaCrypto.
                it.segmentedDrm?.let { descriptor ->
                    SegmentedDashBridge.drmConfigurationFor(descriptor)?.let(::setDrmConfiguration)
                }
            }
        }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri((artworkUrl?.takeIf(String::isNotBlank)
                    ?: (videoId ?: resolved?.youtubeCandidate?.videoId)?.let {
                        "https://i.ytimg.com/vi/$it/hqdefault.jpg"
                    })?.let(Uri::parse))
                .setIsPlayable(true)
                .build(),
        )
        .build()
}

    private fun PlayableTrack.mediaIdKey(): String = when {
    !playbackUrl.isNullOrBlank() -> "local:${playbackUrl}"
    !videoId.isNullOrBlank() -> videoId
    else -> "query:${artist.lowercase()}|${title.lowercase()}"
}

private fun MediaItem.toPlayableTrack(): PlayableTrack {
    val uriStr = localConfiguration?.uri?.toString()
    val localUri = when {
        uriStr?.startsWith("content://") == true || uriStr?.startsWith("file://") == true || uriStr?.startsWith("/") == true -> uriStr
        mediaId.startsWith("local:") -> mediaId.removePrefix("local:")
        else -> null
    }
    return PlayableTrack(
        title = mediaMetadata.title?.toString().orEmpty().ifBlank { "Unknown track" },
        artist = mediaMetadata.artist?.toString().orEmpty().ifBlank { "Unknown artist" },
        album = mediaMetadata.albumTitle?.toString(),
        artworkUrl = mediaMetadata.artworkUri?.toString(),
        videoId = mediaId.takeUnless { it.startsWith("query:") || it.startsWith("local:") },
        playbackUrl = localUri,
        playbackMimeType = localConfiguration?.mimeType,
    )
}

fun GeneratedTrack.toPlayableTrack(): PlayableTrack {
    val videoId = youtubeVideoIdOrNull()
    return PlayableTrack(
        title = name,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl ?: videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
        videoId = videoId,
    )
}

private fun PlayableTrack.queueKey(): String = "$title|$artist".lowercase()

/**
 * Samsung One UI ships vendor FLAC decoders (c2.sec.flac.decoder,
 * OMX.SEC.FLAC.Decoder, OMX.Exynos.FLAC.Decoder) that decode 24-bit hi-res
 * FLAC to packed 24-bit PCM while failing to advertise
 * KEY_PCM_ENCODING = ENCODING_PCM_24BIT_PACKED in the output MediaFormat.
 * The 3-byte samples are then consumed as 2-byte: buffers drain exactly
 * 3/2 faster — chipmunk pitch at ~1.5x speed — and broken Left/Right byte
 * boundaries surface as harsh digital noise. Only FLAC is affected;
 * Opus/AAC/MP3 play normally, which is why the fault isolated to Samsung
 * hardware playing lossless files.
 *
 * Demotes Samsung vendor decoders to the end of the FLAC codec list so the
 * reliable reference AOSP software decoder (c2.android.flac.decoder) wins.
 * Every other mime type keeps Android's default codec order untouched.
 */
@OptIn(UnstableApi::class)
private val accurateAudioMediaCodecSelector =
    MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        val decoderInfos = runCatching {
            MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder,
            )
        }.getOrDefault(emptyList())
        if (decoderInfos.isEmpty()) {
            emptyList()
        } else {
            // Deprioritize buggy vendor decoders (Samsung One UI / Exynos hardware decoders)
            // across audio MIME types to avoid misreported sample rates, 1.5x fast pitch shifts,
            // or digital boundary distortion. Standard AOSP/Google reference decoders take priority.
            decoderInfos.sortedBy { info -> audioDecoderPriority(info.name) }
        }
    }

/** 0 = trusted reference decoder, 1 = proprietary vendor decoder (demoted to avoid clock skew). */
private fun audioDecoderPriority(name: String): Int {
    val lower = name.lowercase()
    return if (lower.contains("sec.") || lower.contains("exynos")) 1 else 0
}

private fun PlayableTrack.searchQueueTitleKey(): String = title
    .lowercase()
    .replace(SEARCH_TITLE_VARIANT, " ")
    .replace(SEARCH_TITLE_NON_CHARACTER, "")

private val SEARCH_TITLE_VARIANT = Regex(
    """\s*[\[(][^)\]]*\b(?:official|video|audio|lyrics?|cover|karaoke|remaster(?:ed)?|live|version|edit|mix|slowed|reverb)[^)\]]*[])]""",
    RegexOption.IGNORE_CASE,
)
private val SEARCH_TITLE_NON_CHARACTER = Regex("[^\\p{L}\\p{N}]+")
