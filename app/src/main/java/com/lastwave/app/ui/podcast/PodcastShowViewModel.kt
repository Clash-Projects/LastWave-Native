package com.lastwave.app.ui.podcast

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.podcast.PodcastEpisode
import com.lastwave.app.data.podcast.PodcastProgress
import com.lastwave.app.data.podcast.PodcastRepository
import com.lastwave.app.data.podcast.PodcastShow
import com.lastwave.app.data.podcast.toPlayableTrack
import com.lastwave.app.playback.MusicPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@Immutable
data class PodcastShowUiState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val show: PodcastShow? = null,
    val episodes: List<PodcastEpisode> = emptyList(),
    val hasMore: Boolean = false,
    val error: String? = null,
    val progress: Map<String, PodcastProgress> = emptyMap(),
)

@HiltViewModel
class PodcastShowViewModel @Inject constructor(
    private val repository: PodcastRepository,
    private val musicPlayer: MusicPlayer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PodcastShowUiState())
    val uiState: StateFlow<PodcastShowUiState> = _uiState.asStateFlow()

    private var showId: String? = null
    private var continuation: String? = null
    private var loadJob: Job? = null
    private var progressJob: Job? = null

    fun load(showId: String, forceRefresh: Boolean = false) {
        if (showId.isBlank()) return
        if (this.showId == showId && !forceRefresh && _uiState.value.episodes.isNotEmpty()) return
        this.showId = showId
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val page = repository.show(showId, forceRefresh)
                if (page == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Couldn't load this podcast right now.",
                        )
                    }
                    return@launch
                }
                continuation = page.continuation
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        show = page.show,
                        episodes = page.episodes,
                        hasMore = !page.continuation.isNullOrBlank(),
                        error = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: "Couldn't load this podcast. Tap Retry.",
                    )
                }
            }
        }
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            repository.progressForShow(showId).collect { progress ->
                _uiState.update { it.copy(progress = progress) }
            }
        }
    }

    fun loadMore() {
        val token = continuation ?: return
        if (_uiState.value.isLoadingMore) return
        _uiState.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            try {
                val page = repository.moreEpisodes(showId.orEmpty(), token)
                continuation = page.continuation
                _uiState.update {
                    it.copy(
                        isLoadingMore = false,
                        episodes = (it.episodes + page.episodes).distinctBy { episode -> episode.videoId },
                        hasMore = !page.continuation.isNullOrBlank(),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    fun playEpisode(episode: PodcastEpisode) {
        val queue = _uiState.value.episodes
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
                sourceLabel = _uiState.value.show?.title ?: "Podcasts",
            )
        }
    }
}
