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
}
