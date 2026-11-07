package com.lastwave.app.ui.player

import android.content.Context
import android.util.Log
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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
                // Motion art is adaptive (HLS) with several resolutions: the default starts low and
                // a short loop rarely climbs, so clips looked soft. Take the sharpest variant the
                // display can show (the viewport size cap still applies).
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setForceHighestSupportedBitrate(true)
                    // No 4K: sharp on any phone screen without straining the decoder.
                    .setMaxVideoSize(2048, 2048)
                    .build()
            }
    }

    // TextureView ignores videoScalingMode and stretches every frame to its
    // bounds, distorting clips whose shape differs from the container (a
    // wide motion clip in a tall artist header). Center-crop it ourselves
    // with a transform that keeps the real aspect ratio.
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var videoAspect by remember { mutableStateOf(0f) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                val w = videoSize.width * videoSize.pixelWidthHeightRatio
                val h = videoSize.height.toFloat()
                val rotated = videoSize.unappliedRotationDegrees % 180 != 0
                videoAspect = if (rotated) h / w else w / h
                textureView?.let { applyCenterCrop(it, videoAspect) }
            }

            override fun onRenderedFirstFrame() {
                isRendered = true
                onReady()
            }

            override fun onPlayerError(error: PlaybackException) {
                isRendered = false
                Log.w("CanvasVideoPlayer", "Playback error for $videoUrl: ${error.message}", error)
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
            val mediaItem = MediaItem.Builder()
                .setUri(videoUrl)
                .apply {
                    if (videoUrl.contains(".m3u8", ignoreCase = true)) {
                        setMimeType(MimeTypes.APPLICATION_M3U8)
                    }
                }
                .build()
            player.setMediaItem(mediaItem)
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

    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .alpha(alpha),
            factory = { viewContext ->
                TextureView(viewContext).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    isOpaque = false
                    player.setVideoTextureView(this)
                    addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                        applyCenterCrop(view as TextureView, videoAspect)
                    }
                    textureView = this
                }
            },
            update = { view -> applyCenterCrop(view, videoAspect) },
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

/**
 * Scales the (stretched-to-bounds) TextureView content so the video keeps
 * its own aspect ratio and fills the view, cropping the overflow evenly
 * (the TextureView equivalent of ContentScale.Crop).
 */
private fun applyCenterCrop(view: TextureView, videoAspect: Float) {
    val viewW = view.width.toFloat()
    val viewH = view.height.toFloat()
    if (viewW <= 0f || viewH <= 0f || videoAspect <= 0f) return
    val viewAspect = viewW / viewH
    val matrix = android.graphics.Matrix()
    if (videoAspect > viewAspect) {
        // Video is wider than the view: keep full height, widen and crop sides.
        matrix.setScale(videoAspect / viewAspect, 1f, viewW / 2f, viewH / 2f)
    } else {
        // Video is taller than the view: keep full width, heighten and crop top/bottom.
        matrix.setScale(1f, viewAspect / videoAspect, viewW / 2f, viewH / 2f)
    }
    view.setTransform(matrix)
}

