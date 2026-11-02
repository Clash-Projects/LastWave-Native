package com.lastwave.app.widget.wave

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlayableTrack
import org.junit.Test

class WaveWidgetStateTest {
    @Test
    fun fromPlayer_mapsCurrentModesAndNextThreeWithRealQueueIndices() {
        val queue = (0..5).map { index ->
            PlayableTrack(
                title = "Song $index",
                artist = "Artist $index",
                videoId = "video-$index",
                artworkUrl = "https://art/$index",
            )
        }
        val snapshot = WaveWidgetState.fromPlayer(
            playerState = MusicPlayerState(
                current = queue[2],
                queue = queue,
                currentIndex = 2,
                isPlaying = true,
                positionMs = 15_000,
                durationMs = 60_000,
                shuffleEnabled = true,
                repeatMode = Player.REPEAT_MODE_ONE,
            ),
            isLiked = true,
            nowEpochMs = 1_000,
        )

        assertThat(snapshot.trackId).isEqualTo("video:video-2")
        assertThat(snapshot.isLiked).isTrue()
        assertThat(snapshot.shuffleEnabled).isTrue()
        assertThat(snapshot.repeatMode).isEqualTo(Player.REPEAT_MODE_ONE)
        assertThat(snapshot.nextTracks.map { it.queueIndex }).containsExactly(3, 4, 5).inOrder()
        assertThat(snapshot.nextTracks.map { it.title }).containsExactly("Song 3", "Song 4", "Song 5").inOrder()
    }

    @Test
    fun progressAt_extrapolatesOnlyWhilePlayingAndClamps() {
        val playing = WaveWidgetState(
            trackId = "track",
            title = "Song",
            positionMs = 20_000,
            durationMs = 60_000,
            isPlaying = true,
            updatedAtEpochMs = 10_000,
        )
        val paused = playing.copy(isPlaying = false)

        assertThat(playing.progressAt(25_000)).isWithin(0.0001f).of(35f / 60f)
        assertThat(playing.progressAt(100_000)).isEqualTo(1f)
        assertThat(paused.progressAt(25_000)).isWithin(0.0001f).of(1f / 3f)
    }
}
