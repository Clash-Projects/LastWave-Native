package com.lastwave.app.data.music

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** Display-only excerpts from the two 2026-10-25 captures; negative mutations are explicit. */
class HomeCommunityDiscoverDataTest {
    private val api = InnerTubeMusicApi(
        http = mockk(relaxed = true), streamExtractor = mockk(relaxed = true),
        innerTubeXExtractor = mockk(relaxed = true), ytAuth = mockk(relaxed = true),
        settingsPreferences = mockk(relaxed = true),
    )

    private fun fixture(name: String) = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/home_cards/$name.json")).readText()
    ).jsonObject

    private fun community() = api.parseCarouselShelf(fixture("home_community"))!!
    private fun daily() = api.parseCarouselShelf(fixture("home_daily"))!!

    @Test fun capturedCommunityHasUserAuthorAndNoHomeSongPreviews() {
        val item = community().items.first() as HomeShelfItem.Playlist
        assertThat(isCommunityPlaylistItem(item)).isTrue()
        assertThat(item.previewSongs).isEmpty()
        assertThat(item.cardData?.viewsText).isEqualTo("1M views")
        assertThat(item.cardData?.firstSubtitlePageType).isEqualTo("MUSIC_PAGE_TYPE_USER_CHANNEL")
    }

    @Test fun communityDetectionDoesNotUseShelfTitle() {
        val shelf = community().copy(title = "A different localized shelf title")
        assertThat(shelf.items.all(::isCommunityPlaylistItem)).isTrue()
    }

    @Test fun ordinaryPlaylistAndMissingEvidenceStaySquare() {
        val item = community().items.first() as HomeShelfItem.Playlist
        val data = item.cardData!!
        listOf(
            null, data.copy(isTwoRow = false), data.copy(browseId = null),
            data.copy(browseId = "PL123"), data.copy(browseId = "VL"),
            data.copy(pageType = "MUSIC_PAGE_TYPE_ALBUM"),
            data.copy(firstSubtitleBrowseId = null),
            data.copy(firstSubtitlePageType = "MUSIC_PAGE_TYPE_ARTIST"),
        ).forEach { assertThat(isCommunityPlaylistItem(item.copy(cardData = it))).isFalse() }
        assertThat(isCommunityPlaylistItem(item.copy(aspectRatio = null))).isFalse()
    }

    @Test fun onlyFirstSubtitleRunCanQualifyAsCommunityAuthor() {
        val raw = fixture("home_community")
        val row = raw.array("contents")!!.first().obj("musicTwoRowItemRenderer")!!
        val subtitle = row.obj("subtitle")!!
        val runs = subtitle.array("runs")!!
        val changed = JsonObject(row + ("subtitle" to JsonObject(subtitle +
            ("runs" to JsonArray(listOf(runs[2], runs[1], runs[0]))))))
        val shelf = JsonObject(raw + ("contents" to JsonArray(listOf(
            JsonObject(mapOf("musicTwoRowItemRenderer" to changed))
        ))))
        assertThat(isCommunityPlaylistItem(api.parseCarouselShelf(shelf)!!.items.first())).isFalse()
    }

    @Test fun capturedDailyUsesWatchPlayAllAnd544ArtworkWithoutCaption() {
        val shelf = daily()
        assertThat(isDailyDiscoverShelf(shelf)).isTrue()
        assertThat(isDailyDiscoverShelf(shelf.copy(title = "Localized title"))).isTrue()
        val item = shelf.items.first() as HomeShelfItem.Track
        assertThat(item.cardData?.playsText).isEqualTo("2.8M plays")
        assertThat(item.cardData?.largeArtworkUrl).contains("w544-h544")
        assertThat(item.caption).isNull()
        // The captured header has no playlistId; a video watch endpoint is sufficient.
        val watch = fixture("home_daily").obj("header")!!.obj("musicCarouselShelfBasicHeaderRenderer")!!
            .obj("moreContentButton")!!.obj("buttonRenderer")!!.obj("navigationEndpoint")!!.obj("watchEndpoint")!!
        assertThat(watch.string("playlistId")).isNull()
    }

    @Test fun moreBrowseButtonNeverEnablesDailyCards() {
        assertThat(isDailyDiscoverShelf(api.parseCarouselShelf(fixture("home_new_releases"))!!)).isFalse()
        val shelf = daily()
        assertThat(isDailyDiscoverShelf(shelf.copy(playAllButton = HomePlayAllButton("More", "v")))).isFalse()
        assertThat(isDailyDiscoverShelf(shelf.copy(playAllButton = HomePlayAllButton("Play all")))).isFalse()
        assertThat(isDailyDiscoverShelf(shelf.copy(playAllButton = null))).isFalse()
    }

    @Test fun missingOrMixedItemsFallBack() {
        val shelf = daily()
        val song = shelf.items.first() as HomeShelfItem.Track
        listOf(song.copy(cardData = null), song.copy(aspectRatio = null),
            song.copy(cardData = song.cardData!!.copy(watchVideoId = null)))
            .forEach { assertThat(isDailyDiscoverShelf(shelf.copy(items = listOf(it)))).isFalse() }
        assertThat(isDailyDiscoverShelf(shelf.copy(items = emptyList()))).isFalse()
        assertThat(isDailyDiscoverShelf(shelf.copy(items = shelf.items + community().items))).isFalse()
    }

    @Test fun responsiveQuickPicksWithPlayAllAreNotDailyCards() {
        // The capture lacks Quick picks. Use its real responsive song rows under a Play all header.
        val dailyRaw = fixture("home_daily")
        val rows = fixture("community_playlist").array("contents")!!.first()
            .obj("musicPlaylistShelfRenderer")!!.array("contents")!!
        val quickPicksShape = JsonObject(dailyRaw + ("contents" to rows))
        val parsed = api.parseCarouselShelf(quickPicksShape)!!.copy(title = "Quick picks")
        assertThat(parsed.items).isNotEmpty()
        assertThat(isDailyDiscoverShelf(parsed)).isFalse()
    }

    @Test fun aDroppedMalformedSourceItemCannotMakeADailyShelfQualify() {
        val raw = fixture("home_daily")
        val contents = raw.array("contents")!!
        val invalid = JsonObject(mapOf("musicTwoRowItemRenderer" to JsonObject(emptyMap())))
        val shelf = api.parseCarouselShelf(JsonObject(raw + ("contents" to JsonArray(listOf(contents[0], invalid)))))!!
        assertThat(shelf.items).hasSize(1)
        assertThat(shelf.sourceItemCount).isEqualTo(2)
        assertThat(isDailyDiscoverShelf(shelf)).isFalse()
    }

    @Test fun playlistPreviewReadsThreeSongsFourCoversAndHeaderCount() {
        val preview = parsePlaylistPreview(fixture("community_playlist"))!!
        assertThat(preview.songs).hasSize(3)
        assertThat(preview.songs.first().videoId).isEqualTo("hrYwGItueA8")
        assertThat(preview.songs.first().artist).contains("SACHIN KHILLARI")
        assertThat(preview.collageArtworkUrls).hasSize(4)
        assertThat(preview.collageArtworkUrls.distinct()).hasSize(4)
        assertThat(preview.songCount).isEqualTo(13)
        assertThat(preview.title).startsWith("Dev mamledar band Satana")
        assertThat(preview.songPlayCounts).isEmpty()
    }

    @Test fun previewMissingHeaderNeverInventsCountOrTitle() {
        val root = fixture("community_playlist")
        val preview = parsePlaylistPreview(JsonObject(root - "header"))!!
        assertThat(preview.songCount).isNull()
        assertThat(preview.title).isNull()
        assertThat(preview.songs).hasSize(3)
    }

    @Test fun previewHandlesMissingSongFieldsAndRepeatedCovers() {
        val root = fixture("community_playlist")
        val first = root.array("contents")!!.first().obj("musicPlaylistShelfRenderer")!!
            .array("contents")!!.first().obj("musicResponsiveListItemRenderer")!!
        val row = JsonObject(mapOf("musicResponsiveListItemRenderer" to JsonObject(first - "thumbnail")))
        val minimal = JsonObject(mapOf("musicPlaylistShelfRenderer" to JsonObject(mapOf(
            "contents" to JsonArray(listOf(row, row))
        ))))
        val preview = parsePlaylistPreview(minimal)!!
        assertThat(preview.songs).hasSize(2)
        assertThat(preview.collageArtworkUrls).isEmpty()
        assertThat(preview.songs.first().artworkUrl).isNull()
        assertThat(parsePlaylistPreview(JsonObject(emptyMap()))).isNull()
    }

    @Test fun collageKeepsFourDistinctCoversWhenTheFirstSongRepeats() {
        val rows = fixture("community_playlist").array("contents")!!.first()
            .obj("musicPlaylistShelfRenderer")!!.array("contents")!!
        val repeated = JsonObject(mapOf("musicPlaylistShelfRenderer" to JsonObject(mapOf(
            "contents" to JsonArray(listOf(rows.first()) + rows)
        ))))
        val preview = parsePlaylistPreview(repeated)!!
        assertThat(preview.songs).hasSize(3)
        assertThat(preview.collageArtworkUrls).hasSize(4)
        assertThat(preview.collageArtworkUrls.distinct()).hasSize(4)
    }
}
