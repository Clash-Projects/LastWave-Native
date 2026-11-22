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
                    Canvas(
                        Modifier.fillMaxSize().semantics {
                            contentDescription = "Transition waveform: ${view.outgoing.title} into ${view.incoming.title}"
                        },
                    ) {
                        drawMix(view, head.value.toDouble())
                    }
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
                "${it.roundToInt()} bpm",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal),
                color = StudioContent.copy(alpha = 0.8f),
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

private fun DrawScope.drawMix(view: MixView, playheadMs: Double) {
    // Equal context on both sides centers the actual overlap; post-roll is presentation only.
    val startMs = view.cueOutMs - 3_000.0
    val spanMs = view.overlapMs + 6_000.0
    fun x(ms: Double) = ((ms - startMs) / spanMs * size.width).toFloat()
    val top = 12.dp.toPx()
    val bottom = size.height - 12.dp.toPx()
    val gap = 28.dp.toPx()
    val laneHeight = ((bottom - top - gap) / 2f).coerceAtLeast(1f)
    val lowerTop = top + laneHeight + gap
    val boxLeft = x(view.cueOutMs.toDouble())
    val boxRight = x((view.cueOutMs + view.overlapMs).toDouble())
    drawRoundRect(
        StudioContent.copy(alpha = 0.035f), Offset(boxLeft, top),
        Size(boxRight - boxLeft, bottom - top), CornerRadius(8.dp.toPx()),
    )
    val barMs = view.barMs?.takeIf { it > 0.0 }
    val beatMs = barMs?.div(4) ?: 1_000.0
    val origin = view.downbeatMs ?: 0.0
    var beat = origin + ceil((startMs - origin) / beatMs) * beatMs
    while (beat <= startMs + spanMs) {
        val downbeat = barMs != null && kotlin.math.abs((beat - origin) % barMs) < 1.0
        drawLine(
            StudioContent.copy(alpha = if (downbeat) 0.12f else 0.04f),
            Offset(x(beat), top), Offset(x(beat), bottom), 1.dp.toPx(),
        )
        beat += beatMs
    }
    drawSpectrum(view.outgoing, view, true, top, laneHeight, ::x)
    drawSpectrum(view.incoming, view, false, lowerTop, laneHeight, ::x)
    for ((laneTop, outgoing) in listOf(top to true, lowerTop to false)) {
        val path = Path()
        for (step in 0..96) {
            val p = step / 96f
            val gains = MixCurve.gains(p, view.shape)
            val gain = if (outgoing) gains.first else gains.second
            val px = boxLeft + (boxRight - boxLeft) * p
            val py = laneTop + laneHeight - gain * (laneHeight - 4.dp.toPx())
            if (step == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        val color = if (outgoing) CueColor else LevelColor
        drawPath(path, StudioSurface.copy(alpha = 0.85f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
    }
    for (px in listOf(boxLeft, boxRight)) {
        drawLine(CueColor.copy(alpha = 0.35f), Offset(px, top), Offset(px, bottom), 1.dp.toPx())
    }
    val seam = top + laneHeight + gap / 2
    drawLine(StudioContent.copy(alpha = 0.1f), Offset(0f, seam), Offset(size.width, seam), 1.dp.toPx())
    val headX = x(playheadMs).coerceIn(0f, size.width)
    drawLine(PlayheadColor.copy(alpha = 0.08f), Offset(headX, top), Offset(headX, bottom), 8.dp.toPx())
    drawLine(PlayheadColor, Offset(headX, top), Offset(headX, bottom), 1.5.dp.toPx(), StrokeCap.Round)
    drawCircle(StudioSurface, 5.dp.toPx(), Offset(headX, seam))
    drawCircle(PlayheadColor, 3.dp.toPx(), Offset(headX, seam))
    drawRoundRect(
        PlayheadColor, Offset(headX - 3.dp.toPx(), top - 2.dp.toPx()),
        Size(6.dp.toPx(), 4.dp.toPx()), CornerRadius(2.dp.toPx()),
    )
}

private fun DrawScope.drawSpectrum(
    deck: MixDeckView,
    view: MixView,
    outgoing: Boolean,
    top: Float,
    height: Float,
    x: (Double) -> Float,
) {
    val center = top + height / 2f
    drawLine(StudioContent.copy(alpha = 0.1f), Offset(0f, center), Offset(size.width, center), 1.dp.toPx())
    if (deck.bands.isEmpty()) return
    val half = (height / 2f - 6.dp.toPx()).coerceAtLeast(1f)
    fun px(index: Int): Float {
        val mediaMs = deck.bandsStartMs + index * BAND_COLUMN_MS.toDouble()
        return x(if (outgoing) mediaMs else view.incomingToOutgoing(mediaMs))
    }
    val columnWidth = (px(1) - px(0)).coerceAtLeast(0.000001f)
    val first = ((0f - px(0)) / columnWidth).toInt().coerceIn(deck.bands.indices)
    val last = ((size.width - px(0)) / columnWidth).toInt().coerceIn(deck.bands.indices)
    fun band(shift: Int, scale: Float, color: Color) {
        fun heightAt(index: Int): Float {
            val value = ((deck.bands[index] shr shift) and 0xFF) / 255f
            return (value * scale).coerceAtMost(1f) * half
        }
        val path = Path()
        // Hold real boundary samples when the context extends past the song, instead of tapering to a gap.
        path.moveTo(0f, center - heightAt(first))
        for (index in first..last) {
            val position = px(index)
            if (position > 0f && position < size.width) path.lineTo(position, center - heightAt(index))
        }
        path.lineTo(size.width, center - heightAt(last))
        path.lineTo(size.width, center + heightAt(last))
        for (index in last downTo first) {
            val position = px(index)
            if (position > 0f && position < size.width) path.lineTo(position, center + heightAt(index))
        }
        path.lineTo(0f, center + heightAt(first))
        path.close()
        drawPath(path, color.copy(alpha = 0.92f))
    }
    // The spectrum shows the source audio. Separate envelopes show the gain changes, so a cued deck never disappears.
    band(16, 1.1f, BassColor)
    band(8, 0.9f, MidColor)
    band(0, 0.42f, AirColor)
}

private fun formatDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
