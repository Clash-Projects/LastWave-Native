package com.lastwave.app.ui.player.automix

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lastwave.app.data.artwork.ArtworkNormalizer
import com.lastwave.app.playback.automix.api.MixDeckView
import com.lastwave.app.playback.automix.api.MixView
import com.lastwave.app.ui.common.ArtworkViewModel
import com.lastwave.app.ui.common.LocalArtworkViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
internal fun PreloadAutoMixArtwork(view: MixView) {
    val artworkViewModel: ArtworkViewModel = LocalArtworkViewModel.current ?: hiltViewModel()
    LaunchedEffect(artworkViewModel, view.outgoing.title, view.outgoing.artist, view.incoming.title, view.incoming.artist) {
        for (deck in listOf(view.outgoing, view.incoming)) {
            if (deck.artworkUrl?.startsWith("file://") != true) artworkViewModel.resolve(deck.title, deck.artist)
        }
    }
}

@Composable
internal fun AutoMixArtwork(deck: MixDeckView, modifier: Modifier, videoId: String? = null) {
    val context = LocalContext.current
    val artworkViewModel: ArtworkViewModel = LocalArtworkViewModel.current ?: hiltViewModel()
    val key = remember(deck.title, deck.artist) { ArtworkNormalizer.cacheKey(deck.title, deck.artist) }
    val resolved by remember(key, artworkViewModel) {
        artworkViewModel.resolved.map { it[key] }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = artworkViewModel.resolved.value[key])
    var failed by remember(key) { mutableStateOf(emptySet<String>()) }
    var refreshed by remember(key) { mutableStateOf(false) }
    var retained by remember(key) { mutableStateOf<Painter?>(null) }
    val embedded = deck.artworkUrl?.takeIf(ArtworkNormalizer::isRealImage)
    val thumbnail = videoId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        ?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
    fun small(url: String?): String? = url?.takeIf(ArtworkNormalizer::isRealImage)?.let {
        ArtworkNormalizer.mobileDataArtwork(it, it) ?: it
    }
    val resolvedSmall = small(resolved)
    val url = listOfNotNull(small(embedded?.takeIf { it.startsWith("file://") }), resolvedSmall, small(embedded), thumbnail)
        .firstOrNull { it !in failed }

    LaunchedEffect(key, resolvedSmall, failed) {
        if (embedded?.startsWith("file://") == true && embedded !in failed) return@LaunchedEffect
        if (resolved == null) {
            artworkViewModel.resolve(deck.title, deck.artist)
        } else if (resolvedSmall != null && resolvedSmall in failed && !refreshed) {
            refreshed = true
            artworkViewModel.refresh(deck.title, deck.artist)
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val previous = retained
        if (previous == null) {
            Icon(Icons.Filled.MusicNote, null, Modifier.fillMaxSize(0.4f), tint = Color.White.copy(alpha = 0.45f))
        }
        if (url != null) {
            val request = remember(context, url) {
                // The popup is a thumbnail: fetch a smaller provider asset, not just a smaller decode of its master.
                ImageRequest.Builder(context).data(url).size(144).crossfade(120).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                placeholder = previous,
                error = previous,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { retained = it.painter },
                onError = { failed = failed + url },
            )
        } else if (previous != null) {
            Image(previous, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
