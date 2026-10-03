package com.lastwave.app.data.artwork

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.lyrics.LrclibLyricsApi
import org.junit.Test

class ArtworkMatchingTest {

    @Test
    fun testHighResProvidersSet() {
        assertThat(ArtworkNormalizer.HIGH_RES_PROVIDERS).containsExactly(
            "spotify", "apple", "itunes", "tidal", "deezer"
        )
        assertThat(ArtworkNormalizer.HIGH_RES_PROVIDERS).doesNotContain("youtube")
        assertThat(ArtworkNormalizer.HIGH_RES_PROVIDERS).doesNotContain("none")
    }

    @Test
    fun testUpscaleYoutubeArtwork() {
        val lowResGoogle = "https://lh3.googleusercontent.com/test_image=w120-h120-l90-rj"
        assertThat(ArtworkNormalizer.upscaleYoutubeArtwork(lowResGoogle))
            .isEqualTo("https://lh3.googleusercontent.com/test_image=s1200")

        val s544Google = "https://lh3.googleusercontent.com/test_image=s544"
        assertThat(ArtworkNormalizer.upscaleYoutubeArtwork(s544Google))
            .isEqualTo("https://lh3.googleusercontent.com/test_image=s1200")

        val hqDefaultYt = "https://i.ytimg.com/vi/abc12345/hqdefault.jpg"
        assertThat(ArtworkNormalizer.upscaleYoutubeArtwork(hqDefaultYt))
            .isEqualTo("https://i.ytimg.com/vi/abc12345/maxresdefault.jpg")

        val nullUrl: String? = null
        assertThat(ArtworkNormalizer.upscaleYoutubeArtwork(nullUrl)).isNull()
    }

    @Test
    fun testZeroMetadataMismatchHomonymsAndVersions() {
        val reqTitle = "Anti-Hero"
        val reqArtist = "Taylor Swift"

        // Legitimate match
        val candTitle = "Anti-Hero"
        val candArtist = "Taylor Swift"
        val titleMatches = LrclibLyricsApi.titlesMatch(
            LrclibLyricsApi.cleanTrackTitle(candTitle),
            LrclibLyricsApi.cleanTrackTitle(reqTitle),
        )
        val versionMatches = LrclibLyricsApi.sameVersion(reqTitle, candTitle)
        val artistMatches = LrclibLyricsApi.artistMatches(
            LrclibLyricsApi.cleanArtistName(candArtist),
            LrclibLyricsApi.cleanArtistName(reqArtist),
        )
        assertThat(titleMatches && versionMatches && artistMatches).isTrue()

        // Homonym by different artist must be rejected
        val homonymArtist = "Some Other Band"
        val homonymArtistMatches = LrclibLyricsApi.artistMatches(
            LrclibLyricsApi.cleanArtistName(homonymArtist),
            LrclibLyricsApi.cleanArtistName(reqArtist),
        )
        assertThat(homonymArtistMatches).isFalse()

        // Remix must be rejected when original is requested
        val remixTitle = "Anti-Hero (Kungs Remix)"
        val remixVersionMatches = LrclibLyricsApi.sameVersion(reqTitle, remixTitle)
        assertThat(remixVersionMatches).isFalse()

        // Acoustic version must be rejected when original is requested
        val acousticTitle = "Anti-Hero (Acoustic Version)"
        val acousticVersionMatches = LrclibLyricsApi.sameVersion(reqTitle, acousticTitle)
        assertThat(acousticVersionMatches).isFalse()

        // Cover version must be rejected
        val coverTitle = "Anti-Hero (Cover)"
        val coverVersionMatches = LrclibLyricsApi.sameVersion(reqTitle, coverTitle)
        assertThat(coverVersionMatches).isFalse()

        // Karaoke version must be rejected
        val karaokeTitle = "Anti-Hero (Karaoke Version)"
        val karaokeVersionMatches = LrclibLyricsApi.sameVersion(reqTitle, karaokeTitle)
        assertThat(karaokeVersionMatches).isFalse()
    }

    @Test
    fun testTidalImageUuidSplitting() {
        val coverUuid = "699ee144-c4ed-4da0-83fd-87fb736bb9a7"
        val parts = coverUuid.split("-")
        assertThat(parts).hasSize(5)
        val url = "https://resources.tidal.com/images/${parts.joinToString("/")}/1280x1280.jpg"
        assertThat(url).isEqualTo("https://resources.tidal.com/images/699ee144/c4ed/4da0/83fd/87fb736bb9a7/1280x1280.jpg")
    }

    @Test
    fun testSpotifyArtworkUpscalePattern() {
        val oembedThumb = "https://image-cdn-fa.spotifycdn.com/image/ab67616d00001e02baf89eb11ec7c657805d2da0"
        val upscaled = oembedThumb.replace("ab67616d00001e02", "ab67616d0000b273")
        assertThat(upscaled).isEqualTo("https://image-cdn-fa.spotifycdn.com/image/ab67616d0000b273baf89eb11ec7c657805d2da0")
    }
}
