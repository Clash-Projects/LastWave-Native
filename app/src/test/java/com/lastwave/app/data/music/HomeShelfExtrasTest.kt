package com.lastwave.app.data.music

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Before
import org.junit.Test

/**
 * Tests for additive shelf extras parsing (aspectRatio, itemType, caption,
 * moreBrowseId, community songs). Missing fields fall back to null/empty and
 * never crash; unknown shapes keep the default square card.
 */
class HomeShelfExtrasTest {

    private lateinit var api: InnerTubeMusicApi

    @Before
    fun setUp() {
        api = InnerTubeMusicApi(
            http = mockk(relaxed = true),
            streamExtractor = mockk(relaxed = true),
            innerTubeXExtractor = mockk(relaxed = true),
            ytAuth = mockk(relaxed = true),
            settingsPreferences = mockk(relaxed = true),
        )
    }

    private fun shelfJson(itemJson: String, headerExtra: String = ""): String = """
        {
            "header": {
                "musicCarouselShelfBasicHeaderRenderer": {
                    "title": { "runs": [{ "text": "Shelf" }] }
                    $headerExtra
                }
            },
            "contents": [ $itemJson ]
        }
    """.trimIndent()

    @Test
    fun parsesAspectRatioOntoItem() {
        val item = """
            {
                "musicTwoRowItemRenderer": {
                    "aspectRatio": "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_RECTANGLE_16_9",
                    "title": { "runs": [{ "text": "Video" }] },
                    "navigationEndpoint": { "watchEndpoint": { "videoId": "vid1" } }
                }
            }
        """.trimIndent()
        val shelf = api.parseCarouselShelf(Json.parseToJsonElement(shelfJson(item)).jsonObject)
        val track = shelf?.items?.first() as? HomeShelfItem.Track
        assertThat(track?.aspectRatio)
            .isEqualTo("MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_RECTANGLE_16_9")
    }

    @Test
    fun parsesPageTypeIntoItemType() {
        val item = """
            {
                "musicTwoRowItemRenderer": {
                    "title": { "runs": [{ "text": "Playlist" }] },
                    "navigationEndpoint": {
                        "browseEndpoint": {
                            "browseId": "VLPL_x",
                            "browseEndpointContextSupportedConfigs": {
                                "browseEndpointContextMusicConfig": {
                                    "pageType": "MUSIC_PAGE_TYPE_PLAYLIST"
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()
        val shelf = api.parseCarouselShelf(Json.parseToJsonElement(shelfJson(item)).jsonObject)
        val pl = shelf?.items?.first() as? HomeShelfItem.Playlist
        assertThat(pl?.itemType).isEqualTo("playlist")
    }

    @Test
    fun parsesStraplineAndMoreBrowseId() {
        val item = """
            {
                "musicTwoRowItemRenderer": {
                    "title": { "runs": [{ "text": "Song" }] },
                    "navigationEndpoint": { "watchEndpoint": { "videoId": "v" } }
                }
            }
        """.trimIndent()
        val extra = """,
            "strapline": { "runs": [{ "text": "FOR RELAXING" }] },
            "moreContentButton": {
                "buttonRenderer": {
                    "navigationEndpoint": { "browseEndpoint": { "browseId": "FEmusic_more" } }
                }
            }
        """
        val shelf = api.parseCarouselShelf(Json.parseToJsonElement(shelfJson(item, extra)).jsonObject)
        assertThat(shelf?.strapline).isEqualTo("FOR RELAXING")
        assertThat(shelf?.moreBrowseId).isEqualTo("FEmusic_more")
    }

    @Test
    fun missingFieldsFallBackSafely() {
        val item = """
            {
                "musicTwoRowItemRenderer": {
                    "title": { "runs": [{ "text": "Song" }] },
                    "navigationEndpoint": { "watchEndpoint": { "videoId": "v" } }
                }
            }
        """.trimIndent()
        val shelf = api.parseCarouselShelf(Json.parseToJsonElement(shelfJson(item)).jsonObject)
        val track = shelf?.items?.first() as? HomeShelfItem.Track
        assertThat(track?.aspectRatio).isNull()
        assertThat(track?.caption).isNull()
        assertThat(shelf?.strapline).isNull()
        assertThat(shelf?.moreBrowseId).isNull()
    }

    @Test
    fun unknownItemShapeStillProducesSquareFallback() {
        val item = """
            {
                "musicTwoRowItemRenderer": {
                    "title": { "runs": [{ "text": "Song" }] },
                    "navigationEndpoint": { "watchEndpoint": { "videoId": "v" } },
                    "unexpectedField": { "foo": "bar" }
                }
            }
        """.trimIndent()
        val shelf = api.parseCarouselShelf(Json.parseToJsonElement(shelfJson(item)).jsonObject)
        assertThat(shelf?.items).hasSize(1)
        assertThat(shelf?.items?.first()).isInstanceOf(HomeShelfItem.Track::class.java)
    }
}
