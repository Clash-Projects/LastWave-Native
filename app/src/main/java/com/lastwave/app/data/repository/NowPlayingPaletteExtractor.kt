package com.lastwave.app.data.repository

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Theme seed picked from a cover, plus its next most prominent colour of a different hue. */
data class ArtworkAccent(val seed: String, val second: String? = null)

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
    suspend fun extractAccent(artworkUrl: String): ArtworkAccent? = withContext(Dispatchers.IO) {
        try {
            val request = ImageRequest.Builder(context)
                .data(artworkUrl)
                .allowHardware(false) // Palette needs to read pixels from a software bitmap
                .build()
            val result = context.imageLoader.execute(request)
            val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                ?: return@withContext null

            // No default filters: blacks, whites and greys must be counted,
            // otherwise a B&W cover is judged only by its few tinted pixels.
            val palette = Palette.from(bitmap).clearFilters().maximumColorCount(24).generate()
            pickArtworkAccent(palette.swatches.map { it.rgb to it.population })
        } catch (e: Exception) {
            null
        }
    }
}

private const val MIN_SATURATION = 0.2f
/** Near-black/near-white pixels carry no reliable hue. */
private const val MIN_LIGHTNESS = 0.12f
private const val MAX_LIGHTNESS = 0.88f
/** Below this share of real colour the cover is treated as monochrome. */
private const val MIN_CHROMATIC_SHARE = 0.1f
/** Swatches smaller than this are logos, text or compression noise. */
private const val MIN_SWATCH_SHARE = 0.03f
/** A second colour closer in hue than this is just a shade of the seed. */
private const val MIN_SECOND_HUE_DELTA = 30

/**
 * Picks the theme seed from (rgb, population) swatches. Used to take
 * Palette's vibrant swatch first, so a tiny logo or a few noisy pixels set
 * the theme; and grey covers produced hue 0, i.e. red. Now the colour must
 * cover a real part of the artwork, dominance outranks vividness, and a
 * mostly achromatic cover returns its dominant grey, which
 * [com.lastwave.app.ui.theme.Md3SchemeBuilder.isAchromatic] turns into the
 * monochrome scheme.
 */
internal fun pickArtworkAccent(swatches: List<Pair<Int, Int>>): ArtworkAccent? {
    val total = swatches.sumOf { it.second }.toFloat()
    if (total <= 0f) return null
    val hsl = FloatArray(3)
    fun hslOf(rgb: Int): FloatArray = FloatArray(3).also { androidx.core.graphics.ColorUtils.colorToHSL(rgb, it) }
    val chromatic = swatches.filter { (rgb, _) ->
        hslOf(rgb).let { it[1] >= MIN_SATURATION && it[2] in MIN_LIGHTNESS..MAX_LIGHTNESS }
    }
    val dominant = swatches.maxBy { it.second }.first
    if (chromatic.sumOf { it.second } / total < MIN_CHROMATIC_SHARE) return ArtworkAccent(hexOf(grey(dominant, hsl)))
    val ranked = chromatic
        .filter { it.second / total >= MIN_SWATCH_SHARE }
        .sortedByDescending { (rgb, population) -> population * (0.5f + hslOf(rgb)[1]) }
        .map { it.first }
    val best = ranked.firstOrNull() ?: return ArtworkAccent(hexOf(grey(dominant, hsl)))
    val bestHue = hslOf(best)[0].toInt()
    val second = ranked.firstOrNull {
        com.lastwave.app.ui.theme.hueDelta(hslOf(it)[0].toInt(), bestHue) >= MIN_SECOND_HUE_DELTA
    }
    return ArtworkAccent(hexOf(best), second?.let(::hexOf))
}

private fun grey(rgb: Int, hsl: FloatArray): Int {
    androidx.core.graphics.ColorUtils.colorToHSL(rgb, hsl)
    hsl[1] = 0f
    return androidx.core.graphics.ColorUtils.HSLToColor(hsl)
}

private fun hexOf(rgb: Int) = "#%02X%02X%02X".format((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
