package com.lastwave.app.data.repository

import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.network.LastFmApiService
import com.lastwave.app.playback.PlayableTrack
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistExtendedTracksTest {

    @Test
    fun matchesExactAndNormalizedArtistNames() {
        assertTrue(isTrackByOrFeaturingArtist("Eminem", "Houdini", "Eminem"))
        assertTrue(isTrackByOrFeaturingArtist("Beyoncé", "Halo", "beyonce"))
        assertTrue(isTrackByOrFeaturingArtist("AC/DC", "Back in Black", "ACDC"))
    }

    @Test
    fun matchesArtistInMultiArtistCredits() {
        assertTrue(isTrackByOrFeaturingArtist("Drake & 21 Savage", "Rich Flex", "21 Savage"))
        assertTrue(isTrackByOrFeaturingArtist("David Guetta feat. Sia", "Titanium", "Sia"))
        assertTrue(isTrackByOrFeaturingArtist("Coldplay x BTS", "My Universe", "BTS"))
    }

    @Test
    fun matchesFeaturingInTrackTitles() {
        assertTrue(isTrackByOrFeaturingArtist("Taylor Swift", "Fortnight (feat. Post Malone)", "Post Malone"))
        assertTrue(isTrackByOrFeaturingArtist("Metro Boomin", "Creepin' (with The Weeknd)", "The Weeknd"))
        assertTrue(isTrackByOrFeaturingArtist("Travis Scott", "SICKO MODE ft. Drake", "Drake"))
    }

    @Test
    fun rejectsUnrelatedArtistsAndSubstringCollisions() {
        assertFalse(isTrackByOrFeaturingArtist("Steve Aoki", "Cake Face", "Eve"))
        assertFalse(isTrackByOrFeaturingArtist("The Beatles", "Yesterday", "Queen"))
        assertFalse(isTrackByOrFeaturingArtist("Post Malone", "Circles", "Drake"))
    }

    @Test
    fun getArtistExtendedTracksAppendsMatchingSongsAndPreservesSeedTracks() = runBlocking {
        val innerTube = mockk<InnerTubeMusicApi>(relaxed = true)
        val relatedGraph = mockk<RelatedGraphRepository>(relaxed = true)
        val lastFm = mockk<LastFmApiService>(relaxed = true)
        val sessionPrefs = mockk<com.lastwave.app.data.local.SessionPreferences>(relaxed = true)

        val repo = ArtistRepository(innerTube, relatedGraph, lastFm, sessionPrefs)

        val seed = listOf(
            PlayableTrack(title = "Top Song 1", artist = "Queen", videoId = "v1"),
            PlayableTrack(title = "Top Song 2", artist = "Queen", videoId = "v2"),
        )

        val searchTracks = listOf(
            YouTubeMusicTrack(title = "Top Song 1", artist = "Queen", videoId = "v1"), // Duplicate of seed
            YouTubeMusicTrack(title = "Bohemian Rhapsody", artist = "Queen", videoId = "v3"),
            YouTubeMusicTrack(title = "Under Pressure", artist = "Queen & David Bowie", videoId = "v4"),
            YouTubeMusicTrack(title = "Random Track", artist = "Random Artist", videoId = "v5"), // Unrelated
        )

        coEvery { innerTube.searchSongs("Queen", limit = any(), prefetchStreams = false) } returns searchTracks
        coEvery { innerTube.searchSongs("Queen feat", limit = any(), prefetchStreams = false) } returns emptyList()

        val result = repo.getArtistExtendedTracks(
            artistName = "Queen",
            seedTracks = seed,
            limit = 10,
        )

        assertEquals(4, result.size)
        assertEquals("v1", result[0].videoId)
        assertEquals("v2", result[1].videoId)
        assertEquals("v3", result[2].videoId)
        assertEquals("v4", result[3].videoId)
    }
}
