package com.lastwave.app.data.recommend

import android.util.Log
import com.lastwave.app.data.local.RecommendationSource
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.repository.RelatedGraphRepository
import com.lastwave.app.data.spotify.SpotifyPreferences
import com.lastwave.app.data.spotify.SpotifyRecommendationApi
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
) {
    suspend fun relatedFor(
        seedVideoId: String?,
        title: String,
        artist: String,
        limit: Int = 20,
        durationSeconds: Int? = null,
    ): List<YouTubeMusicTrack> {
        if (isSpotifyActive()) {
            val seeded = spotifyTracks(title, artist, durationSeconds, limit)
            if (seeded.isNotEmpty()) return seeded
        }
        return seedVideoId?.takeIf { it.isNotBlank() }
            ?.let { relatedGraph.relatedFor(it, limit, prefetchStreams = false) }
            .orEmpty()
    }

    suspend fun isSpotifyActive(): Boolean =
        settingsPreferences.settings.first().recommendationSource == RecommendationSource.SPOTIFY &&
            spotifyPreferences.isConnected.first()

    private suspend fun spotifyTracks(
        title: String,
        artist: String,
        durationSeconds: Int?,
        limit: Int,
    ): List<YouTubeMusicTrack> = try {
        val candidates = spotifyRecommendations.recommendationsFor(
            title = title,
            artist = artist,
            durationMs = durationSeconds?.toLong()?.times(1000L),
            limit = limit,
        )
        val matched = coroutineScope {
            val semaphore = Semaphore(MAX_CONCURRENT_MATCHES)
            candidates.take(MAX_MATCHES_PER_CALL).map { candidate ->
                async {
                    val name = candidate.name?.takeIf { it.isNotBlank() } ?: return@async null
                    val artists = candidate.artists.mapNotNull { it.name }.joinToString(", ")
                    semaphore.withPermit {
                        innerTube.findBestMatchOrNull(name, artists, prefetchStreams = false)
                    }
                }
            }.awaitAll().filterNotNull()
        }.distinctBy { it.videoId }
        Log.i(TAG, "Spotify -> ${matched.size}/${candidates.size} tracks matched on YouTube Music")
        matched
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Log.w(TAG, "Spotify seeds unavailable, keeping YouTube Music: ${error.message}")
        emptyList()
    }

    private companion object {
        const val TAG = "RecommendationRepository"
        const val MAX_CONCURRENT_MATCHES = 4
        const val MAX_MATCHES_PER_CALL = 24
    }
}
