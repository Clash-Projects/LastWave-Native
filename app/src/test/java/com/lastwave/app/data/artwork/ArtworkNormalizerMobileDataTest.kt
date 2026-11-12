package com.lastwave.app.data.artwork

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkNormalizerMobileDataTest {
    @Test
    fun googleThumbnailUsesTheSmallerMobileVariant() {
        val highResolution = "https://lh3.googleusercontent.com/art=w1200-h1200-l90-rj"

        assertEquals(
            "https://lh3.googleusercontent.com/art=w320-h320-l90-rj",
            ArtworkNormalizer.mobileDataArtwork(highResolution, null),
        )
    }

    @Test
    fun youtubeMaxResolutionThumbnailFallsBackToHighQuality() {
        val highResolution = "https://i.ytimg.com/vi/video-id/maxresdefault.jpg"

        assertEquals(
            "https://i.ytimg.com/vi/video-id/hqdefault.jpg",
            ArtworkNormalizer.mobileDataArtwork(highResolution, null),
        )
    }

    @Test
    fun unsupportedPrimaryProviderUsesDistinctEmbeddedThumbnail() {
        val embedded = "https://i.ytimg.com/vi/video-id/maxresdefault.jpg"

        assertEquals(
            "https://i.ytimg.com/vi/video-id/hqdefault.jpg",
            ArtworkNormalizer.mobileDataArtwork("https://i.scdn.co/image/art", embedded),
        )
    }

    @Test
    fun appleArtworkUsesACompactImageVariant() {
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music123/300x300bb.jpg",
            ArtworkNormalizer.mobileDataArtwork(
                "https://is1-ssl.mzstatic.com/image/thumb/Music123/1200x1200bb.jpg",
                null,
            ),
        )
    }

    @Test
    fun tidalAndDeezerArtworkUseTheirSmallerSizes() {
        assertEquals(
            "https://resources.tidal.com/images/cover-id/320x320.jpg",
            ArtworkNormalizer.mobileDataArtwork("https://resources.tidal.com/images/cover-id/1280x1280.jpg", null),
        )
        assertEquals(
            "https://cdn-images.dzcdn.net/images/cover/cover-id/250x250-000000-80-0-0.jpg",
            ArtworkNormalizer.mobileDataArtwork("https://cdn-images.dzcdn.net/images/cover/cover-id/1000x1000-000000-80-0-0.jpg", null),
        )
    }

    @Test
    fun lastFmArtworkUsesACompactSizeSegment() {
        assertEquals(
            "https://lastfm.freetls.fastly.net/i/u/300x300/cover.jpg",
            ArtworkNormalizer.mobileDataArtwork(
                "https://lastfm.freetls.fastly.net/i/u/770x0/cover.jpg",
                null,
            ),
        )
    }
}
