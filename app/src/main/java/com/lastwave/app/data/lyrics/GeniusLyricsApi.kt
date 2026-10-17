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

/**
 * Web scraper for Genius.com lyrics, used strictly as a last resort when
 * none of the time-synced providers have lyrics for a track. Genius has no
 * timestamps but an immense catalogue.
 *
 * The flow: search the open multi-search endpoint (no API key), match the
 * best candidate, fetch its page and pull the text out of the
 * `data-lyrics-container` blocks — section headers ("[Verse 1]") kept.
 */
@Singleton
class GeniusLyricsApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun fetchLyrics(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        try {
            scrape(title, artist)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun scrape(title: String, artist: String): String? {
        val cleanTitle = cleanQuery(title)
        val cleanArtist = cleanQuery(artist)

        // If the title is in "Artist - Title" format, take both parts apart.
        val titleParts = if (cleanTitle.contains(TITLE_SEPARATOR)) {
            cleanTitle.split(TITLE_SEPARATOR, limit = 2)
        } else null

        val extractedTitle = when {
            titleParts != null && titleParts[0].trim().equals(cleanArtist, ignoreCase = true) -> titleParts[1].trim()
            titleParts != null && titleParts[1].trim().equals(cleanArtist, ignoreCase = true) -> titleParts[0].trim()
            titleParts != null && titleParts[0].isNotBlank() && titleParts[1].isNotBlank() -> titleParts[1].trim()
            else -> cleanTitle
        }

        val extractedArtist = when {
            titleParts != null && titleParts[0].trim().equals(cleanArtist, ignoreCase = true) -> cleanArtist
            titleParts != null && titleParts[1].trim().equals(cleanArtist, ignoreCase = true) -> cleanArtist
            titleParts != null && cleanArtist.isBlank() -> titleParts[0].trim()
            else -> cleanArtist
        }

        val titleWithoutBrackets = extractedTitle
            .replace(BRACKETED_CONTENT, " ")
            .replace(NON_ALPHANUMERIC, " ")
            .replace(WHITESPACE, " ")
            .trim()

        val attempts = buildList {
            if (extractedArtist.isNotBlank() && extractedTitle.isNotBlank()) {
                add(SearchAttempt("$extractedArtist $extractedTitle".trim(), extractedTitle, extractedArtist))
            }
            if (extractedArtist.isNotBlank() && titleWithoutBrackets.isNotBlank() && titleWithoutBrackets != extractedTitle) {
                add(SearchAttempt("$extractedArtist $titleWithoutBrackets".trim(), titleWithoutBrackets, extractedArtist))
            }
            if (cleanTitle != extractedTitle) {
                add(SearchAttempt("$cleanArtist $cleanTitle".trim(), cleanTitle, cleanArtist))
                add(SearchAttempt(cleanTitle, extractedTitle, extractedArtist))
            }
            if (titleWithoutBrackets.isNotBlank()) {
                add(SearchAttempt(titleWithoutBrackets, titleWithoutBrackets, extractedArtist))
            } else if (extractedTitle.isNotBlank()) {
                add(SearchAttempt(extractedTitle, extractedTitle, extractedArtist))
            }
        }.distinctBy { it.query }

        val songUrl = attempts.firstNotNullOfOrNull { attempt ->
            searchSongUrl(attempt.query, attempt.title, attempt.artist)
        } ?: return null

        val html = httpGet(songUrl)?.takeIf { it.isNotBlank() } ?: return null
        return parseText(html)?.takeIf { it.isNotBlank() }
    }

    private suspend fun searchSongUrl(query: String, targetTitle: String, targetArtist: String): String? {
        val url = "https://genius.com/api/search/multi?q=${URLEncoder.encode(query, "UTF-8")}"
        val body = httpGet(url) ?: return null
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val response = root["response"]?.jsonObject ?: return null
            val sections = response["sections"]?.jsonArray ?: return null
            val songSection = sections.firstOrNull {
                (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "song"
            }?.jsonObject ?: return null
            val hits = songSection["hits"]?.jsonArray ?: return null
            val candidates = hits.mapNotNull { (it as? JsonObject)?.get("result")?.jsonObject }
            bestMatch(candidates, targetTitle, targetArtist)?.get("url")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    private fun bestMatch(
        candidates: List<JsonObject>,
        targetTitle: String,
        targetArtist: String,
    ): JsonObject? {
        if (candidates.isEmpty()) return null
        val normTitle = targetTitle.lowercase()
        val normArtist = targetArtist.lowercase()
        return candidates.mapNotNull { item ->
            val title = item["title"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: ""
            val artist = item["artist_names"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: ""
            var score = 0

            val titleMatches = normTitle.isNotBlank() &&
                (title == normTitle || title.contains(normTitle) || normTitle.contains(title))
            val artistMatches = normArtist.isNotBlank() &&
                (artist == normArtist || artist.contains(normArtist) || normArtist.contains(artist))
            if (!titleMatches && !artistMatches) return@mapNotNull null

            if (title == normTitle) score += 50 else if (titleMatches) score += 25
            if (artistMatches) score += if (artist == normArtist) 40 else 20

            // Threads and translations of the song, not the song itself.
            val path = item["path"]?.jsonPrimitive?.contentOrNull ?: ""
            if (path.contains("translation", ignoreCase = true) && !normTitle.contains("translation")) score -= 30
            if (path.contains("türkçe", ignoreCase = true) || path.contains("polskie-tlumaczenie", ignoreCase = true)) {
                score -= 40
            }
            if (path.contains("tracklist", ignoreCase = true) || path.contains("album-art", ignoreCase = true)) {
                score -= 50
            }

            if (score <= 0) return@mapNotNull null
            item to score
        }.maxByOrNull { it.second }?.first
    }

    /**
     * Extracts the lyrics text while preserving stanzas and section headers.
     */
    internal fun parseText(html: String): String? {
        return try {
            val doc = Jsoup.parse(html)
            // Modern Genius uses data-lyrics-container="true", older pages div.lyrics.
            var containers = doc.select("div[data-lyrics-container=true]")
            if (containers.isEmpty()) containers = doc.select("div.lyrics")
            if (containers.isEmpty()) {
                null
            } else {
                val builder = StringBuilder()
                for (container in containers) {
                    container.select(
                        "[data-exclude-from-selection=true], " +
                            ".LyricsHeader__Container, " +
                            ".SongBioPreview__Container, " +
                            ".InreadAd__Container, " +
                            "button, " +
                            "script, " +
                            "style",
                    ).remove()
                    // Turn <br> into explicit newlines before extracting text.
                    container.select("br").forEach { it.replaceWith(TextNode("\n")) }
                    container.select("p").forEach { it.prepend("\n") }
                    val text = container.wholeText()
                    if (text.isNotBlank()) builder.append(text).append("\n")
                }
                stripArtifacts(builder.toString()).takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Cleans the artifacts a scraped page carries: recommendation inserts,
     *  a trailing "Embed", unicode spaces. Section headers stay. */
    internal fun stripArtifacts(raw: String): String =
        raw.replace('\u00A0', ' ')
            .replace('\u200B', ' ')
            .replace('\uFEFF', ' ')
            .replace(YOU_MIGHT_ALSO_LIKE, "")
            .trim()
            .replace(TRAILING_EMBED, "")
            .trim()

    private fun cleanQuery(text: String): String {
        val cleaned = text
            .replace(DECORATIVE_CHARS, " ")
            .replace(NOISE, " ")
            .replace(PRODUCER_TAGS, " ")
            .substringBefore(" | ")
            .replace(WHITESPACE, " ")
            .trim()
        return cleaned.ifBlank { text.trim() }
    }

    private suspend fun httpGet(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
        return try {
            okHttpClient.newCall(request).awaitSuccessfulBodyOrNull()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    private data class SearchAttempt(
        val query: String,
        val title: String,
        val artist: String,
    )

    companion object {
        /**
         * Deliberately not a browser. Genius sits behind Cloudflare, which
         * challenges a browser-claiming User-Agent whose TLS fingerprint does
         * not back the claim; a plain one is answered normally.
         */
        private const val USER_AGENT = "LastWave"

        private val TITLE_SEPARATOR = Regex("""\s*[-–—:]\s*""")
        private val DECORATIVE_CHARS = Regex("""[♪♫★☆【】《》「」~_]""")
        private val PRODUCER_TAGS = Regex("""(?i)\b(?:prod(?:uced)?\.?(?:\s+by)?)\s+.*$""")
        private val NOISE = Regex(
            """\s*[(\[]\s*(?:from|feat\.?|ft\.?|featuring|with|prod\.?|produced by|official|lyrical|video|audio|remix|music video|visualizer|mv|hd|4k|hq|full song)[^)\]]*[)\]]|""" +
                """\s*\b(?:official\s+(?:music\s+)?(?:video|audio)|lyrical(?:\s+video)?|full\s+song|4k\s+video|hd\s+video|music\s+video)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val BRACKETED_CONTENT = Regex("""\s*[\(\[].*?[\)\]]""")
        private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}\\s]")
        private val WHITESPACE = Regex("""\s+""")
        private val YOU_MIGHT_ALSO_LIKE = Regex("""\d*You might also like""", RegexOption.IGNORE_CASE)
        private val TRAILING_EMBED = Regex("""\d*Embed\s*$""", RegexOption.IGNORE_CASE)
    }
}
