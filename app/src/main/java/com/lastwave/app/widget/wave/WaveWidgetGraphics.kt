package com.lastwave.app.widget.wave

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
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

    fun strip(art: Bitmap?, color: Int, widthDp: Float, heightDp: Float, sideDp: Float): Bitmap {
        val scale = minOf(2f, 640f / widthDp.coerceAtLeast(1f), 256f / heightDp.coerceAtLeast(1f))
        val width = (widthDp * scale).toInt().coerceIn(1, 640)
        val height = (heightDp * scale).toInt().coerceIn(1, 256)
        val side = minOf(height.toFloat(), sideDp * scale)
        return cached("strip:${key(art)}:$color:$width:$height:$side") {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
                val radius = minOf(height / 4f, 20f * scale)
                val clip = Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) }
                canvas.clipPath(clip)
                canvas.drawColor(color)
                if (art != null && !art.isRecycled) {
                    // Crop directly into the flush left square; the outer clip rounds only its left corners.
                    val target = RectF(0f, 0f, side, height.toFloat())
                    val cropWidth = (art.width * side / height).toInt().coerceIn(1, art.width)
                    val left = (art.width - cropWidth) / 2
                    canvas.drawBitmap(art, android.graphics.Rect(left, 0, left + cropWidth, art.height),
                        target, paint(Color.WHITE))
                } else {
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

    fun rounded(art: Bitmap): Bitmap = masked(art, circular = false)
    fun circle(art: Bitmap): Bitmap = masked(art, circular = true)

    private fun masked(art: Bitmap, circular: Boolean): Bitmap = cached("mask:${key(art)}:$circular") {
        val size = minOf(art.width, art.height)
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { output ->
            val p = paint(Color.WHITE).apply { shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            val bounds = RectF(0f, 0f, size.toFloat(), size.toFloat())
            if (circular) Canvas(output).drawOval(bounds, p)
            else Canvas(output).drawRoundRect(bounds, size * 0.08f, size * 0.08f, p)
        }
    }

    fun circular(art: Bitmap?, accent: Int, progress: Float): Bitmap {
        // The ring is a static decorative wave, with only the played arc changing at ticker cadence.
        val bucket = (progress.coerceIn(0f, 1f) * 120).toInt()
        return cached("circular:${key(art)}:$accent:$bucket") {
            val size = 320
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { output ->
                val canvas = Canvas(output)
                canvas.drawCircle(160f, 160f, 158f, paint(0xF7101023.toInt()))
                canvas.drawCircle(160f, 160f, 156f, paint(0xFF27263E.toInt()).apply {
                    style = Paint.Style.STROKE; strokeWidth = 2f
                })
                val clip = Path().apply { addCircle(160f, 160f, 125f, Path.Direction.CW) }
                canvas.save()
                canvas.clipPath(clip)
                if (art != null && !art.isRecycled) {
                    canvas.drawBitmap(art, null, RectF(35f, 35f, 285f, 285f), paint(Color.WHITE))
                } else canvas.drawColor(WaveColorMath.mix(accent, 0xFF121125.toInt(), 0.7f))
                canvas.drawRect(35f, 35f, 285f, 285f, paint(Color.WHITE).apply {
                    shader = LinearGradient(0f, 120f, 0f, 285f,
                        intArrayOf(0x00000000, 0xE6000000.toInt()), null, Shader.TileMode.CLAMP)
                })
                canvas.restore()
                val wave = Path()
                for (index in 0..240) {
                    val angle = index / 240.0 * PI * 2 - PI / 2
                    val radius = 143f + (sin(angle * 9) * 3 + sin(angle * 5 + 1.4) * 2).toFloat()
                    val x = 160 + cos(angle).toFloat() * radius
                    val y = 160 + sin(angle).toFloat() * radius
                    if (index == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
                }
                wave.close()
                canvas.drawPath(wave, paint(0xFFA29EC8.toInt()).apply { style = Paint.Style.STROKE; strokeWidth = 1.7f })
                canvas.drawArc(RectF(15f, 15f, 305f, 305f), -90f, bucket * 3f, false,
                    paint(WaveColorMath.mix(accent, 0xFFE0D9FF.toInt(), 0.65f)).apply {
                        style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND
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
