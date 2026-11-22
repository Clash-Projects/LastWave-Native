package com.lastwave.app.data.generate

import android.net.Uri
import androidx.compose.runtime.Immutable
import com.lastwave.app.data.artwork.ArtworkNormalizer
import com.lastwave.app.data.music.InnerTubeMusicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * [listeners]/[playcount]/[match]/[album] are best-effort extra signals —
 * present only when the source Last.fm endpoint supplies them (not every
 * endpoint does). Used exclusively by the Recommendations engine's hidden-
 * gem / similarity / album-diversity scoring (see RecommendationEngine) —
 * port of normaliseTracks()'s extra fields in app.js.
 */
@Immutable
data class GeneratedTrack(
    val name: String,
    val artist: String,
    val artworkUrl: String? = null,
    val url: String = "",
    val listeners: Long? = null,
    val playcount: Long? = null,
    val match: Double? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val albumArtist: String? = null,
    val durationMs: Long? = null,
    val lrcUri: String? = null,
) {
    /**
     * Normalized identity: trimmed, inner whitespace collapsed, lowercased.
     * Raw `" $name | $artist ".lowercase()` used to miss the same song across
     * sources (heart-tap vs YT import vs sync pull-back differ in spacing),
     * stacking duplicates on every re-import. Always compare via [key] or
     * [sameSongAs], never via ad-hoc string concatenation.
     */
    val key: String get() = "${name.normalizeTrackText()}|${artist.normalizeTrackText()}".lowercase()
}

/**
 * Trims and collapses inner whitespace so equivalent titles compare equal.
 * Same result as `trim().replace(Regex("\\s+"), " ")`, without a regex
 * matcher per call: this runs for every row of every playlist comparison.
 */
fun String.normalizeTrackText(): String {
    val trimmed = trim()
    var needsWork = false
    for (i in trimmed.indices) {
        val c = trimmed[i]
        if (c.isRegexWhitespace() && (c != ' ' || (i + 1 < trimmed.length && trimmed[i + 1].isRegexWhitespace()))) {
            needsWork = true
            break
        }
    }
    if (!needsWork) return trimmed
    val out = StringBuilder(trimmed.length)
    var inRun = false
    for (c in trimmed) {
        if (c.isRegexWhitespace()) {
            if (!inRun) out.append(' ')
            inRun = true
        } else {
            out.append(c)
            inRun = false
        }
    }
    return out.toString()
}

/** The characters Java regex `\s` matches: space, tab, LF, VT, FF, CR. */
private fun Char.isRegexWhitespace(): Boolean =
    this == ' ' || this == '\t' || this == '\n' || this == '\u000B' || this == '\u000C' || this == '\r'

/**
 * Strong song identity: same YouTube videoId wins outright (covers equal
 * songs whose title/artist spelling drifted, e.g. "(Official Video)" or
 * "- Topic" suffixes); otherwise falls back to normalized [GeneratedTrack.key]
 * plus a trimmed case-insensitive name+artist comparison.
 */
fun GeneratedTrack.sameSongAs(other: GeneratedTrack): Boolean {
    val mine = youtubeVideoIdOrNull()
    val theirs = other.youtubeVideoIdOrNull()
    if (mine != null && mine == theirs) return true
    if (name.isBlank() && other.name.isBlank()) return false
    if (key == other.key) return true
    return name.trim().equals(other.name.trim(), ignoreCase = true) &&
        artist.trim().equals(other.artist.trim(), ignoreCase = true)
}

/**
 * Keeps the first occurrence of each song, dropping later duplicates.
 *
 * A track is dropped when it is [sameSongAs] any track already kept. That
 * relation is three exact-match tests (videoId, normalized key, trimmed
 * case-insensitive name+artist), so each is a hash lookup here: O(n)
 * instead of comparing every pair, which took seconds on a few thousand
 * liked songs.
 */
fun List<GeneratedTrack>.distinctSongs(): List<GeneratedTrack> {
    if (size < 2) return this
    val out = ArrayList<GeneratedTrack>(size)
    val videoIds = HashSet<String>(size * 2)
    val keys = HashSet<String>(size * 2)
    val namePairs = HashSet<Pair<String, String>>(size * 2)
    for (track in this) {
        val videoId = track.youtubeVideoIdOrNull()
        if (videoId != null && videoId in videoIds) continue
        // sameSongAs never matches two blank-named tracks past the videoId test.
        val named = track.name.isNotBlank()
        val namePair = track.name.trim().foldCase() to track.artist.trim().foldCase()
        val key = track.key // read once: key normalizes both fields on every access
        if (named && (key in keys || namePair in namePairs)) continue
        out.add(track)
        videoId?.let(videoIds::add)
        if (named) {
            keys.add(key)
            namePairs.add(namePair)
        }
    }
    return out
}

/**
 * Two strings fold to the same value exactly when `equals(ignoreCase = true)`
 * holds: it compares per character after toUpperCase, then toLowerCase of
 * that. Plain lowercase() differs for e.g. Turkish dotted I and Greek sigma.
 */
private fun String.foldCase(): String {
    val out = CharArray(length)
    for (i in indices) out[i] = Character.toLowerCase(Character.toUpperCase(this[i]))
    return String(out)
}

/**
 * List-time videoId pinning (the limusic invariant): every track that can play
 * must carry its YouTube videoId in [GeneratedTrack.url] BEFORE it reaches a
 * queue or a saved playlist, so tap-to-play resolves directly with zero
 * search. Already-pinned tracks pass through with no network. Unresolvable
 * rows resolve to null and are dropped — a strict miss must never substitute
 * a wrong song at play time. Bounded fan-out mirrors the importers.
 */
suspend fun List<GeneratedTrack>.pinnedWithVideoIds(
    innerTube: InnerTubeMusicApi,
    concurrency: Int = 4,
): List<GeneratedTrack> = coroutineScope {
    if (isEmpty()) return@coroutineScope emptyList()
    val limiter = Semaphore(concurrency)
    map { track ->
        async {
            if (track.youtubeVideoIdOrNull() != null) return@async track
            // Imported files play from disk; never swap them for a YouTube match.
            if (track.isLocalFile()) return@async track
            if (track.name.isBlank()) return@async null
            limiter.withPermit {
                try {
                    innerTube.findBestMatchOrNull(track.name, track.artist, prefetchStreams = false)?.let { match ->
                        track.copy(
                            name = track.name.ifBlank { match.title },
                            artist = track.artist.ifBlank { match.artist },
                            album = track.album ?: match.album,
                            artworkUrl = track.artworkUrl ?: match.artworkUrl,
                            url = "https://music.youtube.com/watch?v=${match.videoId}",
                        )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
            }
        }
    }.awaitAll().filterNotNull()
}

/**
 * Resolves YouTube video IDs for tracks in a playlist, updating tracks that match
 * while strictly PRESERVING all tracks (never dropping unresolvable or offline tracks).
 */
suspend fun List<GeneratedTrack>.pinnedWithVideoIdsPreserving(
    innerTube: InnerTubeMusicApi,
    concurrency: Int = 4,
): List<GeneratedTrack> = coroutineScope {
    if (isEmpty()) return@coroutineScope emptyList()
    val limiter = Semaphore(concurrency)
    map { track ->
        async {
            if (track.youtubeVideoIdOrNull() != null) return@async track
            // Imported files play from disk; never swap them for a YouTube match.
            if (track.isLocalFile()) return@async track
            if (track.name.isBlank()) return@async track
            limiter.withPermit {
                try {
                    innerTube.findBestMatchOrNull(track.name, track.artist, prefetchStreams = false)?.let { match ->
                        track.copy(
                            name = track.name.ifBlank { match.title },
                            artist = track.artist.ifBlank { match.artist },
                            album = track.album ?: match.album,
                            artworkUrl = track.artworkUrl ?: match.artworkUrl,
                            url = "https://music.youtube.com/watch?v=${match.videoId}",
                        )
                    } ?: track
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    track
                }
            }
        }
    }.awaitAll()
}

/** Serializable form of [GeneratedTrack], used for Room-persisted playlists
 *  (JSON blob column) and Playlist screen export (CSV/M3U). */
@Serializable
@Immutable
data class StoredTrack(
    val name: String? = "",
    val artist: String? = "",
    val url: String? = "",
    val image: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val albumArtist: String? = null,
    val durationMs: Long? = null,
    val lrcUri: String? = null,
)

fun GeneratedTrack.toStored() = StoredTrack(
    name = name,
    artist = artist,
    url = url,
    image = artworkUrl,
    album = album,
    trackNumber = trackNumber,
    discNumber = discNumber,
    albumArtist = albumArtist,
    durationMs = durationMs,
    lrcUri = lrcUri,
)

fun StoredTrack.toGenerated() = GeneratedTrack(
    name = name.orEmpty(),
    artist = artist.orEmpty(),
    artworkUrl = image,
    url = url.orEmpty(),
    album = album,
    trackNumber = trackNumber,
    discNumber = discNumber,
    albumArtist = albumArtist,
    durationMs = durationMs,
    lrcUri = lrcUri,
)

fun GeneratedTrack.youtubeVideoIdOrNull(): String? {
    val value = url.trim()
    if (YOUTUBE_VIDEO_ID_REGEX.matches(value)) return value
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val videoId = when {
        host == "youtu.be" -> uri.pathSegments.firstOrNull()
        host == "youtube.com" || host.endsWith(".youtube.com") ->
            uri.getQueryParameter("v")
                ?: uri.pathSegments
                    .takeIf { it.firstOrNull() in setOf("embed", "shorts", "live") }
                    ?.getOrNull(1)
        else -> null
    }
    return videoId?.takeIf(YOUTUBE_VIDEO_ID_REGEX::matches)
}

private val YOUTUBE_VIDEO_ID_REGEX = Regex("[A-Za-z0-9_-]{11}")

/**
 * Local songs: an imported audio file stores its absolute path in
 * [GeneratedTrack.url]. Such rows play straight from disk and must never be
 * re-pinned to a YouTube videoId, searched for, or mirrored to YouTube Music.
 */
fun GeneratedTrack.isLocalFile(): Boolean = isLocalAudioPath(url)

fun isLocalAudioPath(url: String?): Boolean =
    url != null && (url.startsWith("/") || url.startsWith("content://") || url.startsWith("file://"))

/**
 * Formats Media3 1.2.1 (+ bundled FFmpeg audio decoder, custom AIFF/AIFC PCM
 * extractor, and native C++ DSD64..DSD512 FIR decimator) can open from storage.
 * Proprietary or unbundled codecs (APE, WMA, WavPack, Musepack, TTA) stay listed
 * in [UNSUPPORTED_AUDIO] so the scanner skips and reports them clearly.
 */
object LocalAudioFormats {
    private val MIME_BY_EXTENSION = mapOf(
        "mp3" to "audio/mpeg",
        "mp2" to "audio/mpeg",
        "flac" to "audio/flac",
        "wav" to "audio/wav",
        "wave" to "audio/wav",
        "aif" to "audio/aiff",
        "aiff" to "audio/aiff",
        "aifc" to "audio/aiff",
        "dsf" to "audio/x-dsf",
        "dff" to "audio/x-dff",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/ogg",
        "m4a" to "audio/mp4",
        "m4b" to "audio/mp4",
        "mp4" to "audio/mp4",
        "alac" to "audio/mp4",
        "aac" to "audio/aac",
        "mka" to "audio/x-matroska",
        "webm" to "audio/webm",
        "amr" to "audio/amr",
        "ac3" to "audio/ac3",
        "ec3" to "audio/eac3",
        "eac3" to "audio/eac3",
        "ac4" to "audio/ac4",
    )

    /** Audio-looking extensions we recognise but cannot play (reported as skipped). */
    val UNSUPPORTED_AUDIO = setOf("ape", "wma", "wv", "mpc", "tta", "mid", "midi")

    val supportedExtensions: Set<String> get() = MIME_BY_EXTENSION.keys

    fun extensionOf(name: String): String {
        val decoded = runCatching { Uri.decode(name) }.getOrNull() ?: name
        return decoded.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    }

    fun isSupported(name: String): Boolean = extensionOf(name) in MIME_BY_EXTENSION

    fun mimeFor(name: String): String? = MIME_BY_EXTENSION[extensionOf(name)]

    /**
     * Path or content URI of a local audio track, or null. Accepts the `file://` form too:
     * a queue restored from Media3 carries the file URI.
     */
    fun importedPathOrNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("http://") || url.startsWith("https://")) return null
        if (url.contains("tidal_mpd") || url.contains("segdrm_mpd")) return null
        val clean = url.removePrefix("file://")
        if (clean.startsWith("/") || clean.startsWith("content://") || url.startsWith("content://")) {
            return if (url.startsWith("content://")) url else clean
        }
        return null
    }

    const val IMPORT_DIR = "local_music"

    /** Sidecar lyrics path for an imported file: same base name, `.lrc`. */
    fun lrcPathFor(audioPath: String): String = audioPath.substringBeforeLast('.') + ".lrc"
}

/**
 * Last.fm's quirky "bare object instead of a 1-item array" behavior (seen
 * throughout app.js as `Array.isArray(x) ? x : (x ? [x] : [])`) shows up on
 * every endpoint Generate uses, each with a slightly different response
 * shape. Rather than hand-write a dedicated @Serializable DTO + custom list
 * serializer per endpoint, this navigates the raw JsonElement directly —
 * the same normalisation app.js's normaliseTracks() does, applied generically.
 */
object GenerateJson {

    fun asObjectList(element: JsonElement?): List<JsonObject> = when (element) {
        is JsonArray -> element.mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(element)
        else -> emptyList()
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    /** artist field varies: a bare string on some endpoints, `{name, mbid}`
     *  or `{#text}` on others — matches normaliseTracks()'s artist handling. */
    private fun JsonObject.artistName(): String {
        val a = this["artist"] ?: return ""
        return when (a) {
            is JsonPrimitive -> a.contentOrNull.orEmpty()
            is JsonObject -> a.stringOrNull("name") ?: a.stringOrNull("#text").orEmpty()
            else -> ""
        }
    }

    private fun JsonObject.bestImageUrl(): String? {
        val images = (this["image"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        fun bySize(size: String) = images.firstOrNull { it.stringOrNull("size") == size }
            ?.stringOrNull("#text")?.takeIf { ArtworkNormalizer.isRealImage(it) }
        return bySize("extralarge")
            ?: bySize("large")
            ?: bySize("medium")
            ?: images.firstOrNull { ArtworkNormalizer.isRealImage(it.stringOrNull("#text")) }?.stringOrNull("#text")
    }

    /** album field varies the same way artist does: bare string, `{#text}`,
     *  or `{title}` depending on endpoint — matches normaliseTracks(). */
    private fun JsonObject.albumName(): String? {
        val a = this["album"] ?: return null
        return when (a) {
            is JsonPrimitive -> a.contentOrNull
            is JsonObject -> a.stringOrNull("#text") ?: a.stringOrNull("title")
            else -> null
        }
    }

    /** Port of normaliseTracks(): filters entries with no name, extracts
     *  artist + best image per the shared priority/placeholder rules, plus
     *  the best-effort listeners/playcount/match/album signals used only by
     *  the Recommendations engine's scoring. */
    fun normalise(element: JsonElement?): List<GeneratedTrack> =
        asObjectList(element)
            .mapNotNull { obj ->
                val name = obj.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                GeneratedTrack(
                    name = name,
                    artist = obj.artistName(),
                    artworkUrl = obj.bestImageUrl(),
                    url = obj.stringOrNull("url").orEmpty(),
                    listeners = obj.stringOrNull("listeners")?.toLongOrNull(),
                    playcount = obj.stringOrNull("playcount")?.toLongOrNull(),
                    match = obj.stringOrNull("match")?.toDoubleOrNull(),
                    album = obj.albumName(),
                )
            }

    /** Simple name reader for endpoints like artist.getsimilar, where
     *  entries are `{name, ...}` rather than track-shaped. */
    fun namesOf(element: JsonElement?): List<String> =
        asObjectList(element).mapNotNull { it.stringOrNull("name")?.takeIf(String::isNotBlank) }
}
