package com.lastwave.app.data.generate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for Liked Songs duplication: the same song arrived with a
 * different identity depending on source (heart-tap has url="", YT import
 * carries ?v=..., sync matches by videoId), so every re-import stacked
 * another copy — including onto the YouTube side via sync push.
 */
class TrackIdentityTest {

    @Test
    fun keyNormalizesWhitespaceAndCase() {
        val spaced = GeneratedTrack(name = "  Round  2 ", artist = "JaeyBxrd ")
        val clean = GeneratedTrack(name = "round 2", artist = "jaeybxrd")
        assertEquals(clean.key, spaced.key)
    }

    @Test
    fun sameSongAcrossSources() {
        // Heart-tap from playback carries no videoId.
        val heart = GeneratedTrack(name = "Round 2", artist = "JaeyBxrd")
        // YT liked import carries the watch URL for the same song.
        val imported = GeneratedTrack(
            name = "Round 2 ",
            artist = " jaeybxrd",
            url = "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
        )
        assertTrue(heart.sameSongAs(imported))
        assertTrue(imported.sameSongAs(heart))
    }

    @Test
    fun sameVideoIdWinsDespiteTitleDrift() {
        // Bare IDs take the framework-independent regex path, so this covers
        // the videoId branch under plain unit tests (Uri is stubbed there).
        val official = GeneratedTrack(
            name = "Round 2 (Official Video)",
            artist = "JaeyBxrd - Topic",
            url = "dQw4w9WgXcQ",
        )
        val plain = GeneratedTrack(
            name = "Round 2",
            artist = "JaeyBxrd",
            url = "dQw4w9WgXcQ",
        )
        assertTrue(official.sameSongAs(plain))
    }

    @Test
    fun differentSongsAreNotSame() {
        val a = GeneratedTrack(name = "Round 2", artist = "JaeyBxrd")
        val b = GeneratedTrack(name = "Round 3", artist = "JaeyBxrd")
        val c = GeneratedTrack(name = "Round 2", artist = "SomeoneElse")
        assertFalse(a.sameSongAs(b))
        assertFalse(a.sameSongAs(c))
    }

    @Test
    fun distinctSongsKeepsFirstOccurrence() {
        val poor = GeneratedTrack(name = "Round 2 ", artist = " JaeyBxrd")
        val rich = GeneratedTrack(
            name = "Round 2",
            artist = "JaeyBxrd",
            url = "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
        )
        val other = GeneratedTrack(name = "Freaked Out", artist = "Fat Papi")
        val healed = listOf(poor, rich, other, poor.copy()).distinctSongs()
        assertEquals(listOf(poor, other), healed)
    }

    @Test
    fun distinctSongsCollapsesRepeatedVideoIds() {
        val a = GeneratedTrack(name = "A", artist = "X", url = "dQw4w9WgXcQ")
        val b = GeneratedTrack(name = "A (Official Video)", artist = "X", url = "dQw4w9WgXcQ")
        assertEquals(listOf(a), listOf(a, b).distinctSongs())
    }

    @Test
    fun sameSongWithoutVideoIdMatchesCoreTitleAndArtist() {
        val ytTitle = GeneratedTrack(name = "Starboy (Official Music Video)", artist = "The Weeknd - Topic")
        val plainTitle = GeneratedTrack(name = "Starboy", artist = "The Weeknd")
        assertTrue(ytTitle.sameSongAs(plainTitle))
        assertTrue(plainTitle.sameSongAs(ytTitle))
    }

    @Test
    fun distinctSongsCollapsesCoreTitleVariantsWithoutVideoId() {
        val plain = GeneratedTrack(name = "Starboy", artist = "The Weeknd")
        val video = GeneratedTrack(name = "Starboy (Official Audio)", artist = "The Weeknd")
        val other = GeneratedTrack(name = "Blinding Lights", artist = "The Weeknd")
        val result = listOf(plain, video, other).distinctSongs()
        assertEquals(listOf(plain, other), result)
    }
}
