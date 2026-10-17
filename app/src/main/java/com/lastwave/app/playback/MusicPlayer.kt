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
import android.media.MediaCodecList
import android.os.Handler
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.ts.AdtsExtractor
import com.lastwave.app.data.plugin.SegmentedMpdBuilder
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
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YOUTUBE_WEB_USER_AGENT
import com.lastwave.app.data.lossless.LosslessAudioStream
import com.lastwave.app.data.lossless.LosslessMusicApi
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.plugin.ModuleDrmFactory
import com.lastwave.app.data.plugin.SegmentedDashBridge
import com.lastwave.app.data.plugin.stableCacheKey
import com.lastwave.app.data.repository.RelatedGraphRepository
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
    /** Stable library id when one exists. Never a title or artist string. */
    val internalTrackId: String? = null,
    val playbackUrl: String? = null,
    val playbackMimeType: String? = null,
    val durationMs: Long? = null,
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
    val durationMs: Long = 0L,
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
    val shuffleEnabled: Boolean = false,
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
    private val relatedGraph: RelatedGraphRepository,
    private val losslessMusicApi: LosslessMusicApi,
    private val moduleDrmFactory: ModuleDrmFactory,
    private val moduleManager: com.lastwave.app.data.plugin.ModuleManager,
    private val offlineLicense: com.lastwave.app.data.plugin.ModuleOfflineLicense,
    private val segBridge: SegmentedDashBridge,
    private val settingsPreferences: SettingsPreferences,
    private val equalizerPreferences: EqualizerPreferences,
    private val discoverRepository: DiscoverRepository,
    private val playlistRepository: PlaylistRepository,
    private val nativeAudioEngine: dagger.Lazy<NativeAudioEngine>,
    private val audioEffectsEngine: AudioEffectsEngine,
    private val applicationScope: CoroutineScope,
    private val downloadedTrackDao: dagger.Lazy<com.lastwave.app.data.local.db.DownloadedTrackDao>,
    private val usbDacMonitor: UsbDacMonitor,
    private val exclusiveUsbOutput: ExclusiveUsbOutput,
    private val songPlayStatsRepository: dagger.Lazy<com.lastwave.app.data.repository.SongPlayStatsRepository>,
    private val internalDirectOutput: InternalDirectOutput,
) {
    private val appContext = context.applicationContext
    /**
     * Playback resolver is the only path allowed to publish a YouTube URL for
     * the selected track. Prefetch may fill the next track's cache entry and
     * must not replace a playback result.
     */
    private val streamResolver = StreamResolveCoordinator(
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
            .setConstantBitrateSeekingAlwaysEnabled(true)
            .setMp3ExtractorFlags(
                Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING or
                    Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING
            )
            .setAdtsExtractorFlags(
                AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING
            )
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
    /**
     * Keep-alive for a stream that died mid-track: re-resolves in place with
     * backoff while the track stays buffered, instead of parking the player on
     * "Unable to resolve" (mid-song stop). Cancelled by any explicit
     * navigation, pause or stop through [cancelPendingPlaybackResolution].
     */
    private var playbackRecoveryJob: Job? = null
    /** Set by a play-button tap during a recovery backoff to retry immediately. */
    @Volatile private var recoveryRetryNow = false
    private val playRequestGeneration = AtomicLong()
    /** Work that must not compete with the first audio buffer. Runs on READY. */
    private var startupFollowUp: (() -> Unit)? = null
    private var activeUpgradeJob: Job? = null
    private var activeUpgradeMediaId: String? = null
    private var activeUpgradeDeferred: Deferred<ResolvedStream?>? = null
    @Volatile private var swappingMediaId: String? = null
    private var queueEnrichmentJob: Job? = null
    private var recommendationJob: Job? = null
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
    /**
     * True once the standby holds the upcoming queue and is preparing it, i.e.
     * [standbyIndex] / [standbyQueue] are live. Cleared by [cancelCrossfade]
     * and by the hand-off.
     */
    @Volatile private var crossfadeStandbyArmed = false
    /**
     * True while an armed standby has not reached STATE_READY yet, so the
     * current-track downloader yields the connection. Only large lossless files
     * ran long enough to compete for bandwidth, which is why this never showed
     * up with Opus. Narrower than [crossfadeStandbyArmed] on purpose: once the
     * standby is buffered, background caching of the outgoing track resumes.
     */
    @Volatile private var crossfadeStandbyPreparing = false
    /** Spacing for next-track resolve kicks, so a failing module is not hammered. */
    private var lastCrossfadeResolveKickMs = 0L
    private var lastCrossfadeResolveIndex = C.INDEX_UNSET
    private var lastCrossfadeLogKey: String? = null
    private var lastCrossfadeLogAtMs = 0L
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
                shuffleEnabled = it.shuffleEnabled,
            )
        }
        .distinctUntilChanged()
        .stateIn(applicationScope, SharingStarted.Eagerly, PlaybackChromeState())
    val progressState: StateFlow<PlaybackProgressState> = state
        .map {
            val dur = if (it.durationMs > 0L) it.durationMs else (it.current?.durationMs ?: findKnownDuration(it.current) ?: 0L)
            PlaybackProgressState(positionMs = it.positionMs, durationMs = dur)
        }
        .distinctUntilChanged()
        .stateIn(applicationScope, SharingStarted.Eagerly, PlaybackProgressState())

    private var errorRetryCount = 0
    private var retryMediaId: String? = null
    private val losslessBypassMediaIds = ConcurrentHashMap.newKeySet<String>()
    // Written by the settings collector, read on the main thread.
    @Volatile
    private var bitPerfectEnabled = false
    /** PCM rate reported by the decoder. Wins over a catalog tag that guessed 44.1. */
    @Volatile
    private var decodedSampleRateHz = 0
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
    // ---- App-owned audio focus -------------------------------------------------
    // The player is built with handleAudioFocus=false (see createPlayer), so
    // Media3 never answers a focus change itself. Its automatic handling parks
    // playback in a suppression state — playWhenReady stays true, nothing
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
    private val audioSinks = CopyOnWriteArrayList<NativeProcessingAudioSink>()
    /** True while the usbdevfs exclusive session owns output. */
    @Volatile private var usbExclusiveSinkActive = false
    @Volatile private var usbExclusivePrefEnabled = false
    /** Raw "USB Exclusive Output" toggle, before the Bit-Perfect gate. */
    @Volatile private var usbExclusiveRawPref = false
    /** Fork: "Sortie directe interne" toggle (only effective with Bit-Perfect on). */
    @Volatile private var internalDirectPrefEnabled = false
    /** Availability of the internal exclusive endpoint (settings + signal path). */
    val internalDirectAvailability: StateFlow<InternalDirectAvailability> = internalDirectOutput.availability
    val internalDirectSession: StateFlow<InternalDirectSession?> = internalDirectOutput.session

    /** Re-checks what the current output accepts (settings / signal-path page opened). */
    fun refreshInternalDirectAvailability() = internalDirectOutput.refreshAvailability(probe = true)

    fun internalDirectUnderruns(): Long = internalDirectOutput.underruns()

    val isInternalDirectPrefEnabled: Boolean get() = bitPerfectEnabled && internalDirectPrefEnabled
    /** System audio-effects mode pref (Settings -> Experimental, default OFF). */
    @Volatile private var systemEffectsModePref = false
    /** Effective mode: pref ON and on a mixer route (bypass routes suspend). */
    @Volatile private var systemEffectsEffective = false
    private val systemEffectsBridge by lazy { SystemEffectsBridge(appContext) }
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

    private fun stagePendingQuality(mediaId: String, stream: ResolvedStream) {
        pendingQualities[mediaId] = stream
    }

    /** Stashed stream for [mediaId], clearing the stash so it is used once. */
    private fun consumePendingQuality(mediaId: String): ResolvedStream? {
        return pendingQualities.remove(mediaId)
    }

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

    /**
     * Media id of the item that produced the currently published
     * [MusicPlayerState.durationMs]. A published duration is only reusable while
     * this still matches the item playing: without it, the outgoing track's
     * length became the incoming track's seek-bar ceiling *and* its crossfade
     * timer, which handed off to the next item seconds into a full-length track.
     * Written and read on the main thread alongside the state it describes.
     */
    private var durationOwnerMediaId: String? = null

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
                PlaybackStartupLog.prepareEnd()
                android.util.Log.w("MusicPlayer", "Track BUFFERING / STALLED: '${_state.value.current?.title}' at ${player.currentPosition}ms (buffered=${player.bufferedPosition}ms)")
            }
            if (playbackState == Player.STATE_READY) {
                PlaybackStartupLog.prepareEnd()
                PlaybackStartupLog.ready()
                onMain { runStartupFollowUp() }
            }
            // Natural-end safety net: ExoPlayer auto-advances while a next
            // window exists, but when the queue truly ends — or the next
            // placeholder failed to open and ExoPlayer gave up — playback
            // parks in STATE_ENDED with isPlaying=false and never moves.
            // Users saw "song is over, never skips to next". Force a
            // lossless-first advance; the call is debounced and a no-op
            // when ExoPlayer already moved on.
            if (playbackState == Player.STATE_ENDED) {
                if (player.repeatMode == Player.REPEAT_MODE_ONE || _state.value.repeatMode == Player.REPEAT_MODE_ONE) {
                    onMain {
                        runCatching {
                            player.seekTo(player.currentMediaItemIndex, 0L)
                            player.prepare()
                            player.play()
                        }
                        resetPlayhead(0L, _state.value.current?.mediaIdKey())
                        _state.update { it.copy(positionMs = 0L, isPlaying = true) }
                    }
                } else {
                    android.util.Log.i("MusicPlayer", "Playback STATE_ENDED for '${_state.value.current?.title}' -> advancing to next")
                    onMain { handleNaturalTrackEnd() }
                }
            }
            refresh(player)
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            outgoingPlayer?.playWhenReady = isPlaying
            if (isPlaying) {
                unavailableSkipJob?.cancel()
                unavailableSkipJob = null
                player.currentMediaItem?.mediaId?.let {
                    unavailableMediaIds.remove(it)
                    unavailableAttemptCounts.remove(it)
                }
            }
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, playWhenReadyChangeReason: Int) {
            // Hold focus exactly while playback is intended: requesting it
            // anywhere else just fights the other apps that own focus.
            if (playWhenReady) requestMediaAudioFocus() else abandonMediaAudioFocus()
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (isCasting) return
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
                else -> if (rawPos > 1_500L) 0L else rawPos
            }
            lastSeekTargetMs = -1L
            playheadPosMs = targetPos
            playheadWallMs = SystemClock.elapsedRealtime()
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
                // The stream we staged for this exact mediaId, if any. Taken
                // before the reset so the badge survives it.
                val stagedQuality = consumePendingQuality(mediaItem.mediaId)
                    ?: currentTrack.mediaIdKey().let(::consumePendingQuality)
                    ?: currentTrack.videoId?.let(::consumePendingQuality)
                val stream = stagedQuality
                    ?: mediaItem.localConfiguration?.customCacheKey?.let(preparedStreams::get)
                    ?: preparedStreams.values.firstOrNull { it.url == mediaItem.localConfiguration?.uri?.toString() }
                    ?: findPreparedStreamFor(currentTrack, currentTrack.videoId, currentTrack.mediaIdKey() in losslessBypassMediaIds)
                _state.update {
                    it.copy(
                        current = currentTrack,
                        currentIndex = currentIndex,
                        positionMs = targetPos,
                        bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L),
                        durationMs = effectiveDuration(player.duration, player, it.durationMs),
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
                    ?: mediaItem.localConfiguration?.customCacheKey?.let(preparedStreams::get)
                    ?: preparedStreams.values.firstOrNull { it.url == mediaItem.localConfiguration?.uri?.toString() }
                    ?: findPreparedStreamFor(currentTrack, currentTrack.videoId, currentTrack.mediaIdKey() in losslessBypassMediaIds)
                val isLocal = currentTrack.isLocalTrack() || mediaItem.mediaId.startsWith("local:")
                val needsResolution = mediaItem.localConfiguration?.uri?.scheme == "lastwave" ||
                    (!isLocal && (prepared == null || prepared.isExpired()))
                if (needsResolution) {
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
            val failedLosslessStream = !failedLocalStream && (rejectedStream?.isLossless
                ?: customCacheKey?.startsWith("lossless:")
                ?: isCachedManifest
                ?: _state.value.isLossless)
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

            if (failedLosslessStream) {
                if (failedMediaId != null && (errorRetryCount > 0 || !isRetryablePlaybackFailure(error))) {
                    losslessBypassMediaIds += failedMediaId
                }
            } else if (!failedLocalStream && !videoId.isNullOrBlank()) {
                if (rejectedYouTubeCandidate == null) innerTube.invalidateCache(videoId)
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
                            // A dead stream must be replaced now: the fast
                            // module walk used to eat the whole throttle
                            // window and land on "Unable to resolve".
                            losslessBudget = LosslessBudget.Disabled,
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
                        if (playedAudibly &&
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
                            allowAutoSkip = retryProvenUnplayable ||
                                shouldAutoSkipForPlaybackError(error, playedAudibly, confirmedUnplayable),
                        )
                    }
                }
                _state.update { it.copy(isBuffering = true, error = null) }
                return
            }

            // Retries weren't even attempted (non-retryable error, or already
            // exhausted on a previous one). A track that was audibly playing
            // still goes through the keep-alive loop rather than stopping.
            if (playedAudibly &&
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
            scheduleUnavailableMediaSkip(failedIndex, failedMediaId, failure = error, allowAutoSkip = shouldAutoSkipForPlaybackError(error, playedAudibly, confirmedUnplayable))
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
                    else -> _state.value.current?.takeIf { it.videoId.isNullOrBlank() }
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
                            // Loader thread owns the audio pipeline: never park
                            // it on a module round trip. A slow walk here is a
                            // multi-second hole in playback, not a freebie.
                            resolveTrackAudioStreamWithRetry(
                                track,
                                track.videoId,
                                allowLossless = !bypassLossless,
                                losslessBudget = LosslessBudget.Disabled,
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
                    .withRequestHeaders(resolvedPlaceholder.requestHeaders)
                stream != null -> dataSpec.withRequestHeaders(stream.requestHeaders)
                else -> dataSpec
            }
        }
        val baseLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ if (handleAudioFocus) 45_000 else 15_000,
                /* maxBufferMs = */ if (handleAudioFocus) 120_000 else 30_000,
                /* bufferForPlaybackMs = */ if (handleAudioFocus) 250 else 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ if (handleAudioFocus) 750 else 2_500,
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
                    // The crossfade standby must not share the DAC. Its
                    // volume is 0, and a second configure() retunes the clock
                    // to the next track (44.1 PCM written into a 96 kHz alt).
                    exclusiveUsb = if (handleAudioFocus) exclusiveUsbOutput else null,
                    // Same rule as USB: the crossfade standby never claims the endpoint.
                    internalDirect = if (handleAudioFocus) internalDirectOutput else null,
                ).also { sink ->
                    sink.onConfiguredFormat = { rateHz, _, _ ->
                        // The crossfade standby arms early and configures its
                        // sink while the OUTGOING track is still the live one.
                        // Only the active player may publish sample rate and
                        // re-settle bit-perfect/system volume, otherwise the
                        // next track's rate lands on the current track's pill.
                        if (handleAudioFocus && (activePlayer == null || (playerDelegate.isInitialized() && activePlayer === playerDelegate.value))) {
                            onDecodedPcmFormatConfigured(rateHz)
                        }
                    }
                    sink.bitDepthHintProvider = {
                        val s = _state.value
                        val hintRate = s.samplingRateKHz
                            ?: (if (decodedSampleRateHz > 0) decodedSampleRateHz / 1000.0 else 0.0)
                        // Output sizing biases UP safely: an unknown depth must not size a
                        // 16-bit output that would truncate a true 24-bit source,
                        // so only a genuinely reported depth is used as-is and
                        // everything else falls through to the up-biasing
                        // rate guess. A reported 16 is trusted even at >48kHz —
                        // 16/96 and 16/192 are real, and the sources no longer
                        // send a placeholder 16.
                        val hintKnown = s.bitDepth?.takeIf { it > 0 }
                        parseQualityFromCodec(s.audioCodec)?.substringBefore('/')?.toIntOrNull()
                            ?: hintKnown
                            ?: inferBitDepth(s.copy(bitDepth = hintKnown))
                            ?: run {
                                when {
                                    hintRate > 192.0 -> 32
                                    hintRate > 48.0 -> 24
                                    else -> null
                                }
                            }
                    }
                    val isSpatial = isSpatialAudioCodec(_state.value.audioCodec)
                    sink.setBitPerfectRequested(!isSpatial && bitPerfectEnabled)
                    sink.syncExclusiveUsb(handleAudioFocus && exclusiveUsbWanted())
                    sink.syncInternalDirect(handleAudioFocus && !isSpatial && internalDirectWanted())
                    audioSinks.add(sink)
                    runCatching {
                        routedDacDeviceId?.let { id -> findOutputDevice(id)?.let(sink::setPreferredDevice) }
                    }
                }
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
                DefaultMediaSourceFactory(appContext, extractorsFactory).setDataSourceFactory(resolving)
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
                        // Crossfade standby or background players must NEVER publish format or rate to active state.
                        if (!handleAudioFocus || (activePlayer != null && this@apply !== activePlayer)) return
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
                            onMain { applyDacRoutingFor(48000) }
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
                    }
                })
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
                    } ?: _state.value.repeatMode
                    // A persisted tempo stretch resamples by definition — it
                    // must not survive into a Bit-Perfect session.
                    val restoredSpeed = restoredSession.speed.coerceIn(0.5f, 2f)
                    restoredPlayer.setPlaybackSpeed(if (bitPerfectEnabled) 1f else restoredSpeed)
                    restoredPlayer.pause()
                } else {
                    restoredPlayer.repeatMode = _state.value.repeatMode
                    restoredPlayer.shuffleModeEnabled = _state.value.shuffleEnabled
                }
            }
    }

    private val player: ExoPlayer
        get() = activePlayer ?: playerDelegate.value

    init {
        setupSpatializerListener()
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
                    val usbAlive = !exclusiveUsbOutput.isActive() || exclusiveUsbOutput.isStreamingAudio()
                    // Seek-bar clock keeps running while the track is
                    // BUFFERING (e.g. TIME_UNSET at track start while the
                    // container parses): the notification/system session
                    // extrapolates position from speed and keeps moving, so
                    // freezing the wall clock here pinned the main-player bar
                    // at 0:00 while the notification looked normal. Paused /
                    // ended states still freeze via isPlaying=false.
                    val playingNow = _state.value.isPlaying &&
                        !exclusiveUsbOutput.isPaused() &&
                        usbAlive
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

                            if (updateCrossfade(pos)) {
                                cadenceMs = 60L
                            } else {

                    // Steady-state ensure: if the track-start preload failed or
                    // was cancelled, don't wait for the 30s second-chance —
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
                systemEffectsModePref = settings.systemEffectsMode
                updateBitPerfectState()
                recomputeUsbExclusivePref()
                if (bitPerfectEnabled && settings.isStudioMasterClarityEnabled) {
                    // Self-heal: both must never be on — Bit-Perfect wins.
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
            cancelCrossfade()
            losslessBypassMediaIds.clear()
            errorRetryCount = 0
            retryMediaId = null
            if (playerDelegate.isInitialized()) {
                player.stop()
                player.clearMediaItems()
            }
            resetPlayhead(startPositionMs, selectedTrack.mediaIdKey())
            _state.value = MusicPlayerState(
                current = selectedTrack,
                queue = tracks,
                currentIndex = selectedIndex,
                sourceLabel = sourceLabel,
                isEndlessQueue = endlessDiscover || radioQueueActive,
                isBuffering = true,
                isPlaying = true,
                positionMs = startPositionMs.coerceAtLeast(0L),
                // Seed duration so the main-player seek bar has a denominator
                // from t=0 when one is known (repeat plays, enriched metadata,
                // knownDurations). Without this the bar sat at 0:00/disabled
                // until ExoPlayer parsed the container (TIME_UNSET -> READY),
                // while the notification extrapolated via speed and looked fine.
                durationMs = selectedTrack.durationMs ?: findKnownDuration(selectedTrack) ?: 0L,
                shuffleEnabled = if (playerDelegate.isInitialized()) player.shuffleModeEnabled else startShuffled,
                repeatMode = _state.value.repeatMode,
            )
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
                    resetPlayhead(startPositionMs, selectedTrack.mediaIdKey())
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
                    PlaybackStartupLog.prepareStart()
                    player.prepare()
                    player.play()
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
                            player.play()
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
        val isLocalTrack = currentItem?.mediaId?.startsWith("local:") == true ||
            _state.value.current?.isLocalTrack() == true
        val needsResolution = currentItem?.localConfiguration?.uri?.scheme == "lastwave" ||
            currentPrepared?.isExpired() == true ||
            (!isLocalTrack && currentPrepared == null) ||
            player.playerError != null ||
            _state.value.error != null
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
        player.play()
    }

    fun pause() {
        // A deliberate pause supersedes a focus-induced one, so a later
        // AUDIOFOCUS_GAIN must not resurrect playback the user stopped.
        if (!pausingForAudioFocus) focusPauseAwaitingRecovery = false
        cancelPendingPlaybackResolution()
        onMain {
            exclusiveUsbOutput.setPaused(true)
            if (isCasting) castPlayback?.pause()
            if (playerDelegate.isInitialized()) player.pause()
            _state.update { it.copy(isPlaying = false, isBuffering = false) }
            persistPlaybackSession()
        }
    }

    /**
     * Asks the platform for media audio focus. Best-effort: a refusal is logged,
     * not fatal — the user explicitly asked for playback and the system still
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
        pausingForAudioFocus = true
        focusPauseAwaitingRecovery = autoRestore && playing
        if (playing) pause()
        pausingForAudioFocus = false
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
        android.util.Log.i("MusicPlayer", "Audio focus regained — resuming interrupted playback")
        resume()
    }

    /** Keeps playing at a reduced level while the holder asked for a duck. */
    private fun setDucked(ducked: Boolean) {
        if (isCasting || !playerDelegate.isInitialized()) return
        if (ducked) {
            if (unduckedVolume != null) return
            val current = runCatching { player.volume }.getOrDefault(1f)
            if (current <= AudioFocusPolicy.DUCK_VOLUME) return
            unduckedVolume = current
            runCatching { player.volume = AudioFocusPolicy.DUCK_VOLUME }
        } else {
            val level = unduckedVolume ?: return
            unduckedVolume = null
            runCatching { player.volume = level }
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
    fun seekTo(positionMs: Long) = onMain {
        if (isCasting) {
            castPlayback?.seek(positionMs.coerceAtLeast(0))
            return@onMain
        }
        cancelCrossfade()
        val target = positionMs.coerceAtLeast(0)
        val now = SystemClock.elapsedRealtime()
        lastSeekTargetMs = target
        lastSeekAtElapsedMs = now
        playheadPosMs = target
        playheadWallMs = now
        exclusiveUsbOutput.noteSeek(target * 1_000L)
        player.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    private fun resetPlayhead(startPosMs: Long = 0L, key: String? = null) {
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
        val now = SystemClock.elapsedRealtime()
        val key = _state.value.current?.let { track ->
            track.videoId?.takeIf { it.isNotBlank() } ?: "${track.title}|${track.artist}"
        }
        val seekAge = now - lastSeekAtElapsedMs
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
            // IDLE, or the player is still holding the previous item while the
            // queue has already moved on. Nothing is rendering, so the playhead
            // must not fall through to the wall clock below: that clock keeps
            // counting whenever _state.isPlaying is true, and it is held true on
            // purpose across resolve/buffer windows so a locked screen does not
            // drop its wake lock. The two together made the seek bar run
            // forward through a stall or a track transition - the "playing while
            // buffering" report. Freeze here instead; the real position resumes
            // as soon as the item matches again.
            //
            // USB-exclusive output is excluded above and keeps its own stream
            // clock, so bit-perfect playback is untouched by this.
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

    @MainThread
    private fun cancelCrossfade() {
        crossfadeStandbyArmed = false
        crossfadeStandbyPreparing = false
        lastCrossfadeLogKey = null
        if (!playerDelegate.isInitialized()) return
        outgoingPlayer = null
        val standby = if (player === secondaryPlayer) playerDelegate.value else secondaryPlayer
        standby?.stop()
        standby?.clearMediaItems()
        standbyQueue = emptyList()
        standbyIndex = C.INDEX_UNSET
        player.volume = 1f
    }

    /**
     * The standby player without creating one. Never forces the lazy primary:
     * when [player] IS the secondary the primary was necessarily built first.
     */
    private fun currentStandby(): ExoPlayer? =
        if (player === secondaryPlayer) {
            playerDelegate.value.takeIf { playerDelegate.isInitialized() }
        } else {
            secondaryPlayer
        }

    /** Builds the standby on first crossfade; afterwards it is reused and swapped. */
    @MainThread
    private fun standbyOrCreate(): ExoPlayer = currentStandby() ?: run {
        val engine = NativeAudioEngine(settingsPreferences, equalizerPreferences, applicationScope)
        val effects = AudioEffectsEngine(equalizerPreferences, settingsPreferences, applicationScope)
        secondaryNativeEngine = engine
        secondaryEffects = effects
        createPlayer({ engine }, effects, false).also { secondaryPlayer = it }
    }

    /**
     * Installs the current queue on the standby and lets it prepare, so the next
     * track spends the rest of this one buffering. Runs as soon as the next item
     * is resolved instead of at the edge of the fade window: with short-TTL
     * module/addon FLAC URLs the old inside-the-window arm left no time to
     * resolve, prepare and buffer, and every such fade became a hard cut.
     * Re-armed automatically when the item at [nextIndex] is replaced (a TTL
     * refresh swaps it), and torn down by [cancelCrossfade] on every queue edit.
     */
    @MainThread
    private fun armCrossfadeStandby(nextIndex: Int, nextItem: MediaItem) {
        val standby = standbyOrCreate()
        standbyQueue = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        standbyIndex = nextIndex
        standby.volume = 0f
        standby.setAudioAttributes(player.audioAttributes, false)
        standby.pause()
        standby.setMediaItems(standbyQueue, nextIndex, 0L)
        standby.prepare()
        crossfadeStandbyArmed = true
        crossfadeStandbyPreparing = true
        logCrossfadeDecision(
            "armed",
            "index=$nextIndex track='${nextItem.mediaMetadata.title}'",
        )
    }

    /**
     * One line per crossfade decision, rate-limited per key. Only the hand-off
     * used to log, which is why a missed fade was indistinguishable from a
     * disabled one; the armed/waiting states repeat on every 60ms tick and must
     * not flood logcat.
     */
    @MainThread
    private fun logCrossfadeDecision(key: String, detail: String) {
        val now = SystemClock.elapsedRealtime()
        if (lastCrossfadeLogKey == key && now - lastCrossfadeLogAtMs < CROSSFADE_LOG_THROTTLE_MS) return
        lastCrossfadeLogKey = key
        lastCrossfadeLogAtMs = now
        android.util.Log.i("MusicPlayer", "Crossfade[$key]: $detail")
    }

    @MainThread
    private fun updateCrossfade(positionMs: Long): Boolean {
        if (!crossfadeEnabled || bitPerfectEnabled) return false
        outgoingPlayer?.let { outgoing ->
            // Drive the blend off the incoming (active) player position so a
            // pause parks the fade instead of counting down underneath it.
            val incomingPos = runCatching { player.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
            val progress = (incomingPos.toFloat() / overlapDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            if (progress >= 1f || outgoing.playbackState == Player.STATE_ENDED || outgoing.playerError != null) {
                cancelCrossfade()
            } else {
                // Perceptual fade: outgoing ramps down, incoming ramps up.
                // Equal-power sin/cos kept both near full level for most of
                // the window, which sounded like two songs mixed together.
                val outGain = (1f - progress) * (1f - progress)
                val inGain = progress * progress
                player.volume = inGain
                outgoing.volume = outGain
                outgoing.playWhenReady = player.isPlaying
            }
            return false
        }
        if (!player.isPlaying || player.repeatMode == Player.REPEAT_MODE_ONE || _state.value.repeatMode == Player.REPEAT_MODE_ONE) return false
        // Time the handoff off the best-known duration. Requiring the TRUE
        // container duration alone means TIME_UNSET streams (YouTube WebM/MP4
        // takes 30-40s to parse) never arm. Fall back to the seeded /
        // resolve-time duration also used by the progress bar.
        //
        // A window opened off a duration that belongs to ANOTHER track is worse
        // than no window at all: `remaining` crosses its threshold seconds into
        // a full-length track and the hand-off abandons the rest of it. So
        // effectiveDuration returns 0 here rather than the outgoing track's
        // length, and this track simply idles until its own duration is known.
        val timingDurationMs = player.duration.takeIf { it > 0L }
            ?: effectiveDuration(player.duration, player, _state.value.durationMs).takeIf { it > 0L }
            ?: return false
        val nextIndex = player.nextMediaItemIndex
        val hasNext = nextIndex != C.INDEX_UNSET &&
            nextIndex != player.currentMediaItemIndex &&
            nextIndex in 0 until player.mediaItemCount
        val nextItem = if (hasNext) player.getMediaItemAt(nextIndex) else null
        val stream = nextItem?.localConfiguration?.customCacheKey?.let(preparedStreams::get)
        val livePosMs = runCatching { player.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
            .takeIf { it > 0L } ?: positionMs.coerceAtLeast(0L)
        // Pure state only: never materializes the standby just to ask whether it
        // is already armed, which would build a second player on the first tick
        // of every track.
        val armedForNext = crossfadeStandbyArmed && standbyIndex == nextIndex &&
            standbyQueue.size == player.mediaItemCount &&
            standbyQueue.getOrNull(nextIndex) == nextItem
        val standby = currentStandby()
        val isNextLocal = nextItem?.mediaId?.startsWith("local:") == true

        val plan = planCrossfade(
            CrossfadePlanInput(
                crossfadeEnabled = true,
                bitPerfectEnabled = false,
                isPlaying = player.isPlaying,
                repeatOne = player.repeatMode == Player.REPEAT_MODE_ONE || _state.value.repeatMode == Player.REPEAT_MODE_ONE,
                durationMs = timingDurationMs,
                positionMs = livePosMs,
                crossfadeMs = crossfadeDurationMs,
                playbackSpeed = player.playbackParameters.speed,
                hasNextItem = hasNext,
                nextItemResolved = if (isNextLocal) {
                    nextItem?.localConfiguration?.uri?.scheme != "lastwave"
                } else {
                    nextItem?.localConfiguration?.uri?.scheme != "lastwave" && stream != null && !stream.isExpired()
                },
                streamExpired = if (isNextLocal) false else (stream?.isExpired() == true || (nextItem?.localConfiguration?.uri?.scheme != "lastwave" && stream == null)),
                standbyArmedForNext = armedForNext,
                standbyReady = armedForNext && standby?.playbackState == Player.STATE_READY,
                standbyDurationMs = if (armedForNext) standby?.duration ?: 0L else 0L,
                standbyBufferedMs = if (armedForNext) {
                    runCatching { standby?.bufferedPosition ?: 0L }.getOrDefault(0L)
                } else {
                    0L
                },
                armLeadMs = CROSSFADE_ARM_LEAD_MS,
                refreshLeadMs = CROSSFADE_PREP_REFRESH_LEAD_MS,
            ),
        )

        when (plan) {
            // Idle carries the blocking gate (disabled/bit-perfect/paused/no-next/
            // too-early/...). It used to return silently, which made a missed fade
            // indistinguishable from a disabled one in logcat; rate-limited here
            // so the 60ms ticker cannot flood.
            is CrossfadeDecision.Idle -> {
                logCrossfadeDecision(
                    "idle-${plan.reason.name.lowercase()}",
                    "remaining=${timingDurationMs - livePosMs}ms hasNext=$hasNext " +
                        "armed=$armedForNext ready=${standby?.playbackState == Player.STATE_READY}",
                )
                return false
            }

            is CrossfadeDecision.ResolveNext -> {
                // Unresolved or dead-signed next item. Refreshing early
                // (CROSSFADE_PREP_REFRESH_LEAD_MS) instead of at the window edge
                // is what lets provider-module FLAC actually blend.
                //
                // A module that keeps failing must not be re-requested on every
                // tick, so kicks are spaced by CROSSFADE_RESOLVE_RETRY_MS and
                // re-armed whenever the target index changes.
                val nowMs = SystemClock.elapsedRealtime()
                val dueForKick = lastCrossfadeResolveIndex != nextIndex ||
                    nowMs - lastCrossfadeResolveKickMs >= CROSSFADE_RESOLVE_RETRY_MS
                logCrossfadeDecision(
                    plan.reason.name.lowercase(),
                    "index=$nextIndex remaining=${timingDurationMs - livePosMs}ms " +
                        "inFlight=${preloadJob?.isActive == true} dueForKick=$dueForKick",
                )
                if (dueForKick && preloadJob?.isActive != true) {
                    lastCrossfadeResolveIndex = nextIndex
                    lastCrossfadeResolveKickMs = nowMs
                    preloadNextTrack(nextIndex, nextItem?.toPlayableTrack())
                }
                return false
            }

            CrossfadeDecision.Arm -> {
                if (nextItem != null) armCrossfadeStandby(nextIndex, nextItem)
                return false
            }

            CrossfadeDecision.WaitingForReady -> {
                logCrossfadeDecision(
                    "waiting-ready",
                    "remaining=${timingDurationMs - livePosMs}ms state=${standby?.playbackState} " +
                        "buffered=${standby?.bufferedPosition}ms",
                )
                return false
            }

            CrossfadeDecision.WaitingForWindow -> {
                // Buffered: let the outgoing track's background caching resume.
                crossfadeStandbyPreparing = false
                return false
            }

            is CrossfadeDecision.HandOff -> {
                if (standby == null || nextItem == null) return false
                // A queue edit during preparation must never start a stale next track.
                if (standbyQueue.indices.any { standbyQueue[it] != player.getMediaItemAt(it) }) {
                    cancelCrossfade()
                    return false
                }
                handOffCrossfade(standby, plan.overlapMs)
                return true
            }
        }
    }

    /** Swaps the standby in as the active player and starts the blend. */
    @MainThread
    private fun handOffCrossfade(standby: ExoPlayer, overlapMs: Long) {
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
        overlapDurationMs = overlapMs
        outgoing.volume = 1f
        standby.volume = 0f
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
        // The standby is now the live player; the outgoing one is the next
        // standby. Cleared so the downloader resumes caching the new track.
        crossfadeStandbyArmed = false
        crossfadeStandbyPreparing = false
        lastCrossfadeLogKey = null
        android.util.Log.i(
            "MusicPlayer",
            "Crossfade: handing off '${outgoing.currentMediaItem?.mediaMetadata?.title}' -> " +
                "'${standby.currentMediaItem?.mediaMetadata?.title}' (overlap ${overlapDurationMs}ms)",
        )
        listener.onMediaItemTransition(standby.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        refresh(standby)
    }

    private fun updateBitPerfectState() {
        // Bit-Perfect applies to stereo lossless/PCM streams (FLAC, YouTube Music, local downloads).
        // Dolby Atmos / Spatial audio requires system decoding & binaural virtualization;
        // forcing bit-perfect bypass or bit-perfect mixer on Atmos mutes the output.
        val isSpatial = isSpatialAudioCodec(_state.value.audioCodec)
        val effectiveBitPerfect = bitPerfectEnabled && !isSpatial
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
        updateSystemEffectsState()
    }

    /**
     * System audio-effects mode: pref ON + mixer route = flat in-app DSP and
     * a published audio session for external EQ / OEM Dolby. Bypass routes
     * (bit-perfect, USB exclusive) suspend it automatically; ending the mode
     * re-pushes the user's DSP prefs (collectors only fire on change).
     */
    private fun updateSystemEffectsState() {
        val bypassRoute = nativeBitPerfectApplied || usbExclusiveSinkActive
        val effective = systemEffectsModePref && !bypassRoute
        if (effective == systemEffectsEffective) return
        systemEffectsEffective = effective
        val engines = listOfNotNull(
            runCatching { nativeAudioEngine.get() }.getOrNull(),
            secondaryNativeEngine,
        )
        if (effective) {
            val zeros = FloatArray(NativeAudioEngine.EQUALIZER_BAND_COUNT)
            engines.forEach { engine ->
                runCatching { engine.setEqualizer(false, zeros) }
                runCatching { engine.setStudioMasterClarity(false) }
                engine.systemFlattened = true
            }
        } else {
            engines.forEach { it.systemFlattened = false }
            applicationScope.launch(Dispatchers.IO) {
                val eq = runCatching { equalizerPreferences.settings.first() }.getOrNull()
                val misc = runCatching { settingsPreferences.settings.first() }.getOrNull()
                val gains = eq?.gainsDb?.toFloatArray()
                    ?.takeIf { it.size == NativeAudioEngine.EQUALIZER_BAND_COUNT }
                engines.forEach { engine ->
                    if (gains != null) {
                        runCatching { engine.setEqualizer(eq?.enabled == true, gains) }
                    }
                    if (misc != null) {
                        runCatching { engine.setStudioMasterClarity(misc.isStudioMasterClarityEnabled) }
                        runCatching { engine.setClarityPreset(ClarityPresets.fromIndex(misc.clarityPreset)) }
                        runCatching { engine.setClarityAtmosBypass(misc.clarityAtmosBypass) }
                    }
                }
            }
        }
        runCatching { audioEffectsEngine.setSystemEffectsActive(effective) }
        runCatching { secondaryEffects?.setSystemEffectsActive(effective) }
        runCatching { systemEffectsBridge.setModeActive(effective) }
        android.util.Log.i(
            "MusicPlayer",
            "SYSTEM EFFECTS effective=$effective (pref=$systemEffectsModePref bypassRoute=$bypassRoute)",
        )
        PlaybackDiagnostics.event(
            "SystemEffects",
            "effective=$effective pref=$systemEffectsModePref bypassRoute=$bypassRoute",
        )
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
        if (!bitPerfectEnabled || !usbExclusivePrefEnabled) return
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
        if (isSpatialAudioCodec(_state.value.audioCodec)) 48000
        else _state.value.samplingRateKHz?.times(1000.0)?.toInt()?.takeIf { it > 0 }

    private fun dacRateFor(resolved: ResolvedStream): Int? =
        if (isSpatialAudioCodec(resolved.audioCodec) || isSpatialAudioCodec(_state.value.audioCodec)) 48000
        else resolved.samplingRateKHz?.times(1000.0)?.toInt()?.takeIf { it > 0 }
            ?: currentSourceRateHz()

    private fun exclusiveUsbWanted(): Boolean {
        if (isCasting) return false
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
        if (!bitPerfectEnabled || !usbExclusivePrefEnabled) return false
        // E-AC-3 JOC / 360 RA is multichannel. Exclusive USB is stereo PCM
        // only — keep Android's decoder+mixer so Atmos actually plays.
        if (isSpatialAudioCodec(_state.value.audioCodec)) return false
        val dac = usbDacMonitor.state.value.dac
        return dac?.hasUsbPeripheral == true && dac.usbPermissionGranted
    }

    /** Consecutive 1 Hz ticks with an implausible clock on an unconfirmed DAC. */
    private var exclusiveBadDriftTicks = 0

    /**
     * Fork safety guard. A DAC whose clock was never confirmed and that
     * consumes the stream several % away from the configured rate is playing
     * garbage (seen as loud distorted noise on a UAC1 dongle). Healthy DACs
     * stay within a few hundred PPM, so 2 % sustained for 2 s is unambiguous.
     * Only while PCM is actually flowing: a network stall must not trip it.
     */
    private fun checkExclusiveHealth(driftPpm: Double?) {
        if (driftPpm == null || exclusiveUsbOutput.isClockMatched() || !exclusiveUsbOutput.isStreamingAudio()) {
            exclusiveBadDriftTicks = 0
            return
        }
        if (kotlin.math.abs(driftPpm) < EXCLUSIVE_DRIFT_ABORT_PPM) {
            exclusiveBadDriftTicks = 0
            return
        }
        exclusiveBadDriftTicks++
        if (exclusiveBadDriftTicks < EXCLUSIVE_DRIFT_ABORT_TICKS) return
        exclusiveBadDriftTicks = 0
        val percent = String.format(java.util.Locale.ROOT, "%.1f", driftPpm / 10_000.0)
        exclusiveUsbOutput.abortUnhealthy("DAC clock off by $percent %")
        healthTracker.reset()
        applyDacRoutingFor(currentSourceRateHz())
        updateSignalPath()
    }

    /** Diagnostic text for the signal-path page (USB driver trace + descriptors). */
    fun usbDiagnosticReport(): String = exclusiveUsbOutput.diagnosticReport()

    /**
     * Fork: direct output without usbdevfs (direct AudioTrack, then AAudio
     * MMAP). Bit-Perfect must be on (it is what turns the DSP off). Every route,
     * Bluetooth and USB included: on a USB DAC the sink tries usbdevfs
     * exclusive FIRST and only falls back to this when it does not engage
     * (permission not granted yet, driver refused, …).
     */
    private fun internalDirectWanted(): Boolean {
        if (isCasting) return false
        if (!bitPerfectEnabled || !internalDirectPrefEnabled) return false
        return !isSpatialAudioCodec(_state.value.audioCodec)
    }

    /**
     * Effective USB exclusive wish, recomputed whenever an input changes. The
     * old code captured "enabled && bitPerfectEnabled" once: when the pref
     * flow emitted before the settings flow at startup, USB exclusive stayed
     * off for the whole session. It follows its own toggle only, so turning
     * "USB Exclusive Output" off always stops usbdevfs.
     */
    private fun recomputeUsbExclusivePref() {
        usbExclusivePrefEnabled = bitPerfectEnabled && usbExclusiveRawPref
    }

    /** True only for genuine Atmos decode: an E-AC-3 JOC decoder. A plain
     *  `audio/eac3`/`ac3` decoder (common, video-passthrough silicon) cannot
     *  render an Atmos music stream — treating it as capable used to request
     *  spatial manifests that ExoPlayer then choked on (3003 → retry loop →
     *  "Playback interrupted") instead of dropping to stereo. */
    private val isAtmosDecoderAvailable: Boolean by lazy {
        val platform = runCatching {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            codecList.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { type ->
                    type.equals("audio/eac3-joc", ignoreCase = true) ||
                        type.equals("audio/eac3", ignoreCase = true) ||
                        type.equals("audio/ac3", ignoreCase = true)
                }
            }
        }.getOrDefault(false)
        // Bundled FFmpeg decodes the E-AC-3 core of JOC streams on any device.
        val ffmpeg = runCatching {
            androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable() &&
                androidx.media3.decoder.ffmpeg.FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_E_AC3)
        }.getOrDefault(false)
        android.util.Log.i("MusicPlayer", "[ATMOS] decoder availability platform=$platform ffmpeg=$ffmpeg")
        platform || ffmpeg
    }

    fun isSpatialAudioSupportedOnDevice(): Boolean {
        if (isAtmosDecoderAvailable) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            val am = audioManager ?: return false
            val spatializer = am.spatializer
            if (spatializer.isAvailable || spatializer.isEnabled) {
                return true
            }
        }
        return false
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S_V2)
    private object SpatializerHelper {
        fun registerListener(
            context: Context,
            audioManager: AudioManager,
            onChanged: () -> Unit,
        ) {
            val spatializer = audioManager.spatializer
            spatializer.addOnSpatializerStateChangedListener(
                ContextCompat.getMainExecutor(context),
                object : android.media.Spatializer.OnSpatializerStateChangedListener {
                    override fun onSpatializerEnabledChanged(sp: android.media.Spatializer, enabled: Boolean) {
                        android.util.Log.i("MusicPlayer", "System Spatializer enabled changed: $enabled")
                        onChanged()
                    }

                    override fun onSpatializerAvailableChanged(sp: android.media.Spatializer, available: Boolean) {
                        android.util.Log.i("MusicPlayer", "System Spatializer available changed: $available")
                        onChanged()
                    }
                }
            )
        }
    }

    private fun setupSpatializerListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            val am = audioManager ?: return
            runCatching {
                SpatializerHelper.registerListener(appContext, am) {
                    updateSignalPath()
                    if (isSpatialAudioCodec(_state.value.audioCodec)) {
                        applyDacRoutingFor(48000)
                    }
                }
            }
        }
    }

    /**
     * Routes ExoPlayer output to the USB DAC at the track's native rate.
     * Always active when a DAC is present (it can only improve delivery);
     * the Bit-Perfect toggle decides bypass, volume policy and verdict.
     * The SOURCE rate always drives the output-stream request (44.1/48/
     * 88.2/96/176.4/192/352.8/384 kHz) — never a fixed 48 kHz — so no
     * LastWave resampler runs when the route accepts the source format.
     */
    private fun applyDacRoutingFor(sourceRateHz: Int?, streamCodec: String? = null) {
        val isSpatial = isSpatialAudioCodec(streamCodec) || isSpatialAudioCodec(_state.value.audioCodec)
        val effectiveRateHz = if (isSpatial) 48000 else sourceRateHz
        val dac = usbDacMonitor.state.value.dac
        val exclusiveWanted = exclusiveUsbWanted()
        exclusiveUsbOutput.setWanted(exclusiveWanted)
        val internalWanted = !isSpatial && internalDirectWanted()
        internalDirectOutput.setWanted(internalWanted)
        audioSinks.forEach { sink ->
            sink.setBitPerfectRequested(!isSpatial && bitPerfectEnabled)
            sink.syncExclusiveUsb(exclusiveWanted)
            sink.syncInternalDirect(internalWanted)
        }
        val exclusive = exclusiveUsbOutput.isActive()
        usbExclusiveSinkActive = exclusive
        // AudioFX on the player session would process nothing on either
        // exclusive path; the effects engine treats both the same way.
        val anyExclusive = exclusive || internalDirectOutput.isActive()
        runCatching { audioEffectsEngine.setUsbExclusiveActive(anyExclusive) }
        runCatching { secondaryEffects?.setUsbExclusiveActive(anyExclusive) }
        // Same-family fallback for DACs lacking the source rate (88.2 ->
        // 44.1 kHz): the exclusive stream opens at the supported rate with
        // native soxr conversion instead of failing onto the mixer. Null =
        // native rate (or no DAC / not exclusive): today's behavior.
        // The signal path stays honest automatically — the resampler check
        // fails, so a converted track can never report gold.
        val usbRates = exclusiveUsbOutput.supportedHardwareRatesHz()
        val platformRates = dac?.sampleRatesHz.orEmpty()
        val dacRates = when {
            usbRates.isNotEmpty() && platformRates.isNotEmpty() ->
                usbRates.filter { it in platformRates }.ifEmpty { usbRates }
            usbRates.isNotEmpty() -> usbRates
            platformRates.isNotEmpty() -> platformRates
            else -> {
                val known = exclusiveUsbOutput.lastHardwareRateHz()
                if (known > 0) listOf(known) else emptyList()
            }
        }
        val fallbackHz = if (exclusiveWanted && dac != null) {
            selectExclusiveRateFallback(effectiveRateHz, dacRates)
        } else {
            null
        }
        val device = if (!exclusive && dac != null && dac.deviceId > 0 && (effectiveRateHz ?: 0) > 0) {
            findOutputDevice(dac.deviceId)
        } else {
            null
        }
        routedDacDeviceId = device?.id
        audioSinks.forEach { sink ->
            runCatching { sink.setPreferredDevice(if (exclusive) null else device) }
            runCatching { sink.setOutputSampleRateOverride(if (isSpatial) null else effectiveRateHz) }
            runCatching { sink.setExclusiveFallbackRateHz(fallbackHz, effectiveRateHz) }
        }
        android.util.Log.i(
            "MusicPlayer",
            "BIT-PERFECT OUTPUT REQUEST srcRate=$effectiveRateHz " +
                "dac=${dac?.name} routed=${device != null} exclusive=$exclusive " +
                "wanted=$exclusiveWanted bitPerfect=$bitPerfectEnabled isSpatial=$isSpatial " +
                "rateFallback=${fallbackHz?.let { "$effectiveRateHz->$it" } ?: "none"}",
        )
        usbDacMonitor.setRouteRequested(exclusive || device != null)
        exclusiveUsbOutput.syncListeningGain()
        manageDacSystemVolume(!isSpatial && bitPerfectEnabled)
        updateSystemEffectsState()
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
        val isConverting = audioSinks.any { sink ->
            runCatching { sink.isExclusiveConverting() }.getOrDefault(false)
        } || exclusiveUsbOutput.isClockFallbackActive()
        val clockFallback = exclusive && isConverting
        val exclusiveRate = exclusiveUsbOutput.currentRateHz()
        val appRateHz = if (exclusive && exclusiveRate > 0) {
            exclusiveRate
        } else {
            audioSinks.firstNotNullOfOrNull { sink ->
                runCatching { sink.currentOutputSampleRateHz() }.getOrNull()?.takeIf { it > 0 }
            } ?: 0
        }
        val sourceRateHz = if (isSpatialAudioCodec(snapshot.audioCodec)) 48000
        else snapshot.samplingRateKHz?.times(1000.0)?.toInt()?.takeIf { it > 0 }
            ?: (if (!clockFallback) appRateHz.takeIf { exclusive && it > 0 } else null)
            ?: audioSinks.firstNotNullOfOrNull { sink ->
                runCatching { sink.exclusiveSourceSampleRateHz() }.getOrNull()?.takeIf { it > 0 }
            }
        // Backfill the MEASURED rate into state when metadata omitted it
        // (rate only — never depth). Without this a stream that reported only
        // a depth would render as a bare "24-BIT FLAC" for audio the pipeline
        // proves is running at 96kHz.
        // Spatial is excluded: its 48kHz here is a forced pipeline rate.
        if (snapshot.samplingRateKHz == null && (sourceRateHz ?: 0) > 0 &&
            !isSpatialAudioCodec(snapshot.audioCodec)
        ) {
            _state.update { it.copy(samplingRateKHz = sourceRateHz!! / 1000.0) }
        }
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
        val dspBypassActuallyActive = !clockFallback &&
            (exclusive || (bitPerfectEnabled && nativeBitPerfectApplied && sinkDirect && !sinkStale))
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
        val internalSession = internalDirectOutput.session.value?.takeIf {
            audioSinks.any { sink -> runCatching { sink.isInternalDirectActive() }.getOrDefault(false) }
        }
        val internalPrefOn = bitPerfectEnabled && internalDirectPrefEnabled
        val internalFailure = if (!internalPrefOn || internalSession != null || exclusive) {
            null
        } else {
            val availability = internalDirectOutput.availability.value
            when (availability.reason) {
                InternalDirectReason.USB -> availability.reason
                else -> internalDirectOutput.lastFailure
                    ?: availability.reason.takeUnless {
                        it == InternalDirectReason.AVAILABLE || it == InternalDirectReason.ACTIVE
                    }
            }
        }
        _signalPath.value = evaluateSignalPath(
            SignalPathInput(
                internalDirect = internalSession,
                internalDirectWanted = internalPrefOn,
                internalDirectFailure = internalFailure,
                bypassRequested = bitPerfectEnabled,
                sourceLabel = srcLabel,
                sourceRateHz = sourceRateHz,
                // The source row follows the same shared hi-res rule as the pill
                // (resolveDepthForDisplay), so the dialog can never disagree
                // with the badge. Only when that yields nothing do we fall back
                // to parsing an explicit depth out of the label; the sample
                // rate alone still never asserts a depth here.
                sourceBitDepth = resolveDepthForDisplay(
                    snapshot.bitDepth,
                    snapshot.samplingRateKHz,
                )
                    ?: parseQualityFromCodec(srcLabel)?.substringBefore('/')?.toIntOrNull()
                    ?: parseQualityFromCodec(snapshot.audioCodec)?.substringBefore('/')?.toIntOrNull()
                    ?: inferBitDepth(snapshot, allowRateGuess = false),
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
                routeVerified = exclusive && !clockFallback && exclusiveUsbOutput.isClockMatched(),
                driftPpm = healthTracker.driftPpm,
                glitchCount = healthTracker.glitchCount,
                isPlaying = snapshot.isPlaying,
                usbExclusiveActive = exclusive,
                exclusiveClockMatched = exclusive && !clockFallback && exclusiveUsbOutput.isClockMatched(),
                exclusiveHardwareVolume = hardwareVolume,
                exclusiveFailureReason = exclusiveUsbOutput.lastFailureReason.takeIf { !exclusive },
                clockFallbackResampled = clockFallback,
            ),
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
            // With exhausted history there is no heard song to return to —
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
        if (playHistory.lastOrNull() == mediaIdKey) return
        playHistory.addLast(mediaIdKey)
        while (playHistory.size > MAX_PLAY_HISTORY) playHistory.removeFirst()
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
        val currentIndex = player.currentMediaItemIndex
        val currentPos = order.indexOf(currentIndex)
        if (last || currentPos == -1) {
            order.add(index)
        } else {
            val at = (currentPos + 1).coerceIn(0, order.size)
            order.add(at, index)
        }
        player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), Random.nextLong()))
    }

    /**
     * In ExoPlayer, replaceMediaItem() internally removes and re-inserts the
     * item into ShuffleOrder at a completely random position (using
     * DefaultShuffleOrder.cloneAndInsert). This scrambles the upcoming order,
     * causes the current track to be placed into the future and repeat, or
     * changes which track plays next. Preserve the exact active permutation.
     */
    @MainThread
    private fun replaceMediaItemPreservingShuffle(index: Int, mediaItem: MediaItem) {
        if (!player.shuffleModeEnabled || player.currentTimeline.isEmpty) {
            player.replaceMediaItem(index, mediaItem)
            return
        }
        val timeline = player.currentTimeline
        val count = player.mediaItemCount
        val order = mutableListOf<Int>()
        var cursor = timeline.getFirstWindowIndex(true)
        var guard = 0
        while (cursor != C.INDEX_UNSET && guard++ < count + 1) {
            order.add(cursor)
            cursor = timeline.getNextWindowIndex(cursor, Player.REPEAT_MODE_OFF, true)
        }
        player.replaceMediaItem(index, mediaItem)
        if (order.size == count && index in order) {
            player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), Random.nextLong()))
        }
    }

    private fun nextQueueIndex(state: MusicPlayerState): Int {
        val queue = state.queue
        if (queue.isEmpty()) return C.INDEX_UNSET
        val start = (state.currentIndex + 1).coerceAtLeast(0)
        // Shuffle fallback (engine has no next, e.g. repeat-off at the true
        // permutation end): with repeat-all/one keep going on a random track,
        // otherwise stop — an unconditional random jump here made shuffle
        // play forever and made manual-next at the end jump unpredictably.
        // Manual navigation only (auto-advance replays in place on ONE), so a
        // repeat-one next at the end wraps instead of silently doing nothing.
        val wrapRepeat = state.repeatMode == Player.REPEAT_MODE_ALL
        val ordered = if (state.shuffleEnabled) {
            if (wrapRepeat) queue.indices.shuffled() else emptyList()
        } else {
            (start until queue.size) + if (wrapRepeat) (0 until start) else emptyList()
        }
        return ordered.firstOrNull { it != state.currentIndex && queue[it].mediaIdKey() !in unavailableMediaIds }
            ?: C.INDEX_UNSET
    }

    private fun previousQueueIndex(state: MusicPlayerState): Int {
        val queue = state.queue
        if (queue.isEmpty()) return C.INDEX_UNSET
        val start = state.currentIndex - 1
        // Manual navigation only: repeat-one wraps like repeat-all so a
        // manual previous never silently does nothing (auto-advance still
        // replays in place on ONE via handleNaturalTrackEnd).
        val wrapRepeat = state.repeatMode == Player.REPEAT_MODE_ALL
        val ordered = (start downTo 0) + if (wrapRepeat) (queue.lastIndex downTo 0) else emptyList()
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

    /**
     * Takes over playback of [index] after (re)installing its media source.
     * The loader thread may have already opened its own resolve of this
     * placeholder and started audible playback while the app-level resolve
     * was still in flight: rewinding to zero then replays the intro seconds
     * ("plays a moment then jumps"). Only seek when still at the very start.
     */
    @MainThread
    private fun takeOverPlayback(index: Int, expectedMediaId: String) {
        val alreadyAudible = player.currentMediaItemIndex == index &&
            player.currentMediaItem?.mediaId == expectedMediaId &&
            (player.isPlaying || player.currentPosition > 1_500L)
        if (!alreadyAudible) {
            player.seekToDefaultPosition(index)
        }
        if (player.playbackState == Player.STATE_IDLE) {
            PlaybackStartupLog.prepareStart()
            player.prepare()
        }
        player.play()
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
        cancelStaleResolverWork("queue-item")
        cancelActiveUpgrade()
        preloadJob?.cancel()
        unavailableSkipJob?.cancel()
        unavailableSkipJob = null
        val mediaItem = player.getMediaItemAt(index)
        val track = mediaItem.toPlayableTrack()
        PlaybackStartupLog.begin(
            trackId = track.internalTrackId?.takeIf { it.isNotBlank() } ?: track.videoId.orEmpty(),
            youtubeVideoId = track.videoId,
        )
        track.playbackIdentity()?.let { streamResolver.playback.begin(it) }
        val prepared = mediaItem.localConfiguration?.customCacheKey?.let(preparedStreams::get)
            ?: preparedStreams.values.firstOrNull { it.url == mediaItem.localConfiguration?.uri?.toString() }
            ?: findPreparedStreamFor(track, track.videoId, track.mediaIdKey() in losslessBypassMediaIds)
        val preparedMatches = prepared?.youtubeCandidate?.videoId?.let { resolvedId ->
            track.videoId.isNullOrBlank() || resolvedId == track.videoId
        } ?: true
        if (!preparedMatches && prepared != null) {
            preparedStreams.remove(prepared.cacheKey)
            track.playbackIdentity()?.let { streamResolver.cache.invalidate(it) }
        }
        val isLocal = mediaItem.mediaId.startsWith("local:") || track.isLocalTrack()
        val isAlreadyResolved = if (isLocal) {
            mediaItem.localConfiguration?.uri?.scheme != "lastwave"
        } else {
            prepared != null && !prepared.isExpired() && mediaItem.localConfiguration?.uri?.scheme != "lastwave"
        }
        if (preparedMatches && isAlreadyResolved) {
            // Already resolved: publish quality synchronously so the badge is
            // correct from the first frame (no transition may fire for a
            // same-item play to republish it later). The stream is also staged,
            // because takeOverPlayback's seek fires the transition reset that
            // clears these very fields.
            prepared?.let { stream ->
                stagePendingQuality(mediaItem.mediaId, stream)
                stagePendingQuality(track.mediaIdKey(), stream)
                track.videoId?.let { stagePendingQuality(it, stream) }
                publishResolvedQuality(stream, expectedMediaId = mediaItem.mediaId)
            }
            PlaybackStartupLog.cacheLookupStart()
            PlaybackStartupLog.cacheLookupEnd(hit = true)
            takeOverPlayback(index, mediaItem.mediaId)
            val playing = prepared
            scheduleStartupFollowUp { preloadNextQueueItem(index) }
            if (playing != null && !playing.isLossless && playing.audioCodec != "DOLBY ATMOS") {
                scheduleQualityUpgrade(
                    track = track,
                    expectedMediaId = mediaItem.mediaId,
                    generation = generation,
                    currentStream = playing,
                )
            }
            return
        }

        val expectedMediaId = mediaItem.mediaId
        resetPlayhead(0L, expectedMediaId)
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
                durationMs = track.durationMs ?: findKnownDuration(track) ?: 0L,
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
        decodedSampleRateHz = 0
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
                    // Staged so the transition reset fired by takeOverPlayback's
                    // seek hands the resolved depth straight back instead of
                    // blanking the pill.
                    stagePendingQuality(expectedMediaId, resolved)
                    stagePendingQuality(track.mediaIdKey(), resolved)
                    track.videoId?.let { stagePendingQuality(it, resolved) }
                    publishResolvedQuality(resolved, expectedMediaId = expectedMediaId)
                    applyDacRoutingFor(dacRateFor(resolved))
                    logStreamEvent("queue-prepare", resolved, retry = 0)
                    cacheCurrentTrackStream(resolved)
                    learnQueueVideoId(index, track, resolved.youtubeCandidate?.videoId, resolved.rematchedFromVideoId)
                    replaceMediaItemPreservingShuffle(index, track.toMediaItem(resolved))
                    takeOverPlayback(index, expectedMediaId)
                    enrichUpcomingQueue(index)
                    if (!resolved.isLossless && resolved.audioCodec != "DOLBY ATMOS") {
                        scheduleQualityUpgrade(
                            track = track,
                            expectedMediaId = expectedMediaId,
                            generation = generation,
                            currentStream = resolved,
                        )
                    }
                    scheduleStartupFollowUp {
                        preloadNextQueueItem(index)
                        scheduleDeferredRecommendations()
                    }
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
                            stagePendingQuality(expectedMediaId, ytFallback)
                            stagePendingQuality(track.mediaIdKey(), ytFallback)
                            track.videoId?.let { stagePendingQuality(it, ytFallback) }
                            publishResolvedQuality(ytFallback, expectedMediaId = expectedMediaId)
                            applyDacRoutingFor(dacRateFor(ytFallback))
                            logStreamEvent("queue-prepare-yt-fallback", ytFallback, retry = 0)
                            cacheCurrentTrackStream(ytFallback)
                            replaceMediaItemPreservingShuffle(index, track.toMediaItem(ytFallback))
                            takeOverPlayback(index, expectedMediaId)
                            enrichUpcomingQueue(index)
                            scheduleDeferredRecommendations()
                            preloadNextQueueItem(index)
                            scheduleQualityUpgrade(
                                track = track,
                                expectedMediaId = expectedMediaId,
                                generation = generation,
                                currentStream = ytFallback,
                            )
                        }
                        return@launch
                    }
                }
                withContext(Dispatchers.Main.immediate) {
                    if (generation == playRequestGeneration.get()) {
                        // This item is audibly playing right now (TTL refresh or
                        // a mid-song re-resolve): a failed re-resolve must not
                        // stop or skip it. Hand it to the keep-alive loop, which
                        // restores the stream in place.
                        val aliveHere = playerDelegate.isInitialized() &&
                            player.isPlaying &&
                            player.currentMediaItemIndex == index &&
                            player.currentMediaItem?.mediaId == expectedMediaId
                        if (aliveHere) {
                            startPlaybackRecoveryLoop(
                                failedIndex = index,
                                failedMediaId = expectedMediaId,
                                track = track,
                                videoId = track.videoId,
                                generation = generation,
                                resumePositionMs = player.currentPosition.coerceAtLeast(0L),
                                failedLosslessStream = false,
                                rejectedUrl = null,
                            )
                            return@withContext
                        }
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

    private fun cancelActiveUpgrade() {
        activeUpgradeJob?.cancel()
        activeUpgradeJob = null
        activeUpgradeMediaId = null
        activeUpgradeDeferred?.cancel()
        activeUpgradeDeferred = null
    }

    private fun cancelPendingPlaybackResolution() {
        playRequestGeneration.incrementAndGet()
        playRequest?.cancel()
        playRequest = null
        playbackRecoveryJob?.cancel()
        playbackRecoveryJob = null
        cancelStaleResolverWork("playback-cancelled")
        cancelActiveUpgrade()
        preloadJob?.cancel()
        preloadJob = null
        currentTrackCacheJob?.cancel()
        currentTrackCacheJob = null
        unavailableSkipJob?.cancel()
        unavailableSkipJob = null
    }

    /**
     * Drops every in-flight YouTube resolve except the one the caller is
     * about to start. Shared [resolutionRequests] live on [applicationScope],
     * so cancelling [playRequest] alone used to let them finish and publish.
     */
    private fun cancelStaleResolverWork(reason: String) {
        streamResolver.cancelAll(reason)
        resolutionRequests.values.forEach { it.second.cancel() }
        resolutionRequests.clear()
        queueEnrichmentJob?.cancel()
        queueEnrichmentJob = null
        startupFollowUp = null
    }

    private fun scheduleStartupFollowUp(block: () -> Unit) {
        startupFollowUp = block
    }

    private fun runStartupFollowUp() {
        val block = startupFollowUp ?: return
        startupFollowUp = null
        block()
    }

    fun toggleShuffle() = setShuffleEnabled(!state.value.shuffleEnabled)

    fun setShuffleEnabled(enabled: Boolean) = onMain {
        if (isCasting) {
            _state.update { it.copy(shuffleEnabled = enabled) }
            persistPlaybackSession()
            return@onMain
        }
        // Converge BOTH flags: refresh() mirrors the engine flag into state
        // on every player event, so returning early on engine-only equality
        // leaves a stale state flag behind — the toggle then visibly flips
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
        // Shuffle is engine state that survives stop()/clearMediaItems(): without
        // this reset the next fresh queue silently inherits shuffle while the
        // fresh state says off — until the first refresh() flips it back on.
        player.shuffleModeEnabled = false
        preparedStreams.clear()
        if (clearSession) {
            _state.value = MusicPlayerState()
            clearPersistedPlaybackSession()
            applicationScope.launch(Dispatchers.IO) { WidgetUpdater.clear(appContext) }
        } else {
            _state.update { it.copy(isPlaying = false, isBuffering = false) }
        }
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
     * Attaches a YouTube thumbnail when the video id is already known.
     * Does not search, and does not resolve streams. Scrolling a playlist
     * must not start resolver work.
     */
    private fun enrichUpcomingQueue(currentIndex: Int) {
        queueEnrichmentJob?.cancel()
        queueEnrichmentJob = null
        if (currentIndex !in 0 until player.mediaItemCount) return
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
    private fun scheduleDeferredRecommendations() {
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
                if (_state.value.isEndlessQueue) {
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
    private fun preloadNextQueueItem(currentIndex: Int) {
        if (player.repeatMode == Player.REPEAT_MODE_ONE || _state.value.repeatMode == Player.REPEAT_MODE_ONE) return
        val nextIndex = if (player.shuffleModeEnabled) player.nextMediaItemIndex else currentIndex + 1
        if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until player.mediaItemCount || nextIndex == currentIndex) return
        val nextItem = player.getMediaItemAt(nextIndex)
        if (nextItem.localConfiguration?.uri?.scheme != "lastwave") return
        preloadNextTrack(nextIndex, nextItem.toPlayableTrack())
    }

    private fun preloadNextTrack(nextIndex: Int, nextTrack: PlayableTrack?) {
        if (nextTrack == null || nextTrack.playbackUrl != null) return
        val identity = nextTrack.playbackIdentity()
        if (identity == null) {
            // No videoId yet (imported/legacy row): the next item must still
            // warm up or the transition lands on an unresolved placeholder.
            // Match an id now, save it onto the source playlist, then run the
            // normal preload flow with a concrete identity.
            if (nextTrack.title.isBlank()) return
            preloadJob?.cancel()
            preloadJob = applicationScope.launch(Dispatchers.IO) {
                val matchId = runCatching {
                    innerTube.findBestMatchOrNull(nextTrack.title, nextTrack.artist, prefetchStreams = false)?.videoId
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@launch
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.Main.immediate) {
                    if (nextIndex !in 0 until player.mediaItemCount ||
                        nextIndex == player.currentMediaItemIndex
                    ) {
                        return@withContext
                    }
                    val queuedTrack = player.getMediaItemAt(nextIndex).toPlayableTrack()
                    if (!queuedTrack.videoId.isNullOrBlank() ||
                        queuedTrack.mediaIdKey() != nextTrack.mediaIdKey()
                    ) {
                        return@withContext
                    }
                    learnQueueVideoId(nextIndex, queuedTrack, matchId)
                    val identified = queuedTrack.copy(videoId = matchId, internalTrackId = matchId)
                    replaceMediaItemPreservingShuffle(nextIndex, identified.toMediaItem())
                    // Detach before recursing: the follow-up preload cancels
                    // the current preload job, and we are running inside it.
                    preloadJob = null
                    preloadNextTrack(nextIndex, identified)
                }
            }
            return
        }
        if (streamResolver.playback.requested == identity) return
        warmArtwork(nextTrack)
        preloadJob?.cancel()
        preloadJob = applicationScope.launch(Dispatchers.IO) {
            val misc = runCatching { settingsPreferences.settings.first() }.getOrDefault(MiscSettings())
            val wantLossless = misc.preferLosslessStreaming &&
                misc.losslessQuality != LosslessMusicApi.QUALITY_YOUTUBE &&
                !losslessMusicApi.isCoolingDown

            var losslessResolved: ResolvedStream? = null
            if (wantLossless) {
                val expectedSec = nextTrack.durationMs?.takeIf { it > 0 }?.div(1_000)?.toInt()
                losslessResolved = runCatching {
                    resolveLosslessTrackAudioStream(
                        track = nextTrack,
                        misc = misc,
                        excludedLosslessUrls = emptySet(),
                        expectedDurationSeconds = expectedSec,
                    )
                }.getOrNull()
            }

            if (losslessResolved != null) {
                ensureActive()
                val dataSpec = DataSpec.Builder()
                    .setUri(Uri.parse(losslessResolved.url))
                    .setPosition(0)
                    .setLength(NEXT_TRACK_PREFETCH_BYTES)
                    .setKey(losslessResolved.cacheKey)
                    .build()
                    .withRequestHeaders(losslessResolved.requestHeaders)
                val rejected = runCatching {
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
                }.exceptionOrNull()
                if (rejected is CancellationException) throw rejected
                withContext(Dispatchers.Main.immediate) {
                    if (streamResolver.playback.requested == identity) return@withContext
                    val queuedTrack = if (nextIndex in 0 until player.mediaItemCount) {
                        player.getMediaItemAt(nextIndex).toPlayableTrack()
                    } else {
                        null
                    } ?: return@withContext
                    if (queuedTrack.videoId != identity.youtubeVideoId || nextIndex == player.currentMediaItemIndex) {
                        return@withContext
                    }
                    registerPreparedStream(losslessResolved)
                    stagePendingQuality(queuedTrack.mediaIdKey(), losslessResolved)
                    queuedTrack.videoId?.let { stagePendingQuality(it, losslessResolved) }
                    replaceMediaItemPreservingShuffle(nextIndex, queuedTrack.toMediaItem(losslessResolved))
                    logStreamEvent("next-prepared-lossless", losslessResolved, retry = 0)
                }
            } else {
                streamResolver.prefetchNext(identity, nextTrack.durationMs) { entry ->
                    if (entry.youtubeVideoId != identity.youtubeVideoId) {
                        streamResolver.cache.invalidate(identity)
                        return@prefetchNext
                    }
                    val resolved = entry.toPlayerStream()
                    val dataSpec = DataSpec.Builder()
                        .setUri(Uri.parse(resolved.url))
                        .setPosition(0)
                        .setLength(NEXT_TRACK_PREFETCH_BYTES)
                        .setKey(resolved.cacheKey)
                        .build()
                        .withRequestHeaders(resolved.requestHeaders)
                    val rejected = runCatching {
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
                    }.exceptionOrNull()
                    if (rejected is CancellationException) throw rejected
                    if (rejected != null) {
                        logResolutionFailure(nextTrack, "next-cache", 0, rejected)
                        val status = rejected.httpStatusCodeOrNull()
                        if (status == 403 || status == 410) {
                            streamResolver.cache.invalidate(identity)
                            innerTube.reportPlaybackFailure(identity.youtubeVideoId)
                            return@prefetchNext
                        }
                    }
                    withContext(Dispatchers.Main.immediate) {
                        if (streamResolver.playback.requested == identity) return@withContext
                        val queuedTrack = if (nextIndex in 0 until player.mediaItemCount) {
                            player.getMediaItemAt(nextIndex).toPlayableTrack()
                        } else {
                            null
                        } ?: return@withContext
                        if (queuedTrack.videoId != identity.youtubeVideoId || nextIndex == player.currentMediaItemIndex) {
                            return@withContext
                        }
                        registerPreparedStream(resolved)
                        stagePendingQuality(queuedTrack.mediaIdKey(), resolved)
                        queuedTrack.videoId?.let { stagePendingQuality(it, resolved) }
                        replaceMediaItemPreservingShuffle(nextIndex, queuedTrack.toMediaItem(resolved))
                        logStreamEvent("next-prepared", resolved, retry = 0)
                    }
                }
            }
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
                player.addMediaItems(fresh.map(PlayableTrack::toMediaItem))
                refresh(player)
                val isPlayerStoppedAtEnd = player.playbackState == Player.STATE_ENDED &&
                    player.currentMediaItemIndex >= previousCount - 1
                if (isPlayerStoppedAtEnd) {
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
    }

    private fun isDisallowedRadioTitle(titleLower: String): Boolean {
        val keywords = listOf(
            "mashup", "mash up", "mash-up",
            "jukebox", "juke box",
            "mega mix", "megamix",
            "non stop", "nonstop", "non-stop",
            "all songs", "top songs", "audio jukebox",
            "full album", "full songs", "compilation",
            "slowed + reverb", "slowed and reverb", "slowed reverb", "slowed & reverb",
            "bass boosted", "8d audio",
            "karaoke", "ringtone", "instrumental",
            "1 hour", "10 hour", "10 hours", "extended mix",
            "nightcore", "clean version", "sped up", "speed up",
            "soundtrack compilation", "reaction",
        )
        return keywords.any { titleLower.contains(it) }
    }

    private fun cleanCoreTitle(title: String): String {
        var s = title.lowercase().trim()
        val bracketRegex = Regex(
            """[\(\[\{](?:feat\.?|featuring|with|remix|acoustic|live|unplugged|radio edit|radio mix|club mix|extended|version|ver\.|remaster|remastered|deluxe|anniversary|lo-?fi|slowed|reverb|sped up|speed up|karaoke|instrumental|cover|reprise|clean|explicit|bonus|official|audio|video|lyrics?|visualizer|original mix|mix)[^\)\]\}]*[\)\]\}]""",
            RegexOption.IGNORE_CASE,
        )
        s = s.replace(bracketRegex, "")

        val generalBracketRegex = Regex("""[\(\[\{][^\)\]\}]*(?:live|edit|mix|version|remaster|\d{4})[^\)\]\}]*[\)\]\}]""", RegexOption.IGNORE_CASE)
        s = s.replace(generalBracketRegex, "")

        val hyphenSuffixRegex = Regex(
            """\s*[-–—|/]\s*(?:feat\.?|remix|acoustic|live|unplugged|radio edit|extended|version|remaster.*|deluxe.*|lo-?fi.*|slowed.*|reverb.*|sped up.*|karaoke|instrumental|cover.*|reprise|bonus.*|official.*).*$""",
            RegexOption.IGNORE_CASE,
        )
        s = s.replace(hyphenSuffixRegex, "")

        s = s.replace(Regex("""[^a-zA-Z0-9\s]"""), " ")
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    private fun cleanCoreArtist(artist: String): String {
        var a = artist.lowercase().trim()
        val primaryRegex = Regex("""^(.*?)(?:\s+(?:feat\.?|ft\.?|featuring|with|&|,|\/)\s+.*)$""", RegexOption.IGNORE_CASE)
        val match = primaryRegex.find(a)
        if (match != null) {
            a = match.groupValues[1]
        }
        a = a.replace(Regex("""[^a-zA-Z0-9\s]"""), " ")
        return a.replace(Regex("""\s+"""), " ").trim()
    }

    private fun isSameCoreSongOrVersion(
        candidateTitle: String,
        candidateArtist: String,
        knownCoreTitleArtists: Set<String>,
        knownCoreTitles: Set<String>,
        seedTitle: String,
        seedArtist: String,
    ): Boolean {
        val candTitleLower = candidateTitle.trim().lowercase()
        val candArtistLower = candidateArtist.trim().lowercase()
        val seedTitleLower = seedTitle.trim().lowercase()
        val seedArtistLower = seedArtist.trim().lowercase()

        if (candTitleLower == seedTitleLower && candArtistLower == seedArtistLower) return true

        val coreCandTitle = cleanCoreTitle(candidateTitle)
        val coreCandArtist = cleanCoreArtist(candidateArtist)
        val coreSeedTitle = cleanCoreTitle(seedTitle)
        val coreSeedArtist = cleanCoreArtist(seedArtist)

        if (coreCandTitle.isBlank()) return true

        if (coreCandTitle == coreSeedTitle) {
            if (coreCandArtist == coreSeedArtist ||
                candArtistLower.contains(seedArtistLower) ||
                seedArtistLower.contains(candArtistLower)
            ) {
                return true
            }
        }

        val candKey = "$coreCandTitle|$coreCandArtist"
        if (candKey in knownCoreTitleArtists) return true

        if (coreCandArtist.isNotBlank() && coreCandTitle in knownCoreTitles) {
            val hasArtistMatch = knownCoreTitleArtists.any { it.endsWith("|$coreCandArtist") }
            if (hasArtistMatch) return true
        }

        return false
    }

    /**
     * Curates dynamic, context-aware radio tracks similar to YouTube Music:
     * - Multi-seed related automix from /next ensures the candidate pool never starves.
     * - Seamlessly branches to similar artist radio searches and local listener taste.
     * - Strict anti-duplicate-version filter excludes covers, acoustic, live, remix,
     *   lo-fi, slowed, or remaster variants of already-played or queued songs.
     * - Smoothly paces artist distribution and blends listener affinity.
     */
    private suspend fun fetchContextAwareRadioTracks(
        primarySeed: PlayableTrack,
        fallbackSeeds: List<PlayableTrack> = emptyList(),
        knownVideoIds: Set<String>,
        knownKeys: Set<String>,
        knownCoreTitleArtists: Set<String>,
        knownCoreTitles: Set<String>,
        batchSize: Int = RADIO_QUEUE_BATCH_SIZE,
    ): List<PlayableTrack> {
        val seenVideoIds = knownVideoIds.toMutableSet()
        val seenKeys = knownKeys.toMutableSet()
        val currentCoreTitleArtists = knownCoreTitleArtists.toMutableSet()
        val currentCoreTitles = knownCoreTitles.toMutableSet()

        val candidateYtTracks = mutableListOf<YouTubeMusicTrack>()
        val seenCandidateIds = mutableSetOf<String>()

        fun addCandidates(tracks: List<YouTubeMusicTrack>) {
            for (track in tracks) {
                if (track.videoId.isNotBlank() && seenCandidateIds.add(track.videoId)) {
                    candidateYtTracks.add(track)
                }
            }
        }

        // 1. Primary seed automix / related
        val primarySeedVideoId = primarySeed.videoId?.takeIf(String::isNotBlank)
            ?: innerTube.findBestMatchOrNull(primarySeed.title, primarySeed.artist, prefetchStreams = false)?.videoId
            ?: runCatching {
                innerTube.searchSongs("${primarySeed.artist} ${primarySeed.title}", limit = 3, prefetchStreams = false).firstOrNull()?.videoId
            }.getOrNull()

        if (primarySeedVideoId != null) {
            radioUsedSeeds.add(primarySeedVideoId)
            val related = runCatching {
                relatedGraph.relatedFor(primarySeedVideoId, limit = batchSize, prefetchStreams = false)
            }.getOrDefault(emptyList())
            addCandidates(related)
        }

        // 2. Branch out to fallback seeds from tail/recent queue to avoid same-cluster starvation
        if (candidateYtTracks.count { it.videoId !in seenVideoIds } < 20) {
            for (seed in fallbackSeeds.take(3)) {
                val seedVideoId = seed.videoId?.takeIf(String::isNotBlank)
                    ?: runCatching {
                        innerTube.findBestMatchOrNull(seed.title, seed.artist, prefetchStreams = false)?.videoId
                    }.getOrNull()
                if (seedVideoId != null && seedVideoId !in radioUsedSeeds) {
                    radioUsedSeeds.add(seedVideoId)
                    val related = runCatching {
                        relatedGraph.relatedFor(seedVideoId, limit = 25, prefetchStreams = false)
                    }.getOrDefault(emptyList())
                    addCandidates(related)
                }
                if (candidateYtTracks.count { it.videoId !in seenVideoIds } >= 25) break
            }
        }

        // 3. Dynamic taste radio search on primary artist to keep continuous flow
        if (candidateYtTracks.count { it.videoId !in seenVideoIds } < 20 && primarySeed.artist.isNotBlank()) {
            val queries = listOf(
                "${primarySeed.artist} radio",
                "${primarySeed.artist} ${primarySeed.title} similar songs",
            )
            for (query in queries) {
                val searchTracks = runCatching {
                    innerTube.searchSongs(query, limit = 20, prefetchStreams = false)
                }.getOrDefault(emptyList())
                addCandidates(searchTracks)
                if (candidateYtTracks.count { it.videoId !in seenVideoIds } >= 25) break
            }
        }

        // 4. Fallback to listener taste profile from recent high-engagement artists
        if (candidateYtTracks.count { it.videoId !in seenVideoIds } < 15) {
            val statsRepo = runCatching { songPlayStatsRepository.get() }.getOrNull()
            val topTasteArtists = runCatching {
                statsRepo?.mostPlayedSince(30, 10)?.map { it.artist.trim() }?.filter { it.isNotBlank() }.orEmpty()
            }.getOrDefault(emptyList())

            for (artist in topTasteArtists.shuffled().take(2)) {
                val artistTracks = runCatching {
                    innerTube.searchSongs("$artist songs", limit = 15, prefetchStreams = false)
                }.getOrDefault(emptyList())
                addCandidates(artistTracks)
            }
        }

        if (candidateYtTracks.isEmpty()) return emptyList()

        val generatedCandidates = candidateYtTracks.map { yt ->
            com.lastwave.app.data.generate.GeneratedTrack(
                name = yt.title,
                artist = yt.artist,
                album = yt.album,
                artworkUrl = yt.artworkUrl,
                url = "https://music.youtube.com/watch?v=${yt.videoId}",
            )
        }
        val allowedKeys = runCatching {
            discoverRepository.filterRecommendationExclusions(generatedCandidates).mapTo(mutableSetOf()) { it.key }
        }.getOrDefault(generatedCandidates.mapTo(mutableSetOf()) { it.key })

        val statsRepo = runCatching { songPlayStatsRepository.get() }.getOrNull()
        val affinityArtists = runCatching {
            statsRepo?.mostPlayedSince(30, 40)?.map { it.artist.trim().lowercase() }?.toSet().orEmpty()
        }.getOrDefault(emptySet())

        val artistCounts = mutableMapOf<String, Int>()
        val filtered = mutableListOf<PlayableTrack>()

        for (yt in candidateYtTracks) {
            val title = yt.title.trim()
            val titleLower = title.lowercase()
            val artist = yt.artist.trim()
            val artistLower = artist.lowercase()
            val trackKey = "$titleLower|$artistLower"

            if (title.isBlank() || artist.isBlank()) continue
            if (yt.videoId == primarySeedVideoId) continue
            if (yt.videoId in seenVideoIds || trackKey in seenKeys) continue
            if (trackKey !in allowedKeys) continue
            if (isDisallowedRadioTitle(titleLower)) continue

            // Strict anti-duplicate-version check: rejects acoustic, live, remix, cover, lofi of any queued/heard track
            if (isSameCoreSongOrVersion(
                    candidateTitle = title,
                    candidateArtist = artist,
                    knownCoreTitleArtists = currentCoreTitleArtists,
                    knownCoreTitles = currentCoreTitles,
                    seedTitle = primarySeed.title,
                    seedArtist = primarySeed.artist,
                )
            ) {
                continue
            }

            val skips = statsRepo?.skipCountFor(title, artist) ?: 0
            if (skips >= 2) continue

            val currentArtistCount = artistCounts.getOrDefault(artistLower, 0)
            if (currentArtistCount >= 3) continue

            artistCounts[artistLower] = currentArtistCount + 1
            seenVideoIds.add(yt.videoId)
            seenKeys.add(trackKey)
            val coreTitle = cleanCoreTitle(title)
            val coreArtist = cleanCoreArtist(artist)
            currentCoreTitleArtists.add("$coreTitle|$coreArtist")
            currentCoreTitles.add(coreTitle)

            filtered.add(
                PlayableTrack(
                    title = title,
                    artist = artist,
                    album = yt.album,
                    artworkUrl = yt.artworkUrl,
                    videoId = yt.videoId,
                    durationMs = yt.durationSeconds?.takeIf { it > 0 }?.times(1_000L),
                ),
            )

            if (filtered.size >= batchSize) break
        }

        if (affinityArtists.isNotEmpty() && filtered.size > 4) {
            val (highAffinity, normal) = filtered.partition { it.artist.trim().lowercase() in affinityArtists }
            if (highAffinity.isNotEmpty()) {
                val interleaved = mutableListOf<PlayableTrack>()
                var hiIdx = 0
                var normIdx = 0
                while (hiIdx < highAffinity.size || normIdx < normal.size) {
                    repeat(3) {
                        if (normIdx < normal.size) interleaved.add(normal[normIdx++])
                    }
                    if (hiIdx < highAffinity.size) interleaved.add(highAffinity[hiIdx++])
                }
                return interleaved
            }
        }

        return filtered
    }

    private fun startRadioQueue(seed: PlayableTrack, resumePlaybackImmediately: Boolean = false) {
        radioQueueLoadJob?.cancel()
        radioUsedSeeds.clear()
        seed.videoId?.takeIf(String::isNotBlank)?.let { radioUsedSeeds.add(it) }

        radioQueueLoadJob = applicationScope.launch(Dispatchers.IO) {
            try {
                val existingTracks = withContext(Dispatchers.Main.immediate) {
                    if (!playerDelegate.isInitialized()) emptyList()
                    else (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toPlayableTrack() }
                }
                val existingVideoIds = existingTracks.mapNotNullTo(mutableSetOf()) { it.videoId }
                val existingKeys = existingTracks.mapTo(mutableSetOf()) { it.queueKey() }
                val knownCoreTitleArtists = existingTracks.withHistoryTracks().mapTo(mutableSetOf()) {
                    "${cleanCoreTitle(it.title)}|${cleanCoreArtist(it.artist)}"
                }
                val knownCoreTitles = existingTracks.withHistoryTracks().mapTo(mutableSetOf()) {
                    cleanCoreTitle(it.title)
                }

                val fallbackSeeds = existingTracks.takeLast(5).filter { !it.artist.equals(seed.artist, ignoreCase = true) }
                val toAdd = fetchContextAwareRadioTracks(
                    primarySeed = seed,
                    fallbackSeeds = fallbackSeeds,
                    knownVideoIds = existingVideoIds,
                    knownKeys = existingKeys,
                    knownCoreTitleArtists = knownCoreTitleArtists,
                    knownCoreTitles = knownCoreTitles,
                    batchSize = RADIO_QUEUE_BATCH_SIZE,
                )
                if (toAdd.isEmpty() || !radioQueueActive) return@launch

                withContext(Dispatchers.Main.immediate) {
                    if (!radioQueueActive || !playerDelegate.isInitialized()) return@withContext
                    val current = player.currentMediaItem?.toPlayableTrack()
                    val stillCurrentSeed = resumePlaybackImmediately ||
                        (seed.videoId != null && current?.videoId == seed.videoId) ||
                        (current?.title.equals(seed.title, ignoreCase = true) && current?.artist.equals(seed.artist, ignoreCase = true))
                    if (!stillCurrentSeed) return@withContext

                    val currentKeys = (0 until player.mediaItemCount).mapTo(mutableSetOf()) {
                        player.getMediaItemAt(it).toPlayableTrack().queueKey()
                    }
                    val validToAdd = toAdd.filter { it.queueKey() !in currentKeys }
                    if (validToAdd.isNotEmpty()) {
                        val previousCount = player.mediaItemCount
                        player.addMediaItems(validToAdd.map(PlayableTrack::toMediaItem))
                        refresh(player)
                        _state.update { it.copy(isEndlessQueue = true) }
                        enrichUpcomingQueue(player.currentMediaItemIndex)

                        val isPlayerStoppedAtEnd = player.playbackState == Player.STATE_ENDED &&
                            player.currentMediaItemIndex >= previousCount - 1
                        if (isPlayerStoppedAtEnd || resumePlaybackImmediately) {
                            val nextToPlay = previousCount.coerceIn(0, player.mediaItemCount - 1)
                            android.util.Log.i("MusicPlayer", "Queue expired/started: auto-resuming endless playback at index $nextToPlay")
                            resolveAndPlayQueueItem(nextToPlay)
                        } else {
                            val nextIndex = player.currentMediaItemIndex + 1
                            if (nextIndex in 0 until player.mediaItemCount) {
                                preloadNextTrack(nextIndex, player.getMediaItemAt(nextIndex).toPlayableTrack())
                            }
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

    private fun extendRadioQueueIfNeeded(currentIndex: Int, forceIfAtEnd: Boolean = false) {
        if (!radioQueueActive || radioQueueLoadJob?.isActive == true) return
        val currentCount = player.mediaItemCount
        val remaining = currentCount - currentIndex - 1
        if (!forceIfAtEnd && (currentIndex < 0 || remaining > RADIO_QUEUE_REFILL_THRESHOLD)) return

        radioQueueLoadJob = applicationScope.launch(Dispatchers.IO) {
            try {
                val currentQueue = withContext(Dispatchers.Main.immediate) {
                    if (!playerDelegate.isInitialized()) emptyList()
                    else (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toPlayableTrack() }
                }
                if (currentQueue.isEmpty() || !radioQueueActive) return@launch

                val knownVideoIds = currentQueue.mapNotNullTo(mutableSetOf()) { it.videoId }
                val knownKeys = currentQueue.mapTo(mutableSetOf()) { it.queueKey() }
                val knownCoreTitleArtists = currentQueue.withHistoryTracks().mapTo(mutableSetOf()) {
                    "${cleanCoreTitle(it.title)}|${cleanCoreArtist(it.artist)}"
                }
                val knownCoreTitles = currentQueue.withHistoryTracks().mapTo(mutableSetOf()) {
                    cleanCoreTitle(it.title)
                }

                val nextSeed = currentQueue
                    .drop(currentIndex.coerceAtLeast(0))
                    .firstOrNull { it.videoId != null && it.videoId !in radioUsedSeeds }
                    ?: currentQueue.getOrNull(currentIndex)
                    ?: currentQueue.lastOrNull { it.videoId != null }
                    ?: return@launch

                val fallbackSeeds = currentQueue
                    .takeLast(10)
                    .filter { !it.artist.equals(nextSeed.artist, ignoreCase = true) }
                    .distinctBy { it.artist.lowercase() }

                val toAdd = fetchContextAwareRadioTracks(
                    primarySeed = nextSeed,
                    fallbackSeeds = fallbackSeeds,
                    knownVideoIds = knownVideoIds,
                    knownKeys = knownKeys,
                    knownCoreTitleArtists = knownCoreTitleArtists,
                    knownCoreTitles = knownCoreTitles,
                    batchSize = RADIO_QUEUE_BATCH_SIZE,
                )
                if (toAdd.isEmpty() || !radioQueueActive) return@launch

                withContext(Dispatchers.Main.immediate) {
                    if (!radioQueueActive || !playerDelegate.isInitialized()) return@withContext
                    val existingKeys = (0 until player.mediaItemCount).mapTo(mutableSetOf()) {
                        player.getMediaItemAt(it).toPlayableTrack().queueKey()
                    }
                    val validToAdd = toAdd.filter { it.queueKey() !in existingKeys }
                    if (validToAdd.isNotEmpty()) {
                        val previousCount = player.mediaItemCount
                        player.addMediaItems(validToAdd.map(PlayableTrack::toMediaItem))
                        refresh(player)
                        _state.update { it.copy(isEndlessQueue = true) }
                        enrichUpcomingQueue(currentIndex)

                        val isPlayerStoppedAtEnd = player.playbackState == Player.STATE_ENDED &&
                            player.currentMediaItemIndex >= previousCount - 1
                        if (isPlayerStoppedAtEnd) {
                            val nextToPlay = previousCount.coerceIn(0, player.mediaItemCount - 1)
                            android.util.Log.i("MusicPlayer", "End of queue reached: auto-resuming infinite playback at index $nextToPlay")
                            resolveAndPlayQueueItem(nextToPlay)
                        } else {
                            val nextIndex = player.currentMediaItemIndex + 1
                            if (nextIndex in 0 until player.mediaItemCount) {
                                preloadNextTrack(nextIndex, player.getMediaItemAt(nextIndex).toPlayableTrack())
                            }
                        }
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
            // Queue end: endless queues refill asynchronously — kick them
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
                if (currentTrack != null && !isRepeatOne) {
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

    /**
     * Rendering watchdog: the progress bar is a wall clock and [refresh]
     * preserves the playing flag while a track resolves, so the UI can show
     * a normally-moving "playing" track while nothing audible comes out.
     * Two shapes, both fixed by the same recovery a manual seek performs
     * (renderer reset + fresh source):
     *
     * 1. Parked: ExoPlayer is not rendering at all — wedged placeholder
     *    load, dead-but-not-erroring source after a window swap, or a stuck
     *    IDLE/READY window, all with playWhenReady=true. Fires after
     *    [RENDER_STALL_TIMEOUT_MS] with zero movement in position AND
     *    buffer. A growing buffer means a slow network, which must never
     *    be interrupted.
     * 2. Inaudible: ExoPlayer reports rendering with an advancing position
     *    yet no music stream is active system-wide
     *    ([AudioManager.isMusicActive]) — output gated downstream of the
     *    player (stale device route, dead exclusive session, wedged sink).
     *    Fires after [INAUDIBLE_TIMEOUT_MS] of advancing-but-silent output.
     *    USB-exclusive playback bypasses AudioTrack by design, so it is
     *    excluded here (the parked branch still guards it).
     *
     * Never fires while a resolve/skip/crossfade owns the transition, on
     * ENDED (owned by [handleNaturalTrackEnd]), while casting, paused, or
     * with an error showing — healthy playback is never touched.
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
            if (progressed) {
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
        val usbBypass = exclusiveUsbOutput.isActive()
        val musicActive = usbBypass || runCatching { audioManager?.isMusicActive == true }.getOrDefault(true)
        if (posNow == lastAdvancingPositionMs || musicActive) {
            lastAdvancingPositionMs = posNow
            if (musicActive) inaudibleSinceMs = 0L
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
     * registered source without moving the playhead. Anything else (or a
     * repeat stall) goes through the full lossless-first
     * [resolveAndPlayQueueItem], which also covers expired/dead signed URLs
     * with fresh ones. Capped per track; beyond that the existing
     * error/unavailable machinery owns the window.
     */
    @MainThread
    private fun recoverSilentAdvance(index: Int, mediaId: String) {
        if (isCasting) return
        if (!playerDelegate.isInitialized() || index !in 0 until player.mediaItemCount) return
        if (!isCurrentMediaMatch(player, _state.value.current) || !_state.value.isPlaying || _state.value.error != null) return
        if (silentRecoveries.size > 64) silentRecoveries.clear()
        val attempt = (silentRecoveries[mediaId] ?: 0) + 1
        if (attempt > MAX_SILENT_RECOVERIES) return
        silentRecoveries[mediaId] = attempt
        val item = player.getMediaItemAt(index)
        val prepared = item.localConfiguration?.customCacheKey?.let(preparedStreams::get)
        val isLocal = item.mediaId.startsWith("local:") || _state.value.current?.isLocalTrack() == true
        val windowResolved = item.localConfiguration?.uri?.scheme != "lastwave" &&
            (isLocal || (prepared != null && !prepared.isExpired()))
        android.util.Log.w(
            "MusicPlayer",
            "Silent window '${_state.value.current?.title}' (attempt $attempt): " +
                "state=${player.playbackState} playWhenReady=${player.playWhenReady} " +
                "pos=${player.currentPosition}ms buf=${player.bufferedPosition}ms " +
                "resolved=$windowResolved -> ${if (windowResolved && attempt == 1) "renderer reset" else "re-resolve"}",
        )
        if (windowResolved && attempt == 1) {
            runCatching { player.seekTo(player.currentPosition.coerceAtLeast(0L)) }
            if (player.playbackState == Player.STATE_IDLE) runCatching { player.prepare() }
            runCatching { player.play() }
            return
        }
        resolveAndPlayQueueItem(index)
    }

    /**
     * Keep-alive for a track that died mid-playback. The three player-error
     * retries are the fast path; when they're gone (throttled URLs, module
     * cold start) the old code parked the player with "Unable to resolve" and
     * a tap-to-retry, which is the mid-song stop users see. This instead keeps
     * the same track buffering and re-resolves it in place — YouTube first,
     * no module walk — until audio comes back or the user moves on. If the
     * cap runs out the track is skipped like any other dead one: never
     * parked, never a retry prompt.
     *
     * @param resumePositionMs where the stream died, so playback resumes there
     */
    @MainThread
    private fun startPlaybackRecoveryLoop(
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
                // keep-alive owns nothing any more — leave without an error.
                val stillOurs = withContext(Dispatchers.Main.immediate) {
                    generation == playRequestGeneration.get() && _state.value.isBuffering
                }
                if (!stillOurs) return@launch
                attempt++
                if (attempt > 1) {
                    // A tap on play during the backoff wants a retry NOW: poll
                    // in small slices instead of one long sleep so the flag lands.
                    var waited = 0L
                    val total = (RECOVERY_BACKOFF_BASE_MS shl (attempt - 2).coerceAtMost(3))
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
                val stream = runCatching {
                    resolveTrackAudioStream(
                        track = track,
                        videoId = videoId,
                        allowLocalDownloads = false,
                        allowLossless = !failedLosslessStream && failedMediaId !in losslessBypassMediaIds,
                        excludedLosslessUrls = if (failedLosslessStream) setOfNotNull(rejectedUrl) else emptySet(),
                        losslessBudget = LosslessBudget.Disabled,
                    )
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    lastRecoveryError = error
                    logResolutionFailure(track, "playback-recovery", attempt, error)
                    null
                }
                if (stream == null) {
                    if (attempt >= MAX_PLAYBACK_RECOVERY_ATTEMPTS) break
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
                        true
                    } else {
                        false
                    }
                }
                if (restored) return@launch
            }
            // Cap reached: no parking, no retry prompt. The track counts as
            // proven-unavailable for this session and advances exactly like
            // any other dead track (next song, endless refill, or an honest
            // stop at the true queue end).
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
        // No premature 1-2s skip: first transient failure holds with
        // tap-to-retry; only proven-dead or repeatedly failing items
        // auto-advance. Player-error retries already exhausted
        // MAX_PLAYBACK_RETRIES before reaching here, so they skip at once.
        val provenDead = isExplicitlyUnplayableFailure(failure) || isUnsupportedMediaFailure(failure)
        val attempts = (unavailableAttemptCounts[failedMediaId] ?: 0) + 1
        unavailableAttemptCounts[failedMediaId] = attempts
        val alreadyRetriedExhaustively = errorRetryCount >= MAX_PLAYBACK_RETRIES
        if (!provenDead && !alreadyRetriedExhaustively && attempts < MIN_UNAVAILABLE_ATTEMPTS_BEFORE_SKIP) {
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
        /** Wall-clock ms when the minted URL dies (addon `exp`, YouTube
         *  `expire`); null = unknown or never expires. Stale prefetches are
         *  re-resolved instead of handed dead to ExoPlayer. */
        val expiresAtEpochMs: Long? = null,
        /** The stored videoId this stream replaced after it turned out
         *  unplayable. Set only by the title/artist rematch fallback; used
         *  to heal stored rows that pinned the dead id. */
        val rematchedFromVideoId: String? = null,
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
                // 16/96), absent depth is unknown — the rate alone never
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

        return ResolvedStream(
            url = uri.toString(),
            mimeType = mime,
            bitrateKbps = bitrateKbps,
            audioCodec = resolvedBadge,
            cacheKey = "local:$trackKey",
            isLossless = isLossless,
            bitDepth = if (isLossless) bitDepth?.takeIf { it > 0 } else null,
            samplingRateKHz = samplingRateKHz?.takeIf { it > 0.0 },
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

    /**
     * How long the lossless / provider-module branch may run before playback
     * falls back to YouTube.
     */
    private enum class LosslessBudget {
        /**
         * Interactive playback: give up quickly so the user hears *something*.
         * The module chain is several round trips (up to four searches plus a
         * stream fetch per quality tier), so this regularly expires on slow
         * links — which is the right trade when a track is already playing.
         */
        Interactive,

        /**
         * Background preload: nobody is waiting on audio, so never time out.
         * A 3.5s cap made the next track settle on the Opus stream instead of
         * the requested FLAC, or on nothing at all, which is why the crossfade
         * standby was still unprepared when its window opened.
         */
        Unbounded,

        /**
         * Recovery / loader paths where a module round trip is pure harm: the
         * stream that just died must come back from YouTube as fast as the
         * network allows. Walking the lossless chain here burned 5s of the
         * throttle window on every mid-song recovery and delivered the
         * "Unable to resolve" stop instead of audio.
         */
        Disabled,
    }

    private suspend fun resolveTrackAudioStream(
        track: PlayableTrack,
        videoId: String?,
        allowLossless: Boolean = true,
        excludedLosslessUrls: Set<String> = emptySet(),
        allowLocalDownloads: Boolean = true,
        losslessBudget: LosslessBudget = LosslessBudget.Interactive,
    ): ResolvedStream = withContext(Dispatchers.IO) {
        val identityKey = track.playbackIdentity()?.cacheKey
            ?: track.internalTrackId?.takeIf { it.isNotBlank() }
            ?: videoId?.takeIf { it.isNotBlank() }
            ?: track.playbackUrl
        val key = listOf(identityKey, videoId, allowLossless, excludedLosslessUrls, allowLocalDownloads, losslessBudget)
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
                )
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

    /**
     * Resolves audio stream for playback.
     *
     * For interactive starts (first track or explicit queue tap), if lossless streaming
     * is preferred, allocates up to 5 seconds to resolve lossless FLAC directly at 0:00.
     * If lossless resolves in time, playback begins immediately in lossless.
     * Otherwise, falls back to YouTube Opus without delaying playback further.
     */
    private suspend fun resolveRemoteTrackAudioStream(
        track: PlayableTrack,
        videoId: String?,
        allowLocalDownloads: Boolean = true,
        allowLossless: Boolean = true,
        excludedLosslessUrls: Set<String> = emptySet(),
        losslessBudget: LosslessBudget = LosslessBudget.Interactive,
    ): ResolvedStream {
        val directId = videoId?.takeIf { it.isNotBlank() } ?: track.videoId?.takeIf { it.isNotBlank() }
        val identity = directId?.let { id ->
            TrackIdentity(track.internalTrackId?.takeIf { it.isNotBlank() } ?: id, id)
        }

        // 1. Fast local download check (instant, bit-perfect)
        if (allowLocalDownloads) {
            val localQuick = runCatching { resolveLocalDownloadedAudioStream(track, deepScan = false) }.getOrNull()
            if (localQuick != null) {
                return localQuick
            }
        }

        // 2. 5-second lossless window for first/interactive track. Recovery
        // paths pass LosslessBudget.Disabled so the module chain never delays
        // the stream that has to replace a dead one.
        val misc = runCatching { settingsPreferences.settings.first() }.getOrDefault(MiscSettings())
        val wantLossless = allowLossless &&
            misc.preferLosslessStreaming &&
            misc.losslessQuality != LosslessMusicApi.QUALITY_YOUTUBE &&
            !losslessMusicApi.isCoolingDown &&
            losslessBudget != LosslessBudget.Disabled

        if (wantLossless) {
            val expectedSec = (track.durationMs?.takeIf { it > 0 })?.div(1_000)?.toInt()
            val timeoutMs = when (losslessBudget) {
                LosslessBudget.Interactive -> 5_000L
                LosslessBudget.Unbounded -> 30_000L
                LosslessBudget.Disabled -> 0L
            }
            val deferred = applicationScope.async(Dispatchers.IO) {
                runCatching {
                    resolveLosslessTrackAudioStream(
                        track = track,
                        misc = misc,
                        excludedLosslessUrls = excludedLosslessUrls,
                        expectedDurationSeconds = expectedSec,
                    )
                }.getOrNull()
            }
            activeUpgradeDeferred = deferred

            val losslessStream = runCatching {
                withTimeoutOrNull(timeoutMs) {
                    deferred.await()
                }
            }.getOrNull()

            if (losslessStream != null) {
                activeUpgradeDeferred = null
                registerPreparedStream(losslessStream)
                android.util.Log.i(
                    "MusicPlayer",
                    "[STARTUP RESOLVE] Resolved lossless at startup for '${track.title}' (${losslessStream.audioCodec}, ${losslessStream.bitrateKbps}kbps)",
                )
                return losslessStream
            }
            android.util.Log.i(
                "MusicPlayer",
                "[STARTUP RESOLVE] Lossless startup resolution exceeded ${timeoutMs}ms for '${track.title}', falling back to YouTube while continuing lossless search in background for hot-swap",
            )
        }

        PlaybackStartupLog.cacheLookupStart()
        val cached = identity?.let { streamResolver.cache.get(it) }?.takeIf { it.matches(identity) }
        PlaybackStartupLog.cacheLookupEnd(hit = cached != null)
        if (cached != null && identity != null) {
            streamResolver.playback.begin(identity)
            return acceptResolvedIdentity(track, identity, cached)
        }
        return coroutineScope {
            val localQuick = async(Dispatchers.IO) {
                if (!allowLocalDownloads) null
                else runCatching { resolveLocalDownloadedAudioStream(track, deepScan = true) }.getOrNull()
            }
            val youtube = async(Dispatchers.IO) {
                resolveYoutubeTrackAudioStream(track, videoId)
            }
            while (true) {
                ensureActive()
                val localDone = localQuick.isCompleted
                val youtubeDone = youtube.isCompleted
                when {
                    localDone && youtubeDone -> {
                        val local = localQuick.await()
                        if (local != null) return@coroutineScope local
                        return@coroutineScope youtube.await()
                    }
                    localDone -> {
                        val local = localQuick.await()
                        if (local != null) {
                            youtube.cancel()
                            return@coroutineScope local
                        }
                        return@coroutineScope youtube.await()
                    }
                    youtubeDone -> {
                        localQuick.cancel()
                        return@coroutineScope youtube.await()
                    }
                }
                delay(5)
            }
            error("Playback resolve race ended unexpectedly")
        }
    }

    private suspend fun resolveLosslessTrackAudioStream(
        track: PlayableTrack,
        misc: MiscSettings,
        excludedLosslessUrls: Set<String> = emptySet(),
        expectedDurationSeconds: Int? = null,
    ): ResolvedStream? {
        val atmosSupported = isSpatialAudioSupportedOnDevice()
        // Atmos plays only when explicitly selected (quality 28) AND the device
        // can render it. No toggle, no auto-upgrade: any other tier never becomes Atmos.
        // An Atmos request on an incapable device falls back to hi-res stereo.
        val effectiveQuality = if (misc.losslessQuality == LosslessMusicApi.QUALITY_DOLBY_ATMOS && !atmosSupported) {
            LosslessMusicApi.QUALITY_MAX_HI_RES
        } else {
            misc.losslessQuality
        }
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
        // Unknown depth stays unknown at ≤48kHz (renders rate-only, honest).
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
        // stereo quality tier so the waterfall tries hi-res → CD → lossless
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
            // tries hi_res → lossless → high instead of skipping to YouTube.
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
            // Use the stereo stream instead — re-derive badge and metadata
            // from the new stream by recursing with the stereo quality.
            // (We can't just drop through because badge/playUrl were computed
            // from the original spatial stream.)
            android.util.Log.i("MusicPlayer", "[LOSSLESS] stereo fallback succeeded for '${track.title}'")
            return resolveLosslessTrackAudioStream(
                track = track,
                misc = misc.copy(losslessQuality = stereoQuality),
                excludedLosslessUrls = excludedLosslessUrls,
                expectedDurationSeconds = expectedDurationSeconds,
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
            val lower = playUrl.substringBefore('?').lowercase()
            val isExplicitDash = lower.endsWith(".mpd") || lower.contains(".mpd") || lower.contains("/mpd")
            mimeType = when {
                stream.mimeType.isNotBlank() && stream.mimeType != MimeTypes.APPLICATION_MPD -> stream.mimeType
                isExplicitDash -> MimeTypes.APPLICATION_MPD
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
            cacheKey = "lossless:${track.mediaIdKey()}:${stream.formatId}",
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

    private suspend fun resolveYoutubeTrackAudioStream(
        track: PlayableTrack,
        videoId: String?,
    ): ResolvedStream {
        val directId = videoId?.takeIf { it.isNotBlank() } ?: track.videoId?.takeIf { it.isNotBlank() }
        if (directId != null) {
            val identity = TrackIdentity(
                trackId = track.internalTrackId?.takeIf { it.isNotBlank() } ?: directId,
                youtubeVideoId = directId,
            )
            val entry = try {
                streamResolver.resolvePlayback(identity, track.durationMs)
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException ||
                    failure is com.lastwave.app.playback.resolve.StaleResolveException ||
                    !isDeadVideoIdFailure(failure) ||
                    track.title.isBlank()
                ) {
                    throw failure
                }
                // The stored id itself is unplayable (removed / region-locked).
                // Rows that pinned it would otherwise fail forever, so fall
                // back to a fresh search and remember the replacement.
                return resolveRematchedYoutubeStream(track, deadVideoId = directId)
            }
            return acceptResolvedIdentity(track, identity, entry)
        }
        if (track.title.isBlank()) {
            throw java.io.IOException("No video ID or search query available for track")
        }
        PlaybackStartupLog.videoIdLookupStart()
        val matchId = try {
            innerTube.findBestMatch(
                title = track.title,
                artist = track.artist,
                prefetchStreams = false,
            ).videoId.takeIf { it.isNotBlank() }
                ?: throw java.io.IOException("No playable match found")
        } finally {
            PlaybackStartupLog.videoIdLookupEnd()
        }
        val identity = TrackIdentity(matchId, matchId)
        val entry = streamResolver.resolvePlayback(identity, track.durationMs)
        if (entry.youtubeVideoId != matchId) {
            streamResolver.cache.invalidate(identity)
            throw java.io.IOException("Rejected mismatched stream requested=$matchId resolved=${entry.youtubeVideoId}")
        }
        return entry.toPlayerStream()
    }

    /**
     * A stored videoId failed as explicitly unplayable. Retry once through a
     * fresh title/artist search; the resolved stream records the dead id so
     * callers can persist the replacement onto stored playlists.
     */
    private suspend fun resolveRematchedYoutubeStream(
        track: PlayableTrack,
        deadVideoId: String,
    ): ResolvedStream {
        val deadIdentity = TrackIdentity(
            trackId = track.internalTrackId?.takeIf { it.isNotBlank() } ?: deadVideoId,
            youtubeVideoId = deadVideoId,
        )
        PlaybackStartupLog.videoIdLookupStart()
        val matchId = try {
            innerTube.findBestMatch(
                title = track.title,
                artist = track.artist,
                prefetchStreams = false,
            ).videoId.takeIf { it.isNotBlank() }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            null
        } finally {
            PlaybackStartupLog.videoIdLookupEnd()
        }
        // Search failed or landed on the same dead video: nothing improved.
        if (matchId == null || matchId == deadVideoId) {
            throw java.io.IOException("Unable to resolve a playable audio stream for $deadVideoId")
        }
        val identity = TrackIdentity(matchId, matchId)
        // Re-point the active claim at the replacement so the resolve below
        // is not rejected as stale (same handshake as cached-identity hits).
        streamResolver.playback.begin(identity)
        val entry = try {
            streamResolver.resolvePlayback(identity, track.durationMs)
        } catch (failure: Throwable) {
            // Hand the claim back to the stored identity when it is still
            // ours: the normal retry path re-resolves with it and must not
            // be treated as a stale selection.
            if (streamResolver.playback.requested == identity) {
                streamResolver.playback.begin(deadIdentity)
            }
            throw failure
        }
        if (entry.youtubeVideoId != matchId) {
            streamResolver.cache.invalidate(identity)
        }
        val resolved = acceptResolvedIdentity(
            track = track,
            identity = identity,
            entry = entry,
            requestedIdOverride = matchId,
        )
        return resolved.copy(rematchedFromVideoId = deadVideoId)
    }

    private fun acceptResolvedIdentity(
        track: PlayableTrack,
        identity: TrackIdentity,
        entry: StreamCache,
        requestedIdOverride: String? = null,
    ): ResolvedStream {
        if (!entry.matches(identity)) {
            streamResolver.cache.invalidate(identity)
            throw java.io.IOException(
                "Rejected mismatched stream requested=${identity.youtubeVideoId} resolved=${entry.youtubeVideoId}",
            )
        }
        val active = streamResolver.playback.requested
        if (active != null && active != identity) {
            throw com.lastwave.app.playback.resolve.StaleResolveException(identity)
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

    private fun StreamCache.toPlayerStream(): ResolvedStream {
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
            durationMs = ytStream.durationMs?.takeIf { it > 0 },
            expiresAtEpochMs = ytStream.expiresAtEpochMs,
        )
    }

    private fun publishResolvedQuality(resolved: ResolvedStream, expectedMediaId: String? = null) {
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

    private fun scheduleQualityUpgrade(
        track: PlayableTrack,
        expectedMediaId: String,
        generation: Long,
        currentStream: ResolvedStream,
    ) {
        if (expectedMediaId == activeUpgradeMediaId && activeUpgradeJob?.isActive == true) {
            android.util.Log.i("MusicPlayer", "[STREAM UPGRADE] already running for '${track.title}'")
            return
        }
        val misc = runCatching { runBlocking { settingsPreferences.settings.first() } }.getOrDefault(MiscSettings())
        val wantLossless = misc.preferLosslessStreaming &&
            misc.losslessQuality != LosslessMusicApi.QUALITY_YOUTUBE
        if (!wantLossless || losslessMusicApi.isCoolingDown) {
            android.util.Log.i(
                "MusicPlayer",
                "[STREAM UPGRADE] skipped '${track.title}' prefer=${misc.preferLosslessStreaming} quality=${misc.losslessQuality} cooling=${losslessMusicApi.isCoolingDown}",
            )
            return
        }
        if (currentStream.isLossless || currentStream.audioCodec == "DOLBY ATMOS" ||
            currentStream.cacheKey.startsWith("lossless:") || currentStream.cacheKey.startsWith("local:")
        ) return

        val inFlightLossless = activeUpgradeDeferred
        activeUpgradeDeferred = null

        activeUpgradeJob?.cancel()
        activeUpgradeMediaId = expectedMediaId
        android.util.Log.i(
            "MusicPlayer",
            "[STREAM UPGRADE] starting for '${track.title}' quality=${misc.losslessQuality}",
        )
        activeUpgradeJob = applicationScope.launch(Dispatchers.IO) {
            try {
                val expectedSec = (track.durationMs?.takeIf { it > 0 } ?: currentStream.durationMs)
                    ?.div(1_000)
                    ?.toInt()
                var seed = if (inFlightLossless != null) {
                    runCatching { inFlightLossless.await() }.getOrNull()
                } else {
                    null
                }
                // A duration miss must not end the search. The addon drops
                // candidates outside its own ceiling when expected duration
                // is set, and a stream that still fails the 35s check is
                // excluded so the next candidate can be resolved.
                var upgraded: ResolvedStream? = null
                val maxAttempts = 3
                for (attempt in 1..maxAttempts) {
                    currentCoroutineContext().ensureActive()
                    if (generation != playRequestGeneration.get()) return@launch

                    upgraded = selectDurationCompatibleUpgrade(
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
                        pending ?: runCatching {
                            resolveLosslessTrackAudioStream(
                                track = track,
                                misc = misc,
                                excludedLosslessUrls = excluded,
                                expectedDurationSeconds = expectedSec,
                            )
                        }.getOrNull()
                    }

                    if (upgraded != null || attempt == maxAttempts) break
                    android.util.Log.d("MusicPlayer", "[STREAM UPGRADE] Attempt $attempt did not resolve upgrade for '${track.title}', retrying in 3s while active track plays...")
                    delay(3_000L)
                }

                if (upgraded == null) {
                    android.util.Log.i("MusicPlayer", "[STREAM UPGRADE] No upgrade stream found for '${track.title}' after retries")
                    return@launch
                }

                // Verify the upgraded stream is genuinely better than what is currently playing
                if (!isWorthSwapping(currentStream, upgraded)) {
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
                cacheCurrentTrackStream(upgraded)
                logStreamEvent("stream-upgrade-cached", upgraded, retry = 0)

                // Check whether the active track is still playing and eligible for swap
                val currentPos = withContext(Dispatchers.Main.immediate) {
                    if (!playerDelegate.isInitialized()) return@withContext -1L
                    if (generation != playRequestGeneration.get()) return@withContext -1L
                    val currentIndex = player.currentMediaItemIndex
                    if (currentIndex !in 0 until player.mediaItemCount) return@withContext -1L
                    val currentItem = player.getMediaItemAt(currentIndex)
                    if (currentItem.mediaId != expectedMediaId && currentItem.mediaId != track.mediaIdKey()) return@withContext -1L

                    val dur = player.duration
                    val pos = player.currentPosition
                    if (dur > 0L && pos > dur - 12_000L) {
                        android.util.Log.d("MusicPlayer", "[STREAM UPGRADE] Near end of track (${pos}/${dur}ms), omitting swap")
                        return@withContext -1L
                    }
                    if (outgoingPlayer != null || crossfadeStandbyPreparing || crossfadeStandbyArmed) {
                        android.util.Log.d("MusicPlayer", "[STREAM UPGRADE] Crossfade in flight, omitting swap")
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
                    val stereoMisc = misc.copy(
                        losslessQuality = if (misc.losslessQuality == LosslessMusicApi.QUALITY_DOLBY_ATMOS) {
                            LosslessMusicApi.QUALITY_MAX_HI_RES
                        } else {
                            misc.losslessQuality
                        },
                    )
                    val stereoCandidate = runCatching {
                        resolveLosslessTrackAudioStream(
                            track,
                            stereoMisc,
                            excludedLosslessUrls = emptySet(),
                            expectedDurationSeconds = expectedSec,
                        )
                    }.getOrNull()
                    if (stereoCandidate != null && isWorthSwapping(currentStream, stereoCandidate)) {
                        val stereoKnownDur = stereoCandidate.durationMs ?: knownDur
                        registerPreparedStream(stereoCandidate)
                        cacheCurrentTrackStream(stereoCandidate)
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

                // Perform seamless swap on Main thread with cached bytes ready
                withContext(Dispatchers.Main.immediate) {
                    if (generation != playRequestGeneration.get()) return@withContext
                    if (!playerDelegate.isInitialized()) return@withContext
                    val currentIndex = player.currentMediaItemIndex
                    if (currentIndex !in 0 until player.mediaItemCount) return@withContext
                    val currentItem = player.getMediaItemAt(currentIndex)
                    if (currentItem.mediaId != expectedMediaId && currentItem.mediaId != track.mediaIdKey()) return@withContext

                    val dur = player.duration
                    val swapPos = player.currentPosition
                    if (dur > 0L && swapPos > dur - 10_000L) {
                        android.util.Log.d("MusicPlayer", "[STREAM UPGRADE] Near end of track (${swapPos}/${dur}ms), omitting swap")
                        return@withContext
                    }
                    if (outgoingPlayer != null || crossfadeStandbyPreparing || crossfadeStandbyArmed) {
                        android.util.Log.d("MusicPlayer", "[STREAM UPGRADE] Crossfade in flight, omitting swap")
                        return@withContext
                    }

                    val playWhenReady = player.playWhenReady
                    val updatedMediaItem = track.toMediaItem(swapCandidate)

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
                    lastSeekTargetMs = swapPos
                    lastSeekAtElapsedMs = SystemClock.elapsedRealtime()
                    player.seekTo(currentIndex, swapPos)
                    player.prepare()
                    if (playWhenReady) {
                        player.play()
                    }
                    applicationScope.launch(Dispatchers.Main.immediate) {
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
                    applyDacRoutingFor(dacRateFor(swapCandidate), swapCandidate.audioCodec)
                    updateBitPerfectState()

                    logStreamEvent("stream-upgrade-swapped", swapCandidate, retry = 0)
                    android.util.Log.i(
                        "MusicPlayer",
                        "[STREAM UPGRADE] Seamlessly hot-swapped '${track.title}' to ${swapCandidate.audioCodec} (${swapCandidate.bitrateKbps}kbps, ${swapCandidate.samplingRateKHz}kHz) at ${swapPos}ms",
                    )
                }
            } catch (_: CancellationException) {
            } catch (e: Throwable) {
                android.util.Log.w("MusicPlayer", "[STREAM UPGRADE] Exception upgrading '${track.title}': ${e.message}")
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
            val resolvingFactory = ResolvingDataSource.Factory(cacheDataSourceFactory) { dataSpec ->
                val stream = dataSpec.key?.let(preparedStreams::get)
                    ?: preparedStreams.values.firstOrNull { it.url == dataSpec.uri.toString() }
                if (stream != null) {
                    dataSpec.buildUpon()
                        .setKey(stream.cacheKey)
                        .build()
                        .withRequestHeaders(stream.requestHeaders)
                } else {
                    dataSpec
                }
            }
            // Same decode path as the active player: platform immersive
            // renderer ahead of software fallback so a spatial mix auditions
            // exactly as it would play. A bare factory without it fails
            // spatial manifests here while the main pipeline could play them.
            val renderersFactory = object : DefaultRenderersFactory(appContext) {
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
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
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

            auditionPlayer = ExoPlayer.Builder(appContext, renderersFactory)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(appContext, extractorsFactory).setDataSourceFactory(resolvingFactory)
                        .setDrmSessionManagerProvider { mediaItem ->
                            val stream = mediaItem.localConfiguration?.customCacheKey
                                ?.let(preparedStreams::get)
                            val descriptor = stream?.segmentedDrm
                            if (descriptor?.drm == null) DrmSessionManager.DRM_UNSUPPORTED
                            else moduleDrmFactory.sessionManagerFor(descriptor)
                        },
                )
                .setLoadControl(loadControl)
                .build().apply {
                    playWhenReady = false
                    volume = 0f
                }

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
            val deadlineMs = SystemClock.elapsedRealtime() + 5_000L
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
                    // transcode/mislabeled rendition — reject before the swap.
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
                    val minBufferedMs = targetPositionMs + 1_500L
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

    private suspend fun resolveTrackAudioStreamWithRetry(
        track: PlayableTrack,
        videoId: String?,
        allowLossless: Boolean,
        losslessBudget: LosslessBudget = LosslessBudget.Interactive,
    ): ResolvedStream {
        runCatching { streamResolutionWakeLock?.acquire(60_000L) }
        try {
            var lastFailure: Throwable? = null
            repeat(2) { attempt ->
                try {
                    return resolveTrackAudioStream(track, videoId, allowLossless, losslessBudget = losslessBudget)
                } catch (error: Throwable) {
                    if (error is CancellationException &&
                        error !is kotlinx.coroutines.TimeoutCancellationException
                    ) {
                        throw error
                    }
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

    private fun findKnownDuration(track: PlayableTrack?): Long? {
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
     * playlists that hold the same song — blank-id rows get the id so the
     * next play resolves directly, and rows that pinned a now-dead id get
     * the replacement (only when [rematchedFromVideoId] names exactly the id
     * that failed). No-op when the learned id already matches.
     */
    private fun learnQueueVideoId(
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

    private fun findPreparedStreamFor(
        track: PlayableTrack,
        videoId: String?,
        bypassLossless: Boolean,
    ): ResolvedStream? {
        val matchVideoId = videoId ?: track.videoId
        val localKey = "${track.artist.trim().lowercase()}_${track.title.trim().lowercase()}"
        val losslessPrefix = "lossless:${track.mediaIdKey()}:"

        // 1. Prefer prepared lossless stream if lossless is allowed
        if (!bypassLossless) {
            val losslessHit = preparedStreams.values.firstOrNull { stream ->
                !stream.isExpired() && stream.cacheKey.startsWith(losslessPrefix)
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
            !stream.isExpired() && matchVideoId != null && stream.youtubeCandidate?.videoId == matchVideoId
        }
    }

    private fun registerPreparedStream(stream: ResolvedStream) {
        preparedStreams.entries.removeIf { it.value.isExpired() }
        preparedStreams[stream.cacheKey] = stream
        val seedMs = stream.durationMs ?: stream.youtubeCandidate?.durationMs
            ?: _state.value.current?.durationMs ?: findKnownDuration(_state.value.current)
        rememberKnownDuration(stream.cacheKey, seedMs)
        rememberKnownDuration(stream.youtubeCandidate?.videoId, seedMs)
        rememberTrackDuration(_state.value.current, seedMs)
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
     * return null — never fetch here; the resolve path already measured them.
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
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
            error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
    }

    /**
     * Auto-skip gate for playback errors. Mid-queue tracks that fail at
     * position 0 with a transient HTTP status (401/403 throttled or expired
     * signed URL, 408/429, 5xx) or a network/timeout blip must HOLD with
     * tap-to-retry instead of auto-advancing: otherwise one bad stretch
     * eats the queue 2-3s at a time ("buffers then skips"). Only
     * confirmed-unplayable, permanent decode/format errors, or 404/410
     * (gone) auto-skip — same as a track that already played audibly never
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
            // 16/96 file — trust it. When the retriever omits depth/rate
            // (pre-S APIs, several OEMs/files), the FLAC header itself is
            // read instead — same STREAMINFO facts, working on every device.
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
                    // marked CD-quality (16/44.1) FLAC — and any FLAC whose
                    // container omits BITS_PER_SAMPLE — as lossy, which pushed
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

    fun restoreSessionIfNeeded(): Boolean {
        if (_state.value.current != null) return false
        return restorePlaybackSession()
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
        val restoredDuration = session.durationMs.takeIf { it > 0L }
            ?: restoredQueue[restoredIndex].durationMs
            ?: findKnownDuration(restoredQueue[restoredIndex])
            ?: 0L
        _state.value = MusicPlayerState(
            current = restoredQueue[restoredIndex],
            queue = restoredQueue,
            currentIndex = restoredIndex,
            sourceLabel = session.sourceLabel,
            isEndlessQueue = session.isEndlessQueue,
            positionMs = session.positionMs.coerceAtLeast(0),
            durationMs = restoredDuration,
            shuffleEnabled = session.shuffleEnabled,
            repeatMode = session.repeatMode,
            speed = session.speed,
        )
        durationOwnerMediaId = restoredQueue[restoredIndex].mediaIdKey()
        pendingRestoredSession = session.copy(
            queue = restoredQueue,
            currentIndex = restoredIndex,
        )
        return true
    }

    /**
     * Narrow gate for the dead-videoId rematch: only explicit unplayability
     * (video removed, region-locked, no playable format) — never transient
     * network trouble, which must reach the normal retry path untouched.
     */
    private fun isDeadVideoIdFailure(error: Throwable): Boolean =
        error.causeChain().any { cause ->
            cause is ConfirmedUnplayableMediaException ||
                (cause is java.io.IOException &&
                    cause.message?.contains("Unable to resolve a playable audio stream") == true)
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
        if (sourceQueue.isEmpty()) return
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
        val currentDuration = snapshot.durationMs.takeIf { it > 0L }
            ?: snapshot.current?.durationMs
            ?: 0L
        val session = PersistedPlaybackSession(
            queue = persistedQueue,
            currentIndex = persistedIndex,
            positionMs = snapshot.positionMs.coerceAtLeast(0),
            sourceLabel = snapshot.sourceLabel,
            isEndlessQueue = snapshot.isEndlessQueue,
            shuffleEnabled = snapshot.shuffleEnabled,
            repeatMode = snapshot.repeatMode,
            speed = snapshot.speed,
            durationMs = currentDuration,
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
            (player.currentMediaItemIndex != previous.currentIndex || !isCurrentMediaMatch(player, previous.current))
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
        // blocked in runBlocking) and refresh() would downgrade the state —
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
            bitrateKbps = previous.bitrateKbps.takeIf { hasExplicitPrevious } ?: streamForCurrent?.bitrateKbps,
            audioCodec = previous.audioCodec.takeIf { hasExplicitPrevious } ?: streamForCurrent?.audioCodec,
            isLossless = (previous.isLossless && sameTrack) || (streamForCurrent?.isLossless == true),
            bitDepth = previous.bitDepth.takeIf { hasExplicitPrevious } ?: streamForCurrent?.bitDepth,
            samplingRateKHz = previous.samplingRateKHz.takeIf { hasExplicitPrevious } ?: streamForCurrent?.samplingRateKHz,
            sleepTimerRemainingMs = sleepTimerDeadlineMs?.minus(SystemClock.elapsedRealtime())?.coerceAtLeast(0),
            error = if (isPlayingState) null else previous.error,
        )
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
        /** Masks pre-seek position reads with the seek target while ExoPlayer lands. */
        const val SEEK_SETTLE_WINDOW_MS = 400L
        /** Signal-path report + stream-health sampling cadence while playing. */
        const val SIGNAL_PATH_TICK_MS = 1_000L
        const val MAX_PLAYBACK_RETRIES = 3
        /** First keep-alive backoff after a mid-track stream death. */
        const val RECOVERY_BACKOFF_BASE_MS = 1_000L
        /** Keep-alive backoff ceiling (1s → 2s → 4s, then flat). */
        const val RECOVERY_BACKOFF_MAX_MS = 4_000L
        /**
         * Keep-alive re-resolve attempts before giving up honestly: 1s → 2s →
         * 4s → 4s, so ~11s on top of the fast player-error retries. The
         * googlevideo throttle window it is waiting out is normally a few
         * seconds, and a short visible retry beats a minute of silence —
         * past that the tap-to-retry error is the honest answer.
         */
        const val MAX_PLAYBACK_RECOVERY_ATTEMPTS = 5
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
         *  silent window, not slow loading — legit rebuffers advance the
         *  buffer and reset the clock. Well above normal hitches, far below
         *  a full silent track. */
        const val RENDER_STALL_TIMEOUT_MS = 8_000L
        /** Rendering with an advancing position yet no active music stream
         *  this long means gated output, not a startup gap (those last a
         *  second or two). User-muted-to-zero still counts as active, so
         *  this never fires on a deliberately silent phone. */
        const val INAUDIBLE_TIMEOUT_MS = 10_000L
        /** Renderer-reset, then re-resolve. Beyond that the error/unavailable
         *  machinery owns the window — never loop recovery forever. */
        const val MAX_SILENT_RECOVERIES = 2
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
                .setExtras(
                    android.os.Bundle().apply {
                        val d = durationMs ?: resolved?.durationMs
                        if (d != null && d > 0L) putLong("durationMs", d)
                        internalTrackId?.takeIf { it.isNotBlank() }?.let { putString("internalTrackId", it) }
                    }
                )
                .build(),
        )
        .build()
}

    private fun PlayableTrack.mediaIdKey(): String = when {
    !playbackUrl.isNullOrBlank() -> "local:${playbackUrl}"
    !videoId.isNullOrBlank() -> videoId
    else -> "query:${artist.lowercase()}|${title.lowercase()}"
}

fun PlayableTrack.isLocalTrack(): Boolean =
    playbackUrl?.let { url ->
        (url.startsWith("/") || url.startsWith("content://") || url.startsWith("file://")) &&
            !url.contains("tidal_mpd") && !url.contains("segdrm_mpd")
    } == true || (mediaIdKey().startsWith("local:") && !mediaIdKey().contains("tidal_mpd") && !mediaIdKey().contains("segdrm_mpd"))

private fun MediaItem.toPlayableTrack(): PlayableTrack {
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

private fun PlayableTrack.queueKey(): String =
    playbackIdentity()?.cacheKey ?: mediaIdKey()

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
