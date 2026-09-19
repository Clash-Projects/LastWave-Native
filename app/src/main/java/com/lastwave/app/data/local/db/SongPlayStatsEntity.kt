package com.lastwave.app.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Local listening-behavior stats per track — no Last.fm/YouTube account
 * needed. Keyed the same way as GeneratedTrack.key ("$title|$artist",
 * lowercased) so it lines up with the rest of the recommendation pipeline.
 *
 * Written by MusicPlayer (via SongPlayStatsRepository) as tracks actually
 * play and get skipped. Read by LocalTasteSuggestionEngine, the local
 * counterpart to RecommendationEngine's Last.fm-driven pipeline.
 */
@Entity(tableName = "song_play_stats")
data class SongPlayStatsEntity(
    @PrimaryKey val trackKey: String,
    val title: String = "",
    val artist: String = "",
    val videoId: String? = null,
    val artworkUrl: String? = null,
    // Real accumulated listened duration, not just "saved somewhere" — the
    // signal Nocturne's ForYouSuggestionEngine scores on.
    val totalPlayTimeMs: Long = 0L,
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val lastPlayedAtMillis: Long = 0L,
)

@Dao
interface SongPlayStatsDao {
    @Query("SELECT * FROM song_play_stats WHERE trackKey = :trackKey LIMIT 1")
    suspend fun find(trackKey: String): SongPlayStatsEntity?

    @Upsert
    suspend fun upsert(entity: SongPlayStatsEntity)

    /** Most-listened tracks within a recency window — Nocturne's mostPlayedSongs(fromTimeStamp). */
    @Query("SELECT * FROM song_play_stats WHERE lastPlayedAtMillis >= :sinceMillis ORDER BY totalPlayTimeMs DESC LIMIT :limit")
    suspend fun mostPlayedSince(sinceMillis: Long, limit: Int): List<SongPlayStatsEntity>

    @Query("SELECT * FROM song_play_stats ORDER BY lastPlayedAtMillis DESC LIMIT :limit")
    suspend fun recentlyPlayed(limit: Int): List<SongPlayStatsEntity>

    @Query("SELECT * FROM song_play_stats ORDER BY totalPlayTimeMs DESC LIMIT :limit")
    fun observeTopPlayed(limit: Int): Flow<List<SongPlayStatsEntity>>

    @Query("SELECT trackKey FROM song_play_stats")
    suspend fun getAllTrackKeys(): List<String>

    @Query("DELETE FROM song_play_stats")
    suspend fun clearAll()
}
