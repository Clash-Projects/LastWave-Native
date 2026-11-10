package com.lastwave.app.playback

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Network
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
import com.lastwave.app.playback.resolve.toStreamCache
import com.lastwave.app.playback.resolve.toYouTubeAudioStream
import com.lastwave.app.data.music.meetsBitrateLimit
import com.lastwave.app.data.music.racePlaybackCandidates
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
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
    internal val http: OkHttpClient,
    internal val innerTube: InnerTubeMusicApi,
    internal val recommendationRepository: RecommendationRepository,
    internal val losslessMusicApi: LosslessMusicApi,
    internal val moduleDrmFactory: ModuleDrmFactory,
    internal val moduleManager: com.lastwave.app.data.plugin.ModuleManager,
    internal val offlineLicense: com.lastwave.app.data.plugin.ModuleOfflineLicense,
    private val segBridge: SegmentedDashBridge,
    internal val settingsPreferences: SettingsPreferences,
    internal val equalizerPreferences: EqualizerPreferences,
    private val discoverRepository: DiscoverRepository,
    private val playlistRepository: PlaylistRepository,
    internal val nativeAudioEngine: dagger.Lazy<NativeAudioEngine>,
    internal val audioEffectsEngine: AudioEffectsEngine,
    internal val applicationScope: CoroutineScope,
    internal val downloadedTrackDao: dagger.Lazy<com.lastwave.app.data.local.db.DownloadedTrackDao>,
    internal val usbDacMonitor: UsbDacMonitor,
    internal val exclusiveUsbOutput: ExclusiveUsbOutput,
    private val songPlayStatsRepository: dagger.Lazy<com.lastwave.app.data.repository.SongPlayStatsRepository>,
    internal val internalDirectOutput: InternalDirectOutput,
    private val podcastProgressStore: com.lastwave.app.data.podcast.PodcastProgressStore,
    /** AutoEQ: started with the player so the saved correction is published before playback. */
    private val autoEqController: com.lastwave.app.data.autoeq.AutoEqController,
    internal val lyricsRepository: dagger.Lazy<com.lastwave.app.data.lyrics.LyricsRepository>,
    private val jamRepository: dagger.Lazy<com.lastwave.app.data.jam.JamRepository>,
    internal val autoMixCoordinator: dagger.Lazy<com.lastwave.app.playback.automix.engine.DualDeckTransportCoordinator>,
    internal val musicHapticsController: com.lastwave.app.playback.haptics.MusicHapticsController,
    private val networkMonitor: NetworkMonitor,
    private val ytMusicAuthManager: YtMusicAuthManager,
    private val loudnessPrefs: LoudnessPrefs,
) {

    private class VpnYoutubeLoginRequiredException(message: String) : java.io.IOException(message)

    val hapticsController: com.lastwave.app.playback.haptics.MusicHapticsController
        get() = musicHapticsController

    internal val appContext = context.applicationContext
    /**
     * Playback resolver is the only path allowed to publish a YouTube URL for
     * the selected track. Prefetch may fill the next track's cache entry and
     * must not replace a playback result.
     */
    internal val streamResolver = StreamResolveCoordinator(
        cacheDir = File(appContext.cacheDir, "playback_stream_cache"),
        scope = applicationScope,
        extract = { videoId -> innerTube.resolveAudioStream(videoId) },
    )
    private val streamResolutionWakeLock by lazy {
        (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LastWave:StreamResolutionWakeLock",
        )?.apply { setReferenceCounted(false) }
    }
    private val extractorsFactory: ExtractorsFactory by lazy {
        DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setMp3ExtractorFlags(
                Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING or
                    Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING
            )
            .setAdtsExtractorFlags(
                AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING
            )
    }
    internal var castPlayback: com.lastwave.app.playback.cast.CastPlayback? = null
    internal val isCasting: Boolean get() = castPlayback?.active == true || _state.value.isCasting

    fun initializeCast(): Boolean = initializeCastImpl()
    fun requestUsbPermission() = usbDacMonitor.requestPermission()
    fun usbDiagnosticReport(): String = exclusiveUsbOutput.diagnosticReport()

    internal val playbackPreferences = appContext.getSharedPreferences(
        PLAYBACK_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    internal val persistenceJson = Json { ignoreUnknownKeys = true }
    internal var lastPersistedSignature = ""
    internal var playbackPersistenceJob: Job? = null
    internal var pendingRestoredSession: PersistedPlaybackSession? = null
    @Volatile internal var persistenceGeneration = 0L
    internal val playbackPersistenceLock = Any()
    private var ticker: Job? = null
    internal var playRequest: Job? = null
    @Volatile private var pausedResolutionMediaId: String? = null
    /**
     * Keep-alive for a stream that died mid-track: re-resolves in place with
     * backoff while the track stays buffered, instead of parking the player on
     * "Unable to resolve" (mid-song stop). Cancelled by any explicit
     * navigation, pause or stop through [cancelPendingPlaybackResolution].
     */
    internal var playbackRecoveryJob: Job? = null
    /** Set by a play-button tap during a recovery backoff to retry immediately. */
    @Volatile private var recoveryRetryNow = false
    internal val playRequestGeneration = AtomicLong()
    /** Work that must not compete with the first audio buffer. Runs on READY. */
    internal var startupFollowUp: (() -> Unit)? = null
    internal var activeUpgradeJob: Job? = null
    internal var activeUpgradeMediaId: String? = null
    internal var activeUpgradeTargetQuality: Int? = null
    internal var activeUpgradeDeferred: Deferred<ResolvedStream?>? = null
    /** Selected track's fallback resolve, kept briefly after a provider win. */
    @Volatile internal var startupFallbackJob: Job? = null
    @Volatile internal var latestPlaybackSettings = MiscSettings()
    private data class AdaptiveQualityTarget(val network: Network, val profile: Int, val quality: Int)
    @Volatile private var adaptiveTargetQuality: AdaptiveQualityTarget? = null
    private val adaptiveController = AdaptiveQualityController()
    private data class MeasuredBandwidth(val network: Network?, val kbps: Int, val atMs: Long)
    @Volatile private var measuredBandwidth: MeasuredBandwidth? = null

    /**
     * ExoPlayer's default meter is this process-wide singleton, so listening to
     * it sees every deck's media downloads without changing the player setup.
     */
    private val playbackBandwidthSampling = lazy {
        androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(appContext).also { meter ->
            meter.addEventListener(android.os.Handler(Looper.getMainLooper())) { _, bytes, bitrateEstimate ->
                // Tiny reads (headers, cache hits) say nothing about the link.
                if (bytes >= MIN_BANDWIDTH_SAMPLE_BYTES && bitrateEstimate > 0L) {
                    val previous = measuredBandwidth
                    val sample = MeasuredBandwidth(
                        network = networkMonitor.activeRoute.value.network,
                        kbps = (bitrateEstimate / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        atMs = SystemClock.elapsedRealtime(),
                    )
                    // Servers pace a stream to a few times its bitrate, so a
                    // low-tier song reads as a slow link (776 kbps on 100 Mbps
                    // Wi-Fi) and blocked every upgrade. Throughput is a lower
                    // bound on the link: keep the route's best fresh sample.
                    val best = previous?.takeIf {
                        it.network == sample.network && it.kbps > sample.kbps &&
                            sample.atMs - it.atMs <= MEASURED_BANDWIDTH_MAX_AGE_MS
                    }
                    measuredBandwidth = best ?: sample
                    if (previous == null || sample.atMs - lastBandwidthLogMs >= 30_000L) {
                        lastBandwidthLogMs = sample.atMs
                        android.util.Log.i(
                            "MusicPlayer",
                            "[ADAPTIVE] measured ${sample.kbps}kbps from ${bytes / 1024}KB (route best ${(best ?: sample).kbps}kbps)",
                        )
                    }
                }
            }
        }
    }

    /** Measured speed on this route when recent, bounded by Android's link estimate. */
    private fun adaptiveBandwidthKbps(route: NetworkMonitor.ActiveRoute): Int? {
        val measured = measuredBandwidth?.takeIf {
            it.network == route.network && SystemClock.elapsedRealtime() - it.atMs <= MEASURED_BANDWIDTH_MAX_AGE_MS
        }?.kbps
        return StreamingQualityPolicy.combineBandwidth(measured, route.downstreamBandwidthKbps)
    }
    private var lastQualityDeferLogMs = 0L
    private var lastBandwidthLogMs = 0L
    @Volatile private var swappingMediaId: String? = null
    /** True while the standby is preparing or fading in for a quality hot-swap. */
    internal var hotSwapActive = false
    internal var queueEnrichmentJob: Job? = null
    private var recommendationJob: Job? = null
    internal var preloadJob: Job? = null
    private var searchWarmJob: Job? = null
    private var searchResultWarmJob: Job? = null
    internal var currentTrackCacheJob: Job? = null
    internal val resolutionRequests = ConcurrentHashMap<List<Any?>, Pair<Long, Deferred<ResolvedStream>>>()
    internal var discoverQueueLoadJob: Job? = null
    internal var discoverQueueActive = false
    internal var radioQueueLoadJob: Job? = null
    private val radioQueueMutex = Mutex()
    internal var radioQueueActive = false
    private val radioUsedSeeds = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var currentRadioContinuationToken: String? = null
    @Volatile
    private var currentRadioAutomixPlaylistId: String? = null
    internal var unavailableSkipJob: Job? = null
    internal val unavailableMediaIds = mutableSetOf<String>()
    /**
     * Consecutive failure count per mediaId for auto-skip gating. First
     * transient failure (timeout, throttled OPUS URL, slow first resolve)
     * holds with tap-to-retry; only proven-dead or repeatedly failing
     * items auto-advance. Cleared on audible success / new queue.
     * Main-thread only.
     */
    private val unavailableAttemptCounts = mutableMapOf<String, Int>()
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
    /** Last steady-state preload retry (elapsedRealtime); throttles the 30s ensure. */
    private var lastPreloadRetryMs = 0L
    /**
     * Debounce for the natural-end auto-advance safety net: ExoPlayer can
     * emit STATE_ENDED repeatedly (plus the ticker watchdog) for the same
     * stuck item. Without this, handleNaturalTrackEnd would re-launch
     * resolveAndPlayQueueItem every 60ms, churning generations.
     */
    private var lastAutoAdvanceKey: String? = null
    private var lastAutoAdvanceAtMs = 0L
    /**
     * Rendering watchdog (silent-advance safety net): ExoPlayer position at
     * the last ticker sample, buffer at the last sample, and when the
     * current stall began (0 = rendering or not monitored). See the ticker
     * check below for the full contract.
     */
    private var lastRenderPositionMs = -1L
    private var lastRenderBufferedMs = -1L
    private var renderStallSinceMs = 0L
    /** Same, for the inaudible-but-rendering branch + its position cursor. */
    private var inaudibleSinceMs = 0L
    private var lastAdvancingPositionMs = -1L
    /** Recovery attempts per mediaId, so a hopeless window stops at 2 and
     *  the existing error/unavailable machinery owns it from there. */
    private val silentRecoveries = mutableMapOf<String, Int>()
    /** Tail-pin watchdog cursor: window key + when the tail park began
     *  (0 = not parked). A track change always starts a fresh grace. */
    private var tailPinnedKey: String? = null
    private var tailPinnedSinceMs = 0L
    private var sleepTimerDeadlineMs: Long? = null
    private var sleepTimerStep = 0
    @Volatile
    internal var crossfadeEnabled = false
    /** Settings -> Autoplay similar songs. Off: the queue ends where the user left it. */
    @Volatile internal var autoplaySimilarEnabled = true
    @Volatile
    internal var crossfadeDurationMs = 5_000L
    /**
     * Mirror of [MiscSettings.stopPlaybackOnTaskRemoved] for the playback
     * service, which cannot block on DataStore from onTaskRemoved(). The
     * default matches the preference's default so a task removal that races
     * the first settings emission still stops playback.
     */
    @Volatile
    // Off by default: a music player keeps playing after it is swiped out of
    // Recents. Users who turn the setting on get the stop behaviour.
    private var stopPlaybackOnTaskRemovedPref = false

    /**
     * Where the song was when [stopAndClear] stopped it while keeping the
     * session. stop() + clearMediaItems() make the player (and every later
     * progress tick) report 0, so resume() would restart the song from the
     * beginning; this is the position to start from instead.
     */
    private var resumeAfterStopPositionMs: Long? = null
    val stopPlaybackOnTaskRemoved: Boolean
        get() = stopPlaybackOnTaskRemovedPref
    internal var activePlayer: ExoPlayer? = null
    internal var secondaryPlayer: ExoPlayer? = null
    internal var secondaryNativeEngine: NativeAudioEngine? = null
    internal var secondaryEffects: AudioEffectsEngine? = null
    internal var outgoingPlayer: ExoPlayer? = null
    internal var overlapDurationMs = 0L
    internal var standbyQueue: List<MediaItem> = emptyList()
    internal var standbyIndex = C.INDEX_UNSET
    /**
     * True once the standby holds the upcoming queue and is preparing it, i.e.
     * [standbyIndex] / [standbyQueue] are live. Cleared by [cancelCrossfade]
     * and by the hand-off.
     */
    @Volatile internal var crossfadeStandbyArmed = false
    /**
     * True while an armed standby has not reached STATE_READY yet, so the
     * current-track downloader yields the connection. Only large lossless files
     * ran long enough to compete for bandwidth, which is why this never showed
     * up with Opus. Narrower than [crossfadeStandbyArmed] on purpose: once the
     * standby is buffered, background caching of the outgoing track resumes.
     */
    @Volatile internal var crossfadeStandbyPreparing = false
    /** Spacing for next-track resolve kicks, so a failing module is not hammered. */
    internal var lastCrossfadeResolveKickMs = 0L
    internal var lastCrossfadeResolveIndex = C.INDEX_UNSET
    internal var lastCrossfadeLogKey: String? = null
    internal var lastCrossfadeLogAtMs = 0L
    internal val _state = MutableStateFlow(MusicPlayerState())
    val state: StateFlow<MusicPlayerState> = _state.asStateFlow()
    /** Album identity for ALBUM loudness mode: one measurement spans the album. */
    private val loudnessAlbumKey: StateFlow<String?> = _state
        .map { it.current?.album?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }
        .distinctUntilChanged()
        .stateIn(applicationScope, SharingStarted.Eagerly, null)
    val chromeState: StateFlow<PlaybackChromeState> = state
        .map {
            PlaybackChromeState(
                current = it.current,
                sourceLabel = it.sourceLabel,
                isPlaying = it.isPlaying,
                isBuffering = it.isBuffering,
                queueSize = it.queue.size,
                shuffleEnabled = it.shuffleEnabled,
                deckBlendTelemetry = it.deckBlendTelemetry,
                isCrossfading = it.isCrossfading,
            )
        }
        .distinctUntilChanged()
        .stateIn(applicationScope, SharingStarted.Eagerly, PlaybackChromeState())
    /** See [ListPlaybackState]: what list rows need, nothing that ticks. */
    val listPlaybackState: StateFlow<ListPlaybackState> = chromeState
        .map {
            ListPlaybackState(
                current = it.current,
                sourceLabel = it.sourceLabel,
                isPlaying = it.isPlaying,
                shuffleEnabled = it.shuffleEnabled,
            )
        }
        .distinctUntilChanged()
        .stateIn(applicationScope, SharingStarted.Eagerly, ListPlaybackState())
    val progressState: StateFlow<PlaybackProgressState> = state
        .map {
            val dur = if (it.durationMs > 0L) it.durationMs else (it.current?.durationMs ?: findKnownDuration(it.current) ?: 0L)
            PlaybackProgressState(positionMs = it.positionMs, durationMs = dur)
        }
        .distinctUntilChanged()
        .stateIn(applicationScope, SharingStarted.Eagerly, PlaybackProgressState())
    /**
     * [state] with the per-tick playhead fields zeroed. UI that only needs
     * play/pause, volume, speed, sleep timer etc. collects this instead of
     * [state], so it no longer recomposes on every 60ms progress tick
     * (progress-driven UI reads [progressState] in leaf composables).
     */
    val stateWithoutProgress: StateFlow<MusicPlayerState> = state
        .map { it.withoutProgress() }
        .distinctUntilChanged()
        .stateIn(applicationScope, SharingStarted.WhileSubscribed(5_000), _state.value.withoutProgress())

    private var errorRetryCount = 0
    /** Local in-app playback volume (persisted). Never holds the cast level. */
    var appVolume: Float = 1f
        private set
    /** Last volume of the cast receiver; only meaningful while casting. */
    internal var castVolume: Float = 1f
    private var appVolumePersistJob: Job? = null
    private var retryMediaId: String? = null
    internal val losslessBypassMediaIds = ConcurrentHashMap.newKeySet<String>()
    // Written by the settings collector, read on the main thread.
    @Volatile
    internal var bitPerfectEnabled = false
    /** PCM rate reported by the decoder. Wins over a catalog tag that guessed 44.1. */
    @Volatile
    internal var decodedSampleRateHz = 0
    /**
     * Whether the native layer actually honors the current Bit-Perfect
     * request (read back after every [updateBitPerfectState], not just the
     * DataStore wish). The verdict ANDs this with the sinks' direct-path
     * state so the badge can never claim Bit-Perfect on a pref alone.
     */
    @Volatile
    internal var nativeBitPerfectApplied = false
    /** Previous system level saved when DAC mode auto-maxed it; -1 = untouched. */
    internal var savedSystemVolume = -1
    internal var dacVolumeManaged = false
    internal val audioManager: AudioManager? by lazy {
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    @Volatile
    internal var cachedSysMaxVolume: Int = -1
    @Volatile
    internal var cachedSysFixedVolume: Boolean? = null
    @Volatile
    internal var lastCachedSysVolume: Int = 0
    @Volatile
    internal var lastSysVolumeCheckMs: Long = 0L
    // ---- App-owned audio focus -------------------------------------------------
    // The player is built with handleAudioFocus=false (see createPlayer), so
    // Media3 never answers a focus change itself. Its automatic handling parks
    // playback in a suppression state â€” playWhenReady stays true, nothing
    // renders, the clock freezes, the pause icon shows, and it only recovers if
    // the other app sends AUDIOFOCUS_GAIN. Holders that never do are the
    // "song pauses and stays paused" bug. Owning focus here turns every state
    // into an explicit, recoverable one.
    private val audioFocusLock = Any()
    private val audioFocusAttributes: android.media.AudioAttributes? by lazy {
        runCatching {
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setHapticChannelsMuted(false)
                    }
                }
                .build()
        }.getOrNull()
    }
    private var audioFocusRequest: AudioFocusRequest? = null
    /** True while a focus pause waits for GAIN to restore playback. */
    @Volatile private var focusPauseAwaitingRecovery = false
    /** Guard so pause() inside pauseForAudioFocus doesn't drop the restore flag. */
    @Volatile private var pausingForAudioFocus = false
    /** Volume to re-apply when a duck ends; null when not ducked. */
    @Volatile private var unduckedVolume: Float? = null
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        handleAudioFocusChange(change)
    }
    /** Every ExoPlayer audio sink, so DAC routing/rate follow player rebuilds. */
    internal val audioSinks = CopyOnWriteArrayList<NativeProcessingAudioSink>()
    private val playerToSink = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Player, NativeProcessingAudioSink>())
    /** True while the usbdevfs exclusive session owns output. */
    @Volatile internal var usbExclusiveSinkActive = false
    val isUsbExclusiveActive: Boolean get() = usbExclusiveSinkActive || exclusiveUsbOutput.isActive()

    fun updateMusicHapticsConfig(newConfig: com.lastwave.app.playback.haptics.MusicHapticsConfig) {
        musicHapticsController.updateConfig(newConfig)
        applicationScope.launch {
            settingsPreferences.updateMusicHapticsConfig(newConfig)
        }
    }
    @Volatile internal var usbExclusivePrefEnabled = false
    /** Raw "USB Exclusive Output" toggle, before the Bit-Perfect gate. */
    @Volatile internal var usbExclusiveRawPref = false
    /** Fork: "Sortie directe interne" toggle (only effective with Bit-Perfect on). */
    @Volatile internal var internalDirectPrefEnabled = false
    /** Availability of the internal exclusive endpoint (settings + signal path). */
    val internalDirectAvailability: StateFlow<InternalDirectAvailability> = internalDirectOutput.availability
    val internalDirectSession: StateFlow<InternalDirectSession?> = internalDirectOutput.session

    /** Re-checks what the current output accepts (settings / signal-path page opened). */
    fun refreshInternalDirectAvailability() = internalDirectOutput.refreshAvailability(probe = true)

    fun internalDirectUnderruns(): Long = internalDirectOutput.underruns()

    val isInternalDirectPrefEnabled: Boolean get() = bitPerfectEnabled && internalDirectPrefEnabled
    /** System audio-effects mode pref (Settings -> Experimental, default OFF). */
    @Volatile internal var systemEffectsModePref = false
    /** Effective mode: pref ON and on a mixer route (bypass routes suspend). */
    @Volatile internal var systemEffectsEffective = false
    internal val systemEffectsBridge by lazy { SystemEffectsBridge(appContext) }
    /** AudioDeviceInfo id currently requested via setPreferredDevice, if any. */
    internal var routedDacDeviceId: Int? = null
    internal val healthTracker = StreamHealthTracker()
    internal var lastHealthTrackKey: String? = null
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
    @Volatile internal var playheadPosMs = 0L
    @Volatile internal var playheadWallMs = 0L
    @Volatile private var playheadMoving = false
    @Volatile private var playheadKey: String? = null
    internal val _signalPath = MutableStateFlow(SignalPathReport.initial())
    /** Verified signal-path report; BIT-PERFECT shows only when all checks pass. */
    val signalPath: StateFlow<SignalPathReport> = _signalPath.asStateFlow()
    val usbDacState: StateFlow<UsbDacMonitor.State> = usbDacMonitor.state
    internal val resolvingMediaIds = ConcurrentHashMap<String, Long>()
    internal val preparedStreams = ConcurrentHashMap<String, ResolvedStream>()

    /**
     * Stream resolved for a track we are about to hand over to ExoPlayer, plus
     * the mediaId it belongs to.
     *
     * [takeOverPlayback] calls `seekToDefaultPosition`, which fires
     * [onMediaItemTransition]; that callback resets the quality fields so the
     * pill can never show the previous song's format. With the decoder no longer
     * permitted to write depth, that reset was the last chance to wipe a
     * perfectly good resolved depth, and the republish that used to run
     * afterwards only fired when `preparedStreams[customCacheKey]` happened to
     * resolve. Stashing the stream here lets the transition reset hand the
     * depth straight back, so the pill is correct from the first frame
     * regardless of cache-key bookkeeping.
     */
    private val pendingQualities = ConcurrentHashMap<String, ResolvedStream>()

    internal fun stagePendingQuality(mediaId: String, stream: ResolvedStream) {
        pendingQualities[mediaId] = stream
    }

    /** Stashed stream for [mediaId], clearing the stash so it is used once. */
    private fun consumePendingQuality(mediaId: String): ResolvedStream? {
        return pendingQualities.remove(mediaId)
    }

    /**
     * Known track durations (ms) keyed by MediaItem customCacheKey and by
     * mediaId/videoId. ExoPlayer reports TIME_UNSET until it has parsed
     * enough of a throttled progressive stream to infer duration â€” for some
     * YouTube WebM/MP4 URLs that takes 30-40s, during which the progress bar
     * sat frozen at 0 with seeking disabled. Seeding the resolve-time
     * duration here (approxDurationMs / lossless durationSeconds / the exact
     * player duration once learned) keeps progress + seek alive from t=0.
     */
    private val knownDurations = ConcurrentHashMap<String, Long>()

    /**
     * Media id of the item that produced the currently published
     * [MusicPlayerState.durationMs]. A published duration is only reusable while
     * this still matches the item playing: without it, the outgoing track's
     * length became the incoming track's seek-bar ceiling *and* its crossfade
     * timer, which handed off to the next item seconds into a full-length track.
     * Written and read on the main thread alongside the state it describes.
     */
    internal var durationOwnerMediaId: String? = null
    private var isOverlapMixActive = false

    private val mediaCache: Cache by lazy {
        val cacheDir = java.io.File(appContext.cacheDir, "media_stream_cache")
        // Streamed audio and artwork share the Storage screen's selected cap.
        // The audio side receives whatever the artwork cache is not using.
        val evictor = AdaptiveCacheEvictor(CacheStoragePolicy.streamCacheLimitBytes())
        CacheStoragePolicy.setStreamLimitListener(evictor::setMaxBytes)
        val dbProvider = StandaloneDatabaseProvider(appContext)
        SimpleCache(cacheDir, evictor, dbProvider).also(evictor::attach)
    }

    /** Size of the bounded streamed-audio cache. Call from an IO dispatcher. */
    fun playbackCacheSpaceBytes(): Long = runCatching { mediaCache.cacheSpace }.getOrDefault(0L)

    /** Clear streamed bytes and resolved stream URLs without touching offline downloads. */
    fun clearPlaybackCache() {
        runCatching {
            mediaCache.keys.toList().forEach { key -> mediaCache.removeResource(key) }
        }
        streamResolver.cache.clear()
    }

    private val mediaHttpClient: OkHttpClient by lazy {
        // Share the resolver's dispatcher, DNS policy and connection pool with
        // YouTube extraction. YouTube may bind a googlevideo URL to the network
        // context that minted it; an independent media client can choose a
        // different route/address family, which often fails on full-tunnel VPNs.
        // Strip API-only interceptors and the metadata cache while retaining
        // the shared transport state.
        http.newBuilder()
            .cache(null)
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
            }
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Shared media transport. Defaults stay provider-neutral: each request
     * carries its own stream's headers (see [mediaRequestHeaders]), so a
     * YouTube client's headers can never ride along to a provider CDN or the
     * other way round. No factory User-Agent either, since Media3 appends it
     * after the per-request one.
     */
    private val httpUpstreamFactory: OkHttpDataSource.Factory by lazy {
        OkHttpDataSource.Factory(mediaHttpClient)
            .setUserAgent(null)
            .setContentTypePredicate(HttpDataSource.REJECT_PAYWALL_TYPES)
            .setDefaultRequestProperties(NEUTRAL_MEDIA_HEADERS)
    }

    private val cacheDataSourceFactory: CacheDataSource.Factory by lazy {
        val defaultDataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(appContext, httpUpstreamFactory)
        val chunkedUpstreamFactory = androidx.media3.datasource.DataSource.Factory {
            ChunkedRangeDataSource(defaultDataSourceFactory.createDataSource(), refresher = ::refreshRejectedYouTubeUrl)
        }
        CacheDataSource.Factory()
            .setCache(mediaCache)
            .setUpstreamDataSourceFactory(chunkedUpstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** Refused googlevideo URL -> the same-file replacement already minted for it. */
    private val refreshedYouTubeUrls = ConcurrentHashMap<String, ChunkedRangeDataSource.Replacement>()

    /**
     * Seamless mid-song URL swap, called on the loader thread when a chunk is
     * refused (403/410). Re-resolves the same video on another client and
     * accepts the result only if it is byte-identical in layout — same itag
     * and same content length — so the read continues at the same offset with
     * no error, no re-buffer and no gap. Anything else returns null and the
     * normal error/retry path takes over.
     */
    private fun refreshRejectedYouTubeUrl(failedUri: Uri): ChunkedRangeDataSource.Replacement? {
        val failedUrl = failedUri.toString()
        refreshedYouTubeUrls[failedUrl]?.let { return it }
        val candidate = preparedStreams.values.firstOrNull { it.url == failedUrl }?.youtubeCandidate
            ?: return null
        val itag = candidate.itag ?: return null
        val contentLength = candidate.contentLength?.takeIf { it > 0L } ?: return null
        val fresh = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(SEAMLESS_URL_REFRESH_TIMEOUT_MS) {
                runCatching {
                    // Cools down the client that minted the refused URL, so the
                    // re-resolve lands on a different one.
                    innerTube.reportPlaybackFailure(candidate.videoId, candidate)
                    innerTube.resolveAudioStream(candidate.videoId)
                }.getOrNull()
            }
        } ?: return null
        if (fresh.itag != itag || fresh.contentLength != contentLength || fresh.url == failedUrl) {
            android.util.Log.i(
                "MusicPlayer",
                "[SEAMLESS] no same-file replacement for ${candidate.videoId} " +
                    "(itag ${fresh.itag}/$itag, length ${fresh.contentLength}/$contentLength)",
            )
            return null
        }
        android.util.Log.i(
            "MusicPlayer",
            "[SEAMLESS] swapped refused URL for ${candidate.videoId}: ${candidate.clientProfile} -> ${fresh.clientProfile}",
        )
        val replacement = ChunkedRangeDataSource.Replacement(
            uri = Uri.parse(fresh.url),
            requestHeaders = fresh.requestHeaders + ("User-Agent" to fresh.userAgent),
        )
        if (refreshedYouTubeUrls.size > MAX_REFRESHED_URL_ENTRIES) refreshedYouTubeUrls.clear()
        refreshedYouTubeUrls[failedUrl] = replacement
        return replacement
    }

    /** Headers for one media request, scoped to the stream that owns the URL. */
    private fun mediaRequestHeaders(stream: ResolvedStream?): Map<String, String> = buildMap {
        putAll(NEUTRAL_MEDIA_HEADERS)
        stream?.requestHeaders?.forEach { (name, value) ->
            if (!name.equals("User-Agent", ignoreCase = true)) put(name, value)
        }
        val ua = stream?.userAgent?.takeIf(String::isNotBlank)
            ?: stream?.requestHeaders?.entries?.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
            ?: YOUTUBE_WEB_USER_AGENT
        put("User-Agent", ua)
    }

    /**
     * Persists the resume position of the playing podcast episode. Only
     * podcast episodes are stored: songs have no resume semantics. Positions
     * under 10s are ignored (a tap-through is not a listen) and anything
     * past 95% counts as finished.
     */
    private fun persistPodcastProgress(
        track: PlayableTrack?,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean = false,
    ) {
        val episode = track?.takeIf { it.mediaType == MediaType.PODCAST_EPISODE } ?: return
        val videoId = episode.videoId?.takeIf { it.isNotBlank() } ?: return
        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                podcastProgressStore.save(
                    videoId = videoId,
                    showId = episode.showId,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    completed = completed,
                )
            }.onFailure { error ->
                android.util.Log.w("MusicPlayer", "Failed to save podcast progress", error)
            }
        }
    }

    /** Snapshot of the currently playing episode, for progress checkpoints. */
    private fun checkpointPodcastProgress(completed: Boolean = false) {
        val snapshot = _state.value
        val track = snapshot.current ?: return
        if (track.mediaType != MediaType.PODCAST_EPISODE) return
        val livePosition = runCatching { player.currentPosition.coerceAtLeast(0L) }.getOrNull()
        val position = livePosition?.takeIf { it > 0L } ?: snapshot.positionMs
        val duration = snapshot.durationMs.takeIf { it > 0L }
            ?: track.durationMs
            ?: 0L
        persistPodcastProgress(track, position, duration, completed)
    }

    /**
     * Feeds SongPlayStatsRepository â€” the real "how much did you actually
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
        if (previousTrack.mediaType == MediaType.PODCAST_EPISODE ||
            !previousTrack.showId.isNullOrBlank() ||
            previousTrack.artist.equals("Podcast", ignoreCase = true)
        ) {
            // Podcast episodes are not songs: they never feed the local taste
            // graph. They only persist a resume position. A natural advance
            // (the episode played out) counts as finished; an explicit skip
            // does not.
            val playedRatio = if (previousDurationMs > 0L) {
                previousPositionMs.toDouble() / previousDurationMs.toDouble()
            } else {
                0.0
            }
            val finished = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                playedRatio >= PODCAST_FINISHED_RATIO
            persistPodcastProgress(previousTrack, previousPositionMs, previousDurationMs, completed = finished)
            return
        }
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

    internal val listener: Player.Listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player === this@MusicPlayer.player) {
                refresh(player)
            }
        }
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            if (player !== this@MusicPlayer.player) return
            val audioFormats = tracks.groups
                .filter { it.type == C.TRACK_TYPE_AUDIO }
                .flatMap { group ->
                    (0 until group.length).map { index ->
                        val format = group.getTrackFormat(index)
                        "${format.sampleMimeType ?: "?"}/${format.codecs ?: "?"}" +
                            ":selected=${group.isTrackSelected(index)}" +
                            ":supported=${group.isTrackSupported(index)}"
                    }
                }
            android.util.Log.i(
                "LastWaveDecode",
                "track='${_state.value.current?.title}' audioFormats=${audioFormats.joinToString().ifBlank { "none" }}",
            )
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (isCasting) return
            val live = player
            // Ignore stale outgoing/standby player callbacks: only the
            // active player owns auto-advance. Outgoing ENDED is expected
            // after a handoff and is cleaned by cancelCrossfade().
            if (player.playbackState != playbackState) return
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($playbackState)"
            }
            val audioTracks = live.currentTracks.groups
                .filter { it.type == C.TRACK_TYPE_AUDIO }
                .flatMap { group ->
                    (0 until group.length).map { index ->
                        val format = group.getTrackFormat(index)
                        "${format.sampleMimeType ?: "?"}/${format.codecs ?: "?"}" +
                            ":selected=${group.isTrackSelected(index)}" +
                            ":supported=${group.isTrackSupported(index)}"
                    }
                }.joinToString().ifBlank { "none" }
            android.util.Log.i("MusicPlayer", "Playback state changed: $stateName, isPlaying=${player.isPlaying}, currentPos=${player.currentPosition}ms / ${player.duration}ms, bufferedPos=${player.bufferedPosition}ms, track='${_state.value.current?.title}', audioTracks=$audioTracks")
            if (playbackState == Player.STATE_BUFFERING) {
                // BUFFERING begins preparation; only READY ends the startup interval.
                android.util.Log.w("MusicPlayer", "Track BUFFERING / STALLED: '${_state.value.current?.title}' at ${live.currentPosition}ms (buffered=${live.bufferedPosition}ms)")
            }
            if (playbackState == Player.STATE_READY) {
                PlaybackStartupLog.prepareEnd()
                PlaybackStartupLog.ready()
                onMain { runStartupFollowUp() }
                // Started paused: nothing competes for bandwidth any more.
                onMain { if (!player.playWhenReady) releaseDeferredPreload() }
            }
            // Natural-end safety net: ExoPlayer auto-advances while a next
            // window exists, but when the queue truly ends â€” or the next
            // placeholder failed to open and ExoPlayer gave up â€” playback
            // parks in STATE_ENDED with isPlaying=false and never moves.
            // Users saw "song is over, never skips to next". Force a
            // lossless-first advance; the call is debounced and a no-op
            // when ExoPlayer already moved on.
            if (playbackState == Player.STATE_ENDED) {
                checkpointPodcastProgress(completed = true)
                if (live.repeatMode == Player.REPEAT_MODE_ONE || _state.value.repeatMode == Player.REPEAT_MODE_ONE) {
                    onMain {
                        runCatching {
                            live.seekTo(live.currentMediaItemIndex, 0L)
                            live.prepare()
                            live.play()
                        }
                        resetPlayhead(0L, _state.value.current?.mediaIdKey())
                        _state.update { it.copy(positionMs = 0L, isPlaying = true) }
                    }
                } else {
                    android.util.Log.i("MusicPlayer", "Playback STATE_ENDED for '${_state.value.current?.title}' -> advancing to next")
                    onMain { handleNaturalTrackEnd() }
                }
            }
            refresh(live)
        }
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // A seek flushes the sink; queued haptic pulses belong to audio that
            // will never play and the tap's timeline must re-anchor. Gapless
            // auto-transitions keep the timeline continuous.
            if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                musicHapticsController.onPositionDiscontinuity()
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            musicHapticsController.onIsPlayingChanged(isPlaying, player.volume)
            outgoingPlayer?.playWhenReady = isPlaying
            if (isPlaying) {
                onMain { releaseDeferredPreload() }
                unavailableSkipJob?.cancel()
                unavailableSkipJob = null
                player.currentMediaItem?.mediaId?.let {
                    unavailableMediaIds.remove(it)
                    unavailableAttemptCounts.remove(it)
                }
                resetRenderWatchdog()
            }
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, playWhenReadyChangeReason: Int) {
            // Hold focus exactly while playback is intended: requesting it
            // anywhere else just fights the other apps that own focus.
            //
            // A pause caused by a *focus change* must not abandon the request:
            // the platform only delivers AUDIOFOCUS_GAIN back to the app that
            // still owns the lost request, so abandoning here killed
            // PauseTransiently's recovery path — the song paused for a call or
            // a notification ding and never resumed. restoreAfterAudioFocus()
            // drops the request itself on the user's own pause.
            if (playWhenReady) requestMediaAudioFocus()
            else if (!pausingForAudioFocus) abandonMediaAudioFocus()
            // Every pause / play path (button, notification, headset, focus
            // loss, a new song) lands here: an AutoMix blend must pause and
            // resume with the transport instead of finishing on its own.
            runCatching { autoMixCoordinator.get().setTransportPaused(!playWhenReady) }
        }
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _state.update { it.copy(speed = playbackParameters.speed, pitch = playbackParameters.pitch) }
        }
        override fun onVolumeChanged(volume: Float) {
            musicHapticsController.onIsPlayingChanged(player.isPlaying, volume)
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (isCasting) return
            val targetPlayer = player
            // In-place quality swap replaces the playing item with the same
            // song at the same position from a better source. It reports as
            // a playlist change but is not the queue moving on: skip history,
            // scrobble and re-resolve bookkeeping for it.
            if ((reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ||
                 reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) &&
                mediaItem?.mediaId != null &&
                mediaItem.mediaId == swappingMediaId
            ) {
                swappingMediaId = null
                return
            }
            recordLocalListenSignal(reason)
            // Settle target or sanitize raw position from ExoPlayer
            val rawPos = player.currentPosition.coerceAtLeast(0L)
            val seekSettling = lastSeekTargetMs >= 0L &&
                (SystemClock.elapsedRealtime() - lastSeekAtElapsedMs <= SEEK_SETTLE_WINDOW_MS)
            val targetPos = when {
                seekSettling -> lastSeekTargetMs
                else -> if (rawPos > 1_500L && reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && !autoMixCoordinator.get().telemetry.value.isArmedOrBlending) 0L else rawPos
            }
            lastSeekTargetMs = -1L
            playheadPosMs = targetPos
            playheadWallMs = SystemClock.elapsedRealtime()
            if (mediaItem == null) return
            // Natural advances (track end, repeat-all wrap, crossfade
            // handoff) are the only transitions the explicit next()/queue-tap
            // paths don't record â€” manual seeks arrive as SEEK, not AUTO.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                recordHistory(_state.value.current?.mediaIdKey())
            }
            losslessBypassMediaIds.retainAll(setOf(mediaItem.mediaId))
            silentRecoveries.remove(mediaItem.mediaId)
            if (retryMediaId != mediaItem.mediaId) {
                retryMediaId = mediaItem.mediaId
                errorRetryCount = 0
            }
            val currentIndex = player.currentMediaItemIndex
            resetRenderWatchdog()
            val currentTrack = mediaItem.toPlayableTrack()
            val currentQueue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toPlayableTrack() }
            // The stream we staged for this exact mediaId, if any. Taken
            // before the reset so the badge survives it.
            val stagedQuality = consumePendingQuality(mediaItem.mediaId)
                ?: currentTrack.mediaIdKey().let(::consumePendingQuality)
                ?: currentTrack.videoId?.let(::consumePendingQuality)
            val itemStream = mediaItem.localConfiguration?.customCacheKey?.let(preparedStreams::get)
                ?: preparedStreams.values.firstOrNull { it.url == mediaItem.localConfiguration?.uri?.toString() }
            val streamCandidate = stagedQuality ?: itemStream
            val requestedQuality = selectedStreamingQuality(latestPlaybackSettings)
            val stream = streamCandidate
                ?.takeIf { isStreamAllowedForQuality(it, requestedQuality, latestPlaybackSettings) }
                ?: findPreparedStreamFor(currentTrack, currentTrack.videoId, currentTrack.mediaIdKey() in losslessBypassMediaIds)
            val profileMismatch = streamCandidate != null &&
                !isStreamAllowedForQuality(streamCandidate, requestedQuality, latestPlaybackSettings)
            _state.update {
                it.copy(
                    current = currentTrack,
                    currentIndex = currentIndex,
                    positionMs = targetPos,
                    bufferedPositionMs = targetPlayer.bufferedPosition.coerceAtLeast(0L),
                    durationMs = effectiveDuration(targetPlayer.duration, targetPlayer, it.durationMs),
                    queue = if (currentQueue.isNotEmpty()) currentQueue else it.queue,
                    isBuffering = true,
                    error = null,
                    audioCodec = stream?.audioCodec,
                    bitrateKbps = stream?.bitrateKbps,
                    isLossless = stream?.isLossless == true,
                    bitDepth = stream?.bitDepth,
                    samplingRateKHz = if (isSpatialAudioCodec(stream?.audioCodec)) 48.0 else stream?.samplingRateKHz,
                )
            }
                decodedSampleRateHz = 0
                stream?.let { s ->
                    publishResolvedQuality(s, expectedMediaId = mediaItem.mediaId)
                    applyDacRoutingFor(dacRateFor(s), s.audioCodec)
                    if (!s.isLossless && s.audioCodec != "DOLBY ATMOS") {
                        scheduleQualityUpgrade(
                            track = currentTrack,
                            expectedMediaId = mediaItem.mediaId,
                            generation = playRequestGeneration.get(),
                            currentStream = s,
                        )
                    }
                } ?: run {
                    applyDacRoutingFor(currentSourceRateHz())
                }
                if (outgoingPlayer == null) cancelCrossfade()
                // Queue placeholders are intentionally non-playable until
                // their signed stream has been resolved. Resolve an item
                // before Media3 can attempt to open its lastwave:// URI.
                val prepared = stream
                    ?: findPreparedStreamFor(currentTrack, currentTrack.videoId, currentTrack.mediaIdKey() in losslessBypassMediaIds)
                val isLocal = currentTrack.isLocalTrack() || mediaItem.mediaId.startsWith("local:")
                val hasNonPlaceholderUri = mediaItem.localConfiguration?.uri?.scheme?.let { it != "lastwave" } == true
                val isStreamExpired = prepared?.isExpired() == true
                val needsResolution = !hasNonPlaceholderUri || (!isLocal && isStreamExpired) || (!isLocal && profileMismatch)
                if (needsResolution) {
                    // During lazy-player construction a restored queue is
                    // installed before the lazy value is published. Defer
                    // resolution until the first explicit playback action.
                    if (playerDelegate.isInitialized() && targetPlayer.playWhenReady) {
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
                scheduleDeferredRecommendations()
                if (player.repeatMode != Player.REPEAT_MODE_ONE && _state.value.repeatMode != Player.REPEAT_MODE_ONE) {
                    val nextIndex = if (player.shuffleModeEnabled) {
                        player.nextMediaItemIndex
                    } else {
                        currentIndex + 1
                    }
                    if (nextIndex != C.INDEX_UNSET && nextIndex in 0 until player.mediaItemCount && nextIndex != currentIndex) {
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
            val isGenuineLocal = currentTrack?.isLocalTrack() == true || failedMediaId?.startsWith("local:") == true
            val failedLocalStream = isGenuineLocal && player.currentMediaItem?.localConfiguration?.uri?.scheme in setOf("file", "content")
            val isCachedManifest = player.currentMediaItem?.localConfiguration?.uri?.toString()?.let {
                it.contains("tidal_mpd") || it.contains("segdrm_mpd")
            } == true
            val failedLosslessStream = !failedLocalStream && (
                rejectedStream?.isLossless == true || customCacheKey?.startsWith("lossless:") == true ||
                    isCachedManifest || (rejectedStream == null && _state.value.isLossless)
                )
            val rejectedYouTubeCandidate = rejectedStream?.youtubeCandidate
            val videoId = rejectedYouTubeCandidate?.videoId ?: trackVideoId
            val httpStatus = error.httpStatusCodeOrNull()

            if (isGenuineLocal) {
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

            val isTransientIo = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                httpStatus == 408
            val isFatalHttp = httpStatus == 401 || httpStatus == 403 || httpStatus == 404 || httpStatus == 410 || httpStatus == 416
            if (errorRetryCount == 0 && isTransientIo && !isFatalHttp && (!failedLosslessStream || playedAudibly) &&
                rejectedStream != null && !rejectedStream.isExpired() && failedIndex in 0 until player.mediaItemCount
            ) {
                errorRetryCount = 1
                android.util.Log.i("MusicPlayer", "Transient network glitch for '${currentTrack?.title}', retrying same stream at ${currentPos}ms")
                player.prepare()
                player.seekTo(failedIndex, currentPos)
                player.play()
                return
            }

            // Transport failures (expired signed URL, 401/403/410, network
            // blip) say nothing about the addon's FLAC being unplayable: the
            // addon can mint a fresh URL. Only decode/format failures may pin
            // the track to YouTube for the session.
            val losslessTransportFailure = failedLosslessStream && isTransportFailure(error)
            if (failedLosslessStream) {
                if (failedMediaId != null && !losslessTransportFailure &&
                    (errorRetryCount > 0 || !isRetryablePlaybackFailure(error))
                ) {
                    losslessBypassMediaIds += failedMediaId
                }
            } else if (!failedLocalStream && !videoId.isNullOrBlank()) {
                if (rejectedYouTubeCandidate == null || httpStatus == 403 || httpStatus == 416) {
                    innerTube.invalidateCache(videoId)
                }
                innerTube.reportPlaybackFailure(videoId, rejectedYouTubeCandidate)
                currentTrack?.playbackIdentity()?.takeIf { it.youtubeVideoId == videoId }?.let { identity ->
                    streamResolver.cache.invalidate(identity)
                }
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
                cancelActiveUpgrade()
                playRequest = applicationScope.launch(Dispatchers.IO) {
                    var retryResolutionFailure: Throwable? = null
                    try {
                        rejectedStream?.cacheKey?.let { cacheKey ->
                            runCatching { mediaCache.removeResource(cacheKey) }
                            preparedStreams.remove(cacheKey)
                        }
                        val retryDelayMs = if (failedLocalStream || (failedLosslessStream && !playedAudibly)) {
                            0L
                        } else {
                            playbackRetryDelayMs(error, retry)
                        }
                        if (retryDelayMs > 0L) delay(retryDelayMs)
                        currentCoroutineContext().ensureActive()
                        val updated = currentTrack.copy(
                            playbackUrl = null,
                            playbackMimeType = null,
                        )
                        val stream = withFallbackAttempts {
                            resolveTrackAudioStream(
                                track = updated,
                                allowLocalDownloads = false,
                                videoId = videoId,
                                allowLossless = failedMediaId !in losslessBypassMediaIds,
                                excludedLosslessUrls = if (failedLosslessStream) {
                                    setOfNotNull(rejectedStream?.url)
                                } else emptySet(),
                                // A dead stream must be replaced now: the fast
                                // module walk used to eat the whole throttle
                                // window and land on "Unable to resolve".
                                // Exception: the first retry of a lossless
                                // stream that died in transport mid-song
                                // re-asks the addon (raced against YouTube) so
                                // a hi-res listener isn't dropped to Opus by an
                                // expired URL. Before first audio the fallback
                                // plays now and the upgrade job retries the
                                // provider in the background.
                                losslessBudget = if (retry == 1 && losslessTransportFailure && playedAudibly) {
                                    LosslessBudget.Interactive
                                } else {
                                    LosslessBudget.Disabled
                                },
                            )
                        }
                        withContext(Dispatchers.Main.immediate) {
                            if (generation != playRequestGeneration.get() ||
                                player.currentMediaItemIndex != failedIndex ||
                                player.currentMediaItem?.mediaId != failedMediaId
                            ) {
                                return@withContext
                            }
                            if (failedIndex in 0 until player.mediaItemCount) {
                                registerPreparedStream(stream)
                                publishResolvedQuality(stream, expectedMediaId = failedMediaId)
                                applyDacRoutingFor(dacRateFor(stream))
                                logStreamEvent("player-retry", stream, retry = retry)
                                cacheCurrentTrackStream(stream)
                                learnQueueVideoId(failedIndex, updated, stream.youtubeCandidate?.videoId, stream.rematchedFromVideoId)
                                replaceMediaItemPreservingShuffle(failedIndex, updated.toMediaItem(stream))
                                lastSeekTargetMs = currentPos
                                lastSeekAtElapsedMs = SystemClock.elapsedRealtime()
                                player.seekTo(failedIndex, currentPos)
                                player.prepare()
                                player.play()
                                preloadNextQueueItem(failedIndex)
                                if (!stream.isLossless && stream.audioCodec != "DOLBY ATMOS") {
                                    scheduleQualityUpgrade(
                                        track = updated,
                                        expectedMediaId = failedMediaId ?: updated.mediaIdKey(),
                                        generation = generation,
                                        currentStream = stream,
                                    )
                                }
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
                        val retryProvenUnplayable = retryResolutionFailure != null &&
                            isExplicitlyUnplayableFailure(retryResolutionFailure)
                        // Mid-track death: keep the song alive. The single
                        // retry above was the fast path; this keeps
                        // re-resolving in place instead of stopping the track
                        // with "Unable to resolve".
                        if ((playedAudibly || isTransportFailure(error)) &&
                            !retryProvenUnplayable &&
                            !confirmedUnplayable &&
                            !isUnsupportedMediaFailure(error) &&
                            failedMediaId != null
                        ) {
                            startPlaybackRecoveryLoop(
                                failedIndex = failedIndex,
                                failedMediaId = failedMediaId,
                                track = currentTrack,
                                videoId = videoId,
                                generation = generation,
                                resumePositionMs = currentPos,
                                failedLosslessStream = failedLosslessStream,
                                rejectedUrl = rejectedStream?.url,
                            )
                            return@withContext
                        }
                        _state.update { it.copy(error = error.message ?: "Playback error (${error.errorCodeName})", isBuffering = false) }
                        scheduleUnavailableMediaSkip(
                            failedIndex = failedIndex,
                            failedMediaId = failedMediaId,
                            expectedGeneration = generation,
                            failure = retryResolutionFailure ?: error,
                            // The source failed and every YouTube attempt failed too.
                            allowAutoSkip = true,
                            exhausted = true,
                        )
                    }
                }
                _state.update { it.copy(isBuffering = true, error = null) }
                return
            }

            // Retries weren't even attempted (non-retryable error, or already
            // exhausted on a previous one). A track that was audibly playing
            // still goes through the keep-alive loop rather than stopping.
            if ((playedAudibly || isTransportFailure(error)) &&
                !confirmedUnplayable &&
                !isUnsupportedMediaFailure(error) &&
                currentTrack != null &&
                failedMediaId != null
            ) {
                startPlaybackRecoveryLoop(
                    failedIndex = failedIndex,
                    failedMediaId = failedMediaId,
                    track = currentTrack,
                    videoId = videoId,
                    generation = playRequestGeneration.get(),
                    resumePositionMs = currentPos,
                    failedLosslessStream = failedLosslessStream,
                    rejectedUrl = rejectedStream?.url,
                )
                return
            }
            _state.update { it.copy(error = error.message ?: "Playback error (${error.errorCodeName})", isBuffering = false) }
            val retriesExhausted = errorRetryCount >= MAX_PLAYBACK_RETRIES
            scheduleUnavailableMediaSkip(
                failedIndex,
                failedMediaId,
                failure = error,
                allowAutoSkip = retriesExhausted || shouldAutoSkipForPlaybackError(error, playedAudibly, confirmedUnplayable),
                exhausted = retriesExhausted,
            )
        }
    }

    // USB exclusive output is engaged from [applyDacRoutingFor] while
    // Bit-Perfect (or the USB Exclusive toggle) is on and a DAC is granted.
    // The sink graph is always NativeProcessingAudioSink so exclusive can
    // start mid-session without rebuilding ExoPlayer.
    private fun createAudioMediaSourceFactory(dataSourceFactory: DataSource.Factory): MediaSource.Factory {
        val drmProvider = DrmSessionManagerProvider { mediaItem ->
            val stream = mediaItem.localConfiguration?.customCacheKey?.let(preparedStreams::get)
            val descriptor = stream?.segmentedDrm
            if (descriptor?.drm == null) DrmSessionManager.DRM_UNSUPPORTED
            else moduleDrmFactory.sessionManagerFor(descriptor)
        }
        val defaultFactory = DefaultMediaSourceFactory(appContext, extractorsFactory)
            .setDataSourceFactory(dataSourceFactory)
            .setDrmSessionManagerProvider(drmProvider)
        val dashFactory = DashMediaSource.Factory(dataSourceFactory)
            .setManifestParser(com.lastwave.app.data.plugin.AlacDashManifestParser())
            .setDrmSessionManagerProvider(drmProvider)

        return object : MediaSource.Factory {
            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                val config = mediaItem.localConfiguration
                val mimeType = config?.mimeType.orEmpty()
                val extension = config?.uri?.lastPathSegment
                    ?.substringBefore('?')
                    ?.substringAfterLast('.', "")
                    ?.lowercase()
                    .orEmpty()
                val prepared = config?.customCacheKey?.let(preparedStreams::get)
                val isDash = mimeType == MimeTypes.APPLICATION_MPD ||
                    mimeType.contains("dash", ignoreCase = true) ||
                    extension == "mpd" ||
                    prepared?.mimeType == MimeTypes.APPLICATION_MPD ||
                    prepared?.segmentedDrm != null
                return if (isDash) {
                    dashFactory.createMediaSource(mediaItem)
                } else {
                    defaultFactory.createMediaSource(mediaItem)
                }
            }

            override fun getSupportedTypes(): IntArray = defaultFactory.supportedTypes

            override fun setDrmSessionManagerProvider(
                provider: DrmSessionManagerProvider,
            ): MediaSource.Factory {
                defaultFactory.setDrmSessionManagerProvider(provider)
                dashFactory.setDrmSessionManagerProvider(provider)
                return this
            }

            override fun setLoadErrorHandlingPolicy(
                policy: LoadErrorHandlingPolicy,
            ): MediaSource.Factory {
                defaultFactory.setLoadErrorHandlingPolicy(policy)
                dashFactory.setLoadErrorHandlingPolicy(policy)
                return this
            }
        }
    }

    internal fun createPlayer(
        engineProvider: () -> NativeAudioEngine? = { null },
        effects: AudioEffectsEngine = audioEffectsEngine,
        handleAudioFocus: Boolean = true,
    ): ExoPlayer {
        playbackBandwidthSampling.value
        val resolving = ResolvingDataSource.Factory(cacheDataSourceFactory) { dataSpec ->
            val placeholder = dataSpec.uri.takeIf { it.scheme == "lastwave" }
            val resolvedPlaceholder = placeholder?.let { uri ->
                val videoId = uri.lastPathSegment.takeIf { uri.host == "youtube" }
                val title = uri.getQueryParameter("title").orEmpty()
                val artist = uri.getQueryParameter("artist").orEmpty()

                val track = when {
                    !videoId.isNullOrBlank() -> _state.value.queue.firstOrNull { it.videoId == videoId }
                        ?: _state.value.current?.takeIf { it.videoId == videoId }
                        ?: PlayableTrack(
                            title = title,
                            artist = artist,
                            videoId = videoId,
                            internalTrackId = streamResolver.playback.requested
                                ?.takeIf { it.youtubeVideoId == videoId }
                                ?.trackId,
                        )
                    // Queue rows without a video id: Media3 can open the next one
                    // before its transition, so match on the row's own title and
                    // artist. Never fall back to whatever is currently playing.
                    else -> (_state.value.queue.asSequence() + listOfNotNull(_state.value.current))
                        .firstOrNull { it.videoId.isNullOrBlank() && it.title == title && it.artist == artist }
                        ?: PlayableTrack(title = title, artist = artist).takeIf { title.isNotBlank() }
                }?.let { resolvedTrack ->
                    resolvedTrack.copy(durationMs = resolvedTrack.durationMs ?: findKnownDuration(resolvedTrack))
                } ?: throw java.io.IOException("Refusing title-only placeholder resolve")
                // Media3 can open the next item before its transition callback.
                // Resolve queue placeholders on its loader thread as well.
                val bypassLossless = track.mediaIdKey() in losslessBypassMediaIds
                // An already-prepared stream for this track skips the blocking
                // re-resolve entirely (worst-case loader stall was 30-90s).
                val preparedHit = findPreparedStreamFor(track, videoId, bypassLossless)
                    ?.takeUnless { hit ->
                        val resolvedId = hit.youtubeCandidate?.videoId
                        !videoId.isNullOrBlank() && resolvedId != null && resolvedId != videoId
                    }
                if (preparedHit != null) {
                    android.util.Log.i("MusicPlayer", "[MEDIA3] loader prepared-hit '${track.title}' key=${preparedHit.cacheKey}")
                    applicationScope.launch(Dispatchers.Main.immediate) {
                        stagePendingQuality(track.mediaIdKey(), preparedHit)
                        track.videoId?.let { stagePendingQuality(it, preparedHit) }
                    }
                    preparedHit
                } else {
                    runCatching {
                        runBlocking(Dispatchers.IO) {
                            resolveTrackAudioStreamWithRetry(
                                track,
                                track.videoId,
                                allowLossless = !bypassLossless,
                                losslessBudget = if (bypassLossless) LosslessBudget.Disabled else LosslessBudget.Interactive,
                                // An upcoming item claiming playback is rejected as stale,
                                // retried by Media3 every 5 s, and ended up marked lossless-bypass.
                                claimPlayback = track.mediaIdKey() == _state.value.current?.mediaIdKey(),
                            ).also { resolved ->
                                logStreamEvent("loader-prepared", resolved, retry = 0)
                                android.util.Log.i("MusicPlayer", "[MEDIA3] loader resolved '${track.title}' key=${resolved.cacheKey} codec=${resolved.audioCodec}")
                                applicationScope.launch(Dispatchers.Main.immediate) {
                                    registerPreparedStream(resolved)
                                    stagePendingQuality(track.mediaIdKey(), resolved)
                                    track.videoId?.let { stagePendingQuality(it, resolved) }
                                    val isCurrentlyPlaying = track.mediaIdKey() == _state.value.current?.mediaIdKey()
                                    if (isCurrentlyPlaying) {
                                        publishResolvedQuality(resolved, expectedMediaId = track.mediaIdKey())
                                        // The loader never waits on the addon, so a
                                        // YouTube stream here must still be promoted
                                        // back to lossless via the hot-swap path
                                        // (the transition handler can miss it when
                                        // this registration lands after the transition).
                                        if (!bypassLossless && !resolved.isLossless && resolved.audioCodec != "DOLBY ATMOS") {
                                            scheduleQualityUpgrade(
                                                track = track,
                                                expectedMediaId = track.mediaIdKey(),
                                                generation = playRequestGeneration.get(),
                                                currentStream = resolved,
                                            )
                                        }
                                    }
                                    learnQueueVideoId(-1, track, resolved.youtubeCandidate?.videoId, resolved.rematchedFromVideoId)
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
                    .withRequestHeaders(mediaRequestHeaders(resolvedPlaceholder))
                stream != null -> dataSpec.withRequestHeaders(mediaRequestHeaders(stream))
                // Unmatched URLs (DASH segments, redirects) get neutral headers only.
                else -> dataSpec
            }
        }
        // The standby becomes the active player after every crossfade /
        // AutoMix / stream-upgrade swap and stays active for the following
        // songs, so it needs the same deep buffer: the old 15-30s window ran
        // dry on any network dip and stalled songs mid-way.
        val baseLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 45_000,
                /* maxBufferMs = */ 120_000,
                /* bufferForPlaybackMs = */ 250,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(15_000, true)
            .build()
        var builtSink: NativeProcessingAudioSink? = null
        // One haptics analyser per player sink. It is fed from
        // NativeProcessingAudioSink.handleBuffer: Media3 1.2.1 drops custom
        // AudioProcessors on its float output path, so a DefaultAudioSink tap
        // never receives audio here.
        val hapticTaps = mutableListOf<com.lastwave.app.playback.haptics.MusicHapticsAnalyzer>()
        val renderersFactory = object : DefaultRenderersFactory(appContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink {
                val fallbackSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(true)
                    .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                    .build()
                val engine = runCatching(engineProvider).getOrNull()
                val enhancedSink = try {
                    DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(true)
                        .setEnableAudioTrackPlaybackParams(true)
                        .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                        .build()
                } catch (error: Exception) {
                    android.util.Log.w("MusicPlayer", "Enhanced audio sink unavailable; using PCM16", error)
                    effects.setFallbackRequired(true)
                    DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(true)
                        .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                        .build()
                } catch (error: LinkageError) {
                    android.util.Log.w("MusicPlayer", "Enhanced audio sink linkage failed; using PCM16", error)
                    effects.setFallbackRequired(true)
                    DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(true)
                        .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                        .build()
                }
                if (engine?.isAvailable == true) {
                    effects.setFallbackRequired(false)
                } else {
                    effects.setFallbackRequired(true)
                }
                val processorEngine = engine ?: nativeAudioEngine.get()
                val sink = NativeProcessingAudioSink(
                    enhancedDelegate = enhancedSink,
                    fallbackDelegate = fallbackSink,
                    processor = NativePcmAudioProcessor(processorEngine),
                    onPlatformEffectsRequired = effects::setFallbackRequired,
                    usbOutput = if (handleAudioFocus) UsbBitPerfectOutput(audioManager) else null,
                    // The crossfade standby must not share the DAC. Its
                    // volume is 0, and a second configure() retunes the clock
                    // to the next track (44.1 PCM written into a 96 kHz alt).
                    exclusiveUsb = if (handleAudioFocus) exclusiveUsbOutput else null,
                    // Same rule as USB: the crossfade standby never claims the endpoint.
                    internalDirect = if (handleAudioFocus) internalDirectOutput else null,
                    loudness = LoudnessStage(loudnessPrefs.settings, loudnessAlbumKey),
                ).also { createdSink ->
                    createdSink.hapticsAnalyzer = musicHapticsController.createAnalyzer().also(hapticTaps::add)
                    createdSink.onConfiguredFormat = { rateHz, _, _ ->
                        // The crossfade standby arms early and configures its
                        // sink while the OUTGOING track is still the live one.
                        // Only the active player may publish sample rate and
                        // re-settle bit-perfect/system volume, otherwise the
                        // next track's rate lands on the current track's pill.
                        if (handleAudioFocus && (activePlayer == null || (playerDelegate.isInitialized() && activePlayer === playerDelegate.value))) {
                            onDecodedPcmFormatConfigured(rateHz)
                        }
                    }
                    createdSink.bitDepthHintProvider = {
                        val stateSnapshot = _state.value
                        val hintRate = stateSnapshot.samplingRateKHz
                            ?: (if (decodedSampleRateHz > 0) decodedSampleRateHz / 1000.0 else 0.0)
                        // Output sizing biases UP safely: an unknown depth must not size a
                        // 16-bit output that would truncate a true 24-bit source,
                        // so only a genuinely reported depth is used as-is and
                        // everything else falls through to the up-biasing
                        // rate guess. A reported 16 is trusted even at >48kHz â€”
                        // 16/96 and 16/192 are real, and the sources no longer
                        // send a placeholder 16.
                        val hintKnown = stateSnapshot.bitDepth?.takeIf { it > 0 }
                        val fallbackDepth: Int? = when {
                            hintRate > 192.0 -> 32
                            hintRate > 48.0 -> 24
                            else -> null
                        }
                        parseQualityFromCodec(stateSnapshot.audioCodec)?.substringBefore('/')?.toIntOrNull()
                            ?: hintKnown
                            ?: inferBitDepth(stateSnapshot.copy(bitDepth = hintKnown))
                            ?: fallbackDepth
                    }
                    val isSpatial = isSpatialAudioCodec(_state.value.audioCodec)
                    createdSink.setBitPerfectRequested(!isSpatial && bitPerfectEnabled)
                    createdSink.syncExclusiveUsb(handleAudioFocus && !isSpatial && exclusiveUsbWanted())
                    createdSink.syncInternalDirect(handleAudioFocus && !isSpatial && internalDirectWanted())
                    audioSinks.add(createdSink)
                    runCatching {
                        routedDacDeviceId?.let { id -> findOutputDevice(id)?.let(createdSink::setPreferredDevice) }
                    }
                }
                builtSink = sink
                return sink
            }

            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>,
            ) {
                // Hardware/platform Dolby Atmos & Spatial audio MediaCodec renderer:
                // Placed ahead of FFmpeg so Android's native Dolby Atmos HAL and Spatializer
                // decode E-AC-3 JOC with hardware dialogue normalization, full volume
                // (no -8 dB FFmpeg downmix attenuation), and system spatial virtualization.
                // Standard codecs (FLAC, Opus, AAC, MP3, Vorbis) return FORMAT_UNSUPPORTED_TYPE
                // here so FFmpeg retains priority for them.
                val dolbySpatialRenderer = object : MediaCodecAudioRenderer(
                    context,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    audioSink,
                ) {
                    override fun supportsFormat(
                        mediaCodecSelector: MediaCodecSelector,
                        format: androidx.media3.common.Format,
                    ): Int {
                        val mime = format.sampleMimeType?.lowercase().orEmpty()
                        val isDolbyOrSpatial = mime.contains("eac3") || mime.contains("ec-3") ||
                            mime.contains("ac-3") || mime.contains("ac3") || mime.contains("ac4") ||
                            mime.contains("mha1") || mime.contains("mhm1")
                        if (!isDolbyOrSpatial) {
                            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
                        }
                        return super.supportsFormat(mediaCodecSelector, format)
                    }
                }
                out.add(dolbySpatialRenderer)

                super.buildAudioRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    audioSink,
                    eventHandler,
                    eventListener,
                    out,
                )
                // Explicit software decoder fallback for MediaTek / ColorOS
                if (FfmpegCompat.isUsable) try {
                    // Direct dependency (org.jellyfin.media3:media3-ffmpeg-decoder):
                    // construct it typed instead of via reflection.
                    out.add(
                        androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer(eventHandler, eventListener, audioSink),
                    )
                } catch (_: Throwable) {
                }
            }
        }.apply {
            // FFmpeg-first decoding, the Poweramp/VLC model: every codec the
            // bundled GPL build supports (FLAC 24/96-192, Opus, AAC, MP3,
            // Vorbis) decodes through the same battle-tested software path on
            // every device, eliminating per-OEM platform codec bugs that
            // surface as noise/distortion. setEnableDecoderFallback keeps the
            // platform decoder for anything FFmpeg rejects.
            setExtensionRendererMode(
                if (FfmpegCompat.isUsable) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
            )
            setEnableDecoderFallback(true)
            setEnableAudioTrackPlaybackParams(false)
            setMediaCodecSelector(accurateAudioMediaCodecSelector)
        }

        return ExoPlayer.Builder(appContext, renderersFactory)
            .setMediaSourceFactory(createAudioMediaSourceFactory(resolving))
            .setLoadControl(baseLoadControl)
            // Decode from a sync point but discard audio before the requested time.
            // Addon DASH segments are 10 s, so CLOSEST_SYNC could land seconds early.
            .setSeekParameters(SeekParameters.EXACT)
            .build().apply {
                musicHapticsController.registerPlayerTaps(this, hapticTaps)
                // Feed ReplayGain container tags into loudness normalization.
                // Additive listener: never touches playback, 0 dB without tags.
                addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    override fun onAudioPositionAdvancing(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        playoutStartSystemTimeMs: Long,
                    ) {
                        if (activePlayer != null && this@apply !== activePlayer) return
                        if (eventTime.windowIndex != currentMediaItemIndex) return
                        PlaybackStartupLog.audioAdvancing(playoutStartSystemTimeMs)
                    }

                    override fun onLoadError(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                        mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
                        error: java.io.IOException,
                        wasCanceled: Boolean,
                    ) {
                        if (wasCanceled || this@apply !== activePlayer) return
                        if (eventTime.windowIndex != currentMediaItemIndex) return
                        rescueFailingProviderStream(this@apply, error)
                    }

                    override fun onAudioInputFormatChanged(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        format: androidx.media3.common.Format,
                    ) {
                        // Crossfade standby or background players must NEVER publish format or rate to active state.
                        if (!handleAudioFocus || (activePlayer != null && this@apply !== activePlayer)) return
                        // Only the live player feeds the process-wide AudioFX gain: the standby's
                        // next-track tags used to replace the playing track's correction.
                        runCatching { effects.setReplayGainFromFormat(format) }
                        // Pre-buffering of the next track in the playlist must NOT leak into the active track's quality pill.
                        if (eventTime.windowIndex != currentMediaItemIndex) return
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
                        _state.update { snapshot ->
                            var updated = snapshot
                            val isSpatial = isSpatialAudioCodec(detectedCodec) || isSpatialAudioCodec(updated.audioCodec)
                            if (isSpatial) {
                                decodedSampleRateHz = 48000
                                updated = updated.copy(samplingRateKHz = 48.0)
                            } else if (rateHz > 0) {
                                decodedSampleRateHz = rateHz
                                val kHz = rateHz / 1000.0
                                if (updated.samplingRateKHz != kHz) updated = updated.copy(samplingRateKHz = kHz)
                            }
                            // The pill describes the SONG, not the decoder.
                            // format.pcmEncoding is the decoder's OUTPUT width: a
                            // 24-bit FLAC routinely decodes to 16-bit PCM on a
                            // device whose decoder (or mixer) cannot carry more,
                            // so reading it here reported the phone's limits as
                            // the file's depth. Only the source depth already
                            // resolved by publishResolvedQuality / the local
                            // retriever is used, through the shared hi-res rule
                            // (a rate above 48kHz is 24-bit in practice, so a
                            // reported 16 is corrected there).
                            val manifestDepth = player.currentMediaItem?.localConfiguration?.customCacheKey
                                ?.let(preparedStreams::get)?.url
                                ?.let { runCatching { renditionDepthFromStreamUrl(it) }.getOrNull() }
                            val sourceDepth = manifestDepth
                                ?: resolveDepthForDisplay(
                                    updated.bitDepth,
                                    updated.samplingRateKHz ?: (if (rateHz > 0) rateHz / 1000.0 else null),
                                ) ?: parseQualityFromCodec(updated.audioCodec)?.substringBefore('/')?.toIntOrNull()
                              ?: if ((detectedCodec == "FLAC" || updated.isLossless) && rateHz > 48_000) 24 else null
                            if (isSpatialAudioCodec(detectedCodec)) {
                                updated = updated.copy(audioCodec = detectedCodec, isLossless = false)
                            } else if (!isSpatialAudioCodec(updated.audioCodec) && detectedCodec != null) {
                                val currentIsExplicit = isExplicitQuality(updated.audioCodec, updated.bitDepth, updated.samplingRateKHz)
                                val detectedBadge = when {
                                    detectedCodec == "FLAC" && rateHz > 0 && sourceDepth != null -> {
                                        "$sourceDepth/${formatSampleRateKHz(rateHz / 1000.0)}kHz"
                                    }
                                    detectedCodec == "FLAC" &&
                                        ((sourceDepth ?: 0) > 16 || rateHz > 48_000) -> "HI-RES FLAC"
                                    else -> detectedCodec
                                }
                                val finalCodec = if (detectedCodec == "FLAC") {
                                    if (detectedBadge == "FLAC" && currentIsExplicit && updated.audioCodec?.contains("/") == true) {
                                        updated.audioCodec
                                    } else {
                                        detectedBadge
                                    }
                                } else if (currentIsExplicit && updated.audioCodec != "FLAC" && updated.audioCodec != "HI-RES FLAC" && updated.audioCodec != "LOSSLESS") {
                                    updated.audioCodec
                                } else {
                                    detectedBadge
                                }
                                updated = updated.copy(
                                    audioCodec = finalCodec,
                                    bitDepth = manifestDepth ?: updated.bitDepth ?: sourceDepth,
                                    bitrateKbps = if (detectedCodec == "FLAC") (updated.bitrateKbps ?: bitrate) else (updated.bitrateKbps ?: bitrate ?: if (detectedCodec == "OPUS") 160 else null),
                                    isLossless = detectedCodec == "FLAC",
                                )
                            }
                            android.util.Log.i("MusicPlayer", "AudioInputFormatChanged: mime=${format.sampleMimeType}, rate=${rateHz}Hz, bitrate=${format.bitrate}, detectedCodec=$detectedCodec -> qualityPill=[codec=${updated.audioCodec}, depth=${updated.bitDepth}, bitrate=${updated.bitrateKbps}kbps, rate=${updated.samplingRateKHz}kHz]")
                            updated
                        }
                        if (isSpatialAudioCodec(detectedCodec)) {
                            onMain { applyDacRoutingFor(48000, detectedCodec) }
                        }
                    }
                })
                // Focus is app-owned (see requestMediaAudioFocus): Media3's
                // automatic handling answers a transient loss with a playback
                // suppression that freezes the clock behind a pause icon and
                // never recovers when the holder withholds AUDIOFOCUS_GAIN.
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
                        .build(),
                    /* handleAudioFocus = */ false,
                )
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
                addListener(object : Player.Listener {
                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        effects.attach(audioSessionId)
                        runCatching { systemEffectsBridge.onSessionChanged(audioSessionId) }
                        musicHapticsController.onAudioSessionIdChanged(audioSessionId)
                    }
                })
            }.also { playerInstance ->
                builtSink?.let { s -> playerToSink[playerInstance] = s }
            }
    }

    private fun onDecodedPcmFormatConfigured(rateHz: Int) {
        if (rateHz <= 0) return
        // The sink's format.pcmEncoding (the callback's second argument) is the
        // DECODER's output width, not the song's depth, and is deliberately
        // dropped: the bundled FFmpeg decoder only ever emits
        // ENCODING_PCM_16BIT or ENCODING_PCM_FLOAT, so treating it as source
        // depth relabelled every 24-bit track as 16-bit a moment after playback
        // started. Only the sample rate (preserved end to end) is recorded; the
        // pill's depth comes from publishResolvedQuality / the local retriever,
        // which describe the song itself.
        decodedSampleRateHz = rateHz
        val rateKHz = rateHz / 1000.0
        _state.update { current ->
            val isSpatial = isSpatialAudioCodec(current.audioCodec)
            if (isSpatial) return@update current
            if (current.samplingRateKHz == rateKHz) current else current.copy(samplingRateKHz = rateKHz)
        }
        updateBitPerfectState()
    }

    internal val playerDelegate = ResettableLazy {
        createPlayer({ nativeAudioEngine.get() }, audioEffectsEngine, true)
            .also { restoredPlayer ->
                activePlayer = restoredPlayer
                musicHapticsController.bindToPlayer(restoredPlayer)
                restoredPlayer.addListener(listener)
                val restoredSession = pendingRestoredSession
                pendingRestoredSession = null
                if (restoredSession != null && restoredSession.queue.isNotEmpty()) {
                    val restoredIndex = restoredSession.currentIndex.coerceIn(restoredSession.queue.indices)
                    restoredPlayer.setMediaItems(
                        restoredSession.queue.map(PlayableTrack::toMediaItem),
                        restoredIndex,
                        restoredSession.positionMs.coerceAtLeast(0),
                    )
                    if (restoredSession.shuffleEnabled && restoredSession.queue.size > 1) {
                        // Resume the same shuffle instead of a fresh one that
                        // replays songs already heard before the restart.
                        val saved = restoredSession.shuffleOrder
                        val order = if (saved.size == restoredSession.queue.size &&
                            saved.sorted() == restoredSession.queue.indices.toList()
                        ) {
                            saved
                        } else {
                            listOf(restoredIndex) + restoredSession.queue.indices.filter { it != restoredIndex }.shuffled()
                        }
                        runCatching {
                            restoredPlayer.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), Random.nextLong()))
                        }
                    }
                    restoredPlayer.shuffleModeEnabled = restoredSession.shuffleEnabled
                    restoredPlayer.repeatMode = restoredSession.repeatMode.takeIf {
                        it in Player.REPEAT_MODE_OFF..Player.REPEAT_MODE_ALL
                    } ?: _state.value.repeatMode
                    val restoredSpeed = restoredSession.speed.coerceIn(0.25f, 3f)
                    val restoredPitch = restoredSession.pitch.coerceIn(0.25f, 3f)
                    restoredPlayer.playbackParameters = PlaybackParameters(
                        if (bitPerfectEnabled) 1f else restoredSpeed,
                        if (bitPerfectEnabled) 1f else restoredPitch,
                    )
                    restoredPlayer.pause()
                } else {
                    restoredPlayer.repeatMode = _state.value.repeatMode
                    restoredPlayer.shuffleModeEnabled = _state.value.shuffleEnabled
                }
                restoredPlayer.volume = appVolume
                runCatching { nativeAudioEngine.get().setOutputVolume(if (bitPerfectEnabled) 1f else appVolume) }
                runCatching { autoMixCoordinator.get().setBaseVolume(appVolume) }
            }
    }

    internal val player: ExoPlayer
        get() = activePlayer ?: playerDelegate.value

    @MainThread
    fun release() {
        cancelCrossfade()
        activePlayer?.removeListener(listener)
        activePlayer?.release()
        activePlayer = null
        secondaryPlayer?.removeListener(listener)
        secondaryPlayer?.release()
        secondaryPlayer = null
        playerDelegate.reset()
    }

    init {
        applicationScope.launch {
            autoMixCoordinator.get().telemetry.collect { tel ->
                _state.update { it.copy(deckBlendTelemetry = tel) }
            }
        }
        val autoMixCoord = autoMixCoordinator.get()
        autoMixCoord.onPerformDeckSwap = { outgoing, incoming ->
            // Carry the exact play order across. Walking only from the
            // incoming song onward produced a partial order that failed the
            // size check, so the new deck kept its own random shuffle and
            // already-played songs came round again.
            val shuffleOrder = currentPlayOrder(outgoing)
            if (shuffleOrder.size == incoming.mediaItemCount && incoming.mediaItemCount > 0) {
                runCatching {
                    incoming.setShuffleOrder(DefaultShuffleOrder(shuffleOrder.toIntArray(), Random.nextLong()))
                }
            }
            incoming.shuffleModeEnabled = outgoing.shuffleModeEnabled
            incoming.repeatMode = outgoing.repeatMode
            incoming.playbackParameters = outgoing.playbackParameters
            crossfadeStandbyArmed = false
            crossfadeStandbyPreparing = false
            outgoingPlayer = outgoing
            activePlayer = incoming
            outgoing.removeListener(listener)
            outgoing.repeatMode = Player.REPEAT_MODE_OFF
            outgoing.shuffleModeEnabled = false
            incoming.addListener(listener)
            incoming.setAudioAttributes(incoming.audioAttributes, false)
            // The swap happens mid-blend; it must never un-pause the user.
            if (outgoing.playWhenReady) incoming.play() else incoming.pause()
            listener.onMediaItemTransition(incoming.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            refresh(incoming)
        }
        autoMixCoord.onTransitionComplete = {
            val toStop = outgoingPlayer
            outgoingPlayer = null
            toStop?.stop()
            toStop?.clearMediaItems()
        }
        setupSpatializerListener()
        runCatching { restorePlaybackSession() }.getOrElse { error ->
            // A corrupt session or OEM media-stack failure must not become a
            // permanent launch-crash loop. Discard only the resumable session.
            android.util.Log.e("MusicPlayer", "Playback restore disabled", error)
            _state.update { live -> MusicPlayerState().withSubsystemStateFrom(live) }
            clearPersistedPlaybackSession()
            false
        }
        ticker = applicationScope.launch(Dispatchers.Main.immediate) {
            var lastTickerPersistMs = 0L
            var lastPodcastProgressMs = 0L
            while (true) {
                // The ticker is the sole driver of progress-bar updates: it
                // must never die (one escaping exception used to kill the
                // 60ms cadence permanently, leaving the bar to update only on
                // rare player events) and every path must reach the delay
                // below, including crossfade handoffs and track mismatches.
                var cadenceMs = 500L
                try {
                    val usbAlive = !exclusiveUsbOutput.isActive() || exclusiveUsbOutput.isStreamingAudio()
                    // Seek-bar clock keeps running while the track is
                    // BUFFERING (e.g. TIME_UNSET at track start while the
                    // container parses): the notification/system session
                    // extrapolates position from speed and keeps moving, so
                    // freezing the wall clock here pinned the main-player bar
                    // at 0:00 while the notification looked normal. Paused /
                    // ended states still freeze via isPlaying=false.
                    if (!isCasting) {
                        val livePlaying = player.isPlaying
                        val playingNow = _state.value.isPlaying &&
                            !exclusiveUsbOutput.isPaused() &&
                            usbAlive
                        val playhead = advancePlayhead(playingNow)
                        if (playhead != _state.value.positionMs) {
                            _state.update { it.copy(positionMs = playhead) }
                        }
                    }
                    if (!isCasting && exclusiveUsbOutput.isActive()) {
                        // Never touch ExoPlayer here. Its playback thread holds
                        // the player lock inside the blocking USB write, so a
                        // currentPosition read froze the seek bar after the
                        // first buffer and left drift on "measuringâ€¦".
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
                        if (outgoingPlayer != null) {
                            updateCrossfade(player.currentPosition.coerceAtLeast(0L))
                            cadenceMs = 50L
                        }
                        if (!isCurrentMediaMatch(player, _state.value.current) && !player.isPlaying) {
                            _state.update { it.copy(sleepTimerRemainingMs = remaining?.coerceAtLeast(0)) }
                            cadenceMs = 60L
                        } else {
                            val dur = effectiveDuration(player.duration, player, _state.value.durationMs)
                            val pos = _state.value.positionMs
                            val buf = player.bufferedPosition.coerceAtLeast(0)
                            val sleepRemaining = remaining?.coerceAtLeast(0)
                            maybeAdaptQualityForBuffer(player, pos, buf)

                            val upcomingIdx = player.nextMediaItemIndex
                            val nextItem = if (upcomingIdx != C.INDEX_UNSET && upcomingIdx in 0 until player.mediaItemCount) {
                                player.getMediaItemAt(upcomingIdx)
                            } else null
                            val nextTrk = nextItem?.toPlayableTrack()

                            val stream = nextItem?.localConfiguration?.customCacheKey?.let(preparedStreams::get)
                                ?: preparedStreams.values.firstOrNull { it.url == nextItem?.localConfiguration?.uri?.toString() }
                            val nextStreamUrl = stream?.url ?: nextItem?.localConfiguration?.uri?.toString()

                            val transitionActive = crossfadeEnabled || autoMixCoordinator.get().isEnabled
                            if (transitionActive && upcomingIdx != C.INDEX_UNSET && nextItem != null) {
                                val remainingMs = dur - pos
                                if (dur > 0L && (pos > dur * 0.25f || remainingMs < 90_000L)) {
                                    val isNextNonPlaceholder = nextItem.localConfiguration?.uri?.scheme?.let { it != "lastwave" } == true
                                    val isStreamDead = stream?.isExpired() == true
                                    val isNextResolved = isNextNonPlaceholder && !isStreamDead

                                    if (!isNextResolved) {
                                        val nowMs = SystemClock.elapsedRealtime()
                                        val dueForKick = lastCrossfadeResolveIndex != upcomingIdx ||
                                            nowMs - lastCrossfadeResolveKickMs >= CROSSFADE_RESOLVE_RETRY_MS
                                        if (dueForKick && preloadJob?.isActive != true) {
                                            lastCrossfadeResolveIndex = upcomingIdx
                                            lastCrossfadeResolveKickMs = nowMs
                                            preloadNextTrack(upcomingIdx, nextTrk)
                                        }
                                    } else {
                                        standbyOrCreate()
                                        // A quality hot-swap owns the standby until it commits.
                                        if (!hotSwapActive && (!crossfadeStandbyArmed || standbyIndex != upcomingIdx)) {
                                            armCrossfadeStandby(upcomingIdx, nextItem)
                                        }
                                    }
                                }
                            }

                            // Only the decoded stream length may time a mix:
                            // catalog metadata can describe another edit of the
                            // song and opened the blend halfway through it.
                            autoMixCoordinator.get().onTrackProgressTick(
                                primaryPlayer = player,
                                // A quality hot-swap is playing this same song on the standby.
                                secondaryPlayer = currentStandby().takeUnless { hotSwapActive },
                                currentTrack = _state.value.current,
                                nextTrack = nextTrk,
                                positionMs = runCatching { player.currentPosition }.getOrDefault(pos).coerceAtLeast(0L),
                                durationMs = player.duration.takeIf { it > 0L } ?: 0L,
                                nextTrackStreamUrl = nextStreamUrl,
                            )
                            if (updateCrossfade(pos)) {
                                cadenceMs = 60L
                            } else {

                    // Steady-state ensure: if the track-start preload failed or
                    // was cancelled, don't wait for the 30s second-chance â€”
                    // retry every 30s so the next track is resolved well
                    // BEFORE the fade window (crossfade needs processed
                    // bytes, not a last-second scramble). Stops firing once
                    // the item is replaced; also tightens gapless natural
                    // advances, not just fades.
                    if (player.isPlaying && outgoingPlayer == null && preloadJob?.isActive != true) {
                        val nowMs = SystemClock.elapsedRealtime()
                        if (nowMs - lastPreloadRetryMs >= 30_000L) {
                            val upcomingIndex = player.nextMediaItemIndex
                            if (upcomingIndex != C.INDEX_UNSET &&
                                upcomingIndex in 0 until player.mediaItemCount &&
                                upcomingIndex != player.currentMediaItemIndex &&
                                player.getMediaItemAt(upcomingIndex).localConfiguration?.uri?.scheme == "lastwave"
                            ) {
                                lastPreloadRetryMs = nowMs
                                preloadNextQueueItem(player.currentMediaItemIndex)
                            }
                        }
                    }

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
                    // parked at ENDED â€” or pinned READY at the duration tail
                    // with playWhenReady and a next window â€” drive the same
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
                        (player.nextMediaItemIndex != C.INDEX_UNSET || player.repeatMode == Player.REPEAT_MODE_ONE || _state.value.repeatMode == Player.REPEAT_MODE_ONE)
                    ) {
                        handleNaturalTrackEnd()
                    }

                    // Tail-pin watchdog: parked AT the duration tail with
                    // playWhenReady but no ENDED and no advance - BUFFERING
                    // on a stalled next-resolve, or READY-frozen while still
                    // reporting playing. The ENDED branch above, the
                    // pinned-tail advance (!isPlaying only) and the render
                    // watchdog (parked branch needs !isPlaying; rendering
                    // branch treats a frozen clock as "wait longer") all miss
                    // this shape, so the bar sits at -0:00 with Pause showing
                    // until the user taps next. After a grace window, drive
                    // the same debounced lossless-first advance, which no-ops
                    // for repeat-one, paused, actively-resolving, lastwave
                    // placeholders and end-of-queue states.
                    val tailNow = SystemClock.elapsedRealtime()
                    val tailKey = "${player.currentMediaItem?.mediaId}|${player.currentMediaItemIndex}"
                    val tailPinned = player.duration > 0L &&
                        pos >= player.duration - END_OF_TRACK_STALL_THRESHOLD_MS &&
                        player.playWhenReady &&
                        _state.value.error == null &&
                        player.playbackState != Player.STATE_ENDED &&
                        (player.nextMediaItemIndex != C.INDEX_UNSET || player.repeatMode == Player.REPEAT_MODE_ONE || _state.value.repeatMode == Player.REPEAT_MODE_ONE)
                    if (!tailPinned || tailKey != tailPinnedKey) {
                        tailPinnedKey = tailKey.takeIf { tailPinned }
                        tailPinnedSinceMs = 0L
                    } else if (tailPinnedSinceMs == 0L) {
                        tailPinnedSinceMs = tailNow
                    } else if (tailNow - tailPinnedSinceMs >= TAIL_PIN_TIMEOUT_MS) {
                        tailPinnedSinceMs = 0L
                        handleNaturalTrackEnd()
                    }

                    // Stream-health sampling: effective clock drift + glitch
                    // watch, 1 Hz while playing. Feeds the signal-path popup.
                    val tickerNow = SystemClock.elapsedRealtime()
                    updateRenderStallWatchdog(tickerNow)
                    val healthPlaying = player.isPlaying || _state.value.isPlaying
                    if (healthPlaying && tickerNow - lastSignalPathMs >= SIGNAL_PATH_TICK_MS) {
                        lastSignalPathMs = tickerNow
                        val exclusiveRate = exclusiveUsbOutput.currentRateHz()
                        if (exclusiveUsbOutput.isActive() && exclusiveRate > 0) {
                            val drift = healthTracker.sampleExclusive(
                                exclusiveUsbOutput.framesWritten(),
                                exclusiveRate,
                                tickerNow,
                                true,
                            )
                            checkExclusiveHealth(drift)
                        } else {
                            exclusiveBadDriftTicks = 0
                            healthTracker.sample(pos, tickerNow, true)
                        }
                        updateSignalPath()
                    }

                    if (isOverlapMixActive) {
                        return@launch
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
                                // Session persistence rebuilds a queue slice every call â€”
                                // throttling it from every tick to 2s removes constant
                                // main-thread allocation with zero UX difference (the
                                // signature already buckets positions at 5s).
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastTickerPersistMs >= TICKER_PERSIST_INTERVAL_MS) {
                                    lastTickerPersistMs = now
                                    persistPlaybackSession()
                                }
                            }
                            // Podcast resume positions are checkpointed on
                            // their own cadence, outside the "state changed"
                            // gate: a paused episode must still be saved.
                            val podcastNow = SystemClock.elapsedRealtime()
                            if (_state.value.isPlaying &&
                                podcastNow - lastPodcastProgressMs >= PODCAST_PROGRESS_INTERVAL_MS
                            ) {
                                lastPodcastProgressMs = podcastNow
                                checkpointPodcastProgress()
                            }
                            // Preserve lazy player startup when there is no
                            // restored or active queue. The short-circuit
                            // avoids touching ExoPlayer.
                            // 60ms only matters for an on-screen seek bar /
                            // lyrics. With no screen visible, a 250ms cadence
                            // keeps notification, widget, scrobble and Jam
                            // positions accurate while waking the CPU ~4x less.
                            // Transitions keep full precision near the tail.
                            val nearTransition = transitionActive && dur > 0L && dur - pos < 90_000L
                            cadenceMs = when {
                                !_state.value.isPlaying -> 500L
                                com.lastwave.app.util.AppVisibility.isVisible || nearTransition -> 60L
                                else -> BACKGROUND_TICKER_CADENCE_MS
                            }
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
            runCatching { settingsPreferences.resetAppVolumeAfterCastFixOnce() }
            settingsPreferences.appVolume.collect { vol ->
                onMain {
                    // Ignore our own (debounced) writes coming back and any
                    // stale value emitted while a newer one is pending.
                    if (appVolumePersistJob?.isActive == true) return@onMain
                    if (kotlin.math.abs(vol - appVolume) < 1e-4f && _state.value.appVolume == appVolume) return@onMain
                    appVolume = vol
                    _state.update { if (it.isCasting) it else it.copy(appVolume = vol) }
                    if (playerDelegate.isInitialized() && unduckedVolume == null) {
                        runCatching { player.volume = vol }
                    }
                    runCatching { nativeAudioEngine.get().setOutputVolume(if (bitPerfectEnabled) 1f else vol) }
                    runCatching { autoMixCoordinator.get().setBaseVolume(vol) }
                }
            }
        }

        applicationScope.launch {
            settingsPreferences.settings.collect { settings ->
                val previousPlaybackSettings = latestPlaybackSettings
                latestPlaybackSettings = settings
                musicHapticsController.updateConfig(settings.musicHapticsConfig)
                crossfadeEnabled = settings.crossfadeEnabled
                if (autoplaySimilarEnabled && !settings.autoplaySimilarEnabled) {
                    withContext(Dispatchers.Main.immediate) { disableRadioQueue() }
                }
                autoplaySimilarEnabled = settings.autoplaySimilarEnabled
                crossfadeDurationMs = settings.crossfadeSeconds.coerceIn(1, 12) * 1000L
                stopPlaybackOnTaskRemovedPref = settings.stopPlaybackOnTaskRemoved
                if (settings.wifiStreamingQuality != previousPlaybackSettings.wifiStreamingQuality ||
                    settings.cellularStreamingQuality != previousPlaybackSettings.cellularStreamingQuality
                ) {
                    onMain {
                        resetAdaptiveQuality()
                        // A profile change is applied like a handoff: down now, up once.
                        considerAdaptiveNetworkQuality(networkMonitor.activeRoute.value, routeChanged = true)
                    }
                }
                val wasBitPerfect = bitPerfectEnabled
                bitPerfectEnabled = settings.isBitPerfectEnabled
                systemEffectsModePref = settings.systemEffectsMode
                updateBitPerfectState()
                recomputeUsbExclusivePref()
                if (bitPerfectEnabled && settings.isStudioMasterClarityEnabled) {
                    // Self-heal: both must never be on â€” Bit-Perfect wins.
                    settingsPreferences.setStudioMasterClarity(false)
                }
                if (bitPerfectEnabled && !wasBitPerfect) {
                    // Freshly engaged: ask for direct USB access right away so
                    // the DAC route is usable without hunting for the dialog.
                    maybeRequestUsbPermission()
                } else if (!bitPerfectEnabled) {
                    if (usbExclusivePrefEnabled) {
                        usbExclusivePrefEnabled = false
                    }
                    internalDirectPrefEnabled = false
                    applicationScope.launch {
                        runCatching { UsbExclusivePrefs.setEnabled(appContext, false) }
                        runCatching {
                            com.lastwave.app.playback.usb.InternalDirectPrefs.setEnabled(appContext, false)
                        }
                    }
                }
                onMain {
                    applyDacRoutingFor(currentSourceRateHz())
                    updateSignalPath()
                }
                if (playerDelegate.isInitialized()) {
                    onMain {
                        if (!crossfadeEnabled || bitPerfectEnabled) cancelCrossfade()
                        if (bitPerfectEnabled) {
                            // Tempo stretch resamples, which defeats bit-perfect.
                            // Volume is left alone: the direct sinks scale it in
                            // software, and pinning it to 1 killed the in-app mute.
                            if (runCatching { player.playbackParameters.speed }.getOrDefault(1f) != 1f) {
                                runCatching { player.setPlaybackSpeed(1f) }
                            }
                        }
                    }
                }
            }
        }

        applicationScope.launch(Dispatchers.Main.immediate) {
            var previousRoute: NetworkMonitor.ActiveRoute? = null
            networkMonitor.activeRoute.collectLatest { route ->
                val previous = previousRoute
                val routeChanged = previous != null && (
                    route.network != previous.network || route.isCellular != previous.isCellular
                    )
                previousRoute = route
                // Bandwidth estimates flap; only a settled estimate moves the target.
                if (routeChanged) resetAdaptiveQuality() else delay(2_500L)
                considerAdaptiveNetworkQuality(route, routeChanged)
            }
        }

        applicationScope.launch(Dispatchers.Main.immediate) {
            jamRepository.get().sessionState.collect { session ->
                val isJam = session is com.lastwave.app.data.jam.JamSessionState.Active
                if (isJam != _state.value.isJamActive) {
                    _state.update { it.copy(isJamActive = isJam) }
                }
            }
        }

        applicationScope.launch {
            usbDacMonitor.state.collect {
                if (bitPerfectEnabled && usbExclusivePrefEnabled) {
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
                if (enabled && !usbExclusiveRawPref) exclusiveUsbOutput.clearBlockedDevices()
                usbExclusiveRawPref = enabled
                recomputeUsbExclusivePref()
                if (usbExclusivePrefEnabled) maybeRequestUsbPermission()
                onMain {
                    applyDacRoutingFor(currentSourceRateHz())
                    updateSignalPath()
                }
            }
        }

        applicationScope.launch {
            com.lastwave.app.playback.usb.InternalDirectPrefs.enabledFlow(appContext).collect { enabled ->
                internalDirectPrefEnabled = enabled
                onMain {
                    applyDacRoutingFor(currentSourceRateHz())
                    updateSignalPath()
                }
            }
        }

        applicationScope.launch {
            com.lastwave.app.playback.usb.InternalDirectPrefs.forceSoftwareVolumeFlow(appContext).collect { force ->
                exclusiveUsbOutput.setForceSoftwareVolume(force)
                onMain { updateSignalPath() }
            }
        }

        applicationScope.launch {
            // Route changes (jack, Bluetooth, USB): re-decide which path is wanted.
            var lastRoute: InternalRoute? = null
            internalDirectOutput.availability.collect { availability ->
                if (availability.route == lastRoute) return@collect
                lastRoute = availability.route
                audioSinks.forEach { sink -> runCatching { sink.retryInternalDirect() } }
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
        startRadio: Boolean = true,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
    ) {
        pendingRestoredSession = null
        disableDiscoverQueue()
        disableRadioQueue()
        queueEnrichmentJob?.cancel()
        unavailableSkipJob?.cancel()
        unavailableMediaIds.clear()
        unavailableAttemptCounts.clear()
        playHistory.clear()
        latePreloadKey = null
        lastPreloadRetryMs = 0L
        // A podcast episode never seeds a music radio.
        val radioAllowed = startRadio && autoplaySimilarEnabled && track.mediaType == MediaType.SONG
        radioQueueActive = radioAllowed
        startResolvedQueuePlayback(
            tracks = listOf(track),
            selectedIndex = 0,
            startPositionMs = startPositionMs,
            sourceLabel = sourceLabel,
            endlessDiscover = false,
            playWhenReady = playWhenReady,
        )
        if (radioAllowed) {
            _state.update { it.copy(isEndlessQueue = true) }
            startRadioQueue(track)
        }
    }

    fun playQueue(
        tracks: List<PlayableTrack>,
        startIndex: Int = 0,
        sourceLabel: String = "LastWave",
        startShuffled: Boolean = false,
        enableRadioContinuation: Boolean = false,
    ) {
        if (!enableRadioContinuation) disableRadioQueue()
        playQueueInternal(
            tracks = tracks,
            startIndex = startIndex,
            endlessDiscover = false,
            sourceLabel = sourceLabel,
            startShuffled = startShuffled,
            enableRadioContinuation = enableRadioContinuation,
        )
    }

    /**
     * Starts a podcast episode queue. Unlike [playQueue] this never grows the
     * queue with radio continuations and never marks the queue endless, so the
     * episode list the user picked is exactly what plays.
     */
    fun playPodcastQueue(
        tracks: List<PlayableTrack>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L,
        sourceLabel: String = "Podcasts",
    ) {
        if (tracks.isEmpty()) return
        disableRadioQueue()
        disableDiscoverQueue()
        pendingRestoredSession = null
        playRequest?.cancel()
        queueEnrichmentJob?.cancel()
        unavailableSkipJob?.cancel()
        unavailableMediaIds.clear()
        unavailableAttemptCounts.clear()
        playHistory.clear()
        latePreloadKey = null
        lastPreloadRetryMs = 0L
        val selectedIndex = startIndex.coerceIn(tracks.indices)
        startResolvedQueuePlayback(
            tracks = tracks,
            selectedIndex = selectedIndex,
            startPositionMs = startPositionMs.coerceAtLeast(0L),
            sourceLabel = sourceLabel,
            endlessDiscover = false,
        )
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
        enableRadioContinuation: Boolean = false,
    ) {
        if (tracks.isEmpty()) return
        pendingRestoredSession = null
        discoverQueueLoadJob?.cancel()
        discoverQueueActive = endlessDiscover
        if (!enableRadioContinuation || !autoplaySimilarEnabled) {
            disableRadioQueue()
        } else {
            radioQueueActive = true
            radioUsedSeeds.clear()
            tracks.getOrNull(startIndex)?.videoId?.takeIf(String::isNotBlank)?.let { radioUsedSeeds.add(it) }
        }
        val selectedIndex = startIndex.coerceIn(tracks.indices)
        playRequest?.cancel()
        queueEnrichmentJob?.cancel()
        unavailableSkipJob?.cancel()
        unavailableMediaIds.clear()
        unavailableAttemptCounts.clear()
        // Fresh queue context: previous-queue history no longer applies.
        // (Same-queue navigations via startResolvedQueuePlayback keep it.)
        playHistory.clear()
        latePreloadKey = null
        lastPreloadRetryMs = 0L
        startResolvedQueuePlayback(
            tracks = tracks,
            selectedIndex = selectedIndex,
            startPositionMs = 0L,
            sourceLabel = sourceLabel,
            endlessDiscover = endlessDiscover || (enableRadioContinuation && autoplaySimilarEnabled),
            startShuffled = startShuffled,
        )
    }

    internal fun startResolvedQueuePlayback(
        tracks: List<PlayableTrack>,
        selectedIndex: Int,
        startPositionMs: Long,
        sourceLabel: String,
        endlessDiscover: Boolean,
        startShuffled: Boolean = false,
        playWhenReady: Boolean = true,
    ) {
        val selectedTrack = tracks[selectedIndex].withYoutubeArtwork()
        PlaybackStartupLog.begin(
            trackId = selectedTrack.internalTrackId?.takeIf { it.isNotBlank() } ?: selectedTrack.videoId.orEmpty(),
            youtubeVideoId = selectedTrack.videoId,
        )
        if (isCasting) {
            onMain {
                cancelPendingPlaybackResolution()
                ensureForegroundService()
                _state.value = _state.value.copy(current = selectedTrack, queue = tracks,
                    currentIndex = selectedIndex, positionMs = startPositionMs,
                    durationMs = selectedTrack.durationMs ?: findKnownDuration(selectedTrack) ?: 0L,
                    sourceLabel = sourceLabel, isEndlessQueue = endlessDiscover,
                    shuffleEnabled = startShuffled, error = null)
                durationOwnerMediaId = selectedTrack.mediaIdKey()
                castPlayback?.load(_state.value)
            }
            return
        }
        warmArtwork(selectedTrack)
        val generation = playRequestGeneration.incrementAndGet()
        playRequest?.cancel()
        cancelStaleResolverWork("queue-playback")
        tracks.getOrNull(selectedIndex)?.playbackIdentity()?.let { streamResolver.playback.begin(it) }
        cancelActiveUpgrade()
        preloadJob?.cancel()
        onMain {
            if (generation != playRequestGeneration.get()) return@onMain
            ensureForegroundService()
            // drop any running automix blend, the old decks are going away
            autoMixCoordinator.get().cancelTransition(revertToPrimary = true)
            cancelCrossfade()
            losslessBypassMediaIds.clear()
            errorRetryCount = 0
            retryMediaId = null
            if (playerDelegate.isInitialized()) {
                player.stop()
                player.clearMediaItems()
            }
            resetPlayhead(startPositionMs, selectedTrack.mediaIdKey())
            resetRenderWatchdog()
            _state.update { live -> MusicPlayerState(
                current = selectedTrack,
                queue = tracks,
                currentIndex = selectedIndex,
                sourceLabel = sourceLabel,
                isEndlessQueue = endlessDiscover || radioQueueActive,
                isBuffering = true,
                isPlaying = playWhenReady,
                positionMs = startPositionMs.coerceAtLeast(0L),
                // Seed duration so the main-player seek bar has a denominator
                // from t=0 when one is known (repeat plays, enriched metadata,
                // knownDurations). Without this the bar sat at 0:00/disabled
                // until ExoPlayer parsed the container (TIME_UNSET -> READY),
                // while the notification extrapolated via speed and looked fine.
                durationMs = selectedTrack.durationMs ?: findKnownDuration(selectedTrack) ?: 0L,
                shuffleEnabled = if (playerDelegate.isInitialized()) player.shuffleModeEnabled else startShuffled,
                repeatMode = live.repeatMode,
            ).withSubsystemStateFrom(live) }
            durationOwnerMediaId = selectedTrack.mediaIdKey()
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
                    val isShuffle = startShuffled || (playerDelegate.isInitialized() && player.shuffleModeEnabled)
                    resolved?.let {
                        registerPreparedStream(it)
                        stagePendingQuality(selectedTrack.mediaIdKey(), it)
                        publishResolvedQuality(it, expectedMediaId = selectedTrack.mediaIdKey())
                        applyDacRoutingFor(dacRateFor(it))
                        logStreamEvent("player-prepare", it, retry = 0)
                        cacheCurrentTrackStream(it)
                    }
                    if (selectedTrack.playbackUrl != null) {
                        applicationScope.launch(Dispatchers.IO) { publishLocalTrackQuality(selectedTrack) }
                    }
                    val learnedVideoId = resolved?.youtubeCandidate?.videoId?.takeIf { it.isNotBlank() }
                    val playbackTrack = if (learnedVideoId != null && selectedTrack.videoId.isNullOrBlank()) {
                        selectedTrack.copy(videoId = learnedVideoId, internalTrackId = learnedVideoId)
                    } else {
                        selectedTrack
                    }
                    learnQueueVideoId(selectedIndex, selectedTrack, learnedVideoId, resolved?.rematchedFromVideoId)
                    val mediaItems = tracks.mapIndexed { index, track ->
                        val itemTrack = if (index == selectedIndex) playbackTrack else track
                        itemTrack.toMediaItem(if (index == selectedIndex) resolved else null)
                    }
                    val targetStartCue = startPositionMs.coerceAtLeast(0L)
                    resetPlayhead(targetStartCue, selectedTrack.mediaIdKey())
                    player.setMediaItems(mediaItems, selectedIndex, targetStartCue)
                    if (isShuffle) {
                        if (mediaItems.size > 1) {
                            val remaining = mediaItems.indices.filter { it != selectedIndex }.shuffled()
                            val order = (listOf(selectedIndex) + remaining).toIntArray()
                            player.setShuffleOrder(DefaultShuffleOrder(order, Random.nextLong()))
                        }
                        player.shuffleModeEnabled = true
                    } else {
                        player.shuffleModeEnabled = false
                    }
                    player.repeatMode = _state.value.repeatMode
                    PlaybackStartupLog.prepareStart()
                    resetRenderWatchdog()
                    player.prepare()
                    if (playWhenReady) player.play() else player.pause()
                    enrichUpcomingQueue(selectedIndex)
                    resolved?.let { stream ->
                        if (!stream.isLossless && stream.audioCodec != "DOLBY ATMOS") {
                            scheduleQualityUpgrade(
                                track = playbackTrack,
                                expectedMediaId = playbackTrack.mediaIdKey(),
                                generation = generation,
                                currentStream = stream,
                            )
                        }
                    }
                    scheduleStartupFollowUp {
                        preloadNextQueueItem(selectedIndex)
                        scheduleDeferredRecommendations()
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (generation != playRequestGeneration.get()) return@launch
                if (error is VpnYoutubeLoginRequiredException) {
                    withContext(Dispatchers.Main.immediate) {
                        if (generation == playRequestGeneration.get()) {
                            _state.update {
                                it.copy(
                                    isPlaying = false,
                                    isBuffering = false,
                                    error = error.message,
                                )
                            }
                        }
                    }
                    return@launch
                }
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
                            val isShuffle = startShuffled || (playerDelegate.isInitialized() && player.shuffleModeEnabled)
                            registerPreparedStream(ytFallback)
                            stagePendingQuality(selectedTrack.mediaIdKey(), ytFallback)
                            publishResolvedQuality(ytFallback, expectedMediaId = selectedTrack.mediaIdKey())
                            cacheCurrentTrackStream(ytFallback)
                            val mediaItems = tracks.mapIndexed { index, track ->
                                track.toMediaItem(if (index == selectedIndex) ytFallback else null)
                            }
                            player.setMediaItems(mediaItems, selectedIndex, startPositionMs.coerceAtLeast(0L))
                            if (isShuffle) {
                                if (mediaItems.size > 1) {
                                    val remaining = mediaItems.indices.filter { it != selectedIndex }.shuffled()
                                    val order = (listOf(selectedIndex) + remaining).toIntArray()
                                    player.setShuffleOrder(DefaultShuffleOrder(order, Random.nextLong()))
                                }
                                player.shuffleModeEnabled = true
                            } else {
                                player.shuffleModeEnabled = false
                            }
                            player.repeatMode = _state.value.repeatMode
                            player.prepare()
                            if (playWhenReady) player.play() else player.pause()
                            enrichUpcomingQueue(selectedIndex)
                            if (endlessDiscover) {
                                appendMissingDiscoverTracks(discoverRepository.getCachedFeed().map(GeneratedTrack::toPlayableTrack))
                            }
                            scheduleQualityUpgrade(
                                track = selectedTrack,
                                expectedMediaId = selectedTrack.mediaIdKey(),
                                generation = generation,
                                currentStream = ytFallback,
                            )
                            scheduleDeferredRecommendations()
                            preloadNextQueueItem(selectedIndex)
                        }
                        return@launch
                    }
                }
                withContext(Dispatchers.Main.immediate) {
                    if (generation == playRequestGeneration.get()) {
                        val failedKey = selectedTrack.mediaIdKey()
                        val attempts = (unavailableAttemptCounts[failedKey] ?: 0) + 1
                        unavailableAttemptCounts[failedKey] = attempts
                        val provenDead = isExplicitlyUnplayableFailure(error) || isUnsupportedMediaFailure(error)
                        if (!provenDead && attempts < MIN_UNAVAILABLE_ATTEMPTS_BEFORE_SKIP) {
                            val isOffline = isNetworkException(error)
                            _state.update {
                                it.copy(
                                    isPlaying = false,
                                    isBuffering = false,
                                    error = if (isOffline) "Track not available offline"
                                    else error.message?.takeIf(String::isNotBlank)
                                        ?: "Couldn't load this track. Tap play to retry.",
                                )
                            }
                            return@withContext
                        }
                        unavailableMediaIds += failedKey
                        val isShuffle = startShuffled || (playerDelegate.isInitialized() && player.shuffleModeEnabled)
                        val nextIndex = if (isShuffle) {
                            tracks.indices
                                .filter { tracks[it].mediaIdKey() !in unavailableMediaIds }
                                .randomOrNull()
                        } else {
                            val ordered = (selectedIndex + 1 until tracks.size) + (0 until selectedIndex)
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
                                startShuffled = isShuffle,
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

    fun playNext(track: PlayableTrack) = onMain {
        val enriched = track.withYoutubeArtwork()
        if (isCasting) {
            _state.update { snapshot ->
                val queue = snapshot.queue.toMutableList()
                queue.add((snapshot.currentIndex + 1).coerceIn(0, queue.size), enriched)
                snapshot.copy(queue = queue)
            }
            persistPlaybackSession()
            return@onMain
        }
        val wasEnded = player.playbackState == Player.STATE_ENDED
        val wasEmpty = player.mediaItemCount == 0
        val currentIndex = player.currentMediaItemIndex
        val index = (currentIndex + 1).coerceIn(0, player.mediaItemCount)
        player.addMediaItem(index, enriched.toMediaItem())
        // Under shuffle the insert lands at a random permutation spot;
        // pin it directly after the current track so it truly plays next.
        placeInsertedIndexInShuffleOrder(index, last = false)
        refresh(player)
        enrichUpcomingQueue(currentIndex.coerceAtLeast(0))
        val nextIndex = if (player.shuffleModeEnabled) player.nextMediaItemIndex else (currentIndex + 1)
        if (nextIndex != C.INDEX_UNSET && nextIndex in 0 until player.mediaItemCount) {
            preloadNextTrack(nextIndex, player.getMediaItemAt(nextIndex).toPlayableTrack())
        }
        if (wasEmpty || wasEnded) {
            player.seekToDefaultPosition(index)
            player.prepare()
            player.play()
        }
        persistPlaybackSession()
    }

    fun addToQueue(track: PlayableTrack) = onMain {
        val enriched = track.withYoutubeArtwork()
        if (isCasting) {
            _state.update { it.copy(queue = it.queue + enriched) }
            persistPlaybackSession()
        } else {
            val wasEnded = player.playbackState == Player.STATE_ENDED
            val wasEmpty = player.mediaItemCount == 0
            val previousCount = player.mediaItemCount
            player.addMediaItem(enriched.toMediaItem())
            // Under shuffle the append lands at a random permutation
            // spot; pin it at the end of the actual play order.
            placeInsertedIndexInShuffleOrder(player.mediaItemCount - 1, last = true)
            refresh(player)
            val currentIndex = player.currentMediaItemIndex
            enrichUpcomingQueue(currentIndex.coerceAtLeast(0))
            val nextIndex = if (player.shuffleModeEnabled) player.nextMediaItemIndex else currentIndex + 1
            if (nextIndex == player.mediaItemCount - 1) {
                preloadNextTrack(nextIndex, player.getMediaItemAt(nextIndex).toPlayableTrack())
            }
            if (wasEmpty || (wasEnded && currentIndex >= previousCount - 1)) {
                player.seekToDefaultPosition(previousCount)
                player.prepare()
                player.play()
            }
            persistPlaybackSession()
        }
    }

    /** Appends multiple tracks to the active queue without stopping or restarting current playback. */
    fun appendTracksToQueue(tracks: List<PlayableTrack>) = onMain {
        if (tracks.isEmpty()) return@onMain
        val seenQueueKeys = (0 until player.mediaItemCount).mapTo(mutableSetOf()) {
            player.getMediaItemAt(it).toPlayableTrack().queueKey()
        }
        val fresh = tracks.filter { track ->
            track.title.isNotBlank() && track.artist.isNotBlank() && seenQueueKeys.add(track.queueKey())
        }
        if (fresh.isEmpty()) return@onMain

        if (isCasting) {
            _state.update { it.copy(queue = it.queue + fresh) }
            persistPlaybackSession()
        } else {
            val wasEmpty = player.mediaItemCount == 0
            val wasEnded = player.playbackState == Player.STATE_ENDED
            val previousCount = player.mediaItemCount
            player.addMediaItems(fresh.map(PlayableTrack::toMediaItem))
            refresh(player)
            val currentIndex = player.currentMediaItemIndex
            enrichUpcomingQueue(currentIndex.coerceAtLeast(0))
            if (wasEmpty || (wasEnded && currentIndex >= previousCount - 1)) {
                player.seekToDefaultPosition(previousCount)
                player.prepare()
                player.play()
            }
            persistPlaybackSession()
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
        // An explicit resume replaces any focus-induced pause we owed.
        focusPauseAwaitingRecovery = false
        exclusiveUsbOutput.setPaused(false)
        if (isCasting) {
            castPlayback?.play()
            return@onMain
        }
        ensureForegroundService()
        if (retryInterruptedPlayback()) return@onMain
        val pendingCurrent = _state.value.current
        val pausedMediaId = pausedResolutionMediaId
        if (pausedMediaId != null) {
            val pausedIndex = if (pendingCurrent?.mediaIdKey() == pausedMediaId) {
                (0 until player.mediaItemCount).firstOrNull { index ->
                    player.getMediaItemAt(index).mediaId == pausedMediaId
                }
            } else {
                null
            }

            pausedResolutionMediaId = null

            if (pausedIndex != null) {
                resolveAndPlayQueueItem(pausedIndex)
                return@onMain
            }
        }
        if (player.mediaItemCount == 0 && pendingCurrent != null) {
            val q = _state.value.queue.ifEmpty { listOf(pendingCurrent) }
            val idx = _state.value.currentIndex.coerceIn(q.indices)
            // After a stop, continue where the song was instead of at 0:00.
            val startAt = resumeAfterStopPositionMs ?: _state.value.positionMs
            resumeAfterStopPositionMs = null
            startResolvedQueuePlayback(
                tracks = q,
                selectedIndex = idx,
                startPositionMs = startAt,
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
        val isLocalTrack = currentItem?.mediaId?.startsWith("local:") == true ||
            _state.value.current?.isLocalTrack() == true
        val hasNonPlaceholderUri = currentItem?.localConfiguration?.uri?.scheme?.let { it != "lastwave" } == true
        val isDead = currentPrepared?.isExpired() == true
        val hasError = player.playerError != null || _state.value.error != null
        val needsResolution = !hasNonPlaceholderUri || isDead || hasError
        if (needsResolution && player.currentMediaItemIndex in 0 until player.mediaItemCount) {
            _state.update { it.copy(error = null) }
            resolveAndPlayQueueItem(player.currentMediaItemIndex)
            return@onMain
        }
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) {
            seekTo(0)
            player.prepare()
        }
        outgoingPlayer?.play()
        player.play()
        runCatching { autoMixCoordinator.get().setTransportPaused(false) }
    }

    fun pause() = pauseInternal(focusInitiated = false)

    /**
     * @param focusInitiated true when this pause answers an audio-focus loss.
     *   Such a pause must leave the focus request in place: the platform only
     *   delivers AUDIOFOCUS_GAIN back to the app that still owns the lost
     *   request, so abandoning it here would strand [pauseForAudioFocus]'s
     *   recovery and a brief interruption would never resume.
     */
    private fun pauseInternal(focusInitiated: Boolean) {
        // A deliberate pause supersedes a focus-induced one, so a later
        // AUDIOFOCUS_GAIN must not resurrect playback the user stopped.
        if (!focusInitiated) focusPauseAwaitingRecovery = false
        val selectedMediaId = _state.value.current?.mediaIdKey()
        pausedResolutionMediaId = selectedMediaId?.takeIf { mediaId ->
            resolvingMediaIds[mediaId] == playRequestGeneration.get()
        }
        cancelPendingPlaybackResolution()
        onMain {
            exclusiveUsbOutput.setPaused(true)
            if (isCasting) castPlayback?.pause()
            // Set for the duration of the player pause so the
            // onPlayWhenReadyChanged listener sees it no matter which thread
            // this ran on, and clear it before the state bookkeeping below.
            pausingForAudioFocus = focusInitiated
            try {
                // Pauses the incoming AutoMix deck too (it is not the active
                // player until the blend's hand-over point).
                runCatching { autoMixCoordinator.get().setTransportPaused(true) }
                outgoingPlayer?.pause()
                if (playerDelegate.isInitialized()) player.pause()
            } finally {
                pausingForAudioFocus = false
            }
            _state.update { it.copy(isPlaying = false, isBuffering = false) }
            checkpointPodcastProgress()
            persistPlaybackSession()
        }
    }

    /**
     * Asks the platform for media audio focus. Best-effort: a refusal is logged,
     * not fatal â€” the user explicitly asked for playback and the system still
     * mixes the stream out; [AudioFocusPolicy] keeps the decision consistent.
     */
    private fun requestMediaAudioFocus() {
        if (isCasting) return
        val manager = audioManager ?: return
        val attributes = audioFocusAttributes ?: return
        val result = synchronized(audioFocusLock) {
            val request = audioFocusRequest
                ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .setWillPauseWhenDucked(false)
                    .build()
                    .also { audioFocusRequest = it }
            runCatching { manager.requestAudioFocus(request) }
                .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        }
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            android.util.Log.w("MusicPlayer", "Audio focus request refused ($result)")
        }
    }

    private fun abandonMediaAudioFocus() {
        val manager = audioManager ?: return
        synchronized(audioFocusLock) {
            runCatching {
                audioFocusRequest?.let { request -> manager.abandonAudioFocusRequest(request) }
            }
        }
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        val action = AudioFocusPolicy.actionFor(focusChange)
        android.util.Log.i("MusicPlayer", "Audio focus change: $focusChange -> $action")
        onMain {
            when (action) {
                AudioFocusAction.Restore -> restoreAfterAudioFocus()
                AudioFocusAction.PausePermanently -> pauseForAudioFocus(autoRestore = false)
                AudioFocusAction.PauseTransiently -> pauseForAudioFocus(autoRestore = true)
                AudioFocusAction.Duck -> setDucked(true)
                AudioFocusAction.KeepPlaying -> Unit
            }
        }
    }

    /**
     * Pauses for a focus loss as a real (recoverable) pause: playWhenReady goes
     * false, the clock and the pause icon agree, and the play button works.
     * Transient losses remember the intent so AUDIOFOCUS_GAIN restores playback.
     */
    private fun pauseForAudioFocus(autoRestore: Boolean) {
        if (isCasting) return
        val playing = _state.value.isPlaying ||
            (playerDelegate.isInitialized() && player.playWhenReady)
        // A permanent loss must also clear any intent left by an earlier
        // transient one, so this assignment is unconditional.
        focusPauseAwaitingRecovery = autoRestore && playing
        if (playing) pauseInternal(focusInitiated = true)
    }

    private fun restoreAfterAudioFocus() {
        unduckedVolume?.let { level ->
            unduckedVolume = null
            if (playerDelegate.isInitialized()) runCatching { player.volume = level }
        }
        if (!focusPauseAwaitingRecovery) return
        focusPauseAwaitingRecovery = false
        // The user paused by hand during the interruption: respect it.
        if (_state.value.isPlaying) return
        if (playerDelegate.isInitialized() && player.playWhenReady) return
        android.util.Log.i("MusicPlayer", "Audio focus regained â€” resuming interrupted playback")
        resume()
    }

    /** Keeps playing at a reduced level while the holder asked for a duck. */
    private fun setDucked(ducked: Boolean) {
        if (isCasting || !playerDelegate.isInitialized()) return
        if (ducked) {
            if (unduckedVolume != null) return
            val current = runCatching { player.volume }.getOrDefault(appVolume)
            val duckTarget = (AudioFocusPolicy.DUCK_VOLUME * appVolume).coerceIn(0f, 1f)
            if (current <= duckTarget) return
            unduckedVolume = current
            runCatching { player.volume = duckTarget }
        } else {
            val level = unduckedVolume ?: appVolume
            unduckedVolume = null
            runCatching { player.volume = level }
        }
    }

    fun setAppVolume(volume: Float) = onMain {
        val clamped = volume.coerceIn(0f, 1f)
        if (isCasting) {
            // Slider / volume keys drive the receiver only; local playback
            // volume and its saved value stay untouched.
            castVolume = clamped
            _state.update { it.copy(appVolume = clamped) }
            castPlayback?.setVolume(clamped)
            return@onMain
        }
        appVolume = clamped
        _state.update { it.copy(appVolume = clamped) }
        if (playerDelegate.isInitialized() && unduckedVolume == null) {
            runCatching { player.volume = clamped }
        }
        runCatching { nativeAudioEngine.get().setOutputVolume(if (bitPerfectEnabled) 1f else clamped) }
        runCatching { autoMixCoordinator.get().setBaseVolume(clamped) }
        // Persist once the slider settles, not on every drag event (60-120/s
        // of DataStore writes). Each write also echoed back through the
        // collector below, snapping the slider/volume to an older value
        // mid-drag, which made the in-app volume feel jumpy.
        appVolumePersistJob?.cancel()
        appVolumePersistJob = applicationScope.launch {
            delay(APP_VOLUME_PERSIST_DEBOUNCE_MS)
            settingsPreferences.setAppVolume(clamped)
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
        // A tap while the keep-alive re-resolve is backing off means "now":
        // nudge the loop instead of pausing a track that is already stopped
        // by a dead stream and visibly restoring.
        if (playbackRecoveryJob?.isActive == true && _state.value.isBuffering) {
            recoveryRetryNow = true
            resume()
            return@onMain
        }
        val holdingUsb = exclusiveUsbOutput.isActive() && !exclusiveUsbOutput.isPaused()
        val isPlayerActive = playerDelegate.isInitialized() &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED &&
            player.playerError == null &&
            _state.value.error == null &&
            (player.isPlaying || player.playWhenReady)
        val playing = (_state.value.isPlaying && _state.value.error == null) ||
            isPlayerActive ||
            holdingUsb
        if (playing) pause() else resume()
    }

    @MainThread
    private fun retryInterruptedPlayback(): Boolean {
        val snapshot = _state.value
        val track = snapshot.current ?: return false
        if (snapshot.error == null || track.isLocalTrack()) return false
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
    /** Returns the live sub-millisecond playback position of the active audio player. */
    fun currentPositionMs(): Long {
        if (Looper.myLooper() == Looper.getMainLooper() && playerDelegate.isInitialized()) {
            return runCatching { player.currentPosition.coerceAtLeast(0L) }.getOrDefault(_state.value.positionMs)
        }
        return _state.value.positionMs
    }
    fun seekTo(positionMs: Long) = onMain {
        if (isCasting) {
            castPlayback?.seek(positionMs.coerceAtLeast(0))
            return@onMain
        }
        if (autoMixCoordinator.get().isEnabled) {
            autoMixCoordinator.get().handleSeekEvent(positionMs.coerceAtLeast(0))
        } else {
            cancelCrossfade()
        }
        cancelActiveUpgrade()
        currentTrackCacheJob?.cancel()
        currentTrackCacheJob = null
        preloadJob?.cancel()
        preloadJob = null
        val target = positionMs.coerceAtLeast(0)
        val now = SystemClock.elapsedRealtime()
        lastSeekTargetMs = target
        lastSeekAtElapsedMs = now
        playheadPosMs = target
        playheadWallMs = now
        exclusiveUsbOutput.noteSeek(target * 1_000L)
        player.seekTo(target)
        _state.update { it.copy(positionMs = target, isBuffering = true) }
        checkpointPodcastProgress()
    }

    internal fun resetPlayhead(startPosMs: Long = 0L, key: String? = null) {
        val now = SystemClock.elapsedRealtime()
        val target = startPosMs.coerceAtLeast(0L)
        lastSeekTargetMs = target
        lastSeekAtElapsedMs = now
        playheadPosMs = target
        playheadWallMs = now
        playheadKey = key
        playheadMoving = false
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
        return playheadPosMs.coerceAtLeast(0L)
    }

    private fun isCurrentMediaMatch(player: Player, expected: PlayableTrack?): Boolean {
        if (expected == null) return false
        val currentItem = runCatching { player.currentMediaItem }.getOrNull() ?: return false

        // Actively rendering audio is the authoritative source of playback truth
        if (player.isPlaying) return true

        val currentIndex = runCatching { player.currentMediaItemIndex }.getOrNull() ?: -1
        if (currentIndex >= 0 && currentIndex == _state.value.currentIndex && currentIndex in 0 until player.mediaItemCount) {
            return true
        }

        val itemMediaId = currentItem.mediaId
        val expectedKey = expected.mediaIdKey()
        if (itemMediaId == expectedKey) return true

        val expectedVideoId = expected.videoId?.takeIf { it.isNotBlank() }
        if (expectedVideoId != null) {
            if (itemMediaId == expectedVideoId) return true
            if (itemMediaId.removePrefix("local:") == expectedVideoId) return true
        }

        val expectedPlaybackUrl = expected.playbackUrl?.takeIf { it.isNotBlank() }
        if (expectedPlaybackUrl != null) {
            if (itemMediaId == expectedPlaybackUrl || itemMediaId == "local:$expectedPlaybackUrl") return true
            if (itemMediaId.removePrefix("local:") == expectedPlaybackUrl) return true
            val uriStr = currentItem.localConfiguration?.uri?.toString()
            if (uriStr == expectedPlaybackUrl || uriStr == "file://$expectedPlaybackUrl") return true
        }

        val itemTitle = currentItem.mediaMetadata.title?.toString()?.trim()
        val itemArtist = currentItem.mediaMetadata.artist?.toString()?.trim()
        val expectedTitle = expected.title.trim()
        val expectedArtist = expected.artist.trim()
        if (!itemTitle.isNullOrBlank() && itemTitle.equals(expectedTitle, ignoreCase = true)) {
            if (itemArtist.isNullOrBlank() || expectedArtist.isBlank() ||
                itemArtist.equals(expectedArtist, ignoreCase = true) ||
                itemArtist.contains(expectedArtist, ignoreCase = true) ||
                expectedArtist.contains(itemArtist, ignoreCase = true)
            ) {
                return true
            }
        }

        val queryKey = "query:${expected.artist.lowercase()}|${expected.title.lowercase()}"
        if (itemMediaId == queryKey) return true

        return false
    }

    /**
     * Accurate audio playhead clock.
     * In standard playback, aligns with ExoPlayer's true hardware audio presentation position
     * ([Player.getCurrentPosition]) to ensure sub-millisecond sync with vocals and lyrics.
     * When scrubbing, holds at [lastSeekTargetMs] during the settle window.
     * In exclusive USB bit-perfect mode, follows the DAC stream clock without blocking ExoPlayer.
     */
    private fun advancePlayhead(playing: Boolean): Long {
        if (isCasting || isOverlapMixActive) {
            return _state.value.positionMs
        }
        val now = SystemClock.elapsedRealtime()
        val seekAge = now - lastSeekAtElapsedMs
        if (lastSeekTargetMs >= 0L && seekAge in 0..SEEK_SETTLE_WINDOW_MS) {
            return lastSeekTargetMs
        }
        val key = _state.value.current?.let { track ->
            track.videoId?.takeIf { it.isNotBlank() } ?: "${track.title}|${track.artist}"
        }
        if (lastSeekTargetMs >= 0L) {
            if (seekAge in 0..SEEK_SETTLE_WINDOW_MS) {
                playheadPosMs = lastSeekTargetMs
                playheadWallMs = now
                playheadKey = key
                playheadMoving = playing
                return playheadPosMs
            } else {
                lastSeekTargetMs = -1L
            }
        }
        if (key != playheadKey) {
            playheadKey = key
            playheadPosMs = 0L
            playheadWallMs = now
            playheadMoving = playing
        }
        if (!exclusiveUsbOutput.isActive() && playerDelegate.isInitialized()) {
            val playbackState = player.playbackState
            val currentMediaMatch = isCurrentMediaMatch(player, _state.value.current) || player.isPlaying
            if (currentMediaMatch && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)) {
                val exoPos = player.currentPosition.coerceAtLeast(0L)
                if (playbackState == Player.STATE_BUFFERING && playheadPosMs == 0L && exoPos > 1_500L) {
                    return 0L
                }
                playheadPosMs = exoPos
                playheadWallMs = now
                playheadMoving = playing && playbackState == Player.STATE_READY
                val dur = _state.value.durationMs
                return if (dur > 0L) exoPos.coerceAtMost(dur) else exoPos
            } else if (playbackState == Player.STATE_ENDED) {
                val dur = if (_state.value.durationMs > 0L) _state.value.durationMs else player.duration.coerceAtLeast(0L)
                playheadPosMs = dur
                playheadWallMs = now
                playheadMoving = false
                return dur
            }
            playheadMoving = false
            playheadWallMs = now
            return playheadPosMs
        }
        if (!playing) {
            if (playheadMoving) {
                playheadPosMs += (now - playheadWallMs).coerceAtLeast(0L)
                playheadMoving = false
            }
            playheadWallMs = now
            val dur = _state.value.durationMs
            return if (dur > 0L) playheadPosMs.coerceAtMost(dur) else playheadPosMs
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


    fun seekToQueueItem(index: Int) = onMain {
        if (isCasting) {
            val snapshot = _state.value
            if (index in snapshot.queue.indices) {
                val targetTrack = snapshot.queue[index]
                _state.value = snapshot.copy(current = targetTrack, currentIndex = index,
                    positionMs = 0, durationMs = targetTrack.durationMs ?: findKnownDuration(targetTrack) ?: 0L, error = null)
                castPlayback?.load(_state.value)
            }
            return@onMain
        }
        val snapshot = _state.value
        // If user taps the song currently playing, restart it from 0:00 immediately
        if (index in snapshot.queue.indices && index == snapshot.currentIndex) {
            seekTo(0)
            player.play()
            return@onMain
        }
        if (index in snapshot.queue.indices && index != snapshot.currentIndex) {
            recordHistory(snapshot.current?.mediaIdKey())
        }
        resetPlayhead(0L, snapshot.queue.getOrNull(index)?.mediaIdKey())
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
        val isResolvingCurrent = pendingState.currentIndex != player.currentMediaItemIndex ||
            playRequest?.isActive == true
        val pos = if (isResolvingCurrent) 0L else player.currentPosition
        if (pos > 5_000) {
            seekTo(0)
        } else {
            // Under shuffle, walk the explicit listening history first:
            // the engine permutation is rebuilt on toggle/handoff/edits, so
            // its "previous" is often a song never heard in this session.
            // With exhausted history there is no heard song to return to â€”
            // restart instead of jumping to a random unheard track.
            val historyIndex = if (pendingState.shuffleEnabled) popHistoryIndex(pendingState) else null
            if (historyIndex == null && pendingState.shuffleEnabled) {
                seekTo(0)
                return@onMain
            }
            val timeline = player.currentTimeline
            val fromIndex = if (pendingState.currentIndex in 0 until player.mediaItemCount) {
                pendingState.currentIndex
            } else {
                player.currentMediaItemIndex
            }
            val repeatOne = player.repeatMode == Player.REPEAT_MODE_ONE ||
                pendingState.repeatMode == Player.REPEAT_MODE_ONE
            var candidatePrev = if (!timeline.isEmpty && fromIndex in 0 until player.mediaItemCount) {
                timeline.getPreviousWindowIndex(fromIndex, player.repeatMode, player.shuffleModeEnabled).takeIf { it != C.INDEX_UNSET }
            } else {
                player.previousMediaItemIndex.takeIf { it != C.INDEX_UNSET }
            }
            var guard = 0
            while (candidatePrev != null &&
                guard++ < player.mediaItemCount &&
                (candidatePrev == fromIndex ||
                 candidatePrev == pendingState.currentIndex ||
                 (candidatePrev in 0 until player.mediaItemCount && player.getMediaItemAt(candidatePrev).mediaId in unavailableMediaIds))
            ) {
                if (repeatOne) break
                candidatePrev = timeline.getPreviousWindowIndex(candidatePrev, player.repeatMode, player.shuffleModeEnabled).takeIf { it != C.INDEX_UNSET }
            }
            val enginePrev = candidatePrev
            val index = historyIndex
                ?: if (enginePrev == null || enginePrev == fromIndex || enginePrev == pendingState.currentIndex || (repeatOne && enginePrev == player.currentMediaItemIndex)) {
                    val logical = previousQueueIndex(pendingState)
                    if (logical != C.INDEX_UNSET) logical else enginePrev
                } else {
                    enginePrev
                }
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
        val timeline = player.currentTimeline
        val fromIndex = if (pendingState.currentIndex in 0 until player.mediaItemCount) {
            pendingState.currentIndex
        } else {
            player.currentMediaItemIndex
        }
        val repeatOne = player.repeatMode == Player.REPEAT_MODE_ONE ||
            pendingState.repeatMode == Player.REPEAT_MODE_ONE
        var candidateNext = if (!timeline.isEmpty && fromIndex in 0 until player.mediaItemCount) {
            timeline.getNextWindowIndex(fromIndex, player.repeatMode, player.shuffleModeEnabled).takeIf { it != C.INDEX_UNSET }
        } else {
            player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }
        }
        var guard = 0
        while (candidateNext != null &&
            guard++ < player.mediaItemCount &&
            (candidateNext == fromIndex ||
             candidateNext == pendingState.currentIndex ||
             (candidateNext in 0 until player.mediaItemCount && player.getMediaItemAt(candidateNext).mediaId in unavailableMediaIds))
        ) {
            if (repeatOne) break
            candidateNext = timeline.getNextWindowIndex(candidateNext, player.repeatMode, player.shuffleModeEnabled).takeIf { it != C.INDEX_UNSET }
        }
        val engineNext = candidateNext
        // Under REPEAT_ONE the engine loops next/previous onto the current
        // item, so a manual next would replay the same track and look dead.
        // Fall through to the logical queue order instead; a single-track
        // loop (no logical next) still replays via the engine index.
        val index = if (engineNext == null || engineNext == fromIndex || engineNext == pendingState.currentIndex || (repeatOne && engineNext == player.currentMediaItemIndex)) {
            val logical = nextQueueIndex(pendingState)
            if (logical != C.INDEX_UNSET) logical else engineNext
        } else {
            engineNext
        }
        index.takeIf { it != C.INDEX_UNSET }?.let {
            if (it in 0 until player.mediaItemCount) resolveAndPlayQueueItem(it)
            else playPendingQueueItem(it, pendingState)
        }
    }

    private fun recordHistory(mediaIdKey: String?) {
        if (mediaIdKey.isNullOrBlank()) return
        synchronized(playHistory) {
            if (playHistory.lastOrNull() == mediaIdKey) return
            playHistory.addLast(mediaIdKey)
            while (playHistory.size > MAX_PLAY_HISTORY) playHistory.removeFirst()
        }
    }

    /**
     * Queue tracks plus history entries still present in the queue, for
     * recently-played exclusion sets. History stores keys only; entries
     * that left the queue can't be resolved back to tracks and are skipped.
     */
    private fun List<PlayableTrack>.withHistoryTracks(): List<PlayableTrack> {
        if (playHistory.isEmpty()) return this
        val byKey = associateBy { it.mediaIdKey() }
        return this + playHistory.mapNotNull { byKey[it] }
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

    /** The live play order: shuffle order when shuffling, list order otherwise. */
    fun toggleShuffle() = setShuffleEnabled(!state.value.shuffleEnabled)

    fun setShuffleEnabled(enabled: Boolean) = onMain {
        if (isCasting) {
            _state.update { it.copy(shuffleEnabled = enabled) }
            persistPlaybackSession()
            return@onMain
        }
        // Converge BOTH flags: refresh() mirrors the engine flag into state
        // on every player event, so returning early on engine-only equality
        // leaves a stale state flag behind â€” the toggle then visibly flips
        // back ("unsuffles itself") on the next event.
        if (player.shuffleModeEnabled == enabled && _state.value.shuffleEnabled == enabled) return@onMain
        cancelCrossfade()
        if (enabled && player.mediaItemCount > 1) {
            val current = player.currentMediaItemIndex.takeIf { it in 0 until player.mediaItemCount } ?: 0
            val rest = (0 until player.mediaItemCount).filter { it != current }.shuffled()
            val order = (listOf(current) + rest).toIntArray()
            player.setShuffleOrder(DefaultShuffleOrder(order, Random.nextLong()))
        }
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
        cancelCrossfade()
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

    fun setPlaybackParameters(speed: Float, pitch: Float) = onMain {
        val clampedSpeed = speed.coerceIn(0.25f, 3.0f)
        val clampedPitch = pitch.coerceIn(0.25f, 3.0f)
        if (isCasting) {
            castPlayback?.setSpeed(clampedSpeed)
            _state.update { it.copy(speed = clampedSpeed, pitch = clampedPitch) }
            persistPlaybackSession()
            return@onMain
        }
        cancelCrossfade()
        player.playbackParameters = PlaybackParameters(clampedSpeed, clampedPitch)
        _state.update { it.copy(speed = clampedSpeed, pitch = clampedPitch) }
        persistPlaybackSession()
    }

    fun setSpeed(speed: Float) = onMain {
        val currentPitch = runCatching { player.playbackParameters.pitch }.getOrDefault(_state.value.pitch)
        setPlaybackParameters(speed, currentPitch)
    }

    fun setPitch(pitch: Float) = onMain {
        val currentSpeed = runCatching { player.playbackParameters.speed }.getOrDefault(_state.value.speed)
        setPlaybackParameters(currentSpeed, pitch)
    }

    fun resetPlaybackParameters() = onMain {
        setPlaybackParameters(1f, 1f)
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
     * @param clearSession When true (the default â€” matches every existing
     *   caller's prior behavior), the persisted queue/track in
     *   SharedPreferences is wiped along with the in-memory state, so the
     *   next launch starts with no player. Pass false when the stop is
     *   incidental (e.g. the task was swiped from Recents while nothing was
     *   playing) and the last session should still be restorable the next
     *   time the app opens.
     */
    fun stopAndClear(clearSession: Boolean = true) = onMain {
        // Captured before the player is torn down: afterwards it reports 0.
        val stoppedAtMs = runCatching {
            if (playerDelegate.isInitialized() && player.mediaItemCount > 0) player.currentPosition else null
        }.getOrNull()?.takeIf { it > 0L } ?: _state.value.positionMs
        if (isCasting) castPlayback?.disconnect()
        // stop any automix blend too
        autoMixCoordinator.get().cancelTransition(revertToPrimary = true)
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
        // Save the podcast resume position before the queue is torn down.
        checkpointPodcastProgress()
        // stop() keeps playWhenReady unchanged, so no listener callback would
        // ever abandon the focus we still hold — do it explicitly, or every
        // other media app keeps ducking to a stopped player.
        abandonMediaAudioFocus()
        player.stop()
        player.clearMediaItems()
        // Shuffle is engine state that survives stop()/clearMediaItems(): without
        // this reset the next fresh queue silently inherits shuffle while the
        // fresh state says off â€” until the first refresh() flips it back on.
        player.shuffleModeEnabled = false
        preparedStreams.clear()
        if (clearSession) {
            _state.update { live -> MusicPlayerState().withSubsystemStateFrom(live) }
            clearPersistedPlaybackSession()
            applicationScope.launch(Dispatchers.IO) { WidgetUpdater.clear(appContext) }
        } else {
            // Keep the song where it stopped, for resume() and for the saved
            // session that restores it after the process is gone.
            resumeAfterStopPositionMs = stoppedAtMs.coerceAtLeast(0L)
            _state.update { it.copy(isPlaying = false, isBuffering = false, positionMs = stoppedAtMs.coerceAtLeast(0L)) }
            persistPlaybackSession()
        }
        if (clearSession) resumeAfterStopPositionMs = null
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
    fun clearQueueKeepCurrent() = onMain {
        cancelCrossfade()
        if (isCasting) {
            val snapshot = _state.value
            val currentTrack = snapshot.current ?: return@onMain
            _state.value = snapshot.copy(queue = listOf(currentTrack), currentIndex = 0, current = currentTrack)
            persistPlaybackSession()
            return@onMain
        }
        val count = player.mediaItemCount
        if (count <= 1) return@onMain
        val current = player.currentMediaItemIndex
        if (current !in 0 until count) return@onMain
        if (current + 1 < count) {
            player.removeMediaItems(current + 1, count)
        }
        if (current > 0) {
            player.removeMediaItems(0, current)
        }
        val snapshot = _state.value
        val currentTrack = snapshot.current
        if (currentTrack != null) {
            _state.value = snapshot.copy(queue = listOf(currentTrack), currentIndex = 0, current = currentTrack)
            persistPlaybackSession()
        }
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
     * Attaches a YouTube thumbnail when the video id is already known.
     * Does not search, and does not resolve streams. Scrolling a playlist
     * must not start resolver work.
     */
    internal fun enrichUpcomingQueue(currentIndex: Int) {
        queueEnrichmentJob?.cancel()
        queueEnrichmentJob = null
        if (currentIndex !in 0 until player.mediaItemCount) return
        // Podcast queues are finite lists of episodes: no artwork/related
        // enrichment from the music graph.
        val currentIsEpisode = player.getMediaItemAt(currentIndex).toPlayableTrack().mediaType ==
            MediaType.PODCAST_EPISODE
        if (currentIsEpisode) return
        val nextIndex = if (player.shuffleModeEnabled) player.nextMediaItemIndex else currentIndex + 1
        if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until player.mediaItemCount) return
        if (nextIndex == player.currentMediaItemIndex) return
        val item = player.getMediaItemAt(nextIndex)
        val track = item.toPlayableTrack()
        val videoId = track.videoId?.takeIf { it.isNotBlank() } ?: return
        if (!track.artworkUrl.isNullOrBlank()) return
        replaceMediaItemPreservingShuffle(nextIndex, track.withYoutubeArtwork().toMediaItem(
            item.localConfiguration?.customCacheKey?.let(preparedStreams::get)?.takeUnless { it.isExpired() },
        ))
        warmArtwork(track.copy(videoId = videoId))
    }

    private var thumbnailJob: Job? = null

    private fun warmArtwork(track: PlayableTrack) {
        val url = track.withYoutubeArtwork().artworkUrl?.takeIf(String::isNotBlank) ?: return
        val videoId = track.videoId
        thumbnailJob?.cancel()
        MetadataLog.delayed("thumbnail", videoId)
        thumbnailJob = applicationScope.launch {
            delay(MetadataLog.SETTLE_MS)
            MetadataLog.started("thumbnail", videoId)
            appContext.imageLoader.enqueue(ImageRequest.Builder(appContext).data(url).size(512).build())
        }
    }

    /**
     * Radio and discover growth hit the network. They wait until the current
     * song has been playing, so they do not share the startup connection
     * with the stream extract or the next-track prefetch.
     */
    internal fun scheduleDeferredRecommendations() {
        val generation = playRequestGeneration.get()
        val videoId = _state.value.current?.videoId
        recommendationJob?.cancel()
        MetadataLog.delayed("recommendations", videoId)
        recommendationJob = applicationScope.launch {
            delay(MetadataLog.SETTLE_MS)
            if (generation != playRequestGeneration.get()) {
                MetadataLog.skipped("recommendations", videoId)
                return@launch
            }
            MetadataLog.started("recommendations", videoId)
            withContext(Dispatchers.Main.immediate) {
                if (generation != playRequestGeneration.get()) return@withContext
                val index = player.currentMediaItemIndex
                if (index !in 0 until player.mediaItemCount) return@withContext
                if (discoverQueueActive && !radioQueueActive) {
                    appendMissingDiscoverTracks(discoverRepository.getCachedFeed().map(GeneratedTrack::toPlayableTrack))
                }
                extendDiscoverQueueIfNeeded(index)
                extendRadioQueueIfNeeded(index)
            }
        }
    }

    /**
     * Pre-resolves only the next track. The track after that stays unresolved
     * until it becomes the next selection.
     */
    @MainThread
    internal fun preloadNextQueueItem(currentIndex: Int) {
        if (player.repeatMode == Player.REPEAT_MODE_ONE || _state.value.repeatMode == Player.REPEAT_MODE_ONE) return
        val nextIndex = if (player.shuffleModeEnabled) player.nextMediaItemIndex else currentIndex + 1
        if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until player.mediaItemCount || nextIndex == currentIndex) return
        val nextItem = player.getMediaItemAt(nextIndex)
        if (nextItem.localConfiguration?.uri?.scheme != "lastwave") return
        preloadNextTrack(nextIndex, nextItem.toPlayableTrack())
    }

    /**
     * Warms the addon's server-side search cache for the few items after
     * [nextIndex] (the next item itself gets a full preload). A tap or skip
     * to any of them then skips the cold upstream lookup. Sequential, once
     * per track, after a short delay so it never competes with the real
     * next-track resolve.
     */
    @MainThread
    private fun warmUpcomingSearches(nextIndex: Int) {
        val timeline = player.currentTimeline
        if (timeline.isEmpty || nextIndex !in 0 until timeline.windowCount) return
        val shuffle = player.shuffleModeEnabled
        val upcoming = ArrayList<PlayableTrack>(SEARCH_WARM_AHEAD)
        var idx = nextIndex
        var steps = 0
        while (upcoming.size < SEARCH_WARM_AHEAD && steps < SEARCH_WARM_AHEAD * 3) {
            steps++
            idx = timeline.getNextWindowIndex(idx, Player.REPEAT_MODE_OFF, shuffle)
            if (idx == C.INDEX_UNSET || idx == player.currentMediaItemIndex) break
            val item = player.getMediaItemAt(idx)
            if (item.localConfiguration?.uri?.scheme != "lastwave") continue
            val track = item.toPlayableTrack()
            if (track.playbackUrl != null || track.title.isBlank() || track.artist.isBlank()) continue
            upcoming += track
        }
        if (upcoming.isEmpty()) return
        val generation = playRequestGeneration.get()
        searchWarmJob?.cancel()
        searchWarmJob = applicationScope.launch(Dispatchers.IO) {
            delay(SEARCH_WARM_DELAY_MS)
            if (!canWarmPlayback(generation)) return@launch
            val misc = latestPlaybackSettings
            if (!misc.preferLosslessStreaming ||
                selectedStreamingQuality(misc) == LosslessMusicApi.QUALITY_YOUTUBE
            ) return@launch
            val quality = effectiveLosslessQuality(misc)
            for (track in upcoming) {
                if (!canWarmPlayback(generation) || losslessMusicApi.isCoolingDown) return@launch
                runCatching {
                    losslessMusicApi.warmSearch(
                        track.title, track.artist, track.album, quality,
                        expectedDurationSeconds = track.durationMs?.takeIf { it > 0 }?.div(1_000)?.toInt(),
                    )
                }.onFailure { if (it is CancellationException) throw it }
            }
        }
    }

    /**
     * Warms stream lookup for likely taps on an unmetered connection. The first
     * candidate with a known video ID can also reuse its opening audio bytes.
     */
    fun warmLikelyPlays(tracks: List<PlayableTrack>) {
        searchResultWarmJob?.cancel()
        val targets = tracks
            .filter { it.mediaType == MediaType.SONG && it.title.isNotBlank() && it.playbackUrl == null }
            .distinctBy(PlayableTrack::mediaIdKey)
            .take(SEARCH_RESULT_WARM_COUNT)
        if (targets.isEmpty()) return
        val generation = playRequestGeneration.get()
        searchResultWarmJob = applicationScope.launch(Dispatchers.IO) {
            // Results land every debounce while typing; only warm a set the user is looking at.
            delay(SEARCH_RESULT_WARM_DWELL_MS)
            val misc = latestPlaybackSettings
            val quality = selectedStreamingQuality(misc)
            val firstVideoId = targets.firstNotNullOfOrNull { it.playbackIdentity()?.youtubeVideoId }
            for (track in targets) {
                if (!canWarmPlayback(generation)) break
                coroutineScope {
                    if (misc.preferLosslessStreaming && quality != LosslessMusicApi.QUALITY_YOUTUBE &&
                        track.artist.isNotBlank() && !losslessMusicApi.isCoolingDown
                    ) launch {
                        runCatching {
                            losslessMusicApi.warmSearch(
                                track.title, track.artist, track.album, effectiveLosslessQuality(misc, quality),
                                expectedDurationSeconds = track.durationMs?.takeIf { it > 0 }?.div(1_000)?.toInt(),
                            )
                        }.onFailure { if (it is CancellationException) throw it }
                    }
                    val identity = track.playbackIdentity()
                    if (identity != null && streamResolver.playback.requested != identity) launch youtubeWarm@{
                        val stream = resolvePreloadYoutube(track, quality) ?: return@youtubeWarm
                        if (identity.youtubeVideoId == firstVideoId) {
                            warmStreamPrefix(stream, generation)
                        }
                    }
                }
            }
        }
    }

    private var deferredPreload: Pair<Int, PlayableTrack?>? = null

    /** Playback was asked for but no audio is advancing yet (initial start or rebuffer). */
    @MainThread
    private fun isCurrentTrackStarting(): Boolean {
        if (isCasting) return false
        val live = player
        return live.playWhenReady && !live.isPlaying &&
            live.playbackState != Player.STATE_ENDED && live.mediaItemCount > 0
    }

    @MainThread
    private fun releaseDeferredPreload() {
        val (nextIndex, requested) = deferredPreload ?: return
        if (isCurrentTrackStarting()) return
        deferredPreload = null
        // The queue may have changed while parked: only preload what is still there.
        if (nextIndex !in 0 until player.mediaItemCount || nextIndex == player.currentMediaItemIndex) return
        val live = player.getMediaItemAt(nextIndex).toPlayableTrack()
        if (requested != null && live.mediaIdKey() != requested.mediaIdKey()) return
        preloadNextTrack(nextIndex, live)
    }

    fun cancelSearchResultWarm() {
        searchResultWarmJob?.cancel()
    }

    internal fun preloadNextTrack(nextIndex: Int, nextTrack: PlayableTrack?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            onMain { preloadNextTrack(nextIndex, nextTrack) }
            return
        }
        // While the current track is still starting, its first CDN segments
        // and the next track's resolve/download would split the bandwidth
        // and delay first audio. Park the newest request until it plays.
        if (isCurrentTrackStarting()) {
            deferredPreload = nextIndex to nextTrack
            return
        }
        deferredPreload = null
        if (nextTrack == null || nextTrack.playbackUrl != null || nextTrack.title.isBlank()) return
        val identity = nextTrack.playbackIdentity()
        if (identity != null && streamResolver.playback.requested == identity) return
        warmArtwork(nextTrack)
        val generation = playRequestGeneration.get()
        preloadJob?.cancel()
        preloadJob = applicationScope.launch(Dispatchers.IO) {
            val misc = latestPlaybackSettings
            val quality = selectedStreamingQuality(misc)
            val wantProvider = misc.preferLosslessStreaming &&
                quality != LosslessMusicApi.QUALITY_YOUTUBE && !losslessMusicApi.isCoolingDown
            val preloadScope = this
            // These are read and written only on Main. A higher-quality, ready
            // result can replace the fallback while the item is still upcoming.
            var expectedMediaId = nextTrack.mediaIdKey()
            var prefixJob: Job? = null

            suspend fun publish(stream: ResolvedStream) = withContext(Dispatchers.Main.immediate) {
                if (playRequestGeneration.get() != generation) return@withContext
                val publishedId = publishNextPreparedStream(nextIndex, expectedMediaId, stream)
                    ?: return@withContext
                if (prefixJob == null) warmUpcomingSearches(nextIndex)
                expectedMediaId = publishedId
                prefixJob?.cancel()
                prefixJob = preloadScope.launch {
                    delay(NEXT_TRACK_PREFETCH_DELAY_MS)
                    warmStreamPrefix(stream, generation)
                }
            }

            if (wantProvider) launch {
                val candidate = withTimeoutOrNull(LOSSLESS_BACKGROUND_BUDGET_MS) {
                    runCatching {
                        resolveLosslessTrackAudioStream(
                            track = nextTrack,
                            misc = misc,
                            excludedLosslessUrls = emptySet(),
                            expectedDurationSeconds = nextTrack.durationMs?.takeIf { it > 0 }?.div(1_000)?.toInt(),
                            requestedQuality = quality,
                        )
                    }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                }
                if (candidate != null && isProviderReadyForStartup(candidate)) publish(candidate)
            }
            launch {
                resolvePreloadYoutube(nextTrack, quality)?.let { publish(it) }
            }
        }
    }

    private suspend fun resolvePreloadYoutube(track: PlayableTrack, quality: Int): ResolvedStream? =
        runCatching {
            resolveYoutubeTrackAudioStream(
                track, track.videoId, claimPlayback = false, requestedQuality = quality,
            )
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()

    @MainThread
    private fun publishNextPreparedStream(
        index: Int,
        expectedMediaId: String,
        stream: ResolvedStream,
    ): String? {
        val live = player
        if (index !in 0 until live.mediaItemCount || index == live.currentMediaItemIndex) return null
        val item = live.getMediaItemAt(index)
        if (item.mediaId != expectedMediaId || stream.isExpired()) return null
        val config = item.localConfiguration ?: return null
        if (config.uri.scheme != "lastwave" && config.customCacheKey == null) return null
        val misc = latestPlaybackSettings
        val quality = selectedStreamingQuality(misc)
        if (!isStreamAllowedForQuality(stream, quality, misc)) return null
        val previous = config.customCacheKey?.let(preparedStreams::get)
        if (previous != null && !previous.isExpired() && isStreamAllowedForQuality(previous, quality, misc)) {
            if (StreamingQualityPolicy.rank(streamQualityTier(previous)) >= StreamingQualityPolicy.rank(streamQualityTier(stream))) return null
            // An armed deck may already be buffered for this transition.
            if (crossfadeStandbyArmed && standbyIndex == index) return null
        }

        val track = item.toPlayableTrack()
        val resolvedId = stream.youtubeCandidate?.videoId
        val identified = if (resolvedId != null &&
            (track.videoId.isNullOrBlank() || track.videoId == stream.rematchedFromVideoId)
        ) {
            track.copy(videoId = resolvedId, internalTrackId = resolvedId)
        } else {
            track
        }
        learnQueueVideoId(index, track, resolvedId, stream.rematchedFromVideoId)
        // Record metadata for the upcoming item without changing the active
        // item's duration or the shared HTTP defaults.
        preparedStreams[stream.cacheKey] = stream
        rememberKnownDuration(stream.cacheKey, stream.durationMs)
        rememberTrackDuration(identified, stream.durationMs)
        stagePendingQuality(identified.mediaIdKey(), stream)
        identified.videoId?.let { stagePendingQuality(it, stream) }
        replaceMediaItemPreservingShuffle(index, identified.toMediaItem(stream))
        if (preparedStreams.size > MAX_PREPARED_STREAMS) prunePreparedStreams()
        logStreamEvent("next-prepared", stream, retry = 0)
        return identified.mediaIdKey()
    }

    private fun canWarmPlayback(generation: Long): Boolean =
        playRequestGeneration.get() == generation && !_state.value.isBuffering && networkMonitor.isUnmetered()

    private val prefixDataSourceFactory: CacheDataSource.Factory by lazy {
        val upstream = OkHttpDataSource.Factory(
            mediaHttpClient.newBuilder().callTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build(),
        ).setContentTypePredicate(HttpDataSource.REJECT_PAYWALL_TYPES)
            .setUserAgent(null)
            .setDefaultRequestProperties(NEUTRAL_MEDIA_HEADERS)
        // Prefix reads share the byte cache and transport, but have their
        // own HTTP defaults so headers from the playing stream stay scoped.
        CacheDataSource.Factory()
            .setCache(mediaCache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private suspend fun warmStreamPrefix(stream: ResolvedStream, generation: Long) {
        if (!canWarmPlayback(generation) || stream.isExpired() || stream.segmentedDrm != null ||
            stream.mimeType == MimeTypes.APPLICATION_MPD || stream.mimeType.contains("mpegurl", ignoreCase = true)
        ) return
        val misc = latestPlaybackSettings
        if (!isStreamAllowedForQuality(stream, selectedStreamingQuality(misc), misc)) return
        if (mediaCache.isCached(stream.cacheKey, 0L, NEXT_TRACK_PREFETCH_BYTES)) return
        val uri = Uri.parse(stream.url)
        if (uri.scheme != "http" && uri.scheme != "https") return
        val headers = mediaRequestHeaders(stream)
        val request = DataSpec.Builder()
            .setUri(uri)
            .setPosition(0L)
            .setLength(NEXT_TRACK_PREFETCH_BYTES)
            .setKey(stream.cacheKey)
            .build()
            .withRequestHeaders(headers)
        val source = prefixDataSourceFactory.createDataSource()
        try {
            source.open(request)
            val buffer = ByteArray(16 * 1024)
            var remaining = NEXT_TRACK_PREFETCH_BYTES.toInt()
            while (remaining > 0 && canWarmPlayback(generation)) {
                currentCoroutineContext().ensureActive()
                val count = source.read(buffer, 0, minOf(remaining, buffer.size))
                if (count <= 0) break
                remaining -= count
            }
        } catch (_: java.io.IOException) {
            // A speculative prefix may fail while the stream remains usable.
        } finally {
            runCatching { source.close() }
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
    internal fun cacheCurrentTrackStream(stream: ResolvedStream?) {
        currentTrackCacheJob?.cancel()
        if (stream == null) return
        val uri = Uri.parse(stream.url)
        if (uri.scheme !in setOf("http", "https")) return
        val cacheKey = stream.cacheKey
        val requestHeaders = mediaRequestHeaders(stream)
        currentTrackCacheJob = applicationScope.launch(Dispatchers.IO) {
            // Let ExoPlayer open the stream and buffer the opening window
            // first; background caching must never delay audibility.
            delay(CURRENT_TRACK_CACHE_START_DELAY_MS)
            if (!_state.value.isPlaying) return@launch
            currentCoroutineContext().ensureActive()
            var offset = 0L
            while (isActive) {
                currentCoroutineContext().ensureActive()
                // Read-ahead beyond the player's own buffer is a byte
                // prefetch: only on unmetered routes, and paused on a handoff.
                if (_state.value.isBuffering || !_state.value.isPlaying || !networkMonitor.isUnmetered()) {
                    delay(CURRENT_TRACK_CACHE_STANDBY_YIELD_MS)
                    continue
                }
                // An armed crossfade standby needs the pipe to reach STATE_READY.
                // Yield while it holds the upcoming queue rather than
                // cancelling, so no progress is lost and caching resumes after
                // the hand-off. Only files big enough to still be downloading
                // at the fade ever hit this, which is why Opus never showed the
                // starvation that made lossless fades fail.
                if (crossfadeStandbyPreparing) {
                    delay(CURRENT_TRACK_CACHE_STANDBY_YIELD_MS)
                    continue
                }
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
                val previousCount = player.mediaItemCount
                appendToQueueEnd(fresh.map(PlayableTrack::toMediaItem))
                refresh(player)
                if (player.playbackState == Player.STATE_ENDED) {
                    val nextToPlay = previousCount.coerceIn(0, player.mediaItemCount - 1)
                    resolveAndPlayQueueItem(nextToPlay)
                }
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
        currentRadioContinuationToken = null
        currentRadioAutomixPlaylistId = null
    }

    private suspend fun <T> retryRadioRequest(
        times: Int = 3,
        initialDelayMs: Long = 500L,
        block: suspend () -> T?,
    ): T? {
        var delayMs = initialDelayMs
        repeat(times) { attempt ->
            try {
                val result = block()
                if (result != null) return result
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                android.util.Log.d("MusicPlayer", "Radio request attempt ${attempt + 1} failed", e)
            }
            if (attempt < times - 1) {
                delay(delayMs)
                delayMs *= 2
            }
        }
        return null
    }

    private suspend fun reseedWithLastQueueSong(
        currentQueue: List<PlayableTrack>,
        knownVideoIds: Set<String>,
        knownKeys: Set<String>,
        knownSongKeys: Set<String>,
        recentHistoryKeys: Set<String>,
    ): List<PlayableTrack> {
        val candidateTrack = currentQueue.asReversed().firstOrNull {
            !it.videoId.isNullOrBlank() && it.videoId !in radioUsedSeeds
        } ?: run {
            // Recycle seeds so endless radio playback never terminates
            radioUsedSeeds.clear()
            currentQueue.lastOrNull { !it.videoId.isNullOrBlank() }
        }
        val seedVideoId = candidateTrack?.videoId
        if (seedVideoId == null) {
            android.util.Log.d("MusicPlayer", "No fresh seed track available for radio re-seeding; stopping extending quietly")
            return emptyList()
        }
        radioUsedSeeds.add(seedVideoId)
        if (recommendationRepository.isSpotifyActive()) {
            android.util.Log.i("MusicPlayer", "Re-seeding Spotify recommendations from queue track: $seedVideoId")
            val seeded = recommendationRepository.relatedFor(
                seedVideoId = seedVideoId,
                title = candidateTrack.title,
                artist = candidateTrack.artist,
                limit = 30,
                durationSeconds = candidateTrack.durationMs?.let { (it / 1000L).toInt() },
            )
            return PureRadioQueue.filterStrictYtmRadioBatch(
                incomingTracks = seeded.map { it.toPlayableTrack() },
                existingVideoIds = knownVideoIds,
                existingKeys = knownKeys,
                existingSongKeys = knownSongKeys,
                recentVideoIds = recentHistoryKeys,
                recentKeys = recentHistoryKeys,
                seedVideoId = seedVideoId,
                seedSongKey = PureRadioQueue.normalizedSongKey(candidateTrack.title, candidateTrack.artist),
            )
        }
        android.util.Log.i("MusicPlayer", "Re-seeding YTM radio with RDAMVM of queue track: $seedVideoId")
        val reseedResult = retryRadioRequest { innerTube.fetchRadio(seedVideoId) }
        return if (reseedResult != null && reseedResult.tracks.isNotEmpty()) {
            currentRadioContinuationToken = reseedResult.continuationToken
            currentRadioAutomixPlaylistId = reseedResult.automixPlaylistId
            val filtered = PureRadioQueue.filterStrictYtmRadioBatch(
                incomingTracks = reseedResult.tracks.map { it.toPlayableTrack() },
                existingVideoIds = knownVideoIds,
                existingKeys = knownKeys,
                existingSongKeys = knownSongKeys,
                recentVideoIds = recentHistoryKeys,
                recentKeys = recentHistoryKeys,
                seedVideoId = seedVideoId,
                seedSongKey = PureRadioQueue.normalizedSongKey(candidateTrack.title, candidateTrack.artist),
            )
            if (filtered.isEmpty()) {
                PureRadioQueue.filterStrictYtmRadioBatch(
                    incomingTracks = reseedResult.tracks.map { it.toPlayableTrack() },
                    existingVideoIds = knownVideoIds,
                    existingKeys = knownKeys,
                    existingSongKeys = knownSongKeys,
                    recentVideoIds = emptySet(),
                    recentKeys = emptySet(),
                    seedVideoId = seedVideoId,
                    seedSongKey = PureRadioQueue.normalizedSongKey(candidateTrack.title, candidateTrack.artist),
                )
            } else {
                filtered
            }
        } else {
            android.util.Log.d("MusicPlayer", "Re-seeding with RDAMVM returned no tracks; stopping extending quietly")
            emptyList()
        }
    }

    private fun startRadioQueue(seed: PlayableTrack, resumePlaybackImmediately: Boolean = false) {
        // Podcast episodes are not radio seeds: a podcast queue never grows
        // with related songs, it is a finite list the user picked.
        if (seed.mediaType != MediaType.SONG) return
        radioQueueLoadJob?.cancel()
        radioUsedSeeds.clear()
        currentRadioContinuationToken = null
        currentRadioAutomixPlaylistId = null
        seed.videoId?.takeIf(String::isNotBlank)?.let { radioUsedSeeds.add(it) }

        val expectedGeneration = playRequestGeneration.get()

        radioQueueLoadJob = applicationScope.launch(Dispatchers.IO) {
            radioQueueMutex.withLock {
                try {
                    if (expectedGeneration != playRequestGeneration.get() || !radioQueueActive) return@withLock

                    val seedVideoId = seed.videoId?.takeIf(String::isNotBlank)
                        ?: innerTube.findBestMatchOrNull(seed.title, seed.artist, prefetchStreams = false)?.videoId

                    if (seedVideoId != null) {
                        radioUsedSeeds.add(seedVideoId)
                    }

                    if (expectedGeneration != playRequestGeneration.get() || !radioQueueActive) return@withLock

                    val existingTracks = withContext(Dispatchers.Main.immediate) {
                        if (!playerDelegate.isInitialized()) emptyList()
                        else (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toPlayableTrack() }
                    }
                    val existingVideoIds = existingTracks.mapNotNullTo(mutableSetOf()) { it.videoId }
                    val existingKeys = existingTracks.mapTo(mutableSetOf()) { it.queueKey() }
                    val existingSongKeys = existingTracks.mapTo(mutableSetOf()) { PureRadioQueue.normalizedSongKey(it.title, it.artist) }
                    val recentHistoryKeys = playHistory.takeLast(50).toSet()

                    val toAdd: List<PlayableTrack> = if (seedVideoId != null && recommendationRepository.isSpotifyActive()) {
                        val seeded = recommendationRepository.relatedFor(
                            seedVideoId = seedVideoId,
                            title = seed.title,
                            artist = seed.artist,
                            limit = 30,
                            durationSeconds = seed.durationMs?.let { (it / 1000L).toInt() },
                        )
                        if (expectedGeneration != playRequestGeneration.get() || !radioQueueActive) return@withLock
                        PureRadioQueue.filterStrictYtmRadioBatch(
                            incomingTracks = seeded.map { it.toPlayableTrack() },
                            existingVideoIds = existingVideoIds,
                            existingKeys = existingKeys,
                            existingSongKeys = existingSongKeys,
                            recentVideoIds = recentHistoryKeys,
                            recentKeys = recentHistoryKeys,
                            seedVideoId = seedVideoId,
                            seedSongKey = PureRadioQueue.normalizedSongKey(seed.title, seed.artist),
                        )
                    } else if (seedVideoId != null) {
                        val radioResult = retryRadioRequest { innerTube.fetchRadio(seedVideoId) }
                        if (expectedGeneration != playRequestGeneration.get() || !radioQueueActive) return@withLock

                        if (radioResult != null && radioResult.tracks.isNotEmpty()) {
                            currentRadioContinuationToken = radioResult.continuationToken
                            currentRadioAutomixPlaylistId = radioResult.automixPlaylistId
                            PureRadioQueue.filterStrictYtmRadioBatch(
                                incomingTracks = radioResult.tracks.map { it.toPlayableTrack() },
                                existingVideoIds = existingVideoIds,
                                existingKeys = existingKeys,
                                existingSongKeys = existingSongKeys,
                                recentVideoIds = recentHistoryKeys,
                                recentKeys = recentHistoryKeys,
                                seedVideoId = seedVideoId,
                                seedSongKey = PureRadioQueue.normalizedSongKey(seed.title, seed.artist),
                            )
                        } else if (radioResult != null && !radioResult.automixPlaylistId.isNullOrBlank()) {
                            val automixId = radioResult.automixPlaylistId.orEmpty()
                            val automixResult = retryRadioRequest { innerTube.fetchRadioByPlaylistId(automixId) }
                            if (expectedGeneration != playRequestGeneration.get() || !radioQueueActive) return@withLock

                            if (automixResult != null && automixResult.tracks.isNotEmpty()) {
                                currentRadioContinuationToken = automixResult.continuationToken
                                currentRadioAutomixPlaylistId = automixResult.automixPlaylistId
                                PureRadioQueue.filterStrictYtmRadioBatch(
                                    incomingTracks = automixResult.tracks.map { it.toPlayableTrack() },
                                    existingVideoIds = existingVideoIds,
                                    existingKeys = existingKeys,
                                    existingSongKeys = existingSongKeys,
                                    recentVideoIds = recentHistoryKeys,
                                    recentKeys = recentHistoryKeys,
                                    seedVideoId = seedVideoId,
                                    seedSongKey = PureRadioQueue.normalizedSongKey(seed.title, seed.artist),
                                )
                            } else {
                                emptyList()
                            }
                        } else {
                            emptyList()
                        }
                    } else if (seed.artist.isNotBlank()) {
                        val artistTracks = retryRadioRequest { innerTube.searchSongs(seed.artist, limit = 25) }
                        if (expectedGeneration != playRequestGeneration.get() || !radioQueueActive) return@withLock
                        PureRadioQueue.filterStrictYtmRadioBatch(
                            incomingTracks = (artistTracks ?: emptyList()).map { it.toPlayableTrack() },
                            existingVideoIds = existingVideoIds,
                            existingKeys = existingKeys,
                            existingSongKeys = existingSongKeys,
                            recentVideoIds = recentHistoryKeys,
                            recentKeys = recentHistoryKeys,
                            seedVideoId = "",
                            seedSongKey = PureRadioQueue.normalizedSongKey(seed.title, seed.artist),
                        )
                    } else {
                        emptyList()
                    }

                    if (toAdd.isEmpty() || !radioQueueActive || expectedGeneration != playRequestGeneration.get()) return@withLock

                    withContext(Dispatchers.Main.immediate) {
                        if (!radioQueueActive || !playerDelegate.isInitialized() || expectedGeneration != playRequestGeneration.get()) return@withContext
                        val current = player.currentMediaItem?.toPlayableTrack()
                        val stillCurrentSeed = resumePlaybackImmediately ||
                            (seed.videoId != null && current?.videoId == seed.videoId) ||
                            (current?.title.equals(seed.title, ignoreCase = true) && current?.artist.equals(seed.artist, ignoreCase = true))
                        if (!stillCurrentSeed) return@withContext

                        // Radio only ever appends. Songs the user queued (a
                        // playlist, an album) are never removed to make room:
                        // dropping everything after the current index is what
                        // cut an 80-song playlist down mid-playback.
                        val currentKeys = (0 until player.mediaItemCount).mapTo(mutableSetOf()) {
                            player.getMediaItemAt(it).toPlayableTrack().queueKey()
                        }
                        val validToAdd = toAdd.filter { it.queueKey() !in currentKeys }
                        if (validToAdd.isNotEmpty()) {
                            val previousCount = player.mediaItemCount
                            appendToQueueEnd(validToAdd.map(PlayableTrack::toMediaItem))
                            refresh(player)
                            _state.update { it.copy(isEndlessQueue = true) }
                            enrichUpcomingQueue(player.currentMediaItemIndex)

                            if (player.playbackState == Player.STATE_ENDED || resumePlaybackImmediately) {
                                val nextToPlay = previousCount.coerceIn(0, player.mediaItemCount - 1)
                                android.util.Log.i("MusicPlayer", "Queue expired/started: auto-resuming endless playback at index $nextToPlay")
                                resolveAndPlayQueueItem(nextToPlay)
                            } else {
                                preloadNextQueueItem(player.currentMediaItemIndex)
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
    }

    private fun extendRadioQueueIfNeeded(currentIndex: Int, forceIfAtEnd: Boolean = false) {
        if (!radioQueueActive || !autoplaySimilarEnabled || radioQueueLoadJob?.isActive == true) return
        val currentCount = player.mediaItemCount
        val remaining = currentCount - currentIndex - 1
        if (!forceIfAtEnd && (currentIndex < 0 || remaining > RADIO_QUEUE_REFILL_THRESHOLD)) return

        if (!radioQueueMutex.tryLock()) {
            android.util.Log.d("MusicPlayer", "Radio queue extension already in progress; skipping concurrent trigger")
            return
        }

        val expectedGeneration = playRequestGeneration.get()

        radioQueueLoadJob = applicationScope.launch(Dispatchers.IO) {
            try {
                if (expectedGeneration != playRequestGeneration.get() || !radioQueueActive) return@launch

                val currentQueue = withContext(Dispatchers.Main.immediate) {
                    if (!playerDelegate.isInitialized()) emptyList()
                    else (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toPlayableTrack() }
                }
                if (currentQueue.isEmpty() || !radioQueueActive || expectedGeneration != playRequestGeneration.get()) return@launch

                val knownVideoIds = currentQueue.mapNotNullTo(mutableSetOf()) { it.videoId }
                val knownKeys = currentQueue.mapTo(mutableSetOf()) { it.queueKey() }
                val knownSongKeys = currentQueue.mapTo(mutableSetOf()) { PureRadioQueue.normalizedSongKey(it.title, it.artist) }
                val recentHistoryKeys = playHistory.takeLast(50).toSet()

                val continuationToken = currentRadioContinuationToken
                val automixId = currentRadioAutomixPlaylistId

                val toAdd: List<PlayableTrack> = if (!continuationToken.isNullOrBlank()) {
                    val result = retryRadioRequest { innerTube.fetchRadioContinuation(continuationToken) }
                    if (expectedGeneration != playRequestGeneration.get() || !radioQueueActive) return@launch

                    val filtered = if (result != null && result.tracks.isNotEmpty()) {
                        currentRadioContinuationToken = result.continuationToken
                        if (result.automixPlaylistId != null) {
                            currentRadioAutomixPlaylistId = result.automixPlaylistId
                        }
                        PureRadioQueue.filterStrictYtmRadioBatch(
                            incomingTracks = result.tracks.map { it.toPlayableTrack() },
                            existingVideoIds = knownVideoIds,
                            existingKeys = knownKeys,
                            existingSongKeys = knownSongKeys,
                            recentVideoIds = recentHistoryKeys,
                            recentKeys = recentHistoryKeys,
                        )
                    } else emptyList()

                    if (filtered.isNotEmpty()) {
                        filtered
                    } else if (!automixId.isNullOrBlank()) {
                        currentRadioContinuationToken = null
                        currentRadioAutomixPlaylistId = null
                        val automixResult = retryRadioRequest { innerTube.fetchRadioByPlaylistId(automixId) }
                        if (expectedGeneration != playRequestGeneration.get() || !radioQueueActive) return@launch

                        val automixFiltered = if (automixResult != null && automixResult.tracks.isNotEmpty()) {
                            currentRadioContinuationToken = automixResult.continuationToken
                            currentRadioAutomixPlaylistId = automixResult.automixPlaylistId
                            PureRadioQueue.filterStrictYtmRadioBatch(
                                incomingTracks = automixResult.tracks.map { it.toPlayableTrack() },
                                existingVideoIds = knownVideoIds,
                                existingKeys = knownKeys,
                                existingSongKeys = knownSongKeys,
                                recentVideoIds = recentHistoryKeys,
                                recentKeys = recentHistoryKeys,
                            )
                        } else emptyList()

                        if (automixFiltered.isNotEmpty()) {
                            automixFiltered
                        } else {
                            reseedWithLastQueueSong(currentQueue, knownVideoIds, knownKeys, knownSongKeys, recentHistoryKeys)
                        }
                    } else {
                        currentRadioContinuationToken = null
                        reseedWithLastQueueSong(currentQueue, knownVideoIds, knownKeys, knownSongKeys, recentHistoryKeys)
                    }
                } else if (!automixId.isNullOrBlank()) {
                    currentRadioAutomixPlaylistId = null
                    val automixResult = retryRadioRequest { innerTube.fetchRadioByPlaylistId(automixId) }
                    if (expectedGeneration != playRequestGeneration.get() || !radioQueueActive) return@launch

                    val automixFiltered = if (automixResult != null && automixResult.tracks.isNotEmpty()) {
                        currentRadioContinuationToken = automixResult.continuationToken
                        currentRadioAutomixPlaylistId = automixResult.automixPlaylistId
                        PureRadioQueue.filterStrictYtmRadioBatch(
                            incomingTracks = automixResult.tracks.map { it.toPlayableTrack() },
                            existingVideoIds = knownVideoIds,
                            existingKeys = knownKeys,
                            existingSongKeys = knownSongKeys,
                            recentVideoIds = recentHistoryKeys,
                            recentKeys = recentHistoryKeys,
                        )
                    } else emptyList()

                    if (automixFiltered.isNotEmpty()) {
                        automixFiltered
                    } else {
                        reseedWithLastQueueSong(currentQueue, knownVideoIds, knownKeys, knownSongKeys, recentHistoryKeys)
                    }
                } else {
                    reseedWithLastQueueSong(currentQueue, knownVideoIds, knownKeys, knownSongKeys, recentHistoryKeys)
                }

                val finalToAdd = if (toAdd.isEmpty() && forceIfAtEnd) {
                    reseedWithLastQueueSong(currentQueue, knownVideoIds, knownKeys, knownSongKeys, emptySet())
                } else toAdd

                if (finalToAdd.isEmpty() || !radioQueueActive || expectedGeneration != playRequestGeneration.get()) return@launch

                withContext(Dispatchers.Main.immediate) {
                    if (!radioQueueActive || !playerDelegate.isInitialized() || expectedGeneration != playRequestGeneration.get()) return@withContext
                    val existingKeys = (0 until player.mediaItemCount).mapTo(mutableSetOf()) {
                        player.getMediaItemAt(it).toPlayableTrack().queueKey()
                    }
                    val validToAdd = finalToAdd.filter { it.queueKey() !in existingKeys }
                    if (validToAdd.isNotEmpty()) {
                        val previousCount = player.mediaItemCount
                        appendToQueueEnd(validToAdd.map(PlayableTrack::toMediaItem))
                        refresh(player)
                        _state.update { it.copy(isEndlessQueue = true) }
                        enrichUpcomingQueue(player.currentMediaItemIndex)

                        if (player.playbackState == Player.STATE_ENDED) {
                            val nextToPlay = previousCount.coerceIn(0, player.mediaItemCount - 1)
                            android.util.Log.i("MusicPlayer", "End of queue reached: auto-resuming infinite playback at index $nextToPlay")
                            resolveAndPlayQueueItem(nextToPlay)
                        } else {
                            preloadNextQueueItem(player.currentMediaItemIndex)
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                android.util.Log.d("MusicPlayer", "Failed to extend radio queue", e)
            } finally {
                radioQueueMutex.unlock()
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
        // next transition â€” never steal it. This keeps manual next(),
        // queue-taps and unavailable-skips authoritative.
        if (playRequest?.isActive == true) return
        if (unavailableSkipJob?.isActive == true) return
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex == C.INDEX_UNSET || currentIndex !in 0 until player.mediaItemCount) return
        val currentMediaItem = player.currentMediaItem
        val currentUriScheme = currentMediaItem?.localConfiguration?.uri?.scheme
        val mediaId = currentMediaItem?.mediaId
        // If this track is actively resolving or is a lastwave placeholder, it has NOT played yet;
        // never auto-advance or skip past it.
        if (currentUriScheme == "lastwave") return
        if (mediaId != null && resolvingMediaIds.containsKey(mediaId)) return

        // REPEAT_ONE loops the same track: always replay the current item, never advance
        if (player.repeatMode == Player.REPEAT_MODE_ONE || _state.value.repeatMode == Player.REPEAT_MODE_ONE) {
            runCatching {
                player.seekTo(currentIndex, 0L)
                player.prepare()
                player.play()
            }
            resetPlayhead(0L, mediaId)
            _state.update { it.copy(positionMs = 0L, isPlaying = true) }
            return
        }

        effectiveDuration(player.duration, player, _state.value.durationMs)
        val trueDurMs = player.duration
        val pos = runCatching { player.currentPosition }.getOrDefault(0L)

        // If the current track is near the start (just transitioned / resolving),
        // do not auto-advance past it.
        if (pos < 2_000L && (trueDurMs > 5_000L || currentUriScheme == "lastwave")) return

        val stuckAtEnd = (player.playbackState == Player.STATE_ENDED && (pos >= trueDurMs - END_OF_TRACK_STALL_THRESHOLD_MS || trueDurMs <= 0L || pos > 2_000L)) ||
            (
                trueDurMs > 0L &&
                    pos >= trueDurMs - END_OF_TRACK_STALL_THRESHOLD_MS &&
                    player.playWhenReady &&
                    !player.isPlaying &&
                    player.playbackState == Player.STATE_READY &&
                    _state.value.error == null
            )
        if (!stuckAtEnd) return
        if (mediaId == null) return
        val key = "$mediaId|$currentIndex|${player.mediaItemCount}|${player.shuffleModeEnabled}|${player.repeatMode}"
        val now = SystemClock.elapsedRealtime()
        if (key == lastAutoAdvanceKey && now - lastAutoAdvanceAtMs < AUTO_ADVANCE_DEBOUNCE_MS) return
        lastAutoAdvanceKey = key
        lastAutoAdvanceAtMs = now

        val timeline = player.currentTimeline
        if (timeline.isEmpty) return
        var nextIndex = timeline.getNextWindowIndex(currentIndex, player.repeatMode, player.shuffleModeEnabled)
        if (nextIndex == C.INDEX_UNSET) {
            // Queue end: endless queues refill asynchronously â€” kick them
            // and seamlessly resume once items land without pausing/stopping.
            if (discoverQueueActive) {
                extendDiscoverQueueIfNeeded(currentIndex)
                return
            }
            if (radioQueueActive) {
                extendRadioQueueIfNeeded(currentIndex, forceIfAtEnd = true)
                return
            }
            val isRepeatAll = player.repeatMode == Player.REPEAT_MODE_ALL || _state.value.repeatMode == Player.REPEAT_MODE_ALL
            val isRepeatOne = player.repeatMode == Player.REPEAT_MODE_ONE || _state.value.repeatMode == Player.REPEAT_MODE_ONE
            if (isRepeatOne) {
                runCatching {
                    player.seekTo(currentIndex, 0L)
                    player.prepare()
                    player.play()
                }
                resetPlayhead(0L, mediaId)
                _state.update { it.copy(positionMs = 0L, isPlaying = true) }
                return
            } else if (isRepeatAll && player.mediaItemCount > 0) {
                nextIndex = if (player.shuffleModeEnabled) {
                    timeline.getFirstWindowIndex(true).takeIf { it != C.INDEX_UNSET } ?: 0
                } else {
                    0
                }
            } else {
                val currentTrack = player.currentMediaItem?.toPlayableTrack()
                if (autoplaySimilarEnabled && currentTrack != null && !isRepeatOne && currentTrack.mediaType == MediaType.SONG) {
                    radioQueueActive = true
                    _state.update { it.copy(isEndlessQueue = true) }
                    startRadioQueue(currentTrack, resumePlaybackImmediately = true)
                    return
                }
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
    internal fun resetRenderWatchdog() {
        renderStallSinceMs = 0L
        inaudibleSinceMs = 0L
        lastRenderPositionMs = -1L
        lastRenderBufferedMs = -1L
        lastAdvancingPositionMs = -1L
    }

    /**
     * Rendering watchdog: the progress bar is a wall clock and [refresh]
     * preserves the playing flag while a track resolves, so the UI can show
     * a normally-moving "playing" track while nothing audible comes out.
     * Two shapes, both fixed by the same recovery a manual seek performs
     * (renderer reset + fresh source):
     *
     * 1. Parked: ExoPlayer is not rendering at all â€” wedged placeholder
     *    load, dead-but-not-erroring source after a window swap, or a stuck
     *    IDLE/READY window, all with playWhenReady=true. Fires after
     *    [RENDER_STALL_TIMEOUT_MS] with zero movement in position AND
     *    buffer. A growing buffer means a slow network, which must never
     *    be interrupted.
     * 2. Inaudible: ExoPlayer reports rendering with an advancing position
     *    yet no music stream is active system-wide
     *    ([AudioManager.isMusicActive]) â€” output gated downstream of the
     *    player (stale device route, dead exclusive session, wedged sink).
     *    Fires after [INAUDIBLE_TIMEOUT_MS] of advancing-but-silent output.
     *    USB-exclusive playback bypasses AudioTrack by design, so it is
     *    excluded here (the parked branch still guards it).
     *
     * Never fires while a resolve/skip/crossfade owns the transition, on
     * ENDED (owned by [handleNaturalTrackEnd]), while casting, paused, or
     * with an error showing â€” healthy playback is never touched.
     */
    @MainThread
    private fun updateRenderStallWatchdog(nowMs: Long) {
        val snapshot = _state.value
        val stalledWindow = player.currentMediaItemIndex
        val mediaId = player.currentMediaItem?.mediaId
        val windowOk = !isCasting &&
            playerDelegate.isInitialized() &&
            player.mediaItemCount > 0 &&
            snapshot.isPlaying &&
            snapshot.error == null &&
            player.playWhenReady &&
            playRequest?.isActive != true &&
            unavailableSkipJob?.isActive != true &&
            outgoingPlayer == null &&
            !autoMixCoordinator.get().telemetry.value.isBlendingActive &&
            player.playbackState != Player.STATE_BUFFERING &&
            !snapshot.isBuffering &&
            lastSeekTargetMs < 0L &&
            (nowMs - lastSeekAtElapsedMs) >= SEEK_SETTLE_WINDOW_MS &&
            stalledWindow != C.INDEX_UNSET &&
            stalledWindow in 0 until player.mediaItemCount &&
            mediaId != null &&
            isCurrentMediaMatch(player, snapshot.current) &&
            player.playbackState != Player.STATE_ENDED
        // Null mediaId can't smart-cast through the flag above; re-check
        // here so the recovery call below type-checks.
        if (!windowOk || mediaId == null) {
            renderStallSinceMs = 0L
            inaudibleSinceMs = 0L
            return
        }
        val posNow = runCatching { player.currentPosition }.getOrDefault(0L)
        val bufNow = runCatching { player.bufferedPosition }.getOrDefault(0L)
        val progressed = posNow != lastRenderPositionMs || bufNow != lastRenderBufferedMs
        lastRenderPositionMs = posNow
        lastRenderBufferedMs = bufNow
        if (!player.isPlaying) {
            // Parked branch: only a fully frozen loader counts as stalled.
            inaudibleSinceMs = 0L
            if (progressed || player.isLoading) {
                renderStallSinceMs = 0L
                return
            }
            if (renderStallSinceMs == 0L) {
                renderStallSinceMs = nowMs
                return
            }
            if (nowMs - renderStallSinceMs < RENDER_STALL_TIMEOUT_MS) return
            renderStallSinceMs = 0L
            recoverSilentAdvance(stalledWindow, mediaId)
            return
        }
        // Rendering branch: position must be advancing (else the parked
        // branch above owns it) with no active music stream behind it.
        renderStallSinceMs = 0L
        val directOutputBypass = exclusiveUsbOutput.isActive() || internalDirectOutput.isActive()
        val musicActive = directOutputBypass || runCatching { audioManager?.isMusicActive == true }.getOrDefault(true)
        if (posNow == lastAdvancingPositionMs || musicActive) {
            lastAdvancingPositionMs = posNow
            if (musicActive) {
                silentRecoveries.remove(mediaId)
                inaudibleSinceMs = 0L
            }
            return
        }
        lastAdvancingPositionMs = posNow
        if (inaudibleSinceMs == 0L) {
            inaudibleSinceMs = nowMs
            return
        }
        if (nowMs - inaudibleSinceMs < INAUDIBLE_TIMEOUT_MS) return
        inaudibleSinceMs = 0L
        recoverSilentAdvance(stalledWindow, mediaId)
    }

    /**
     * Tiered recovery for a window the watchdog proved silent. Attempt 1 on
     * an already-resolved window mirrors the proven manual-seek rescue: a
     * same-position seek resets the stalled renderers and re-opens the
     * registered source without moving the playhead. If output is still
     * silent, request an audible fallback at the same playhead instead of
     * reopening the same resolved source forever. A fallback that also stays
     * silent is handed to the normal unavailable/skip path.
     */
    @MainThread
    private fun recoverSilentAdvance(index: Int, mediaId: String) {
        if (isCasting) return
        if (!playerDelegate.isInitialized() || index !in 0 until player.mediaItemCount) return
        if (!isCurrentMediaMatch(player, _state.value.current) || !_state.value.isPlaying || _state.value.error != null) return
        if (player.isLoading) {
            renderStallSinceMs = 0L
            return
        }
        if (silentRecoveries.size > 64) silentRecoveries.clear()
        val attempt = (silentRecoveries[mediaId] ?: 0) + 1
        if (attempt > MAX_SILENT_RECOVERIES) {
            scheduleUnavailableMediaSkip(
                failedIndex = index,
                failedMediaId = mediaId,
                expectedGeneration = playRequestGeneration.get(),
                failure = java.io.IOException("No audio output after $MAX_SILENT_RECOVERIES recovery attempts"),
                allowAutoSkip = true,
                exhausted = true,
            )
            return
        }
        silentRecoveries[mediaId] = attempt
        val item = player.getMediaItemAt(index)
        val prepared = item.localConfiguration?.customCacheKey?.let(preparedStreams::get)
            ?: preparedStreams.values.firstOrNull { it.url == item.localConfiguration?.uri?.toString() }
        val isLocal = item.mediaId.startsWith("local:") || _state.value.current?.isLocalTrack() == true
        val hasNonPlaceholderUri = item.localConfiguration?.uri?.scheme?.let { it != "lastwave" } == true
        val windowResolved = hasNonPlaceholderUri && (isLocal || (prepared == null || !prepared.isExpired()))
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        android.util.Log.w(
            "MusicPlayer",
            "Silent window '${_state.value.current?.title}' (attempt $attempt): " +
                "state=${player.playbackState} playWhenReady=${player.playWhenReady} " +
                "pos=${positionMs}ms buf=${player.bufferedPosition}ms " +
                "codec=${prepared?.audioCodec ?: "unknown"} " +
                "resolved=$windowResolved -> ${if (windowResolved && attempt == 1) "renderer reset" else "provider fallback"}",
        )
        if (windowResolved && attempt == 1) {
            resetRenderWatchdog()
            runCatching { player.seekTo(positionMs) }
            if (player.playbackState == Player.STATE_IDLE) runCatching { player.prepare() }
            runCatching { player.play() }
            return
        }
        resetRenderWatchdog()
        if (isLocal) {
            // A provider switch cannot repair a local file's output route.
            // Keep its existing retry path and do not silently replace it.
            resolveAndPlayQueueItem(index)
            return
        }

        val track = item.toPlayableTrack()
        val generation = playRequestGeneration.incrementAndGet()
        playRequest?.cancel()
        cancelActiveUpgrade()
        cancelStaleResolverWork("silent-output-fallback")
        losslessBypassMediaIds += track.mediaIdKey()
        startPlaybackRecoveryLoop(
            failedIndex = index,
            failedMediaId = mediaId,
            track = track.copy(playbackUrl = null, playbackMimeType = null),
            videoId = track.videoId,
            generation = generation,
            resumePositionMs = positionMs,
            failedLosslessStream = prepared?.isLossless == true,
            rejectedUrl = prepared?.url,
        )
    }

    /**
     * Keep-alive for a track that died mid-playback. The three player-error
     * retries are the fast path; when they're gone (throttled URLs, module
     * cold start) the old code parked the player with "Unable to resolve" and
     * a tap-to-retry, which is the mid-song stop users see. This instead keeps
     * the same track buffering and re-resolves it in place — YouTube first,
     * no module walk — until audio comes back or the user moves on. Transient
     * transport failures keep the selected item in place and retry with a
     * capped backoff. Only an explicit unplayable verdict can advance the queue.
     *
     * @param resumePositionMs where the stream died, so playback resumes there
     */
    @MainThread
    internal fun startPlaybackRecoveryLoop(
        failedIndex: Int,
        failedMediaId: String,
        track: PlayableTrack,
        videoId: String?,
        generation: Long,
        resumePositionMs: Long,
        failedLosslessStream: Boolean,
        rejectedUrl: String?,
    ) {
        ensureForegroundService()
        _state.update { it.copy(isBuffering = true, error = null) }
        playbackRecoveryJob?.cancel()
        playbackRecoveryJob = applicationScope.launch(Dispatchers.IO) {
            var attempt = 0
            var lastRecoveryError: Throwable? = null
            while (currentCoroutineContext().isActive) {
                // Navigation bumped the generation, or the user paused: the
                // keep-alive owns nothing any more â€” leave without an error.
                val stillOurs = withContext(Dispatchers.Main.immediate) {
                    generation == playRequestGeneration.get() &&
                        _state.value.isBuffering &&
                        playerDelegate.isInitialized() &&
                        player.playWhenReady &&
                        failedIndex in 0 until player.mediaItemCount &&
                        player.getMediaItemAt(failedIndex).mediaId == failedMediaId
                }
                if (!stillOurs) return@launch
                attempt++
                if (attempt > 1) {
                    // A tap on play during the backoff wants a retry NOW: poll
                    // in small slices instead of one long sleep so the flag lands.
                    var waited = 0L
                    val total = (RECOVERY_BACKOFF_BASE_MS shl (attempt - 2).coerceAtMost(5))
                        .coerceAtMost(RECOVERY_BACKOFF_MAX_MS)
                    while (waited < total && currentCoroutineContext().isActive) {
                        if (recoveryRetryNow) {
                            recoveryRetryNow = false
                            break
                        }
                        delay(250L)
                        waited += 250L
                    }
                }
                val stream = try {
                    boundedRecoveryResolve {
                        resolveTrackAudioStream(
                            track = track,
                            videoId = videoId,
                            allowLocalDownloads = false,
                            // First attempt re-asks the addon (a lossless stream
                            // that died mid-track usually just needs a fresh URL);
                            // later attempts take the fast YouTube path and rely
                            // on the quality upgrade below to return to lossless.
                            allowLossless = attempt == 1 && failedMediaId !in losslessBypassMediaIds,
                            excludedLosslessUrls = if (failedLosslessStream) setOfNotNull(rejectedUrl) else emptySet(),
                            losslessBudget = if (attempt == 1) LosslessBudget.Interactive else LosslessBudget.Disabled,
                        )
                    }
                } catch (cancellation: CancellationException) {
                    // boundedRecoveryResolve() only lets THIS job's own
                    // cancellation through (pause/skip/navigation).
                    throw cancellation
                } catch (error: Throwable) {
                    lastRecoveryError = error
                    logResolutionFailure(track, "playback-recovery", attempt, error)
                    null
                }
                if (stream == null) {
                    if (lastRecoveryError?.let(::isExplicitlyUnplayableFailure) == true) break
                    continue
                }
                val restored = withContext(Dispatchers.Main.immediate) {
                    // Generation move, navigation, or a user pause each own the
                    // transition: never re-install playback under them.
                    if (generation != playRequestGeneration.get() ||
                        !playerDelegate.isInitialized() ||
                        failedIndex !in 0 until player.mediaItemCount ||
                        player.getMediaItemAt(failedIndex).mediaId != failedMediaId ||
                        !_state.value.isBuffering
                    ) {
                        return@withContext false
                    }
                    if (failedIndex in 0 until player.mediaItemCount) {
                        val updated = track.copy(playbackUrl = null, playbackMimeType = null)
                        registerPreparedStream(stream)
                        publishResolvedQuality(stream, expectedMediaId = failedMediaId)
                        applyDacRoutingFor(dacRateFor(stream))
                        cacheCurrentTrackStream(stream)
                        logStreamEvent("playback-recovery", stream, retry = attempt)
                        replaceMediaItemPreservingShuffle(failedIndex, updated.toMediaItem(stream))
                        lastSeekTargetMs = resumePositionMs
                        lastSeekAtElapsedMs = SystemClock.elapsedRealtime()
                        player.seekTo(failedIndex, resumePositionMs)
                        player.prepare()
                        player.play()
                        _state.update { it.copy(error = null, isBuffering = false) }
                        // The track is whole again: restore the fast-retry
                        // budget for the next error.
                        errorRetryCount = 0
                        preloadNextQueueItem(failedIndex)
                        if (!stream.isLossless && stream.audioCodec != "DOLBY ATMOS") {
                            scheduleQualityUpgrade(
                                track = updated,
                                expectedMediaId = failedMediaId,
                                generation = generation,
                                currentStream = stream,
                            )
                        }
                        true
                    } else {
                        false
                    }
                }
                if (restored) return@launch
            }
            // A definitive resolver verdict can still skip a track. Network
            // failures never reach this branch: they continue retrying the
            // same item with a capped delay instead of consuming the queue.
            withContext(Dispatchers.Main.immediate) {
                if (generation != playRequestGeneration.get()) return@withContext
                unavailableAttemptCounts[failedMediaId] = MIN_UNAVAILABLE_ATTEMPTS_BEFORE_SKIP
                scheduleUnavailableMediaSkip(
                    failedIndex = failedIndex,
                    failedMediaId = failedMediaId,
                    expectedGeneration = generation,
                    failure = lastRecoveryError
                        ?: java.io.IOException("Playback recovery exhausted"),
                    allowAutoSkip = true,
                )
            }
        }
    }

    @MainThread
    private fun isDeviceOnline(): Boolean = runCatching {
        val manager = appContext.getSystemService(android.net.ConnectivityManager::class.java)
        val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)
        capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    /** Runs a YouTube fallback resolve up to [MAX_PLAYBACK_RETRIES] times with growing delays. */
    /**
     * Re-resolve for a stream that died mid-track, with a deadline.
     *
     * The retry and recovery coroutines rethrow every CancellationException
     * as "we were cancelled". But a 403 makes reportPlaybackFailure() cancel
     * the shared in-flight stream requests for that video, and a hung resolve
     * never returns at all — both ended the coroutine silently, leaving the
     * player IDLE at ~1:00 with no error, skip or recovery. Only a
     * cancellation of THIS job propagates; anything else is a failure the
     * caller's retry/recovery logic handles.
     */
    private suspend fun <T : Any> boundedRecoveryResolve(resolve: suspend () -> T): T {
        val result = try {
            withTimeoutOrNull(RECOVERY_RESOLVE_TIMEOUT_MS) { resolve() }
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw java.io.IOException("Stream re-resolve was cancelled by another request", error)
        }
        if (result != null) return result
        // A hung shared request would otherwise be re-joined by the next
        // attempt (requests are deduped for 60s): drop and cancel the ones
        // still pending past the whole YouTube resolve budget.
        val cutoff = SystemClock.elapsedRealtime() - YT_RESOLVE_TOTAL_TIMEOUT_MS
        resolutionRequests.entries.removeIf { (_, request) ->
            val stale = !request.second.isCompleted && request.first <= cutoff
            if (stale) request.second.cancel()
            stale
        }
        throw java.io.IOException("Stream re-resolve timed out after ${RECOVERY_RESOLVE_TIMEOUT_MS}ms")
    }

    private suspend fun <T : Any> withFallbackAttempts(resolve: suspend () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return boundedRecoveryResolve(resolve)
            } catch (error: Exception) {
                if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                if (attempt >= MAX_PLAYBACK_RETRIES || isExplicitlyUnplayableFailure(error)) throw error
                delay(PLAYBACK_RETRY_BASE_DELAY_MS * attempt)
                attempt++
            }
        }
    }

    internal fun scheduleUnavailableMediaSkip(
        failedIndex: Int,
        failedMediaId: String?,
        expectedGeneration: Long = playRequestGeneration.get(),
        failure: Throwable,
        allowAutoSkip: Boolean = true,
        exhausted: Boolean = false,
    ) {
        if (failure is CancellationException || expectedGeneration != playRequestGeneration.get()) return
        // Offline, every track fails the same way: skipping would burn the whole queue.
        if (allowAutoSkip && failedMediaId?.startsWith("local:") != true && !isDeviceOnline()) {
            player.pause()
            _state.update { it.copy(isPlaying = false, isBuffering = false, error = "No internet connection. Tap play to retry.") }
            return
        }
        if (!allowAutoSkip) {
            player.pause()
            _state.update { it.copy(isPlaying = false, isBuffering = false, error = "Playback interrupted. Tap play to retry.") }
            return
        }
        if (failedIndex == C.INDEX_UNSET || failedMediaId == null) return
        // No premature 1-2s skip: first transient failure holds with
        // tap-to-retry; only proven-dead or repeatedly failing items
        // auto-advance. Player-error retries already exhausted
        // MAX_PLAYBACK_RETRIES before reaching here, so they skip at once.
        val provenDead = isExplicitlyUnplayableFailure(failure) || isUnsupportedMediaFailure(failure)
        val attempts = (unavailableAttemptCounts[failedMediaId] ?: 0) + 1
        unavailableAttemptCounts[failedMediaId] = attempts
        val alreadyRetriedExhaustively = errorRetryCount >= MAX_PLAYBACK_RETRIES
        if (!exhausted && !provenDead && !alreadyRetriedExhaustively && attempts < MIN_UNAVAILABLE_ATTEMPTS_BEFORE_SKIP) {
            player.pause()
            _state.update {
                it.copy(
                    isPlaying = false,
                    isBuffering = false,
                    error = failure.message?.takeIf(String::isNotBlank)
                        ?: "Couldn't load this track. Tap play to retry.",
                )
            }
            return
        }
        player.pause()
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
                !failedItemStillQueued || _state.value.currentIndex != failedIndex
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
                    player.pause()
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
            if (nextIndex == C.INDEX_UNSET && player.mediaItemCount > 1) {
                val candidateIndices = if (player.shuffleModeEnabled) {
                    (0 until player.mediaItemCount).shuffled()
                } else {
                    ((failedIndex + 1 until player.mediaItemCount) + (0 until failedIndex)).toList()
                }
                nextIndex = candidateIndices.firstOrNull { index ->
                    index != failedIndex && player.getMediaItemAt(index).mediaId !in unavailableMediaIds
                } ?: C.INDEX_UNSET
            }
            unavailableSkipJob = null
            if (nextIndex == C.INDEX_UNSET) {
                player.pause()
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

    internal fun onMain(action: () -> Unit) {
        applicationScope.launch(Dispatchers.Main.immediate) { action() }
    }

    data class ResolvedStream(
        val url: String,
        val mimeType: String,
        val bitrateKbps: Int?,
        val audioCodec: String?,
        val cacheKey: String,
        /** Provider tier used to create this stream, or YouTube's standard tier. */
        val qualityTier: Int? = null,
        val requestHeaders: Map<String, String> = emptyMap(),
        val isLossless: Boolean = false,
        val bitDepth: Int? = null,
        val samplingRateKHz: Double? = null,
        val youtubeCandidate: YouTubeAudioStream? = null,
        val durationMs: Long? = null,
        /** Provider chunk descriptor; when set, playback is DASH+Widevine. */
        val segmentedDrm: com.lastwave.app.data.plugin.SegmentedStreamDescriptor? = null,
        /** Wall-clock ms when the minted URL dies (addon `exp`, YouTube
         *  `expire`); null = unknown or never expires. Stale prefetches are
         *  re-resolved instead of handed dead to ExoPlayer. */
        val expiresAtEpochMs: Long? = null,
        /** The stored videoId this stream replaced after it turned out
         *  unplayable. Set only by the title/artist rematch fallback; used
         *  to heal stored rows that pinned the dead id. */
        val rematchedFromVideoId: String? = null,
        val userAgent: String? = null,
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
        knownDurationMs: Long? = null,
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
        var fileDurationMs: Long? = null

        runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                if (targetUrl.startsWith("content://")) {
                    retriever.setDataSource(appContext, uri)
                } else {
                    retriever.setDataSource(uri.path ?: targetUrl.removePrefix("file://"))
                }
                fileDurationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.takeIf { it > 0 }
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

        // Heal unindexed fragmented MP4s (Atmos / AAC DASH downloads):
        // If file is local and writable, build mfra seek index and patch duration in-place.
        if (pathLower.endsWith(".m4a") || pathLower.endsWith(".mp4")) {
            runCatching {
                val candidatePath = uri.path ?: targetUrl.removePrefix("file://")
                val localFile = File(candidatePath)
                if (localFile.isFile && localFile.canWrite()) {
                    val finalDurationHint = fileDurationMs ?: knownDurationMs ?: 0L
                    if (Fmp4IndexBuilder.finalizeFmp4(localFile, finalDurationHint)) {
                        if (fileDurationMs == null || fileDurationMs <= 0L) {
                            fileDurationMs = knownDurationMs?.takeIf { it > 0 }
                        }
                    }
                }
            }
        }

        // FLAC STREAMINFO fallback: device- and API-independent ground truth.
        // The retriever keys above need API 31+ and still return null on
        // several OEMs/files, which left bitDepth null and the pill stuck at
        // rate-only ("44.1kHz FLAC"). The header parse works on every device.
        if ((mime.contains("flac") || (uri.path ?: targetUrl).endsWith(".flac", ignoreCase = true)) &&
            (bitDepth == null || samplingRateKHz == null)
        ) {
            runCatching {
                val header: FlacStreamInfo? = if (targetUrl.startsWith("content://")) {
                    appContext.contentResolver.openInputStream(uri)?.use { readFlacStreamInfo(it) }
                } else {
                    val path = uri.path ?: targetUrl.removePrefix("file://")
                    java.io.File(path).takeIf { it.isFile && it.canRead() }?.inputStream()?.use { readFlacStreamInfo(it) }
                }
                if (header != null) {
                    if (bitDepth == null) bitDepth = header.bitDepth?.takeIf { it > 0 }
                    if (samplingRateKHz == null) header.sampleRateHz?.takeIf { it > 0 }?.let { samplingRateKHz = it / 1000.0 }
                }
            }
        }

        val resolvedBadge = badge ?: when {
            mime.contains("flac") -> {
                // Retriever facts only: present depth is trusted (even
                // 16/96), absent depth is unknown â€” the rate alone never
                // asserts 16 or 24 here.
                val d = bitDepth?.takeIf { it > 0 }
                if (samplingRateKHz != null && samplingRateKHz > 0.0) {
                    if (d != null) "$d/${formatSampleRateKHz(samplingRateKHz)}kHz"
                    else "${formatSampleRateKHz(samplingRateKHz)}kHz FLAC"
                } else if ((d ?: 0) > 16) "HI-RES FLAC" else "FLAC"
            }
            mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "M4A AAC"
            mime.contains("opus") || mime.contains("ogg") -> "OPUS"
            mime.contains("mp3") || mime.contains("mpeg") -> "320k MP3"
            else -> "AUDIO"
        }

        val isLossless = isLosslessTrack ?: mime.contains("flac")
        val trackKey = "${displayArtist.lowercase()}_${displayTitle.lowercase()}"
        val effectiveDurationMs = fileDurationMs ?: knownDurationMs?.takeIf { it > 0 }

        return ResolvedStream(
            url = uri.toString(),
            mimeType = mime,
            bitrateKbps = bitrateKbps,
            audioCodec = resolvedBadge,
            cacheKey = "local:$trackKey",
            isLossless = isLossless,
            bitDepth = if (isLossless) bitDepth?.takeIf { it > 0 } else null,
            samplingRateKHz = samplingRateKHz?.takeIf { it > 0.0 },
            durationMs = effectiveDurationMs,
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
            bitDepth = s.bitDepth?.takeIf { it > 0 },
            samplingRateKHz = s.sampleRate.takeIf { it > 0 }?.div(1000.0),
            // Seed the slider denominator: the MPD timeline alone may take a
            // while to parse, and without this the bar sat dead until then.
            durationMs = descriptor.durationSec.takeIf { it > 0 }?.times(1_000L),
            segmentedDrm = descriptor,
        )
    }

    private suspend fun resolveLocalDownloadedAudioStream(
        track: PlayableTrack,
        deepScan: Boolean = true,
    ): ResolvedStream? {
        val title = track.title.trim()
        val artist = track.artist.trim()

        // 1. If track already carries a playbackUrl (e.g. from Downloads screen), check it first
        track.playbackUrl?.takeIf(String::isNotBlank)?.let { preUrl ->
            val resolved = checkAndBuildLocalStream(
                targetUrl = preUrl,
                displayTitle = title,
                displayArtist = artist,
                fallbackMime = track.playbackMimeType,
                knownDurationMs = track.durationMs ?: findKnownDuration(track),
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
                    ?: (if (!deepScan) null else dao.getAllList().firstOrNull { entity ->
                        val eTitle = entity.title.trim()
                        val eArtist = entity.artist.trim()
                        eTitle.equals(title, ignoreCase = true) && eArtist.equals(artist, ignoreCase = true) ||
                            cleanTrackTitle(eTitle).equals(cleanTitle, ignoreCase = true) &&
                            (cleanTrackArtist(eArtist).equals(cleanArtist, ignoreCase = true) ||
                             eArtist.contains(cleanArtist, ignoreCase = true) ||
                             cleanArtist.contains(eArtist, ignoreCase = true))
                    })
            }.getOrNull()
        }

        // 3. If entity found in DB, check its mediaStoreUri and filePath
        if (downloaded != null) {
            val candidates = mutableListOf<String>()
            downloaded.mediaStoreUri?.takeIf(String::isNotBlank)?.let { candidates.add(it) }
            downloaded.filePath.takeIf(String::isNotBlank)?.let { if (!candidates.contains(it)) candidates.add(it) }

            for (candidate in candidates) {
                val resolved = checkAndBuildLocalStream(
                    targetUrl = candidate,
                    displayTitle = downloaded.title,
                    displayArtist = downloaded.artist,
                    badge = downloaded.formatBadge,
                    isLosslessTrack = downloaded.isLossless,
                    knownBitrate = downloaded.bitrateKbps,
                    knownDurationMs = downloaded.durationMs.takeIf { it > 0L },
                )
                if (resolved != null) {
                    if (candidate != downloaded.filePath && candidate.startsWith("/")) {
                        runCatching { dao?.insert(downloaded.copy(filePath = candidate)) }
                    }
                    return resolved
                }
            }
            if (deepScan) {
                val file = findDownloadFileByName(downloadDirs, File(downloaded.filePath).name)
                val moved = file?.absolutePath?.takeUnless(candidates::contains)?.let { path ->
                    checkAndBuildLocalStream(
                        targetUrl = path,
                        displayTitle = downloaded.title,
                        displayArtist = downloaded.artist,
                        badge = downloaded.formatBadge,
                        isLosslessTrack = downloaded.isLossless,
                        knownBitrate = downloaded.bitrateKbps,
                        knownDurationMs = downloaded.durationMs.takeIf { it > 0L },
                    )
                }
                if (moved != null && file != null) {
                    runCatching { dao?.insert(downloaded.copy(filePath = file.absolutePath)) }
                    return moved
                }
            }
        }

        // Startup skips the directory walk. A Music/ tree of thousands of
        // files was blocking playback even when the YouTube URL was cached.
        if (!deepScan) return null

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
        val scanContext = currentCoroutineContext()
        for (dir in searchDirs) {
            dir.walkTopDown().maxDepth(6).forEach { f ->
                scanContext.ensureActive()
                if (f.isFile && f.length() > 0) indexedByName.getOrPut(f.name) { f }
            }
        }
        for (base in candidateBases) {
            for (ext in candidateExtensions) {
                val candidateFile = indexedByName["$base.$ext"] ?: continue
                val resolved = checkAndBuildLocalStream(
                    targetUrl = candidateFile.absolutePath,
                    displayTitle = title,
                    displayArtist = artist,
                    knownDurationMs = track.durationMs ?: findKnownDuration(track),
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
    private suspend fun findDownloadFileByName(dirs: List<File>, fileName: String): File? {
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

    /**
     * How long the lossless / provider-module branch may run before playback
     * falls back to YouTube.
     */
    internal enum class LosslessBudget {
        /**
         * Interactive playback: give up quickly so the user hears *something*.
         * The module chain is several round trips (up to four searches plus a
         * stream fetch per quality tier), so this regularly expires on slow
         * links â€” which is the right trade when a track is already playing.
         */
        Interactive,
        Disabled,
    }

    private suspend fun resolveTrackAudioStream(
        track: PlayableTrack,
        videoId: String?,
        allowLossless: Boolean = true,
        excludedLosslessUrls: Set<String> = emptySet(),
        allowLocalDownloads: Boolean = true,
        losslessBudget: LosslessBudget = LosslessBudget.Interactive,
        claimPlayback: Boolean = true,
    ): ResolvedStream = withContext(Dispatchers.IO) {
        // Include the active network profile in the in-flight resolver key.
        // Without it, starting the same track again after changing Wi-Fi or
        // cellular quality could join an older request and reuse its rendition.
        val miscSnapshot = runCatching { settingsPreferences.settings.first() }.getOrDefault(MiscSettings())
        val requestedQuality = selectedStreamingQuality(miscSnapshot)
        val identityKey = track.playbackIdentity()?.cacheKey
            ?: track.internalTrackId?.takeIf { it.isNotBlank() }
            ?: videoId?.takeIf { it.isNotBlank() }
            ?: track.mediaIdKey()
        val key = listOf(
            identityKey,
            videoId,
            allowLossless,
            excludedLosslessUrls,
            allowLocalDownloads,
            losslessBudget,
            claimPlayback,
            requestedQuality,
        )
        val now = SystemClock.elapsedRealtime()
        resolutionRequests.entries.removeIf { now - it.value.first > 60_000L }
        if (resolutionRequests.size >= 64) {
            resolutionRequests.entries.removeIf { it.value.second.isCompleted }
        }
        val request = resolutionRequests.computeIfAbsent(key) {
            now to applicationScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                resolveRemoteTrackAudioStream(
                    track = track,
                    videoId = videoId,
                    allowLocalDownloads = allowLocalDownloads,
                    allowLossless = allowLossless,
                    excludedLosslessUrls = excludedLosslessUrls,
                    losslessBudget = losslessBudget,
                    claimPlayback = claimPlayback,
                    miscSnapshot = miscSnapshot,
                    requestedQuality = requestedQuality,
                )
            }
        }
        try {
            request.second.await().also {
                if (it.isExpired()) throw java.io.IOException("Prepared stream expired")
                val liveSettings = latestPlaybackSettings
                if (!isStreamAllowedForQuality(it, selectedStreamingQuality(liveSettings), liveSettings)) {
                    throw java.io.IOException("Streaming quality changed during resolution")
                }
            }
        } catch (error: Exception) {
            if (error !is CancellationException || request.second.isCancelled) {
                resolutionRequests.remove(key, request)
            }
            throw error
        }
    }

    /**
     * Opens a progressive provider URL the way playback will before it may win
     * a cold start, so an HTTP 5xx or an HTML error page loses to the fallback
     * instead of failing inside ExoPlayer. The ranged read is drained so the
     * pooled connection can be reused by the player's own open.
     */
    private suspend fun isProviderReadyForStartup(stream: ResolvedStream): Boolean {
        if (stream.segmentedDrm != null || !stream.mimeType.startsWith("audio/")) return false
        if (stream.isExpired()) return false
        if (mediaCache.isCached(stream.cacheKey, 0L, STARTUP_PROBE_BYTES)) return true
        val uri = Uri.parse(stream.url)
        if (uri.scheme != "http" && uri.scheme != "https") return false
        val request = runCatching {
            okhttp3.Request.Builder().url(stream.url)
                .apply { mediaRequestHeaders(stream).forEach { (name, value) -> header(name, value) } }
                .header("Range", "bytes=0-${STARTUP_PROBE_BYTES - 1}")
                .build()
        }.getOrNull() ?: return false
        val startedAt = SystemClock.elapsedRealtime()
        return withTimeoutOrNull(STARTUP_PROVIDER_PROBE_MS) {
            suspendCancellableCoroutine { continuation ->
                val call = mediaHttpClient.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        android.util.Log.i("MusicPlayer", "[STARTUP SOURCE] provider probe failed: ${e.javaClass.simpleName}")
                        continuation.resume(false)
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        val readable = response.use {
                            val type = it.body?.contentType()
                            it.isSuccessful && type?.type != "text" && type?.subtype != "json" &&
                                runCatching {
                                    val source = it.body?.source() ?: return@runCatching false
                                    source.request(1L).also { ok ->
                                        if (ok) source.request(STARTUP_PROBE_BYTES)
                                    }
                                }.getOrDefault(false)
                        }
                        android.util.Log.i(
                            "MusicPlayer",
                            "[STARTUP SOURCE] provider probe http=${response.code} readable=$readable " +
                                "ms=${SystemClock.elapsedRealtime() - startedAt}",
                        )
                        continuation.resume(readable)
                    }
                })
            }
        } ?: false.also {
            android.util.Log.i("MusicPlayer", "[STARTUP SOURCE] provider probe timed out after ${STARTUP_PROVIDER_PROBE_MS}ms")
        }
    }

    private suspend fun resolveRemoteTrackAudioStream(
        track: PlayableTrack,
        videoId: String?,
        allowLocalDownloads: Boolean,
        allowLossless: Boolean,
        excludedLosslessUrls: Set<String>,
        losslessBudget: LosslessBudget,
        claimPlayback: Boolean,
        miscSnapshot: MiscSettings,
        requestedQuality: Int,
    ): ResolvedStream = coroutineScope {
        if (allowLocalDownloads) {
            resolveLocalDownloadedAudioStream(track, deepScan = false)?.let { return@coroutineScope it }
        }
        val wantProvider = allowLossless && miscSnapshot.preferLosslessStreaming &&
            requestedQuality != LosslessMusicApi.QUALITY_YOUTUBE &&
            losslessBudget != LosslessBudget.Disabled
        PlaybackStartupLog.cacheLookupStart()
        val prepared = findPreparedStreamFor(track, videoId, bypassLossless = !wantProvider, requestedQuality)
            ?.takeIf { it.url !in excludedLosslessUrls }
            ?.takeIf {
                val isLocal = it.cacheKey.startsWith("local:") || it.cacheKey.startsWith("offline:")
                allowLocalDownloads || !isLocal
            }
        PlaybackStartupLog.cacheLookupEnd(hit = prepared != null)
        if (prepared != null && prepared.youtubeCandidate == null &&
            !prepared.cacheKey.startsWith("lossless:")
        ) {
            if (claimPlayback) PlaybackStartupLog.sourceSelected("prepared", "key=${prepared.cacheKey.substringBefore(':')}")
            return@coroutineScope prepared
        }

        val generation = playRequestGeneration.get()
        // The selected track can keep resolving its preferred tier after first audio.
        // A loader resolving a future queue item must never replace the active track's upgrade.
        val canPromote = claimPlayback && _state.value.current?.mediaIdKey() == track.mediaIdKey()
        val preparedProvider = prepared?.takeIf { it.youtubeCandidate == null }
        val provider = if (wantProvider && (preparedProvider != null || !losslessMusicApi.isCoolingDown)) {
            val resolveScope = if (canPromote) applicationScope else this
            resolveScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                preparedProvider ?: try {
                    resolveLosslessTrackAudioStream(
                        track = track,
                        misc = miscSnapshot,
                        excludedLosslessUrls = excludedLosslessUrls,
                        expectedDurationSeconds = track.durationMs?.takeIf { it > 0 }?.div(1_000)?.toInt(),
                        requestedQuality = requestedQuality,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            }.also {
                if (canPromote && generation == playRequestGeneration.get()) activeUpgradeDeferred = it
                it.start()
            }
        } else null

        val providerDeadline = SystemClock.elapsedRealtime() + STARTUP_PROVIDER_RESOLVE_MS
        suspend fun awaitProvider(): ResolvedStream? = try {
            val remaining = (providerDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
            withTimeoutOrNull(remaining) { provider?.await() }
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        }

        // The selected track's fallback outlives a provider win for a short
        // while: if the provider URL dies on open, the retry finds YouTube in
        // the resolver caches instead of starting a cold lookup.
        val fallbackScope = if (canPromote) applicationScope else this
        val fallback = fallbackScope.async(Dispatchers.IO) {
            try {
                val cached = prepared?.takeIf { it.youtubeCandidate != null }
                val stream = if (cached != null && vpnYoutubeLoginRequiredExceptionOrNull() == null) {
                    cached
                } else {
                    kotlinx.coroutines.withTimeout(STARTUP_FALLBACK_KEEPALIVE_MS) {
                        resolveYoutubeTrackAudioStream(track, videoId, claimPlayback, requestedQuality)
                    }
                }
                Result.success(stream)
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                Result.failure(java.io.IOException("Timed out resolving fallback audio", error))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
        if (canPromote) {
            startupFallbackJob?.cancel()
            startupFallbackJob = fallback
        }
        suspend fun localDownload(): ResolvedStream? = if (!allowLocalDownloads) null else try {
            resolveLocalDownloadedAudioStream(track, deepScan = true)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        // DASH/DRM cannot be byte-probed; it needs a full player prepare.
        fun isManifest(stream: ResolvedStream) = !stream.mimeType.startsWith("audio/") || stream.segmentedDrm != null
        suspend fun playableProvider(acceptManifest: Boolean): ResolvedStream? = awaitProvider()?.takeIf {
            isStreamAllowedForQuality(it, requestedQuality, miscSnapshot) &&
                (if (isManifest(it)) acceptManifest else isProviderReadyForStartup(it))
        }
        // Whichever source is playable first starts. Waiting on the provider
        // first cost 5 s per song whenever it answered slowly or with an error;
        // the upgrade job promotes to the chosen tier once that is playable.
        try {
            (racePlaybackCandidates(
                first = { fallback.await().getOrNull() },
                second = {
                    racePlaybackCandidates(
                        first = { localDownload() },
                        second = { playableProvider(acceptManifest = false) },
                    )
                },
            ) ?: playableProvider(acceptManifest = true)
                ?: fallback.await().getOrThrow()).also { winner ->
                if (claimPlayback) {
                    val name = when {
                        winner.cacheKey.startsWith("local:") || winner.cacheKey.startsWith("offline:") -> "local"
                        winner.youtubeCandidate != null -> "youtube"
                        else -> "provider"
                    }
                    PlaybackStartupLog.sourceSelected(
                        name,
                        "tier=${streamQualityTier(winner)} codec=${winner.audioCodec} kbps=${winner.bitrateKbps} requested=$requestedQuality",
                    )
                }
            }
        } finally {
            val abandoned = !currentCoroutineContext().isActive
            if (!canPromote || abandoned) {
                fallback.cancel()
                provider?.cancel()
            }
        }
    }

    private fun streamingProfile(misc: MiscSettings, route: NetworkMonitor.ActiveRoute): Int =
        effectiveLosslessQuality(
            misc,
            StreamingQualityPolicy.profileFor(misc.wifiStreamingQuality, misc.cellularStreamingQuality, route.isCellular),
        )

    /** Adaptive streaming is the cellular "Auto" choice; every other choice is played as picked. */
    private fun isAdaptive(misc: MiscSettings, route: NetworkMonitor.ActiveRoute): Boolean =
        StreamingQualityPolicy.isAdaptive(misc.cellularStreamingQuality, route.isCellular)

    internal fun selectedStreamingQuality(misc: MiscSettings): Int {
        val route = networkMonitor.activeRoute.value
        val profile = streamingProfile(misc, route)
        if (!isAdaptive(misc, route)) return profile
        return adaptiveTargetFor(route, profile)
            ?: StreamingQualityPolicy.forBandwidth(profile, adaptiveBandwidthKbps(route), currentQuality = null)
    }

    private fun adaptiveTargetFor(route: NetworkMonitor.ActiveRoute, profile: Int): Int? =
        adaptiveTargetQuality?.takeIf { it.network == route.network && it.profile == profile }?.quality

    @MainThread
    private fun resetAdaptiveQuality() {
        adaptiveTargetQuality = null
        adaptiveController.reset()
    }

    @MainThread
    private fun considerAdaptiveNetworkQuality(route: NetworkMonitor.ActiveRoute, routeChanged: Boolean) {
        if (routeChanged) enforceProfileOnUpcoming()
        val network = route.network ?: return
        val settings = latestPlaybackSettings
        if (isAdaptive(settings, route)) {
            val profile = streamingProfile(settings, route)
            // The link estimate only picks a route's starting tier. Lowering the
            // target on every settled estimate pulled a full buffer down a tier
            // with no cooldown; after the start the buffer controller moves it.
            if (adaptiveTargetFor(route, profile) == null) {
                val target = StreamingQualityPolicy.forBandwidth(profile, adaptiveBandwidthKbps(route), currentQuality = null)
                adaptiveTargetQuality = AdaptiveQualityTarget(network, profile, target)
            }
        }
        if (playerDelegate.isInitialized()) {
            val live = player
            maybeAdaptQualityForBuffer(live, live.currentPosition, live.bufferedPosition)
        }
    }

    @MainThread
    private fun maybeAdaptQualityForBuffer(
        live: ExoPlayer,
        positionMs: Long,
        bufferedPositionMs: Long,
    ) {
        if (!live.playWhenReady || isCasting || bitPerfectEnabled || exclusiveUsbOutput.isActive()) return
        val route = networkMonitor.activeRoute.value
        val network = route.network ?: return
        val item = live.currentMediaItem ?: return
        val track = item.toPlayableTrack()
        if (track.mediaType != MediaType.SONG || track.isLocalTrack()) return
        val currentStream = item.localConfiguration?.customCacheKey?.let(preparedStreams::get)
            ?: preparedStreams.values.firstOrNull { it.url == item.localConfiguration?.uri?.toString() }
            ?: return
        if (currentStream.cacheKey.startsWith("local:") || currentStream.cacheKey.startsWith("offline:")) return

        val settings = latestPlaybackSettings
        val profile = streamingProfile(settings, route)
        val requestedQuality = selectedStreamingQuality(settings)
        val now = SystemClock.elapsedRealtime()
        fun requestQuality(quality: Int) {
            val scheduled = scheduleQualityUpgrade(
                track = track,
                expectedMediaId = item.mediaId,
                generation = playRequestGeneration.get(),
                currentStream = currentStream,
                requestedQualityOverride = quality,
            )
            if (scheduled) {
                adaptiveController.onSwitch(now)
            } else if (now - lastQualityDeferLogMs >= 10_000L) {
                lastQualityDeferLogMs = now
                android.util.Log.i(
                    "MusicPlayer",
                    "[STREAM UPGRADE] switch to $quality deferred: swapLocked=${isStreamSwapLocked(allowLateSwap = true)} " +
                        "transition=${isTransitionRunning()} hotSwap=$hotSwapActive",
                )
            }
        }

        // Over the active ceiling (cellular handoff or a tighter profile):
        // move down now, adaptive or not, and stop reading ahead on it.
        if (!isStreamAllowedForQuality(currentStream, requestedQuality, settings)) {
            currentTrackCacheJob?.cancel()
            if (activeUpgradeTargetQuality != requestedQuality || activeUpgradeJob?.isActive != true) {
                enforceProfileOnUpcoming()
                requestQuality(requestedQuality)
            }
            return
        }
        val currentTier = streamQualityTier(currentStream)
        // One promotion per track and target: a track with no better source
        // must not re-run the provider search on every tick or cooldown.
        fun attempted(quality: Int) = activeUpgradeMediaId == item.mediaId && activeUpgradeTargetQuality == quality
        // A lossy stream below the target (started on the fallback, or the
        // setting or route was raised) is promoted mid-song. Adaptive off, the
        // target is the profile. A lossless stream is already the provider's
        // best for this track, so it is left alone.
        if (!attempted(requestedQuality) && !currentStream.isLossless &&
            track.mediaIdKey() !in losslessBypassMediaIds &&
            !isSpatialAudioCodec(currentStream.audioCodec) &&
            StreamingQualityPolicy.rank(currentTier) < StreamingQualityPolicy.rank(requestedQuality)
        ) {
            requestQuality(requestedQuality)
            return
        }
        if (!isAdaptive(settings, route)) return

        val decision = adaptiveController.sample(
            AdaptiveQualityController.Sample(
                nowMs = now,
                positionMs = positionMs,
                bufferedAheadMs = (bufferedPositionMs - positionMs).coerceAtLeast(0L),
                remainingMs = if (live.duration > 0L) live.duration - positionMs else Long.MAX_VALUE,
                isRebuffering = live.playbackState == Player.STATE_BUFFERING,
                isPlaying = live.isPlaying,
                isLoading = live.isLoading,
                currentTier = currentTier,
                profile = profile,
                bandwidthKbps = adaptiveBandwidthKbps(route),
            ),
        )
        if (decision !is AdaptiveQualityController.Decision.Switch) return
        if (!decision.downshift && (activeUpgradeJob?.isActive == true || attempted(decision.quality))) return
        adaptiveTargetQuality = AdaptiveQualityTarget(network, profile, decision.quality)
        if (decision.downshift) enforceProfileOnUpcoming()
        requestQuality(decision.quality)
    }

    private fun effectiveLosslessQuality(misc: MiscSettings, requestedQuality: Int? = null): Int =
        StreamingQualityPolicy.effective(
            selected = requestedQuality ?: selectedStreamingQuality(misc),
            atmosSupported = isSpatialAudioSupportedOnDevice(),
        )

    private suspend fun resolveLosslessTrackAudioStream(
        track: PlayableTrack,
        misc: MiscSettings,
        excludedLosslessUrls: Set<String> = emptySet(),
        expectedDurationSeconds: Int? = null,
        requestedQuality: Int? = null,
    ): ResolvedStream? {
        val atmosSupported = isSpatialAudioSupportedOnDevice()
        val effectiveQuality = effectiveLosslessQuality(misc, requestedQuality)
        val stream = losslessMusicApi.resolveStream(
            title = track.title,
            artist = track.artist,
            expectedDurationSeconds = expectedDurationSeconds,
            expectedAlbum = track.album,
            preferredQuality = effectiveQuality,
            excludedUrls = excludedLosslessUrls,
        ) ?: if (effectiveQuality == LosslessMusicApi.QUALITY_DOLBY_ATMOS) {
            android.util.Log.i(
                "MusicPlayer",
                "[LOSSLESS] Atmos resolve returned null for '${track.title}'; trying stereo hi-res fallback",
            )
            losslessMusicApi.resolveStream(
                title = track.title,
                artist = track.artist,
                expectedDurationSeconds = expectedDurationSeconds,
                expectedAlbum = track.album,
                preferredQuality = LosslessMusicApi.QUALITY_MAX_HI_RES,
                excludedUrls = excludedLosslessUrls,
            )
        } else {
            null
        } ?: return null

        if (stream.url.isBlank() || stream.url in excludedLosslessUrls) return null
        // 320 kbps and Data Saver are caps, not minimums. Some addons answer a
        // lower-tier request with a higher rendition; reject it so Wi-Fi and
        // cellular profiles never silently exceed the selected quality limit.
        if (effectiveQuality in setOf(LosslessMusicApi.QUALITY_MP3_320, LosslessMusicApi.QUALITY_DATA_SAVER) &&
            stream.formatId != effectiveQuality
        ) {
            android.util.Log.i(
                "MusicPlayer",
                "[LOSSLESS] requested tier $effectiveQuality but addon returned ${stream.formatId}; keeping playback within the selected quality cap",
            )
            return null
        }

        val manifestCodec = LosslessMusicApi.manifestCodecOf(stream.url)?.lowercase()
        val manifestIsLossy = manifestCodec?.let {
            it.contains("opus") || it.contains("mp4a") || it.contains("aac") || it.contains("mp3")
        } == true
        val manifestIsSpatial = manifestCodec?.let {
            it.contains("ec-3") || it.contains("eac3") || it.contains("ac-3") ||
                it.contains("mha1") || it.contains("mhm1")
        } == true
        // Immersive mixes are premium but never bit-exact: exclude them
        // from the lossless flag even though their carrier is not lossy.
        val isLossless = !manifestIsLossy && !manifestIsSpatial &&
            stream.formatId != LosslessMusicApi.QUALITY_DOLBY_ATMOS &&
            stream.formatId != LosslessMusicApi.QUALITY_MP3_320 &&
            stream.formatId != LosslessMusicApi.QUALITY_DATA_SAVER &&
            !stream.mimeType.contains("mp3", ignoreCase = true) &&
            !stream.mimeType.contains("aac", ignoreCase = true)

        val manifestCodecBadge = when {
            manifestCodec?.contains("ec-3") == true || manifestCodec?.contains("eac3") == true ||
                manifestCodec?.contains("ac-3") == true -> "DOLBY ATMOS"
            manifestCodec?.contains("mha1") == true || manifestCodec?.contains("mhm1") == true -> "SPATIAL AUDIO"
            manifestCodec?.contains("flac") == true ->
                if (stream.bitDepth > 16 || stream.samplingRate > 48.0) "HI-RES FLAC" else "FLAC"
            manifestCodec?.contains("opus") == true -> "OPUS"
            manifestCodec?.contains("mp4a") == true || manifestCodec?.contains("aac") == true -> "AAC"
            manifestCodec?.contains("mp3") == true -> "MP3"
            else -> null
        }
        val effectiveRate = if (stream.samplingRate > 1000.0) stream.samplingRate / 1000.0 else stream.samplingRate
        // Unknown depth stays unknown at â‰¤48kHz (renders rate-only, honest).
        // Only a hi-res rate implies 24-bit; never invent 16.
        val streamBitDepth = if (isLossless) (stream.bitDepth.takeIf { it > 0 }
            ?: if (effectiveRate > 48.0) 24 else null) else null
        val resolvedBitDepth: Int? = if (isLossless) (resolveDepthForDisplay(streamBitDepth, effectiveRate)
            ?: parseQualityFromCodec(stream.audioCodecOverride)?.substringBefore('/')?.toIntOrNull()
            ?: streamBitDepth) else null
        val badge = when {
            stream.audioCodecOverride != null -> stream.audioCodecOverride
            // Spatial badges only from manifest evidence: the request's
            // preferred format must never dress a stereo fallback as Atmos.
            manifestCodecBadge == "DOLBY ATMOS" || manifestCodecBadge == "SPATIAL AUDIO" ->
                manifestCodecBadge
            stream.formatId == LosslessMusicApi.QUALITY_MP3_320 -> "320 kbps"
            stream.formatId == LosslessMusicApi.QUALITY_DATA_SAVER -> "96 kbps"
            isLossless && (resolvedBitDepth ?: 0) > 0 && effectiveRate > 0.0 ->
                "$resolvedBitDepth/${formatSampleRateKHz(effectiveRate)}kHz"
            manifestCodecBadge != null -> manifestCodecBadge
            isLossless && ((resolvedBitDepth ?: 0) > 16 || effectiveRate > 48.0) -> "HI-RES FLAC"
            isLossless -> if ((resolvedBitDepth ?: 0) > 0 && effectiveRate > 0.0) "$resolvedBitDepth/${formatSampleRateKHz(effectiveRate)}kHz" else "LOSSLESS"
            else -> "AUDIO"
        }
        // Device-capability veto: a spatial manifest that slips through on a
        // device that cannot render Atmos (no spatializer, no JOC decoder)
        // is unplayable by construction. Re-request from the addon with a
        // stereo quality tier so the waterfall tries hi-res â†’ CD â†’ lossless
        // before we give up and fall to YouTube.
        val spatialResult = manifestCodecBadge == "DOLBY ATMOS" ||
            manifestCodecBadge == "SPATIAL AUDIO" ||
            stream.audioCodecOverride == "DOLBY ATMOS"
        if (spatialResult && !atmosSupported) {
            android.util.Log.w(
                "MusicPlayer",
                "[LOSSLESS] veto: spatial manifest for '${track.title}' on incapable device; re-requesting stereo tier",
            )
            // Re-request with hi-res stereo quality so the addon waterfall
            // tries hi_res â†’ lossless â†’ high instead of skipping to YouTube.
            val stereoQuality = if (effectiveQuality == LosslessMusicApi.QUALITY_DOLBY_ATMOS) {
                LosslessMusicApi.QUALITY_MAX_HI_RES
            } else {
                effectiveQuality
            }
            val stereoStream = losslessMusicApi.resolveStream(
                title = track.title,
                artist = track.artist,
                expectedDurationSeconds = expectedDurationSeconds,
                expectedAlbum = track.album,
                preferredQuality = stereoQuality,
                excludedUrls = excludedLosslessUrls,
            )
            if (stereoStream == null) {
                android.util.Log.w("MusicPlayer", "[LOSSLESS] stereo fallback also returned null for '${track.title}'")
                return null
            }
            // Verify the stereo fallback isn't spatial too
            val stereoManifestCodec = LosslessMusicApi.manifestCodecOf(stereoStream.url)?.lowercase()
            val stereoIsSpatial = stereoManifestCodec?.let {
                it.contains("ec-3") || it.contains("eac3") || it.contains("ac-3") ||
                    it.contains("mha1") || it.contains("mhm1")
            } == true || stereoStream.audioCodecOverride == "DOLBY ATMOS"
            if (stereoIsSpatial) {
                android.util.Log.w("MusicPlayer", "[LOSSLESS] stereo fallback still spatial for '${track.title}'; giving up")
                return null
            }
            // Use the stereo stream instead â€” re-derive badge and metadata
            // from the new stream by recursing with the stereo quality.
            // (We can't just drop through because badge/playUrl were computed
            // from the original spatial stream.)
            android.util.Log.i("MusicPlayer", "[LOSSLESS] stereo fallback succeeded for '${track.title}'")
            return resolveLosslessTrackAudioStream(
                track = track,
                misc = misc,
                excludedLosslessUrls = excludedLosslessUrls,
                expectedDurationSeconds = expectedDurationSeconds,
                requestedQuality = stereoQuality,
            )
        }

        val playUrl: String
        val mimeType: String
        val isDashDataUrl = stream.url.startsWith("data:application/dash+xml")
        val isRawXml = stream.url.trimStart().startsWith("<")
        if (isDashDataUrl || isRawXml) {
            val rawXml = when {
                stream.url.startsWith("data:application/dash+xml;base64,") -> {
                    String(
                        android.util.Base64.decode(stream.url.substringAfter("base64,"), android.util.Base64.DEFAULT),
                        Charsets.UTF_8,
                    )
                }
                stream.url.startsWith("data:application/dash+xml,") -> {
                    java.net.URLDecoder.decode(stream.url.substringAfter(","), "UTF-8")
                }
                isRawXml -> stream.url.trimStart()
                else -> {
                    val payload = stream.url.substringAfter(",")
                    runCatching {
                        String(android.util.Base64.decode(payload, android.util.Base64.DEFAULT), Charsets.UTF_8)
                    }.getOrElse { payload }
                }
            }
            val xml = SegmentedMpdBuilder.sanitizeMpdXml(rawXml)
            // Content-addressed manifest: a re-resolve (expiry refresh, error
            // retry) must never overwrite the file a playing item is still
            // opening/reading. A torn or swapped manifest corrupts the DASH
            // timeline â€” frozen/creeping position that later jumps while audio
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
            val lower = playUrl.substringBefore('?').lowercase()
            val isExplicitDash = lower.endsWith(".mpd") || lower.contains(".mpd") || lower.contains("/mpd")
            mimeType = when {
                stream.mimeType.isNotBlank() && stream.mimeType != MimeTypes.APPLICATION_MPD -> stream.mimeType
                // The resolver already identified a manifest: never hand it to
                // the progressive source ("Invalid content type: application/dash+xml").
                isExplicitDash || stream.mimeType == MimeTypes.APPLICATION_MPD -> MimeTypes.APPLICATION_MPD
                lower.endsWith(".m3u8") || lower.contains(".m3u8") -> "application/x-mpegURL"
                lower.endsWith(".mp3") || stream.formatId == LosslessMusicApi.QUALITY_MP3_320 -> "audio/mpeg"
                lower.endsWith(".aac") -> "audio/aac"
                lower.endsWith(".mp4") || lower.endsWith(".m4a") ||
                    stream.formatId == LosslessMusicApi.QUALITY_DATA_SAVER ||
                    stream.formatId == LosslessMusicApi.QUALITY_DOLBY_ATMOS -> "audio/mp4"
                lower.endsWith(".flac") ||
                    stream.formatId == LosslessMusicApi.QUALITY_CD_LOSSLESS ||
                    stream.formatId == LosslessMusicApi.QUALITY_HI_RES_96 ||
                    stream.formatId == LosslessMusicApi.QUALITY_MAX_HI_RES -> "audio/flac"
                lower.endsWith(".ogg") || lower.endsWith(".opus") -> "audio/ogg"
                else -> stream.mimeType.ifBlank { "audio/flac" }
            }
        }

        return ResolvedStream(
            url = playUrl,
            mimeType = mimeType,
            bitrateKbps = stream.bitrateKbps,
            audioCodec = badge,
            cacheKey = "lossless:${track.mediaIdKey()}:${stream.formatId}:${stream.trackId}",
            qualityTier = stream.formatId,
            isLossless = isLossless,
            bitDepth = resolvedBitDepth?.takeIf { it > 0 } ?: streamBitDepth,
            samplingRateKHz = effectiveRate.takeIf { it > 0.0 },
            durationMs = stream.durationSeconds.takeIf { it > 0 }?.times(1_000L)
                ?: track.durationMs
                ?: findKnownDuration(track),
            expiresAtEpochMs = addonUrlExpiryMs(stream.url)
                ?: addonUrlExpiryMs(playUrl)
                ?: (System.currentTimeMillis() + 2 * 60 * 60 * 1000L),
        )
    }

    internal suspend fun resolveYoutubeTrackAudioStream(
        track: PlayableTrack,
        videoId: String?,
        claimPlayback: Boolean = true,
        requestedQuality: Int = selectedStreamingQuality(latestPlaybackSettings),
    ): ResolvedStream {
        vpnYoutubeLoginRequiredExceptionOrNull()?.let { throw it }
        val directId = videoId?.takeIf { it.isNotBlank() } ?: track.videoId?.takeIf { it.isNotBlank() }
        if (directId != null) {
            val identity = TrackIdentity(
                trackId = track.internalTrackId?.takeIf { it.isNotBlank() } ?: directId,
                youtubeVideoId = directId,
            )
            val entry = try {
                resolveYoutubeEntry(identity, track.durationMs, claimPlayback, requestedQuality)
            } catch (failure: Throwable) {
                if (failure is CancellationException || !isDeadVideoIdFailure(failure) || track.title.isBlank()) {
                    throw failure
                }
                return resolveRematchedYoutubeStream(track, directId, claimPlayback, requestedQuality)
            }
            return acceptResolvedIdentity(track, identity, entry, claimPlayback = claimPlayback)
        }
        if (track.title.isBlank()) throw java.io.IOException("No video ID or search query available for track")
        val matchId = findPlaybackVideoId(track, claimPlayback)
            ?: throw java.io.IOException("No playable match found")
        val identity = TrackIdentity(matchId, matchId)
        val entry = resolveYoutubeEntry(identity, track.durationMs, claimPlayback, requestedQuality)
        return acceptResolvedIdentity(track, identity, entry, claimPlayback = claimPlayback)
    }

    private suspend fun resolveYoutubeEntry(
        identity: TrackIdentity,
        durationMs: Long?,
        claimPlayback: Boolean,
        requestedQuality: Int,
    ): StreamCache {
        if (requestedQuality != LosslessMusicApi.QUALITY_DATA_SAVER) {
            val entry = if (claimPlayback) streamResolver.resolvePlayback(identity, durationMs)
                else streamResolver.resolveTrackStream(identity, durationMs)
            if (requestedQuality != LosslessMusicApi.QUALITY_MP3_320 ||
                entry.toYouTubeAudioStream().meetsBitrateLimit(StreamingQualityPolicy.MP3_320_MAX_BPS)
            ) return entry
        }
        // The ordinary YouTube cache is keyed by track, not bitrate. Keep
        // constrained candidates in the extractor's bitrate-aware cache.
        val maxBitrateBps = if (requestedQuality == LosslessMusicApi.QUALITY_DATA_SAVER) {
            StreamingQualityPolicy.DATA_SAVER_MAX_BPS
        } else {
            StreamingQualityPolicy.MP3_320_MAX_BPS
        }
        val token = if (claimPlayback) streamResolver.playback.claim(identity) else null
        if (claimPlayback) PlaybackStartupLog.extractStart()
        val candidate = try {
            innerTube.resolveAudioStream(identity.youtubeVideoId, maxBitrateBps)
        } finally {
            if (claimPlayback) PlaybackStartupLog.extractEnd()
        }
        if (token != null && !streamResolver.playback.isCurrent(token, identity)) {
            throw com.lastwave.app.playback.resolve.StaleResolveException(identity)
        }
        return candidate.toStreamCache(identity)
    }

    private suspend fun findPlaybackVideoId(track: PlayableTrack, logStartup: Boolean): String? {
        if (logStartup) PlaybackStartupLog.videoIdLookupStart()
        return try {
            innerTube.findBestMatch(
                title = track.title,
                artist = track.artist,
                prefetchStreams = false,
                expectedDurationMs = track.durationMs,
            ).videoId.takeIf { it.isNotBlank() }
        } finally {
            if (logStartup) PlaybackStartupLog.videoIdLookupEnd()
        }
    }

    /** Rematch an unplayable stored ID once so callers can persist its replacement. */
    private suspend fun resolveRematchedYoutubeStream(
        track: PlayableTrack,
        deadVideoId: String,
        claimPlayback: Boolean,
        requestedQuality: Int,
    ): ResolvedStream {
        val deadIdentity = TrackIdentity(
            trackId = track.internalTrackId?.takeIf { it.isNotBlank() } ?: deadVideoId,
            youtubeVideoId = deadVideoId,
        )
        val matchId = try {
            findPlaybackVideoId(track, claimPlayback)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            null
        }?.takeUnless { it == deadVideoId }
            ?: throw java.io.IOException("Unable to resolve a playable audio stream for $deadVideoId")
        val identity = TrackIdentity(matchId, matchId)
        if (claimPlayback) streamResolver.playback.replaceClaim(deadIdentity, identity)
        val entry = try {
            resolveYoutubeEntry(identity, track.durationMs, claimPlayback, requestedQuality)
        } catch (failure: Throwable) {
            if (claimPlayback && streamResolver.playback.requested == identity) {
                runCatching { streamResolver.playback.replaceClaim(identity, deadIdentity) }
            }
            throw failure
        }
        return acceptResolvedIdentity(
            track = track,
            identity = identity,
            entry = entry,
            requestedIdOverride = matchId,
            claimPlayback = claimPlayback,
        ).copy(rematchedFromVideoId = deadVideoId)
    }

    private suspend fun vpnYoutubeLoginRequiredExceptionOrNull(): VpnYoutubeLoginRequiredException? {
        if (!networkMonitor.isOnVpn()) return null
        if (ytMusicAuthManager.awaitLoadedConnection().isConnected) return null
        return VpnYoutubeLoginRequiredException(
            appContext.getString(R.string.playback_vpn_youtube_login_required),
        )
    }

    private fun acceptResolvedIdentity(
        track: PlayableTrack,
        identity: TrackIdentity,
        entry: StreamCache,
        requestedIdOverride: String? = null,
        claimPlayback: Boolean = true,
    ): ResolvedStream {
        if (!entry.matches(identity)) {
            streamResolver.cache.invalidate(identity)
            throw java.io.IOException(
                "Rejected mismatched stream requested=${identity.youtubeVideoId} resolved=${entry.youtubeVideoId}",
            )
        }
        if (claimPlayback) {
            val active = streamResolver.playback.requested
            if (active != null && active != identity) {
                throw com.lastwave.app.playback.resolve.StaleResolveException(identity)
            }
        }
        return entry.toPlayerStream().also { resolved ->
            val resolvedId = resolved.youtubeCandidate?.videoId
            val requestedId = requestedIdOverride
                ?: track.videoId?.takeIf { it.isNotBlank() }
                ?: identity.youtubeVideoId
            if (resolvedId != null && resolvedId != requestedId) {
                streamResolver.cache.invalidate(identity)
                preparedStreams.remove(resolved.cacheKey)
                innerTube.invalidateCache(resolvedId)
                throw java.io.IOException(
                    "Rejected mismatched stream requested=$requestedId resolved=$resolvedId",
                )
            }
        }
    }

    internal fun publishResolvedQuality(resolved: ResolvedStream, expectedMediaId: String? = null) {
        if (expectedMediaId != null) {
            val current = _state.value.current
            val currentMediaId = current?.mediaIdKey()
            val matches = currentMediaId == expectedMediaId ||
                current?.videoId == expectedMediaId ||
                (playerDelegate.isInitialized() && player.currentMediaItem?.mediaId == expectedMediaId)
            if (current != null && !matches) {
                android.util.Log.d(
                    "MusicPlayer",
                    "Quality Pill: ignoring publishResolvedQuality for non-current track (expected=$expectedMediaId, current=$currentMediaId)",
                )
                return
            }
        }
        android.util.Log.i(
            "MusicPlayer",
            "Quality Pill: publishResolvedQuality(codec=${resolved.audioCodec}, depth=${resolved.bitDepth}, rate=${resolved.samplingRateKHz}kHz, kbps=${resolved.bitrateKbps}, isLossless=${resolved.isLossless})",
        )
        val seedMs = resolved.durationMs ?: resolved.youtubeCandidate?.durationMs
            ?: _state.value.current?.durationMs ?: findKnownDuration(_state.value.current)
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
                    samplingRateKHz = if (isSpatialAudioCodec(resolved.audioCodec)) {
                        48.0
                    } else {
                        // The freshly resolved stream's explicit sample rate MUST take priority over
                        // any residual or standby decodedSampleRateHz from previous tracks/decoders.
                        resolved.samplingRateKHz
                            ?: (if (decodedSampleRateHz > 0) decodedSampleRateHz / 1000.0 else it.samplingRateKHz)
                    },
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
        rememberTrackDuration(_state.value.current, seedMs)
        updateBitPerfectState()
        if (isSpatialAudioCodec(resolved.audioCodec)) {
            onMain { applyDacRoutingFor(currentSourceRateHz()) }
        }
    }

    private fun isWorthSwapping(current: ResolvedStream, candidate: ResolvedStream): Boolean {
        // Dolby Atmos candidate beats a lossy non-Atmos stream (Dolby
        // selected => play Dolby), but NEVER a lossless / hi-res stream: a
        // spatial remix must not replace stereo lossless via background
        // upgrade. Dolby plays only when chosen up front.
        if (candidate.audioCodec == "DOLBY ATMOS") {
            return current.audioCodec != "DOLBY ATMOS" && !current.isLossless
        }
        // Lossless candidate beats lossy stream
        if (candidate.isLossless && !current.isLossless) {
            return true
        }
        // Hi-Res candidate beats CD lossless
        if (candidate.isLossless && current.isLossless) {
            val candDepth = candidate.bitDepth ?: 16
            val currDepth = current.bitDepth ?: 16
            val candRate = candidate.samplingRateKHz ?: 44.1
            val currRate = current.samplingRateKHz ?: 44.1
            if (candDepth > currDepth || candRate > currRate) return true
        }
        // Higher bitrate within lossy (e.g. 320k vs 160k)
        val candKbps = candidate.bitrateKbps ?: 0
        val currKbps = current.bitrateKbps ?: 0
        if (candKbps >= currKbps + 64) {
            return true
        }
        return false
    }

    private fun ResolvedStream.qualityInfo(): StreamQualityInfo {
        val youtube = youtubeCandidate
        return StreamQualityInfo(
            isLocal = cacheKey.startsWith("local:") || cacheKey.startsWith("offline:"),
            isYouTube = youtube != null,
            youtubeWithinDataSaver = youtube?.meetsBitrateLimit(StreamingQualityPolicy.DATA_SAVER_MAX_BPS) == true,
            youtubeWithin320 = youtube?.meetsBitrateLimit(StreamingQualityPolicy.MP3_320_MAX_BPS) == true,
            isSpatial = isSpatialAudioCodec(audioCodec),
            providerTier = qualityTier?.takeIf { youtube == null },
            isLossless = isLossless,
            bitDepth = bitDepth,
            samplingRateKHz = samplingRateKHz,
            bitrateKbps = bitrateKbps,
        )
    }

    private fun streamQualityTier(stream: ResolvedStream): Int = StreamingQualityPolicy.tierOf(stream.qualityInfo())

    internal fun isStreamAllowedForQuality(
        stream: ResolvedStream,
        requestedQuality: Int,
        misc: MiscSettings,
    ): Boolean = StreamingQualityPolicy.isAllowed(
        stream = stream.qualityInfo(),
        requested = requestedQuality,
        effective = effectiveLosslessQuality(misc, requestedQuality),
    )

    private fun shouldAdaptStreamQuality(
        current: ResolvedStream,
        candidate: ResolvedStream,
        requestedQuality: Int,
        misc: MiscSettings,
    ): Boolean {
        if (current.cacheKey == candidate.cacheKey || !isStreamAllowedForQuality(candidate, requestedQuality, misc)) return false
        if (!isStreamAllowedForQuality(current, requestedQuality, misc)) return true
        if (requestedQuality == LosslessMusicApi.QUALITY_DATA_SAVER) return false
        return StreamingQualityPolicy.rank(streamQualityTier(candidate)) > StreamingQualityPolicy.rank(streamQualityTier(current)) ||
            isWorthSwapping(current, candidate)
    }

    /**
     * Locked only while a transition is actually running (fade, AutoMix arm or
     * blend, another hot-swap) or near the end of the track. A standby that is
     * merely pre-buffering the next song is released by the swap instead:
     * it is armed for most of every track, so treating it as a lock blocked
     * every promotion and every profile downgrade.
     */
    @MainThread
    private fun isStreamSwapLocked(allowLateSwap: Boolean = false): Boolean {
        if (!playerDelegate.isInitialized() || hotSwapActive) return true
        val p = player
        val dur = p.duration
        val pos = p.currentPosition
        if (!allowLateSwap && dur > 0L && pos > dur - 30_000L) return true
        return isTransitionRunning()
    }

    /**
     * AutoMix reports ARMED from 25% of the track onward, which is a plan, not
     * a transition. Only a blend, or the approach to its splice point, counts.
     */
    @MainThread
    private fun isTransitionRunning(): Boolean {
        if (outgoingPlayer != null || _state.value.isCrossfading) return true
        val mix = autoMixCoordinator.get().telemetry.value
        if (mix.isBlendingActive) return true
        if (!mix.isArmedOrBlending || !playerDelegate.isInitialized()) return false
        val spliceAt = mix.spliceStartPositionMs
        return spliceAt > 0L && spliceAt - player.currentPosition < AUTOMIX_SWAP_GUARD_MS
    }

    /** Drops an idle pre-buffered standby; the progress tick re-arms it later. */
    @MainThread
    private fun releaseIdleStandby(reason: String): Boolean {
        if (!crossfadeStandbyArmed && !crossfadeStandbyPreparing) return true
        if (isTransitionRunning() || hotSwapActive) return false
        crossfadeStandbyArmed = false
        crossfadeStandbyPreparing = false
        currentStandby()?.let { standby ->
            standby.stop()
            standby.clearMediaItems()
        }
        standbyQueue = emptyList()
        standbyIndex = C.INDEX_UNSET
        android.util.Log.i("MusicPlayer", "[STREAM UPGRADE] released idle standby: $reason")
        return true
    }

    /**
     * After a profile change or handoff, the next item (and an armed standby
     * holding it) may carry a stream above the new ceiling. Put it back to a
     * placeholder so it resolves under the active profile.
     */
    @MainThread
    private fun enforceProfileOnUpcoming() {
        if (!playerDelegate.isInitialized() || isTransitionRunning() || hotSwapActive) return
        val live = player
        val index = live.nextMediaItemIndex
        if (index == C.INDEX_UNSET || index !in 0 until live.mediaItemCount) return
        val item = live.getMediaItemAt(index)
        val stream = item.localConfiguration?.customCacheKey?.let(preparedStreams::get) ?: return
        val misc = latestPlaybackSettings
        val quality = selectedStreamingQuality(misc)
        if (isStreamAllowedForQuality(stream, quality, misc)) return
        if (standbyIndex == index || crossfadeStandbyArmed || crossfadeStandbyPreparing) {
            if (!releaseIdleStandby("upcoming stream above profile $quality")) return
        }
        val track = item.toPlayableTrack()
        consumePendingQuality(item.mediaId)
        track.videoId?.let(::consumePendingQuality)
        replaceMediaItemPreservingShuffle(index, track.toMediaItem())
        android.util.Log.i(
            "MusicPlayer",
            "[STREAM UPGRADE] upcoming '${track.title}' tier=${streamQualityTier(stream)} exceeds profile $quality; re-resolving",
        )
        preloadNextTrack(index, track)
    }

    internal fun scheduleQualityUpgrade(
        track: PlayableTrack,
        expectedMediaId: String,
        generation: Long,
        currentStream: ResolvedStream,
        requestedQualityOverride: Int? = null,
    ): Boolean {
        val misc = latestPlaybackSettings
        val requestedQuality = requestedQualityOverride ?: selectedStreamingQuality(misc)
        val explicitlyChanging = requestedQualityOverride != null
        val aboveLimit = !isStreamAllowedForQuality(currentStream, requestedQuality, misc)
        val wantLossless = misc.preferLosslessStreaming && requestedQuality != LosslessMusicApi.QUALITY_YOUTUBE
        if (expectedMediaId == activeUpgradeMediaId && requestedQuality == activeUpgradeTargetQuality &&
            activeUpgradeJob?.isActive == true
        ) return false
        if (isStreamSwapLocked(allowLateSwap = aboveLimit)) return false
        // Data Saver is the floor: an allowed stream has nothing to promote to.
        if (!explicitlyChanging && requestedQuality == LosslessMusicApi.QUALITY_DATA_SAVER) return false
        if (!explicitlyChanging && (!wantLossless || currentStream.isLossless ||
                isSpatialAudioCodec(currentStream.audioCodec) || currentStream.cacheKey.startsWith("lossless:") ||
                currentStream.cacheKey.startsWith("local:") || currentStream.cacheKey.startsWith("offline:"))
        ) return false

        val inFlightLossless = activeUpgradeDeferred
        if (explicitlyChanging) inFlightLossless?.cancel()
        activeUpgradeDeferred = null

        activeUpgradeJob?.cancel()
        activeUpgradeMediaId = expectedMediaId
        activeUpgradeTargetQuality = requestedQuality
        activeUpgradeJob = applicationScope.launch(Dispatchers.IO) {
            android.util.Log.i(
                "MusicPlayer",
                "[STREAM UPGRADE] starting for '${track.title}' quality=$requestedQuality",
            )
            try {
                // A promotion can wait until the active audio starts. A
                // downgrade may be needed to recover a stalled track.
                if (!aboveLimit) {
                    val started = withTimeoutOrNull(8_000L) {
                        withContext(Dispatchers.Main.immediate) {
                            while (generation == playRequestGeneration.get() &&
                                player.currentMediaItem?.mediaId == expectedMediaId
                            ) {
                                if (player.isPlaying && player.currentPosition > 0L) return@withContext true
                                delay(100L)
                            }
                            false
                        }
                    }
                    if (started != true) return@launch
                }
                fun candidateAllowed(candidate: ResolvedStream): Boolean {
                    val liveSettings = latestPlaybackSettings
                    return !candidate.isExpired() &&
                        isStreamAllowedForQuality(candidate, requestedQuality, misc) &&
                        isStreamAllowedForQuality(candidate, selectedStreamingQuality(liveSettings), liveSettings)
                }
                suspend fun resolveYoutubeCandidate(): ResolvedStream? = try {
                    resolveYoutubeTrackAudioStream(
                        track, track.videoId, claimPlayback = false, requestedQuality = requestedQuality,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
                val expectedSec = (track.durationMs?.takeIf { it > 0 } ?: currentStream.durationMs)
                    ?.div(1_000)
                    ?.toInt()
                val searchDeadlineMs = SystemClock.elapsedRealtime() + LOSSLESS_BACKGROUND_BUDGET_MS
                fun searchRemainingMs() = searchDeadlineMs - SystemClock.elapsedRealtime()
                var seed = inFlightLossless?.takeUnless { explicitlyChanging }?.let { pending ->
                    withTimeoutOrNull(searchRemainingMs().coerceAtLeast(1L)) {
                        runCatching { pending.await() }.getOrNull()
                    }
                }?.takeIf {
                    it.cacheKey.startsWith("lossless:${track.mediaIdKey()}:") && candidateAllowed(it) &&
                        isWorthSwapping(currentStream, it)
                }
                val youtubeFirst = !wantLossless || aboveLimit
                // A duration miss must not end the search. The addon drops
                // candidates outside its own ceiling when expected duration
                // is set, and a stream that still fails the 35s check is
                // excluded so the next candidate can be resolved.
                var upgraded: ResolvedStream? = null
                val maxAttempts = 3
                for (attempt in 1..maxAttempts) {
                    currentCoroutineContext().ensureActive()
                    if (generation != playRequestGeneration.get()) return@launch
                    while (!youtubeFirst && losslessMusicApi.isCoolingDown && searchRemainingMs() > 0L) delay(500L)
                    if (searchRemainingMs() <= 0L) break

                    upgraded = withTimeoutOrNull(searchRemainingMs()) {
                        selectDurationCompatibleUpgrade<ResolvedStream>(
                            expectedSec = expectedSec,
                            // Use UPGRADE_DURATION_TOLERANCE_SEC (35s) tolerance to account for intro/outro silence
                            toleranceSec = UPGRADE_DURATION_TOLERANCE_SEC,
                            urlOf = { it.url },
                            durationSecOf = { it.durationMs?.div(1_000)?.toInt() },
                            onRejected = { rejected ->
                                val candidateSec = rejected.durationMs?.div(1_000)?.toInt()
                                android.util.Log.w(
                                    "MusicPlayer",
                                    "[STREAM UPGRADE] Severe duration mismatch for '${track.title}': " +
                                        "expected ${expectedSec}s vs candidate ${candidateSec}s",
                                )
                            },
                        ) { excluded ->
                            currentCoroutineContext().ensureActive()
                            if (generation != playRequestGeneration.get()) return@selectDurationCompatibleUpgrade null
                            val pending = seed?.takeIf { it.url !in excluded }
                            seed = null
                            val youtube = if (youtubeFirst && excluded.isEmpty()) {
                                withTimeoutOrNull(8_000L) { resolveYoutubeCandidate() }?.takeIf(::candidateAllowed)
                            } else null
                            val lossless = if (pending == null && youtube == null && wantLossless) runCatching {
                                resolveLosslessTrackAudioStream(
                                    track = track,
                                    misc = misc,
                                    excludedLosslessUrls = excluded,
                                    expectedDurationSeconds = expectedSec,
                                    requestedQuality = requestedQuality,
                                )
                            }.getOrNull() else null
                            // No lossless for this track: a raised setting must
                            // still lift it off a bitrate-capped stream.
                            val uncapped = if (pending == null && youtube == null && lossless == null &&
                                explicitlyChanging && !youtubeFirst && excluded.isEmpty()
                            ) {
                                withTimeoutOrNull(8_000L) { resolveYoutubeCandidate() }
                            } else null
                            (pending ?: youtube ?: lossless ?: uncapped)?.takeIf(::candidateAllowed)
                        }
                    }

                    if (upgraded != null || attempt == maxAttempts || searchRemainingMs() <= 3_000L) break
                    android.util.Log.d("MusicPlayer", "[STREAM UPGRADE] Attempt $attempt did not resolve upgrade for '${track.title}', retrying in 3s while active track plays...")
                    delay(3_000L)
                }

                if (upgraded == null) {
                    android.util.Log.i("MusicPlayer", "[STREAM UPGRADE] No upgrade stream found for '${track.title}' after retries")
                    return@launch
                }

                // Verify the upgraded stream is genuinely better than what is currently playing
                val candidateAccepted = candidateAllowed(upgraded) &&
                    if (explicitlyChanging) shouldAdaptStreamQuality(currentStream, upgraded, requestedQuality, misc)
                    else isWorthSwapping(currentStream, upgraded)
                if (!candidateAccepted) {
                    android.util.Log.i("MusicPlayer", "[STREAM UPGRADE] Stream for '${track.title}' not worth swapping (codec=${upgraded.audioCodec})")
                    return@launch
                }

                currentCoroutineContext().ensureActive()
                if (generation != playRequestGeneration.get()) return@launch

                val knownDur = upgraded.durationMs
                    ?: track.durationMs
                    ?: findKnownDuration(track)
                knownDur?.let { d ->
                    rememberKnownDuration(upgraded.cacheKey, d)
                    rememberKnownDuration(track.mediaIdKey(), d)
                    rememberTrackDuration(track, d)
                }
                registerPreparedStream(upgraded)
                logStreamEvent("stream-upgrade-cached", upgraded, retry = 0)

                // Check whether the active track is still playing and eligible for swap
                val currentPos = withContext(Dispatchers.Main.immediate) {
                    if (!playerDelegate.isInitialized()) return@withContext -1L
                    if (generation != playRequestGeneration.get()) return@withContext -1L
                    val currentIndex = player.currentMediaItemIndex
                    if (currentIndex !in 0 until player.mediaItemCount) return@withContext -1L
                    val currentItem = player.getMediaItemAt(currentIndex)
                    if (currentItem.mediaId != expectedMediaId && currentItem.mediaId != track.mediaIdKey()) return@withContext -1L

                    val pos = player.currentPosition
                    if (isStreamSwapLocked(allowLateSwap = aboveLimit)) {
                        android.util.Log.d("MusicPlayer", "[STREAM UPGRADE] Stream swap locked within 35s of track end or during pre-decoding/transition, omitting swap")
                        return@withContext -1L
                    }
                    pos
                }
                if (currentPos < 0L) return@launch

                // Pre-audition on silent background player before touching active playback
                var swapCandidate = upgraded
                var auditionSuccess = auditionStream(
                    track = track,
                    upgraded = swapCandidate,
                    targetPositionMs = currentPos,
                    expectedDurationMs = knownDur,
                    generation = generation,
                )
                if (!auditionSuccess && upgraded.audioCodec == "DOLBY ATMOS") {
                    // Immersive mix proved unplayable here: fall back to
                    // stereo lossless via the same hot-swap path so the track
                    // still promotes off lossy instead of sticking on it.
                    android.util.Log.w("MusicPlayer", "[STREAM UPGRADE] Immersive audition failed for '${track.title}'; trying stereo fallback via hot-swap")
                    val stereoCandidate = runCatching {
                        resolveLosslessTrackAudioStream(
                            track,
                            misc,
                            excludedLosslessUrls = emptySet(),
                            expectedDurationSeconds = expectedSec,
                            requestedQuality = if (requestedQuality == LosslessMusicApi.QUALITY_DOLBY_ATMOS) {
                                LosslessMusicApi.QUALITY_MAX_HI_RES
                            } else {
                                requestedQuality
                            },
                        )
                    }.getOrNull()
                    if (stereoCandidate != null && candidateAllowed(stereoCandidate) &&
                        (aboveLimit || isWorthSwapping(currentStream, stereoCandidate))
                    ) {
                        val stereoKnownDur = stereoCandidate.durationMs ?: knownDur
                        registerPreparedStream(stereoCandidate)
                        val stereoAudition = auditionStream(
                            track = track,
                            upgraded = stereoCandidate,
                            targetPositionMs = currentPos,
                            expectedDurationMs = stereoKnownDur,
                            generation = generation,
                        )
                        if (stereoAudition) {
                            swapCandidate = stereoCandidate
                            auditionSuccess = true
                        }
                    }
                }
                if (!auditionSuccess) {
                    android.util.Log.w("MusicPlayer", "[STREAM UPGRADE] Audition failed for '${track.title}'; retaining active stream without interruption")
                    // Never cut audio for a failed probe: drop the bad
                    // rendition so a later retry doesn't reuse it.
                    runCatching { preparedStreams.remove(swapCandidate.cacheKey) }
                    runCatching { mediaCache.removeResource(swapCandidate.cacheKey) }
                    return@launch
                }

                currentCoroutineContext().ensureActive()
                if (generation != playRequestGeneration.get()) return@launch

                // A stream that just died leaves only its buffer to play. The
                // offset probe takes about 3 s of it, and that made the swap
                // miss; with this little left, line up on positions alone.
                val runwayMs = withContext(Dispatchers.Main.immediate) {
                    if (playerDelegate.isInitialized()) player.bufferedPosition - player.currentPosition else 0L
                }
                val lagMs = if (aboveLimit && runwayMs < HOT_SWAP_PROBE_MIN_RUNWAY_MS) {
                    null
                } else {
                    measureSwapLagMs(track, currentStream, swapCandidate, currentPos, generation)
                }
                android.util.Log.i("MusicPlayer", "[STREAM UPGRADE] Measured source offset for '${track.title}': ${lagMs?.let { "${it}ms" } ?: "unknown"}")
                val alignedLagMs = lagMs ?: 0L

                // Perform seamless swap on Main thread with cached bytes ready
                withContext(Dispatchers.Main.immediate) {
                    if (generation != playRequestGeneration.get()) return@withContext
                    if (!candidateAllowed(swapCandidate)) return@withContext
                    if (!playerDelegate.isInitialized()) return@withContext
                    val currentIndex = player.currentMediaItemIndex
                    if (currentIndex !in 0 until player.mediaItemCount) return@withContext
                    val currentItem = player.getMediaItemAt(currentIndex)
                    if (currentItem.mediaId != expectedMediaId && currentItem.mediaId != track.mediaIdKey()) return@withContext

                    if (isStreamSwapLocked(allowLateSwap = aboveLimit) ||
                        !releaseIdleStandby("swap to ${swapCandidate.audioCodec}")
                    ) {
                        android.util.Log.d("MusicPlayer", "[STREAM UPGRADE] Stream swap locked near track end or during a transition, omitting swap")
                        return@withContext
                    }

                    suspend fun overlap() = overlapSwapToStandby(track, swapCandidate, currentIndex, generation, alignedLagMs) {
                        candidateAllowed(swapCandidate)
                    }
                    var outcome = overlap()
                    // A line-up can miss (late seek, a standby that stalls). The
                    // live player was not touched, so it is simply tried again.
                    repeat(2) { if (outcome == OverlapSwap.Failed && player.isPlaying) outcome = overlap() }
                    if (outcome == OverlapSwap.Swapped) {
                        cacheCurrentTrackStream(swapCandidate)
                        return@withContext
                    }
                    // Replacing the item under a playing track is an audible gap.
                    // It is only for a player that is silent anyway (paused,
                    // stalled) or an output that cannot run two decks.
                    if (outcome == OverlapSwap.Failed && player.isPlaying) {
                        android.util.Log.w("MusicPlayer", "[STREAM UPGRADE] Overlap swap could not line up for '${track.title}'; keeping the playing stream")
                        return@withContext
                    }
                    if (generation != playRequestGeneration.get()) return@withContext
                    if (player.currentMediaItemIndex != currentIndex || !candidateAllowed(swapCandidate) ||
                        isStreamSwapLocked(allowLateSwap = aboveLimit)
                    ) return@withContext

                    val playWhenReady = player.playWhenReady
                    val updatedMediaItem = track.toMediaItem(swapCandidate)
                    // Exact, not nearest sync point: the swap must land on the same audio.
                    val swapTargetMs = (player.currentPosition + alignedLagMs).coerceAtLeast(0L)
                    val swappedPlayer = player
                    swappedPlayer.setSeekParameters(SeekParameters.EXACT)

                    // Same song, same position, better source: mark so the
                    // transition callback doesn't treat it as a new track.
                    swappingMediaId = updatedMediaItem.mediaId
                    registerPreparedStream(swapCandidate)
                    stagePendingQuality(updatedMediaItem.mediaId, swapCandidate)
                    stagePendingQuality(track.mediaIdKey(), swapCandidate)
                    track.videoId?.let { stagePendingQuality(it, swapCandidate) }
                    val prevVolume = runCatching { player.volume }.getOrDefault(1f)
                    player.volume = 0f
                    replaceMediaItemPreservingShuffle(currentIndex, updatedMediaItem)
                    lastSeekTargetMs = swapTargetMs
                    lastSeekAtElapsedMs = SystemClock.elapsedRealtime()
                    player.seekTo(currentIndex, swapTargetMs)
                    player.prepare()
                    if (playWhenReady) {
                        player.play()
                    }
                    applicationScope.launch(Dispatchers.Main.immediate) {
                        withTimeoutOrNull(HOT_SWAP_READY_TIMEOUT_MS) {
                            while (swappedPlayer.playbackState == Player.STATE_BUFFERING) delay(20L)
                        }
                        runCatching { swappedPlayer.setSeekParameters(SeekParameters.EXACT) }
                        delay(30L)
                        val steps = 5
                        for (i in 1..steps) {
                            if (generation != playRequestGeneration.get()) break
                            runCatching { player.volume = prevVolume * (i.toFloat() / steps) }
                            delay(10L)
                        }
                        if (generation == playRequestGeneration.get()) {
                            runCatching { player.volume = prevVolume }
                        }
                    }

                    // Update quality pill and signal path quality teller immediately
                    publishResolvedQuality(swapCandidate, expectedMediaId = track.mediaIdKey())
                    cacheCurrentTrackStream(swapCandidate)
                    applyDacRoutingFor(dacRateFor(swapCandidate), swapCandidate.audioCodec)
                    updateBitPerfectState()

                    logStreamEvent("stream-upgrade-swapped", swapCandidate, retry = 0)
                    android.util.Log.i(
                        "MusicPlayer",
                        "[STREAM UPGRADE] Seamlessly hot-swapped '${track.title}' to ${swapCandidate.audioCodec} (${swapCandidate.bitrateKbps}kbps, ${swapCandidate.samplingRateKHz}kHz) at ${swapTargetMs}ms",
                    )
                }
            } catch (_: CancellationException) {
            } catch (e: Throwable) {
                android.util.Log.w("MusicPlayer", "[STREAM UPGRADE] Exception upgrading '${track.title}': ${e.message}")
            }
        }
        return true
    }

    /** A muted player with the real decode path, for testing a stream off-air. */
    private fun buildSilentPlayer(probe: SwapPcmProbe? = null): ExoPlayer {
        val resolvingFactory = ResolvingDataSource.Factory(cacheDataSourceFactory) { dataSpec ->
            val stream = dataSpec.key?.let(preparedStreams::get)
                ?: preparedStreams.values.firstOrNull { it.url == dataSpec.uri.toString() }
            if (stream != null) {
                dataSpec.buildUpon()
                    .setKey(stream.cacheKey)
                    .build()
                    .withRequestHeaders(mediaRequestHeaders(stream))
            } else {
                dataSpec
            }
        }
        // Same decode path as the active player: platform immersive
        // renderer ahead of software fallback so a spatial mix auditions
        // exactly as it would play. A bare factory without it fails
        // spatial manifests here while the main pipeline could play them.
        val renderersFactory = object : DefaultRenderersFactory(appContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink? {
                val sink = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                return if (sink != null && probe != null) TappingAudioSink(sink, probe) else sink
            }

            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>,
            ) {
                val immersiveRenderer = object : MediaCodecAudioRenderer(
                    context,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    audioSink,
                ) {
                    override fun supportsFormat(
                        mediaCodecSelector: MediaCodecSelector,
                        format: androidx.media3.common.Format,
                    ): Int {
                        val mime = format.sampleMimeType?.lowercase().orEmpty()
                        val isImmersive = mime.contains("eac3") || mime.contains("ec-3") ||
                            mime.contains("ac-3") || mime.contains("ac3") || mime.contains("ac4") ||
                            mime.contains("mha1") || mime.contains("mhm1")
                        if (!isImmersive) {
                            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
                        }
                        return super.supportsFormat(mediaCodecSelector, format)
                    }
                }
                out.add(immersiveRenderer)
                super.buildAudioRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    audioSink,
                    eventHandler,
                    eventListener,
                    out,
                )
            }
        }.apply {
            setExtensionRendererMode(
                if (FfmpegCompat.isUsable) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
            )
            setEnableDecoderFallback(true)
            setMediaCodecSelector(accurateAudioMediaCodecSelector)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 15_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500,
            )
            .build()

        return ExoPlayer.Builder(appContext, renderersFactory)
            .setMediaSourceFactory(createAudioMediaSourceFactory(resolvingFactory))
            .setLoadControl(loadControl)
            .setSeekParameters(SeekParameters.EXACT)
            .build().apply {
                playWhenReady = false
                volume = 0f
            }
    }

    /**
     * Measures how far apart the two masters are by decoding the same stretch of
     * both on silent players at high speed. Null when it can't be measured
     * (undecodable passthrough, silence, a different master, or a slow network).
     */
    private suspend fun measureSwapLagMs(
        track: PlayableTrack,
        current: ResolvedStream,
        candidate: ResolvedStream,
        positionMs: Long,
        generation: Long,
    ): Long? {
        if (bitPerfectEnabled || exclusiveUsbOutput.isActive() || current.isExpired()) return null
        val captures = withContext(Dispatchers.Main) {
            preparedStreams[current.cacheKey] = current
            val currentProbe = SwapPcmProbe()
            val candidateProbe = SwapPcmProbe()
            val players = listOf(
                buildSilentPlayer(currentProbe) to track.toMediaItem(current),
                buildSilentPlayer(candidateProbe) to track.toMediaItem(candidate),
            )
            try {
                players.forEach { (probePlayer, item) ->
                    probePlayer.setMediaItem(item, positionMs)
                    probePlayer.setPlaybackSpeed(HOT_SWAP_PROBE_SPEED)
                    probePlayer.prepare()
                    probePlayer.playWhenReady = true
                }
                val finished = withTimeoutOrNull(HOT_SWAP_PROBE_TIMEOUT_MS) {
                    while (!(currentProbe.isComplete && candidateProbe.isComplete)) {
                        val failed = generation != playRequestGeneration.get() ||
                            currentProbe.unsupported || candidateProbe.unsupported ||
                            players.any { (probePlayer, _) -> probePlayer.playerError != null }
                        if (failed) return@withTimeoutOrNull false
                        delay(50L)
                    }
                    true
                } == true
                if (!finished) return@withContext null
                val reference = currentProbe.snapshot() ?: return@withContext null
                val target = candidateProbe.snapshot() ?: return@withContext null
                reference to target
            } finally {
                players.forEach { (probePlayer, _) -> runCatching { probePlayer.release() } }
            }
        } ?: return null
        return withContext(Dispatchers.Default) {
            SwapAlignment.measureLagMs(captures.first, captures.second)
        }
    }

    /**
     * A provider stream can stop serving mid-song: community addons expire or
     * drop a stream without saying when. The refused chunk is known while the
     * buffered audio is still playing, so the track crosses to YouTube now
     * instead of cutting when the buffer runs dry. The track then stays off the
     * provider until it is played again, or it would be promoted straight back.
     */
    @MainThread
    private fun rescueFailingProviderStream(live: ExoPlayer, error: java.io.IOException) {
        val item = live.currentMediaItem ?: return
        val stream = item.localConfiguration?.customCacheKey?.let(preparedStreams::get) ?: return
        if (stream.youtubeCandidate != null || stream.cacheKey.startsWith("local:") || stream.cacheKey.startsWith("offline:")) return
        val track = item.toPlayableTrack()
        if (!live.isPlaying || track.mediaIdKey() in losslessBypassMediaIds) return
        // Only a server's refusal: with the network down YouTube fails too.
        val refused = generateSequence<Throwable>(error) { it.cause }.take(8).any {
            it is HttpDataSource.InvalidResponseCodeException || it is java.io.FileNotFoundException
        }
        if (!refused) return
        val scheduled = scheduleQualityUpgrade(
            track = track,
            expectedMediaId = item.mediaId,
            generation = playRequestGeneration.get(),
            currentStream = stream,
            requestedQualityOverride = LosslessMusicApi.QUALITY_YOUTUBE,
        )
        if (!scheduled) return
        losslessBypassMediaIds += track.mediaIdKey()
        losslessBypassMediaIds += item.mediaId
        android.util.Log.w(
            "MusicPlayer",
            "[STREAM UPGRADE] provider stream refused a chunk for '${track.title}' with " +
                "${(live.bufferedPosition - live.currentPosition).coerceAtLeast(0L)}ms buffered; crossing to YouTube",
        )
    }

    private enum class OverlapSwap { Swapped, Unavailable, Failed }

    /**
     * Starts the upgraded stream muted on the standby, lines it up with the live
     * player, then fades across while both play the same audio. Audio never stops.
     * The live player is untouched unless the result is [OverlapSwap.Swapped].
     */
    @MainThread
    private suspend fun overlapSwapToStandby(
        track: PlayableTrack,
        candidate: ResolvedStream,
        index: Int,
        generation: Long,
        lagMs: Long,
        canCommit: () -> Boolean,
    ): OverlapSwap {
        val live = player
        // AutoMix only needs the standby inside its blend window, which
        // isStreamSwapLocked already keeps swaps out of. Excluding it here sent
        // every swap with AutoMix on down the in-place path, an audible cut.
        if (isCasting || bitPerfectEnabled || exclusiveUsbOutput.isActive() || isOverlapMixActive || !live.isPlaying) {
            return OverlapSwap.Unavailable
        }
        val liveMediaId = live.currentMediaItem?.mediaId ?: return OverlapSwap.Unavailable
        val candidateItem = track.toMediaItem(candidate)
        val queue = (0 until live.mediaItemCount).map { i ->
            if (i == index) candidateItem else live.getMediaItemAt(i)
        }
        hotSwapActive = true
        registerPreparedStream(candidate)
        val standby = standbyOrCreate()
        var committed = false
        try {
            standby.volume = 0f
            standby.setAudioAttributes(live.audioAttributes, false)
            standby.playbackParameters = live.playbackParameters
            standby.setSeekParameters(SeekParameters.EXACT)
            standby.pause()
            standby.setMediaItems(queue, index, (live.currentPosition + lagMs + HOT_SWAP_SEEK_LEAD_MS).coerceAtLeast(0L))
            standby.prepare()

            fun stillValid() = generation == playRequestGeneration.get() && player === live && live.isPlaying &&
                live.currentMediaItem?.mediaId == liveMediaId && standby.playerError == null &&
                standby.playbackState != Player.STATE_IDLE && canCommit()

            suspend fun awaitStandby(playing: Boolean): Boolean = withTimeoutOrNull(HOT_SWAP_READY_TIMEOUT_MS) {
                while (if (playing) !standby.isPlaying else standby.playbackState != Player.STATE_READY) {
                    if (!stillValid()) return@withTimeoutOrNull false
                    delay(10L)
                }
                true
            } == true

            if (!awaitStandby(playing = false)) return OverlapSwap.Failed
            standby.play()

            // A seek lands anywhere within about 100 ms, an audible double hit
            // in the fade, and pausing the deck for its lead is no finer. So a
            // seek only gets close, and the muted deck is then run slightly
            // fast or slow until it has made up the difference: nothing
            // restarts, so that lands within a few ms. Every reading waits
            // HOT_SWAP_SETTLE_MS: a deck's position is a blend for about a
            // second after its output changes, and reading it earlier moved the
            // deck on errors of up to 50 ms and passed swaps that were off.
            val liveParameters = live.playbackParameters
            var seekLeadMs = HOT_SWAP_SEEK_LEAD_MS
            var seeked = false
            var driftMs = Long.MAX_VALUE
            val rounds = StringBuilder()
            for (round in 1..HOT_SWAP_ALIGN_ROUNDS) {
                if (!awaitStandby(playing = true)) return OverlapSwap.Failed
                delay(HOT_SWAP_SETTLE_MS)
                if (!stillValid()) return OverlapSwap.Failed
                driftMs = live.currentPosition + lagMs - standby.currentPosition
                rounds.append(driftMs)
                if (kotlin.math.abs(driftMs) <= HOT_SWAP_ALIGNED_MS || round == HOT_SWAP_ALIGN_ROUNDS) break
                val gapMs = kotlin.math.abs(driftMs)
                if (gapMs <= HOT_SWAP_NUDGE_MAX_MS) {
                    // The gentlest rate is the most exact, so a wide gap is
                    // closed quickly first and the next round trims the rest.
                    val nudge = (gapMs / HOT_SWAP_NUDGE_SPAN_MS).coerceIn(HOT_SWAP_NUDGE_MIN, HOT_SWAP_NUDGE_MAX)
                    rounds.append(" nudge ${"%.2f".format(nudge)}, ")
                    val factor = if (driftMs > 0L) 1f + nudge else 1f - nudge
                    standby.playbackParameters = PlaybackParameters(liveParameters.speed * factor, liveParameters.pitch)
                    delay((gapMs / (nudge * liveParameters.speed)).toLong())
                    standby.playbackParameters = liveParameters
                } else {
                    // Seeks take 0.3 s on one stream and 1 s on another; only a
                    // seek's own miss says which.
                    if (seeked) seekLeadMs = (seekLeadMs + driftMs).coerceIn(0L, HOT_SWAP_SEEK_LEAD_MAX_MS)
                    seeked = true
                    rounds.append(" seek +$seekLeadMs, ")
                    standby.seekTo(live.currentPosition + lagMs + seekLeadMs)
                }
            }
            android.util.Log.i("MusicPlayer", "[STREAM UPGRADE] align drift per round (ms): $rounds")
            if (kotlin.math.abs(driftMs) > HOT_SWAP_MAX_DRIFT_MS) {
                android.util.Log.w("MusicPlayer", "[STREAM UPGRADE] Could not align standby (drift ${driftMs}ms)")
                return OverlapSwap.Failed
            }
            val sameQueue = standby.mediaItemCount == live.mediaItemCount &&
                (0 until live.mediaItemCount).all { i ->
                    i == index || standby.getMediaItemAt(i).mediaId == live.getMediaItemAt(i).mediaId
                }
            if (!sameQueue || !stillValid()) return OverlapSwap.Failed

            val timeline = live.currentTimeline
            val order = mutableListOf<Int>()
            var cursor = timeline.getFirstWindowIndex(live.shuffleModeEnabled)
            while (cursor != C.INDEX_UNSET && order.size < live.mediaItemCount) {
                order.add(cursor)
                cursor = timeline.getNextWindowIndex(cursor, Player.REPEAT_MODE_OFF, live.shuffleModeEnabled)
            }
            if (order.size == standby.mediaItemCount) {
                runCatching { standby.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), Random.nextLong())) }
            }
            standby.shuffleModeEnabled = live.shuffleModeEnabled
            standby.repeatMode = live.repeatMode

            live.removeListener(listener)
            activePlayer = standby
            musicHapticsController.bindToPlayer(standby)
            musicHapticsController.onAudioSessionIdChanged(standby.audioSessionId)
            standby.addListener(listener)
            committed = true
            standbyQueue = emptyList()
            standbyIndex = C.INDEX_UNSET
            resetRenderWatchdog()
            publishResolvedQuality(candidate, expectedMediaId = track.mediaIdKey())
            applyDacRoutingFor(dacRateFor(candidate), candidate.audioCodec)
            updateBitPerfectState()
            refresh(standby)
            logStreamEvent("stream-upgrade-swapped", candidate, retry = 0)
            android.util.Log.i(
                "MusicPlayer",
                "[STREAM UPGRADE] Overlap hot-swapped '${track.title}' to ${candidate.audioCodec} (offset ${lagMs}ms, drift ${driftMs}ms)",
            )

            applicationScope.launch(Dispatchers.Main.immediate) {
                val targetVolume = appVolume
                try {
                    // Linear gains: both sides carry the same audio, so they sum to constant level.
                    for (step in 1..HOT_SWAP_FADE_STEPS) {
                        val progress = step.toFloat() / HOT_SWAP_FADE_STEPS
                        standby.volume = targetVolume * progress
                        live.volume = targetVolume * (1f - progress)
                        delay(HOT_SWAP_FADE_STEP_MS)
                    }
                } finally {
                    if (player === standby) standby.volume = appVolume
                    standby.setSeekParameters(SeekParameters.EXACT)
                    live.stop()
                    live.clearMediaItems()
                    hotSwapActive = false
                }
            }
            return OverlapSwap.Swapped
        } finally {
            if (!committed) {
                runCatching {
                    standby.stop()
                    standby.clearMediaItems()
                    standby.setSeekParameters(SeekParameters.EXACT)
                    // A cancel mid-nudge must not leave the deck off-speed.
                    standby.playbackParameters = live.playbackParameters
                }
                hotSwapActive = false
            }
        }
    }

    private suspend fun auditionStream(
        track: PlayableTrack,
        upgraded: ResolvedStream,
        targetPositionMs: Long,
        expectedDurationMs: Long?,
        generation: Long = playRequestGeneration.get(),
    ): Boolean = withContext(Dispatchers.Main) {
        var auditionPlayer: ExoPlayer? = null
        try {
            auditionPlayer = buildSilentPlayer()

            val testItem = track.toMediaItem(upgraded)
            auditionPlayer.setMediaItem(testItem)
            if (targetPositionMs > 0L) {
                auditionPlayer.seekTo(targetPositionMs)
            }
            auditionPlayer.prepare()

            // Condition-based wait, no fixed timeout: the silent check ends
            // when the candidate proves playable (ready + buffered past the
            // live edge), proves unplayable (error/ended/duration mismatch),
            // or becomes irrelevant (new generation / track changed).
            val deadlineMs = SystemClock.elapsedRealtime() + AUDITION_TIMEOUT_MS
            while (currentCoroutineContext().isActive && SystemClock.elapsedRealtime() < deadlineMs) {
                if (generation != playRequestGeneration.get()) return@withContext false
                if (auditionPlayer.playerError != null) {
                    android.util.Log.w(
                        "MusicPlayer",
                        "[STREAM AUDITION] Stream failed: ${auditionPlayer.playerError?.errorCodeName}",
                    )
                    return@withContext false
                }
                if (auditionPlayer.playbackState == Player.STATE_ENDED) {
                    android.util.Log.w(
                        "MusicPlayer",
                        "[STREAM AUDITION] Stream ended prematurely",
                    )
                    return@withContext false
                }
                if (auditionPlayer.playbackState == Player.STATE_READY) {
                    val candidateDur = auditionPlayer.duration
                    if (expectedDurationMs != null && expectedDurationMs > 0L && candidateDur > 0L) {
                        val durationDiffMs = kotlin.math.abs(candidateDur - expectedDurationMs)
                        if (durationDiffMs > UPGRADE_DURATION_TOLERANCE_SEC * 1_000L) {
                            android.util.Log.w(
                                "MusicPlayer",
                                "[STREAM AUDITION] Duration mismatch: expected ${expectedDurationMs}ms vs candidate ${candidateDur}ms (diff=${durationDiffMs}ms)",
                            )
                            return@withContext false
                        }
                    }
                    // Codec truth: a lossless claim arriving as lossy bytes, or
                    // a spatial claim arriving as plain stereo bytes, is a
                    // transcode/mislabeled rendition â€” reject before the swap.
                    val decodedMime = runCatching { auditionPlayer.audioFormat?.sampleMimeType?.lowercase().orEmpty() }.getOrDefault("")
                    if (decodedMime.isNotBlank()) {
                        val claimsSpatial = upgraded.audioCodec == "DOLBY ATMOS" || upgraded.audioCodec == "SPATIAL AUDIO"
                        val isSpatialMime = decodedMime.contains("eac3") || decodedMime.contains("ec-3") ||
                            decodedMime.contains("ac-3") || decodedMime.contains("ac3") ||
                            decodedMime.contains("mha1") || decodedMime.contains("mhm1")
                        if (claimsSpatial && !isSpatialMime) {
                            android.util.Log.w(
                                "MusicPlayer",
                                "[STREAM AUDITION] Codec mismatch: claimed ${upgraded.audioCodec} but decoded $decodedMime",
                            )
                            return@withContext false
                        }
                        if (upgraded.isLossless) {
                            val isLossyMime = decodedMime.contains("opus") || decodedMime.contains("mp4a") ||
                                decodedMime.contains("aac") || decodedMime.contains("mp3") || decodedMime.contains("mpeg")
                            if (isLossyMime) {
                                android.util.Log.w(
                                    "MusicPlayer",
                                    "[STREAM AUDITION] Codec mismatch: claimed lossless but decoded $decodedMime",
                                )
                                return@withContext false
                            }
                        }
                    }
                    if (generation != playRequestGeneration.get()) return@withContext false
                    // Past one segment: some provider streams serve their first
                    // 10 s and refuse the next request, which passed a 1.5 s check
                    // and then cut the song right after the swap.
                    val minBufferedMs = targetPositionMs + AUDITION_MIN_BUFFER_MS
                    if (auditionPlayer.bufferedPosition >= minBufferedMs ||
                        auditionPlayer.bufferedPercentage >= 100 ||
                        (candidateDur > 0L && auditionPlayer.bufferedPosition >= candidateDur - 500L)
                    ) {
                        android.util.Log.i(
                            "MusicPlayer",
                            "[STREAM AUDITION] Stream audition successful! Buffered through ${auditionPlayer.bufferedPosition}ms",
                        )
                        return@withContext true
                    }
                }
                delay(150L)
            }
            android.util.Log.w("MusicPlayer", "[STREAM AUDITION] Audition timed out or canceled for '${track.title}'")
            false
        } catch (e: Throwable) {
            android.util.Log.w("MusicPlayer", "[STREAM AUDITION] Exception during audition: ${e.message}")
            false
        } finally {
            auditionPlayer?.let { p ->
                runCatching { p.release() }
            }
            // Grace period for Media3 cache locks to release before main player accesses the cache
            delay(150L)
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

    internal suspend fun resolveTrackAudioStreamWithRetry(
        track: PlayableTrack,
        videoId: String?,
        allowLossless: Boolean,
        losslessBudget: LosslessBudget = LosslessBudget.Interactive,
        claimPlayback: Boolean = true,
    ): ResolvedStream {
        runCatching { streamResolutionWakeLock?.acquire(60_000L) }
        try {
            var lastFailure: Throwable? = null
            repeat(2) { attempt ->
                try {
                    return resolveTrackAudioStream(
                        track = track,
                        videoId = videoId,
                        allowLossless = allowLossless,
                        losslessBudget = losslessBudget,
                        claimPlayback = claimPlayback,
                    )
                } catch (error: Throwable) {
                    if (error is CancellationException &&
                        error !is kotlinx.coroutines.TimeoutCancellationException
                    ) {
                        throw error
                    }
                    if (error is VpnYoutubeLoginRequiredException) throw error
                    val failure = if (error is kotlinx.coroutines.TimeoutCancellationException) {
                        java.io.IOException("Timed out resolving audio", error)
                    } else {
                        error
                    }
                    lastFailure = failure
                    if (attempt == 0 && failure is java.io.IOException) {
                        delay(PLAYBACK_RETRY_BASE_DELAY_MS + Random.nextLong(PLAYBACK_RETRY_JITTER_MS + 1L))
                    } else {
                        throw failure
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

    private fun rememberTrackDuration(track: PlayableTrack?, durationMs: Long?) {
        if (track == null) return
        val ms = durationMs?.takeIf { it > 0L } ?: track.durationMs?.takeIf { it > 0L } ?: return
        rememberKnownDuration(track.mediaIdKey(), ms)
        rememberKnownDuration(track.videoId, ms)
        rememberKnownDuration("${track.artist}|${track.title}".lowercase(), ms)
        rememberKnownDuration("${track.title}|${track.artist}".lowercase(), ms)
        rememberKnownDuration("query:${track.artist.lowercase()}|${track.title.lowercase()}", ms)
    }

    internal fun findKnownDuration(track: PlayableTrack?): Long? {
        if (track == null) return null
        if (track.durationMs != null && track.durationMs > 0L) return track.durationMs
        return track.mediaIdKey().let(knownDurations::get)
            ?: track.videoId?.let(knownDurations::get)
            ?: "${track.artist}|${track.title}".lowercase().let(knownDurations::get)
            ?: "${track.title}|${track.artist}".lowercase().let(knownDurations::get)
            ?: "query:${track.artist.lowercase()}|${track.title.lowercase()}".let(knownDurations::get)
    }

    /**
     * Best-known duration for the item the player currently holds, applied
     * through [selectDuration] so a previous track's length can never be reused
     * as this one's denominator or crossfade timer.
     *
     * Returns 0 while nothing trustworthy is known, which is the honest answer:
     * a stream whose container has not parsed yet has no length, and the caller
     * shows no denominator rather than the outgoing track's. Reuse across
     * re-reads of the *same* item is still allowed, so a mid-track rebuffer
     * cannot freeze the progress bar at 0:00.
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
        val currentTrack = current?.toPlayableTrack() ?: _state.value.current
        if (playerDurationMs > 0) {
            rememberKnownDuration(current?.localConfiguration?.customCacheKey, playerDurationMs)
            rememberKnownDuration(current?.mediaId, playerDurationMs)
            rememberTrackDuration(currentTrack, playerDurationMs)
            durationOwnerMediaId = current?.mediaId
            return playerDurationMs
        }
        // Everything below is keyed to the item in hand, so it is trustworthy
        // even across a transition. `previousMs` deliberately is not: that is
        // whatever was published last, which after a track change describes a
        // different song entirely.
        val knownForCurrentItem = current?.localConfiguration?.customCacheKey?.let(knownDurations::get)
            ?: current?.mediaId?.let(knownDurations::get)
            ?: findKnownDuration(currentTrack)
            ?: current?.mediaMetadata?.extras?.getLong("durationMs")?.takeIf { it > 0L }
            ?: currentTrack?.durationMs?.takeIf { it > 0L }
            ?: current?.localConfiguration?.customCacheKey?.let(preparedStreams::get)?.durationMs
        return selectDuration(
            DurationRequest(
                playerMs = playerDurationMs,
                knownForCurrentItemMs = knownForCurrentItem?.coerceAtLeast(0L) ?: 0L,
                publishedMs = previousMs,
                publishedBelongsToCurrentItem = current?.mediaId != null &&
                    durationOwnerMediaId == current.mediaId,
            ),
        )
    }

    /**
     * A queue item resolved to a concrete YouTube videoId this session. The
     * id is stamped onto the in-memory queue and current track so the rest
     * of the session works with a direct identity, and persisted onto stored
     * playlists that hold the same song â€” blank-id rows get the id so the
     * next play resolves directly, and rows that pinned a now-dead id get
     * the replacement (only when [rematchedFromVideoId] names exactly the id
     * that failed). No-op when the learned id already matches.
     */
    internal fun learnQueueVideoId(
        index: Int,
        track: PlayableTrack,
        learnedVideoId: String?,
        rematchedFromVideoId: String? = null,
    ) {
        val videoId = learnedVideoId?.takeIf { it.isNotBlank() } ?: return
        val previousVideoId = track.videoId?.takeIf { it.isNotBlank() }
        if (previousVideoId == videoId) return
        if (previousVideoId != null && previousVideoId != rematchedFromVideoId) return
        val learned = track.copy(videoId = videoId, internalTrackId = videoId)
        _state.update { state ->
            val queue = state.queue.toMutableList()
            val queueIndex = if (index in queue.indices && queue[index].mediaIdKey() == track.mediaIdKey()) {
                index
            } else {
                queue.indexOfFirst { it.mediaIdKey() == track.mediaIdKey() }
            }
            if (queueIndex >= 0) queue[queueIndex] = learned
            state.copy(
                queue = queue,
                current = if (state.current?.mediaIdKey() == track.mediaIdKey()) learned else state.current,
            )
        }
        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                playlistRepository.pinVideoIdForSong(
                    GeneratedTrack(name = track.title, artist = track.artist),
                    videoId,
                    replaceVideoId = previousVideoId,
                )
            }
        }
    }

    internal fun findPreparedStreamFor(
        track: PlayableTrack,
        videoId: String?,
        bypassLossless: Boolean,
        requestedQuality: Int = selectedStreamingQuality(latestPlaybackSettings),
    ): ResolvedStream? {
        val matchVideoId = videoId ?: track.videoId
        val localKey = "${track.artist.trim().lowercase()}_${track.title.trim().lowercase()}"
        val losslessPrefix = "lossless:${track.mediaIdKey()}:"

        // 1. Prefer prepared lossless stream if lossless is allowed
        if (!bypassLossless) {
            val losslessHit = preparedStreams.values.firstOrNull { stream ->
                !stream.isExpired() && stream.cacheKey.startsWith(losslessPrefix) &&
                    isStreamAllowedForQuality(stream, requestedQuality, latestPlaybackSettings)
            }
            if (losslessHit != null) return losslessHit
        }

        // 2. Local / offline download
        val localHit = preparedStreams.values.firstOrNull { stream ->
            !stream.isExpired() && (stream.cacheKey.startsWith("local:") || stream.cacheKey.startsWith("offline:")) &&
                stream.cacheKey.substringAfter(':') == localKey
        }
        if (localHit != null) return localHit

        // 3. Fallback to matched YouTube candidate
        return preparedStreams.values.firstOrNull { stream ->
            !stream.isExpired() && matchVideoId != null && stream.youtubeCandidate?.videoId == matchVideoId &&
                isStreamAllowedForQuality(stream, requestedQuality, latestPlaybackSettings)
        }
    }

    internal fun registerPreparedStream(stream: ResolvedStream) {
        preparedStreams.entries.removeIf { it.value.isExpired() }
        preparedStreams[stream.cacheKey] = stream
        val seedMs = stream.durationMs ?: stream.youtubeCandidate?.durationMs
            ?: _state.value.current?.durationMs ?: findKnownDuration(_state.value.current)
        rememberKnownDuration(stream.cacheKey, seedMs)
        rememberKnownDuration(stream.youtubeCandidate?.videoId, seedMs)
        rememberTrackDuration(_state.value.current, seedMs)
        if (preparedStreams.size <= MAX_PREPARED_STREAMS) return
        // ExoPlayer is main-thread only. Resolve coroutines on Dispatchers.IO
        // (startup lossless, hot-swap upgrade, stereo fallback) call this too;
        // touching `player` here from a worker crashed the app with
        // "Player is accessed on the wrong thread". Prune on main instead.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            applicationScope.launch(Dispatchers.Main.immediate) { prunePreparedStreams() }
            return
        }
        prunePreparedStreams()
    }

    @MainThread
    private fun prunePreparedStreams() {
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
            .toList()
            .forEach(preparedStreams::remove)
    }

    internal fun logStreamEvent(
        stage: String,
        stream: ResolvedStream,
        retry: Int,
        httpStatus: Int? = null,
        error: Throwable? = null,
    ) {
        val candidate = stream.youtubeCandidate
        val expiryMs = stream.expiresAtEpochMs ?: candidate?.expiresAtEpochMs
        val expiry = when {
            expiryMs == null -> "unknown"
            expiryMs <= System.currentTimeMillis() -> "expired"
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

    internal fun logResolutionFailure(
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

    internal fun ResolvedStream.isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochMs?.let { it - now <= RESOLVED_URL_EXPIRY_MARGIN_MS }
            ?: (youtubeCandidate?.expiresAtEpochMs?.let { it - now <= RESOLVED_URL_EXPIRY_MARGIN_MS } == true)

    /** Addon media URLs carry `exp` (ms epoch; far-future = never expires).
     *  Tolerates s-epoch in case a proxy ever rewrites the param. */
    private fun addonUrlExpiryMs(url: String): Long? {
        val raw = runCatching { Uri.parse(url).getQueryParameter("exp") }.getOrNull() ?: return null
        val num = raw.toLongOrNull()?.takeIf { it > 0L } ?: return null
        return if (num > 1_000_000_000_000L) num else num * 1_000L
    }

    private fun Throwable.httpStatusCodeOrNull(): Int? = causeChain()
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()
        ?.responseCode

    /**
     * Rendition depth for the bytes actually playing. Handles every URL shape
     * this pipeline stores: base64 data URLs (pre-resolve), the MPD file we
     * wrote from them (file:// post-resolve), and raw XML. Network URLs
     * return null â€” never fetch here; the resolve path already measured them.
     */
    private fun renditionDepthFromStreamUrl(url: String): Int? {
        if (url.startsWith("data:application/dash+xml")) {
            return runCatching { LosslessMusicApi.manifestBitDepthOf(url) }.getOrNull()
        }
        if (url.trimStart().startsWith("<")) {
            return runCatching { LosslessMusicApi.manifestBitDepthOf(url) }.getOrNull()
        }
        val path = when {
            url.startsWith("file://") -> runCatching { Uri.parse(url).path }.getOrNull()
            url.startsWith("/") -> url
            else -> null
        } ?: return null
        return runCatching {
            val f = java.io.File(path)
            if (!f.exists() || f.length() <= 0L || f.length() > 256 * 1024L) return@runCatching null
            val head = ByteArray(32 * 1024)
            java.io.FileInputStream(f).use { ins ->
                val n = ins.read(head)
                if (n <= 0) return@runCatching null
                LosslessMusicApi.manifestBitDepthOf(String(head, 0, n, Charsets.UTF_8))
            }
        }.getOrNull()
    }

    private fun playbackRetryDelayMs(error: PlaybackException, retry: Int): Long {
        val status = error.httpStatusCodeOrNull()
        // 403 (expired/throttled googlevideo URL) benefits from a short
        // backoff so the fresh resolve + open isn't re-throttled instantly.
        val transientHttp = status == 403 || status == 408 || status == 429 || (status != null && status in 500..599)
        val transientNetwork = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        if (!transientHttp && !transientNetwork) return 0L
        val exponential = PLAYBACK_RETRY_BASE_DELAY_MS * (1L shl (retry - 1).coerceAtMost(3))
        return exponential + Random.nextLong(PLAYBACK_RETRY_JITTER_MS + 1L)
    }

    /** Network/HTTP failure of the transport, as opposed to the bytes being undecodable. */
    private fun isTransportFailure(error: PlaybackException): Boolean {
        if (isUnsupportedMediaFailure(error)) return false
        val status = error.httpStatusCodeOrNull()
        if (status == 401 || status == 403 || status == 408 || status == 410 ||
            status == 429 || (status != null && status in 500..599)
        ) return true
        return error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
    }

    private fun isRetryablePlaybackFailure(error: PlaybackException): Boolean {
        if (isUnsupportedMediaFailure(error)) return true
        val status = error.httpStatusCodeOrNull()
        if (status == 401 || status == 403 || status == 404 || status == 410 ||
            status == 408 || status == 416 || status == 429 || (status != null && status in 500..599)
        ) return true
        return error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
            error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
    }

    /**
     * Auto-skip gate for playback errors. Mid-queue tracks that fail at
     * position 0 with a transient HTTP status (401/403 throttled or expired
     * signed URL, 408/429, 5xx) or a network/timeout blip must HOLD with
     * tap-to-retry instead of auto-advancing: otherwise one bad stretch
     * eats the queue 2-3s at a time ("buffers then skips"). Only
     * confirmed-unplayable, permanent decode/format errors, or 404/410
     * (gone) auto-skip â€” same as a track that already played audibly never
     * skipping.
     */
    private fun shouldAutoSkipForPlaybackError(
        error: PlaybackException,
        playedAudibly: Boolean,
        confirmedUnplayable: Boolean,
    ): Boolean {
        if (playedAudibly) return false
        if (confirmedUnplayable || isUnsupportedMediaFailure(error)) return true
        when (error.httpStatusCodeOrNull()) {
            404, 410 -> return true
            401, 403, 408, 429 -> return false
            in 500..599 -> return false
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> false
            else -> !isRetryablePlaybackFailure(error)
        }
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

            // Retriever values are measured container facts (STREAMINFO),
            // not backend hearsay: a present 16 beside 96kHz is a genuine
            // 16/96 file â€” trust it. When the retriever omits depth/rate
            // (pre-S APIs, several OEMs/files), the FLAC header itself is
            // read instead â€” same STREAMINFO facts, working on every device.
            // Only a truly unreadable header stays unknown, never a guessed
            // 16 or 44.1.
            var effectiveBitDepth = bitDepth?.takeIf { it > 0 }
            var effectiveRateKHz = sampleRateKHz
            if (isFlac && (effectiveBitDepth == null || effectiveRateKHz == null)) {
                runCatching {
                    val header: FlacStreamInfo? = if (url.startsWith("content://")) {
                        appContext.contentResolver.openInputStream(Uri.parse(url))?.use { readFlacStreamInfo(it) }
                    } else {
                        java.io.File(url.removePrefix("file://")).takeIf { it.isFile && it.canRead() }
                            ?.inputStream()?.use { readFlacStreamInfo(it) }
                    }
                    if (header != null) {
                        if (effectiveBitDepth == null) effectiveBitDepth = header.bitDepth?.takeIf { it > 0 }
                        if (effectiveRateKHz == null) header.sampleRateHz?.takeIf { it > 0 }?.let { effectiveRateKHz = it / 1000.0 }
                    }
                }
            }

            val codec = when {
                isFlac && effectiveBitDepth != null && effectiveRateKHz != null && effectiveRateKHz > 0.0 ->
                    "$effectiveBitDepth/${formatSampleRateKHz(effectiveRateKHz)}kHz"
                isFlac && ((effectiveBitDepth ?: 0) > 16 || (effectiveRateKHz ?: 0.0) > 48.0) -> "HI-RES FLAC"
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
                    bitDepth = effectiveBitDepth,
                    samplingRateKHz = effectiveRateKHz,
                    // FLAC is lossless at every bit depth. Requiring >16 here
                    // marked CD-quality (16/44.1) FLAC â€” and any FLAC whose
                    // container omits BITS_PER_SAMPLE â€” as lossy, which pushed
                    // qualityLabel onto the kbps branch ("FLAC 1324 kbps")
                    // instead of showing the bit depth / sample rate.
                    isLossless = isFlac,
                )
            }
            onMain {
                applyDacRoutingFor(currentSourceRateHz())
            }
            updateBitPerfectState()
        } catch (_: Exception) {
            // Retriever unreadable (scoped-storage race, odd container):
            // badge from the file extension so the pill never falls back
            // to a bare "AUDIO" with no provenance.
            val lower = url.lowercase()
            val fallbackCodec = when {
                lower.endsWith(".flac") -> "FLAC"
                lower.endsWith(".m4a") || lower.endsWith(".mp4") || lower.endsWith(".aac") -> "AAC"
                lower.endsWith(".opus") || lower.endsWith(".ogg") -> "OPUS"
                lower.endsWith(".mp3") -> "MP3"
                lower.endsWith(".wav") -> "WAV"
                else -> "AUDIO"
            }
            _state.update {
                it.copy(
                    audioCodec = fallbackCodec,
                    // Unknown, not 16/44.1: asserting depths/rates for an
                    // unreadable file is exactly the false-tag bug.
                    bitDepth = null,
                    samplingRateKHz = null,
                    isLossless = fallbackCodec == "FLAC",
                )
            }
            onMain {
                applyDacRoutingFor(currentSourceRateHz())
            }
            updateBitPerfectState()
        } finally {
            runCatching { retriever.release() }
        }
    }

    internal fun ensureForegroundService() {
        val intent = Intent(appContext, MusicPlaybackService::class.java)
        // Background-start restrictions (Android 12+) can reject this when
        // playback is triggered from widget/tile paths â€” that must never take
        // the app down; playback simply continues without foreground priority.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appContext.startForegroundService(intent)
            else appContext.startService(intent)
        }.onFailure {
            android.util.Log.w("MusicPlayer", "Foreground service start rejected", it)
        }
    }

    fun restoreSessionIfNeeded(): Boolean {
        if (_state.value.current != null) return false
        return restorePlaybackSession()
    }


    @MainThread
    internal fun refresh(player: Player) {
        if (isCasting) return
        val previous = _state.value
        val selectedMediaId = previous.current?.mediaIdKey()
        val selectionIsResolving = selectedMediaId != null &&
            resolvingMediaIds[selectedMediaId] == playRequestGeneration.get()
        val selectionIsPausedResolution = selectedMediaId != null &&
            selectedMediaId == pausedResolutionMediaId
        if ((selectionIsResolving ||
                selectionIsPausedResolution ||
                unavailableSkipJob?.isActive == true) &&
            (player.currentMediaItemIndex != previous.currentIndex ||
                !isCurrentMediaMatch(player, previous.current))
        ) return
        val queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toPlayableTrack() }
        val current = player.currentMediaItem?.toPlayableTrack()
        if (queue.isEmpty() && current == null && previous.current != null) {
            // ExoPlayer briefly reports an empty timeline while a selected
            // track is being resolved. Keep the logical queue available for
            // transport controls until the new timeline is installed.
            return
        }
        if (exclusiveUsbOutput.isActive()) {
            exclusiveUsbOutput.setPaused(!player.playWhenReady)
        }
        val sameTrack = current?.let { it.title == previous.current?.title && it.artist == previous.current?.artist } == true ||
            (current?.videoId != null && current.videoId == previous.current?.videoId)
        val rawBuffering = player.playWhenReady && (
            player.playbackState == Player.STATE_BUFFERING ||
                (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0)
            )
        // Screen-off continuity: while the selected track is under explicit
        // lossless-first resolution, ExoPlayer reports not-playing (loader
        // blocked in runBlocking) and refresh() would downgrade the state â€”
        // releasing the service wake/wifi locks mid-resolve so a locked
        // screen stalls until unlock. Preserve the intended playing state.
        val rawPlaying = player.isPlaying
        val isBuffering = player.playWhenReady && (rawBuffering || (selectionIsResolving && previous.isBuffering))
        val isPlayingState = rawPlaying || (player.playWhenReady && selectionIsResolving && previous.isPlaying)
        // Never zero out a known duration when ExoPlayer briefly reports
        // TIME_UNSET (buffering / container not parsed yet): that reset froze
        // the bar at 0:00 and disabled seeking until the next event.
        val dur = effectiveDuration(player.duration, player, previous.durationMs)
        val currentMediaMatch = isCurrentMediaMatch(player, previous.current) || player.isPlaying
        val pos = if (!exclusiveUsbOutput.isActive() && player.playbackState != Player.STATE_IDLE && currentMediaMatch) {
            settleSeekPosition(player.currentPosition.coerceAtLeast(0L)).let { raw ->
                if (previous.positionMs == 0L && player.playbackState == Player.STATE_BUFFERING && raw > 1_500L) 0L else raw
            }
        } else {
            previous.positionMs
        }
        val hasExplicitPrevious = sameTrack && isExplicitQuality(previous.audioCodec, previous.bitDepth, previous.samplingRateKHz)
        val streamForCurrent = if (!hasExplicitPrevious) {
            player.currentMediaItem?.localConfiguration?.customCacheKey?.let(preparedStreams::get)
                ?: preparedStreams.values.firstOrNull { it.url == player.currentMediaItem?.localConfiguration?.uri?.toString() }
                ?: current?.let { findPreparedStreamFor(it, it.videoId, it.mediaIdKey() in losslessBypassMediaIds) }
        } else null

        val next = MusicPlayerState(
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
            pitch = player.playbackParameters.pitch,
            bitrateKbps = previous.bitrateKbps.takeIf { hasExplicitPrevious } ?: streamForCurrent?.bitrateKbps,
            audioCodec = previous.audioCodec.takeIf { hasExplicitPrevious } ?: streamForCurrent?.audioCodec,
            isLossless = (previous.isLossless && sameTrack) || (streamForCurrent?.isLossless == true),
            bitDepth = previous.bitDepth.takeIf { hasExplicitPrevious } ?: streamForCurrent?.bitDepth,
            samplingRateKHz = previous.samplingRateKHz.takeIf { hasExplicitPrevious } ?: streamForCurrent?.samplingRateKHz,
            sleepTimerRemainingMs = sleepTimerDeadlineMs?.minus(SystemClock.elapsedRealtime())?.coerceAtLeast(0),
            error = if (isPlayingState) null else previous.error,
        )
        // automix telemetry is written from another thread, take it from the
        // current value instead of `previous` so we don't write back a stale one
        _state.update { live -> next.withSubsystemStateFrom(live) }
        persistPlaybackSession()
        updateSignalPath()
    }

    companion object {
        private const val EXCLUSIVE_DRIFT_ABORT_PPM = 20_000.0
        private const val EXCLUSIVE_DRIFT_ABORT_TICKS = 2
        /**
         * Fallback target when the DAC descriptor lacks the source rate.
         * Resamples to a clock rate supported by the DAC (including 48kHz, 96kHz, etc.)
         * so playback continues flawlessly without the DAC going silent on clock mismatch.
         * Priority:
         * 1. Same-family integer divisor (e.g. 88.2 -> 44.1, 192 -> 96).
         * 2. Same-family integer multiple (e.g. 44.1 -> 88.2, 48 -> 96).
         * 3. Same clock family (44.1k or 48k family), closest to source rate.
         * 4. Closest supported rate overall (e.g. 44.1k -> 48kHz).
         * Returns null only if the source rate is already supported natively,
         * or if supportedHz is empty / sourceHz invalid.
         */
        fun selectExclusiveRateFallback(sourceHz: Int?, supportedHz: List<Int>): Int? {
            val src = sourceHz?.takeIf { it > 0 } ?: return null
            val supported = supportedHz.filter { it > 0 }.toSet()
            if (supported.isEmpty() || src in supported) return null

            // When a DAC lacks a high-rate 44.1 kHz crystal (88.2 / 176.4 / 352.8 / 705.6 kHz),
            // prefer its native 48 kHz-family hardware crystal (96 / 192 / 384 / 48 kHz) where
            // USB High-Speed 125us microframes have exact integer frame counts (12 / 24 / 48 / 6).
            if (src > 44100 && src % 44100 == 0) {
                val family48 = supported.filter { it % 48000 == 0 }
                if (family48.isNotEmpty()) {
                    val hiRes48 = family48.filter { it >= src }.minOrNull()
                        ?: family48.maxOrNull()
                    if (hiRes48 != null) return hiRes48
                }
            }

            // 1. Same-family integer divisor (e.g. 192 -> 96 or 48)
            val divisors = supported.filter { it < src && src % it == 0 }
            divisors.maxOrNull()?.let { return it }

            // 2. Same-family integer multiple (e.g. 44.1 -> 88.2, 48 -> 96)
            val multiples = supported.filter { it > src && it % src == 0 }
            multiples.minOrNull()?.let { return it }

            // 3. Same clock family (44.1k family vs 48k family), closest to source
            val is441Family = (src % 44100 == 0)
            val is48Family = (src % 48000 == 0)
            val sameFamily = supported.filter {
                (is441Family && it % 44100 == 0) || (is48Family && it % 48000 == 0)
            }
            sameFamily.minByOrNull { kotlin.math.abs(it - src) }?.let { return it }

            // 4. Closest supported rate overall (e.g. 44.1k -> 48kHz)
            return supported.minByOrNull { kotlin.math.abs(it - src) }
        }

        const val YOUTUBE_PROMOTE_BUDGET_MS = 12_000L
        const val MISSING_ARTIST_METADATA_TIMEOUT_MS = 1_200L
        /** Total cap for one YouTube fallback chain from fork, covering the
         *  promote wait plus every stacked re-resolve. Normal resolves take
         *  seconds; past this the track fails fast instead of spinning. */
        const val YT_RESOLVE_TOTAL_TIMEOUT_MS = 30_000L
        /** Cap for one mid-track re-resolve (the YT budget above plus margin). */
        const val RECOVERY_RESOLVE_TIMEOUT_MS = 35_000L
        /** Budget for a seamless same-file URL swap; the read-ahead buffer covers it. */
        const val SEAMLESS_URL_REFRESH_TIMEOUT_MS = 12_000L
        const val MAX_REFRESHED_URL_ENTRIES = 64
        const val DISCOVER_QUEUE_BATCH_SIZE = 16
        const val DISCOVER_QUEUE_REFILL_THRESHOLD = 8
        const val RADIO_QUEUE_BATCH_SIZE = 35
        const val RADIO_QUEUE_REFILL_THRESHOLD = 14
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
        /** Steady-state progress ticker cadence while no LastWave screen is visible. */
        const val BACKGROUND_TICKER_CADENCE_MS = 250L
        /** In-app volume is saved this long after the slider stops moving. */
        const val APP_VOLUME_PERSIST_DEBOUNCE_MS = 400L
        /** Podcast resume-position checkpoint cadence. */
        const val PODCAST_PROGRESS_INTERVAL_MS = 10_000L
        /** Played ratio at (or past) which an episode counts as finished. */
        const val PODCAST_FINISHED_RATIO = 0.95
        /** Masks pre-seek position reads with the seek target while ExoPlayer lands. */
        const val SEEK_SETTLE_WINDOW_MS = 400L
        /** Signal-path report + stream-health sampling cadence while playing. */
        const val SIGNAL_PATH_TICK_MS = 1_000L
        const val MAX_PLAYBACK_RETRIES = 3
        const val STARTUP_PROVIDER_RESOLVE_MS = 8_000L
        const val STARTUP_PROVIDER_PROBE_MS = 1_000L
        const val STARTUP_PROBE_BYTES = 16 * 1024L
        /** Fallback resolve kept alive after a provider wins, for instant failover. */
        const val STARTUP_FALLBACK_KEEPALIVE_MS = 15_000L
        const val MIN_BANDWIDTH_SAMPLE_BYTES = 256L * 1024
        const val MEASURED_BANDWIDTH_MAX_AGE_MS = 5 * 60_000L
        /** No quality swap this close to a planned AutoMix splice. */
        const val AUTOMIX_SWAP_GUARD_MS = 15_000L
        val NEUTRAL_MEDIA_HEADERS = mapOf(
            "Accept" to "audio/*,*/*;q=0.8",
            "Accept-Encoding" to "identity",
        )
        const val LOSSLESS_BACKGROUND_BUDGET_MS = 30_000L
        const val HOT_SWAP_PROBE_SPEED = 4f
        const val HOT_SWAP_PROBE_TIMEOUT_MS = 8_000L
        const val HOT_SWAP_READY_TIMEOUT_MS = 6_000L
        /** Queue items past the next one whose addon search is pre-warmed. */
        const val SEARCH_WARM_AHEAD = 4
        const val SEARCH_WARM_DELAY_MS = 1_500L
        /** Top search results warmed per settled result set (each also costs one server plan prep). */
        const val SEARCH_RESULT_WARM_COUNT = 2
        const val SEARCH_RESULT_WARM_DWELL_MS = 500L
        /** A first guess at what a seek on the standby takes, so it lands near the live position. */
        const val HOT_SWAP_SEEK_LEAD_MS = 300L
        const val HOT_SWAP_SEEK_LEAD_MAX_MS = 3_000L
        /** Speed offset of the muted deck while it closes a gap in about the span. */
        const val HOT_SWAP_NUDGE_MIN = 0.1f
        const val HOT_SWAP_NUDGE_MAX = 0.5f
        const val HOT_SWAP_NUDGE_SPAN_MS = 1_500f
        const val HOT_SWAP_NUDGE_MAX_MS = 750L
        const val HOT_SWAP_SETTLE_MS = 1_000L
        const val HOT_SWAP_PROBE_MIN_RUNWAY_MS = 12_000L
        const val AUDITION_MIN_BUFFER_MS = 12_000L
        const val AUDITION_TIMEOUT_MS = 10_000L
        const val HOT_SWAP_ALIGN_ROUNDS = 6
        const val HOT_SWAP_ALIGNED_MS = 10L
        /** Beyond this the two decks are heard as a double hit during the fade. */
        const val HOT_SWAP_MAX_DRIFT_MS = 15L
        const val HOT_SWAP_FADE_STEPS = 20
        const val HOT_SWAP_FADE_STEP_MS = 12L
        /** First keep-alive backoff after a transient stream failure. */
        const val RECOVERY_BACKOFF_BASE_MS = 1_000L
        /** Keep-alive backoff ceiling for repeated transient failures. */
        const val RECOVERY_BACKOFF_MAX_MS = 30_000L
        /** Consecutive failures for the same mediaId before auto-advance is
         *  allowed. First transient failure holds with tap-to-retry so a
         *  slow first OPUS resolve never looks like a 1-2s play-then-skip.
         *  Proven-dead (confirmed-unplayable / unsupported) still skips at once. */
        const val MIN_UNAVAILABLE_ATTEMPTS_BEFORE_SKIP = 2
        /** A failure at/after this position means the track audibly played,
         *  so it must hold with tap-to-retry instead of auto-skipping. */
        const val MIN_AUDIBLE_PLAYBACK_MS = 1_000L
        /** Tail window where a pinned READY+playWhenReady state counts as a
         *  missed natural advance and triggers the lossless-first watchdog. */
        const val END_OF_TRACK_STALL_THRESHOLD_MS = 750L
        /** Grace before a tail-parked window (pos pinned at the duration
         *  with playWhenReady, no ENDED) is force-advanced. Well above
         *  gapless handoffs (ms) and resolve hiccups, far below "stuck
         *  at -0:00 forever". */
        const val TAIL_PIN_TIMEOUT_MS = 5_000L
        /** Debounce so STATE_ENDED + ticker watchdog can't churn generations. */
        const val AUTO_ADVANCE_DEBOUNCE_MS = 3_000L
        /** Buffered lead kept for the crossfade standby: the next track is
         *  resolved and prepared this far ahead of the fade window, so the
         *  hand-off only ever has to wait for STATE_READY. */
        const val CROSSFADE_ARM_LEAD_MS = 10_000L
        /** Extra headroom on top of [CROSSFADE_ARM_LEAD_MS] for re-requesting a
         *  next-track stream whose signed URL is dead or inside the expiry
         *  margin. Provider modules / addons mint short-TTL FLAC URLs, so a
         *  resolve started at track start reads expired by the time the window
         *  opens; re-requesting at the window edge left no time to prepare and
         *  turned every such fade into a hard cut. */
        const val CROSSFADE_PREP_REFRESH_LEAD_MS = 30_000L
        /** Rate limit for the per-tick crossfade decision log. */
        const val CROSSFADE_LOG_THROTTLE_MS = 5_000L
        /** Minimum gap between next-track resolve kicks for the same item. */
        const val CROSSFADE_RESOLVE_RETRY_MS = 8_000L
        /** Poll cadence used while the downloader yields to an armed standby. */
        const val CURRENT_TRACK_CACHE_STANDBY_YIELD_MS = 500L
        /** UI-playing but ExoPlayer frozen (pos + buffer) this long means a
         *  silent window, not slow loading â€” legit rebuffers advance the
         *  buffer and reset the clock. Well above normal hitches, far below
         *  a full silent track. */
        const val RENDER_STALL_TIMEOUT_MS = 8_000L
        /** Rendering with an advancing position yet no active music stream
         *  this long means gated output, not a startup gap (those last a
         *  second or two). User-muted-to-zero still counts as active, so
         *  this never fires on a deliberately silent phone. */
        const val INAUDIBLE_TIMEOUT_MS = 10_000L
        /** Renderer-reset, then re-resolve. Beyond that the error/unavailable
         *  machinery owns the window â€” never loop recovery forever. */
        const val MAX_SILENT_RECOVERIES = 2
        const val PLAYBACK_RETRY_BASE_DELAY_MS = 350L
        const val PLAYBACK_RETRY_JITTER_MS = 250L
        const val NEXT_TRACK_PREFETCH_BYTES = 128L * 1024
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
        )
        val SLEEP_TIMER_MINUTES = intArrayOf(0, 15, 30, 60)
    }
}

internal fun PlayableTrack.withYoutubeArtwork(): PlayableTrack =
    if (artworkUrl.isNullOrBlank() && !videoId.isNullOrBlank()) {
        copy(artworkUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
    } else this

internal fun PlayableTrack.toMediaItem(resolved: MusicPlayer.ResolvedStream? = null): MediaItem {
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
                .setExtras(
                    android.os.Bundle().apply {
                        val d = durationMs ?: resolved?.durationMs
                        if (d != null && d > 0L) putLong("durationMs", d)
                        internalTrackId?.takeIf { it.isNotBlank() }?.let { putString("internalTrackId", it) }
                        putString("mediaType", mediaType.name)
                        showId?.takeIf { it.isNotBlank() }?.let { putString("showId", it) }
                    }
                )
                .build(),
        )
        .build()
}

internal fun com.lastwave.app.playback.resolve.StreamCache.toPlayerStream(): MusicPlayer.ResolvedStream {
    val ytStream = toYouTubeAudioStream()
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
    val headers = buildMap {
        putAll(ytStream.requestHeaders)
        if (!ytStream.userAgent.isNullOrBlank()) {
            put("User-Agent", ytStream.userAgent)
        }
        put("Origin", "https://music.youtube.com")
        put("Referer", "https://music.youtube.com/")
    }
    return MusicPlayer.ResolvedStream(
        url = ytStream.url,
        mimeType = cleanMime,
        bitrateKbps = trueBitrate,
        audioCodec = codec,
        cacheKey = ytStream.mediaCacheKey,
        qualityTier = LosslessMusicApi.QUALITY_YOUTUBE,
        requestHeaders = headers,
        isLossless = false,
        samplingRateKHz = ytStream.sampleRateHz?.let { it / 1_000.0 },
        youtubeCandidate = ytStream,
        durationMs = ytStream.durationMs?.takeIf { it > 0 },
        expiresAtEpochMs = ytStream.expiresAtEpochMs,
        userAgent = ytStream.userAgent,
    )
}

internal fun PlayableTrack.mediaIdKey(): String = when {
    !playbackUrl.isNullOrBlank() -> "local:${playbackUrl}"
    !videoId.isNullOrBlank() -> videoId
    else -> "query:${artist.lowercase()}|${title.lowercase()}"
}

fun PlayableTrack.isLocalTrack(): Boolean =
    playbackUrl?.let { url ->
        (url.startsWith("/") || url.startsWith("content://") || url.startsWith("file://")) &&
            !url.contains("tidal_mpd") && !url.contains("segdrm_mpd")
    } == true || (mediaIdKey().startsWith("local:") && !mediaIdKey().contains("tidal_mpd") && !mediaIdKey().contains("segdrm_mpd"))

internal fun MediaItem.toPlayableTrack(): PlayableTrack {
    val uriStr = localConfiguration?.uri?.toString()
    val isCachedManifest = uriStr?.contains("tidal_mpd") == true ||
        uriStr?.contains("segdrm_mpd") == true ||
        uriStr?.endsWith(".mpd") == true
    val localUri = when {
        mediaId.startsWith("local:") -> {
            when {
                uriStr?.startsWith("content://") == true || uriStr?.startsWith("file://") == true || uriStr?.startsWith("/") == true -> uriStr
                else -> mediaId.removePrefix("local:")
            }
        }
        uriStr?.startsWith("content://") == true -> uriStr
        uriStr?.startsWith("file://") == true && !isCachedManifest -> uriStr
        else -> null
    }
    val dur = mediaMetadata.extras?.getLong("durationMs")?.takeIf { it > 0L }
    val mediaType = mediaMetadata.extras?.getString("mediaType")
        ?.let { name -> runCatching { MediaType.valueOf(name) }.getOrNull() }
        ?: MediaType.SONG
    return PlayableTrack(
        title = mediaMetadata.title?.toString().orEmpty().ifBlank { "Unknown track" },
        artist = mediaMetadata.artist?.toString().orEmpty().ifBlank { "Unknown artist" },
        album = mediaMetadata.albumTitle?.toString(),
        artworkUrl = mediaMetadata.artworkUri?.toString(),
        videoId = mediaId.takeUnless { it.startsWith("query:") || it.startsWith("local:") },
        internalTrackId = mediaMetadata.extras?.getString("internalTrackId"),
        playbackUrl = localUri,
        playbackMimeType = localConfiguration?.mimeType,
        durationMs = dur,
        mediaType = mediaType,
        showId = mediaMetadata.extras?.getString("showId"),
        trackNumber = mediaMetadata.trackNumber ?: mediaMetadata.extras?.getInt("trackNumber")?.takeIf { it > 0 },
    )
}

fun GeneratedTrack.toPlayableTrack(): PlayableTrack {
    if (com.lastwave.app.data.generate.isLocalAudioPath(url)) {
        // Local songs: imported file, played straight from app storage.
        return PlayableTrack(
            title = name,
            artist = artist,
            album = album,
            artworkUrl = artworkUrl,
            playbackUrl = url,
            playbackMimeType = com.lastwave.app.data.generate.LocalAudioFormats.mimeFor(url),
        )
    }
    val videoId = youtubeVideoIdOrNull()
    return PlayableTrack(
        title = name,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl ?: videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
        videoId = videoId,
    )
}

fun YouTubeMusicTrack.toPlayableTrack(): PlayableTrack =
    PlayableTrack(
        title = title.trim(),
        artist = artist.trim(),
        album = album?.trim(),
        artworkUrl = artworkUrl,
        videoId = videoId.takeIf(String::isNotBlank),
        durationMs = durationSeconds?.takeIf { it > 0 }?.times(1_000L),
    )

internal fun PlayableTrack.queueKey(): String =
    PureRadioQueue.queueKey(this)

/**
 * Samsung One UI ships vendor FLAC decoders (c2.sec.flac.decoder,
 * OMX.SEC.FLAC.Decoder, OMX.Exynos.FLAC.Decoder) that decode 24-bit hi-res
 * FLAC to packed 24-bit PCM while failing to advertise
 * KEY_PCM_ENCODING = ENCODING_PCM_24BIT_PACKED in the output MediaFormat.
 * The 3-byte samples are then consumed as 2-byte: buffers drain exactly
 * 3/2 faster â€” chipmunk pitch at ~1.5x speed â€” and broken Left/Right byte
 * boundaries surface as harsh digital noise. Only FLAC is affected;
 * Opus/AAC/MP3 play normally, which is why the fault isolated to Samsung
 * hardware playing lossless files.
 *
 * Demotes Samsung vendor decoders to the end of the FLAC codec list so the
 * reliable reference AOSP software decoder (c2.android.flac.decoder) wins.
 * Every other mime type keeps Android's default codec order untouched.
 */
@OptIn(UnstableApi::class)
internal val accurateAudioMediaCodecSelector =
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
internal fun audioDecoderPriority(name: String): Int {
    val lower = name.lowercase()
    return if (lower.contains("sec.") || lower.contains("exynos") ||
        lower.contains("mtk") || lower.contains("mediatek") || lower.contains("omx.mtk.") ||
        lower.contains("c2.mtk.") || lower.contains("coloros")) 1 else 0
}

internal fun PlayableTrack.searchQueueTitleKey(): String = title
    .lowercase()
    .replace(SEARCH_TITLE_VARIANT, " ")
    .replace(SEARCH_TITLE_NON_CHARACTER, "")

internal val SEARCH_TITLE_VARIANT = Regex(
    """\s*[\[(][^)\]]*\b(?:official|video|audio|lyrics?|cover|karaoke|remaster(?:ed)?|live|version|edit|mix|slowed|reverb)[^)\]]*[])]""",
    RegexOption.IGNORE_CASE,
)
internal val SEARCH_TITLE_NON_CHARACTER = Regex("[^\\p{L}\\p{N}]+")
