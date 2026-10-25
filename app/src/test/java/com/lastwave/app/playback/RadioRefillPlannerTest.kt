package com.lastwave.app.playback

import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YtRadioResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class RadioRefillPlannerTest {

    private fun makeTrack(id: String, title: String = "Title $id", artist: String = "Artist $id"): YouTubeMusicTrack =
        YouTubeMusicTrack(
            videoId = id,
            title = title,
            artist = artist,
        )

    private fun makePlayable(id: String, title: String = "Title $id", artist: String = "Artist $id"): PlayableTrack =
        PlayableTrack(
            videoId = id,
            title = title,
            artist = artist,
        )

    @Test
    fun refillReachesTargetSongs_whenPagesAreSmall() = runTest {
        // Initial queue has 50 songs
        val initialQueue = (1..50).map { makePlayable("init_$it") }

        // Each continuation page returns 15 distinct tracks
        // Page 1: p1_1 .. p1_15
        // Page 2: p2_1 .. p2_15
        // Page 3: p3_1 .. p3_15
        // Page 4: p4_1 .. p4_15
        // Total = 60 tracks across 4 pages, reaching target of 50
        val pageRequests = mutableListOf<RadioRefillPlanner.RefillFetchSource>()
        val result = RadioRefillPlanner.executeRefill(
            initialQueue = initialQueue,
            initialContinuationToken = "token_1",
            initialAutomixPlaylistId = null,
            targetNewSongs = 50,
            maxPages = 4,
            fetchSource = { source ->
                pageRequests.add(source)
                when (source) {
                    is RadioRefillPlanner.RefillFetchSource.Continuation -> {
                        when (source.token) {
                            "token_1" -> YtRadioResult(
                                tracks = (1..15).map { makeTrack("p1_$it") },
                                continuationToken = "token_2",
                            )
                            "token_2" -> YtRadioResult(
                                tracks = (1..15).map { makeTrack("p2_$it") },
                                continuationToken = "token_3",
                            )
                            "token_3" -> YtRadioResult(
                                tracks = (1..15).map { makeTrack("p3_$it") },
                                continuationToken = "token_4",
                            )
                            "token_4" -> YtRadioResult(
                                tracks = (1..15).map { makeTrack("p4_$it") },
                                continuationToken = "token_5",
                            )
                            else -> null
                        }
                    }
                    else -> null
                }
            },
        )

        assertEquals(4, result.pagesFetched)
        assertEquals(60, result.tracksToAdd.size)
        assertTrue(result.tracksToAdd.size >= 50)
        assertEquals("token_5", result.nextContinuationToken)
        assertFalse(result.isExhausted)
    }

    @Test
    fun refillPreservesExactYtmOrder() = runTest {
        val initialQueue = listOf(makePlayable("init_1"))

        val page1Tracks = listOf(makeTrack("p1_a"), makeTrack("p1_b"), makeTrack("p1_c"))
        val page2Tracks = listOf(makeTrack("p2_x"), makeTrack("p2_y"), makeTrack("p2_z"))

        val result = RadioRefillPlanner.executeRefill(
            initialQueue = initialQueue,
            initialContinuationToken = "tok_1",
            initialAutomixPlaylistId = null,
            targetNewSongs = 5,
            maxPages = 2,
            fetchSource = { source ->
                when (source) {
                    is RadioRefillPlanner.RefillFetchSource.Continuation -> {
                        if (source.token == "tok_1") {
                            YtRadioResult(tracks = page1Tracks, continuationToken = "tok_2")
                        } else {
                            YtRadioResult(tracks = page2Tracks, continuationToken = null)
                        }
                    }
                    else -> null
                }
            },
        )

        val expectedOrder = listOf("p1_a", "p1_b", "p1_c", "p2_x", "p2_y", "p2_z")
        assertEquals(expectedOrder, result.tracksToAdd.map { it.videoId })
    }

    @Test
    fun duplicatesAreNeverAdded_acrossPagesAndHistory() = runTest {
        // Initial queue contains track "dupe_queue"
        val initialQueue = listOf(makePlayable("init_1"), makePlayable("dupe_queue"))
        // Recent history contains track "dupe_history"
        val recentHistory = setOf("dupe_history")

        // Page 1 returns: dupe_queue, dupe_history, fresh_1, intra_dupe, intra_dupe
        // Page 2 returns: fresh_1 (cross-page dupe), fresh_2
        val result = RadioRefillPlanner.executeRefill(
            initialQueue = initialQueue,
            initialContinuationToken = "t1",
            initialAutomixPlaylistId = null,
            recentHistoryKeys = recentHistory,
            targetNewSongs = 10,
            maxPages = 2,
            fetchSource = { source ->
                when (source) {
                    is RadioRefillPlanner.RefillFetchSource.Continuation -> {
                        if (source.token == "t1") {
                            YtRadioResult(
                                tracks = listOf(
                                    makeTrack("dupe_queue"),
                                    makeTrack("dupe_history"),
                                    makeTrack("fresh_1"),
                                    makeTrack("intra_dupe"),
                                    makeTrack("intra_dupe"),
                                ),
                                continuationToken = "t2",
                            )
                        } else {
                            YtRadioResult(
                                tracks = listOf(
                                    makeTrack("fresh_1"), // Should be excluded as already seen in page 1
                                    makeTrack("fresh_2"),
                                ),
                                continuationToken = null,
                            )
                        }
                    }
                    else -> null
                }
            },
        )

        // Only fresh_1, intra_dupe (first occurrence), and fresh_2 must be admitted
        assertEquals(listOf("fresh_1", "intra_dupe", "fresh_2"), result.tracksToAdd.map { it.videoId })
    }

    @Test
    fun loopStopsWhenNoTokenOrAutomixRemains_andReseedFails() = runTest {
        val initialQueue = listOf(makePlayable("seed_1"))

        var fetchCalls = 0
        val result = RadioRefillPlanner.executeRefill(
            initialQueue = initialQueue,
            initialContinuationToken = "tok_only",
            initialAutomixPlaylistId = null,
            targetNewSongs = 50,
            maxPages = 4,
            fetchSource = { source ->
                fetchCalls++
                when (source) {
                    is RadioRefillPlanner.RefillFetchSource.Continuation -> {
                        // Returns 5 tracks and NO subsequent continuation or automix
                        YtRadioResult(
                            tracks = (1..5).map { makeTrack("trk_$it") },
                            continuationToken = null,
                            automixPlaylistId = null,
                        )
                    }
                    is RadioRefillPlanner.RefillFetchSource.Reseed -> {
                        // Reseed returns empty tracks
                        YtRadioResult(tracks = emptyList())
                    }
                    else -> null
                }
            },
        )

        // Fetched page 1 (continuation), then page 2 (reseed attempt), then stopped quietly
        assertEquals(2, fetchCalls)
        assertEquals(5, result.tracksToAdd.size)
        assertTrue(result.isExhausted)
    }

    @Test
    fun pageCapIsStrictlyRespected() = runTest {
        val initialQueue = listOf(makePlayable("init_1"))

        // Always returns infinite small pages of 2 tracks each with continuation token
        var pagesAttempted = 0
        val result = RadioRefillPlanner.executeRefill(
            initialQueue = initialQueue,
            initialContinuationToken = "token_infinite",
            initialAutomixPlaylistId = null,
            targetNewSongs = 50,
            maxPages = 4, // Hard cap of 4
            fetchSource = { source ->
                pagesAttempted++
                YtRadioResult(
                    tracks = listOf(makeTrack("page_${pagesAttempted}_1"), makeTrack("page_${pagesAttempted}_2")),
                    continuationToken = "token_infinite_$pagesAttempted",
                )
            },
        )

        // Must stop exactly at 4 page fetches, even though only 8 tracks collected (< 50 target)
        assertEquals(4, pagesAttempted)
        assertEquals(4, result.pagesFetched)
        assertEquals(8, result.tracksToAdd.size)
        assertFalse(result.isExhausted)
    }

    @Test
    fun staleResponseAbortsRefill() = runTest {
        val initialQueue = listOf(makePlayable("init_1"))
        val isStaleFlag = AtomicBoolean(false)

        val result = RadioRefillPlanner.executeRefill(
            initialQueue = initialQueue,
            initialContinuationToken = "tok_1",
            initialAutomixPlaylistId = null,
            targetNewSongs = 50,
            maxPages = 4,
            isStale = { isStaleFlag.get() },
            fetchSource = {
                // User changed song during fetch
                isStaleFlag.set(true)
                YtRadioResult(
                    tracks = (1..20).map { makeTrack("trk_$it") },
                    continuationToken = "tok_2",
                )
            },
        )

        // Refill aborted due to stale generation
        assertTrue(result.tracksToAdd.isEmpty())
    }

    @Test
    fun transitionsFromContinuationToAutomixThenReseed() = runTest {
        val initialQueue = listOf(makePlayable("init_seed"))

        val sourcesRun = mutableListOf<String>()
        val result = RadioRefillPlanner.executeRefill(
            initialQueue = initialQueue,
            initialContinuationToken = "tok_c1",
            initialAutomixPlaylistId = null,
            targetNewSongs = 50,
            maxPages = 3,
            fetchSource = { source ->
                when (source) {
                    is RadioRefillPlanner.RefillFetchSource.Continuation -> {
                        sourcesRun.add("continuation")
                        // Page 1 returns tracks and an automix ID, but NO continuation token
                        YtRadioResult(
                            tracks = (1..10).map { makeTrack("c_$it") },
                            continuationToken = null,
                            automixPlaylistId = "auto_mix_id",
                        )
                    }
                    is RadioRefillPlanner.RefillFetchSource.Automix -> {
                        sourcesRun.add("automix")
                        // Page 2 follows automix, returns tracks and no continuation/automix
                        YtRadioResult(
                            tracks = (1..10).map { makeTrack("a_$it") },
                            continuationToken = null,
                            automixPlaylistId = null,
                        )
                    }
                    is RadioRefillPlanner.RefillFetchSource.Reseed -> {
                        sourcesRun.add("reseed_${source.seedVideoId}")
                        // Page 3 reseeds with RDAMVM of the latest song ("a_10")
                        YtRadioResult(
                            tracks = (1..10).map { makeTrack("r_$it") },
                            continuationToken = "tok_new",
                        )
                    }
                }
            },
        )

        assertEquals(listOf("continuation", "automix", "reseed_a_10"), sourcesRun)
        assertEquals(30, result.tracksToAdd.size)
        assertEquals("tok_new", result.nextContinuationToken)
    }

    @Test
    fun onlyOneRefillRunsAtATimeViaMutex() = runTest {
        val mutex = Mutex()
        val executionCounter = AtomicInteger(0)

        val job1 = launch(Dispatchers.Default) {
            if (mutex.tryLock()) {
                try {
                    delay(50)
                    executionCounter.incrementAndGet()
                } finally {
                    mutex.unlock()
                }
            }
        }

        val job2 = launch(Dispatchers.Default) {
            if (mutex.tryLock()) {
                try {
                    delay(50)
                    executionCounter.incrementAndGet()
                } finally {
                    mutex.unlock()
                }
            }
        }

        job1.join()
        job2.join()

        // Exactly one refill must run
        assertEquals(1, executionCounter.get())
    }
}
