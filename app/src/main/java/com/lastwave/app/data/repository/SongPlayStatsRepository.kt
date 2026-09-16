package com.lastwave.app.data.repository

import com.lastwave.app.data.local.db.SongPlayStatsDao
import com.lastwave.app.data.local.db.SongPlayStatsEntity
import javax.inject.Inject
import javax.inject.Singleton

private fun trackKeyOf(title: String, artist: String) = "${title.trim()}|${artist.trim()}".lowercase()

/**
 * Local listening-behavior tracker: real accumulated listened time per track
 * plus how often it gets skipped. This is the signal LocalTasteSuggestionEngine
 * scores on, independent of any Last.fm/YouTube account. MusicPlayer calls
 * [recordListenedMs] on every track transition and [recordSkip] when a track
 * is abandoned early.
 */
@Singleton
class SongPlayStatsRepository @Inject constructor(
    private val dao: SongPlayStatsDao,
) {
    suspend fun recordListenedMs(
        title: String,
        artist: String,
        videoId: String?,
        artworkUrl: String?,
        listenedMs: Long,
        completed: Boolean,
    ) {
        if (title.isBlank() || artist.isBlank() || listenedMs <= 0L) return
        val key = trackKeyOf(title, artist)
        val existing = dao.find(key)
        dao.upsert(
            (existing ?: SongPlayStatsEntity(trackKey = key, title = title, artist = artist)).copy(
                videoId = videoId ?: existing?.videoId,
                artworkUrl = artworkUrl ?: existing?.artworkUrl,
                totalPlayTimeMs = (existing?.totalPlayTimeMs ?: 0L) + listenedMs,
                playCount = (existing?.playCount ?: 0) + if (completed) 1 else 0,
                lastPlayedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun recordSkip(title: String, artist: String, videoId: String?) {
        if (title.isBlank() || artist.isBlank()) return
        val key = trackKeyOf(title, artist)
        val existing = dao.find(key)
        dao.upsert(
            (existing ?: SongPlayStatsEntity(trackKey = key, title = title, artist = artist)).copy(
                videoId = videoId ?: existing?.videoId,
                skipCount = (existing?.skipCount ?: 0) + 1,
                lastPlayedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun mostPlayedSince(days: Int = 30, limit: Int = 100): List<SongPlayStatsEntity> =
        dao.mostPlayedSince(System.currentTimeMillis() - days * 86_400_000L, limit)

    suspend fun recentlyPlayed(limit: Int = 20): List<SongPlayStatsEntity> = dao.recentlyPlayed(limit)

    suspend fun skipCountFor(title: String, artist: String): Int =
        dao.find(trackKeyOf(title, artist))?.skipCount ?: 0
}
