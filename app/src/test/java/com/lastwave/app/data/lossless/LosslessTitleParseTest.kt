package com.lastwave.app.data.lossless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YouTube video uploads put the song first and pile movie/singer/handle
 * segments after pipes. The search query must be the song, not the tail.
 */
class LosslessTitleParseTest {

    private fun words(title: String, artist: String) =
        LosslessMusicApi.parseTitle(title, artist).words.joinToString(" ")

    @Test
    fun `pipe title keeps the song, not the trailing singer names`() {
        assertEquals(
            "vaaroon forever",
            words(
                "Vaaroon Forever (Official Video) | Mirzapur The Movie | Shreya Ghoshal | Romy | Anand B | Ginny D",
                "Anand Bhaskar",
            ),
        )
    }

    @Test
    fun `pipe title drops handles and genre tags`() {
        assertEquals(
            "dil ho tum",
            words(
                "Dil Ho Tum (Visualizer) |  Rishi Kumar | @hansikaapareek | @AnuragMishramusic | Hindi Love song",
                "Rishi Kumar",
            ),
        )
    }

    @Test
    fun `artist-first pipe title still finds the song`() {
        assertEquals("kesariya", words("Arijit Singh | Kesariya", "Arijit Singh"))
    }

    @Test
    fun `version in a later pipe segment is kept as a version`() {
        val parts = LosslessMusicApi.parseTitle("Kesariya | Remix", "Arijit Singh")
        assertEquals("kesariya", parts.words.joinToString(" "))
        assertTrue("remix" in parts.versions)
    }

    @Test
    fun `plain and dash titles are unchanged`() {
        assertEquals("kaniya", words("Kaniya", "Alok Ranjan Srivastava"))
        assertEquals("arjan vailly", words("Animal - Arjan Vailly", "Bhupinder Babbal"))
    }
}
