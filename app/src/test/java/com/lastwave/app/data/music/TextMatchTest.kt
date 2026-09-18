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
}
