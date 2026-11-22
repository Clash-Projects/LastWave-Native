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

private const val TAG = "SyncedVideoDialog"


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

    var videoAspectRatio by remember(uiState) {
        val stream = (uiState as? SyncedVideoUiState.Ready)?.activeStream
        val initial = videoAspectRatio(videoPlayer.videoSize)
            ?: stream?.let { s ->
                val w = s.width
                val h = s.height
                if (w != null && h != null && w > 0 && h > 0) w.toFloat() / h.toFloat() else null
            }
            ?: (16f / 9f)
        mutableFloatStateOf(initial)
    }

    DisposableEffect(videoPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoAspectRatio(videoSize)?.let { aspect ->
                    videoAspectRatio = aspect
                }
            }

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
                            modifier = Modifier.aspectRatio(videoAspectRatio),
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
