package com.lastwave.app.ui.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.local.audio.LocalAudioRepository
import com.lastwave.app.data.local.audio.LocalAudioTrack
import com.lastwave.app.data.local.audio.toPlayableTrack
import com.lastwave.app.playback.MusicPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LocalAudioSort(val label: String) {
    RECENT("Recently Added"),
    TITLE("Song Title"),
    ARTIST("Artist Name"),
    DURATION("Duration"),
}

data class LocalMusicUiState(
    val tracks: List<LocalAudioTrack> = emptyList(),
    val isLoading: Boolean = false,
    val hasPermission: Boolean = false,
    val searchQuery: String = "",
    val sortOrder: LocalAudioSort = LocalAudioSort.RECENT,
    val toastMessage: String? = null,
)

@HiltViewModel
class LocalMusicViewModel @Inject constructor(
    private val localAudioRepository: LocalAudioRepository,
    private val musicPlayer: MusicPlayer,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOrder = MutableStateFlow(LocalAudioSort.RECENT)
    val sortOrder: StateFlow<LocalAudioSort> = _sortOrder.asStateFlow()

    private val _hasPermission = MutableStateFlow(localAudioRepository.hasStoragePermission())
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private data class FilterConfig(val query: String, val sort: LocalAudioSort)

    val uiState: StateFlow<LocalMusicUiState> = combine(
        localAudioRepository.tracks,
        localAudioRepository.isLoading,
        _hasPermission,
        combine(_searchQuery, _sortOrder) { q, s -> FilterConfig(q, s) },
        _toastMessage,
    ) { rawTracks, loading, permission, filter, toast ->
        val filtered = if (filter.query.isBlank()) {
            rawTracks
        } else {
            val q = filter.query.lowercase().trim()
            rawTracks.filter {
                it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q) ||
                    it.album.lowercase().contains(q)
            }
        }

        val sorted = when (filter.sort) {
            LocalAudioSort.RECENT -> filtered.sortedByDescending { it.dateAddedSec }
            LocalAudioSort.TITLE -> filtered.sortedBy { it.title.lowercase() }
            LocalAudioSort.ARTIST -> filtered.sortedBy { it.artist.lowercase() }
            LocalAudioSort.DURATION -> filtered.sortedByDescending { it.durationMs }
        }

        LocalMusicUiState(
            tracks = sorted,
            isLoading = loading,
            hasPermission = permission,
            searchQuery = filter.query,
            sortOrder = filter.sort,
            toastMessage = toast,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LocalMusicUiState(isLoading = true, hasPermission = localAudioRepository.hasStoragePermission()),
    )

    init {
        checkPermissionAndLoad()
    }

    fun checkPermissionAndLoad() {
        val granted = localAudioRepository.hasStoragePermission()
        _hasPermission.value = granted
        if (granted) {
            viewModelScope.launch {
                localAudioRepository.refresh()
            }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        _hasPermission.value = granted
        if (granted) {
            viewModelScope.launch {
                localAudioRepository.refresh()
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortOrder(sort: LocalAudioSort) {
        _sortOrder.value = sort
    }

    fun playTrack(track: LocalAudioTrack, currentList: List<LocalAudioTrack>) {
        val playables = currentList.map { it.toPlayableTrack() }
        val startIndex = currentList.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        musicPlayer.playQueue(
            tracks = playables,
            startIndex = startIndex,
            sourceLabel = "Device Music",
            startShuffled = false,
        )
    }

    fun playAll(tracks: List<LocalAudioTrack>, shuffled: Boolean = false) {
        if (tracks.isEmpty()) return
        val playables = tracks.map { it.toPlayableTrack() }
        val startIndex = if (shuffled) playables.indices.random() else 0
        musicPlayer.playQueue(
            tracks = playables,
            startIndex = startIndex,
            sourceLabel = "Device Music",
            startShuffled = shuffled,
        )
    }

    fun playNext(track: LocalAudioTrack) {
        musicPlayer.playNext(track.toPlayableTrack())
        _toastMessage.value = "Playing next: ${track.title}"
    }

    fun addToQueue(track: LocalAudioTrack) {
        musicPlayer.addToQueue(track.toPlayableTrack())
        _toastMessage.value = "Added to queue: ${track.title}"
    }

    fun dismissToast() {
        _toastMessage.value = null
    }
}
