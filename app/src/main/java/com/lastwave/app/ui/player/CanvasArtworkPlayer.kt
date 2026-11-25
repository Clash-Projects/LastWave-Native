package com.lastwave.app.ui.player

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.os.Build
import android.util.Log
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lastwave.app.data.canvas.CANVAS_UA
import com.lastwave.app.data.canvas.CanvasArtwork
import com.lastwave.app.data.canvas.CanvasCache
import kotlinx.coroutines.delay
import java.util.Locale

private const val LOG_TAG = "CanvasArtworkPlayer"
private const val SURFACE_REPAINT_WAIT_MS = 700L
private const val FADE_IN_DURATION_MS = 320

enum class CanvasContentMode {
    CROP,
    FIT_PORTRAIT,
}

@OptIn(UnstableApi::class)
@Composable
fun CanvasArtworkPlayer(
    canvas: CanvasArtwork,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    contentMode: CanvasContentMode = CanvasContentMode.CROP,
    alignPortraitTop: Boolean = false,
    onAspectRatioChanged: (Float) -> Unit = {},
    portraitRevealBounds: IntSize = IntSize.Zero,
    presentationAlpha: () -> Float = { 1f },
    onRenderedChanged: (Boolean) -> Unit = {},
    onCoverChanged: (Float) -> Unit = {},
    bottomFade: Float = 0f,
    bottomFadeEndPx: Float? = null,
    pausedForTransition: Boolean = false,
) {
    val appContext = LocalContext.current

    var activeUrl by remember(canvas) { mutableStateOf(canvas.url) }
    var frameRendered by remember(canvas) { mutableStateOf(false) }
    var videoAspect by remember(canvas) { mutableFloatStateOf(0f) }
    var containerBounds by remember { mutableStateOf(IntSize.Zero) }
    var videoSurfaceView by remember(canvas) { mutableStateOf<TextureView?>(null) }
    var frameCounter by remember(canvas) { mutableIntStateOf(0) }
    var surfaceEpoch by remember(canvas) { mutableIntStateOf(0) }

    val latestMode by rememberUpdatedState(contentMode)
    val latestPinTop by rememberUpdatedState(alignPortraitTop)
    val latestRevealSize by rememberUpdatedState(portraitRevealBounds)
    val latestPresentationAlpha by rememberUpdatedState(presentationAlpha)
    val notifyAspect by rememberUpdatedState(onAspectRatioChanged)
    val notifyRendered by rememberUpdatedState(onRenderedChanged)
    val notifyCover by rememberUpdatedState(onCoverChanged)

    val exoPlayer = remember { buildCanvasExoPlayer(appContext) }

    DisposableEffect(exoPlayer) {
        val playbackListener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                val effectiveWidth = size.width * size.pixelWidthHeightRatio
                val ratio = if (effectiveWidth.isFinite() && effectiveWidth > 0f && size.height > 0) {
                    effectiveWidth / size.height
                } else {
                    0f
                }
                if (ratio != videoAspect) {
                    frameRendered = false
                }
                videoAspect = ratio
                notifyAspect(ratio)
                videoSurfaceView?.updateScaleMatrix(ratio, latestMode, latestPinTop)
            }

            override fun onPlayerError(error: PlaybackException) {
                val backupUrl = canvas.fallbackUrl
                if (backupUrl != null && backupUrl != activeUrl) {
                    Log.d(LOG_TAG, "Primary canvas error, falling back to: $backupUrl")
                    activeUrl = backupUrl
                } else {
                    Log.w(LOG_TAG, "Canvas playback error: ${error.message}")
                    frameRendered = false
                }
            }
        }
        exoPlayer.addListener(playbackListener)
        onDispose {
            exoPlayer.removeListener(playbackListener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(activeUrl) {
        frameRendered = false
        videoAspect = 0f
        notifyAspect(0f)

        val mediaBuilder = MediaItem.Builder().setUri(activeUrl)
        detectMimeType(activeUrl)?.let { mime -> mediaBuilder.setMimeType(mime) }
        exoPlayer.setMediaItem(mediaBuilder.build())
        exoPlayer.prepare()
    }

    val isForeground = rememberIsForeground()
    LaunchedEffect(isForeground, pausedForTransition) {
        exoPlayer.playWhenReady = isForeground && !pausedForTransition
    }

    LaunchedEffect(surfaceEpoch) {
        if (surfaceEpoch == 0) return@LaunchedEffect
        frameRendered = false
        val snapshotTick = frameCounter
        if (exoPlayer.playbackState != Player.STATE_IDLE) {
            exoPlayer.seekTo(exoPlayer.currentPosition)
        }
        delay(SURFACE_REPAINT_WAIT_MS)
        if (frameCounter == snapshotTick) {
            frameRendered = false
        }
    }

    LaunchedEffect(frameRendered) {
        notifyRendered(frameRendered)
    }

    val revealAlpha by animateFloatAsState(
        targetValue = if (frameRendered) 1f else 0f,
        animationSpec = tween(durationMillis = FADE_IN_DURATION_MS),
        label = "canvasAlpha",
    )

    LaunchedEffect(Unit) {
        snapshotFlow { revealAlpha * latestPresentationAlpha() }
            .collect { effectiveAlpha -> notifyCover(effectiveAlpha) }
    }

    DisposableEffect(Unit) {
        onDispose {
            notifyRendered(false)
            notifyCover(0f)
            notifyAspect(0f)
        }
    }

    AndroidView(
        modifier = modifier.onSizeChanged { newSize -> containerBounds = newSize },
        factory = { viewCtx ->
            val surfaceView = TextureView(viewCtx)
            surfaceView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            surfaceView.isOpaque = false
            surfaceView.alpha = 0f
            exoPlayer.setVideoTextureView(surfaceView)

            val wrappedListener = surfaceView.surfaceTextureListener
            surfaceView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                private var awaitingNewSurface = false

                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    wrappedListener?.onSurfaceTextureAvailable(surface, width, height)
                    if (awaitingNewSurface) {
                        awaitingNewSurface = false
                        surfaceEpoch++
                    }
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    wrappedListener?.onSurfaceTextureSizeChanged(surface, width, height)
                    if (latestMode == CanvasContentMode.FIT_PORTRAIT && videoAspect in 0f..1f) {
                        frameRendered = false
                    }
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    awaitingNewSurface = true
                    frameRendered = false
                    return wrappedListener?.onSurfaceTextureDestroyed(surface) ?: true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                    wrappedListener?.onSurfaceTextureUpdated(surface)
                    if (frameRendered) return

                    val matrixApplied = videoSurfaceView?.updateScaleMatrix(
                        videoAspect,
                        latestMode,
                        latestPinTop,
                    ) == true
                    val isPortraitFit = latestMode == CanvasContentMode.FIT_PORTRAIT &&
                        videoAspect > 0f && videoAspect < 1f
                    val targetBounds = latestRevealSize
                    val view = videoSurfaceView
                    val boundsMatched = !isPortraitFit || (
                        targetBounds != IntSize.Zero &&
                            view?.width == targetBounds.width &&
                            view.height == targetBounds.height
                        )

                    if ((latestMode == CanvasContentMode.CROP || matrixApplied) && boundsMatched) {
                        frameRendered = true
                        frameCounter++
                    }
                }
            }

            videoSurfaceView = surfaceView
            MaskContainerView(viewCtx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                addView(surfaceView)
            }
        },
        update = { container ->
            val surfaceView = container.getChildAt(0) as TextureView
            surfaceView.alpha = if (contentMode == CanvasContentMode.FIT_PORTRAIT && videoAspect <= 0f) {
                0f
            } else {
                revealAlpha * presentationAlpha()
            }
            surfaceView.updateScaleMatrix(videoAspect, contentMode, alignPortraitTop)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surfaceView.applyApi31BottomFade(bottomFade, containerBounds, bottomFadeEndPx)
            } else {
                container.fadeRatio = bottomFade
                container.fadeLimitPx = bottomFadeEndPx
            }
        },
    )
}

@OptIn(UnstableApi::class)
private fun buildCanvasExoPlayer(context: Context): ExoPlayer {
    val httpSource = DefaultHttpDataSource.Factory()
        .setUserAgent(CANVAS_UA)
        .setAllowCrossProtocolRedirects(true)
    val upstream = DefaultDataSource.Factory(context, httpSource)
    val cachedSource = CanvasCache.dataSourceFactory(context, upstream)

    val player = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(cachedSource))
        .build()

    player.volume = 0f
    player.repeatMode = Player.REPEAT_MODE_ONE
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        .setForceHighestSupportedBitrate(true)
        .setMaxVideoSize(2048, 2048)
        .build()
    return player
}

private fun TextureView.updateScaleMatrix(
    clipAspect: Float,
    mode: CanvasContentMode,
    pinPortraitTop: Boolean,
): Boolean {
    if (width <= 0 || height <= 0 || !clipAspect.isFinite() || clipAspect <= 0f) {
        setTransform(Matrix())
        return false
    }

    val containerAspect = width.toFloat() / height.toFloat()
    val anchorX = width / 2f
    val anchorY = if (pinPortraitTop && clipAspect < 1f) 0f else height / 2f
    val fitInside = mode == CanvasContentMode.FIT_PORTRAIT && clipAspect < 1f
    val scaleHorizontal = if (fitInside) clipAspect <= containerAspect else clipAspect > containerAspect

    val transform = Matrix()
    if (scaleHorizontal) {
        transform.setScale(clipAspect / containerAspect, 1f, anchorX, anchorY)
    } else {
        transform.setScale(1f, containerAspect / clipAspect, anchorX, anchorY)
    }
    setTransform(transform)
    return true
}

@RequiresApi(Build.VERSION_CODES.S)
private fun TextureView.applyApi31BottomFade(
    ratio: Float,
    bounds: IntSize,
    limitPx: Float?,
) {
    val endY = limitPx?.coerceIn(0f, bounds.height.toFloat()) ?: bounds.height.toFloat()
    if (ratio <= 0.001f || endY <= 0f) {
        setRenderEffect(null)
        return
    }
    val shader = buildFadeGradient(endY, ratio.coerceAtMost(1f))
    try {
        val effect = RenderEffect.createBlendModeEffect(
            RenderEffect.createOffsetEffect(0f, 0f),
            RenderEffect.createShaderEffect(shader),
            BlendMode.DST_IN,
        )
        setRenderEffect(effect)
    } catch (_: Throwable) {
    }
}

private class MaskContainerView(context: Context) : FrameLayout(context) {
    var fadeRatio: Float = 0f
        set(value) {
            val normalized = value.coerceIn(0f, 1f)
            if (normalized == field) return
            field = normalized
            cachedShader = null
            invalidate()
        }

    var fadeLimitPx: Float? = null
        set(value) {
            if (value == field) return
            field = value
            cachedShader = null
            invalidate()
        }

    private val alphaMaskPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private var cachedShader: LinearGradient? = null
    private var shaderForHeight = 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cachedShader = null
    }

    override fun dispatchDraw(canvas: Canvas) {
        val ratio = fadeRatio
        val endY = fadeLimitPx?.coerceIn(0f, height.toFloat()) ?: height.toFloat()
        if (ratio <= 0.001f || endY <= 0f) {
            super.dispatchDraw(canvas)
            return
        }

        var shader = cachedShader
        if (shader == null || shaderForHeight != height) {
            shader = buildFadeGradient(endY, ratio)
            cachedShader = shader
            shaderForHeight = height
        }
        alphaMaskPaint.shader = shader

        val saveId = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), alphaMaskPaint)
        canvas.restoreToCount(saveId)
    }
}

private fun buildFadeGradient(endY: Float, ratio: Float): LinearGradient =
    LinearGradient(
        0f,
        endY * (1f - ratio),
        0f,
        endY,
        Color.BLACK,
        Color.TRANSPARENT,
        Shader.TileMode.CLAMP,
    )

private fun detectMimeType(url: String): String? {
    val cleanPath = url.substringBefore('?').lowercase(Locale.ROOT)
    return when {
        cleanPath.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
        cleanPath.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
        else -> null
    }
}
