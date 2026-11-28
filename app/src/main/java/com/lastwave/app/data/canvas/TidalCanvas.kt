package com.lastwave.app.data.canvas

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.Locale

object TidalCanvas {

    private const val LOG_TAG = "TidalCanvas"
    private const val SEARCH_ENDPOINT = "https://api.tidal.com/v1/search"
    private const val PUBLIC_EMBED_TOKEN = "vNVdglQOjFJJGG2U"

    private val parser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val regionCode: String by lazy {
        val code = Locale.getDefault().country
        if (code.length == 2) code.uppercase(Locale.ROOT) else "US"
    }

    fun search(client: OkHttpClient, title: String, artist: String, album: String?): CanvasArtwork? {
        val searchTerm = if (album.isNullOrBlank()) "$artist $title" else "$album $artist $title"
        val entries = querySection(client, searchTerm, searchType = "TRACKS", rootKey = "tracks")
            ?: return null

        for (element in entries) {
            val trackObj = element as? JsonObject ?: continue
            val hitTitle = trackObj["title"]?.jsonPrimitive?.contentOrNull ?: continue
            val credited = readArtistNames(trackObj)
            if (!matchesQuery(hitTitle, credited, title, artist)) continue

            val albumObj = trackObj["album"]?.jsonObject ?: continue
            val coverUuid = albumObj["videoCover"]?.jsonPrimitive?.contentOrNull
            if (coverUuid.isNullOrBlank()) continue
            val mp4Url = coverUrl(coverUuid) ?: continue

            Log.d(LOG_TAG, "Video cover found for '$hitTitle' by ${credited.joinToString()}")
            return CanvasArtwork(
                url = mp4Url,
                title = hitTitle,
                artist = credited.joinToString(", ").ifBlank { null },
                album = albumObj["title"]?.jsonPrimitive?.contentOrNull,
                source = CanvasSource.TIDAL,
            )
        }
        return null
    }

    fun searchAlbum(client: OkHttpClient, album: String, artist: String): CanvasArtwork? {
        val entries = querySection(client, "$album $artist", searchType = "ALBUMS", rootKey = "albums")
            ?: return null

        for (element in entries) {
            val albumObj = element as? JsonObject ?: continue
            val hitAlbum = albumObj["title"]?.jsonPrimitive?.contentOrNull ?: continue
            val credited = readArtistNames(albumObj)
            if (!matchesQuery(hitAlbum, credited, album, artist)) continue

            val coverUuid = albumObj["videoCover"]?.jsonPrimitive?.contentOrNull
            if (coverUuid.isNullOrBlank()) continue
            val mp4Url = coverUrl(coverUuid) ?: continue

            Log.d(LOG_TAG, "Video cover found for album '$hitAlbum' by ${credited.joinToString()}")
            return CanvasArtwork(
                url = mp4Url,
                title = hitAlbum,
                artist = credited.joinToString(", ").ifBlank { null },
                album = hitAlbum,
                source = CanvasSource.TIDAL,
            )
        }
        return null
    }

    internal fun coverUrl(id: String): String? {
        val segments = id.split('-')
        if (segments.size != 5) return null
        return "https://resources.tidal.com/videos/${segments.joinToString("/")}/1280x1280.mp4"
    }

    private fun querySection(
        client: OkHttpClient,
        term: String,
        searchType: String,
        rootKey: String,
    ): JsonArray? {
        val endpoint = SEARCH_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("query", term)
            .addQueryParameter("limit", "10")
            .addQueryParameter("types", searchType)
            .addQueryParameter("countryCode", regionCode)
            .build()
            .toString()

        val response = canvasGet(
            client,
            endpoint,
            mapOf("X-Tidal-Token" to PUBLIC_EMBED_TOKEN, "User-Agent" to CANVAS_UA),
        ) ?: return null

        val root = try {
            parser.parseToJsonElement(response).jsonObject
        } catch (_: Exception) {
            return null
        }
        return root[rootKey]?.jsonObject?.get("items")?.jsonArray
    }

    private fun readArtistNames(node: JsonObject): List<String> {
        val array = node["artists"]?.jsonArray ?: return emptyList()
        val names = ArrayList<String>(array.size)
        for (item in array) {
            val name = item.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            if (name != null) names.add(name)
        }
        return names
    }

    private fun matchesQuery(
        foundName: String,
        foundArtists: List<String>,
        expectedName: String,
        expectedArtist: String,
    ): Boolean {
        if (foundName.normalizeForMatch() != expectedName.normalizeForMatch()) return false
        val wantedList = splitArtists(expectedArtist)
        val creditedList = foundArtists.mapNotNull { it.normalizeForMatch().takeIf(String::isNotBlank) }
        if (wantedList.isEmpty() || creditedList.isEmpty()) return false
        return wantedList.all { want ->
            creditedList.any { artist -> artist == want || artist.contains(want) || want.contains(artist) }
        }
    }
}
