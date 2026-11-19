package com.lastwave.app.ui.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import dagger.hilt.android.qualifiers.ApplicationContext
import com.lastwave.app.data.local.db.SongPlayStatsEntity
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.repository.SongPlayStatsRepository
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.toPlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.lastwave.app.ui.player.LyricsUiState
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
    private val lyricsRepository: com.lastwave.app.data.lyrics.LyricsRepository,
    private val canvasRepository: com.lastwave.app.data.canvas.CanvasRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private var ambientCacheUrl: String? = null
    private var ambientCacheHex: String? = null

    /**
     * Artwork accent for the TV's ambient glow — memoized by URL. The home
     * root and the hero both derive from the same artwork, so the second
     * caller is an instant cache hit instead of a second decode, and the
     * decode itself is capped at 256 px (Palette quantises far below that),
     * keeping track changes cheap on low-RAM TV hardware. Swatch preference
     * matches the phone extractor exactly.
     */
    suspend fun extractAmbientAccent(artworkUrl: String): String? {
        if (artworkUrl == ambientCacheUrl) return ambientCacheHex
        val hex = withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(appContext)
                    .data(artworkUrl)
                    .size(256)
                    .allowHardware(false) // Palette reads pixels: software bitmap
                    .build()
                val result = appContext.imageLoader.execute(request)
                val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                    ?: return@withContext null
                val palette = Palette.from(bitmap).generate()
                val swatch = palette.vibrantSwatch
                    ?: palette.dominantSwatch
                    ?: palette.mutedSwatch
                    ?: return@withContext null
                val rgb = swatch.rgb
                "#%02X%02X%02X".format((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
            } catch (e: Exception) {
                null
            }
        }
        ambientCacheUrl = artworkUrl
        ambientCacheHex = hex
        return hex
    }

    var topTracks by mutableStateOf<List<SongPlayStatsEntity>>(emptyList())
        private set
    var recentTracks by mutableStateOf<List<SongPlayStatsEntity>>(emptyList())
        private set

    /** True once the first history read finishes — gates the empty-state text so it never flashes. */
    var historyLoaded by mutableStateOf(false)
        private set

    private val searchQueryInternal = MutableStateFlow("")
    val searchQuery: MutableStateFlow<String> = searchQueryInternal

    var searchResults by mutableStateOf<List<YouTubeMusicTrack>>(emptyList())
        private set
    var isSearching by mutableStateOf(false)
        private set

    /** BitChord-style suggestions: the YouTube Music home feed, chart fallback. */
    var homeSuggestions by mutableStateOf<List<YouTubeMusicTrack>>(emptyList())
        private set
    var suggestionsLoading by mutableStateOf(true)
        private set

    /** Lyrics for the current track, loaded through the shared phone repository. */
    var lyricsState by mutableStateOf<LyricsUiState>(LyricsUiState.Idle)
        private set
    private var lyricsRequestId = 0

    /** Motion artwork (Spotify Canvas & friends) for the current track. */
    var canvas by mutableStateOf<com.lastwave.app.data.canvas.CanvasArtwork?>(null)
        private set

    /** Spotify-TV style artist profile currently open, if any. */
    var artistName by mutableStateOf<String?>(null)
        private set
    var artistSongs by mutableStateOf<List<YouTubeMusicTrack>>(emptyList())
        private set
    var artistLoading by mutableStateOf(false)
        private set

    /** Opens the artist page: top songs come from the shared YT Music search. */
    fun openArtist(name: String) {
        if (name.isBlank() || artistName == name) return
        artistName = name
        artistLoading = true
        artistSongs = emptyList()
        viewModelScope.launch {
            artistSongs = runCatching { innerTube.searchSongs(name, limit = 20) }.getOrDefault(emptyList())
            artistLoading = false
        }
    }

    fun closeArtist() {
        artistName = null
        artistSongs = emptyList()
        artistLoading = false
    }

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
        historyLoaded = true
        }
        viewModelScope.launch {
            // Personalized YT Music home feed first; if it comes back empty
            // (signed out / blocked), fall back to the global charts, then to a
            // trending search — the TV home always opens with concrete music,
            // never a random pick and never an empty shelf.
            val personalized = runCatching { innerTube.fetchHomeSongs() }.getOrDefault(emptyList())
            val suggestions = personalized
                .ifEmpty { runCatching { innerTube.fetchCharts() }.getOrDefault(emptyList()) }
                .ifEmpty { runCatching { innerTube.searchSongs("trending songs", limit = 24) }.getOrDefault(emptyList()) }
            homeSuggestions = suggestions.distinctBy { it.videoId }.take(SUGGESTION_LIMIT)
            suggestionsLoading = false
        }
        viewModelScope.launch {
            // Track changes drive lyrics + motion artwork: hold the previous
            // song's words until the new fetch resolves so the TV screen never
            // flashes empty.
            musicPlayer.state
                .map { it.current?.videoId }
                .distinctUntilChanged()
                .collect {
                    loadLyrics(forceRefresh = false)
                    loadCanvas()
                }
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

    /**
     * Loads lyrics for whatever is playing through [com.lastwave.app.data.lyrics.LyricsRepository]
     * — the same multi-source stack the phone player uses (LRCLIB, Musixmatch,
     * YouTube, Apple Music, …), so TV gets synced lines when any provider has them.
     */
    fun loadLyrics(forceRefresh: Boolean) {
        val track = musicPlayer.state.value.current
        if (track == null) {
            lyricsRequestId++
            lyricsState = LyricsUiState.Idle
            return
        }
        val requestId = ++lyricsRequestId
        viewModelScope.launch {
            val spinner = launch {
                delay(400)
                if (requestId == lyricsRequestId && lyricsState !is LyricsUiState.Success) {
                    lyricsState = LyricsUiState.Loading
                }
            }
            val durationSeconds = track.durationMs?.takeIf { it > 0 }?.let { (it / 1000).toInt() }
            val result = runCatching {
                lyricsRepository.getLyrics(
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationSeconds = durationSeconds,
                    forceRefresh = forceRefresh,
                    wordByWord = false,
                    videoId = track.videoId,
                )
            }.getOrElse { error ->
                com.lastwave.app.data.lyrics.LyricsResult.Error(
                    error.message ?: "Couldn't load lyrics",
                )
            }
            spinner.join()
            if (requestId != lyricsRequestId) return@launch
            lyricsState = when (result) {
                com.lastwave.app.data.lyrics.LyricsResult.Empty -> LyricsUiState.Empty
                is com.lastwave.app.data.lyrics.LyricsResult.Success ->
                    if (result.lines.isEmpty() && result.plainLyrics.isNullOrBlank()) {
                        LyricsUiState.Empty
                    } else {
                        LyricsUiState.Success(
                            lines = result.lines,
                            isSynced = result.isSynced,
                            isWordSynced = result.isWordSynced,
                            plainLyrics = result.plainLyrics,
                            isInstrumental = result.isInstrumental,
                            source = result.source,
                        )
                    }
                is com.lastwave.app.data.lyrics.LyricsResult.Error ->
                    LyricsUiState.Error(result.message)
            }
        }
    }

    fun reloadLyrics() = loadLyrics(forceRefresh = true)

    /** Fetches motion artwork for whatever is playing (Spotify Canvas first). */
    private suspend fun loadCanvas() {
        canvas = null
        val track = musicPlayer.state.value.current ?: return
        canvas = runCatching {
            canvasRepository.canvasFor(track)
        }.getOrNull()
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
        private const val SUGGESTION_LIMIT = 24
        const val TV_SOURCE_LABEL = "LastWave TV"
    }
}
