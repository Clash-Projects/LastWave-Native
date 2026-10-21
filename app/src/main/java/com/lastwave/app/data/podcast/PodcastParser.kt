package com.lastwave.app.data.podcast

import com.lastwave.app.data.music.array
import com.lastwave.app.data.music.collectObjects
import com.lastwave.app.data.music.obj
import com.lastwave.app.data.music.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parsers for the WEB_REMIX podcast surfaces. Episode metadata comes straight
 * from YouTube Music's own ordering; nothing here re-ranks or filters it.
 *
 * Shapes handled:
 *  - show card: `musicTwoRowItemRenderer` (browse id `MPSPPL…`)
 *  - landing/show episode: `musicMultiRowListItemRenderer`
 *  - episode search row: `musicResponsiveListItemRenderer`
 */
object PodcastParser {

    fun parseHome(root: JsonElement): List<PodcastShelf> {
        val carousels = mutableListOf<JsonObject>()
        collectObjects(root, "musicCarouselShelfRenderer", carousels)
        val shelves = mutableListOf<PodcastShelf>()
        for (carousel in carousels) {
            val title = shelfTitle(carousel) ?: continue
            val showRenderers = mutableListOf<JsonObject>()
            collectObjects(carousel, "musicTwoRowItemRenderer", showRenderers)
            val shows = showRenderers.mapNotNull { renderer -> parseShowCard(renderer) }
                .distinctBy { it.showId }
            val episodes = parseEpisodeRenderers(carousel)
            if (shows.isEmpty() && episodes.isEmpty()) continue
            shelves += PodcastShelf(title = title, shows = shows, episodes = episodes)
        }
        if (shelves.isNotEmpty()) return shelves

        val fallbackShows = mutableListOf<JsonObject>()
        collectObjects(root, "musicTwoRowItemRenderer", fallbackShows)
        val parsedFallback = fallbackShows.mapNotNull { renderer -> parseShowCard(renderer) }
            .distinctBy { it.showId }
        if (parsedFallback.isNotEmpty()) {
            return listOf(PodcastShelf(title = "Podcasts", shows = parsedFallback))
        }
        val episodes = parseEpisodeRenderers(root)
        return if (episodes.isEmpty()) emptyList() else listOf(PodcastShelf(title = "Podcasts", episodes = episodes))
    }

    fun parseShow(
        root: JsonElement,
        showId: String,
        fallbackTitle: String = "",
    ): PodcastShowPage {
        val headers = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveHeaderRenderer", headers)
        if (headers.isEmpty()) collectObjects(root, "musicDetailHeaderRenderer", headers)
        if (headers.isEmpty()) collectObjects(root, "musicImmersiveHeaderRenderer", headers)
        val header = headers.firstOrNull()
        val title = header?.let { text -> titleText(text) }?.takeIf { it.isNotBlank() }
            ?: fallbackTitle.takeIf { it.isNotBlank() }
            ?: "Podcast"
        val description = header?.let { h ->
            textOf(h.obj("description")) ?: textOf(h.obj("descriptionText"))
        }
        val publisher = header?.let { h ->
            textOf(h.obj("subtitle"))?.takeIf { it.isNotBlank() }
        }
        return PodcastShowPage(
            show = PodcastShow(
                showId = showId,
                title = title,
                publisher = publisher,
                description = description,
                artworkUrl = header?.let { element -> artworkUrl(element) },
            ),
            episodes = parseEpisodeRenderers(root).map { episode ->
                episode.copy(
                    showId = episode.showId ?: showId,
                    showTitle = episode.showTitle ?: title,
                )
            },
            continuation = episodeContinuation(root),
        )
    }

    fun parseEpisodeContinuation(
        root: JsonElement,
        showId: String? = null,
        showTitle: String? = null,
    ): PodcastEpisodePage = PodcastEpisodePage(
        episodes = parseEpisodeRenderers(root).map { episode ->
            episode.copy(
                showId = episode.showId ?: showId,
                showTitle = episode.showTitle ?: showTitle,
            )
        },
        continuation = episodeContinuation(root),
    )

    /**
     * Episode search rows.
     *
     * [requirePodcastMarker] is only used for the unfiltered fallback, where
     * songs and episodes share the same renderer: without a marker every song
     * would look like an episode. Filtered searches already contain only
     * episodes, so they do not need the marker.
     */
    fun parseEpisodeSearch(
        root: JsonElement,
        requirePodcastMarker: Boolean = false,
    ): List<PodcastEpisode> {
        val renderers = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveListItemRenderer", renderers)
        return renderers.mapNotNull { renderer ->
            if (!isEpisodeSearchRow(renderer, requirePodcastMarker)) return@mapNotNull null
            parseResponsiveEpisode(renderer)
        }.distinctBy { it.videoId }
    }

    /** The `Episodes` chip's search params, used to retry with a fresh filter token. */
    fun episodeSearchParams(root: JsonElement): String? {
        val chips = mutableListOf<JsonObject>()
        collectObjects(root, "chipCloudChipRenderer", chips)
        val episodeChip = chips.firstOrNull { chip ->
            val label = textOf(chip.obj("text"))?.lowercase().orEmpty()
            label.contains("episode")
        } ?: return null
        return findString(episodeChip, "params")?.takeIf { it.isNotBlank() }
    }

    fun parseShowCard(renderer: JsonObject): PodcastShow? {
        val showId = browseIds(renderer).firstOrNull { id ->
            id.startsWith("MPSPPL") || id.startsWith("MPED")
        } ?: return null
        val title = titleText(renderer)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val subtitle = textOf(renderer.obj("subtitle"))
        return PodcastShow(
            showId = showId,
            title = title,
            publisher = subtitle?.takeIf { it.isNotBlank() },
            artworkUrl = artworkUrl(renderer),
        )
    }

    private fun parseEpisodeRenderers(root: JsonElement): List<PodcastEpisode> {
        val multiRows = mutableListOf<JsonObject>()
        collectObjects(root, "musicMultiRowListItemRenderer", multiRows)
        val episodes = multiRows.mapNotNull { parseMultiRowEpisode(it) }.toMutableList()
        if (episodes.isEmpty()) {
            val responsive = mutableListOf<JsonObject>()
            collectObjects(root, "musicResponsiveListItemRenderer", responsive)
            episodes += responsive
                .filter { isEpisodeSearchRow(it, requirePodcastMarker = false) }
                .mapNotNull { renderer -> parseResponsiveEpisode(renderer) }
        }
        return episodes.distinctBy { it.videoId }.filter { it.videoId.isNotBlank() }
    }

    private fun parseMultiRowEpisode(renderer: JsonObject): PodcastEpisode? {
        val videoId = episodeVideoId(renderer) ?: return null
        val title = titleText(renderer)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val subtitleRuns = renderer.obj("subtitle")?.array("runs").orEmpty().mapNotNull { it.asText() }
        val subtitleText = subtitleRuns.joinToString(" • ").trim()
        val meta = episodeMeta(subtitleText)
        return PodcastEpisode(
            videoId = videoId,
            title = title,
            showId = browseIds(renderer).firstOrNull { it.startsWith("MPSPPL") },
            showTitle = meta.second,
            description = textOf(renderer.obj("description")),
            artworkUrl = artworkUrl(renderer),
            durationMs = durationMs(renderer),
            publishedLabel = meta.first,
        )
    }

    private fun parseResponsiveEpisode(renderer: JsonObject): PodcastEpisode? {
        val videoId = episodeVideoId(renderer) ?: return null
        val columns = renderer.array("flexColumns").orEmpty().toList()
        val title = columnText(columns, 0)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val subtitleText = columnText(columns, 1).orEmpty()
        val meta = episodeMeta(subtitleText)
        return PodcastEpisode(
            videoId = videoId,
            title = title,
            showId = browseIds(renderer).firstOrNull { it.startsWith("MPSPPL") },
            showTitle = meta.second,
            description = null,
            artworkUrl = artworkUrl(renderer),
            durationMs = durationMs(renderer),
            publishedLabel = meta.first,
        )
    }

    /**
     * A search row is an episode when it carries a podcast marker (page type,
     * `MPED`/`MPSPPL` browse id or a "Podcast" subtitle). Without the marker
     * only rows whose page type is explicitly a non-podcast one are rejected,
     * so a filtered episode search still returns its rows.
     */
    private fun isEpisodeSearchRow(renderer: JsonObject, requirePodcastMarker: Boolean): Boolean {
        if (hasPodcastMarker(renderer)) return true
        if (requirePodcastMarker) return false
        val pageType = findString(renderer, "pageType")
        return pageType == null || !pageType.contains("PODCAST", ignoreCase = true)
    }

    /** Podcast markers YouTube uses: a podcast page type, an `MPED`/`MPSPPL`
     *  browse id, a "Podcast" subtitle, or the podcast video type constant. */
    private fun hasPodcastMarker(renderer: JsonObject): Boolean {
        val pageType = findString(renderer, "pageType")
        if (pageType?.contains("PODCAST", ignoreCase = true) == true) return true
        if (findString(renderer, "videoType")?.contains("PODCAST", ignoreCase = true) == true) return true
        if (browseIds(renderer).any { it.startsWith("MPED") || it.startsWith("MPSPPL") }) return true
        if (columnText(renderer.array("flexColumns").orEmpty(), 1)
                ?.contains("podcast", ignoreCase = true) == true
        ) {
            return true
        }
        return renderer.toString().contains("PODCAST_EPISODE", ignoreCase = true)
    }

    private fun episodeVideoId(renderer: JsonObject): String? {
        val direct = renderer.obj("onTap")?.obj("watchEndpoint")?.string("videoId")
            ?: renderer.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: renderer.obj("playlistItemData")?.string("videoId")
            ?: renderer.obj("doubleTapCommand")?.obj("watchEndpoint")?.string("videoId")
        if (!direct.isNullOrBlank()) return direct
        val columns = renderer.array("flexColumns").orEmpty()
        for (column in columns) {
            val runs = column.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
                ?: continue
            for (run in runs) {
                val id = run.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
                if (!id.isNullOrBlank()) return id
            }
        }
        return findString(renderer, "videoId")?.takeIf { it.length == 11 }
    }

    /** Duration from the playback progress block, the length text or the fixed column. */
    private fun durationMs(renderer: JsonObject): Long? {
        val progress = renderer.obj("playbackProgress")?.obj("musicPlaybackProgressRenderer")
        parseClockDuration(textOf(progress?.obj("durationText")))?.let { return it }
        parseClockDuration(textOf(renderer.obj("lengthText")))?.let { return it }
        val fixed = renderer.array("fixedColumns").orEmpty()
        for (column in fixed) {
            val runs = column.obj("musicResponsiveListItemFixedColumnRenderer")?.obj("text")?.array("runs")
                ?: continue
            for (run in runs) {
                parseClockDuration(run.asText())?.let { return it }
            }
        }
        return null
    }

    /**
     * Splits a subtitle into (published label, show title). YouTube renders
     * these as `"902K views • 18h ago"` or `"18h ago • Deep Dive"`.
     */
    private fun episodeMeta(subtitle: String): Pair<String?, String?> {
        val segments = subtitle.split('•', '·')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (segments.isEmpty()) return null to null
        val published = segments.firstOrNull { segment -> isDateLabel(segment) }
            ?: segments.firstOrNull { segment -> !isStatSegment(segment) }
        val show = segments.lastOrNull { it != published && !isStatSegment(it) && !isDateLabel(it) }
        return published to show
    }

    private fun isDateLabel(value: String): Boolean =
        value.contains("ago", ignoreCase = true) ||
            MONTH_PATTERN.containsMatchIn(value) ||
            DAY_PATTERN.matches(value)

    private fun isStatSegment(value: String): Boolean {
        val lower = value.lowercase()
        return lower.contains("view") || lower.contains("play") || lower.contains("listen") ||
            lower.contains("episode") || lower.contains("min") || lower.contains("hr") ||
            lower.contains("season") || lower.contains("podcast")
    }

    /** `1:02:03`, `45 min`, `1 hr 29 min`. Anything else is not a duration. */
    private fun parseClockDuration(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val cleaned = value.trim()
        val colonParts = cleaned.split(':')
        if (colonParts.size in 2..3 && colonParts.all { it.trim().toIntOrNull() != null }) {
            val seconds = colonParts.fold(0L) { total, part -> total * 60L + part.trim().toLong() }
            return seconds.takeIf { it > 0L }?.times(1000L)
        }
        HOURS_MINUTES_REGEX.matchEntire(cleaned)?.let { match ->
            val hours = match.groupValues[1].toLongOrNull() ?: 0L
            val minutes = match.groupValues[2].toLongOrNull() ?: 0L
            val total = hours * 3600L + minutes * 60L
            return total.takeIf { it > 0L }?.times(1000L)
        }
        MINUTES_REGEX.matchEntire(cleaned)?.let { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: 0L
            return minutes.takeIf { it > 0L }?.times(60_000L)
        }
        return null
    }

    private fun episodeContinuation(root: JsonElement): String? {
        val shelves = mutableListOf<JsonObject>()
        collectObjects(root, "musicShelfRenderer", shelves)
        val episodeShelf = shelves.firstOrNull { shelf ->
            val multiRows = mutableListOf<JsonObject>()
            collectObjects(shelf, "musicMultiRowListItemRenderer", multiRows)
            multiRows.isNotEmpty()
        }
        return episodeShelf?.let { shelf -> continuationIn(shelf) } ?: continuationIn(root)
    }

    private fun continuationIn(element: JsonElement): String? {
        val commands = mutableListOf<JsonObject>()
        collectObjects(element, "continuationCommand", commands)
        commands.firstNotNullOfOrNull { it.string("token")?.takeIf(String::isNotBlank) }?.let { return it }
        val legacy = mutableListOf<JsonObject>()
        collectObjects(element, "nextContinuationData", legacy)
        return legacy.firstNotNullOfOrNull { it.string("continuation")?.takeIf(String::isNotBlank) }
    }

    private fun columnText(columns: List<JsonElement>, index: Int): String? =
        columns.getOrNull(index)?.asObject()
            ?.obj("musicResponsiveListItemFlexColumnRenderer")
            ?.obj("text")?.let { element -> textOf(element) }

    private fun shelfTitle(element: JsonObject): String? {
        val header = element.obj("header")
        val direct = textOf(element.obj("title"))
        if (!direct.isNullOrBlank()) return direct
        val candidates = listOf(
            header?.obj("musicCarouselShelfBasicHeaderRenderer")?.obj("title"),
            header?.obj("musicShelfHeaderRenderer")?.obj("title"),
            header?.obj("musicCardShelfHeaderBasicRenderer")?.obj("title"),
            header?.obj("musicHeaderRenderer")?.obj("title"),
        )
        return candidates.firstNotNullOfOrNull { textOf(it) }
    }

    private fun titleText(element: JsonObject): String? =
        textOf(element.obj("title")) ?: textOf(element.obj("name"))

    private fun textOf(element: JsonElement?): String? {
        if (element == null) return null
        if (element is JsonPrimitive) return element.contentOrNull?.takeIf { it.isNotBlank() }
        val obj = element.asObject() ?: return null
        obj.array("runs")?.joinToString("") { run ->
            (run.asObject()?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
        }?.takeIf { it.isNotBlank() }?.let { return it }
        return obj.string("simpleText")?.takeIf { it.isNotBlank() }
    }

    private fun artworkUrl(element: JsonElement): String? {
        val arrays = mutableListOf<JsonArray>()
        collectArrays(element, "thumbnails", arrays)
        val best = arrays.firstOrNull { it.isNotEmpty() } ?: return null
        val url = best.lastOrNull()?.asObject()?.string("url")
            ?: best.firstOrNull()?.asObject()?.string("url")
            ?: return null
        val absolute = if (url.startsWith("//")) "https:$url" else url
        return if ((absolute.contains("googleusercontent.com") || absolute.contains("ggpht.com")) &&
            '=' in absolute
        ) {
            absolute.substringBeforeLast('=') + "=w512-h512-l90-rj"
        } else {
            absolute
        }
    }

    private fun collectArrays(element: JsonElement, key: String, output: MutableList<JsonArray>) {
        when (element) {
            is JsonObject -> element.forEach { (name, child) ->
                if (name == key && child is JsonArray) output += child
                collectArrays(child, key, output)
            }
            is JsonArray -> element.forEach { collectArrays(it, key, output) }
            else -> Unit
        }
    }

    private fun browseIds(element: JsonElement): List<String> {
        val ids = mutableListOf<String>()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> node.forEach { (name, child) ->
                    if (name == "browseId") {
                        (child as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let(ids::add)
                    }
                    walk(child)
                }
                is JsonArray -> node.forEach { child -> walk(child) }
                else -> Unit
            }
        }
        walk(element)
        return ids
    }

    private fun findString(element: JsonElement, key: String): String? = when (element) {
        is JsonObject -> {
            (element[key] as? JsonPrimitive)?.contentOrNull
                ?: element.values.firstNotNullOfOrNull { findString(it, key) }
        }
        is JsonArray -> element.firstNotNullOfOrNull { findString(it, key) }
        else -> null
    }

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

    /** Text of a run object, or of a plain string. */
    private fun JsonElement.asText(): String? = when (this) {
        is JsonPrimitive -> contentOrNull
        is JsonObject -> string("text") ?: textOf(this)
        else -> null
    }

    private val MONTH_PATTERN = Regex(
        """\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val DAY_PATTERN = Regex("""\d{1,2}[/.]\d{1,2}[/.]\d{2,4}""")
    private val HOURS_MINUTES_REGEX = Regex(
        """(\d+)\s*(?:h|hr|hrs|hour|hours)\s*(?:(\d+)\s*(?:m|min|mins|minute|minutes))?""",
        RegexOption.IGNORE_CASE,
    )
    private val MINUTES_REGEX = Regex(
        """(\d+)\s*(?:m|min|mins|minute|minutes)""",
        RegexOption.IGNORE_CASE,
    )
}
