package com.lastwave.app.ui.player.automix

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
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

/**
 * The two decks of a running AutoMix, DJ-software style: the outgoing song
 * above, the incoming one below, on one shared time axis. Waveforms are split
 * into bass / mids / highs and drawn as they will be heard, after the EQ moves
 * and level curves, so the bass visibly hands over at the swap.
 */
@Composable
fun AutoMixDeckView(view: MixView, progress: Float, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .background(scheme.surfaceContainerLowest)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        DeckHeader(view.outgoing)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            val playhead = scheme.primary
            val outline = scheme.onSurface.copy(alpha = 0.45f)
            val box = scheme.onSurface.copy(alpha = 0.06f)
            val grid = scheme.onSurface.copy(alpha = 0.14f)
            val level = scheme.secondary
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "AutoMix: ${view.outgoing.title} into ${view.incoming.title}" },
            ) {
                drawMix(view, progress, playhead, outline, box, grid, level)
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = scheme.surfaceContainerHigh,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Text(
                    text = view.bars?.let { "$it bars" } ?: "${(view.overlapMs / 1000.0).roundToInt()} s",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        DeckHeader(view.incoming)
    }
}

@Composable
private fun DeckHeader(deck: MixDeckView) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        AsyncImage(
            model = deck.artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(scheme.surfaceContainerHigh),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                deck.title,
                style = MaterialTheme.typography.bodyMedium,
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
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            deck.bpm?.let {
                Text("${it.roundToInt()} bpm", style = MaterialTheme.typography.labelMedium, color = scheme.onSurface)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                deck.camelotKey?.let { key ->
                    Text(
                        key,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black.copy(alpha = 0.85f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(camelotColor(key))
                            .padding(horizontal = 4.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(formatDuration(deck.durationMs), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}

/** Frequency legend shared by DJ software: bass blue, mids orange, highs near-white. */
private val BassColor = Color(0xFF3D63F2)
private val MidColor = Color(0xFFE0893A)
private val HighColor = Color(0xFFF3E6D3)

private fun DrawScope.drawMix(
    view: MixView,
    progress: Float,
    playhead: Color,
    outline: Color,
    boxFill: Color,
    gridColor: Color,
    levelColor: Color,
) {
    val pad = view.overlapMs * 0.3
    val startMs = view.cueOutMs - pad
    val spanMs = view.overlapMs + 2 * pad
    fun x(outgoingMs: Double) = ((outgoingMs - startMs) / spanMs * size.width).toFloat()
    val gap = size.height * 0.06f
    val laneHeight = (size.height - gap) / 2f
    val boxLeft = x(view.cueOutMs.toDouble())
    val boxRight = x((view.cueOutMs + view.overlapMs).toDouble())
    val corner = CornerRadius(12.dp.toPx())

    drawRoundRect(boxFill, Offset(boxLeft, 0f), Size(boxRight - boxLeft, size.height), corner)
    // Bar lines across both lanes, on the outgoing grid.
    val barMs = view.barMs
    val downbeat = view.downbeatMs
    if (barMs != null && downbeat != null) {
        var bar = downbeat + ceil((startMs - downbeat) / barMs) * barMs
        while (bar <= startMs + spanMs) {
            drawLine(gridColor, Offset(x(bar), 0f), Offset(x(bar), size.height), strokeWidth = 1.dp.toPx())
            bar += barMs
        }
    }
    drawDeck(view.outgoing, view, outgoing = true, top = 0f, height = laneHeight, ::x)
    drawDeck(view.incoming, view, outgoing = false, top = laneHeight + gap, height = laneHeight, ::x)

    // Level curves inside the mix box.
    for ((top, outgoing) in listOf(0f to true, laneHeight + gap to false)) {
        val path = Path()
        val steps = 48
        for (step in 0..steps) {
            val p = step / steps.toFloat()
            val (outGain, inGain) = MixCurve.gains(p, view.shape)
            val gain = if (outgoing) outGain else inGain
            val px = boxLeft + (boxRight - boxLeft) * p
            val py = top + laneHeight - gain * (laneHeight - 4.dp.toPx())
            if (step == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, levelColor, style = Stroke(width = 1.5.dp.toPx()))
        drawRoundRect(outline, Offset(boxLeft, top), Size(boxRight - boxLeft, laneHeight), corner, style = Stroke(1.dp.toPx()))
    }
    val headX = boxLeft + (boxRight - boxLeft) * progress.coerceIn(0f, 1f)
    drawLine(playhead, Offset(headX, 0f), Offset(headX, size.height), strokeWidth = 2.dp.toPx())
}

private fun DrawScope.drawDeck(
    deck: MixDeckView,
    view: MixView,
    outgoing: Boolean,
    top: Float,
    height: Float,
    x: (Double) -> Float,
) {
    val center = top + height / 2f
    val half = height / 2f - 2.dp.toPx()
    val columnWidth = (x(BAND_COLUMN_MS.toDouble() / if (outgoing) 1.0 else view.tempoRatio.toDouble()) - x(0.0)).coerceAtLeast(1f)
    val mixEnd = (view.cueOutMs + view.overlapMs).toDouble()
    deck.bands.forEachIndexed { index, packed ->
        val mediaMs = deck.bandsStartMs + index * BAND_COLUMN_MS.toDouble()
        val outgoingMs = if (outgoing) mediaMs else view.incomingToOutgoing(mediaMs)
        val px = x(outgoingMs)
        if (px < -columnWidth || px > size.width) return@forEachIndexed
        val inMix = outgoingMs >= view.cueOutMs && outgoingMs <= mixEnd
        // Audio this deck will not play is greyed out: the old song after the mix, the new one before it.
        val unheard = if (outgoing) outgoingMs > mixEnd else outgoingMs < view.cueOutMs
        val alpha = if (unheard) 0.25f else 1f
        val gain = if (inMix) {
            val p = ((outgoingMs - view.cueOutMs) / view.overlapMs).toFloat()
            MixCurve.gains(p, view.shape).let { if (outgoing) it.first else it.second }.coerceAtLeast(0.15f)
        } else {
            1f
        }
        val (bass, treble) = eqAt(view, outgoing, outgoingMs)
        val low = ((packed shr 16) and 0xFF) / 255f * bass * gain
        val mid = ((packed shr 8) and 0xFF) / 255f * gain
        val high = (packed and 0xFF) / 255f * treble * gain
        for ((value, color) in listOf(low to BassColor, mid to MidColor, high * 0.7f to HighColor)) {
            val h = value * half
            if (h > 0.5f) drawRect(color.copy(alpha = alpha), Offset(px, center - h), Size(columnWidth, h * 2))
        }
    }
}

/** Bass and treble levels (1 = open) the deck's EQ stage applies at [outgoingMs], mirroring the coordinator. */
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

/** Camelot wheel position as hue, so compatible keys (neighbours) get neighbouring colours. */
private fun camelotColor(key: String): Color {
    val number = key.dropLast(1).toIntOrNull() ?: return Color.Gray
    val minor = key.last().uppercaseChar() == 'A'
    return Color.hsl(((number - 1) * 30f) % 360f, 0.7f, if (minor) 0.72f else 0.8f)
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
