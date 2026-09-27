package com.lastwave.app.data.generate

import com.lastwave.app.data.local.db.RecommendationExclusionDao
import com.lastwave.app.data.local.db.SongPlayStatsDao
import com.lastwave.app.data.local.db.SongPlayStatsEntity
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.playlist.PlaylistRepository
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_SUGGESTIONS = 50
private const val PLAY_WEIGHT = 2.0f
private const val SKIP_PENALTY = 3.0f
private const val LIKED_BONUS = 5.0f
private const val RECENCY_BONUS = 1.5f
private const val TIME_OF_DAY_BONUS = 1.3f
private val MORNING = 6..11
private val AFTERNOON = 12..17
private val EVENING = 18..21

/**
 * Local-listening-behavior alternative to [RecommendationEngine] — a port of
 * Nocturne's ForYouSuggestionEngine. Scores tracks purely on real playback
 * signal recorded by SongPlayStatsRepository (accumulated listened time,
 * skip count, recency, time-of-day) — no Last.fm account required — then
 * seeds YouTube Music's own "related songs" (watch-next) endpoint from the
 * top-scored local tracks via the existing InnerTubeMusicApi.fetchRelatedSongs.
 *
 * Much lighter than RecommendationEngine's 7-source Last.fm pipeline: no
 * diversity caps, no genre exploration, no multi-cycle refill. Good as the
 * default "For You" source when Last.fm isn't connected, or as a fast
 * alternative feed regardless — see integration notes at the bottom of this
 * file for where to wire it in.
 */
@Singleton
class LocalTasteSuggestionEngine @Inject constructor(
    private val songPlayStatsDao: SongPlayStatsDao,
    private val recommendationExclusionDao: RecommendationExclusionDao,
    private val playlistRepository: PlaylistRepository,
    private val innerTube: InnerTubeMusicApi,
) {
    private fun YouTubeMusicTrack.toGeneratedTrack() = GeneratedTrack(
        name = title,
        artist = artist,
        artworkUrl = artworkUrl,
        url = "https://music.youtube.com/watch?v=$videoId",
        album = album,
    )

    private fun currentTimeOfDay(): String = when (LocalTime.now().hour) {
        in MORNING -> "morning"
        in AFTERNOON -> "afternoon"
        in EVENING -> "evening"
        else -> "night"
    }

    /** Port of Nocturne's scoreSong() — same weights, same shape. */
    private fun scoreStats(
        stats: SongPlayStatsEntity,
        likedKeys: Set<String>,
        recentKeys: Set<String>,
        timeOfDay: String,
    ): Float {
        val isLiked = stats.trackKey in likedKeys
        val isRecent = stats.trackKey in recentKeys

        var score = stats.totalPlayTimeMs.toFloat() * PLAY_WEIGHT
        score -= stats.skipCount.toFloat() * SKIP_PENALTY
        if (isLiked) score += LIKED_BONUS
        if (isRecent) score += RECENCY_BONUS

        // Same limitation Nocturne notes: no tempo detection from local
        // data, so this just leans on recency/liked at the edges of the day.
        val hourBonus = when (timeOfDay) {
            "morning" -> if (isRecent) TIME_OF_DAY_BONUS else 1.0f
            "night" -> if (isLiked) TIME_OF_DAY_BONUS else 1.0f
            else -> 1.0f
        }
        score *= hourBonus
        return score.coerceAtLeast(0f)
    }

    private fun isJunkTitle(titleLower: String): Boolean {
        val keywords = listOf(
            "mashup", "mash up", "mash-up", "jukebox", "mega mix", "megamix",
            "non stop", "nonstop", "audio jukebox", "full album", "full songs", "compilation",
            "slowed + reverb", "slowed reverb", "slowed & reverb", "bass boosted", "8d audio",
            "karaoke", "ringtone", "instrumental", "1 hour", "10 hour", "clean version", "sped up",
        )
        return keywords.any { titleLower.contains(it) }
    }

    /**
     * Build the local "For You" suggestion list. [total] caps the result.
     * Scores tracks on real listening behavior (play duration, skips, liked,
     * recency, time-of-day), seeded via YouTube Music related songs with
     * fallback to liked and recently played songs.
     */
    suspend fun run(total: Int = MAX_SUGGESTIONS): List<GeneratedTrack> {
        val blacklist = recommendationExclusionDao.getAll().map { it.trackKey }.toSet()
        val timeOfDay = currentTimeOfDay()

        val recentStats = songPlayStatsDao.mostPlayedSince(
            sinceMillis = System.currentTimeMillis() - 30L * 86_400_000L,
            limit = 100,
        ).ifEmpty {
            songPlayStatsDao.recentlyPlayed(50)
        }

        val likedSongs = runCatching { playlistRepository.getLikedSongs()?.tracks.orEmpty() }
            .getOrDefault(emptyList())
        val likedKeys = likedSongs.map { it.key }.toSet()
        val recentKeys = songPlayStatsDao.recentlyPlayed(20).map { it.trackKey }.toSet()

        val scored = recentStats
            .filter { it.trackKey !in blacklist && (it.skipCount < 2 || it.totalPlayTimeMs > 45_000L) }
            .sortedByDescending { scoreStats(it, likedKeys, recentKeys, timeOfDay) }

        val seedVideoIds = mutableListOf<String>()
        for (item in scored.take(8)) {
            val vid = item.videoId?.takeIf(String::isNotBlank)
                ?: innerTube.findBestMatchOrNull(item.title, item.artist, prefetchStreams = false)?.videoId
            if (vid != null && vid !in seedVideoIds) {
                seedVideoIds.add(vid)
            }
            if (seedVideoIds.size >= 5) break
        }

        if (seedVideoIds.isEmpty() && likedSongs.isNotEmpty()) {
            for (liked in likedSongs.shuffled().take(5)) {
                val vid = liked.youtubeVideoIdOrNull()
                    ?: innerTube.findBestMatchOrNull(liked.name, liked.artist, prefetchStreams = false)?.videoId
                if (vid != null && vid !in seedVideoIds) {
                    seedVideoIds.add(vid)
                }
            }
        }

        if (seedVideoIds.isEmpty()) return emptyList()

        val suggestions = mutableListOf<GeneratedTrack>()
        val seenKeys = mutableSetOf<String>()
        val artistCounts = mutableMapOf<String, Int>()

        for (seedVideoId in seedVideoIds) {
            if (suggestions.size >= total) break
            val related = runCatching {
                innerTube.fetchRelatedSongs(seedVideoId, limit = 40, prefetchStreams = false)
            }.getOrDefault(emptyList())
            val filtered = related
                .map { it.toGeneratedTrack() }
                .filter { track ->
                    val normArtist = track.artist.trim().lowercase()
                    val count = artistCounts.getOrDefault(normArtist, 0)
                    track.key !in blacklist &&
                        track.key !in seenKeys &&
                        count < 3 &&
                        !isJunkTitle(track.name.lowercase())
                }
                .shuffled()
                .take(10)

            for (track in filtered) {
                suggestions.add(track)
                seenKeys.add(track.key)
                val normArtist = track.artist.trim().lowercase()
                artistCounts[normArtist] = (artistCounts[normArtist] ?: 0) + 1
            }
        }

        // Fill remaining from liked songs if needed
        if (suggestions.size < total && likedSongs.isNotEmpty()) {
            val remainingLiked = likedSongs.shuffled()
            for (liked in remainingLiked) {
                if (suggestions.size >= total) break
                val likedVid = liked.youtubeVideoIdOrNull() ?: continue
                val related = runCatching {
                    innerTube.fetchRelatedSongs(likedVid, limit = 30, prefetchStreams = false)
                }.getOrDefault(emptyList())
                val filtered = related
                    .map { it.toGeneratedTrack() }
                    .filter { track ->
                        val normArtist = track.artist.trim().lowercase()
                        val count = artistCounts.getOrDefault(normArtist, 0)
                        track.key !in blacklist &&
                            track.key !in seenKeys &&
                            count < 3 &&
                            !isJunkTitle(track.name.lowercase())
                    }
                    .shuffled()
                    .take(total - suggestions.size)

                for (track in filtered) {
                    suggestions.add(track)
                    seenKeys.add(track.key)
                    val normArtist = track.artist.trim().lowercase()
                    artistCounts[normArtist] = (artistCounts[normArtist] ?: 0) + 1
                }
            }
        }

        return suggestions.take(total)
    }
}

/*
 * INTEGRATION — where to call LocalTasteSuggestionEngine.run():
 *
 * 1. FeedRepository (data/feed/FeedRepository.kt) builds the "Discover
 *    something new" / radio section from TasteProfile + RecommendationEngine
 *    today. Swap or blend in LocalTasteSuggestionEngine.run() there — it's
 *    the natural drop-in since both return List<GeneratedTrack>.
 *
 * 2. GenerateRepository's "My Recommendations" flow (data/generate/
 *    GenerateRepository.kt) currently always builds a TasteProfile and runs
 *    RecommendationEngine. Add a branch: if TasteProfile.hasPersonalSignals
 *    is false (no Last.fm, guest) OR the user has no Last.fm connected at
 *    all, try LocalTasteSuggestionEngine.run() first and only fall back to
 *    RecommendationEngine/chart seeding if it returns empty (fresh install).
 *
 * 3. Simplest first step to test it in isolation: add a temporary debug
 *    entry point (a Settings row or log line) that calls
 *    LocalTasteSuggestionEngine.run() directly and logs the result, before
 *    wiring it into Feed/Generate. Needs a few days of real listening first
 *    — song_play_stats is empty until MusicPlayer has recorded some plays.
 */
