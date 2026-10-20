package com.lastwave.app.playback

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A podcast episode must survive a session round-trip as an episode: the
 * media type and the show id are what keep it out of radio, scrobble and
 * lyrics paths after a process restart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PodcastSessionPersistenceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `podcast episode keeps its media type and show id through a session round trip`() {
        val episode = PlayableTrack(
            title = "Episode 12",
            artist = "Deep Dive",
            videoId = "vid12",
            durationMs = 3_723_000L,
            mediaType = MediaType.PODCAST_EPISODE,
            showId = "MPSPPLdeep1",
        )
        val song = PlayableTrack(
            title = "Track One",
            artist = "Artist A",
            videoId = "vid1",
            durationMs = 210_000L,
        )

        val session = PersistedPlaybackSession(
            queue = listOf(episode, song),
            currentIndex = 0,
            positionMs = 45_000L,
        )

        val encoded = json.encodeToString(PersistedPlaybackSession.serializer(), session)
        val decoded = json.decodeFromString(PersistedPlaybackSession.serializer(), encoded)

        assertThat(decoded.queue[0].mediaType).isEqualTo(MediaType.PODCAST_EPISODE)
        assertThat(decoded.queue[0].showId).isEqualTo("MPSPPLdeep1")
        assertThat(decoded.queue[0].artist).isEqualTo("Deep Dive")
        assertThat(decoded.queue[1].mediaType).isEqualTo(MediaType.SONG)
        assertThat(decoded.queue[1].showId).isNull()
    }

    @Test
    fun `a session written before podcasts existed still decodes as songs`() {
        val legacy = """
            {
              "version": 2,
              "queue": [
                {
                  "title": "Track One",
                  "artist": "Artist A",
                  "album": "Album X",
                  "artworkUrl": "https://art1.png",
                  "videoId": "vid1",
                  "durationMs": 210000
                }
              ],
              "currentIndex": 0,
              "positionMs": 45000
            }
        """.trimIndent()

        val decoded = json.decodeFromString(PersistedPlaybackSession.serializer(), legacy)

        assertThat(decoded.queue).hasSize(1)
        assertThat(decoded.queue.first().mediaType).isEqualTo(MediaType.SONG)
        assertThat(decoded.queue.first().showId).isNull()
    }
}
