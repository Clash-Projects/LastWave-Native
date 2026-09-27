package com.lastwave.app.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class LrclibRecord(
    val id: Long? = null,
    val name: String? = null,
    @SerialName("trackName")
    val trackName: String? = null,
    @SerialName("artistName")
    val artistName: String? = null,
    @SerialName("albumName")
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean? = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

@Singleton
class LrclibLyricsApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Attempts to fetch lyrics from LRCLIB.
     * Exact match via /api/get is tried with progressively looser parameters
     * (a wrong album tag or a YouTube-length duration must not 404 away a
     * record the database holds), then falls back to /api/search.
     */
    suspend fun fetchLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null,
    ): LrclibRecord? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null

        // 1. Exact matches, strictest first so a correct album/duration still
        // pins the right version (studio vs live/remix).
        val exactAttempts = listOf(
            Triple(title, artist, Pair(album, durationSeconds)),
            Triple(title, artist, Pair(null, durationSeconds)),
            Triple(title, artist, Pair(null, null)),
        )
        for ((t, a, params) in exactAttempts) {
            val exact = getLyricsExact(t, a, params.first, params.second)
            if (exact != null && (!exact.syncedLyrics.isNullOrBlank() || !exact.plainLyrics.isNullOrBlank() || exact.instrumental == true)) {
                return@withContext exact
            }
        }

        // 2. Clean title (strip "(feat. ...)", "- Extended", "[Official Video]", etc.) and retry exact
        val cleanedTitle = cleanTrackTitle(title)
        val cleanedArtist = cleanArtistName(artist)
        if (cleanedTitle != title || cleanedArtist != artist) {
            for (params in listOf(Pair(album, durationSeconds), Pair(null, durationSeconds), Pair(null, null))) {
                val cleanedExact = getLyricsExact(cleanedTitle, cleanedArtist, params.first, params.second)
                if (cleanedExact != null && (!cleanedExact.syncedLyrics.isNullOrBlank() || !cleanedExact.plainLyrics.isNullOrBlank() || cleanedExact.instrumental == true)) {
                    return@withContext cleanedExact
                }
            }
        }

        // 3. Fallback to /api/search query with tiered duration tolerance.
        // Raw title is passed for version-tag comparison (cleaning strips
        // "(Live)"/"(Remix)" markers that distinguish recordings).
        searchLyrics(cleanedTitle, cleanedArtist, durationSeconds, rawTitle = title)
            ?: searchLyrics(title, artist, durationSeconds, rawTitle = title)
    }

    private fun getLyricsExact(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
    ): LrclibRecord? {
        val urlBuilder = "https://lrclib.net/api/get".toHttpUrlOrNull()?.newBuilder() ?: return null
        urlBuilder.addQueryParameter("track_name", title.trim())
        urlBuilder.addQueryParameter("artist_name", artist.trim())
        if (!album.isNullOrBlank()) {
            urlBuilder.addQueryParameter("album_name", album.trim())
        }
        if (durationSeconds != null && durationSeconds > 0) {
            urlBuilder.addQueryParameter("duration", durationSeconds.toString())
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", "LastWave-Android/1.0 (https://github.com/clash-projects/lastwave)")
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                json.decodeFromString<LrclibRecord>(body)
            }
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun searchLyrics(
        title: String,
        artist: String,
        durationSeconds: Int? = null,
        rawTitle: String = title,
    ): LrclibRecord? {
        val urlBuilder = "https://lrclib.net/api/search".toHttpUrlOrNull()?.newBuilder() ?: return null
        urlBuilder.addQueryParameter("q", "$artist $title".trim())

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", "LastWave-Android/1.0 (https://github.com/clash-projects/lastwave)")
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val list = json.decodeFromString<List<LrclibRecord>>(body)
                if (list.isEmpty()) return null

                // Score candidates on artist/title only; duration is a soft
                // preference, not a reject gate — YouTube/music-video lengths
                // routinely differ 10-60s from the database's audio length.
                // Tiers: <=8s (right version) -> <=30s -> closest overall.
                data class Scored(val record: LrclibRecord, val durationDelta: Double)

                val matched = list.mapNotNull { candidate ->
                    val candArtist = cleanArtistName(candidate.artistName ?: "")
                    val reqArtist = cleanArtistName(artist)
                    if (!artistMatches(candArtist, reqArtist)) return@mapNotNull null

                    val candRawTitle = candidate.trackName ?: candidate.name ?: ""
                    // Never serve the wrong recording: a live/remix/cover tag
                    // present on one side but not the other rejects the record
                    // at every tier, however close the duration is.
                    if (!sameVersion(rawTitle, candRawTitle)) return@mapNotNull null

                    val candTitle = cleanTrackTitle(candRawTitle)
                    val reqTitle = cleanTrackTitle(title)
                    val titleMatches = titlesMatch(candTitle, reqTitle)
                    if (!titleMatches) return@mapNotNull null

                    val delta = if (durationSeconds != null && durationSeconds > 0 && candidate.duration != null && candidate.duration > 0) {
                        kotlin.math.abs(candidate.duration - durationSeconds)
                    } else {
                        0.0
                    }
                    Scored(candidate, delta)
                }.sortedBy { it.durationDelta }

                if (matched.isEmpty()) return null

                fun pick(predicate: (LrclibRecord) -> Boolean, maxDelta: Double): LrclibRecord? =
                    matched.firstOrNull { it.durationDelta <= maxDelta && predicate(it.record) }?.record

                // Synced lyrics: strict version pin first, then relaxed.
                pick({ !it.syncedLyrics.isNullOrBlank() }, STRICT_DURATION_DELTA)
                    ?: pick({ !it.syncedLyrics.isNullOrBlank() }, RELAXED_DURATION_DELTA)
                    ?: matched.firstOrNull { !it.record.syncedLyrics.isNullOrBlank() }?.record
                    // Plain lyrics: same tiers.
                    ?: pick({ !it.plainLyrics.isNullOrBlank() }, STRICT_DURATION_DELTA)
                    ?: pick({ !it.plainLyrics.isNullOrBlank() }, RELAXED_DURATION_DELTA)
                    ?: matched.firstOrNull { !it.record.plainLyrics.isNullOrBlank() }?.record
                    ?: matched.firstOrNull { it.record.instrumental == true }?.record
            }
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** Exact-version pin: studio vs live/remix stay distinct. */
        const val STRICT_DURATION_DELTA = 8.0

        /** Music-video/intro lengths vs database audio lengths. */
        const val RELAXED_DURATION_DELTA = 30.0

        /**
         * Title match that also tolerates dirty community titles carrying an
         * "Artist - Title" prefix (e.g. trackName "Ed Sheeran - Shape Of You
         * [Official Video]"): the prefix-stripped variant is tried too.
         *
         * Substring matching is length-gated: bare `contains` accepted "Love"
         * for "Love Me Like You Do" (wrong song's lyrics). Shorter side must
         * cover >=70% of the longer side, otherwise token overlap decides.
         */
        fun titlesMatch(candidateTitle: String, requestTitle: String): Boolean {
            val variants = listOf(candidateTitle, stripLeadingArtistPrefix(candidateTitle))
            return variants.any { cand -> strictTitleMatch(cand, requestTitle) }
        }

        private fun strictTitleMatch(a: String, b: String): Boolean {
            val ca = a.trim()
            val cb = b.trim()
            if (ca.isBlank() || cb.isBlank()) return false
            if (ca.equals(cb, ignoreCase = true)) return true
            if (ca.contains(cb, ignoreCase = true) || cb.contains(ca, ignoreCase = true)) {
                val ratio = minOf(ca.length, cb.length).toDouble() / maxOf(ca.length, cb.length)
                if (ratio >= 0.7) return true
            }
            return isSimilar(ca, cb)
        }

        /**
         * Artist agreement for search-result filtering. Bare `contains` both
         * ways accepted "Ann" for "Annie" or "John" for "John Lennon covers".
         * Exact wins; substring needs length cover; otherwise token overlap.
         */
        fun artistMatches(candidateArtist: String, requestArtist: String): Boolean {
            val ca = candidateArtist.trim()
            val ra = requestArtist.trim()
            if (ca.isBlank() || ra.isBlank()) return false
            if (ca.equals(ra, ignoreCase = true)) return true
            if (ca.contains(ra, ignoreCase = true) || ra.contains(ca, ignoreCase = true)) {
                if (minOf(ca.length, ra.length) < 4) return isSimilar(ca, ra)
                val ratio = minOf(ca.length, ra.length).toDouble() / maxOf(ca.length, ra.length)
                if (ratio >= 0.6) return true
            }
            return isSimilar(ca, ra)
        }

        /** Removes a leading "Artist - " / "Artist – " / "Artist: " segment. */
        fun stripLeadingArtistPrefix(raw: String): String {
            val stripped = raw.replace(
                Regex("""^\s*.+?\s*[-–—:]\s+(?=\S)"""),
                "",
            ).trim()
            // Guard against legit "A - B" song titles: only accept the strip
            // when something meaningful remains.
            return if (stripped.length >= 2) stripped else raw.trim()
        }

        /**
         * Recording-version tags that distinguish releases of one song.
         * Remaster/radio-edit style markers are deliberately absent: cleaning
         * already normalizes those, and they denote the same recording.
         */
        private val VERSION_KEYWORDS = mapOf(
            "live" to "live",
            "concert" to "live",
            "session" to "live",
            "unplugged" to "unplugged",
            "acoustic" to "acoustic",
            "remix" to "remix",
            "cover" to "cover",
            "karaoke" to "karaoke",
            "instrumental" to "instrumental",
            "slowed" to "slowed",
            "sped up" to "sped",
            "speed up" to "sped",
            "spedup" to "sped",
            "sped" to "sped",
            "nightcore" to "sped",
            "demo" to "demo",
            "lullaby" to "lullaby",
            "8d" to "8d",
        )

        /** Version tags found in brackets or a trailing "- X" suffix. */
        fun versionTags(rawTitle: String): Set<String> {
            val tags = mutableSetOf<String>()
            val segments = mutableListOf<String>()
            Regex("""[\(\[](.*?)[\)\]]""").findAll(rawTitle).forEach { segments.add(it.groupValues[1]) }
            Regex("""\s*[-–—:]\s*([^-–—:\(\[]+)\s*$""").find(rawTitle)?.let { segments.add(it.groupValues[1]) }
            for (segment in segments) {
                val lower = " $segment ".lowercase()
                for ((keyword, tag) in VERSION_KEYWORDS) {
                    if (lower.contains(keyword)) tags.add(tag)
                }
            }
            return tags
        }

        /** True only when both titles describe the same recording version. */
        fun sameVersion(requestTitle: String, candidateTitle: String): Boolean =
            versionTags(requestTitle) == versionTags(candidateTitle)

        fun isSimilar(s1: String, s2: String): Boolean {
            val a = s1.trim().lowercase()
            val b = s2.trim().lowercase()
            if (a == b) return true
            if (a.isEmpty() || b.isEmpty()) return false
            val aTokens = a.split(Regex("""\s+""")).toSet()
            val bTokens = b.split(Regex("""\s+""")).toSet()
            val intersection = aTokens.intersect(bTokens).size
            val union = aTokens.union(bTokens).size
            return if (union > 0) (intersection.toDouble() / union) >= 0.5 else false
        }

        fun cleanTrackTitle(raw: String): String {
            // Only credits and packaging are stripped here. Version markers
            // (remix, live, acoustic, edit, version, extended, remastered)
            // name a different recording and must survive cleaning so the
            // version check can reject the wrong cut.
            var out = raw
            out = out.replace(
                Regex("""(?i)\s*[\(\[](?:feat\.?|ft\.?|featuring|with)[^\)\]]*[\)\]]"""),
                "",
            )
            out = out.replace(Regex("""(?i)\s+(?:feat\.?|ft\.?|featuring)\s+.*$"""), "")
            out = out.replace(
                Regex("""(?i)\s*[\(\[]\s*(?:official\s*)?(?:music\s*)?(?:video|audio|visualizer|lyrics?\s*video|lyrics?|m/?v|hd|hq|4k|full\s*song)\s*[\)\]]"""),
                "",
            )
            out = out.replace(Regex("""(?i)\s*[\(\[]\s*official\s*[\)\]]"""), "")
            return out.replace(Regex("""\s+"""), " ").trim().ifBlank { raw.trim() }
        }

        fun cleanArtistName(raw: String): String {
            return raw
                .replace(Regex("""(?i)\s*[\(\[](?:feat\.?|ft\.?)[^\)\]]*[\)\]]"""), "")
                .replace(Regex("""(?i)\s*(?:feat\.?|ft\.?)\s+.*$"""), "")
                .trim()
        }
    }
}
