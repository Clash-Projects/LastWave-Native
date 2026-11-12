package com.lastwave.app.ui.podcast

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.podcast.PodcastEpisode
import com.lastwave.app.data.podcast.PodcastRepository
import com.lastwave.app.data.podcast.PodcastShelf
import com.lastwave.app.data.podcast.toPlayableTrack
import com.lastwave.app.playback.MusicPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@Immutable
data class PodcastsUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val shelves: List<PodcastShelf> = emptyList(),
    val error: String? = null,
    val query: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<PodcastEpisode> = emptyList(),
    val enabled: Boolean = true,
)

@HiltViewModel
class PodcastsViewModel @Inject constructor(
    private val repository: PodcastRepository,
    private val musicPlayer: MusicPlayer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PodcastsUiState())
    val uiState: StateFlow<PodcastsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            repository.enabled.collect { enabled ->
                _uiState.update { it.copy(enabled = enabled) }
                if (!enabled) {
                    loadJob?.cancel()
                    searchJob?.cancel()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            shelves = emptyList(),
                            searchResults = emptyList(),
                            isSearching = false,
                        )
                    }
                } else if (_uiState.value.shelves.isEmpty() && _uiState.value.error == null) {
                    load()
                }
            }
        }
    }

    fun load(forceRefresh: Boolean = false) {
        loadJob?.cancel()
        _uiState.update {
            it.copy(
                isLoading = it.shelves.isEmpty(),
                isRefreshing = forceRefresh,
                error = null,
            )
        }
        loadJob = viewModelScope.launch {
            try {
                val shelves = repository.home(forceRefresh)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        shelves = shelves,
                        error = if (shelves.isEmpty()) "No podcasts available right now." else null,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = error.message ?: "Couldn't load podcasts. Tap Retry.",
                    )
                }
            }
        }
    }

    fun refresh() = load(forceRefresh = true)

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(isSearching = false, searchResults = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _uiState.update { it.copy(isSearching = true) }
            try {
                val results = repository.searchEpisodes(query)
                _uiState.update { it.copy(isSearching = false, searchResults = results) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _uiState.update { it.copy(isSearching = false, searchResults = emptyList()) }
            }
        }
    }

    fun clearQuery() = onQueryChange("")

    /** Plays one episode and keeps the visible list as the queue behind it. */
    fun playEpisode(episode: PodcastEpisode, queue: List<PodcastEpisode> = listOf(episode)) {
        viewModelScope.launch {
            val startPositionMs = withContext(Dispatchers.IO) {
                repository.resumePositionMs(episode)
            }
            val tracks = queue.map { it.toPlayableTrack() }
            val index = queue.indexOfFirst { it.videoId == episode.videoId }.coerceAtLeast(0)
            musicPlayer.playPodcastQueue(
                tracks = tracks,
                startIndex = index,
                startPositionMs = startPositionMs,
                sourceLabel = episode.showTitle?.takeIf { it.isNotBlank() } ?: "Podcasts",
            )
        }
    }

    fun playLatest(shelf: PodcastShelf) {
        val episode = shelf.episodes.firstOrNull() ?: return
        playEpisode(episode, shelf.episodes)
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}
