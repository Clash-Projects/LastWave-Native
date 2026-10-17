package com.lastwave.app.ui.player.lyricsshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.lastwave.app.data.artwork.ArtworkNormalizer
import com.lastwave.app.ui.player.FastBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ArtworkResolution(
    val coverBitmap: Bitmap?,
    val blurredBitmap: Bitmap,
    val resolvedUrl: String?,
)

object ArtworkResolver {

    private const val LOAD_TIMEOUT_MS = 5000L

    /**
     * Resolves high-resolution album artwork following the app's established hierarchy:
     * 1. Track's own artworkUrl (if real image)
     * 2. Resolved ambient artwork URL from PlayerHost / ArtworkRepository
     * 3. Upgraded high-res URL via ArtworkNormalizer.upscaleYoutubeArtwork()
     * 4. File / content URIs for downloaded or local audio
     * 5. Fallback brand gradient if all else fails or times out
     *
     * Decodes with allowHardware(false) for software Canvas drawing and Palette access.
     */
    suspend fun resolveArtwork(
        context: Context,
        trackInfo: TrackInfo,
        themePrimaryColor: Int = ColorContrastHelper.BRAND_LIME,
    ): ArtworkResolution = withContext(Dispatchers.IO) {
        val targetUrl = determineArtworkUrl(trackInfo)

        val loadedBitmap = if (!targetUrl.isNullOrBlank()) {
            withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                runCatching {
                    val request = ImageRequest.Builder(context)
                        .data(targetUrl)
                        .allowHardware(false)
                        .size(1080)
                        .build()

                    val result = context.imageLoader.execute(request)
                    if (result is SuccessResult) {
                        (result.drawable as? BitmapDrawable)?.bitmap?.let { original ->
                            // Ensure it's a software bitmap
                            if (original.config == Bitmap.Config.HARDWARE) {
                                original.copy(Bitmap.Config.ARGB_8888, false)
                            } else {
                                original
                            }
                        }
                    } else null
                }.getOrNull()
            }
        } else null

        val blurred = if (loadedBitmap != null) {
            runCatching {
                FastBlur.blurArtwork(loadedBitmap, radius = 25, maxDimension = 180)
            }.getOrElse {
                createBrandFallbackGradient(themePrimaryColor)
            }
        } else {
            createBrandFallbackGradient(themePrimaryColor)
        }

        ArtworkResolution(
            coverBitmap = loadedBitmap,
            blurredBitmap = blurred,
            resolvedUrl = targetUrl,
        )
    }

    /**
     * Pure function implementing the exact resolution order:
     * 1. track.artworkUrl (if real)
     * 2. resolvedArtworkUrl (if real)
     * 3. upscaled YouTube URL
     */
    fun determineArtworkUrl(trackInfo: TrackInfo): String? {
        val raw = trackInfo.artworkUrl
        val resolved = trackInfo.resolvedArtworkUrl

        // Local files take highest precedence
        if (raw?.startsWith("file://") == true || raw?.startsWith("content://") == true) {
            return raw
        }

        val primary = when {
            ArtworkNormalizer.isRealImage(raw) -> raw
            ArtworkNormalizer.isRealImage(resolved) -> resolved
            else -> null
        }

        return primary?.let { ArtworkNormalizer.upscaleYoutubeArtwork(it) ?: it }
    }

    /**
     * Creates a smooth 1080x1350 brand fallback gradient when no artwork is available.
     */
    fun createBrandFallbackGradient(
        accentColor: Int,
        width: Int = 540,
        height: Int = 720,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val darkBase = ColorContrastHelper.DEFAULT_DARK
        val gradient = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(accentColor, darkBase, 0xFF080B0E.toInt()),
            floatArrayOf(0.0f, 0.55f, 1.0f),
            Shader.TileMode.CLAMP,
        )
        paint.shader = gradient
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return bitmap
    }
}
