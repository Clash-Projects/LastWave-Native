package com.lastwave.app.data.playlist

import com.lastwave.app.data.generate.GeneratedTrack

/**
 * Supported external playlist providers for the "paste a link" importer.
 *
 * These are **public** playlist sources: the track list is read from the
 * provider's public web page, so no API key, OAuth or account is required.
 * Private playlists / "Liked Songs" would need OAuth and are not supported.
 */
enum class ExternalPlaylistSource(
    val label: String,
    val hostHint: String,
) {
    SPOTIFY("Spotify", "spotify.com"),
    APPLE_MUSIC("Apple Music", "music.apple.com"),
}

/** One row read off a provider page, before it is matched to a playable track. */
data class ExternalTrackRow(
    val title: String,
    val artist: String,
    val album: String? = null,
)

/** A parsed provider playlist — the preview payload shown before importing. */
data class ExternalPlaylistResult(
    val source: ExternalPlaylistSource,
    val title: String,
    val author: String? = null,
    val artworkUrl: String? = null,
    val rows: List<ExternalTrackRow>,
)

/** Outcome of a match pass: mirrors [CsvImportResult] so the UI reports identically. */
data class ExternalImportResult(
    val source: ExternalPlaylistSource,
    val suggestedTitle: String,
    val totalRows: Int,
    val matchedCount: Int,
    val tracks: List<GeneratedTrack>,
)

/**
 * Link helpers shared by every provider importer.
 *
 * Accepts the shapes users actually paste or share:
 * - `https://open.spotify.com/playlist/<id>?si=...`
 * - `https://open.spotify.com/intl-de/playlist/<id>`   (locale segment)
 * - `https://spotify.link/<short>`                     (expanded by OkHttp redirects)
 * - `spotify:playlist:<id>`                            (URI form)
 * - `https://music.apple.com/us/playlist/<slug>/pl.<id>`
 * - `https://music.apple.com/us/playlist/<id>`         (no slug)
 */
object ExternalPlaylistLink {

    private val SPOTIFY_ID = Regex("""spotify\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?playlist/([A-Za-z0-9]+)""")
    private val SPOTIFY_URI = Regex("""spotify:playlist:([A-Za-z0-9]+)""")
    private val APPLE_ID = Regex("""music\.apple\.com/[a-z]{2}(?:-[a-z]{2})?/playlist/(?:[^/]+/)?(pl\.[A-Za-z0-9]+)""")

    /** Best-effort provider detection from a pasted string. */
    fun detect(raw: String): ExternalPlaylistSource? {
        val value = raw.trim().lowercase()
        if (value.isEmpty()) return null
        if (value.contains("spotify.com") || value.contains("spotify.link") || value.startsWith("spotify:")) {
            return ExternalPlaylistSource.SPOTIFY
        }
        if (value.contains("music.apple.com")) return ExternalPlaylistSource.APPLE_MUSIC
        return null
    }

    /** Provider-specific playlist id, or null when the link is not a playlist. */
    fun extractId(raw: String, source: ExternalPlaylistSource): String? {
        val value = raw.trim()
        return when (source) {
            ExternalPlaylistSource.SPOTIFY ->
                SPOTIFY_ID.find(value)?.groupValues?.getOrNull(1)
                    ?: SPOTIFY_URI.find(value)?.groupValues?.getOrNull(1)
            ExternalPlaylistSource.APPLE_MUSIC ->
                APPLE_ID.find(value)?.groupValues?.getOrNull(1)
        }
    }
}
