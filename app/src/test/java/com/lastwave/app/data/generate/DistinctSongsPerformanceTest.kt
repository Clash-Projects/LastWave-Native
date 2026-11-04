package com.lastwave.app.data.generate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Tapping the heart on a large Liked Songs library froze the main thread
 * (ANR): distinctSongs compared every pair and [GeneratedTrack.key] ran two
 * regex replaces on every read. These tests pin the faster versions to the
 * exact results of the old ones.
 */
class DistinctSongsPerformanceTest {

    /** The pre-fix implementation, kept as the reference result. */
    private fun List<GeneratedTrack>.distinctSongsReference(): List<GeneratedTrack> {
        if (size < 2) return this
        val out = ArrayList<GeneratedTrack>(size)
        for (track in this) {
            if (out.none { it.sameSongAs(track) }) out.add(track)
        }
        return out
    }

    @Test
    fun normalizeMatchesRegexVersion() {
        val regex = Regex("\\s+")
        val samples = listOf(
            "", " ", "a", " a ", "a  b", "a\tb", "a \t\n b", "  Round  2 ",
            "x\u000By", "x\u000Cy", "x\ry", "no-change", "a b c", "trail \t",
            " nbsp ", "a  b",
        )
        for (s in samples) {
            assertEquals("for [$s]", s.trim().replace(regex, " "), s.normalizeTrackText())
        }
    }

    @Test
    fun distinctSongsMatchesPairwiseReference() {
        val random = Random(7)
        // Turkish dotted I and Greek final sigma: equals(ignoreCase) and
        // lowercase() disagree on these, and sameSongAs uses the former.
        val names = listOf(
            "Round 2", "round 2 ", "Majboor", "  majboor", "Cry Baby", "", " ", "Homunculus",
            "\u0130stanbul", "istanbul", "ISTANBUL", "\u03A3\u0391\u03A3", "\u03C3\u03B1\u03C2", "\u03C3\u03B1\u03C3",
        )
        val artists = listOf("JaeyBxrd", "jaeybxrd ", "Vaundy", "VAUNDY", "", "Official HIGE DANdism", "\u0130", "i")
        val ids = listOf("", "", "", "dQw4w9WgXcQ", "NAkQVL61BRI", "tty54CzXZDg")
        repeat(500) {
            val list = List(random.nextInt(0, 40)) {
                GeneratedTrack(
                    name = names.random(random),
                    artist = artists.random(random),
                    url = ids.random(random),
                )
            }
            assertEquals(list.distinctSongsReference(), list.distinctSongs())
        }
    }

    @Test
    fun distinctSongsIsFastOnLargeLibraries() {
        val tracks = List(5_000) { i ->
            GeneratedTrack(name = "Seed Song $i  (Remastered  ${i % 7})", artist = "Seed  Artist ${i % 400}")
        }
        val withDuplicate = tracks + tracks.first()
        val started = System.nanoTime()
        val result = withDuplicate.distinctSongs()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(5_000, result.size)
        // Loose bound so a cold or busy CI JVM cannot flake it; the old
        // pairwise version needed minutes for this input.
        assertTrue("distinctSongs took ${elapsedMs}ms for 5,000 tracks", elapsedMs < 10_000)
    }
}
