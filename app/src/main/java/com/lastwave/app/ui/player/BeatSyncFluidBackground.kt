package com.lastwave.app.ui.player

// Beat-synced fluid background, used only while Settings > beat sync is on.
// A port of Lyra Player's flow renderer: its fragment shader (from
// liblyra_runtime.so), its cover.frag tone pass and its shipped ui.toml
// defaults (flow.* / rhythm.*). The cover fills the screen, a rotating
// container carries its top-left and bottom-right quarters, and the whole
// thing is drawn on a ~100 dp canvas and blurred until only colour is left.
// The bass envelope zooms, pushes, speeds up and brightens it on every hit.

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.artwork.ArtworkNormalizer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.rhythm.FluidRhythmEngine
import com.lastwave.app.ui.common.ArtworkViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Lyra ui.toml defaults (saturation, dim and blur matched to its screen).
private const val FLOW_SATURATION = 2.0f
private const val FLOW_DIM = 0.70f
private const val FLOW_SIGMA_PX = 39.7f // flow.sigma 31.77 px on the flow.canvas_dp 100 canvas, x1.25
private const val CANVAS_DP = 100
private const val ROTATION_DEN = 0.034 // flow.*_den: *_num / den degrees per second
private const val TOP_ROTATION = 1.2
private const val BOTTOM_ROTATION = 0.67
private const val CONTAINER_ROTATION = -0.6
private const val RHYTHM_STRENGTH = 0.85f
private const val SPEED_BOOST = 1.8f
private const val DILATION = 0.16f
private const val DISPLACEMENT = 0.065f
private const val LOBE_SCALE = 0.5f
private const val RHYTHM_BRIGHTNESS = 0.14f
private const val RHYTHM_CONTRAST = 0.32f
private const val RHYTHM_SATURATION = 0.28f
private const val RHYTHM_HIGHLIGHTS = 0.12f
private const val COVER_MORPH_MS = 1400
private const val APPEAR_MS = 800
private const val LYRICS_DIM = 0.85f
// Set by comparing screen captures with Lyra on the same song: its colours sit
// calmer than its config reads here, and its shapes move further on a hit.
private const val SHAPE_GAIN = 1.5f
private const val COLOR_GAIN = 0.5f

private val FLOW_AGSL = """
uniform shader curArt;
uniform shader prevArt;
uniform float2 curSize;
uniform float2 prevSize;
uniform float2 viewSize;
uniform float3 angles;
uniform float3 motion;
uniform float3 lobes;
uniform float morph;
uniform float dim;
uniform float2 tone;
uniform float2 light;

float2 rotateAt(float2 p, float2 c, float a) {
    float co = cos(a), s = sin(a);
    p -= c;
    return float2(co * p.x + s * p.y, -s * p.x + co * p.y) + c;
}
// Square crop of the whole cover (q 0), its top-left (1) or bottom-right (2) quarter.
float2 crop(float2 dims, float2 p, int q) {
    float2 size = q > 0 ? max(float2(1.0), floor(dims * 0.5)) : dims;
    float2 offset = q == 2 ? floor(dims * 0.5) : float2(0.0);
    p = (p - 0.5) * (min(size.x, size.y) / size) + 0.5;
    return clamp(offset + p * size, offset + 0.5, offset + size - 0.5);
}
half3 source(float2 p, int q) {
    half3 c = curArt.eval(crop(curSize, p, q)).rgb;
    if (morph < 0.999) c = mix(prevArt.eval(crop(prevSize, p, q)).rgb, c, half(morph));
    half l = dot(c, half3(0.213, 0.715, 0.072));
    return clamp(half3(l) + (c - half3(l)) * half(${FLOW_SATURATION}), 0.0, 1.0);
}
bool inside(float2 p) { return all(greaterThanEqual(p, float2(0.0))) && all(lessThan(p, float2(1.0))); }

half4 main(float2 coord) {
    float side = max(viewSize.x, viewSize.y);
    float2 uv = (coord - (viewSize - side) * 0.5) / side;
    float2 field = (uv - 0.5) / (1.0 + motion.x) + 0.5 + motion.yz;
    half3 c = source(field, 0);
    float2 p = rotateAt(field, float2(0.5), angles.z);
    if (inside(p)) {
        float2 top = rotateAt(p - motion.yz * 0.6, float2(0.25), angles.x) / 0.5;
        top = (top - 0.5) / (1.0 + lobes.x + lobes.z) + 0.5;
        if (inside(top)) c = source(top, 1);
        float2 bottom = (rotateAt(p + motion.yz * 0.6, float2(0.75), angles.y) - 0.5) / 0.5;
        bottom = (bottom - 0.5) / (1.0 + lobes.y - lobes.z) + 0.5;
        if (inside(bottom)) c = source(bottom, 2);
    }
    // Lyra cover.frag flow pass: contrast, saturation, dim, then a luma-weighted lift.
    if (tone.x > 0.0) c += half(tone.x) * c * (1.0 - c) * (2.0 * c - 1.0);
    if (tone.y > 0.0) {
        half l = dot(c, half3(0.213, 0.715, 0.072));
        c = clamp(half3(l) + (c - half3(l)) * half(1.0 + tone.y), 0.0, 1.0);
    }
    c *= half(dim);
    half l = dot(c, half3(0.213, 0.715, 0.072));
    c += c * (1.0 - c) * (half(light.x) + half(light.y) * smoothstep(0.02, 0.65, l));
    return half4(c, 1.0);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class FlowRenderer {
    private val shader = RuntimeShader(FLOW_AGSL)
    private val paint = Paint().apply { shader = this@FlowRenderer.shader }
    private val shaders = HashMap<Bitmap, BitmapShader>(2)

    private fun shaderFor(bitmap: Bitmap): BitmapShader = shaders.getOrPut(bitmap) {
        BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    fun render(canvas: android.graphics.Canvas, w: Float, h: Float, clock: Double, seed: Int, cur: Bitmap, prev: Bitmap, morph: Float, rhythm: Float, dim: Float) {
        shaders.keys.retainAll { it === cur || it === prev }
        shader.setInputShader("curArt", shaderFor(cur))
        shader.setInputShader("prevArt", shaderFor(prev))
        shader.setFloatUniform("curSize", cur.width.toFloat(), cur.height.toFloat())
        shader.setFloatUniform("prevSize", prev.width.toFloat(), prev.height.toFloat())
        shader.setFloatUniform("viewSize", w, h)
        val top = turn(clock, TOP_ROTATION, seed, 0)
        val bottom = turn(clock, BOTTOM_ROTATION, seed, 1)
        val container = turn(clock, CONTAINER_ROTATION, seed, 2)
        shader.setFloatUniform("angles", top, bottom, container)
        val r = rhythm * RHYTHM_STRENGTH * SHAPE_GAIN
        val push = DISPLACEMENT * r
        shader.setFloatUniform("motion", DILATION * r, push * cos(container), push * sin(container))
        // Lobes swell on each hit; size exchange grows one while the other shrinks.
        val lobe = LOBE_SCALE * r
        val glow = rhythm * RHYTHM_STRENGTH * COLOR_GAIN
        shader.setFloatUniform("lobes", lobe, lobe, lobe * sin(top - bottom))
        shader.setFloatUniform("morph", morph)
        shader.setFloatUniform("dim", dim)
        shader.setFloatUniform("tone", RHYTHM_CONTRAST * glow, RHYTHM_SATURATION * glow)
        shader.setFloatUniform("light", RHYTHM_BRIGHTNESS * glow, RHYTHM_HIGHLIGHTS * glow)
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    // Wrapped in double so hours of playback never lose float precision.
    private fun turn(clock: Double, num: Double, seed: Int, index: Int): Float {
        val phase = ((seed ushr (index * 5)) and 0x1F) / 32.0
        return ((clock * num / ROTATION_DEN / 360.0 + phase) % 1.0 * 2 * PI).toFloat()
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
internal fun BeatSyncFluidArtworkBackground(
    track: PlayableTrack,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    extraBlur: Boolean = false,
    artworkViewModel: ArtworkViewModel = com.lastwave.app.ui.common.LocalArtworkViewModel.current ?: hiltViewModel(),
    fallback: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val embeddedUrl = track.artworkUrl
    val key = remember(track.title, track.artist) { ArtworkNormalizer.cacheKey(track.title, track.artist) }
    val resolvedUrl by remember(key) {
        artworkViewModel.resolved.map { it[key] }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = artworkViewModel.resolved.value[key])
    val localCover = embeddedUrl?.startsWith("file://") == true
    LaunchedEffect(key, localCover) {
        if (!localCover && resolvedUrl == null) artworkViewModel.resolve(track.title, track.artist)
    }
    val artworkUrl = when {
        localCover -> embeddedUrl
        !resolvedUrl.isNullOrBlank() -> resolvedUrl
        ArtworkNormalizer.isRealImage(embeddedUrl) -> ArtworkNormalizer.upscaleYoutubeArtwork(embeddedUrl)
        else -> null
    }

    var current by remember { mutableStateOf<Bitmap?>(null) }
    var previous by remember { mutableStateOf<Bitmap?>(null) }
    val morph = remember { Animatable(1f) }
    val appear = remember { Animatable(0f) }
    val seed = remember(track.videoId, track.title) { (track.videoId.hashCode() * 31) xor track.title.hashCode() }
    val renderer = remember { runCatching { FlowRenderer() }.getOrNull() }

    LaunchedEffect(artworkUrl) {
        val url = artworkUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val bitmap = loadTexture(context, url) ?: return@LaunchedEffect
        val showing = current?.takeIf { !it.isRecycled }
        current = bitmap
        if (showing != null && showing !== bitmap) {
            previous = showing
            morph.snapTo(0f)
            morph.animateTo(1f, tween(COVER_MORPH_MS))
            previous = null
        }
    }
    LaunchedEffect(current != null) {
        if (current != null) appear.animateTo(1f, tween(APPEAR_MS))
    }

    // Frame clock: Lyra's speed boost runs the rotation faster while the bass hits.
    var clockNanos by remember { mutableLongStateOf(0L) }
    var clock by remember { mutableDoubleStateOf(0.0) }
    val motionActive = isPlaying && rememberIsForeground()
    LaunchedEffect(motionActive) {
        if (!motionActive) {
            FluidRhythmEngine.tick(0.016f, false)
            return@LaunchedEffect
        }
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1e9f).coerceIn(0.001f, 0.1f)
            last = now
            val r = FluidRhythmEngine.tick(dt, true)
            clock += dt * (1f + SPEED_BOOST * r * RHYTHM_STRENGTH * SHAPE_GAIN)
            clockNanos = now
        }
    }

    Box(modifier) {
        // Always underneath, so a load or resume never flashes black.
        fallback()
        if (renderer == null || current == null) return@Box
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = appear.value }
                .layout { measurable, constraints ->
                    val w = constraints.maxWidth
                    val h = constraints.maxHeight
                    // Lyra draws the flow on a ~100 dp canvas; the blur hides the low resolution.
                    val scale = (maxOf(w, h) / CANVAS_DP.dp.toPx()).coerceAtLeast(1f)
                    val sw = (w / scale).toInt().coerceAtLeast(1)
                    val sh = (h / scale).toInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(sw, sh))
                    layout(w, h) {
                        placeable.placeWithLayer(0, 0) {
                            scaleX = w.toFloat() / sw
                            scaleY = h.toFloat() / sh
                            transformOrigin = TransformOrigin(0f, 0f)
                            renderEffect = RenderEffect.createBlurEffect(FLOW_SIGMA_PX, FLOW_SIGMA_PX, Shader.TileMode.CLAMP)
                                .asComposeRenderEffect()
                        }
                    }
                }
                .drawBehind {
                    @Suppress("UNUSED_VARIABLE") val frame = clockNanos
                    val cur = current?.takeIf { !it.isRecycled } ?: return@drawBehind
                    val prv = previous?.takeIf { !it.isRecycled } ?: cur
                    drawIntoCanvas { canvas ->
                        renderer.render(
                            canvas.nativeCanvas, size.width, size.height, clock, seed, cur, prv, morph.value,
                            rhythm = if (motionActive) FluidRhythmEngine.currentRhythmValue else 0f,
                            dim = if (extraBlur) FLOW_DIM * LYRICS_DIM else FLOW_DIM,
                        )
                    }
                },
        )
    }
}
