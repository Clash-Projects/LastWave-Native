package com.lastwave.app.data.repository

import android.app.Application
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.local.db.AppDatabase
import com.lastwave.app.data.local.db.RelatedGraphDao
import com.lastwave.app.data.local.db.RelatedGraphEdgeEntity
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class RelatedGraphRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: RelatedGraphDao
    private val api = mockk<InnerTubeMusicApi>(relaxed = true)
    private lateinit var refreshScope: TestScope
    private lateinit var repository: RelatedGraphRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        dao = db.relatedGraphDao()
        refreshScope = TestScope(StandardTestDispatcher())
        repository = RelatedGraphRepository(api, dao, refreshScope)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun freshHit_returnsCacheWithoutNetwork() = runTest {
        val now = System.currentTimeMillis()
        seedEdge("seed", "r1", now)
        seedEdge("seed", "r2", now - 1000L)

        val result = repository.relatedFor("seed", limit = 2)

        assertThat(result.map { it.videoId }).containsExactly("r1", "r2").inOrder()
        coVerify(exactly = 0) { api.fetchRelatedSongs(any(), any(), any()) }
    }

    @Test
    fun staleHit_returnsCacheImmediatelyThenRefreshesInBackground() = runTest {
        seedEdge("seed", "old1", System.currentTimeMillis() - FRESH_TTL_MS - 1)
        coEvery { api.fetchRelatedSongs("seed", any(), any()) } returns listOf(track("old1"), track("new1"))

        val result = repository.relatedFor("seed", limit = 1)

        assertThat(result.map { it.videoId }).containsExactly("old1")
        refreshScope.advanceUntilIdle()
        coVerify(exactly = 1) { api.fetchRelatedSongs("seed", any(), any()) }
        val rows = dao.edgesFor("seed", limit = 10)
        assertThat(rows.single { it.relatedVideoId == "old1" }.seenCount).isEqualTo(2)
        assertThat(rows.map { it.relatedVideoId }).contains("new1")
    }

    @Test
    fun coldCache_awaitsNetworkAndPersistsEdges() = runTest {
        coEvery { api.fetchRelatedSongs("cold", any(), any()) } returns listOf(track("n1"), track("n2"))

        val result = repository.relatedFor("cold", limit = 5)

        assertThat(result.map { it.videoId }).containsExactly("n1", "n2").inOrder()
        val rows = dao.edgesFor("cold", limit = 10)
        assertThat(rows.map { it.relatedVideoId }).containsExactly("n1", "n2")
        assertThat(rows.all { it.seenCount == 1 }).isTrue()
    }

    @Test
    fun coldCacheWithNetworkFailure_returnsEmptyWithoutThrowing() = runTest {
        coEvery { api.fetchRelatedSongs("cold", any(), any()) } throws IOException("boom")

        val result = repository.relatedFor("cold", limit = 5)

        assertThat(result).isEmpty()
    }

    @Test
    fun forceNetworkFailureWithWarmCache_fallsBackToCache() = runTest {
        seedEdge("seed", "cached1", System.currentTimeMillis())
        coEvery { api.fetchRelatedSongs("seed", any(), any()) } throws IOException("boom")

        val result = repository.relatedFor("seed", limit = 10, forceNetwork = true)

        assertThat(result.map { it.videoId }).containsExactly("cached1")
    }

    @Test
    fun forceNetworkFailureWithColdCache_returnsEmpty() = runTest {
        coEvery { api.fetchRelatedSongs("cold", any(), any()) } throws IOException("boom")

        val result = repository.relatedFor("cold", limit = 10, forceNetwork = true)

        assertThat(result).isEmpty()
    }

    @Test
    fun concurrentStaleReads_collapseIntoOneRefresh() = runTest {
        seedEdge("seed", "old1", System.currentTimeMillis() - FRESH_TTL_MS - 1)
        coEvery { api.fetchRelatedSongs("seed", any(), any()) } returns listOf(track("new1"))

        val first = async { repository.relatedFor("seed", limit = 1) }
        val second = async { repository.relatedFor("seed", limit = 1) }
        first.await()
        second.await()
        refreshScope.advanceUntilIdle()

        coVerify(exactly = 1) { api.fetchRelatedSongs("seed", any(), any()) }
    }

    @Test
    fun enforceHygiene_prunesOldRowsAndTrimsToCap() = runTest {
        val now = System.currentTimeMillis()
        (0 until 5).forEach { i -> seedEdge("s$i", "r$i", now + i) }
        seedEdge("sOld", "rOld", now - PRUNE_AFTER_MS - 1)

        repository.enforceHygiene(now, maxEdges = 3)

        assertThat(dao.count()).isEqualTo(3)
        assertThat(dao.edgesFor("s0", limit = 10)).isEmpty()
        assertThat(dao.edgesFor("s1", limit = 10)).isEmpty()
        assertThat(dao.edgesFor("s2", limit = 10)).hasSize(1)
        assertThat(dao.edgesFor("s3", limit = 10)).hasSize(1)
        assertThat(dao.edgesFor("s4", limit = 10)).hasSize(1)
        assertThat(dao.edgesFor("sOld", limit = 10)).isEmpty()
    }

    private suspend fun seedEdge(source: String, related: String, lastSeenMillis: Long) {
        dao.upsertAll(
            listOf(
                RelatedGraphEdgeEntity(
                    sourceVideoId = source,
                    relatedVideoId = related,
                    title = "Title $related",
                    artist = "Artist $related",
                    firstSeenAtMillis = lastSeenMillis,
                    lastSeenAtMillis = lastSeenMillis,
                ),
            ),
        )
    }

    private fun track(id: String) = YouTubeMusicTrack(
        videoId = id,
        title = "Title $id",
        artist = "Artist $id",
    )
}
