package com.lastwave.app.ui.newreleases

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.newreleases.NewReleasesRepository
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class NewReleasesUiState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val tracks: List<YouTubeMusicTrack> = emptyList(),
    val error: String? = null,
    val isRefreshing: Boolean = false,
)

@HiltViewModel
class NewReleasesViewModel @Inject constructor(
    private val repository: NewReleasesRepository,
    private val musicPlayer: MusicPlayer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewReleasesUiState())
    val uiState: StateFlow<NewReleasesUiState> = _uiState.asStateFlow()

    init {
        loadInitial()
    }

    fun loadInitial() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val initialTracks = repository.fetchInitialBatch()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        tracks = initialTracks,
                        error = if (initialTracks.isEmpty()) "No new releases available right now." else null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Couldn't load new releases. Check connection and tap to retry.",
                    )
                }
            }
        }
    }

    fun loadMore() {
        val current = _uiState.value
        if (current.isLoading || current.isLoadingMore || current.tracks.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            try {
                val more = repository.fetchNextBatch()
                _uiState.update {
                    it.copy(
                        isLoadingMore = false,
                        tracks = if (more.isNotEmpty()) it.tracks + more else it.tracks,
                    )
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                val refreshed = repository.fetchInitialBatch()
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        tracks = refreshed,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        error = e.message ?: "Failed to refresh new releases.",
                    )
                }
            }
        }
    }

    fun playTrack(index: Int) {
        val tracks = _uiState.value.tracks
        if (tracks.isEmpty() || index !in tracks.indices) return
        musicPlayer.playQueue(
            tracks = tracks.map { it.toPlayableTrack() },
            startIndex = index,
            sourceLabel = "New Releases",
        )
    }

    fun playAll() {
        val tracks = _uiState.value.tracks
        if (tracks.isEmpty()) return
        musicPlayer.playQueue(
            tracks = tracks.map { it.toPlayableTrack() },
            startIndex = 0,
            sourceLabel = "New Releases",
        )
    }

    fun shuffle() {
        val tracks = _uiState.value.tracks
        if (tracks.isEmpty()) return
        musicPlayer.playQueue(
            tracks = tracks.shuffled().map { it.toPlayableTrack() },
            startIndex = 0,
            sourceLabel = "New Releases",
        )
    }

    private fun YouTubeMusicTrack.toPlayableTrack(): PlayableTrack = PlayableTrack(
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        videoId = videoId.takeIf(String::isNotBlank),
    )
}
