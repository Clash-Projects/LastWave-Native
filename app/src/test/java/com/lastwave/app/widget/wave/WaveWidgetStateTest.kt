package com.lastwave.app.widget.wave

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlayableTrack
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class WaveWidgetStateTest {
    @Test
    fun fromPlayer_mapsCurrentModesAndAvailableNextTracksWithRealQueueIndices() {
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
        assertThat(snapshot.recentTracks.map { it.title }).containsExactly("Song 2")
        assertThat(snapshot.recentTracks.single().queueIndex).isEqualTo(-1)
    }

    @Test
    fun fromPlayer_limitsUpNextToFourAfterCurrentAndPreservesReplayIndicesOnRestore() {
        val queue = (0..8).map { index ->
            PlayableTrack("Song $index", "Artist", videoId = "video-$index")
        }
        val snapshot = WaveWidgetState.fromPlayer(
            MusicPlayerState(current = queue[2], queue = queue, currentIndex = 2),
            isLiked = false,
        )
        val restored = Json.decodeFromString<WaveWidgetState>(Json.encodeToString(snapshot))

        assertThat(snapshot.nextTracks.map { it.queueIndex }).containsExactly(3, 4, 5, 6).inOrder()
        assertThat(snapshot.nextTracks.map { it.videoId })
            .containsExactly("video-3", "video-4", "video-5", "video-6").inOrder()
        assertThat(snapshot.recentTracks.map { it.title }).containsExactly("Song 2")
        assertThat(restored.nextTracks).isEqualTo(snapshot.nextTracks)
        assertThat(restored.nextTracks.last().queueIndex).isEqualTo(6)
        assertThat(restored.nextTracks.last().trackId).isEqualTo("video:video-6")
    }

    @Test
    fun recentHistory_keepsFourDistinctPlayedTracksAndMovesReplayToFront() {
        val queue = (0..5).map { PlayableTrack("Song $it", "Artist", videoId = "video-$it") }
        var history = WaveWidgetState()
        for (index in queue.indices) {
            history = WaveWidgetState.fromPlayer(
                MusicPlayerState(current = queue[index], queue = queue, currentIndex = index),
                isLiked = false,
            ).withRecentHistory(history)
        }
        history = WaveWidgetState.fromPlayer(
            MusicPlayerState(current = queue[2], queue = queue, currentIndex = 2),
            isLiked = false,
        ).withRecentHistory(history)

        assertThat(history.recentTracks.map { it.title })
            .containsExactly("Song 2", "Song 5", "Song 4", "Song 3").inOrder()
        assertThat(history.recentTracks.map { it.queueIndex }).containsExactly(-1, -1, -1, -1)
    }

    @Test
    fun recentHistory_survivesNoTrackClearStateMutationAndSerialization() {
        val track = PlayableTrack(
            title = "Local song",
            artist = "Artist",
            artworkUrl = "content://art/one",
            playbackUrl = "content://media/audio/one",
        )
        val played = WaveWidgetState.fromPlayer(
            MusicPlayerState(current = track), isLiked = false,
        ).withRecentHistory(WaveWidgetState())
        val liked = WaveWidgetState(
            trackId = played.trackId, title = played.title, artist = played.artist, isLiked = true,
        ).withRecentHistory(played)
        val cleared = WaveWidgetState().withRecentHistory(liked)
        val restored = Json.decodeFromString<WaveWidgetState>(Json.encodeToString(cleared))

        assertThat(restored.hasTrack).isFalse()
        assertThat(restored.recentTracks).isEqualTo(played.recentTracks)
        assertThat(restored.recentTracks.single().playbackUri).isEqualTo(track.playbackUrl)
    }

    @Test
    fun recentHistory_neverStoresExpiringStreamingUrlsAndRetainsExactVideoIdentity() {
        val track = PlayableTrack(
            title = "Song", artist = "Artist", internalTrackId = "library-id", videoId = "video-id",
            playbackUrl = "https://stream.example/expiring?token=one",
        )
        val snapshot = WaveWidgetState.fromPlayer(MusicPlayerState(current = track), isLiked = false)
            .withRecentHistory(WaveWidgetState())

        assertThat(snapshot.recentTracks.single().videoId).isEqualTo("video-id")
        assertThat(snapshot.recentTracks.single().playbackUri).isNull()
    }

    @Test
    fun recentHistory_deduplicatesVideoIdentityAfterLibraryMetadataIsLearned() {
        val track = PlayableTrack("Song", "Artist", videoId = "video-id")
        val original = WaveWidgetState.fromPlayer(MusicPlayerState(current = track), isLiked = false)
        val identified = WaveWidgetState.fromPlayer(
            MusicPlayerState(current = track.copy(internalTrackId = "library-id")), isLiked = false,
        ).withRecentHistory(original)

        assertThat(identified.recentTracks.map { it.trackId }).containsExactly("internal:library-id")
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

    @Test
    fun recordedHistory_fillsFourSlotsFromEarlierSessionsInRealPlaybackOrder() {
        val current = PlayableTrack("Current", "Artist", videoId = "current")
        val state = WaveWidgetState.fromPlayer(MusicPlayerState(current = current), isLiked = false)
        val recorded = listOf("current", "latest", "second", "third", "fourth").map {
            WaveQueueTrack("video:$it", -1, if (it == "current") "Current" else it, "Artist", videoId = it)
        }

        val hydrated = state.withRecordedHistory(recorded)

        assertThat(hydrated.recentTracks.map { it.videoId })
            .containsExactly("current", "latest", "second", "third").inOrder()
    }

    @Test
    fun recordedHistory_prefersDatabaseOrderAndRetainsDurableLocalPlaybackMetadata() {
        val local = WaveQueueTrack("media:123", -1, "Local", "Artist", playbackUri = "content://audio/123")
        val stale = WaveQueueTrack("video:stale", -1, "Stale", "Artist", videoId = "stale")
        val state = WaveWidgetState(recentTracks = listOf(stale, local))
        val latest = WaveQueueTrack("video:latest", -1, "Latest", "Artist", videoId = "latest")
        val recordedLocal = WaveQueueTrack("song:local|artist", -1, "Local", "Artist")

        val hydrated = state.withRecordedHistory(listOf(latest, recordedLocal))

        assertThat(hydrated.recentTracks.map { it.title })
            .containsExactly("Latest", "Local", "Stale").inOrder()
        assertThat(hydrated.recentTracks[1].trackId).isEqualTo("media:123")
        assertThat(hydrated.recentTracks[1].playbackUri).isEqualTo("content://audio/123")
    }

    @Test
    fun recordedHistory_mergesMissingVideoIdentityButPreservesDistinctKnownVersions() {
        val unknown = WaveQueueTrack("internal:known", -1, " Song ", "Artist")
        val recorded = listOf(
            WaveQueueTrack("video:one", -1, "Song", "Artist", videoId = "one"),
            WaveQueueTrack("video:two", -1, "Song", "Artist", videoId = "two"),
        )
        val hydrated = WaveWidgetState(recentTracks = listOf(unknown))
            .withRecordedHistory(recorded, includeCurrent = false)

        assertThat(hydrated.recentTracks.map { it.videoId }).containsExactly("one", "two").inOrder()
        assertThat(hydrated.recentTracks.first().trackId).isEqualTo("internal:known")
        assertThat(hydrated.recentTracks[1].trackId).isEqualTo("video:two")
    }

    @Test
    fun recordedHistory_usesDatabaseOrderWhenDurableCurrentSnapshotIsInactive() {
        val old = PlayableTrack("Old current", "Artist", videoId = "old")
        val state = WaveWidgetState.fromPlayer(MusicPlayerState(current = old), isLiked = false)
        val recorded = (1..4).map {
            WaveQueueTrack("video:$it", -1, "Track $it", "Artist", videoId = "$it")
        }

        val hydrated = state.withRecordedHistory(recorded, includeCurrent = false)

        assertThat(hydrated.recentTracks.map { it.videoId }).containsExactly("1", "2", "3", "4").inOrder()
        assertThat(hydrated.trackId).isEqualTo("video:old")

        val restored = Json.decodeFromString<WaveWidgetState>(Json.encodeToString(hydrated))
            .withRestoredHistory()
        assertThat(restored.recentTracks.map { it.videoId }).containsExactly("1", "2", "3", "4").inOrder()
        assertThat(restored.recentTracks.firstOrNull { it.trackId == hydrated.recentTracks[3].trackId })
            .isEqualTo(hydrated.recentTracks[3])
    }

    @Test
    fun recordedHistory_enrichesLiveMissingIdentityWithoutDiscardingDifferentKnownVersions() {
        val current = PlayableTrack("Song", "Artist", internalTrackId = "current")
        val state = WaveWidgetState.fromPlayer(MusicPlayerState(current = current), isLiked = false)
        val recorded = listOf("one", "two").map {
            WaveQueueTrack("video:$it", -1, "Song", "Artist", videoId = it)
        }

        val hydrated = state.withRecordedHistory(recorded)

        assertThat(hydrated.recentTracks.map { it.videoId }).containsExactly("one", "two").inOrder()
        assertThat(hydrated.recentTracks.map { it.trackId })
            .containsExactly("internal:current", "video:two").inOrder()
    }
}
