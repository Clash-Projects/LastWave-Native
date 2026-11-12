package com.lastwave.app.data.artwork

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArtworkSearchTermsTest {

    @Test
    fun stripsYoutubeUploadNoise() {
        assertThat(ArtworkNormalizer.searchTerms("Taylor Swift - Love Story (Official Music Video)", "TaylorSwiftVEVO"))
            .isEqualTo("Love Story" to "TaylorSwift")
        assertThat(ArtworkNormalizer.searchTerms("Blinding Lights", "The Weeknd - Topic"))
            .isEqualTo("Blinding Lights" to "The Weeknd")
        assertThat(ArtworkNormalizer.searchTerms("Song | From The Movie", "Someone"))
            .isEqualTo("Song" to "Someone")
    }

    @Test
    fun keepsRealTitles() {
        assertThat(ArtworkNormalizer.searchTerms("Bohemian Rhapsody - Remastered 2011", "Queen"))
            .isEqualTo("Bohemian Rhapsody - Remastered 2011" to "Queen")
        assertThat(ArtworkNormalizer.searchTerms("Love Story (Taylor's Version)", "Taylor Swift"))
            .isEqualTo("Love Story (Taylor's Version)" to "Taylor Swift")
    }

    @Test
    fun upgradesLastFmSize() {
        assertThat(ArtworkNormalizer.upgradeLastFm("https://lastfm.freetls.fastly.net/i/u/300x300/abc.jpg"))
            .isEqualTo("https://lastfm.freetls.fastly.net/i/u/770x0/abc.jpg")
        assertThat(ArtworkNormalizer.upgradeLastFm("https://example.com/i/u/300x300/abc.jpg"))
            .isEqualTo("https://example.com/i/u/300x300/abc.jpg")
    }
}
