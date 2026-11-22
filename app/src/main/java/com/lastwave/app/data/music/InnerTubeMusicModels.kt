package com.lastwave.app.data.music

import okhttp3.MediaType.Companion.toMediaType

import android.content.Context
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

@androidx.compose.runtime.Immutable
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
    val userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
    /** YouTube "Stable volume" variant: compressed and several dB quieter than the master. */
    val isDrc: Boolean = false,
) {
    val mediaCacheKey: String
        get() = "youtube:$videoId:$clientProfile:${itag ?: -1}:$authScope:${expiresAtEpochMs ?: 0L}"
}

internal fun YouTubeAudioStream.meetsBitrateLimit(maxBitrateBps: Int?): Boolean =
    maxBitrateBps == null || (
        bitrate in 1..maxBitrateBps &&
            mimeType?.startsWith("audio/", ignoreCase = true) == true &&
            codec?.equals("hls", ignoreCase = true) != true &&
            !url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
    )

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

@androidx.compose.runtime.Immutable
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

internal data class SharedStreamRequest(
val deferred: Deferred<YouTubeAudioStream>,
val waiters: AtomicInteger = AtomicInteger(0),
)
internal data class CachedStream(
val cachedAtEpochMs: Long,
val stream: YouTubeAudioStream,
)
internal data class StreamCacheKey(
val videoId: String,
val clientProfile: String,
val itag: Int?,
val authScope: String,
val expiresAtEpochMs: Long?,
val lookupAuthScope: String,
val maxBitrateBps: Int?,
) {
fun matches(stream: YouTubeAudioStream): Boolean =
clientProfile == stream.clientProfile &&
itag == stream.itag &&
authScope == stream.authScope &&
expiresAtEpochMs == stream.expiresAtEpochMs
companion object {
fun from(stream: YouTubeAudioStream, lookupAuthScope: String, maxBitrateBps: Int?) = StreamCacheKey(
videoId = stream.videoId,
clientProfile = stream.clientProfile,
itag = stream.itag,
authScope = stream.authScope,
expiresAtEpochMs = stream.expiresAtEpochMs,
lookupAuthScope = lookupAuthScope,
maxBitrateBps = maxBitrateBps,
)
}
}



open class InnerTubeHttpException(val responseCode: Int) :
    IOException("InnerTube HTTP $responseCode")

data class PlayerClient(
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
    val needsSignatureTimestamp: Boolean
        get() = name == "WEB_REMIX" || name == "WEB_EMBEDDED_PLAYER" || name == "MWEB" || name == "TVHTML5_SIMPLY_EMBEDDED_PLAYER"
    val needsPoToken: Boolean
        get() = name == "WEB_REMIX" || name == "WEB_EMBEDDED_PLAYER" || name == "MWEB" || name == "ANDROID_MUSIC" || name == "ANDROID"
    val needsGvsPoToken: Boolean
        get() = needsPoToken || name == "TVHTML5" || name == "TVHTML5_SIMPLY_EMBEDDED_PLAYER"
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
        put("Origin", origin)
        put("Referer", referer)
    }
}

internal const val ARTIST_RELEASE_MAX_PAGES = 10
internal val EP_MARKER = Regex("(^|[^a-z])ep([^a-z]|$)")
internal val LATIN_AMERICA_OR_SPAIN = setOf("ES", "MX", "AR", "CO", "CL", "PE", "VE", "EC", "GT", "CU", "BO", "DO", "HN", "PY", "SV", "NI", "CR", "PR", "PA", "UY")
internal val ARABIC_COUNTRIES = setOf("SA", "EG", "AE", "IQ", "MA", "DZ", "SD", "YE", "SY", "TN", "JO", "LY", "LB", "OM", "KW", "QA", "BH")
internal val ENGLISH_COUNTRIES = setOf("US", "GB", "CA", "AU", "NZ", "IE", "ZA")
internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
internal val CLIENT_IDS = mapOf(
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
internal const val YOUTUBE_MUSIC_ORIGIN = "https://music.youtube.com"
internal const val MUSIC_API = "https://music.youtube.com/youtubei/v1"
internal const val YOUTUBE_API = "https://www.youtube.com/youtubei/v1"
internal const val MUSIC_VIDEO_TYPE_OMV = "MUSIC_VIDEO_TYPE_OMV"
internal val VIDEO_ID_PATTERN = Regex("[A-Za-z0-9_-]{11}")
internal const val LIBRARY_PLAYLISTS_BROWSE_ID = "FEmusic_liked_playlists"
internal const val YT_HISTORY_BROWSE_ID = "FEmusic_history"
internal const val YT_LIKED_BROWSE_ID = "VLLM"
internal const val YT_HOME_BROWSE_ID = "FEmusic_home"
internal const val YT_NEW_RELEASES_BROWSE_ID = "FEmusic_new_releases"
internal const val YT_CHARTS_BROWSE_ID = "FEmusic_charts"
internal const val MAX_CONTINUATION_PAGES = 600
internal const val WRITE_ACTIONS_PER_REQUEST = 50

internal const val HEDGED_CLIENT_STAGGER_DELAY_MS = 300L
internal const val DIRECT_FAST_CLIENT_TIMEOUT_MS = 4_000L
internal const val VISITOR_BOOTSTRAP_BUDGET_MS = 3_000L
internal const val VISITOR_BOOTSTRAP_RETRY_COOLDOWN_MS = 60_000L
internal const val POTOKEN_FAST_BUDGET_MS = 1_000L
internal const val DIRECT_FAST_PATH_BUDGET_MS = 6_000L
internal const val AUTHENTICATED_CIPHER_PATH_BUDGET_MS = 24_000L
internal const val AUTHENTICATED_CIPHER_CLIENT_BUDGET_MS = 20_000L
internal const val PLAYER_REQUEST_CALL_TIMEOUT_MS = 20_000L
internal const val CLIENT_RACE_TIMEOUT_MS = 8_000L
internal const val STREAM_RESOLVE_TOTAL_TIMEOUT_MS = 40_000L
internal const val INNERTUBEX_STAGE_TIMEOUT_MS = 7_000L
internal const val SIGNED_IN_INNERTUBEX_STAGE_TIMEOUT_MS = 16_000L
internal const val NEWPIPE_FALLBACK_TIMEOUT_MS = 9_000L
internal const val YOUTUBE_PROMOTE_BUDGET_MS = 12_000L

internal val DIRECT_FAST_CLIENT_ORDER = listOf("TVHTML5", "VISIONOS", "ANDROID_TESTSUITE", "ANDROID_VR", "WEB_REMIX")
internal const val VIDEO_LOG_TAG = "InnerTubeVideo"
internal val VIDEO_CLIENT_ORDER = listOf("ANDROID_VR", "VISIONOS", "IOS", "TVHTML5")
internal val ALIGNMENT_AUDIO_CLIENT_ORDER = listOf("VISIONOS", "ANDROID_VR")
internal const val VIDEO_CLIENT_TIMEOUT_MS = 6_000L
internal const val VIDEO_CIPHER_CONTEXT_TTL_MS = 30 * 60 * 1000L
internal const val MAX_VIDEO_PROBES_PER_CLIENT = 2
internal const val MAX_FORMAT_PROBES_PER_CLIENT = 4
internal const val MAX_PLAYER_REQUEST_ATTEMPTS = 2
internal const val CONFIG_REQUEST_TIMEOUT_MS = 6_000L
internal const val WEB_CONFIG_RETRY_COOLDOWN_MS = 30_000L
internal const val RELATED_REQUEST_TIMEOUT_MS = 8_000L
internal const val SEARCH_REQUEST_TIMEOUT_MS = 10_000L
internal const val ACCOUNT_SWITCHER_TIMEOUT_MS = 15_000L
internal const val STREAM_PROBE_TIMEOUT_MS = 15_000L
internal const val HISTORY_PING_TIMEOUT_MS = 15_000L
internal const val URL_EXPIRY_MARGIN_MS = 2 * 60 * 1000L
internal const val REQUEST_RETRY_BASE_DELAY_MS = 250L
internal const val REQUEST_RETRY_JITTER_MS = 180L
internal const val NEWPIPE_RETRY_BASE_DELAY_MS = 300L
internal const val NEWPIPE_RETRY_JITTER_MS = 220L
internal const val UNKNOWN_STREAM_EXPIRY_TTL_MS = 5 * 60 * 1000L

internal val CONFIRMED_UNAVAILABLE_REASONS = listOf(
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

internal const val CLIENT_COOLDOWN_MS = 60_000L
internal const val MAX_STREAM_CACHE_ENTRIES = 64
internal const val STREAM_TTL_MS = 4 * 60 * 60 * 1000L
internal const val MAX_MATCH_CACHE_ENTRIES = 1024
internal const val FALLBACK_TOKEN_SESSION = "lastwave_session"
internal const val NEWPIPE_SOURCE = "NEWPIPE"
internal const val ANONYMOUS_AUTH_SCOPE = "anonymous"
internal const val STREAM_LOG_TAG = "LastWaveStream"
internal const val PLAYLIST_LOG_TAG = "LastWavePlaylist"
internal const val WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
internal const val FALLBACK_WEB_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
internal const val FALLBACK_WEB_VERSION = "1.20260707.12.00"
internal const val VIDEO_SEARCH_FILTER = "EgWKAQIQAWoKEAkQBRAKEAMQBA=="
internal const val ARTIST_SEARCH_FILTER = "EgWKAQIgAWoKEAkQBRAKEAMQBA=="
internal const val ALBUM_SEARCH_FILTER = "EgWKAQIYAWoKEAkQBRAKEAMQBA=="
internal const val PODCASTS_BROWSE_ID = "FEmusic_podcasts"
internal const val PODCAST_EPISODES_SEARCH_FILTER = "EgWKAQJIAWoSEBEQAxAQEAUQChAOEAkQBBAV"
internal val CODEC_PATTERN = Regex("""codecs?=[\"']([^\"']+)["']""", RegexOption.IGNORE_CASE)

internal val PLAYER_CLIENTS = listOf(
    PlayerClient(
        name = "ANDROID_VR",
        version = "1.60.19",
        apiKey = "AIzaSyD-p045F_WzU-vA_YgX20SCx4KAo",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1) gzip",
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
        version = "20.03.02",
        apiKey = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
        userAgent = "com.google.ios.youtube/20.03.02 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)",
        osName = "iPhone",
        osVersion = "18.2",
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
