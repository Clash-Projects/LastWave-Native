package com.lastwave.app.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.sp
import com.lastwave.app.ui.common.ExpressiveMotion
import kotlinx.coroutines.Job
import android.content.ContextWrapper
import android.util.Log
import android.view.SurfaceView
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.mutableDoubleStateOf
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import com.lastwave.app.playback.ChunkedRangeDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.lastwave.app.R
import com.lastwave.app.data.music.YouTubeVideoStream
import com.lastwave.app.data.music.TextMatch
import com.lastwave.app.data.video.SyncedVideoRepository
import com.lastwave.app.data.video.VideoSearchCandidate
import com.lastwave.app.data.video.extractYoutubeVideoId
import com.lastwave.app.data.video.shouldPreloadVideo
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.common.EdgeToEdgeDialogWindow
import com.lastwave.app.ui.common.ExpressiveLoadingIndicator
import com.lastwave.app.ui.common.adaptiveContentWidth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

private const val TAG = "SyncedVideoPlayer"
private const val VIDEO_PREFS_NAME = "synced_video"
private const val VIDEO_OVERRIDE_PREFS_NAME = "synced_video_overrides"
private const val PREF_VIDEO_LAST_USED_MS = "video_last_used_ms"
internal const val VIDEO_PRELOAD_DELAY_MS = 1_500L
private const val MAX_LINK_REFRESHES_PER_SONG = 2

/** Detects taps on the video without consuming gestures already handled by overlay controls. */
private fun Modifier.videoTapGestures(
    onTap: () -> Unit,
    onDoubleTap: (Offset, Float) -> Unit,
): Modifier = pointerInput(onTap, onDoubleTap) {
    var lastTapAtMs = 0L
    var lastTapPosition = Offset.Zero
    awaitPointerEventScope {
        while (true) {
            val up = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull {
                it.previousPressed && !it.pressed && !it.isConsumed
            } ?: continue
            val now = SystemClock.uptimeMillis()
            val dx = up.position.x - lastTapPosition.x
            val dy = up.position.y - lastTapPosition.y
            val isDoubleTap = now - lastTapAtMs in 1L..300L && dx * dx + dy * dy <= 80f * 80f
            if (isDoubleTap) {
                onDoubleTap(up.position, size.width.toFloat())
                lastTapAtMs = 0L
            } else {
                onTap()
                lastTapAtMs = now
                lastTapPosition = up.position
            }
        }
    }
}

/** IPv4-first on ordinary networks; VPN transport detection preserves the VPN resolver's address order. */
private fun videoMediaHttp(context: android.content.Context): okhttp3.OkHttpClient =
    okhttp3.OkHttpClient.Builder()
        .dns(com.lastwave.app.data.network.Ipv4FirstDnsResolver(context = context))
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

/**
 * Starts with enough estimated bandwidth to reach high-resolution renditions,
 * then adapts to measured bandwidth. The quality-up threshold sits below the
 * default 10s because one video player buffers only 8s and could never reach it.
 */
@OptIn(UnstableApi::class)
private fun adaptiveVideoPlayerBuilder(
    context: android.content.Context,
    dataSourceFactory: DataSource.Factory,
): ExoPlayer.Builder = ExoPlayer.Builder(context)
    .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
    .setBandwidthMeter(
        DefaultBandwidthMeter.Builder(context)
            .setInitialBitrateEstimate(20_000_000L)
            .build(),
    )
    .setTrackSelector(
        DefaultTrackSelector(
            context,
            AdaptiveTrackSelection.Factory(
                /* minDurationForQualityIncreaseMs = */ 4_000,
                /* maxDurationForQualityDecreaseMs = */ 6_000,
                /* minDurationToRetainAfterDiscardMs = */ 6_000,
                /* bandwidthFraction = */ 0.85f,
            ),
        ),
    )

sealed interface SyncedVideoUiState {
    data object Loading : SyncedVideoUiState
    data class Ready(
        val bundle: com.lastwave.app.data.music.SyncedVideoStreamBundle,
        val activeStream: YouTubeVideoStream,
        val selectedHeight: Int? = null,
        val adaptiveFailed: Boolean = false,
    ) : SyncedVideoUiState {
        val stream: YouTubeVideoStream get() = activeStream
        val isAuto: Boolean get() = selectedHeight == null || selectedHeight <= 0
        private val adaptiveUri: String?
            get() = bundle.adaptiveManifestUri.takeIf { isAuto && !adaptiveFailed }
        val usesAdaptive: Boolean get() = adaptiveUri != null
        val playbackUri: String get() = adaptiveUri ?: activeStream.url
        val playbackMimeType: String?
            get() = if (usesAdaptive) MimeTypes.APPLICATION_MPD else activeStream.mimeType?.takeIf { it.isNotBlank() }
        val currentQualityLabel: String
            get() = if (isAuto) "Auto" else activeStream.displayQuality
    }
    data class Error(val message: String, val noMatchingVideo: Boolean = false) : SyncedVideoUiState
}

sealed interface VideoPickerUiState {
    data object Hidden : VideoPickerUiState
    data object Loading : VideoPickerUiState
    data class Results(val candidates: List<VideoSearchCandidate>, val savedVideoId: String?) : VideoPickerUiState
    data class Error(val message: String) : VideoPickerUiState
}

@HiltViewModel
class SyncedVideoViewModel @Inject constructor(
    private val videoRepository: SyncedVideoRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {
    private val _uiState = MutableStateFlow<SyncedVideoUiState>(SyncedVideoUiState.Loading)
    val uiState: StateFlow<SyncedVideoUiState> = _uiState.asStateFlow()

    private val _isVideoActive = MutableStateFlow(false)
    val isVideoActive: StateFlow<Boolean> = _isVideoActive.asStateFlow()

    private val videoPrefs by lazy { appContext.getSharedPreferences(VIDEO_PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    private val videoOverridePrefs by lazy {
        appContext.getSharedPreferences(VIDEO_OVERRIDE_PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }
    private val _videoPickerState = MutableStateFlow<VideoPickerUiState>(VideoPickerUiState.Hidden)
    val videoPickerState: StateFlow<VideoPickerUiState> = _videoPickerState.asStateFlow()
    private var linkRefreshesLeft = 0
    private var refreshLinks: ((String?) -> Unit)? = null
    private var alignJob: Job? = null
    private var alignedVideoId: String? = null
    private var currentAudioVideoId: String? = null
    private data class VideoPickerRequest(
        val title: String,
        val artist: String,
        val audioVideoId: String?,
        val durationMs: Long?,
    )
    private var videoPickerRequest: VideoPickerRequest? = null
    private var videoSearchJob: Job? = null

    private var currentKey: String? = null
    private var loadJob: kotlinx.coroutines.Job? = null
    private var openedAwaitingLoad = false

    /** Shared HTTP factory for video streams – reused across cover and fullscreen players. */
    val httpFactory: OkHttpDataSource.Factory = OkHttpDataSource.Factory(videoMediaHttp(appContext))

    private var _playerInitialized = false

    /** Shared ExoPlayer surviving composition exits. Released only when ViewModel is cleared. */
    @OptIn(UnstableApi::class)
    val player: ExoPlayer by lazy {
        _playerInitialized = true
        val defaultDataSourceFactory = DefaultDataSource.Factory(appContext, httpFactory)
        val chunkedFactory = DataSource.Factory {
            ChunkedRangeDataSource(defaultDataSourceFactory.createDataSource(), chunkSize = 256 * 1024L)
        }
        adaptiveVideoPlayerBuilder(appContext, chunkedFactory)
            .build()
            .apply {
                volume = 0f
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
            }
    }

    fun setVideoActive(active: Boolean) {
        openedAwaitingLoad = active
        _isVideoActive.value = active
        if (active) {
            videoPrefs.edit().putLong(PREF_VIDEO_LAST_USED_MS, System.currentTimeMillis()).apply()
            startAlignmentIfNeeded()
        }
    }

    fun isVideoUnavailableFor(
        title: String,
        artist: String,
        videoId: String?,
        durationMs: Long?,
        state: SyncedVideoUiState,
    ): Boolean {
        val selectedVideoId = savedVideoOverride(title, artist)
        val key = videoRequestKey(title, artist, videoId, durationMs, selectedVideoId)
        return currentKey == key && (state as? SyncedVideoUiState.Error)?.noMatchingVideo == true
    }

    /** Readies the video before the tap, only for people likely to want one. */
    fun preloadVideo(title: String, artist: String, videoId: String?, durationMs: Long? = null) {
        val lastUsedMs = videoPrefs.getLong(PREF_VIDEO_LAST_USED_MS, -1L).takeIf { it >= 0L }
        val batterySaverOn = appContext.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
        val preload = shouldPreloadVideo(lastUsedMs, System.currentTimeMillis(), batterySaverOn)
        Log.i(TAG, "Preload ${if (preload) "started" else "skipped"} for '$title' (lastUsed=$lastUsedMs batterySaver=$batterySaverOn)")
        if (preload) loadVideo(title, artist, videoId, durationMs)
    }

    /** Downloads a few MB, so it waits for an open video unless the network is unmetered. */
    private fun startAlignmentIfNeeded() {
        val bundle = (_uiState.value as? SyncedVideoUiState.Ready)?.bundle ?: return
        val unmetered = appContext.getSystemService(android.net.ConnectivityManager::class.java)
            ?.isActiveNetworkMetered == false
        if ((!_isVideoActive.value && !unmetered) || alignedVideoId == bundle.videoId) return
        alignedVideoId = bundle.videoId
        val audioVideoId = currentAudioVideoId
        alignJob?.cancel()
        alignJob = viewModelScope.launch {
            // The sync loop seeks to the new offset once it lands.
            videoRepository.measureContentOffsetMs(bundle, audioVideoId)?.let { offsetMs ->
                val ready = _uiState.value as? SyncedVideoUiState.Ready
                if (ready?.bundle?.videoId == bundle.videoId) {
                    _uiState.value = ready.copy(bundle = ready.bundle.copy(introOffsetMs = offsetMs))
                }
            }
        }
    }

    fun loadVideo(title: String, artist: String, videoId: String?, durationMs: Long? = null) {
        val selectedVideoId = savedVideoOverride(title, artist)
        val key = videoRequestKey(title, artist, videoId, durationMs, selectedVideoId)
        // The video view only calls this once shown, so opening it on a new song also
        // changes the key. Only a track change under an already open video should close it.
        val justOpened = openedAwaitingLoad
        openedAwaitingLoad = false
        // A resolve can take several seconds; a second one would reload the player when it lands.
        if (currentKey == key && (_uiState.value is SyncedVideoUiState.Ready || loadJob?.isActive == true)) return
        loadJob?.cancel()
        alignJob?.cancel()
        alignedVideoId = null
        currentAudioVideoId = videoId

        if (currentKey != null && currentKey != key) {
            // Track changed – reset video toggle and stop player
            if (!justOpened) _isVideoActive.value = false
            if (_playerInitialized) {
                player.stop()
                player.clearMediaItems()
            }
        }
        currentKey = key
        linkRefreshesLeft = MAX_LINK_REFRESHES_PER_SONG
        refreshLinks = { avoidClientName -> resolveStream(title, artist, videoId, durationMs, selectedVideoId, avoidClientName) }
        resolveStream(title, artist, videoId, durationMs, selectedVideoId, avoidClientName = null)
    }

    private fun videoRequestKey(
        title: String,
        artist: String,
        videoId: String?,
        durationMs: Long?,
        selectedVideoId: String?,
    ): String = "$title|$artist|$videoId|$durationMs|$selectedVideoId"

    private fun resolveStream(
        title: String,
        artist: String,
        videoId: String?,
        durationMs: Long?,
        selectedVideoId: String?,
        avoidClientName: String?,
    ) {
        loadJob = viewModelScope.launch {
            _uiState.value = SyncedVideoUiState.Loading
            videoRepository.resolveVideo(
                title,
                artist,
                videoId,
                expectedDurationMs = durationMs,
                avoidClientName = avoidClientName,
                selectedVideoId = selectedVideoId,
            )
                .onSuccess { resolved ->
                    // A saved offset applies before the video starts, so there is no jump.
                    val savedOffset = videoRepository.savedOffsetMs(resolved, videoId)
                    if (savedOffset != null) Log.i(TAG, "Using saved offset $savedOffset ms for ${resolved.videoId}")
                    val bundle = savedOffset?.let { resolved.copy(introOffsetMs = it) } ?: resolved
                    _uiState.value = SyncedVideoUiState.Ready(
                        bundle = bundle,
                        activeStream = bundle.defaultStream,
                        selectedHeight = null,
                    )
                    startAlignmentIfNeeded()
                }
                .onFailure { error ->
                    _uiState.value = SyncedVideoUiState.Error(
                        message = error.message ?: "Unable to resolve video stream",
                        noMatchingVideo = error.message?.startsWith("No verified music video found") == true,
                    )
                }
        }
    }

    fun selectQuality(height: Int?) {
        val current = _uiState.value as? SyncedVideoUiState.Ready ?: return
        val targetStream = if (height == null || height <= 0) {
            current.bundle.defaultStream
        } else {
            current.bundle.availableStreams.firstOrNull { (it.height ?: 0) == height }
                ?: current.bundle.availableStreams.minByOrNull { abs((it.height ?: 0) - height) }
                ?: current.bundle.defaultStream
        }
        _uiState.value = current.copy(
            activeStream = targetStream,
            selectedHeight = height,
        )
    }

    /** A chosen quality falls back to Auto; a failed Auto stream surfaces Retry instead of a black frame. */
    fun onVideoPlaybackError() {
        val ready = _uiState.value as? SyncedVideoUiState.Ready ?: return
        if (ready.selectedHeight != null) {
            selectQuality(null)
            return
        }
        videoRepository.invalidate(ready.bundle.videoId)
        if (_playerInitialized) {
            player.stop()
            player.clearMediaItems()
        }
        // Links can die mid-song, so get new ones. The sync loop and load effect resume at the audio's position.
        if (linkRefreshesLeft > 0) {
            linkRefreshesLeft--
            Log.w(TAG, "Refreshing video links after ${ready.bundle.clientName} failed ($linkRefreshesLeft refreshes left)")
            refreshLinks?.invoke(ready.bundle.clientName)
            return
        }
        if (ready.usesAdaptive) {
            // The default stream passed its probe, so try it before giving up.
            _uiState.value = ready.copy(adaptiveFailed = true)
            return
        }
        if (fallBackToLowerStream(ready)) return
        _uiState.value = SyncedVideoUiState.Error(appContext.getString(R.string.video_stream_unavailable))
    }

    /** New links can't help a decoder that refuses the track, so this skips straight to other streams. */
    fun onNoVideoTrackSelected(playingUri: String?) {
        val ready = _uiState.value as? SyncedVideoUiState.Ready ?: return
        if (playingUri != ready.playbackUri) return
        Log.w(TAG, "No decoder took ${ready.activeStream.height}p (${ready.activeStream.codec}), adaptive=${ready.usesAdaptive}")
        if (ready.usesAdaptive) {
            _uiState.value = ready.copy(adaptiveFailed = true)
            return
        }
        if (fallBackToLowerStream(ready)) return
        if (_playerInitialized) player.stop()
        _uiState.value = SyncedVideoUiState.Error(appContext.getString(R.string.video_stream_unavailable))
    }

    private fun fallBackToLowerStream(ready: SyncedVideoUiState.Ready): Boolean {
        val failed = ready.activeStream
        val remaining = ready.bundle.availableStreams.filter { it.url != failed.url }
        val failedHeight = failed.height ?: 0
        val next = remaining.filter { (it.height ?: 0) < failedHeight }.maxByOrNull { it.height ?: 0 }
            ?: remaining.firstOrNull()
            ?: return false
        Log.w(TAG, "Video stream ${failed.height}p (${failed.codec}) failed; trying ${next.height}p (${next.codec})")
        _uiState.value = ready.copy(
            bundle = ready.bundle.copy(
                availableStreams = remaining,
                defaultStream = if (ready.bundle.defaultStream.url == failed.url) next else ready.bundle.defaultStream,
            ),
            activeStream = next,
            selectedHeight = null,
            adaptiveFailed = true,
        )
        return true
    }

    fun retry(title: String, artist: String, videoId: String?, durationMs: Long? = null) {
        videoRepository.invalidate(videoId)
        currentKey = null
        loadVideo(title, artist, videoId, durationMs)
    }

    fun openVideoPicker(title: String, artist: String, videoId: String?, durationMs: Long?) {
        videoSearchJob?.cancel()
        val request = VideoPickerRequest(title, artist, videoId, durationMs)
        videoPickerRequest = request
        _videoPickerState.value = VideoPickerUiState.Loading
        videoSearchJob = viewModelScope.launch {
            val candidates = runCatching {
                videoRepository.searchVideoCandidates(title, artist, durationMs)
            }.getOrElse { error ->
                if (videoPickerRequest == request) {
                    _videoPickerState.value = VideoPickerUiState.Error(error.message ?: "Could not search videos")
                }
                return@launch
            }
            if (videoPickerRequest != request || _videoPickerState.value != VideoPickerUiState.Loading) return@launch
            _videoPickerState.value = VideoPickerUiState.Results(
                candidates = candidates,
                savedVideoId = savedVideoOverride(title, artist),
            )
        }
    }

    fun dismissVideoPicker() {
        videoSearchJob?.cancel()
        videoSearchJob = null
        _videoPickerState.value = VideoPickerUiState.Hidden
        videoPickerRequest = null
    }

    fun selectVideo(candidate: VideoSearchCandidate) {
        saveVideoOverride(candidate.videoId)
    }

    fun selectVideoUrl(input: String): Boolean {
        val videoId = extractYoutubeVideoId(input) ?: return false
        saveVideoOverride(videoId)
        return true
    }

    private fun saveVideoOverride(videoId: String) {
        val request = videoPickerRequest ?: return
        videoOverridePrefs.edit().putString(videoOverrideKey(request.title, request.artist), videoId).apply()
        dismissVideoPicker()
        currentKey = null
        loadVideo(request.title, request.artist, request.audioVideoId, request.durationMs)
    }

    fun clearVideoOverride() {
        val request = videoPickerRequest ?: return
        videoOverridePrefs.edit().remove(videoOverrideKey(request.title, request.artist)).apply()
        dismissVideoPicker()
        currentKey = null
        loadVideo(request.title, request.artist, request.audioVideoId, request.durationMs)
    }

    private fun savedVideoOverride(title: String, artist: String): String? =
        videoOverridePrefs.getString(videoOverrideKey(title, artist), null)

    private fun videoOverrideKey(title: String, artist: String): String =
        "${TextMatch.normalize(title)}|${TextMatch.normalize(artist)}"

    @OptIn(UnstableApi::class)
    override fun onCleared() {
        super.onCleared()
        if (_playerInitialized) {
            player.release()
        }
    }
}

/**
 * High-performance, exact-millisecond synchronized video viewer dialog with full-screen support.
 *
 * - Audio remains driving master clock from [MusicPlayer]; video runs muted with volume = 0f.
 * - Millisecond sync loop adjusts video play/pause and phase locks within sub-frame delta.
 * - Expandable to edge-to-edge immersive full-screen with double-tap seek and auto-hiding controls.
 * - Liquid Glass themed with fluid entrance and responsive controls.
 */
private enum class VideoSeekSide { REWIND, FORWARD }

/**
 * In-place 16:9 synchronized video player designed to replace album cover art.
 * - Audio remains driving master clock from [MusicPlayer]; video runs muted with volume = 0f.
 * - Exact PLL sync loop locks video to audio with zero buffer thrashing.
 * - Smoothly expands into edge-to-edge sensor landscape cinema mode when fullscreen is requested.
 */
/**
 * Sleek Liquid Glass segmented pill switcher: [ Song | Video ]
 * Modeled after YouTube Music & SimpMusic.
 */
@Composable
fun SongVideoPillSwitcher(
    isVideoActive: Boolean,
    onModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.10f),
        border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.18f)),
        modifier = modifier.height(34.dp),
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // "Song" Pill Option
            Surface(
                onClick = { onModeChange(false) },
                shape = RoundedCornerShape(50),
                color = if (!isVideoActive) Color.White.copy(alpha = 0.28f) else Color.Transparent,
                border = if (!isVideoActive) BorderStroke(0.6.dp, Color.White.copy(alpha = 0.35f)) else null,
                modifier = Modifier.height(28.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = if (!isVideoActive) Color.White else Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Song",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (!isVideoActive) FontWeight.Bold else FontWeight.Medium,
                        color = if (!isVideoActive) Color.White else Color.White.copy(alpha = 0.65f),
                    )
                }
            }

            Spacer(Modifier.width(2.dp))

            // "Video" Pill Option
            Surface(
                onClick = { onModeChange(true) },
                shape = RoundedCornerShape(50),
                color = if (isVideoActive) Color.White.copy(alpha = 0.28f) else Color.Transparent,
                border = if (isVideoActive) BorderStroke(0.6.dp, Color.White.copy(alpha = 0.35f)) else null,
                modifier = Modifier.height(28.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.SmartDisplay,
                        contentDescription = null,
                        tint = if (isVideoActive) Color.White else Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Video",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isVideoActive) FontWeight.Bold else FontWeight.Medium,
                        color = if (isVideoActive) Color.White else Color.White.copy(alpha = 0.65f),
                    )
                }
            }
        }
    }
}

/**
 * In-place 16:9 synchronized video player designed to replace album cover art.
 * - Audio remains driving master clock from [MusicPlayer]; video runs muted with volume = 0f.
 * - Exact PLL sync loop locks video to audio with zero buffer thrashing.
 * - Tapping fullscreen triggers [onEnterFullscreen] to seamlessly open edge-to-edge landscape mode.
 */
@OptIn(UnstableApi::class)
@Composable
fun SyncedCoverVideoPlayer(
    track: PlayableTrack,
    musicPlayer: MusicPlayer,
    onCloseVideo: () -> Unit,
    onEnterFullscreen: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: SyncedVideoViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState by musicPlayer.stateWithoutProgress.collectAsStateWithLifecycle()
    val progressState by musicPlayer.progressState.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(true) }
    var isRendered by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var doubleTapSide by remember { mutableStateOf<VideoSeekSide?>(null) }
    var seekResetJob by remember { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var lastHardSeekTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(track.videoId, track.title, track.durationMs) {
        viewModel.loadVideo(track.title, track.artist, track.videoId, track.durationMs)
    }

    // Auto-hide controls after inactivity
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(3500L)
            controlsVisible = false
        }
    }

    // Extract exact headers from stream to prevent 403 Forbidden
    val activeStream = (uiState as? SyncedVideoUiState.Ready)?.activeStream
    val requestHeaders = activeStream?.requestHeaders ?: mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
        "Origin" to "https://www.youtube.com",
        "Referer" to "https://www.youtube.com/watch?v=${activeStream?.videoId ?: track.videoId}",
    )

    var isBuffering by remember { mutableStateOf(false) }

    val httpFactory = viewModel.httpFactory
    val videoPlayer = viewModel.player

    // Initialise isRendered from current player state so returning to a ready player
    // does not flash the loading spinner.
    var isRenderedLocal = videoPlayer.playbackState == Player.STATE_READY
            && videoPlayer.currentMediaItem != null
    if (isRenderedLocal) isRendered = true

    DisposableEffect(videoPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                isRendered = true
                isBuffering = false
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = (playbackState == Player.STATE_BUFFERING)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.w("SyncedVideo", "Video player error: ${error.message}", error)
                viewModel.onVideoPlaybackError()
            }

            // No error fires when no decoder takes the track: the player just
            // runs with nothing to show, which is a black screen.
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (!tracks.isEmpty && !tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                    viewModel.onNoVideoTrackSelected(videoPlayer.currentMediaItem?.localConfiguration?.uri?.toString())
                }
            }
        }
        videoPlayer.addListener(listener)
        onDispose {
            videoPlayer.removeListener(listener)
            videoPlayer.clearVideoSurfaceView(null)
            // Do NOT release – the ViewModel owns the player's lifecycle
            videoPlayer.playWhenReady = false
        }
    }

    // Exact millisecond synchronization loop (PLL) with frame-accurate intro skit offset
    val introOffsetMs = (uiState as? SyncedVideoUiState.Ready)?.bundle?.introOffsetMs ?: 0L

    // Load stream into video player when ready or when switching quality
    LaunchedEffect(uiState, introOffsetMs) {
        val state = uiState
        if (state is SyncedVideoUiState.Ready) {
            val stream = state.activeStream
            val currentMediaUri = videoPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
            // Idle means the last attempt failed, so the same URI still needs loading.
            if (currentMediaUri == state.playbackUri && videoPlayer.playbackState != Player.STATE_IDLE) return@LaunchedEffect

            val streamUa = stream.requestHeaders["User-Agent"]
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
            httpFactory.setUserAgent(streamUa)
            httpFactory.setDefaultRequestProperties(stream.requestHeaders)

            isRendered = false
            val audioPos = musicPlayer.currentPositionMs()
            val targetPos = (audioPos + introOffsetMs).coerceAtLeast(0L)
            val mediaItem = MediaItem.Builder()
                .setUri(state.playbackUri)
                .apply {
                    state.playbackMimeType?.let { setMimeType(it) }
                }
                .build()
            videoPlayer.setMediaItem(mediaItem, targetPos)
            videoPlayer.prepare()
            videoPlayer.playWhenReady = playerState.isPlaying
        }
    }

    LaunchedEffect(videoPlayer, playerState.isPlaying, uiState, introOffsetMs) {
        if (uiState !is SyncedVideoUiState.Ready) return@LaunchedEffect
        while (isActive) {
            if (videoPlayer.playbackState == Player.STATE_READY) {
                val audioPos = musicPlayer.currentPositionMs()
                val targetVideoPos = (audioPos + introOffsetMs).coerceAtLeast(0L)
                val videoPos = videoPlayer.currentPosition
                val driftMs = videoPos - targetVideoPos
                val now = SystemClock.elapsedRealtime()

                // Play / Pause lock
                if (videoPlayer.playWhenReady != playerState.isPlaying) {
                    videoPlayer.playWhenReady = playerState.isPlaying
                }

                // Phase-Locked Loop (PLL) sync
                if (abs(driftMs) > 600L) {
                    // Drift > 600ms or user seek: snap video to exact position
                    if (now - lastHardSeekTime > 1200L) {
                        videoPlayer.seekTo(targetVideoPos)
                        lastHardSeekTime = now
                    }
                } else if (abs(driftMs) > 200L && playerState.isPlaying) {
                    // Medium drift (200ms - 600ms): rapid catchup speed (video is muted)
                    val targetSpeed = if (driftMs < 0) 1.15f else 0.85f
                    if (videoPlayer.playbackParameters.speed != targetSpeed) {
                        videoPlayer.playbackParameters = PlaybackParameters(targetSpeed)
                    }
                } else if (abs(driftMs) > 60L && playerState.isPlaying) {
                    // Micro-drift (60ms - 200ms): gentle glide
                    val targetSpeed = if (driftMs < 0) 1.05f else 0.95f
                    if (videoPlayer.playbackParameters.speed != targetSpeed) {
                        videoPlayer.playbackParameters = PlaybackParameters(targetSpeed)
                    }
                } else {
                    // Frame-locked (<60ms)
                    if (videoPlayer.playbackParameters.speed != 1.0f) {
                        videoPlayer.playbackParameters = PlaybackParameters(1.0f)
                    }
                }
            }
            delay(50L)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    val videoAlpha by animateFloatAsState(
        targetValue = if (isRendered) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "videoFade",
    )

    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .background(Color.Black)
            .videoTapGestures(
                onTap = { controlsVisible = !controlsVisible },
                onDoubleTap = { offset, width ->
                    val isRightSide = offset.x > width / 2f
                    val delta = if (isRightSide) 10_000L else -10_000L
                    val newPos = (musicPlayer.currentPositionMs() + delta)
                        .coerceIn(0L, progressState.durationMs.coerceAtLeast(1L))
                    musicPlayer.seekTo(newPos)
                    videoPlayer.seekTo(newPos)
                    lastHardSeekTime = SystemClock.elapsedRealtime()
                    doubleTapSide = if (isRightSide) VideoSeekSide.FORWARD else VideoSeekSide.REWIND
                    seekResetJob?.cancel()
                    seekResetJob = coroutineScope.launch {
                        delay(650L)
                        doubleTapSide = null
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (uiState) {
            is SyncedVideoUiState.Loading -> {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(36.dp),
                )
            }

            is SyncedVideoUiState.Error -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = (uiState as SyncedVideoUiState.Error).message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { viewModel.retry(track.title, track.artist, track.videoId, track.durationMs) },
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) {
                        Text("Retry", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            is SyncedVideoUiState.Ready -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        SurfaceView(viewContext).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            videoPlayer.setVideoSurfaceView(this)
                        }
                    },
                    update = { surfaceView ->
                        videoPlayer.setVideoSurfaceView(surfaceView)
                    },
                )
                if (isBuffering) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }

        // Animated double-tap seek side bubbles
        AnimatedVisibility(
            visible = doubleTapSide == VideoSeekSide.REWIND,
            enter = fadeIn(tween(100)) + scaleIn(ExpressiveMotion.spatialSpring(), initialScale = 0.88f),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.38f)
                .fillMaxHeight(0.85f),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 60.dp, bottomEnd = 60.dp))
                    .background(Color.Black.copy(alpha = 0.60f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.FastRewind, null, tint = Color.White, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(4.dp))
                    Text("-10s", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        AnimatedVisibility(
            visible = doubleTapSide == VideoSeekSide.FORWARD,
            enter = fadeIn(tween(100)) + scaleIn(ExpressiveMotion.spatialSpring(), initialScale = 0.88f),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxWidth(0.38f)
                .fillMaxHeight(0.85f),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp, topStart = 60.dp, bottomStart = 60.dp))
                    .background(Color.Black.copy(alpha = 0.60f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.FastForward, null, tint = Color.White, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(4.dp))
                    Text("+10s", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        // Top controls bar with sleek glass styling
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)
                        )
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Sync badge
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.55f),
                    border = BorderStroke(0.7.dp, Color.White.copy(alpha = 0.18f)),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E676).copy(alpha = pulseAlpha))
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = stringResource(R.string.video_sync_tag),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                letterSpacing = 0.8.sp,
                            ),
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                        )
                    }
                }

                Spacer(Modifier.width(6.dp))
                Surface(
                    onClick = { viewModel.openVideoPicker(track.title, track.artist, track.videoId, track.durationMs) },
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.55f),
                    border = BorderStroke(0.7.dp, Color.White.copy(alpha = 0.18f)),
                    modifier = Modifier.height(32.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 9.dp),
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Choose video", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.weight(1f))

                // Quality Badge
                if (uiState is SyncedVideoUiState.Ready) {
                    Surface(
                        onClick = { showQualityDialog = true },
                        shape = RoundedCornerShape(50),
                        color = Color.Black.copy(alpha = 0.55f),
                        border = BorderStroke(0.7.dp, Color.White.copy(alpha = 0.18f)),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Icon(
                                Icons.Filled.HighQuality,
                                contentDescription = stringResource(R.string.video_quality),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = (uiState as SyncedVideoUiState.Ready).currentQualityLabel,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }

                // Fullscreen Button
                if (onEnterFullscreen != null) {
                    Surface(
                        onClick = onEnterFullscreen,
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.55f),
                        border = BorderStroke(0.7.dp, Color.White.copy(alpha = 0.18f)),
                        modifier = Modifier.size(32.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Fullscreen,
                                contentDescription = stringResource(R.string.video_enter_fullscreen),
                                tint = Color.White,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }

                // Close / Revert to Cover Art
                Surface(
                    onClick = onCloseVideo,
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.65f),
                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.28f)),
                    modifier = Modifier.size(34.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close video, show album art",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        // Persistent close button in top-right when top controls bar is hidden
        AnimatedVisibility(
            visible = !controlsVisible,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        ) {
            Surface(
                onClick = { viewModel.openVideoPicker(track.title, track.artist, track.videoId, track.durationMs) },
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.60f),
                border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.25f)),
                modifier = Modifier.height(34.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Choose video", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        // Persistent close button in top-right when top controls are hidden.
        AnimatedVisibility(
            visible = !controlsVisible,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        ) {
            Surface(
                onClick = onCloseVideo,
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.60f),
                border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.25f)),
                modifier = Modifier.size(34.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close video, show album art",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // Center play indicator when paused
        if (!playerState.isPlaying) {
            Surface(
                onClick = { musicPlayer.togglePlayPause() },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.65f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play music",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        }
    }

    if (showQualityDialog && uiState is SyncedVideoUiState.Ready) {
        val ready = uiState as SyncedVideoUiState.Ready
        VideoQualityDialog(
            bundle = ready.bundle,
            selectedHeight = ready.selectedHeight,
            onSelectQuality = { height -> viewModel.selectQuality(height) },
            onDismiss = { showQualityDialog = false },
        )
    }
    VideoCandidatePickerDialog(viewModel, track)
}

/**
 * True Edge-to-Edge Fullscreen Landscape Video Player with YouTube-style controls.
 * - Fits the screen completely in sensor landscape.
 * - Top bar features a prominent Minimize button (`Icons.Filled.ExpandMore`) to smoothly
 *   minimize back to the Now Playing screen.
 * - YouTube-style transport: -10s, Master Play/Pause, +10s, interactive scrubbing timeline,
 *   resolution selector, and double-tap seeking.
 */
@OptIn(UnstableApi::class)
@Composable
fun FullscreenLandscapeVideoStage(
    track: PlayableTrack,
    musicPlayer: MusicPlayer,
    onMinimize: () -> Unit,
    onCloseVideo: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SyncedVideoViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState by musicPlayer.stateWithoutProgress.collectAsStateWithLifecycle()
    val progressState by musicPlayer.progressState.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(true) }
    var isRendered by remember { mutableStateOf(false) }
    var isUserScrubbing by remember { mutableStateOf(false) }
    var scrubProgress by remember { mutableFloatStateOf(0f) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var doubleTapSide by remember { mutableStateOf<VideoSeekSide?>(null) }
    var seekResetJob by remember { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var lastHardSeekTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(track.videoId, track.title, track.durationMs) {
        viewModel.loadVideo(track.title, track.artist, track.videoId, track.durationMs)
    }

    // Auto-hide controls in fullscreen after 3.5s
    LaunchedEffect(controlsVisible, isUserScrubbing) {
        if (controlsVisible && !isUserScrubbing) {
            delay(3500L)
            controlsVisible = false
        }
    }

    val activeStream = (uiState as? SyncedVideoUiState.Ready)?.activeStream
    val requestHeaders = activeStream?.requestHeaders ?: mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
        "Origin" to "https://www.youtube.com",
        "Referer" to "https://www.youtube.com/watch?v=${activeStream?.videoId ?: track.videoId}",
    )

    var isBuffering by remember { mutableStateOf(false) }

    val httpFactory = viewModel.httpFactory
    val videoPlayer = viewModel.player

    var isRenderedLocal = videoPlayer.playbackState == Player.STATE_READY
            && videoPlayer.currentMediaItem != null
    if (isRenderedLocal) isRendered = true

    DisposableEffect(videoPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                isRendered = true
                isBuffering = false
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = (playbackState == Player.STATE_BUFFERING)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.w("SyncedVideo", "Fullscreen video player error: ${error.message}", error)
                viewModel.onVideoPlaybackError()
            }

            // No error fires when no decoder takes the track: the player just
            // runs with nothing to show, which is a black screen.
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (!tracks.isEmpty && !tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                    viewModel.onNoVideoTrackSelected(videoPlayer.currentMediaItem?.localConfiguration?.uri?.toString())
                }
            }
        }
        videoPlayer.addListener(listener)
        onDispose {
            videoPlayer.removeListener(listener)
            videoPlayer.clearVideoSurfaceView(null)
            // Do NOT release – the ViewModel owns the player's lifecycle
            videoPlayer.playWhenReady = false
        }
    }

    // PLL sync loop with frame-accurate intro skit offset
    val introOffsetMs = (uiState as? SyncedVideoUiState.Ready)?.bundle?.introOffsetMs ?: 0L

    // Load stream into video player
    LaunchedEffect(uiState, introOffsetMs) {
        val state = uiState
        if (state is SyncedVideoUiState.Ready) {
            val stream = state.activeStream
            val currentMediaUri = videoPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
            // Idle means the last attempt failed, so the same URI still needs loading.
            if (currentMediaUri == state.playbackUri && videoPlayer.playbackState != Player.STATE_IDLE) return@LaunchedEffect

            val streamUa = stream.requestHeaders["User-Agent"]
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
            httpFactory.setUserAgent(streamUa)
            httpFactory.setDefaultRequestProperties(stream.requestHeaders)

            isRendered = false
            val audioPos = musicPlayer.currentPositionMs()
            val targetPos = (audioPos + introOffsetMs).coerceAtLeast(0L)
            val mediaItem = MediaItem.Builder()
                .setUri(state.playbackUri)
                .apply {
                    state.playbackMimeType?.let { setMimeType(it) }
                }
                .build()
            videoPlayer.setMediaItem(mediaItem, targetPos)
            videoPlayer.prepare()
            videoPlayer.playWhenReady = playerState.isPlaying
        }
    }

    LaunchedEffect(videoPlayer, playerState.isPlaying, uiState, introOffsetMs) {
        if (uiState !is SyncedVideoUiState.Ready) return@LaunchedEffect
        while (isActive) {
            if (videoPlayer.playbackState == Player.STATE_READY) {
                val audioPos = musicPlayer.currentPositionMs()
                val targetVideoPos = (audioPos + introOffsetMs).coerceAtLeast(0L)
                val videoPos = videoPlayer.currentPosition
                val driftMs = videoPos - targetVideoPos
                val now = SystemClock.elapsedRealtime()

                // Play / Pause sync
                if (videoPlayer.playWhenReady != playerState.isPlaying) {
                    videoPlayer.playWhenReady = playerState.isPlaying
                }

                // Phase-Locked Loop (PLL)
                if (abs(driftMs) > 600L) {
                    if (now - lastHardSeekTime > 1200L) {
                        videoPlayer.seekTo(targetVideoPos)
                        lastHardSeekTime = now
                    }
                } else if (abs(driftMs) > 200L && playerState.isPlaying) {
                    val targetSpeed = if (driftMs < 0) 1.15f else 0.85f
                    if (videoPlayer.playbackParameters.speed != targetSpeed) {
                        videoPlayer.playbackParameters = PlaybackParameters(targetSpeed)
                    }
                } else if (abs(driftMs) > 60L && playerState.isPlaying) {
                    val targetSpeed = if (driftMs < 0) 1.05f else 0.95f
                    if (videoPlayer.playbackParameters.speed != targetSpeed) {
                        videoPlayer.playbackParameters = PlaybackParameters(targetSpeed)
                    }
                } else {
                    if (videoPlayer.playbackParameters.speed != 1.0f) {
                        videoPlayer.playbackParameters = PlaybackParameters(1.0f)
                    }
                }
            }
            delay(50L)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    val durationSafe = progressState.durationMs.coerceAtLeast(1L)
    val currentPosition = if (isUserScrubbing) {
        (scrubProgress * durationSafe).toLong()
    } else {
        progressState.positionMs
    }
    val sliderValue = if (isUserScrubbing) scrubProgress else (currentPosition.toFloat() / durationSafe).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .videoTapGestures(
                onTap = { controlsVisible = !controlsVisible },
                onDoubleTap = { offset, width ->
                    val isRightSide = offset.x > width / 2f
                    val delta = if (isRightSide) 10_000L else -10_000L
                    val newPos = (musicPlayer.currentPositionMs() + delta)
                        .coerceIn(0L, durationSafe)
                    musicPlayer.seekTo(newPos)
                    videoPlayer.seekTo(newPos)
                    lastHardSeekTime = SystemClock.elapsedRealtime()
                    doubleTapSide = if (isRightSide) VideoSeekSide.FORWARD else VideoSeekSide.REWIND
                    seekResetJob?.cancel()
                    seekResetJob = coroutineScope.launch {
                        delay(650L)
                        doubleTapSide = null
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (uiState) {
            is SyncedVideoUiState.Loading -> {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(48.dp),
                )
            }

            is SyncedVideoUiState.Error -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(
                        text = (uiState as SyncedVideoUiState.Error).message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = { viewModel.retry(track.title, track.artist, track.videoId, track.durationMs) },
                    ) {
                        Text("Retry")
                    }
                }
            }
            is SyncedVideoUiState.Ready -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        SurfaceView(viewContext).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            videoPlayer.setVideoSurfaceView(this)
                        }
                    },
                    update = { surfaceView ->
                        videoPlayer.setVideoSurfaceView(surfaceView)
                    },
                )
                if (isBuffering) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        }

        // Animated double-tap seek side bubbles
        AnimatedVisibility(
            visible = doubleTapSide == VideoSeekSide.REWIND,
            enter = fadeIn(tween(100)) + scaleIn(ExpressiveMotion.spatialSpring(), initialScale = 0.88f),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.35f)
                .fillMaxHeight(0.85f),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 70.dp, bottomEnd = 70.dp))
                    .background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.FastRewind, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("-10s", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        AnimatedVisibility(
            visible = doubleTapSide == VideoSeekSide.FORWARD,
            enter = fadeIn(tween(100)) + scaleIn(ExpressiveMotion.spatialSpring(), initialScale = 0.88f),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxWidth(0.35f)
                .fillMaxHeight(0.85f),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(topEnd = 0.dp, bottomEnd = 0.dp, topStart = 70.dp, bottomStart = 70.dp))
                    .background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.FastForward, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("+10s", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        // YouTube-Style Top Bar (Minimize button, Track metadata, Quality & Close)
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Prominent Minimize Button (Chevron Down)
                Surface(
                    onClick = onMinimize,
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.55f),
                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.20f)),
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = "Minimize to Now Playing",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                // Song Title & Artist
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Live Sync Tag
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.55f),
                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.20f)),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E676).copy(alpha = pulseAlpha))
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = stringResource(R.string.video_sync_tag),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                letterSpacing = 0.8.sp,
                            ),
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = { viewModel.openVideoPicker(track.title, track.artist, track.videoId, track.durationMs) },
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.55f),
                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.20f)),
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Search, contentDescription = "Choose a different video", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Quality Selector
                if (uiState is SyncedVideoUiState.Ready) {
                    Surface(
                        onClick = { showQualityDialog = true },
                        shape = RoundedCornerShape(50),
                        color = Color.Black.copy(alpha = 0.55f),
                        border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.20f)),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        ) {
                            Icon(
                                Icons.Filled.HighQuality,
                                contentDescription = stringResource(R.string.video_quality),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = (uiState as SyncedVideoUiState.Ready).currentQualityLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }

                // Close Button (revert to cover art)
                Surface(
                    onClick = onCloseVideo,
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.55f),
                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.20f)),
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close video",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        // YouTube-Style Center Transport Controls (-10s, Master Play/Pause, +10s)
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(36.dp),
            ) {
                // Replay 10s
                Surface(
                    onClick = {
                        val newPos = (musicPlayer.currentPositionMs() - 10_000L).coerceAtLeast(0L)
                        musicPlayer.seekTo(newPos)
                        videoPlayer.seekTo(newPos)
                    },
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.55f),
                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.20f)),
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Replay10,
                            contentDescription = "-10s",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }

                // Master Play / Pause Button
                Surface(
                    onClick = { musicPlayer.togglePlayPause() },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 8.dp,
                    modifier = Modifier.size(68.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (playerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(38.dp),
                        )
                    }
                }

                // Forward 10s
                Surface(
                    onClick = {
                        val newPos = (musicPlayer.currentPositionMs() + 10_000L).coerceAtMost(durationSafe)
                        musicPlayer.seekTo(newPos)
                        videoPlayer.seekTo(newPos)
                    },
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.55f),
                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.20f)),
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Forward10,
                            contentDescription = "+10s",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            }
        }

        // YouTube-Style Bottom Timeline Bar
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatTime(currentPosition),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.9f),
                    )

                    Slider(
                        value = sliderValue,
                        onValueChange = {
                            isUserScrubbing = true
                            scrubProgress = it
                        },
                        onValueChangeFinished = {
                            isUserScrubbing = false
                            val target = (scrubProgress * durationSafe).toLong()
                            musicPlayer.seekTo(target)
                            videoPlayer.seekTo(target)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    )

                    Text(
                        text = formatTime(durationSafe),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.75f),
                    )

                    Spacer(Modifier.width(8.dp))

                    // Exit Fullscreen / Minimize
                    Surface(
                        onClick = onMinimize,
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.55f),
                        border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.20f)),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.FullscreenExit,
                                contentDescription = "Exit Fullscreen",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showQualityDialog && uiState is SyncedVideoUiState.Ready) {
        val ready = uiState as SyncedVideoUiState.Ready
        VideoQualityDialog(
            bundle = ready.bundle,
            selectedHeight = ready.selectedHeight,
            onSelectQuality = { height -> viewModel.selectQuality(height) },
            onDismiss = { showQualityDialog = false },
        )
    }
    VideoCandidatePickerDialog(viewModel, track)
}

@OptIn(UnstableApi::class)
@Composable
fun SyncedVideoPlayerDialog(
    track: PlayableTrack,
    musicPlayer: MusicPlayer,
    onDismiss: () -> Unit,
    viewModel: SyncedVideoViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState by musicPlayer.stateWithoutProgress.collectAsStateWithLifecycle()
    val progressState by musicPlayer.progressState.collectAsStateWithLifecycle()

    var isFullscreen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var isRendered by remember { mutableStateOf(false) }
    var isUserScrubbing by remember { mutableStateOf(false) }
    var scrubProgress by remember { mutableFloatStateOf(0f) }
    var doubleTapFeedback by remember { mutableStateOf<String?>(null) }
    var showQualityDialog by remember { mutableStateOf(false) }

    LaunchedEffect(track) {
        viewModel.loadVideo(track.title, track.artist, track.videoId, track.durationMs)
    }

    // Auto-hide controls in fullscreen after inactivity
    LaunchedEffect(controlsVisible, isFullscreen, isUserScrubbing) {
        if (controlsVisible && isFullscreen && !isUserScrubbing) {
            delay(3500L)
            controlsVisible = false
        }
    }

    // Fullscreen immersive system bars handler
    DisposableEffect(view, isFullscreen) {
        val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val previousBehavior = controller?.systemBarsBehavior

        if (isFullscreen) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (isFullscreen) {
                controller?.show(WindowInsetsCompat.Type.systemBars())
                previousBehavior?.let { controller?.systemBarsBehavior = it }
            }
        }
    }

    // Back button exits fullscreen first, then dismisses
    BackHandler {
        if (isFullscreen) {
            isFullscreen = false
        } else {
            onDismiss()
        }
    }

    val httpFactory = remember(context) {
        OkHttpDataSource.Factory(videoMediaHttp(context))
            .setDefaultRequestProperties(
                mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Origin" to "https://www.youtube.com",
                    "Referer" to "https://www.youtube.com",
                )
            )
    }

    // ExoPlayer dedicated strictly for video rendering
    val videoPlayer = remember(context) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 2_000,
                /* maxBufferMs = */ 8_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val defaultDataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val chunkedFactory = DataSource.Factory {
            ChunkedRangeDataSource(defaultDataSourceFactory.createDataSource(), chunkSize = 256 * 1024L)
        }

        adaptiveVideoPlayerBuilder(context, chunkedFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                volume = 0f
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
            }
    }

    DisposableEffect(videoPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                isRendered = true
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.w(TAG, "Video player error: ${error.message}", error)
                viewModel.onVideoPlaybackError()
            }

            // No error fires when no decoder takes the track: the player just
            // runs with nothing to show, which is a black screen.
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (!tracks.isEmpty && !tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                    viewModel.onNoVideoTrackSelected(videoPlayer.currentMediaItem?.localConfiguration?.uri?.toString())
                }
            }
        }
        videoPlayer.addListener(listener)
        onDispose {
            videoPlayer.removeListener(listener)
            videoPlayer.release()
        }
    }

    // Load stream into video player when ready or when switching quality
    LaunchedEffect(uiState) {
        val state = uiState
        if (state is SyncedVideoUiState.Ready) {
            val stream = state.activeStream
            val currentMediaUri = videoPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
            // Idle means the last attempt failed, so the same URI still needs loading.
            if (currentMediaUri == state.playbackUri && videoPlayer.playbackState != Player.STATE_IDLE) return@LaunchedEffect

            val streamUa = stream.requestHeaders["User-Agent"]
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
            httpFactory.setUserAgent(streamUa)
            httpFactory.setDefaultRequestProperties(stream.requestHeaders)

            isRendered = false
            val currentPos = videoPlayer.currentPosition.takeIf { it > 0 } ?: musicPlayer.currentPositionMs()
            val mediaItem = MediaItem.Builder()
                .setUri(state.playbackUri)
                .apply {
                    state.playbackMimeType?.let { setMimeType(it) }
                }
                .build()
            videoPlayer.setMediaItem(mediaItem, currentPos)
            videoPlayer.prepare()
            videoPlayer.playWhenReady = playerState.isPlaying
        }
    }

    // Exact millisecond synchronization loop (PLL)
    LaunchedEffect(videoPlayer, playerState.isPlaying, uiState) {
        if (uiState !is SyncedVideoUiState.Ready) return@LaunchedEffect
        while (isActive) {
            val audioPos = musicPlayer.currentPositionMs()
            val videoPos = videoPlayer.currentPosition
            val driftMs = videoPos - audioPos
            val audioSpeed = playerState.speed

            // Play / Pause sync
            if (videoPlayer.playWhenReady != playerState.isPlaying) {
                videoPlayer.playWhenReady = playerState.isPlaying
            }

            // Drift compensation
            if (abs(driftMs) > 150L) {
                // Hard seek for significant drift or user seeks
                videoPlayer.seekTo(audioPos)
                if (videoPlayer.playbackParameters.speed != audioSpeed) {
                    videoPlayer.playbackParameters = PlaybackParameters(audioSpeed)
                }
            } else if (abs(driftMs) > 40L) {
                // Micro-phase adjustment for sub-millisecond convergence without stutters
                val speedCorrection = if (driftMs > 0) 0.95f else 1.05f
                videoPlayer.playbackParameters = PlaybackParameters(audioSpeed * speedCorrection)
            } else {
                // Exact sync achieved
                if (videoPlayer.playbackParameters.speed != audioSpeed) {
                    videoPlayer.playbackParameters = PlaybackParameters(audioSpeed)
                }
            }

            delay(50L) // 20 times per second high-precision clock
        }
    }

    // Pulsing indicator for active live sync
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    val videoAlpha by animateFloatAsState(
        targetValue = if (isRendered) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "videoFade",
    )

    val currentPosition = if (isUserScrubbing) {
        (scrubProgress * progressState.durationMs).toLong()
    } else {
        progressState.positionMs
    }

    Dialog(
        onDismissRequest = {
            if (isFullscreen) isFullscreen = false else onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        EdgeToEdgeDialogWindow()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isFullscreen) Color.Black else Color.Black.copy(alpha = 0.65f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (!isFullscreen) onDismiss()
                },
            contentAlignment = Alignment.Center,
        ) {
            val contentModifier = if (isFullscreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxWidth(0.94f)
                    .adaptiveContentWidth(maxWidth = 660.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {} // Consume clicks inside card
            }

            Surface(
                modifier = contentModifier,
                shape = if (isFullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(28.dp),
                color = if (isFullscreen) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isFullscreen) Modifier.fillMaxSize()
                            else Modifier.padding(16.dp)
                        ),
                ) {
                    // Dialog Header (visible only when not fullscreen)
                    if (!isFullscreen) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.SmartDisplay,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = track.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    // Quality Badge Button
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .clickable { showQualityDialog = true }
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = if (uiState is SyncedVideoUiState.Ready) {
                                                (uiState as SyncedVideoUiState.Ready).currentQualityLabel
                                            } else {
                                                "Auto"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            IconButton(
                                onClick = { viewModel.openVideoPicker(track.title, track.artist, track.videoId, track.durationMs) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = "Choose a different video",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { showQualityDialog = true },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Filled.HighQuality,
                                    contentDescription = stringResource(R.string.video_quality),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { isFullscreen = true },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Fullscreen,
                                    contentDescription = stringResource(R.string.video_enter_fullscreen),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.common_cancel),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // Video Area Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isFullscreen) Modifier.weight(1f)
                                else Modifier
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(20.dp))
                            )
                            .background(Color.Black)
                            .videoTapGestures(
                                onTap = { controlsVisible = !controlsVisible },
                                onDoubleTap = { offset, width ->
                                    val isRightSide = offset.x > width / 2f
                                    val delta = if (isRightSide) 10_000L else -10_000L
                                    val newPos = (musicPlayer.currentPositionMs() + delta)
                                        .coerceIn(0L, progressState.durationMs.coerceAtLeast(1L))
                                    musicPlayer.seekTo(newPos)
                                    videoPlayer.seekTo(newPos)
                                    doubleTapFeedback = if (isRightSide) "+10s" else "-10s"
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Media3 ExoPlayer SurfaceView
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { viewContext ->
                                SurfaceView(viewContext).apply {
                                    layoutParams = FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                    videoPlayer.setVideoSurfaceView(this)
                                }
                            },
                            update = { surfaceView ->
                                videoPlayer.setVideoSurfaceView(surfaceView)
                            },
                        )

                        // Loading State
                        if (uiState is SyncedVideoUiState.Loading || (!isRendered && uiState is SyncedVideoUiState.Ready)) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                ExpressiveLoadingIndicator(modifier = Modifier.size(44.dp))
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = stringResource(R.string.video_buffering),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.85f),
                                )
                            }
                        }

                        // Error State
                        if (uiState is SyncedVideoUiState.Error) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.video_stream_unavailable),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.9f),
                                )
                                Spacer(Modifier.height(12.dp))
                                FilledTonalButton(
                                    onClick = { viewModel.retry(track.title, track.artist, track.videoId, track.durationMs) },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                ) {
                                    Text(stringResource(R.string.video_retry))
                                }
                            }
                        }

                        // Double tap feedback overlay
                        LaunchedEffect(doubleTapFeedback) {
                            if (doubleTapFeedback != null) {
                                delay(650L)
                                doubleTapFeedback = null
                            }
                        }
                        doubleTapFeedback?.let { feedback ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.Black.copy(alpha = 0.65f))
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = feedback,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                            }
                        }

                        // Fullscreen In-Video Top Bar (visible when controlsVisible is true)
                        if (isFullscreen) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = controlsVisible,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            Brush.verticalGradient(
                                                colors = listOf(
                                                    Color.Black.copy(alpha = 0.75f),
                                                    Color.Transparent,
                                                )
                                            )
                                        )
                                        .padding(horizontal = 16.dp, vertical = 20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(onClick = { isFullscreen = false }) {
                                        Icon(
                                            Icons.Filled.FullscreenExit,
                                            contentDescription = stringResource(R.string.video_exit_fullscreen),
                                            tint = Color.White,
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = track.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = track.artist,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.75f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(Color(0xFF4CAF50).copy(alpha = 0.22f))
                                            .padding(horizontal = 9.dp, vertical = 3.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF4CAF50).copy(alpha = pulseAlpha)),
                                        )
                                        Spacer(Modifier.width(5.dp))
                                        Text(
                                            text = stringResource(R.string.video_sync_tag),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color(0xFF4CAF50),
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(
                                        onClick = { viewModel.openVideoPicker(track.title, track.artist, track.videoId, track.durationMs) },
                                    ) {
                                        Icon(Icons.Filled.Search, contentDescription = "Choose a different video", tint = Color.White)
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    // Fullscreen Quality Pill
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .clickable { showQualityDialog = true }
                                            .background(Color.White.copy(alpha = 0.18f))
                                            .padding(horizontal = 9.dp, vertical = 4.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.HighQuality,
                                            contentDescription = stringResource(R.string.video_quality),
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = if (uiState is SyncedVideoUiState.Ready) {
                                                (uiState as SyncedVideoUiState.Ready).currentQualityLabel
                                            } else {
                                                "Auto"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(onClick = onDismiss) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.common_cancel),
                                            tint = Color.White,
                                        )
                                    }
                                }
                            }
                        }

                        // Playback Center Controls overlay
                        androidx.compose.animation.AnimatedVisibility(
                            visible = controlsVisible,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.align(Alignment.Center),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                            ) {
                                // -10s
                                IconButton(
                                    onClick = {
                                        val newPos = (musicPlayer.currentPositionMs() - 10_000L).coerceAtLeast(0L)
                                        musicPlayer.seekTo(newPos)
                                        videoPlayer.seekTo(newPos)
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.5f)),
                                ) {
                                    Icon(
                                        Icons.Filled.Replay10,
                                        contentDescription = "-10s",
                                        tint = Color.White,
                                        modifier = Modifier.size(26.dp),
                                    )
                                }

                                // Play / Pause Master Button
                                IconButton(
                                    onClick = { musicPlayer.togglePlayPause() },
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                ) {
                                    Icon(
                                        imageVector = if (playerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(36.dp),
                                    )
                                }

                                // +10s
                                IconButton(
                                    onClick = {
                                        val newPos = (musicPlayer.currentPositionMs() + 10_000L)
                                            .coerceAtMost(progressState.durationMs.coerceAtLeast(1L))
                                        musicPlayer.seekTo(newPos)
                                        videoPlayer.seekTo(newPos)
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.5f)),
                                ) {
                                    Icon(
                                        Icons.Filled.Forward10,
                                        contentDescription = "+10s",
                                        tint = Color.White,
                                        modifier = Modifier.size(26.dp),
                                    )
                                }
                            }
                        }

                        // Fullscreen Bottom Seekbar overlay
                        if (isFullscreen) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = controlsVisible,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            Brush.verticalGradient(
                                                colors = listOf(
                                                    Color.Transparent,
                                                    Color.Black.copy(alpha = 0.85f),
                                                )
                                            )
                                        )
                                        .padding(horizontal = 20.dp, vertical = 18.dp),
                                ) {
                                    VideoTimelineRow(
                                        currentPosition = currentPosition,
                                        durationMs = progressState.durationMs,
                                        isUserScrubbing = isUserScrubbing,
                                        scrubProgress = scrubProgress,
                                        onScrubChange = {
                                            isUserScrubbing = true
                                            scrubProgress = it
                                        },
                                        onScrubFinished = {
                                            isUserScrubbing = false
                                            val target = (scrubProgress * progressState.durationMs).toLong()
                                            musicPlayer.seekTo(target)
                                            videoPlayer.seekTo(target)
                                        },
                                        onToggleFullscreen = { isFullscreen = false },
                                        isFullscreen = true,
                                    )
                                }
                            }
                        }
                    }

                    // Dialog bottom controls (visible when not fullscreen)
                    if (!isFullscreen) {
                        Spacer(Modifier.height(10.dp))
                        VideoTimelineRow(
                            currentPosition = currentPosition,
                            durationMs = progressState.durationMs,
                            isUserScrubbing = isUserScrubbing,
                            scrubProgress = scrubProgress,
                            onScrubChange = {
                                isUserScrubbing = true
                                scrubProgress = it
                            },
                            onScrubFinished = {
                                isUserScrubbing = false
                                val target = (scrubProgress * progressState.durationMs).toLong()
                                musicPlayer.seekTo(target)
                                videoPlayer.seekTo(target)
                            },
                            onToggleFullscreen = { isFullscreen = true },
                            isFullscreen = false,
                        )
                    }
                }
            }
        }
    }

    if (showQualityDialog && uiState is SyncedVideoUiState.Ready) {
        val ready = uiState as SyncedVideoUiState.Ready
        VideoQualityDialog(
            bundle = ready.bundle,
            selectedHeight = ready.selectedHeight,
            onSelectQuality = { height ->
                viewModel.selectQuality(height)
            },
            onDismiss = { showQualityDialog = false },
        )
    }
    VideoCandidatePickerDialog(viewModel, track)
}

@Composable
private fun VideoCandidatePickerDialog(
    viewModel: SyncedVideoViewModel,
    track: PlayableTrack,
) {
    val state by viewModel.videoPickerState.collectAsStateWithLifecycle()
    var videoLink by remember(track.title, track.artist) { mutableStateOf("") }
    when (val picker = state) {
        VideoPickerUiState.Hidden -> Unit
        VideoPickerUiState.Loading -> AlertDialog(
            onDismissRequest = viewModel::dismissVideoPicker,
            title = { Text("Find the right video") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Searching YouTube for ${track.title}…")
                    }
                    Spacer(Modifier.height(12.dp))
                    VideoLinkInput(
                        videoLink = videoLink,
                        onVideoLinkChange = { videoLink = it },
                        onUseLink = { viewModel.selectVideoUrl(videoLink) },
                    )
                }
            },
            confirmButton = { TextButton(onClick = viewModel::dismissVideoPicker) { Text("Cancel") } },
        )
        is VideoPickerUiState.Error -> AlertDialog(
            onDismissRequest = viewModel::dismissVideoPicker,
            title = { Text("Video search failed") },
            text = { Text(picker.message) },
            confirmButton = { TextButton(onClick = viewModel::dismissVideoPicker) { Text("OK") } },
        )
        is VideoPickerUiState.Results -> AlertDialog(
            onDismissRequest = viewModel::dismissVideoPicker,
            title = { Text("Choose a video") },
            text = {
                Column {
                    Text(
                        "Pick the correct upload for ${track.title}. Your choice is saved for this song.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    VideoLinkInput(
                        videoLink = videoLink,
                        onVideoLinkChange = { videoLink = it },
                        onUseLink = { viewModel.selectVideoUrl(videoLink) },
                    )
                    Spacer(Modifier.height(4.dp))
                    if (picker.candidates.isEmpty()) {
                        Text(
                            "No likely matches found. Paste the exact YouTube link above.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        LazyColumn(modifier = Modifier.height(320.dp)) {
                            items(picker.candidates, key = { it.videoId }) { candidate ->
                                Surface(
                                    onClick = { viewModel.selectVideo(candidate) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (candidate.videoId == picker.savedVideoId) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else Color.Transparent,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                        Text(
                                            candidate.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            buildString {
                                                append(candidate.channel)
                                                candidate.durationSeconds?.let { append(" • ").append(formatTime(it * 1_000L)) }
                                                append(" • likely match")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = viewModel::clearVideoOverride) { Text("Use automatic") }
                    TextButton(onClick = viewModel::dismissVideoPicker) { Text("Close") }
                }
            },
        )
    }
}

@Composable
private fun VideoLinkInput(
    videoLink: String,
    onVideoLinkChange: (String) -> Unit,
    onUseLink: () -> Unit,
) {
    val pastedVideoId = extractYoutubeVideoId(videoLink)
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = videoLink,
            onValueChange = onVideoLinkChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("YouTube video link or ID") },
            placeholder = { Text("https://youtu.be/…") },
            supportingText = {
                Text(if (pastedVideoId == null) "Paste a YouTube link or 11-character video ID" else "Video ID: $pastedVideoId")
            },
        )
        TextButton(
            onClick = onUseLink,
            enabled = pastedVideoId != null,
            modifier = Modifier.align(Alignment.End),
        ) { Text("Use this link") }
    }
}

@Composable
private fun VideoTimelineRow(
    currentPosition: Long,
    durationMs: Long,
    isUserScrubbing: Boolean,
    scrubProgress: Float,
    onScrubChange: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    onToggleFullscreen: () -> Unit,
    isFullscreen: Boolean,
) {
    val durationSafe = durationMs.coerceAtLeast(1L)
    val sliderValue = if (isUserScrubbing) scrubProgress else (currentPosition.toFloat() / durationSafe).coerceIn(0f, 1f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatTime(currentPosition),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isFullscreen) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = sliderValue,
            onValueChange = onScrubChange,
            onValueChangeFinished = onScrubFinished,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = if (isFullscreen) Color.White.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        )
        Text(
            text = formatTime(durationMs),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isFullscreen) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        IconButton(
            onClick = onToggleFullscreen,
            modifier = Modifier.size(34.dp),
        ) {
            Icon(
                imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                contentDescription = if (isFullscreen) stringResource(R.string.video_exit_fullscreen) else stringResource(R.string.video_enter_fullscreen),
                tint = if (isFullscreen) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VideoQualityDialog(
    bundle: com.lastwave.app.data.music.SyncedVideoStreamBundle,
    selectedHeight: Int?,
    onSelectQuality: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .adaptiveContentWidth(maxWidth = 380.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp, horizontal = 18.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.HighQuality,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.video_quality_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(Modifier.height(14.dp))

                val isAutoSelected = selectedHeight == null || selectedHeight <= 0
                QualityOptionRow(
                    label = stringResource(R.string.video_quality_auto_recommended),
                    subtitle = "${bundle.defaultStream.displayQuality} • Best for smooth sync",
                    isSelected = isAutoSelected,
                    onClick = {
                        onSelectQuality(null)
                        onDismiss()
                    },
                )

                Spacer(Modifier.height(4.dp))

                val heights = bundle.availableStreams
                    .mapNotNull { it.height }
                    .distinct()
                    .sortedDescending()

                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(heights) { h ->
                        val isSelected = !isAutoSelected && selectedHeight == h
                        val stream = bundle.availableStreams.firstOrNull { it.height == h }
                        val label = stream?.displayQuality ?: "${h}p"
                        val subtitle = when {
                            h >= 2160 -> "Ultra HD 4K"
                            h >= 1440 -> "Quad HD 2K"
                            h >= 1080 -> "Full HD"
                            h >= 720 -> "High Definition"
                            else -> "Standard Definition"
                        }
                        QualityOptionRow(
                            label = label,
                            subtitle = subtitle,
                            isSelected = isSelected,
                            onClick = {
                                onSelectQuality(h)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QualityOptionRow(
    label: String,
    subtitle: String? = null,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
