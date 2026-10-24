package com.lastwave.app.data.khinsider

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

/** One album row from a KHInsider search page. */
data class KhAlbumSummary(
    val id: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val platform: String? = null,
    val type: String? = null,
    val year: String? = null,
)

/** One track row of an album page. [pagePath] is the raw href, kept encoded exactly as KHInsider writes it. */
data class KhTrack(
    val pagePath: String,
    val name: String,
    val number: Int? = null,
    val disc: Int? = null,
    val durationSeconds: Int? = null,
)

data class KhAlbum(
    val id: String,
    val name: String,
    val coverUrl: String? = null,
    val platforms: List<String> = emptyList(),
    val year: String? = null,
    val publisher: String? = null,
    val developer: String? = null,
    val type: String? = null,
    val formats: List<String> = emptyList(),
    val tracks: List<KhTrack> = emptyList(),
) {
    val hasFlac: Boolean get() = formats.any { it.equals("flac", ignoreCase = true) }
}

/** One page of a KHInsider album list (top lists, a platform, a year). */
data class KhAlbumPage(
    val albums: List<KhAlbumSummary>,
    val page: Int,
    val hasNext: Boolean,
)

/** A browsable KHInsider list: [path] is relative to the site, e.g. "/top40". */
data class KhBrowseList(val id: String, val title: String, val path: String, val paged: Boolean = true)

/** Audio file format a user can prefer. MP3 is always present on KHInsider. */
enum class KhFormat(val extension: String) { FLAC("flac"), MP3("mp3") }

/**
 * Pure HTML parsing for downloads.khinsider.com. KHInsider has no API, so
 * this reads the same pages a browser shows. Parsing goes by link targets
 * and cell contents rather than fixed column positions, so a reordered or
 * extra column does not break it.
 */
object KhInsiderParser {
    const val BASE_URL = "https://downloads.khinsider.com"
    private const val ALBUM_PREFIX = "/game-soundtracks/album/"
    private val KNOWN_FORMATS = setOf("mp3", "flac", "ogg", "m4a", "aac", "wav", "opus")
    private val DURATION = Regex("""^(\d{1,2}:)?\d{1,2}:\d{2}$""")
    private val TRACK_NUMBER = Regex("""^(\d+)\.$""")
    private val FILE_LINK = Regex("""^https?://[^/]+/(?:soundtracks|ost)/.+""", RegexOption.IGNORE_CASE)

    fun searchUrl(query: String): String =
        "$BASE_URL/search?search=${URLEncoder.encode(query.trim(), "UTF-8")}"

    /** Popular lists, as linked from the site's menu. */
    val POPULAR_LISTS = listOf(
        KhBrowseList("top40", "Top 40", "/top40", paged = false),
        KhBrowseList("top6m", "Top 100 · 6 mois", "/last-6-months-top-100", paged = false),
        KhBrowseList("topnew", "Top 100 · nouveautés", "/top-100-newly-added", paged = false),
        KhBrowseList("favorites", "Les plus favoris", "/most-favorites", paged = false),
        KhBrowseList("alltime", "Top 1000 de tous les temps", "/all-time-top-1000", paged = false),
    )

    /** Main platforms from the site's menu (slug -> label); the full list comes from /console-list. */
    val MAIN_PLATFORMS = listOf(
        "nintendo-switch" to "Switch", "switch-2" to "Switch 2", "playstation-5" to "PS5",
        "playstation-4" to "PS4", "xbox-one" to "Xbox One", "windows" to "Windows", "steam" to "Steam",
        "nintendo-3ds" to "3DS", "nintendo-ds" to "DS", "nintendo-wii-u" to "Wii U", "nintendo-wii" to "Wii",
        "nintendo-gamecube" to "GameCube", "nintendo-64" to "N64", "nintendo-snes" to "SNES",
        "nintendo-nes" to "NES", "gameboy-advance" to "GBA", "gameboy" to "Game Boy",
        "playstation-3" to "PS3", "playstation-2" to "PS2", "playstation" to "PS1",
        "playstation-portable-psp" to "PSP", "playstation-vita" to "PS Vita",
        "xbox-360" to "Xbox 360", "xbox" to "Xbox",
    )

    fun platformList(slug: String, label: String) = KhBrowseList("platform:$slug", label, "/game-soundtracks/$slug")

    fun yearList(year: Int) = KhBrowseList("year:$year", year.toString(), "/game-soundtracks/year/$year")

    fun listUrl(path: String, page: Int): String =
        BASE_URL + path + if (page > 1) (if ('?' in path) "&" else "?") + "page=$page" else ""

    private val NON_PLATFORM_SLUGS = setOf(
        "album", "browse", "year", "publisher", "developer", "ost", "gamerips", "singles",
        "arrangements", "remixes", "compilations", "inspired-by",
    )

    /** Every platform on /console-list, as (slug, label), in page order. */
    fun parsePlatforms(html: String): List<Pair<String, String>> {
        val doc = Jsoup.parse(html, BASE_URL)
        val content = doc.getElementById("pageContent") ?: doc.body()
        val out = LinkedHashMap<String, String>()
        for (a in content.select("a[href]")) {
            val path = a.absUrl("href").substringAfter("khinsider.com", "").substringBefore('?')
            if (!path.startsWith("/game-soundtracks/")) continue
            val slug = path.removePrefix("/game-soundtracks/").trimEnd('/')
            if (slug.isBlank() || '/' in slug || slug in NON_PLATFORM_SLUGS) continue
            val label = a.text().trim()
            if (label.isNotBlank() && slug !in out) out[slug] = label
        }
        return out.map { it.key to it.value }
    }

    /** An album list page plus whether a following page exists. */
    fun parseAlbumPage(html: String, page: Int): KhAlbumPage {
        val doc = Jsoup.parse(html, BASE_URL)
        val albums = parseSearch(doc)
        val next = "page=${page + 1}"
        val hasNext = doc.select("a[href]").any { a ->
            val href = a.attr("href")
            href.contains("?$next") || href.contains("&$next")
        }
        return KhAlbumPage(albums, page, hasNext)
    }

    fun albumUrl(albumId: String): String = "$BASE_URL$ALBUM_PREFIX$albumId"

    fun trackPageUrl(pagePath: String): String =
        if (pagePath.startsWith("http")) pagePath else BASE_URL + pagePath

    /** Album id from any album or track URL/path, e.g. ".../album/minecraft/1-01.mp3" -> "minecraft". */
    fun albumIdOf(urlOrPath: String): String? {
        val idx = urlOrPath.indexOf(ALBUM_PREFIX)
        if (idx < 0) return null
        return urlOrPath.substring(idx + ALBUM_PREFIX.length)
            .substringBefore('?').substringBefore('#').substringBefore('/')
            .takeIf { it.isNotBlank() }
    }

    fun isAlbumPage(finalUrl: String): Boolean {
        val path = finalUrl.substringAfter("://").substringAfter('/', "")
        return ("/$path").startsWith(ALBUM_PREFIX) && albumIdOf(finalUrl) != null &&
            ("/$path").removePrefix(ALBUM_PREFIX).trimEnd('/').count { it == '/' } == 0
    }

    /** Cloudflare interstitials ("Just a moment…" JS challenge or a hard block page). */
    fun isCloudflareChallenge(html: String): Boolean {
        val head = html.take(6000)
        return head.contains("challenge-platform") || head.contains("cf-chl") ||
            head.contains("<title>Just a moment", ignoreCase = true)
    }

    fun isCloudflareBlock(html: String): Boolean =
        html.take(6000).contains("Attention Required! | Cloudflare")

    fun parseSearch(html: String): List<KhAlbumSummary> = parseSearch(Jsoup.parse(html, BASE_URL))

    fun parseSearch(doc: Document): List<KhAlbumSummary> {
        val table = doc.selectFirst("table.albumList") ?: doc.select("table").firstOrNull { t ->
            t.select("a[href]").any { albumIdOf(it.attr("href")) != null }
        } ?: return emptyList()
        val rows = table.select("tr")
        val header = rows.firstOrNull { it.selectFirst("th") != null }
        val headerNames = header?.children()?.map { it.text().trim().lowercase() }.orEmpty()
        fun col(name: String) = headerNames.indexOfFirst { it == name }
        val platformCol = col("platform")
        val typeCol = col("type")
        val yearCol = col("year")
        val seen = LinkedHashMap<String, KhAlbumSummary>()
        for (row in rows) {
            if (row === header) continue
            val cells = row.children().filter { it.tagName() == "td" }
            if (cells.isEmpty()) continue
            val nameLink = row.select("a[href]").firstOrNull { a ->
                albumIdOf(a.attr("href")) != null && a.text().isNotBlank()
            } ?: continue
            val id = albumIdOf(nameLink.attr("href")) ?: continue
            if (id in seen) continue
            fun cell(i: Int) = cells.getOrNull(i)?.text()?.trim()?.takeIf { it.isNotBlank() }
            seen[id] = KhAlbumSummary(
                id = id,
                name = nameLink.text().trim(),
                thumbnailUrl = row.selectFirst("img[src]")?.absUrl("src")?.takeIf { it.isNotBlank() },
                platform = if (platformCol >= 0) cell(platformCol) else null,
                type = if (typeCol >= 0) cell(typeCol) else null,
                year = if (yearCol >= 0) cell(yearCol) else null,
            )
        }
        return seen.values.toList()
    }

    fun parseAlbum(albumId: String, html: String): KhAlbum? =
        parseAlbum(albumId, Jsoup.parse(html, albumUrl(albumId)))

    fun parseAlbum(albumId: String, doc: Document): KhAlbum? {
        val content = doc.getElementById("pageContent") ?: doc.body()
        val table = content.getElementById("songlist") ?: return null
        val name = content.selectFirst("h2")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: content.selectFirst("h2")?.text()?.trim()
            ?: albumId

        val headerCells = (table.getElementById("songlist_header") ?: table.selectFirst("tr:has(th)"))
            ?.select("th")?.map { it.text().trim() }.orEmpty()
        val formats = headerCells.map { it.lowercase() }.filter { it in KNOWN_FORMATS }.distinct()
            .ifEmpty { listOf("mp3") }

        val tracks = mutableListOf<KhTrack>()
        for (row in table.select("tr")) {
            if (row.selectFirst("th") != null || row.id() == "songlist_footer") continue
            val link = row.select("a[href]").firstOrNull { a ->
                val href = a.attr("href")
                href.contains("$ALBUM_PREFIX$albumId/") && a.text().isNotBlank()
            } ?: continue
            val cells = row.children().filter { it.tagName() == "td" }.map { it.text().trim() }
            val numberIdx = cells.indexOfFirst { TRACK_NUMBER.matches(it) }
            val number = numberIdx.takeIf { it >= 0 }?.let { TRACK_NUMBER.find(cells[it])?.groupValues?.get(1)?.toIntOrNull() }
            val disc = numberIdx.takeIf { it > 0 }?.let { cells[it - 1].toIntOrNull() }
            val duration = cells.firstOrNull { DURATION.matches(it) }?.let(::parseDuration)
            tracks += KhTrack(
                pagePath = link.attr("href").let { href ->
                    if (href.startsWith("http")) href.substringAfter("khinsider.com") else href
                },
                name = link.text().trim(),
                number = number,
                disc = disc,
                durationSeconds = duration,
            )
        }

        val info = content.select("p").firstOrNull { p ->
            val t = p.text()
            t.contains("Platforms:") || t.contains("Year:") || t.contains("Number of Files:")
        }
        val infoText = info?.lineText().orEmpty()
        fun field(label: String): String? = infoText.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$label:") }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotBlank() }
        val platforms = field("Platforms")?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

        val cover = content.select("img[src]").map { it.absUrl("src") }
            .firstOrNull { it.contains("/soundtracks/$albumId/") }

        return KhAlbum(
            id = albumId,
            name = name,
            coverUrl = cover,
            platforms = platforms.distinct(),
            year = field("Year"),
            publisher = field("Published by"),
            developer = field("Developed by"),
            type = field("Album type"),
            formats = formats,
            tracks = tracks,
        )
    }

    /** All audio file links on a track page, keyed by lower-case extension, in page order. */
    fun parseTrackFiles(html: String, pageUrl: String = BASE_URL): Map<String, String> {
        val doc = Jsoup.parse(html, pageUrl)
        val out = LinkedHashMap<String, String>()
        for (a in doc.select("a[href]")) {
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (!FILE_LINK.matches(href)) continue
            val ext = href.substringBefore('?').substringAfterLast('.', "").lowercase()
            if (ext in KNOWN_FORMATS && ext !in out) out[ext] = href
        }
        return out
    }

    /** The preferred format's file, falling back to MP3 and then any audio file. */
    fun pickFile(files: Map<String, String>, preferred: KhFormat): String? =
        files[preferred.extension] ?: files["mp3"] ?: files.values.firstOrNull()

    fun parseDuration(text: String): Int? {
        val parts = text.trim().split(':').map { it.toIntOrNull() ?: return null }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }

    // --- Playback URIs -----------------------------------------------------
    // A queued KHInsider track carries its *track page*, not the file URL:
    // the file link is read from the page when the player opens it, so the
    // FLAC/MP3 choice applies at play time and saved queues never go stale.

    const val SCHEME = "khinsider"
    private const val URI_PREFIX = "$SCHEME://track?page="

    fun playbackUri(pagePath: String): String = URI_PREFIX + URLEncoder.encode(pagePath, "UTF-8")

    fun isPlaybackUri(uri: String?): Boolean = uri?.startsWith(URI_PREFIX) == true

    fun pagePathOf(playbackUri: String): String? {
        if (!isPlaybackUri(playbackUri)) return null
        val raw = playbackUri.removePrefix(URI_PREFIX).substringBefore('&')
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()?.takeIf { it.startsWith("/") }
    }

    /** Text with each <br>-separated label on its own line. */
    private fun Element.lineText(): String {
        val copy = clone()
        copy.select("br").forEach { it.after("\n") }
        return copy.wholeText()
    }
}
