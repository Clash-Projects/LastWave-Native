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

private const val TAG = "SyncedVideoPlayer"
internal const val VIDEO_PREFS_NAME = "synced_video"
internal const val VIDEO_OVERRIDE_PREFS_NAME = "synced_video_overrides"
internal const val PREF_VIDEO_LAST_USED_MS = "video_last_used_ms"
internal const val VIDEO_PRELOAD_DELAY_MS = 1_500L
internal const val MAX_LINK_REFRESHES_PER_SONG = 2

internal fun videoAspectRatio(videoSize: VideoSize): Float? {
    if (videoSize.width <= 0 || videoSize.height <= 0) return null
    val w = videoSize.width * videoSize.pixelWidthHeightRatio
    val h = videoSize.height.toFloat()
    val rotated = videoSize.unappliedRotationDegrees % 180 != 0
    val aspect = if (rotated) h / w else w / h
    return if (aspect.isFinite() && aspect > 0f) aspect else null
}

/** Detects taps on the video without consuming gestures already handled by overlay controls. */
internal fun Modifier.videoTapGestures(
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
internal fun videoMediaHttp(context: android.content.Context): okhttp3.OkHttpClient =
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
internal fun adaptiveVideoPlayerBuilder(
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

    var videoAspectRatio by remember(activeStream) {
        val initial = videoAspectRatio(videoPlayer.videoSize)
            ?: activeStream?.let { s ->
                val w = s.width
                val h = s.height
                if (w != null && h != null && w > 0 && h > 0) w.toFloat() / h.toFloat() else null
            }
            ?: (16f / 9f)
        mutableFloatStateOf(initial)
    }

    // Initialise isRendered from current player state so returning to a ready player
    // does not flash the loading spinner.
    var isRenderedLocal = videoPlayer.playbackState == Player.STATE_READY
            && videoPlayer.currentMediaItem != null
    if (isRenderedLocal) isRendered = true

    DisposableEffect(videoPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoAspectRatio(videoSize)?.let { aspect ->
                    videoAspectRatio = aspect
                }
            }

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

    var videoAspectRatio by remember(activeStream) {
        val initial = videoAspectRatio(videoPlayer.videoSize)
            ?: activeStream?.let { s ->
                val w = s.width
                val h = s.height
                if (w != null && h != null && w > 0 && h > 0) w.toFloat() / h.toFloat() else null
            }
            ?: (16f / 9f)
        mutableFloatStateOf(initial)
    }

    var isRenderedLocal = videoPlayer.playbackState == Player.STATE_READY
            && videoPlayer.currentMediaItem != null
    if (isRenderedLocal) isRendered = true

    DisposableEffect(videoPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoAspectRatio(videoSize)?.let { aspect ->
                    videoAspectRatio = aspect
                }
            }

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
