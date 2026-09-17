package com.lastwave.app.data.playlist

import com.lastwave.app.data.artwork.awaitSuccessfulBodyOrNull
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.music.InnerTubeMusicApi
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Importer for **public** Apple Music playlists.
 *
 * Apple has no keyless playlist API, so the track list is read straight off
 * the public web page (`https://music.apple.com/<storefront>/playlist/<slug>/pl.<id>`)
 * with a desktop browser User-Agent. Two tiers are tried, in order:
 *
 * 1. `<script id="serialized-server-data" type="application/json">` — the
 *    ~450KB hydration blob. It is the only source that carries artists, so it
 *    is the primary path: we walk the JSON and collect every object holding
 *    both a string `title` and a string `artistName`.
 * 2. `<script id="schema:music-playlist" type="application/ld+json">` — a
 *    schema.org `MusicPlaylist` whose `track` entries expose **no** artist.
 *    Used only when tier 1 yields nothing, with `artist = ""` (such rows match
 *    poorly and end up in the skipped count).
 *
 * Parsing never throws: a malformed or unexpected page degrades to an empty
 * row list rather than an exception.
 */
@Singleton
class AppleMusicPlaylistImporter @Inject constructor(
    okHttpClient: OkHttpClient,
    private val innerTube: InnerTubeMusicApi,
) {
    private val client = okHttpClient.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Pure page parser — no network, safe to unit test directly.
     *
     * @param html raw playlist page HTML.
     * @return the parsed [ExternalPlaylistResult]; [ExternalPlaylistResult.rows]
     *         is empty when the page cannot be understood.
     */
    internal fun parsePage(html: String): ExternalPlaylistResult {
        val rows = parseRows(html)
        return ExternalPlaylistResult(
            source = ExternalPlaylistSource.APPLE_MUSIC,
            title = parseTitle(html),
            rows = rows,
        )
    }

    /**
     * Downloads a public Apple Music playlist page and parses its track list.
     *
     * @param urlOrId a full `music.apple.com` playlist URL, or a bare playlist
     *                id such as `pl.abc123` (which is fetched from the `us`
     *                storefront, slug-less — Apple redirects by id).
     * @throws IllegalArgumentException when the input is neither a URL nor a
     *         recognisable Apple Music playlist id.
     */
    suspend fun fetchPlaylist(urlOrId: String): ExternalPlaylistResult = withContext(Dispatchers.IO) {
        val input = urlOrId.trim()
        val isUrl = input.startsWith("http://", ignoreCase = true) || input.startsWith("https://", ignoreCase = true)
        // Users often paste the host without a scheme ("music.apple.com/us/playlist/…").
        val raw = if (!isUrl && input.contains("music.apple.com", ignoreCase = true)) "https://$input" else input
        val id = ExternalPlaylistLink.extractId(raw, ExternalPlaylistSource.APPLE_MUSIC)
        // A bare "pl.<id>" carries no storefront/slug, so it is not matched by the link regex.
        val bareId = input.takeIf { it.startsWith("pl.", ignoreCase = true) }
        val target = when {
            // Apple URLs carry the storefront + slug, so fetch them verbatim.
            isUrl -> input
            id != null -> "$FALLBACK_PLAYLIST_URL$id"
            bareId != null -> "$FALLBACK_PLAYLIST_URL$bareId"
            else -> throw IllegalArgumentException("That does not look like an Apple Music playlist link.")
        }
        val body = fetchHtml(target)
            ?: throw IOException("Could not load that Apple Music playlist. Check the link and try again.")
        parsePage(body)
    }

    /**
     * Downloads the playlist, parses it, then matches every row to a playable
     * YouTube Music track via [InnerTubeMusicApi.findBestMatchOrNull].
     *
     * Rows that find no confident match are dropped and surface as
     * `totalRows - matchedCount` in the returned [ExternalImportResult].
     */
    suspend fun fetchAndMatch(urlOrId: String): ExternalImportResult = withContext(Dispatchers.IO) {
        val parsed = fetchPlaylist(urlOrId)
        val limiter = Semaphore(4)
        val tracks = coroutineScope {
            parsed.rows.map { row ->
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
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        ExternalImportResult(
            source = ExternalPlaylistSource.APPLE_MUSIC,
            suggestedTitle = parsed.title,
            totalRows = parsed.rows.size,
            matchedCount = tracks.size,
            tracks = tracks,
        )
    }

    // ---------------------------------------------------------------- network

    /** GETs [url] with desktop headers; returns the body only on a successful (2xx) response. */
    private suspend fun fetchHtml(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return try {
            client.newCall(request).awaitSuccessfulBodyOrNull()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    // ----------------------------------------------------------------- parse

    /** Tier 1 (`serialized-server-data`), then tier 2 (`schema:music-playlist`) as a fallback. */
    private fun parseRows(html: String): List<ExternalTrackRow> {
        val fromServerData = parseServerDataRows(html)
        if (fromServerData.isNotEmpty()) return fromServerData
        return parseLdJsonRows(html)
    }

    /** Walks the hydration blob collecting objects with both `title` and `artistName`. */
    private fun parseServerDataRows(html: String): List<ExternalTrackRow> {
        val content = scriptContent(html, SERVER_DATA_SCRIPT) ?: return emptyList()
        val root = readJson(content) ?: return emptyList()
        val rows = mutableListOf<ExternalTrackRow>()
        val seen = HashSet<Pair<String, String>>()
        collectSongObjects(root, depth = 0, rows, seen)
        return rows
    }

    private fun collectSongObjects(
        element: JsonElement,
        depth: Int,
        out: MutableList<ExternalTrackRow>,
        seen: MutableSet<Pair<String, String>>,
    ) {
        if (depth > MAX_JSON_DEPTH) return
        when (element) {
            is JsonObject -> {
                val title = element[TITLE_KEY]?.asStringOrNull()
                val artist = element[ARTIST_KEY]?.asStringOrNull()
                if (title != null && artist != null && title.isNotBlank()) {
                    val row = ExternalTrackRow(title = title.trim(), artist = artist.trim())
                    val key = dedupeKey(row)
                    if (seen.add(key)) out += row
                }
                for (value in element.values) collectSongObjects(value, depth + 1, out, seen)
            }
            is JsonArray -> {
                for (value in element) collectSongObjects(value, depth + 1, out, seen)
            }
            else -> Unit
        }
    }

    /**
     * Fallback: schema.org `MusicPlaylist` track names. These entries carry no
     * artist (verified against live pages), so rows get a blank artist.
     */
    private fun parseLdJsonRows(html: String): List<ExternalTrackRow> {
        val content = scriptContent(html, SCHEMA_SCRIPT) ?: return emptyList()
        val root = readJson(content) ?: return emptyList()
        val tracks = findArray(root, TRACK_KEY, depth = 0) ?: return emptyList()
        val rows = mutableListOf<ExternalTrackRow>()
        val seen = HashSet<Pair<String, String>>()
        for (entry in tracks) {
            val title = (entry as? JsonObject)?.get("name")?.asStringOrNull()?.trim().orEmpty()
            if (title.isBlank()) continue
            val row = ExternalTrackRow(title = title, artist = "")
            val key = dedupeKey(row)
            if (seen.add(key)) rows += row
        }
        return rows
    }

    /** Playlist title: ld+json `name`, else the page `<title>`, else a generic label. */
    private fun parseTitle(html: String): String {
        val fromSchema = scriptContent(html, SCHEMA_SCRIPT)
            ?.let { readJson(it) }
            ?.let { findName(it) }
            ?.let { cleanTitle(it) }
            ?.takeIf { it.isNotBlank() }
        if (fromSchema != null) return fromSchema
        val fromTitleTag = TITLE_TAG.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { cleanTitle(decodeEntities(it)) }
            ?.takeIf { it.isNotBlank() }
        return fromTitleTag ?: DEFAULT_PLAYLIST_TITLE
    }

    /** First `name` string on the ld+json root (the `MusicPlaylist` object). */
    private fun findName(root: JsonElement): String? = when (root) {
        is JsonObject -> root["name"]?.asStringOrNull()
        is JsonArray -> root.firstNotNullOfOrNull { findName(it) }
        else -> null
    }

    /** Depth-first search for the first [key] whose value is a [JsonArray]. */
    private fun findArray(element: JsonElement, key: String, depth: Int): JsonArray? {
        if (depth > MAX_JSON_DEPTH) return null
        return when (element) {
            is JsonObject -> {
                val direct = element[key] as? JsonArray
                if (direct != null) direct
                else element.values.firstNotNullOfOrNull { findArray(it, key, depth + 1) }
            }
            is JsonArray -> element.firstNotNullOfOrNull { findArray(it, key, depth + 1) }
            else -> null
        }
    }

    private fun readJson(content: String): JsonElement? = try {
        json.parseToJsonElement(content)
    } catch (_: Exception) {
        null
    }

    /** Content of the single `<script ... id="…" ...>` matched by [pattern]. */
    private fun scriptContent(html: String, pattern: Regex): String? =
        pattern.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun JsonElement.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun dedupeKey(row: ExternalTrackRow): Pair<String, String> =
        row.title.lowercase(Locale.ROOT) to row.artist.lowercase(Locale.ROOT)

    private fun decodeEntities(value: String): String = value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")

    /** Strips bidi marks, collapses whitespace and drops Apple's trailing "… - Apple Music" branding. */
    private fun cleanTitle(value: String): String = BIDI_MARKS
        .replace(value, "")
        .replace(WHITESPACE, " ")
        .trim()
        .let { APPLE_SUFFIX.replace(it, "") }
        .trim()
        .ifEmpty { DEFAULT_PLAYLIST_TITLE }

    private companion object {
        const val TITLE_KEY = "title"
        const val ARTIST_KEY = "artistName"
        const val TRACK_KEY = "track"
        const val DEFAULT_PLAYLIST_TITLE = "Apple Music Playlist"
        const val FALLBACK_PLAYLIST_URL = "https://music.apple.com/us/playlist/"
        const val MAX_JSON_DEPTH = 256
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Safari/537.36"
        /**
         * `<script>` tags here vary: the id may be quoted (`id="…"`), single
         * quoted or completely bare (`id=schema:music-playlist`, as Apple
         * actually ships it), and other attributes may sit on either side.
         */
        val SERVER_DATA_SCRIPT = Regex(
            """<script[^>]*\bid\s*=\s*["']?serialized-server-data["']?[^>]*>(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val SCHEMA_SCRIPT = Regex(
            """<script[^>]*\bid\s*=\s*["']?schema:music-playlist["']?[^>]*>(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val TITLE_TAG = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val WHITESPACE = Regex("\\s+")
        val BIDI_MARKS = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")
        val APPLE_SUFFIX = Regex(
            "\\s*[-–—]\\s*(?:Playlist\\s*[-–—]\\s*)?Apple\\s*Music\\s*$",
            RegexOption.IGNORE_CASE,
        )
    }
}
