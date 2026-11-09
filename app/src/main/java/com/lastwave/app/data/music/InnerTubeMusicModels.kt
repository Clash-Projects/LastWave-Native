package com.lastwave.app.data.music

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
