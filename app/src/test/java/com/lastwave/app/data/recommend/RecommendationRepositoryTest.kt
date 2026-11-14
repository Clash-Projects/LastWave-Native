package com.lastwave.app.data.recommend

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.RecommendationSource
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.repository.RelatedGraphRepository
import com.lastwave.app.data.spotify.SpotifyPreferences
import com.lastwave.app.data.spotify.SpotifyRecommendationApi
import com.lastwave.app.data.spotify.SpotifyWebSearchResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RecommendationRepositoryTest {

    private val innerTube = mockk<InnerTubeMusicApi>(relaxed = true)
    private val relatedGraph = mockk<RelatedGraphRepository>(relaxed = true)
    private val spotify = mockk<SpotifyRecommendationApi>(relaxed = true)
    private val settings = mockk<SettingsPreferences>(relaxed = true)
    private val spotifyPreferences = mockk<SpotifyPreferences>(relaxed = true)

    private val youtubeTrack1 = YouTubeMusicTrack(videoId = "yt1", title = "Song 1", artist = "Artist 1")
    private val youtubeTrack2 = YouTubeMusicTrack(videoId = "yt2", title = "Song 2", artist = "Artist 2")
    private val youtubeTrack3 = YouTubeMusicTrack(videoId = "yt3", title = "Song 3", artist = "Artist 3")
    private val youtubeTrackFallback = YouTubeMusicTrack(videoId = "ytFallback", title = "Fallback", artist = "Artist")

    private fun repository(source: RecommendationSource, connected: Boolean) = RecommendationRepository(
        innerTube = innerTube,
        relatedGraph = relatedGraph,
        spotifyRecommendations = spotify,
        settingsPreferences = settings,
        spotifyPreferences = spotifyPreferences,
    ).also {
        every { settings.settings } returns flowOf(MiscSettings(recommendationSource = source))
        every { spotifyPreferences.isConnected } returns flowOf(connected)
        coEvery { relatedGraph.relatedFor(any(), any(), any(), any()) } returns listOf(youtubeTrackFallback)
    }

    @Test
    fun spotifyCandidate_withMultipleArtistsAndDuration_passesDetailsToInnerTube() = runTest {
        val repo = repository(RecommendationSource.SPOTIFY, connected = true)
        val candidate = SpotifyWebSearchResponse.TracksContainer.TrackItem(
            id = "sp1",
            name = "Collaborative Track",
            duration_ms = 210000L,
            artists = listOf(
                SpotifyWebSearchResponse.TracksContainer.TrackItem.ArtistItem("Main Artist"),
                SpotifyWebSearchResponse.TracksContainer.TrackItem.ArtistItem("Featured Artist"),
                SpotifyWebSearchResponse.TracksContainer.TrackItem.ArtistItem("Second Guest"),
            ),
        )
        coEvery { spotify.recommendationsFor(any(), any(), any(), any()) } returns listOf(candidate)
        coEvery {
            innerTube.findBestMatchOrNull(
                title = any(),
                artist = any(),
                prefetchStreams = any(),
                expectedDurationMs = any(),
            )
        } returns youtubeTrack1

        repo.relatedFor(seedVideoId = null, title = "Seed", artist = "Artist")

        coVerify {
            innerTube.findBestMatchOrNull(
                title = "Collaborative Track",
                artist = "Main Artist feat. Featured Artist, Second Guest",
                prefetchStreams = false,
                expectedDurationMs = 210000L,
            )
        }
    }

    @Test
    fun spotifyCandidate_withExistingFeatInTitle_doesNotDuplicateFeat() = runTest {
        val repo = repository(RecommendationSource.SPOTIFY, connected = true)
        val candidate = SpotifyWebSearchResponse.TracksContainer.TrackItem(
            id = "sp1",
            name = "Track Title (feat. Featured Artist)",
            duration_ms = 180000L,
            artists = listOf(
                SpotifyWebSearchResponse.TracksContainer.TrackItem.ArtistItem("Main Artist"),
                SpotifyWebSearchResponse.TracksContainer.TrackItem.ArtistItem("Featured Artist"),
            ),
        )
        coEvery { spotify.recommendationsFor(any(), any(), any(), any()) } returns listOf(candidate)
        coEvery {
            innerTube.findBestMatchOrNull(
                title = any(),
                artist = any(),
                prefetchStreams = any(),
                expectedDurationMs = any(),
            )
        } returns youtubeTrack1

        repo.relatedFor(seedVideoId = null, title = "Seed", artist = "Artist")

        coVerify {
            innerTube.findBestMatchOrNull(
                title = "Track Title (feat. Featured Artist)",
                artist = "Main Artist",
                prefetchStreams = false,
                expectedDurationMs = 180000L,
            )
        }
    }

    @Test
    fun whenSpotifyMatchesAtLeast3Tracks_returnsSpotifyMatchesWithoutFallback() = runTest {
        val repo = repository(RecommendationSource.SPOTIFY, connected = true)
        val candidates = listOf(
            SpotifyWebSearchResponse.TracksContainer.TrackItem(id = "sp1", name = "Song 1", artists = listOf(SpotifyWebSearchResponse.TracksContainer.TrackItem.ArtistItem("A1"))),
            SpotifyWebSearchResponse.TracksContainer.TrackItem(id = "sp2", name = "Song 2", artists = listOf(SpotifyWebSearchResponse.TracksContainer.TrackItem.ArtistItem("A2"))),
            SpotifyWebSearchResponse.TracksContainer.TrackItem(id = "sp3", name = "Song 3", artists = listOf(SpotifyWebSearchResponse.TracksContainer.TrackItem.ArtistItem("A3"))),
        )
        coEvery { spotify.recommendationsFor(any(), any(), any(), any()) } returns candidates
        coEvery { innerTube.findBestMatchOrNull("Song 1", any(), any(), any()) } returns youtubeTrack1
        coEvery { innerTube.findBestMatchOrNull("Song 2", any(), any(), any()) } returns youtubeTrack2
        coEvery { innerTube.findBestMatchOrNull("Song 3", any(), any(), any()) } returns youtubeTrack3

        val result = repo.relatedFor(seedVideoId = "seed", title = "Seed", artist = "Artist")

        assertThat(result).containsExactly(youtubeTrack1, youtubeTrack2, youtubeTrack3).inOrder()
        coVerify(exactly = 0) { relatedGraph.relatedFor(any(), any(), any(), any()) }
    }

    @Test
    fun whenSpotifyMatches1Or2Tracks_andSeedVideoIdPresent_complementsFromRelatedGraph() = runTest {
        val repo = repository(RecommendationSource.SPOTIFY, connected = true)
        val candidates = listOf(
            SpotifyWebSearchResponse.TracksContainer.TrackItem(id = "sp1", name = "Song 1", artists = listOf(SpotifyWebSearchResponse.TracksContainer.TrackItem.ArtistItem("A1"))),
        )
        coEvery { spotify.recommendationsFor(any(), any(), any(), any()) } returns candidates
        coEvery { innerTube.findBestMatchOrNull("Song 1", any(), any(), any()) } returns youtubeTrack1
        coEvery {
            relatedGraph.relatedFor("seed", limit = 4, prefetchStreams = false)
        } returns listOf(youtubeTrack1, youtubeTrack2, youtubeTrack3)

        val result = repo.relatedFor(seedVideoId = "seed", title = "Seed", artist = "Artist", limit = 5)

        assertThat(result).containsExactly(youtubeTrack1, youtubeTrack2, youtubeTrack3).inOrder()
        coVerify(exactly = 1) {
            relatedGraph.relatedFor("seed", limit = 4, prefetchStreams = false)
        }
    }

    @Test
    fun whenSpotifyMatches1Or2Tracks_andSeedVideoIdBlank_returnsMatchesWithoutFallback() = runTest {
        val repo = repository(RecommendationSource.SPOTIFY, connected = true)
        val candidates = listOf(
            SpotifyWebSearchResponse.TracksContainer.TrackItem(id = "sp1", name = "Song 1", artists = listOf(SpotifyWebSearchResponse.TracksContainer.TrackItem.ArtistItem("A1"))),
        )
        coEvery { spotify.recommendationsFor(any(), any(), any(), any()) } returns candidates
        coEvery { innerTube.findBestMatchOrNull("Song 1", any(), any(), any()) } returns youtubeTrack1

        val result = repo.relatedFor(seedVideoId = "", title = "Seed", artist = "Artist")

        assertThat(result).containsExactly(youtubeTrack1)
        coVerify(exactly = 0) { relatedGraph.relatedFor(any(), any(), any(), any()) }
    }

    @Test
    fun emptySpotifyResult_fallsBackToYouTubeGraph() = runTest {
        val repo = repository(RecommendationSource.SPOTIFY, connected = true)
        coEvery { spotify.recommendationsFor(any(), any(), any(), any()) } returns emptyList()

        val result = repo.relatedFor(seedVideoId = "seed", title = "Song", artist = "Artist")

        assertThat(result).containsExactly(youtubeTrackFallback)
        coVerify(exactly = 1) { relatedGraph.relatedFor("seed", 20, prefetchStreams = false) }
    }

    @Test
    fun spotifyUnmatchedTracks_fallBackToYouTubeGraph() = runTest {
        val repo = repository(RecommendationSource.SPOTIFY, connected = true)
        coEvery { spotify.recommendationsFor(any(), any(), any(), any()) } returns listOf(
            SpotifyWebSearchResponse.TracksContainer.TrackItem(id = "sp", name = "Obscure"),
        )
        coEvery { innerTube.findBestMatchOrNull(any(), any(), any(), any()) } returns null

        val result = repo.relatedFor(seedVideoId = "seed", title = "Song", artist = "Artist")

        assertThat(result).containsExactly(youtubeTrackFallback)
        coVerify(exactly = 1) { relatedGraph.relatedFor("seed", 20, prefetchStreams = false) }
    }

    @Test
    fun failingSpotifyCall_fallsBackToYouTubeGraph() = runTest {
        val repo = repository(RecommendationSource.SPOTIFY, connected = true)
        coEvery { spotify.recommendationsFor(any(), any(), any(), any()) } throws RuntimeException("endpoint retired")

        val result = repo.relatedFor(seedVideoId = "seed", title = "Song", artist = "Artist")

        assertThat(result).containsExactly(youtubeTrackFallback)
        coVerify(exactly = 1) { relatedGraph.relatedFor("seed", 20, prefetchStreams = false) }
    }

    @Test
    fun disconnectedSpotify_neverAsksSpotify() = runTest {
        val repo = repository(RecommendationSource.SPOTIFY, connected = false)

        val result = repo.relatedFor(seedVideoId = "seed", title = "Song", artist = "Artist")

        assertThat(result).containsExactly(youtubeTrackFallback)
        coVerify(exactly = 0) { spotify.recommendationsFor(any(), any(), any(), any()) }
    }

    @Test
    fun youtubeSource_neverAsksSpotify() = runTest {
        val repo = repository(RecommendationSource.YOUTUBE_MUSIC, connected = true)

        val result = repo.relatedFor(seedVideoId = "seed", title = "Song", artist = "Artist")

        assertThat(result).containsExactly(youtubeTrackFallback)
        coVerify(exactly = 0) { spotify.recommendationsFor(any(), any(), any(), any()) }
    }
}
