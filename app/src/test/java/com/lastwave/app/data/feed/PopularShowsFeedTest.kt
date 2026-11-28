package com.lastwave.app.data.feed

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.generate.TasteProfileProvider
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.music.HomeChip
import com.lastwave.app.data.music.HomePage
import com.lastwave.app.data.music.HomeShelf
import com.lastwave.app.data.music.HomeShelfItem
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubeMusicEntity
import com.lastwave.app.data.music.YouTubeMusicEntityKind
import com.lastwave.app.data.music.YtMusicTasteSignals
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.podcast.PodcastRepository
import com.lastwave.app.data.repository.HomeRepository
import com.lastwave.app.data.repository.HomeAlbum
import com.lastwave.app.data.recommend.RecommendationRepository
import com.lastwave.app.data.ytmusic.YtConnection
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PopularShowsFeedTest {

    private val innerTube: InnerTubeMusicApi = mockk(relaxed = true)
    private val recommendationRepository: com.lastwave.app.data.recommend.RecommendationRepository =
        mockk(relaxed = true)
    private val homeRepository: HomeRepository = mockk(relaxed = true)
    private val tasteProfileProvider: TasteProfileProvider = mockk(relaxed = true)
    private val ytAuth: YtMusicAuthManager = mockk(relaxed = true)
    private val playlistRepository: PlaylistRepository = mockk(relaxed = true)
    private val podcastRepository: PodcastRepository = mockk(relaxed = true)
    private val settingsPreferences: SettingsPreferences = mockk(relaxed = true)

    private fun createRepository(): FeedRepository {
        return FeedRepository(
            innerTube,
            recommendationRepository,
            homeRepository,
            tasteProfileProvider,
            ytAuth,
            playlistRepository,
            podcastRepository,
            settingsPreferences,
            org.robolectric.RuntimeEnvironment.getApplication(),
        )
    }

    private fun createShows(count: Int): List<HomeShelfItem.PodcastShow> {
        return (1..count).map { i ->
            HomeShelfItem.PodcastShow(
                browseId = "MPSP_show_$i",
                title = "Show Number $i",
                author = "Author $i",
                artworkUrl = "https://example.com/show_$i.jpg",
            )
        }
    }

    @Before
    fun setUp() {
        coEvery { ytAuth.awaitLoadedConnection() } returns YtConnection()
        coEvery { ytAuth.connection } returns MutableStateFlow(YtConnection())
        coEvery { settingsPreferences.settings } returns MutableStateFlow(MiscSettings(podcastsEnabled = true))
        coEvery { homeRepository.fetchRecentTracks(any(), any(), any(), any()) } returns Result.success(
            com.lastwave.app.data.repository.RecentTracksPage(
                nowPlaying = null,
                tracks = emptyList(),
                page = 1,
                totalPages = 1,
            )
        )
        coEvery { homeRepository.fetchFriends(any()) } returns Result.success(emptyList())
        coEvery { innerTube.fetchHomePage() } returns HomePage(
            shelves = emptyList(),
            chips = listOf(HomeChip(title = "Podcasts", params = "podcast_params_1")),
        )
    }

    @Test
    fun completedRefreshDoesNotReuseOldRecommendations() = runBlocking {
        val oldTrack = YouTubeMusicTrack("old", "Old song", "Artist")
        val oldRelease = com.lastwave.app.data.music.YouTubePlaylistSummary("release", "Old release")
        coEvery { innerTube.fetchHomeSongs() } returnsMany listOf(listOf(oldTrack), emptyList())
        coEvery { innerTube.fetchNewReleases() } returnsMany listOf(listOf(oldRelease), emptyList())
        val repository = createRepository()

        val first = repository.loadFeed("test_user")
        val refreshed = repository.loadFeed("test_user")

        assertThat(first.quickPicks).contains(oldTrack)
        assertThat(first.newReleases).containsExactly(oldRelease)
        assertThat(refreshed.quickPicks).isEmpty()
        assertThat(refreshed.newReleases).isEmpty()
        assertThat(repository.getCachedFeed("test_user", YtConnection())?.newReleases).isEmpty()
    }

    @Test
    fun refreshEmitsFreshHomePageWithoutWaitingForOtherSources() = runBlocking {
        val oldShelf = HomeShelf("Old", items = listOf(HomeShelfItem.Track(YouTubeMusicTrack("old", "Old", "Artist"))))
        val newShelf = HomeShelf("New", items = listOf(HomeShelfItem.Track(YouTubeMusicTrack("new", "New", "Artist"))))
        coEvery { innerTube.fetchHomePage() } returns HomePage(shelves = listOf(oldShelf))
        val repository = createRepository()
        val cached = repository.loadFeed("test_user")

        val homeStarted = CompletableDeferred<Unit>()
        val homePage = CompletableDeferred<HomePage>()
        val releases = CompletableDeferred<List<com.lastwave.app.data.music.YouTubePlaylistSummary>>()
        val update = CompletableDeferred<FeedData>()
        coEvery { innerTube.fetchHomePage() } coAnswers {
            homeStarted.complete(Unit)
            homePage.await()
        }
        coEvery { innerTube.fetchNewReleases() } coAnswers { releases.await() }
        val load = async { repository.loadFeed("test_user") { update.complete(it) } }

        try {
            withTimeout(5_000) { homeStarted.await() }
            assertThat(update.isCompleted).isFalse()
            homePage.complete(HomePage(shelves = listOf(newShelf)))
            val fresh = withTimeout(5_000) { update.await() }
            assertThat(fresh.nativeShelves).containsExactly(newShelf)
            assertThat(fresh.quickTiles).isEqualTo(cached.quickTiles)
            assertThat(load.isCompleted).isFalse()
        } finally {
            homePage.complete(HomePage())
            releases.complete(emptyList())
        }
        load.await()
        Unit
    }

    @Test
    fun timedOutDiscoveryDoesNotKeepFeedLoading() = runBlocking {
        val connection = YtConnection(cookies = mapOf("session" to "value"), accountName = "User")
        val discoveryCancelled = CompletableDeferred<Unit>()
        coEvery { ytAuth.awaitLoadedConnection() } returns connection
        coEvery { ytAuth.connection } returns MutableStateFlow(connection)
        coEvery { innerTube.fetchTasteSignals(any(), any(), any()) } returns YtMusicTasteSignals(
            likedTracks = listOf(YouTubeMusicTrack("video", "Song", "Artist")),
        )
        coEvery { recommendationRepository.relatedFor(any(), any(), any(), 30, any()) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                discoveryCancelled.complete(Unit)
            }
        }

        withTimeout(10_000) { createRepository().loadFeed("test_user") }
        assertThat(discoveryCancelled.isCompleted).isTrue()
    }

    @Test
    fun albumSourcesLoadInParallel() = runBlocking {
        val artistPageStarted = CompletableDeferred<Unit>()
        val releaseArtistPage = CompletableDeferred<Unit>()
        val albumSearchStarted = CompletableDeferred<Unit>()
        coEvery { innerTube.fetchHomeSongs() } returns listOf(YouTubeMusicTrack("video", "Song", "Artist"))
        coEvery { innerTube.searchArtists("Artist", any()) } returns listOf(
            YouTubeMusicEntity(YouTubeMusicEntityKind.ARTIST, "Artist", browseId = "artist_id"),
        )
        coEvery { homeRepository.fetchTopAlbums(any(), any(), any()) } returns Result.success(
            listOf(HomeAlbum("Album", "Artist")),
        )
        coEvery { innerTube.fetchArtistPage(any(), any(), any(), any()) } coAnswers {
            artistPageStarted.complete(Unit)
            releaseArtistPage.await()
            null
        }
        coEvery { innerTube.searchAlbums(any(), any()) } coAnswers {
            albumSearchStarted.complete(Unit)
            emptyList()
        }

        val load = async { createRepository().loadFeed("test_user") }
        try {
            withTimeout(5_000) { artistPageStarted.await() }
            withTimeout(5_000) { albumSearchStarted.await() }
            assertThat(load.isCompleted).isFalse()
        } finally {
            releaseArtistPage.complete(Unit)
        }
        load.await()
        Unit
    }

    @Test
    fun homeShelvesAppearBeforeOtherFeedSourcesFinish() = runBlocking {
        val releases = CompletableDeferred<List<com.lastwave.app.data.music.YouTubePlaylistSummary>>()
        val firstUpdate = CompletableDeferred<FeedData>()
        val shelf = HomeShelf(
            title = "For you",
            items = listOf(HomeShelfItem.Track(YouTubeMusicTrack("video", "Song", "Artist"))),
        )
        coEvery { innerTube.fetchHomePage() } returns HomePage(shelves = listOf(shelf))
        coEvery { innerTube.fetchNewReleases() } coAnswers { releases.await() }

        val load = async { createRepository().loadFeed("test_user") { firstUpdate.complete(it) } }
        try {
            val update = withTimeout(5_000) { firstUpdate.await() }
            assertThat(update.nativeShelves).containsExactly(shelf)
            assertThat(load.isCompleted).isFalse()
        } finally {
            releases.complete(emptyList())
        }
        load.await()
        Unit
    }

    @Test
    fun popularShows_sampleSize_isAtMost10() = runTest {
        val raw28Shows = createShows(28)
        coEvery { innerTube.fetchHomeChip(any()) } returns HomePage(
            shelves = listOf(
                HomeShelf(
                    title = "Shows for you",
                    items = raw28Shows,
                )
            )
        )

        val repository = createRepository()
        val feed = repository.loadFeed("test_user")

        assertThat(feed.podcastShows).hasSize(10)
        assertThat(feed.podcastShelfTitle).isEqualTo("Shows for you")
        val ids = feed.podcastShows.map { it.showId }
        val rawIds = raw28Shows.map { it.browseId }
        assertThat(rawIds).containsAtLeastElementsIn(ids)
    }

    @Test
    fun popularShows_refreshGivesDifferentSample() = runTest {
        val raw28Shows = createShows(28)
        coEvery { innerTube.fetchHomeChip(any()) } returns HomePage(
            shelves = listOf(
                HomeShelf(
                    title = "Shows for you",
                    items = raw28Shows,
                )
            )
        )

        val repository = createRepository()
        val feed1 = repository.loadFeed("test_user")
        val ids1 = feed1.podcastShows.map { it.showId }

        // Second load simulates refresh
        val feed2 = repository.loadFeed("test_user")
        val ids2 = feed2.podcastShows.map { it.showId }

        assertThat(ids1).hasSize(10)
        assertThat(ids2).hasSize(10)
        // With 28 items, choosing 10 uniformly at random has negligible probability of exact identical ordered match
        val exactlyIdentical = ids1 == ids2
        assertThat(exactlyIdentical).isFalse()
    }

    @Test
    fun popularShows_remainsCachedWithinSession() = runTest {
        val raw28Shows = createShows(28)
        coEvery { innerTube.fetchHomeChip(any()) } returns HomePage(
            shelves = listOf(
                HomeShelf(
                    title = "Shows for you",
                    items = raw28Shows,
                )
            )
        )

        val repository = createRepository()
        val feed = repository.loadFeed("test_user")
        val cached = repository.getCachedFeed("test_user", YtConnection())

        assertThat(cached).isNotNull()
        assertThat(cached?.podcastShows).isEqualTo(feed.podcastShows)
        assertThat(cached?.podcastShelfTitle).isEqualTo(feed.podcastShelfTitle)
        assertThat(repository.getCachedFeed("another_user", YtConnection())).isNull()
    }

    @Test
    fun feedRestoresFromDiskAfterProcessRestart() = runTest {
        coEvery { innerTube.fetchHomePage() } returns HomePage(
            shelves = listOf(
                HomeShelf(
                    title = "Quick picks",
                    items = listOf(
                        HomeShelfItem.Track(YouTubeMusicTrack("vid00000001", "Song", "Artist")),
                        HomeShelfItem.Playlist(com.lastwave.app.data.music.YouTubePlaylistSummary("PL1", "Mix")),
                    ),
                )
            ),
            chips = listOf(HomeChip(title = "Podcasts", params = "podcast_params_1")),
        )
        coEvery { innerTube.fetchHomeChip(any()) } returns HomePage(
            shelves = listOf(HomeShelf(title = "Shows for you", items = createShows(3))),
        )

        val feed = createRepository().loadFeed("test_user")

        // A new instance has no memory cache, like a cold start.
        assertThat(createRepository().getCachedFeed("test_user", YtConnection())).isEqualTo(feed)
        assertThat(createRepository().getCachedFeed("another_user", YtConnection())).isNull()
    }

    @Test
    fun popularShows_emptyResultHidesRow() = runTest {
        coEvery { innerTube.fetchHomeChip(any()) } returns HomePage(
            shelves = emptyList(),
        )

        val repository = createRepository()
        val feed = repository.loadFeed("test_user")

        assertThat(feed.podcastShows).isEmpty()
        assertThat(feed.podcastShelfTitle).isNull()
    }
}
