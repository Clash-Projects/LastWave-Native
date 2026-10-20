package com.lastwave.app.data.music

import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import com.lastwave.app.data.local.AppLanguage
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.local.appLocale
import com.lastwave.app.data.generate.normalizeTrackText
import com.lastwave.app.data.podcast.PodcastEpisode
import com.lastwave.app.data.podcast.PodcastEpisodePage
import com.lastwave.app.data.podcast.PodcastParser
import com.lastwave.app.data.podcast.PodcastShelf
import com.lastwave.app.data.podcast.PodcastShowPage
import java.util.Locale
import com.lastwave.app.data.music.potoken.BotGuardTokenGenerator
import com.lastwave.app.data.music.potoken.PoTokenResult
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

/**
 * Collapses the same song appearing twice in a fetched playlist. A YouTube
 * playlist can legitimately repeat a track under two uploads (different
 * videoIds), and an account-authenticated browse can hand back the same song
 * under two renderers; both surface as two identical title+artist rows in
 * every playlist detail, which reads as a duplication bug. Keeps the first
 * occurrence per videoId and per normalized title+artist.
 */
internal fun List<YouTubeMusicTrack>.distinctPlaylistSongs(): List<YouTubeMusicTrack> {
    if (size < 2) return this
    val seenVideoIds = mutableSetOf<String>()
    val seenSongs = mutableSetOf<String>()
    val out = ArrayList<YouTubeMusicTrack>(size)
    for (track in this) {
        val songKey = "${track.title.normalizeTrackText()}|${track.artist.normalizeTrackText()}".lowercase()
        if (!seenVideoIds.add(track.videoId) || !seenSongs.add(songKey)) continue
        out += track
    }
    return out
}

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

/** Rate endpoints: like, and clear a like (the web player's pair). */
internal const val LIKE_ENDPOINT = "like/like"
internal const val REMOVE_LIKE_ENDPOINT = "like/removelike"

/** `{"videoId": …}` — the rate target shared by like and remove-like. */
internal fun likeTargetBody(videoId: String): kotlinx.serialization.json.JsonObject =
    buildJsonObject {
        put("videoId", videoId)
    }

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
    /**
     * False when a continuation page failed to load (e.g. a 429) and [items] is
     * therefore a truncated view of the real playlist. Callers that diff against
     * the remote must skip the merge when this is false, otherwise present
     * tracks look "removed remotely" and get deleted.
     */
    val complete: Boolean = true,
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
    @Volatile
    private var podcastEpisodesFilter: String? = null
    private val apiScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + containedAudioResolutionFailure(STREAM_LOG_TAG),
    )
    private val failedClientsUntil = ConcurrentHashMap<String, Long>()
    private val lastResolvedStreams = ConcurrentHashMap<String, YouTubeAudioStream>()
    @Volatile private var lastSuccessfulClientName: String? = null
    @Volatile private var webConfig: WebConfig? = null
    /** Last wall-clock attempt to (re)bootstrap visitor data: the fast path
     *  must not re-fetch on every song when the config endpoint is down. */
    @Volatile private var lastVisitorBootstrapAttemptMs = 0L
    /** Last wall-clock attempt to fetch the web config from the network.
     *  Unlike [lastVisitorBootstrapAttemptMs] this also paces re-fetches
     *  after a synthetic-visitor config was cached, so one early scrape
     *  miss cannot pin the placeholder for the whole session. */
    @Volatile private var lastConfigFetchAttemptMs = 0L

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
        val isLiked = com.lastwave.app.data.playlist.PlaylistImportManager.isYtLikedId(rawId)
        val header = playlistHeader(root)
        val title = extractTitleFromHeader(header, root)
            ?: if (isLiked) "Liked Music" else null

        val author = header?.obj("subtitle")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
            ?: header?.obj("straplineTextOne")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
            ?: findFirstAuthor(header)
            ?: if (isLiked) "YouTube Music" else null

        val artworkUrl = extractArtworkFromHeader(header, root)

        val trackLimit = maxTracks?.coerceAtLeast(1)
        val songs = mutableListOf<YouTubeMusicTrack>()
        val isNextResponse = (root as? JsonObject)?.obj("contents")?.obj("singleColumnMusicWatchNextResultsRenderer") != null ||
            (root as? JsonObject)?.obj("currentVideoEndpoint") != null
        val playlistPage = !isNextResponse && (browseId.startsWith("VL") || browseId.startsWith("FE") || browseId.startsWith("PL") || isLiked)
        fun trackContainers(page: JsonElement): List<JsonElement> =
            if (playlistPage) playlistTrackContainers(page) else listOf(page)
        val initialContainers = trackContainers(root)
        val initialSongs = if (initialContainers.isNotEmpty()) {
            initialContainers.flatMap(::parseSongRenderers).ifEmpty { parseSongRenderers(root) }
        } else {
            parseSongRenderers(root)
        }.distinctBy { it.videoId }.let { parsed ->
            trackLimit?.let { parsed.take(it) } ?: parsed
        }
        if (initialSongs.isEmpty() && initialContainers.isEmpty()) {
            if (header != null) {
                return@withContext YouTubePlaylistResult(
                    id = rawId,
                    title = title ?: if (isLiked) "Liked Music" else "YouTube Playlist",
                    author = author,
                    artworkUrl = artworkUrl,
                    trackCount = 0,
                    tracks = emptyList(),
                    isComplete = true,
                )
            }
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
            if (playlistPage) {
                containers.firstNotNullOfOrNull(::playlistTrackContinuationToken)
            } else {
                containers.firstNotNullOfOrNull(::genericContinuationToken) ?: genericContinuationToken(pageJson)
            }
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
        // Zero tracks means nothing usable loaded (root error page, private /
        // deleted playlist, or blocked request) — keep the null contract so
        // callers fall back to cached data / error UI instead of an empty list.
        if (songs.isEmpty()) {
            android.util.Log.w(PLAYLIST_LOG_TAG, "playlist-no-tracks browseId=$browseId")
            return@withContext null
        }
        // Diagnostic: the same song arriving under two videoIds renders as
        // two identical rows in every playlist detail (the display layer
        // collapses them, but the upstream shape stays visible here).
        val sameSongTwice = songs
            .groupBy { "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}" }
            .filter { it.value.size > 1 }
        if (sameSongTwice.isNotEmpty()) {
            android.util.Log.w(
                PLAYLIST_LOG_TAG,
                "playlist-same-song-two-videoIds browseId=$browseId " +
                    sameSongTwice.values.flatten()
                        .joinToString { "${it.title}|${it.artist}|${it.videoId}" },
            )
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
        val isLiked = com.lastwave.app.data.playlist.PlaylistImportManager.isYtLikedId(browseId)
        if (ytAuth.connection.value.isConnected) {
            runCatching { browseRoot(browseId, authenticated = true) }.getOrNull()?.let {
                if (hasPlaylistContent(it)) return it to true
            }
            if (isLiked) {
                runCatching { browseRoot("FEmusic_liked_videos", authenticated = true) }.getOrNull()?.let {
                    if (hasPlaylistContent(it)) return it to true
                }
                if (browseId != "LM") {
                    runCatching { browseRoot("LM", authenticated = true) }.getOrNull()?.let {
                        if (hasPlaylistContent(it)) return it to true
                    }
                }
            }
        }
        runCatching { browseRoot(browseId, authenticated = false) }.getOrNull()?.let {
            if (hasPlaylistContent(it)) return it to false
        }
        // RDCLAK / RD-prefixed IDs (radio/mix) or Liked Music fall back to the "next" endpoint
        if (browseId.startsWith("RDCLAK") || browseId.startsWith("RD") || isLiked) {
            val playlistId = if (isLiked) "LM" else browseId.removePrefix("VL")
            val auth = ytAuth.connection.value.isConnected
            runCatching { fetchPlaylistViaNext(playlistId, authenticated = auth) }.getOrNull()?.let {
                return it to auth
            }
        }
        return null
    }

    private fun hasPlaylistContent(root: JsonElement): Boolean =
        parseSongRenderers(root).isNotEmpty() || playlistHeader(root) != null || playlistTrackContainers(root).isNotEmpty()

    /**
     * Resolves a playlist (RDCLAK…, RD… radio/mix or Liked Music fallback)
     * through the `next` endpoint when browse fails or returns no tracks.
     */
    private suspend fun fetchPlaylistViaNext(playlistId: String, authenticated: Boolean = false): JsonElement? {
        val config = getWebConfig()
        val root = post(
            url = "$MUSIC_API/next?key=${config.apiKey}&prettyPrint=false",
            body = buildJsonObject {
                put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                put("playlistId", playlistId)
                put("isAudioOnly", true)
            },
            clientName = "WEB_REMIX",
            clientVersion = config.clientVersion,
            userAgent = WEB_USER_AGENT,
            authenticated = authenticated,
            callTimeoutMs = RELATED_REQUEST_TIMEOUT_MS,
        )
        // Sanity-check: the response must contain at least one track renderer.
        val hasTracks = parseSongRenderers(root).isNotEmpty()
        return if (hasTracks) root else null
    }

    /**
     * Plain lyrics from a video's YouTube Music "Lyrics" tab, when it has
     * one: `next` names the tab's browse endpoint, `browse` answers with a
     * description shelf holding the text.
     */
    suspend fun fetchLyricsTabText(videoId: String): String? {
        val id = videoId.trim()
        if (!VIDEO_ID_PATTERN.matches(id)) return null
        val config = getWebConfig()
        val next = post(
            url = "$MUSIC_API/next?key=${config.apiKey}&prettyPrint=false",
            body = buildJsonObject {
                put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                put("videoId", id)
                put("isAudioOnly", true)
            },
            clientName = "WEB_REMIX",
            clientVersion = config.clientVersion,
            userAgent = WEB_USER_AGENT,
            callTimeoutMs = RELATED_REQUEST_TIMEOUT_MS,
        )
        val tabs = mutableListOf<JsonObject>()
        collectObjects(next, "tabRenderer", tabs)
        val lyricsTab = tabs.firstOrNull { tab ->
            val title = tab.obj("title")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?.takeIf { it.isNotBlank() }
                ?: tab.string("title")
            title?.contains("lyrics", ignoreCase = true) == true
        }
        // A localized page names the tab in its own language: fall back to
        // whatever tab follows the first one and offers a browse endpoint.
        val endpoint = lyricsTab?.let(::firstBrowseEndpoint)
            ?: tabs.drop(1).firstNotNullOfOrNull(::firstBrowseEndpoint)
            ?: return null
        val browseId = endpoint.string("browseId")?.takeIf { it.isNotBlank() } ?: return null
        val params = endpoint.string("params")
        val page = post(
            url = "$MUSIC_API/browse?key=${config.apiKey}&prettyPrint=false",
            body = buildJsonObject {
                put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                put("browseId", browseId)
                params?.takeIf { it.isNotBlank() }?.let { put("params", it) }
            },
            clientName = "WEB_REMIX",
            clientVersion = config.clientVersion,
            userAgent = WEB_USER_AGENT,
            callTimeoutMs = RELATED_REQUEST_TIMEOUT_MS,
        )
        val shelves = mutableListOf<JsonObject>()
        collectObjects(page, "musicDescriptionShelfRenderer", shelves)
        return shelves.firstNotNullOfOrNull { shelf ->
            youtubeText(shelf["description"])?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * The video's timed captions, in start order (the transcript panel's
     * cues). `get_transcript` takes one tiny protobuf param: field 1,
     * length, video id.
     */
    suspend fun fetchTranscriptCues(videoId: String): List<Pair<Long, String>>? {
        val id = videoId.trim()
        if (!VIDEO_ID_PATTERN.matches(id)) return null
        val config = getWebConfig()
        val idBytes = id.toByteArray(Charsets.UTF_8)
        val params = Base64.encodeToString(
            byteArrayOf(0x0A, idBytes.size.toByte()) + idBytes,
            Base64.NO_WRAP,
        )
        val response = post(
            url = "$MUSIC_API/get_transcript?key=${config.apiKey}&prettyPrint=false",
            body = buildJsonObject {
                put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                put("params", params)
            },
            clientName = "WEB_REMIX",
            clientVersion = config.clientVersion,
            userAgent = WEB_USER_AGENT,
            callTimeoutMs = RELATED_REQUEST_TIMEOUT_MS,
        )
        val cues = mutableListOf<JsonObject>()
        collectObjects(response, "transcriptCueRenderer", cues)
        val lines = cues.mapNotNull { cue ->
            val startMs = (cue["startOffsetMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?: return@mapNotNull null
            val text = youtubeText(cue["cue"])?.trim(' ', '\n', '\t', '♪').orEmpty()
            text.takeIf { it.isNotEmpty() }?.let { startMs to it }
        }.sortedBy { it.first }
        return lines.takeIf { it.isNotEmpty() }
    }

    private fun firstBrowseEndpoint(tab: JsonObject): JsonObject? {
        val endpoints = mutableListOf<JsonObject>()
        collectObjects(tab, "browseEndpoint", endpoints)
        return endpoints.firstOrNull()
    }

    /** The text of a YouTube renderer: `runs`, `simpleText`, or a plain string. */
    private fun youtubeText(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonPrimitive -> element.contentOrNull
        is JsonArray -> element.mapNotNull(::youtubeText).joinToString("").takeIf { it.isNotBlank() }
        is JsonObject -> {
            val direct = (element["text"] as? JsonPrimitive)?.contentOrNull
                ?: (element["simpleText"] as? JsonPrimitive)?.contentOrNull
            direct ?: element.values.mapNotNull(::youtubeText).joinToString("").takeIf { it.isNotBlank() }
        }
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
                runCatching {
                    val root = runCatching { browseRoot(YT_LIKED_BROWSE_ID, authenticated = true) }
                        .getOrNull()?.takeIf { parseSongRenderers(it).isNotEmpty() }
                        ?: browseRoot("FEmusic_liked_videos", authenticated = true)
                    parseSongRenderers(root)
                }
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
        }.onFailure {
            android.util.Log.w(PLAYLIST_LOG_TAG, "createRemotePlaylist failed for \"$cleanTitle\"", it)
        }.getOrNull() ?: return@withContext null
        root.string("playlistId")?.takeIf { it.isNotBlank() }
            ?: findString(root, "playlistId")?.takeIf { it.isNotBlank() }
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

    /**
     * Likes a video on the connected YouTube Music account (the same
     * `like/like` the web player posts). Best effort: a failure returns
     * false for the caller to report, never throws.
     */
    suspend fun likeVideo(videoId: String): Boolean = setVideoLiked(videoId, liked = true)

    /** Clears a like (unliking in the player removes it on YouTube too). */
    suspend fun removeVideoLike(videoId: String): Boolean = setVideoLiked(videoId, liked = false)

    private suspend fun setVideoLiked(videoId: String, liked: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (videoId.isBlank()) return@withContext false
            if (!ytAuth.connection.value.isConnected) return@withContext false
            val config = getWebConfig()
            val endpoint = if (liked) LIKE_ENDPOINT else REMOVE_LIKE_ENDPOINT
            runCatching {
                post(
                    url = "$MUSIC_API/$endpoint?key=${config.apiKey}&prettyPrint=false",
                    body = buildJsonObject {
                        put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                        put("target", likeTargetBody(videoId))
                    },
                    clientName = "WEB_REMIX",
                    clientVersion = config.clientVersion,
                    userAgent = WEB_USER_AGENT,
                    authenticated = true,
                )
                true
            }.onFailure {
                android.util.Log.w(PLAYLIST_LOG_TAG, "$endpoint failed for $videoId", it)
            }.getOrDefault(false)
        }

    /** Reads back an OWNED playlist with each item's setVideoId for diffs/removals. */
    suspend fun fetchOwnedPlaylist(
        playlistIdOrUrl: String,
        stopAfterVideoId: String? = null,
    ): YtOwnedPlaylist? = withContext(Dispatchers.IO) {
        if (!ytAuth.connection.value.isConnected) return@withContext null
        val rawId = extractPlaylistId(playlistIdOrUrl)
        if (rawId.isBlank()) return@withContext null
        val browseId = when {
            rawId.startsWith("VL") || rawId.startsWith("RDCLAK") || rawId.startsWith("FE") || rawId.startsWith("MPRE") || rawId.startsWith("UC") -> rawId
            else -> "VL$rawId"
        }

        val rootResult = runCatching { browseRoot(browseId, authenticated = true) }
        val root = rootResult.getOrNull()
            ?: (if (browseId != rawId) runCatching { browseRoot(rawId, authenticated = true) }.getOrNull() else null)
            ?: run {
                android.util.Log.w(PLAYLIST_LOG_TAG, "fetchOwnedPlaylist failed for browseId=$browseId", rootResult.exceptionOrNull())
                return@withContext null
            }

        val header = playlistHeader(root)
        val title = extractTitleFromHeader(header, root)
            ?: root.obj("header")?.obj("musicEditablePlaylistDetailHeaderRenderer")
                ?.obj("header")?.obj("musicResponsiveHeaderRenderer")?.obj("title")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: root.obj("header")?.obj("musicDetailHeaderRenderer")?.obj("title")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: root.obj("header")?.obj("musicResponsiveHeaderRenderer")?.obj("title")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: "Playlist"

        val shelves = playlistTrackContainers(root)
        val containers = if (shelves.isNotEmpty()) shelves else listOf(root)

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
        containers.forEach(::absorb)

        var token = containers.firstNotNullOfOrNull(::playlistTrackContinuationToken)
        val seenTokens = mutableSetOf<String>()
        var page = 0
        fun targetFound() = stopAfterVideoId != null && items.any {
            it.videoId == stopAfterVideoId && !it.setVideoId.isNullOrBlank()
        }
        var complete = true
        while (!targetFound() && !token.isNullOrBlank() && page < MAX_CONTINUATION_PAGES) {
            val currentToken = token ?: break
            if (!seenTokens.add(currentToken)) break
            val nextPage = runCatching { browseContinuation(currentToken, authenticated = true) }
                .getOrNull()
            if (nextPage == null) {
                // A continuation page failed to load (e.g. a 429). items is now a
                // truncated view of the real playlist — flag it so diffing callers
                // skip the merge instead of treating present tracks as "removed".
                complete = false
                break
            }
            val pageContainers = playlistTrackContainers(nextPage)
            val effectivePageContainers = if (pageContainers.isNotEmpty()) pageContainers else listOf(nextPage)
            effectivePageContainers.forEach(::absorb)
            token = effectivePageContainers.firstNotNullOfOrNull(::playlistTrackContinuationToken)
            page++
        }
        // Hitting the page cap with a token still pending is also a truncated view
        // (unless we stopped early because the target was already found).
        if (!targetFound() && !token.isNullOrBlank() && page >= MAX_CONTINUATION_PAGES) {
            complete = false
        }

        YtOwnedPlaylist(id = rawId, title = title, items = items, complete = complete)
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
    internal fun playlistTrackContainers(root: JsonElement): List<JsonElement> {
        val shelves = mutableListOf<JsonObject>()
        collectObjects(root, "musicPlaylistShelfRenderer", shelves)
        collectObjects(root, "musicPlaylistShelfContinuation", shelves)
        collectObjects(root, "playlistVideoListRenderer", shelves)
        collectObjects(root, "playlistVideoListContinuation", shelves)
        val validShelves = shelves.filterNot(::isSuggestionShelf)
        if (validShelves.isNotEmpty()) return validShelves

        val musicShelves = mutableListOf<JsonObject>()
        collectObjects(root, "musicShelfRenderer", musicShelves)
        val validMusicShelves = musicShelves.filterNot(::isSuggestionShelf).filter { parseSongRenderers(it).isNotEmpty() }
        if (validMusicShelves.isNotEmpty()) return validMusicShelves

        val continuations = root.obj("continuationContents")
        continuations?.obj("musicShelfContinuation")?.takeUnless(::isSuggestionShelf)?.let { return listOf(it) }
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
        prefetchStreams: Boolean = false,
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
                callTimeoutMs = SEARCH_REQUEST_TIMEOUT_MS,
            )
            return parseSongRenderers(root)
        }
        val filtered = runCatching { runSearch("EgWKAQIIAWoKEAkQBRAKEAMQBA==") }.getOrDefault(emptyList())
        // Unfiltered POST only when the filtered search came back empty (rare miss).
        val results = if (filtered.isEmpty()) {
            runCatching { runSearch(null) }.getOrDefault(emptyList())
        } else {
            filtered
        }
        val candidates = if (results.isNotEmpty()) {
            results
        } else {
            // Fallback: in regions or on networks where YouTube Music (WEB_REMIX) is blocked/restricted,
            // query YouTube's standard web endpoint using the WEB client.
            runCatching {
                val body = buildJsonObject {
                    put("context", context("WEB", "2.20261011.05.00", null))
                    put("query", query.trim())
                }
                val root = post(
                    url = "$YOUTUBE_API/search?key=AIzaSyAO_FJ2SlqAz8GlBg1fA54p0wDE7Xk80mU&prettyPrint=false",
                    body = body,
                    clientName = "WEB",
                    clientVersion = "2.20261011.05.00",
                    userAgent = WEB_USER_AGENT,
                    origin = YOUTUBE_ORIGIN,
                    referer = "$YOUTUBE_ORIGIN/",
                    callTimeoutMs = SEARCH_REQUEST_TIMEOUT_MS,
                )
                parseSongRenderers(root)
            }.getOrDefault(emptyList())
        }

        val finalResults = candidates
            .distinctBy { it.videoId }
            .filter { it.videoId.isNotBlank() }
            .take(limit)
        if (prefetchStreams) finalResults.take(2).forEach { prefetchStream(it.videoId) }
        finalResults
    }

    suspend fun fetchPodcastHome(): List<PodcastShelf> = withContext(Dispatchers.IO) {
        val root = podcastBrowseRoot(PODCASTS_BROWSE_ID)
        PodcastParser.parseHome(root)
    }

    suspend fun fetchPodcastShow(showId: String): PodcastShowPage? = withContext(Dispatchers.IO) {
        if (showId.isBlank()) return@withContext null
        val root = podcastBrowseRoot(showId)
        PodcastParser.parseShow(root, showId)
    }

    suspend fun fetchPodcastShowContinuation(
        showId: String,
        continuation: String,
        showTitle: String? = null,
    ): PodcastEpisodePage = withContext(Dispatchers.IO) {
        if (showId.isBlank() || continuation.isBlank()) {
            return@withContext PodcastEpisodePage(episodes = emptyList())
        }
        val root = podcastBrowseContinuation(continuation)
        PodcastParser.parseEpisodeContinuation(root, showId = showId, showTitle = showTitle)
    }

    suspend fun searchPodcastEpisodes(query: String, limit: Int = 30): List<PodcastEpisode> =
        withContext(Dispatchers.IO) {
            if (query.isBlank() || limit <= 0) return@withContext emptyList()
            val config = getWebConfig()
            suspend fun runSearch(params: String?): JsonObject = post(
                url = "$MUSIC_API/search?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                    put("query", query.trim())
                    if (params != null) put("params", params)
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
                callTimeoutMs = SEARCH_REQUEST_TIMEOUT_MS,
            )

            val cachedFilter = podcastEpisodesFilter
            val filteredRoot = runCatching {
                runSearch(cachedFilter ?: PODCAST_EPISODES_SEARCH_FILTER)
            }.getOrNull()
            // The Episodes filter already restricts the rows to episodes, so no
            // podcast marker is required for them.
            var episodes = filteredRoot
                ?.let { PodcastParser.parseEpisodeSearch(it, requirePodcastMarker = false) }
                .orEmpty()

            if (episodes.isEmpty()) {
                val unfilteredRoot = runCatching { runSearch(null) }.getOrNull()
                val discoveredFilter = unfilteredRoot?.let(PodcastParser::episodeSearchParams)
                    ?.takeIf { it.isNotBlank() && it != cachedFilter }
                if (discoveredFilter != null) {
                    val retried = runCatching { runSearch(discoveredFilter) }.getOrNull()
                    val parsed = retried
                        ?.let { PodcastParser.parseEpisodeSearch(it, requirePodcastMarker = false) }
                        .orEmpty()
                    if (parsed.isNotEmpty()) {
                        podcastEpisodesFilter = discoveredFilter
                        episodes = parsed
                    }
                }
                if (episodes.isEmpty()) {
                    // No filter token available: only keep rows that are
                    // explicitly podcasts.
                    episodes = unfilteredRoot
                        ?.let { PodcastParser.parseEpisodeSearch(it, requirePodcastMarker = true) }
                        .orEmpty()
                }
            }

            episodes.distinctBy { it.videoId }.take(limit)
        }

    private suspend fun podcastBrowseRoot(browseId: String): JsonObject {
        val connected = ytAuth.connection.value.isConnected
        return runCatching { browseRoot(browseId, connected) }.getOrElse { error ->
            if (connected) browseRoot(browseId, false) else throw error
        }
    }

    private suspend fun podcastBrowseContinuation(token: String): JsonObject {
        val connected = ytAuth.connection.value.isConnected
        return runCatching { browseContinuation(token, connected) }.getOrElse { error ->
            if (connected) browseContinuation(token, false) else throw error
        }
    }

    /** Anonymous YouTube Music radio for a seed video. This intentionally
     * stays cookie-free so related-song playlists work without an account. */
    suspend fun fetchRelatedSongs(
        videoId: String,
        limit: Int = 30,
        prefetchStreams: Boolean = false,
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
                                durationMs = track.durationSeconds?.takeIf { it > 0 }?.times(1_000L),
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
                                        durationMs = track.durationSeconds?.takeIf { it > 0 }?.times(1_000L),
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
                    durationMs = track.durationSeconds?.takeIf { it > 0 }?.times(1_000L),
                )
            }
        }

        // Catalog pages do not resolve audio. Playback resolves the selected
        // track and, at most, the next one.

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
                durationMs = track.durationSeconds?.takeIf { it > 0 }?.times(1_000L),
            )
        }

        // Catalog pages do not resolve audio. Playback resolves the selected
        // track and, at most, the next one.

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
        val root = runCatching {
            post(
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
        }.getOrNull()
        val items = root?.let { parseEntityRenderers(it, kind) }.orEmpty()
        if (items.isNotEmpty()) return@withContext items.take(limit)

        val fallbackRoot = runCatching {
            post(
                url = "$YOUTUBE_API/search?key=AIzaSyAO_FJ2SlqAz8GlBg1fA54p0wDE7Xk80mU&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB", "2.20261011.05.00", null))
                    put("query", query.trim())
                },
                clientName = "WEB",
                clientVersion = "2.20261011.05.00",
                userAgent = WEB_USER_AGENT,
                origin = YOUTUBE_ORIGIN,
                referer = "$YOUTUBE_ORIGIN/",
            )
        }.getOrNull() ?: return@withContext emptyList()
        parseEntityRenderers(fallbackRoot, kind).take(limit)
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
        }.getOrNull()
        val items = root?.let { parsePlaylistRenderers(it) }.orEmpty()
        if (items.isNotEmpty()) return@withContext items.take(limit)

        val fallbackRoot = runCatching {
            post(
                url = "$YOUTUBE_API/search?key=AIzaSyAO_FJ2SlqAz8GlBg1fA54p0wDE7Xk80mU&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB", "2.20261011.05.00", null))
                    put("query", query.trim())
                    put("params", "Eg-KAQwIABAAGAAgACgB")
                },
                clientName = "WEB",
                clientVersion = "2.20261011.05.00",
                userAgent = WEB_USER_AGENT,
                origin = YOUTUBE_ORIGIN,
                referer = "$YOUTUBE_ORIGIN/",
            )
        }.getOrNull() ?: return@withContext emptyList()
        parsePlaylistRenderers(fallbackRoot).take(limit)
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
            val durationSec = videoDetails?.string("lengthSeconds")?.toIntOrNull()
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
                    durationSeconds = durationSec,
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
                kotlinx.coroutines.withTimeout(STREAM_RESOLVE_TOTAL_TIMEOUT_MS) {
                    resolveAudioStreamInternal(videoId, authScope, startedAt)
                }
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
            shared.waiters.decrementAndGet()
            // Do not cancel the shared deferred when the last waiter leaves.
            // Prefetch is cancelled the moment the user taps that same track,
            // which used to abort a player response that had already arrived
            // and force a second 2s youtubei/player round trip. The request
            // lives on apiScope, writes the stream cache, and the next waiter
            // joins it via activeStreamRequests.
        }
    }

    /**
     * Download-only NewPipe extraction preferring the Opus (WebM) rendition, which
     * the download pipeline remuxes losslessly into a fully tagged .opus file.
     * No fallback to [resolveAudioStream]: the download path already tried it.
     */
    suspend fun resolveDownloadStream(videoId: String): YouTubeAudioStream = withContext(Dispatchers.IO) {
        require(videoId.isNotBlank()) { "Missing YouTube Music video id" }
        streamExtractor.resolveAudioStream(videoId, preferOpus = true)
    }

    /**
     * One youtubei/player POST. The client that served the previous song goes
     * first; the next client runs only if this one returns no direct URL.
     * Parallel hedges used to open VISIONOS, ANDROID_VR, and TVHTML5 together
     * and cancel the losers after the winner, which showed up as several
     * player requests and a cancellation immediately after a ~2s extract.
     */
    private suspend fun resolveDirectUrlFastPath(
        videoId: String,
        authScope: String,
    ): YouTubeAudioStream? {
        // Visitor data is mandatory, not best-effort: VISIONOS/ANDROID_VR
        // answer LOGIN_REQUIRED with zero formats when it is missing, so a
        // resolve without it burns the fast-path budget on misses. Bootstrap
        // it here (bounded, throttled) when the startup pre-warm hasn't —
        // including when the cached value is only the synthetic placeholder,
        // so a transient scrape miss self-heals instead of sticking.
        var visitorData = webConfig?.visitorData?.takeIf { it != FALLBACK_TOKEN_SESSION }
        if (visitorData.isNullOrBlank()) {
            val now = System.currentTimeMillis()
            if (now - lastVisitorBootstrapAttemptMs >= VISITOR_BOOTSTRAP_RETRY_COOLDOWN_MS) {
                lastVisitorBootstrapAttemptMs = now
                visitorData = runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(VISITOR_BOOTSTRAP_BUDGET_MS) {
                        getWebConfig()
                    }?.visitorData
                }.getOrNull() ?: visitorData
            }
            noteAttempt("direct-fast=visitor-${if (!visitorData.isNullOrBlank()) "ok" else "missing"}")
        }
        // One PoToken per resolve, shared across clients: the minter serves
        // cache hits in milliseconds (engine is pre-warmed at startup) and
        // anything slower proceeds without a token instead of stalling audio.
        val poToken = fastPoToken(videoId, visitorData)
        val nowMs = System.currentTimeMillis()
        val ordered = DIRECT_FAST_CLIENT_ORDER
            .mapNotNull { name -> PLAYER_CLIENTS.firstOrNull { it.name == name } }
            .filter { nowMs >= (failedClientsUntil[clientFailureKey(videoId, it.key, authScope)] ?: 0L) }
            .sortedByDescending { it.name == lastSuccessfulClientName }
        if (ordered.isEmpty()) {
            noteAttempt("direct-fast=no-clients")
            return null
        }

        val deadline = SystemClock.elapsedRealtime() + DIRECT_FAST_PATH_BUDGET_MS
        for (client in ordered) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                noteAttempt("direct-fast=budget-exhausted")
                return null
            }
            var failed = false
            val stream = try {
                kotlinx.coroutines.withTimeoutOrNull(minOf(DIRECT_FAST_CLIENT_TIMEOUT_MS, remaining)) {
                    resolveDirectClientStream(
                        videoId = videoId,
                        client = client,
                        visitorData = visitorData,
                        signatureTimestamp = null,
                        playerPoToken = if (client.needsPoToken) poToken?.playerToken else null,
                        gvsPoToken = if (client.needsGvsPoToken) poToken?.sessionToken else null,
                        authScope = authScope,
                        probeCandidates = false,
                        allowCipherFormats = false,
                        maxAttempts = 1,
                    )
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                failed = true
                noteAttempt("client=${client.name} outcome=error ${resolutionFailureDetail(error)}")
                null
            }
            if (stream != null) {
                noteAttempt("client=${client.name} outcome=direct-url itag=${stream.itag ?: -1}")
                lastSuccessfulClientName = client.name
                return stream
            }
            if (!failed) noteAttempt("client=${client.name} outcome=timeout")
        }
        noteAttempt("direct-fast=exhausted")
        return null
    }

    private suspend fun resolveAudioStreamInternal(
        videoId: String,
        authScope: String,
        startedAt: Long,
    ): YouTubeAudioStream = kotlinx.coroutines.withContext(ResolutionAttemptLog()) {
        kotlinx.coroutines.coroutineScope {
        val now = System.currentTimeMillis()

        // Direct clients and the NewPipe/InnerTubeX race start together.
        // The direct clients often return HTTP 200 with no playable URL; waiting
        // for that miss before starting NewPipe added the whole fast-path RTT
        // to every cold song.
        val fallback = async { racePlayableFallback(videoId, authScope, now, startedAt) }
        val fast = resolveDirectUrlFastPath(videoId, authScope)
        if (fast != null && probeStream(fast, "direct-fast-probe")) {
            fallback.cancel()
            cacheResolvedStream(fast, now)
            lastResolvedStreams[resolutionKey(videoId, authScope)] = fast
            logStreamEvent("direct-fast-resolved", fast)
            logStage(videoId, "direct-fast", startedAt)
            noteAttempt("fallback=cancelled-after-direct")
            logResolveAttempts(videoId, failed = false)
            return@coroutineScope fast
        }
        val resolved = try {
            fallback.await()
        } catch (error: Throwable) {
            if (error.isCooperativeCancellation()) throw error
            noteAttempt("fallback=failed ${resolutionFailureDetail(error)}")
            logResolveAttempts(videoId, failed = true)
            throw error
        }
        if (resolved == null) {
            noteAttempt("fallback=exhausted")
            logResolveAttempts(videoId, failed = true)
            throw IOException("Unable to resolve a playable audio stream for $videoId")
        }
        logResolveAttempts(videoId, failed = false)
        resolved
        }
    }

    private suspend fun racePlayableFallback(
        videoId: String,
        authScope: String,
        now: Long,
        startedAt: Long,
    ): YouTubeAudioStream? = kotlinx.coroutines.coroutineScope {
        val channel = kotlinx.coroutines.channels.Channel<YouTubeAudioStream>(2)
        val jobs = listOf(
            launch(Dispatchers.IO) {
                var failed = false
                val candidate = try {
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
                    failed = true
                    logClientFailure(videoId, "INNERTUBEX", failure)
                    noteAttempt("client=INNERTUBEX outcome=error ${resolutionFailureDetail(failure)}")
                    null
                }
                if (candidate == null) {
                    if (!failed) noteAttempt("client=INNERTUBEX outcome=empty")
                    return@launch
                }
                val compatible = candidate.isAdaptive || isCompatibleAudioCandidate(candidate)
                if (!compatible) {
                    noteAttempt("client=INNERTUBEX outcome=incompatible itag=${candidate.itag ?: -1}")
                    logStreamEvent("innertubex-rejected", candidate, detail = "compatible=false")
                    innerTubeXExtractor.reportPlaybackFailure(
                        videoId = videoId,
                        authScope = authScope,
                        clientProfile = candidate.clientProfile,
                    )
                    return@launch
                }
                if (probeStream(candidate, "innertubex-probe")) channel.trySend(candidate)
            },
            launch(Dispatchers.IO) {
                var failed = false
                val stream = try {
                    kotlinx.coroutines.withTimeoutOrNull(NEWPIPE_FALLBACK_TIMEOUT_MS) {
                        streamExtractor.resolveAudioStream(videoId)
                    }
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    failed = true
                    logClientFailure(videoId, "NEWPIPE", failure)
                    noteAttempt("client=NEWPIPE outcome=error ${resolutionFailureDetail(failure)}")
                    null
                }
                // resolveAudioStream already rejected URLs that answer 403.
                if (stream != null) {
                    noteAttempt("client=NEWPIPE outcome=accepted itag=${stream.itag ?: -1} mime=${stream.mimeType.orEmpty()}")
                    channel.trySend(stream)
                } else if (!failed) {
                    noteAttempt("client=NEWPIPE outcome=empty")
                }
            },
        )
        val remaining = AtomicInteger(jobs.size)
        jobs.forEach { job ->
            job.invokeOnCompletion {
                if (remaining.decrementAndGet() == 0) channel.close()
            }
        }
        try {
            val winner = kotlinx.coroutines.withTimeoutOrNull(NEWPIPE_FALLBACK_TIMEOUT_MS) {
                channel.receiveCatching().getOrNull()
            } ?: return@coroutineScope null
            cacheResolvedStream(winner, now)
            lastResolvedStreams[resolutionKey(videoId, authScope)] = winner
            val stage = if (winner.clientProfile == NEWPIPE_SOURCE) "newpipe" else "innertubex"
            logStreamEvent("resolved", winner)
            logStage(videoId, stage, startedAt)
            winner
        } finally {
            channel.close()
            jobs.forEach { it.cancel() }
        }
    }

    /**
     * One video-bound PoToken for the fast path, served from the minter's
     * cache whenever the pre-warmed engine is alive (milliseconds). Uses the
     * same session rule as the startup pre-warm so the engine is actually
     * reusable. Anything slower — cold WebView boot, missing WebView, dead
     * challenge — returns null and the resolve proceeds without a token
     * instead of blocking audio on it.
     */
    private suspend fun fastPoToken(videoId: String, visitorData: String?): PoTokenResult? {
        if (videoId.isBlank()) return null
        val session = visitorData?.takeIf { it.isNotBlank() } ?: FALLBACK_TOKEN_SESSION
        return try {
            kotlinx.coroutines.withTimeoutOrNull(POTOKEN_FAST_BUDGET_MS) {
                BotGuardTokenGenerator.mintToken(videoId, session)
            }
        } catch (error: Throwable) {
            // A timed-out mint reports as cancellation while the parent is
            // still alive; only a genuinely cancelled parent rethrows.
            if (error is kotlinx.coroutines.CancellationException && !currentCoroutineContext().isActive) throw error
            null
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
        maxAttempts: Int = MAX_PLAYER_REQUEST_ATTEMPTS,
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
            maxAttempts = maxAttempts,
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
            noteAttempt("probe stage=$stage client=${stream.clientProfile} itag=${stream.itag ?: -1} outcome=expired")
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
                noteAttempt(
                    "probe stage=$stage client=${stream.clientProfile} itag=${stream.itag ?: -1} " +
                        "http=${response.code} valid=$valid",
                )
                valid
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logStreamEvent(stage, stream, retry = retry, detail = "error=${error::class.java.simpleName}")
            noteAttempt(
                "probe stage=$stage client=${stream.clientProfile} itag=${stream.itag ?: -1} " +
                    resolutionFailureDetail(error),
            )
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

    private suspend fun noteAttempt(entry: String) {
        currentCoroutineContext()[ResolutionAttemptLog]?.record(entry)
    }

    private suspend fun logResolveAttempts(videoId: String, failed: Boolean) {
        val summary = currentCoroutineContext()[ResolutionAttemptLog]?.summary() ?: "none"
        android.util.Log.i(
            STREAM_LOG_TAG,
            "[YOUTUBE] id=$videoId resolve=${if (failed) "failed" else "ok"} $summary",
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
        prefetchStreams: Boolean = false,
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
                // Hybrid gate: 4.2.3's recall numbers (title >= 60, artist >= 35
                // with its contains clauses) minus its title-blind second chance
                // and minus 4.2.4's loosening (>= 30 + fuzzy subset matcher),
                // which substituted wrong tracks. Strict misses throw below.
                maxOf(
                    similarity(candidate.title, title),
                    similarity(baseTitle(candidate.title), baseTitle(title)),
                ) >= 60 &&
                    (cleanArtist.isBlank() ||
                        similarity(candidate.artist, cleanArtist) >= 35 ||
                        normalize(candidate.artist).contains(normalize(cleanArtist)) ||
                        normalize(candidate.title).contains(normalize(cleanArtist)))
            }
            .maxByOrNull { candidate -> matchScore(candidate, title, cleanArtist) }
            ?: throw IOException("No reliable YouTube Music match found for $title by $artist")
        return best.also {
            if (matchCache.size > MAX_MATCH_CACHE_ENTRIES) matchCache.clear()
            matchCache[cacheKey] = it
        }
    }

    suspend fun findBestMatchOrNull(
        title: String,
        artist: String,
        prefetchStreams: Boolean = false,
    ): YouTubeMusicTrack? = try {
        findBestMatch(title, artist, prefetchStreams)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    /**
     * SimpMusic / Metrolist / ArchiveTune style broad search for downloads.
     * Same scoring as playback but across multiple query variants
     * (title+artist, title-only, base/cleaned title) so a track that plays
     * still resolves for download even when the first strict query misses.
     * Never throws (except cancellation) — returns ranked candidates,
     * best first, possibly empty.
     */
    suspend fun findDownloadCandidates(
        title: String,
        artist: String,
        excludedVideoIds: Set<String> = emptySet(),
        limit: Int = 6,
    ): List<YouTubeMusicTrack> = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext emptyList()
        val cleanArtist = artist.takeUnless {
            it.isBlank() || it.equals("Unknown artist", ignoreCase = true)
        }.orEmpty()
        val cleanTitle = baseTitle(title).trim().ifBlank { title.trim() }
        val queries = linkedSetOf(
            listOf(title.trim(), cleanArtist).filter { it.isNotBlank() }.joinToString(" "),
            title.trim(),
            listOf(cleanTitle, cleanArtist).filter { it.isNotBlank() }.joinToString(" "),
            cleanTitle,
        ).filter { it.isNotBlank() }.take(4)
        val merged = linkedMapOf<String, YouTubeMusicTrack>()
        for (query in queries) {
            try {
                val results = searchSongs(query = query, limit = 15, prefetchStreams = false)
                for (track in results) {
                    if (track.videoId.isBlank() || track.videoId in excludedVideoIds) continue
                    merged.putIfAbsent(track.videoId, track)
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Exception) {
            }
            // Enough to try: downloads resolve candidates serially, so a
            // huge list only burns the per-track budget. Ten ranked
            // candidates across the query variants is plenty.
            if (merged.size >= 10) break
        }
        if (merged.isEmpty()) return@withContext emptyList()
        val ranked = merged.values.sortedByDescending { matchScore(it, title, cleanArtist) }
        val plausible = ranked.filter { candidate ->
            TextMatch.isSafeTitleMatch(candidate.title, title, cleanArtist)
        }
        plausible.take(limit.coerceIn(1, 10))
    }

    suspend fun isPlayable(title: String, artist: String): Boolean =
        findBestMatchOrNull(title, artist) != null

    /** Warms the cached web config (api key/version/visitor data) so the
     *  first playback's fast path can attach visitor data without an extra
     *  blocking fetch. Safe to call from a startup coroutine; never throws. */
    suspend fun preWarmPlayback() {
        val config = runCatching { getWebConfig() }.getOrNull()
        val visitor = config?.visitorData ?: FALLBACK_TOKEN_SESSION
        runCatching { BotGuardTokenGenerator.preWarm(visitor) }
    }

    private suspend fun getWebConfig(): WebConfig {
        // A config whose visitor data is only the synthetic placeholder is
        // usable but NOT final: the homepage scrape can miss once (network
        // not yet up at startup, consent interstitial, transient block) and
        // keeping that placeholder cached for the whole session degrades
        // every later search/resolve call. Re-fetch it once the retry
        // cooldown elapses; keep serving the stale value meanwhile.
        webConfig?.takeIf { it.hasRealVisitorData() }?.let { return it }
        return configMutex.withLock {
            webConfig?.takeIf { it.hasRealVisitorData() }?.let { return@withLock it }
            val now = System.currentTimeMillis()
            if (now - lastConfigFetchAttemptMs >= WEB_CONFIG_RETRY_COOLDOWN_MS) {
                lastConfigFetchAttemptMs = now
                val cachedPlaceholder = webConfig
                if (cachedPlaceholder == null) {
                    // First config of the session: block — callers cannot
                    // proceed without any visitor data at all.
                    val fetched = try {
                        fetchWebConfig()
                    } catch (cancellation: kotlinx.coroutines.CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        null
                    }
                    if (fetched != null) {
                        webConfig = fetched
                        return@withLock fetched
                    }
                } else {
                    // Placeholder in hand: refresh off the critical path so a
                    // slow homepage fetch never stalls search/resolve calls
                    // holding this mutex. The result lands for the next caller.
                    apiScope.launch {
                        val fetched = try {
                            fetchWebConfig()
                        } catch (cancellation: kotlinx.coroutines.CancellationException) {
                            throw cancellation
                        } catch (error: Exception) {
                            null
                        }
                        if (fetched != null && fetched.hasRealVisitorData() &&
                            webConfig?.hasRealVisitorData() != true
                        ) {
                            webConfig = fetched
                        }
                    }
                    return@withLock cachedPlaceholder
                }
            }
            val fallback = webConfig
                ?: WebConfig(FALLBACK_WEB_KEY, FALLBACK_WEB_VERSION, FALLBACK_TOKEN_SESSION)
            webConfig = fallback
            fallback
        }
    }

    /** True when the config's visitor data came from an actual homepage
     *  scrape rather than the synthetic placeholder. */
    private fun WebConfig.hasRealVisitorData(): Boolean =
        !visitorData.isNullOrBlank() && visitorData != FALLBACK_TOKEN_SESSION

    private suspend fun fetchWebConfig(): WebConfig {
        val request = Request.Builder()
            .url("$YOUTUBE_MUSIC_ORIGIN/")
            .header("User-Agent", WEB_USER_AGENT)
            .header("Cookie", "SOCS=CAESEwgDEgk2ODE4NDk5NzAaAmVuIAEaBgiA_LyaBg; CONSENT=YES+cb.20210328-17-p0.en+FX+999")
            .build()
        val call = http.newCall(request).apply {
            timeout().timeout(CONFIG_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        val (status, html) = call.readResponseBody()
        if (status !in 200..299) throw IOException("YouTube Music config HTTP $status")
        return WebConfig(
            apiKey = findConfig(html, "INNERTUBE_API_KEY") ?: FALLBACK_WEB_KEY,
            clientVersion = findConfig(html, "INNERTUBE_CONTEXT_CLIENT_VERSION") ?: FALLBACK_WEB_VERSION,
            visitorData = findConfig(html, "VISITOR_DATA")
                ?: findConfig(html, "EOM_VISITOR_DATA")
                ?: FALLBACK_TOKEN_SESSION,
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
        fun buildRequest(): Request {
            val sanitizedBody = if (!authenticated && body["context"]?.jsonObject?.contains("user") == true) {
                val origContext = body["context"]?.jsonObject
                if (origContext != null) {
                    val cleanContext = JsonObject(origContext.filterKeys { it != "user" })
                    JsonObject(body.toMutableMap().apply { put("context", cleanContext) })
                } else body
            } else body

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

            return builder
                .post(sanitizedBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }

        var lastException: Exception? = null
        for (attempt in 1..maxAttempts) {
            currentCoroutineContext().ensureActive()
            val request = buildRequest()
            try {
                val call = http.newCall(request)
                callTimeoutMs?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) }
                val (status, text) = call.readResponseBody()
                if (status !in 200..299) {
                    if (status == 400 || status == 403 || status == 429) webConfig = null
                    if (authenticated && (status == 401 || status == 403)) {
                        if (attempt < maxAttempts && ytAuth.refreshCookiesFromCookieManager()) {
                            android.util.Log.i(PLAYLIST_LOG_TAG, "Auto-refreshed YouTube cookies from CookieManager after HTTP $status; retrying request")
                            continue
                        }
                    }
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

    private val UNSUPPORTED_YT_REGIONS = setOf("CN", "RU", "IR", "KP", "CU", "SY", "BY")

    private fun resolveRegionCode(language: AppLanguage, locale: Locale): String {
        val sysCountry = runCatching { Locale.getDefault().country }.getOrDefault("")
        val localeCountry = locale.country
        val resolved = if (localeCountry.length == 2 && localeCountry.all { it.isLetter() }) {
            localeCountry.uppercase()
        } else {
            when (language) {
                AppLanguage.TURKISH -> "TR"
                AppLanguage.CHINESE_SIMPLIFIED -> "CN"
                AppLanguage.RUSSIAN -> "RU"
                AppLanguage.PORTUGUESE_BRAZIL -> "BR"
                AppLanguage.SPANISH -> if (sysCountry in LATIN_AMERICA_OR_SPAIN) sysCountry else "ES"
                AppLanguage.INDONESIAN -> "ID"
                AppLanguage.HINDI -> "IN"
                AppLanguage.GERMAN -> if (sysCountry in setOf("AT", "CH", "DE")) sysCountry else "DE"
                AppLanguage.FRENCH -> if (sysCountry in setOf("BE", "CA", "CH", "FR")) sysCountry else "FR"
                AppLanguage.ITALIAN -> if (sysCountry == "CH") sysCountry else "IT"
                AppLanguage.JAPANESE -> "JP"
                AppLanguage.KOREAN -> "KR"
                AppLanguage.ARABIC -> if (sysCountry in ARABIC_COUNTRIES) sysCountry else "SA"
                AppLanguage.ENGLISH -> if (sysCountry in ENGLISH_COUNTRIES) sysCountry else "US"
                AppLanguage.SYSTEM -> if (sysCountry.length == 2 && sysCountry.all { it.isLetter() }) sysCountry.uppercase() else "US"
            }
        }
        return if (resolved in UNSUPPORTED_YT_REGIONS || resolved.isBlank()) "US" else resolved
    }

    private fun context(
        name: String,
        version: String,
        visitorData: String?,
        osVersion: String? = null,
        authenticated: Boolean = false,
    ): JsonObject {
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
            // the default (first) channel's. Only attach when authenticated!
            if (authenticated) {
                ytAuth.connection.value.onBehalfOfUser?.takeIf { it.isNotBlank() }?.let { delegate ->
                    put("user", buildJsonObject {
                        put("lockedSafetyMode", false)
                        put("onBehalfOfUser", delegate)
                    })
                }
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

        if (songs.isEmpty()) {
            val generalVideos = mutableListOf<JsonObject>()
            collectObjects(root, "videoRenderer", generalVideos)
            collectObjects(root, "compactVideoRenderer", generalVideos)
            songs.addAll(generalVideos.mapNotNull(::parseVideoRenderer))
        }
        return songs.distinctBy { it.videoId }.filter { it.videoId.isNotBlank() }
    }

    private fun parseVideoRenderer(renderer: JsonObject): YouTubeMusicTrack? {
        val videoId = renderer.string("videoId") ?: return null
        val title = renderer.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: renderer.obj("title")?.string("simpleText")
            ?: return null
        val artist = renderer.obj("ownerText")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
            ?: renderer.obj("shortBylineText")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
            ?: "Unknown artist"
        val duration = renderer.obj("lengthText")?.string("simpleText")?.let(::parseDuration)
            ?: renderer.string("lengthSeconds")?.toIntOrNull()
        val thumbnails = renderer.obj("thumbnail")?.array("thumbnails")
        val artwork = thumbnails?.lastOrNull()?.asObject()?.string("url")?.highResolutionArtwork()
        return YouTubeMusicTrack(videoId, title, artist, null, artwork, duration)
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
        // Centralized stat detection covers "15M listens", "15 ml listens",
        // "2.3M monthly listeners", "10K subscribers", "Track 16", etc.
        // The explicit contains-checks below stay as a fast pre-filter so a
        // future ArtistHelper regression can never leak counters as artists.
        if (value.contains(" view", ignoreCase = true) ||
            value.contains(" views", ignoreCase = true) ||
            value.contains(" song", ignoreCase = true) ||
            value.contains(" play", ignoreCase = true) ||
            value.contains(" plays", ignoreCase = true) ||
            value.contains(" stream", ignoreCase = true) ||
            value.contains(" track", ignoreCase = true) ||
            value.contains(" listen", ignoreCase = true) ||
            value.contains(" subscrib", ignoreCase = true) ||
            value.contains(" follow", ignoreCase = true) ||
            value.contains(" monthly", ignoreCase = true) ||
            value.contains(" fan", ignoreCase = true)
        ) return false
        if (com.lastwave.app.util.ArtistHelper.isPlayCountOrStat(value)) return false
        // Leading digit + stat word anywhere ("15 ml listens") is never an artist.
        // Real artists starting with digits ("1975", "21 Savage", "30 Seconds")
        // don't contain counter words, so this is safe.
        val lower = value.lowercase()
        if (value.firstOrNull()?.isDigit() == true &&
            (lower.contains("listen") || lower.contains("subscrib") ||
                lower.contains("follow") || lower.contains("monthly") ||
                lower.contains("play") || lower.contains("view") ||
                lower.contains("stream") || lower.contains("scrobbl"))
        ) return false
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
        val needsPoToken: Boolean
            get() = name == "WEB_REMIX" || name == "WEB_EMBEDDED_PLAYER" || name == "MWEB" || name == "ANDROID_MUSIC" || name == "ANDROID"
        /** TVHTML5 mints readable URLs only when the media URL carries a GVS
         *  `pot=` token (the player body itself needs none). */
        val needsGvsPoToken: Boolean
            get() = needsPoToken || name == "TVHTML5"
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
            "WEB" to "1",
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
        private val VIDEO_ID_PATTERN = Regex("[A-Za-z0-9_-]{11}")
        const val LIBRARY_PLAYLISTS_BROWSE_ID = "FEmusic_liked_playlists"
        const val YT_HISTORY_BROWSE_ID = "FEmusic_history"
        const val YT_LIKED_BROWSE_ID = "VLLM"
        const val YT_HOME_BROWSE_ID = "FEmusic_home"
        const val YT_NEW_RELEASES_BROWSE_ID = "FEmusic_new_releases"
        const val YT_CHARTS_BROWSE_ID = "FEmusic_charts"
        const val MAX_CONTINUATION_PAGES = 600
        const val WRITE_ACTIONS_PER_REQUEST = 50

        const val HEDGED_CLIENT_STAGGER_DELAY_MS = 300L
        /** Per-client bound for the direct-URL fast path (single POST, no
         *  extras). Relaxed for slow devices: a fast first success still
         *  wins the race, only the worst case waits longer. */
        const val DIRECT_FAST_CLIENT_TIMEOUT_MS = 4_000L
        /** Bound for the mandatory visitor-data bootstrap on the fast path.
         *  VISIONOS/ANDROID_VR answer LOGIN_REQUIRED with zero formats when
         *  visitor data is missing, so a resolve without it burns the whole
         *  fast-path budget on guaranteed misses. */
        const val VISITOR_BOOTSTRAP_BUDGET_MS = 3_000L
        /** Cooldown before the fast path retries a failed visitor bootstrap. */
        const val VISITOR_BOOTSTRAP_RETRY_COOLDOWN_MS = 60_000L
        /** Bound for a warm PoToken mint on the fast path. The engine is
         *  pre-warmed at startup, so a cache hit lands in milliseconds;
         *  anything slower proceeds without a token. */
        const val POTOKEN_FAST_BUDGET_MS = 1_000L
        /** Whole fast path must settle inside this budget before heavier stages run. */
        const val DIRECT_FAST_PATH_BUDGET_MS = 6_000L
        /** Whole-call cap for one player-API POST (connect + read + body). */
        const val PLAYER_REQUEST_CALL_TIMEOUT_MS = 8_000L
        /** Max wait on the direct-client/NewPipe race before the last-resort stage. */
        const val CLIENT_RACE_TIMEOUT_MS = 8_000L
        /** Hard cap for one full stream resolution; callers retry with fresh state. */
        const val STREAM_RESOLVE_TOTAL_TIMEOUT_MS = 20_000L
        /** Bound for the InnerTubeX cipher stage so a hung resolve fails fast. */
        const val INNERTUBEX_STAGE_TIMEOUT_MS = 7_000L
        /** Bound for the last-resort NewPipe stage. */
        const val NEWPIPE_FALLBACK_TIMEOUT_MS = 9_000L
        /** Overall budget for promoting a staged YouTube stream from MusicPlayer. */
        const val YOUTUBE_PROMOTE_BUDGET_MS = 12_000L
        /** No-cipher clients tried first, in order (mirrors limusic's fallback order). */
        val DIRECT_FAST_CLIENT_ORDER = listOf("VISIONOS", "ANDROID_VR", "TVHTML5")
        const val MAX_FORMAT_PROBES_PER_CLIENT = 2
        const val MAX_PLAYER_REQUEST_ATTEMPTS = 2
        const val CONFIG_REQUEST_TIMEOUT_MS = 6_000L
        /** Cooldown before a cached-but-synthetic web config is re-fetched. */
        const val WEB_CONFIG_RETRY_COOLDOWN_MS = 30_000L
        const val RELATED_REQUEST_TIMEOUT_MS = 8_000L
        /** Per-call cap for interactive search POSTs: without it a hanging
         *  search burns both socket timeouts per attempt and stalls playback
         *  resolution with no error. Normal searches answer in ~1s. */
        const val SEARCH_REQUEST_TIMEOUT_MS = 10_000L
        const val ACCOUNT_SWITCHER_TIMEOUT_MS = 15_000L
        /** Per-probe socket timeout for the 0-1 byte stream validity check. */
        const val STREAM_PROBE_TIMEOUT_MS = 6_000L
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
        const val PODCASTS_BROWSE_ID = "FEmusic_podcasts"
        const val PODCAST_EPISODES_SEARCH_FILTER = "EgWKAQJIAWoSEBEQAxAQEAUQChAOEAkQBBAV"
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

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()
internal fun JsonElement.asObject(): JsonObject? = this as? JsonObject
internal fun JsonElement.obj(key: String): JsonObject? = (this as? JsonObject)?.obj(key)
internal fun JsonElement.array(key: String): JsonArray? = (this as? JsonObject)?.array(key)
internal fun JsonElement.string(key: String): String? = (this as? JsonObject)?.string(key)
internal fun JsonElement.int(key: String): Int? = (this as? JsonObject)?.int(key)

internal fun collectObjects(element: JsonElement, key: String, output: MutableList<JsonObject>) {
    when (element) {
        is JsonObject -> element.forEach { (name, child) ->
            if (name == key && child is JsonObject) output += child
            collectObjects(child, key, output)
        }
        is JsonArray -> element.forEach { collectObjects(it, key, output) }
        else -> Unit
    }
}

internal fun isSuggestionShelf(shelf: JsonObject): Boolean {
    val titleText = shelf.obj("title")?.array("runs")?.joinToString("") {
        it.asObject()?.string("text").orEmpty()
    } ?: shelf.obj("title")?.string("simpleText")
      ?: shelf.obj("header")?.obj("musicShelfHeaderRenderer")?.obj("title")?.array("runs")?.joinToString("") {
          it.asObject()?.string("text").orEmpty()
      }
      ?: shelf.obj("header")?.obj("musicShelfHeaderRenderer")?.obj("title")?.string("simpleText")
      ?: shelf.obj("header")?.obj("musicHeaderRenderer")?.obj("title")?.array("runs")?.joinToString("") {
          it.asObject()?.string("text").orEmpty()
      }
    if (!titleText.isNullOrBlank()) {
        val lower = titleText.trim().lowercase()
        if (lower.contains("suggestion") || lower.contains("suggested") ||
            lower.contains("recommend") || lower.contains("vorschl") ||
            lower.contains("empfehl") || lower.contains("sugeren") ||
            lower.contains("sugest") || lower.contains("recommand") ||
            lower.contains("sugger") || lower.contains("aanbevel") ||
            lower.contains("förslag") || lower.contains("forslag") ||
            lower.contains("suosituk") || lower.contains("рекомендац") ||
            lower.contains("öneri") || lower.contains("सुझाव") ||
            lower.contains("建议") || lower.contains("추천") ||
            lower.contains("おすすめ") || lower.contains("اقتراح")
        ) {
            return true
        }
    }
    val bottomEndpoint = shelf.obj("bottomEndpoint")
    if (bottomEndpoint != null) {
        val endpointStr = bottomEndpoint.toString().lowercase()
        if (endpointStr.contains("suggestion") || endpointStr.contains("suggested")) {
            return true
        }
    }
    val contents = shelf.array("contents") ?: shelf.array("items")
    if (contents != null && contents.isNotEmpty()) {
        val allHaveAddAction = contents.all { item ->
            val itemObj = item.asObject() ?: return@all false
            val editEndpoints = mutableListOf<JsonObject>()
            collectObjects(itemObj, "playlistEditEndpoint", editEndpoints)
            editEndpoints.any { ep ->
                ep.array("actions")?.any { act ->
                    act.asObject()?.string("action")?.contains("ACTION_ADD", ignoreCase = true) == true
                } == true
            }
        }
        if (allHaveAddAction) return true
    }
    return false
}
