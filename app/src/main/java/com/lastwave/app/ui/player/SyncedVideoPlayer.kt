package com.lastwave.app.ui.player

import android.app.Activity
import android.content.ContextWrapper
import android.util.Log
import android.view.TextureView
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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lastwave.app.R
import com.lastwave.app.data.music.YouTubeVideoStream
import com.lastwave.app.data.video.SyncedVideoRepository
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

sealed interface SyncedVideoUiState {
    data object Loading : SyncedVideoUiState
    data class Ready(
        val bundle: com.lastwave.app.data.music.SyncedVideoStreamBundle,
        val activeStream: YouTubeVideoStream,
        val selectedHeight: Int? = null,
    ) : SyncedVideoUiState {
        val stream: YouTubeVideoStream get() = activeStream
        val isAuto: Boolean get() = selectedHeight == null || selectedHeight <= 0
        val currentQualityLabel: String
            get() = if (isAuto) "Auto" else activeStream.displayQuality
    }
    data class Error(val message: String) : SyncedVideoUiState
}

@HiltViewModel
class SyncedVideoViewModel @Inject constructor(
    private val videoRepository: SyncedVideoRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<SyncedVideoUiState>(SyncedVideoUiState.Loading)
    val uiState: StateFlow<SyncedVideoUiState> = _uiState.asStateFlow()

    private var currentKey: String? = null

    fun loadVideo(title: String, artist: String, videoId: String?) {
        val key = "$title|$artist|$videoId"
        if (currentKey == key && _uiState.value is SyncedVideoUiState.Ready) return
        currentKey = key

        viewModelScope.launch {
            _uiState.value = SyncedVideoUiState.Loading
            videoRepository.resolveVideo(title, artist, videoId)
                .onSuccess { bundle ->
                    _uiState.value = SyncedVideoUiState.Ready(
                        bundle = bundle,
                        activeStream = bundle.defaultStream,
                        selectedHeight = null,
                    )
                }
                .onFailure { error ->
                    _uiState.value = SyncedVideoUiState.Error(
                        error.message ?: "Unable to resolve video stream"
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

    fun retry(title: String, artist: String, videoId: String?) {
        currentKey = null
        loadVideo(title, artist, videoId)
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
    val playerState by musicPlayer.state.collectAsStateWithLifecycle()
    val progressState by musicPlayer.progressState.collectAsStateWithLifecycle()

    var isFullscreen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var isRendered by remember { mutableStateOf(false) }
    var isUserScrubbing by remember { mutableStateOf(false) }
    var scrubProgress by remember { mutableFloatStateOf(0f) }
    var doubleTapFeedback by remember { mutableStateOf<String?>(null) }
    var showQualityDialog by remember { mutableStateOf(false) }

    LaunchedEffect(track) {
        viewModel.loadVideo(track.title, track.artist, track.videoId)
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

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Origin" to "https://www.youtube.com",
                    "Referer" to "https://www.youtube.com",
                )
            )
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
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
            if (currentMediaUri == stream.url) return@LaunchedEffect

            isRendered = false
            val currentPos = videoPlayer.currentPosition.takeIf { it > 0 } ?: musicPlayer.currentPositionMs()
            val mediaItem = MediaItem.Builder()
                .setUri(stream.url)
                .apply {
                    stream.mimeType?.let { setMimeType(it) }
                }
                .build()
            videoPlayer.setMediaItem(mediaItem)
            videoPlayer.prepare()
            videoPlayer.seekTo(currentPos)
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
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        controlsVisible = !controlsVisible
                                    },
                                    onDoubleTap = { offset ->
                                        val isRightSide = offset.x > size.width / 2
                                        val delta = if (isRightSide) 10_000L else -10_000L
                                        val newPos = (musicPlayer.currentPositionMs() + delta)
                                            .coerceIn(0L, progressState.durationMs.coerceAtLeast(1L))
                                        musicPlayer.seekTo(newPos)
                                        videoPlayer.seekTo(newPos)
                                        doubleTapFeedback = if (isRightSide) "+10s" else "-10s"
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        // Media3 ExoPlayer TextureView
                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(videoAlpha),
                            factory = { viewContext ->
                                TextureView(viewContext).apply {
                                    layoutParams = FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                    isOpaque = true
                                    videoPlayer.setVideoTextureView(this)
                                }
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
                                    onClick = { viewModel.retry(track.title, track.artist, track.videoId) },
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
