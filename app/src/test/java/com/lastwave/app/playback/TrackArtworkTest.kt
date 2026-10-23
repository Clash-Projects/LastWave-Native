package com.lastwave.app.playback

import org.junit.Assert.*
import org.junit.Test

class TrackArtworkTest {
    private val first = PlayableTrack("First", "Artist", videoId = "first")
    private val second = PlayableTrack("Second", "Artist", videoId = "second")

    @Test fun `new song cannot read previous artwork before collector catches up`() {
        val art = TrackArtwork<String>()
        art.select(first)
        val request = art.begin("first.jpg", first)!!
        assertTrue(art.complete(request, first, "first cover"))
        assertEquals("first cover", art.valueFor(first))
        assertNull(art.valueFor(second))
        assertNull(art.urlFor(second))
        assertFalse(art.complete(request, second, "late first cover"))
    }

    @Test fun `rapid skips reject old requests even with the same image URL`() {
        val art = TrackArtwork<String>()
        art.select(first)
        val old = art.begin("shared.jpg", first)!!
        art.select(second)
        val current = art.begin("shared.jpg", second)!!
        assertFalse(art.complete(old, second, "wrong cover"))
        assertNull(art.valueFor(second))
        assertTrue(art.complete(current, second, "right cover"))
        assertEquals("right cover", art.valueFor(second))
    }

    @Test fun `skip away and back rejects the original request`() {
        val art = TrackArtwork<String>()
        art.select(first)
        val old = art.begin("cover.jpg", first)!!
        art.select(second)
        art.select(first)
        val current = art.begin("cover.jpg", first)!!
        assertFalse(art.complete(old, first, "stale"))
        assertTrue(art.complete(current, first, "current"))
    }

    @Test fun `different recordings with identical titles have different owners`() {
        val art = TrackArtwork<String>()
        art.select(first)
        val old = art.begin("cover.jpg", first)!!
        val differentRecording = first.copy(videoId = "different")
        art.select(differentRecording)
        assertFalse(art.complete(old, differentRecording, "wrong recording"))
        assertNull(art.valueFor(differentRecording))
    }

    @Test fun `same song progress does not invalidate artwork and stop clears it`() {
        val art = TrackArtwork<String>()
        art.select(first)
        val request = art.begin("cover.jpg", first)!!
        art.complete(request, first, "cover")
        assertFalse(art.select(first.copy(durationMs = 180_000)))
        assertEquals("cover", art.valueFor(first))
        art.select(null)
        assertNull(art.valueFor(first))
        assertFalse(art.complete(request, first, "late cover"))
    }

    @Test fun `failed replacement leaves a blank image`() {
        val art = TrackArtwork<String>()
        art.select(first)
        val original = art.begin("preview.jpg", first)!!
        art.complete(original, first, "preview")
        val replacement = art.begin("full.jpg", first)!!
        assertNull(art.valueFor(first))
        art.complete(replacement, first, null)
        assertNull(art.valueFor(first))
    }
}
