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
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.podcast.PodcastRepository
import com.lastwave.app.data.repository.HomeRepository
import com.lastwave.app.data.repository.RelatedGraphRepository
import com.lastwave.app.data.ytmusic.YtConnection
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PopularShowsFeedTest {

    private val innerTube: InnerTubeMusicApi = mockk(relaxed = true)
    private val relatedGraph: RelatedGraphRepository = mockk(relaxed = true)
    private val homeRepository: HomeRepository = mockk(relaxed = true)
    private val tasteProfileProvider: TasteProfileProvider = mockk(relaxed = true)
    private val ytAuth: YtMusicAuthManager = mockk(relaxed = true)
    private val playlistRepository: PlaylistRepository = mockk(relaxed = true)
    private val podcastRepository: PodcastRepository = mockk(relaxed = true)
    private val settingsPreferences: SettingsPreferences = mockk(relaxed = true)

    private fun createRepository(): FeedRepository {
        return FeedRepository(
            innerTube,
            relatedGraph,
            homeRepository,
            tasteProfileProvider,
            ytAuth,
            playlistRepository,
            podcastRepository,
            settingsPreferences,
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
        val cached = repository.getCachedFeed()

        assertThat(cached).isNotNull()
        assertThat(cached?.podcastShows).isEqualTo(feed.podcastShows)
        assertThat(cached?.podcastShelfTitle).isEqualTo(feed.podcastShelfTitle)
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
