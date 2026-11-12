package com.lastwave.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts a representative accent color from a track's artwork for the
 * "Dynamic Now Playing Theme" setting (Settings → Appearance). Reuses the
 * app's single Coil ImageLoader (LastWaveApplication.newImageLoader()) via
 * context.imageLoader — the now-playing row on Home has almost always
 * already decoded this exact URL into that same memory/disk cache, so this
 * is normally a cache hit rather than a fresh network fetch.
 *
 * Runs entirely off the main thread. Any failure — network, decode, no
 * usable swatch — returns null so ThemeRepository can fall back to the
 * user's regular selected accent, per the feature's own fallback
 * requirement, rather than leaving a stale or broken color applied.
 */
@Singleton
class NowPlayingPaletteExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun extractAccentHex(artworkUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = ImageRequest.Builder(context)
                .data(artworkUrl)
                .size(128)
                .allowHardware(false) // Palette needs to read pixels from a software bitmap
                .build()
            val result = context.imageLoader.execute(request)
            val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                ?: return@withContext null

            extractArtworkAccent(bitmap)
        } catch (cancellation: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (e: Exception) {
            null
        }
    }
}

/** Palette ignores alpha, so hidden RGB in transparent artwork must not vote. */
internal fun extractArtworkAccent(bitmap: Bitmap): String? {
    val opaque = if (bitmap.hasAlpha()) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val visible = pixels.filter { Color.alpha(it) >= 224 }.toIntArray()
        if (visible.isEmpty()) return null
        Bitmap.createBitmap(visible, visible.size, 1, Bitmap.Config.ARGB_8888)
    } else null
    return try {
        val palette = Palette.from(opaque ?: bitmap)
            .clearFilters()
            .resizeBitmapArea(-1)
            .maximumColorCount(24)
            .generate()
        pickArtworkAccent(palette.swatches.map { it.rgb to it.population })
    } finally {
        opaque?.recycle()
    }
}

private data class ArtworkSwatch(val rgb: Int, val population: Int, val hsl: FloatArray)

/**
 * Count related shades together so a shaded blue sleeve does not lose to a
 * small flat red logo. RGB spread rejects compression tint in greys/shadows;
 * population, never saturation, chooses the representative colour family.
 */
internal fun pickArtworkAccent(swatches: List<Pair<Int, Int>>): String? {
    val samples = swatches.filter { it.second > 0 }.map { (rgb, population) ->
        ArtworkSwatch(rgb, population, FloatArray(3).also {
            androidx.core.graphics.ColorUtils.colorToHSL(rgb, it)
        })
    }
    val dominant = samples.maxByOrNull { it.population } ?: return null
    val total = samples.sumOf { it.population }.toFloat()
    fun monochrome(): String = hexOf(androidx.core.graphics.ColorUtils.HSLToColor(
        floatArrayOf(0f, 0f, dominant.hsl[2]),
    ))
    val chromatic = samples.filter {
        val spread = maxOf(Color.red(it.rgb), Color.green(it.rgb), Color.blue(it.rgb)) -
            minOf(Color.red(it.rgb), Color.green(it.rgb), Color.blue(it.rgb))
        spread >= 18 && it.hsl[1] >= 0.12f && it.hsl[2] in 0.035f..0.965f
    }
    if (chromatic.sumOf { it.population } / total < 0.12f) return monochrome()
    fun hueDistance(a: Float, b: Float): Float = kotlin.math.abs(a - b).let { minOf(it, 360f - it) }
    val family = chromatic.map { candidate ->
        chromatic.filter { hueDistance(candidate.hsl[0], it.hsl[0]) <= 18f }
    }.maxWithOrNull(compareBy<List<ArtworkSwatch>> { group -> group.sumOf { it.population } }
        .thenBy { group -> group.maxOf { it.population } }) ?: return monochrome()
    // Scattered coloured speckles must not collectively turn a grey sleeve chromatic.
    if (family.sumOf { it.population } / total < 0.08f) return monochrome()
    return hexOf(family.maxWith(compareBy<ArtworkSwatch> { it.population }.thenBy { it.rgb }).rgb)
}

private fun hexOf(rgb: Int) = "#%02X%02X%02X".format((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
