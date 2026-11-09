package com.lastwave.app.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert

/** A track or artist hidden from recommendations until [expiresAtMillis]. */
@Entity(tableName = "recommendation_snoozes", primaryKeys = ["targetType", "targetKey"])
data class RecommendationSnoozeEntity(
    val targetType: String,
    val targetKey: String,
    val displayName: String,
    val artistName: String = "",
    val snoozedAtMillis: Long,
    val expiresAtMillis: Long,
) {
    companion object {
        const val TYPE_TRACK = "track"
        const val TYPE_ARTIST = "artist"
    }
}

@Dao
interface RecommendationSnoozeDao {
    @Upsert
    suspend fun upsert(entity: RecommendationSnoozeEntity)

    @Query("SELECT * FROM recommendation_snoozes WHERE expiresAtMillis > :now")
    suspend fun getActive(now: Long): List<RecommendationSnoozeEntity>

    @Query("DELETE FROM recommendation_snoozes WHERE expiresAtMillis <= :now")
    suspend fun deleteExpired(now: Long)

    @Query("DELETE FROM recommendation_snoozes WHERE targetType = :targetType AND targetKey = :targetKey")
    suspend fun delete(targetType: String, targetKey: String): Int

    @Query("DELETE FROM recommendation_snoozes")
    suspend fun clear()
}

/** Preset snooze lengths; kept to three so the chooser stays one row of chips. */
enum class SnoozeDuration(val millis: Long) {
    ONE_WEEK(7L * 24 * 60 * 60 * 1000),
    ONE_MONTH(30L * 24 * 60 * 60 * 1000),
    THREE_MONTHS(90L * 24 * 60 * 60 * 1000),
}
