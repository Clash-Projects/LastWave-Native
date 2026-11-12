/*
 * Additive extras parser for native YouTube Music Home shelves (GPL-3.0).
 * Reads only optional fields that already exist on the renderers; never throws,
 * never changes the meaning of an item. Part of the Home chips + card-system work.
 */
package com.lastwave.app.data.music

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Optional per-item layout/data extras that the card system reads to pick a
 * card variant. Everything defaults to null/empty so unknown shapes are safe.
 *
 * Extracted from `musicTwoRowItemRenderer`, `musicCardShelfRenderer`,
 * `musicResponsiveListItemRenderer`, and `musicPlaylistShelfRenderer`.
 */
internal data class HomeItemExtras(
    val aspectRatio: String? = null,
    val itemType: String? = null,
    val caption: String? = null,
    val collageArtworkUrls: List<String> = emptyList(),
    val previewSongs: List<YouTubeMusicTrack> = emptyList(),
)

/** Aspect-ratio value YTM puts on `musicTwoRowItemRenderer.aspectRatio`. */
internal const val ASPECT_RECTANGLE_16_9 =
    "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_RECTANGLE_16_9"
internal const val ASPECT_RECTANGLE_16_9_V2 =
    "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_RECTANGLE_SIXTEEN_BY_NINE"

/** Returns true when [aspectRatio] denotes a wide 16:9 (video-style) card. */
internal fun isWideAspect(aspectRatio: String?): Boolean =
    aspectRatio != null &&
        aspectRatio.contains("RECTANGLE", ignoreCase = true)

/**
 * Reads the page type from a navigation endpoint's
 * `browseEndpointContextSupportedConfigs.browseEndpointContextMusicConfig.pageType`.
 * Returns null when absent (guest feeds rarely set it).
 */
internal fun parsePageType(navEndpoint: JsonObject?): String? {
    val browse = navEndpoint?.obj("browseEndpoint") ?: return null
    return browse.obj("browseEndpointContextSupportedConfigs")
        ?.obj("browseEndpointContextMusicConfig")
        ?.string("pageType")
        ?.takeIf { it.isNotBlank() }
}

/** Maps a YTM pageType to a coarse item type the card system switches on. */
internal fun pageTypeToItemType(pageType: String?): String? = when {
    pageType == null -> null
    pageType.contains("PLAYLIST") -> "playlist"
    pageType.contains("ALBUM") || pageType.contains("AUDIOBOOK") -> "album"
    pageType.contains("ARTIST") -> "artist"
    pageType.contains("PODCAST_SHOW") -> "podcast"
    pageType.contains("NON_MUSIC_AUDIO_TRACK") -> "episode"
    else -> null
}

/**
 * Reads a caption from a two-row / card shelf item. YTM places the "Because
 * you liked X" / "Because you listened" caption in the item's `subtitle`'s
 * last free-text run group, or in `subtitleDescription`/`headline` on card
 * shelves. Only returns a caption when it is descriptive (starts with a
 * "because"-style connector or is longer than a plain type label).
 */
internal fun parseItemCaption(item: JsonObject): String? {
    // Card shelves carry a dedicated caption/subtitle-description field.
    val cardCaption = item.obj("subtitleDescription")?.let { runsText(it) }
        ?: item.obj("headline")?.let { runsText(it) }
    if (!cardCaption.isNullOrBlank()) return cardCaption.trim()

    // Two-row items: caption lives in a `strapline`-style run or a caption block.
    val captionRuns = item.obj("caption")?.let { runsText(it) }
        ?: item.obj("overline")?.let { runsText(it) }
    if (!captionRuns.isNullOrBlank()) return captionRuns.trim()
    return null
}

/** Concatenates the text of a `{"runs":[...]}`/`simpleText` node. */
private fun runsText(node: JsonObject): String? =
    node.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
        ?.takeIf { it.isNotBlank() }
        ?: node.string("simpleText")?.takeIf { it.isNotBlank() }

/**
 * Reads up to [max] collage cover URLs from a playlist item that carries
 * multiple thumbnails (community playlist cards). Falls back to the item's own
 * thumbnail list when a dedicated collage array is absent. Never throws.
 */
internal fun parseCollageCovers(item: JsonObject, max: Int = 4): List<String> {
    val out = mutableListOf<String>()
    // Some playlist renderers expose a multi-thumbnail collage node.
    val collage = item.obj("thumbnailCollageRenderer")
        ?: item.obj("collageRenderer")
    if (collage != null) {
        val thumbs = mutableListOf<JsonObject>()
        collectThumbNodes(collage, thumbs)
        for (t in thumbs) {
            val url = t.array("thumbnails")?.lastOrNull()?.asObject()?.string("url")
            if (!url.isNullOrBlank()) out += if (url.startsWith("//")) "https:$url" else url
            if (out.size >= max) break
        }
    }
    return out
}

private fun collectThumbNodes(el: JsonElement, out: MutableList<JsonObject>) {
    when (el) {
        is JsonObject -> {
            if (el.array("thumbnails") != null) out += el
            el.values.forEach { collectThumbNodes(it, out) }
        }
        is kotlinx.serialization.json.JsonArray -> el.forEach { collectThumbNodes(it, out) }
        else -> Unit
    }
}

/**
 * Reads the preview song rows embedded in a playlist shelf item (community
 * playlists). Looks for `contents`/`items` arrays of responsive list items.
 * Never throws; returns at most [max] songs.
 */
internal fun parsePreviewSongs(
    item: JsonObject,
    parseSong: (JsonObject) -> YouTubeMusicTrack?,
    max: Int = 3,
): List<YouTubeMusicTrack> {
    val rows = mutableListOf<JsonObject>()
    for (key in listOf("contents", "items", "songs", "previewTracks")) {
        val arr = item.array(key) ?: continue
        for (e in arr) {
            val o = e.asObject() ?: continue
            o.obj("musicResponsiveListItemRenderer")?.let(rows::add)
                ?: o.obj("musicTwoRowItemRenderer")?.let(rows::add)
            if (rows.size >= max) break
        }
        if (rows.isNotEmpty()) break
    }
    return rows.mapNotNull(parseSong).take(max)
}

/** Convenience: builds [HomeItemExtras] for a shelf item renderer node. */
internal fun parseHomeItemExtras(
    item: JsonObject,
    navEndpoint: JsonObject?,
    parseSong: (JsonObject) -> YouTubeMusicTrack?,
): HomeItemExtras {
    val aspect = item.string("aspectRatio")
    val pageType = parsePageType(navEndpoint)
    return HomeItemExtras(
        aspectRatio = aspect,
        itemType = pageTypeToItemType(pageType),
        caption = parseItemCaption(item),
        collageArtworkUrls = parseCollageCovers(item),
        previewSongs = parsePreviewSongs(item, parseSong),
    )
}
