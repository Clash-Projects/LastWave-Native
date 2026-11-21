package com.lastwave.app.ui.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricsSignatureTest {

    @Test
    fun intro_showsFromTheStartOfALongIntro() {
        assertThat(shouldShowSignatureIntro(introEndMs = 8_000L, positionMs = 0L)).isTrue()
    }

    @Test
    fun intro_showsWithExactlyTheThresholdLeft() {
        assertThat(shouldShowSignatureIntro(introEndMs = 8_000L, positionMs = 8_000L - LYRICS_INTRO_MIN_MS)).isTrue()
    }

    @Test
    fun intro_skipsWhenLyricsArriveLateInTheIntro() {
        // Loading finished 1 s before the first line: the card would only flash.
        assertThat(shouldShowSignatureIntro(introEndMs = 8_000L, positionMs = 7_000L)).isFalse()
    }

    @Test
    fun intro_skipsAfterTheFirstLine() {
        assertThat(shouldShowSignatureIntro(introEndMs = 8_000L, positionMs = 9_000L)).isFalse()
    }

    @Test
    fun intro_showsAgainAfterSeekingBackToTheStart() {
        assertThat(shouldShowSignatureIntro(introEndMs = 8_000L, positionMs = 7_500L)).isFalse()
        assertThat(shouldShowSignatureIntro(introEndMs = 8_000L, positionMs = 500L)).isTrue()
    }

    @Test
    fun title_keepsOnlyTheMainName() {
        assertThat(signatureTitle("Flower In A Hurricane [From GTAVI: Original Score]")).isEqualTo("Flower In A Hurricane")
        assertThat(signatureTitle("Round 2 (feat. Someone)")).isEqualTo("Round 2")
        assertThat(signatureTitle("Song - Remastered 2011")).isEqualTo("Song")
        assertThat(signatureTitle("Song feat. Someone")).isEqualTo("Song")
        assertThat(signatureTitle("Hip-Hop Hooray")).isEqualTo("Hip-Hop Hooray")
        assertThat(signatureTitle("(Untitled)")).isEqualTo("(Untitled)")
    }

    @Test
    fun provider_usesTheProviderTitle() {
        assertThat(lyricsProviderLabel("Apple Music (Line-Sync)")).isEqualTo("Apple Music")
        assertThat(lyricsProviderLabel("LRCLIB (Plain)")).isEqualTo("LRCLIB")
        assertThat(lyricsProviderLabel("Kugou KRC (Word-Sync)")).isEqualTo("Kugou")
        assertThat(lyricsProviderLabel("Spotify (Musixmatch)")).isEqualTo("Spotify")
    }

    @Test
    fun provider_unknownSourceDropsTheSyncSuffix() {
        assertThat(lyricsProviderLabel("NewSource (Word-Sync)")).isEqualTo("NewSource")
    }

    @Test
    fun provider_nothingToCreditForDeviceFilesOrMissingSource() {
        assertThat(lyricsProviderLabel("Local LRC")).isNull()
        assertThat(lyricsProviderLabel("Downloaded Lyrics (LRC)")).isNull()
        assertThat(lyricsProviderLabel(null)).isNull()
        assertThat(lyricsProviderLabel("  ")).isNull()
    }
}
