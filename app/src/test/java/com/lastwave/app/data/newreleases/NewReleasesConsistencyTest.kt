package com.lastwave.app.data.newreleases

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.feed.FeedData
import com.lastwave.app.data.feed.FeedRepository
import com.lastwave.app.data.model.AlbumPageData
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.playback.PlayableTrack
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NewReleasesConsistencyTest {

    @Test
    fun localizedSearchQueries_returnsLanguageAppropriateTerms() {
        val jaQueries = getLocalizedSearchQueries("ja", 2026)
        assertThat(jaQueries.first()).isEqualTo("新曲 2026")

        val esQueries = getLocalizedSearchQueries("es", 2026)
        assertThat(esQueries.first()).isEqualTo("música nueva 2026")

        val deQueries = getLocalizedSearchQueries("de", 2026)
        assertThat(deQueries.first()).isEqualTo("neue musik 2026")

        val frQueries = getLocalizedSearchQueries("fr", 2026)
        assertThat(frQueries.first()).isEqualTo("nouvelle musique 2026")

        val koQueries = getLocalizedSearchQueries("ko", 2026)
        assertThat(koQueries.first()).isEqualTo("신곡 2026")

        val enQueries = getLocalizedSearchQueries("en", 2026)
        assertThat(enQueries.first()).isEqualTo("new music 2026")
    }

    @Test
    fun fetchInitialBatch_prioritizesHomeFeedNewReleases() = runTest {
        val innerTube: InnerTubeMusicApi = mockk(relaxed = true)
        val feedRepository: FeedRepository = mockk(relaxed = true)

        every { innerTube.getEffectiveHlGl() } returns Pair("en", "US")
        coEvery { innerTube.fetchNewReleasesAlbumsGrid() } returns Pair(emptyList(), null)

        val homeNewReleaseAlbum = YouTubePlaylistSummary(
            id = "MPREb_home_album",
            title = "Home Featured Release",
            author = "Relevant Local Artist",
            artworkUrl = "https://example.com/cover.jpg",
        )

        val cachedFeed = FeedData(
            newReleases = listOf(homeNewReleaseAlbum),
        )
        every { feedRepository.getCachedFeed() } returns cachedFeed

        val albumTrack = PlayableTrack(
            title = "Lead Single",
            artist = "Relevant Local Artist",
            album = "Home Featured Release",
            artworkUrl = "https://example.com/cover.jpg",
            videoId = "track_1",
        )
        val albumPage = AlbumPageData(
            title = "Home Featured Release",
            artist = "Relevant Local Artist",
            browseId = "MPREb_home_album",
            tracks = listOf(albumTrack),
        )
        coEvery { innerTube.fetchAlbumPage("MPREb_home_album", any(), any()) } returns albumPage

        val repository = NewReleasesRepository(innerTube, feedRepository)
        val batchTracks = repository.fetchInitialBatch()

        // Verify that the initial batch directly opens with the album from the Home feed
        // (which also provides the tile thumbnail artwork)
        assertThat(batchTracks).isNotEmpty()
        assertThat(batchTracks.first().title).isEqualTo("Lead Single")
        assertThat(batchTracks.first().artist).isEqualTo("Relevant Local Artist")
        assertThat(batchTracks.first().album).isEqualTo("Home Featured Release")
    }

    @Test
    fun fetchInitialBatch_fallsBackToRegionalInnerTubeWhenCacheEmpty() = runTest {
        val innerTube: InnerTubeMusicApi = mockk(relaxed = true)
        val feedRepository: FeedRepository = mockk(relaxed = true)

        every { innerTube.getEffectiveHlGl() } returns Pair("en", "US")
        coEvery { innerTube.fetchNewReleasesAlbumsGrid() } returns Pair(emptyList(), null)

        every { feedRepository.getCachedFeed() } returns null

        val regionalExploreAlbum = YouTubePlaylistSummary(
            id = "MPREb_regional",
            title = "Regional Album",
            author = "Regional Artist",
        )
        coEvery { innerTube.fetchNewReleases() } returns listOf(regionalExploreAlbum)

        val track = PlayableTrack(
            title = "Explore Track",
            artist = "Regional Artist",
            album = "Regional Album",
            videoId = "explore_vid",
        )
        val albumPage = AlbumPageData(
            title = "Regional Album",
            artist = "Regional Artist",
            browseId = "MPREb_regional",
            tracks = listOf(track),
        )
        coEvery { innerTube.fetchAlbumPage("MPREb_regional", any(), any()) } returns albumPage

        val repository = NewReleasesRepository(innerTube, feedRepository)
        val batchTracks = repository.fetchInitialBatch()

        assertThat(batchTracks).isNotEmpty()
        assertThat(batchTracks.first().artist).isEqualTo("Regional Artist")
    }
}
