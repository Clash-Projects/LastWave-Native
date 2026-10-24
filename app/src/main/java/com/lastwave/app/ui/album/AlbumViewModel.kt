package com.lastwave.app.ui.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.favorites.FavoriteItem
import com.lastwave.app.data.favorites.FavoriteKind
import com.lastwave.app.data.favorites.FavoritesRepository
import com.lastwave.app.data.model.AlbumPageData
import com.lastwave.app.data.repository.AlbumRepository
import com.lastwave.app.playback.MusicPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

@HiltViewModel
class AlbumViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val repository: AlbumRepository,
    private val favoritesRepository: FavoritesRepository,
    private val musicPlayer: MusicPlayer,
    private val settingsPreferences: SettingsPreferences,
) : ViewModel() {

    val settings: StateFlow<MiscSettings> = settingsPreferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MiscSettings())

    private val _uiState = MutableStateFlow<AlbumUiState>(AlbumUiState.Loading)
    val uiState: StateFlow<AlbumUiState> = _uiState.asStateFlow()

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
                    _uiState.value = AlbumUiState.Error(e.message ?: appContext.getString(com.lastwave.app.R.string.alb_load_fail))
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

    private fun AlbumPageData.toFavorite() =
        FavoriteItem(title = title, artist = artist, browseId = browseId, artworkUrl = artworkUrl)

    val isFavorite: StateFlow<Boolean> = combine(_uiState, favoritesRepository.favorites(FavoriteKind.ALBUM)) { state, favorites ->
        val album = (state as? AlbumUiState.Success)?.data?.toFavorite() ?: return@combine false
        favorites.any { it.matches(album) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleFavorite() {
        val data = (_uiState.value as? AlbumUiState.Success)?.data ?: return
        viewModelScope.launch { favoritesRepository.toggle(FavoriteKind.ALBUM, data.toFavorite()) }
    }
}
