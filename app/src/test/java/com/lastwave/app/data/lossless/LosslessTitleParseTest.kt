package com.lastwave.app.data.lossless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YouTube video uploads put the song first and pile movie/singer/handle
 * segments after pipes. The search query must be the song, not the tail.
 * Cases are real titles that previously searched for the wrong thing.
 */
class LosslessTitleParseTest {

    private fun query(title: String, artist: String): String {
        val p = LosslessMusicApi.parseTitle(title, artist)
        return (p.words + p.versions).joinToString(" ")
    }

    private val cases = listOf(
        Triple("Vaaroon Forever (Official Video) | Mirzapur The Movie | Shreya Ghoshal | Romy | Anand B | Ginny D", "Anand Bhaskar", "vaaroon forever"),
        Triple("Dil Ho Tum (Visualizer) |  Rishi Kumar | @hansikaapareek | @AnuragMishramusic | Hindi Love song", "Rishi Kumar", "dil ho tum"),
        Triple("Pesamale - Official Music Video Song | Siri Xander | Arra Aria Khayal |Tamil Indie Pop", "Siri Xander", "pesamale"),
        Triple("The Revolutionaries: Maahiya (Song) | Rohit Saraf,Pratibha Ranta| Akashdeep,Tushar Joshi,Neeti Mohan", "Neeti Mohan", "maahiya"),
        Triple("Full Video: Kesariya | Brahmastra | Ranbir Kapoor | Alia Bhatt | Pritam | Arijit Singh", "Arijit Singh", "kesariya"),
        Triple("Arijit Singh: Tum Hi Ho | Aashiqui 2 | Lyrical Video", "Arijit Singh", "tum hi ho"),
        Triple("Lyrical: Apna Bana Le | Bhediya | Varun Dhawan, Kriti Sanon | Sachin-Jigar, Arijit Singh", "Arijit Singh", "apna bana le"),
        Triple("Heeriye (Official Video) Jasleen Royal ft Arijit Singh | Dulquer Salmaan | Aditya Sharma |Taani Tanvir", "Jasleen Royal", "heeriye"),
        Triple("KARAN AUJLA - SOFTLY (Official Video) | Making Memories | Latest Punjabi Songs 2023", "Karan Aujla", "softly"),
        Triple("Chaleya (Hindi) | Jawan | Shah Rukh Khan | Nayanthara | Atlee | Anirudh | Arijit S, Shilpa R | Kumaar", "Arijit Singh", "chaleya hindi"),
        Triple("Satranga | ANIMAL | Ranbir Kapoor, Rashmika | Arijit Singh, Shreyas P, Siddharth-Garima | Bhushan K", "Arijit Singh", "satranga"),
        Triple("Ve Kamleya - Lyrical | Rocky Aur Rani Kii Prem Kahaani | Arijit, Shreya | Pritam", "Arijit Singh", "ve kamleya"),
        Triple("Arjan Vailly | Animal | Ranbir Kapoor | Bhupinder Babbal | Manan Bhardwaj", "Bhupinder Babbal", "arjan vailly"),
        Triple("Arijit Singh | Kesariya", "Arijit Singh", "kesariya"),
        // Non-pipe titles must not change.
        Triple("Animal - Arjan Vailly", "Bhupinder Babbal", "arjan vailly"),
        Triple("Saiyaara", "Shreya Ghoshal", "saiyaara"),
        Triple("Kaniya", "Alok Ranjan Srivastava", "kaniya"),
        Triple("Shape of You", "Ed Sheeran", "shape of you"),
    )

    @Test
    fun `real video titles search for the song`() {
        val wrong = cases.mapNotNull { (title, artist, expected) ->
            val got = query(title, artist)
            if (got == expected) null else "'$title' -> '$got' (expected '$expected')"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `version in a later pipe segment is kept as a version`() {
        val parts = LosslessMusicApi.parseTitle("Kesariya | Remix", "Arijit Singh")
        assertEquals("kesariya", parts.words.joinToString(" "))
        assertTrue("remix" in parts.versions)
    }

    @Test
    fun `trailing genre tag is context, not a version`() {
        val parts = LosslessMusicApi.parseTitle("Dil Ho Tum | Hindi Love song", "Rishi Kumar")
        assertTrue("hindi" !in parts.versions)
    }
}
