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


    @MainThread
internal fun MusicPlayer.cancelCrossfade() {
        crossfadeStandbyArmed = false
        crossfadeStandbyPreparing = false
        lastCrossfadeLogKey = null
        _state.update { if (it.isCrossfading) it.copy(isCrossfading = false) else it }
        if (!playerDelegate.isInitialized()) return
        val outgoingToStop = outgoingPlayer
        outgoingPlayer = null
        outgoingToStop?.stop()
        outgoingToStop?.clearMediaItems()
        if (!autoMixCoordinator.get().isEnabled) {
            val standby = if (player === secondaryPlayer) playerDelegate.value else secondaryPlayer
            standby?.stop()
            standby?.clearMediaItems()
            standbyQueue = emptyList()
            standbyIndex = C.INDEX_UNSET
        }
        player.volume = appVolume
    }

    /**
     * The standby player without creating one. Never forces the lazy primary:
     * when [player] IS the secondary the primary was necessarily built first.
     */
internal fun MusicPlayer.currentStandby(): ExoPlayer? =
        if (player === secondaryPlayer) {
            playerDelegate.value.takeIf { playerDelegate.isInitialized() }
        } else {
            secondaryPlayer
        }

    /** Builds the standby on first crossfade; afterwards it is reused and swapped. */
    @MainThread
internal fun MusicPlayer.standbyOrCreate(): ExoPlayer = currentStandby() ?: run {
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
internal fun MusicPlayer.armCrossfadeStandby(nextIndex: Int, nextItem: MediaItem) {
        val standby = standbyOrCreate()
        standbyQueue = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        standbyIndex = nextIndex
        standby.volume = 0f
        standby.setAudioAttributes(player.audioAttributes, false)
        val isAlreadySet = standby.mediaItemCount == standbyQueue.size &&
            standby.currentMediaItemIndex == nextIndex &&
            standby.currentMediaItem?.mediaId == nextItem.mediaId &&
            standby.playbackState != Player.STATE_IDLE
        if (!isAlreadySet) {
            standby.pause()
            // The sink still holds the rate routed for the playing track, and
            // opened a 48 kHz next track at 44.1 kHz. Null opens at its own rate.
            playerToSink[standby]?.setOutputSampleRateOverride(null)
            standby.setMediaItems(standbyQueue, nextIndex, 0L)
            standby.prepare()
        }
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
internal fun MusicPlayer.logCrossfadeDecision(key: String, detail: String) {
        val now = SystemClock.elapsedRealtime()
        if (lastCrossfadeLogKey == key && now - lastCrossfadeLogAtMs < CROSSFADE_LOG_THROTTLE_MS) return
        lastCrossfadeLogKey = key
        lastCrossfadeLogAtMs = now
        android.util.Log.i("MusicPlayer", "Crossfade[$key]: $detail")
    }

    @MainThread
internal fun MusicPlayer.updateCrossfade(positionMs: Long): Boolean {
        // hot-swap just pauses the fade
        if (hotSwapActive) return false
        // Podcast episodes are long-form speech: a crossfade would cut the
        // tail of an episode into the next one.
        val crossfadeBlocked = !crossfadeEnabled || bitPerfectEnabled ||
            autoMixCoordinator.get().isEnabled ||
            _state.value.current?.mediaType == MediaType.PODCAST_EPISODE
        if (crossfadeBlocked) {
            // finish a fade that was already running, otherwise isCrossfading
            // stays true and the badge sticks on "Crossfade"
            if (_state.value.isCrossfading) cancelCrossfade()
            return false
        }
        outgoingPlayer?.let { outgoing ->
            // Drive the blend off the incoming (active) player position so a
            // pause parks the fade instead of counting down underneath it.
            val incomingPos = runCatching { player.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
            val progress = (incomingPos.toFloat() / overlapDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            if (progress >= 1f || outgoing.playerError != null) {
                cancelCrossfade()
            } else {
                // Perceptual fade: outgoing ramps down, incoming ramps up.
                // Equal-power sin/cos kept both near full level for most of
                // the window, which sounded like two songs mixed together.
                _state.update { if (!it.isCrossfading) it.copy(isCrossfading = true) else it }
                val rawIn = CrossfadeCurve.incomingGain(progress)
                val rawOut = if (outgoing.playbackState == Player.STATE_ENDED) 0f else CrossfadeCurve.outgoingGain(progress)
                val trim = CrossfadeCurve.headroom(rawIn, rawOut)
                val inGain = (rawIn * trim).coerceIn(0f, 1f)
                val outGain = (rawOut * trim).coerceIn(0f, 1f)

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
        // Catalog metadata is NOT a fallback here: it can belong to a
        // different edit of the song and cut it off mid-way. The resolver's
        // duration for this exact stream is safe.
        val timingDurationMs = player.duration.takeIf { it > 0L }
            ?: player.currentMediaItem?.localConfiguration?.customCacheKey
                ?.let(preparedStreams::get)?.durationMs?.takeIf { it > 0L }
            ?: return false
        val nextIndex = player.nextMediaItemIndex
        val hasNext = nextIndex != C.INDEX_UNSET &&
            nextIndex != player.currentMediaItemIndex &&
            nextIndex in 0 until player.mediaItemCount
        val nextItem = if (hasNext) player.getMediaItemAt(nextIndex) else null
        val stream = nextItem?.localConfiguration?.customCacheKey?.let(preparedStreams::get)
            ?: preparedStreams.values.firstOrNull { it.url == nextItem?.localConfiguration?.uri?.toString() }
        val livePosMs = runCatching { player.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
            .takeIf { it > 0L } ?: positionMs.coerceAtLeast(0L)
        // Pure state only: never materializes the standby just to ask whether it
        // is already armed, which would build a second player on the first tick
        // of every track.
        val armedForNext = crossfadeStandbyArmed && standbyIndex == nextIndex &&
            standbyQueue.size == player.mediaItemCount &&
            standbyQueue.getOrNull(nextIndex)?.mediaId == nextItem?.mediaId
        val standby = currentStandby()
        val isNextLocal = nextItem?.mediaId?.startsWith("local:") == true
        val isNextNonPlaceholder = nextItem?.localConfiguration?.uri?.scheme?.let { it != "lastwave" } == true
        val isStreamDead = stream?.isExpired() == true

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
                nextItemResolved = isNextNonPlaceholder && !isStreamDead,
                streamExpired = isStreamDead,
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
                if (standbyIndex != nextIndex || standbyQueue.getOrNull(nextIndex)?.mediaId != nextItem.mediaId) {
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
internal fun MusicPlayer.handOffCrossfade(standby: ExoPlayer, overlapMs: Long) {
        val outgoing = player
        val shuffleOrder = mutableListOf<Int>()
        val timeline = outgoing.currentTimeline
        var index = timeline.getFirstWindowIndex(outgoing.shuffleModeEnabled)
        while (index != C.INDEX_UNSET) {
            shuffleOrder.add(index)
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, outgoing.shuffleModeEnabled)
        }
        if (shuffleOrder.size == standby.mediaItemCount) {
            runCatching {
                standby.setShuffleOrder(DefaultShuffleOrder(shuffleOrder.toIntArray(), Random.nextLong()))
            }
        }
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
        if (outgoing.currentMediaItemIndex != C.INDEX_UNSET && outgoing.mediaItemCount > outgoing.currentMediaItemIndex + 1) {
            runCatching {
                outgoing.removeMediaItems(outgoing.currentMediaItemIndex + 1, outgoing.mediaItemCount)
            }
        }
        outgoingPlayer = outgoing
        activePlayer = standby
        musicHapticsController.bindToPlayer(standby)
        musicHapticsController.onAudioSessionIdChanged(standby.audioSessionId)
        standby.addListener(listener)
        // Keep handleAudioFocus = false: LastWave manages audio focus manually via requestMediaAudioFocus().
        // Setting true here activates ExoPlayer's internal AudioFocusManager which steals focus, causing Android
        // to send AUDIOFOCUS_LOSS (-1) to LastWave's own focus listener, immediately pausing playback.
        standby.setAudioAttributes(standby.audioAttributes, false)
        // Hand-off never starts the next song behind a pause.
        if (outgoing.playWhenReady) standby.play()
        // The standby is now the live player; the outgoing one is the next
        // standby. Cleared so the downloader resumes caching the new track.
        crossfadeStandbyArmed = false
        crossfadeStandbyPreparing = false
        lastCrossfadeLogKey = null
        _state.update { it.copy(isCrossfading = true) }
        android.util.Log.i(
            "MusicPlayer",
            "Crossfade: handing off '${outgoing.currentMediaItem?.mediaMetadata?.title}' -> " +
                "'${standby.currentMediaItem?.mediaMetadata?.title}' (overlap ${overlapDurationMs}ms)",
        )
        listener.onMediaItemTransition(standby.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        refresh(standby)
    }

internal fun MusicPlayer.updateBitPerfectState() {
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
        autoMixCoordinator.get().updateHardwareBypassState(effectiveBitPerfect || exclusiveUsbOutput.isActive())
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
internal fun MusicPlayer.updateSystemEffectsState() {
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
