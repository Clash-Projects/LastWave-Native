package com.lastwave.app.data.canvas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CanvasMatchingTest {

    @Test
    fun testNormalizeForMatch() {
        val raw = "Beyoncé - CRAZY IN LOVE (feat. JAY-Z)"
        val normalized = raw.normalizeForMatch()
        assertThat(normalized).isEqualTo("beyonce crazy in love feat jay z")
    }

    @Test
    fun testSplitArtists() {
        val artists1 = splitArtists("Taylor Swift feat. Post Malone")
        assertThat(artists1).containsExactly("taylor swift", "post malone")

        val artists2 = splitArtists("Daft Punk & Pharrell Williams")
        assertThat(artists2).containsExactly("daft punk", "pharrell williams")

        val artists3 = splitArtists("Marshmello x Khalid")
        assertThat(artists3).containsExactly("marshmello", "khalid")
    }

    @Test
    fun testCanvasArtworkMatches() {
        val canvas = CanvasArtwork(
            url = "https://example.com/loop.mp4",
            title = "Crazy In Love",
            artist = "Beyoncé ft. Jay-Z",
            album = "Dangerously in Love",
            source = CanvasSource.APPLE,
        )

        // Matching with minor punctuation/case variations
        val isMatch = canvas.matches(
            wantTitle = "Crazy in Love",
            wantArtist = "Beyonce",
            wantAlbum = "Dangerously In Love",
        )
        assertThat(isMatch).isTrue()

        // Mismatched title should be rejected
        val wrongTitle = canvas.matches(
            wantTitle = "Halo",
            wantArtist = "Beyonce",
            wantAlbum = "Dangerously In Love",
        )
        assertThat(wrongTitle).isFalse()
    }

    @Test
    fun testTidalCoverUrlFormatting() {
        val uuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        val expected = "https://resources.tidal.com/videos/a1b2c3d4/e5f6/7890/abcd/ef1234567890/1280x1280.mp4"
        val url = TidalCanvas.coverUrl(uuid)
        assertThat(url).isEqualTo(expected)

        // Malformed UUID returns null
        assertThat(TidalCanvas.coverUrl("invalid-uuid")).isNull()
    }
}
