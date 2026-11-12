package com.lastwave.app.data.music

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.Test

class MoodsParserTest {
    @Test
    fun groupsKeepServerNamesAndTileOrder() {
        val groups = parseMoodGroups(Json.parseToJsonElement(GROUPS_FIXTURE))

        assertThat(groups.map { it.title }).containsExactly("For you", "Moods & moments", "Genres").inOrder()
        assertThat(groups[0].tiles.map { it.label }).containsExactly("Romance", "Commute").inOrder()
        assertThat(groups[1].tiles.map { it.label }).containsExactly("Chill", "Commute").inOrder()
        assertThat(groups[2].tiles.map { it.label }).containsExactly("African", "Arabic").inOrder()
    }

    @Test
    fun colorsRetainUnsignedArgbFromResponse() {
        val tiles = parseMoodGroups(Json.parseToJsonElement(GROUPS_FIXTURE)).flatMap { it.tiles }

        assertThat(tiles.map { it.colorArgb }).containsExactly(
            4291559424L, 4294951424L, 4288988671L, 4294951424L, 4278232339L, 4293020416L,
        ).inOrder()
        assertThat(tiles.first().colorArgb.toInt()).isEqualTo(0xFFCC0000.toInt())
    }

    @Test
    fun categoryEndpointsAndOpaqueParamsArePreserved() {
        val groups = parseMoodGroups(Json.parseToJsonElement(GROUPS_FIXTURE))
        val romance = groups[0].tiles[0]

        assertThat(romance.browseId).isEqualTo("FEmusic_moods_and_genres_category")
        assertThat(romance.params).isEqualTo("ggMPOg1uX0FzQ2FhZWtUY211")
        assertThat(groups[1].tiles[0].params).isEqualTo("ggMPOg1uX1JOQWZFeDByc2Jm")
        assertThat(groups[2].tiles[0].params).isEqualTo("ggMPOg1uX0UzWGxlRE5jMDVk")
        assertThat(groups[0].tiles[1].key).isEqualTo(groups[1].tiles[1].key)
        assertThat(romance.key).isNotEqualTo(groups[0].tiles[1].key)

        val opaque = "opaque+/value==%2F"
        val changed = GROUPS_FIXTURE.replaceFirst("ggMPOg1uX0FzQ2FhZWtUY211", opaque)
        assertThat(parseMoodGroups(Json.parseToJsonElement(changed))[0].tiles[0].params).isEqualTo(opaque)
    }

    @Test
    fun invalidTilesAreSkippedWithoutDroppingOtherGroupsOrChangingOrder() {
        val malformed = listOf(
            GROUPS_FIXTURE.replaceFirst("\"Romance\"", "\"\""),
            GROUPS_FIXTURE.replaceFirst("\"ggMPOg1uX0FzQ2FhZWtUY211\"", "\"\""),
            GROUPS_FIXTURE.replaceFirst("\"params\"", "\"unsupportedParams\""),
            GROUPS_FIXTURE.replaceFirst("FEmusic_moods_and_genres_category", "unsupported_browse"),
            GROUPS_FIXTURE.replaceFirst("\"leftStripeColor\"", "\"unsupportedColor\""),
        )
        malformed.forEach { fixture ->
            val groups = parseMoodGroups(Json.parseToJsonElement(fixture))
            assertThat(groups).hasSize(3)
            assertThat(groups[0].tiles.map { it.label }).containsExactly("Commute")
            assertThat(groups[1].tiles.map { it.label }).containsExactly("Chill", "Commute").inOrder()
        }
    }

    @Test
    fun colorsRejectOutOfRangeOrMalformedValues() {
        listOf("-1", "4294967296", "1.5", "null", "\"invalid\"").forEach { value ->
            val fixture = GROUPS_FIXTURE.replaceFirst("4291559424", value)
            assertThat(parseMoodGroups(Json.parseToJsonElement(fixture))[0].tiles.map { it.label })
                .containsExactly("Commute")
        }
        listOf(0L, 4294967295L).forEach { value ->
            val fixture = GROUPS_FIXTURE.replaceFirst("4291559424", value.toString())
            assertThat(parseMoodGroups(Json.parseToJsonElement(fixture))[0].tiles[0].colorArgb)
                .isEqualTo(value)
        }
    }

    @Test
    fun missingOrUnrelatedContentsAreEmpty() {
        listOf("{}", "null", "{\"contents\": []}").forEach { fixture ->
            assertThat(parseMoodGroups(Json.parseToJsonElement(fixture))).isEmpty()
        }
    }

    @Test
    fun categoryUsesExistingShelfParserAndPreservesPlaylistOrder() {
        val category = parseMoodCategory(Json.parseToJsonElement(CATEGORY_FIXTURE), parserApi())

        assertThat(category.title).isEqualTo("Romance")
        assertThat(category.shelves.map { it.title })
            .containsExactly("Romantic Bollywood & Indian", "Sweethearts & romance").inOrder()
        val playlists = category.shelves.flatMap { it.items }.map { it as HomeShelfItem.Playlist }
        assertThat(playlists.map { it.playlist.title })
            .containsExactly("Bollywood Romantic Moments", "Sweetheart Pop").inOrder()
        assertThat(playlists.map { it.playlist.id }).containsExactly(
            "RDCLAK5uy_miAacfMxVybbt7ketqqnPPbH9LDn1TavU",
            "RDCLAK5uy_l1oO11DBO4FD8U7bOrqUKK5Y_PkISUMQM",
        ).inOrder()
        assertThat(playlists.map { it.aspectRatio }).containsExactly(
            "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_SQUARE",
            "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_SQUARE",
        )
    }

    @Test
    fun categoryOnlyExposesPlaylistShelvesToTheCategoryScreen() {
        val root = Json.parseToJsonElement(CATEGORY_FIXTURE)
        val playlistShelf = parserApi().parseHomeShelves(root).first()
        val track = HomeShelfItem.Track(YouTubeMusicTrack(videoId = "video", title = "Song", artist = "Artist"))
        val api = mockk<InnerTubeMusicApi>()
        every { api.parseHomeShelves(root) } returns listOf(
            HomeShelf(title = "Songs", items = listOf(track)),
            playlistShelf.copy(items = listOf(track) + playlistShelf.items),
            HomeShelf(title = "Empty"),
        )

        val category = parseMoodCategory(root, api)

        assertThat(category.shelves).containsExactly(playlistShelf)
    }

    private fun parserApi() = InnerTubeMusicApi(
        http = mockk(relaxed = true),
        streamExtractor = mockk(relaxed = true),
        innerTubeXExtractor = mockk(relaxed = true),
        ytAuth = mockk(relaxed = true),
        settingsPreferences = mockk(relaxed = true),
        appContext = mockk(relaxed = true),
    )

    private companion object {
        // Trimmed from the supplied logged-in response: two items per group, no tracking or account data.
        val GROUPS_FIXTURE = """
            {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{
              "sectionListRenderer":{"contents":[
                {"gridRenderer":{
                  "header":{"gridHeaderRenderer":{"title":{"runs":[{"text":"For you"}]}}},
                  "items":[
                    {"musicNavigationButtonRenderer":{
                      "buttonText":{"runs":[{"text":"Romance"}]},"solid":{"leftStripeColor":4291559424},
                      "clickCommand":{"browseEndpoint":{"browseId":"FEmusic_moods_and_genres_category","params":"ggMPOg1uX0FzQ2FhZWtUY211"}}
                    }},
                    {"musicNavigationButtonRenderer":{
                      "buttonText":{"runs":[{"text":"Commute"}]},"solid":{"leftStripeColor":4294951424},
                      "clickCommand":{"browseEndpoint":{"browseId":"FEmusic_moods_and_genres_category","params":"ggMPOg1uX044Z2o5WERLckpU"}}
                    }}
                  ]
                }},
                {"gridRenderer":{
                  "header":{"gridHeaderRenderer":{"title":{"runs":[{"text":"Moods & moments"}]}}},
                  "items":[
                    {"musicNavigationButtonRenderer":{
                      "buttonText":{"runs":[{"text":"Chill"}]},"solid":{"leftStripeColor":4288988671},
                      "clickCommand":{"browseEndpoint":{"browseId":"FEmusic_moods_and_genres_category","params":"ggMPOg1uX1JOQWZFeDByc2Jm"}}
                    }},
                    {"musicNavigationButtonRenderer":{
                      "buttonText":{"runs":[{"text":"Commute"}]},"solid":{"leftStripeColor":4294951424},
                      "clickCommand":{"browseEndpoint":{"browseId":"FEmusic_moods_and_genres_category","params":"ggMPOg1uX044Z2o5WERLckpU"}}
                    }}
                  ]
                }},
                {"gridRenderer":{
                  "header":{"gridHeaderRenderer":{"title":{"runs":[{"text":"Genres"}]}}},
                  "items":[
                    {"musicNavigationButtonRenderer":{
                      "buttonText":{"runs":[{"text":"African"}]},"solid":{"leftStripeColor":4278232339},
                      "clickCommand":{"browseEndpoint":{"browseId":"FEmusic_moods_and_genres_category","params":"ggMPOg1uX0UzWGxlRE5jMDVk"}}
                    }},
                    {"musicNavigationButtonRenderer":{
                      "buttonText":{"runs":[{"text":"Arabic"}]},"solid":{"leftStripeColor":4293020416},
                      "clickCommand":{"browseEndpoint":{"browseId":"FEmusic_moods_and_genres_category","params":"ggMPOg1uX3VOQWxsblVZTFNE"}}
                    }}
                  ]
                }}
              ]}
            }}}]}}}
        """.trimIndent()

        // Trimmed from the supplied Romance response: first playlist of its first two shelves.
        val CATEGORY_FIXTURE = """
            {
              "header":{"musicHeaderRenderer":{"title":{"runs":[{"text":"Romance"}]}}},
              "contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{
                "sectionListRenderer":{"contents":[
                  {"musicCarouselShelfRenderer":{
                    "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Romantic Bollywood & Indian"}]}}},
                    "contents":[{"musicTwoRowItemRenderer":{
                      "aspectRatio":"MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_SQUARE",
                      "title":{"runs":[{"text":"Bollywood Romantic Moments"}]},
                      "subtitle":{"runs":[{"text":"Playlist"},{"text":" \u2022 "},{"text":"YouTube Music"}]},
                      "navigationEndpoint":{"browseEndpoint":{
                        "browseId":"VLRDCLAK5uy_miAacfMxVybbt7ketqqnPPbH9LDn1TavU",
                        "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}
                      }}
                    }}]
                  }},
                  {"musicCarouselShelfRenderer":{
                    "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Sweethearts & romance"}]}}},
                    "contents":[{"musicTwoRowItemRenderer":{
                      "aspectRatio":"MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_SQUARE",
                      "title":{"runs":[{"text":"Sweetheart Pop"}]},
                      "subtitle":{"runs":[{"text":"Playlist"},{"text":" \u2022 "},{"text":"YouTube Music"}]},
                      "navigationEndpoint":{"browseEndpoint":{
                        "browseId":"VLRDCLAK5uy_l1oO11DBO4FD8U7bOrqUKK5Y_PkISUMQM",
                        "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}
                      }}
                    }}]
                  }}
                ]}
              }}}]}}}
        """.trimIndent()
    }
}
