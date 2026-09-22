package com.lastwave.app.data.music

import android.net.Uri
import android.os.SystemClock
import com.lastwave.app.data.local.AppLanguage
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.local.appLocale
import java.util.Locale
import com.lastwave.app.data.music.potoken.BotGuardTokenGenerator
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import com.lastwave.app.data.ytmusic.YtConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

data class YouTubeMusicTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationSeconds: Int? = null,
)

enum class YouTubeMusicEntityKind { ARTIST, ALBUM }

data class YouTubeMusicEntity(
    val kind: YouTubeMusicEntityKind,
    val name: String,
    val artist: String? = null,
    val subtitle: String? = null,
    val browseId: String,
    val playlistId: String? = null,
    val artworkUrl: String? = null,
)

data class YouTubeAudioStream(
    val videoId: String,
    val url: String,
    val itag: Int?,
    val mimeType: String?,
    val codec: String?,
    val bitrate: Int,
    val sampleRateHz: Int?,
    val durationMs: Long?,
    val contentLength: Long?,
    val isAdaptive: Boolean,
    val clientProfile: String,
    val authScope: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val expiresAtEpochMs: Long? = null,
) {
    val mediaCacheKey: String
        get() = "youtube:$videoId:$clientProfile:${itag ?: -1}:$authScope:${expiresAtEpochMs ?: 0L}"
}

data class YtMusicTasteSignals(
    val recentTracks: List<YouTubeMusicTrack> = emptyList(),
    val likedTracks: List<YouTubeMusicTrack> = emptyList(),
    val feedTracks: List<YouTubeMusicTrack> = emptyList(),
)

/** A provider explicitly identified the media as unavailable, rather than a
 * request merely failing because the network or extractor was slow. */
class ConfirmedUnplayableMediaException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

data class YouTubePlaylistResult(
    val id: String,
    val title: String,
    val author: String? = null,
    val artworkUrl: String? = null,
    val trackCount: Int = 0,
    val tracks: List<YouTubeMusicTrack> = emptyList(),
    /** False when continuation pages failed mid-load and [tracks] is only a
     *  prefix. Callers showing this must offer retry instead of caching it
     *  as the full playlist. */
    val isComplete: Boolean = true,
)

data class YouTubePlaylistSummary(
    val id: String,
    val title: String,
    val author: String? = null,
    val trackCountText: String? = null,
    val artworkUrl: String? = null,
)

data class YtAccountInfo(
    val accountName: String,
    val channelHandle: String? = null,
    val photoUrl: String? = null,
)

/**
 * One switchable YouTube channel within the signed-in session. Selection is
 * applied per-request, never via cookies: [pageId] (brand-channel delegation
 * token from `account/get_account_switcher`'s `pageIdToken`, the same
 * `pageid=` music.youtube.com sends when the user picks a channel) goes out
 * as the `X-Goog-PageId` header (+`X-Goog-AuthUser: 0`); [channelId]
 * (legacy `onBehalfOfUser`/UC token from `account_menu`) is sent as the
 * InnerTube context `user.onBehalfOfUser`; [authUserIndex] (multi-login
 * session index) goes out as the `X-Goog-AuthUser` header. Blank [pageId]
 * + null [channelId] = YouTube's default (first) channel.
 */
data class YtChannelOption(
    val channelId: String? = null,
    val authUserIndex: Int? = null,
    val accountName: String,
    val channelHandle: String? = null,
    val photoUrl: String? = null,
    val isActive: Boolean = false,
    val pageId: String = "",
)

/** One item of an OWNED playlist, carrying its `setVideoId` — the unique
 *  per-entry token required by ACTION_REMOVE_VIDEO edits. */
data class YtOwnedPlaylistItem(
    val videoId: String,
    val setVideoId: String? = null,
)

data class YtOwnedPlaylist(
    val id: String,
    val title: String,
    val items: List<YtOwnedPlaylistItem> = emptyList(),
)

/**
 * Client for the same private InnerTube endpoints used by the YouTube Music
 * web/mobile clients. Search uses WEB_REMIX; playback delegates first to
 * InnerTubeX and keeps the older direct/NewPipe extractors as bounded fallbacks.
 *
 * Anonymous by default — but when a YouTube Music account is connected via
 * [YtMusicAuthManager], requests can opt in to the account's cookies +
 * SAPISIDHASH Authorization header, unlocking library browsing, playlist
 * creation and playlist edits (the same surfaces music.youtube.com uses).
 *
 * InnerTube is not a public/stable Google API. The web client key/version
 * are therefore bootstrapped from music.youtube.com and cached instead of
 * permanently tying search to a stale build identifier.
 */
@Singleton
class InnerTubeMusicApi @Inject constructor(
    private val http: OkHttpClient,
    private val streamExtractor: YouTubeStreamExtractor,
    private val innerTubeXExtractor: InnerTubeXStreamExtractor,
    private val ytAuth: YtMusicAuthManager,
    private val settingsPreferences: SettingsPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val configMutex = Mutex()
    private val matchCache = ConcurrentHashMap<String, YouTubeMusicTrack>()
    private val streamCache = ConcurrentHashMap<StreamCacheKey, CachedStream>()
    private val activeStreamRequests = ConcurrentHashMap<String, SharedStreamRequest>()
    private val apiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val failedClientsUntil = ConcurrentHashMap<String, Long>()
    private val lastResolvedStreams = ConcurrentHashMap<String, YouTubeAudioStream>()
    @Volatile private var lastSuccessfulClientName: String? = null
    @Volatile private var webConfig: WebConfig? = null

    fun invalidateCache(videoId: String) {
        streamCache.keys.removeIf { it.videoId == videoId }
        lastResolvedStreams.entries.removeIf { it.value.videoId == videoId }
        activeStreamRequests.entries.removeIf { (key, request) ->
            if (!key.startsWith("$videoId|")) return@removeIf false
            request.deferred.cancel()
            true
        }
        matchCache.values.removeIf { it.videoId == videoId }
        streamExtractor.invalidateCache(videoId)
    }

    /** Marks the exact extractor/client that produced a rejected URL, then
     * clears its state so the next attempt cannot pick the same stale result. */
    fun reportPlaybackFailure(videoId: String, rejected: YouTubeAudioStream? = null) {
        val stream = rejected ?: lastResolvedStreams[resolutionKey(videoId, playbackAuthScope())]
        if (stream != null && stream.clientProfile != NEWPIPE_SOURCE) {
            failedClientsUntil[clientFailureKey(videoId, stream.clientProfile, stream.authScope)] =
                System.currentTimeMillis() + CLIENT_COOLDOWN_MS
            if (stream.clientProfile.startsWith("INNERTUBEX:")) {
                innerTubeXExtractor.reportPlaybackFailure(videoId, stream.authScope, stream.clientProfile)
            }
        }
        streamCache.keys.removeIf { key ->
            key.videoId == videoId && (stream == null || key.matches(stream))
        }
        lastResolvedStreams.entries.removeIf { it.value.videoId == videoId }
        activeStreamRequests.entries.removeIf { (key, request) ->
            if (!key.startsWith("$videoId|")) return@removeIf false
            request.deferred.cancel()
            true
        }
        if (stream == null || stream.clientProfile == NEWPIPE_SOURCE) {
            streamExtractor.invalidatePlayerState(videoId)
        } else {
            streamExtractor.invalidateCache(videoId)
        }
    }

    /** Proactively resolves and seeds the in-memory stream cache in the background */
    fun prefetchStream(videoId: String) {
        if (videoId.isBlank()) return
        apiScope.launch {
            try {
                resolveAudioStream(videoId)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Prefetch is opportunistic; foreground playback resolves again.
            }
        }
    }

    fun extractPlaylistId(input: String): String {
        val clean = input.trim()
        if (clean.contains("list=")) {
            return clean.substringAfter("list=").substringBefore('&').substringBefore('#')
        }
        if (clean.contains("playlist/")) {
            return clean.substringAfter("playlist/").substringBefore('?').substringBefore('/')
        }
        return clean
    }

    /**
     * Loads and parses any YouTube Music or standard YouTube playlist by ID or
     * URL — following continuation pages until the playlist is exhausted, so
     * playlists of ANY length import fully (a single browse response only
     * returns ~100 items, which used to silently truncate imports).
     *
     * When an account is connected, the first attempt is authenticated so
     * owned/private playlists resolve too; it transparently falls back to
     * anonymous for public ones.
     * [maxTracks] keeps preview/radio surfaces bounded; imports leave it null
     * and retain the exhaustive continuation behavior above.
     */
    suspend fun fetchPlaylist(
        playlistIdOrUrl: String,
        maxTracks: Int? = null,
        progressive: Boolean = false,
        onPageLoaded: ((List<YouTubeMusicTrack>) -> Unit)? = null,
    ): YouTubePlaylistResult? = withContext(Dispatchers.IO) {
        val rawId = extractPlaylistId(playlistIdOrUrl)
        if (rawId.isBlank()) return@withContext null
        val browseId = when {
            rawId.startsWith("VL") || rawId.startsWith("RDCLAK") || rawId.startsWith("FE") || rawId.startsWith("MPRE") || rawId.startsWith("UC") -> rawId
            else -> "VL$rawId"
        }

        val rootResult = runCatching { fetchPlaylistRoot(browseId) }
        val (root, authenticatedAs) = rootResult.getOrNull() ?: run {
            val cause = rootResult.exceptionOrNull()?.javaClass?.simpleName
                ?: "null-response"
            android.util.Log.w(
                PLAYLIST_LOG_TAG,
                "playlist-root-failed browseId=$browseId error=$cause",
            )
            return@withContext null
        }
        val header = playlistHeader(root)

        val title = extractTitleFromHeader(header, root)

        val author = header?.obj("subtitle")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
            ?: header?.obj("straplineTextOne")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
            ?: findFirstAuthor(header)

        val artworkUrl = extractArtworkFromHeader(header, root)

        val trackLimit = maxTracks?.coerceAtLeast(1)
        val songs = mutableListOf<YouTubeMusicTrack>()
        val playlistPage = browseId.startsWith("VL")
        fun trackContainers(page: JsonElement): List<JsonElement> =
            if (playlistPage) playlistTrackContainers(page) else listOf(page)
        val initialContainers = trackContainers(root)
        val initialSongs = if (initialContainers.isNotEmpty()) {
            initialContainers.flatMap(::parseSongRenderers)
        } else {
            parseSongRenderers(root)
        }.distinctBy { it.videoId }.let { parsed ->
            trackLimit?.let { parsed.take(it) } ?: parsed
        }
        if (initialSongs.isEmpty() && initialContainers.isEmpty()) {
            val topKeys = (root as? JsonObject)?.keys?.take(8)?.joinToString(",").orEmpty()
            android.util.Log.w(
                PLAYLIST_LOG_TAG,
                "playlist-empty-containers browseId=$browseId keys=$topKeys",
            )
            return@withContext null
        }
        val containers = if (initialContainers.isNotEmpty()) initialContainers else listOf(root)
        songs += initialSongs

        // Follow continuation pages until gone. Safety cap is enormous on
        // purpose (60k tracks) — it only exists to bound a pathological loop.
        fun continuation(containers: List<JsonElement>, pageJson: JsonElement): String? =
            containers.firstNotNullOfOrNull {
                if (playlistPage) playlistTrackContinuationToken(it) else genericContinuationToken(it)
            } ?: genericContinuationToken(pageJson)
        var token = continuation(containers, root)
        if ((trackLimit != null || progressive) && songs.isNotEmpty()) {
            onPageLoaded?.invoke(songs.toList())
        }
        val seenTokens = mutableSetOf<String>()
        var page = 0
        var truncated = false
        while (
            !token.isNullOrBlank() &&
            page < MAX_CONTINUATION_PAGES &&
            (trackLimit == null || songs.size < trackLimit)
        ) {
            val currentToken = token ?: break
            if (!seenTokens.add(currentToken)) {
                truncated = true
                break
            }
            val nextPage = runCatching {
                browseContinuation(currentToken, authenticated = authenticatedAs)
            }.getOrNull()
            if (nextPage == null) {
                // Transient page failure (rate-limit/offline): keep the tracks
                // already collected instead of failing the whole playlist —
                // callers surface isComplete=false with a retry affordance.
                android.util.Log.w(
                    PLAYLIST_LOG_TAG,
                    "playlist-continuation-failed browseId=$browseId page=$page collected=${songs.size}",
                )
                truncated = true
                break
            }
            val pageContainers = trackContainers(nextPage)
            val pageSongs = if (pageContainers.isNotEmpty()) {
                pageContainers.flatMap(::parseSongRenderers)
            } else {
                parseSongRenderers(nextPage)
            }
            if (pageSongs.isEmpty()) {
                android.util.Log.w(
                    PLAYLIST_LOG_TAG,
                    "playlist-continuation-empty browseId=$browseId page=$page collected=${songs.size}",
                )
                truncated = true
                break
            }
            val knownVideoIds = songs.mapTo(mutableSetOf()) { it.videoId }
            val newSongs = pageSongs
                .filter { knownVideoIds.add(it.videoId) }
                .let { parsed -> trackLimit?.let { parsed.take(it - songs.size) } ?: parsed }
            songs += newSongs
            if (trackLimit != null || progressive) onPageLoaded?.invoke(songs.toList())
            token = continuation(pageContainers, nextPage)
            page++
        }
        if (!token.isNullOrBlank() && (trackLimit == null || songs.size < trackLimit)) truncated = true
        if (trackLimit == null && songs.isNotEmpty()) onPageLoaded?.invoke(songs.toList())
        if (truncated) {
            android.util.Log.w(
                PLAYLIST_LOG_TAG,
                "playlist-truncated browseId=$browseId collected=${songs.size} pages=$page",
            )
        }

        songs.take(3).forEach { prefetchStream(it.videoId) }
        // Zero tracks means nothing usable loaded (root error page, private /
        // deleted playlist, or blocked request) — keep the null contract so
        // callers fall back to cached data / error UI instead of an empty list.
        if (songs.isEmpty()) {
            android.util.Log.w(PLAYLIST_LOG_TAG, "playlist-no-tracks browseId=$browseId")
            return@withContext null
        }
        YouTubePlaylistResult(
            id = rawId,
            title = title ?: "",
            author = author,
            artworkUrl = artworkUrl,
            trackCount = songs.size,
            tracks = songs,
            isComplete = !truncated,
        )
    }

    suspend fun fetchPlaylistArtwork(playlistIdOrUrl: String): String? = withContext(Dispatchers.IO) {
        val rawId = extractPlaylistId(playlistIdOrUrl)
        if (rawId.isBlank()) return@withContext null
        val browseId = when {
            rawId.startsWith("VL") || rawId.startsWith("RDCLAK") || rawId.startsWith("FE") || rawId.startsWith("MPRE") || rawId.startsWith("UC") -> rawId
            else -> "VL$rawId"
        }
        val root = fetchPlaylistRoot(browseId)?.first ?: return@withContext null
        extractArtworkFromHeader(playlistHeader(root), root)
            ?: parseSongRenderers(root).firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) }
    }

    private suspend fun fetchPlaylistRoot(browseId: String): Pair<JsonElement, Boolean>? {
        if (ytAuth.connection.value.isConnected) {
            runCatching { browseRoot(browseId, authenticated = true) }.getOrNull()?.let {
                return it to true
            }
        }
        return runCatching { browseRoot(browseId, authenticated = false) }.getOrNull()?.let { it to false }
    }

    private fun playlistHeader(root: JsonElement): JsonObject? {
        val rootObj = root as? JsonObject ?: return findFirstHeaderRenderer(root)
        val header = rootObj.obj("header")
        return header?.obj("musicDetailHeaderRenderer")
            ?: header?.obj("musicResponsiveHeaderRenderer")
            ?: header?.obj("musicEditablePlaylistDetailHeaderRenderer")?.obj("header")?.obj("musicResponsiveHeaderRenderer")
            ?: header?.obj("musicEditablePlaylistDetailHeaderRenderer")?.obj("header")?.obj("musicDetailHeaderRenderer")
            ?: header?.obj("musicEditablePlaylistDetailHeaderRenderer")
            ?: header?.obj("musicVisualHeaderRenderer")
            ?: header?.obj("musicHeaderRenderer")
            ?: header?.obj("playlistHeaderRenderer")
            ?: findFirstHeaderRenderer(root)
    }

    private fun findFirstHeaderRenderer(root: JsonElement): JsonObject? {
        val renderers = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveHeaderRenderer", renderers)
        collectObjects(root, "musicDetailHeaderRenderer", renderers)
        collectObjects(root, "musicEditablePlaylistDetailHeaderRenderer", renderers)
        collectObjects(root, "musicVisualHeaderRenderer", renderers)
        collectObjects(root, "musicHeaderRenderer", renderers)
        return renderers.firstOrNull()
    }

    private fun extractTitleFromHeader(header: JsonObject?, root: JsonElement): String? {
        if (header != null) {
            val runsText = header.obj("title")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?.trim()?.takeIf { it.isNotBlank() }
            if (runsText != null) return runsText

            val nestedHeader = header.obj("header")?.obj("musicResponsiveHeaderRenderer")
                ?: header.obj("header")?.obj("musicDetailHeaderRenderer")
                ?: header.obj("header")
            if (nestedHeader != null) {
                val nestedRuns = nestedHeader.obj("title")?.array("runs")
                    ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                    ?.trim()?.takeIf { it.isNotBlank() }
                if (nestedRuns != null) return nestedRuns
            }

            val simpleTitle = header.obj("title")?.string("simpleText")
                ?: header.string("title")
            if (!simpleTitle.isNullOrBlank()) return simpleTitle.trim()
        }

        val titles = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveHeaderRenderer", titles)
        for (h in titles) {
            val t = h.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }?.trim()
            if (!t.isNullOrBlank()) return t
        }
        return null
    }

    private fun findFirstAuthor(header: JsonObject?): String? {
        if (header == null) return null
        return header.obj("subtitle")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
            ?: header.obj("straplineTextOne")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
            ?: header.obj("secondSubtitle")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
    }

    private fun extractArtworkFromHeader(header: JsonObject?, root: JsonElement): String? {
        if (header != null) {
            extractThumbnailsUrl(header)?.let { return it }
        }
        val thumbObjects = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveHeaderRenderer", thumbObjects)
        collectObjects(root, "musicDetailHeaderRenderer", thumbObjects)
        collectObjects(root, "musicEditablePlaylistDetailHeaderRenderer", thumbObjects)
        collectObjects(root, "musicVisualHeaderRenderer", thumbObjects)
        collectObjects(root, "musicThumbnailRenderer", thumbObjects)
        for (to in thumbObjects) {
            extractThumbnailsUrl(to)?.let { return it }
        }
        return null
    }

    /** The account's own library playlists (FEmusic_liked_playlists). */
    suspend fun fetchLibraryPlaylists(): List<YouTubePlaylistSummary> = withContext(Dispatchers.IO) {
        val config = getWebConfig()
        // Let the initial request failure propagate. Treating a network/auth
        // failure as a real empty library made valid playlists flash away.
        val root = post(
            url = "$MUSIC_API/browse?key=${config.apiKey}&prettyPrint=false",
            body = buildJsonObject {
                put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                put("browseId", LIBRARY_PLAYLISTS_BROWSE_ID)
            },
            clientName = "WEB_REMIX",
            clientVersion = config.clientVersion,
            userAgent = WEB_USER_AGENT,
            authenticated = true,
        )

        val summaries = parsePlaylistRenderers(root).toMutableList()
        var token = genericContinuationToken(root)
        var page = 0
        while (!token.isNullOrBlank() && page < 20) {
            val nextPage = runCatching {
                post(
                    url = "$MUSIC_API/browse?key=${config.apiKey}&prettyPrint=false",
                    body = buildJsonObject {
                        put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                        put("browseId", LIBRARY_PLAYLISTS_BROWSE_ID)
                        put("continuation", token)
                    },
                    clientName = "WEB_REMIX",
                    clientVersion = config.clientVersion,
                    userAgent = WEB_USER_AGENT,
                    authenticated = true,
                )
            }.getOrNull() ?: break
            summaries += parsePlaylistRenderers(nextPage)
            token = genericContinuationToken(nextPage)
            page++
        }
        summaries.distinctBy { it.id }.filter { it.id.isNotBlank() }
    }

    /** Read-only signals and playable Home-feed candidates from a connected
     * account. Each request is isolated so a missing surface cannot break the
     * remaining signals or the normal recommendation fallback. */
    suspend fun fetchTasteSignals(
        recentLimit: Int = 30,
        likedLimit: Int = 24,
        feedLimit: Int = 40,
    ): YtMusicTasteSignals = withContext(Dispatchers.IO) {
        if (!ytAuth.connection.value.isConnected) return@withContext YtMusicTasteSignals()
        kotlinx.coroutines.coroutineScope {
            val recent = async {
                runCatching { parseSongRenderers(browseRoot(YT_HISTORY_BROWSE_ID, authenticated = true)) }
                    .getOrDefault(emptyList())
                    .distinctBy { it.videoId }
                    .take(recentLimit.coerceIn(0, 50))
            }
            val liked = async {
                runCatching { parseSongRenderers(browseRoot(YT_LIKED_BROWSE_ID, authenticated = true)) }
                    .getOrDefault(emptyList())
                    .distinctBy { it.videoId }
                    .take(likedLimit.coerceIn(0, 50))
            }
            val feed = async {
                runCatching {
                    parseHomeFeedSongs(browseRoot(YT_HOME_BROWSE_ID, authenticated = true))
                }
                    .getOrDefault(emptyList())
                    .distinctBy { it.videoId }
                    .take(feedLimit.coerceIn(0, 60))
            }
            YtMusicTasteSignals(
                recentTracks = recent.await(),
                likedTracks = liked.await(),
                feedTracks = feed.await(),
            )
        }
    }

    suspend fun fetchNewReleases(): List<YouTubePlaylistSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val root = browseRoot(YT_NEW_RELEASES_BROWSE_ID, authenticated = ytAuth.connection.value.isConnected)
            parsePlaylistRenderers(root)
        }.getOrDefault(emptyList())
    }

    data class NewReleasesBrowseBatch(
        val directTracks: List<YouTubeMusicTrack>,
        val albums: List<YouTubePlaylistSummary>,
        val continuationToken: String?,
    )

    suspend fun fetchNewReleasesPage(continuationToken: String? = null): NewReleasesBrowseBatch = withContext(Dispatchers.IO) {
        val isAuth = ytAuth.connection.value.isConnected
        val root = if (!continuationToken.isNullOrBlank()) {
            browseContinuation(continuationToken, authenticated = isAuth)
        } else {
            browseRoot(YT_NEW_RELEASES_BROWSE_ID, authenticated = isAuth)
        }
        val directTracks = (parseSongRenderers(root) + parseHomeFeedSongs(root)).distinctBy { it.videoId }
        val albums = parsePlaylistRenderers(root)
        val nextToken = genericContinuationToken(root)
        NewReleasesBrowseBatch(directTracks, albums, nextToken)
    }


    suspend fun fetchNewReleasesAlbumsGrid(continuationToken: String? = null): Pair<List<YouTubePlaylistSummary>, String?> = withContext(Dispatchers.IO) {
        val isAuth = ytAuth.connection.value.isConnected
        val root = if (!continuationToken.isNullOrBlank()) {
            browseContinuation(continuationToken, authenticated = isAuth)
        } else {
            browseRoot("FEmusic_new_releases_albums", authenticated = isAuth)
        }
        val albums = parsePlaylistRenderers(root)
        val nextToken = genericContinuationToken(root)
        albums to nextToken
    }


    suspend fun fetchCharts(): List<YouTubeMusicTrack> = withContext(Dispatchers.IO) {
        runCatching {
            val root = browseRoot(YT_CHARTS_BROWSE_ID, authenticated = false)
            parseSongRenderers(root)
        }.getOrDefault(emptyList())
    }

    suspend fun fetchHomeMixes(): List<YouTubePlaylistSummary> = withContext(Dispatchers.IO) {
        val isAuth = ytAuth.connection.value.isConnected
        runCatching {
            val root = browseRoot(YT_HOME_BROWSE_ID, authenticated = isAuth)
            parsePlaylistRenderers(root)
        }.getOrDefault(emptyList())
    }

    suspend fun fetchHomeSongs(): List<YouTubeMusicTrack> = withContext(Dispatchers.IO) {
        val isAuth = ytAuth.connection.value.isConnected
        runCatching {
            val root = browseRoot(YT_HOME_BROWSE_ID, authenticated = isAuth)
            parseHomeFeedSongs(root)
        }.getOrDefault(emptyList())
    }

    /**
     * Returns the per-play `videostatsPlaybackUrl` tracking URL for [videoId]
     * from an AUTHENTICATED `player` response, or null when there is no
     * connected account or the response carries no tracking URL.
     *
     * This mirrors ytmusicapi's `get_song` + `add_history_item` pair: history
     * is registered by GET-ing this URL (see [submitHistoryPlayback]), never
     * by merely fetching the song, resolving its stream, or reading history.
     * The player call MUST be authenticated — per ytmusicapi issue #703 an
     * anonymous player response yields a tracking URL whose ping returns 204
     * yet never lands in history.
     */
    suspend fun fetchHistoryTrackingUrl(videoId: String, account: YtConnection): String? = withContext(Dispatchers.IO) {
        if (!account.isConnected || ytAuth.connection.value != account) return@withContext null
        val config = getWebConfig()
        val root = post(
            url = "$MUSIC_API/player?key=${config.apiKey}&prettyPrint=false",
            body = buildJsonObject {
                put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                put("videoId", videoId)
                put("playbackContext", buildJsonObject {
                    put("contentPlaybackContext", buildJsonObject {
                        // Same default signature timestamp used by ytmusicapi's get_song.
                        put("signatureTimestamp", System.currentTimeMillis() / 86_400_000L - 1L)
                    })
                })
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            },
            clientName = "WEB_REMIX",
            clientVersion = config.clientVersion,
            userAgent = WEB_USER_AGENT,
            authenticated = true,
            authenticatedAccount = account,
            visitorData = config.visitorData,
        )
        root.obj("playbackTracking")?.obj("videostatsPlaybackUrl")?.string("baseUrl")
    }

    /**
     * Registers one listen by GET-ing a [trackingBaseUrl] previously obtained
     * from [fetchHistoryTrackingUrl], mirroring ytmusicapi's
     * `add_history_item` (`ver=2`, `c=WEB_REMIX`, random 16-char `cpn`).
     *
     * @return the HTTP status code. 2xx means YouTube accepted the ping
     *   (204 in practice). Note the upstream caveat (ytmusicapi #703): 204
     *   can also be returned when nothing is recorded, which is why callers
     *   must only submit URLs from authenticated player responses.
     *
     * Privacy: the tracking URL and account cookies are authenticating
     * material — this function never logs them, only the resulting code.
     */
    suspend fun submitHistoryPlayback(trackingBaseUrl: String, cpn: String, account: YtConnection): Int =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            if (!account.isConnected || ytAuth.connection.value != account) {
                throw kotlinx.coroutines.CancellationException("YouTube account changed")
            }
            val base = trackingBaseUrl.toHttpUrlOrNull()
                ?: throw IOException("Invalid history tracking URL")
            require(base.isHttps && (base.host == "youtube.com" || base.host.endsWith(".youtube.com"))) {
                "Unexpected history tracking host"
            }
            val url = base.newBuilder()
                .setQueryParameter("ver", "2")
                .setQueryParameter("c", "WEB_REMIX")
                .setQueryParameter("cpn", cpn)
                .build()
            val builder = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", WEB_USER_AGENT)
                .header("Origin", YOUTUBE_MUSIC_ORIGIN)
                .header("X-Origin", YOUTUBE_MUSIC_ORIGIN)
                .header("Referer", "$YOUTUBE_MUSIC_ORIGIN/")
            // Same account surface as every other authenticated call: the
            // ping is attributed to whoever owns these cookies.
            ytAuth.cookieHeaderValue(account)?.let { builder.header("Cookie", it) }
            ytAuth.authorizationHeaderValue(account = account)?.let { builder.header("Authorization", it) }
            val historyPageId = account.pageId.takeIf { it.isNotBlank() }
            if (historyPageId != null) {
                builder.header("X-Goog-PageId", historyPageId)
                builder.header("X-Goog-AuthUser", (account.authUserIndex ?: 0).toString())
            } else {
                account.authUserIndex?.let { builder.header("X-Goog-AuthUser", it.toString()) }
            }
            webConfig?.visitorData?.let { builder.header("X-Goog-Visitor-Id", it) }
            val call = http.newCall(builder.build())
            call.timeout().timeout(HISTORY_PING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        response.use {
                            if (continuation.isActive) continuation.resume(it.code)
                        }
                    }
                })
            }
        }

    /** Identity of the signed-in account (account_menu endpoint). */
    suspend fun fetchAccountInfo(): YtAccountInfo? = withContext(Dispatchers.IO) {
        if (!ytAuth.connection.value.isConnected) return@withContext null
        val config = getWebConfig()
        val root = runCatching {
            post(
                url = "$MUSIC_API/account/account_menu?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
                authenticated = true,
            )
        }.getOrNull() ?: return@withContext null

        val headers = mutableListOf<JsonObject>()
        collectObjects(root, "activeAccountHeaderRenderer", headers)
        val header = headers.firstOrNull() ?: return@withContext null
        val accountName = header.obj("accountName")?.array("runs")?.firstOrNull()
            ?.asObject()?.string("text")?.trim().orEmpty()
        if (accountName.isBlank()) return@withContext null
        YtAccountInfo(
            accountName = accountName,
            channelHandle = header.obj("channelHandle")?.array("runs")?.firstOrNull()
                ?.asObject()?.string("text"),
            photoUrl = header.obj("accountPhoto")?.obj("thumbnails")?.array("thumbnails")
                ?.lastOrNull()?.asObject()?.string("url"),
        )
    }

    /**
     * Every channel/profile switchable inside the current session, active
     * entry first. Primary source is `GET /getAccountSwitcherEndpoint` — the
     * same endpoint music.youtube.com fires on the avatar → switch-account
     * button — whose `accountItem` entries carry the brand-channel delegation
     * token (`pageIdToken.pageId`, surfaced in the web client's
     * `signin?...&pageid=` switch request) and/or a multi-login
     * `authuser=N` index. `account_menu` (`accountItemRenderer` /
     * `onBehalfOfUser`) is kept as a fallback. Cookies stay identical across
     * channels, which is why cookie-only clients are stuck on the first one.
     */
    suspend fun fetchAvailableChannels(): List<YtChannelOption> = withContext(Dispatchers.IO) {
        if (!ytAuth.connection.value.isConnected) return@withContext emptyList()
        val config = getWebConfig()
        val switcherRoot = runCatching { fetchAccountSwitcherRoot(config) }.getOrNull()
        val switcherOptions = switcherRoot?.let(::parseAccountSwitcherChannels).orEmpty()
        if (switcherOptions.isNotEmpty()) return@withContext markActiveChannel(switcherOptions)

        val root = runCatching {
            post(
                url = "$MUSIC_API/account/account_menu?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
                authenticated = true,
            )
        }.getOrNull() ?: return@withContext emptyList()

        val options = mutableListOf<YtChannelOption>()
        // Active session channel — the baseline "default" choice.
        val activeHeaders = mutableListOf<JsonObject>()
        collectObjects(root, "activeAccountHeaderRenderer", activeHeaders)
        activeHeaders.firstOrNull()?.let { header ->
            val name = header.obj("accountName")?.array("runs")?.firstOrNull()
                ?.asObject()?.string("text")?.trim().orEmpty()
            if (name.isNotBlank()) {
                options += YtChannelOption(
                    accountName = name,
                    channelHandle = header.obj("channelHandle")?.array("runs")?.firstOrNull()
                        ?.asObject()?.string("text"),
                    photoUrl = header.obj("accountPhoto")?.obj("thumbnails")?.array("thumbnails")
                        ?.lastOrNull()?.asObject()?.string("url"),
                    isActive = true,
                )
            }
        }

        val items = mutableListOf<JsonObject>()
        collectObjects(root, "accountItemRenderer", items)
        for (item in items) {
            val name = item.obj("accountName")?.string("simpleText")?.trim()
                ?: item.obj("accountName")?.array("runs")?.firstOrNull()
                    ?.asObject()?.string("text")?.trim()
                ?: continue
            if (name.isBlank()) continue
            val (channelId, authUser) = findChannelToken(item)
            val photo = item.obj("accountPhoto")?.obj("thumbnails")?.array("thumbnails")
                ?.lastOrNull()?.asObject()?.string("url")
                ?: extractThumbnailsUrl(item)
            val handle = item.obj("channelHandle")?.array("runs")?.firstOrNull()
                ?.asObject()?.string("text")
            // Skip the row that merely repeats the active session entry.
            if (channelId == null && authUser == null &&
                options.any { it.accountName.equals(name, ignoreCase = true) }
            ) continue
            options += YtChannelOption(
                channelId = channelId,
                authUserIndex = authUser,
                accountName = name,
                channelHandle = handle,
                photoUrl = photo,
                isActive = false,
            )
        }
        markActiveChannel(
            options.distinctBy { Triple(it.channelId, it.authUserIndex, it.accountName.lowercase()) },
        )
    }

    /**
     * GETs music.youtube.com's own account-switcher endpoint (the one the
     * avatar → switch-account button fires), which answers as `)]}\'` +
     * `{"code":"SUCCESS","data":{...}}`. Returns the `data` subtree, or
     * throws on any failure (callers fall back to `account_menu`).
     */
    private suspend fun fetchAccountSwitcherRoot(config: WebConfig): JsonObject = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val account = ytAuth.connection.value
        if (!account.isConnected) throw IOException("YouTube Music not connected")
        val builder = Request.Builder()
            .url("$YOUTUBE_MUSIC_ORIGIN/getAccountSwitcherEndpoint")
            .get()
            .header("User-Agent", WEB_USER_AGENT)
            .header("Origin", YOUTUBE_MUSIC_ORIGIN)
            .header("Referer", "$YOUTUBE_MUSIC_ORIGIN/")
            .header("X-YouTube-Client-Name", CLIENT_IDS["WEB_REMIX"] ?: "WEB_REMIX")
            .header("X-YouTube-Client-Version", config.clientVersion)
        ytAuth.cookieHeaderValue(account)?.let { builder.header("Cookie", it) }
        ytAuth.authorizationHeaderValue(account = account)?.let { builder.header("Authorization", it) }
        // The web client always sends an explicit authuser here (0 for the
        // first login); brand rows under it carry `authuser=0&pageid=...`.
        builder.header("X-Goog-AuthUser", (account.authUserIndex ?: 0).toString())
        webConfig?.visitorData?.let { builder.header("X-Goog-Visitor-Id", it) }
        val call = http.newCall(builder.build())
        call.timeout().timeout(ACCOUNT_SWITCHER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val (status, text) = call.readResponseBody()
        if (status !in 200..299) throw InnerTubeHttpException(status)
        // Anti-hijack prefix (`)]}\'`): the JSON payload starts at the first `{`.
        val body = text.substringAfter('{', missingDelimiterValue = "")
        if (body.isEmpty()) throw IOException("Empty account switcher response")
        val parsed = try {
            json.parseToJsonElement("{$body").jsonObject
        } catch (error: Exception) {
            throw IOException("Invalid account switcher response", error)
        }
        if (parsed["code"]?.jsonPrimitive?.contentOrNull != "SUCCESS") {
            throw IOException("Account switcher rejected session")
        }
        parsed["data"]?.jsonObject ?: parsed
    }

    /**
     * Parses `getAccountSwitcherEndpoint` rows (`accountItem`, not
     * `accountItemRenderer`). Brand channels carry
     * `supportedTokens[].pageIdToken.pageId`; the main channel has no
     * `pageIdToken`. Multi-login index comes from
     * `accountSigninToken.signinUrl`'s `authuser=N` (brand rows under the
     * first login carry `authuser=0&pageid=...`).
     */
    private fun parseAccountSwitcherChannels(root: JsonObject): List<YtChannelOption> {
        val items = mutableListOf<JsonObject>()
        collectObjects(root, "accountItem", items)
        if (items.isEmpty()) return emptyList()
        val options = mutableListOf<YtChannelOption>()
        for (item in items) {
            val name = item.obj("accountName")?.string("simpleText")?.trim()
                ?: item.obj("accountName")?.array("runs")?.firstOrNull()
                    ?.asObject()?.string("text")?.trim()
                ?: continue
            if (name.isBlank()) continue
            // "Other accounts" rows without a channel are still useful as
            // multi-login targets, but nameless rows never are.
            val (pageId, authUser, isSelected) = findSwitcherToken(item)
            val photo = item.obj("accountPhoto")?.obj("thumbnails")?.array("thumbnails")
                ?.lastOrNull()?.asObject()?.string("url")
                ?: extractThumbnailsUrl(item)
            val handle = item.obj("channelHandle")?.array("runs")?.firstOrNull()
                ?.asObject()?.string("text")
            options += YtChannelOption(
                channelId = null,
                authUserIndex = authUser,
                accountName = name,
                channelHandle = handle,
                photoUrl = photo,
                isActive = isSelected,
                pageId = pageId.orEmpty(),
            )
        }
        return options.distinctBy { Triple(it.pageId, it.authUserIndex, it.accountName.lowercase()) }
    }

    /** Marks the locally selected channel active (server `isSelected` is only
     *  a fallback for fresh logins — we switch per-request, never server-side). */
    private fun markActiveChannel(options: List<YtChannelOption>): List<YtChannelOption> {
        if (options.isEmpty()) return options
        val current = ytAuth.connection.value
        val matchIndex = options.indexOfFirst { option ->
            option.pageId == current.pageId &&
                option.channelId == current.onBehalfOfUser &&
                option.authUserIndex == current.authUserIndex
        }
        val resolved = if (matchIndex >= 0) {
            options.mapIndexed { index, option -> option.copy(isActive = index == matchIndex) }
        } else if (options.none { it.isActive }) {
            // No stored selection and no server hint: default (blank pageId) first.
            options.mapIndexed { index, option ->
                option.copy(isActive = index == options.indexOfFirst { it.pageId.isBlank() }.takeIf { it >= 0 } ?: 0)
            }
        } else {
            options
        }
        return resolved.sortedByDescending { it.isActive }
    }

    private data class SwitcherToken(
        val pageId: String?,
        val authUserIndex: Int?,
        val isSelected: Boolean,
    )

    private fun findSwitcherToken(item: JsonObject): SwitcherToken {
        var pageId: String? = null
        var pageIdFromUrl: String? = null
        var authUser: Int? = null
        var isSelected = false
        fun visit(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, child) ->
                    if (child is JsonPrimitive && child.isString) {
                        val value = child.contentOrNull.orEmpty()
                        when (key) {
                            "pageId" -> if (pageId == null && value.isNotBlank()) pageId = value
                            "signinUrl", "signinurl" -> {
                                if (authUser == null) {
                                    Regex("authuser=(\\d+)").find(value)?.groupValues
                                        ?.getOrNull(1)?.toIntOrNull()?.let { authUser = it }
                                }
                                if (pageIdFromUrl == null) {
                                    Regex("[?&]pageid=(\\d+)").find(value)?.groupValues
                                        ?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { pageIdFromUrl = it }
                                }
                            }
                        }
                        if (authUser == null) {
                            Regex("authuser=(\\d+)").find(value)?.groupValues
                                ?.getOrNull(1)?.toIntOrNull()?.let { authUser = it }
                        }
                    } else if (child is JsonPrimitive) {
                        if (key == "isSelected" && child.toString() == "true") isSelected = true
                    }
                    visit(child)
                }
                is JsonArray -> element.forEach(::visit)
                else -> Unit
            }
        }
        visit(item)
        return SwitcherToken(pageId ?: pageIdFromUrl, authUser, isSelected)
    }

    /** Scoped search for a channel delegation token inside one menu entry. */
    private fun findChannelToken(item: JsonObject): Pair<String?, Int?> {
        var onBehalf: String? = null
        var channelId: String? = null
        var browseId: String? = null
        var authUser: Int? = null
        fun visit(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, child) ->
                    if (child is JsonPrimitive && child.isString) {
                        val value = child.contentOrNull.orEmpty()
                        when (key) {
                            "onBehalfOfUser" -> if (value.isNotBlank()) onBehalf = value
                            "channelId" -> if (value.isChannelId()) channelId = value
                            "browseId" -> if (value.isChannelId()) browseId = value
                        }
                        if (authUser == null) {
                            Regex("authuser=(\\d+)").find(value)?.groupValues
                                ?.getOrNull(1)?.toIntOrNull()?.let { authUser = it }
                        }
                    }
                    visit(child)
                }
                is JsonArray -> element.forEach(::visit)
                else -> Unit
            }
        }
        visit(item)
        return Pair(onBehalf ?: channelId ?: browseId, authUser)
    }

    private fun String.isChannelId(): Boolean =
        startsWith("UC") && length >= 20 && all { it.isLetterOrDigit() || it == '_' || it == '-' }

    /** Creates a PRIVATE playlist owned by the connected account; returns its id. */
    suspend fun createRemotePlaylist(title: String): String? = withContext(Dispatchers.IO) {
        if (!ytAuth.connection.value.isConnected) return@withContext null
        val cleanTitle = title.replace("<", "(").replace(">", ")").take(150)
            .ifBlank { "LastWave Playlist" }
        val config = getWebConfig()
        val root = runCatching {
            post(
                url = "$MUSIC_API/playlist/create?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                    put("title", cleanTitle)
                    put("privacyStatus", "PRIVATE")
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
                authenticated = true,
            )
        }.getOrNull() ?: return@withContext null
        root.string("playlistId")?.takeIf { it.isNotBlank() }
    }

    /** Renames an owned remote playlist via ACTION_SET_PLAYLIST_NAME. */
    suspend fun renameRemotePlaylist(playlistId: String, title: String): Boolean = withContext(Dispatchers.IO) {
        editRemotePlaylist(
            playlistId = playlistId,
            actions = listOf(buildJsonObject {
                put("action", "ACTION_SET_PLAYLIST_NAME")
                put("playlistName", title.take(150))
            }),
        )
    }

    /** Deletes a remote playlist owned by the connected account. */
    suspend fun deleteRemotePlaylist(playlistId: String): Boolean = withContext(Dispatchers.IO) {
        if (!ytAuth.connection.value.isConnected) return@withContext false
        val config = getWebConfig()
        // HTTP success is authoritative for this endpoint; some responses omit
        // the "status" field entirely, so don't require it.
        runCatching {
            post(
                url = "$MUSIC_API/playlist/delete?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                    put("playlistId", playlistId)
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
                authenticated = true,
            )
            true
        }.getOrDefault(false)
    }

    /** Appends videos (deduped server-side); batched at InnerTube's ~50 actions/request. */
    suspend fun addVideosToRemotePlaylist(playlistId: String, videoIds: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            if (videoIds.isEmpty()) return@withContext true
            videoIds.chunked(WRITE_ACTIONS_PER_REQUEST).all { chunk ->
                editRemotePlaylist(
                    playlistId = playlistId,
                    actions = chunk.map { videoId ->
                        buildJsonObject {
                            put("action", "ACTION_ADD_VIDEO")
                            put("addedVideoId", videoId)
                            put("dedupeOption", "DEDUPE_OPTION_SKIP")
                        }
                    },
                )
            }
        }

    /** Removes entries by their per-entry setVideoId (from [fetchOwnedPlaylist]). */
    suspend fun removeVideosFromRemotePlaylist(
        playlistId: String,
        removals: List<Pair<String, String>>,
    ): Boolean = withContext(Dispatchers.IO) {
        if (removals.isEmpty()) return@withContext true
        removals.chunked(WRITE_ACTIONS_PER_REQUEST).all { chunk ->
            editRemotePlaylist(
                playlistId = playlistId,
                actions = chunk.map { (setVideoId, removedVideoId) ->
                    buildJsonObject {
                        put("action", "ACTION_REMOVE_VIDEO")
                        put("setVideoId", setVideoId)
                        put("removedVideoId", removedVideoId)
                    }
                },
            )
        }
    }

    /** Reads back an OWNED playlist with each item's setVideoId for diffs/removals. */
    suspend fun fetchOwnedPlaylist(
        playlistIdOrUrl: String,
        stopAfterVideoId: String? = null,
    ): YtOwnedPlaylist? = withContext(Dispatchers.IO) {
        if (!ytAuth.connection.value.isConnected) return@withContext null
        val rawId = extractPlaylistId(playlistIdOrUrl)
        if (rawId.isBlank()) return@withContext null
        val browseId = if (rawId.startsWith("VL")) rawId else "VL$rawId"

        val root = runCatching { browseRoot(browseId, authenticated = true) }.getOrNull()
            ?: return@withContext null

        val title = root.obj("header")?.obj("musicEditablePlaylistDetailHeaderRenderer")
            ?.obj("header")?.obj("musicResponsiveHeaderRenderer")?.obj("title")?.array("runs")
            ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: root.obj("header")?.obj("musicDetailHeaderRenderer")?.obj("title")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: root.obj("header")?.obj("musicResponsiveHeaderRenderer")?.obj("title")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: "Playlist"

        val shelves = playlistTrackContainers(root)
        if (shelves.isEmpty()) return@withContext null

        val items = mutableListOf<YtOwnedPlaylistItem>()
        val seenEntries = mutableSetOf<String>()
        fun absorb(element: JsonElement) {
            val renderers = mutableListOf<JsonObject>()
            collectObjects(element, "musicResponsiveListItemRenderer", renderers)
            collectObjects(element, "playlistVideoRenderer", renderers)
            for (renderer in renderers) {
                val videoId = renderer.obj("playlistItemData")?.string("videoId")
                    ?: (renderer["videoId"] as? JsonPrimitive)?.contentOrNull
                    ?: findString(renderer, "videoId")
                    ?: continue
                val setVideoId = extractSetVideoId(renderer)
                val entryKey = setVideoId ?: videoId
                if (videoId.isBlank() || !seenEntries.add(entryKey)) continue
                items += YtOwnedPlaylistItem(videoId, setVideoId)
            }
        }
        shelves.forEach(::absorb)

        var token = shelves.firstNotNullOfOrNull(::playlistTrackContinuationToken)
        val seenTokens = mutableSetOf<String>()
        var page = 0
        fun targetFound() = stopAfterVideoId != null && items.any {
            it.videoId == stopAfterVideoId && !it.setVideoId.isNullOrBlank()
        }
        while (!targetFound() && !token.isNullOrBlank() && page < MAX_CONTINUATION_PAGES) {
            val currentToken = token ?: break
            if (!seenTokens.add(currentToken)) return@withContext null
            val nextPage = runCatching { browseContinuation(currentToken, authenticated = true) }
                .getOrNull() ?: return@withContext null
            val pageContainers = playlistTrackContainers(nextPage)
            if (pageContainers.isEmpty()) return@withContext null
            pageContainers.forEach(::absorb)
            token = pageContainers.firstNotNullOfOrNull(::playlistTrackContinuationToken)
            page++
        }
        if (!targetFound() && !token.isNullOrBlank()) return@withContext null

        YtOwnedPlaylist(id = rawId, title = title, items = items)
    }

    private suspend fun editRemotePlaylist(playlistId: String, actions: List<JsonElement>): Boolean =
        withContext(Dispatchers.IO) {
            if (!ytAuth.connection.value.isConnected) return@withContext false
            val cleanId = playlistId.removePrefix("VL")
            val config = getWebConfig()
            runCatching {
                val root = post(
                    url = "$MUSIC_API/browse/edit_playlist?key=${config.apiKey}&prettyPrint=false",
                    body = buildJsonObject {
                        put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                        put("playlistId", cleanId)
                        put("actions", JsonArray(actions))
                    },
                    clientName = "WEB_REMIX",
                    clientVersion = config.clientVersion,
                    userAgent = WEB_USER_AGENT,
                    authenticated = true,
                )
                val status = root.string("status").orEmpty()
                status.isBlank() || status.contains("SUCCEEDED", ignoreCase = true) || root["actions"] != null
            }.getOrElse { false }
        }

    private suspend fun browseRoot(browseId: String, authenticated: Boolean): JsonObject {
        val config = getWebConfig()
        return post(
            url = "$MUSIC_API/browse?key=${config.apiKey}&prettyPrint=false",
            body = buildJsonObject {
                put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                put("browseId", browseId)
            },
            clientName = "WEB_REMIX",
            clientVersion = config.clientVersion,
            userAgent = WEB_USER_AGENT,
            authenticated = authenticated,
        )
    }

    private suspend fun browseContinuation(token: String, authenticated: Boolean): JsonObject {
        val config = getWebConfig()
        return post(
            url = "$MUSIC_API/browse?key=${config.apiKey}&prettyPrint=false",
            body = buildJsonObject {
                put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                put("continuation", token)
            },
            clientName = "WEB_REMIX",
            clientVersion = config.clientVersion,
            userAgent = WEB_USER_AGENT,
            authenticated = authenticated,
        )
    }

    /** Only playlist rows and their continuation, excluding recommendation shelves. */
    private fun playlistTrackContainers(root: JsonElement): List<JsonElement> {
        val shelves = mutableListOf<JsonObject>()
        collectObjects(root, "musicPlaylistShelfRenderer", shelves)
        collectObjects(root, "musicPlaylistShelfContinuation", shelves)
        collectObjects(root, "playlistVideoListRenderer", shelves)
        collectObjects(root, "playlistVideoListContinuation", shelves)
        if (shelves.isNotEmpty()) return shelves

        val musicShelves = mutableListOf<JsonObject>()
        collectObjects(root, "musicShelfRenderer", musicShelves)
        musicShelves.firstOrNull { parseSongRenderers(it).isNotEmpty() }?.let { return listOf(it) }

        val continuations = root.obj("continuationContents")
        continuations?.obj("musicShelfContinuation")?.let { return listOf(it) }
        return listOf("onResponseReceivedActions", "onResponseReceivedEndpoints", "onResponseReceivedCommands")
            .flatMap { key -> root.array(key).orEmpty() }
            .mapNotNull { action ->
                (action.obj("appendContinuationItemsAction") ?: action.obj("reloadContinuationItemsCommand"))
                    ?.array("continuationItems")
            }
    }

    private fun playlistTrackContinuationToken(container: JsonElement): String? {
        val contents = (container as? JsonArray) ?: container.array("contents")
        if (contents != null) {
            for (i in contents.indices.reversed()) {
                val item = contents[i].asObject() ?: continue
                val continuationItem = item.obj("continuationItemRenderer") ?: continue
                val commands = mutableListOf<JsonObject>()
                collectObjects(continuationItem, "continuationCommand", commands)
                commands.firstNotNullOfOrNull { command ->
                    command.string("token")?.takeIf(String::isNotBlank)
                }?.let { return it }
                val nextData = mutableListOf<JsonObject>()
                collectObjects(continuationItem, "nextContinuationData", nextData)
                nextData.firstNotNullOfOrNull {
                    it.string("continuation")?.takeIf(String::isNotBlank)
                }?.let { return it }
            }
        }
        val commands = mutableListOf<JsonObject>()
        collectObjects(container, "continuationCommand", commands)
        commands.firstNotNullOfOrNull { command ->
            command.string("token")?.takeIf(String::isNotBlank)
        }?.let { return it }

        val continuations = mutableListOf<JsonObject>()
        collectObjects(container, "nextContinuationData", continuations)
        return continuations.firstNotNullOfOrNull {
            it.string("continuation")?.takeIf(String::isNotBlank)
        }
    }

    /** First continuation token anywhere in the tree (grid/list fallbacks). */
    private fun genericContinuationToken(root: JsonElement): String? {
        val commands = mutableListOf<JsonObject>()
        collectObjects(root, "continuationCommand", commands)
        commands.firstNotNullOfOrNull { cmd ->
            cmd.string("token")?.takeIf(String::isNotBlank)
        }?.let { return it }

        val legacyItems = mutableListOf<JsonObject>()
        collectObjects(root, "nextContinuationData", legacyItems)
        return legacyItems.firstNotNullOfOrNull { it.string("continuation")?.takeIf(String::isNotBlank) }
    }

    private fun extractSetVideoId(renderer: JsonObject): String? {
        (renderer["setVideoId"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let { return it }
        renderer.obj("playlistItemData")?.let { data ->
            (data.string("playlistSetVideoId") ?: data.string("videoSetVideoId") ?: data.string("setVideoId"))
                ?.takeIf(String::isNotBlank)?.let { return it }
        }
        val editEndpoints = mutableListOf<JsonObject>()
        collectObjects(renderer, "playlistEditEndpoint", editEndpoints)
        for (ep in editEndpoints) {
            val actions = ep.array("actions") ?: continue
            for (action in actions) {
                val actObj = action.asObject() ?: continue
                val svId = actObj.string("setVideoId")
                if (!svId.isNullOrBlank()) return svId
            }
        }
        return findString(renderer, "setVideoId") ?: findString(renderer, "playlistSetVideoId")
    }

    suspend fun searchSongs(
        query: String,
        limit: Int = 30,
        prefetchStreams: Boolean = true,
    ): List<YouTubeMusicTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val config = getWebConfig()
        suspend fun runSearch(params: String?): List<YouTubeMusicTrack> {
            val body = buildJsonObject {
                put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                put("query", query.trim())
                if (params != null) put("params", params)
            }
            val root = post(
                url = "$MUSIC_API/search?key=${config.apiKey}&prettyPrint=false",
                body = body,
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
            )
            return parseSongRenderers(root)
        }
        val filtered = runCatching { runSearch("EgWKAQIIAWoKEAkQBRAKEAMQBA==") }.getOrDefault(emptyList())
        // Unfiltered POST only when the filtered search came back empty (rare miss).
        val results = (if (filtered.isEmpty()) {
            (runCatching { runSearch(null) }.getOrDefault(emptyList()))
        } else {
            filtered
        })
            .distinctBy { it.videoId }
            .filter { it.videoId.isNotBlank() }
            .take(limit)
        if (prefetchStreams) results.take(2).forEach { prefetchStream(it.videoId) }
        results
    }

    /** Anonymous YouTube Music radio for a seed video. This intentionally
     * stays cookie-free so related-song playlists work without an account. */
    suspend fun fetchRelatedSongs(
        videoId: String,
        limit: Int = 30,
        prefetchStreams: Boolean = true,
    ): List<YouTubeMusicTrack> = withContext(Dispatchers.IO) {
        if (videoId.isBlank() || limit <= 0) return@withContext emptyList()
        val config = getWebConfig()
        val root = post(
            url = "$MUSIC_API/next?key=${config.apiKey}&prettyPrint=false",
            body = buildJsonObject {
                put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                put("videoId", videoId)
                put("playlistId", "RDAMVM$videoId")
                put("params", "wAEB")
                put("isAudioOnly", true)
            },
            clientName = "WEB_REMIX",
            clientVersion = config.clientVersion,
            userAgent = WEB_USER_AGENT,
            callTimeoutMs = RELATED_REQUEST_TIMEOUT_MS,
        )
        val results = parseSongRenderers(root)
            .filterNot { it.videoId == videoId }
            .take(limit)
        if (prefetchStreams) results.take(2).forEach { prefetchStream(it.videoId) }
        results
    }

    suspend fun searchArtists(query: String, limit: Int = 30): List<YouTubeMusicEntity> =
        searchEntities(query, YouTubeMusicEntityKind.ARTIST, ARTIST_SEARCH_FILTER, limit)

    suspend fun searchAlbums(query: String, limit: Int = 30): List<YouTubeMusicEntity> =
        searchEntities(query, YouTubeMusicEntityKind.ALBUM, ALBUM_SEARCH_FILTER, limit)

    /** Loads and parses full artist details including top songs, albums, singles, and similar artists. */
    suspend fun fetchArtistPage(
        browseId: String,
        artistNameFallback: String = "",
        onLoaded: (com.lastwave.app.data.model.ArtistPageData) -> Unit = {},
    ): com.lastwave.app.data.model.ArtistPageData? = withContext(Dispatchers.IO) {
        if (browseId.isBlank()) return@withContext null
        val config = getWebConfig()
        val root = runCatching {
            post(
                url = "$MUSIC_API/browse?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                    put("browseId", browseId)
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
            )
        }.getOrNull() ?: return@withContext null

        val header = root.obj("header")?.obj("musicVisualHeaderRenderer")
            ?: root.obj("header")?.obj("musicImmersiveHeaderRenderer")
            ?: root.obj("header")?.obj("musicHeaderRenderer")

        val rawTitle = header?.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?.ifBlank { null }
            ?: header?.string("title")
            ?: artistNameFallback.ifBlank { "Artist" }
        val title = com.lastwave.app.util.ArtistHelper.primaryArtist(rawTitle).trim().ifBlank { rawTitle }

        val subscriberText = header?.obj("subscriptionButton")?.obj("subscribeButtonRenderer")?.obj("subscriberCountText")?.array("runs")
            ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: header?.obj("subtitle")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: header?.obj("straplineTextOne")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }

        val descRuns = header?.obj("description")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }

        val bannerThumbs = header?.obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.array("thumbnails")
            ?: header?.obj("thumbnail")?.array("thumbnails")
        val avatarThumbs = header?.obj("foregroundThumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.array("thumbnails")
            ?: bannerThumbs

        val artworkUrl = avatarThumbs?.lastOrNull()?.asObject()?.string("url")?.highResolutionArtwork()
            ?: header?.let(::extractThumbnailsUrl)
        val bannerUrl = bannerThumbs?.lastOrNull()?.asObject()?.string("url")?.highResolutionArtwork()
            ?: artworkUrl

        val shelves = mutableListOf<JsonObject>()
        collectObjects(root, "musicShelfRenderer", shelves)
        collectObjects(root, "musicCarouselShelfRenderer", shelves)

        var topSongs = emptyList<com.lastwave.app.playback.PlayableTrack>()
        val albums = mutableListOf<com.lastwave.app.data.model.ArtistAlbumItem>()
        val singles = mutableListOf<com.lastwave.app.data.model.ArtistAlbumItem>()
        val similarArtists = mutableListOf<com.lastwave.app.data.model.ArtistSummaryItem>()

        for (shelf in shelves) {
            val heading = shelf.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?: shelf.obj("header")?.obj("musicCarouselShelfBasicHeaderRenderer")?.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?: ""

            when {
                heading.contains("song", ignoreCase = true) || heading.contains("track", ignoreCase = true) -> {
                    if (topSongs.isEmpty()) {
                        val parsed = parseSongRenderers(shelf)
                        val previewTracks = parsed.map { track ->
                            com.lastwave.app.playback.PlayableTrack(
                                title = track.title,
                                artist = track.artist.takeUnless { it == "Unknown artist" } ?: title,
                                album = track.album,
                                artworkUrl = track.artworkUrl ?: artworkUrl,
                                videoId = track.videoId,
                            )
                        }

                        if (previewTracks.isNotEmpty()) {
                            onLoaded(com.lastwave.app.data.model.ArtistPageData(
                                name = title,
                                browseId = browseId,
                                artworkUrl = artworkUrl,
                                bannerUrl = bannerUrl,
                                topSongs = previewTracks,
                            ))
                        }

                        // YouTube Music artist overview only embeds 5 preview tracks in the initial shelf.
                        // Follow the shelf's "Show all" / "More" endpoint (e.g. VLOLAK... or FEmusic_artist_more_tracks) to get full top tracks.
                        val moreEndpoint = shelf.obj("bottomEndpoint")?.obj("browseEndpoint")
                            ?: shelf.obj("bottomText")?.array("runs")?.firstOrNull()?.asObject()?.obj("navigationEndpoint")?.obj("browseEndpoint")
                            ?: shelf.obj("title")?.array("runs")?.firstOrNull()?.asObject()?.obj("navigationEndpoint")?.obj("browseEndpoint")
                            ?: shelf.obj("header")?.obj("musicShelfHeaderRenderer")?.obj("title")?.array("runs")?.firstOrNull()?.asObject()?.obj("navigationEndpoint")?.obj("browseEndpoint")
                            ?: shelf.obj("header")?.obj("musicCarouselShelfBasicHeaderRenderer")?.obj("moreContentButton")?.obj("buttonRenderer")?.obj("navigationEndpoint")?.obj("browseEndpoint")

                        val moreBrowseId = moreEndpoint?.string("browseId")
                        val moreParams = moreEndpoint?.string("params")

                        val fullTracks = if (!moreBrowseId.isNullOrBlank()) {
                            runCatching {
                                browseSongs(moreBrowseId, params = moreParams, limit = 100).map { track ->
                                    com.lastwave.app.playback.PlayableTrack(
                                        title = track.title,
                                        artist = track.artist.takeUnless { it == "Unknown artist" } ?: title,
                                        album = track.album,
                                        artworkUrl = track.artworkUrl ?: artworkUrl,
                                        videoId = track.videoId,
                                    )
                                }
                            }.getOrNull()
                        } else null

                        topSongs = if (!fullTracks.isNullOrEmpty()) {
                            fullTracks
                        } else {
                            previewTracks
                        }
                    }
                }
                heading.contains("album", ignoreCase = true) -> {
                    albums.addAll(parseAlbumTwoRowItems(shelf, defaultType = "Album"))
                }
                heading.contains("single", ignoreCase = true) || heading.contains("ep", ignoreCase = true) -> {
                    singles.addAll(parseAlbumTwoRowItems(shelf, defaultType = "Single"))
                }
                heading.contains("similar", ignoreCase = true) || heading.contains("fans", ignoreCase = true) || heading.contains("like", ignoreCase = true) -> {
                    similarArtists.addAll(parseArtistTwoRowItems(shelf))
                }
            }
        }

        // Fallback: If no top songs shelf was explicitly labelled, try parsing songs from whole root
        if (topSongs.isEmpty()) {
            val parsed = parseSongRenderers(root)
            topSongs = parsed.map { track ->
                com.lastwave.app.playback.PlayableTrack(
                    title = track.title,
                    artist = track.artist.takeUnless { it == "Unknown artist" } ?: title,
                    album = track.album,
                    artworkUrl = track.artworkUrl ?: artworkUrl,
                    videoId = track.videoId,
                )
            }
        }

        // Prefetch first few tracks for instant playback
        topSongs.take(3).forEach { it.videoId?.let { id -> prefetchStream(id) } }

        com.lastwave.app.data.model.ArtistPageData(
            name = title,
            browseId = browseId,
            artworkUrl = artworkUrl,
            bannerUrl = bannerUrl,
            subscribers = subscriberText?.takeIf(String::isNotBlank),
            bio = descRuns?.takeIf(String::isNotBlank),
            topSongs = topSongs,
            albums = albums.distinctBy { it.browseId.ifBlank { it.title } },
            singles = singles.distinctBy { it.browseId.ifBlank { it.title } },
            similarArtists = similarArtists.distinctBy { it.browseId.ifBlank { it.name } },
        )
    }

    /** Loads and parses complete album details including ordered tracklist and metadata. */
    suspend fun fetchAlbumPage(browseId: String, albumTitleFallback: String = "", artistFallback: String = ""): com.lastwave.app.data.model.AlbumPageData? = withContext(Dispatchers.IO) {
        if (browseId.isBlank()) return@withContext null
        val normalizedBrowseId = if (browseId.startsWith("PL") || browseId.startsWith("OLAK")) "VL$browseId" else browseId
        val config = getWebConfig()
        val root = runCatching {
            post(
                url = "$MUSIC_API/browse?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                    put("browseId", normalizedBrowseId)
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
            )
        }.getOrNull() ?: return@withContext null

        val responsiveHeaders = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveHeaderRenderer", responsiveHeaders)
        val header = root.obj("header")?.obj("musicDetailHeaderRenderer")
            ?: root.obj("header")?.obj("musicResponsiveHeaderRenderer")
            ?: root.obj("header")?.obj("musicEditablePlaylistDetailHeaderRenderer")?.obj("header")?.obj("musicResponsiveHeaderRenderer")
            ?: responsiveHeaders.firstOrNull()

        val title = header?.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?.ifBlank { null }
            ?: header?.string("title")
            ?: albumTitleFallback.ifBlank { "Album" }

        val subRuns = header?.obj("subtitle")?.array("runs")?.mapNotNull { it.asObject() }.orEmpty()
        val artistRun = subRuns.firstOrNull { it.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("UC") == true }
        val artist = artistRun?.string("text") ?: header?.obj("straplineTextOne")?.array("runs")?.firstOrNull()?.asObject()?.string("text") ?: artistFallback.ifBlank { "Various Artists" }
        val artistBrowseId = artistRun?.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")

        val releaseYear = subRuns.mapNotNull { it.string("text") }.firstOrNull { it.trim().matches(Regex("^(19|20)\\d{2}$")) }

        val secondSubtitleRuns = header?.obj("secondSubtitle")?.array("runs")?.mapNotNull { it.asObject()?.string("text") }.orEmpty()
        val durationText = secondSubtitleRuns.firstOrNull { "min" in it.lowercase() || "hour" in it.lowercase() || "sec" in it.lowercase() }

        val descRuns = header?.obj("description")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }

        val thumbs = header?.obj("thumbnail")?.obj("croppedSquareThumbnailRenderer")?.array("thumbnails")
            ?: header?.obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.array("thumbnails")
            ?: header?.obj("thumbnail")?.array("thumbnails")
        val artworkUrl = thumbs?.lastOrNull()?.asObject()?.string("url")?.highResolutionArtwork()

        val songPages = collectBrowseSongPages(root, limit = 100)
        val tracks = songPages.tracks.map { track ->
            val cleanArtist = track.artist.trim()
            val resolvedArtist = if (com.lastwave.app.util.ArtistHelper.isPlayCountOrStat(cleanArtist) ||
                cleanArtist.isBlank() ||
                cleanArtist.equals("Unknown artist", ignoreCase = true)
            ) {
                artist
            } else {
                cleanArtist
            }
            com.lastwave.app.playback.PlayableTrack(
                title = track.title,
                artist = resolvedArtist,
                album = title,
                artworkUrl = track.artworkUrl ?: artworkUrl,
                videoId = track.videoId,
            )
        }

        // Prefetch first few tracks for instant playback
        tracks.take(3).forEach { it.videoId?.let { id -> prefetchStream(id) } }

        val otherAlbums = mutableListOf<com.lastwave.app.data.model.ArtistAlbumItem>()
        val shelves = mutableListOf<JsonObject>()
        collectObjects(root, "musicCarouselShelfRenderer", shelves)
        for (shelf in shelves) {
            val heading = shelf.obj("header")?.obj("musicCarouselShelfBasicHeaderRenderer")?.obj("title")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() } ?: ""
            if (heading.contains("album", ignoreCase = true) || heading.contains("more by", ignoreCase = true)) {
                otherAlbums.addAll(parseAlbumTwoRowItems(shelf, defaultType = "Album"))
            }
        }

        com.lastwave.app.data.model.AlbumPageData(
            title = title,
            artist = artist,
            artistBrowseId = artistBrowseId,
            browseId = browseId,
            artworkUrl = artworkUrl,
            releaseYear = releaseYear,
            trackCountText = if (songPages.isComplete) "${tracks.size} songs" else null,
            durationText = durationText,
            description = descRuns?.takeIf(String::isNotBlank),
            tracks = tracks,
            otherAlbums = otherAlbums.distinctBy { it.browseId.ifBlank { it.title } },
        )
    }

    private fun parseAlbumTwoRowItems(container: JsonObject, defaultType: String): List<com.lastwave.app.data.model.ArtistAlbumItem> {
        val items = mutableListOf<JsonObject>()
        collectObjects(container, "musicTwoRowItemRenderer", items)
        return items.mapNotNull { item ->
            val title = item.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?: item.obj("title")?.string("simpleText")
                ?: return@mapNotNull null
            val nav = item.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?: item.obj("title")?.array("runs")?.firstOrNull()?.asObject()?.obj("navigationEndpoint")?.obj("browseEndpoint")
            val browseId = nav?.string("browseId") ?: ""
            val subtitleRuns = item.obj("subtitle")?.array("runs")?.mapNotNull { it.asObject()?.string("text") }.orEmpty()
            val year = subtitleRuns.firstOrNull { it.trim().matches(Regex("^(19|20)\\d{2}$")) }
            val type = subtitleRuns.firstOrNull { it.equals("Single", true) || it.equals("EP", true) || it.equals("Album", true) } ?: defaultType
            val thumbs = item.obj("thumbnailRenderer")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.array("thumbnails")
                ?: item.obj("thumbnail")?.array("thumbnails")
            val artworkUrl = thumbs?.lastOrNull()?.asObject()?.string("url")?.highResolutionArtwork()

            com.lastwave.app.data.model.ArtistAlbumItem(
                title = title.trim(),
                browseId = browseId,
                year = year,
                type = type,
                artworkUrl = artworkUrl,
            )
        }
    }

    private fun parseArtistTwoRowItems(container: JsonObject): List<com.lastwave.app.data.model.ArtistSummaryItem> {
        val items = mutableListOf<JsonObject>()
        collectObjects(container, "musicTwoRowItemRenderer", items)
        return items.flatMap { item ->
            val title = item.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?: item.obj("title")?.string("simpleText")
                ?: return@flatMap emptyList()
            val nav = item.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?: item.obj("title")?.array("runs")?.firstOrNull()?.asObject()?.obj("navigationEndpoint")?.obj("browseEndpoint")
            val browseId = nav?.string("browseId") ?: ""
            val subtitle = item.obj("subtitle")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            val thumbs = item.obj("thumbnailRenderer")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.array("thumbnails")
                ?: item.obj("thumbnail")?.array("thumbnails")
            val artworkUrl = thumbs?.lastOrNull()?.asObject()?.string("url")?.highResolutionArtwork()

            val split = com.lastwave.app.util.ArtistHelper.splitArtists(title)
            split.mapIndexed { index, singleName ->
                com.lastwave.app.data.model.ArtistSummaryItem(
                    name = singleName,
                    browseId = if (split.size == 1 || index == 0) browseId else "",
                    artworkUrl = artworkUrl,
                    subtitle = subtitle?.takeIf { it.isNotBlank() },
                )
            }
        }.distinctBy { it.name.lowercase().trim() }
    }

    /** Loads playable songs for an artist or album without opening YouTube. */
    suspend fun browseSongs(browseId: String, params: String? = null, limit: Int? = null): List<YouTubeMusicTrack> = withContext(Dispatchers.IO) {
        require(browseId.isNotBlank()) { "Missing YouTube Music browse id" }
        val config = getWebConfig()
        val root = post(
            url = "$MUSIC_API/browse?key=${config.apiKey}&prettyPrint=false",
            body = buildJsonObject {
                put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                put("browseId", browseId)
                if (!params.isNullOrBlank()) {
                    put("params", params)
                }
            },
            clientName = "WEB_REMIX",
            clientVersion = config.clientVersion,
            userAgent = WEB_USER_AGENT,
        )
        val result = collectBrowseSongPages(root, limit).tracks
        result.take(2).forEach { prefetchStream(it.videoId) }
        result
    }

    private data class BrowseSongPages(val tracks: List<YouTubeMusicTrack>, val isComplete: Boolean)

    private suspend fun collectBrowseSongPages(root: JsonElement, limit: Int?): BrowseSongPages {
        val shelves = mutableListOf<JsonObject>()
        collectObjects(root, "musicPlaylistShelfRenderer", shelves)
        if (shelves.isEmpty()) collectObjects(root, "musicShelfRenderer", shelves)
        val primaryShelf = shelves.firstOrNull { shelf ->
            val heading = shelf.obj("title")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            heading.equals("Songs", ignoreCase = true) || heading.equals("Tracks", ignoreCase = true)
        } ?: shelves.firstOrNull()

        val songs = mutableListOf<YouTubeMusicTrack>()
        songs.addAll(parseSongRenderers(primaryShelf ?: root))
        if (primaryShelf == null) return BrowseSongPages(songs.take(limit ?: songs.size), isComplete = false)

        var token = playlistTrackContinuationToken(primaryShelf)
        val seenTokens = mutableSetOf<String>()
        val knownVideoIds = songs.mapTo(mutableSetOf()) { it.videoId }
        var page = 0
        val maxPages = if (limit != null) minOf(8, (limit / 20) + 1) else 6
        while (!token.isNullOrBlank() && page < maxPages && (limit == null || songs.size < limit)) {
            val currentToken = token ?: break
            if (!seenTokens.add(currentToken)) break
            val nextPage = runCatching {
                browseContinuation(currentToken, authenticated = false)
            }.getOrNull() ?: break
            val containers = playlistTrackContainers(nextPage)
            if (containers.isEmpty()) break
            val pageSongs = containers.flatMap(::parseSongRenderers)
            songs.addAll(pageSongs.filter { knownVideoIds.add(it.videoId) })
            token = containers.firstNotNullOfOrNull(::playlistTrackContinuationToken)
            page++
        }

        val result = if (limit != null) songs.take(limit) else songs
        return BrowseSongPages(result, token.isNullOrBlank() && result.size == songs.size)
    }

    private suspend fun searchEntities(
        query: String,
        kind: YouTubeMusicEntityKind,
        filter: String,
        limit: Int,
    ): List<YouTubeMusicEntity> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val config = getWebConfig()
        val root = post(
            url = "$MUSIC_API/search?key=${config.apiKey}&prettyPrint=false",
            body = buildJsonObject {
                put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                put("query", query.trim())
                put("params", filter)
            },
            clientName = "WEB_REMIX",
            clientVersion = config.clientVersion,
            userAgent = WEB_USER_AGENT,
        )
        parseEntityRenderers(root, kind).take(limit)
    }

    suspend fun searchPlaylists(query: String, limit: Int = 30): List<YouTubePlaylistSummary> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val config = getWebConfig()
        val root = runCatching {
            post(
                url = "$MUSIC_API/search?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                    put("query", query.trim())
                    put("params", "Eg-KAQwIABAAGAAgACgB")
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
            )
        }.getOrNull() ?: return@withContext emptyList()
        parsePlaylistRenderers(root).take(limit)
    }

    /** Fetches rich metadata (title, artist, album, artwork) for a single YouTube video ID */
    suspend fun fetchSongDetails(videoId: String): YouTubeMusicTrack? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null

        // 1. Try InnerTube /player or /next (returns exact artist, title, album, artwork from YouTube Music)
        try {
            val config = getWebConfig()
            val root = post(
                url = "$MUSIC_API/player?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                    put("videoId", videoId)
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
            )
            val videoDetails = root.obj("videoDetails")
            var title = videoDetails?.string("title")
            var artist = videoDetails?.string("author")
            val thumbs = videoDetails?.obj("thumbnail")?.array("thumbnails")
            val artworkUrl = thumbs?.lastOrNull()?.let { (it as? JsonObject)?.string("url") }

            if (!title.isNullOrBlank() && !artist.isNullOrBlank()) {
                if (artist.endsWith(" - Topic")) {
                    artist = artist.removeSuffix(" - Topic").trim()
                }
                if (title.contains(" - ")) {
                    val parts = title.split(" - ", limit = 2)
                    if (artist.isBlank() || artist == "YouTube Music" || artist.equals(parts[0].trim(), ignoreCase = true)) {
                        artist = parts[0].trim()
                        title = parts[1].trim()
                    }
                }
                return@withContext YouTubeMusicTrack(
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    artworkUrl = artworkUrl ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                )
            }
        } catch (_: Exception) {}

        // 2. Fallback: YouTube oEmbed
        try {
            val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
            val request = Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            val resp = http.newCall(request).execute()
            val jsonStr = resp.use { it.body?.string().orEmpty() }
            if (jsonStr.isNotBlank()) {
                val obj = json.parseToJsonElement(jsonStr).jsonObject
                val rawTitle = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val author = obj["author_name"]?.jsonPrimitive?.contentOrNull.orEmpty().removeSuffix(" - Topic").trim()
                val thumbnail = obj["thumbnail_url"]?.jsonPrimitive?.contentOrNull ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                var finalTitle = rawTitle
                var finalArtist = author.ifBlank { "YouTube Music" }
                if (rawTitle.contains(" - ")) {
                    val split = rawTitle.split(" - ", limit = 2)
                    finalArtist = split[0].trim()
                    finalTitle = split[1].trim()
                }

                if (finalTitle.isNotBlank()) {
                    return@withContext YouTubeMusicTrack(
                        videoId = videoId,
                        title = finalTitle,
                        artist = finalArtist,
                        artworkUrl = thumbnail,
                    )
                }
            }
        } catch (_: Exception) {}

        null
    }

    /** Instant in-memory stream peek for 0ms playback (Desktop way). Returns null if not cached/fresh. */
    fun peekCachedStream(videoId: String): YouTubeAudioStream? {
        if (videoId.isBlank()) return null
        val now = System.currentTimeMillis()
        val authScope = playbackAuthScope()
        val entry = streamCache.entries
            .asSequence()
            .filter { it.key.videoId == videoId && it.key.authScope == authScope }
            .sortedByDescending { it.value.cachedAtEpochMs }
            .firstOrNull() ?: return null
        val cached = entry.value
        return if (cached.stream.isFresh(cached.cachedAtEpochMs, now)) {
            cached.stream
        } else {
            null
        }
    }

    /** Resolves and byte-probes an expiring googlevideo URL immediately before use. */
    suspend fun resolveAudioStream(videoId: String): YouTubeAudioStream = withContext(Dispatchers.IO) {
        require(videoId.isNotBlank()) { "Missing YouTube Music video id" }
        val startedAt = SystemClock.elapsedRealtime()
        logStage(videoId, "start")
        peekCachedStream(videoId)?.let { stream ->
            val authScope = playbackAuthScope()
            lastResolvedStreams[resolutionKey(videoId, authScope)] = stream
            logStreamEvent("cache-hit", stream)
            return@withContext stream
        }
        val now = System.currentTimeMillis()
        val authScope = playbackAuthScope()

        streamCache.entries
            .asSequence()
            .filter { it.key.videoId == videoId && it.key.authScope == authScope }
            .sortedByDescending { it.value.cachedAtEpochMs }
            .firstOrNull()
            ?.let { entry ->
                val cached = entry.value
                val stream = cached.stream
                if (stream.isFresh(cached.cachedAtEpochMs, now)) {
                    lastResolvedStreams[resolutionKey(videoId, authScope)] = stream
                    logStreamEvent("cache-hit", stream)
                    return@withContext stream
                }
                streamCache.remove(entry.key, entry.value)
                lastResolvedStreams.remove(resolutionKey(videoId, authScope), stream)
                if (stream.expiresAtEpochMs == null || stream.expiresAtEpochMs - now > URL_EXPIRY_MARGIN_MS) {
                    reportPlaybackFailure(videoId, stream)
                }
                logStreamEvent("cache-rejected", stream)
            }

        val requestKey = resolutionKey(videoId, authScope)
        val shared = activeStreamRequests.computeIfAbsent(requestKey) {
            lateinit var request: SharedStreamRequest
            val deferred = apiScope.async(start = CoroutineStart.LAZY) {
                resolveAudioStreamInternal(videoId, authScope, startedAt)
            }
            request = SharedStreamRequest(deferred)
            deferred.invokeOnCompletion {
                activeStreamRequests.remove(requestKey, request)
            }
            request
        }
        shared.waiters.incrementAndGet()
        shared.deferred.start()
        try {
            // Hard cap on one full resolution: without it a bad network could
            // chain every fallback stage into a 60-90s wait before the caller
            // ever got a chance to retry with fresh state.
            kotlinx.coroutines.withTimeoutOrNull(STREAM_RESOLVE_TOTAL_TIMEOUT_MS) {
                shared.deferred.await()
            } ?: throw IOException("Timed out resolving audio stream for $videoId")
        } finally {
            if (shared.waiters.decrementAndGet() == 0 && !shared.deferred.isCompleted) {
                shared.deferred.cancel()
                activeStreamRequests.remove(requestKey, shared)
            }
        }
    }

    /** Resolves stream specifically optimized for download compatibility (M4A AAC container). */
    suspend fun resolveDownloadStream(videoId: String): YouTubeAudioStream = withContext(Dispatchers.IO) {
        require(videoId.isNotBlank()) { "Missing YouTube Music video id" }
        try {
            streamExtractor.resolveAudioStream(videoId, preferM4a = true)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            resolveAudioStream(videoId)
        }
    }

    /**
     * limusic-style direct-URL fast path. Fires the no-cipher clients hedged
     * in parallel (staggered, first success wins) with a tight per-client
     * bound and a whole-path budget; returns the first direct-URL audio
     * stream, or null (never throws) so the full chain below still runs.
     */
    private suspend fun resolveDirectUrlFastPath(
        videoId: String,
        authScope: String,
    ): YouTubeAudioStream? = kotlinx.coroutines.coroutineScope {
        // Cached-only visitor data: never fetch the web config here — the
        // fast path must stay a pure player POST per client. Startup pre-warm
        // (preWarmPlayback) keeps this populated from the first song.
        val visitorData = webConfig?.visitorData
        val nowMs = System.currentTimeMillis()
        val ordered = DIRECT_FAST_CLIENT_ORDER
            .mapNotNull { name -> PLAYER_CLIENTS.firstOrNull { it.name == name } }
            .filter { nowMs >= (failedClientsUntil[clientFailureKey(videoId, it.key, authScope)] ?: 0L) }
            // The client that served the previous song is the most likely to
            // work again — try it first, keep limusic's order otherwise.
            .sortedByDescending { it.name == lastSuccessfulClientName }
        if (ordered.isEmpty()) return@coroutineScope null

        val channel = kotlinx.coroutines.channels.Channel<YouTubeAudioStream>(ordered.size)
        val jobs = ordered.mapIndexed { index, client ->
            launch(Dispatchers.IO) {
                // Hedged stagger: each client gets a short head start over the
                // next, but a slow client never serializes a full timeout
                // onto the ones behind it.
                if (index > 0) {
                    delay(DIRECT_FAST_STAGGER_MS * index)
                    if (!isActive) return@launch
                }
                val stream = try {
                    kotlinx.coroutines.withTimeoutOrNull(DIRECT_FAST_CLIENT_TIMEOUT_MS) {
                        resolveDirectClientStream(
                            videoId = videoId,
                            client = client,
                            visitorData = visitorData,
                            signatureTimestamp = null,
                            playerPoToken = null,
                            gvsPoToken = null,
                            authScope = authScope,
                            probeCandidates = false,
                            allowCipherFormats = false,
                        )
                    }
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
                if (stream != null) {
                    lastSuccessfulClientName = client.name
                    channel.trySend(stream)
                }
            }
        }
        val winner = kotlinx.coroutines.withTimeoutOrNull(DIRECT_FAST_PATH_BUDGET_MS) {
            channel.receiveCatching().getOrNull()
        }
        jobs.forEach { it.cancel() }
        channel.close()
        winner
    }

    private suspend fun resolveAudioStreamInternal(
        videoId: String,
        authScope: String,
        startedAt: Long,
    ): YouTubeAudioStream = kotlinx.coroutines.coroutineScope {
        val now = System.currentTimeMillis()

        // 0. Direct-URL fast path (limusic-style): VISIONOS → ANDROID_VR →
        // TVHTML5, one player POST each, first direct-URL audio format wins.
        // No webConfig fetch, no signatureTimestamp, no poToken, no Rhino
        // decipher, no probe — these clients serve ready URLs. Typical
        // 1-3s. Miss falls through to the full chain below.
        resolveDirectUrlFastPath(videoId, authScope)?.let { fast ->
            cacheResolvedStream(fast, now)
            lastResolvedStreams[resolutionKey(videoId, authScope)] = fast
            logStreamEvent("direct-fast-resolved", fast)
            logStage(videoId, "direct-fast", startedAt)
            return@coroutineScope fast
        }

        // 1. Primary: InnerTubeX (Desktop-style — built-in YouTubeCipherService
        //    handles n-param deobfuscation internally, no Rhino JS overhead).
        //    Bounded so a hung cipher/config fetch fails fast into stage 2.
        //    Media3 validates the URL on open — no blocking probeStream here.
        val innerTubeXCandidate = try {
            kotlinx.coroutines.withTimeoutOrNull(INNERTUBEX_STAGE_TIMEOUT_MS) {
                val visitorData = try {
                    getWebConfig().visitorData
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
                innerTubeXExtractor.resolve(videoId, visitorData, authScope)
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            logClientFailure(videoId, "INNERTUBEX", failure)
            null
        }
        if (innerTubeXCandidate != null) {
            val compatible = innerTubeXCandidate.isAdaptive || isCompatibleAudioCandidate(innerTubeXCandidate)
            // Return URL immediately; player open is the real validation.
            if (compatible) {
                cacheResolvedStream(innerTubeXCandidate, now)
                lastResolvedStreams[resolutionKey(videoId, authScope)] = innerTubeXCandidate
                logStreamEvent("innertubex-resolved", innerTubeXCandidate)
                logStage(videoId, "innertubex", startedAt)
                return@coroutineScope innerTubeXCandidate
            }
            logStreamEvent("innertubex-rejected", innerTubeXCandidate, detail = "compatible=$compatible")
            innerTubeXExtractor.reportPlaybackFailure(
                videoId = videoId,
                authScope = authScope,
                clientProfile = innerTubeXCandidate.clientProfile,
            )
        }

        // 2. Parallel fallback: race direct InnerTube clients vs NewPipe
        //    (NewPipe is no longer primary — it's a parallel racer, first to finish wins)
        val configDeferred = async(Dispatchers.IO) {
            getWebConfig()
        }
        val signatureTimestampDeferred = async(Dispatchers.IO) {
            streamExtractor.getSignatureTimestamp(videoId)
        }
        val poTokenDeferred = async(Dispatchers.IO) {
            val visitorData = getWebConfig().visitorData
            BotGuardTokenGenerator.mintToken(videoId, visitorData ?: FALLBACK_TOKEN_SESSION)
        }
        val prerequisiteJobs = listOf(configDeferred, signatureTimestampDeferred, poTokenDeferred)

        val channel = kotlinx.coroutines.channels.Channel<YouTubeAudioStream>(2)
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        val confirmedUnavailableReasons = ConcurrentHashMap.newKeySet<String>()
        val transientFailures = ConcurrentHashMap.newKeySet<String>()
        val remainingResolvers = AtomicInteger(2)

        fun resolverFinished() {
            if (remainingResolvers.decrementAndGet() == 0) channel.close()
        }

        // 2a. Direct InnerTube clients (hedged parallel, no Rhino dependency)
        jobs += launch(Dispatchers.IO) {
            try {
                val config = configDeferred.await()
                // Do NOT await signatureTimestampDeferred here: it goes
                // through NewPipe's player-JS download and used to hold back
                // every direct client for 10-20s on a cold cache. Only the
                // web clients below actually need it, and they await it
                // lazily inside their own job.
                val poTokenResult = kotlinx.coroutines.withTimeoutOrNull(1_500L) { poTokenDeferred.await() }
                val poToken = poTokenResult?.playerToken
                val gvsPoToken = poTokenResult?.sessionToken?.takeIf { config.visitorData != null }
                val availableClients = playerClients(config).filter { candidate ->
                    now >= (failedClientsUntil[clientFailureKey(videoId, candidate.key, authScope)] ?: 0L)
                }
                if (availableClients.isEmpty()) transientFailures += "All player clients are cooling down"

                val prioritizedClients = availableClients.sortedByDescending { it.name == lastSuccessfulClientName }

                kotlinx.coroutines.coroutineScope {
                    val clientJobs = mutableListOf<kotlinx.coroutines.Job>()
                    val winnerFound = AtomicBoolean(false)

                    for (client in prioritizedClients) {
                        if (winnerFound.get() || !isActive) break

                        clientJobs += launch(Dispatchers.IO) {
                            try {
                                // Only web clients must present a signature
                                // timestamp; app/TV clients play without one.
                                // Awaiting it here keeps the NewPipe player-JS
                                // fetch off the critical path of every client
                                // that doesn't need it.
                                val signatureTimestamp = if (client.needsSignatureTimestamp) {
                                    runCatching { signatureTimestampDeferred.await() }.getOrNull()
                                } else null
                                val stream = resolveDirectClientStream(
                                    videoId = videoId,
                                    client = client,
                                    visitorData = config.visitorData,
                                    signatureTimestamp = signatureTimestamp,
                                    playerPoToken = poToken,
                                    gvsPoToken = gvsPoToken,
                                    authScope = authScope,
                                )
                                if (winnerFound.compareAndSet(false, true)) {
                                    lastSuccessfulClientName = client.name
                                    channel.trySend(stream)
                                    clientJobs.forEach { if (it != coroutineContext[kotlinx.coroutines.Job]) it.cancel() }
                                }
                            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                                throw cancellation
                            } catch (failure: Throwable) {
                                failedClientsUntil[clientFailureKey(videoId, client.key, authScope)] =
                                    System.currentTimeMillis() + CLIENT_COOLDOWN_MS
                                logClientFailure(videoId, client.key, failure)
                                val confirmedReason = failure.confirmedUnavailableReasonOrNull()
                                if (confirmedReason != null) confirmedUnavailableReasons += confirmedReason
                                else transientFailures += "${client.key}: ${failure.message.orEmpty()}"
                            }
                        }

                        if (prioritizedClients.size > 1 && !winnerFound.get()) {
                            delay(HEDGED_CLIENT_STAGGER_DELAY_MS)
                        }
                    }
                    clientJobs.joinAll()
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                transientFailures += "Direct clients: ${failure.message.orEmpty()}"
                logClientFailure(videoId, "DIRECT", failure)
            } finally {
                resolverFinished()
            }
        }

        // 2b. NewPipe racer (parallel with direct clients — NOT primary, just a racer)
        jobs += launch(Dispatchers.IO) {
            var lastFailure: Throwable? = null
            try {
                for (attempt in 0..1) {
                    try {
                        val stream = streamExtractor.resolveAudioStream(videoId)
                        // No pre-return probe: Media3 open validates. Keeps first byte fast.
                        channel.trySend(stream)
                        return@launch
                    } catch (cancellation: kotlinx.coroutines.CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        lastFailure = error
                        if (attempt == 0 && error.confirmedUnavailableReasonOrNull() == null) {
                            // Only a rejected media URL proves the cached
                            // player JS is stale. Wiping it on any transient
                            // error forced a full player-JS re-download (and
                            // Rhino re-parse) for the NEXT song too — the
                            // per-song 10-30s penalty this retry caused.
                            val staleCipherEvidence = error is IOException &&
                                error.message?.contains("rejected media URL") == true
                            if (staleCipherEvidence) streamExtractor.invalidatePlayerState(videoId)
                            delay(NEWPIPE_RETRY_BASE_DELAY_MS + Random.nextLong(NEWPIPE_RETRY_JITTER_MS + 1L))
                        } else {
                            break
                        }
                    }
                }
                val confirmedReason = lastFailure?.confirmedUnavailableReasonOrNull()
                if (confirmedReason != null) confirmedUnavailableReasons += confirmedReason
                else transientFailures += "NewPipe: ${lastFailure?.message.orEmpty()}"
            } finally {
                resolverFinished()
            }
        }

        try {
            // Bounded wait: if neither racer produces a winner in time, fall
            // through to the last-resort stage instead of waiting for every
            // hedged client's retries to exhaust themselves.
            val winner = kotlinx.coroutines.withTimeoutOrNull(CLIENT_RACE_TIMEOUT_MS) {
                channel.receiveCatching().getOrNull()
            }
            if (winner != null) {
                cacheResolvedStream(winner, now)
                lastResolvedStreams[resolutionKey(videoId, authScope)] = winner
                logStreamEvent("resolved", winner)
                logStage(videoId, "race", startedAt)
                winner
            } else {
                val confirmedReason = confirmedUnavailableReasons.firstOrNull()
                if (confirmedReason != null && transientFailures.isEmpty()) {
                    throw ConfirmedUnplayableMediaException(confirmedReason)
                }
                val details = transientFailures.firstOrNull()?.take(160).orEmpty()
                val suffix = details.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
                throw IOException("Unable to resolve a playable audio stream for $videoId$suffix")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is ConfirmedUnplayableMediaException) throw e
            jobs.forEach { it.cancel() }
            // 3. Last resort: NewPipe, bounded so one hung extraction can't run unbounded.
            //    No pre-return probe — Media3 validates on open.
            val npStream = try {
                kotlinx.coroutines.withTimeoutOrNull(NEWPIPE_FALLBACK_TIMEOUT_MS) {
                    streamExtractor.resolveAudioStream(videoId)
                } ?: throw IOException("NewPipe fallback timed out for $videoId")
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (fallbackFailure: Throwable) {
                val fallbackConfirmed = fallbackFailure.confirmedUnavailableReasonOrNull()
                if (fallbackConfirmed != null) confirmedUnavailableReasons += fallbackConfirmed
                else transientFailures += "Fallback NewPipe: ${fallbackFailure.message.orEmpty()}"
                val confirmedReason = confirmedUnavailableReasons.firstOrNull()
                if (confirmedReason != null && transientFailures.isEmpty()) {
                    throw ConfirmedUnplayableMediaException(confirmedReason, fallbackFailure)
                }
                throw IOException("Unable to resolve audio stream for $videoId", fallbackFailure)
            }
            cacheResolvedStream(npStream, now)
            lastResolvedStreams[resolutionKey(videoId, authScope)] = npStream
            logStreamEvent("fallback-resolved", npStream)
            logStage(videoId, "newpipe-fallback", startedAt)
            npStream
        } finally {
            channel.close()
            jobs.forEach { it.cancel() }
            prerequisiteJobs.forEach { it.cancel() }
        }
    }

    private suspend fun resolveDirectClientStream(
        videoId: String,
        client: PlayerClient,
        visitorData: String?,
        signatureTimestamp: Int?,
        playerPoToken: String?,
        gvsPoToken: String?,
        authScope: String,
        probeCandidates: Boolean = true,
        allowCipherFormats: Boolean = true,
    ): YouTubeAudioStream {
        val body = buildJsonObject {
            put("context", buildJsonObject {
                put("client", buildJsonObject {
                    put("clientName", client.name)
                    put("clientVersion", client.version)
                    put("hl", "en")
                    put("gl", "US")
                    if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
                    if (!client.osName.isNullOrBlank()) put("osName", client.osName)
                    if (!client.osVersion.isNullOrBlank()) put("osVersion", client.osVersion)
                    if (!client.deviceMake.isNullOrBlank()) put("deviceMake", client.deviceMake)
                    if (!client.deviceModel.isNullOrBlank()) put("deviceModel", client.deviceModel)
                    if (!client.androidSdkVersion.isNullOrBlank()) {
                        put("androidSdkVersion", client.androidSdkVersion)
                    }
                })
                if (!playerPoToken.isNullOrBlank()) {
                    put("serviceIntegrityDimensions", buildJsonObject {
                        put("poToken", playerPoToken)
                    })
                }
            })
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            if (signatureTimestamp != null) {
                put("playbackContext", buildJsonObject {
                    put("contentPlaybackContext", buildJsonObject {
                        put("signatureTimestamp", signatureTimestamp)
                    })
                })
            }
        }
        val playerApi = if (client.name == "WEB_REMIX") MUSIC_API else YOUTUBE_API
        val root = post(
            url = "$playerApi/player?key=${client.apiKey}&prettyPrint=false",
            body = body,
            clientName = client.name,
            clientVersion = client.version,
            userAgent = client.userAgent,
            authenticated = client.name == "WEB_REMIX" && ytAuth.connection.value.isConnected,
            origin = client.origin,
            referer = client.referer,
            visitorData = visitorData,
            maxAttempts = MAX_PLAYER_REQUEST_ATTEMPTS,
            // Whole-call cap (connect + read + body): the injected client's
            // 15s socket timeouts otherwise let one hung POST stall a hedged
            // racer for 30s+ across its two attempts.
            callTimeoutMs = PLAYER_REQUEST_CALL_TIMEOUT_MS,
        )
        val status = root.obj("playabilityStatus")
        val state = status?.string("status")
        if (state != "OK") {
            val reason = status?.string("reason").orEmpty()
            if (state == "UNPLAYABLE" && reason.isConfirmedUnavailableReason()) {
                throw ConfirmedUnplayableMediaException(reason.ifBlank { state.orEmpty() })
            }
            throw IOException(reason.ifBlank { "Player status ${state ?: "missing"}" })
        }

        val streaming = root.obj("streamingData")
        val streamRequestHeaders = buildMap {
            putAll(client.streamRequestHeaders)
            if (!visitorData.isNullOrBlank()) put("X-Goog-Visitor-Id", visitorData)
            if (client.name == "WEB_REMIX" && ytAuth.connection.value.isConnected) {
                ytAuth.cookieHeaderValue()?.let { put("Cookie", it) }
                ytAuth.authorizationHeaderValue()?.let { put("Authorization", it) }
            }
        }
        val responseExpiry = streaming?.string("expiresInSeconds")
            ?.toLongOrNull()
            ?.let { System.currentTimeMillis() + it.coerceAtLeast(1L) * 1_000L }
        val formats = buildList {
            streaming?.array("formats").orEmpty().forEach { add(it to false) }
            streaming?.array("adaptiveFormats").orEmpty().forEach { add(it to true) }
        }
        val candidates = formats.mapNotNull { (element, isAdaptive) ->
                val format = element as? JsonObject ?: return@mapNotNull null
                val url = format.string("url")
                    ?: if (allowCipherFormats) {
                        (format.string("signatureCipher") ?: format.string("cipher"))
                            ?.let { streamExtractor.decipherStreamUrl(videoId, it) }
                    } else {
                        null
                    }
                    ?: return@mapNotNull null
                val mime = format.string("mimeType")
                if (mime?.startsWith("audio/") != true) return@mapNotNull null
                val finalUrl = appendPoToken(url, gvsPoToken)
                YouTubeAudioStream(
                    videoId = videoId,
                    url = finalUrl,
                    itag = format.int("itag"),
                    mimeType = mime.substringBefore(';'),
                    codec = extractCodec(mime),
                    bitrate = format.int("bitrate") ?: 0,
                    sampleRateHz = format.string("audioSampleRate")?.toIntOrNull(),
                    durationMs = format.string("approxDurationMs")?.toLongOrNull(),
                    contentLength = format.string("contentLength")?.toLongOrNull(),
                    isAdaptive = isAdaptive,
                    clientProfile = client.key,
                    authScope = authScope,
                    requestHeaders = streamRequestHeaders,
                    expiresAtEpochMs = listOfNotNull(streamExpiryEpochMs(finalUrl), responseExpiry).minOrNull()
                        ?: System.currentTimeMillis() + UNKNOWN_STREAM_EXPIRY_TTL_MS,
                )
            }
            .filter(::isCompatibleAudioCandidate)
            // IOS-app formats only serve bounded-Range requests and 403 the
            // open-ended opens ExoPlayer (and plain probes) use — never play
            // them, on any path.
            .filter { !it.clientProfile.startsWith("IOS@") }
            .sortedWith(
                compareBy<YouTubeAudioStream> { it.isAdaptive }
                    .thenByDescending { it.bitrate },
            )
        // Direct fast path: no probeCandidates. Stage 2 still probes defensively for cipher clients.
        if (!probeCandidates) {
            return candidates.firstOrNull()
                ?: throw IOException("${client.key} returned no usable audio URL")
        }
        for (candidate in candidates.take(MAX_FORMAT_PROBES_PER_CLIENT)) {
            if (probeStream(candidate, "client-probe")) return candidate
        }
        throw IOException("${client.key} returned no usable audio URL")
    }

    private fun cacheResolvedStream(stream: YouTubeAudioStream, cachedAt: Long) {
        streamCache[StreamCacheKey.from(stream)] = CachedStream(cachedAt, stream)
        pruneStreamCache()
    }

    private suspend fun probeStream(
        stream: YouTubeAudioStream,
        stage: String,
        retry: Int = 0,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (stream.expiresAtEpochMs != null && stream.expiresAtEpochMs - now <= URL_EXPIRY_MARGIN_MS) {
            logStreamEvent(stage, stream, retry = retry, detail = "expired=true")
            return false
        }
        val isHls = stream.mimeType?.contains("mpegurl", true) == true ||
            stream.url.substringBefore('?').endsWith(".m3u8", true) ||
            stream.codec?.equals("hls", true) == true
        val requestBuilder = Request.Builder()
            .url(stream.url)
            .header("Accept-Encoding", "identity")
            .apply {
                if (!isHls) header("Range", "bytes=0-1")
                stream.requestHeaders.forEach { (name, value) -> header(name, value) }
            }
        val request = requestBuilder.build()
        val call = http.newCall(request)
        call.timeout().timeout(STREAM_PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        val cancellationHandle = currentCoroutineContext()[kotlinx.coroutines.Job]
            ?.invokeOnCompletion { cause ->
                if (cause is kotlinx.coroutines.CancellationException) call.cancel()
            }
        return try {
            call.execute().use { response ->
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                val validType = !contentType.contains("text/html") &&
                    !contentType.contains("application/json") &&
                    !contentType.contains("text/plain")
                val valid = (response.code == 200 || response.code == 206) &&
                    validType && response.body?.source()?.request(1L) == true
                logStreamEvent(
                    stage = stage,
                    stream = stream,
                    httpStatus = response.code,
                    retry = retry,
                    detail = "valid=$valid type=${contentType.substringBefore(';').take(40)}",
                )
                valid
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logStreamEvent(stage, stream, retry = retry, detail = "error=${error::class.java.simpleName}")
            false
        } finally {
            cancellationHandle?.dispose()
        }
    }

    private fun YouTubeAudioStream.isFresh(cachedAt: Long, now: Long): Boolean =
        now - cachedAt < STREAM_TTL_MS &&
            (expiresAtEpochMs == null || expiresAtEpochMs - now > URL_EXPIRY_MARGIN_MS)

    private fun appendPoToken(url: String, token: String?): String {
        if (token.isNullOrBlank()) return url
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (parsed.queryParameter("pot") != null) return url
        val fragmentIndex = url.indexOf('#').takeIf { it >= 0 } ?: url.length
        val base = url.substring(0, fragmentIndex)
        val fragment = url.substring(fragmentIndex)
        val separator = when {
            base.endsWith('?') || base.endsWith('&') -> ""
            '?' in base -> "&"
            else -> "?"
        }
        return "$base${separator}${Uri.encode("pot")}=${Uri.encode(token)}$fragment"
    }

    private fun streamExpiryEpochMs(url: String): Long? = url.toHttpUrlOrNull()
        ?.queryParameter("expire")
        ?.toLongOrNull()
        ?.times(1_000L)

    private fun clientFailureKey(videoId: String, clientKey: String, authScope: String): String =
        "$videoId|$clientKey|$authScope"

    private fun resolutionKey(videoId: String, authScope: String): String = "$videoId|$authScope"

    private fun playbackAuthScope(): String {
        val connection = ytAuth.connection.value
        if (!connection.isConnected) return ANONYMOUS_AUTH_SCOPE
        val cookieDigest = ytAuth.cookieHeaderValue()
            ?.let { cookie ->
                MessageDigest.getInstance("SHA-256")
                    .digest(cookie.toByteArray())
                    .take(8)
                    .joinToString("") { byte ->
                        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                    }
            }
            ?: "none"
        return "account:${connection.connectedAtMillis}:$cookieDigest"
    }

    private fun extractCodec(mimeType: String): String? = CODEC_PATTERN
        .find(mimeType)
        ?.groupValues
        ?.getOrNull(1)
        ?.substringBefore(',')
        ?.trim()

    private fun isCompatibleAudioCandidate(stream: YouTubeAudioStream): Boolean {
        val mime = stream.mimeType.orEmpty().lowercase()
        val codec = stream.codec.orEmpty().lowercase()
        return when {
            mime == "audio/webm" -> codec.isBlank() || codec.contains("opus") || codec.contains("vorbis")
            mime == "audio/mp4" || mime == "audio/m4a" -> codec.isBlank() || codec.contains("mp4a") || codec.contains("aac")
            mime == "audio/ogg" -> codec.isBlank() || codec.contains("opus") || codec.contains("vorbis")
            mime == "audio/mpeg" -> true
            else -> false
        }
    }

    private fun logStreamEvent(
        stage: String,
        stream: YouTubeAudioStream,
        httpStatus: Int? = null,
        retry: Int = 0,
        detail: String? = null,
    ) {
        android.util.Log.i(STREAM_LOG_TAG, "[YOUTUBE] stage=$stage videoId=${stream.videoId} client=${stream.clientProfile} status=$httpStatus retry=$retry detail=$detail")
        val now = System.currentTimeMillis()
        val expiryState = when {
            stream.expiresAtEpochMs == null -> "unknown"
            stream.expiresAtEpochMs <= now -> "expired"
            else -> "fresh"
        }
        android.util.Log.d(
            STREAM_LOG_TAG,
            "stage=$stage videoId=${stream.videoId} client=${stream.clientProfile} " +
                "itag=${stream.itag ?: -1} mime=${stream.mimeType.orEmpty()} " +
                "expiry=$expiryState retry=$retry http=${httpStatus ?: 0} ${detail?.take(80).orEmpty()}",
        )
    }

    private fun logStage(videoId: String, stage: String, startedAt: Long? = null) {
        val elapsed = startedAt?.let { SystemClock.elapsedRealtime() - it }
        android.util.Log.i(STREAM_LOG_TAG, "[YOUTUBE] id=$videoId stage=$stage" + (elapsed?.let { " +${it}ms" } ?: ""))
    }

    private fun logClientFailure(videoId: String, client: String, error: Throwable?) {
        val status = generateSequence(error) { it.cause }
            .filterIsInstance<InnerTubeHttpException>()
            .firstOrNull()
            ?.responseCode ?: 0
        android.util.Log.d(
            STREAM_LOG_TAG,
            "stage=client-resolve-failed videoId=$videoId client=$client itag=-1 " +
                "mime=unknown expiry=unknown retry=0 http=$status error=${error?.javaClass?.simpleName.orEmpty()}",
        )
    }

    private fun playerClients(config: WebConfig): List<PlayerClient> = PLAYER_CLIENTS.map { client ->
        if (client.name == "WEB_REMIX") {
            client.copy(version = config.clientVersion, apiKey = config.apiKey)
        } else {
            client
        }
    }

    private fun Throwable.confirmedUnavailableReasonOrNull(): String? {
        val causes = generateSequence(this) { it.cause }.take(10).toList()
        causes.filterIsInstance<ConfirmedUnplayableMediaException>()
            .firstOrNull()
            ?.message
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        return causes.firstNotNullOfOrNull { cause ->
            cause.message?.takeIf { it.isConfirmedUnavailableReason() }
        }
    }

    private fun String.isConfirmedUnavailableReason(): Boolean {
        val reason = lowercase()
        return CONFIRMED_UNAVAILABLE_REASONS.any(reason::contains)
    }

    /** Keeps the stream cache from growing without bound over long sessions:
     *  drops expired entries first, then trims the oldest inserts. */
    private fun pruneStreamCache() {
        if (streamCache.size <= MAX_STREAM_CACHE_ENTRIES) return
        val now = System.currentTimeMillis()
        streamCache.entries.removeIf { entry ->
            !entry.value.stream.isFresh(entry.value.cachedAtEpochMs, now)
        }
        if (streamCache.size > MAX_STREAM_CACHE_ENTRIES) {
            streamCache.entries
                .sortedBy { it.value.cachedAtEpochMs }
                .take(streamCache.size - MAX_STREAM_CACHE_ENTRIES)
                .forEach { streamCache.remove(it.key, it.value) }
        }
    }

    suspend fun findBestMatch(
        title: String,
        artist: String,
        prefetchStreams: Boolean = true,
        excludedVideoId: String? = null,
        excludedVideoIds: Set<String> = emptySet(),
    ): YouTubeMusicTrack {
        val cleanArtist = artist.takeUnless { it.equals("Unknown artist", ignoreCase = true) }.orEmpty()
        val cacheKey = "${normalize(cleanArtist)}|${normalize(title)}"
        matchCache[cacheKey]?.takeIf { it.videoId != excludedVideoId && it.videoId !in excludedVideoIds }?.let { return it }
        val results = searchSongs(
            query = listOf(title, cleanArtist).filter { it.isNotBlank() }.joinToString(" "),
            limit = 30,
            prefetchStreams = prefetchStreams,
        )
        val validCandidates = results.filter {
            it.videoId.isNotBlank() && it.videoId != excludedVideoId && it.videoId !in excludedVideoIds
        }
        val best = validCandidates.asSequence()
            .filter { candidate ->
                val titleMatch = maxOf(similarity(candidate.title, title), similarity(baseTitle(candidate.title), baseTitle(title))) >= 60
                val artistMatch = cleanArtist.isBlank() || similarity(candidate.artist, cleanArtist) >= 35
                titleMatch && artistMatch
            }
            .maxByOrNull { candidate -> matchScore(candidate, title, cleanArtist) }
            ?: validCandidates.maxByOrNull { candidate -> matchScore(candidate, title, cleanArtist) }
            ?: validCandidates.firstOrNull()
            ?: throw IOException("No reliable YouTube Music match found for $title by $artist")
        return best.also {
            if (matchCache.size > MAX_MATCH_CACHE_ENTRIES) matchCache.clear()
            matchCache[cacheKey] = it
        }
    }

    suspend fun findBestMatchOrNull(
        title: String,
        artist: String,
        prefetchStreams: Boolean = true,
    ): YouTubeMusicTrack? = try {
        findBestMatch(title, artist, prefetchStreams)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    suspend fun isPlayable(title: String, artist: String): Boolean =
        findBestMatchOrNull(title, artist) != null

    /** Warms the cached web config (api key/version/visitor data) so the
     *  first playback's fast path can attach visitor data without an extra
     *  blocking fetch. Safe to call from a startup coroutine; never throws. */
    suspend fun preWarmPlayback() {
        runCatching { getWebConfig() }
    }

    private suspend fun getWebConfig(): WebConfig {
        webConfig?.let { return it }
        return configMutex.withLock {
            webConfig?.let { return@withLock it }
            val config = runCatching { fetchWebConfig() }
                .getOrElse { WebConfig(FALLBACK_WEB_KEY, FALLBACK_WEB_VERSION, null) }
            webConfig = config
            config
        }
    }

    private suspend fun fetchWebConfig(): WebConfig {
        val request = Request.Builder()
            .url("$YOUTUBE_MUSIC_ORIGIN/")
            .header("User-Agent", WEB_USER_AGENT)
            .build()
        val call = http.newCall(request).apply {
            timeout().timeout(CONFIG_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        val (status, html) = call.readResponseBody()
        if (status !in 200..299) throw IOException("YouTube Music config HTTP $status")
        return WebConfig(
            apiKey = findConfig(html, "INNERTUBE_API_KEY") ?: FALLBACK_WEB_KEY,
            clientVersion = findConfig(html, "INNERTUBE_CONTEXT_CLIENT_VERSION") ?: FALLBACK_WEB_VERSION,
            visitorData = findConfig(html, "VISITOR_DATA"),
        )
    }

    private fun findConfig(html: String, key: String): String? {
        if (html.isBlank()) return null
        val escaped = Regex("\\\"$key\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(html)?.groupValues?.getOrNull(1)
        return escaped
            ?.replace("\\u003d", "=")
            ?.replace("\\x3d", "=")
            ?.replace("\\/", "/")
    }

    private suspend fun post(
        url: String,
        body: JsonObject,
        clientName: String,
        clientVersion: String,
        userAgent: String,
        authenticated: Boolean = false,
        origin: String = YOUTUBE_MUSIC_ORIGIN,
        referer: String = "$YOUTUBE_MUSIC_ORIGIN/",
        visitorData: String? = null,
        maxAttempts: Int = 2,
        callTimeoutMs: Long? = null,
        authenticatedAccount: YtConnection? = null,
    ): JsonObject {
        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
            .header("Origin", origin)
            .header("X-Origin", origin)
            .header("Referer", referer)
            .header("X-Goog-Api-Format-Version", "1")
            .header("X-YouTube-Client-Name", CLIENT_IDS[clientName] ?: clientName)
            .header("X-YouTube-Client-Version", clientVersion)
            .apply {
                val (hl, gl) = getEffectiveHlGl()
                header("Accept-Language", "$hl-$gl,$hl;q=0.9,en;q=0.8")
            }

        if (!visitorData.isNullOrBlank()) {
            builder.header("X-Goog-Visitor-Id", visitorData)
        }

        // Account-authenticated surface: cookies + per-request SAPISIDHASH.
        // Only applied when explicitly requested AND a connection exists —
        // anonymous endpoints must stay cookie-free so playback never
        // depends on login state.
        if (authenticated) {
            val account = authenticatedAccount ?: ytAuth.connection.value
            if (authenticatedAccount != null && ytAuth.connection.value != account) {
                throw kotlinx.coroutines.CancellationException("YouTube account changed")
            }
            ytAuth.cookieHeaderValue(account)?.let { builder.header("Cookie", it) }
            ytAuth.authorizationHeaderValue(account = account)?.let { builder.header("Authorization", it) }
            // Brand-channel delegation: same cookies, but YouTube answers as
            // the selected channel instead of the default (first) one.
            // Mirrors music.youtube.com, which sends `pageid=` on its switcher
            // `signin` request and `X-Goog-PageId` per API call afterwards.
            val pageId = account.pageId.takeIf { it.isNotBlank() }
            if (pageId != null) {
                builder.header("X-Goog-PageId", pageId)
                builder.header("X-Goog-AuthUser", (account.authUserIndex ?: 0).toString())
            } else {
                // Multi-login session index: cookies are shared across the
                // session's Google accounts, this flag picks which one answers.
                account.authUserIndex?.let { builder.header("X-Goog-AuthUser", it.toString()) }
            }
        }

        val request = builder
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        var lastException: Exception? = null
        for (attempt in 1..maxAttempts) {
            currentCoroutineContext().ensureActive()
            try {
                val call = http.newCall(request)
                callTimeoutMs?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) }
                val (status, text) = call.readResponseBody()
                if (status !in 200..299) {
                    if (status == 400 || status == 403 || status == 429) webConfig = null
                    throw InnerTubeHttpException(status)
                }
                return try {
                    json.parseToJsonElement(text).jsonObject
                } catch (error: Exception) {
                    throw IOException("Invalid InnerTube response", error)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                if (attempt >= maxAttempts || !e.isTransientRequestFailure()) break
                val exponential = REQUEST_RETRY_BASE_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(3))
                delay(exponential + Random.nextLong(REQUEST_RETRY_JITTER_MS + 1L))
            }
        }
        throw (lastException as? IOException) ?: IOException("InnerTube call failed: ${lastException}")
    }

    private suspend fun okhttp3.Call.readResponseBody(): Pair<Int, String> =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, error: IOException) {
                    continuation.resumeWithException(error)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val result = runCatching {
                        response.use { it.code to it.body?.string().orEmpty() }
                    }
                    continuation.resumeWith(result)
                }
            })
        }

    private fun Exception.isTransientRequestFailure(): Boolean = when (this) {
        // Only HTTP-level transient statuses retry. Broad `IOException -> true`
        // used to retry connection resets/DNS for a full second per call on the
        // interactive path; keep connect timeouts classified as transient below.
        is InnerTubeHttpException -> responseCode == 408 || responseCode == 429 || responseCode in 500..599
        is java.net.SocketTimeoutException, is java.net.ConnectException -> true
        is IOException -> this is java.io.InterruptedIOException
        else -> false
    }

    fun getEffectiveHlGl(): Pair<String, String> {
        val languageTag = runCatching { settingsPreferences.readLanguageTagSync() }.getOrNull()
        val language = AppLanguage.fromTag(languageTag)
        val locale = language.appLocale()
        val hl = when (language) {
            AppLanguage.SYSTEM -> {
                val sysLang = locale.language.ifBlank { "en" }
                val sysCountry = locale.country
                if (sysCountry.isNotBlank() && (sysLang.equals("zh", ignoreCase = true) || sysLang.equals("pt", ignoreCase = true))) {
                    "$sysLang-$sysCountry"
                } else sysLang
            }
            AppLanguage.CHINESE_SIMPLIFIED -> "zh-CN"
            AppLanguage.PORTUGUESE_BRAZIL -> "pt-BR"
            else -> language.tag
        }
        val gl = resolveRegionCode(language, locale)
        return hl to gl
    }

    private fun resolveRegionCode(language: AppLanguage, locale: Locale): String {
        val sysCountry = runCatching { Locale.getDefault().country }.getOrDefault("")
        val localeCountry = locale.country
        if (localeCountry.length == 2 && localeCountry.all { it.isLetter() }) {
            return localeCountry.uppercase()
        }
        return when (language) {
            AppLanguage.TURKISH -> "TR"
            AppLanguage.CHINESE_SIMPLIFIED -> "CN"
            AppLanguage.RUSSIAN -> "RU"
            AppLanguage.PORTUGUESE_BRAZIL -> "BR"
            AppLanguage.SPANISH -> if (sysCountry in LATIN_AMERICA_OR_SPAIN) sysCountry else "ES"
            AppLanguage.INDONESIAN -> "ID"
            AppLanguage.HINDI -> "IN"
            AppLanguage.GERMAN -> if (sysCountry in setOf("AT", "CH", "DE")) sysCountry else "DE"
            AppLanguage.FRENCH -> if (sysCountry in setOf("BE", "CA", "CH", "FR")) sysCountry else "FR"
            AppLanguage.JAPANESE -> "JP"
            AppLanguage.KOREAN -> "KR"
            AppLanguage.ARABIC -> if (sysCountry in ARABIC_COUNTRIES) sysCountry else "SA"
            AppLanguage.ENGLISH -> if (sysCountry in ENGLISH_COUNTRIES) sysCountry else "US"
            AppLanguage.SYSTEM -> if (sysCountry.length == 2 && sysCountry.all { it.isLetter() }) sysCountry.uppercase() else "US"
        }
    }

    private fun context(name: String, version: String, visitorData: String?, osVersion: String? = null): JsonObject {
        val (hl, gl) = getEffectiveHlGl()
        return buildJsonObject {
            put("client", buildJsonObject {
                put("clientName", name)
                put("clientVersion", version)
                put("hl", hl)
                put("gl", gl)
                if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
                if (!osVersion.isNullOrBlank()) put("osVersion", osVersion)
            })
            // Brand-channel delegation: same session cookies, but YouTube
            // renders the selected channel's library/history/likes instead of
            // the default (first) channel's. Mirrors music.youtube.com, which
            // sends this flag when the user picks a channel.
            ytAuth.connection.value.onBehalfOfUser?.takeIf { it.isNotBlank() }?.let { delegate ->
                put("user", buildJsonObject {
                    put("lockedSafetyMode", false)
                    put("onBehalfOfUser", delegate)
                })
            }
        }
    }

    private fun parseSongRenderers(root: JsonElement): List<YouTubeMusicTrack> {
        val songs = mutableListOf<YouTubeMusicTrack>()
        val cardRenderers = mutableListOf<JsonObject>()
        collectObjects(root, "musicCardShelfRenderer", cardRenderers)
        songs.addAll(cardRenderers.mapNotNull(::parseCardShelfSong))

        val renderers = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveListItemRenderer", renderers)
        songs.addAll(renderers.mapNotNull(::parseSong))


        val queueRenderers = mutableListOf<JsonObject>()
        collectObjects(root, "playlistPanelVideoRenderer", queueRenderers)
        songs.addAll(queueRenderers.mapNotNull(::parsePlaylistPanelSong))

        if (songs.isEmpty()) {
            val ytVideos = mutableListOf<JsonObject>()
            collectObjects(root, "playlistVideoRenderer", ytVideos)
            songs.addAll(ytVideos.mapNotNull(::parsePlaylistVideoRenderer))
        }
        return songs.distinctBy { it.videoId }.filter { it.videoId.isNotBlank() }
    }

    /** Home carousels use compact two-row cards. Only cards whose own
     * navigation is a direct watch endpoint are songs; album, artist and
     * playlist cards are deliberately ignored. */
    private fun parseHomeFeedSongs(root: JsonElement): List<YouTubeMusicTrack> {
        val rows = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveListItemRenderer", rows)
        val songs = rows.filter { row ->
            directWatchVideoId(row) != null
        }.mapNotNull(::parseSong).toMutableList()
        val renderers = mutableListOf<JsonObject>()
        collectObjects(root, "musicTwoRowItemRenderer", renderers)
        songs += renderers.mapNotNull(::parseTwoRowSong)
        return songs.distinctBy { it.videoId }.filter { it.videoId.isNotBlank() }
    }

    private fun directWatchVideoId(renderer: JsonObject): String? =
        renderer.obj("playlistItemData")?.string("videoId")
            ?: renderer.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: (renderer.obj("overlay") ?: renderer.obj("thumbnailOverlay"))
                ?.obj("musicItemThumbnailOverlayRenderer")
                ?.obj("content")?.obj("musicPlayButtonRenderer")
                ?.obj("playNavigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: renderer.obj("onTap")?.obj("watchEndpoint")?.string("videoId")
            ?: renderer.array("flexColumns")?.firstOrNull()?.asObject()
                ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")
                ?.array("runs")?.firstOrNull()?.asObject()
                ?.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: renderer.obj("doubleTapCommand")?.obj("watchEndpoint")?.string("videoId")

    private fun parseCardShelfSong(renderer: JsonObject): YouTubeMusicTrack? {
        val videoId = directWatchVideoId(renderer)
            ?: renderer.obj("title")?.array("runs")?.firstOrNull()?.asObject()
                ?.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: renderer.obj("onTap")?.obj("watchEndpoint")?.string("videoId")
            ?: findString(renderer, "videoId")
            ?: return null
        val titleRuns = renderer.obj("title")?.array("runs")
        val title = titleRuns?.joinToString("") { it.asObject()?.string("text").orEmpty() }?.trim()?.takeIf(String::isNotBlank)
            ?: renderer.obj("title")?.string("simpleText")?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val subRuns = renderer.obj("subtitle")?.array("runs")?.mapNotNull { it.asObject() }.orEmpty()
        val artist = subRuns.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("UC") == true
        }?.string("text") ?: subRuns.mapNotNull { it.string("text") }
            .firstOrNull { it.isLikelyArtistDetail() }
            ?: "Unknown artist"
        val album = subRuns.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("MPRE") == true
        }?.string("text")
        val duration = subRuns.mapNotNull { it.string("text") }.firstNotNullOfOrNull(::parseDuration)
        val artwork = extractThumbnailsUrl(renderer)
        return YouTubeMusicTrack(videoId, title, artist, album, artwork, duration)
    }

    private fun parseTwoRowSong(renderer: JsonObject): YouTubeMusicTrack? {
        val titleRuns = renderer.obj("title")?.array("runs")
        val videoId = directWatchVideoId(renderer)
            ?: titleRuns?.firstOrNull()?.asObject()
                ?.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: return null
        val title = titleRuns?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?.trim()?.takeIf(String::isNotBlank)
            ?: renderer.obj("title")?.string("simpleText")?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val details = renderer.obj("subtitle")?.array("runs")
            ?.mapNotNull { it.asObject() }.orEmpty()
        val artist = details.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?.string("browseId")?.startsWith("UC") == true
        }?.string("text") ?: details.mapNotNull { it.string("text") }
            .firstOrNull { it.isLikelyArtistDetail() }
            ?: "Unknown artist"
        val album = details.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?.string("browseId")?.startsWith("MPRE") == true
        }?.string("text")
        val duration = details.mapNotNull { it.string("text") }.firstNotNullOfOrNull(::parseDuration)
        val artwork = extractThumbnailsUrl(renderer)
            ?: (renderer.obj("thumbnailRenderer") ?: renderer.obj("thumbnail"))
                ?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.array("thumbnails")
                ?.lastOrNull()?.asObject()?.string("url")?.let {
                    if (it.startsWith("//")) "https:$it" else it
                }?.highResolutionArtwork()
        return YouTubeMusicTrack(videoId, title, artist, album, artwork, duration)
    }

    private fun parsePlaylistVideoRenderer(renderer: JsonObject): YouTubeMusicTrack? {
        val videoId = renderer.string("videoId") ?: return null
        val title = renderer.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: renderer.obj("title")?.string("simpleText")
            ?: return null
        val artist = renderer.obj("shortBylineText")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
            ?: "Unknown artist"
        val duration = renderer.string("lengthSeconds")?.toIntOrNull()
        val thumbnails = renderer.obj("thumbnail")?.array("thumbnails")
        val artwork = thumbnails?.lastOrNull()?.asObject()?.string("url")?.highResolutionArtwork()
            ?: extractThumbnailsUrl(renderer)
        return YouTubeMusicTrack(videoId, title, artist, null, artwork, duration)
    }

    private fun parsePlaylistPanelSong(renderer: JsonObject): YouTubeMusicTrack? {
        if (renderer.obj("unplayableText") != null) return null
        val videoId = renderer.string("videoId")
            ?: renderer.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: return null
        val title = renderer.obj("title")?.array("runs")
            ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?.trim()?.takeIf(String::isNotBlank)
            ?: renderer.obj("title")?.string("simpleText")?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val detailRuns = (renderer.obj("longBylineText") ?: renderer.obj("shortBylineText"))
            ?.array("runs")?.mapNotNull { it.asObject() }.orEmpty()
        val artist = detailRuns.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?.string("browseId")?.startsWith("UC") == true
        }?.string("text") ?: detailRuns.mapNotNull { it.string("text") }
            .firstOrNull { it.isLikelyArtistDetail() }
            ?: "Unknown artist"
        val album = detailRuns.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?.string("browseId")?.startsWith("MPRE") == true
        }?.string("text")
        val duration = renderer.obj("lengthText")?.array("runs")
            ?.mapNotNull { it.asObject()?.string("text") }
            ?.firstNotNullOfOrNull(::parseDuration)
        val artwork = extractThumbnailsUrl(renderer)
            ?: renderer.obj("thumbnail")?.array("thumbnails")
                ?.lastOrNull()?.asObject()?.string("url")?.let {
                    if (it.startsWith("//")) "https:$it" else it
                }?.highResolutionArtwork()
        return YouTubeMusicTrack(videoId, title, artist, album, artwork, duration)
    }

    private fun parseSong(renderer: JsonObject): YouTubeMusicTrack? {
        val videoId = directWatchVideoId(renderer)
            ?: findString(renderer, "videoId")
            ?: return null
        val columns = renderer.array("flexColumns")
        val titleRuns = columns?.getOrNull(0)?.asObject()
            ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
        val title = titleRuns?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: columns?.getOrNull(0)?.asObject()
                ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.string("simpleText")
                ?.trim()?.takeIf { it.isNotBlank() }
            ?: renderer.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?.trim()?.takeIf { it.isNotBlank() }
            ?: renderer.obj("title")?.string("simpleText")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val detailRuns = (1 until (columns?.size ?: 0)).flatMap { idx ->
            columns?.getOrNull(idx)?.asObject()
                ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
                ?.mapNotNull { it.asObject() }.orEmpty()
        }
        val fixedRuns = renderer.array("fixedColumns")?.flatMap { col ->
            col.asObject()?.obj("musicResponsiveListItemFixedColumnRenderer")?.obj("text")?.array("runs")
                ?.mapNotNull { it.asObject() }.orEmpty()
        }.orEmpty()
        val allDetailRuns = detailRuns + fixedRuns + (renderer.obj("subtitle")?.array("runs")?.mapNotNull { it.asObject() }.orEmpty())

        val artist = allDetailRuns.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("UC") == true
        }?.string("text") ?: allDetailRuns.mapNotNull { it.string("text") }
            .firstOrNull { it.isLikelyArtistDetail() }
            ?: "Unknown artist"
        val album = allDetailRuns.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("MPRE") == true
        }?.string("text")
        val duration = allDetailRuns.mapNotNull { it.string("text") }.firstNotNullOfOrNull(::parseDuration)
            ?: renderer.string("lengthSeconds")?.toIntOrNull()
        val artwork = extractThumbnailsUrl(renderer)
        return YouTubeMusicTrack(videoId, title, artist, album, artwork, duration)
    }

    private fun parseEntityRenderers(root: JsonElement, kind: YouTubeMusicEntityKind): List<YouTubeMusicEntity> {
        val renderers = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveListItemRenderer", renderers)
        return renderers.mapNotNull { renderer -> parseEntity(renderer, kind) }
            .distinctBy { it.browseId }
    }

    private fun parseEntity(renderer: JsonObject, kind: YouTubeMusicEntityKind): YouTubeMusicEntity? {
        val navigation = renderer.obj("navigationEndpoint")?.obj("browseEndpoint") ?: return null
        val browseId = navigation.string("browseId") ?: return null
        if (kind == YouTubeMusicEntityKind.ARTIST && !browseId.startsWith("UC")) return null
        if (kind == YouTubeMusicEntityKind.ALBUM && !browseId.startsWith("MPRE")) return null

        val columns = renderer.array("flexColumns")
        val name = columns?.getOrNull(0)?.asObject()
            ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
            ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?.trim()?.takeIf(String::isNotBlank) ?: return null
        val details = columns?.getOrNull(1)?.asObject()
            ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
            ?.mapNotNull { it.asObject() }.orEmpty()
        val artist = if (kind == YouTubeMusicEntityKind.ALBUM) {
            details.firstOrNull { run ->
                run.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("UC") == true
            }?.string("text")
        } else null
        val subtitle = details.mapNotNull { it.string("text")?.trim() }
            .filter { it.isNotBlank() && it !in setOf("•", "·", "Artist", "Album", "EP", "Single") }
            .joinToString(" · ")
            .trim()
            .takeIf(String::isNotBlank)
        val thumbnails = renderer.obj("thumbnail")?.obj("musicThumbnailRenderer")
            ?.obj("thumbnail")?.array("thumbnails")
        val artwork = thumbnails?.lastOrNull()?.asObject()?.string("url")?.let {
            (if (it.startsWith("//")) "https:$it" else it).highResolutionArtwork()
        }
        return YouTubeMusicEntity(
            kind = kind,
            name = name,
            artist = artist,
            subtitle = subtitle,
            browseId = browseId,
            playlistId = findString(renderer, "playlistId"),
            artworkUrl = artwork,
        )
    }

    private fun parsePlaylistRenderers(root: JsonElement): List<YouTubePlaylistSummary> {
        val renderers = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveListItemRenderer", renderers)
        collectObjects(root, "musicTwoRowItemRenderer", renderers)
        collectObjects(root, "gridPlaylistRenderer", renderers)
        collectObjects(root, "musicGridItemRenderer", renderers)
        collectObjects(root, "playlistRenderer", renderers)
        return renderers.mapNotNull { renderer ->
            val nav = renderer.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?: renderer.obj("title")?.array("runs")?.firstOrNull()?.asObject()?.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?: renderer.obj("thumbnailOverlay")?.obj("musicItemThumbnailOverlayRenderer")?.obj("content")?.obj("musicPlayButtonRenderer")?.obj("playNavigationEndpoint")?.obj("watchEndpoint")
                ?: renderer.obj("onTap")?.obj("browseEndpoint")
            val browseId = nav?.string("browseId") ?: nav?.string("playlistId") ?: return@mapNotNull null
            val playlistId = if (browseId.startsWith("VL")) browseId.removePrefix("VL") else browseId

            val title = renderer.array("flexColumns")?.getOrNull(0)?.asObject()
                ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?: renderer.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?: renderer.obj("title")?.string("simpleText")
                ?: return@mapNotNull null

            val subtitleRuns = renderer.array("flexColumns")?.getOrNull(1)?.asObject()
                ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
                ?: renderer.obj("subtitle")?.array("runs")
            val author = subtitleRuns?.firstOrNull {
                it.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("UC") == true
            }?.string("text") ?: subtitleRuns?.firstOrNull()?.string("text")
                ?.takeUnless { it.equals("Playlist", ignoreCase = true) }

            val trackCountText = subtitleRuns?.mapNotNull { it.asObject()?.string("text") }?.lastOrNull { "song" in it.lowercase() || "track" in it.lowercase() }

            val artwork = extractThumbnailsUrl(renderer)

            YouTubePlaylistSummary(
                id = playlistId,
                title = title.trim(),
                author = author?.trim(),
                trackCountText = trackCountText,
                artworkUrl = artwork,
            )
        }.distinctBy { it.id }
    }

    private fun extractThumbnailsUrl(renderer: JsonElement): String? {
        val foundArrays = mutableListOf<JsonArray>()
        fun findThumbnails(el: JsonElement) {
            when (el) {
                is JsonObject -> {
                    el.array("thumbnails")?.takeIf { it.isNotEmpty() }?.let { foundArrays += it }
                    el.values.forEach { findThumbnails(it) }
                }
                is JsonArray -> el.forEach { findThumbnails(it) }
                else -> Unit
            }
        }
        val thumbnailNode = renderer.asObject()?.let {
            it.obj("thumbnail") ?: it.obj("thumbnailRenderer") ?: it
        } ?: renderer
        findThumbnails(thumbnailNode)
        val bestArray = foundArrays.firstOrNull { it.isNotEmpty() } ?: return null
        val bestUrl = bestArray.lastOrNull()?.asObject()?.string("url")
            ?: bestArray.firstOrNull()?.asObject()?.string("url")
            ?: return null
        val formatted = if (bestUrl.startsWith("//")) "https:$bestUrl" else bestUrl
        return formatted.highResolutionArtwork()
    }

    private fun collectObjects(element: JsonElement, key: String, output: MutableList<JsonObject>) {
        when (element) {
            is JsonObject -> element.forEach { (name, child) ->
                if (name == key && child is JsonObject) output += child
                collectObjects(child, key, output)
            }
            is JsonArray -> element.forEach { collectObjects(it, key, output) }
            else -> Unit
        }
    }

    private fun findString(element: JsonElement, key: String): String? = when (element) {
        is JsonObject -> {
            (element[key] as? JsonPrimitive)?.contentOrNull
                ?: element.values.firstNotNullOfOrNull { findString(it, key) }
        }
        is JsonArray -> element.firstNotNullOfOrNull { findString(it, key) }
        else -> null
    }

    private fun String.isUsefulDetail(): Boolean =
        trim().isNotBlank() && trim() !in setOf("•", "·", "Song", "Video")

    private fun String.isLikelyArtistDetail(): Boolean {
        val value = trim()
        if (!value.isUsefulDetail()) return false
        if (value.equals("Album", true) || value.equals("Single", true) ||
            value.equals("EP", true) || value.equals("Playlist", true)
        ) return false
        if (parseDuration(value) != null || value.matches(Regex("^(19|20)\\d{2}$"))) return false
        if (value.contains(" view", ignoreCase = true) ||
            value.contains(" views", ignoreCase = true) ||
            value.contains(" song", ignoreCase = true) ||
            value.contains(" play", ignoreCase = true) ||
            value.contains(" plays", ignoreCase = true) ||
            value.contains(" stream", ignoreCase = true) ||
            value.contains(" track", ignoreCase = true)
        ) return false
        if (com.lastwave.app.util.ArtistHelper.isPlayCountOrStat(value)) return false
        return true
    }

    private fun parseDuration(value: String): Int? {
        val parts = value.trim().split(':').mapNotNull(String::toIntOrNull)
        if (parts.size !in 2..3) return null
        return parts.fold(0) { total, part -> total * 60 + part }
    }

    private fun similarity(a: String, b: String): Int = TextMatch.similarity(a, b)

    private fun matchScore(candidate: YouTubeMusicTrack, title: String, artist: String): Int =
        TextMatch.matchScore(candidate, title, artist)

    private fun tokens(value: String): Set<String> = TextMatch.tokens(value)

    private fun baseTitle(value: String): String = TextMatch.baseTitle(value)

    private fun String.highResolutionArtwork(): String {
        val url = if (startsWith("//")) "https:$this" else this
        return when {
            (url.contains("googleusercontent.com") || url.contains("ggpht.com")) && '=' in url ->
                url.substringBeforeLast('=') + "=w512-h512-l90-rj"
            else -> url
        }
    }

    private fun normalize(value: String): String = TextMatch.normalize(value)

    private data class SharedStreamRequest(
        val deferred: Deferred<YouTubeAudioStream>,
        val waiters: AtomicInteger = AtomicInteger(0),
    )

    private data class CachedStream(
        val cachedAtEpochMs: Long,
        val stream: YouTubeAudioStream,
    )

    private data class StreamCacheKey(
        val videoId: String,
        val clientProfile: String,
        val itag: Int?,
        val authScope: String,
        val expiresAtEpochMs: Long?,
    ) {
        fun matches(stream: YouTubeAudioStream): Boolean =
            clientProfile == stream.clientProfile &&
                itag == stream.itag &&
                authScope == stream.authScope &&
                expiresAtEpochMs == stream.expiresAtEpochMs

        companion object {
            fun from(stream: YouTubeAudioStream) = StreamCacheKey(
                videoId = stream.videoId,
                clientProfile = stream.clientProfile,
                itag = stream.itag,
                authScope = stream.authScope,
                expiresAtEpochMs = stream.expiresAtEpochMs,
            )
        }
    }

    /** Visible to the history-sync manager so it can tell auth failures
     *  (drop, never retry) apart from transient ones (bounded retry). */
    internal class InnerTubeHttpException(val responseCode: Int) :
        IOException("InnerTube HTTP $responseCode")

    private data class WebConfig(val apiKey: String, val clientVersion: String, val visitorData: String?)

    private data class PlayerClient(
        val name: String,
        val version: String,
        val apiKey: String,
        val userAgent: String,
        val osName: String? = null,
        val osVersion: String? = null,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val androidSdkVersion: String? = null,
    ) {
        val key = "$name@$version"
        /** Web clients must present a signatureTimestamp in the player
         *  request; app/TV clients resolve fine without one. */
        val needsSignatureTimestamp: Boolean
            get() = name == "WEB_REMIX" || name == "WEB_EMBEDDED_PLAYER" || name == "MWEB"
        val origin = if (name == "WEB_REMIX") YOUTUBE_MUSIC_ORIGIN else YOUTUBE_ORIGIN
        val referer = when (name) {
            "WEB_REMIX" -> "$YOUTUBE_MUSIC_ORIGIN/"
            "TVHTML5", "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> "$YOUTUBE_ORIGIN/tv"
            "WEB_EMBEDDED_PLAYER" -> "$YOUTUBE_ORIGIN/embed"
            else -> "$YOUTUBE_ORIGIN/"
        }
        val streamRequestHeaders = buildMap {
            put("User-Agent", userAgent)
            put("X-YouTube-Client-Name", CLIENT_IDS[name] ?: name)
            put("X-YouTube-Client-Version", version)
            if (name == "WEB_REMIX" || name == "TVHTML5" || name == "TVHTML5_SIMPLY_EMBEDDED_PLAYER" || name == "WEB_EMBEDDED_PLAYER" || name == "MWEB") {
                put("Origin", origin)
                put("Referer", referer)
            }
        }
    }

    private companion object {
        val LATIN_AMERICA_OR_SPAIN = setOf("ES", "MX", "AR", "CO", "CL", "PE", "VE", "EC", "GT", "CU", "BO", "DO", "HN", "PY", "SV", "NI", "CR", "PR", "PA", "UY")
        val ARABIC_COUNTRIES = setOf("SA", "EG", "AE", "IQ", "MA", "DZ", "SD", "YE", "SY", "TN", "JO", "LY", "LB", "OM", "KW", "QA", "BH")
        val ENGLISH_COUNTRIES = setOf("US", "GB", "CA", "AU", "NZ", "IE", "ZA")
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val CLIENT_IDS = mapOf(
            "WEB_REMIX" to "67",
            "IOS" to "5",
            "IOS_MUSIC" to "26",
            "IOS_CREATOR" to "15",
            "ANDROID" to "3",
            "ANDROID_MUSIC" to "21",
            "ANDROID_VR" to "28",
            "ANDROID_TESTSUITE" to "30",
            "ANDROID_CREATOR" to "14",
            "TVHTML5" to "7",
            "TVHTML5_SIMPLY_EMBEDDED_PLAYER" to "85",
            "VISIONOS" to "101",
            "WEB_EMBEDDED_PLAYER" to "56",
            "MWEB" to "62",
        )
        const val YOUTUBE_MUSIC_ORIGIN = "https://music.youtube.com"
        const val YOUTUBE_ORIGIN = "https://www.youtube.com"
        const val MUSIC_API = "https://music.youtube.com/youtubei/v1"
        const val YOUTUBE_API = "https://www.youtube.com/youtubei/v1"
        const val LIBRARY_PLAYLISTS_BROWSE_ID = "FEmusic_liked_playlists"
        const val YT_HISTORY_BROWSE_ID = "FEmusic_history"
        const val YT_LIKED_BROWSE_ID = "VLLM"
        const val YT_HOME_BROWSE_ID = "FEmusic_home"
        const val YT_NEW_RELEASES_BROWSE_ID = "FEmusic_new_releases"
        const val YT_CHARTS_BROWSE_ID = "FEmusic_charts"
        const val MAX_CONTINUATION_PAGES = 600
        const val WRITE_ACTIONS_PER_REQUEST = 50

        const val HEDGED_CLIENT_STAGGER_DELAY_MS = 300L
        /** Per-client bound for the direct-URL fast path (single POST, no extras). */
        const val DIRECT_FAST_CLIENT_TIMEOUT_MS = 2_500L
        /** Stagger between hedged fast-path clients; first success wins. */
        const val DIRECT_FAST_STAGGER_MS = 250L
        /** Whole fast path must settle inside this budget before heavier stages run. */
        const val DIRECT_FAST_PATH_BUDGET_MS = 4_000L
        /** Whole-call cap for one player-API POST (connect + read + body). */
        const val PLAYER_REQUEST_CALL_TIMEOUT_MS = 6_000L
        /** Max wait on the direct-client/NewPipe race before the last-resort stage. */
        const val CLIENT_RACE_TIMEOUT_MS = 8_000L
        /** Hard cap for one full stream resolution; callers retry with fresh state. */
        const val STREAM_RESOLVE_TOTAL_TIMEOUT_MS = 12_000L
        /** Bound for the InnerTubeX cipher stage so a hung resolve fails fast. */
        const val INNERTUBEX_STAGE_TIMEOUT_MS = 4_000L
        /** Bound for the last-resort NewPipe stage. */
        const val NEWPIPE_FALLBACK_TIMEOUT_MS = 6_000L
        /** Overall budget for promoting a staged YouTube stream from MusicPlayer. */
        const val YOUTUBE_PROMOTE_BUDGET_MS = 12_000L
        /** No-cipher clients tried first, in order (mirrors limusic's fallback order). */
        val DIRECT_FAST_CLIENT_ORDER = listOf("VISIONOS", "ANDROID_VR", "TVHTML5")
        const val MAX_FORMAT_PROBES_PER_CLIENT = 2
        const val MAX_PLAYER_REQUEST_ATTEMPTS = 2
        const val CONFIG_REQUEST_TIMEOUT_MS = 4_000L
        const val RELATED_REQUEST_TIMEOUT_MS = 8_000L
        const val ACCOUNT_SWITCHER_TIMEOUT_MS = 15_000L
        /** Per-probe socket timeout for the 0-1 byte stream validity check. */
        const val STREAM_PROBE_TIMEOUT_MS = 4_000L
        const val HISTORY_PING_TIMEOUT_MS = 15_000L
        const val URL_EXPIRY_MARGIN_MS = 2 * 60 * 1000L
        const val REQUEST_RETRY_BASE_DELAY_MS = 250L
        const val REQUEST_RETRY_JITTER_MS = 180L
        const val NEWPIPE_RETRY_BASE_DELAY_MS = 300L
        const val NEWPIPE_RETRY_JITTER_MS = 220L
        const val UNKNOWN_STREAM_EXPIRY_TTL_MS = 5 * 60 * 1000L

        val CONFIRMED_UNAVAILABLE_REASONS = listOf(
            "video has been removed",
            "video has been deleted",
            "video was removed",
            "video was deleted",
            "this video is private",
            "this is a private video",
            "not available in your country",
            "blocked in your country",
            "copyright claim",
        )

        /** How long a failed player client is skipped by the racer. */
        const val CLIENT_COOLDOWN_MS = 60_000L

        /** Hard cap so long sessions can't grow the caches without bound. */
        const val MAX_STREAM_CACHE_ENTRIES = 64
        const val STREAM_TTL_MS = 4 * 60 * 60 * 1000L
        const val MAX_MATCH_CACHE_ENTRIES = 1024
        const val FALLBACK_TOKEN_SESSION = "lastwave_session"
        const val NEWPIPE_SOURCE = "NEWPIPE"
        const val ANONYMOUS_AUTH_SCOPE = "anonymous"
        const val STREAM_LOG_TAG = "LastWaveStream"
        const val PLAYLIST_LOG_TAG = "LastWavePlaylist"
        const val WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
        const val FALLBACK_WEB_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
        const val FALLBACK_WEB_VERSION = "1.20260707.12.00"
        const val ARTIST_SEARCH_FILTER = "EgWKAQIgAWoKEAkQBRAKEAMQBA=="
        const val ALBUM_SEARCH_FILTER = "EgWKAQIYAWoKEAkQBRAKEAMQBA=="
        val CODEC_PATTERN = Regex("""codecs?=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
        val PLAYER_CLIENTS = listOf(
            PlayerClient(
                name = "ANDROID_VR",
                version = "1.65.10",
                apiKey = "AIzaSyD-p045F_WzU-vA_YgX20SCx4KAo",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                osName = "Android",
                osVersion = "12",
                deviceMake = "Oculus",
                deviceModel = "Quest 3",
                androidSdkVersion = "32",
            ),
            PlayerClient(
                name = "VISIONOS",
                version = "0.1",
                apiKey = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
                userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
                osName = "visionOS",
                osVersion = "1.3.21O771",
                deviceMake = "Apple",
                deviceModel = "RealityDevice14,1",
            ),
            PlayerClient(
                name = "TVHTML5",
                version = "7.20260308.08.00",
                apiKey = "AIzaSyAO_FJ2SlqAz8GlBg1fA54p0wDE7Xk80mU",
                userAgent = "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/4.0 Chrome/76.0.3809.146 TV Safari/537.36",
            ),
            PlayerClient(
                name = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                version = "2.0",
                apiKey = "AIzaSyAO_FJ2SlqAz8GlBg1fA54p0wDE7Xk80mU",
                userAgent = "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/4.0 Chrome/76.0.3809.146 TV Safari/537.36",
            ),
            PlayerClient(
                name = "ANDROID_TESTSUITE",
                version = "1.9",
                apiKey = "AIzaSyD-p045F_WzU-vA_YgX20SCx4KAo",
                userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 12) gzip",
                osName = "Android",
                osVersion = "12",
            ),
            PlayerClient(
                name = "IOS_MUSIC",
                version = "7.27.0",
                apiKey = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
                userAgent = "com.google.ios.youtubemusic/7.27.0 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)",
                osName = "iOS",
                osVersion = "17.5.1.21F90",
                deviceMake = "Apple",
                deviceModel = "iPhone16,2",
            ),
            PlayerClient(
                name = "ANDROID_MUSIC",
                version = "7.27.52",
                apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
                userAgent = "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 14; en_US; Pixel 8; Build/UD1A.230803.041) gzip",
                osName = "Android",
                osVersion = "14",
                deviceMake = "Google",
                deviceModel = "Pixel 8",
                androidSdkVersion = "34",
            ),
            PlayerClient(
                name = "IOS",
                version = "21.26.4",
                apiKey = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
                userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2;)",
                osName = "iPhone",
                osVersion = "18.3.2.22D82",
                deviceMake = "Apple",
                deviceModel = "iPhone16,2",
            ),
            PlayerClient(
                name = "ANDROID",
                version = "21.26.364",
                apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
                userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
                osName = "Android",
                osVersion = "11",
            ),
            PlayerClient(
                name = "ANDROID_CREATOR",
                version = "24.32.100",
                apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
                userAgent = "com.google.android.apps.youtube.creator/24.32.100 (Linux; U; Android 13; en_US) gzip",
                osName = "Android",
                osVersion = "13",
            ),
            PlayerClient(
                name = "IOS_CREATOR",
                version = "24.32.100",
                apiKey = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
                userAgent = "com.google.ios.creator/24.32.100 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)",
                osName = "iOS",
                osVersion = "17.5.1.21F90",
            ),
            PlayerClient(
                name = "WEB_REMIX",
                version = FALLBACK_WEB_VERSION,
                apiKey = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30",
                userAgent = WEB_USER_AGENT,
            ),
            PlayerClient(
                name = "WEB_EMBEDDED_PLAYER",
                version = FALLBACK_WEB_VERSION,
                apiKey = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30",
                userAgent = WEB_USER_AGENT,
            ),
            PlayerClient(
                name = "MWEB",
                version = "2.20260707.01.00",
                apiKey = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30",
                userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
            ),
        )
    }
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()
private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
private fun JsonElement.obj(key: String): JsonObject? = (this as? JsonObject)?.obj(key)
private fun JsonElement.array(key: String): JsonArray? = (this as? JsonObject)?.array(key)
private fun JsonElement.string(key: String): String? = (this as? JsonObject)?.string(key)
private fun JsonElement.int(key: String): Int? = (this as? JsonObject)?.int(key)
