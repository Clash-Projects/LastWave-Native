package com.lastwave.app.ui.player.lyricsshare

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtworkResolutionOrderTest {

    @Test
    fun determineArtworkUrl_localFile_takesPrecedence() {
        val track = TrackInfo(
            title = "Local Song",
            artist = "Local Artist",
            artworkUrl = "file:///storage/emulated/0/Music/cover.jpg",
            resolvedArtworkUrl = "https://i.ytimg.com/vi/123/hqdefault.jpg",
        )
        val url = ArtworkResolver.determineArtworkUrl(track)
        assertThat(url).isEqualTo("file:///storage/emulated/0/Music/cover.jpg")
    }

    @Test
    fun determineArtworkUrl_trackArtworkUrl_takesPrecedenceOverResolved() {
        val track = TrackInfo(
            title = "Online Song",
            artist = "Artist",
            artworkUrl = "https://lh3.googleusercontent.com/track_art=w544-h544",
            resolvedArtworkUrl = "https://lh3.googleusercontent.com/ambient_art=w544-h544",
        )
        val url = ArtworkResolver.determineArtworkUrl(track)
        assertThat(url).contains("track_art")
    }

    @Test
    fun determineArtworkUrl_resolvedAmbientUsedWhenTrackUrlIsBlankOrPlaceholder() {
        val track = TrackInfo(
            title = "Online Song",
            artist = "Artist",
            artworkUrl = "", // Blank
            resolvedArtworkUrl = "https://lh3.googleusercontent.com/ambient_art=w544-h544",
        )
        val url = ArtworkResolver.determineArtworkUrl(track)
        assertThat(url).contains("ambient_art")
    }

    @Test
    fun determineArtworkUrl_upscalesYouTubeUrls() {
        val track = TrackInfo(
            title = "YouTube Song",
            artist = "Artist",
            artworkUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            resolvedArtworkUrl = null,
        )
        val url = ArtworkResolver.determineArtworkUrl(track)
        // ArtworkNormalizer upscales hqdefault to maxresdefault
        assertThat(url).contains("maxresdefault.jpg")
    }

    @Test
    fun determineArtworkUrl_returnsNullWhenNoValidArtwork() {
        val track = TrackInfo(
            title = "No Art",
            artist = "Artist",
            artworkUrl = null,
            resolvedArtworkUrl = null,
        )
        val url = ArtworkResolver.determineArtworkUrl(track)
        assertThat(url).isNull()
    }

    @Test
    fun createBrandFallbackGradient_returnsValidBitmap() {
        val gradient = ArtworkResolver.createBrandFallbackGradient(
            accentColor = ColorContrastHelper.BRAND_LIME,
            width = 100,
            height = 100,
        )
        assertThat(gradient).isNotNull()
        assertThat(gradient.width).isEqualTo(100)
        assertThat(gradient.height).isEqualTo(100)
    }
}
