package com.lastwave.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubePlaylistResult
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.data.playlist.PlaylistImportManager
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import com.lastwave.app.data.ytmusic.YtMusicLibraryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ImportTab(val titleRes: Int) {
    LIBRARY(com.lastwave.app.R.string.ytimp_tab_library),
    SEARCH(com.lastwave.app.R.string.ytimp_tab_search),
    LINK(com.lastwave.app.R.string.ytimp_tab_link),
    CSV(com.lastwave.app.R.string.ytimp_tab_csv),
}

data class YouTubeImportUiState(
    // Default to LINK so guests land on the paste-URL flow immediately (works
    // without a YouTube Music account). Switches to LIBRARY once connected.
    val selectedTab: ImportTab = ImportTab.LINK,
    val query: String = "",
    val directLink: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<YouTubePlaylistSummary> = emptyList(),
    /** Playlists from the connected YouTube Music account's own library. */
    val libraryPlaylists: List<YouTubePlaylistSummary> = emptyList(),
    val isLoadingLibrary: Boolean = false,
    val ytConnected: Boolean = false,
    val ytAccountName: String = "",
    val previewPlaylist: YouTubePlaylistResult? = null,
    val isPreviewLoading: Boolean = false,
    val selectedPlaylistIds: Set<String> = emptySet(),
    val isImporting: Boolean = false,
    val importProgress: String? = null,
    val errorMessage: String? = null,
    val importedCount: Int = 0,
    val csvFilename: String? = null,
    val isCsvImporting: Boolean = false,
)

@HiltViewModel
class YouTubePlaylistImportViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val innerTube: InnerTubeMusicApi,
    private val importManager: PlaylistImportManager,
    private val ytAuthManager: YtMusicAuthManager,
    private val ytMusicLibraryManager: YtMusicLibraryManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(YouTubeImportUiState())
    val uiState: StateFlow<YouTubeImportUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private var previewJob: Job? = null
    private var previewGeneration = 0L

    private fun cancelSearch() {
        searchGeneration++
        searchJob?.cancel()
        searchJob = null
    }

    private fun cancelPreview() {
        previewGeneration++
        previewJob?.cancel()
        previewJob = null
    }

    init {
        viewModelScope.launch {
            ytMusicLibraryManager.accountPlaylists.collect { playlists ->
                _uiState.update {
                    it.copy(libraryPlaylists = playlists, isLoadingLibrary = false)
                }
            }
        }
        viewModelScope.launch {
            ytAuthManager.connection.collect { connection ->
                val wasConnected = _uiState.value.ytConnected
                val nowConnected = connection.isConnected
                _uiState.update {
                    it.copy(ytConnected = nowConnected, ytAccountName = connection.accountName)
                }
                if (nowConnected && !wasConnected) {
                    // Freshly connected (or reconnected): land on the account's
                    // own library so the "select & import" flow is front and center.
                    if (_uiState.value.selectedTab in listOf(ImportTab.LINK, ImportTab.SEARCH) &&
                        _uiState.value.searchResults.isEmpty()
                    ) {
                        selectTab(ImportTab.LIBRARY)
                    }
                    loadLibrary()
                }
            }
        }
        // Initial popular playlists search for instant inspiration
        search("Top Hits 2024")
    }

    fun selectTab(tab: ImportTab) {
        if (_uiState.value.selectedTab == tab) return
        cancelSearch()
        cancelPreview()
        _uiState.update {
            it.copy(selectedTab = tab, errorMessage = null, isSearching = false, isPreviewLoading = false, previewPlaylist = null)
        }
        if (tab == ImportTab.LIBRARY && _uiState.value.libraryPlaylists.isEmpty() &&
            !_uiState.value.isLoadingLibrary
        ) {
            loadLibrary()
        }
    }

    fun onQueryChange(query: String) {
        if (_uiState.value.query == query) return
        cancelSearch()
        _uiState.update { it.copy(query = query, errorMessage = null, isSearching = false, searchResults = emptyList()) }
    }

    fun onDirectLinkChange(link: String) {
        if (_uiState.value.directLink == link) return
        cancelPreview()
        _uiState.update { it.copy(directLink = link, errorMessage = null, isPreviewLoading = false, previewPlaylist = null) }
    }

    fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun loadLibrary(force: Boolean = false) {
        if (!_uiState.value.ytConnected) return
        if (_uiState.value.isLoadingLibrary) return
        if (!force && _uiState.value.libraryPlaylists.isNotEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingLibrary = true, errorMessage = null) }
            try {
                ytMusicLibraryManager.refresh()
                _uiState.update { it.copy(isLoadingLibrary = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingLibrary = false,
                        errorMessage = appContext.getString(com.lastwave.app.R.string.ytimp_load_fail, e.localizedMessage ?: e.message),
                    )
                }
            }
        }
    }

    fun search(query: String = _uiState.value.query) {
        cancelSearch()
        val generation = searchGeneration
        _uiState.update { it.copy(isSearching = false, errorMessage = null) }
        if (query.isBlank()) return
        val clean = query.trim()

        // Check if query is a direct YouTube link or playlist ID
        val extractedId = innerTube.extractPlaylistId(clean)
        if (extractedId.startsWith("PL") || extractedId.startsWith("VL") || extractedId.startsWith("RDCLAK") ||
            PlaylistImportManager.isYtLikedId(extractedId) || clean.contains("list=")
        ) {
            loadPreview(extractedId)
            return
        }

        _uiState.update { it.copy(isSearching = true) }
        searchJob = viewModelScope.launch {
            try {
                val results = innerTube.searchPlaylists(clean)
                if (generation != searchGeneration) return@launch
                _uiState.update {
                    it.copy(
                        searchResults = results,
                        isSearching = false,
                        errorMessage = if (results.isEmpty()) "No playlists found for \"$clean\"" else null,
                    )
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation != searchGeneration) return@launch
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        errorMessage = e.localizedMessage ?: "Failed to search playlists",
                    )
                }
            }
        }
    }

    fun resolveDirectLink(link: String = _uiState.value.directLink) {
        if (link.isBlank()) return
        val clean = link.trim()
        val extractedId = innerTube.extractPlaylistId(clean)
        loadPreview(extractedId)
    }

    fun loadPreview(playlistIdOrUrl: String) {
        cancelPreview()
        val generation = previewGeneration
        _uiState.update { it.copy(isPreviewLoading = true, previewPlaylist = null, errorMessage = null) }
        previewJob = viewModelScope.launch {
            try {
                val result = innerTube.fetchPlaylist(playlistIdOrUrl)
                if (generation != previewGeneration) return@launch
                if (result != null && result.tracks.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            previewPlaylist = result,
                            isPreviewLoading = false,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isPreviewLoading = false,
                            errorMessage = if (_uiState.value.ytConnected) {
                                "Couldn't load this playlist. If it's not yours, check that sharing is enabled."
                            } else {
                                "Couldn't load playlist songs. Public/unlisted links work anonymously — connect a YouTube Music account in Settings to import your own."
                            },
                        )
                    }
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation != previewGeneration) return@launch
                _uiState.update {
                    it.copy(
                        isPreviewLoading = false,
                        errorMessage = e.localizedMessage ?: "Failed to load playlist preview",
                    )
                }
            }
        }
    }

    fun dismissPreview() {
        cancelPreview()
        _uiState.update { it.copy(previewPlaylist = null, isPreviewLoading = false) }
    }

    fun togglePlaylistSelection(playlistId: String) {
        _uiState.update {
            val current = it.selectedPlaylistIds
            val updated = if (playlistId in current) current - playlistId else current + playlistId
            it.copy(selectedPlaylistIds = updated)
        }
    }

    /** Select/deselect all across the ACTIVE tab's list (library or search). */
    fun selectAll(select: Boolean) {
        _uiState.update { current ->
            val activeList = when (current.selectedTab) {
                ImportTab.LIBRARY -> current.libraryPlaylists
                else -> current.searchResults
            }
            val activeIds = activeList.map { it.id }.toSet()
            val updated =
                if (select) current.selectedPlaylistIds + activeIds
                else current.selectedPlaylistIds - activeIds
            current.copy(selectedPlaylistIds = updated)
        }
    }

    fun importSelected(onSuccess: (List<SavedPlaylist>) -> Unit) {
        importPlaylists(_uiState.value.selectedPlaylistIds, _uiState.value.previewPlaylist, onSuccess)
    }

    /** The preview's action imports only the playlist the user is reviewing. */
    fun importPreview(onSuccess: (List<SavedPlaylist>) -> Unit) {
        val preview = _uiState.value.previewPlaylist ?: return
        importPlaylists(setOf(preview.id), preview, onSuccess)
    }

    private fun importPlaylists(
        selectedIds: Set<String>,
        preview: YouTubePlaylistResult?,
        onSuccess: (List<SavedPlaylist>) -> Unit,
    ) {
        if (selectedIds.isEmpty() || _uiState.value.isImporting) return
        _uiState.update { it.copy(isImporting = true, importProgress = "Starting import...") }

        viewModelScope.launch {
            val savedList = mutableListOf<SavedPlaylist>()
            val ownedIds = _uiState.value.libraryPlaylists.mapTo(mutableSetOf()) { it.id }
                .filterNotTo(mutableSetOf()) { PlaylistImportManager.isYtLikedId(it) }

            try {
                if (preview != null && selectedIds.contains(preview.id) && selectedIds.size == 1) {
                    val saved = if (PlaylistImportManager.isYtLikedId(preview.id)) {
                        importManager.importYtLikedIntoLikedSongs(preview)
                    } else if (preview.id in ownedIds) {
                        importManager.importOwnedYouTubePlaylist(preview)
                    } else {
                        importManager.importYouTubePlaylist(preview)
                    }
                    savedList.add(saved)
                } else {
                    var count = 0
                    // LM/VLLM/LL aliases all resolve to the same Liked Music
                    // collection — importing more than one stacks duplicates.
                    var likedImported = false
                    for (id in selectedIds) {
                        count++
                        _uiState.update { it.copy(importProgress = "Importing playlist $count of ${selectedIds.size}...") }
                        val playlistResult = innerTube.fetchPlaylist(id)
                        if (playlistResult != null && playlistResult.tracks.isNotEmpty()) {
                            val saved = if (PlaylistImportManager.isYtLikedId(playlistResult.id) ||
                                PlaylistImportManager.isYtLikedId(id)
                            ) {
                                if (likedImported) continue
                                likedImported = true
                                importManager.importYtLikedIntoLikedSongs(playlistResult)
                            } else if (playlistResult.id in ownedIds) {
                                importManager.importOwnedYouTubePlaylist(playlistResult)
                            } else {
                                importManager.importYouTubePlaylist(playlistResult)
                            }
                            savedList.add(saved)
                        }
                    }
                }

                _uiState.update {
                    it.copy(
                        isImporting = false,
                        importProgress = null,
                        importedCount = savedList.size,
                    )
                }
                onSuccess(savedList)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        importProgress = null,
                        errorMessage = appContext.getString(com.lastwave.app.R.string.ytimp_fail, e.localizedMessage ?: e.message),
                    )
                }
            }
        }
    }

    fun importCsv(
        inputStream: java.io.InputStream,
        filename: String,
        onSuccess: (SavedPlaylist) -> Unit,
    ) {
        val fileType = filename.substringAfterLast('.', "File").uppercase()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isCsvImporting = true,
                    csvFilename = filename,
                    importProgress = "Parsing & matching $fileType tracks...",
                    errorMessage = null,
                )
            }
            try {
                val (savedPlaylist, result) = inputStream.use {
                    importManager.importCsvStream(it, filename)
                }
                _uiState.update {
                    it.copy(
                        isCsvImporting = false,
                        importProgress = null,
                        importedCount = 1,
                    )
                }
                onSuccess(savedPlaylist)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isCsvImporting = false,
                        importProgress = null,
                        errorMessage = appContext.getString(com.lastwave.app.R.string.ytimp_type_fail, fileType, e.localizedMessage ?: e.message),
                    )
                }
            } catch (error: LinkageError) {
                _uiState.update {
                    it.copy(
                        isCsvImporting = false,
                        importProgress = null,
                        errorMessage = appContext.getString(com.lastwave.app.R.string.ytimp_type_rom, fileType),
                    )
                }
            }
        }
    }
}
