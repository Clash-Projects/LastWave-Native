package com.lastwave.app.data.canvas

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.Locale

/**
 * Which provider a motion artwork clip came from.
 */
enum class CanvasSource {
    APPLE,
    TIDAL,
    COMMUNITY,
}

/**
 * A looping video that stands in for a track's cover art (Apple motion artwork,
 * Tidal video cover, or Community Canvas manifest).
 *
 * [url] is the primary stream/file to mount; [fallbackUrl] is tried if that fails.
 */
data class CanvasArtwork(
    val url: String,
    val fallbackUrl: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val source: CanvasSource = CanvasSource.COMMUNITY,
) {
    /**
     * Whether this clip really belongs to the track we asked about.
     *
     * Title and artists must match once punctuation, case and accents
     * are stripped. The album is checked when both sides provide it.
     */
    fun matches(wantTitle: String, wantArtist: String, wantAlbum: String?): Boolean {
        val titleOk = title == null || wantTitle.isBlank() ||
            title.normalizeForMatch() == wantTitle.normalizeForMatch()

        val titleArtists = splitArtists(wantArtist)
        val ourArtists = splitArtists(artist.orEmpty())
        val artistOk = artist == null || wantArtist.isBlank() ||
            (titleArtists.isNotEmpty() && ourArtists.isNotEmpty() &&
                titleArtists.all { want -> ourArtists.any { it == want || it.contains(want) || want.contains(it) } })

        val albumOk = album.isNullOrBlank() || wantAlbum.isNullOrBlank() ||
            album.normalizeForMatch() == wantAlbum.normalizeForMatch()

        return titleOk && artistOk && albumOk
    }
}

/**
 * Normalizes case, accents and punctuation between platforms (e.g. "Beyoncé - CRAZY IN LOVE (feat. JAY-Z)"
 * against "Beyonce Crazy in Love feat Jay Z").
 */
internal fun String.normalizeForMatch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

/**
 * Splits credited artists across varying separators (commas, ampersands, "feat.", "ft.", "x", "with").
 */
internal fun splitArtists(raw: String): List<String> =
    raw.split(ARTIST_SEPARATORS)
        .map { it.normalizeForMatch() }
        .filter { it.isNotBlank() }

private val ARTIST_SEPARATORS = Regex(
    "(?:\\s*,\\s*|\\s*&\\s*|\\s+×\\s+|\\s+x\\s+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b)",
    RegexOption.IGNORE_CASE,
)

internal const val CANVAS_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

/**
 * A plain GET returning the response body string, or null on non-2xx or failure.
 */
internal fun canvasGet(client: OkHttpClient, url: String, headers: Map<String, String> = emptyMap()): String? {
    val requestBuilder = Request.Builder()
        .url(url)
        .header("User-Agent", CANVAS_UA)

    headers.forEach { (name, value) ->
        requestBuilder.header(name, value)
    }

    return try {
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.isSuccessful) {
                response.body?.string()
            } else {
                null
            }
        }
    } catch (e: Exception) {
        Log.w("CanvasArtwork", "GET $url failed: ${e.message}")
        null
    }
}
