package com.lastwave.app.data.lyrics

import android.util.Log
import com.lastwave.app.data.canvas.SpotifyCanvas
import com.lastwave.app.data.spotify.SpotifyAuthRepository
import com.lastwave.app.data.spotify.SpotifyColorLyricsResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SpotifyLyrics"

/**
 * Fetches official Spotify Color Lyrics (time-synced line-by-line and syllable timings)
 * using the user's authenticated Spotify Web session credentials.
 */
@Singleton
class SpotifyLyricsApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val spotifyAuthRepository: SpotifyAuthRepository,
    private val spotifyCanvas: SpotifyCanvas,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSeconds: Int? = null,
    ): LyricsResult.Success? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null

        val personalToken = spotifyAuthRepository.getPersonalToken() ?: return@withContext null
        val clientToken = spotifyAuthRepository.getClientToken() ?: return@withContext null

        try {
            val durationMs = durationSeconds?.let { it.toLong() * 1000L }
            val trackData = spotifyCanvas.searchTrack(personalToken, clientToken, title, artist, durationMs)
                ?: return@withContext null
            val trackId = trackData.id ?: trackData.uri?.substringAfterLast(":")
                ?: return@withContext null

            val url = "https://spclient.wg.spotify.com/color-lyrics/v2/track/$trackId?format=json&vocalRemoval=false&market=from_token"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $personalToken")
                .header("Client-Token", clientToken)
                .header("App-Platform", "WebPlayer")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "application/json")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code != 404) {
                        Log.d(TAG, "Spotify color lyrics HTTP ${response.code} for track $trackId")
                    }
                    return@use null
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@use null

                val parsed = runCatching { json.decodeFromString<SpotifyColorLyricsResponse>(body) }.getOrNull()
                    ?: return@use null
                val lyricsData = parsed.lyrics ?: return@use null
                val rawLines = lyricsData.lines
                if (rawLines.isEmpty()) return@use null

                val isSynced = lyricsData.syncType == "LINE_SYNCED" || lyricsData.syncType == "SYLLABLE_SYNCED"
                val lines = rawLines.mapNotNull { line ->
                    val startMs = line.startTimeMs?.toLongOrNull() ?: 0L
                    val endMs = line.endTimeMs?.toLongOrNull() ?: 0L
                    val text = line.words.orEmpty().trim()
                    if (text.isBlank() && startMs == 0L) return@mapNotNull null
                    val duration = if (endMs > startMs) endMs - startMs else 0L
                    LyricLine(
                        timeMs = startMs,
                        durationMs = duration,
                        text = text,
                    )
                }

                if (lines.isEmpty()) return@use null

                val providerName = lyricsData.providerDisplayName ?: lyricsData.provider ?: "Spotify"
                LyricsResult.Success(
                    lines = lines,
                    isSynced = isSynced,
                    isWordSynced = lyricsData.syncType == "SYLLABLE_SYNCED",
                    plainLyrics = lines.joinToString("\n") { it.text },
                    source = "Spotify ($providerName)",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch Spotify color lyrics for '$title' by '$artist': ${e.message}")
            null
        }
    }
}
