package com.lastwave.app.ui.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.lastwave.app.data.local.db.SongPlayStatsEntity
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.repository.SongPlayStatsRepository
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.toPlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the leanback (Android TV) home: the D-pad home is deliberately thin —
 * now-playing hero plus the local listening history (top tracks / recently
 * played) and a remote-keyboard search. Everything plays through the same
 * [MusicPlayer] singleton the phone UI uses, so queue, radio and the media
 * session behave identically.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class TvHomeViewModel @Inject constructor(
    val musicPlayer: MusicPlayer,
    private val songPlayStats: SongPlayStatsRepository,
    private val innerTube: InnerTubeMusicApi,
) : ViewModel() {

    var topTracks by mutableStateOf<List<SongPlayStatsEntity>>(emptyList())
        private set
    var recentTracks by mutableStateOf<List<SongPlayStatsEntity>>(emptyList())
        private set

    private val searchQueryInternal = MutableStateFlow("")
    val searchQuery: MutableStateFlow<String> = searchQueryInternal

    var searchResults by mutableStateOf<List<YouTubeMusicTrack>>(emptyList())
        private set
    var isSearching by mutableStateOf(false)
        private set

    /** Kept as its own low-frequency flow so the 60 ms progress ticker never reaches the UI. */
    val shuffleEnabled = musicPlayer.state
        .map { it.shuffleEnabled }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val repeatMode = musicPlayer.state
        .map { it.repeatMode }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Player.REPEAT_MODE_OFF)

    init {
        viewModelScope.launch {
            val top = runCatching { songPlayStats.mostPlayedSince(days = 90, limit = TOP_ROW_LIMIT) }
                .getOrDefault(emptyList())
            val recent = runCatching { songPlayStats.recentlyPlayed(limit = TOP_ROW_LIMIT) }
                .getOrDefault(emptyList())
            topTracks = top
            // Recently played keeps its own identity: drop entries already shown as top tracks.
            recentTracks = recent.filterNot { row -> top.any { it.trackKey == row.trackKey } }
                .take(TOP_ROW_LIMIT)
        }
        viewModelScope.launch {
            searchQueryInternal
                .debounce(350)
                .distinctUntilChanged()
                .collect { query -> runSearch(query) }
        }
    }

    private suspend fun runSearch(query: String) {
        if (query.isBlank()) {
            searchResults = emptyList()
            isSearching = false
            return
        }
        isSearching = true
        val results = runCatching { innerTube.searchSongs(query, limit = 30) }
            .getOrDefault(emptyList())
        searchResults = results
        isSearching = false
    }

    fun onSearchQueryChange(query: String) {
        searchQueryInternal.value = query
    }

    fun playTrack(track: YouTubeMusicTrack) {
        musicPlayer.play(track.toPlayableTrack(), sourceLabel = TV_SOURCE_LABEL, startRadio = true)
    }

    fun playHistoryRow(entity: SongPlayStatsEntity) {
        musicPlayer.play(
            track = PlayableTrack(
                title = entity.title,
                artist = entity.artist,
                artworkUrl = entity.artworkUrl,
                videoId = entity.videoId,
            ),
            sourceLabel = TV_SOURCE_LABEL,
            startRadio = true,
        )
    }

    fun togglePlayPause() = musicPlayer.togglePlayPause()
    fun next() = musicPlayer.next()
    fun previous() = musicPlayer.previous()
    fun toggleShuffle() = musicPlayer.toggleShuffle()
    fun cycleRepeatMode() = musicPlayer.cycleRepeatMode()
    fun seekTo(positionMs: Long) = musicPlayer.seekTo(positionMs)

    companion object {
        private const val TOP_ROW_LIMIT = 24
        const val TV_SOURCE_LABEL = "LastWave TV"
    }
}
