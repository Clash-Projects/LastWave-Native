package com.lastwave.app.ui.player.nowplaying

import android.graphics.drawable.BitmapDrawable
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.android.material.color.utilities.DynamicColor
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeContent
import com.lastwave.app.data.artwork.ArtworkNormalizer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.common.ArtworkViewModel
import com.lastwave.app.ui.common.LocalArtworkViewModel
import com.lastwave.app.ui.player.ambientPaletteCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * A dark Material 3 scheme built from the playing song's cover, so every role
 * (primary, containers, surfaces) follows the song. Until the cover is read, or
 * when a song has none, the app accent seeds it, so the player is never light.
 */
@Composable
internal fun rememberSongColorScheme(track: PlayableTrack): ColorScheme {
    val seed = rememberArtworkSeed(track) ?: MaterialTheme.colorScheme.primary
    return remember(seed) { songColorScheme(seed) }
}

/**
 * Content scheme: unlike the app-wide artwork theme (deliberately muted), it
 * keeps the cover's own chroma, which is what makes each song look different.
 */
internal fun songColorScheme(seed: Color): ColorScheme {
    val scheme = SchemeContent(Hct.fromInt(seed.toArgb()), true, 0.0)
    val roles = MaterialDynamicColors()
    fun DynamicColor.color() = Color(getArgb(scheme))
    return darkColorScheme(
        primary = roles.primary().color(),
        onPrimary = roles.onPrimary().color(),
        primaryContainer = roles.primaryContainer().color(),
        onPrimaryContainer = roles.onPrimaryContainer().color(),
        inversePrimary = roles.inversePrimary().color(),
        secondary = roles.secondary().color(),
        onSecondary = roles.onSecondary().color(),
        secondaryContainer = roles.secondaryContainer().color(),
        onSecondaryContainer = roles.onSecondaryContainer().color(),
        tertiary = roles.tertiary().color(),
        onTertiary = roles.onTertiary().color(),
        tertiaryContainer = roles.tertiaryContainer().color(),
        onTertiaryContainer = roles.onTertiaryContainer().color(),
        background = roles.background().color(),
        onBackground = roles.onBackground().color(),
        surface = roles.surface().color(),
        onSurface = roles.onSurface().color(),
        surfaceVariant = roles.surfaceVariant().color(),
        onSurfaceVariant = roles.onSurfaceVariant().color(),
        surfaceTint = roles.primary().color(),
        inverseSurface = roles.inverseSurface().color(),
        inverseOnSurface = roles.inverseOnSurface().color(),
        error = roles.error().color(),
        onError = roles.onError().color(),
        errorContainer = roles.errorContainer().color(),
        onErrorContainer = roles.onErrorContainer().color(),
        outline = roles.outline().color(),
        outlineVariant = roles.outlineVariant().color(),
        scrim = Color.Black,
        surfaceBright = roles.surfaceBright().color(),
        surfaceDim = roles.surfaceDim().color(),
        surfaceContainer = roles.surfaceContainer().color(),
        surfaceContainerHigh = roles.surfaceContainerHigh().color(),
        surfaceContainerHighest = roles.surfaceContainerHighest().color(),
        surfaceContainerLow = roles.surfaceContainerLow().color(),
        surfaceContainerLowest = roles.surfaceContainerLowest().color(),
    )
}

/**
 * The cover's lead colour. Reads and fills the classic player's palette cache,
 * so switching players never decodes the same cover twice.
 */
@Composable
private fun rememberArtworkSeed(track: PlayableTrack): Color? {
    val context = LocalContext.current
    val artworkViewModel: ArtworkViewModel = LocalArtworkViewModel.current ?: hiltViewModel()
    val key = remember(track.title, track.artist) { ArtworkNormalizer.cacheKey(track.title, track.artist) }
    val onlineUrl by remember(key) {
        artworkViewModel.resolved.map { it[key] }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = artworkViewModel.resolved.value[key])
    // Local songs: the cover embedded in the user's file wins over online art.
    val url = onlineUrl?.takeIf { it.isNotBlank() && track.artworkUrl?.startsWith("file://") != true }
        ?: ArtworkNormalizer.upscaleYoutubeArtwork(track.artworkUrl)?.takeIf { it.isNotBlank() }
    // Not keyed on the initial value: the previous song's colour stays until the
    // next cover is read, instead of flashing the fallback in between.
    val seed by produceState<Color?>(initialValue = null, url) {
        if (url == null) {
            value = null
            return@produceState
        }
        ambientPaletteCache.get(url)?.first?.takeIf { it.alpha > 0f }?.let {
            value = it
            return@produceState
        }
        val extracted = withContext(Dispatchers.Default) {
            runCatching {
                val request = ImageRequest.Builder(context).data(url).allowHardware(false).size(128).build()
                val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                    ?: return@runCatching null
                val palette = Palette.from(bitmap).clearFilters().generate()
                // Same swatch picks as FullPlayer, so the shared cache entries mean the same thing.
                val primary = palette.vibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch
                val secondary = palette.lightVibrantSwatch ?: palette.darkVibrantSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch
                val tertiary = palette.darkVibrantSwatch ?: palette.darkMutedSwatch ?: palette.dominantSwatch
                fun Palette.Swatch?.color() = this?.let { Color(it.rgb) } ?: Color.Transparent
                Triple(primary.color(), secondary.color(), tertiary.color())
                    .also { if (primary != null) ambientPaletteCache.put(url, it) }
            }.getOrNull()
        }
        extracted?.first?.takeIf { it.alpha > 0f }?.let { value = it }
    }
    return seed
}
