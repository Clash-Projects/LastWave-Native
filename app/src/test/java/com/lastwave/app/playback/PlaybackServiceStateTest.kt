package com.lastwave.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackServiceStateTest {
    private val playing = MusicPlayerState(
        current = PlayableTrack("Song", "Artist"),
        isPlaying = true,
    )

    @Test fun `progress ticks are suppressed until clock resynchronization`() {
        assertTrue(sameServiceState(playing, playing.copy(positionMs = 4_999)))
        assertFalse(sameServiceState(playing, playing.copy(positionMs = 5_000)))
    }

    @Test fun `duration becoming available publishes without interaction`() {
        assertFalse(sameServiceState(playing, playing.copy(durationMs = 180_000)))
    }

    @Test fun `buffering completion and speed changes publish immediately`() {
        assertFalse(sameServiceState(playing.copy(isBuffering = true), playing))
        assertFalse(sameServiceState(playing, playing.copy(speed = 1.5f)))
    }

    @Test fun `paused seeks and playing seeks across clock buckets publish`() {
        val paused = playing.copy(isPlaying = false)
        assertFalse(sameServiceState(paused, paused.copy(positionMs = 100)))
        assertFalse(sameServiceState(playing, playing.copy(positionMs = 60_000)))
    }
}
