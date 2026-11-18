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

    /** Official Spotify logo with soundwave curves. */
    val Spotify: ImageVector by lazy {
        ImageVector.Builder("Spotify", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = ink) {
                // Circle
                moveTo(12f, 2f)
                curveTo(6.477f, 2f, 2f, 6.477f, 2f, 12f)
                curveTo(2f, 17.523f, 6.477f, 22f, 12f, 22f)
                curveTo(17.523f, 22f, 22f, 17.523f, 22f, 12f)
                curveTo(22f, 6.477f, 17.523f, 2f, 12f, 2f)
                close()
                // Arc 3 (bottom)
                moveTo(16.562f, 16.425f)
                curveTo(16.382f, 16.72f, 16f, 16.812f, 15.705f, 16.632f)
                curveTo(13.357f, 15.197f, 10.402f, 14.872f, 6.921f, 15.669f)
                curveTo(6.584f, 15.746f, 6.248f, 15.536f, 6.171f, 15.199f)
                curveTo(6.094f, 14.862f, 6.304f, 14.526f, 6.641f, 14.449f)
                curveTo(10.45f, 13.578f, 13.719f, 13.944f, 16.355f, 15.567f)
                curveTo(16.65f, 15.747f, 16.742f, 16.13f, 16.562f, 16.425f)
                close()
                // Arc 2 (middle)
                moveTo(17.813f, 13.332f)
                curveTo(17.586f, 13.7f, 17.103f, 13.815f, 16.735f, 13.589f)
                curveTo(14.048f, 11.937f, 9.95f, 11.458f, 6.771f, 12.423f)
                curveTo(6.358f, 12.548f, 5.921f, 12.315f, 5.796f, 11.903f)
                curveTo(5.671f, 11.49f, 5.904f, 11.053f, 6.316f, 10.928f)
                curveTo(9.949f, 9.825f, 14.459f, 10.358f, 17.556f, 12.262f)
                curveTo(17.924f, 12.488f, 18.039f, 12.972f, 17.813f, 13.332f)
                close()
                // Arc 1 (top)
                moveTo(17.928f, 10.155f)
                curveTo(14.73f, 8.256f, 9.458f, 8.081f, 6.408f, 9.007f)
                curveTo(5.918f, 9.155f, 5.398f, 8.878f, 5.25f, 8.388f)
                curveTo(5.102f, 7.898f, 5.378f, 7.378f, 5.868f, 7.23f)
                curveTo(9.382f, 6.163f, 15.207f, 6.368f, 18.892f, 8.556f)
                curveTo(19.335f, 8.819f, 19.48f, 9.394f, 19.217f, 9.836f)
                curveTo(18.955f, 10.279f, 18.38f, 10.424f, 17.928f, 10.155f)
                close()
            }
        }.build()
    }

    /** Official Last.fm logo. */
    val LastFm: ImageVector by lazy {
        ImageVector.Builder("LastFm", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = ink) {
                moveTo(10.584f, 17.21f)
                lineTo(9.704f, 14.818f)
                curveTo(9.704f, 14.818f, 8.274f, 16.412f, 6.131f, 16.412f)
                curveTo(4.234f, 16.412f, 2.887f, 14.763f, 2.887f, 12.124f)
                curveTo(2.887f, 8.742f, 4.591f, 7.533f, 6.268f, 7.533f)
                curveTo(8.688f, 7.533f, 9.457f, 9.1f, 10.117f, 11.107f)
                lineTo(10.997f, 13.856f)
                curveTo(11.877f, 16.522f, 13.526f, 18.666f, 18.282f, 18.666f)
                curveTo(21.691f, 18.666f, 24f, 17.622f, 24f, 14.873f)
                curveTo(24f, 12.646f, 22.735f, 11.492f, 20.37f, 10.942f)
                lineTo(18.612f, 10.557f)
                curveTo(17.402f, 10.282f, 17.045f, 9.787f, 17.045f, 8.962f)
                curveTo(17.045f, 8.028f, 17.787f, 7.478f, 18.997f, 7.478f)
                curveTo(20.317f, 7.478f, 21.031f, 7.973f, 21.141f, 9.155f)
                lineTo(23.89f, 8.825f)
                curveTo(23.67f, 6.351f, 21.966f, 5.333f, 19.161f, 5.333f)
                curveTo(16.687f, 5.333f, 14.268f, 6.268f, 14.268f, 9.265f)
                curveTo(14.268f, 11.135f, 15.175f, 12.316f, 17.457f, 12.866f)
                lineTo(19.327f, 13.306f)
                curveTo(20.729f, 13.636f, 21.196f, 14.213f, 21.196f, 15.01f)
                curveTo(21.196f, 16.027f, 20.206f, 16.44f, 18.336f, 16.44f)
                curveTo(15.56f, 16.44f, 14.406f, 14.983f, 13.746f, 12.976f)
                lineTo(12.839f, 10.226f)
                curveTo(11.684f, 6.653f, 9.842f, 5.333f, 6.186f, 5.333f)
                curveTo(2.144f, 5.333f, 0f, 7.89f, 0f, 12.233f)
                curveTo(0f, 16.413f, 2.144f, 18.667f, 5.993f, 18.667f)
                curveTo(9.099f, 18.667f, 10.584f, 17.21f, 10.584f, 17.21f)
                close()
            }
        }.build()
    }
}
