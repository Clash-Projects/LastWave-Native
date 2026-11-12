package com.lastwave.app.data.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalPlaylistLinkTest {

    @Test
    fun detectsAndExtractsStandardSpotifyUrl() {
        val url = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abcd1234"
        assertEquals(ExternalPlaylistSource.SPOTIFY, ExternalPlaylistLink.detect(url))
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", ExternalPlaylistLink.extractId(url, ExternalPlaylistSource.SPOTIFY))
    }

    @Test
    fun detectsAndExtractsIntlSpotifyUrl() {
        val url = "https://open.spotify.com/intl-es/playlist/37i9dQZF1DXcBWIGoYBM5M"
        assertEquals(ExternalPlaylistSource.SPOTIFY, ExternalPlaylistLink.detect(url))
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", ExternalPlaylistLink.extractId(url, ExternalPlaylistSource.SPOTIFY))
    }

    @Test
    fun detectsAndExtractsUserPlaylistUrl() {
        val url = "https://open.spotify.com/user/spotify/playlist/37i9dQZF1DXcBWIGoYBM5M"
        assertEquals(ExternalPlaylistSource.SPOTIFY, ExternalPlaylistLink.detect(url))
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", ExternalPlaylistLink.extractId(url, ExternalPlaylistSource.SPOTIFY))
    }

    @Test
    fun detectsAndExtractsSpotifyUri() {
        val uri = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"
        assertEquals(ExternalPlaylistSource.SPOTIFY, ExternalPlaylistLink.detect(uri))
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", ExternalPlaylistLink.extractId(uri, ExternalPlaylistSource.SPOTIFY))
    }

    @Test
    fun detectsAndExtractsBareSpotifyId() {
        val id = "37i9dQZF1DXcBWIGoYBM5M"
        assertEquals(ExternalPlaylistSource.SPOTIFY, ExternalPlaylistLink.detect(id))
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", ExternalPlaylistLink.extractId(id, ExternalPlaylistSource.SPOTIFY))
    }

    @Test
    fun detectsAndExtractsAppleMusicUrl() {
        val url = "https://music.apple.com/us/playlist/today-hits/pl.d95f87b8f04746f1ac3dc44ff4e69b2d"
        assertEquals(ExternalPlaylistSource.APPLE_MUSIC, ExternalPlaylistLink.detect(url))
        assertEquals("pl.d95f87b8f04746f1ac3dc44ff4e69b2d", ExternalPlaylistLink.extractId(url, ExternalPlaylistSource.APPLE_MUSIC))
    }

    @Test
    fun returnsNullForInvalidLink() {
        val invalid = "https://youtube.com/watch?v=12345"
        assertNull(ExternalPlaylistLink.detect(invalid))
    }

    @Test
    fun detectsSpotifyLikedSongsLinks() {
        org.junit.Assert.assertTrue(
            ExternalPlaylistLink.isSpotifyLiked("https://open.spotify.com/collection/tracks")
        )
        org.junit.Assert.assertTrue(
            ExternalPlaylistLink.isSpotifyLiked("spotify:collection:tracks")
        )
        org.junit.Assert.assertTrue(
            ExternalPlaylistLink.isSpotifyLiked("spotify:liked")
        )
        org.junit.Assert.assertFalse(
            ExternalPlaylistLink.isSpotifyLiked("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        )
    }
}
