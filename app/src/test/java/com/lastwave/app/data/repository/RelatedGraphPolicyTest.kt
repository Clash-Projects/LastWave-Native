package com.lastwave.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.local.db.RelatedGraphEdgeEntity
import com.lastwave.app.data.music.YouTubeMusicTrack
import org.junit.Test

class RelatedGraphPolicyTest {

    @Test
    fun isFresh_boundaryIsInclusive() {
        assertThat(isFresh(FRESH_TTL_MS)).isTrue()
        assertThat(isFresh(FRESH_TTL_MS + 1)).isFalse()
        assertThat(isFresh(0L)).isTrue()
    }

    @Test
    fun needsBackgroundRefresh_shortOrStaleCacheRefreshes() {
        assertThat(needsBackgroundRefresh(ageMs = 0L, cachedCount = 2, limit = 5)).isTrue()
        assertThat(needsBackgroundRefresh(ageMs = 0L, cachedCount = 5, limit = 5)).isFalse()
        assertThat(needsBackgroundRefresh(ageMs = FRESH_TTL_MS + 1, cachedCount = 5, limit = 5)).isTrue()
    }

    @Test
    fun mergeFetchedEdges_relistedEdgeIncrementsAndPreservesFirstSeen() {
        val existing = listOf(
            RelatedGraphEdgeEntity(
                sourceVideoId = "seed",
                relatedVideoId = "r1",
                title = "Old title",
                seenCount = 2,
                firstSeenAtMillis = 100L,
                lastSeenAtMillis = 200L,
            ),
        )

        val merged = mergeFetchedEdges(existing, listOf(track("r1")), "seed", nowMillis = 500L)

        val row = merged.single()
        assertThat(row.seenCount).isEqualTo(3)
        assertThat(row.firstSeenAtMillis).isEqualTo(100L)
        assertThat(row.lastSeenAtMillis).isEqualTo(500L)
        assertThat(row.title).isEqualTo("Title r1")
    }

    @Test
    fun mergeFetchedEdges_newTrackStartsAtSeenCountOne() {
        val merged = mergeFetchedEdges(emptyList(), listOf(track("r1")), "seed", nowMillis = 500L)

        val row = merged.single()
        assertThat(row.seenCount).isEqualTo(1)
        assertThat(row.firstSeenAtMillis).isEqualTo(500L)
        assertThat(row.lastSeenAtMillis).isEqualTo(500L)
    }

    @Test
    fun mergeFetchedEdges_unlistedExistingEdgesAreNotReturned() {
        val existing = listOf(
            RelatedGraphEdgeEntity(
                sourceVideoId = "seed",
                relatedVideoId = "keep",
                seenCount = 2,
                firstSeenAtMillis = 100L,
                lastSeenAtMillis = 200L,
            ),
            RelatedGraphEdgeEntity(
                sourceVideoId = "seed",
                relatedVideoId = "r1",
                seenCount = 1,
                firstSeenAtMillis = 100L,
                lastSeenAtMillis = 200L,
            ),
        )

        val merged = mergeFetchedEdges(existing, listOf(track("r1")), "seed", nowMillis = 500L)

        assertThat(merged.map { it.relatedVideoId }).containsExactly("r1")
    }

    private fun track(id: String) = YouTubeMusicTrack(
        videoId = id,
        title = "Title $id",
        artist = "Artist $id",
    )
}
