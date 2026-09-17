package com.lastwave.app.data.playlist

import android.util.Log
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.music.InnerTubeMusicApi
import java.io.IOException
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

private const val TAG = "SpotifyImport"

/**
 * Reads the track list of a **public** Spotify playlist and matches it to
 * playable YouTube Music tracks.
 *
 * Spotify's Web API needs a client secret this app does not ship, so the
 * importer reads the public embed page instead:
 * `https://open.spotify.com/embed/playlist/<id>` serves an HTML document whose
 * `<script id="__NEXT_DATA__" type="application/json">` tree holds one object
 * per track (`uri`, `title`, `subtitle`) plus one object describing the
 * playlist itself (`spotify:playlist:…`).
 */
@Singleton
class SpotifyPlaylistImporter @Inject constructor(
    okHttpClient: OkHttpClient,
    private val innerTube: InnerTubeMusicApi,
) {
    private val client = okHttpClient.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Pure parser: Spotify embed HTML in, parsed playlist out. Performs no
     * network and never throws — unreadable input yields an empty [rows] list so
     * the caller can show a friendly "could not read that playlist" message.
     *
     * @param html raw embed-page HTML.
     * @param fallbackTitle title used when the page carries no playlist name.
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
     * Downloads the public embed page for [urlOrId] and returns its track list.
     *
     * @throws IllegalArgumentException when [urlOrId] is not a Spotify playlist link or URI.
     * @throws IOException when the page cannot be fetched (non-200, network error).
     */
    suspend fun fetchPlaylist(urlOrId: String): ExternalPlaylistResult = withContext(Dispatchers.IO) {
        val id = ExternalPlaylistLink.extractId(urlOrId, ExternalPlaylistSource.SPOTIFY)
            ?: throw IllegalArgumentException("That does not look like a Spotify playlist link.")
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

    /**
     * Fetches the playlist and resolves every row to a playable YouTube Music
     * track, mirroring [CsvPlaylistImporter]'s bounded fan-out.
     *
     * Unmatched rows are dropped; [ExternalImportResult.totalRows] still counts
     * them so the UI can report "N of M matched".
     */
    suspend fun fetchAndMatch(urlOrId: String): ExternalImportResult = withContext(Dispatchers.IO) {
        val playlist = fetchPlaylist(urlOrId)
        val rows = playlist.rows
        val limiter = Semaphore(4)
        val tracks = coroutineScope {
            rows.map { row ->
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
        }
        ExternalImportResult(
            source = ExternalPlaylistSource.SPOTIFY,
            suggestedTitle = playlist.title,
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
