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

    private val youtubeTrack = YouTubeMusicTrack(videoId = "yt", title = "Song", artist = "Artist")

    private fun repository(source: RecommendationSource, connected: Boolean) = RecommendationRepository(
        innerTube = innerTube,
        relatedGraph = relatedGraph,
        spotifyRecommendations = spotify,
        settingsPreferences = settings,
        spotifyPreferences = spotifyPreferences,
    ).also {
        every { settings.settings } returns flowOf(MiscSettings(recommendationSource = source))
        every { spotifyPreferences.isConnected } returns flowOf(connected)
        coEvery { relatedGraph.relatedFor(any(), any(), any(), any()) } returns listOf(youtubeTrack)
    }

    @Test
    fun spotifySeed_isMatchedBackToYouTubeMusic() = runTest {
        val repo = repository(RecommendationSource.SPOTIFY, connected = true)
        coEvery { spotify.recommendationsFor(any(), any(), any(), any()) } returns listOf(
            SpotifyWebSearchResponse.TracksContainer.TrackItem(
                id = "sp",
                name = "Song",
                artists = listOf(SpotifyWebSearchResponse.TracksContainer.TrackItem.ArtistItem("Artist")),
            ),
        )
        coEvery { innerTube.findBestMatchOrNull(any(), any(), any()) } returns youtubeTrack

        val result = repo.relatedFor(seedVideoId = "seed", title = "Song", artist = "Artist")

        assertThat(result).containsExactly(youtubeTrack)
        coVerify(exactly = 0) { relatedGraph.relatedFor(any(), any(), any(), any()) }
    }

    @Test
    fun emptySpotifyResult_fallsBackToYouTubeGraph() = runTest {
        val repo = repository(RecommendationSource.SPOTIFY, connected = true)
        coEvery { spotify.recommendationsFor(any(), any(), any(), any()) } returns emptyList()

        val result = repo.relatedFor(seedVideoId = "seed", title = "Song", artist = "Artist")

        assertThat(result).containsExactly(youtubeTrack)
    }

    @Test
    fun spotifyUnmatchedTracks_fallBackToYouTubeGraph() = runTest {
        val repo = repository(RecommendationSource.SPOTIFY, connected = true)
        coEvery { spotify.recommendationsFor(any(), any(), any(), any()) } returns listOf(
            SpotifyWebSearchResponse.TracksContainer.TrackItem(id = "sp", name = "Obscure"),
        )
        coEvery { innerTube.findBestMatchOrNull(any(), any(), any()) } returns null

        val result = repo.relatedFor(seedVideoId = "seed", title = "Song", artist = "Artist")

        assertThat(result).containsExactly(youtubeTrack)
    }

    @Test
    fun failingSpotifyCall_fallsBackToYouTubeGraph() = runTest {
        val repo = repository(RecommendationSource.SPOTIFY, connected = true)
        coEvery { spotify.recommendationsFor(any(), any(), any(), any()) } throws RuntimeException("endpoint retired")

        val result = repo.relatedFor(seedVideoId = "seed", title = "Song", artist = "Artist")

        assertThat(result).containsExactly(youtubeTrack)
    }

    @Test
    fun disconnectedSpotify_neverAsksSpotify() = runTest {
        val repo = repository(RecommendationSource.SPOTIFY, connected = false)

        val result = repo.relatedFor(seedVideoId = "seed", title = "Song", artist = "Artist")

        assertThat(result).containsExactly(youtubeTrack)
        coVerify(exactly = 0) { spotify.recommendationsFor(any(), any(), any(), any()) }
    }

    @Test
    fun youtubeSource_neverAsksSpotify() = runTest {
        val repo = repository(RecommendationSource.YOUTUBE_MUSIC, connected = true)

        val result = repo.relatedFor(seedVideoId = "seed", title = "Song", artist = "Artist")

        assertThat(result).containsExactly(youtubeTrack)
        coVerify(exactly = 0) { spotify.recommendationsFor(any(), any(), any(), any()) }
    }
}
