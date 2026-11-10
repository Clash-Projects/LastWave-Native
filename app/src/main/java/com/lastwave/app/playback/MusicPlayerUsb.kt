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


    /** Device key already prompted for USB access (deny = no nagging). */
    private var usbPermissionPromptedKey: String? = null

    /**
     * Asks for direct USB access when Bit-Perfect is on and a USB audio
     * peripheral is present but not yet granted. Once per device: a deny is
     * respected until the DAC is detached (which clears the key via null).
     * No peripheral / already granted / toggle off = silent no-op.
     */
internal fun MusicPlayer.maybeRequestUsbPermission() {
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

internal fun MusicPlayer.findOutputDevice(deviceId: Int): AudioDeviceInfo? = runCatching {
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull { it.id == deviceId }
    }.getOrNull()

internal fun MusicPlayer.currentSourceRateHz(): Int? =
        if (isSpatialAudioCodec(_state.value.audioCodec)) 48000
        else _state.value.samplingRateKHz?.times(1000.0)?.toInt()?.takeIf { it > 0 }

internal fun MusicPlayer.dacRateFor(resolved: ResolvedStream): Int? =
        if (isSpatialAudioCodec(resolved.audioCodec) || isSpatialAudioCodec(_state.value.audioCodec)) 48000
        else resolved.samplingRateKHz?.times(1000.0)?.toInt()?.takeIf { it > 0 }
            ?: currentSourceRateHz()

internal fun MusicPlayer.applyDacRoutingFor(resolved: ResolvedStream) {
        applyDacRoutingFor(dacRateFor(resolved), resolved.audioCodec)
}

internal fun MusicPlayer.exclusiveUsbWanted(streamCodec: String? = null): Boolean {
        if (isCasting) return false
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
        if (!bitPerfectEnabled || !usbExclusivePrefEnabled) return false
        // E-AC-3 JOC / 360 RA is multichannel. Exclusive USB is stereo PCM
        // only — keep Android's decoder+mixer so Atmos actually plays.
        if (isSpatialAudioCodec(streamCodec) || isSpatialAudioCodec(_state.value.audioCodec)) return false
        val dac = usbDacMonitor.state.value.dac
        return dac?.hasUsbPeripheral == true && dac.usbPermissionGranted
    }

    /** Consecutive 1 Hz ticks with an implausible clock on an unconfirmed DAC. */
    internal var exclusiveBadDriftTicks = 0

    /**
     * Fork safety guard. A DAC whose clock was never confirmed and that
     * consumes the stream several % away from the configured rate is playing
     * garbage (seen as loud distorted noise on a UAC1 dongle). Healthy DACs
     * stay within a few hundred PPM, so 2 % sustained for 2 s is unambiguous.
     * Only while PCM is actually flowing: a network stall must not trip it.
     */
internal fun MusicPlayer.checkExclusiveHealth(driftPpm: Double?) {
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

    /**
     * Fork: direct output without usbdevfs (direct AudioTrack, then AAudio
     * MMAP). Bit-Perfect must be on (it is what turns the DSP off). Every route,
     * Bluetooth and USB included: on a USB DAC the sink tries usbdevfs
     * exclusive FIRST and only falls back to this when it does not engage
     * (permission not granted yet, driver refused, â€¦).
     */
internal fun MusicPlayer.internalDirectWanted(): Boolean {
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
internal fun MusicPlayer.recomputeUsbExclusivePref() {
        usbExclusivePrefEnabled = bitPerfectEnabled && usbExclusiveRawPref
    }

    /** True only for genuine Atmos decode: an E-AC-3 JOC decoder. A plain
     *  `audio/eac3`/`ac3` decoder (common, video-passthrough silicon) cannot
     *  render an Atmos music stream â€” treating it as capable used to request
     *  spatial manifests that ExoPlayer then choked on (3003 â†’ retry loop â†’
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
        val ffmpeg = FfmpegCompat.isUsable && runCatching {
            androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable() &&
                androidx.media3.decoder.ffmpeg.FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_E_AC3)
        }.getOrDefault(false)
        android.util.Log.i("MusicPlayer", "[ATMOS] decoder availability platform=$platform ffmpeg=$ffmpeg")
        platform || ffmpeg
    }

fun MusicPlayer.isSpatialAudioSupportedOnDevice(): Boolean {
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
internal object SpatializerHelper {
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

internal fun MusicPlayer.setupSpatializerListener() {
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
     * 88.2/96/176.4/192/352.8/384 kHz) â€” never a fixed 48 kHz â€” so no
     * LastWave resampler runs when the route accepts the source format.
     */
internal fun MusicPlayer.applyDacRoutingFor(sourceRateHz: Int?, streamCodec: String? = null) {
        val isSpatial = isSpatialAudioCodec(streamCodec) || isSpatialAudioCodec(_state.value.audioCodec)
        val effectiveRateHz = if (isSpatial) 48000 else sourceRateHz
        val dac = usbDacMonitor.state.value.dac
        val exclusiveWanted = !isSpatial && exclusiveUsbWanted(streamCodec)
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
        musicHapticsController.setUsbExclusiveModeActive(exclusive)
        // AudioFX on the player session would process nothing on either
        // exclusive path; the effects engine treats both the same way.
        val anyExclusive = exclusive || internalDirectOutput.isActive()
        runCatching { audioEffectsEngine.setUsbExclusiveActive(anyExclusive) }
        runCatching { secondaryEffects?.setUsbExclusiveActive(anyExclusive) }
        // Same-family fallback for DACs lacking the source rate (88.2 ->
        // 44.1 kHz): the exclusive stream opens at the supported rate with
        // native soxr conversion instead of failing onto the mixer. Null =
        // native rate (or no DAC / not exclusive): today's behavior.
        // The signal path stays honest automatically â€” the resampler check
        // fails, so a converted track can never report gold.
        val usbRates = exclusiveUsbOutput.supportedHardwareRatesHz()
        // Android AudioDeviceInfo rates describe the mixer route and may be
        // incomplete for usbdevfs exclusive access. Only an open USB descriptor
        // query may pre-select an exclusive fallback; otherwise let configure()
        // attempt the source clock and verify it with the DAC handshake.
        val fallbackHz = if (exclusiveWanted && dac != null && usbRates.isNotEmpty()) {
            selectExclusiveRateFallback(effectiveRateHz, usbRates)
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
     * signal-path verdict honestly reports scaled output until unity â€” MAX
     * (or a hardware-volume route) is still the only bit-exact state.
     * A manual change mid-session is respected and never overwritten.
     *
     * The persisted session below only settles restores owed by older builds
     * that used to auto-max; this build never strands the level at max.
     */
internal fun MusicPlayer.manageDacSystemVolume(engaged: Boolean) {
        if (engaged) return
        val manager = audioManager ?: return
        if (dacVolumeManaged || persistedVolumeManaged()) {
            dacVolumeManaged = false
            val saved = savedSystemVolume.takeIf { it >= 0 } ?: persistedSavedVolume()
            val max = if (cachedSysMaxVolume > 0) cachedSysMaxVolume else {
                runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0).also {
                    if (it > 0) cachedSysMaxVolume = it
                }
            }
            val current = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
            if (saved in 0 until max && current == max) {
                runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }
            }
            savedSystemVolume = -1
            persistVolumeSession(saved = -1, managed = false)
        }
    }

internal fun MusicPlayer.volumeSessionPrefs() =
        appContext.getSharedPreferences("lastwave_bitperfect_volume", Context.MODE_PRIVATE)

internal fun MusicPlayer.persistedVolumeManaged(): Boolean = runCatching {
        volumeSessionPrefs().getBoolean(KEY_VOLUME_MANAGED, false)
    }.getOrDefault(false)

internal fun MusicPlayer.persistedSavedVolume(): Int = runCatching {
        volumeSessionPrefs().getInt(KEY_VOLUME_SAVED, -1)
    }.getOrDefault(-1)

internal fun MusicPlayer.persistVolumeSession(saved: Int, managed: Boolean) {
        runCatching {
            volumeSessionPrefs().edit()
                .putInt(KEY_VOLUME_SAVED, saved)
                .putBoolean(KEY_VOLUME_MANAGED, managed)
                .apply()
        }
    }

    /** Rebuilds the verified signal-path report; main thread (reads player). */
internal fun MusicPlayer.updateSignalPath() {
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
        // (rate only â€” never depth). Without this a stream that reported only
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
        val sysMax = if (cachedSysMaxVolume > 0) cachedSysMaxVolume else {
            runCatching {
                audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            }.getOrNull()?.also { if (it > 0) cachedSysMaxVolume = it } ?: 0
        }
        val nowMs = SystemClock.elapsedRealtime()
        val sysVol = if (nowMs - lastSysVolumeCheckMs < 2500L) {
            lastCachedSysVolume
        } else {
            lastSysVolumeCheckMs = nowMs
            runCatching {
                audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC)
            }.getOrNull()?.also { lastCachedSysVolume = it } ?: lastCachedSysVolume
        }
        val sysFixed = cachedSysFixedVolume ?: runCatching {
            audioManager?.isVolumeFixed()
        }.getOrNull().also { cachedSysFixedVolume = it } ?: false
        val dac = usbDacMonitor.state.value.dac
        val srcLabel = snapshot.audioCodec
            ?: if (snapshot.isLossless) "LOSSLESS" else "Audio"
        usbExclusiveSinkActive = exclusive
        musicHapticsController.setUsbExclusiveModeActive(exclusive)
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
        val exclusiveAppVolume = if (exclusive) exclusiveUsbOutput.softwareGain() else appVolume
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
                parametricEqActive = com.lastwave.app.playback.eq.ParametricEqNative.isEngaged(),
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
                wireBits = exclusiveUsbOutput.activeWireBits(),
            ),
        )
    }
