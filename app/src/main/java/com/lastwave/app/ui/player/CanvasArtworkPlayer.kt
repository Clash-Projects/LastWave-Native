package com.lastwave.app.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val TAG = "CanvasArtworkPlayer"
private const val REPAINT_TIMEOUT_MS = 700L
private const val FRAME_CAPTURE_PX = 128

/**
 * Duration of the single fade that owns the still-cover <-> moving-clip crossfade.
 *
 * Callers derive the cover alpha as `1f - <reported clip alpha>`, so this one
 * duration is the whole contract: two mismatched fades leave the pair summing to
 * less than full opacity mid-transition and the backdrop shows through the
 * artwork box. One constant, one animation.
 */
internal const val CANVAS_FADE_MS = 300

/**
 * How a motion artwork clip fills its container bounds.
 */
enum class CanvasContentMode {
    CROP,
    FIT_PORTRAIT,
}

/**
 * A looping video player that renders animated album artwork over a still sleeve or background.
 *
 * Dedicated, silent ExoPlayer:
 * - Audio tracks disabled entirely (no network bandwidth spent on audio streams).
 * - Zero volume, no audio focus requested (zero ducking or interference with the main music player).
 * - Loops via REPEAT_MODE_ONE.
 * - Cached via [CanvasCache] so repeated loops stream purely from disk.
 * - Fades itself in over [CANVAS_FADE_MS] once the first frame arrives, and reports
 *   that exact alpha through [onCoverChanged] so the caller never runs a second,
 *   competing fade. Pass [fadeOut] to drain the clip through the same fade when the
 *   caller needs it gone before it leaves the tree.
 * - Pauses decoding when the screen is off or app is backgrounded ([rememberIsForeground]).
 */
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
    fadeOut: Boolean = false,
    onFrameCaptured: (Bitmap) -> Unit = {},
    refreshFrameEveryMs: Long? = null,
    frameCapturePx: Int = FRAME_CAPTURE_PX,
    bottomFade: Float = 0f,
    bottomFadeEndPx: Float? = null,
    bottomFadeFallbackColor: Int? = null,
    pausedForTransition: Boolean = false,
) {
    val context = LocalContext.current

    var url by remember(canvas) { mutableStateOf(canvas.url) }
    var rendered by remember(canvas) { mutableStateOf(false) }
    var clipAspect by remember(canvas) { mutableFloatStateOf(0f) }
    // Deliberately NOT keyed on [canvas]: AndroidView builds its TextureView once via
    // `factory`, so a per-canvas slot would reset to null on the next track and never
    // be repopulated - silently disabling the transform and frame capture from then on.
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var frameTick by remember(canvas) { mutableIntStateOf(0) }
    var surfaceGeneration by remember(canvas) { mutableIntStateOf(0) }

    val currentContentMode by rememberUpdatedState(contentMode)
    val currentAlignPortraitTop by rememberUpdatedState(alignPortraitTop)
    val currentPortraitRevealBounds by rememberUpdatedState(portraitRevealBounds)
    val currentPresentationAlpha by rememberUpdatedState(presentationAlpha)
    val currentFadeOut by rememberUpdatedState(fadeOut)
    val reportAspect by rememberUpdatedState(onAspectRatioChanged)

    val player = remember {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(CANVAS_UA)
            .setAllowCrossProtocolRedirects(true)
        val upstream = DefaultDataSource.Factory(context, httpFactory)
        val cachedFactory = CanvasCache.dataSourceFactory(context, upstream)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cachedFactory))
            .build()
            .apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val width = videoSize.width * videoSize.pixelWidthHeightRatio
                val aspect = if (width.isFinite() && width > 0f && videoSize.height > 0) {
                    width / videoSize.height
                } else 0f
                if (aspect != clipAspect) rendered = false
                clipAspect = aspect
                reportAspect(aspect)
                textureView?.applyContentTransform(clipAspect, currentContentMode, currentAlignPortraitTop)
            }

            override fun onPlayerError(error: PlaybackException) {
                val alternate = canvas.fallbackUrl
                if (alternate != null && alternate != url) {
                    Log.d(TAG, "Primary canvas error, falling back to: $alternate")
                    url = alternate
                } else {
                    Log.w(TAG, "Canvas playback error: ${error.message}")
                    rendered = false
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(url) {
        rendered = false
        clipAspect = 0f
        // Deliberately not reporting aspect 0 here. Zeroing it made the caller's
        // hero sizing collapse to its short form and then grow again once the new
        // clip reported its size - two relayouts inside a single track change.
        // Holding the last known aspect keeps the box still until real geometry
        // arrives; teardown still reports 0 via onDispose.
        val item = MediaItem.Builder().setUri(url)
        mimeTypeOf(url)?.let { item.setMimeType(it) }
        player.setMediaItem(item.build())
        player.prepare()
    }

    val foreground = rememberIsForeground()
    LaunchedEffect(foreground, pausedForTransition) {
        player.playWhenReady = foreground && !pausedForTransition
    }

    LaunchedEffect(surfaceGeneration) {
        if (surfaceGeneration == 0) return@LaunchedEffect
        rendered = false
        val before = frameTick
        if (player.playbackState != Player.STATE_IDLE) {
            player.seekTo(player.currentPosition)
        }
        delay(REPAINT_TIMEOUT_MS)
        if (frameTick == before) {
            rendered = false
        }
    }

    val alpha by animateFloatAsState(
        // [fadeOut] drains the clip through the same fade the enter uses, so a caller
        // that has to unmount it (the next track has no canvas) can raise the still
        // cover underneath instead of the clip popping out of the tree.
        targetValue = if (rendered && !currentFadeOut) 1f else 0f,
        animationSpec = tween(durationMillis = CANVAS_FADE_MS),
        label = "canvasAlpha",
    )

    // Report both signals synchronously. Going through LaunchedEffect/snapshotFlow
    // cost a frame plus a coroutine dispatch before the caller could even start its
    // own fade, which is what left the crossfade permanently out of step.
    val reportRendered by rememberUpdatedState(onRenderedChanged)
    val reportCover by rememberUpdatedState(onCoverChanged)
    SideEffect {
        reportRendered(rendered)
        reportCover(alpha * currentPresentationAlpha())
    }

    LaunchedEffect(rendered) {
        if (!rendered) return@LaunchedEffect
        withFrameMillis { }
        val view = textureView ?: return@LaunchedEffect
        val bitmap = view.captureAt(frameCapturePx, clipAspect, contentMode, alignPortraitTop)
        if (bitmap != null) {
            onFrameCaptured(bitmap)
        }
    }

    LaunchedEffect(rendered, refreshFrameEveryMs, frameCapturePx, clipAspect, contentMode, alignPortraitTop) {
        val interval = refreshFrameEveryMs ?: return@LaunchedEffect
        if (!rendered) return@LaunchedEffect
        while (isActive) {
            delay(interval)
            val view = textureView ?: continue
            val bitmap = view.captureAt(frameCapturePx, clipAspect, contentMode, alignPortraitTop)
            if (bitmap != null) {
                onFrameCaptured(bitmap)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            reportRendered(false)
            reportCover(0f)
            reportAspect(0f)
            textureView = null
        }
    }

    AndroidView(
        factory = { viewContext ->
            val texture = TextureView(viewContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                isOpaque = true
                this.alpha = 0f
                player.setVideoTextureView(this)

                val delegate = surfaceTextureListener
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var replacing = false

                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        delegate?.onSurfaceTextureAvailable(surface, width, height)
                        if (!replacing) return
                        replacing = false
                        surfaceGeneration++
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                        delegate?.onSurfaceTextureSizeChanged(surface, width, height)
                        if (currentContentMode == CanvasContentMode.FIT_PORTRAIT && clipAspect in 0f..1f) {
                            rendered = false
                        }
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        replacing = true
                        rendered = false
                        return delegate?.onSurfaceTextureDestroyed(surface) ?: true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                        delegate?.onSurfaceTextureUpdated(surface)
                        if (!rendered) {
                            val transformed = textureView?.applyContentTransform(
                                clipAspect, currentContentMode, currentAlignPortraitTop,
                            ) == true
                            val portrait = currentContentMode == CanvasContentMode.FIT_PORTRAIT &&
                                clipAspect > 0f && clipAspect < 1f
                            val expected = currentPortraitRevealBounds
                            val view = textureView
                            val layoutReady = !portrait ||
                                (expected != IntSize.Zero && view?.width == expected.width &&
                                    view?.height == expected.height)
                            if ((currentContentMode == CanvasContentMode.CROP || transformed) && layoutReady) {
                                rendered = true
                                frameTick++
                            }
                        }
                    }
                }
            }
            val background = View(viewContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(android.graphics.Color.BLACK)
                this.alpha = 0f
            }
            textureView = texture
            FadingBottomFrame(viewContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                addView(background)
                addView(texture)
            }
        },
        update = { frame ->
            val bg = frame.getChildAt(0)
            val view = frame.getChildAt(1) as TextureView
            val effectiveAlpha = if (contentMode == CanvasContentMode.FIT_PORTRAIT && clipAspect <= 0f) {
                0f
            } else {
                alpha * presentationAlpha()
            }
            bg.alpha = effectiveAlpha
            view.alpha = effectiveAlpha
            view.applyContentTransform(clipAspect, contentMode, alignPortraitTop)
            // Fade the clip with a plain saveLayer + DST_IN gradient on the parent
            // FrameLayout, on every API level. The Android 12+ branch used to attach a
            // blend-mode RenderEffect straight to this TextureView; a video surface
            // forced through a blend RenderNode is what underflowed the canvas save
            // stack ("Underflow in restore - more restores than saves") and killed the
            // process. Same gradient, same 0.30 strength, same pixels - no RenderEffect.
            frame.fadeFraction = bottomFade
            frame.fadeEndPx = bottomFadeEndPx
            frame.fadeFallbackColor = bottomFadeFallbackColor
        },
        modifier = modifier,
    )
}

private fun TextureView.captureAt(
    maxPx: Int,
    clipAspect: Float,
    contentMode: CanvasContentMode,
    alignPortraitTop: Boolean,
): Bitmap? {
    val viewWidth = width
    val viewHeight = height
    if (viewWidth <= 0 || viewHeight <= 0) return null
    val scale = maxPx.toFloat() / maxOf(viewWidth, viewHeight)
    return runCatching {
        val frame = if (scale >= 1f) {
            getBitmap()
        } else {
            getBitmap(
                (viewWidth * scale).roundToInt().coerceAtLeast(1),
                (viewHeight * scale).roundToInt().coerceAtLeast(1),
            )
        } ?: return null
        if (contentMode != CanvasContentMode.FIT_PORTRAIT || clipAspect >= 1f || clipAspect <= 0f) {
            return frame
        }

        val viewAspect = viewWidth.toFloat() / viewHeight
        val contentWidth = if (clipAspect < viewAspect) frame.height * clipAspect else frame.width.toFloat()
        val contentHeight = if (clipAspect < viewAspect) frame.height.toFloat() else frame.width / clipAspect
        val left = ceil((frame.width - contentWidth) / 2f).toInt().coerceIn(0, frame.width - 1)
        val top = if (alignPortraitTop) 0 else
            ceil((frame.height - contentHeight) / 2f).toInt().coerceIn(0, frame.height - 1)
        val right = (frame.width - left).coerceAtLeast(left + 1)
        val bottom = if (alignPortraitTop) contentHeight.toInt().coerceIn(1, frame.height) else
            (frame.height - top).coerceAtLeast(top + 1)
        Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
    }.getOrNull()
}

private fun TextureView.applyContentTransform(
    clipAspect: Float,
    contentMode: CanvasContentMode,
    alignPortraitTop: Boolean,
): Boolean {
    val bounds = IntSize(width, height)
    if (bounds.width <= 0 || bounds.height <= 0 || !clipAspect.isFinite() || clipAspect <= 0f) {
        setTransform(Matrix())
        return false
    }
    val viewAspect = bounds.width.toFloat() / bounds.height
    val pivotX = bounds.width / 2f
    val pivotY = if (alignPortraitTop && clipAspect < 1f) {
        0f
    } else bounds.height / 2f
    val matrix = Matrix().apply {
        val fit = contentMode == CanvasContentMode.FIT_PORTRAIT && clipAspect < 1f
        if (fit && clipAspect > viewAspect) {
            setScale(1f, viewAspect / clipAspect, pivotX, pivotY)
        } else if (fit) {
            setScale(clipAspect / viewAspect, 1f, pivotX, pivotY)
        } else if (clipAspect > viewAspect) {
            setScale(clipAspect / viewAspect, 1f, pivotX, pivotY)
        } else {
            setScale(1f, viewAspect / clipAspect, pivotX, pivotY)
        }
    }
    setTransform(matrix)
    return true
}

private class FadingBottomFrame(context: Context) : FrameLayout(context) {
    var fadeFraction: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped == field) return
            field = clamped
            gradient = null
            invalidate()
        }
    var fadeEndPx: Float? = null
        set(value) {
            if (value == field) return
            field = value
            gradient = null
            invalidate()
        }

    private val maskPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    /**
     * Colour to fade toward when the GPU refuses the saveLayer + DST_IN mask. Without it
     * a broken layer would leave the clip with a hard bottom edge that reads as flat
     * cover art; with it the melt survives as an ordinary source-over gradient.
     */
    var fadeFallbackColor: Int? = null
        set(value) {
            if (value == field) return
            field = value
            invalidate()
        }

    private var gradient: LinearGradient? = null
    private var gradientHeight = 0

    /**
     * Set once the GPU refuses this saveLayer+DST_IN pass. [bottomFade] is the only thing
     * that depends on it, so degrading to the fallback gradient is the right failure mode
     * - a missing blend mode must never take the process with it.
     */
    private var fadeBroken = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradient = null
    }

    override fun dispatchDraw(canvas: Canvas) {
        val fade = fadeFraction
        val endY = fadeEndPx?.coerceIn(0f, height.toFloat()) ?: height.toFloat()
        if (fade <= 0.001f || endY <= 0f) {
            super.dispatchDraw(canvas)
            return
        }
        if (fadeBroken) {
            drawWithFallbackFade(canvas, fade, endY)
            return
        }
        val shader = gradient?.takeIf { gradientHeight == height } ?: LinearGradient(
            0f,
            endY * (1f - fade),
            0f,
            endY,
            android.graphics.Color.BLACK,
            android.graphics.Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        ).also {
            gradient = it
            gradientHeight = height
        }
        maskPaint.shader = shader
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        // saveLayer returns -1 when the driver refuses to allocate the layer.
        if (layer < 0) {
            giveUpOnBlendMask()
            drawWithFallbackFade(canvas, fade, endY)
            return
        }
        var restored = false
        try {
            super.dispatchDraw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        } catch (graphics: RuntimeException) {
            giveUpOnBlendMask()
        } finally {
            // Always hand the save stack back exactly what this pass took, even if the
            // child draw blew up - otherwise every later restore underflows.
            restored = runCatching { canvas.restoreToCount(layer) }.isSuccess
        }
        if (!restored) giveUpOnBlendMask()
    }

    /**
     * Same ramp as the DST_IN mask, but plain source-over toward [fadeFallbackColor], so a
     * refused layer costs the blend mode and nothing else. Falls back to an unpainted
     * child draw when the caller supplied no colour.
     */
    private fun drawWithFallbackFade(canvas: Canvas, fade: Float, endY: Float) {
        super.dispatchDraw(canvas)
        val color = fadeFallbackColor ?: return
        val top = endY * (1f - fade)
        canvas.drawRect(
            0f,
            top,
            width.toFloat(),
            height.toFloat(),
            Paint().apply {
                shader = LinearGradient(
                    0f,
                    top,
                    0f,
                    endY,
                    color and 0x00FFFFFF,
                    color,
                    Shader.TileMode.CLAMP,
                )
            },
        )
    }

    private fun giveUpOnBlendMask() {
        if (fadeBroken) return
        fadeBroken = true
        maskPaint.shader = null
        gradient = null
        Log.w(TAG, "Blend-mode bottom fade unavailable, using source-over gradient")
    }
}

private fun mimeTypeOf(url: String): String? {
    val path = url.substringBefore('?').lowercase(Locale.ROOT)
    return when {
        path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
        path.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
        else -> null
    }
}
