package com.lastwave.app.data.local.db

import android.app.Application
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class RelatedGraphDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: RelatedGraphDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.relatedGraphDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun edge(
        source: String,
        related: String,
        seenCount: Int = 1,
        firstSeen: Long = NOW,
        lastSeen: Long = NOW,
    ) = RelatedGraphEdgeEntity(
        sourceVideoId = source,
        relatedVideoId = related,
        title = "Title $related",
        artist = "Artist $related",
        seenCount = seenCount,
        firstSeenAtMillis = firstSeen,
        lastSeenAtMillis = lastSeen,
    )

    @Test
    fun edgesFor_ordersBySeenCountDescThenLastSeenDesc() = runBlocking {
        dao.upsertAll(
            listOf(
                edge("s1", "r1", seenCount = 1, lastSeen = NOW),
                edge("s1", "r2", seenCount = 3, lastSeen = NOW),
                edge("s1", "r3", seenCount = 3, lastSeen = NOW + 5),
                edge("s2", "r4", seenCount = 9, lastSeen = NOW + 9),
            ),
        )

        val edges = dao.edgesFor("s1", limit = 10)

        assertThat(edges.map { it.relatedVideoId }).containsExactly("r3", "r2", "r1").inOrder()
    }

    @Test
    fun referralStats_countsDistinctSourcesPerRelatedId() = runBlocking {
        dao.upsertAll(
            listOf(
                edge("s1", "r1", lastSeen = NOW),
                edge("s2", "r1", lastSeen = NOW + 10),
                edge("s1", "r2", lastSeen = NOW),
            ),
        )

        val stats = dao.referralStats(listOf("r1", "r2"))

        assertThat(stats).hasSize(2)
        val r1 = stats.single { it.relatedVideoId == "r1" }
        val r2 = stats.single { it.relatedVideoId == "r2" }
        assertThat(r1.sourceCount).isEqualTo(2)
        assertThat(r1.lastSeenAtMillis).isEqualTo(NOW + 10)
        assertThat(r2.sourceCount).isEqualTo(1)
    }

    @Test
    fun topReferred_ordersBySourceCountDescThenLastSeenDesc() = runBlocking {
        dao.upsertAll(
            listOf(
                edge("s1", "rB", lastSeen = NOW),
                edge("s2", "rB", lastSeen = NOW),
                edge("s3", "rB", lastSeen = NOW),
                edge("s1", "rA", lastSeen = NOW + 20),
                edge("s2", "rA", lastSeen = NOW),
                edge("s4", "rD", lastSeen = NOW + 5),
                edge("s5", "rD", lastSeen = NOW),
                edge("s1", "rC", lastSeen = NOW),
            ),
        )

        val ranked = dao.topReferred(limit = 10)

        assertThat(ranked.map { it.relatedVideoId })
            .containsExactly("rB", "rA", "rD", "rC")
            .inOrder()
    }

    @Test
    fun pruneOlderThan_deletesOnlyRowsStrictlyOlderThanCutoff() = runBlocking {
        dao.upsertAll(
            listOf(
                edge("s1", "old", lastSeen = NOW - 101),
                edge("s1", "boundary", lastSeen = NOW - 100),
                edge("s1", "fresh", lastSeen = NOW),
            ),
        )

        dao.pruneOlderThan(NOW - 100)

        assertThat(dao.count()).isEqualTo(2)
        assertThat(dao.edgesFor("s1", limit = 10).map { it.relatedVideoId })
            .containsExactly("fresh", "boundary")
            .inOrder()
    }

    @Test
    fun trimToCap_keepsExactlyCapNewestRows() = runBlocking {
        dao.upsertAll((0 until 5).map { i -> edge("s$i", "r$i", lastSeen = NOW + i) })

        dao.trimToCap(cap = 3)

        assertThat(dao.count()).isEqualTo(3)
        assertThat(dao.edgesFor("s0", limit = 10)).isEmpty()
        assertThat(dao.edgesFor("s1", limit = 10)).isEmpty()
        assertThat(dao.edgesFor("s2", limit = 10)).hasSize(1)
        assertThat(dao.edgesFor("s3", limit = 10)).hasSize(1)
        assertThat(dao.edgesFor("s4", limit = 10)).hasSize(1)
    }

    @Test
    fun count_reflectsUpsertsAndReplacements() = runBlocking {
        dao.upsertAll(listOf(edge("s1", "r1"), edge("s1", "r2")))
        assertThat(dao.count()).isEqualTo(2)

        dao.upsertAll(listOf(edge("s1", "r1", seenCount = 4), edge("s2", "r1")))
        assertThat(dao.count()).isEqualTo(3)
        assertThat(dao.edgesFor("s1", limit = 10).first().relatedVideoId).isEqualTo("r1")
    }

    @Test
    fun lastSeenFor_returnsMaxTimestampOrNull() = runBlocking {
        dao.upsertAll(
            listOf(
                edge("s1", "r1", lastSeen = NOW),
                edge("s1", "r2", lastSeen = NOW + 50),
            ),
        )

        assertThat(dao.lastSeenFor("s1")).isEqualTo(NOW + 50)
        assertThat(dao.lastSeenFor("missing")).isNull()
    }

    @Test
    fun inMemorySchema_containsTableAndBothIndices() {
        val names = mutableListOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type IN ('table', 'index') " +
                "AND name LIKE '%related_graph_edges%'",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) names.add(cursor.getString(0))
        }

        assertThat(names).containsAtLeast(
            "related_graph_edges",
            "index_related_graph_edges_relatedVideoId",
            "index_related_graph_edges_lastSeenAtMillis",
        )
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
