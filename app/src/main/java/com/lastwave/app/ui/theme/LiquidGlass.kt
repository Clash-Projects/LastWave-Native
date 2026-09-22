package com.lastwave.app.ui.theme

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
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
 * Typealiases for clean, unified Backdrop types across the application.
 */
typealias LayerBackdrop = LayerBackdrop
typealias Backdrop = Backdrop

/** Factory matching Kyant0 Backdrop's official API */
@Composable
fun rememberLayerBackdrop(): LayerBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()

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
) {
    /** Flagship bottom navigation dock: deep material, strong ambient presence, zero cartoon border. */
    BottomNavigation(
        blurDp = 10f,
        lensHeightDp = 14f,
        lensAmountDp = 18f,
        depthEffect = true,
        shadowRadiusDp = 12f,
        hasRimHighlight = false,
    ),

    /** Floating mini-player bar: responsive optical slab with gentle depth. */
    MiniPlayer(
        blurDp = 8f,
        lensHeightDp = 12f,
        lensAmountDp = 16f,
        depthEffect = true,
        shadowRadiusDp = 10f,
        hasRimHighlight = false,
    ),

    /** Full player playback controls (play/pause, skip): high tactile presence. */
    PlayerControls(
        blurDp = 6f,
        lensHeightDp = 10f,
        lensAmountDp = 12f,
        depthEffect = true,
        shadowRadiusDp = 6f,
        hasRimHighlight = true,
    ),

    /** Floating action pills and icon buttons: compact floating pieces of optical glass. */
    FloatingControls(
        blurDp = 6f,
        lensHeightDp = 8f,
        lensAmountDp = 10f,
        depthEffect = true,
        shadowRadiusDp = 6f,
        hasRimHighlight = true,
    ),

    /** Modal sheets & drawers: large surface with wide soft blur and controlled thickness. */
    ModalSheet(
        blurDp = 16f,
        lensHeightDp = 16f,
        lensAmountDp = 16f,
        depthEffect = true,
        shadowRadiusDp = 16f,
        hasRimHighlight = false,
    ),

    /** Context menus: floating card with clear text separation. */
    ContextMenu(
        blurDp = 14f,
        lensHeightDp = 12f,
        lensAmountDp = 12f,
        depthEffect = true,
        shadowRadiusDp = 10f,
        hasRimHighlight = false,
    ),

    /** Dialogs and header overlays. */
    Overlay(
        blurDp = 12f,
        lensHeightDp = 10f,
        lensAmountDp = 12f,
        depthEffect = true,
        shadowRadiusDp = 8f,
        hasRimHighlight = false,
    ),

    /** Compact cards and playlist items. */
    Card(
        blurDp = 8f,
        lensHeightDp = 8f,
        lensAmountDp = 8f,
        depthEffect = false,
        shadowRadiusDp = 4f,
        hasRimHighlight = false,
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
    val isDark = LocalIsDarkTheme.current
    val cap = if (isDark) 0.16f else 0.22f
    color.copy(alpha = minOf(color.alpha, cap))
} else if (enabled) {
    color.copy(alpha = minOf(color.alpha, 0.74f))
} else color

@Composable
fun isLiquidGlassEnabled(): Boolean = LocalLiquidGlass.current

/**
 * Central Liquid Glass Chrome Modifier built on Kyant0 Backdrop:
 * - Samples underlying sibling content without recursive self-capture.
 * - Applies saturation boost (colorControls/vibrancy) + Gaussian blur + SDF lens refraction.
 * - Never paints fake white strokes, neon edges, or cartoon borders.
 * - Edge definition comes from refraction contrast, subtle rim highlight, and soft depth shadow.
 */
@Composable
fun Modifier.liquidGlassChrome(
    shape: Shape,
    enabled: Boolean,
    preset: LiquidGlassPreset = LiquidGlassPreset.Card,
    backdrop: Backdrop? = LocalLiquidGlassBackdrop.current,
    interactionSource: MutableInteractionSource? = null,
    exportedBackdrop: LayerBackdrop? = null,
): Modifier {
    if (!enabled) return this
    if (!isLiquidGlassBackdropSupported() || backdrop == null) {
        return this.background(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
            shape = shape,
        ).clip(shape)
    }

    val isDark = LocalIsDarkTheme.current
    val density = LocalDensity.current
    val lensSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shape is CornerBasedShape

    val highlight = remember(isDark, lensSupported, preset.hasRimHighlight) {
        if (!preset.hasRimHighlight) null
        else Highlight(
            alpha = if (isDark) 0.26f else 0.18f,
            style = if (lensSupported) HighlightStyle.Default else HighlightStyle.Plain,
        )
    }

    val shadow = remember(isDark, preset.shadowRadiusDp) {
        if (preset.shadowRadiusDp <= 0f) null
        else Shadow(
            radius = preset.shadowRadiusDp.dp,
            color = Color.Black.copy(alpha = if (isDark) 0.28f else 0.12f),
        )
    }

    val surfaceTint = remember(isDark) {
        if (isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.25f)
    }

    val glassModifier = remember(
        backdrop,
        shape,
        preset,
        isDark,
        surfaceTint,
        highlight,
        shadow,
        lensSupported,
        exportedBackdrop,
    ) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                if (!size.isSpecified || !size.width.isFinite() || !size.height.isFinite() ||
                    size.width <= 0f || size.height <= 0f
                ) return@drawBackdrop

                // Step 1: Color controls for authentic vibrancy
                colorControls(saturation = if (isDark) 1.10f else 1.05f)

                // Step 2: Background blur
                blur(preset.blurDp.dp.toPx())

                // Step 3: SDF Lens refraction (API 33+ with CornerBasedShape)
                if (lensSupported) {
                    val maxRadius = if (shape is CornerBasedShape) {
                        minOf(
                            shape.topStart.toPx(size, density),
                            shape.topEnd.toPx(size, density),
                            shape.bottomStart.toPx(size, density),
                            shape.bottomEnd.toPx(size, density),
                        ).coerceAtLeast(0f)
                    } else size.minDimension / 2f

                    val lensH = minOf(preset.lensHeightDp.dp.toPx(), maxRadius, size.minDimension / 2f)
                    val lensA = minOf(preset.lensAmountDp.dp.toPx(), size.minDimension / 2f)

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
            highlight = { highlight },
            shadow = { shadow },
            exportedBackdrop = exportedBackdrop,
            onDrawSurface = {
                drawRect(surfaceTint)
            },
        )
    }

    return this.clip(shape).then(glassModifier)
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
 * Circular liquid glass button for action icons and playback controls.
 * Features tactile spring compression without misplacing the backdrop sampling.
 */
@Composable
fun LiquidGlassIconButton(
    backdrop: Backdrop?,
    painter: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.size(48.dp),
    shape: Shape = CircleShape,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    contentDescription: String? = null,
) {
    val enabled = isLiquidGlassEnabled()
    val capable = isLiquidGlassBackdropSupported()
    val scope = rememberCoroutineScope()
    val pressProgress = remember { Animatable(0f) }

    val isDark = LocalIsDarkTheme.current
    val interactiveModifier = if (enabled && capable && backdrop != null) {
        Modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    if (!size.isSpecified || !size.width.isFinite() || !size.height.isFinite() ||
                        size.width <= 0f || size.height <= 0f
                    ) return@drawBackdrop
                    colorControls(saturation = 1.08f)
                    blur(6.dp.toPx())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shape is CornerBasedShape) {
                        lens(
                            refractionHeight = minOf(8.dp.toPx(), size.minDimension / 2f),
                            refractionAmount = minOf(10.dp.toPx(), size.minDimension / 2f),
                            depthEffect = true,
                            chromaticAberration = false,
                        )
                    }
                },
                layerBlock = {
                    val progress = pressProgress.value
                    val scale = lerp(1f, 0.92f, progress)
                    scaleX = scale
                    scaleY = scale
                },
                highlight = {
                    Highlight(
                        alpha = lerp(0.24f, 0.40f, pressProgress.value),
                        style = HighlightStyle.Default,
                    )
                },
                shadow = {
                    Shadow(
                        radius = lerp(6f, 2f, pressProgress.value).dp,
                        color = Color.Black.copy(alpha = 0.20f),
                    )
                },
                onDrawSurface = {
                    val baseAlpha = if (isDark) 0.10f else 0.25f
                    val pressedAlpha = baseAlpha + pressProgress.value * 0.12f
                    drawRect(Color.White.copy(alpha = pressedAlpha))
                },
            )
            .pointerInput(scope) {
                val springSpec = spring<Float>(0.6f, 400f, 0.001f)
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    scope.launch { pressProgress.animateTo(1f, springSpec) }
                    waitForUpOrCancellation()
                    scope.launch { pressProgress.animateTo(0f, springSpec) }
                }
            }
            .clip(shape)
            .clickable(onClick = onClick)
    } else {
        Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f))
            .clickable(onClick = onClick)
    }

    Box(
        modifier = modifier.then(interactiveModifier),
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
