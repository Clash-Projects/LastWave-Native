package com.lastwave.app.widget.wave

import kotlin.math.PI
import kotlin.math.sin

data class WavePoint(val x: Float, val y: Float)

object WaveProgress {
    fun fraction(positionMs: Long, durationMs: Long): Float = when {
        durationMs <= 0L -> 0f
        else -> (positionMs.coerceIn(0L, durationMs).toDouble() / durationMs.toDouble()).toFloat()
    }

    /** Played samples wave gently and meet the flat unplayed line without a jump. */
    fun points(
        widthPx: Int,
        heightPx: Int,
        progress: Float,
        sampleCount: Int = 64,
        cycles: Float = 3f,
    ): List<WavePoint> {
        if (widthPx <= 0 || heightPx <= 0 || sampleCount < 2) return emptyList()
        val fraction = progress.coerceIn(0f, 1f)
        val center = heightPx / 2f
        val amplitude = heightPx * 0.30f
        return (0 until sampleCount).map { index ->
            val normalizedX = index.toFloat() / (sampleCount - 1).toFloat()
            val playedT = if (fraction > 0f) (normalizedX / fraction).coerceIn(0f, 1f) else 1f
            val envelope = if (normalizedX <= fraction && fraction > 0f) {
                sin(PI * playedT).toFloat()
            } else {
                0f
            }
            val wave = if (normalizedX <= fraction) {
                sin(playedT * cycles * 2f * PI).toFloat() * amplitude * envelope
            } else {
                0f
            }
            WavePoint(normalizedX * widthPx, center + wave)
        }
    }
}
