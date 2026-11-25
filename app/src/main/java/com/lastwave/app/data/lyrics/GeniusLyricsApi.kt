package com.lastwave.app.data.lyrics

import com.lastwave.app.data.artwork.awaitSuccessfulBodyOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

private const val GENIUS_UA = "LastWave"
private const val SKIP_SELECTOR =
    "[data-exclude-from-selection=true], .LyricsHeader__Container, .SongBioPreview__Container, .InreadAd__Container, button, script, style"

private val splitSeparatorRegex = Regex("""\s*[-–—:]\s*""")
private val symbolNoiseRegex = Regex("""[♪♫★☆【】《》「」~_]""")
private val prodSuffixRegex = Regex("""(?i)\b(?:prod(?:uced)?\.?(?:\s+by)?)\s+.*$""")
private val tagNoiseRegex = Regex(
    """\s*[(\[]\s*(?:from|feat\.?|ft\.?|featuring|with|prod\.?|produced by|official|lyrical|video|audio|remix|music video|visualizer|mv|hd|4k|hq|full song)[^)\]]*[)\]]|""" +
        """\s*\b(?:official\s+(?:music\s+)?(?:video|audio)|lyrical(?:\s+video)?|full\s+song|4k\s+video|hd\s+video|music\s+video)\b""",
    RegexOption.IGNORE_CASE,
)
private val parensRegex = Regex("""\s*[\(\[].*?[\)\]]""")
private val nonWordRegex = Regex("[^\\p{L}\\p{N}\\s]")
private val multiSpaceRegex = Regex("""\s+""")
private val recommendedRegex = Regex("""\d*You might also like""", RegexOption.IGNORE_CASE)
private val embedFooterRegex = Regex("""\d*Embed\s*$""", RegexOption.IGNORE_CASE)

@Singleton
class GeniusLyricsApi @Inject constructor(
    private val http: OkHttpClient,
) {
    private val decoder = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private data class QueryCandidate(
        val queryText: String,
        val expectedTitle: String,
        val expectedArtist: String,
    )

    suspend fun fetchLyrics(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        try {
            loadSongLyrics(title, artist)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseText(html: String): String? = try {
        val document = Jsoup.parse(html)
        val blocks = document.select("div[data-lyrics-container=true]")
            .takeIf { it.isNotEmpty() }
            ?: document.select("div.lyrics")

        if (blocks.isEmpty()) {
            null
        } else {
            val collected = StringBuilder()
            for (block in blocks) {
                block.select(SKIP_SELECTOR).remove()
                for (br in block.select("br")) {
                    br.replaceWith(TextNode("\n"))
                }
                for (paragraph in block.select("p")) {
                    paragraph.prepend("\n")
                }
                val content = block.wholeText()
                if (content.isNotBlank()) {
                    collected.append(content).append('\n')
                }
            }
            stripArtifacts(collected.toString()).takeIf { it.isNotBlank() }
        }
    } catch (_: Exception) {
        null
    }

    internal fun stripArtifacts(raw: String): String {
        val normalizedSpaces = raw
            .replace('\u00A0', ' ')
            .replace('\u200B', ' ')
            .replace('\uFEFF', ' ')
        return normalizedSpaces
            .replace(recommendedRegex, "")
            .trim()
            .replace(embedFooterRegex, "")
            .trim()
    }

    private suspend fun loadSongLyrics(rawTitle: String, rawArtist: String): String? {
        val sanitizedTitle = sanitize(rawTitle)
        val sanitizedArtist = sanitize(rawArtist)
        val (songTitle, songArtist) = extractPair(sanitizedTitle, sanitizedArtist)

        var matchedUrl: String? = null
        for (candidate in candidateQueries(sanitizedTitle, sanitizedArtist, songTitle, songArtist)) {
            matchedUrl = findTrackPage(candidate.queryText, candidate.expectedTitle, candidate.expectedArtist)
            if (matchedUrl != null) break
        }
        if (matchedUrl == null) return null

        val pageHtml = downloadText(matchedUrl)
        if (pageHtml.isNullOrBlank()) return null

        return parseText(pageHtml)?.takeIf { it.isNotBlank() }
    }

    private fun extractPair(cleanTitle: String, cleanArtist: String): Pair<String, String> {
        if (!cleanTitle.contains(splitSeparatorRegex)) return cleanTitle to cleanArtist

        val segments = cleanTitle.split(splitSeparatorRegex, limit = 2)
        val left = segments[0].trim()
        val right = segments[1].trim()

        val title = when {
            left.equals(cleanArtist, ignoreCase = true) -> right
            right.equals(cleanArtist, ignoreCase = true) -> left
            left.isNotEmpty() && right.isNotEmpty() -> right
            else -> cleanTitle
        }
        val artist = when {
            left.equals(cleanArtist, ignoreCase = true) -> cleanArtist
            right.equals(cleanArtist, ignoreCase = true) -> cleanArtist
            cleanArtist.isEmpty() -> left
            else -> cleanArtist
        }
        return title to artist
    }

    private fun candidateQueries(
        cleanTitle: String,
        cleanArtist: String,
        songTitle: String,
        songArtist: String,
    ): List<QueryCandidate> {
        val strippedTitle = songTitle
            .replace(parensRegex, " ")
            .replace(nonWordRegex, " ")
            .replace(multiSpaceRegex, " ")
            .trim()

        val list = ArrayList<QueryCandidate>(6)
        if (songArtist.isNotEmpty() && songTitle.isNotEmpty()) {
            list.add(QueryCandidate("$songArtist $songTitle".trim(), songTitle, songArtist))
        }
        if (songArtist.isNotEmpty() && strippedTitle.isNotEmpty() && strippedTitle != songTitle) {
            list.add(QueryCandidate("$songArtist $strippedTitle".trim(), strippedTitle, songArtist))
        }
        if (cleanTitle != songTitle) {
            list.add(QueryCandidate("$cleanArtist $cleanTitle".trim(), cleanTitle, cleanArtist))
            list.add(QueryCandidate(cleanTitle, songTitle, songArtist))
        }
        if (strippedTitle.isNotEmpty()) {
            list.add(QueryCandidate(strippedTitle, strippedTitle, songArtist))
        } else if (songTitle.isNotEmpty()) {
            list.add(QueryCandidate(songTitle, songTitle, songArtist))
        }
        return list.distinctBy(QueryCandidate::queryText)
    }

    private suspend fun findTrackPage(query: String, wantTitle: String, wantArtist: String): String? {
        val searchUrl = "https://genius.com/api/search/multi?q=${URLEncoder.encode(query, "UTF-8")}"
        val payload = downloadText(searchUrl) ?: return null

        return try {
            val root = decoder.parseToJsonElement(payload).jsonObject
            val sections = root["response"]?.jsonObject?.get("sections")?.jsonArray ?: return null
            val songBlock = sections
                .mapNotNull { it as? JsonObject }
                .firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "song" }
                ?: return null

            val results = songBlock["hits"]?.jsonArray
                ?.mapNotNull { (it as? JsonObject)?.get("result")?.jsonObject }
                ?: return null

            pickTopHit(results, wantTitle, wantArtist)?.get("url")?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }

    private fun pickTopHit(
        items: List<JsonObject>,
        wantTitle: String,
        wantArtist: String,
    ): JsonObject? {
        if (items.isEmpty()) return null

        val targetTitle = wantTitle.lowercase()
        val targetArtist = wantArtist.lowercase()
        var chosen: JsonObject? = null
        var highestScore = 0

        for (entry in items) {
            val hitTitle = entry["title"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            val hitArtist = entry["artist_names"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()

            val titleMatch = targetTitle.isNotEmpty() &&
                (hitTitle == targetTitle || hitTitle.contains(targetTitle) || targetTitle.contains(hitTitle))
            val artistMatch = targetArtist.isNotEmpty() &&
                (hitArtist == targetArtist || hitArtist.contains(targetArtist) || targetArtist.contains(hitArtist))
            if (!titleMatch && !artistMatch) continue

            var points = 0
            if (hitTitle == targetTitle) {
                points += 50
            } else if (titleMatch) {
                points += 25
            }
            if (artistMatch) {
                points += if (hitArtist == targetArtist) 40 else 20
            }

            val slug = entry["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (slug.contains("translation", ignoreCase = true) && !targetTitle.contains("translation")) {
                points -= 30
            }
            if (slug.contains("türkçe", ignoreCase = true) || slug.contains("polskie-tlumaczenie", ignoreCase = true)) {
                points -= 40
            }
            if (slug.contains("tracklist", ignoreCase = true) || slug.contains("album-art", ignoreCase = true)) {
                points -= 50
            }

            if (points > highestScore) {
                highestScore = points
                chosen = entry
            }
        }
        return chosen
    }

    private fun sanitize(input: String): String {
        val stripped = input
            .replace(symbolNoiseRegex, " ")
            .replace(tagNoiseRegex, " ")
            .replace(prodSuffixRegex, " ")
            .substringBefore(" | ")
            .replace(multiSpaceRegex, " ")
            .trim()
        return stripped.ifEmpty { input.trim() }
    }

    private suspend fun downloadText(targetUrl: String): String? {
        val req = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", GENIUS_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
        return try {
            http.newCall(req).awaitSuccessfulBodyOrNull()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}
