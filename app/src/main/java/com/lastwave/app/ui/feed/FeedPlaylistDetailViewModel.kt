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

    fun load(playlistId: String) {
        if (playlistId.isBlank()) {
            _uiState.value = FeedPlaylistDetailUiState.Error("This playlist is unavailable.")
            return
        }
        if (playlistId == currentPlaylistId && _uiState.value !is FeedPlaylistDetailUiState.Error) return

        currentPlaylistId = playlistId
        viewModelScope.launch {
            _uiState.value = FeedPlaylistDetailUiState.Loading

            // Instant warm render from cached feed if present
            if (playlistId == "yt_liked") {
                val cached = feedRepository.getCachedFeed()?.ytLikedSongs.orEmpty()
                if (cached.isNotEmpty()) {
                    _uiState.value = FeedPlaylistDetailUiState.Success(
                        YouTubePlaylistResult(
                            id = "yt_liked",
                            title = "Liked on YouTube",
                            author = "Your favorites",
                            artworkUrl = cached.firstOrNull()?.artworkUrl,
                            trackCount = cached.size,
                            tracks = cached,
                        )
                    )
                }
            } else if (playlistId == "yt_recent") {
                val cached = feedRepository.getCachedFeed()?.ytRecentSongs.orEmpty()
                if (cached.isNotEmpty()) {
                    _uiState.value = FeedPlaylistDetailUiState.Success(
                        YouTubePlaylistResult(
                            id = "yt_recent",
                            title = "Recently played",
                            author = "On YouTube",
                            artworkUrl = cached.firstOrNull()?.artworkUrl,
                            trackCount = cached.size,
                            tracks = cached,
                        )
                    )
                }
            }

            val result = when (playlistId) {
                "yt_liked" -> {
                    val pl = runCatching { innerTube.fetchPlaylist("LM") }.getOrNull()
                    if (pl != null && pl.tracks.isNotEmpty()) {
                        pl.copy(
                            id = "yt_liked",
                            title = pl.title.ifBlank { "Liked on YouTube" },
                            author = pl.author?.takeIf(String::isNotBlank) ?: "Your favorites",
                        )
                    } else {
                        val taste = runCatching {
                            innerTube.fetchTasteSignals(recentLimit = 0, likedLimit = 50, feedLimit = 0)
                        }.getOrNull()
                        val tracks = taste?.likedTracks.orEmpty().ifEmpty {
                            feedRepository.getCachedFeed()?.ytLikedSongs.orEmpty()
                        }
                        if (tracks.isNotEmpty()) {
                            YouTubePlaylistResult(
                                id = "yt_liked",
                                title = "Liked on YouTube",
                                author = "Your favorites",
                                artworkUrl = tracks.firstOrNull()?.artworkUrl,
                                trackCount = tracks.size,
                                tracks = tracks,
                            )
                        } else null
                    }
                }
                "yt_recent" -> {
                    val taste = runCatching {
                        innerTube.fetchTasteSignals(recentLimit = 50, likedLimit = 0, feedLimit = 0)
                    }.getOrNull()
                    val tracks = taste?.recentTracks.orEmpty().ifEmpty {
                        feedRepository.getCachedFeed()?.ytRecentSongs.orEmpty()
                    }
                    if (tracks.isNotEmpty()) {
                        YouTubePlaylistResult(
                            id = "yt_recent",
                            title = "Recently played",
                            author = "On YouTube",
                            artworkUrl = tracks.firstOrNull()?.artworkUrl,
                            trackCount = tracks.size,
                            tracks = tracks,
                        )
                    } else {
                        val pl = runCatching { innerTube.fetchPlaylist("FEmusic_history") }.getOrNull()
                        pl?.copy(
                            id = "yt_recent",
                            title = pl.title.ifBlank { "Recently played" },
                            author = pl.author?.takeIf(String::isNotBlank) ?: "On YouTube",
                        )
                    }
                }
                else -> runCatching { innerTube.fetchPlaylist(playlistId) }.getOrNull()
            }

            if (currentPlaylistId != playlistId) return@launch
            if (result != null && result.tracks.isNotEmpty()) {
                _uiState.value = FeedPlaylistDetailUiState.Success(result)
            } else if (_uiState.value !is FeedPlaylistDetailUiState.Success) {
                _uiState.value = when {
                    result == null -> FeedPlaylistDetailUiState.Error("Couldn't open this playlist. Check your connection and try again.")
                    else -> FeedPlaylistDetailUiState.Error("This playlist doesn't have any playable tracks right now.")
                }
            }
        }
    }

    fun saveToLibrary() {
        val current = _uiState.value as? FeedPlaylistDetailUiState.Success ?: return
        if (current.isSaving || current.savedToLibrary) return
        val playlistId = currentPlaylistId
        _uiState.value = current.copy(isSaving = true, saveError = null)
        viewModelScope.launch {
            try {
                val saved = importManager.importYouTubePlaylist(
                    current.playlist.copy(title = current.playlist.title.ifBlank { "YouTube playlist" }),
                )
                checkNotNull(playlistRepository.getById(saved.id)) { "Playlist could not be saved" }
                if (currentPlaylistId == playlistId) {
                    _uiState.value = current.copy(savedToLibrary = true)
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
