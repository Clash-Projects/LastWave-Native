package com.lastwave.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.playlist.AppleMusicPlaylistImporter
import com.lastwave.app.data.playlist.ExternalImportResult
import com.lastwave.app.data.playlist.ExternalPlaylistLink
import com.lastwave.app.data.playlist.ExternalPlaylistResult
import com.lastwave.app.data.playlist.ExternalPlaylistSource
import com.lastwave.app.data.playlist.PlaylistImportManager
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.data.playlist.SpotifyPlaylistImporter
import com.lastwave.app.data.playlist.SpotifyUserPlaylist
import com.lastwave.app.data.spotify.SpotifyPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the "paste a Spotify / Apple Music link" importer.
 *
 * Mirrors [YouTubeImportUiState]: one immutable data class pushed through a
 * single [StateFlow], so the screen is a pure function of it.
 */
data class ExternalImportUiState(
    val link: String = "",
    val isLoading: Boolean = false,
    val preview: ExternalPlaylistResult? = null,
    val isImporting: Boolean = false,
    val progress: String? = null,
    val errorMessage: String? = null,
    val importedCount: Int = 0,
    val lastResult: ExternalImportResult? = null,
)

/**
 * Backs [ExternalPlaylistImportScreen]: detects the provider from the pasted
 * link, previews the public playlist's track list, and hands the link to
 * [PlaylistImportManager] for the actual match + save pass.
 */
@HiltViewModel
class ExternalPlaylistImportViewModel @Inject constructor(
    private val importManager: PlaylistImportManager,
    private val spotifyPlaylistImporter: SpotifyPlaylistImporter,
    private val appleMusicPlaylistImporter: AppleMusicPlaylistImporter,
    private val spotifyPreferences: SpotifyPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExternalImportUiState())
    val uiState: StateFlow<ExternalImportUiState> = _uiState.asStateFlow()

    // A late response must never attach the previous playlist to a new link.
    private var previewJob: Job? = null
    private var previewGeneration = 0L

    private fun cancelPreview() {
        previewGeneration++
        previewJob?.cancel()
        previewJob = null
    }

    val spotifyConnected: StateFlow<Boolean> = spotifyPreferences.isConnected
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _userPlaylists = MutableStateFlow<List<SpotifyUserPlaylist>>(emptyList())
    val userPlaylists: StateFlow<List<SpotifyUserPlaylist>> = _userPlaylists.asStateFlow()

    private val _loadingUserPlaylists = MutableStateFlow(false)
    val loadingUserPlaylists: StateFlow<Boolean> = _loadingUserPlaylists.asStateFlow()

    init {
        viewModelScope.launch {
            spotifyConnected.collect { connected ->
                if (connected) {
                    loadUserPlaylists()
                } else {
                    _userPlaylists.value = emptyList()
                }
            }
        }
    }

    fun loadUserPlaylists() {
        viewModelScope.launch {
            _loadingUserPlaylists.value = true
            val playlists = spotifyPlaylistImporter.fetchUserPlaylists()
            _userPlaylists.value = playlists
            _loadingUserPlaylists.value = false
        }
    }

    fun selectUserPlaylist(playlist: SpotifyUserPlaylist) {
        onLinkChange("https://open.spotify.com/playlist/${playlist.id}")
        loadPreview()
    }

    /**
     * Loads the user's Spotify Liked Songs collection into preview.
     */
    fun selectLikedSongs() {
        if (_uiState.value.isImporting) return
        cancelPreview()
        val generation = previewGeneration
        _uiState.update {
            it.copy(
                link = "https://open.spotify.com/collection/tracks",
                errorMessage = null,
                preview = null,
                isLoading = true,
                lastResult = null,
                importedCount = 0,
                progress = null,
            )
        }
        previewJob = viewModelScope.launch {
            try {
                val result = spotifyPlaylistImporter.fetchLikedSongs()
                if (generation != previewGeneration) return@launch
                val rows = result.rows
                if (rows.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            preview = null,
                            errorMessage = "No liked songs found in your Spotify account.",
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            preview = result,
                            errorMessage = null,
                            lastResult = null,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != previewGeneration) return@launch
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Couldn't read Spotify liked songs: ${e.localizedMessage ?: e.message}",
                    )
                }
            }
        }
    }

    fun onLinkChange(value: String) {
        if (_uiState.value.isImporting || value == _uiState.value.link) return
        cancelPreview()
        _uiState.update {
            it.copy(
                link = value,
                errorMessage = null,
                preview = null,
                isLoading = false,
                lastResult = null,
                importedCount = 0,
                progress = null,
            )
        }
    }

    /** Which provider the pasted link points at, or null when unrecognised. */
    fun detectSource(): ExternalPlaylistSource? = ExternalPlaylistLink.detect(_uiState.value.link)

    fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    /** Clears a loaded preview so the user can paste a different link. */
    fun clearPreview() {
        if (_uiState.value.isImporting) return
        cancelPreview()
        _uiState.update {
            it.copy(preview = null, isLoading = false, lastResult = null, importedCount = 0, progress = null, errorMessage = null)
        }
    }

    /**
     * Resolves the pasted link into a preview (title + track rows) without
     * saving anything — the user confirms with the Import CTA afterwards.
     */
    fun loadPreview() {
        if (_uiState.value.isImporting) return
        val raw = _uiState.value.link
        if (raw.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Paste a Spotify or Apple Music playlist link.") }
            return
        }
        if (ExternalPlaylistLink.isSpotifyLiked(raw)) {
            selectLikedSongs()
            return
        }
        val source = ExternalPlaylistLink.detect(raw)
        if (source == null) {
            _uiState.update { it.copy(errorMessage = "Paste a Spotify or Apple Music playlist link.") }
            return
        }

        cancelPreview()
        val generation = previewGeneration
        _uiState.update { it.copy(isLoading = true, preview = null, errorMessage = null, lastResult = null) }
        previewJob = viewModelScope.launch {
            try {
                val result: ExternalPlaylistResult? = when (source) {
                    ExternalPlaylistSource.SPOTIFY -> spotifyPlaylistImporter.fetchPlaylist(raw.trim())
                    ExternalPlaylistSource.APPLE_MUSIC -> appleMusicPlaylistImporter.fetchPlaylist(raw.trim())
                }
                if (generation != previewGeneration) return@launch
                val rows = result?.rows.orEmpty()
                if (rows.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            preview = null,
                            errorMessage = "No tracks found in that playlist. Make sure it is public or your account is connected.",
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, preview = result, errorMessage = null, lastResult = null)
                    }
                    val playlistId = ExternalPlaylistLink.extractId(raw.trim(), ExternalPlaylistSource.SPOTIFY)
                    if (!playlistId.isNullOrBlank()) {
                        _userPlaylists.update { currentList ->
                            currentList.map { pl ->
                                if (pl.id == playlistId) pl.copy(trackCount = rows.size) else pl
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != previewGeneration) return@launch
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Couldn't read that playlist: ${e.localizedMessage ?: e.message}",
                    )
                }
            }
        }
    }

    /**
     * Runs the real import: [PlaylistImportManager] re-reads the playlist,
     * matches every row to a playable track and saves the playlist.
     */
    fun import(onSuccess: (SavedPlaylist) -> Unit) {
        if (_uiState.value.isImporting || _uiState.value.isLoading || _uiState.value.preview == null) return
        val raw = _uiState.value.link
        if (raw.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Paste a Spotify or Apple Music playlist link.") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(isImporting = true, progress = "Importing playlist...", errorMessage = null)
            }
            try {
                val isLiked = ExternalPlaylistLink.isSpotifyLiked(raw)
                val (saved, result) = if (isLiked) {
                    importManager.importSpotifyLikedIntoLikedSongs()
                } else {
                    importManager.importExternalPlaylist(raw.trim())
                }
                val skipped = (result.totalRows - result.matchedCount).coerceAtLeast(0)
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        progress = "${result.matchedCount} imported, $skipped skipped",
                        importedCount = result.matchedCount,
                        lastResult = result,
                    )
                }
                onSuccess(saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        progress = null,
                        errorMessage = "Import failed: ${e.localizedMessage ?: e.message}",
                    )
                }
            }
        }
    }
}
