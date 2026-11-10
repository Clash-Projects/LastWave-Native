package com.lastwave.app.ui.player

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Share
import com.lastwave.app.ui.player.lyricsshare.ShareLyricsDialog
import com.lastwave.app.ui.player.lyricsshare.TrackInfo
import com.lastwave.app.ui.player.lyricsshare.buildLyricsSnapshot
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.Slider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import com.lastwave.app.R
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.LiquidGlassPreset
import com.lastwave.app.ui.theme.LiquidGlassSurface
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.local.LyricsProvider
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.data.lyrics.LyricSyllable
import com.lastwave.app.data.lyrics.LyricsTranslation
import com.lastwave.app.data.lyrics.VocalRole
import com.lastwave.app.data.lyrics.isRtlText
import com.lastwave.app.data.lyrics.providerForSource
import com.lastwave.app.data.lyrics.translationLanguageName
import com.lastwave.app.data.lyrics.translationWireTarget
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlaybackProgressState
import com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator
import com.lastwave.app.ui.common.ExpressiveMotion
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.repeatOnLifecycle
import java.util.Locale

/**
 * One vocal run (lead or backing). Unlit rows and line-synced rows are a
 * single Text; a lit word-synced row is a dim base plus a bright copy wiped
 * by the karaoke mask.
 */
@Composable
internal fun KaraokeVocal(
    vocal: StagedVocal,
    lit: Boolean,
    currentPositionMs: () -> Long,
    style: TextStyle,
    textAlign: TextAlign,
    emphasis: Float,
    glow: Boolean,
    modifier: Modifier = Modifier,
) {
    val direction = if (vocal.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        if (lit && vocal.isWordSynced) {
            KaraokeFillText(
                vocal = vocal,
                currentPositionMs = currentPositionMs,
                style = style,
                textAlign = textAlign,
                emphasis = emphasis,
                glow = glow,
                modifier = modifier,
            )
        } else {
            val drawStyle = remember(style, lit, glow) {
                // On S+ the glow is a blurred copy of the same Text (see
                // LyricGlowText); the shadow is only the pre-S fallback.
                if (lit && glow && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                    style.copy(shadow = LYRIC_FALLBACK_SHADOW)
                } else style
            }
            Box(modifier) {
                if (lit && glow) {
                    LyricGlowText(text = vocal.text, style = style, textAlign = textAlign)
                }
                Text(
                    text = vocal.text,
                    style = drawStyle,
                    color = Color.White.copy(alpha = (if (lit) 1f else LYRIC_IDLE_TEXT_ALPHA) * emphasis),
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}


@Composable
internal fun KaraokeFillText(
    vocal: StagedVocal,
    currentPositionMs: () -> Long,
    style: TextStyle,
    textAlign: TextAlign,
    emphasis: Float,
    glow: Boolean,
    modifier: Modifier = Modifier,
) {
    val isRtl = vocal.isRtl
    val units = vocal.units
    val charRanges = vocal.charRanges
    val fullText = vocal.text
    val fillStyle = remember(style, glow) {
        if (glow && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            style.copy(shadow = LYRIC_FALLBACK_SHADOW)
        } else style
    }
    var layout by remember(fullText) { mutableStateOf<TextLayoutResult?>(null) }
    val maskPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        }
    }
    val clearPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
    }
    val gradientBounds = remember { FloatArray(2) { -1f } }
    val maskGradients = remember { KaraokeMaskGradients() }
    val gradientIsRtl = remember { BooleanArray(2) } // [0] = bound, [1] = rtl

    Box(modifier = modifier) {
        if (glow) {
            LyricGlowText(text = fullText, style = style, textAlign = textAlign)
        }
        // Dim base: the whole row, with each syllable lifting as it is sung.
        Text(
            text = fullText,
            style = style,
            color = Color.White.copy(alpha = LYRIC_LIT_BASE_ALPHA * emphasis),
            textAlign = textAlign,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    val l = layout
                    if (l == null) drawContent()
                    else drawWithSyllableLift(l, units, charRanges, currentPositionMs(), LYRIC_LIFT.toPx(), isRtl)
                },
        )
        // Bright copy, revealed up to the sung extent with a feathered edge.
        Text(
            text = fullText,
            style = fillStyle,
            color = Color.White.copy(alpha = emphasis),
            textAlign = textAlign,
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    val l = layout ?: return@drawWithContent
                    val textLength = fullText.length
                    if (textLength == 0) return@drawWithContent
                    val pos = currentPositionMs()
                    var fill = 0f
                    var sylIdx = -1
                    var sylP = 0f
                    for (i in units.indices) {
                        val syl = units[i]
                        if (pos < syl.timeMs) break
                        val (cs, ce) = charRanges[i]
                        val linear = if (syl.durationMs <= 0L) 1f
                        else ((pos - syl.timeMs).toFloat() / syl.durationMs).coerceIn(0f, 1f)
                        // Mild ease-out: a sung syllable attacks fast and
                        // sustains, so a linear wipe reads as lagging.
                        val p = 1f - Math.pow((1f - linear).toDouble(), 1.4).toFloat()
                        fill = cs + (ce - cs) * p
                        sylIdx = i
                        sylP = p
                        if (p < 1f) break
                    }
                    if (fill <= 0f || sylIdx < 0) return@drawWithContent
                    val liftPx = LYRIC_LIFT.toPx()
                    if (fill >= textLength) {
                        drawWithSyllableLift(l, units, charRanges, pos, liftPx, isRtl)
                        return@drawWithContent
                    }
                    // Interpolate across the syllable's pixel extent, not
                    // per char: in Indic/complex scripts offsets inside a
                    // grapheme cluster (matras, conjuncts) report stalled
                    // or backward x, which froze/jumped the wipe.
                    val (sCs, sCe) = charRanges[sylIdx]
                    val startIdx = sCs.coerceIn(0, textLength - 1)
                    val lastIdx = (sCe - 1).coerceIn(startIdx, textLength - 1)
                    val sameLine = l.getLineForOffset(startIdx) == l.getLineForOffset(lastIdx)
                    val lineIdx: Int
                    val x: Float
                    var feather = 12f
                    if (sameLine) {
                        lineIdx = l.getLineForOffset(startIdx)
                        // Skip the separator space so the wipe starts on the glyphs.
                        var glyphStart = startIdx
                        while (glyphStart < lastIdx && fullText[glyphStart] == ' ') glyphStart++
                        val xs = l.getHorizontalPosition(glyphStart, true)
                        val xe = if (sCe < textLength && l.getLineForOffset(sCe) == lineIdx) {
                            l.getHorizontalPosition(sCe, true)
                        } else if (isRtl) l.getLineLeft(lineIdx) else l.getLineRight(lineIdx)
                        x = xs + (xe - xs) * sylP
                        // Soft edge proportional to the syllable: short
                        // syllables stay crisp, long held notes glide.
                        feather = (kotlin.math.abs(xe - xs) * 0.3f).coerceIn(5f, 18f)
                    } else {
                        val charIdx = fill.toInt().coerceIn(0, textLength - 1)
                        val frac = fill - charIdx
                        lineIdx = l.getLineForOffset(charIdx)
                        val x0 = l.getHorizontalPosition(charIdx, true)
                        val x1 = if (charIdx + 1 < textLength && l.getLineForOffset(charIdx + 1) == lineIdx) {
                            l.getHorizontalPosition(charIdx + 1, true)
                        } else if (isRtl) l.getLineLeft(lineIdx) else l.getLineRight(lineIdx)
                        x = x0 + (x1 - x0) * frac
                    }
                    // Lifted glyphs rise above the row; widen the band so the
                    // unsung part of a rising syllable stays masked.
                    val top = l.getLineTop(lineIdx) - liftPx * 1.5f
                    val bottom = l.getLineBottom(lineIdx)
                    val canvas = drawContext.canvas.nativeCanvas

                    // Visual rows above the one being sung are fully sung:
                    // draw them straight, no offscreen layer.
                    if (lineIdx > 0 && top > 0f) {
                        val saved = canvas.save()
                        canvas.clipRect(-size.width, -size.height, size.width * 2f, top)
                        drawWithSyllableLift(l, units, charRanges, pos, liftPx, isRtl)
                        canvas.restoreToCount(saved)
                    }

                    // Offscreen layer bounded to the sung visual row only.
                    val checkpoint = canvas.saveLayer(0f, top, size.width, bottom, null)
                    drawWithSyllableLift(l, units, charRanges, pos, liftPx, isRtl)
                    val xStart = (x - feather).coerceAtLeast(0f)
                    val xEnd = (x + feather).coerceAtMost(size.width)
                    if (isRtl) {
                        if (xStart > 0f) canvas.drawRect(0f, top, xStart, bottom, clearPaint)
                    } else {
                        if (xEnd < size.width) canvas.drawRect(xEnd, top, size.width, bottom, clearPaint)
                    }
                    if (xEnd > xStart) {
                        if (gradientBounds[0] != xStart || gradientBounds[1] != xEnd ||
                            !gradientIsRtl[0] || gradientIsRtl[1] != isRtl
                        ) {
                            gradientBounds[0] = xStart
                            gradientBounds[1] = xEnd
                            gradientIsRtl[0] = true
                            gradientIsRtl[1] = isRtl
                            maskPaint.shader = null // re-bind so the moved local matrix is picked up on every API level
                            maskPaint.shader = maskGradients.place(rtl = isRtl, xStart, xEnd)
                        }
                        canvas.drawRect(xStart, top, xEnd, bottom, maskPaint)
                    }
                    canvas.restoreToCount(checkpoint)
                },
        )
    }
}

/**
 * Feather gradients for the karaoke wipe. The edge moves every frame, and a
 * fresh LinearGradient per frame churned the GC during word-synced lyrics.
 * Instead one unit-width gradient per direction is created once and only its
 * local matrix is moved/stretched to [xStart, xEnd]; CLAMP tiling makes it
 * render identically to a gradient built at those coordinates.
 */

internal class KaraokeMaskGradients {
    private val ltr = android.graphics.LinearGradient(
        0f, 0f, 1f, 0f,
        android.graphics.Color.WHITE,
        android.graphics.Color.TRANSPARENT,
        android.graphics.Shader.TileMode.CLAMP,
    )
    private val rtl = android.graphics.LinearGradient(
        0f, 0f, 1f, 0f,
        android.graphics.Color.TRANSPARENT,
        android.graphics.Color.WHITE,
        android.graphics.Shader.TileMode.CLAMP,
    )
    private val matrix = android.graphics.Matrix()

    fun place(rtl: Boolean, xStart: Float, xEnd: Float): android.graphics.Shader {
        val shader = if (rtl) this.rtl else ltr
        matrix.setScale((xEnd - xStart).coerceAtLeast(0.001f), 1f)
        matrix.postTranslate(xStart, 0f)
        shader.setLocalMatrix(matrix)
        return shader
    }
}

/**
 * Below API 31 Modifier.blur is a no-op, so the caller keeps the shadow there
 * and this draws nothing. An unbounded edge treatment keeps the halo from
 * being cut off at the Text's own bounds.
 */
@Composable
internal fun LyricGlowText(text: String, style: TextStyle, textAlign: TextAlign = TextAlign.Start) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
    Text(
        text = text,
        style = style,
        color = Color.White.copy(alpha = LYRIC_GLOW_ALPHA),
        textAlign = textAlign,
        modifier = Modifier
            .fillMaxWidth()
            .blur(LYRIC_GLOW_RADIUS, BlurredEdgeTreatment.Unbounded),
    )
}

/**
 * Lift envelope (0..1) for a syllable: rises as it starts being sung, holds
 * while sung, then settles back after it ends, so a soft wave travels across
 * the line. Purely visual; derived from the same timestamps as the wipe.
 */
internal fun syllableLift(syl: LyricSyllable, pos: Long): Float {
    if (pos < syl.timeMs) return 0f
    val dur = syl.durationMs.coerceAtLeast(1L)
    val rise = ((pos - syl.timeMs).toFloat() / dur.coerceIn(140L, 420L)).coerceIn(0f, 1f)
    val riseInv = 1f - rise
    val riseE = 1f - riseInv * riseInv * riseInv
    val after = pos - (syl.timeMs + dur)
    val settle = if (after <= 0L) 1f else (1f - after / 520f).coerceIn(0f, 1f)
    val settleE = settle * settle * (3f - 2f * settle)
    return riseE * settleE
}

/**
 * Draws the text content with each currently-lifting syllable translated up
 * by up to [liftPx]. Syllables wrapping across visual lines are not lifted.
 */

internal fun ContentDrawScope.drawWithSyllableLift(
    l: TextLayoutResult,
    units: List<LyricSyllable>,
    charRanges: List<Pair<Int, Int>>,
    pos: Long,
    liftPx: Float,
    isRtl: Boolean,
) {
    val textLength = l.layoutInput.text.length
    var rects: ArrayList<FloatArray>? = null
    for (i in units.indices) {
        val lift = syllableLift(units[i], pos)
        if (lift <= 0.001f) continue
        val (cs, ce) = charRanges.getOrNull(i) ?: continue
        if (ce <= cs || cs >= textLength) continue
        val last = (ce - 1).coerceAtMost(textLength - 1)
        val li = l.getLineForOffset(cs)
        if (li != l.getLineForOffset(last)) continue
        val xs = l.getHorizontalPosition(cs, true)
        val xe = if (ce < textLength && l.getLineForOffset(ce) == li) {
            l.getHorizontalPosition(ce, true)
        } else if (isRtl) l.getLineLeft(li) else l.getLineRight(li)
        if (rects == null) rects = ArrayList(4)
        rects += floatArrayOf(
            minOf(xs, xe), l.getLineTop(li), maxOf(xs, xe), l.getLineBottom(li), -lift * liftPx,
        )
    }
    if (rects == null) {
        drawContent()
        return
    }
    val canvas = drawContext.canvas.nativeCanvas
    canvas.save()
    for (r in rects) canvas.clipOutRect(r[0], r[1], r[2], r[3])
    drawContent()
    canvas.restore()
    for (r in rects) {
        canvas.save()
        canvas.translate(0f, r[4])
        canvas.clipRect(r[0], r[1], r[2], r[3])
        drawContent()
        canvas.restore()
    }
}
