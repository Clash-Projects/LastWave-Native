package com.lastwave.app.data.network

import com.lastwave.app.data.artwork.awaitSuccessfulBodyOrNull
import com.lastwave.app.data.lyrics.LrclibLyricsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private val ARTIST_CREDIT_SEPARATOR = Regex(
    """\s*(?:,|&|;|/|\s+and\s+|\s+with\s+|\s+feat(?:uring)?\.?\s+|\s+ft\.?\s+|\s+x\s+)\s*""",
    RegexOption.IGNORE_CASE,
)
private val ARTIST_PUNCTUATION = Regex("[^\\p{L}\\p{N}]+")
private val ARTIST_WHITESPACE = Regex("\\s+")

@Serializable
data class ITunesSong(
    val trackId: Long = 0L,
    val trackName: String? = null,
    val artistName: String? = null,
    val collectionName: String? = null,
    val trackTimeMillis: Long? = null,
    val artworkUrl100: String? = null,
    val artworkUrl60: String? = null,
    val primaryGenreName: String? = null,
    val releaseDate: String? = null,
    val copyright: String? = null,
    val trackNumber: Int? = null,
    val trackCount: Int? = null,
    val discNumber: Int? = null,
    val discCount: Int? = null,
    val contentAdvisoryRating: String? = null,
    @SerialName("isStreamable") val isStreamable: Boolean? = null,
)

@Serializable
private data class ITunesSearchResponse(
    val resultCount: Int = 0,
    val results: List<ITunesSong> = emptyList(),
)

@Singleton
class ITunesSearchClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun searchSongs(
        query: String,
        limit: Int = 5,
        country: String? = null,
    ): List<ITunesSong> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val encodedTerm = URLEncoder.encode(query.trim(), "UTF-8")
        val countryParam = if (!country.isNullOrBlank()) "&country=${country.trim().lowercase()}" else ""
        val url = "https://itunes.apple.com/search?term=$encodedTerm&media=music&entity=song&limit=$limit$countryParam"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        val body = try {
            okHttpClient.newCall(request).awaitSuccessfulBodyOrNull() ?: return@withContext emptyList()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return@withContext emptyList()
        }

        try {
            json.decodeFromString<ITunesSearchResponse>(body).results
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun searchBestMatch(
        title: String,
        artist: String,
        durationSeconds: Int? = null,
        country: String? = null,
    ): ITunesSong? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null

        findBest(title, artist, durationSeconds, country)
            ?: run {
                val cleanedTitle = LrclibLyricsApi.cleanTrackTitle(title)
                val cleanedArtist = LrclibLyricsApi.cleanArtistName(artist)
                if (cleanedTitle != title || cleanedArtist != artist) {
                    findBest(cleanedTitle, cleanedArtist, durationSeconds, country)
                } else null
            }
    }

    private suspend fun findBest(
        title: String,
        artist: String,
        durationSeconds: Int?,
        country: String?,
    ): ITunesSong? {
        val query = if (artist.isNotBlank()) "$title $artist" else title
        val results = searchSongs(query = query, limit = 5, country = country)
        if (results.isEmpty()) return null

        val expectedMs = durationSeconds?.takeIf { it > 0 }?.times(1_000L)
        val cleanReqTitle = LrclibLyricsApi.cleanTrackTitle(title)
        val cleanReqArtist = LrclibLyricsApi.cleanArtistName(artist)

        return results
            .filter { it.trackId > 0 }
            .filter { isValidMatch(it, title, artist, cleanReqTitle, cleanReqArtist, expectedMs) }
            .minWithOrNull(
                compareBy<ITunesSong>(
                    { !it.trackName.equals(title, ignoreCase = true) && !LrclibLyricsApi.cleanTrackTitle(it.trackName.orEmpty()).equals(cleanReqTitle, ignoreCase = true) },
                    { artist.isNotBlank() && !it.artistName.equals(artist, ignoreCase = true) && !LrclibLyricsApi.cleanArtistName(it.artistName.orEmpty()).equals(cleanReqArtist, ignoreCase = true) },
                    { durationDiff(it.trackTimeMillis, expectedMs) }
                )
            )
    }

    private fun isValidMatch(
        song: ITunesSong,
        rawTitle: String,
        rawArtist: String,
        cleanReqTitle: String,
        cleanReqArtist: String,
        expectedMs: Long?,
    ): Boolean {
        val songTitle = song.trackName ?: return false
        val songArtist = song.artistName.orEmpty()

        if (!LrclibLyricsApi.sameVersion(rawTitle, songTitle)) return false

        val cleanSongTitle = LrclibLyricsApi.cleanTrackTitle(songTitle)
        val titleMatches = cleanSongTitle.equals(cleanReqTitle, ignoreCase = true) ||
            LrclibLyricsApi.titlesMatch(cleanSongTitle, cleanReqTitle)
        if (!titleMatches) return false

        if (rawArtist.isNotBlank()) {
            if (!artistCreditsMatch(songArtist, rawArtist)) return false
        }

        if (expectedMs != null && expectedMs > 0 && song.trackTimeMillis != null && song.trackTimeMillis > 0) {
            if (abs(song.trackTimeMillis - expectedMs) > MAX_DURATION_DIFF_MS) {
                return false
            }
        }

        return true
    }

    /**
     * iTunes returns the complete credited artist line (for example,
     * "Badshah, Shreya Ghoshal & Hiten"), while YouTube Music can return only
     * the primary artist. Match a request against a full credit or one of its
     * credited artists. Do not use the lyrics matcher here: its token overlap
     * would accept "Vicky Badshah" for a request for "Badshah" and select an
     * unrelated release's cover.
     */
    private fun artistCreditsMatch(candidate: String, request: String): Boolean {
        val candidateCredits = splitArtistCredits(candidate)
        val requestCredits = splitArtistCredits(request)
        return candidateCredits.any { candidateCredit ->
            requestCredits.any { requestCredit -> artistNamesMatch(candidateCredit, requestCredit) }
        }
    }

    private fun splitArtistCredits(raw: String): List<String> =
        LrclibLyricsApi.cleanArtistName(raw)
            .split(ARTIST_CREDIT_SEPARATOR)
            .map { it.normalizeArtistName() }
            .filter(String::isNotBlank)

    private fun artistNamesMatch(candidate: String, request: String): Boolean {
        if (candidate == request) return true
        if (candidate.contains(request) || request.contains(candidate)) {
            return minOf(candidate.length, request.length).toDouble() /
                maxOf(candidate.length, request.length) >= 0.6
        }
        // Preserve typo tolerance for equally sized names without treating an
        // added name as a match (e.g. "Vicky Badshah" for "Badshah").
        return candidate.split(' ').size == request.split(' ').size &&
            LrclibLyricsApi.artistMatches(candidate, request)
    }

    private fun String.normalizeArtistName(): String = lowercase()
        .replace(ARTIST_PUNCTUATION, " ")
        .trim()
        .replace(ARTIST_WHITESPACE, " ")

    private fun durationDiff(songMs: Long?, expectedMs: Long?): Long {
        if (songMs == null || expectedMs == null || songMs <= 0 || expectedMs <= 0) return 0L
        return abs(songMs - expectedMs)
    }

    companion object {
        private const val USER_AGENT = "LastWave"
        private const val MAX_DURATION_DIFF_MS = 12_000L
    }
}
