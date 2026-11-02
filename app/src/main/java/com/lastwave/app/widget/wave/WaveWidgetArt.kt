package com.lastwave.app.widget.wave

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.File
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WaveWidgetArt {
    data class Result(
        val art: Bitmap?,
        val backdrop: Bitmap?,
        val colors: WaveWidgetColors?,
    )

    private const val ART_SIZE = 256
    private const val PALETTE_SIZE = 48
    private const val CACHE_SIZE = 8
    private val cache = object : LinkedHashMap<String, Result>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>?): Boolean = size > CACHE_SIZE
    }

    suspend fun resolve(
        context: Context,
        trackId: String,
        artworkUri: String?,
        artworkPath: String?,
        bitmap: Bitmap? = null,
    ): Result = withContext(Dispatchers.IO) {
        val key = buildString {
            append(trackId).append('|').append(artworkUri).append('|').append(artworkPath)
            bitmap?.takeIf { !it.isRecycled }?.let {
                append('|').append(System.identityHashCode(it)).append(':').append(it.width).append('x').append(it.height)
            }
        }
        synchronized(cache) { cache[key] }?.let { return@withContext it }
        val source = loadSource(context, artworkUri, artworkPath, bitmap)
            ?: return@withContext Result(null, null, null)
        val software = if (source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            source
        } ?: return@withContext Result(null, null, null)
        val square = centerCrop(software, ART_SIZE)
        val rounded = round(square, 34f)
        val paletteBitmap = Bitmap.createScaledBitmap(square, PALETTE_SIZE, PALETTE_SIZE, true)
        val palette = runCatching { Palette.from(paletteBitmap).maximumColorCount(12).generate() }.getOrNull()
        val swatch = palette?.vibrantSwatch?.rgb
            ?: palette?.dominantSwatch?.rgb
            ?: palette?.mutedSwatch?.rgb
        val backdrop = makeBackdrop(square, swatch)
        val result = Result(
            art = rounded,
            backdrop = backdrop,
            // Dynamic layouts use a deliberately dark artwork scrim so their
            // typography remains readable across bright and mixed covers.
            colors = swatch?.let { WaveWidgetColors.derive(it, dark = true) },
        )
        synchronized(cache) { cache[key] = result }
        result
    }

    private suspend fun loadSource(
        context: Context,
        artworkUri: String?,
        artworkPath: String?,
        bitmap: Bitmap?,
    ): Bitmap? {
        bitmap?.takeIf { !it.isRecycled }?.let { return it }
        artworkPath?.takeIf(String::isNotBlank)?.let { path ->
            val file = File(path)
            if (file.isFile) decodeSampledFile(path, ART_SIZE * 2)?.let { return it }
        }
        val uri = artworkUri?.takeIf(String::isNotBlank) ?: return null
        val result = runCatching {
            context.imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(uri)
                    .size(ART_SIZE)
                    .allowHardware(false)
                    .build(),
            )
        }.getOrNull() as? SuccessResult
        return result?.drawable?.toBitmap(ART_SIZE, ART_SIZE, Bitmap.Config.ARGB_8888)
    }

    private fun decodeSampledFile(path: String, maximumSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (maxOf(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > maximumSide * 2) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }

    private fun centerCrop(source: Bitmap, size: Int): Bitmap {
        val scale = size.toFloat() / minOf(source.width, source.height).coerceAtLeast(1)
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(size)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(size)
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val left = ((scaledWidth - size) / 2).coerceAtLeast(0)
        val top = ((scaledHeight - size) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(scaled, left, top, size, size)
    }

    private fun round(source: Bitmap, radius: Float): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.BitmapShader(source, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()), radius, radius, paint)
        return output
    }

    private fun makeBackdrop(source: Bitmap, swatch: Int?): Bitmap {
        val tiny = Bitmap.createScaledBitmap(source, 32, 32, true)
        val blurred = Bitmap.createScaledBitmap(tiny, ART_SIZE, ART_SIZE, true)
        val output = Bitmap.createBitmap(ART_SIZE, ART_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawBitmap(blurred, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
            val seed = swatch ?: 0xFF17383C.toInt()
            drawRect(
                0f,
                0f,
                ART_SIZE.toFloat(),
                ART_SIZE.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f,
                        0f,
                        ART_SIZE.toFloat(),
                        ART_SIZE.toFloat(),
                        intArrayOf(seed.withAlpha(0x66), 0xB8000000.toInt()),
                        null,
                        Shader.TileMode.CLAMP,
                    )
                },
            )
        }
        return round(output, 34f)
    }

    private fun Int.withAlpha(alpha: Int): Int = (this and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
