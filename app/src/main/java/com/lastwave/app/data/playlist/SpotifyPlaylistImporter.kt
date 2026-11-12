package com.lastwave.app.data.playlist

import android.net.Uri
import android.util.Log
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.spotify.SpotifyAuthRepository
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "SpotifyImport"

/**
 * Reads the track list of a Spotify playlist and matches it to playable YouTube Music tracks.
 *
 * Supports both:
 * 1. Direct Web API query when authenticated with Spotify account credentials (unlimited track count, private/account playlists).
 * 2. Public embed page scraping fallback: `https://open.spotify.com/embed/playlist/<id>` reading `__NEXT_DATA__`.
 */
@Singleton
class SpotifyPlaylistImporter @Inject constructor(
    okHttpClient: OkHttpClient,
    private val innerTube: InnerTubeMusicApi,
    private val spotifyAuthRepository: SpotifyAuthRepository,
) {
    private val client = okHttpClient.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Pure parser: Spotify embed HTML in, parsed playlist out. Performs no
     * network and never throws — unreadable input yields an empty [rows] list so
     * the caller can show a friendly "could not read that playlist" message.
     */
    internal fun parseEmbedPage(html: String, fallbackTitle: String = "Spotify Playlist"): ExternalPlaylistResult {
        val payload = NEXT_DATA.find(html)?.groupValues?.getOrNull(1)?.let(::unescapeHtml)
        if (payload.isNullOrBlank()) {
            Log.w(TAG, "No __NEXT_DATA__ payload in Spotify embed page")
            return emptyResult(fallbackTitle)
        }
        val root = try {
            json.parseToJsonElement(payload)
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable __NEXT_DATA__ JSON", e)
            return emptyResult(fallbackTitle)
        }
        var title: String? = null
        val rows = mutableListOf<ExternalTrackRow>()
        val seenUris = mutableSetOf<String>()
        walk(root) parseRow@{ element ->
            val uri = element.text("uri") ?: return@parseRow
            val name = element.text("title") ?: return@parseRow
            when {
                uri.startsWith(SPOTIFY_PLAYLIST_URI) -> {
                    if (title == null) title = name.clean()
                }
                uri.startsWith(SPOTIFY_TRACK_URI) -> {
                    val trackTitle = name.clean()
                    if (trackTitle.isBlank() || !seenUris.add(uri)) return@parseRow
                    rows += ExternalTrackRow(
                        title = trackTitle,
                        artist = element.text("subtitle").orEmpty().clean(),
                    )
                }
            }
        }
        return ExternalPlaylistResult(
            source = ExternalPlaylistSource.SPOTIFY,
            title = title?.takeIf(String::isNotBlank) ?: fallbackTitle,
            rows = rows,
        )
    }

    /**
     * Downloads playlist metadata and tracks. Uses authenticated Spotify Web API when available,
     * otherwise falls back to public embed page scraping.
     */
    suspend fun fetchPlaylist(urlOrId: String): ExternalPlaylistResult = withContext(Dispatchers.IO) {
        var input = urlOrId.trim()
        if (input.contains("spotify.link")) {
            val resolveReq = Request.Builder().url(input).head().build()
            runCatching {
                client.newCall(resolveReq).execute().use { resp ->
                    input = resp.request.url.toString()
                }
            }
        }
        val id = ExternalPlaylistLink.extractId(input, ExternalPlaylistSource.SPOTIFY)
            ?: throw IllegalArgumentException("That does not look like a Spotify playlist link.")

        // First attempt via authenticated Spotify API if personal token is available
        val token = spotifyAuthRepository.getPersonalToken()
        val clientToken = spotifyAuthRepository.getClientToken()
        if (!token.isNullOrBlank()) {
            val apiResult = try {
                fetchViaPathfinder(id, token, clientToken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Pathfinder playlist fetch failed, falling back to embed: ${e.message}")
                null
            }
            if (apiResult != null && apiResult.rows.isNotEmpty()) {
                return@withContext apiResult
            }
            Log.w(TAG, "Authenticated fetch returned no tracks for $id; using public embed fallback")
        } else {
            Log.w(TAG, "No Spotify personal token; using public embed fallback (max ~100 tracks)")
        }

        // Fallback to public embed page scraping
        val request = Request.Builder()
            .url("$EMBED_BASE$id")
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "text/html")
            .get()
            .build()
        val html = client.newCall(request).execute().use { response ->
            if (response.code != 200) {
                throw IOException("Spotify playlist page returned HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }
        parseEmbedPage(html)
    }

    private fun fetchViaPathfinder(id: String, token: String, clientToken: String?): ExternalPlaylistResult? {
        val rows = mutableListOf<ExternalTrackRow>()
        var title = "Spotify Playlist"
        var author: String? = null
        var artworkUrl: String? = null
        var offset = 0
        var totalCount = Int.MAX_VALUE
        var pages = 0

        while (offset < totalCount && pages < 10) {
            pages++
            val variables = """{"uri":"spotify:playlist:$id","offset":$offset,"limit":100,"enableWatchFeedEntrypoint":false}"""
            val body = pathfinderGet("fetchPlaylist", variables, HASH_FETCH_PLAYLIST, token, clientToken)
                ?: return if (rows.isEmpty()) null else buildResult(title, author, artworkUrl, rows)
            if (body.isBlank()) break

            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: break
            val playlist = root["data"]?.jsonObject?.get("playlistV2")?.jsonObject ?: break

            if (pages == 1) {
                title = playlist["name"]?.jsonPrimitive?.contentOrNull ?: title
                author = playlist["ownerV2"]?.jsonObject?.get("data")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                artworkUrl = playlist["images"]?.jsonObject?.get("items")?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("sources")?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            }

            val content = playlist["content"]?.jsonObject
            totalCount = content?.get("totalCount")?.jsonPrimitive?.intOrNull ?: 0
            val items = content?.get("items")?.jsonArray.orEmpty()
            if (items.isEmpty()) break

            for (itemElem in items) {
                val itemObj = itemElem as? JsonObject ?: continue
                val trackData = itemObj["itemV2"]?.jsonObject?.get("data")?.jsonObject
                    ?: (itemObj["track"] as? JsonObject)
                    ?: continue
                val name = trackData["name"]?.jsonPrimitive?.contentOrNull?.clean() ?: continue
                if (name.isBlank()) continue
                val artists = trackData["artists"]?.jsonObject?.get("items")?.jsonArray?.mapNotNull { art ->
                    (art as? JsonObject)?.get("profile")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull?.clean()
                        ?: (art as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.clean()
                }?.joinToString(", ").orEmpty()
                val album = trackData["albumOfTrack"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull?.clean()
                    ?: (trackData["album"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.clean()
                rows += ExternalTrackRow(
                    title = name,
                    artist = artists,
                    album = album,
                )
            }

            offset += items.size
        }

        if (rows.isEmpty()) return null
        return buildResult(title, author, artworkUrl, rows)
    }

    private fun buildResult(
        title: String,
        author: String?,
        artworkUrl: String?,
        rows: List<ExternalTrackRow>,
    ) = ExternalPlaylistResult(
        source = ExternalPlaylistSource.SPOTIFY,
        title = title,
        author = author,
        artworkUrl = artworkUrl,
        rows = rows,
    )

    /**
     * Single hardened Pathfinder GET: retries transient failures (IO errors, 429, 5xx)
     * with backoff, logs every non-success status and GraphQL-level error (stale
     * persisted-query hash, expired token) instead of silently yielding "empty",
     * and returns null only after all attempts fail.
     */
    private fun pathfinderGet(
        operation: String,
        variables: String,
        hash: String,
        token: String,
        clientToken: String?,
    ): String? {
        val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$hash"}}"""
        val url = "$PATHFINDER_ENDPOINT?operationName=$operation&variables=${Uri.encode(variables)}&extensions=${Uri.encode(extensions)}"
        repeat(PATHFINDER_ATTEMPTS) { attempt ->
            val builder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "application/json")
            if (!clientToken.isNullOrBlank()) builder.header("Client-Token", clientToken)
            var retry = false
            try {
                client.newCall(builder.build()).execute().use { response ->
                    val code = response.code
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        if (body.contains("PersistedQueryNotFound") || body.contains("\"errors\"")) {
                            Log.w(TAG, "$operation returned GraphQL errors: ${body.take(200)}")
                        }
                        return body
                    }
                    Log.w(TAG, "$operation failed HTTP $code (attempt ${attempt + 1})")
                    retry = code == 429 || code >= 500
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "$operation I/O error (attempt ${attempt + 1}): ${e.message}")
                retry = true
            }
            if (!retry) return null
            if (attempt < PATHFINDER_ATTEMPTS - 1) Thread.sleep(600L * (attempt + 1))
        }
        return null
    }

    /**
     * Fetches playlists from the connected Spotify account library via Pathfinder.
     *
     * Spotify's `libraryV3` list query omits per-playlist track counts (`content.totalCount`),
     * so any playlist missing a summary count is resolved via a lightweight `fetchPlaylist`
     * (`limit=1`) query instead of defaulting to 0.
     */
    suspend fun fetchUserPlaylists(): List<SpotifyUserPlaylist> = withContext(Dispatchers.IO) {
        val token = spotifyAuthRepository.getPersonalToken() ?: return@withContext emptyList()
        val clientToken = spotifyAuthRepository.getClientToken()
        try {
            val variables = """{"filters":["Playlists"],"order":null,"textFilter":null,"features":[],"limit":50,"offset":0,"flatten":false,"expandedFolders":[],"folderUri":null,"includeFoldersWhenFlattening":true}"""
            val body = pathfinderGet("libraryV3", variables, HASH_LIBRARY_V3, token, clientToken)
                ?: return@withContext emptyList()
            val playlists = parseUserPlaylists(body)
            if (playlists.none { it.trackCount == null }) return@withContext playlists

            val semaphore = Semaphore(USER_PLAYLIST_COUNT_CONCURRENCY)
            coroutineScope {
                playlists.map { pl ->
                    if (pl.trackCount != null) {
                        async { pl }
                    } else {
                        async {
                            val count = semaphore.withPermit {
                                fetchPlaylistTrackCount(pl.id, token, clientToken)
                            }
                            if (count != null) pl.copy(trackCount = count) else pl
                        }
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Failed to fetch user Spotify playlists: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetches only the total count of the user's Spotify Liked Songs collection.
     */
    suspend fun fetchLikedSongsCount(): Int? = withContext(Dispatchers.IO) {
        val token = spotifyAuthRepository.getPersonalToken() ?: return@withContext null
        val clientToken = spotifyAuthRepository.getClientToken()
        try {
            val variables = """{"offset":0,"limit":1}"""
            val body = pathfinderGet("fetchLibraryTracks", variables, HASH_FETCH_LIBRARY_TRACKS, token, clientToken)
                ?: return@withContext null
            parseLikedSongsTotalCount(body)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Failed to fetch Spotify Liked Songs count: ${e.message}")
            null
        }
    }

    private fun fetchPlaylistTrackCount(id: String, token: String, clientToken: String?): Int? {
        val variables = """{"uri":"spotify:playlist:$id","offset":0,"limit":1,"enableWatchFeedEntrypoint":false}"""
        val body = pathfinderGet("fetchPlaylist", variables, HASH_FETCH_PLAYLIST, token, clientToken) ?: return null
        return parsePlaylistTotalCount(body)
    }

    internal fun parseUserPlaylists(body: String): List<SpotifyUserPlaylist> {
        if (body.isBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val libraryItems = root["data"]?.jsonObject?.get("me")?.jsonObject
            ?.get("libraryV3")?.jsonObject?.get("items")?.jsonArray.orEmpty()

        return libraryItems.mapNotNull { itemElement ->
            val obj = itemElement as? JsonObject ?: return@mapNotNull null
            val itemWrapper = obj["item"] as? JsonObject ?: return@mapNotNull null
            val plData = itemWrapper["data"] as? JsonObject ?: return@mapNotNull null
            val typename = itemWrapper["__typename"]?.jsonPrimitive?.contentOrNull
                ?: plData["__typename"]?.jsonPrimitive?.contentOrNull
            if (typename != "PlaylistResponseWrapper") return@mapNotNull null

            val uri = plData["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val id = uri.removePrefix("spotify:playlist:")
            if (id.isBlank()) return@mapNotNull null

            val name = plData["name"]?.jsonPrimitive?.contentOrNull ?: "Spotify Playlist"
            val desc = plData["description"]?.jsonPrimitive?.contentOrNull
            val img = plData["images"]?.jsonObject?.get("items")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("sources")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull

            SpotifyUserPlaylist(
                id = id,
                name = name,
                description = desc,
                artworkUrl = img,
                trackCount = extractLibraryPlaylistCount(plData),
            )
        }
    }

    private fun extractLibraryPlaylistCount(plData: JsonObject): Int? =
        plData["content"]?.jsonObject?.get("totalCount")?.jsonPrimitive?.intOrNull
            ?: plData["tracks"]?.jsonObject?.get("totalCount")?.jsonPrimitive?.intOrNull
            ?: plData["tracks"]?.jsonObject?.get("total")?.jsonPrimitive?.intOrNull
            ?: plData["totalCount"]?.jsonPrimitive?.intOrNull

    internal fun parsePlaylistTotalCount(body: String): Int? {
        if (body.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return root["data"]?.jsonObject?.get("playlistV2")?.jsonObject
            ?.get("content")?.jsonObject?.get("totalCount")?.jsonPrimitive?.intOrNull
    }

    internal fun parseLikedSongsTotalCount(body: String): Int? {
        if (body.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return root["data"]?.jsonObject?.get("me")?.jsonObject
            ?.get("library")?.jsonObject?.get("tracks")?.jsonObject
            ?.get("totalCount")?.jsonPrimitive?.intOrNull
    }

    /**
     * Fetches the user's Spotify Liked Songs collection via Pathfinder.
     */
    suspend fun fetchLikedSongs(): ExternalPlaylistResult = withContext(Dispatchers.IO) {
        val token = spotifyAuthRepository.getPersonalToken()
            ?: throw IllegalStateException("Spotify account is not connected.")
        val clientToken = spotifyAuthRepository.getClientToken()

        val rows = mutableListOf<ExternalTrackRow>()
        var offset = 0
        var totalCount = Int.MAX_VALUE
        var pages = 0

        while (offset < totalCount && pages < 20) {
            pages++
            val variables = """{"offset":$offset,"limit":50}"""
            val body = pathfinderGet("fetchLibraryTracks", variables, HASH_FETCH_LIBRARY_TRACKS, token, clientToken)
                ?: break

            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: break
            val tracksObj = root["data"]?.jsonObject?.get("me")?.jsonObject
                ?.get("library")?.jsonObject?.get("tracks")?.jsonObject ?: break

            totalCount = tracksObj["totalCount"]?.jsonPrimitive?.intOrNull ?: 0
            val items = tracksObj["items"]?.jsonArray.orEmpty()
            if (items.isEmpty()) break

            for (item in items) {
                val itemObj = item as? JsonObject ?: continue
                val trackObj = (itemObj["track"] as? JsonObject)?.get("data") as? JsonObject
                    ?: itemObj["track"] as? JsonObject
                    ?: continue
                val name = trackObj["name"]?.jsonPrimitive?.contentOrNull?.clean() ?: continue
                if (name.isBlank()) continue
                val artists = trackObj["artists"]?.jsonObject?.get("items")?.jsonArray?.mapNotNull { art ->
                    (art as? JsonObject)?.get("profile")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull?.clean()
                        ?: (art as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.clean()
                }?.joinToString(", ").orEmpty()
                val album = trackObj["albumOfTrack"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull?.clean()
                    ?: (trackObj["album"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.clean()
                rows += ExternalTrackRow(
                    title = name,
                    artist = artists,
                    album = album,
                )
            }

            offset += items.size
        }

        ExternalPlaylistResult(
            source = ExternalPlaylistSource.SPOTIFY,
            title = "Spotify Liked Songs",
            author = "Spotify",
            rows = rows,
        )
    }

    /**
     * Fetches and matches the user's Spotify Liked Songs to playable YouTube Music tracks.
     */
    suspend fun fetchAndMatchLikedSongs(): ExternalImportResult = withContext(Dispatchers.IO) {
        val playlist = fetchLikedSongs()
        matchRowsToTracks(playlist.rows, playlist.title)
    }

    /**
     * Fetches the playlist and resolves every row to a playable YouTube Music
     * track, mirroring [CsvPlaylistImporter]'s bounded fan-out.
     */
    suspend fun fetchAndMatch(urlOrId: String): ExternalImportResult = withContext(Dispatchers.IO) {
        val playlist = fetchPlaylist(urlOrId)
        matchRowsToTracks(playlist.rows, playlist.title)
    }

    private suspend fun matchRowsToTracks(
        rows: List<ExternalTrackRow>,
        suggestedTitle: String,
    ): ExternalImportResult = coroutineScope {
        val limiter = Semaphore(4)
        val tracks = rows.map { row ->
            async {
                limiter.withPermit {
                    try {
                        innerTube.findBestMatchOrNull(row.title, row.artist, prefetchStreams = false)?.let { match ->
                            GeneratedTrack(
                                name = row.title,
                                artist = row.artist,
                                album = row.album ?: match.album,
                                artworkUrl = match.artworkUrl,
                                url = "https://music.youtube.com/watch?v=${match.videoId}",
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()

        ExternalImportResult(
            source = ExternalPlaylistSource.SPOTIFY,
            suggestedTitle = suggestedTitle,
            totalRows = rows.size,
            matchedCount = tracks.size,
            tracks = tracks,
        )
    }

    /** Depth-first walk over the parsed JSON tree, visiting every object once. */
    private fun walk(root: JsonElement, visit: (JsonObject) -> Unit) {
        val pending = mutableListOf(root)
        while (pending.isNotEmpty()) {
            when (val current = pending.removeAt(pending.size - 1)) {
                is JsonObject -> {
                    visit(current)
                    pending.addAll(current.values)
                }
                is JsonArray -> pending.addAll(current)
                else -> Unit
            }
        }
    }

    /** String value of [key], or null when absent, null or not a primitive. */
    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    /** Collapses non-breaking spaces (Spotify pads artist lists with them) and trims. */
    private fun String.clean(): String = replace('\u00a0', ' ').trim()

    /** The embed JSON is HTML-escaped inside the script tag; undo the common entities. */
    private fun unescapeHtml(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private fun emptyResult(fallbackTitle: String) = ExternalPlaylistResult(
        source = ExternalPlaylistSource.SPOTIFY,
        title = fallbackTitle,
        rows = emptyList(),
    )

    private companion object {
        const val PATHFINDER_ENDPOINT = "https://api-partner.spotify.com/pathfinder/v1/query"
        const val HASH_FETCH_PLAYLIST = "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf"
        const val HASH_LIBRARY_V3 = "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455"
        const val HASH_FETCH_LIBRARY_TRACKS = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"

        const val PATHFINDER_ATTEMPTS = 3
        const val USER_PLAYLIST_COUNT_CONCURRENCY = 6
        const val EMBED_BASE = "https://open.spotify.com/embed/playlist/"
        const val SPOTIFY_TRACK_URI = "spotify:track:"
        const val SPOTIFY_PLAYLIST_URI = "spotify:playlist:"
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val NEXT_DATA = Regex(
            """<script[^>]*\bid=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
