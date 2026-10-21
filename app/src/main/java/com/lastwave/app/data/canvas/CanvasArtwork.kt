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
    SPOTIFY,
    APPLE,
    TIDAL,
    COMMUNITY,
}

fun CanvasSource.displayName(): String = when (this) {
    CanvasSource.SPOTIFY -> "Spotify Canvas"
    CanvasSource.APPLE -> "Apple Music Motion"
    CanvasSource.TIDAL -> "TIDAL Video Cover"
    CanvasSource.COMMUNITY -> "Community Canvas"
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
    val tallUrl: String? = null,
    val wideUrl: String? = null,
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
     *
     * The title is compared leniently: the same release is packaged differently by every
     * service, so a title that only adds packaging words or a credit to the other one is the
     * same recording. A title that differs in any other way is a different song, and a canvas
     * for the wrong song is worse than no canvas at all.
     */
    fun matches(wantTitle: String, wantArtist: String, wantAlbum: String?): Boolean {
        val titleOk = title == null || wantTitle.isBlank() || title.titleMatches(wantTitle)

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
 * Words a title picks up from packaging rather than from the song itself.
 */
private val PACKAGING_WORDS = setOf(
    "remaster", "remastered", "live", "mix", "edit", "version", "mono", "stereo",
    "deluxe", "bonus", "anniversary", "acoustic", "instrumental", "radio", "single",
    "album", "remix", "dub", "demo", "explicit", "clean", "reissue", "expanded",
    "original",
)

/**
 * Words that introduce a credit rather than describe the recording.
 */
private val CREDIT_WORDS = setOf("feat", "ft", "featuring", "with")

/**
 * Whether two titles name the same recording.
 *
 * The shorter title has to be the longer one without its packaging, and the words left over
 * have to be packaging, a credit, or a year. "Song" and "Song (Remastered 2011)" are the same
 * recording; "Love" and "Love Story" are not, and neither are two titles that share nothing
 * but their first word.
 */
private fun String.titleMatches(other: String): Boolean {
    val ours = normalizeForMatch()
    val theirs = other.normalizeForMatch()
    if (ours == theirs) return true
    if (ours.isEmpty() || theirs.isEmpty()) return false

    val oursWords = ours.split(' ')
    val theirsWords = theirs.split(' ')
    val shorter = if (oursWords.size <= theirsWords.size) ours else theirs
    val longer = if (oursWords.size <= theirsWords.size) theirs else ours
    if (!longer.startsWith(shorter)) return false

    val extra = longer.removePrefix(shorter).trim().split(' ').filter { it.isNotBlank() }
    if (extra.isEmpty()) return true
    if (extra.size > 3) return false
    if (extra.first() in CREDIT_WORDS) return true
    return extra.all { word ->
        word in PACKAGING_WORDS || word.all { it.isDigit() }
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
