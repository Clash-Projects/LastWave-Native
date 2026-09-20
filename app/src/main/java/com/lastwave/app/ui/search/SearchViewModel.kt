package com.lastwave.app.ui.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.download.PlaylistDownloadCoordinator
import com.lastwave.app.data.download.PlaylistDownloadStage
import com.lastwave.app.data.search.SearchHistoryRepository
import com.lastwave.app.data.search.SearchRepository
import com.lastwave.app.data.search.SearchResultItem
import com.lastwave.app.data.search.SearchTab
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SearchStatus { IDLE, LOADING, EMPTY, RESULTS }

@Immutable
data class SearchUiState(
    val query: String = "",
    val tab: SearchTab = SearchTab.TRACKS,
    val status: SearchStatus = SearchStatus.IDLE,
    val results: List<SearchResultItem> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val isShowingSuggestions: Boolean = false,
    val toastMessage: String? = null,
)

/**
 * YouTube Music & Last.fm search with live auto-complete suggestions,
 * persistent search history, debounced search, and multi-tab results.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository,
    private val historyRepository: SearchHistoryRepository,
    private val musicPlayer: MusicPlayer,
    private val playlistDownloadCoordinator: PlaylistDownloadCoordinator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var debounceJob: Job? = null
    private var suggestionsJob: Job? = null
    private var searchQueueJob: Job? = null
    private var lastIssuedQuery: String = ""
    private val requestedDownloadKeys = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            historyRepository.history.collect { history ->
                _uiState.update { it.copy(recentSearches = history) }
            }
        }
        viewModelScope.launch {
            playlistDownloadCoordinator.events.collect { event ->
                if (event.requestKey !in requestedDownloadKeys) return@collect
                val firstProgress = event.stage == PlaylistDownloadStage.DOWNLOADING &&
                    event.completed == 0 && event.failed == 0
                val terminal = event.stage in setOf(
                    PlaylistDownloadStage.COMPLETED,
                    PlaylistDownloadStage.PARTIAL,
                    PlaylistDownloadStage.FAILED,
                    PlaylistDownloadStage.CANCELLED,
                )
                if (firstProgress || terminal) {
                    _uiState.update { it.copy(toastMessage = event.message) }
                }
                if (terminal) requestedDownloadKeys.remove(event.requestKey)
            }
        }
    }

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query, isShowingSuggestions = query.isNotBlank()) }
        debounceJob?.cancel()
        suggestionsJob?.cancel()

        if (query.isBlank()) {
            _uiState.update {
                it.copy(
                    status = SearchStatus.IDLE,
                    results = emptyList(),
                    suggestions = emptyList(),
                    isShowingSuggestions = false,
                )
            }
            return
        }

        // Fast suggestions debounce (120ms)
        suggestionsJob = viewModelScope.launch {
            delay(120)
            val suggestions = repository.getSuggestions(query)
            if (_uiState.value.query == query) {
                _uiState.update { it.copy(suggestions = suggestions) }
            }
        }

        // Full search results debounce (400ms)
        debounceJob = viewModelScope.launch {
            delay(400)
            runSearch(query, saveToHistory = false)
        }
    }

    fun setTab(tab: SearchTab) {
        if (_uiState.value.tab == tab) return
        _uiState.update {
            it.copy(
                tab = tab,
                isShowingSuggestions = false,
                results = emptyList(),
                status = if (it.query.isBlank()) SearchStatus.IDLE else SearchStatus.LOADING,
            )
        }
        val q = _uiState.value.query
        if (q.isNotBlank()) {
            debounceJob?.cancel()
            suggestionsJob?.cancel()
            viewModelScope.launch { runSearch(q, saveToHistory = false) }
        }
    }

    fun searchNow() {
        val q = _uiState.value.query
        if (q.isBlank()) return
        executeSearch(q)
    }

    fun executeSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        debounceJob?.cancel()
        suggestionsJob?.cancel()
        historyRepository.add(trimmed)
        _uiState.update {
            it.copy(
                query = trimmed,
                isShowingSuggestions = false,
            )
        }
        viewModelScope.launch { runSearch(trimmed, saveToHistory = true) }
    }

    fun removeRecentSearch(query: String) {
        historyRepository.remove(query)
    }

    fun clearRecentSearches() {
        historyRepository.clear()
    }

    fun dismissSuggestions() {
        _uiState.update { it.copy(isShowingSuggestions = false) }
    }

    fun downloadPlaylist(item: SearchResultItem) {
        val playlistId = item.entityId?.takeIf(String::isNotBlank) ?: return
        val requestKey = playlistDownloadCoordinator.downloadRemotePlaylist(playlistId, item.name)
        requestedDownloadKeys += requestKey
        val message = playlistDownloadCoordinator.states.value[requestKey]?.message
            ?: "Preparing playlist download…"
        _uiState.update { it.copy(toastMessage = message) }
    }

    fun dismissToast() = _uiState.update { it.copy(toastMessage = null) }

    fun playResult(item: SearchResultItem) {
        searchQueueJob?.cancel()
        val tab = _uiState.value.tab
        when (tab) {
            SearchTab.TRACKS -> {
                val selected = PlayableTrack(
                    title = item.name,
                    artist = item.artist.orEmpty(),
                    album = item.subtitle,
                    artworkUrl = item.artworkUrl,
                    videoId = item.videoId,
                )
                // Start immediately. Similar song radio queue loads and extends infinitely.
                musicPlayer.play(selected, sourceLabel = "Search", startRadio = true)
            }
            SearchTab.ARTISTS, SearchTab.ALBUMS -> viewModelScope.launch {
                val tracks = runCatching { repository.songsFor(item) }.getOrDefault(emptyList())
                if (tracks.isNotEmpty()) {
                    musicPlayer.playQueue(tracks.map { track ->
                        PlayableTrack(
                            title = track.title,
                            artist = track.artist.takeUnless { it == "Unknown artist" } ?: item.artist ?: item.name,
                            album = track.album ?: if (tab == SearchTab.ALBUMS) item.name else null,
                            artworkUrl = track.artworkUrl ?: item.artworkUrl,
                            videoId = track.videoId,
                        )
                    }, sourceLabel = "Search")
                }
            }
            SearchTab.PLAYLISTS, SearchTab.USERS -> Unit
        }
    }

    private suspend fun runSearch(query: String, saveToHistory: Boolean) {
        val tab = _uiState.value.tab
        lastIssuedQuery = query
        _uiState.update { it.copy(status = SearchStatus.LOADING) }
        if (saveToHistory) {
            historyRepository.add(query)
        }
        try {
            val results = repository.search(tab, query)
            // Stale-response guard: discard if the user has typed something
            // new since this call was issued.
            if (lastIssuedQuery != query || _uiState.value.query != query || _uiState.value.tab != tab) return
            _uiState.update {
                it.copy(
                    status = if (results.isEmpty()) SearchStatus.EMPTY else SearchStatus.RESULTS,
                    results = results,
                )
            }
        } catch (e: Exception) {
            if (lastIssuedQuery != query || _uiState.value.query != query || _uiState.value.tab != tab) return
            _uiState.update { it.copy(status = SearchStatus.EMPTY, results = emptyList()) }
        }
    }

    fun clearQuery() {
        debounceJob?.cancel()
        suggestionsJob?.cancel()
        _uiState.update {
            it.copy(
                query = "",
                status = SearchStatus.IDLE,
                results = emptyList(),
                suggestions = emptyList(),
                isShowingSuggestions = false,
            )
        }
    }
}
