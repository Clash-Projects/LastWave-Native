package com.lastwave.app.data.music

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class PlaylistPreview(
    val songs: List<YouTubeMusicTrack> = emptyList(),
    val collageArtworkUrls: List<String> = emptyList(),
    val songCount: Int? = null,
    val title: String? = null,
    val songPlayCounts: Map<String, String> = emptyMap(),
)

/** Reads only the first browse page; neither recommendations nor continuations are previews. */
internal fun parsePlaylistPreview(root: JsonElement): PlaylistPreview? {
    val shelves = previewNodes(root, "musicPlaylistShelfRenderer")
    val header = previewNodes(root, "musicResponsiveHeaderRenderer").firstOrNull()
        ?: previewNodes(root, "musicDetailHeaderRenderer").firstOrNull()
    if (shelves.isEmpty() && header == null) return null
    val rows = shelves.flatMap { it.array("contents").orEmpty() }
        .mapNotNull { it.obj("musicResponsiveListItemRenderer") }
    val songs = mutableListOf<YouTubeMusicTrack>()
    val covers = linkedSetOf<String>()
    val plays = mutableMapOf<String, String>()
    for (row in rows) {
        val columns = row.array("flexColumns").orEmpty().mapNotNull {
            it.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")
        }
        val title = homeRunsText(columns.firstOrNull()) ?: continue
        val firstRun = columns.firstOrNull()?.array("runs")?.firstOrNull()
        val videoId = row.obj("playlistItemData")?.string("videoId")
            ?: firstRun?.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
        if (videoId.isNullOrBlank()) continue
        val thumbnail = row.obj("thumbnail")?.obj("musicThumbnailRenderer")
            ?.obj("thumbnail")?.array("thumbnails").orEmpty()
            .maxByOrNull { it.string("width")?.toIntOrNull() ?: 0 }
            ?.string("url")?.takeIf(String::isNotBlank)
            ?.let { if (it.startsWith("//")) "https:$it" else it }
        if (thumbnail != null && covers.size < 4) covers += thumbnail
        if (songs.size >= 3) continue
        val artistRuns = columns.getOrNull(1)?.array("runs").orEmpty()
        val artists = artistRuns.filter {
            parsePageType(it.obj("navigationEndpoint")) in
                setOf("MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL")
        }.mapNotNull { it.string("text")?.trim()?.takeIf(String::isNotBlank) }
        val artist = artists.joinToString(" & ").ifBlank {
            homeRunsText(columns.getOrNull(1)).orEmpty()
        }
        columns.flatMap { it.array("runs").orEmpty() }.mapNotNull { it.string("text")?.trim() }
            .firstOrNull { it.endsWith(" plays") }?.let { plays[videoId] = it }
        songs += YouTubeMusicTrack(videoId = videoId, title = title, artist = artist, artworkUrl = thumbnail)
    }
    val count = listOfNotNull(header?.obj("secondSubtitle"), header?.obj("subtitle"))
        .flatMap { it.array("runs").orEmpty() }.mapNotNull { it.string("text") }
        .firstNotNullOfOrNull { text ->
            Regex("^([0-9][0-9,]*)\\s+(?:tracks?|songs?)$", RegexOption.IGNORE_CASE)
                .matchEntire(text.trim())?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        }
    return PlaylistPreview(songs, covers.toList(), count, homeRunsText(header?.obj("title")), plays)
}

private fun previewNodes(root: JsonElement, key: String): List<JsonObject> = buildList {
    fun visit(node: JsonElement) {
        when (node) {
            is JsonObject -> {
                node.obj(key)?.let(::add)
                node.values.forEach(::visit)
            }
            is JsonArray -> node.forEach(::visit)
            else -> Unit
        }
    }
    visit(root)
}
