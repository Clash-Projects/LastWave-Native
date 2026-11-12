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


internal const val WAKELOCK_RELEASE_GRACE_MS = 10_000L
internal const val EXPANDED_HEIGHT_DP = 152
internal const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
internal const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
internal const val CONTENT_STYLE_LIST = 1
internal const val MAX_CAR_QUEUE_ITEMS = 100
internal const val MAX_CAR_QUEUE_BEFORE_CURRENT = 50
internal const val MAX_WAVE_USER_PLAYLISTS = 5
internal const val WAVE_SEEK_DISCONTINUITY_MS = 1_500L
internal const val CHANNEL_ID = "lastwave_playback_v3"
internal const val LEGACY_CHANNEL_ID = "lastwave_playback_v2"
internal const val LEGACY_CHANNEL_ID_V1 = "lastwave_playback"
internal const val NOTIFICATION_ID = 4102
internal const val ACTION_PREVIOUS = "com.lastwave.app.playback.PREVIOUS"
internal const val ACTION_TOGGLE = "com.lastwave.app.playback.TOGGLE"
internal const val ACTION_NEXT = "com.lastwave.app.playback.NEXT"
internal const val ACTION_SHUFFLE = "com.lastwave.app.playback.SHUFFLE"
internal const val ACTION_REPEAT = "com.lastwave.app.playback.REPEAT"
internal const val ACTION_STOP = "com.lastwave.app.playback.STOP"

internal val TOPIC_SUFFIX_REGEX = Regex("""(?i)\s*-\s*topic$""")
internal val VEVO_SUFFIX_REGEX = Regex("""(?i)\s*vevo$""")
internal val TITLE_SUFFIX_REGEX = Regex(
    """(?i)\s*[\(\[](official\s*(music\s*)?video|official\s*audio|visualizer|audio|lyric\s*video|lyrics|hd|4k|remastered|hq)[\)\]]""",
)

internal data class CleanTrackMetadata(val title: String, val artist: String)

internal fun sameServiceState(previous: MusicPlayerState, current: MusicPlayerState): Boolean =
    previous.current == current.current &&
        previous.queue == current.queue &&
        previous.currentIndex == current.currentIndex &&
        previous.isPlaying == current.isPlaying &&
        previous.isBuffering == current.isBuffering &&
        previous.durationMs == current.durationMs &&
        previous.speed == current.speed &&
        previous.shuffleEnabled == current.shuffleEnabled &&
        previous.repeatMode == current.repeatMode &&
        previous.isCasting == current.isCasting &&
        previous.appVolume == current.appVolume &&
        (previous.isPlaying || current.isPlaying || previous.positionMs == current.positionMs)

/**
 * Foreground playback host and standard Android MediaSession bridge. It
 * gives the singleton player lock-screen, notification, headset, Bluetooth
 * and external hardware controls without requiring notification-listener
 * access.
 */
@AndroidEntryPoint
class MusicPlaybackService : MediaBrowserServiceCompat() {
    @Inject lateinit var musicPlayer: MusicPlayer
    @Inject lateinit var scrobbleRepository: ScrobbleRepository
    @Inject lateinit var scrobblerPreferences: ScrobblerPreferences

    @Inject lateinit var themeRepository: ThemeRepository
    @Inject lateinit var artworkRepository: com.lastwave.app.data.artwork.ArtworkRepository
    @Inject lateinit var androidAutoLibrary: AndroidAutoMediaLibrary
    @Inject lateinit var lyricsRepository: com.lastwave.app.data.lyrics.LyricsRepository
    @Inject lateinit var trackLikeController: TrackLikeController
    @Inject lateinit var playlistRepository: PlaylistRepository
    @Inject lateinit var jamPlaybackSync: com.lastwave.app.playback.jam.JamPlaybackSync

    // SupervisorJob stops sibling failure propagation; the handler below
    // additionally stops an unexpected exception in any fire-and-forget
    // launch (scrobble, artwork, notification publish) from reaching the
    // default handler and killing the whole process mid-playback. These are
    // swallowed on purpose — but they still reach the on-disk log, so a
    // repeatedly failing subsystem is visible instead of invisible.
    internal val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, error ->
                com.lastwave.app.diagnostics.AppLog.e(
                    "MusicPlaybackService",
                    "Suppressed playback service coroutine failure",
                    error,
                )
            },
    )
    // A handful of ROMs ship broken or deliberately crippled media-session
    // stacks where constructing a MediaSession or MediaController throws, or
    // the binder calls fail at runtime. Audio playback itself never depends
    // on either handle, so they stay nullable and every use degrades to
    // notification-only transport controls instead of crashing the service.
    internal var mediaSession: MediaSessionCompat? = null
    internal var platformSessionToken: MediaSession.Token? = null
    internal var ownController: MediaController? = null
    internal var settings = ScrobblerSettings()
    internal var detectorJob: Job? = null
    internal var detectedKey = ""
    internal var nowPlayingAnnouncedKey = ""
    @Volatile internal var nowPlayingInFlight = false
    internal var accumulatedMs = 0L
    internal var lastPositionMs = 0L
    internal var startedAtEpochSec = 0L
    internal var submissionAttempted = false
    internal var retryCount = 0
    internal var pendingPreviousTrack: PlayableTrack? = null
    internal var pendingPreviousStartedAt: Long = 0L
    internal var cleanedSourceTitle = ""
    internal var cleanedSourceArtist = ""
    internal var cleanedMetadata = CleanTrackMetadata("", "")
    internal var artworkJob: Job? = null
    /** Feeds synced lyrics to the OriginOS music card / Origin Island (vivo only). */
    internal var originLyricsJob: Job? = null
    internal var originLyricsMediaId: String? = null
    internal var artworkUrl: String? = null
    internal var artworkBitmap: Bitmap? = null
    /**
     * The platform's `config_mediaMetadataBitmapMaxSize` (320dp in AOSP),
     * resolved against this app's resources exactly as MediaSession does, so
     * session artwork is never larger than what the framework accepts as-is.
     */
    internal val sessionArtworkLimitPx: Int by lazy {
        runCatching {
            val id = resources.getIdentifier("config_mediaMetadataBitmapMaxSize", "dimen", "android")
            if (id != 0) resources.getDimensionPixelSize(id) else 0
        }.getOrDefault(0).takeIf { it > 0 }
            ?: (320 * resources.displayMetrics.density).toInt()
    }
    internal var notificationSignature = ""
    internal var widgetSignature = ""
    internal var waveWidgetSignature = ""
    internal var systemStateSignature = ""
    internal var legacyBroadcastSignature = ""
    internal var sessionQueueSignature = ""
    internal var carBrowseQueueSignature = ""
    @Volatile internal var isPlaybackForeground = false
    internal var artworkRequestKey = ""
    internal var notificationPalette = NotificationPalette.default()
    internal var wavePlaylistShortcuts: List<WavePlaylistShortcut> = emptyList()
    internal var lastWaveObservedState: MusicPlayerState? = null
    internal var lastWaveObservedAtMs = 0L
    internal var castVolumeProvider: VolumeProviderCompat? = null
    internal var playbackWakeLock: PowerManager.WakeLock? = null
    internal var playbackWifiLock: WifiManager.WifiLock? = null
    internal var wakeLockReleaseJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        WaveWidgetUpdater.setPlaybackServiceActive(true)
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        playbackWakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LastWave:PlaybackWakeLock",
        )?.apply { setReferenceCounted(false) }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        playbackWifiLock = wifiManager?.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "LastWave:PlaybackWifiLock",
        )?.apply { setReferenceCounted(false) }

        createNotificationChannel()
        runCatching {
            val session = MediaSessionCompat(this, "LastWavePlayer").apply {
                setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
                )
                setPlaybackToLocal(android.media.AudioManager.STREAM_MUSIC)
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() = musicPlayer.resume()
                    override fun onPause() = musicPlayer.pause()
                    override fun onSkipToNext() = musicPlayer.next()
                    override fun onSkipToPrevious() = musicPlayer.previous()
                    override fun onSeekTo(pos: Long) = musicPlayer.seekTo(pos)
                    // A stop from an external controller (Bluetooth/car unit,
                    // OEM media card, system UI) ends playback but must not
                    // destroy the session. Wiping the queue, the persisted
                    // session and the notification here turned a routine focus
                    // hand-off — another app taking over the media card, which
                    // some OEM skins report as a stop — into "the song
                    // disappears and I have to reopen the app". The
                    // notification's own Stop action and ACTION_STOP still do
                    // the destructive stop.
                    override fun onStop() = musicPlayer.pause()
                    override fun onSkipToQueueItem(id: Long) = musicPlayer.seekToQueueItem(id.toInt())
                    override fun onSetShuffleMode(shuffleMode: Int) {
                        musicPlayer.setShuffleEnabled(shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE)
                    }
                    override fun onSetRepeatMode(repeatMode: Int) {
                        musicPlayer.setRepeatMode(
                            when (repeatMode) {
                                PlaybackStateCompat.REPEAT_MODE_ONE -> androidx.media3.common.Player.REPEAT_MODE_ONE
                                PlaybackStateCompat.REPEAT_MODE_ALL,
                                PlaybackStateCompat.REPEAT_MODE_GROUP -> androidx.media3.common.Player.REPEAT_MODE_ALL
                                else -> androidx.media3.common.Player.REPEAT_MODE_OFF
                            },
                        )
                    }
                    override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                        if (mediaId == OriginOsMusicCard.EMPTY_LIST_MEDIA_ID) return
                        mediaId?.let { playCarMediaId(OriginOsMusicCard.queueMediaIdOf(it)) }
                    }
                    override fun onSetRating(rating: RatingCompat?) {
                        if (rating?.ratingStyle == RatingCompat.RATING_HEART) setCurrentTrackLiked(rating.hasHeart())
                    }
                    override fun onCustomAction(action: String?, extras: Bundle?) {
                        if (action == OriginOsMusicCard.ACTION_PLAY_MODE) {
                            setOriginOsLoopMode(extras?.getInt(OriginOsMusicCard.KEY_LOOP_MODE) ?: 0)
                        }
                    }
                    override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                        promoteForPlayback()
                        playCarSearch(resolveCarSearchQuery(query, extras))
                    }
                })
                setSessionActivity(openAppPendingIntent())
                // The heart of external controllers, such as the OriginOS music card.
                setRatingType(RatingCompat.RATING_HEART)
                // KWGT / Bluetooth / headset discovery happens while idle too:
                // publish an initial state so media buttons (and widget play
                // buttons) can wake the player before any track is queued.
                setPlaybackState(
                    PlaybackStateCompat.Builder()
                        .setActions(
                            PlaybackStateCompat.ACTION_PLAY or
                                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                                PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH,
                        )
                        .setState(PlaybackStateCompat.STATE_NONE, 0, 0f)
                        .build(),
                )
                isActive = true
            }
            mediaSession = session
            sessionToken = session.sessionToken
            platformSessionToken = session.sessionToken.token as? MediaSession.Token
            ownController = platformSessionToken?.let { token -> MediaController(this, token) }
            ActiveMediaSessionHolder.ownToken = platformSessionToken
        }.onFailure { error ->
            android.util.Log.e("MusicPlaybackService", "System media integration unavailable; continuing audio-only", error)
            runCatching { mediaSession?.release() }
            mediaSession = null
            platformSessionToken = null
            ownController = null
            ActiveMediaSessionHolder.ownToken = null
        }
        notificationPalette = NotificationPalette.from(themeRepository.uiState.value.colorScheme)
        scope.launch {
            wavePlaylistShortcuts = loadWavePlaylistShortcuts()
            publishWaveWidget(musicPlayer.state.value, force = true)
        }
        scope.launch { scrobblerPreferences.settings.collect { settings = it } }
        scope.launch {
            themeRepository.uiState.collectLatest { theme ->
                val newPalette = NotificationPalette.from(theme.colorScheme)
                // Only force a notification rebuild when the palette actually
                // changed — unrelated DataStore settings also flow through this
                // state and used to trigger pointless RemoteViews rebuilds.
                if (newPalette != notificationPalette) {
                    notificationPalette = newPalette
                    notificationSignature = ""
                    publishNotification(musicPlayer.state.value, force = true)
                }
            }
        }
        scope.launch {
            musicPlayer.state
                .onEach(::detectWaveSeek)
                // Position/buffer ticks arrive about 16 times per second for
                // the seek bar. Notification, widget and MediaSession chrome
                // do not need that cadence; Android extrapolates a playing
                // position from the published speed. Filter those ticks before
                // doing string work, artwork lookup and binder-state checks.
                .distinctUntilChanged(::sameServiceState)
                .collect { state ->
                    com.lastwave.app.diagnostics.SessionMarker.noteActivity(
                        this@MusicPlaybackService,
                        playing = state.isPlaying,
                        trackLoaded = state.current != null,
                    )
                    updateWakeLocks(state)
                    requestArtwork(state.current)
                    publishSystemState(state)
                    publishNotification(state)
                    publishWidget(state)
                    publishOriginOsLyrics(state)
                    publishWaveWidget(state)
                    publishCarBrowseState(state)
                    detectTransition(state)
                    themeRepository.updateNowPlayingArtwork(state.current?.title, state.current?.artist)
                }
        }
        scope.launch {
            artworkRepository.resolved.collect { map ->
                val current = musicPlayer.state.value.current ?: return@collect
                val key = com.lastwave.app.data.artwork.ArtworkNormalizer.cacheKey(current.title, current.artist)
                val resolvedUrl = map[key]
                if (!resolvedUrl.isNullOrBlank() && resolvedUrl != artworkUrl) {
                    fetchArtwork(resolvedUrl)
                    if (musicPlayer.state.value.isPlaying) {
                    }
                }
            }
        }
        scope.launch {
            androidAutoLibrary.playlistChanges.collect {
                notifyCarLibraryChanged(AndroidAutoMediaLibrary.PLAYLISTS_ID)
                notifyChildrenChangedSafely(OriginOsMusicCard.LIST_FAVORITE)
                wavePlaylistShortcuts = loadWavePlaylistShortcuts()
                publishWaveWidget(musicPlayer.state.value, force = true)
            }
        }
        scope.launch {
            androidAutoLibrary.downloadChanges.distinctUntilChanged().collect {
                notifyCarLibraryChanged(AndroidAutoMediaLibrary.DOWNLOADS_ID)
                notifyChildrenChangedSafely(OriginOsMusicCard.LIST_LOCAL)
            }
        }
        scope.launch {
            // The heart of the metadata follows a like made anywhere in the app.
            trackLikeController.likedTrackKeys.drop(1).collect {
                publishSystemState(musicPlayer.state.value)
                publishWaveWidget(musicPlayer.state.value, force = true)
            }
        }
        startDetector()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot? {
        if (mediaSession == null) return null
        // KWGT, Wear OS, Bluetooth companions and other third-party
        // controllers connect here as ordinary user apps — NOT as system /
        // car hosts. Returning null rejects the connection
        // (onConnectionFailed), which is why KWGT never listed LastWave as
        // a media app and its transport buttons did nothing. The browse tree
        // only exposes queue/playlist/download metadata, so any
        // UID-verified caller may browse; transport stays gated by the
        // session callback itself.
        if (!isAllowedMediaClient(clientPackageName, clientUid)) return null
        return BrowserRoot(
            AndroidAutoMediaLibrary.ROOT_ID,
            Bundle().apply {
                putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST)
                putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST)
                putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
            },
        )
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>,
    ) {
        result.detach()
        scope.launch {
            val children = runCatching {
                withContext(Dispatchers.IO) {
                    androidAutoLibrary.loadChildren(parentId, musicPlayer.state.value)
                }
            }.onFailure { error ->
                android.util.Log.w("MusicPlaybackService", "Android Auto browse failed for $parentId", error)
            }.getOrDefault(emptyList())
            result.sendResult(children)
        }
    }

    /**
     * The lists of the OriginOS music card: the queue, the playlists and the
     * downloads, all from the Android Auto browse tree. Everything else is
     * browsed as before.
     */
    override fun onLoadChildren(
        parentId: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>,
        options: Bundle,
    ) {
        if (!OriginOsMusicCard.isList(parentId)) {
            super.onLoadChildren(parentId, result, options)
            return
        }
        result.detach()
        scope.launch {
            // Every list is a single page.
            val firstPage = options.getInt(OriginOsMusicCard.KEY_PAGE) == 0
            val children = if (!firstPage) emptyList() else runCatching {
                val state = musicPlayer.state.value
                when (parentId) {
                    OriginOsMusicCard.LIST_CURRENT -> state.queue.mapIndexed { index, track ->
                        MediaBrowserCompat.MediaItem(
                            MediaDescriptionCompat.Builder()
                                // Matches the media id of the metadata, so the card marks what plays.
                                .setMediaId(OriginOsMusicCard.mediaIdOf(index, track))
                                .setTitle(track.title)
                                .setSubtitle(track.artist)
                                .build(),
                            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
                        )
                    }.let { OriginOsMusicCard.singlePage(it, "Nothing playing", "Play something in LastWave") }
                    OriginOsMusicCard.LIST_FAVORITE -> OriginOsMusicCard.singlePage(
                        withContext(Dispatchers.IO) {
                            androidAutoLibrary.loadChildren(AndroidAutoMediaLibrary.PLAYLISTS_ID, state)
                        },
                        "No playlists",
                        "Your playlists show here",
                    )
                    else -> OriginOsMusicCard.singlePage(
                        withContext(Dispatchers.IO) {
                            androidAutoLibrary.loadChildren(AndroidAutoMediaLibrary.DOWNLOADS_ID, state)
                        },
                        "No downloads",
                        "Music downloaded in LastWave shows here",
                    )
                }
            }.onFailure { error ->
                android.util.Log.w("MusicPlaybackService", "Music card list failed for $parentId", error)
            }.getOrDefault(emptyList())
            result.sendResult(children)
        }
    }


    override fun onSearch(
        query: String,
        extras: Bundle?,
        result: Result<List<MediaBrowserCompat.MediaItem>>,
    ) {
        result.detach()
        scope.launch {
            val items = runCatching {
                withContext(Dispatchers.IO) { androidAutoLibrary.search(query) }
            }.onFailure { error ->
                android.util.Log.w("MusicPlaybackService", "Android Auto search failed", error)
            }.getOrDefault(emptyList())
            result.sendResult(items)
        }
    }



    /**
     * Stock Android music broadcasts (`com.android.music.metachanged` /
     * `playstatechanged` / `playbackcomplete`, plus the HTC doubles). KWGT,
     * Tasker, Zooper-style widgets and a long tail of headset/Bluetooth
     * helpers still listen for these alongside the modern MediaSession, and
     * several only list an app as a "media player" once they have seen one.
     * One ordered broadcast per track / play-state change; position ticks
     * are intentionally NOT rebroadcast.
     */
    internal fun broadcastLegacyState(state: MusicPlayerState) {
        val track = state.current
        val playing = state.isPlaying
        val signature = "${track?.title}|${track?.artist}|${track?.album}|$playing"
        if (signature == legacyBroadcastSignature) return
        legacyBroadcastSignature = signature
        val title = track?.title.orEmpty()
        val artist = track?.artist.orEmpty()
        val album = track?.album.orEmpty()
        val extras = { intent: Intent ->
            intent
                .putExtra("track", title)
                .putExtra("artist", artist)
                .putExtra("album", album)
                .putExtra("playing", playing)
                .putExtra("isPlaying", playing)
                .putExtra("duration", state.durationMs)
                .putExtra("position", state.positionMs)
                .putExtra("package", packageName)
        }
        runCatching {
            if (track != null) {
                sendBroadcast(
                    extras(Intent("com.android.music.metachanged"))
                        .putExtra("id", state.currentIndex.toLong()),
                )
                sendBroadcast(extras(Intent("com.htc.music.metachanged")))
            } else {
                sendBroadcast(Intent("com.android.music.playbackcomplete"))
            }
            sendBroadcast(extras(Intent("com.android.music.playstatechanged")))
            sendBroadcast(extras(Intent("com.htc.music.playstatechanged")))
        }.onFailure { error ->
            android.util.Log.w("MusicPlaybackService", "Legacy player broadcast failed", error)
        }
    }

    internal fun updateWakeLocks(state: MusicPlayerState) {
        val shouldHold = state.isPlaying || state.isBuffering
        if (shouldHold) {
            wakeLockReleaseJob?.cancel()
            wakeLockReleaseJob = null
            runCatching {
                if (playbackWakeLock?.isHeld == false) {
                    playbackWakeLock?.acquire()
                }
                if (playbackWifiLock?.isHeld == false) {
                    playbackWifiLock?.acquire()
                }
            }
        } else {
            if (wakeLockReleaseJob == null && (playbackWakeLock?.isHeld == true || playbackWifiLock?.isHeld == true)) {
                wakeLockReleaseJob = scope.launch {
                    delay(WAKELOCK_RELEASE_GRACE_MS)
                    runCatching {
                        if (playbackWakeLock?.isHeld == true) playbackWakeLock?.release()
                        if (playbackWifiLock?.isHeld == true) playbackWifiLock?.release()
                    }
                    wakeLockReleaseJob = null
                }
            }
        }
    }

    internal fun promoteForPlayback(): Boolean {
        if (isPlaybackForeground) return true
        // Browse-only Android Auto connections stay notification-free. A
        // started playback request promotes the already-bound service here.
        val notification = buildNotification(musicPlayer.state.value, artworkBitmap)
        val promoted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { firstError ->
            android.util.Log.w("MusicPlaybackService", "Rich startup notification rejected; retrying minimal", firstError)
        }.recoverCatching {
            val minimal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION") Notification.Builder(this)
            }.setSmallIcon(R.drawable.ic_launcher_logo)
             .setContentTitle(musicPlayer.state.value.current?.title ?: "LastWave")
             .setContentText(musicPlayer.state.value.current?.artist ?: "Music player")
             .setOnlyAlertOnce(true)
             .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    minimal,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, minimal)
            }
        }.isSuccess
        isPlaybackForeground = promoted
        if (!promoted) {
            android.util.Log.w("MusicPlaybackService", "Foreground promotion refused by system")
        }
        return promoted
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground only when playback is actually active. A
        // pause/stop/no-op command must not force a media-playback FGS; when an
        // action such as PLAY starts playback the state collector promotes the
        // service on the isPlaying edge. If we do promote while active and the
        // system refuses, bail with START_NOT_STICKY as before.
        val state = musicPlayer.state.value
        val fromWaveWidget = intent?.getBooleanExtra(
            WaveWidgetActionReceiver.EXTRA_WAVE_WIDGET,
            false,
        ) == true
        // Voice search (Google Assistant / Gemini "play X on LastWave") and
        // media buttons arrive through startForegroundService while nothing
        // plays yet. Not promoting them crashed the app ~5s later with
        // ForegroundServiceDidNotStartInTimeException; if nothing ends up
        // playing, demoteIfIdle() drops the notification again.
        val startsPlayback = intent?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH ||
            intent?.action == Intent.ACTION_MEDIA_BUTTON
        if (state.isPlaying || state.isBuffering || intent?.action == null || startsPlayback || state.current != null || fromWaveWidget) {
            if (!promoteForPlayback()) return START_NOT_STICKY
        }
        when (intent?.action) {
            Intent.ACTION_MEDIA_BUTTON -> {
                if (fromWaveWidget) handleWaveWidgetMediaButton(intent)
                else androidx.media.session.MediaButtonReceiver.handleIntent(mediaSession, intent)
            }
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> playCarSearch(
                resolveCarSearchQuery(intent.getStringExtra(android.app.SearchManager.QUERY), intent.extras),
            )
            ACTION_PREVIOUS -> musicPlayer.previous()
            ACTION_TOGGLE -> musicPlayer.togglePlayPause()
            ACTION_NEXT -> musicPlayer.next()
            ACTION_SHUFFLE -> musicPlayer.toggleShuffle()
            ACTION_REPEAT -> musicPlayer.cycleRepeatMode()
            ACTION_STOP -> musicPlayer.stopAndClear()
        }
        return START_NOT_STICKY
    }



    override fun onTaskRemoved(rootIntent: Intent?) {
        // stopWithTask is false in the Manifest because it cannot express the
        // user's choice: this service deliberately stays a media-playback
        // foreground service whenever a track is loaded (even paused), and a
        // foreground service survives task removal. So the swipe behavior is
        // handled explicitly here.
        val shouldStop = musicPlayer.stopPlaybackOnTaskRemoved ||
            musicPlayer.state.value.current == null
        if (shouldStop) {
            // clearSession = false keeps the last queue/track restorable on the
            // next launch while still stopping audio, notification, cast and
            // wake/wifi locks immediately.
            musicPlayer.stopAndClear(clearSession = false)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Safety net: if the service is torn down while the singleton player is
        // still producing audio (an OEM stop, a system-initiated service stop
        // or a missed task-removal path), never leave orphaned playback behind.
        val playerState = musicPlayer.state.value
        if (playerState.isPlaying || playerState.isBuffering) {
            musicPlayer.stopAndClear(clearSession = false)
        }
        wakeLockReleaseJob?.cancel()
        wakeLockReleaseJob = null
        runCatching { if (playbackWakeLock?.isHeld == true) playbackWakeLock?.release() }
        runCatching { if (playbackWifiLock?.isHeld == true) playbackWifiLock?.release() }
        // The service is the last thing standing in a playback-only session, so
        // this is the last chance to get buffered log records onto disk.
        com.lastwave.app.diagnostics.AppLog.flush()
        detectorJob?.cancel()
        artworkJob?.cancel()
        WaveWidgetUpdater.setPlaybackServiceActive(false)
        if (isPlaybackForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
            isPlaybackForeground = false
        }
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        val releasedToken = platformSessionToken
        runCatching { mediaSession?.isActive = false }
        runCatching { mediaSession?.release() }
        castVolumeProvider = null
        // Let KWGT-style listeners clear their cached track on service death.
        runCatching { sendBroadcast(Intent("com.android.music.playbackcomplete")) }
        platformSessionToken = null
        ownController?.let { controller -> ActiveMediaSessionHolder.clear(controller) }
        ActiveMediaSessionHolder.clearToken(releasedToken)
        scope.cancel()
        super.onDestroy()
    }



    internal companion object {
        const val WAKELOCK_RELEASE_GRACE_MS = 10_000L
        // Expanded RemoteViews content needs ~117dp (brand + title + artist +
        // album + 44dp controls + margins); 128dp clipped the transport row
        // whenever the album line was visible. 152dp leaves breathing room.
        const val EXPANDED_HEIGHT_DP = 152
        const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val CONTENT_STYLE_LIST = 1
        const val MAX_CAR_QUEUE_ITEMS = 100
        const val MAX_CAR_QUEUE_BEFORE_CURRENT = 50
        const val MAX_WAVE_USER_PLAYLISTS = 5
        const val WAVE_SEEK_DISCONTINUITY_MS = 1_500L
        const val CHANNEL_ID = "lastwave_playback_v3"
        const val LEGACY_CHANNEL_ID = "lastwave_playback_v2"
        const val LEGACY_CHANNEL_ID_V1 = "lastwave_playback"
        const val NOTIFICATION_ID = 4102
        const val ACTION_PREVIOUS = "com.lastwave.app.playback.PREVIOUS"
        const val ACTION_TOGGLE = "com.lastwave.app.playback.TOGGLE"
        const val ACTION_NEXT = "com.lastwave.app.playback.NEXT"
        const val ACTION_SHUFFLE = "com.lastwave.app.playback.SHUFFLE"
        const val ACTION_REPEAT = "com.lastwave.app.playback.REPEAT"
        const val ACTION_STOP = "com.lastwave.app.playback.STOP"
    }
}
