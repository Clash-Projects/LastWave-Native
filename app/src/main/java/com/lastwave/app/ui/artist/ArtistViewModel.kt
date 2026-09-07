package com.lastwave.app.ui.artist

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.model.ArtistPageData
import com.lastwave.app.data.repository.ArtistRepository
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.generate.MixLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import com.lastwave.app.data.artwork.ArtworkNormalizer
import javax.inject.Inject

import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SettingsPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

sealed interface ArtistUiState {
    data object Loading : ArtistUiState
    data class Success(val data: ArtistPageData) : ArtistUiState
    data class Error(val message: String) : ArtistUiState
}

@HiltViewModel
class ArtistViewModel @Inject constructor(
    private val repository: ArtistRepository,
    private val musicPlayer: MusicPlayer,
    private val mixLauncher: MixLauncher,
    private val settingsPreferences: SettingsPreferences,
) : ViewModel() {

    val settings: StateFlow<MiscSettings> = settingsPreferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MiscSettings())

    private val _uiState = MutableStateFlow<ArtistUiState>(ArtistUiState.Loading)
    val uiState: StateFlow<ArtistUiState> = _uiState.asStateFlow()

    private var currentArtistName: String = ""
    private var currentBrowseId: String? = null
    private var loadJob: Job? = null

    fun loadArtist(artistName: String, browseId: String? = null) {
        val cached = (_uiState.value as? ArtistUiState.Success)?.data
        if (artistName == currentArtistName && browseId == currentBrowseId &&
            (loadJob?.isActive == true || ArtworkNormalizer.isRealImage(cached?.artworkUrl))) {
            return
        }
        currentArtistName = artistName
        currentBrowseId = browseId

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = ArtistUiState.Loading
            try {
                val data = repository.getArtistDetails(artistName, browseId)
                coroutineContext.ensureActive()
                _uiState.value = ArtistUiState.Success(data)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = ArtistUiState.Error(e.message ?: "Failed to load artist details")
            }
        }
    }

    fun playAll(startIndex: Int = 0) {
        val state = _uiState.value as? ArtistUiState.Success ?: return
        val songs = state.data.topSongs
        if (songs.isNotEmpty()) {
            musicPlayer.playQueue(songs, startIndex.coerceIn(0, songs.lastIndex), sourceLabel = state.data.name)
        }
    }

    fun playShuffle() {
        val state = _uiState.value as? ArtistUiState.Success ?: return
        val songs = state.data.topSongs
        if (songs.isNotEmpty()) {
            val shuffled = songs.shuffled()
            musicPlayer.playQueue(shuffled, 0, sourceLabel = state.data.name)
        }
    }

    fun startArtistMix() {
        val state = _uiState.value as? ArtistUiState.Success ?: return
        val artistName = state.data.name
        val topSongs = state.data.topSongs
        val firstTrack = topSongs.firstOrNull()

        viewModelScope.launch {
            val radioTracks = try {
                repository.getArtistRadio(artistName, firstTrack)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }

            val finalQueue = if (radioTracks.isNotEmpty()) {
                radioTracks
            } else if (topSongs.isNotEmpty()) {
                topSongs.shuffled()
            } else {
                emptyList()
            }

            if (finalQueue.isNotEmpty()) {
                musicPlayer.playQueue(finalQueue, startIndex = 0, sourceLabel = "$artistName Radio")
            }
        }
    }
}
