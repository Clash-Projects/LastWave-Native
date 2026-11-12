package com.lastwave.app.playback

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
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class PureRadioQueueTest {

    private fun createTrack(
        id: String,
        title: String = "Title $id",
        artist: String = "Artist $id",
    ): PlayableTrack = PlayableTrack(
        title = title,
        artist = artist,
        videoId = id,
    )

    @Test
    fun queueOrder_strictlyEqualsYtmResponseOrder() {
        val ytmResponses = listOf(
            createTrack("vid_1"),
            createTrack("vid_2"),
            createTrack("vid_3"),
            createTrack("vid_4"),
            createTrack("vid_5"),
        )

        val filtered = PureRadioQueue.filterStrictYtmRadioBatch(
            incomingTracks = ytmResponses,
            existingVideoIds = emptySet(),
            existingKeys = emptySet(),
            existingSongKeys = emptySet(),
            recentVideoIds = emptySet(),
            recentKeys = emptySet(),
        )

        assertEquals(listOf("vid_1", "vid_2", "vid_3", "vid_4", "vid_5"), filtered.map { it.videoId })
        assertTrue(
            PureRadioQueue.verifyQueueInvariant(
                upcomingQueue = filtered,
                ytmResponsesInOrder = ytmResponses,
                excludedVideoIds = emptySet(),
            ),
        )
    }

    @Test
    fun dedupe_removesSeed_removesCurrentQueue_andRemovesRecentHistoryTracks() {
        val seed = createTrack("seed_1", "Seed Song", "Seed Artist")
        val currentQueue = listOf(
            seed,
            createTrack("queued_2", "Queued Two", "Artist B"),
        )
        val recentHistory = setOf("recent_past_1", "recent_past_2")

        val incomingYtm = listOf(
            createTrack("seed_1", "Seed Song", "Seed Artist"), // Seed track duplicate
            createTrack("queued_2", "Queued Two", "Artist B"), // Active queue duplicate
            createTrack("recent_past_1", "Past 1", "Artist C"), // Recent history duplicate
            createTrack("new_track_1", "New Song 1", "Artist D"),
            createTrack("new_track_2", "New Song 2", "Artist E"),
            createTrack("new_track_1", "New Song 1", "Artist D"), // Intra-batch duplicate
        )

        val filtered = PureRadioQueue.filterStrictYtmRadioBatch(
            incomingTracks = incomingYtm,
            existingVideoIds = currentQueue.mapNotNull { it.videoId }.toSet(),
            existingKeys = currentQueue.map { PureRadioQueue.queueKey(it) }.toSet(),
            existingSongKeys = currentQueue.map { PureRadioQueue.normalizedSongKey(it.title, it.artist) }.toSet(),
            recentVideoIds = recentHistory,
            recentKeys = recentHistory,
            seedVideoId = seed.videoId,
            seedSongKey = PureRadioQueue.normalizedSongKey(seed.title, seed.artist),
        )

        assertEquals(listOf("new_track_1", "new_track_2"), filtered.map { it.videoId })
    }

    @Test
    fun dedupe_removesCounterpartWithSameNormalizedSongKey() {
        val seed = createTrack("music_video_id", "Smooth Operator", "Sade")

        // In YTM, an audio version might have a different videoId, but exact same normalized song key
        val audioVersion = createTrack("atv_audio_id", "Smooth Operator", "Sade")
        val otherSong = createTrack("other_id", "Your Love Is King", "Sade")

        val incomingYtm = listOf(audioVersion, otherSong)

        val filtered = PureRadioQueue.filterStrictYtmRadioBatch(
            incomingTracks = incomingYtm,
            existingVideoIds = setOf(seed.videoId!!),
            existingKeys = setOf(PureRadioQueue.queueKey(seed)),
            existingSongKeys = setOf(PureRadioQueue.normalizedSongKey(seed.title, seed.artist)),
            recentVideoIds = emptySet(),
            recentKeys = emptySet(),
            seedVideoId = seed.videoId,
            seedSongKey = PureRadioQueue.normalizedSongKey(seed.title, seed.artist),
        )

        // audioVersion is detected as counterpart of seed and excluded
        assertEquals(listOf("other_id"), filtered.map { it.videoId })
    }

    @Test
    fun continuationAppend_maintainsStrictOrder() {
        val page1 = listOf(createTrack("p1_1"), createTrack("p1_2"), createTrack("p1_3"))
        val page2 = listOf(createTrack("p2_1"), createTrack("p2_2"), createTrack("p2_3"))

        val queue = mutableListOf<PlayableTrack>()
        queue.addAll(
            PureRadioQueue.filterStrictYtmRadioBatch(
                incomingTracks = page1,
                existingVideoIds = emptySet(),
                existingKeys = emptySet(),
            ),
        )

        val existingVideoIds = queue.mapNotNull { it.videoId }.toSet()
        val existingKeys = queue.map { PureRadioQueue.queueKey(it) }.toSet()
        queue.addAll(
            PureRadioQueue.filterStrictYtmRadioBatch(
                incomingTracks = page2,
                existingVideoIds = existingVideoIds,
                existingKeys = existingKeys,
            ),
        )

        assertEquals(listOf("p1_1", "p1_2", "p1_3", "p2_1", "p2_2", "p2_3"), queue.map { it.videoId })
        assertTrue(
            PureRadioQueue.verifyQueueInvariant(
                upcomingQueue = queue,
                ytmResponsesInOrder = page1 + page2,
                excludedVideoIds = emptySet(),
            ),
        )
    }

    @Test
    fun invariantVerification_failsWhenForeignTracksAreInjected() {
        val ytmResponses = listOf(createTrack("ytm_1"), createTrack("ytm_2"))
        val foreignTrack = createTrack("discover_smooth_operator", "Smooth Operator", "Sade")

        val corruptedQueue = listOf(
            createTrack("ytm_1"),
            foreignTrack, // injected discover / text-search song
            createTrack("ytm_2"),
        )

        assertFalse(
            PureRadioQueue.verifyQueueInvariant(
                upcomingQueue = corruptedQueue,
                ytmResponsesInOrder = ytmResponses,
                excludedVideoIds = emptySet(),
            ),
        )
    }

    @Test
    fun failedRequest_neverInsertsNonYtmSongs() {
        // When YTM returns an empty list or errors, the filtered queue additions must be strictly empty.
        val emptyYtmResponse = emptyList<PlayableTrack>()

        val filtered = PureRadioQueue.filterStrictYtmRadioBatch(
            incomingTracks = emptyYtmResponse,
            existingVideoIds = setOf("current_1"),
            existingKeys = setOf("current_1"),
        )

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun concurrentExtend_triggersOnlyOneExecutionViaMutex() = runTest {
        val mutex = Mutex()
        val executionCounter = AtomicInteger(0)

        // Simulate two concurrent extension triggers (e.g. rapid playback events)
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

        // Exactly one extension must have executed
        assertEquals(1, executionCounter.get())
    }
}
