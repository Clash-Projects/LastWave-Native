package com.lastwave.app.data.canvas

import android.net.Uri
import android.util.Log
import com.lastwave.app.data.spotify.CanvasRequest
import com.lastwave.app.data.spotify.CanvasResponse
import com.lastwave.app.data.spotify.SpotifyAuthRepository
import com.lastwave.app.data.spotify.SpotifyPreferences
import com.lastwave.app.data.spotify.SpotifySearchResponse
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
            Log.d(TAG, "No Spotify personal token for '$title' - '$artist' (connect Spotify first)")
            return@withContext null
        }
        val clientToken = authRepository.getClientToken() ?: run {
            Log.d(TAG, "No Spotify client token for '$title' - '$artist'")
            return@withContext null
        }

        try {
            // 1. Search for matching track on Spotify Pathfinder
            val track = searchTrack(personalToken, clientToken, title, artist, durationMs)
                ?: run {
                    Log.d(TAG, "No Spotify track matched '$title' - '$artist'")
                    return@withContext null
                }
            val trackId = track.id ?: run {
                Log.d(TAG, "Spotify match for '$title' has no track id")
                return@withContext null
            }

            // 2. Fetch Canvas from canvaz-cache Protobuf endpoint
            val canvasUrl = fetchCanvasUrl(personalToken, clientToken, trackId)
                ?: run {
                    Log.d(TAG, "Spotify track $trackId ('${track.name}') has no canvas")
                    return@withContext null
                }

            Log.d(TAG, "Spotify canvas found for '$title' - '$artist' (track $trackId)")
            CanvasArtwork(
                url = canvasUrl,
                tallUrl = canvasUrl,
                title = track.name ?: title,
                artist = track.artists?.items?.firstOrNull()?.profile?.name ?: artist,
                album = album,
                source = CanvasSource.SPOTIFY,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Spotify canvas lookup error for '$title' - '$artist': ${e.message}")
            null
        }
    }

    internal fun searchTrack(
        personalToken: String,
        clientToken: String,
        title: String,
        artist: String,
        durationMs: Long? = null,
    ): SpotifySearchResponse.Data.Search.TracksV2.Items.Item.TrackData? {
        val query = "$title $artist"
        val variables = """{"searchTerm":"$query","offset":0,"limit":3,"numberOfTopResults":3}"""
        val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$PERSISTED_QUERY_HASH"}}"""

        val url = "$PATHFINDER_URL?operationName=searchTracks&variables=${Uri.encode(variables)}&extensions=${Uri.encode(extensions)}"

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $personalToken")
            .header("Client-Token", clientToken)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Pathfinder track search failed: HTTP ${response.code}")
                return@use null
            }
            val body = response.body?.string().orEmpty()
            val parsed = json.decodeFromString<SpotifySearchResponse>(body)
            val items = parsed.data?.searchV2?.tracksV2?.items.orEmpty()
                .mapNotNull { it.item?.data }

            if (items.isEmpty()) return@use null

            // Best match: check duration similarity if available, or closest title
            if (durationMs != null && durationMs > 0) {
                items.minByOrNull { item ->
                    val itemDuration = item.duration?.totalMilliseconds?.toLong() ?: 0L
                    if (itemDuration > 0) abs(itemDuration - durationMs) else Long.MAX_VALUE
                } ?: items.firstOrNull()
            } else {
                items.firstOrNull { item ->
                    val itemName = item.name.orEmpty()
                    itemName.equals(title, ignoreCase = true) ||
                        itemName.normalizeForMatch() == title.normalizeForMatch()
                } ?: items.firstOrNull()
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun fetchCanvasUrl(
        personalToken: String,
        clientToken: String,
        trackId: String,
    ): String? {
        val protoReq = CanvasRequest(
            tracks = listOf(
                CanvasRequest.TrackRequest(trackUri = "spotify:track:$trackId"),
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
                Log.d(TAG, "Canvaz fetch failed for track $trackId: HTTP ${response.code}")
                return@use null
            }
            val respBytes = response.body?.bytes() ?: return@use null
            val canvasResp = ProtoBuf.decodeFromByteArray<CanvasResponse>(respBytes)
            val canvas = canvasResp.canvases.firstOrNull { !it.canvasUrl.isNullOrBlank() }
            canvas?.canvasUrl
        }
    }

    private companion object {
        private const val TAG = "SpotifyCanvas"
        private const val PERSISTED_QUERY_HASH = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428"
        private const val PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
        private const val CANVAS_ENDPOINT = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val CANVAS_UA = "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)"
    }
}
