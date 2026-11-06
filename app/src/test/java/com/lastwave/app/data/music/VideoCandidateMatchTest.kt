package com.lastwave.app.data.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCandidateMatchTest {

    private fun matches(
        title: String,
        channel: String = "Label Records",
        seconds: Int? = null,
        song: String,
        artist: String = "Asha Singer",
        durationMs: Long? = null,
    ) = isSameSongVideo(title, channel, seconds, song, artist, durationMs)

    @Test
    fun rejectsADifferentSongWithASimilarName() {
        assertFalse(matches("Haseena", channel = "Crishna", seconds = 200, song = "Hosanna", artist = "Crishna", durationMs = 200_000))
    }

    @Test
    fun rejectsAnOfficialVideoForAnotherSong() {
        assertFalse(
            matches(
                "Abhijay Sharma - sheher (Official Music Video) | dir. harsh",
                channel = "Abhijay Sharma",
                seconds = 180,
                song = "Qissa",
                artist = "Abhijay Sharma",
                durationMs = 180_000,
            ),
        )
    }

    @Test
    fun acceptsTitleWithExtrasWhenItNamesTheArtist() {
        assertTrue(matches("Aasmaan Jaley(Official Video) | Abhay J | Abhijeet S", song = "Aasmaan Jaley", artist = "Abhijeet Srivastava"))
    }

    @Test
    fun acceptsLabelUploadWhenLengthMatches() {
        assertTrue(matches("Kasturi | Amar Prem Ki Prem Kahani", channel = "Tips", seconds = 240, song = "Kasturi", durationMs = 233_000))
    }

    @Test
    fun rejectsSameTitleWithOtherArtistAndLength() {
        assertFalse(matches("Kaagaz", channel = "Other Person", seconds = 400, song = "Kaagaz", durationMs = 205_000))
    }

    @Test
    fun rejectsWhenLengthIsUnknownAndArtistIsAbsent() {
        assertFalse(matches("Kaagaz", song = "Kaagaz", durationMs = 205_000))
    }

    @Test
    fun titleMustMatchWholeWords() {
        assertFalse(matches("Belonging", channel = "Asha Singer", song = "Belong"))
    }

    @Test
    fun shortArtistWordsDoNotCount() {
        assertFalse(matches("Kaagaz", channel = "The Other", song = "Kaagaz", artist = "The Singer"))
    }

    @Test
    fun otherRenditionDoesNotMatchAVersionedSong() {
        assertFalse(matches("Suroor", channel = "Asha Singer", song = "Suroor (2 am Version)"))
    }

    @Test
    fun matchesNonLatinTitles() {
        assertTrue(matches("तुम ही हो | Official Video", channel = "अरिजीत सिंह", song = "तुम ही हो", artist = "अरिजीत सिंह"))
    }

    @Test
    fun blankTitleNeverMatches() {
        assertFalse(matches("Anything", song = "  "))
    }
}
