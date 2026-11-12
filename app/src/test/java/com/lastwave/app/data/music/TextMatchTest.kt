package com.lastwave.app.data.music

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for issue #102 (Cyrillic / non-Latin search and
 * matching). The matcher previously used a Latin-only word pattern, which
 * reduced every non-Latin query to blank.
 */
class TextMatchTest {

    @Test
    fun normalizeKeepsCyrillic() {
        assertEquals("анна герман", TextMatch.normalize("Анна Герман"))
    }

    @Test
    fun normalizeKeepsCjkAndArabic() {
        assertEquals("周杰伦", TextMatch.normalize("周杰伦"))
        // Combining hamza is stripped like other marks, so أ/ا match alike.
        assertEquals("ام كلثوم", TextMatch.normalize("أم كلثوم"))
    }

    @Test
    fun normalizeStripsDiacriticsButKeepsBaseLetters() {
        assertEquals("cafe", TextMatch.normalize("Café"))
        assertEquals("beyonce", TextMatch.normalize("Beyoncé"))
    }

    @Test
    fun normalizeIsLocaleIndependent() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale("tr", "TR"))
        try {
            // Without Locale.ROOT, "I".lowercase() is "ı" in Turkish.
            assertEquals("i", TextMatch.normalize("I"))
            assertEquals("анна", TextMatch.normalize("АННА"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun tokensSplitCyrillicWords() {
        assertEquals(setOf("анна", "герман"), TextMatch.tokens("Анна Герман"))
    }

    @Test
    fun similarityScoresIdenticalCyrillicAtMax() {
        assertEquals(100, TextMatch.similarity("Анна Герман", "Анна Герман"))
        assertTrue(TextMatch.similarity("Анна Герман", "Владимир Высоцкий") < 100)
    }

    @Test
    fun matchScorePrefersExactCyrillicCandidate() {
        val exact = YouTubeMusicTrack("vid1", "Надежда", "Анна Герман")
        val other = YouTubeMusicTrack("vid2", "Катюша", "Владимир Высоцкий")
        assertTrue(
            TextMatch.matchScore(exact, "Надежда", "Анна Герман") >
                TextMatch.matchScore(other, "Надежда", "Анна Герман"),
        )
    }

    @Test
    fun latinBehaviorIsUnchanged() {
        assertEquals("hello world", TextMatch.normalize("Hello, World!"))
        assertEquals(100, TextMatch.similarity("Hello", "hello"))
        assertEquals(setOf("hello", "world"), TextMatch.tokens("Hello World (Official Video)"))
    }

    @Test
    fun arabicHamzaFoldsToBareAlef() {
        // U+0623 has no NFD decomposition, so mark-stripping alone never folds
        // it: searching أم must match ام.
        assertEquals("ام كلثوم", TextMatch.normalize("أم كلثوم"))
    }

    @Test
    fun hindiVowelSignsAreNotStripped() {
        // Matras are integral letters: तुम and तम are different words.
        assertEquals("तुम ही हो", TextMatch.normalize("तुम ही हो"))
        assertTrue(TextMatch.normalize("तुम ही हो") != TextMatch.normalize("तम ही हो"))
    }

    @Test
    fun koreanStaysComposed() {
        // NFD explodes 5 syllables into ~14 jamo, distorting every ratio and
        // token measure built on the normalized form.
        assertEquals(5, TextMatch.normalize("방탄소년단").length)
    }

    @Test
    fun thaiVowelsAreNotStripped() {
        assertEquals("คอนเสิร์ต", TextMatch.normalize("คอนเสิร์ต"))
    }

    @Test
    fun cjkContainmentMeetsNonLatinBar() {
        // Spaceless overlap at ratio 60: accepted for CJK (4.0.0 bar 45)…
        assertTrue(TextMatch.similarity("夜に駆ける", "に駆ける") >= 72)
        // …while Latin prefix confusion at ratio 66 stays rejected (4.2.4 bar 75).
        assertTrue(TextMatch.similarity("Belonging", "Belong") < 72)
    }

    @Test
    fun spanishLiveCountsAsVariant() {
        val exact = YouTubeMusicTrack("vid1", "Cancion", "Artista")
        val live = YouTubeMusicTrack("vid2", "Cancion En Vivo", "Artista")
        assertTrue(
            TextMatch.matchScore(exact, "Cancion", "Artista") >
                TextMatch.matchScore(live, "Cancion", "Artista"),
        )
    }

    @Test
    fun soundtrackAndOstClausesAreStripped() {
        val titleWithFrom = "Nijame Ne Chebutunna (From \"Ooru Peru Bhairavakona\")"
        val titleWithFeat = "Nijame Ne Chebutunna (feat. Sundeep Kishan, Varsha Bollamma & Kavya Thapar)"
        assertEquals("Nijame Ne Chebutunna", TextMatch.baseTitle(titleWithFrom).trim())
        assertEquals("Nijame Ne Chebutunna", TextMatch.baseTitle(titleWithFeat).trim())
        assertTrue(TextMatch.isSafeTitleMatch(titleWithFrom, titleWithFeat, "Sid Sriram"))
    }

    @Test
    fun originalBeatsAlternateCutsWithSameArtistAndAlbum() {
        val art = "https://lh3.googleusercontent.com/x=w120"
        val original = YouTubeMusicTrack("orig", "Headlights (feat. KIDDO)", "Alok & Alan Walker", "Headlights", art, 161)
        val vip = YouTubeMusicTrack("vip", "Headlights (VIP) (feat. KIDDO)", "Alok & Alan Walker", "Headlights", art, 161)
        val sped = YouTubeMusicTrack("sped", "Headlights (feat. KIDDO) - Sped Up", "Alok & Alan Walker", "Headlights", art, 128)
        val wanted = "Headlights (feat. KIDDO)"
        val artist = "Alok & Alan Walker"
        val best = listOf(vip, sped, original).maxByOrNull { TextMatch.matchScore(it, wanted, artist, 161_000L) }
        assertEquals("orig", best?.videoId)
    }

    @Test
    fun durationPrefersTheSameRecording() {
        val art = "https://lh3.googleusercontent.com/x=w120"
        val albumCut = YouTubeMusicTrack("album", "Headlights", "Alan Walker", "Album", art, 161)
        val extended = YouTubeMusicTrack("ext", "Headlights", "Alan Walker", "Album", art, 214)
        assertTrue(
            TextMatch.matchScore(albumCut, "Headlights", "Alan Walker", 160_000L) >
                TextMatch.matchScore(extended, "Headlights", "Alan Walker", 160_000L),
        )
        // Unknown catalog length keeps the plain title/artist score.
        assertEquals(
            TextMatch.matchScore(albumCut, "Headlights", "Alan Walker"),
            TextMatch.matchScore(albumCut, "Headlights", "Alan Walker", null),
        )
    }

    @Test
    fun differentTakeIsRefusedInBothDirections() {
        assertTrue(!TextMatch.isSameTake("Mr. Brightside (Live)", "Mr. Brightside", "The Killers"))
        assertTrue(!TextMatch.isSameTake("Mr. Brightside", "Mr. Brightside - Live at Wembley", "The Killers"))
        assertTrue(!TextMatch.isSameTake("Blinding Lights (Sped Up)", "Blinding Lights", "The Weeknd"))
        assertTrue(!TextMatch.isSameTake("Yellow (Acoustic Cover)", "Yellow", "Coldplay"))
    }

    @Test
    fun packagingAndTitleWordsAreNotTakes() {
        assertTrue(TextMatch.isSameTake("Bohemian Rhapsody (Remastered 2011)", "Bohemian Rhapsody", "Queen"))
        assertTrue(TextMatch.isSameTake("Song (Radio Edit)", "Song", "X"))
        assertTrue(TextMatch.isSameTake("Live Forever", "Live Forever", "Oasis"))
        assertTrue(TextMatch.isSameTake("Snowman", "Snowman", "Lofi Girl", "Lofi Girl"))
    }

    @Test
    fun severeDurationMismatchNeedsBothRuntimes() {
        assertTrue(TextMatch.isSevereDurationMismatch(200_000L, 600))
        assertTrue(!TextMatch.isSevereDurationMismatch(200_000L, 215))
        assertTrue(!TextMatch.isSevereDurationMismatch(null, 600))
        assertTrue(!TextMatch.isSevereDurationMismatch(200_000L, null))
    }
}
