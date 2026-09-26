package com.lastwave.app.data.canvas

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/**
 * A community-curated `song + artist -> looping video` index (ViMusic manifest).
 *
 * Covers back catalog and independent tracks that major streaming services lack.
 * Held in memory for [TTL_MS] to avoid re-fetching per track.
 */
object CommunityCanvas {

    private const val TAG = "CommunityCanvas"
    private const val MANIFEST = "https://vivimusicanvas.mkmdevilmi.workers.dev/canvas.json"
    private const val TTL_MS = 30L * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class Entry(
        val song: String,
        val artist: String,
        val album: String,
        val url: String,
    )

    @Volatile private var entries: List<Entry> = emptyList()
    @Volatile private var fetchedAtMs = 0L

    fun search(client: OkHttpClient, title: String, artist: String, album: String?): CanvasArtwork? {
        val index = manifest(client).ifEmpty { return null }

        val wantTitle = title.normalizeForMatch()
        val wantArtist = artist.normalizeForMatch()
        val wantAlbum = album?.normalizeForMatch()

        val hit = index.firstOrNull { entry ->
            val song = entry.song.normalizeForMatch()
            val credited = entry.artist.normalizeForMatch()
            val listed = entry.album.normalizeForMatch()
            val titleOk = song.isNotBlank() &&
                (wantTitle.contains(song) || song.contains(wantTitle))
            val artistOk = credited.isNotBlank() &&
                (wantArtist.contains(credited) || credited.contains(wantArtist))
            val albumOk = listed.isBlank() || wantAlbum.isNullOrBlank() || listed == wantAlbum
            titleOk && artistOk && albumOk
        } ?: return null

        Log.d(TAG, "manifest hit for '${hit.song}' by '${hit.artist}'")
        return CanvasArtwork(
            url = hit.url,
            title = hit.song,
            artist = hit.artist,
            album = hit.album.takeIf { it.isNotBlank() },
            source = CanvasSource.COMMUNITY,
        )
    }

    @Synchronized
    private fun manifest(client: OkHttpClient): List<Entry> {
        val now = System.currentTimeMillis()
        if (entries.isNotEmpty() && now - fetchedAtMs < TTL_MS) return entries

        val body = canvasGet(client, MANIFEST, mapOf("User-Agent" to CANVAS_UA))
        if (body == null) {
            fetchedAtMs = now
            return entries
        }

        val parsed = runCatching {
            json.parseToJsonElement(body).jsonObject["items"]?.jsonArray
                ?.mapNotNull { item ->
                    val obj = item.jsonObject
                    Entry(
                        song = obj["song"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        artist = obj["artist"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        album = obj["album"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    )
                }
        }.getOrNull().orEmpty()

        if (parsed.isNotEmpty()) {
            Log.d(TAG, "manifest holds ${parsed.size} entries")
            entries = parsed
        }
        fetchedAtMs = now
        return entries
    }
}
