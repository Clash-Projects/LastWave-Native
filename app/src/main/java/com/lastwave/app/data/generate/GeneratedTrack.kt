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

/** Trims and collapses inner whitespace so equivalent titles compare equal. */
fun String.normalizeTrackText(): String = trim().replace(WHITESPACE_RUN, " ")

private val WHITESPACE_RUN = Regex("\\s+")
private val TITLE_CLEAN_BRACKET_REGEX = Regex(
    """[\(\[\{](?:feat\.?|featuring|with|remix|acoustic|live|unplugged|radio edit|radio mix|club mix|extended|version|ver\.|remaster|remastered|deluxe|anniversary|lo-?fi|slowed|reverb|sped up|speed up|karaoke|instrumental|cover|reprise|clean|explicit|bonus|official|audio|video|lyrics?|visualizer|original mix|mix)[^\)\]\}]*[\)\]\}]""",
    RegexOption.IGNORE_CASE,
)
private val TITLE_CLEAN_HYPHEN_REGEX = Regex(
    """\s*[-–—|/]\s*(?:feat\.?|remix|acoustic|live|unplugged|radio edit|extended|version|remaster.*|deluxe.*|lo-?fi.*|slowed.*|reverb.*|sped up.*|karaoke|instrumental|cover.*|reprise|bonus.*|official.*).*$""",
    RegexOption.IGNORE_CASE,
)
private val ARTIST_CLEAN_PRIMARY_REGEX = Regex(
    """^(.*?)(?:\s+(?:feat\.?|ft\.?|featuring|with|&|,|\/)\s+.*)$""",
    RegexOption.IGNORE_CASE,
)
private val NON_ALPHANUMERIC_REGEX = Regex("""[^a-zA-Z0-9\p{L}\p{N}\s]""")

fun String.cleanCoreTitle(): String {
    var s = trim()
    s = s.replace(TITLE_CLEAN_BRACKET_REGEX, "")
    s = s.replace(TITLE_CLEAN_HYPHEN_REGEX, "")
    s = s.replace(NON_ALPHANUMERIC_REGEX, " ")
    return s.replace(WHITESPACE_RUN, " ").trim().lowercase()
}

fun String.cleanCoreArtist(): String {
    var a = trim()
    val match = ARTIST_CLEAN_PRIMARY_REGEX.find(a)
    if (match != null) {
        a = match.groupValues[1]
    }
    a = a.replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")
    a = a.replace(NON_ALPHANUMERIC_REGEX, " ")
    return a.replace(WHITESPACE_RUN, " ").trim().lowercase()
}

/**
 * Strong song identity: same YouTube videoId wins outright (covers equal
 * songs whose title/artist spelling drifted, e.g. "(Official Video)" or
 * "- Topic" suffixes); otherwise falls back to normalized [GeneratedTrack.key],
 * trimmed case-insensitive name+artist comparison, or cleaned core title+artist.
 */
fun GeneratedTrack.sameSongAs(other: GeneratedTrack): Boolean {
    val mine = youtubeVideoIdOrNull()
    val theirs = other.youtubeVideoIdOrNull()
    if (mine != null && mine == theirs) return true
    if (key == other.key) return true
    if (name.trim().equals(other.name.trim(), ignoreCase = true) &&
        artist.trim().equals(other.artist.trim(), ignoreCase = true)) return true
    val cTitleA = name.cleanCoreTitle()
    val cTitleB = other.name.cleanCoreTitle()
    if (cTitleA.isNotBlank() && cTitleA == cTitleB) {
        val cArtistA = artist.cleanCoreArtist()
        val cArtistB = other.artist.cleanCoreArtist()
        if (cArtistA.isNotBlank() && (cArtistA == cArtistB || cArtistA.contains(cArtistB) || cArtistB.contains(cArtistA))) {
            return true
        }
    }
    return false
}

/** Keeps the first occurrence of each song, dropping later duplicates. */
fun List<GeneratedTrack>.distinctSongs(): List<GeneratedTrack> {
    if (size < 2) return this
    val out = ArrayList<GeneratedTrack>(size)
    for (track in this) {
        if (out.none { it.sameSongAs(track) }) out.add(track)
    }
    return out
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

/** Serializable form of [GeneratedTrack], used for Room-persisted playlists
 *  (JSON blob column) and Playlist screen export (CSV/M3U). */
@Serializable
@Immutable
data class StoredTrack(
    val name: String,
    val artist: String,
    val url: String = "",
    val image: String? = null,
    val album: String? = null,
)

fun GeneratedTrack.toStored() = StoredTrack(name = name, artist = artist, url = url, image = artworkUrl, album = album)
fun StoredTrack.toGenerated() = GeneratedTrack(name = name, artist = artist, artworkUrl = image, url = url, album = album)

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
