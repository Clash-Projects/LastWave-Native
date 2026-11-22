package com.lastwave.app.ui.player.automix

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lastwave.app.playback.automix.analysis.BAND_COLUMN_MS
import com.lastwave.app.playback.automix.api.MixDeckView
import com.lastwave.app.playback.automix.api.MixShape
import com.lastwave.app.playback.automix.api.MixView
import com.lastwave.app.playback.automix.engine.MixCurve
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun AutoMixDeckView(view: MixView, playheadMs: Double, modifier: Modifier = Modifier, status: String? = null) {
    val scheme = MaterialTheme.colorScheme
    // Interpolate telemetry samples without changing the audio clock or mix progress.
    val head = animateFloatAsState(playheadMs.toFloat(), tween(80, easing = LinearEasing), label = "mixPlayhead")
    val colors = MixColors(
        outgoing = scheme.primary,
        incoming = scheme.tertiary,
        bass = scheme.secondary,
        mids = scheme.tertiary,
        highs = scheme.onSurface,
        grid = scheme.onSurface.copy(alpha = 0.08f),
        surface = scheme.surfaceContainerHigh,
    )
    BoxWithConstraints(modifier) {
        val fontScale = LocalDensity.current.fontScale
        val compact = maxHeight < 400.dp && maxWidth >= 440.dp && fontScale < 1.5f
        val contentHeight = maxOf(maxHeight, (if (compact) 160.dp else 280.dp) * fontScale + 160.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                modifier = Modifier.fillMaxWidth().height(contentHeight).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp),
            ) {
                status?.let {
                    Text(
                        it,
                        style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (compact) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.weight(1f)) { DeckHeader(view.outgoing, "OUT", colors.outgoing, compact = true) }
                        Box(Modifier.weight(1f)) { DeckHeader(view.incoming, "IN", colors.incoming, compact = true) }
                    }
                } else {
                    DeckHeader(view.outgoing, "OUTGOING", colors.outgoing)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(scheme.surfaceContainerHigh),
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = "Transition waveform: ${view.outgoing.title} into ${view.incoming.title}" },
                    ) {
                        drawMix(view, head.value.toDouble(), colors)
                    }
                    Row(
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("TRANSITION", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        Text(
                            view.bars?.let { "$it bars" } ?: "${(view.overlapMs / 1000.0).roundToInt()} s",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurface,
                        )
                    }
                }
                if (!compact) DeckHeader(view.incoming, "INCOMING", colors.incoming)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SpectrumLabel("Bass", colors.bass)
                    Spacer(Modifier.width(16.dp))
                    SpectrumLabel("Mid", colors.mids)
                    Spacer(Modifier.width(16.dp))
                    SpectrumLabel("Air", colors.highs)
                }
            }
        }
    }
}

@Composable
private fun DeckHeader(deck: MixDeckView, role: String, accent: Color, compact: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        AsyncImage(
            model = deck.artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(if (compact) 40.dp else 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.surfaceContainerHigh),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (compact) listOfNotNull(role, deck.bpm?.let { "${it.roundToInt()} BPM" }, deck.camelotKey, formatDuration(deck.durationMs)).joinToString(" · ") else role,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                deck.title,
                style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                deck.artist,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!compact) {
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                deck.bpm?.let {
                    Text("${it.roundToInt()} BPM", style = MaterialTheme.typography.labelMedium, color = scheme.onSurface)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    deck.camelotKey?.let { key ->
                        Text(key, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = accent)
                    }
                    Text(formatDuration(deck.durationMs), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SpectrumLabel(label: String, color: Color) {
    Box(Modifier.size(4.dp).background(color, RoundedCornerShape(50)))
    Spacer(Modifier.width(4.dp))
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private class MixColors(
    val outgoing: Color,
    val incoming: Color,
    val bass: Color,
    val mids: Color,
    val highs: Color,
    val grid: Color,
    val surface: Color,
)

private fun DrawScope.drawMix(view: MixView, playheadMs: Double, colors: MixColors) {
    val preRoll = (view.cueOutMs - (view.outgoing.durationMs - 25_000)).coerceIn(3_000, 25_000)
    val startMs = (view.cueOutMs - preRoll).coerceAtLeast(0).toDouble()
    val endMs = (view.cueOutMs + view.overlapMs + 3_000).toDouble()
    val inset = 12.dp.toPx()
    val width = (size.width - 2 * inset).coerceAtLeast(1f)
    fun x(outgoingMs: Double) = inset + ((outgoingMs - startMs) / (endMs - startMs).coerceAtLeast(1.0) * width).toFloat()
    val top = 36.dp.toPx()
    val bottom = size.height - 16.dp.toPx()
    val gap = 24.dp.toPx()
    val laneHeight = ((bottom - top - gap) / 2f).coerceAtLeast(1f)
    val lowerTop = top + laneHeight + gap
    val boxLeft = x(view.cueOutMs.toDouble())
    val boxRight = x((view.cueOutMs + view.overlapMs).toDouble())
    val mixWidth = (boxRight - boxLeft).coerceAtLeast(1f)

    drawRect(
        Brush.verticalGradient(listOf(colors.outgoing.copy(alpha = 0.08f), colors.incoming.copy(alpha = 0.08f))),
        Offset(boxLeft, top), Size(mixWidth, bottom - top),
    )
    val barMs = view.barMs
    val downbeat = view.downbeatMs
    if (barMs != null && barMs > 0 && downbeat != null) {
        var bar = downbeat + ceil((startMs - downbeat) / barMs) * barMs
        while (bar <= endMs) {
            drawLine(colors.grid, Offset(x(bar), top), Offset(x(bar), bottom), strokeWidth = 1.dp.toPx())
            bar += barMs
        }
    } else {
        for (tick in 0..8) {
            val px = inset + width * tick / 8f
            drawLine(colors.grid, Offset(px, top), Offset(px, bottom), strokeWidth = 1.dp.toPx())
        }
    }
    drawDeck(view.outgoing, view, true, top, laneHeight, colors, ::x)
    drawDeck(view.incoming, view, false, lowerTop, laneHeight, colors, ::x)

    for ((laneTop, outgoing) in listOf(top to true, lowerTop to false)) {
        val accent = if (outgoing) colors.outgoing else colors.incoming
        val path = Path()
        for (step in 0..96) {
            val p = step / 96f
            val (outGain, inGain) = MixCurve.gains(p, view.shape)
            val gain = if (outgoing) outGain else inGain
            val px = boxLeft + mixWidth * p
            val py = laneTop + laneHeight - gain * (laneHeight - 4.dp.toPx())
            if (step == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, colors.surface.copy(alpha = 0.8f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, accent.copy(alpha = 0.9f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
    }
    // Cue and release marks frame the real overlap; the cursor continues through the UI post-roll.
    for (px in listOf(boxLeft, boxRight)) {
        drawLine(colors.highs.copy(alpha = 0.25f), Offset(px, top), Offset(px, top + 8.dp.toPx()), 1.dp.toPx())
        drawLine(colors.highs.copy(alpha = 0.25f), Offset(px, bottom - 8.dp.toPx()), Offset(px, bottom), 1.dp.toPx())
    }
    val seam = top + laneHeight + gap / 2
    drawLine(colors.grid, Offset(inset, seam), Offset(size.width - inset, seam), 1.dp.toPx())
    val headX = x(playheadMs).coerceIn(inset, size.width - inset)
    drawLine(colors.outgoing.copy(alpha = 0.08f), Offset(headX, top), Offset(headX, bottom), 8.dp.toPx())
    drawLine(colors.outgoing, Offset(headX, top), Offset(headX, bottom), 1.5.dp.toPx(), StrokeCap.Round)
    drawCircle(colors.surface, 5.dp.toPx(), Offset(headX, seam))
    drawCircle(colors.outgoing, 3.dp.toPx(), Offset(headX, seam))
    val capWidth = 3.dp.toPx()
    drawRoundRect(colors.outgoing, Offset(headX - capWidth, top - 2.dp.toPx()), Size(capWidth * 2, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
}

private fun DrawScope.drawDeck(
    deck: MixDeckView,
    view: MixView,
    outgoing: Boolean,
    top: Float,
    height: Float,
    colors: MixColors,
    x: (Double) -> Float,
) {
    val center = top + height / 2f
    drawLine(colors.highs.copy(alpha = 0.12f), Offset(12.dp.toPx(), center), Offset(size.width - 12.dp.toPx(), center), 1.dp.toPx())
    if (deck.bands.isEmpty()) return
    val half = (height / 2f - 8.dp.toPx()).coerceAtLeast(1f)
    val columnWidth = (x(BAND_COLUMN_MS.toDouble() / if (outgoing) 1.0 else view.tempoRatio.toDouble()) - x(0.0)).coerceAtLeast(1f)
    val mixEnd = (view.cueOutMs + view.overlapMs).toDouble()
    deck.bands.forEachIndexed { index, packed ->
        val mediaMs = deck.bandsStartMs + index * BAND_COLUMN_MS.toDouble()
        val outgoingMs = if (outgoing) mediaMs else view.incomingToOutgoing(mediaMs)
        val px = x(outgoingMs)
        if (px < -columnWidth || px > size.width) return@forEachIndexed
        val inMix = outgoingMs >= view.cueOutMs && outgoingMs <= mixEnd
        val unheard = if (outgoing) outgoingMs > mixEnd else outgoingMs < view.cueOutMs
        val alpha = if (unheard) 0.12f else if (inMix) 0.9f else 0.4f
        val gain = if (inMix) {
            val p = ((outgoingMs - view.cueOutMs) / view.overlapMs.coerceAtLeast(1)).toFloat()
            MixCurve.gains(p, view.shape).let { if (outgoing) it.first else it.second }.coerceAtLeast(0.15f)
        } else {
            1f
        }
        val (bass, treble) = eqAt(view, outgoing, outgoingMs)
        val low = ((packed shr 16) and 0xFF) / 255f * bass * gain
        val mid = ((packed shr 8) and 0xFF) / 255f * gain
        val high = (packed and 0xFF) / 255f * treble * gain
        for ((value, color) in listOf(low to colors.bass, mid to colors.mids, high * 0.7f to colors.highs)) {
            val h = value * half
            if (h > 0.5f) drawRoundRect(color.copy(alpha = alpha), Offset(px, center - h), Size(columnWidth * 0.85f, h * 2), CornerRadius(0.5.dp.toPx()))
        }
    }
}

private fun eqAt(view: MixView, outgoing: Boolean, outgoingMs: Double): Pair<Float, Float> {
    val swapAt = view.bassSwapAtMs?.toDouble() ?: return 1f to 1f
    val ramp = view.bassSwapRampMs.toDouble().coerceAtLeast(1.0)
    val swapProgress = ((outgoingMs - swapAt) / ramp).coerceIn(0.0, 1.0).toFloat()
    if (outgoing) return (1f - swapProgress) to 1f
    val treble = if (view.shape == MixShape.BASS_SWAP) {
        ((outgoingMs - view.cueOutMs) / (swapAt - view.cueOutMs).coerceAtLeast(1.0)).coerceIn(0.0, 1.0).toFloat()
    } else {
        1f
    }
    return swapProgress to treble
}

private fun formatDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
