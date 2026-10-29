package com.lastwave.app.ui.search.khinsider

import androidx.lifecycle.ViewModel
import com.lastwave.app.R
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.download.DownloadProgress
import com.lastwave.app.data.download.TrackDownloadManager
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.khinsider.KhAlbum
import com.lastwave.app.data.khinsider.KhFormat
import com.lastwave.app.data.khinsider.KhInsiderClient
import com.lastwave.app.data.khinsider.KhInsiderParser
import com.lastwave.app.data.khinsider.KhTrack
import com.lastwave.app.data.playlist.LIKED_SONGS_MODE
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class KhAlbumUiState(
    val albumId: String = "",
    val loading: Boolean = false,
    val album: KhAlbum? = null,
    val error: String? = null,
)

/** Per-track offline state shown in the album list. */
sealed interface KhTrackDownload {
    data object None : KhTrackDownload
    data class Running(val percent: Int) : KhTrackDownload
    data object Saved : KhTrackDownload
    data class Failed(val message: String) : KhTrackDownload
}

@HiltViewModel
class KhInsiderAlbumViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val client: KhInsiderClient,
    private val musicPlayer: MusicPlayer,
    private val playlistRepository: PlaylistRepository,
    private val downloadManager: TrackDownloadManager,
) : ViewModel() {
    private val _state = MutableStateFlow(KhAlbumUiState())
    val state: StateFlow<KhAlbumUiState> = _state.asStateFlow()
    val format: StateFlow<KhFormat> = client.format
    val downloadedPages: StateFlow<Set<String>> = client.downloadedPages
    val downloads: StateFlow<Map<String, DownloadProgress>> = downloadManager.downloads

    /** Local playlists an album can be added to (connected-account playlists can't hold KHInsider rows). */
    val playlists: StateFlow<List<SavedPlaylist>> = playlistRepository.playlists
        .map { all -> all.filter { it.remotePlaylistId == null && (it.mode == "custom" || it.mode == LIKED_SONGS_MODE) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    private var loadJob: Job? = null

    fun open(albumId: String, force: Boolean = false) {
        if (albumId.isBlank()) return
        val current = _state.value
        if (!force && current.albumId == albumId && (current.album != null || current.loading)) return
        loadJob?.cancel()
        _state.value = KhAlbumUiState(albumId = albumId, loading = true)
        loadJob = viewModelScope.launch {
            launch { client.refreshDownloads() }
            try {
                val album = client.album(albumId, forceRefresh = force)
                _state.value = KhAlbumUiState(albumId = albumId, album = album)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                _state.value = KhAlbumUiState(albumId = albumId, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun setFormat(format: KhFormat) = client.setFormat(format)

    fun play(startIndex: Int = 0, shuffled: Boolean = false) {
        val album = _state.value.album ?: return
        val queue = album.tracks.map { it.toPlayable(album) }
        if (queue.isEmpty()) return
        musicPlayer.playQueue(
            tracks = queue,
            startIndex = startIndex.coerceIn(0, queue.lastIndex),
            sourceLabel = SOURCE_LABEL,
            startShuffled = shuffled,
        )
    }

    fun playable(track: KhTrack): PlayableTrack? = _state.value.album?.let { track.toPlayable(it) }

    fun addAlbumToPlaylist(playlist: SavedPlaylist) = viewModelScope.launch {
        val album = _state.value.album ?: return@launch
        val added = runCatching { playlistRepository.addTracks(playlist.id, album.tracks.map { it.toGenerated(album) }) }
            .getOrDefault(-1)
        _messages.emit(
            when {
                added < 0 -> context.getString(R.string.khinsider_add_failed, playlist.title)
                added == 0 -> context.getString(R.string.khinsider_add_none, playlist.title)
                else -> context.getString(R.string.khinsider_add_done, added, playlist.title)
            },
        )
    }

    fun createPlaylistWithAlbum(title: String) = viewModelScope.launch {
        if (title.isBlank()) return@launch
        val playlist = runCatching { playlistRepository.createCustom(title) }.getOrNull() ?: run {
            _messages.emit(context.getString(R.string.khinsider_create_failed))
            return@launch
        }
        addAlbumToPlaylist(playlist).join()
    }

    fun downloadTrack(index: Int) {
        val album = _state.value.album ?: return
        val track = album.tracks.getOrNull(index) ?: return
        enqueue(album, track, index)
    }

    fun downloadAlbum() {
        val album = _state.value.album ?: return
        val saved = client.downloadedPages.value
        var queued = 0
        album.tracks.forEachIndexed { index, track ->
            if (track.pagePath !in saved) {
                enqueue(album, track, index)
                queued++
            }
        }
        _messages.tryEmit(
            if (queued == 0) context.getString(R.string.khinsider_album_saved)
            else context.getString(R.string.khinsider_album_queued, queued),
        )
    }

    private fun enqueue(album: KhAlbum, track: KhTrack, index: Int) {
        downloadManager.downloadTrack(
            title = track.name,
            artist = album.name,
            album = album.name,
            artworkUrl = album.coverUrl,
            year = album.year?.filter(Char::isDigit)?.take(4)?.takeIf { it.length == 4 },
            durationMs = track.durationSeconds?.takeIf { it > 0 }?.times(1_000L),
            directSource = KhInsiderParser.playbackUri(track.pagePath),
            trackNumber = track.number ?: (index + 1),
        )
    }

    fun downloadKey(track: KhTrack): String =
        TrackDownloadManager.makeDownloadKey(track.name, _state.value.album?.name.orEmpty())

    companion object {
        const val SOURCE_LABEL = "KHInsider"

        fun KhTrack.toPlayable(album: KhAlbum): PlayableTrack = PlayableTrack(
            title = name,
            // KHInsider lists no per-track artist: the game/album stands in.
            artist = album.name,
            album = album.name,
            artworkUrl = album.coverUrl,
            playbackUrl = KhInsiderParser.playbackUri(pagePath),
            durationMs = durationSeconds?.takeIf { it > 0 }?.times(1_000L),
        )

        fun KhTrack.toGenerated(album: KhAlbum): GeneratedTrack = GeneratedTrack(
            name = name,
            artist = album.name,
            artworkUrl = album.coverUrl,
            album = album.name,
            url = KhInsiderParser.playbackUri(pagePath),
        )
    }
}
