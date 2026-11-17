package com.lastwave.app.ui.player

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.data.artwork.ArtworkNormalizer
import com.lastwave.app.ui.common.ArtworkViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.lang.annotations.Language
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

@Language("AGSL")
internal val FLUID_AGSL = """
    uniform shader curArt;
    uniform shader prevArt;
    uniform float2 curArtSize;
    uniform float2 prevArtSize;
    uniform float fade;

    uniform float4 sides;
    uniform float4 angles;
    uniform float2 centre0;
    uniform float2 centre1;
    uniform float2 centre2;
    uniform float2 centre3;

    uniform float2 twistA;
    uniform float2 twistB;
    uniform float twistRadius;
    uniform float twistAngle;

    uniform float saturation;
    uniform float brightness;
    uniform float contrast;

    float2 twist(float2 coord, float2 offset, float radius, float angle) {
        float2 d = coord - offset;
        float dist = length(d);
        if (dist < radius) {
            float ratio = (radius - dist) / radius;
            float a = ratio * ratio * angle;
            float s = sin(a);
            float c = cos(a);
            d = float2(d.x * c - d.y * s, d.x * s + d.y * c);
        }
        return d + offset;
    }

    float2 localOf(float2 p, float2 centre, float angle, float side) {
        float2 d = p - centre;
        float c = cos(-angle);
        float s = sin(-angle);
        return float2(d.x * c - d.y * s, d.x * s + d.y * c) / side + 0.5;
    }

    bool covers(float2 uv) {
        return uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0;
    }

    half4 main(float2 fragCoord) {
        float2 p = twist(fragCoord, twistA, twistRadius, twistAngle);
        p = twist(p, twistB, twistRadius, -twistAngle);

        float2 uv = clamp(localOf(p, centre0, angles.x, sides.x), 0.0, 1.0);
        float2 u1 = localOf(p, centre1, angles.y, sides.y);
        float2 u2 = localOf(p, centre2, angles.z, sides.z);
        float2 u3 = localOf(p, centre3, angles.w, sides.w);
        if (covers(u1)) uv = u1;
        if (covers(u2)) uv = u2;
        if (covers(u3)) uv = u3;

        float3 cur = float3(curArt.eval(uv * curArtSize).rgb);
        float3 prv = float3(prevArt.eval(uv * prevArtSize).rgb);
        float3 c = mix(prv, cur, fade);

        float mid = (c.r + c.g + c.b) / 3.0;
        c = float3(mid) + (c - float3(mid)) * saturation;
        c *= brightness;
        c = c * (1.0 + contrast) - float3(0.5 * contrast);
        return half4(half3(clamp(c, 0.0, 1.0)), 1.0);
    }
""".trimIndent()

internal data class FluidCopy(
    val centreX: Float,
    val centreY: Float,
    val side: Float,
    val angle: Float,
)

internal fun fluidCopies(
    width: Float,
    height: Float,
    seconds: Float,
    seed: Int,
    rhythm: Float = 0f,
    beatSync: Boolean = false,
): List<FluidCopy> {
    if (!beatSync) {
        // Original LastWave fluid copy calculation (smooth, gentle ambient flow)
        val longest = maxOf(width, height)
        val t = seconds * FLOW_SPEED_ORIGINAL
        val cx = width / 2f
        val cy = height / 2f
        val orbit = { rate: Float, radius: Float, phase: Float ->
            val a = rate * t + phase
            cx + width * radius * cos(a) to cy + width * radius * sin(a)
        }
        val (x3, y3) = orbit(ORBIT_RATE_MID, ORBIT_RADIUS_MID, startPhase(seed, 4))
        val (x4, y4) = orbit(ORBIT_RATE_SMALL, ORBIT_RADIUS_SMALL, startPhase(seed, 5))
        return listOf(
            FluidCopy(cx, cy, longest * SQRT_2, startPhase(seed, 0) + ROTATION_ORIGINAL[0] * t),
            FluidCopy(width / 2.5f, height / 2.5f, longest * 0.8f, startPhase(seed, 1) + ROTATION_ORIGINAL[1] * t),
            FluidCopy(x3, y3, longest * 0.5f, startPhase(seed, 2) + ROTATION_ORIGINAL[2] * t),
            FluidCopy(x4, y4, longest * 0.25f, startPhase(seed, 3) + ROTATION_ORIGINAL[3] * t),
        )
    }

    // Beat-synced fluid copies (Lyra-spec: dynamic lobe dilation & orbit expansion)
    val longest = maxOf(width, height) * (1.0f + rhythm * 0.32f)
    val t = seconds * FLOW_SPEED_ORIGINAL
    val cx = width / 2f
    val cy = height / 2f
    val orbit = { rate: Float, radius: Float, phase: Float ->
        val a = rate * t + phase
        val r = radius * (1.0f + rhythm * 0.38f)
        cx + width * r * cos(a) to cy + width * r * sin(a)
    }
    val (x3, y3) = orbit(ORBIT_RATE_MID, ORBIT_RADIUS_MID, startPhase(seed, 4))
    val (x4, y4) = orbit(ORBIT_RATE_SMALL, ORBIT_RADIUS_SMALL, startPhase(seed, 5))
    return listOf(
        FluidCopy(cx, cy, longest * SQRT_2 * (1.0f + rhythm * 0.15f), startPhase(seed, 0) + ROTATION_ORIGINAL[0] * t),
        FluidCopy(width / 2.5f, height / 2.5f, longest * 0.8f * (1.0f + rhythm * 0.28f), startPhase(seed, 1) + ROTATION_ORIGINAL[1] * t),
        FluidCopy(x3, y3, longest * 0.5f * (1.0f + rhythm * 0.45f), startPhase(seed, 2) + ROTATION_ORIGINAL[2] * t),
        FluidCopy(x4, y4, longest * 0.25f * (1.0f + rhythm * 0.65f), startPhase(seed, 3) + ROTATION_ORIGINAL[3] * t),
    )
}

private fun startPhase(seed: Int, index: Int): Float =
    (((seed ushr (index * 5)) and 0x1F) / 32f) * TWO_PI

private const val TWO_PI = 6.2831855f
private val SQRT_2 = sqrt(2f)

// Original LastWave fluid motion constants (steady, smooth, gentle drift)
private val ROTATION_ORIGINAL = floatArrayOf(0.06f, -0.12f, 0.06f, -0.08f)
private const val FLOW_SPEED_ORIGINAL = 2.5f
private const val BLUR_FRACTION_ORIGINAL = 0.18f
private const val BLUR_SIGMA_MIN_ORIGINAL = 80f
private const val BLUR_SIGMA_MAX_ORIGINAL = 360f

// Beat-synced fluid motion constants (Lyra-spec: crisp contours, punchy surge)
private val ROTATION_BEAT = floatArrayOf(0.24f, -0.32f, 0.20f, -0.18f)
private const val FLOW_SPEED_BEAT = 3.6f
private const val BLUR_FRACTION_BEAT = 0.065f
private const val BLUR_SIGMA_MIN_BEAT = 45f
private const val BLUR_SIGMA_MAX_BEAT = 85f

private const val ORBIT_RADIUS_MID = 0.25f
private const val ORBIT_RADIUS_SMALL = 0.10f
private const val ORBIT_RATE_MID = 0.045f
private const val ORBIT_RATE_SMALL = 0.09f

private const val SATURATION = 1.9f
private const val BRIGHTNESS = 0.52f
private const val LYRICS_BRIGHTNESS = 0.44f
private const val CONTRAST = 0.3f

private const val TWIST_ANGLE = 1.6f
private const val TWIST_REACH = 1.0f
private const val FLUID_RENDER_SCALE = 4

private const val TEXTURE_PX = 256
private const val TEXTURE_CACHE = 3
private const val CROSSFADE_MS = 1400
private const val APPEAR_MS = 800
private const val FRAME_INTERVAL_MS = 66L

private val textureCache = LinkedHashMap<String, Bitmap>()

private suspend fun loadTexture(context: Context, url: String, preBlur: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
    val cacheKey = if (preBlur) "$url-preblurred" else url
    synchronized(textureCache) {
        val cached = textureCache[cacheKey]
        if (cached != null) {
            if (!cached.isRecycled) {
                textureCache.remove(cacheKey)
                textureCache[cacheKey] = cached
                return@withContext cached
            } else {
                textureCache.remove(cacheKey)
            }
        }
    }

    val request = ImageRequest.Builder(context)
        .data(url)
        .size(TEXTURE_PX)
        .allowHardware(false)
        .build()
    val loader = context.imageLoader
    val result = runCatching { loader.execute(request) }.getOrNull()
    var bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return@withContext null

    if (preBlur) {
        bitmap = FastBlur.blurArtwork(bitmap, radius = 18, maxDimension = 64)
    }

    synchronized(textureCache) {
        if (!bitmap.isRecycled) {
            textureCache[cacheKey] = bitmap
            while (textureCache.size > TEXTURE_CACHE) {
                textureCache.remove(textureCache.keys.first())
            }
        }
    }
    bitmap
}

internal fun fluidBlurSigma(
    width: Float,
    height: Float,
    extraBlur: Boolean = false,
    beatSync: Boolean = false,
): Float {
    return if (beatSync) {
        (minOf(width, height) * (if (extraBlur) 0.08f else BLUR_FRACTION_BEAT)).coerceIn(
            if (extraBlur) 70f else BLUR_SIGMA_MIN_BEAT,
            if (extraBlur) 110f else BLUR_SIGMA_MAX_BEAT,
        )
    } else {
        (minOf(width, height) * (if (extraBlur) 0.50f else BLUR_FRACTION_ORIGINAL)).coerceIn(
            if (extraBlur) 280f else BLUR_SIGMA_MIN_ORIGINAL,
            if (extraBlur) 720f else BLUR_SIGMA_MAX_ORIGINAL,
        )
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AgslFluidRenderer {
    private val shader = RuntimeShader(FLUID_AGSL)
    private val paint = Paint()
    private var lastCur: Bitmap? = null
    private var lastCurShader: BitmapShader? = null
    private var lastPrev: Bitmap? = null
    private var lastPrevShader: BitmapShader? = null

    fun invalidateShaders() {
        lastCur = null
        lastCurShader = null
        lastPrev = null
        lastPrevShader = null
    }

    fun render(
        canvas: android.graphics.Canvas,
        width: Float,
        height: Float,
        seconds: Float,
        seed: Int,
        current: Bitmap,
        previous: Bitmap,
        fade: Float,
        extraBlur: Boolean = false,
        rhythm: Float = 0f,
        beatSync: Boolean = false,
    ) {
        if (current.isRecycled || previous.isRecycled) {
            invalidateShaders()
            return
        }

        val copies = fluidCopies(width, height, seconds, seed, rhythm, beatSync)
        val curShader = if (current === lastCur && lastCurShader != null) {
            lastCurShader!!
        } else {
            lastCur = current
            BitmapShader(current, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { lastCurShader = it }
        }
        val prevShader = if (previous === lastPrev && lastPrevShader != null) {
            lastPrevShader!!
        } else {
            lastPrev = previous
            BitmapShader(previous, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { lastPrevShader = it }
        }
        shader.setInputShader("curArt", curShader)
        shader.setInputShader("prevArt", prevShader)
        shader.setFloatUniform("curArtSize", current.width.toFloat(), current.height.toFloat())
        shader.setFloatUniform("prevArtSize", previous.width.toFloat(), previous.height.toFloat())
        shader.setFloatUniform("fade", fade)
        shader.setFloatUniform("sides", copies[0].side, copies[1].side, copies[2].side, copies[3].side)
        shader.setFloatUniform("angles", copies[0].angle, copies[1].angle, copies[2].angle, copies[3].angle)
        shader.setFloatUniform("centre0", copies[0].centreX, copies[0].centreY)
        shader.setFloatUniform("centre1", copies[1].centreX, copies[1].centreY)
        shader.setFloatUniform("centre2", copies[2].centreX, copies[2].centreY)
        shader.setFloatUniform("centre3", copies[3].centreX, copies[3].centreY)
        shader.setFloatUniform("twistA", width * 0.25f, height)
        shader.setFloatUniform("twistB", width * 0.75f, 0f)

        val twistRadius = (maxOf(width, height) + minOf(width, height)) / 2f * TWIST_REACH *
            (if (beatSync) (1.0f + rhythm * 0.25f) else 1.0f)
        shader.setFloatUniform("twistRadius", twistRadius)

        val twistAngle = if (beatSync) TWIST_ANGLE * (1.0f + rhythm * 0.85f) else TWIST_ANGLE
        shader.setFloatUniform("twistAngle", twistAngle)

        val sat = if (beatSync) SATURATION + rhythm * 0.45f else SATURATION
        shader.setFloatUniform("saturation", sat)

        val baseBrightness = if (extraBlur) LYRICS_BRIGHTNESS else BRIGHTNESS
        val brightness = if (beatSync) (baseBrightness + rhythm * 0.22f).coerceIn(0.42f, 0.85f) else baseBrightness
        shader.setFloatUniform("brightness", brightness)

        val contrast = if (beatSync) (CONTRAST + rhythm * 0.20f).coerceIn(0.20f, 0.55f) else CONTRAST
        shader.setFloatUniform("contrast", contrast)

        paint.shader = shader
        canvas.drawRect(0f, 0f, width, height, paint)
    }
}

/**
 * Checks whether this device can render the fluid background.
 * Requires:
 *  - Android 12+ (API >= 31) because RenderEffect and AGSL (API 33+) are unsupported on API <= 30
 *    (where Modifier.blur() is an explicit no-op in Jetpack Compose).
 *  - Hardware acceleration enabled on the current View.
 *  - Not a low-RAM device where background blurs are unsupported or disabled by the OS/GPU.
 */
@Composable
fun isFluidSupported(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return false
    }

    val context = LocalContext.current
    val view = LocalView.current

    if (view.isInEditMode) return false
    if (!view.isHardwareAccelerated) return false

    val activityManager = remember(context) {
        runCatching { context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager }.getOrNull()
    }
    if (activityManager?.isLowRamDevice == true) {
        return false
    }

    return true
}

@Composable
fun FluidArtworkBackground(
    track: PlayableTrack,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    extraBlur: Boolean = false,
    rotatingBackgroundEnabled: Boolean = true,
    lyricsRotatingBackgroundEnabled: Boolean = false,
    fluidBeatSyncEnabled: Boolean = true,
    artworkViewModel: ArtworkViewModel = com.lastwave.app.ui.common.LocalArtworkViewModel.current ?: hiltViewModel(),
    fallback: @Composable () -> Unit,
) {
    val fluidSupported = isFluidSupported()
    val isEnabled = if (extraBlur) lyricsRotatingBackgroundEnabled && rotatingBackgroundEnabled else rotatingBackgroundEnabled
    if (!isEnabled || !fluidSupported) {
        Box(modifier) {
            fallback()
        }
        return
    }

    val context = LocalContext.current
    val embeddedUrl = track.artworkUrl

    val key = remember(track.title, track.artist) { ArtworkNormalizer.cacheKey(track.title, track.artist) }
    val resolvedUrl by remember(key) {
        artworkViewModel.resolved.map { it[key] }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = artworkViewModel.resolved.value[key])

    val localCover = embeddedUrl?.startsWith("file://") == true
    val highResUrl = resolvedUrl?.takeIf { it.isNotBlank() }
    val useHighRes = highResUrl != null

    LaunchedEffect(key, localCover) {
        if (localCover) return@LaunchedEffect
        if (resolvedUrl == null) {
            artworkViewModel.resolve(track.title, track.artist)
        }
    }

    // High-resolution master artwork from metadata providers (Apple Music/Tidal/Deezer/Spotify/YTMusic)
    // takes priority for the player wallpaper background.
    // While resolving in background, embeddedUrl (upscaled) is shown as immediate placeholder.
    val artworkUrl = if (localCover) {
        embeddedUrl
    } else if (useHighRes) {
        highResUrl
    } else if (ArtworkNormalizer.isRealImage(embeddedUrl)) {
        ArtworkNormalizer.upscaleYoutubeArtwork(embeddedUrl)
    } else {
        null
    }
    
    var current by remember { mutableStateOf<Bitmap?>(null) }
    var previous by remember { mutableStateOf<Bitmap?>(null) }
    val crossfade = remember { Animatable(1f) }
    val appear = remember { Animatable(0f) }
    val seed = remember(track.videoId, track.title) {
        (track.videoId.hashCode() * 31) xor track.title.hashCode()
    }

    val agslRenderer = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { AgslFluidRenderer() }.getOrNull()
        } else {
            null
        }
    }

    LaunchedEffect(artworkUrl, agslRenderer == null) {
        val url = artworkUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val bitmap = loadTexture(context, url, preBlur = (agslRenderer == null)) ?: return@LaunchedEffect
        val showing = current
        current = bitmap
        if (showing != null && showing !== bitmap) {
            previous = showing
            crossfade.snapTo(0f)
            crossfade.animateTo(1f, tween(CROSSFADE_MS))
            previous = null
        }
    }

    LaunchedEffect(current?.isRecycled, artworkUrl) {
        if (current?.isRecycled == true) {
            current = null
            val url = artworkUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
            current = loadTexture(context, url, preBlur = (agslRenderer == null))
        }
    }

    LaunchedEffect(current != null) {
        if (current != null) appear.animateTo(1f, tween(APPEAR_MS))
    }

    var seconds by remember { mutableFloatStateOf(0f) }
    var cachedSigma by remember { mutableFloatStateOf(0f) }
    var cachedRenderEffect by remember { mutableStateOf<androidx.compose.ui.graphics.RenderEffect?>(null) }
    var renderEffectRevision by remember { mutableIntStateOf(0) }
    val isForeground = rememberIsForeground()
    val motionActive = isPlaying && isForeground
    val rhythmState by com.lastwave.app.playback.rhythm.FluidRhythmEngine.rhythm.collectAsStateWithLifecycle()
    val activeRhythm = if (fluidBeatSyncEnabled) com.lastwave.app.playback.rhythm.FluidRhythmEngine.currentRhythmValue else 0f

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME || event == Lifecycle.Event.ON_START) {
                agslRenderer?.invalidateShaders()
                cachedRenderEffect = null
                cachedSigma = 0f
                renderEffectRevision++
                com.lastwave.app.playback.rhythm.FluidRhythmEngine.onForegroundResume()
            } else if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                agslRenderer?.invalidateShaders()
                cachedRenderEffect = null
                cachedSigma = 0f
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            agslRenderer?.invalidateShaders()
            cachedRenderEffect = null
        }
    }

    LaunchedEffect(fluidBeatSyncEnabled) {
        com.lastwave.app.playback.rhythm.FluidRhythmEngine.isEnabled = fluidBeatSyncEnabled
    }

    // Runs while playing and foregrounded. Bound seconds to a periodic window to maintain 100% float precision.
    LaunchedEffect(motionActive, fluidBeatSyncEnabled) {
        if (!motionActive) {
            if (fluidBeatSyncEnabled) {
                com.lastwave.app.playback.rhythm.FluidRhythmEngine.tick(0.016f, false)
            }
            return@LaunchedEffect
        }
        if (fluidBeatSyncEnabled) {
            com.lastwave.app.playback.rhythm.FluidRhythmEngine.onForegroundResume()
        }
        var last = withFrameNanos { it }
        while (true) {
            if (!fluidBeatSyncEnabled) {
                delay(FRAME_INTERVAL_MS)
            }
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
            last = now

            if (fluidBeatSyncEnabled) {
                val r = com.lastwave.app.playback.rhythm.FluidRhythmEngine.tick(dt, isPlaying)
                // Lyra Player: speed_boost = 1.8f
                seconds = (seconds + dt * (1.0f + r * 1.8f)) % 6283.185f
            } else {
                seconds = (seconds + dt) % 6283.185f
            }
        }
    }

    Box(modifier) {
        // Fallback is ALWAYS rendered at the base layer so the player background
        // can never flash black or be unshaded during transitions, minimize/resume, or asset loads.
        fallback()

        val curBitmap = current
        if (curBitmap != null && !curBitmap.isRecycled) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = appear.value
                        @Suppress("UNUSED_VARIABLE")
                        val anim = seconds
                    }
                    .layout { measurable, constraints ->
                        val w = constraints.maxWidth
                        val h = constraints.maxHeight
                        val sw = (w / FLUID_RENDER_SCALE).coerceAtLeast(1)
                        val sh = (h / FLUID_RENDER_SCALE).coerceAtLeast(1)
                        val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(sw, sh))
                        layout(w, h) {
                            placeable.placeWithLayer(0, 0) {
                                scaleX = w.toFloat() / sw
                                scaleY = h.toFloat() / sh
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    @Suppress("UNUSED_VARIABLE")
                                    val rev = renderEffectRevision
                                    // Sigma from the FULL size, then scaled, so the blur matches the look.
                                    val sigma = fluidBlurSigma(w.toFloat(), h.toFloat(), extraBlur, beatSync = fluidBeatSyncEnabled) / FLUID_RENDER_SCALE
                                    if (sigma != cachedSigma || cachedRenderEffect == null) {
                                        cachedSigma = sigma
                                        cachedRenderEffect = runCatching {
                                            RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP).asComposeRenderEffect()
                                        }.getOrNull()
                                    }
                                    renderEffect = cachedRenderEffect
                                }
                            }
                        }
                    }
                    .drawBehind {
                        val cur = current?.takeIf { !it.isRecycled } ?: return@drawBehind
                        val prv = previous?.takeIf { !it.isRecycled } ?: cur
                        val r = if (fluidBeatSyncEnabled) com.lastwave.app.playback.rhythm.FluidRhythmEngine.currentRhythmValue else 0f
                        @Suppress("UNUSED_VARIABLE")
                        val rev = renderEffectRevision

                        if (agslRenderer != null) {
                            drawIntoCanvas { canvas ->
                                runCatching {
                                    agslRenderer.render(
                                        canvas = canvas.nativeCanvas,
                                        width = size.width,
                                        height = size.height,
                                        seconds = seconds,
                                        seed = seed,
                                        current = cur,
                                        previous = prv,
                                        fade = crossfade.value,
                                        extraBlur = extraBlur,
                                        rhythm = r,
                                        beatSync = fluidBeatSyncEnabled,
                                    )
                                }.onFailure {
                                    agslRenderer.invalidateShaders()
                                }
                            }
                        } else {
                            val copies = fluidCopies(size.width, size.height, seconds, seed, r, beatSync = fluidBeatSyncEnabled)
                            val curImg = cur.asImageBitmap()
                            for (copy in copies) {
                                rotate(
                                    degrees = Math.toDegrees(copy.angle.toDouble()).toFloat(),
                                    pivot = Offset(copy.centreX, copy.centreY)
                                ) {
                                    drawImage(
                                        image = curImg,
                                        dstOffset = IntOffset(
                                            (copy.centreX - copy.side / 2f).toInt(),
                                            (copy.centreY - copy.side / 2f).toInt()
                                        ),
                                        dstSize = IntSize(copy.side.toInt(), copy.side.toInt())
                                    )
                                }
                            }
                        }
                    }
            )
        }
    }
}
