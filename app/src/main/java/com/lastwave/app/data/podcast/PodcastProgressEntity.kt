package com.lastwave.app.data.podcast

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "podcast_progress",
    indices = [Index("showId"), Index("updatedAtMillis")],
)
data class PodcastProgressEntity(
    @PrimaryKey val videoId: String,
    val showId: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val completed: Boolean = false,
    val updatedAtMillis: Long = 0L,
)

@Dao
interface PodcastProgressDao {
    @Query("SELECT * FROM podcast_progress WHERE videoId = :videoId LIMIT 1")
    suspend fun get(videoId: String): PodcastProgressEntity?

    @Query("SELECT * FROM podcast_progress WHERE showId = :showId ORDER BY updatedAtMillis DESC")
    fun observeForShow(showId: String): Flow<List<PodcastProgressEntity>>

    @Query(
        "SELECT * FROM podcast_progress WHERE showId = :showId AND completed = 0 " +
            "AND positionMs >= :minimumPositionMs ORDER BY updatedAtMillis DESC LIMIT 1",
    )
    suspend fun latestIncompleteForShow(
        showId: String,
        minimumPositionMs: Long,
    ): PodcastProgressEntity?

    @Upsert
    suspend fun upsert(entity: PodcastProgressEntity)

    @Query("DELETE FROM podcast_progress")
    suspend fun clear()
}

fun PodcastProgressEntity.toModel(): PodcastProgress = PodcastProgress(
    videoId = videoId,
    showId = showId,
    positionMs = positionMs,
    durationMs = durationMs,
    completed = completed,
    updatedAtMillis = updatedAtMillis,
)
