package com.lastwave.app.data.spotify

import android.util.Log
import com.lastwave.app.data.canvas.SpotifyCanvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SpotifyRecs"
private const val RADIO_APOLLO_URL = "https://spclient.wg.spotify.com/radio-apollo/v3/stations"
private const val RECOMMENDATIONS_URL = "https://api.spotify.com/v1/recommendations"
private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

@Singleton
class SpotifyRecommendationApi @Inject constructor(
    okHttpClient: OkHttpClient,
    private val authRepository: SpotifyAuthRepository,
    private val spotifyCanvas: SpotifyCanvas,
) {
    private val client = okHttpClient.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Recommendations seeded by the Spotify twin of [title] / [artist].
     */
    suspend fun recommendationsFor(
        title: String,
        artist: String,
        durationMs: Long? = null,
        limit: Int = 20,
    ): List<SpotifyWebSearchResponse.TracksContainer.TrackItem> = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext emptyList()
        val personalToken = authRepository.getPersonalToken() ?: return@withContext emptyList()
        val clientToken = authRepository.getClientToken()
        val seedTrackId = clientToken?.let { token ->
            runCatching { spotifyCanvas.searchTrack(personalToken, token, title, artist, durationMs) }
                .getOrNull()
                ?.resolvedId
        } ?: spotifyCanvas.searchTrackFallback(personalToken, title, artist, durationMs)
        if (seedTrackId == null) {
            Log.d(TAG, "No Spotify match for '$title' - '$artist'")
            return@withContext emptyList()
        }

        val cleanSeedId = seedTrackId.removePrefix("spotify:track:")

        val apolloTracks = fetchApolloRadio(personalToken, clientToken, cleanSeedId, limit)
        if (apolloTracks.isNotEmpty()) {
            return@withContext apolloTracks
        }

        fetchWebRecommendations(personalToken, clientToken, cleanSeedId, limit)
    }

    private fun fetchApolloRadio(
        token: String,
        clientToken: String?,
        seedTrackId: String,
        limit: Int,
    ): List<SpotifyWebSearchResponse.TracksContainer.TrackItem> {
        return try {
            val url = "$RADIO_APOLLO_URL/spotify:track:$seedTrackId".toHttpUrl().newBuilder()
                .addQueryParameter("count", limit.coerceIn(1, 100).toString())
                .addQueryParameter("autoplay", "true")
                .build()
            val body = get(token, clientToken, url) ?: return emptyList()
            val response = json.decodeFromString<SpotifyRecommendationsResponse>(body)
            val tracks = response.allTracks
            if (tracks.isNotEmpty()) {
                Log.i(TAG, "OK [Apollo Radio] seed=$seedTrackId limit=$limit -> ${tracks.size} tracks")
            }
            tracks
        } catch (e: Exception) {
            Log.d(TAG, "Apollo radio query failed: ${e.message}")
            emptyList()
        }
    }

    private fun fetchWebRecommendations(
        token: String,
        clientToken: String?,
        seedTrackId: String,
        limit: Int,
    ): List<SpotifyWebSearchResponse.TracksContainer.TrackItem> {
        return try {
            val url = RECOMMENDATIONS_URL.toHttpUrl().newBuilder()
                .addQueryParameter("seed_tracks", seedTrackId)
                .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
                .build()
            val body = get(token, clientToken, url) ?: return emptyList()
            val tracks = json.decodeFromString<SpotifyRecommendationsResponse>(body).allTracks
            Log.i(TAG, "OK [Web API] seed=$seedTrackId limit=$limit -> ${tracks.size} tracks")
            tracks
        } catch (e: Exception) {
            Log.w(TAG, "Web recommendations query failed: ${e.message}")
            emptyList()
        }
    }

    private fun get(token: String, clientToken: String?, url: HttpUrl): String? {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)
            .header("App-platform", "WebPlayer")
            .header("Origin", "https://open.spotify.com")
            .header("Referer", "https://open.spotify.com/")
            .header("Accept", "application/json")
            .apply {
                if (!clientToken.isNullOrBlank()) {
                    header("Client-Token", clientToken)
                }
            }
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val retryAfter = response.header("Retry-After")
                Log.w(
                    TAG,
                    "HTTP ${response.code} from ${url.encodedPath}" +
                        (retryAfter?.let { " (retry after ${it}s)" } ?: ""),
                )
                return@use null
            }
            response.body?.string()
        }
    }
}
