package com.lastwave.app.data.lyrics

import com.lastwave.app.data.artwork.awaitSuccessfulBodyOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Line-synced community LRC scraped from megalobiz.com: search the site,
 * follow the first LRC download link, pull the stamped rows out of the page.
 * Two hops against an old site, so it sits low in the fallback chain.
 */
@Singleton
class MegalobizLyricsApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    suspend fun fetchLyrics(title: String, artist: String): List<LyricLine>? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val searchUrl = "https://www.megalobiz.com/searchall".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("qry", "$artist $title".trim())
            ?.build()
            ?: return@withContext null
        val results = get(searchUrl.toString()) ?: return@withContext null
        val path = lrcPathFromSearch(results) ?: return@withContext null
        val page = get(BASE + path.replace("&amp;", "&")) ?: return@withContext null
        lrcLinesFromPage(page)
    }

    private suspend fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
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

    internal fun lrcPathFromSearch(results: String): String? =
        LRC_LINK.find(results)?.groupValues?.get(1)

    internal fun lrcLinesFromPage(page: String): List<LyricLine>? {
        val raw = LRC_BODY.find(page)?.groupValues?.get(1) ?: return null
        val lrc = raw.replace(BR_TAG, "\n")
            .replace(HTML_TAG, "")
            .let { text -> LyricsRepository.decodeEntities(text) }
        return LyricsRepository.parseLrc(lrc).takeIf { lines -> lines.any { it.text.isNotBlank() } }
    }

    companion object {
        private const val BASE = "https://www.megalobiz.com"
        private const val USER_AGENT = "LastWave-Android/1.0 (https://github.com/clash-projects/lastwave)"

        private val LRC_LINK = Regex("""href=["'](/lrc/maker/download/[^"']+)["']""", RegexOption.IGNORE_CASE)
        private val LRC_BODY = Regex(
            """id=["']lrc_[^"']*_details["'][^>]*>(.*?)</span>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val BR_TAG = Regex("""(?i)<br\s*/?>""")
        private val HTML_TAG = Regex("""<[^>]+>""")
    }
}
