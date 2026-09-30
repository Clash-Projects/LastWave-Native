package com.lastwave.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Ensures album artwork is safely scaled and compressed before being passed
 * to Glance AppWidgets.
 *
 * Android Binder IPC has a strict 1 MB total transaction buffer limit.
 * Raw 5-10 MB album covers passed over Binder or large Bitmaps loaded into
 * RemoteViews trigger fatal `TransactionTooLargeException` or OutOfMemory crashes.
 *
 * This sanitizer downscales bitmaps to max 512x512 px and aggressively compresses
 * JPEG output to remain comfortably under 200 KiB.
 */
object ArtworkTransportSanitizer {

    const val MAX_DIMENSION_PX = 512
    const val MAX_BYTES = 200 * 1024 // 200 KiB safe threshold for Binder transactions
    private const val INITIAL_JPEG_QUALITY = 85
    private const val MIN_JPEG_QUALITY = 55
    private const val JPEG_QUALITY_STEP = 6

    fun scaleBitmapIfNeeded(bitmap: Bitmap, maxDimensionPx: Int = MAX_DIMENSION_PX): Bitmap {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return bitmap
        }
        val currentMax = max(bitmap.width, bitmap.height)
        if (currentMax <= maxDimensionPx) {
            return bitmap
        }
        val scale = maxDimensionPx.toFloat() / currentMax.toFloat()
        val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return bitmap.scale(targetWidth, targetHeight, filter = true)
    }

    fun compressToByteArray(
        bitmap: Bitmap,
        maxBytes: Int = MAX_BYTES,
    ): ByteArray? {
        if (bitmap.isRecycled) return null
        var quality = INITIAL_JPEG_QUALITY
        var lastValidBytes: ByteArray? = null

        while (quality >= MIN_JPEG_QUALITY) {
            val output = ByteArrayOutputStream()
            val encoded = runCatching {
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            }.getOrDefault(false)

            if (encoded) {
                val bytes = output.toByteArray()
                if (bytes.isNotEmpty()) {
                    lastValidBytes = bytes
                    if (bytes.size <= maxBytes) {
                        return bytes
                    }
                }
            }
            quality -= JPEG_QUALITY_STEP
        }
        return lastValidBytes
    }

    fun sanitizeAndSave(
        context: Context,
        source: Bitmap,
        fileName: String = "twotone_widget_art.jpg",
    ): String? {
        if (source.isRecycled) return null
        return runCatching {
            val scaled = scaleBitmapIfNeeded(source, MAX_DIMENSION_PX)
            val bytes = compressToByteArray(scaled, MAX_BYTES) ?: return null
            val file = File(context.cacheDir, fileName)
            file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()
    }
}
