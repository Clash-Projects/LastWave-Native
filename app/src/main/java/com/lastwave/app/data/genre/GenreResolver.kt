package com.lastwave.app.data.genre

import android.util.Log
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.network.ITunesSearchClient
import com.lastwave.app.data.network.LastFmApiService
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GenreResolver"
private const val CACHE_MAX = 300

/**
 * Faithful port of _resolveTrackGenre(): track.getInfo's top 2 tags
 * (excluding "seen live") joined with ", " -> iTunes primaryGenreName
 * fallback (rejecting the generic "Music" bucket). Session-scoped LRU
 * cache, 300 entries — used by every screen's track context menu to show
 * the "Genre: ..." row and gate the "Explore This Genre" action.
 */
@Singleton
class GenreResolver @Inject constructor(
    private val api: LastFmApiService,
    private val sessionPreferences: SessionPreferences,
    private val iTunesSearchClient: ITunesSearchClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Simple LRU: LinkedHashMap with access order + manual eviction, guarded
    // by a lock since resolve() can be called concurrently from many rows.
    private val cache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > CACHE_MAX
    }
    private val lock = Any()

    suspend fun resolve(name: String, artist: String): String {
        if (name.isBlank() || artist.isBlank()) return ""
        val key = "$name|$artist".lowercase()
        synchronized(lock) { cache[key]?.let { return it } }

        var result = ""

        // Step 1: Last.fm track.getInfo -> toptags
        try {
            val session = sessionPreferences.session.first()
            if (session.apiKey.isNotBlank()) {
                val response = api.get(
                    mapOf(
                        "method" to "track.getInfo",
                        "track" to name,
                        "artist" to artist,
                        "autocorrect" to "1",
                        "api_key" to session.apiKey,
                        "format" to "json",
                    ),
                )
                val body = response.body()?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val parsed = json.parseToJsonElement(body).jsonObject
                    val tagsEl = parsed["track"]?.jsonObject?.get("toptags")?.jsonObject?.get("tag")
                    val names = com.lastwave.app.data.generate.GenerateJson.asObjectList(tagsEl)
                        .mapNotNull { (it["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content }
                        .filter { it.lowercase() != "seen live" }
                        .take(2)
                    if (names.isNotEmpty()) result = names.joinToString(", ")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "track.getInfo toptags miss for $name / $artist", e)
        }

        // Step 2: iTunes primaryGenreName fallback
        if (result.isEmpty()) {
            val song = iTunesSearchClient.searchBestMatch(name, artist)
                ?: iTunesSearchClient.searchSongs("$name $artist", limit = 1).firstOrNull()
            val genre = song?.primaryGenreName
            if (!genre.isNullOrBlank() && genre != "Music") {
                result = genre
            }
        }

        synchronized(lock) { cache[key] = result }
        return result
    }
}
