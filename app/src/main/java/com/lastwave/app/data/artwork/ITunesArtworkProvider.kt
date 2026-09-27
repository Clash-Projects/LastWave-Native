package com.lastwave.app.data.artwork

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ArtworkPipeline"
private const val CRASH_TAG = "ArtworkCrash"

@Serializable
private data class ITunesResult(
    val artworkUrl100: String? = null,
    val artworkUrl60: String? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val trackTimeMillis: Long? = null,
)

@Serializable
private data class ITunesSearchResponse(val results: List<ITunesResult> = emptyList())

/**
 * Faithful port of _itunesFetchArtwork(name, artist, 'track') — the only
 * iTunes call type Home actually uses. Same term format, same 600x600
 * upscale regex, same 6s timeout.
 */
@Singleton
class ITunesArtworkProvider @Inject constructor(
    okHttpClient: OkHttpClient,
) {
    private val client = okHttpClient.newBuilder()
        .callTimeout(6, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val upscalePattern = Regex("""/\d+x\d+bb\.(jpg|png|webp)$""", RegexOption.IGNORE_CASE)

    suspend fun fetchArtworkUrl(track: String, artist: String): String? = withContext(Dispatchers.IO) {
        // Try direct term first
        fetchByTerm(if (artist.isNotBlank()) "$track $artist" else track)
            ?: run {
                val cleanedTrack = ArtworkNormalizer.cleanTitle(track)
                val cleanedArtist = ArtworkNormalizer.cleanArtist(artist)
                val cleanedTerm = if (cleanedArtist.isNotBlank()) "$cleanedTrack $cleanedArtist" else cleanedTrack
                if (cleanedTerm != "$track $artist") {
                    fetchByTerm(cleanedTerm)
                } else null
            }
    }

    private suspend fun fetchByTerm(term: String): String? {
        // limit=1 blindly trusted the top hit: homonym titles ("Halo") often
        // returned another artist's cover (visible as wrong art on Home too).
        // Score a small candidate set and publish only a verified match.
        val track = term.substringBeforeLast(" ").ifBlank { term }
        val url = "https://itunes.apple.com/search?term=${URLEncoder.encode(term, "UTF-8")}&media=music&entity=song&limit=5"
        try {
            val request = Request.Builder().url(url).build()
            val body = client.newCall(request).awaitSuccessfulBodyOrNull() ?: return null
            val parsed = json.decodeFromString<ITunesSearchResponse>(body)
            val best = parsed.results
                .filter { !it.artworkUrl100.isNullOrBlank() || !it.artworkUrl60.isNullOrBlank() }
                .maxByOrNull { score(it, track, term) }
                ?.takeIf { isVerified(it, track, term) } ?: return null
            val raw = best.artworkUrl100 ?: best.artworkUrl60
            return raw?.let { upscale(it) }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            return null
        }
    }

    /** Both title and artist must agree; title alone (homonym) is rejected. */
    private fun isVerified(candidate: ITunesResult, track: String, term: String): Boolean {
        val artist = term.removePrefix(track).trim()
        if (!titleOk(candidate.trackName.orEmpty(), track)) return false
        if (artist.isNotBlank() && !artistOk(candidate.artistName.orEmpty(), artist)) return false
        return true
    }

    private fun score(candidate: ITunesResult, track: String, term: String): Int {
        val artist = term.removePrefix(track).trim()
        var score = if (candidate.trackName?.equals(track, ignoreCase = true) == true) 3
        else if (titleOk(candidate.trackName.orEmpty(), track)) 1 else 0
        if (artistOk(candidate.artistName.orEmpty(), artist)) score += 2
        return score
    }

    private fun titleOk(songTitle: String, title: String): Boolean {
        if (songTitle.isBlank() || title.isBlank()) return false
        if (songTitle.equals(title, ignoreCase = true)) return true
        return com.lastwave.app.data.lyrics.LrclibLyricsApi.titlesMatch(
            com.lastwave.app.data.lyrics.LrclibLyricsApi.cleanTrackTitle(songTitle),
            com.lastwave.app.data.lyrics.LrclibLyricsApi.cleanTrackTitle(title),
        )
    }

    private fun artistOk(songArtist: String, artist: String): Boolean {
        if (artist.isBlank() || songArtist.isBlank()) return false
        return com.lastwave.app.data.lyrics.LrclibLyricsApi.artistMatches(
            com.lastwave.app.data.lyrics.LrclibLyricsApi.cleanArtistName(songArtist),
            com.lastwave.app.data.lyrics.LrclibLyricsApi.cleanArtistName(artist),
        )
    }

    /** …/100x100bb.jpg -> …/600x600bb.jpg */
    private fun upscale(rawUrl: String): String = upscalePattern.replace(rawUrl, "/600x600bb.jpg")
}
