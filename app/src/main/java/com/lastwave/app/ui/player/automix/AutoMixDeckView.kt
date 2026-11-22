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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lastwave.app.playback.automix.analysis.BAND_COLUMN_MS
import com.lastwave.app.playback.automix.api.MixDeckView
import com.lastwave.app.playback.automix.api.MixView
import com.lastwave.app.playback.automix.engine.MixCurve
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// Fixed frequency and transport colors keep this studio view readable with monochrome app palettes.
internal val StudioSurface = Color(0xFF111115)
internal val StudioContent = Color(0xFFF2F0F2)
private val StudioPlot = Color(0xFF1B1B21)
private val BassColor = Color(0xFF345CEB)
private val MidColor = Color(0xFFE59A44)
private val AirColor = Color(0xFFFFEBD0)
private val CueColor = Color(0xFF5ABDE5)
private val LevelColor = Color(0xFFFFD45D)
private val PlayheadColor = Color(0xFF42E58B)

@Composable
internal fun AutoMixDeckView(
    view: MixView,
    playheadMs: Double,
    modifier: Modifier = Modifier,
    status: String? = null,
    outgoingStatus: WaveformPreviewStatus = WaveformPreviewStatus.READY,
    incomingStatus: WaveformPreviewStatus = WaveformPreviewStatus.READY,
) {
    val head = animateFloatAsState(playheadMs.toFloat(), tween(80, easing = LinearEasing), label = "mixPlayhead")
    val reveal = animateFloatAsState(
        if (view.outgoing.bands.isEmpty() && view.incoming.bands.isEmpty()) 0f else 1f, tween(400), label = "mixReveal",
    )
    BoxWithConstraints(modifier) {
        val fontScale = LocalDensity.current.fontScale
        val compact = maxHeight < 350.dp && maxWidth >= 440.dp
        val contentHeight = maxOf(maxHeight, (if (compact) 100.dp else 180.dp) * fontScale + 180.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                modifier = Modifier.fillMaxWidth().height(contentHeight).padding(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        color = StudioContent.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (compact) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.weight(1f)) { DeckHeader(view.outgoing, compact = true) }
                        Box(Modifier.weight(1f)) { DeckHeader(view.incoming, compact = true) }
                    }
                } else {
                    DeckHeader(view.outgoing)
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(StudioPlot),
                ) {
                    // Own layer: the playhead redraws every frame without re-recording the waveforms.
                    Canvas(
                        Modifier.fillMaxSize().graphicsLayer { this.alpha = reveal.value }.semantics {
                            contentDescription = "Transition waveform: ${view.outgoing.title} into ${view.incoming.title}"
                        },
                    ) {
                        drawMix(view)
                    }
                    Canvas(Modifier.fillMaxSize()) { drawPlayhead(view, head.value.toDouble()) }
                    Column(Modifier.fillMaxSize()) {
                        WaveformNotice(outgoingStatus, Modifier.weight(1f).fillMaxWidth())
                        WaveformNotice(incomingStatus, Modifier.weight(1f).fillMaxWidth())
                    }
                }
                if (!compact) DeckHeader(view.incoming)
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SpectrumLabel("Bass", BassColor)
                    Spacer(Modifier.width(12.dp))
                    SpectrumLabel("Mid", MidColor)
                    Spacer(Modifier.width(12.dp))
                    SpectrumLabel("Air", AirColor)
                    Spacer(Modifier.weight(1f))
                    Text(
                        view.bars?.let { "$it bars · " }.orEmpty() + "${(view.overlapMs / 1_000.0).roundToInt()} s",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal),
                        color = StudioContent.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeckHeader(deck: MixDeckView, compact: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AutoMixArtwork(
            deck,
            Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(StudioPlot),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                deck.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                color = StudioContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                deck.artist,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal),
                color = StudioContent.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (compact) DeckMetrics(deck)
        }
        if (!compact) {
            Spacer(Modifier.width(8.dp))
            DeckMetrics(deck)
        }
    }
}

@Composable
private fun DeckMetrics(deck: MixDeckView) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        deck.bpm?.let {
            Text(
                "${it.roundToInt()} BPM",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                color = StudioContent.copy(alpha = 0.9f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            deck.camelotKey?.let { key ->
                Text(
                    key,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                    color = StudioSurface,
                    modifier = Modifier
                        .background(if (key.endsWith("A")) Color(0xFFF0B394) else Color(0xFFE582C5), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            Text(
                formatDuration(deck.durationMs),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal),
                color = StudioContent.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun WaveformNotice(status: WaveformPreviewStatus, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (status == WaveformPreviewStatus.LOADING) {
            Text(
                "Preparing waveform…",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Normal),
                color = StudioContent.copy(alpha = 0.55f),
                modifier = Modifier.background(StudioPlot.copy(alpha = 0.9f), RoundedCornerShape(4.dp)).padding(8.dp),
            )
        }
    }
}

@Composable
private fun SpectrumLabel(label: String, color: Color) {
    Box(Modifier.size(4.dp).background(color, RoundedCornerShape(50)))
    Spacer(Modifier.width(4.dp))
    Text(
        label,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Normal),
        color = StudioContent.copy(alpha = 0.45f),
    )
}

/** One song's lane: the mix window owns the spotlight box, the rest of the song is squeezed beside it. */
private class LaneSegment(val ms0: Double, val ms1: Double, val x0: Float, val x1: Float)

private class Lane(durationMs: Long, preMs: Double, mixMs: Double, padMs: Double, left: Float, right: Float, width: Float) {
    val segments: List<LaneSegment>

    init {
        // The window is the transition plus the same context on both sides, at the box's own scale.
        val scale = (right - left) / mixMs.coerceAtLeast(1.0)
        val mixEnd = preMs + mixMs
        val pre = minOf(preMs, padMs)
        val post = minOf(maxOf(durationMs - mixEnd, 0.0), padMs)
        segments = listOf(
            LaneSegment(preMs - pre, preMs, left - (pre * scale).toFloat(), left),
            LaneSegment(preMs, mixEnd, left, right),
            LaneSegment(mixEnd, mixEnd + post, right, right + (post * scale).toFloat()),
        )
    }

    fun x(ms: Double): Float {
        val segment = if (ms < segments[1].ms0) segments[0] else if (ms <= segments[1].ms1) segments[1] else segments[2]
        val span = segment.ms1 - segment.ms0
        if (span <= 0.0) return segment.x0
        return segment.x0 + ((ms.coerceIn(segment.ms0, segment.ms1) - segment.ms0) / span * (segment.x1 - segment.x0)).toFloat()
    }
}

private class MixPlot(view: MixView, width: Float, val top: Float, val bottom: Float, val gap: Float) {
    val laneHeight = ((bottom - top - gap) / 2f).coerceAtLeast(1f)
    val lowerTop = top + laneHeight + gap
    // Equal context on both sides centers the actual overlap.
    private val padMs = 3_000.0
    val boxLeft = (width * padMs / (view.overlapMs + 2 * padMs)).toFloat()
    val boxRight = width - boxLeft
    val outgoing = Lane(view.outgoing.durationMs, view.cueOutMs.toDouble(), view.overlapMs.toDouble(), padMs, boxLeft, boxRight, width)
    val incoming = Lane(
        view.incoming.durationMs, view.cueInMs.toDouble(), view.overlapMs * view.tempoRatio.toDouble(),
        padMs * view.tempoRatio, boxLeft, boxRight, width,
    )
}

private fun DrawScope.mixPlot(view: MixView) = MixPlot(view, size.width, 12.dp.toPx(), size.height - 12.dp.toPx(), 20.dp.toPx())

private fun DrawScope.drawMix(view: MixView) {
    val plot = mixPlot(view)
    val corner = CornerRadius(8.dp.toPx())
    val boxWidth = plot.boxRight - plot.boxLeft
    // The transition window is the one lit area; everything around it is quieter.
    drawRoundRect(StudioContent.copy(alpha = 0.07f), Offset(plot.boxLeft, plot.top), Size(boxWidth, plot.bottom - plot.top), corner)
    val barMs = view.barMs?.takeIf { it > 0.0 }
    val beatMs = barMs?.div(4) ?: 1_000.0
    val origin = view.downbeatMs ?: 0.0
    val mixStart = view.cueOutMs.toDouble()
    val mixEnd = mixStart + view.overlapMs
    var beat = origin + ceil((mixStart - origin) / beatMs) * beatMs
    while (beat <= mixEnd) {
        val downbeat = barMs != null && kotlin.math.abs((beat - origin) % barMs) < 1.0
        val x = plot.boxLeft + ((beat - mixStart) / view.overlapMs * boxWidth).toFloat()
        drawLine(
            StudioContent.copy(alpha = if (downbeat) 0.14f else 0.05f),
            Offset(x, plot.top), Offset(x, plot.bottom), 1.dp.toPx(),
        )
        beat += beatMs
    }
    drawLane(view.outgoing, plot.outgoing, plot.top, plot.laneHeight, outgoing = true)
    drawLane(view.incoming, plot.incoming, plot.lowerTop, plot.laneHeight, outgoing = false)
    for ((laneTop, outgoing) in listOf(plot.top to true, plot.lowerTop to false)) {
        val path = Path()
        for (step in 0..96) {
            val p = step / 96f
            val gains = MixCurve.gains(p, view.shape)
            val gain = if (outgoing) gains.first else gains.second
            val px = plot.boxLeft + boxWidth * p
            val py = laneTop + plot.laneHeight - gain * (plot.laneHeight - 4.dp.toPx())
            if (step == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        val color = if (outgoing) CueColor else LevelColor
        drawPath(path, StudioSurface.copy(alpha = 0.85f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        drawRoundRect(color, Offset(plot.boxLeft, laneTop), Size(boxWidth, plot.laneHeight), corner, Stroke(1.5.dp.toPx()))
    }
}

private fun DrawScope.drawPlayhead(view: MixView, playheadMs: Double) {
    val plot = mixPlot(view)
    val mixEnd = view.cueOutMs + view.overlapMs
    val outX = if (playheadMs <= mixEnd) plot.outgoing.x(playheadMs) else null
    val inX = if (playheadMs >= view.cueOutMs) plot.incoming.x(view.cueInMs + (playheadMs - view.cueOutMs) * view.tempoRatio) else null
    fun mark(x: Float, from: Float, to: Float) {
        drawLine(PlayheadColor.copy(alpha = 0.1f), Offset(x, from), Offset(x, to), 8.dp.toPx())
        drawLine(PlayheadColor, Offset(x, from), Offset(x, to), 1.5.dp.toPx(), StrokeCap.Round)
    }
    outX?.let { mark(it, plot.top, plot.top + plot.laneHeight) }
    inX?.let { mark(it, plot.lowerTop, plot.lowerTop + plot.laneHeight) }
    if (outX != null && inX != null) {
        // Inside the box both lanes share one x, so the playhead reads as a single line across the overlap.
        mark(outX, plot.top + plot.laneHeight, plot.lowerTop)
        val seam = plot.top + plot.laneHeight + plot.gap / 2
        drawCircle(StudioSurface, 5.dp.toPx(), Offset(outX, seam))
        drawCircle(PlayheadColor, 3.dp.toPx(), Offset(outX, seam))
    }
}

/** Crisp bars, one per pixel bin, keeping the loudest column in each so transients are not averaged away. */
private fun DrawScope.drawLane(deck: MixDeckView, lane: Lane, top: Float, height: Float, outgoing: Boolean) {
    val center = top + height / 2f
    drawLine(StudioContent.copy(alpha = 0.08f), Offset(0f, center), Offset(size.width, center), 1.dp.toPx())
    if (deck.bands.isEmpty()) return
    val half = (height / 2f - 2.dp.toPx()).coerceAtLeast(1f)
    val pitch = 1.5.dp.toPx()
    val barWidth = 1.dp.toPx().coerceAtLeast(1f)
    val last = deck.bands.lastIndex
    lane.segments.forEachIndexed { index, segment ->
        val width = segment.x1 - segment.x0
        if (width < 1f) return@forEachIndexed
        // Outgoing is lit until the mix ends, incoming from the mix start; the other side is dimmed.
        val alpha = when (index) {
            1 -> 1f
            0 -> if (outgoing) 0.6f else 0.2f
            else -> if (outgoing) 0.2f else 0.6f
        }
        val msPerPx = (segment.ms1 - segment.ms0) / width
        var x = segment.x0
        while (x < segment.x1) {
            val from = segment.ms0 + (x - segment.x0) * msPerPx
            val until = from + minOf(pitch, segment.x1 - x) * msPerPx
            val first = floor((from - deck.bandsStartMs) / BAND_COLUMN_MS).toInt()
            val after = ceil((until - deck.bandsStartMs) / BAND_COLUMN_MS).toInt().coerceAtLeast(first + 1)
            if (after > 0 && first <= last) {
                var bass = 0
                var mid = 0
                var air = 0
                for (i in maxOf(first, 0)..minOf(after - 1, last)) {
                    val packed = deck.bands[i]
                    bass = maxOf(bass, (packed shr 16) and 0xFF)
                    mid = maxOf(mid, (packed shr 8) and 0xFF)
                    air = maxOf(air, packed and 0xFF)
                }
                val barX = floor(x)
                fun bar(value: Int, scale: Float, color: Color) {
                    val h = (value / 255f * scale).coerceAtMost(1f) * half
                    if (h > 0f) drawRect(color.copy(alpha = alpha), Offset(barX, center - h), Size(barWidth, h * 2f))
                }
                bar(bass, 1.1f, BassColor)
                bar(mid, 0.9f, MidColor)
                bar(air, 0.42f, AirColor)
            }
            x += pitch
        }
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
