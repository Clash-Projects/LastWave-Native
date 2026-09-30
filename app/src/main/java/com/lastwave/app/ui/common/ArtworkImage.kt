package com.lastwave.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.transform.Transformation
import com.lastwave.app.data.artwork.ArtworkNormalizer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Faithful port of home.js's art-loading behavior for a single row:
 *  - If the track already carries a real (non-placeholder) Last.fm image,
 *    show it directly — zero network calls, matching `!t.image` gating in
 *    enrichTracksWithArt()/​_enrichHomeArt().
 *  - Otherwise, request resolution (Last.fm track.getInfo -> iTunes) the
 *    moment this row enters composition, and recompose automatically the
 *    instant the shared resolved-map StateFlow reports a result — whether
 *    that's this frame or several seconds later.
 *  - Only shows the fallback icon once every provider has genuinely
 *    reported no art (resolved value == ""); shows nothing but the
 *    caller's own background while still resolving.
 */
@Composable
fun ArtworkImage(
    name: String,
    artist: String,
    embeddedUrl: String?,
    fallbackIcon: ImageVector,
    modifier: Modifier = Modifier,
    decodeSizePx: Int? = null,
    alignment: Alignment = Alignment.Center,
    transformations: List<Transformation> = emptyList(),
    artworkViewModel: ArtworkViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var embeddedFailed by remember(name, artist, embeddedUrl) { mutableStateOf(false) }
    if (ArtworkNormalizer.isRealImage(embeddedUrl) && !embeddedFailed) {
        val model = remember(embeddedUrl, decodeSizePx, transformations, context) {
            val builder = ImageRequest.Builder(context).data(embeddedUrl)
            if (decodeSizePx != null) builder.size(decodeSizePx)
            if (transformations.isNotEmpty()) builder.transformations(transformations)
            builder.build()
        }
        Box(modifier = modifier, contentAlignment = alignment) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = alignment,
                modifier = Modifier.fillMaxSize(),
                onError = { embeddedFailed = true },
            )
        }
        return
    }

    val key = remember(name, artist) { ArtworkNormalizer.cacheKey(name, artist) }
    // Collect ONLY this row's slot of the shared resolved-map. Collecting the
    // whole map meant every resolution anywhere recomposed every visible
    // artwork row; with distinctUntilChanged each row recomposes exactly once —
    // when its own URL resolves.
    val resolvedUrl by remember(key) {
        artworkViewModel.resolved.map { it[key] }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = artworkViewModel.resolved.value[key])
    var resolvedFailed by remember(key, resolvedUrl) { mutableStateOf(false) }
    var refreshRequested by remember(key) { mutableStateOf(false) }

    LaunchedEffect(key, embeddedFailed) {
        if (embeddedFailed) {
            refreshRequested = true
            artworkViewModel.refresh(name, artist)
        } else if (resolvedUrl.isNullOrBlank()) {
            artworkViewModel.resolve(name, artist)
        }
    }

    Box(modifier = modifier, contentAlignment = alignment) {
        when {
            !resolvedUrl.isNullOrBlank() && !resolvedFailed -> {
                val model = remember(resolvedUrl, decodeSizePx, transformations, context) {
                    val builder = ImageRequest.Builder(context).data(resolvedUrl)
                    if (decodeSizePx != null) builder.size(decodeSizePx)
                    if (transformations.isNotEmpty()) builder.transformations(transformations)
                    builder.build()
                }
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = alignment,
                    modifier = Modifier.fillMaxSize(),
                    onError = {
                        resolvedFailed = true
                        if (!refreshRequested) {
                            refreshRequested = true
                            artworkViewModel.refresh(name, artist)
                        }
                    },
                )
            }
            else -> Icon(
                fallbackIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (resolvedUrl == null) 0.35f else 0.6f),
                modifier = Modifier.fillMaxSize(0.42f),
            )
        }
    }
}
