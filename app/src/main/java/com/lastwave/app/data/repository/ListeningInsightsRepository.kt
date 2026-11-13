package com.lastwave.app.data.repository

import androidx.compose.runtime.Immutable
import com.lastwave.app.data.local.db.SongPlayStatsDao
import com.lastwave.app.data.local.db.SongPlayStatsEntity
import com.lastwave.app.util.ArtistHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Immutable
data class ListeningInsights(
    val totalListeningTimeMs: Long,
    val totalCompletedPlays: Int,
    val totalSkips: Int,
    val uniqueTracks: Int,
    val uniqueArtists: Int,
    val topTracks: List<TrackInsight>,
    val topArtists: List<ArtistInsight>,
) {
    val totalSessions: Int
        get() = totalCompletedPlays + totalSkips

    val skipRate: Float
        get() = if (totalSessions <= 0) 0f else totalSkips.toFloat() / totalSessions.toFloat()

    val isEmpty: Boolean
        get() = totalListeningTimeMs <= 0L &&
            totalCompletedPlays <= 0 &&
            totalSkips <= 0 &&
            uniqueTracks <= 0

    companion object {
        val EMPTY = ListeningInsights(
            totalListeningTimeMs = 0L,
            totalCompletedPlays = 0,
            totalSkips = 0,
            uniqueTracks = 0,
            uniqueArtists = 0,
            topTracks = emptyList(),
            topArtists = emptyList(),
        )
    }
}

@Immutable
data class TrackInsight(
    val trackKey: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val videoId: String?,
    val playCount: Int,
    val listeningTimeMs: Long,
)

@Immutable
data class ArtistInsight(
    val name: String,
    val totalListeningTimeMs: Long,
    val totalPlays: Int,
    val uniqueTracks: Int,
    val artworkUrl: String?,
)

/**
 * Local, privacy-friendly listening analytics computed directly from
 * [SongPlayStatsDao]. Never triggers network calls or requires Last.fm /
 * YouTube accounts.
 */
@Singleton
class ListeningInsightsRepository @Inject constructor(
    private val songPlayStatsDao: SongPlayStatsDao,
) {
    fun observeInsights(topLimit: Int = DEFAULT_TOP_LIMIT): Flow<ListeningInsights> =
        songPlayStatsDao.observeAllStats()
            .map { stats -> buildInsights(stats, topLimit = topLimit) }
            .flowOn(Dispatchers.Default)

    fun buildInsights(
        stats: List<SongPlayStatsEntity>,
        topLimit: Int = DEFAULT_TOP_LIMIT,
    ): ListeningInsights {
        if (stats.isEmpty()) return ListeningInsights.EMPTY

        val normalizedTracks = stats
            .filter { it.title.isNotBlank() }
            .map { entity ->
                val resolvedKey = entity.trackKey.ifBlank {
                    "${entity.title.trim()}|${entity.artist.trim()}".lowercase()
                }
                entity.copy(
                    trackKey = resolvedKey,
                    title = entity.title.trim(),
                    artist = entity.artist.trim(),
                    totalPlayTimeMs = entity.totalPlayTimeMs.coerceAtLeast(0L),
                    playCount = entity.playCount.coerceAtLeast(0),
                    skipCount = entity.skipCount.coerceAtLeast(0),
                )
            }

        if (normalizedTracks.isEmpty()) return ListeningInsights.EMPTY

        val totalListeningTimeMs = normalizedTracks.sumOf { it.totalPlayTimeMs }
        val totalCompletedPlays = normalizedTracks.sumOf { it.playCount }
        val totalSkips = normalizedTracks.sumOf { it.skipCount }
        val uniqueTracks = normalizedTracks.distinctBy { it.trackKey }.size

        val validArtistTracks = normalizedTracks.filter {
            it.artist.isNotBlank() && !ArtistHelper.isPlayCountOrStat(it.artist)
        }
        val artistGroups = validArtistTracks.groupBy { it.artist.lowercase() }
        val uniqueArtists = artistGroups.size

        val sortedTracks = normalizedTracks.sortedWith(
            compareByDescending<SongPlayStatsEntity> { it.totalPlayTimeMs }
                .thenByDescending { it.playCount }
                .thenBy { it.title.lowercase() },
        )

        val topTracks = sortedTracks
            .take(topLimit.coerceAtLeast(0))
            .map { entity ->
                TrackInsight(
                    trackKey = entity.trackKey,
                    title = entity.title,
                    artist = entity.artist,
                    artworkUrl = entity.artworkUrl?.takeIf(String::isNotBlank),
                    videoId = entity.videoId?.takeIf(String::isNotBlank),
                    playCount = entity.playCount,
                    listeningTimeMs = entity.totalPlayTimeMs,
                )
            }

        val topArtists = artistGroups.values
            .map { group ->
                val byPlayTime = group.sortedByDescending { it.totalPlayTimeMs }
                val displayName = byPlayTime.first().artist
                val artwork = byPlayTime.firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) }
                ArtistInsight(
                    name = displayName,
                    totalListeningTimeMs = group.sumOf { it.totalPlayTimeMs },
                    totalPlays = group.sumOf { it.playCount },
                    uniqueTracks = group.distinctBy { it.trackKey }.size,
                    artworkUrl = artwork,
                )
            }
            .sortedWith(
                compareByDescending<ArtistInsight> { it.totalListeningTimeMs }
                    .thenByDescending { it.totalPlays }
                    .thenBy { it.name.lowercase() },
            )
            .take(topLimit.coerceAtLeast(0))

        return ListeningInsights(
            totalListeningTimeMs = totalListeningTimeMs,
            totalCompletedPlays = totalCompletedPlays,
            totalSkips = totalSkips,
            uniqueTracks = uniqueTracks,
            uniqueArtists = uniqueArtists,
            topTracks = topTracks,
            topArtists = topArtists,
        )
    }

    companion object {
        const val DEFAULT_TOP_LIMIT = 5
        const val MIN_SESSIONS_FOR_SKIP_INSIGHT = 5
    }
}

