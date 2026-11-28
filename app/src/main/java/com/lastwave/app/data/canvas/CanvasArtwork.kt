package com.lastwave.app.data.canvas

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.Locale

internal const val CANVAS_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private val releaseTags = setOf(
    "remaster", "remastered", "live", "mix", "edit", "version", "mono", "stereo",
    "deluxe", "bonus", "anniversary", "acoustic", "instrumental", "radio", "single",
    "album", "remix", "dub", "demo", "explicit", "clean", "reissue", "expanded",
    "original",
)
private val creditTokens = setOf("feat", "ft", "featuring", "with")
private val accentMarksRegex = Regex("\\p{InCombiningDiacriticalMarks}+")
private val symbolStripRegex = Regex("[^a-z0-9\\s]")
private val spaceCollapseRegex = Regex("\\s+")
private val artistSplitRegex = Regex(
    "(?:\\s*,\\s*|\\s*&\\s*|\\s+×\\s+|\\s+x\\s+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b)",
    RegexOption.IGNORE_CASE,
)

enum class CanvasSource {
    SPOTIFY,
    APPLE,
    TIDAL,
}

fun CanvasSource.displayName(): String = when (this) {
    CanvasSource.APPLE -> "Apple Music Motion"
    CanvasSource.TIDAL -> "TIDAL Video Cover"
    CanvasSource.SPOTIFY -> "Spotify Canvas"
}

data class CanvasArtwork(
    val url: String,
    val fallbackUrl: String? = null,
    val tallUrl: String? = null,
    val wideUrl: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val source: CanvasSource = CanvasSource.APPLE,
) {
    fun matches(wantTitle: String, wantArtist: String, wantAlbum: String?): Boolean {
        if (title != null && wantTitle.isNotBlank() && !sameRecordingTitle(title, wantTitle)) {
            return false
        }
        if (artist != null && wantArtist.isNotBlank() && !sameCreditedArtists(wantArtist, artist)) {
            return false
        }
        if (!album.isNullOrBlank() && !wantAlbum.isNullOrBlank()) {
            return album.normalizeForMatch() == wantAlbum.normalizeForMatch()
        }
        return true
    }
}

internal fun String.normalizeForMatch(): String {
    val decomposed = Normalizer.normalize(this, Normalizer.Form.NFD)
    val ascii = accentMarksRegex.replace(decomposed, "").lowercase(Locale.ROOT)
    return spaceCollapseRegex.replace(symbolStripRegex.replace(ascii, " "), " ").trim()
}

internal fun splitArtists(raw: String): List<String> =
    raw.split(artistSplitRegex)
        .mapNotNull { part -> part.normalizeForMatch().takeIf { it.isNotEmpty() } }

internal fun canvasGet(
    client: OkHttpClient,
    url: String,
    headers: Map<String, String> = emptyMap(),
): String? {
    val builder = Request.Builder().url(url).header("User-Agent", CANVAS_UA)
    for ((headerName, headerValue) in headers) {
        builder.header(headerName, headerValue)
    }

    return try {
        client.newCall(builder.build()).execute().use { res ->
            if (res.isSuccessful) res.body?.string() else null
        }
    } catch (ex: Exception) {
        Log.w("CanvasArtwork", "GET $url failed: ${ex.message}")
        null
    }
}

private fun sameCreditedArtists(wantedRaw: String, actualRaw: String): Boolean {
    val wantedList = splitArtists(wantedRaw)
    val actualList = splitArtists(actualRaw)
    if (wantedList.isEmpty() || actualList.isEmpty()) return false
    return wantedList.all { expected ->
        actualList.any { candidate ->
            candidate == expected || candidate.contains(expected) || expected.contains(candidate)
        }
    }
}

private fun sameRecordingTitle(firstRaw: String, secondRaw: String): Boolean {
    val a = firstRaw.normalizeForMatch()
    val b = secondRaw.normalizeForMatch()
    if (a == b) return true
    if (a.isEmpty() || b.isEmpty()) return false

    val aTokens = a.split(' ')
    val bTokens = b.split(' ')
    val (shortForm, longForm) = if (aTokens.size <= bTokens.size) a to b else b to a
    if (!longForm.startsWith(shortForm)) return false

    val remainder = longForm.removePrefix(shortForm).trim().split(' ').filter { it.isNotEmpty() }
    if (remainder.isEmpty()) return true
    if (remainder.size > 3) return false
    if (remainder[0] in creditTokens) return true
    return remainder.all { token -> token in releaseTags || token.all(Char::isDigit) }
}
