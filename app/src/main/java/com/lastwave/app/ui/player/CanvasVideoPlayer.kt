package com.lastwave.app.ui.player

import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lastwave.app.data.canvas.CANVAS_UA
import com.lastwave.app.data.canvas.CanvasCache

/**
 * Reusable Compose wrapper around AndroidX Media3 ExoPlayer for rendering Spotify Canvas
 * and looping video clips behind player controls.
 *
 * - Muted (volume = 0f, audio tracks disabled to conserve CPU/bandwidth).
 * - Loops seamlessly via [Player.REPEAT_MODE_ONE].
 * - Cropped and scaled to fill bounds using [C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING].
 * - Lightweight buffering profile with [DefaultLoadControl].
 * - Automatic resource cleanup when off-screen or disposed.
 * - Smooth crossfade on video ready.
 * - Subtle top-to-bottom dark gradient scrim for maximum control legibility.
 */
@OptIn(UnstableApi::class)
@Composable
fun CanvasVideoPlayer(
    videoUrl: String,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    enableScrim: Boolean = true,
    scrimTopAlpha: Float = 0.35f,
    scrimBottomAlpha: Float = 0.65f,
    onReady: () -> Unit = {},
) {
    val context = LocalContext.current
    var isRendered by remember { mutableStateOf(false) }

    // Lightweight load control to minimize buffer memory footprint
    val loadControl = remember {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 2_000,
                /* maxBufferMs = */ 8_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    val player = remember(context) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(CANVAS_UA)
            .setAllowCrossProtocolRedirects(true)
        val upstream = DefaultDataSource.Factory(context, httpFactory)
        val cachedFactory = CanvasCache.dataSourceFactory(context, upstream)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cachedFactory))
            .setLoadControl(loadControl)
            .build()
            .apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                isRendered = true
                onReady()
            }

            override fun onPlayerError(error: PlaybackException) {
                isRendered = false
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(videoUrl) {
        isRendered = false
        if (videoUrl.isNotBlank()) {
            player.setMediaItem(MediaItem.fromUri(videoUrl))
            player.prepare()
            player.playWhenReady = isPlaying
        } else {
            player.stop()
            player.clearMediaItems()
        }
    }

    LaunchedEffect(isPlaying) {
        player.playWhenReady = isPlaying
    }

    val alpha by animateFloatAsState(
        targetValue = if (isRendered) 1f else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "canvasCrossfade",
    )

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .alpha(alpha),
            factory = { viewContext ->
                TextureView(viewContext).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    isOpaque = false
                    player.setVideoTextureView(this)
                }
            },
        )

        if (enableScrim) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = scrimTopAlpha),
                                Color.Transparent,
                                Color.Black.copy(alpha = scrimBottomAlpha),
                            ),
                        ),
                    ),
            )
        }
    }
}
