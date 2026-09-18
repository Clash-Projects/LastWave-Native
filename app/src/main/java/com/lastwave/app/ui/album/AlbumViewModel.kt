package com.lastwave.app.ui.album

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.model.AlbumPageData
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.repository.AlbumRepository
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SettingsPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface AlbumUiState {
    data object Loading : AlbumUiState
    data class Success(val data: AlbumPageData) : AlbumUiState
    data class Error(val message: String) : AlbumUiState
}

@Immutable
data class AlbumSaveUiState(
    val isSaving: Boolean = false,
    val savedToLibrary: Boolean = false,
    val saveError: String? = null,
)

@HiltViewModel
class AlbumViewModel @Inject constructor(
    private val repository: AlbumRepository,
    private val playlistRepository: PlaylistRepository,
    private val musicPlayer: MusicPlayer,
    private val settingsPreferences: SettingsPreferences,
) : ViewModel() {

    val settings: StateFlow<MiscSettings> = settingsPreferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MiscSettings())

    private val _uiState = MutableStateFlow<AlbumUiState>(AlbumUiState.Loading)
    val uiState: StateFlow<AlbumUiState> = _uiState.asStateFlow()

    private val _saveUiState = MutableStateFlow(AlbumSaveUiState())
    val saveUiState: StateFlow<AlbumSaveUiState> = _saveUiState.asStateFlow()

    private var currentAlbumTitle: String = ""
    private var currentArtistName: String = ""
    private var currentBrowseId: String? = null
    private var loadJob: Job? = null

    fun loadAlbum(albumTitle: String, artistName: String = "", browseId: String? = null) {
        if (albumTitle == currentAlbumTitle && artistName == currentArtistName && browseId == currentBrowseId && _uiState.value is AlbumUiState.Success) {
            return
        }
        currentAlbumTitle = albumTitle
        currentArtistName = artistName
        currentBrowseId = browseId
        _saveUiState.value = AlbumSaveUiState()

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = AlbumUiState.Loading
            try {
                val data = repository.getAlbumDetails(albumTitle, artistName, browseId) { initialData ->
                    coroutineContext.ensureActive()
                    _uiState.value = AlbumUiState.Success(initialData)
                }
                coroutineContext.ensureActive()
                _uiState.value = AlbumUiState.Success(data)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_uiState.value !is AlbumUiState.Success) {
                    _uiState.value = AlbumUiState.Error(e.message ?: "Failed to load album details")
                }
            }
        }
    }

    fun playAll(startIndex: Int = 0) {
        val state = _uiState.value as? AlbumUiState.Success ?: return
        val tracks = state.data.tracks
        if (tracks.isNotEmpty()) {
            musicPlayer.playQueue(
                tracks,
                startIndex.coerceIn(0, tracks.lastIndex),
                sourceLabel = "${state.data.title} — ${state.data.artist}",
            )
        }
    }

    fun playShuffle() {
        val state = _uiState.value as? AlbumUiState.Success ?: return
        val tracks = state.data.tracks
        if (tracks.isNotEmpty()) {
            val shuffled = tracks.shuffled()
            musicPlayer.playQueue(
                shuffled,
                0,
                sourceLabel = "${state.data.title} — ${state.data.artist}",
            )
        }
    }

    /** One-tap save of the whole album to the library (issue #79) — the
     *  album equivalent of the feed playlist detail's "Save to library". */
    fun saveToLibrary() {
        val data = (_uiState.value as? AlbumUiState.Success)?.data ?: return
        if (data.tracks.isEmpty() || _saveUiState.value.isSaving || _saveUiState.value.savedToLibrary) return
        _saveUiState.value = AlbumSaveUiState(isSaving = true)
        viewModelScope.launch {
            try {
                playlistRepository.save(
                    title = data.title.ifBlank { "Album" },
                    subtitle = "${data.artist.ifBlank { "YouTube Music" }} • ${data.tracks.size} tracks",
                    mode = "custom",
                    tracks = data.tracks.map { it.toGeneratedTrack() },
                )
                _saveUiState.value = AlbumSaveUiState(savedToLibrary = true)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _saveUiState.value = AlbumSaveUiState(saveError = "Couldn't save album. Try again.")
            }
        }
    }

    private fun PlayableTrack.toGeneratedTrack() = GeneratedTrack(
        name = title,
        artist = artist,
        artworkUrl = artworkUrl,
        album = album,
        url = videoId?.let { "https://music.youtube.com/watch?v=$it" }.orEmpty(),
    )
}
