package com.lastwave.app.ui.feed

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.feed.FeedMix
import com.lastwave.app.data.feed.FeedArtist
import com.lastwave.app.data.feed.FeedData
import com.lastwave.app.data.feed.FeedQuickTile
import com.lastwave.app.data.feed.FeedRepository
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.model.RecentTrack
import com.lastwave.app.data.music.HomeChip
import com.lastwave.app.data.music.HomeShelf
import com.lastwave.app.data.music.HomeShelfItem
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.data.repository.RelatedGraphRepository
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class FeedUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val launchingRadio: String? = null,
    val error: String? = null,
    val avatarUrl: String? = null,
    val feedData: FeedData = FeedData(),
    val selectedChip: HomeChip? = null,
    val isChipLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val pagedShelves: List<HomeShelf> = emptyList(),
    val chipShelves: List<HomeShelf> = emptyList(),
    val chipContinuationToken: String? = null,
    val defaultContinuationToken: String? = null,
) {
    /** True when a mood/activity chip is active; synthetic sections are hidden in this mode. */
    val isChipActive: Boolean get() = selectedChip != null
    val homeChips: List<HomeChip> get() = feedData.homeChips
}

@HiltViewModel
class FeedViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val repository: FeedRepository,
    private val sessionPreferences: SessionPreferences,
    private val settingsPreferences: SettingsPreferences,
    private val musicPlayer: MusicPlayer,
    private val innerTube: InnerTubeMusicApi,
    private val relatedGraph: RelatedGraphRepository,
    private val ytAuth: YtMusicAuthManager,
    private val homeRepository: com.lastwave.app.data.repository.HomeRepository,
    private val discoverRepository: com.lastwave.app.data.discover.DiscoverRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()
    private val _discoveryTracks = MutableStateFlow<List<GeneratedTrack>>(emptyList())
    val discoveryTracks: StateFlow<List<GeneratedTrack>> = _discoveryTracks.asStateFlow()

    /** Ids of Home sections the user hid in Settings → Home sections. */
    val hiddenHomeSections: StateFlow<Set<String>> = settingsPreferences.settings
        .map { it.hiddenHomeSections }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Podcast surfaces are hidden the moment the user turns them off. */
    val podcastsEnabled: StateFlow<Boolean> = settingsPreferences.settings
        .map { it.podcastsEnabled }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    private var feedJob: Job? = null
    private var chipJob: Job? = null
    private var loadMoreJob: Job? = null
    private var requestGeneration: Long = 0L
    private var lastLoadedMillis: Long = 0L

    init {
        loadDiscovery()
        viewModelScope.launch {
            // The podcast shelf is part of the feed, so flipping the setting
            // has to re-run the feed: the cached copy still holds the shelf.
            settingsPreferences.settings
                .map { it.podcastsEnabled }
                .distinctUntilChanged()
                .drop(1)
                .collect { loadFeed() }
        }
        viewModelScope.launch {
            ytAuth.awaitLoadedConnection()
            combine(ytAuth.connection, sessionPreferences.session) { connection, session ->
                connection to session.username
            }.distinctUntilChanged().collect { (connection, _) ->
                feedJob?.cancel()
                val cached = repository.getCachedFeed()
                _uiState.value = FeedUiState(
                    isLoading = cached == null,
                    feedData = cached ?: FeedData(
                        isYtConnected = connection.isConnected,
                        ytAccountName = connection.accountName.takeIf { connection.isConnected },
                    ),
                )
                loadFeed()
            }
        }
        viewModelScope.launch {
            val stats = homeRepository.fetchStats().getOrNull()
            if (stats != null) {
                _uiState.update { it.copy(avatarUrl = stats.avatarUrl) }
            }
        }
    }

    fun loadDiscovery() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cached = discoverRepository.allowedCachedFeed()
                val tracks = if (cached.isNotEmpty()) cached.take(8) else discoverRepository.nextBatch(8)
                _discoveryTracks.value = tracks
            } catch (_: Exception) {
                // Non-destructive fallback if offline/failed
            }
        }
    }

    fun loadFeed() = fetchFeed(refreshing = false)

    fun refresh() {
        loadDiscovery()
        fetchFeed(refreshing = true)
    }

    fun onVisible() {
        if (feedJob?.isActive == true) return
        // ON_START fires on every return from a pushed screen (artist, album,
        // playlist...). Reloading the whole feed each time kept the tabs in
        // a near-constant loading state and hammered the network — only
        // refresh when there's nothing yet or the data is stale.
        val hasContent = _uiState.value.feedData.quickPicks.isNotEmpty() ||
            _uiState.value.feedData.topArtists.isNotEmpty() ||
            _uiState.value.feedData.newReleases.isNotEmpty()
        val stale = System.currentTimeMillis() - lastLoadedMillis > STALE_AFTER_MILLIS
        if (!hasContent || stale) refresh()
    }

    fun playInfiniteRadio() {
        val feed = _uiState.value.feedData
        (feed.quickPicks + feed.ytLikedSongs + feed.freshFinds).randomOrNull()?.let {
            playTrack(it, "Infinite Radio")
        }
    }

    fun playMix(mix: FeedMix) = playTrack(mix.seed, mix.title)

    private fun fetchFeed(refreshing: Boolean) {
        feedJob?.cancel()
        chipJob?.cancel()
        loadMoreJob?.cancel()
        requestGeneration++
        feedJob = viewModelScope.launch {
            val self = coroutineContext[Job]
            _uiState.update {
                it.copy(
                    isLoading = !refreshing || it.isLoading,
                    isRefreshing = refreshing,
                    error = null,
                    selectedChip = if (refreshing) null else it.selectedChip,
                    isChipLoading = if (refreshing) false else it.isChipLoading,
                    isLoadingMore = if (refreshing) false else it.isLoadingMore,
                    pagedShelves = if (refreshing) emptyList() else it.pagedShelves,
                    chipShelves = if (refreshing) emptyList() else it.chipShelves,
                    chipContinuationToken = if (refreshing) null else it.chipContinuationToken,
                    defaultContinuationToken = if (refreshing) null else it.defaultContinuationToken,
                )
            }
            try {
                val connection = ytAuth.awaitLoadedConnection()
                val username = sessionPreferences.session.value.username.takeIf(String::isNotBlank)
                val data = repository.loadFeed(username) { update ->
                    ensureActive()
                    if (ytAuth.connection.value == connection &&
                        sessionPreferences.session.value.username.takeIf(String::isNotBlank) == username
                    ) {
                        _uiState.update {
                            it.copy(
                                feedData = update,
                                defaultContinuationToken = update.homeContinuationToken ?: it.defaultContinuationToken,
                                isLoading = false,
                            )
                        }
                    }
                }
                ensureActive()
                if (ytAuth.connection.value != connection ||
                    sessionPreferences.session.value.username.takeIf(String::isNotBlank) != username
                ) return@launch
                val stats = homeRepository.fetchStats().getOrNull()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        feedData = data,
                        defaultContinuationToken = data.homeContinuationToken,
                        error = null,
                        avatarUrl = stats?.avatarUrl ?: it.avatarUrl,
                    )
                }
                lastLoadedMillis = System.currentTimeMillis()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                ensureActive()
                _uiState.update {
                    it.copy(isLoading = false, isRefreshing = false, error = appContext.getString(com.lastwave.app.R.string.feed_update_error))
                }
            } finally {
                // Every exit above the success path — cancellation mid-load
                // and the changed-identity guard — used to leave isRefreshing
                // true, and with it a pull-to-refresh spinner that spins over
                // fully-loaded content. Only the job that still owns the state
                // may clear it, so a newer feed job's flags are never reset.
                if (feedJob === self) {
                    _uiState.update { it.copy(isRefreshing = false) }
                }
            }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    /**
     * Toggles the selected mood/activity [chip].
     * Tapping the already selected chip returns to the default view.
     * Selecting a new chip cancels any in-flight requests, hides synthetic sections,
     * and displays only the chip's YTM carousel shelves.
     */
    fun onHomeChipSelected(chip: HomeChip) = selectChip(chip)

    fun selectChip(chip: HomeChip?) {
        val currentSelected = _uiState.value.selectedChip
        if (chip == null || chip == currentSelected) {
            chipJob?.cancel()
            _uiState.update {
                it.copy(
                    selectedChip = null,
                    isChipLoading = false,
                    chipShelves = emptyList(),
                    chipContinuationToken = null,
                )
            }
            return
        }

        chipJob?.cancel()
        loadMoreJob?.cancel()
        val currentGen = ++requestGeneration
        _uiState.update {
            it.copy(
                selectedChip = chip,
                isChipLoading = true,
                chipShelves = emptyList(),
                chipContinuationToken = null,
                error = null,
            )
        }

        chipJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val page = innerTube.fetchHomeChip(chip)
                ensureActive()
                if (requestGeneration != currentGen || _uiState.value.selectedChip != chip) return@launch
                _uiState.update {
                    it.copy(
                        isChipLoading = false,
                        chipShelves = page.shelves,
                        chipContinuationToken = page.continuationToken,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                ensureActive()
                if (requestGeneration == currentGen) {
                    _uiState.update {
                        it.copy(
                            selectedChip = null,
                            isChipLoading = false,
                            error = appContext.getString(com.lastwave.app.R.string.feed_update_error),
                        )
                    }
                }
            }
        }
    }

    /**
     * Loads the next page of shelves when scrolling near the end of Home.
     * Enforces in-flight guard (one request at a time), stale response guard
     * (ignores completed loads if chip or feed changed), and dedupe guard
     * (never appends a shelf with matching title + first item id).
     */
    fun loadMoreHomeShelves() {
        val state = _uiState.value
        if (state.isLoadingMore || state.isChipLoading || state.isLoading || state.isRefreshing) return

        val isChipActive = state.selectedChip != null
        val token = if (isChipActive) {
            state.chipContinuationToken
        } else {
            state.defaultContinuationToken ?: state.feedData.homeContinuationToken
        }
        if (token.isNullOrBlank()) return

        val currentGen = requestGeneration
        val activeChip = state.selectedChip
        _uiState.update { it.copy(isLoadingMore = true) }

        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val nextPage = innerTube.fetchHomeContinuation(token)
                ensureActive()
                if (requestGeneration != currentGen || _uiState.value.selectedChip != activeChip) return@launch

                val existingShelves = if (isChipActive) {
                    _uiState.value.chipShelves
                } else {
                    _uiState.value.feedData.nativeShelves + _uiState.value.pagedShelves
                }
                val existingKeys = existingShelves.map(::shelfFingerprint).toSet()

                val newShelves = nextPage.shelves.filter { shelf ->
                    shelf.items.isNotEmpty() && shelfFingerprint(shelf) !in existingKeys
                }

                _uiState.update { current ->
                    if (isChipActive) {
                        current.copy(
                            isLoadingMore = false,
                            chipShelves = current.chipShelves + newShelves,
                            chipContinuationToken = nextPage.continuationToken,
                        )
                    } else {
                        current.copy(
                            isLoadingMore = false,
                            pagedShelves = current.pagedShelves + newShelves,
                            defaultContinuationToken = nextPage.continuationToken,
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                ensureActive()
                if (requestGeneration == currentGen) {
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            error = appContext.getString(com.lastwave.app.R.string.feed_update_error),
                        )
                    }
                }
            }
        }
    }

    private fun shelfFingerprint(shelf: HomeShelf): String {
        val firstId = when (val item = shelf.items.firstOrNull()) {
            is HomeShelfItem.Track -> item.track.videoId
            is HomeShelfItem.Playlist -> item.playlist.id
            null -> ""
        }
        return "${shelf.title.trim().lowercase()}_$firstId"
    }

    fun playTrack(track: YouTubeMusicTrack, sourceLabel: String = "Feed") {
        musicPlayer.play(track.toPlayableTrack(), sourceLabel = sourceLabel, startRadio = true)
    }

    fun shuffleTracksQueue(tracks: List<YouTubeMusicTrack>, sourceLabel: String = "Feed") {
        if (tracks.isEmpty()) return
        val shuffled = tracks.shuffled()
        musicPlayer.playQueue(
            shuffled.map { it.toPlayableTrack() },
            startIndex = 0,
            sourceLabel = "$sourceLabel Shuffle",
            enableRadioContinuation = true,
        )
    }

    fun playTracksQueue(tracks: List<YouTubeMusicTrack>, startIndex: Int = 0, sourceLabel: String = "Feed") {
        if (tracks.isEmpty()) return
        val playable = tracks.map { it.toPlayableTrack() }
        musicPlayer.playQueue(
            playable,
            startIndex = startIndex.coerceIn(0, playable.lastIndex.coerceAtLeast(0)),
            sourceLabel = sourceLabel,
            enableRadioContinuation = true,
        )
    }

    fun playRecentQueue(tracks: List<RecentTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val playable = tracks.map {
            PlayableTrack(
                title = it.name,
                artist = it.artist.displayName,
                album = it.album.displayName,
                artworkUrl = it.artworkUrl,
                videoId = GeneratedTrack(it.name, it.artist.displayName, it.artworkUrl, it.url).youtubeVideoIdOrNull(),
            )
        }
        musicPlayer.playQueue(
            playable,
            startIndex = startIndex.coerceIn(0, playable.lastIndex.coerceAtLeast(0)),
            sourceLabel = "Jump Back In",
            enableRadioContinuation = true,
        )
    }

    fun playGeneratedQueue(tracks: List<GeneratedTrack>, startIndex: Int = 0, sourceLabel: String = "Heavy Rotation") {
        if (tracks.isEmpty()) return
        val playable = tracks.map {
            PlayableTrack(
                title = it.name,
                artist = it.artist,
                album = it.album,
                artworkUrl = it.artworkUrl,
                videoId = it.youtubeVideoIdOrNull(),
            )
        }
        musicPlayer.playQueue(
            playable,
            startIndex = startIndex.coerceIn(0, playable.lastIndex.coerceAtLeast(0)),
            sourceLabel = sourceLabel,
            enableRadioContinuation = true,
        )
    }

    fun playArtistRadio(artist: FeedArtist) {
        viewModelScope.launch {
            val seeds = try {
                innerTube.searchSongs("${artist.name} songs", limit = 5, prefetchStreams = false)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList()
            }
            val seed = seeds.firstOrNull {
                it.artist.contains(artist.name, ignoreCase = true) || artist.name.contains(it.artist, ignoreCase = true)
            } ?: seeds.firstOrNull()
            val related = seed?.let {
                try {
                    relatedGraph.relatedFor(it.videoId, limit = 25, prefetchStreams = false)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    emptyList()
                }
            }.orEmpty()
            val songs = (listOfNotNull(seed) + related).distinctBy(YouTubeMusicTrack::videoId)
            if (songs.isNotEmpty()) {
                playTracksQueue(songs, startIndex = 0, sourceLabel = "${artist.name} Radio")
            }
        }
    }

    fun playDiscoveryQuery(title: String, query: String) {
        if (_uiState.value.launchingRadio != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(launchingRadio = title) }
            try {
                val seeds = try {
                    innerTube.searchSongs(query, limit = 8, prefetchStreams = false)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    emptyList()
                }
                val seed = seeds.take(5).randomOrNull()
                val related = seed?.let {
                    try {
                        relatedGraph.relatedFor(it.videoId, limit = 24, prefetchStreams = false)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        emptyList()
                    }
                }.orEmpty()
                val songs = (listOfNotNull(seed) + related).distinctBy(YouTubeMusicTrack::videoId)
                if (songs.isNotEmpty()) playTracksQueue(songs, sourceLabel = title)
            } finally {
                _uiState.update { it.copy(launchingRadio = null) }
            }
        }
    }

    fun playPlaylistSummary(summary: YouTubePlaylistSummary) {
        viewModelScope.launch {
            val result = runCatching { innerTube.fetchPlaylist(summary.id, maxTracks = 100) }
                .getOrNull()
            val tracks = result?.tracks.orEmpty()
            if (tracks.isNotEmpty()) {
                val playable = tracks.map { it.toPlayableTrack() }
                musicPlayer.playQueue(playable, startIndex = 0, sourceLabel = summary.title)
            }
        }
    }

    fun playPlaylistById(playlistId: String, title: String) {
        viewModelScope.launch {
            val result = runCatching { innerTube.fetchPlaylist(playlistId, maxTracks = 100) }
                .getOrNull()
            val tracks = result?.tracks.orEmpty()
            if (tracks.isNotEmpty()) {
                val playable = tracks.map { it.toPlayableTrack() }
                musicPlayer.playQueue(playable, startIndex = 0, sourceLabel = title)
            }
        }
    }

    fun playDiscoverMix(tile: FeedQuickTile? = null) {
        viewModelScope.launch {
            var playlistId = tile?.playlistId
            if (playlistId.isNullOrBlank()) {
                val feed = _uiState.value.feedData
                val found = feed.ytSuggestedPlaylists.firstOrNull { it.title.contains("Discover Mix", ignoreCase = true) }
                    ?: feed.nativeShelves.flatMap { it.items }.filterIsInstance<com.lastwave.app.data.music.HomeShelfItem.Playlist>()
                        .map { it.playlist }.firstOrNull { it.title.contains("Discover Mix", ignoreCase = true) }
                playlistId = found?.id
            }
            if (playlistId.isNullOrBlank()) {
                val mixes = runCatching { innerTube.fetchHomeMixes() }.getOrDefault(emptyList())
                playlistId = mixes.firstOrNull { it.title.contains("Discover Mix", ignoreCase = true) }?.id
            }
            if (playlistId.isNullOrBlank()) {
                val search = runCatching { innerTube.searchPlaylists("Discover Mix") }.getOrDefault(emptyList())
                playlistId = search.firstOrNull { it.title.contains("Discover Mix", ignoreCase = true) }?.id
                    ?: search.firstOrNull()?.id
            }
            if (!playlistId.isNullOrBlank()) {
                playPlaylistById(playlistId, "Discover Mix")
            } else {
                playInfiniteRadio()
            }
        }
    }

    fun playMyMix(tile: FeedQuickTile? = null) {
        viewModelScope.launch {
            val playlistId = tile?.playlistId
            val tracks = if (!playlistId.isNullOrBlank()) {
                runCatching { innerTube.fetchPlaylist(playlistId, maxTracks = 100) }.getOrNull()?.tracks.orEmpty()
            } else emptyList()
            val queue = tracks.ifEmpty {
                val recents = _uiState.value.feedData.ytRecentSongs
                val picks = _uiState.value.feedData.quickPicks
                val liked = _uiState.value.feedData.ytLikedSongs
                (recents + liked + picks).distinctBy { it.videoId }.shuffled()
            }
            if (queue.isNotEmpty()) {
                val playable = queue.map { it.toPlayableTrack() }
                musicPlayer.playQueue(playable, startIndex = 0, sourceLabel = "My Mix")
            }
        }
    }

    fun handleQuickTileClick(tile: FeedQuickTile) {
        val videoId = tile.actionVideoId ?: return
        musicPlayer.play(
            PlayableTrack(
                title = tile.title,
                artist = tile.subtitle ?: "",
                artworkUrl = tile.artworkUrl,
                videoId = videoId.takeIf(String::isNotBlank),
            ),
            sourceLabel = "Quick Picks",
        )
    }

    private fun YouTubeMusicTrack.toPlayableTrack(): PlayableTrack = PlayableTrack(
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        videoId = videoId.takeIf(String::isNotBlank),
        durationMs = durationSeconds?.takeIf { it > 0 }?.times(1_000L),
    )

    private companion object {
        const val STALE_AFTER_MILLIS = 90_000L
    }
}
