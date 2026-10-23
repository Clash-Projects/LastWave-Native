package com.lastwave.app.data.generate

import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test

class RecommendationEngineTest {
    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
    }

    @After fun tearDown() { unmockkStatic(Log::class) }

    private fun profile(feed: List<GeneratedTrack>, seeds: List<GeneratedTrack> = emptyList()) = TasteProfile(
        topArtistNames = emptySet(), recentArtists = emptySet(), topTags = emptySet(),
        topTrackKeys = emptySet(), recentTrackKeys = emptySet(),
        topTracksRaw = emptyList(), recentTracksRaw = seeds,
        topArtistsRaw = emptyList(), builtAtMillis = 0, ytMusicFeedRaw = feed,
    )

    @Test fun `enough fresh personal candidates need no network refill`() = runTest {
        val feed = (1..3).map { GeneratedTrack("Song $it", "Artist $it") }
        var freshnessChecks = 0
        val engine = RecommendationEngine(
            rawCall = { error("Unnecessary network call: $it") },
            isFresh = { freshnessChecks++; it }, onProgress = {},
        )
        val result = engine.run(3, profile(feed), emptySet())
        assertThat(result.map { it.key }).containsExactlyElementsIn(feed.map { it.key })
        assertThat(freshnessChecks).isEqualTo(1)
    }

    @Test fun `heard candidates do not prevent refill and exclusions remain strict`() = runTest {
        val heard = (1..3).map { GeneratedTrack("Heard $it", "Known Artist") }
        val checked = mutableListOf<String>()
        val blocked = GeneratedTrack("Blocked", "Other Artist")
        val response = Json.parseToJsonElement("""{
            "similartracks": {"track": [
                {"name":"Fresh 1","artist":{"name":"Artist 1"}},
                {"name":"Fresh 2","artist":{"name":"Artist 2"}},
                {"name":"Fresh 3","artist":{"name":"Artist 3"}},
                {"name":"Blocked","artist":{"name":"Other Artist"}}
            ]}
        }""").jsonObject
        var requests = 0
        val engine = RecommendationEngine(
            rawCall = { requests++; response },
            isFresh = { tracks ->
                checked.addAll(tracks.map { it.key })
                tracks.filterNot { it.key in heard.map { h -> h.key } }
            }, onProgress = {},
        )
        val result = engine.run(
            3, profile(emptyList(), heard.take(1)), setOf(blocked.key), youtubeCandidates = heard,
        )
        assertThat(result.map { it.name }).containsExactly("Fresh 1", "Fresh 2", "Fresh 3")
        assertThat(requests).isEqualTo(1)
        assertThat(checked).containsNoDuplicates()
        assertThat(checked).doesNotContain(blocked.key)
    }

    @Test fun `cancelling a source stops generation immediately`() = runTest {
        var requests = 0
        val engine = RecommendationEngine(
            rawCall = { requests++; throw CancellationException("Stopped") },
            isFresh = { it }, onProgress = {},
        )
        try {
            engine.run(3, profile(emptyList(), listOf(GeneratedTrack("Seed", "Artist"))), emptySet())
            error("Cancellation was swallowed")
        } catch (expected: CancellationException) {
            assertThat(expected.message).isEqualTo("Stopped")
        }
        assertThat(requests).isEqualTo(1)
    }
}
