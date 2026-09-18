package com.lastwave.app.ui.feed

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.feed.FeedRepository
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistResult
import com.lastwave.app.data.playlist.PlaylistImportManager
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface FeedPlaylistDetailUiState {
    data object Loading : FeedPlaylistDetailUiState

    @Immutable
    data class Success(
        val playlist: YouTubePlaylistResult,
        val isSaving: Boolean = false,
        val savedToLibrary: Boolean = false,
        val saveError: String? = null,
        val isLoadingMore: Boolean = false,
        val loadError: String? = null,
    ) : FeedPlaylistDetailUiState

    data class Error(val message: String) : FeedPlaylistDetailUiState
}

@HiltViewModel
class FeedPlaylistDetailViewModel @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
    private val musicPlayer: MusicPlayer,
    private val importManager: PlaylistImportManager,
    private val playlistRepository: PlaylistRepository,
    private val feedRepository: FeedRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<FeedPlaylistDetailUiState>(FeedPlaylistDetailUiState.Loading)
    val uiState: StateFlow<FeedPlaylistDetailUiState> = _uiState.asStateFlow()

    private var currentPlaylistId: String? = null

    private var loadJob: Job? = null

    fun load(playlistId: String, force: Boolean = false) {
        if (playlistId.isBlank()) {
            _uiState.value = FeedPlaylistDetailUiState.Error("This playlist is unavailable.")
            return
        }
        if (!force && playlistId == currentPlaylistId && _uiState.value !is FeedPlaylistDetailUiState.Error) return
        loadJob?.cancel()
        val previous = if (playlistId == currentPlaylistId) _uiState.value as? FeedPlaylistDetailUiState.Success else null
        currentPlaylistId = playlistId
        _uiState.value = previous?.copy(isLoadingMore = true, loadError = null) ?: FeedPlaylistDetailUiState.Loading
        loadJob = viewModelScope.launch {
            val liked = playlistId == "yt_liked"
            val recent = playlistId == "yt_recent"
            val title = if (liked) "Liked on YouTube" else if (recent) "Recently played" else "Playlist"
            val author = if (liked) "Your favorites" else "YouTube Music"
            fun showTracks(tracks: List<YouTubeMusicTrack>) {
                coroutineContext.ensureActive()
                if (tracks.isEmpty()) return
                val state = _uiState.value as? FeedPlaylistDetailUiState.Success
                // Keep the larger warm snapshot until the full fetch catches up.
                if (state != null && state.playlist.tracks.size > tracks.size) return
                _uiState.value = FeedPlaylistDetailUiState.Success(
                    YouTubePlaylistResult(id = playlistId, title = title, author = author,
                        artworkUrl = tracks.firstOrNull()?.artworkUrl, trackCount = tracks.size, tracks = tracks),
                    isLoadingMore = true,
                )
            }
            if (liked) showTracks(feedRepository.getCachedFeed()?.ytLikedSongs.orEmpty())
            if (recent) showTracks(feedRepository.getCachedFeed()?.ytRecentSongs.orEmpty())
            try {
                val result = if (recent) {
                    val taste = innerTube.fetchTasteSignals(recentLimit = 50, likedLimit = 0, feedLimit = 0)
                    taste.recentTracks.takeIf { it.isNotEmpty() }?.let { tracks ->
                        YouTubePlaylistResult(id = playlistId, title = title, author = author,
                            artworkUrl = tracks.firstOrNull()?.artworkUrl, trackCount = tracks.size, tracks = tracks)
                    }
                } else {
                    innerTube.fetchPlaylist(if (liked) "LM" else playlistId, progressive = true, onPageLoaded = ::showTracks)
                } ?: throw java.io.IOException("Couldn't finish loading this playlist. Tap Retry.")
                coroutineContext.ensureActive()
                _uiState.value = FeedPlaylistDetailUiState.Success(
                    result.copy(id = playlistId, title = if (liked || recent) title else result.title.ifBlank { title },
                        author = result.author?.takeIf(String::isNotBlank) ?: author),
                    // Truncated continuation pages still show what loaded, with
                    // an inline retry for the remainder (fixes logged-out
                    // playlists that previously collapsed to a dead Error).
                    isLoadingMore = false,
                    loadError = if (result.isComplete) null else "Some tracks couldn't load. Retry.",
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val state = _uiState.value as? FeedPlaylistDetailUiState.Success
                val message = error.message ?: "Couldn't finish loading. Tap Retry."
                _uiState.value = state?.copy(isLoadingMore = false, loadError = message)
                    ?: FeedPlaylistDetailUiState.Error(message)
            }
        }
    }

    fun saveToLibrary() {
        val current = _uiState.value as? FeedPlaylistDetailUiState.Success ?: return
        if (current.isSaving || current.savedToLibrary) return
        if (current.isLoadingMore || current.loadError != null) {
            _uiState.value = current.copy(saveError = "Finish loading the playlist before saving it.")
            return
        }
        val playlistId = currentPlaylistId
        _uiState.value = current.copy(isSaving = true, saveError = null)
        viewModelScope.launch {
            try {
                val saved = importManager.importYouTubePlaylist(
                    current.playlist.copy(title = current.playlist.title.ifBlank { "YouTube playlist" }),
                )
                val persisted = runCatching { playlistRepository.getById(saved.id) }.getOrNull()
                if (currentPlaylistId == playlistId) {
                    _uiState.value = if (persisted != null) current.copy(savedToLibrary = true)
                    else current.copy(saveError = "Couldn't save playlist. Try again.")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (currentPlaylistId == playlistId) {
                    _uiState.value = current.copy(saveError = "Couldn't save playlist. Try again.")
                }
            }
        }
    }

    fun playFrom(index: Int) {
        val playlist = (_uiState.value as? FeedPlaylistDetailUiState.Success)?.playlist ?: return
        if (playlist.tracks.isEmpty()) return
        musicPlayer.playQueue(
            tracks = playlist.tracks.map { it.toPlayableTrack() },
            startIndex = index.coerceIn(0, playlist.tracks.lastIndex),
            sourceLabel = playlist.title.ifBlank { "Feed mix" },
        )
    }

    fun shuffle() {
        val playlist = (_uiState.value as? FeedPlaylistDetailUiState.Success)?.playlist ?: return
        if (playlist.tracks.isEmpty()) return
        musicPlayer.playQueue(
            tracks = playlist.tracks.shuffled().map { it.toPlayableTrack() },
            startIndex = 0,
            sourceLabel = playlist.title.ifBlank { "Feed mix" },
        )
    }

    private fun YouTubeMusicTrack.toPlayableTrack() = PlayableTrack(
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        videoId = videoId,
    )

}
