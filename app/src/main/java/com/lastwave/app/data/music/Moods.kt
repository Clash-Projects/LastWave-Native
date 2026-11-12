package com.lastwave.app.data.music

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonElement

internal const val MOODS_BROWSE_ID = "FEmusic_moods_and_genres"
internal const val MOOD_CATEGORY_BROWSE_ID = "FEmusic_moods_and_genres_category"

@Immutable
data class MoodTile(
    val label: String,
    val colorArgb: Long,
    val browseId: String,
    val params: String,
) {
    val key: String get() = "$browseId:$params"
}

@Immutable
data class MoodGroup(
    val title: String,
    val tiles: List<MoodTile>,
)

@Immutable
data class MoodCategory(
    val title: String,
    val shelves: List<HomeShelf>,
)

/** Reads only the page's ordered navigation grids, without personalized response metadata. */
internal fun parseMoodGroups(root: JsonElement): List<MoodGroup> {
    val sections = root.obj("contents")
        ?.obj("singleColumnBrowseResultsRenderer")
        ?.array("tabs")?.firstOrNull()
        ?.obj("tabRenderer")?.obj("content")
        ?.obj("sectionListRenderer")?.array("contents")
        ?: return emptyList()

    return sections.mapNotNull { section ->
        val grid = section.obj("gridRenderer") ?: return@mapNotNull null
        val title = homeRunsText(grid.obj("header")?.obj("gridHeaderRenderer")?.obj("title"))
            ?: return@mapNotNull null
        val tiles = grid.array("items").orEmpty().mapNotNull tile@{ item ->
            val button = item.obj("musicNavigationButtonRenderer") ?: return@tile null
            val label = homeRunsText(button.obj("buttonText")) ?: return@tile null
            val endpoint = button.obj("clickCommand")?.obj("browseEndpoint") ?: return@tile null
            val browseId = endpoint.string("browseId")
                ?.takeIf { it == MOOD_CATEGORY_BROWSE_ID } ?: return@tile null
            val params = endpoint.string("params")?.takeIf { it.isNotBlank() } ?: return@tile null
            val color = button.obj("solid")?.string("leftStripeColor")?.toLongOrNull()
                ?.takeIf { it in 0L..0xFFFFFFFFL } ?: return@tile null
            MoodTile(label = label, colorArgb = color, browseId = browseId, params = params)
        }
        MoodGroup(title = title, tiles = tiles)
    }
}

internal fun parseMoodCategory(root: JsonElement, api: InnerTubeMusicApi): MoodCategory =
    MoodCategory(
        title = homeRunsText(root.obj("header")?.obj("musicHeaderRenderer")?.obj("title")).orEmpty(),
        shelves = api.parseHomeShelves(root).mapNotNull { shelf ->
            val playlists = shelf.items.filterIsInstance<HomeShelfItem.Playlist>()
            if (playlists.isEmpty()) null else shelf.copy(items = playlists)
        },
    )
