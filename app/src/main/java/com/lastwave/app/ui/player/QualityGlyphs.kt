package com.lastwave.app.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Small format glyphs for the now-playing quality pill, drawn to sit on one
 * text line next to a label (24x24 grid, single colour, tinted by the Icon).
 *
 * - [Lossless]: a waveform inside an outlined frame.
 * - [HiResLossless]: the same waveform knocked out of a filled frame, so the
 *   higher tier reads at a glance without a second word.
 * - [Spatial]: a point with sound radiating to both sides (Dolby Atmos /
 *   spatial). Original artwork, not a trademarked logo.
 * - [Standard]: the bare waveform for lossy streams (AAC / Opus / MP3).
 */
internal object QualityGlyphs {

    private val ink = SolidColor(Color.Black)

    private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
        moveTo(l + rad, t)
        lineTo(r - rad, t)
        quadTo(r, t, r, t + rad)
        lineTo(r, b - rad)
        quadTo(r, b, r - rad, b)
        lineTo(l + rad, b)
        quadTo(l, b, l, b - rad)
        lineTo(l, t + rad)
        quadTo(l, t, l + rad, t)
        close()
    }

    /** Four bars centred on y = 12 (heights 4 / 8 / 6 / 3). */
    private fun PathBuilder.bars(xs: FloatArray = floatArrayOf(7f, 10.6f, 14.2f, 17.8f)) {
        val heights = floatArrayOf(4f, 8f, 6f, 3f)
        val w = 1.9f
        for (i in xs.indices) {
            val h = heights[i]
            val l = xs[i] - w / 2f
            roundRect(l, 12f - h / 2f, l + w, 12f + h / 2f, w / 2f)
        }
    }

    val Lossless: ImageVector by lazy {
        ImageVector.Builder("Lossless", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = ink,
                strokeLineWidth = 1.8f,
                strokeLineJoin = StrokeJoin.Round,
            ) { roundRect(2.4f, 5.4f, 21.6f, 18.6f, 3.6f) }
            path(fill = ink) { bars() }
        }.build()
    }

    val HiResLossless: ImageVector by lazy {
        ImageVector.Builder("HiResLossless", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = ink, pathFillType = PathFillType.EvenOdd) {
                roundRect(1.5f, 4.5f, 22.5f, 19.5f, 4.2f)
                bars()
            }
        }.build()
    }

    val Spatial: ImageVector by lazy {
        ImageVector.Builder("Spatial", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = ink) {
                moveTo(9.8f, 12f)
                arcTo(2.2f, 2.2f, 0f, true, true, 14.2f, 12f)
                arcTo(2.2f, 2.2f, 0f, true, true, 9.8f, 12f)
                close()
            }
            path(
                fill = null,
                stroke = ink,
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
            ) {
                // inner pair
                moveTo(8f, 8.6f)
                arcTo(4.6f, 4.6f, 0f, false, false, 8f, 15.4f)
                moveTo(16f, 8.6f)
                arcTo(4.6f, 4.6f, 0f, false, true, 16f, 15.4f)
                // outer pair
                moveTo(4.9f, 6.2f)
                arcTo(8.2f, 8.2f, 0f, false, false, 4.9f, 17.8f)
                moveTo(19.1f, 6.2f)
                arcTo(8.2f, 8.2f, 0f, false, true, 19.1f, 17.8f)
            }
        }.build()
    }

    /** Crossfade: one level fading out while the next fades in, in the frame. */
    val Crossfade: ImageVector by lazy {
        ImageVector.Builder("Crossfade", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = ink,
                strokeLineWidth = 1.8f,
                strokeLineJoin = StrokeJoin.Round,
            ) { roundRect(2.4f, 5.4f, 21.6f, 18.6f, 3.6f) }
            path(
                fill = null,
                stroke = ink,
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
            ) {
                // fade out: high on the left, easing down to the right
                moveTo(6f, 8.8f)
                curveTo(10f, 8.8f, 14f, 15.2f, 18f, 15.2f)
                // fade in: low on the left, easing up to the right
                moveTo(6f, 15.2f)
                curveTo(10f, 15.2f, 14f, 8.8f, 18f, 8.8f)
            }
        }.build()
    }

    /** AutoMix: two tracks blended into one, as interlocking rings in the frame. */
    val AutoMix: ImageVector by lazy {
        ImageVector.Builder("AutoMix", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = ink,
                strokeLineWidth = 1.8f,
                strokeLineJoin = StrokeJoin.Round,
            ) { roundRect(2.4f, 5.4f, 21.6f, 18.6f, 3.6f) }
            path(
                fill = null,
                stroke = ink,
                strokeLineWidth = 1.6f,
            ) {
                // left ring, centre (9.6, 12)
                moveTo(6.4f, 12f)
                arcTo(3.2f, 3.2f, 0f, true, true, 12.8f, 12f)
                arcTo(3.2f, 3.2f, 0f, true, true, 6.4f, 12f)
                close()
                // right ring, centre (14.4, 12)
                moveTo(11.2f, 12f)
                arcTo(3.2f, 3.2f, 0f, true, true, 17.6f, 12f)
                arcTo(3.2f, 3.2f, 0f, true, true, 11.2f, 12f)
                close()
            }
        }.build()
    }

    val Standard: ImageVector by lazy {
        ImageVector.Builder("Standard", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = ink) { bars(floatArrayOf(6.6f, 10.2f, 13.8f, 17.4f)) }
        }.build()
    }

    /** Music Haptics: phone silhouette with tactile acoustic vibration waves. */
    val MusicHaptics: ImageVector by lazy {
        ImageVector.Builder("MusicHaptics", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = ink,
                strokeLineWidth = 1.8f,
                strokeLineJoin = StrokeJoin.Round,
                strokeLineCap = StrokeCap.Round,
            ) {
                roundRect(7.5f, 3.5f, 16.5f, 20.5f, 2.2f)
            }
            path(
                fill = null,
                stroke = ink,
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
            ) {
                // Left haptic ripple
                moveTo(3.5f, 8.5f)
                curveTo(2f, 10.5f, 2f, 13.5f, 3.5f, 15.5f)
                // Right haptic ripple
                moveTo(20.5f, 8.5f)
                curveTo(22f, 10.5f, 22f, 13.5f, 20.5f, 15.5f)
            }
        }.build()
    }
}
