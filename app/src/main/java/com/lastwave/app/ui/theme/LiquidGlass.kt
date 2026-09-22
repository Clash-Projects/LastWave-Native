package com.lastwave.app.ui.theme

import android.app.ActivityManager
import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.*
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.launch

/** Shared opt-in flag for Settings > Experimental > Liquid Glass. */
val LocalLiquidGlass = staticCompositionLocalOf { false }

/** Background-only source for surfaces inside the captured scrolling content. */
val LocalLiquidGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Separate source for overlays; never attach it to a parent of its consumers. */
val LocalLiquidGlassOverlayBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * Content-brightness hint in [0f, 1f] for surfaces sitting over bright artwork or backgrounds.
 * Allows liquid glass to adapt its substrate tint and specular intensity.
 */
val LocalLiquidGlassContentBrightness = compositionLocalOf { 0f }

/**
 * Typealiases for clean, unified Backdrop types across the application.
 */
typealias LayerBackdrop = LayerBackdrop
typealias Backdrop = Backdrop

/** Factory matching Kyant0 Backdrop's official API */
@Composable
fun rememberLayerBackdrop(
    onDraw: ContentDrawScope.() -> Unit = { drawContent() },
): LayerBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop(onDraw = onDraw)

/**
 * Continuous-curvature squircle (G2 superellipse) implementing [CornerBasedShape].
 * Produces authentic Apple iOS continuous curvature that eliminates the optical
 * crease where straight edges meet corners, while maintaining full compatibility
 * with [com.kyant.backdrop.effects.lens].
 */
class SquircleShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {

    constructor(radius: Dp) : this(
        CornerSize(radius),
        CornerSize(radius),
        CornerSize(radius),
        CornerSize(radius),
    )

    constructor(percent: Int = 50) : this(
        CornerSize(percent),
        CornerSize(percent),
        CornerSize(percent),
        CornerSize(percent),
    )

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape = SquircleShape(topStart, topEnd, bottomEnd, bottomStart)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return Outline.Rectangle(androidx.compose.ui.geometry.Rect.Zero)

        val isLtr = layoutDirection == LayoutDirection.Ltr
        val tl = if (isLtr) topStart else topEnd
        val tr = if (isLtr) topEnd else topStart
        val br = if (isLtr) bottomEnd else bottomStart
        val bl = if (isLtr) bottomStart else bottomEnd

        if (tl <= 0f && tr <= 0f && br <= 0f && bl <= 0f) {
            return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, w, h))
        }

        val path = createSquirclePath(w, h, tl, tr, br, bl)
        return Outline.Generic(path)
    }
}

/**
 * Builds a path with continuous G2 curvature (Apple superellipse squircle) across 4 independently sized corners.
 * Uses exact G2 cubic Bezier parameters to guarantee zero curvature discontinuity at edge junctions.
 */
fun createSquirclePath(
    w: Float,
    h: Float,
    tlRadius: Float,
    trRadius: Float,
    brRadius: Float,
    blRadius: Float,
): Path {
    val path = Path()
    val maxRadius = minOf(w, h) / 2f
    val tl = tlRadius.coerceIn(0f, maxRadius)
    val tr = trRadius.coerceIn(0f, maxRadius)
    val br = brRadius.coerceIn(0f, maxRadius)
    val bl = blRadius.coerceIn(0f, maxRadius)

    val k = 1.528665f
    var lTl = tl * k
    var lTr = tr * k
    var lBr = br * k
    var lBl = bl * k

    val maxWTop = lTl + lTr
    if (maxWTop > w && maxWTop > 0f) {
        val scale = w / maxWTop
        lTl *= scale
        lTr *= scale
    }
    val maxWBottom = lBl + lBr
    if (maxWBottom > w && maxWBottom > 0f) {
        val scale = w / maxWBottom
        lBl *= scale
        lBr *= scale
    }
    val maxHLeft = lTl + lBl
    if (maxHLeft > h && maxHLeft > 0f) {
        val scale = h / maxHLeft
        lTl *= scale
        lBl *= scale
    }
    val maxHRight = lTr + lBr
    if (maxHRight > h && maxHRight > 0f) {
        val scale = h / maxHRight
        lTr *= scale
        lBr *= scale
    }

    path.moveTo(lTl, 0f)

    path.lineTo(w - lTr, 0f)
    if (lTr > 0.001f) {
        path.cubicTo(
            w - lTr * (1f - 0.712053f), 0f,
            w - lTr * (1f - 0.566789f), lTr * 0.030183f,
            w - lTr * (1f - 0.455953f), lTr * 0.087377f,
        )
        path.cubicTo(
            w - lTr * (1f - 0.345118f), lTr * 0.144571f,
            w - lTr * (1f - 0.242846f), lTr * 0.242846f,
            w - lTr * (1f - 0.144571f), lTr * 0.345118f,
        )
        path.cubicTo(
            w - lTr * (1f - 0.087377f), lTr * 0.455953f,
            w - lTr * 0.030183f, lTr * (1f - 0.566789f),
            w, lTr * (1f - 0.712053f),
        )
        path.lineTo(w, lTr)
    } else {
        path.lineTo(w, 0f)
        path.lineTo(w, lTr)
    }

    path.lineTo(w, h - lBr)
    if (lBr > 0.001f) {
        path.cubicTo(
            w, h - lBr * (1f - 0.712053f),
            w - lBr * 0.030183f, h - lBr * (1f - 0.566789f),
            w - lBr * 0.087377f, h - lBr * (1f - 0.455953f),
        )
        path.cubicTo(
            w - lBr * 0.144571f, h - lBr * (1f - 0.345118f),
            w - lBr * 0.242846f, h - lBr * (1f - 0.242846f),
            w - lBr * 0.345118f, h - lBr * (1f - 0.144571f),
        )
        path.cubicTo(
            w - lBr * 0.455953f, h - lBr * (1f - 0.087377f),
            w - lBr * (1f - 0.566789f), h - lBr * 0.030183f,
            w - lBr * (1f - 0.712053f), h,
        )
        path.lineTo(w - lBr, h)
    } else {
        path.lineTo(w, h)
        path.lineTo(w - lBr, h)
    }

    path.lineTo(lBl, h)
    if (lBl > 0.001f) {
        path.cubicTo(
            lBl * (1f - 0.712053f), h,
            lBl * (1f - 0.566789f), h - lBl * 0.030183f,
            lBl * (1f - 0.455953f), h - lBl * 0.087377f,
        )
        path.cubicTo(
            lBl * (1f - 0.345118f), h - lBl * 0.144571f,
            lBl * (1f - 0.242846f), h - lBl * 0.242846f,
            lBl * (1f - 0.144571f), h - lBl * 0.345118f,
        )
        path.cubicTo(
            lBl * (1f - 0.087377f), h - lBl * 0.455953f,
            lBl * 0.030183f, h - lBl * (1f - 0.566789f),
            0f, h - lBl * (1f - 0.712053f),
        )
        path.lineTo(0f, h - lBl)
    } else {
        path.lineTo(0f, h)
        path.lineTo(0f, h - lBl)
    }

    path.lineTo(0f, lTl)
    if (lTl > 0.001f) {
        path.cubicTo(
            0f, lTl * (1f - 0.712053f),
            lTl * 0.030183f, lTl * (1f - 0.566789f),
            lTl * 0.087377f, lTl * (1f - 0.455953f),
        )
        path.cubicTo(
            lTl * 0.144571f, lTl * (1f - 0.345118f),
            lTl * 0.242846f, lTl * (1f - 0.242846f),
            lTl * 0.345118f, lTl * (1f - 0.144571f),
        )
        path.cubicTo(
            lTl * 0.455953f, lTl * (1f - 0.087377f),
            lTl * (1f - 0.566789f), lTl * 0.030183f,
            lTl * (1f - 0.712053f), 0f,
        )
        path.lineTo(lTl, 0f)
    } else {
        path.lineTo(0f, 0f)
        path.lineTo(lTl, 0f)
    }

    path.close()
    return path
}

/** Keeps glass inside Material's visual bounds while retaining its outer touch target. */
@Composable
fun LiquidGlassSurface(
    onClick: () -> Unit,
    glassModifier: Modifier,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        color = Color.Transparent,
        contentColor = contentColor,
        interactionSource = interactionSource,
        enabled = enabled,
    ) {
        Surface(
            modifier = glassModifier,
            shape = shape,
            color = color,
            contentColor = contentColor,
            tonalElevation = tonalElevation,
            shadowElevation = shadowElevation,
            border = border,
            content = content,
        )
    }
}

/**
 * High-performance background blur using Kyant0 Backdrop sibling capture.
 * Never blurs foreground lyrics, icons, or controls.
 */
@Composable
fun BackdropBlur(
    radius: Dp,
    modifier: Modifier = Modifier,
    veil: Color = Color.Unspecified,
    veilAlpha: Float = 0.74f,
    content: @Composable BoxScope.() -> Unit,
) {
    val defaultVeil = MaterialTheme.colorScheme.surface
    val resolvedVeil = if (veil == Color.Unspecified) defaultVeil else veil
    val view = LocalView.current
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        view.isHardwareAccelerated && !view.isInEditMode
    val backdrop = if (supported) rememberLayerBackdrop() else null

    Box(modifier) {
        Box(
            Modifier
                .matchParentSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
            content = content,
        )
        if (backdrop != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RectangleShape },
                        effects = {
                            if (size.isSpecified && size.width.isFinite() && size.height.isFinite() &&
                                size.width > 0f && size.height > 0f
                            ) {
                                blur(radius.toPx())
                            }
                        },
                        highlight = null,
                        shadow = null,
                        onDrawSurface = {
                            drawRect(resolvedVeil.copy(alpha = veilAlpha))
                        },
                    ),
            )
        } else {
            Box(Modifier.matchParentSize().background(resolvedVeil.copy(alpha = veilAlpha)))
        }
    }
}

/**
 * Optical depth hierarchy presets.
 * Different surfaces occupy distinct physical depths and receive tailored optical treatments.
 */
enum class LiquidGlassPreset(
    val blurDp: Float,
    val lensHeightDp: Float,
    val lensAmountDp: Float,
    val depthEffect: Boolean,
    val shadowRadiusDp: Float,
    val hasRimHighlight: Boolean,
    val progressiveBlur: Boolean = false,
    val pressScale: Float = 0.96f,
    val interactiveSquish: Boolean = true,
) {
    /** Flagship bottom navigation dock: deep material, strong ambient presence, progressive blur over feed. */
    BottomNavigation(
        blurDp = 4f,
        lensHeightDp = 16f,
        lensAmountDp = 32f,
        depthEffect = true,
        shadowRadiusDp = 12f,
        hasRimHighlight = true,
        progressiveBlur = true,
        pressScale = 0.96f,
        interactiveSquish = true,
    ),

    /** Floating mini-player bar: responsive optical slab with progressive blur and gentle depth. */
    MiniPlayer(
        blurDp = 8f,
        lensHeightDp = 12f,
        lensAmountDp = 16f,
        depthEffect = true,
        shadowRadiusDp = 10f,
        hasRimHighlight = false,
        progressiveBlur = true,
        pressScale = 0.98f,
        interactiveSquish = true,
    ),

    /** Full player playback controls (play/pause, skip): high tactile presence. */
    PlayerControls(
        blurDp = 6f,
        lensHeightDp = 10f,
        lensAmountDp = 12f,
        depthEffect = true,
        shadowRadiusDp = 6f,
        hasRimHighlight = true,
        progressiveBlur = false,
        pressScale = 0.92f,
        interactiveSquish = true,
    ),

    /** Floating action pills and icon buttons: compact floating pieces of optical glass with spring squish. */
    FloatingControls(
        blurDp = 4f,
        lensHeightDp = 16f,
        lensAmountDp = 32f,
        depthEffect = true,
        shadowRadiusDp = 8f,
        hasRimHighlight = true,
        progressiveBlur = false,
        pressScale = 0.92f,
        interactiveSquish = true,
    ),

    /** Modal sheets & drawers: large surface with wide soft blur and controlled thickness. */
    ModalSheet(
        blurDp = 16f,
        lensHeightDp = 16f,
        lensAmountDp = 16f,
        depthEffect = true,
        shadowRadiusDp = 16f,
        hasRimHighlight = false,
        progressiveBlur = true,
        pressScale = 1.0f,
        interactiveSquish = false,
    ),

    /** Context menus: floating card with clear text separation. */
    ContextMenu(
        blurDp = 14f,
        lensHeightDp = 12f,
        lensAmountDp = 12f,
        depthEffect = true,
        shadowRadiusDp = 10f,
        hasRimHighlight = false,
        progressiveBlur = false,
        pressScale = 0.98f,
        interactiveSquish = false,
    ),

    /** Dialogs and header overlays. */
    Overlay(
        blurDp = 12f,
        lensHeightDp = 10f,
        lensAmountDp = 12f,
        depthEffect = true,
        shadowRadiusDp = 8f,
        hasRimHighlight = false,
        progressiveBlur = false,
        pressScale = 1.0f,
        interactiveSquish = false,
    ),

    /** Compact cards and playlist items. */
    Card(
        blurDp = 8f,
        lensHeightDp = 8f,
        lensAmountDp = 8f,
        depthEffect = false,
        shadowRadiusDp = 4f,
        hasRimHighlight = false,
        progressiveBlur = false,
        pressScale = 0.98f,
        interactiveSquish = true,
    ),
}

/**
 * Robust device capability gate ensuring zero native or RenderThread crashes:
 * - Preview mode -> native fallback
 * - API < 31 -> native fallback
 * - Software rendering -> native fallback
 * - Low RAM device -> native fallback
 */
@Composable
fun isDeviceGlassCapable(): Boolean {
    val view = LocalView.current
    if (view.isInEditMode) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    if (!view.isHardwareAccelerated) return false
    val context = LocalContext.current
    val am = remember(context) {
        runCatching { context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager }.getOrNull()
    }
    if (am?.isLowRamDevice == true) return false
    return true
}

@Composable
fun isLiquidGlassBackdropSupported(): Boolean =
    LocalLiquidGlass.current && isDeviceGlassCapable()

@Composable
fun Modifier.liquidGlassSource(
    backdrop: LayerBackdrop?,
): Modifier {
    if (backdrop == null) return this
    if (!isLiquidGlassBackdropSupported()) return this
    return this.layerBackdrop(backdrop)
}

/**
 * Adaptive container tint for liquid glass surfaces.
 * In glass mode, this acts as an optical substrate with low opacity (allowing
 * the refracted artwork/background through) while ensuring sufficient contrast for text.
 */
@Composable
fun liquidGlassContainerColor(
    color: Color,
    enabled: Boolean = LocalLiquidGlass.current,
    backdrop: Backdrop? = LocalLiquidGlassBackdrop.current,
): Color = if (enabled && isLiquidGlassBackdropSupported() && backdrop != null) {
    Color.Transparent
} else if (enabled) {
    color.copy(alpha = minOf(color.alpha, 0.74f))
} else color

@Composable
fun isLiquidGlassEnabled(): Boolean = LocalLiquidGlass.current

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object ProgressiveBlurShaderHolder {
    private const val SHADER_SRC = """
        uniform shader content;
        uniform float2 size;
        layout(color) uniform half4 tint;
        uniform float tintIntensity;

        half4 main(float2 coord) {
            float blurAlpha = smoothstep(size.y, size.y * 0.5, coord.y);
            float tintAlpha = smoothstep(size.y, size.y * 0.5, coord.y);
            return mix(content.eval(coord) * blurAlpha, tint * tintAlpha, tintIntensity);
        }
    """
    private var cachedShader: RuntimeShader? = null

    fun get(): RuntimeShader {
        return cachedShader ?: RuntimeShader(SHADER_SRC.trimIndent()).also { cachedShader = it }
    }
}

/**
 * Central Liquid Glass Chrome Modifier built on Kyant0 Backdrop:
 * - Samples underlying sibling content without recursive self-capture.
 * - Applies saturation boost (colorControls/vibrancy) + Gaussian blur + progressive edge blur + SDF lens refraction.
 * - Dynamic tactile spring compression (scaleX/scaleY in layerBlock without misplaced backdrop sampling).
 * - Ambient idle specular glint drift that smoothly hands off to touch-driven shine and back on release.
 * - Dynamic shadow elevation lerp and haptic ticks on press/release.
 * - Content-aware adaptive substrate tint.
 * - Never paints fake white strokes, neon edges, or cartoon borders.
 */
@Composable
fun Modifier.liquidGlassChrome(
    shape: Shape,
    enabled: Boolean = true,
    preset: LiquidGlassPreset = LiquidGlassPreset.Card,
    backdrop: Backdrop? = LocalLiquidGlassBackdrop.current,
    interactionSource: MutableInteractionSource? = null,
    exportedBackdrop: LayerBackdrop? = null,
    ambientMotion: Boolean = true,
    contentBrightness: Float = LocalLiquidGlassContentBrightness.current,
    onPointerPosition: ((Offset?) -> Unit)? = null,
): Modifier {
    if (!enabled) return this
    if (!isLiquidGlassBackdropSupported() || backdrop == null) {
        return this.background(
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
            shape = shape,
        ).clip(shape)
    }

    val isDark = LocalIsDarkTheme.current
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val lensSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shape is CornerBasedShape

    // 1. Press and Touch Tracking
    val pressProgress = remember { Animatable(0f) }
    val touchPosition = remember { mutableStateOf<Offset?>(null) }
    val lastTouchPos = remember { mutableStateOf<Offset?>(null) }
    val touchBlend = remember { Animatable(0f) }

    // 2. Ambient Idle Motion (gently drifts the idle highlight across the glass)
    val infiniteTransition = rememberInfiniteTransition(label = "ambientGlassMotion")
    val ambientDrift by if (ambientMotion) {
        infiniteTransition.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "ambientDrift",
        )
    } else remember { mutableStateOf(0f) }

    val ambientAlphaScale by if (ambientMotion) {
        infiniteTransition.animateFloat(
            initialValue = 0.9f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "ambientAlphaScale",
        )
    } else remember { mutableStateOf(1f) }

    // 3. Dynamic Highlight & Shadow lerp on press
    val highlightAlpha = lerp(
        if (isDark) 0.35f else 0.25f,
        0.55f,
        pressProgress.value,
    )
    val highlight = remember(highlightAlpha, lensSupported, preset.hasRimHighlight) {
        if (!preset.hasRimHighlight) null
        else Highlight(
            alpha = highlightAlpha,
            style = if (lensSupported) HighlightStyle.Default else HighlightStyle.Plain,
        )
    }

    val shadow = remember(isDark, preset.shadowRadiusDp, pressProgress.value) {
        if (preset.shadowRadiusDp <= 0f) null
        else {
            val baseRadius = preset.shadowRadiusDp
            val radiusDp = lerp(baseRadius, maxOf(2f, baseRadius * 0.35f), pressProgress.value)
            val shadowAlpha = lerp(if (isDark) 0.35f else 0.15f, if (isDark) 0.50f else 0.28f, pressProgress.value)
            Shadow(
                radius = radiusDp.dp,
                color = Color.Black.copy(alpha = shadowAlpha),
            )
        }
    }

    // 4. Content-aware adaptive surface tint
    val surfaceTint = remember(isDark, contentBrightness) {
        val baseAlpha = if (isDark) {
            lerp(0.12f, 0.22f, contentBrightness)
        } else {
            lerp(0.42f, 0.28f, contentBrightness)
        }
        if (contentBrightness > 0.5f && isDark) {
            Color.Black.copy(alpha = baseAlpha)
        } else {
            Color.White.copy(alpha = baseAlpha)
        }
    }

    // 5. Draw backdrop modifier
    val glassModifier = Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            if (!size.isSpecified || !size.width.isFinite() || !size.height.isFinite() ||
                size.width <= 0f || size.height <= 0f
            ) return@drawBackdrop

            // Step 1: Color vibrancy (API 33+) or color controls saturation (API 31+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrancy()
            } else {
                colorControls(saturation = 1.10f)
            }

            // Step 2: Background blur
            blur(preset.blurDp.dp.toPx())

            // Step 3: Progressive blur (if enabled on preset, e.g. over scrolling feed/sheets)
            if (preset.progressiveBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val progressiveShader = ProgressiveBlurShaderHolder.get()
                effect(
                    RenderEffect.createRuntimeShaderEffect(
                        progressiveShader.apply {
                            setFloatUniform("size", size.width, size.height)
                            setColorUniform("tint", surfaceTint.toArgb())
                            setFloatUniform("tintIntensity", if (isDark) 0.15f else 0.35f)
                        },
                        "content",
                    ).asComposeRenderEffect(),
                )
            }

            // Step 4: Continuous-curvature Squircle lens refraction (API 33+ with CornerBasedShape)
            if (lensSupported) {
                val maxRadius = if (shape is CornerBasedShape) {
                    val raw = minOf(
                        shape.topStart.toPx(size, density),
                        shape.topEnd.toPx(size, density),
                        shape.bottomStart.toPx(size, density),
                        shape.bottomEnd.toPx(size, density),
                    ).coerceAtLeast(0f)
                    if (raw > 0f) raw else (size.minDimension / 2f)
                } else size.minDimension / 2f

                val lensH = preset.lensHeightDp.dp.toPx().coerceIn(0f, maxRadius)
                val lensA = preset.lensAmountDp.dp.toPx().coerceIn(0f, size.minDimension)

                if (lensH > 0f && lensA > 0f) {
                    lens(
                        refractionHeight = lensH,
                        refractionAmount = lensA,
                        depthEffect = preset.depthEffect,
                        chromaticAberration = false,
                    )
                }
            }
        },
        layerBlock = if (preset.interactiveSquish) {
            {
                val progress = pressProgress.value
                val scale = lerp(1f, preset.pressScale, progress)
                scaleX = scale
                scaleY = scale
            }
        } else null,
        highlight = { highlight },
        shadow = { shadow },
        exportedBackdrop = exportedBackdrop,
        onDrawSurface = {
            // Draw substrate tint
            drawRect(surfaceTint)

            // Dynamic directional specular shine across the squircle (seamless ambient + touch)
            val ambientCenter = Offset(
                size.width * (0.5f + ambientDrift * 0.08f),
                0f,
            )
            val touchPos = touchPosition.value ?: lastTouchPos.value
            val activeCenter = if (touchPos != null && touchBlend.value > 0.001f) {
                val blend = touchBlend.value
                Offset(
                    lerp(ambientCenter.x, touchPos.x, blend),
                    lerp(ambientCenter.y, touchPos.y, blend),
                )
            } else {
                ambientCenter
            }

            val shineRadius = maxOf(size.width, size.height) * 0.55f
            val baseGlintAlpha = if (isDark) 0.24f else 0.38f
            val glintAlpha = lerp(
                baseGlintAlpha * ambientAlphaScale,
                0.55f,
                pressProgress.value,
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = glintAlpha),
                        Color.Transparent,
                    ),
                    center = activeCenter,
                    radius = shineRadius,
                ),
                radius = shineRadius,
                center = activeCenter,
            )
        },
    )

    // 6. Pointer tracking & haptic feedback directly on the surface's local coordinate space
    val pointerModifier = if (preset.interactiveSquish || onPointerPosition != null) {
        Modifier.pointerInput(scope) {
            val springSpec = spring<Float>(0.6f, 400f, 0.001f)
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                touchPosition.value = down.position
                lastTouchPos.value = down.position
                onPointerPosition?.invoke(down.position)

                // Haptic feedback tap on press down
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                scope.launch { pressProgress.animateTo(1f, springSpec) }
                scope.launch { touchBlend.animateTo(1f, springSpec) }

                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) {
                        touchPosition.value = null
                        onPointerPosition?.invoke(null)

                        // Light haptic tick on release
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

                        scope.launch { pressProgress.animateTo(0f, springSpec) }
                        scope.launch {
                            touchBlend.animateTo(0f, springSpec)
                            lastTouchPos.value = null
                        }
                        break
                    }
                    touchPosition.value = change.position
                    lastTouchPos.value = change.position
                    onPointerPosition?.invoke(change.position)
                }
            }
        }
    } else Modifier

    return this
        .clip(shape)
        .then(pointerModifier)
        .then(glassModifier)
}

/**
 * Convenience container wrapping arbitrary content in a liquid-glass surface.
 * Consumers must be siblings of the composable carrying the layerBackdrop source.
 */
@Composable
fun LiquidGlassContainer(
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    preset: LiquidGlassPreset = LiquidGlassPreset.Card,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.liquidGlassChrome(
            shape = shape,
            enabled = true,
            preset = preset,
            backdrop = backdrop,
        ),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/** Floating action pill hosting icon buttons in a liquid glass shell. */
@Composable
fun LiquidGlassActionPill(
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    preset: LiquidGlassPreset = LiquidGlassPreset.FloatingControls,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .liquidGlassChrome(
                shape = shape,
                enabled = true,
                preset = preset,
                backdrop = backdrop,
            ),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Circular / squircle liquid glass button for action icons and playback controls.
 * Features unified tactile spring compression and touch glint via [liquidGlassChrome].
 */
@Composable
fun LiquidGlassIconButton(
    backdrop: Backdrop?,
    painter: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.size(48.dp),
    shape: Shape = SquircleShape(percent = 50),
    tint: Color = MaterialTheme.colorScheme.onSurface,
    contentDescription: String? = null,
) {
    val enabled = isLiquidGlassEnabled()
    Box(
        modifier = modifier
            .liquidGlassChrome(
                shape = shape,
                enabled = enabled,
                preset = LiquidGlassPreset.FloatingControls,
                backdrop = backdrop,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}
