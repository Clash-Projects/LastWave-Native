package com.lastwave.app.widget.wave

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
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
        val recentArt: Map<String, Bitmap> = emptyMap(),
        val nextArt: Map<String, Bitmap> = emptyMap(),
    )

    private const val ART_SIZE = 384
    private const val PALETTE_SIZE = 48
    private const val BLUR_SIZE = 192
    private const val THUMBNAIL_SIZE = 96
    private const val CACHE_SIZE = 8
    private const val THUMBNAIL_CACHE_SIZE = 16
    private val cache = object : LinkedHashMap<String, Result>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>?): Boolean = size > CACHE_SIZE
    }
    private val thumbnailCache = object : LinkedHashMap<String, Bitmap>(THUMBNAIL_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > THUMBNAIL_CACHE_SIZE
    }

    private data class Source(val bitmap: Bitmap, val owned: Boolean) {
        fun release() {
            if (owned && !bitmap.isRecycled) bitmap.recycle()
        }
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
        val source = loadSource(context, artworkUri, artworkPath, bitmap, ART_SIZE)
            ?: return@withContext Result(null, null, null)
        val square = cropSource(source, ART_SIZE)
            ?: return@withContext Result(null, null, null)
        val paletteBitmap = Bitmap.createScaledBitmap(square, PALETTE_SIZE, PALETTE_SIZE, true)
        val palette = try {
            runCatching { Palette.from(paletteBitmap).maximumColorCount(12).generate() }.getOrNull()
        } finally {
            paletteBitmap.recycle()
        }
        val swatch = palette?.vibrantSwatch?.rgb
            ?: palette?.dominantSwatch?.rgb
            ?: palette?.mutedSwatch?.rgb
        val backdrop = makeBackdrop(square, swatch)
        val result = Result(
            // Each layout applies its own strip, circle or rounded mask.
            art = square,
            backdrop = backdrop,
            // Dynamic layouts use a deliberately dark artwork scrim so their
            // typography remains readable across bright and mixed covers.
            colors = swatch?.let { WaveWidgetColors.derive(it, dark = true) },
        )
        synchronized(cache) { cache[key] = result }
        result
    }

    /** Each library row uses up to four small covers. Failed loads remain retryable. */
    suspend fun resolveRecent(context: Context, tracks: List<WaveQueueTrack>): Map<String, Bitmap> =
        withContext(Dispatchers.IO) {
            val result = LinkedHashMap<String, Bitmap>()
            for (track in tracks.filter { it.trackId.isNotBlank() }.distinctBy { it.trackId }.take(4)) {
                val uri = track.artworkUri?.takeIf(String::isNotBlank) ?: continue
                val key = "${track.trackId}|$uri"
                val cached = synchronized(thumbnailCache) { thumbnailCache[key] }
                if (cached != null && !cached.isRecycled) {
                    result[track.trackId] = cached
                    continue
                }
                val source = loadSource(context, uri, null, null, THUMBNAIL_SIZE) ?: continue
                val thumbnail = cropSource(source, THUMBNAIL_SIZE) ?: continue
                synchronized(thumbnailCache) { thumbnailCache[key] = thumbnail }
                result[track.trackId] = thumbnail
            }
            result
        }

    private suspend fun loadSource(
        context: Context,
        artworkUri: String?,
        artworkPath: String?,
        bitmap: Bitmap?,
        size: Int,
    ): Source? {
        bitmap?.takeIf { !it.isRecycled }?.let { return Source(it, owned = false) }
        artworkPath?.takeIf(String::isNotBlank)?.let { path ->
            val file = File(path)
            if (file.isFile) decodeSampledFile(path, size * 2)?.let { return Source(it, owned = true) }
        }
        val uri = artworkUri?.takeIf(String::isNotBlank) ?: return null
        val result = runCatching {
            context.imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(uri)
                    .size(size)
                    .allowHardware(false)
                    .build(),
            )
        }.getOrNull() as? SuccessResult
        val drawable = result?.drawable ?: return null
        // Keep the decoded aspect ratio so the crop never stretches a cover.
        val decoded = drawable.toBitmap(config = Bitmap.Config.ARGB_8888)
        val sharedBitmap = (drawable as? BitmapDrawable)?.bitmap
        return Source(decoded, owned = decoded !== sharedBitmap)
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
        val width = source.width * scale
        val height = source.height * scale
        val left = (size - width) / 2f
        val top = (size - height) / 2f
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { output ->
            Canvas(output).drawBitmap(
                source,
                null,
                RectF(left, top, left + width, top + height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
        }
    }

    private fun cropSource(source: Source, size: Int): Bitmap? {
        val software = if (source.bitmap.config == Bitmap.Config.HARDWARE) {
            source.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            source.bitmap
        }
        return try {
            software?.let { centerCrop(it, size) }
        } finally {
            if (software != null && software !== source.bitmap) software.recycle()
            source.release()
        }
    }

    private fun makeBackdrop(source: Bitmap, swatch: Int?): Bitmap {
        val tiny = Bitmap.createScaledBitmap(source, BLUR_SIZE, BLUR_SIZE, true)
        blur(tiny)
        val output = Bitmap.createBitmap(ART_SIZE, ART_SIZE, Bitmap.Config.ARGB_8888)
        val bounds = RectF(0f, 0f, ART_SIZE.toFloat(), ART_SIZE.toFloat())
        val corner = ART_SIZE * 0.10f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        try {
            Canvas(output).apply {
                // An opaque base keeps transparent covers from exposing the launcher wallpaper.
                paint.color = 0xFF252636.toInt()
                drawRoundRect(bounds, corner, corner, paint)
                paint.color = 0xFFFFFFFF.toInt()
                paint.shader = BitmapShader(tiny, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setScale(ART_SIZE.toFloat() / BLUR_SIZE, ART_SIZE.toFloat() / BLUR_SIZE) })
                }
                drawRoundRect(bounds, corner, corner, paint)
                // A faint bright glaze leaves the cover's upper colors visible.
                paint.shader = LinearGradient(
                    0f, 0f, ART_SIZE.toFloat(), ART_SIZE.toFloat(),
                    intArrayOf(0x28FFFFFF, 0x0CFFFFFF, (swatch ?: 0xFF17383C.toInt()).withAlpha(0x0C)),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP,
                )
                drawRoundRect(bounds, corner, corner, paint)
                // The reading area has a stronger scrim without flattening the top.
                paint.shader = LinearGradient(
                    0f, 0f, 0f, ART_SIZE.toFloat(),
                    intArrayOf(0x00070D14, 0x18070D14, 0x82070D14.toInt(), 0xC2070D14.toInt()),
                    floatArrayOf(0f, 0.4f, 0.72f, 1f),
                    Shader.TileMode.CLAMP,
                )
                drawRoundRect(bounds, corner, corner, paint)
                val rimWidth = ART_SIZE * 0.004f
                val rimBounds = RectF(bounds).apply { inset(rimWidth / 2f, rimWidth / 2f) }
                paint.apply {
                    style = Paint.Style.STROKE
                    strokeWidth = rimWidth
                    shader = LinearGradient(
                        0f, 0f, 0f, ART_SIZE.toFloat(),
                        0x55FFFFFF, 0x14FFFFFF,
                        Shader.TileMode.CLAMP,
                    )
                }
                drawRoundRect(rimBounds, corner, corner, paint)
            }
        } finally {
            tiny.recycle()
        }
        return output
    }

    /** A light frost softens the cover without losing its faces, lettering or detail. */
    private fun blur(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        val scratch = IntArray(pixels.size)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        repeat(2) {
            boxBlur(pixels, scratch, width, height, horizontal = true, radius = 1)
            boxBlur(scratch, pixels, width, height, horizontal = false, radius = 1)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun boxBlur(
        source: IntArray,
        target: IntArray,
        width: Int,
        height: Int,
        horizontal: Boolean,
        radius: Int,
    ) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val step = if (horizontal) 1 else width
        val window = radius * 2 + 1
        for (line in 0 until lines) {
            val start = if (horizontal) line * width else line
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val color = source[start + offset.coerceIn(0, length - 1) * step]
                alpha += color ushr 24
                red += (color ushr 16) and 0xFF
                green += (color ushr 8) and 0xFF
                blue += color and 0xFF
            }
            for (position in 0 until length) {
                target[start + position * step] = ((alpha / window) shl 24) or
                    ((red / window) shl 16) or ((green / window) shl 8) or (blue / window)
                val removed = source[start + (position - radius).coerceIn(0, length - 1) * step]
                val added = source[start + (position + radius + 1).coerceIn(0, length - 1) * step]
                alpha += (added ushr 24) - (removed ushr 24)
                red += ((added ushr 16) and 0xFF) - ((removed ushr 16) and 0xFF)
                green += ((added ushr 8) and 0xFF) - ((removed ushr 8) and 0xFF)
                blue += (added and 0xFF) - (removed and 0xFF)
            }
        }
    }

    private fun Int.withAlpha(alpha: Int): Int = (this and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
