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
import androidx.media3.common.VideoSize
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

private const val TAG = "SyncedVideoViewModel"


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
