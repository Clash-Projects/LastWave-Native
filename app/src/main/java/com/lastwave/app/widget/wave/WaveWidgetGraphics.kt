package com.lastwave.app.widget.wave

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import java.util.LinkedHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal object WaveWidgetGraphics {
    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 16
    }
    private fun cached(key: String, create: () -> Bitmap): Bitmap = synchronized(cache) {
        cache[key]?.takeIf { !it.isRecycled } ?: create().also { cache[key] = it }
    }
    private fun key(art: Bitmap?) = art?.let { "${System.identityHashCode(it)}:${it.generationId}" } ?: "empty"
    private fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.color = color }

    fun strip(
        art: Bitmap?, color: Int, widthDp: Float, heightDp: Float, sideDp: Float,
        leftCornersOnly: Boolean = false,
    ): Bitmap {
        val scale = minOf(3f, 1024f / widthDp.coerceAtLeast(1f), 384f / heightDp.coerceAtLeast(1f))
        val width = (widthDp * scale).toInt().coerceIn(1, 1024)
        val height = (heightDp * scale).toInt().coerceIn(1, 384)
        val side = minOf(height.toFloat(), sideDp * scale)
        return cached("strip:${key(art)}:$color:$width:$height:$side:$leftCornersOnly") {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
                val radius = height * 0.14f
                val clip = Path().apply {
                    addRoundRect(bounds, if (leftCornersOnly)
                        floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
                    else FloatArray(8) { radius }, Path.Direction.CW)
                }
                canvas.clipPath(clip)
                canvas.drawColor(color)
                if (art != null && !art.isRecycled) {
                    // Crop directly into the flush left square; the outer clip rounds only its left corners.
                    val target = RectF(0f, 0f, side, height.toFloat())
                    canvas.drawBitmap(art, centerCrop(art, target.width() / target.height()),
                        target, paint(Color.WHITE))
                } else if (side > 0f) {
                    canvas.drawRect(0f, 0f, side, height.toFloat(), paint(WaveColorMath.mix(color, Color.WHITE, 0.12f)))
                    val line = paint(Color.WHITE).apply { strokeWidth = side / 12; strokeCap = Paint.Cap.ROUND }
                    for (index in 0..3) {
                        val x = side * (0.27f + index * 0.15f)
                        val extent = side * floatArrayOf(0.16f, 0.29f, 0.23f, 0.17f)[index]
                        canvas.drawLine(x, height / 2f - extent, x, height / 2f + extent, line)
                    }
                }
            }
        }
    }

    fun stripArtwork(art: Bitmap?, color: Int): Bitmap =
        strip(art, color, 384f, 384f, 384f, leftCornersOnly = true)

    fun rounded(art: Bitmap): Bitmap = masked(art, circular = false)
    fun circle(art: Bitmap): Bitmap = masked(art, circular = true)

    private fun centerCrop(art: Bitmap, aspect: Float): Rect {
        val width = minOf(art.width, (art.height * aspect).toInt().coerceAtLeast(1))
        val height = minOf(art.height, (art.width / aspect).toInt().coerceAtLeast(1))
        val left = (art.width - width) / 2
        val top = (art.height - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    private fun masked(art: Bitmap, circular: Boolean): Bitmap = cached("mask:${key(art)}:$circular") {
        val size = minOf(art.width, art.height)
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { output ->
            val bounds = RectF(0f, 0f, size.toFloat(), size.toFloat())
            val clip = Path().apply {
                if (circular) addOval(bounds, Path.Direction.CW)
                else addRoundRect(bounds, size * 0.08f, size * 0.08f, Path.Direction.CW)
            }
            val canvas = Canvas(output)
            canvas.clipPath(clip)
            canvas.drawBitmap(art, centerCrop(art, 1f), bounds, paint(Color.WHITE))
        }
    }

    fun circular(art: Bitmap?, accent: Int, progress: Float): Bitmap {
        // The ring is a static decorative wave, with only the played arc changing at ticker cadence.
        val bucket = (progress.coerceIn(0f, 1f) * 120).toInt()
        return cached("circular:${key(art)}:$accent:$bucket") {
            val size = 384
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { output ->
                val canvas = Canvas(output)
                val center = size / 2f
                canvas.drawCircle(center, center, 191f, paint(0xFF101023.toInt()))
                canvas.drawCircle(center, center, 190f, paint(0xFF343147.toInt()).apply {
                    style = Paint.Style.STROKE; strokeWidth = 1.5f
                })
                val clip = Path().apply { addCircle(center, center, 163f, Path.Direction.CW) }
                canvas.save()
                canvas.clipPath(clip)
                if (art != null && !art.isRecycled) {
                    canvas.drawBitmap(art, centerCrop(art, 1f), RectF(29f, 29f, 355f, 355f), paint(Color.WHITE))
                } else canvas.drawColor(WaveColorMath.mix(accent, 0xFF121125.toInt(), 0.7f))
                canvas.drawRect(29f, 29f, 355f, 355f, paint(Color.WHITE).apply {
                    shader = LinearGradient(0f, 178f, 0f, 355f,
                        intArrayOf(0x00000000, 0xDB000000.toInt()), null, Shader.TileMode.CLAMP)
                })
                canvas.restore()
                val wave = Path()
                val playedWave = Path()
                val playedSteps = bucket * 4
                for (index in 0..480) {
                    val angle = index / 480.0 * PI * 2 - PI / 2
                    val radius = 171f + (sin(angle * 8) * 4.5).toFloat()
                    val x = center + cos(angle).toFloat() * radius
                    val y = center + sin(angle).toFloat() * radius
                    if (index == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
                    if (index == 0) playedWave.moveTo(x, y)
                    else if (index <= playedSteps) playedWave.lineTo(x, y)
                }
                wave.close()
                canvas.drawPath(wave, paint(0xFF2D2A44.toInt()).apply {
                    style = Paint.Style.STROKE; strokeWidth = 9f
                })
                canvas.drawPath(wave, paint(0xFF9991C3.toInt()).apply {
                    style = Paint.Style.STROKE; strokeWidth = 2.4f
                })
                if (playedSteps > 0) canvas.drawPath(playedWave,
                    paint(WaveColorMath.mix(accent, 0xFFE0D9FF.toInt(), 0.75f)).apply {
                        style = Paint.Style.STROKE; strokeWidth = 2.8f; strokeCap = Paint.Cap.ROUND
                    })
            }
        }
    }

    fun playButton(): Bitmap = cached("play") {
        Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawCircle(36f, 36f, 35f, paint(0xFFF0E9FF.toInt()).apply {
                shader = LinearGradient(0f, 0f, 72f, 72f,
                    0xFFF9F6FF.toInt(), 0xFFD3CCEB.toInt(), Shader.TileMode.CLAMP)
            })
        }
    }

    fun progress(fraction: Float): Bitmap {
        val bucket = (fraction.coerceIn(0f, 1f) * 280).toInt()
        return cached("progress:$bucket") {
            Bitmap.createBitmap(288, 16, Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                canvas.drawLine(5f, 8f, 283f, 8f, paint(0x70FFFFFF).apply { strokeWidth = 4f; strokeCap = Paint.Cap.ROUND })
                val x = 5f + 278f * bucket / 280f
                canvas.drawLine(5f, 8f, x, 8f, paint(0xFFF6F4FF.toInt()).apply { strokeWidth = 4f; strokeCap = Paint.Cap.ROUND })
                canvas.drawCircle(x, 8f, 5.5f, paint(0xFFF6F4FF.toInt()))
            }
        }
    }
}
