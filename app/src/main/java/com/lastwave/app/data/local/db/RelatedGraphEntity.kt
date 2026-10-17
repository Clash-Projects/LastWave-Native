package com.lastwave.app.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert

/** One observed "sourceVideoId was related to relatedVideoId" edge from a related-track fetch. */
@Entity(
    tableName = "related_graph_edges",
    primaryKeys = ["sourceVideoId", "relatedVideoId"],
    indices = [Index("relatedVideoId"), Index("lastSeenAtMillis")],
)
data class RelatedGraphEdgeEntity(
    val sourceVideoId: String,
    val relatedVideoId: String,
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationSeconds: Int? = null,
    val seenCount: Int = 1,
    val firstSeenAtMillis: Long,
    val lastSeenAtMillis: Long,
)

data class RelatedReferralStats(
    val relatedVideoId: String,
    val sourceCount: Int,
    val lastSeenAtMillis: Long,
)

@Dao
interface RelatedGraphDao {
    @Upsert
    suspend fun upsertAll(edges: List<RelatedGraphEdgeEntity>)

    @Query(
        "SELECT * FROM related_graph_edges WHERE sourceVideoId = :sourceVideoId " +
            "ORDER BY seenCount DESC, lastSeenAtMillis DESC LIMIT :limit",
    )
    suspend fun edgesFor(sourceVideoId: String, limit: Int): List<RelatedGraphEdgeEntity>

    @Query(
        "SELECT MAX(lastSeenAtMillis) FROM related_graph_edges WHERE sourceVideoId = :sourceVideoId",
    )
    suspend fun lastSeenFor(sourceVideoId: String): Long?

    @Query(
        "SELECT relatedVideoId, COUNT(DISTINCT sourceVideoId) AS sourceCount, " +
            "MAX(lastSeenAtMillis) AS lastSeenAtMillis FROM related_graph_edges " +
            "WHERE relatedVideoId IN (:relatedVideoIds) GROUP BY relatedVideoId",
    )
    suspend fun referralStats(relatedVideoIds: List<String>): List<RelatedReferralStats>

    @Query(
        "SELECT relatedVideoId, COUNT(DISTINCT sourceVideoId) AS sourceCount, " +
            "MAX(lastSeenAtMillis) AS lastSeenAtMillis FROM related_graph_edges " +
            "GROUP BY relatedVideoId ORDER BY sourceCount DESC, lastSeenAtMillis DESC LIMIT :limit",
    )
    suspend fun topReferred(limit: Int): List<RelatedReferralStats>

    @Query("SELECT * FROM related_graph_edges WHERE relatedVideoId IN (:relatedVideoIds)")
    suspend fun snapshotsFor(relatedVideoIds: List<String>): List<RelatedGraphEdgeEntity>

    @Query("SELECT COUNT(*) FROM related_graph_edges")
    suspend fun count(): Int

    @Query("DELETE FROM related_graph_edges WHERE lastSeenAtMillis < :cutoffMillis")
    suspend fun pruneOlderThan(cutoffMillis: Long)

    @Query(
        "DELETE FROM related_graph_edges WHERE rowid NOT IN " +
            "(SELECT rowid FROM related_graph_edges ORDER BY lastSeenAtMillis DESC LIMIT :cap)",
    )
    suspend fun trimToCap(cap: Int)
}
