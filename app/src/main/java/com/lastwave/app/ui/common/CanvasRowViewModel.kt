package com.lastwave.app.ui.common

import androidx.lifecycle.ViewModel
import com.lastwave.app.data.canvas.CanvasArtwork
import com.lastwave.app.data.canvas.CanvasRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Thin Hilt-scoped wrapper so TrackContextMenuSheet can query the
 * (singleton, LRU-cached) CanvasRepository without every caller needing to
 * thread it through manually.
 */
@HiltViewModel
class CanvasRowViewModel @Inject constructor(
    private val canvasRepository: CanvasRepository,
) : ViewModel() {
    fun cached(title: String, artist: String, trackId: String? = null): CanvasArtwork? =
        canvasRepository.cached(title, artist, trackId)

    fun hasResolved(title: String, artist: String, trackId: String? = null): Boolean =
        canvasRepository.hasResolved(title, artist, trackId)

    suspend fun resolve(
        title: String,
        artist: String,
        album: String? = null,
        trackId: String? = null,
        durationMs: Long? = null,
    ): CanvasArtwork? = canvasRepository.canvasFor(
        title = title,
        artist = artist,
        album = album,
        trackId = trackId,
        durationMs = durationMs,
    )
}
