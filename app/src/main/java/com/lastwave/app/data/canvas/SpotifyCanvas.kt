package com.lastwave.app.data.canvas

import android.net.Uri
import android.util.Log
import com.lastwave.app.data.spotify.CanvasRequest
import com.lastwave.app.data.spotify.CanvasResponse
import com.lastwave.app.data.spotify.SpotifyAuthRepository
import com.lastwave.app.data.spotify.SpotifyPreferences
import com.lastwave.app.data.spotify.SpotifySearchResponse
import com.lastwave.app.data.spotify.SpotifyWebPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class SpotifyCanvas @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val authRepository: SpotifyAuthRepository,
    private val spotifyPreferences: SpotifyPreferences,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * The `canvas` operation's hash, read out of the web player.
     *
     * A hash that has been retired is not a hard failure: it is looked up again once, and
     * the last known good one is used in the meantime.
     */
    private suspend fun canvasHash(forceRefresh: Boolean = false): String {
        val script = SpotifyWebPlayer.script(okHttpClient, forceRefresh = forceRefresh)
        val hash = script?.let { SpotifyWebPlayer.queryHash(it, SpotifyWebPlayer.CANVAS_OPERATION) }
        if (hash != null) return hash
        Log.w(TAG, "Could not read the canvas operation's hash from the web player")
        return SpotifyCanvasQuery.KNOWN_CANVAS_HASH
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun search(
        title: String,
        artist: String,
        album: String? = null,
        durationMs: Long? = null,
    ): CanvasArtwork? = withContext(Dispatchers.IO) {
        if (!spotifyPreferences.isCanvasEnabled()) {
            Log.d(TAG, "Spotify canvas disabled in settings")
            return@withContext null
        }

        // Both tokens are required: the personal one identifies the signed-in
        // device, the client one the app. Each early exit used to be silent,
        // so "Spotify was never queried" looked exactly like "this track has
        // no canvas".
        val personalToken = authRepository.getPersonalToken() ?: run {
            Log.d(TAG, "No usable Spotify session for '$title' - '$artist' (sign in to Spotify again)")
            return@withContext null
        }
        val clientToken = authRepository.getClientToken() ?: run {
            Log.d(TAG, "No Spotify client token for '$title' - '$artist'")
            return@withContext null
        }

        try {
            // 1. Search for matching track on Spotify Pathfinder, falling back to Web API search
            val trackMatch = searchTrack(personalToken, clientToken, title, artist, durationMs)
            val trackId = trackMatch?.resolvedId ?: searchTrackFallback(personalToken, title, artist, durationMs)
                ?: run {
                    Log.d(TAG, "No Spotify track matched '$title' - '$artist'")
                    return@withContext null
                }
            val trackTitle = trackMatch?.name ?: title
            // Every credited artist, not only the first: the answer is checked against the
            // track that is playing, and a collaboration is only credited when all of its
            // artists are accounted for.
            val trackArtist = trackMatch?.artists?.items
                ?.mapNotNull { it.profile?.name }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
                ?: artist

            // 2. Fetch the canvas: the protobuf route first, the web player query second
            val canvasUrl = fetchCanvasUrl(personalToken, clientToken, trackId)
                ?: run {
                    Log.d(TAG, "Spotify track $trackId ('$trackTitle') has no canvas")
                    return@withContext null
                }

            Log.d(TAG, "Spotify canvas found for '$title' - '$artist' (track $trackId)")
            CanvasArtwork(
                url = canvasUrl,
                tallUrl = canvasUrl,
                title = title,
                artist = artist,
                album = album,
                source = CanvasSource.SPOTIFY,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Spotify canvas lookup error for '$title' - '$artist': ${e.message}")
            null
        }
    }

    internal fun searchTrackFallback(
        personalToken: String,
        title: String,
        artist: String,
        durationMs: Long? = null,
    ): String? {
        val query = "$title $artist"
        val url = "https://api.spotify.com/v1/search?type=track&q=${Uri.encode(query)}&limit=5"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $personalToken")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Fallback Web API search failed: HTTP ${response.code}" + sessionHint(response.code))
                return@use null
            }
            val body = response.body?.string().orEmpty()
            val parsed = runCatching {
                json.decodeFromString<com.lastwave.app.data.spotify.SpotifyWebSearchResponse>(body)
            }.getOrNull()
            val items = parsed?.tracks?.items.orEmpty()
            if (items.isEmpty()) return@use null

            val bestMatch = if (durationMs != null && durationMs > 0) {
                items.minByOrNull { item ->
                    val itemDuration = item.duration_ms ?: 0L
                    if (itemDuration > 0) abs(itemDuration - durationMs) else Long.MAX_VALUE
                } ?: items.firstOrNull()
            } else {
                items.firstOrNull { item ->
                    val itemName = item.name.orEmpty()
                    itemName.equals(title, ignoreCase = true) ||
                        itemName.normalizeForMatch() == title.normalizeForMatch()
                } ?: items.firstOrNull()
            }
            bestMatch?.id
        }
    }

    internal suspend fun searchTrack(
        personalToken: String,
        clientToken: String,
        title: String,
        artist: String,
        durationMs: Long? = null,
    ): SpotifySearchResponse.Data.Search.TracksV2.Items.Item.TrackData? {
        val cleanTitle = title
            .replace(CLEAN_TITLE_REGEX, "")
            .trim()
        val primaryArtist = splitArtists(artist).firstOrNull() ?: artist
        val candidateQueries = listOfNotNull(
            "$cleanTitle $primaryArtist".trim().takeIf { it.isNotBlank() },
            "$title $artist".takeIf { it != "$cleanTitle $primaryArtist" },
        )

        // 1. Primary: searchTracks Pathfinder query (SimpMusic method, returns full track details & duration)
        for (query in candidateQueries) {
            val variables = """{"searchTerm":"$query","offset":0,"limit":5,"numberOfTopResults":5,"includeAudiobooks":false,"includePreReleases":false}"""
            val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$HASH_SEARCH_TRACKS"}}"""
            val url = "$PATHFINDER_ENDPOINT?operationName=searchTracks" +
                "&variables=${Uri.encode(variables)}&extensions=${Uri.encode(extensions)}"

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $personalToken")
                .header("Client-Token", clientToken)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            val items = runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(
                            TAG,
                            "searchTracks query failed: HTTP ${response.code}" + sessionHint(response.code),
                        )
                        emptyList<SpotifySearchResponse.Data.Search.TracksV2.Items.Item.TrackData>()
                    } else {
                        val body = response.body?.string().orEmpty()
                        val parsed = runCatching { json.decodeFromString<SpotifySearchResponse>(body) }.getOrNull()
                        parsed?.data?.searchV2?.tracksV2?.items.orEmpty().mapNotNull { it.item?.data }
                    }
                }
            }.getOrElse {
                Log.w(TAG, "searchTracks call error: ${it.message}")
                emptyList()
            }

            if (items.isNotEmpty()) {
                val match = pickBestTrack(items, title, durationMs)
                if (match != null) return match
            }
        }

        // 2. Fallback: assistedCurationSearch via WebPlayer
        val hash = searchHash()
        for (query in candidateQueries) {
            val variables = """{"term":"$query","offset":0,"limit":3,"numberOfTopResults":3}"""
            val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$hash"}}"""

            for (endpoint in SEARCH_ENDPOINTS) {
                val url = "$endpoint?operationName=${SpotifyWebPlayer.SEARCH_OPERATION}" +
                    "&variables=${Uri.encode(variables)}&extensions=${Uri.encode(extensions)}"

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $personalToken")
                    .header("Client-Token", clientToken)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()

                val items = okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(
                            TAG,
                            "Track search on $endpoint failed: HTTP ${response.code}" +
                                sessionHint(response.code),
                        )
                        return@use emptyList<SpotifySearchResponse.Data.Search.TracksV2.Items.Item.TrackData>()
                    }
                    val body = response.body?.string().orEmpty()
                    val parsed = runCatching { json.decodeFromString<SpotifySearchResponse>(body) }.getOrNull()
                    parsed?.data?.searchV2?.tracksV2?.items.orEmpty().mapNotNull { it.item?.data }
                }

                if (items.isNotEmpty()) {
                    val match = pickBestTrack(items, title, durationMs)
                    if (match != null) return match
                }
            }
        }
        Log.d(TAG, "The track search answered with no tracks for '$title' - '$artist'")
        return null
    }

    /**
     * The track that the search returned which is most likely the one being played.
     *
     * A running time settles it when both sides know one: the same recording is the same
     * length, and a studio take and a live one of the same song are not.
     */
    private fun pickBestTrack(
        items: List<SpotifySearchResponse.Data.Search.TracksV2.Items.Item.TrackData>,
        title: String,
        durationMs: Long?,
    ): SpotifySearchResponse.Data.Search.TracksV2.Items.Item.TrackData? {
        if (durationMs != null && durationMs > 0) {
            return items.minByOrNull { item ->
                val itemDuration = item.duration?.totalMilliseconds?.toLong() ?: 0L
                if (itemDuration > 0) abs(itemDuration - durationMs) else Long.MAX_VALUE
            } ?: items.firstOrNull()
        }
        return items.firstOrNull { item ->
            val itemName = item.name.orEmpty()
            itemName.equals(title, ignoreCase = true) ||
                itemName.normalizeForMatch() == title.normalizeForMatch()
        } ?: items.firstOrNull()
    }

    /**
     * The hash of the search operation, read out of the web player.
     *
     * The last known hash is used when the player cannot be read. That answer is
     * refused by Spotify, so the failure is said out loud rather than hidden.
     */
    private suspend fun searchHash(): String {
        val script = SpotifyWebPlayer.script(okHttpClient)
        val hash = script?.let { SpotifyWebPlayer.queryHash(it, SpotifyWebPlayer.SEARCH_OPERATION) }
        if (hash != null) return hash
        Log.w(TAG, "Could not read the search operation's hash from the web player")
        return FALLBACK_SEARCH_HASH
    }

    private suspend fun fetchCanvasUrl(
        personalToken: String,
        clientToken: String,
        trackId: String,
    ): String? {
        val trackUri = "spotify:track:$trackId"
        // Try SimpMusic's proven protobuf route first
        fetchCanvasViaProtobuf(personalToken, clientToken, trackUri)?.let { return it }
        // Fallback to web player GraphQL query
        return fetchCanvasViaQuery(personalToken, clientToken, trackUri)
    }

    /**
     * The canvas as the web player asks for it.
     *
     * A retired hash is looked up again once before the query is given up on: the web player
     * is rebuilt every so often and every rebuild renames the operation.
     */
    private suspend fun fetchCanvasViaQuery(
        personalToken: String,
        clientToken: String,
        trackUri: String,
        isRetry: Boolean = false,
    ): String? {
        val hash = canvasHash(forceRefresh = isRetry)
        val request = Request.Builder()
            .url(SpotifyCanvasQuery.ENDPOINT)
            .header("Authorization", "Bearer $personalToken")
            .header("Client-Token", clientToken)
            .header("App-platform", "WebPlayer")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(
                SpotifyCanvasQuery.requestBody(trackUri, hash)
                    .toByteArray(Charsets.UTF_8)
                    .toRequestBody("application/json".toMediaType()),
            )
            .build()

        val body = runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else {
                    Log.w(TAG, "Canvas query answered HTTP ${response.code}" + sessionHint(response.code))
                    null
                }
            }
        }.getOrNull()

        if (body == null) {
            Log.w(TAG, "Canvas query did not answer for $trackUri")
            return null
        }

        return when (val answer = SpotifyCanvasQuery.parse(body)) {
            is SpotifyCanvasQuery.Answer.Found -> {
                Log.d(TAG, "Canvas query returned ${answer.type ?: "no type"} for $trackUri")
                answer.url
            }
            is SpotifyCanvasQuery.Answer.NoCanvas -> {
                Log.d(TAG, "No playable canvas for $trackUri (${answer.detail})")
                null
            }
            is SpotifyCanvasQuery.Answer.Failed -> {
                Log.w(TAG, "Canvas query failed for $trackUri: ${answer.reason}")
                if (answer.staleHash && !isRetry) {
                    fetchCanvasViaQuery(personalToken, clientToken, trackUri, isRetry = true)
                } else {
                    null
                }
            }
        }
    }

    /**
     * The canvas from the protobuf endpoint (SimpMusic method).
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun fetchCanvasViaProtobuf(
        personalToken: String,
        clientToken: String,
        trackUri: String,
    ): String? {
        val protoReq = CanvasRequest(
            tracks = listOf(
                CanvasRequest.TrackRequest(trackUri = trackUri),
            ),
        )
        val protoBytes = ProtoBuf.encodeToByteArray(protoReq)
        val reqBody = protoBytes.toRequestBody("application/protobuf".toMediaType())

        val request = Request.Builder()
            .url(CANVAS_ENDPOINT)
            .header("Authorization", "Bearer $personalToken")
            .header("Client-Token", clientToken)
            .header("Accept", "application/protobuf")
            .header("Content-Type", "application/protobuf")
            .header("User-Agent", CANVAS_UA)
            .post(reqBody)
            .build()

        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.d(TAG, "Canvaz fetch failed for track $trackUri: HTTP ${response.code}" + sessionHint(response.code))
                return@use null
            }
            val respBytes = response.body?.bytes() ?: return@use null
            val canvasResp = runCatching {
                ProtoBuf.decodeFromByteArray<CanvasResponse>(respBytes)
            }.getOrNull()
            val canvases = canvasResp?.canvases?.filter { !it.canvasUrl.isNullOrBlank() }.orEmpty()
            val exact = canvases.firstOrNull { it.trackUri == trackUri }
            val parsedUrl = (exact ?: canvases.firstOrNull())?.canvasUrl
            if (!parsedUrl.isNullOrBlank()) {
                return@use parsedUrl
            }

            // Fallback: direct binary/text regex scan for mp4 canvas URL if protobuf decoding missed a tag
            val directMatch = Regex("""https://[^\x00-\x20"']+\.mp4[^\x00-\x20"']*""")
                .find(String(respBytes, Charsets.ISO_8859_1))
                ?.value
            directMatch
        }
    }

    /**
     * What a rejected status code means.
     *
     * A refused session and a track that has no canvas are the same thing from the outside:
     * both come back with nothing. Saying which one it was is the difference between a
     * feature that is switched off and a feature that is broken.
     */
    private fun sessionHint(code: Int): String =
        if (code == 401 || code == 403) {
            " — the session was refused, it has to be signed in again"
        } else {
            ""
        }

    private companion object {
        private const val TAG = "SpotifyCanvas"

        /** SimpMusic proven searchTracks operation hash */
        private const val HASH_SEARCH_TRACKS = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428"

        /**
         * The search operation's hash as of the last time it was read off the web player.
         *
         * Only used when the player cannot be read; the answer it produces is refused,
         * which is why it is logged when it is reached.
         */
        private const val FALLBACK_SEARCH_HASH =
            "f78953bf9207d73493c27284103f5aeb6e728876d5793851bf79bc706127ff70"

        /** Pathfinder query endpoint. */
        private const val PATHFINDER_ENDPOINT = "https://api-partner.spotify.com/pathfinder/v1/query"
        private val SEARCH_ENDPOINTS = listOf(
            PATHFINDER_ENDPOINT,
        )

        private const val CANVAS_ENDPOINT = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val CANVAS_UA = "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)"

        private val CLEAN_TITLE_REGEX = Regex(
            """\s*[\(\[](?:feat\.?|ft\.?|official|video|audio|remastered|lyric|lyrics|hd|4k)[^\)\]]*[\)\]]""",
            RegexOption.IGNORE_CASE,
        )
    }
}
