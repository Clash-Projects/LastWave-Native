package com.lastwave.app.data.recommend

import android.util.Log
import com.lastwave.app.data.local.RecommendationSource
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.repository.RelatedGraphRepository
import com.lastwave.app.data.spotify.SpotifyPreferences
import com.lastwave.app.data.spotify.SpotifyRecommendationApi
import com.lastwave.app.data.tidal.TidalAccount
import com.lastwave.app.data.tidal.TidalSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecommendationRepository @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
    private val relatedGraph: RelatedGraphRepository,
    private val spotifyRecommendations: SpotifyRecommendationApi,
    private val settingsPreferences: SettingsPreferences,
    private val spotifyPreferences: SpotifyPreferences,
    private val tidalAccount: TidalAccount,
    private val tidalSource: TidalSource,
) {
    suspend fun relatedFor(
        seedVideoId: String?,
        title: String,
        artist: String,
        limit: Int = 20,
        durationSeconds: Int? = null,
    ): List<YouTubeMusicTrack> {
        val source = activeExternalSource()
        if (source != null) {
            val matched = externalTracks(source, title, artist, durationSeconds, limit)
            if (matched.size >= 3) return matched
            if (matched.size in 1..2 && !seedVideoId.isNullOrBlank()) {
                val needed = (limit - matched.size).coerceAtLeast(0)
                val fallback = if (needed > 0) {
                    relatedGraph.relatedFor(seedVideoId, limit = needed, prefetchStreams = false)
                } else {
                    emptyList()
                }
                val existingIds = matched.mapTo(mutableSetOf()) { it.videoId }
                return matched + fallback.filter { existingIds.add(it.videoId) }
            }
            if (matched.isNotEmpty()) return matched
        }
        return seedVideoId?.takeIf { it.isNotBlank() }
            ?.let { relatedGraph.relatedFor(it, limit, prefetchStreams = false) }
            .orEmpty()
    }

    /** True when recommendations are seeded by a connected Spotify or TIDAL account instead of YouTube Music. */
    suspend fun usesExternalSeeds(): Boolean = activeExternalSource() != null

    suspend fun activeSourceName(): String = when (activeExternalSource()) {
        RecommendationSource.SPOTIFY -> "Spotify"
        RecommendationSource.TIDAL -> "TIDAL"
        else -> "YouTube Music"
    }

    private suspend fun activeExternalSource(): RecommendationSource? =
        when (val source = settingsPreferences.settings.first().recommendationSource) {
            RecommendationSource.SPOTIFY -> source.takeIf { spotifyPreferences.isConnected.first() }
            RecommendationSource.TIDAL -> source.takeIf { tidalAccount.isLoggedIn }
            RecommendationSource.YOUTUBE_MUSIC -> null
        }

    private class Candidate(val name: String, val artists: List<String>, val durationMs: Long?)

    /** Seeds from [source], each matched to a playable YouTube Music track. */
    private suspend fun externalTracks(
        source: RecommendationSource,
        title: String,
        artist: String,
        durationSeconds: Int?,
        limit: Int,
    ): List<YouTubeMusicTrack> = try {
        val candidates = when (source) {
            RecommendationSource.TIDAL -> tidalSource.radioFor(title, artist, limit).map {
                Candidate(it.title, it.artist.split(", ").filter(String::isNotBlank), (it.duration * 1000).toLong().takeIf { ms -> ms > 0 })
            }
            else -> spotifyRecommendations.recommendationsFor(
                title = title,
                artist = artist,
                durationMs = durationSeconds?.toLong()?.times(1000L),
                limit = limit,
            ).mapNotNull { item ->
                val name = item.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Candidate(name, item.artists.mapNotNull { it.name?.takeIf(String::isNotBlank) }, item.duration_ms ?: item.durationMs)
            }
        }
        val matched = coroutineScope {
            val semaphore = Semaphore(MAX_CONCURRENT_MATCHES)
            candidates.take(MAX_MATCHES_PER_CALL).map { candidate ->
                async {
                    val name = candidate.name
                    val primaryArtist = candidate.artists.firstOrNull().orEmpty()
                    val additionalArtists = candidate.artists.drop(1)
                    val artists = if (additionalArtists.isNotEmpty() && !FEAT_REGEX.containsMatchIn(name)) {
                        if (primaryArtist.isNotBlank()) "$primaryArtist feat. ${additionalArtists.joinToString(", ")}" else additionalArtists.joinToString(", ")
                    } else {
                        primaryArtist.ifBlank { candidate.artists.joinToString(", ") }
                    }
                    val expectedDurationMs = candidate.durationMs
                    semaphore.withPermit {
                        innerTube.findBestMatchOrNull(
                            name,
                            artists,
                            prefetchStreams = false,
                            expectedDurationMs = expectedDurationMs,
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }.distinctBy { it.videoId }
        Log.i(TAG, "$source -> ${matched.size}/${candidates.size} tracks matched on YouTube Music")
        matched
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Log.w(TAG, "$source seeds unavailable, keeping YouTube Music: ${error.message}")
        emptyList()
    }

    private companion object {
        const val TAG = "RecommendationRepository"
        const val MAX_CONCURRENT_MATCHES = 4
        const val MAX_MATCHES_PER_CALL = 24
        val FEAT_REGEX = Regex("""\b(feat|ft|featuring)\b""", RegexOption.IGNORE_CASE)
    }
}
