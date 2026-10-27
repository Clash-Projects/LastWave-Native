package com.lastwave.app.data.music

import kotlinx.serialization.json.JsonObject

/** Renderer evidence retained for cards, independently of Home fetching and ordering. */
data class HomeCardData(
    val isTwoRow: Boolean = false,
    val browseId: String? = null,
    val pageType: String? = null,
    val firstSubtitleBrowseId: String? = null,
    val firstSubtitlePageType: String? = null,
    val watchVideoId: String? = null,
    val viewsText: String? = null,
    val playsText: String? = null,
    val largeArtworkUrl: String? = null,
)

data class HomePlayAllButton(val text: String? = null, val watchVideoId: String? = null)

internal const val HOME_SQUARE_ASPECT = "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_SQUARE"

fun isCommunityPlaylistItem(item: HomeShelfItem): Boolean {
    if (item !is HomeShelfItem.Playlist || item.aspectRatio != HOME_SQUARE_ASPECT) return false
    val data = item.cardData ?: return false
    return data.isTwoRow && data.browseId?.startsWith("VL") == true &&
        data.browseId.length > 2 && data.pageType == "MUSIC_PAGE_TYPE_PLAYLIST" &&
        !data.firstSubtitleBrowseId.isNullOrBlank() &&
        data.firstSubtitlePageType == "MUSIC_PAGE_TYPE_USER_CHANNEL"
}

fun isDailyDiscoverShelf(shelf: HomeShelf): Boolean {
    val button = shelf.playAllButton ?: return false
    if (shelf.sourceItemCount != null && shelf.sourceItemCount != shelf.items.size) return false
    return button.text == "Play all" && !button.watchVideoId.isNullOrBlank() &&
        shelf.items.isNotEmpty() && shelf.items.all { item ->
            item is HomeShelfItem.Track && item.aspectRatio == HOME_SQUARE_ASPECT &&
                item.cardData?.isTwoRow == true &&
                item.cardData.watchVideoId == item.track.videoId && item.track.videoId.isNotBlank()
        }
}

internal fun homeRunsText(node: JsonObject?): String? =
    node?.array("runs")?.joinToString("") { it.string("text").orEmpty() }
        ?.trim()?.takeIf(String::isNotBlank)
        ?: node?.string("simpleText")?.trim()?.takeIf(String::isNotBlank)

internal fun parseHomeCardData(item: JsonObject): HomeCardData {
    val nav = item.obj("navigationEndpoint")
    val runs = item.obj("subtitle")?.array("runs").orEmpty()
    val authorNav = runs.firstOrNull()?.obj("navigationEndpoint")
    val thumbs = item.obj("thumbnailRenderer")?.obj("musicThumbnailRenderer")
        ?.obj("thumbnail")?.array("thumbnails").orEmpty()
    return HomeCardData(
        isTwoRow = true,
        browseId = nav?.obj("browseEndpoint")?.string("browseId"),
        pageType = parsePageType(nav),
        firstSubtitleBrowseId = authorNav?.obj("browseEndpoint")?.string("browseId"),
        firstSubtitlePageType = parsePageType(authorNav),
        watchVideoId = nav?.obj("watchEndpoint")?.string("videoId"),
        viewsText = runs.mapNotNull { it.string("text")?.trim() }
            .firstOrNull { it.endsWith(" views") },
        playsText = runs.mapNotNull { it.string("text")?.trim() }
            .firstOrNull { it.endsWith(" plays") },
        largeArtworkUrl = (thumbs.firstOrNull { it.string("width") == "544" }
            ?: thumbs.maxByOrNull { it.string("width")?.toIntOrNull() ?: 0 })?.string("url"),
    )
}

internal fun parseHomePlayAllButton(shelf: JsonObject): HomePlayAllButton? {
    val button = shelf.obj("header")?.obj("musicCarouselShelfBasicHeaderRenderer")
        ?.obj("moreContentButton")?.obj("buttonRenderer") ?: return null
    return HomePlayAllButton(
        text = homeRunsText(button.obj("text")),
        watchVideoId = button.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId"),
    )
}
