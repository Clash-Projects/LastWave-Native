package com.lastwave.app.data.music

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for YouTube Music Home shelf parsing.
 * Verifies exact shelf title/subtitle extraction, original ordering,
 * track and playlist item parsing, and skip rules (artist cards, blank IDs, empty shelves).
 */
class HomeShelfParserTest {

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

    @Test
    fun shelfHeaderParsing_extractsTitleAndSubtitle() {
        val json = """
            {
                "header": {
                    "musicCarouselShelfBasicHeaderRenderer": {
                        "title": {
                            "runs": [
                                { "text": "Quick picks" }
                            ]
                        },
                        "strapline": {
                            "runs": [
                                { "text": "START RADIO FROM A SONG" }
                            ]
                        }
                    }
                }
            }
        """.trimIndent()
        val headerObj = Json.parseToJsonElement(json).jsonObject
        val parsed = api.parseCarouselShelfHeader(headerObj)

        assertThat(parsed).isNotNull()
        assertThat(parsed?.first).isEqualTo("Quick picks")
        assertThat(parsed?.second).isEqualTo("START RADIO FROM A SONG")
    }

    @Test
    fun shelfHeaderParsing_supportsSimpleTextFallback() {
        val json = """
            {
                "header": {
                    "musicCarouselShelfBasicHeaderRenderer": {
                        "title": {
                            "simpleText": "Forgotten favorites"
                        },
                        "subtitle": {
                            "simpleText": "Listen again"
                        }
                    }
                }
            }
        """.trimIndent()
        val headerObj = Json.parseToJsonElement(json).jsonObject
        val parsed = api.parseCarouselShelfHeader(headerObj)

        assertThat(parsed).isNotNull()
        assertThat(parsed?.first).isEqualTo("Forgotten favorites")
        assertThat(parsed?.second).isEqualTo("Listen again")
    }

    @Test
    fun shelfHeaderParsing_returnsNullWhenTitleIsMissing() {
        val json = """
            {
                "header": {
                    "musicCarouselShelfBasicHeaderRenderer": {
                        "strapline": {
                            "runs": [ { "text": "Subtitle Only" } ]
                        }
                    }
                }
            }
        """.trimIndent()
        val headerObj = Json.parseToJsonElement(json).jsonObject
        val parsed = api.parseCarouselShelfHeader(headerObj)

        assertThat(parsed).isNull()
    }

    @Test
    fun parseCarouselShelf_parsesTracksAndPlaylists() {
        val json = """
            {
                "header": {
                    "musicCarouselShelfBasicHeaderRenderer": {
                        "title": { "runs": [{ "text": "Mixed Shelf" }] }
                    }
                },
                "contents": [
                    {
                        "musicTwoRowItemRenderer": {
                            "title": { "runs": [{ "text": "Song One" }] },
                            "subtitle": { "runs": [{ "text": "Artist A" }] },
                            "navigationEndpoint": {
                                "watchEndpoint": { "videoId": "vid_song_1" }
                            }
                        }
                    },
                    {
                        "musicTwoRowItemRenderer": {
                            "title": { "runs": [{ "text": "Playlist Alpha" }] },
                            "subtitle": {
                                "runs": [
                                    { "text": "Playlist" },
                                    { "text": " • " },
                                    { "text": "YouTube Music" }
                                ]
                            },
                            "navigationEndpoint": {
                                "browseEndpoint": { "browseId": "VLPL_custom_playlist" }
                            }
                        }
                    },
                    {
                        "musicResponsiveListItemRenderer": {
                            "flexColumns": [
                                {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                        "text": { "runs": [{ "text": "Responsive Song" }] }
                                    }
                                },
                                {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                        "text": { "runs": [{ "text": "Artist B" }] }
                                    }
                                }
                            ],
                            "playlistItemData": { "videoId": "vid_responsive_song" }
                        }
                    }
                ]
            }
        """.trimIndent()
        val carouselObj = Json.parseToJsonElement(json).jsonObject
        val shelf = api.parseCarouselShelf(carouselObj)

        assertThat(shelf).isNotNull()
        assertThat(shelf?.title).isEqualTo("Mixed Shelf")
        assertThat(shelf?.items).hasSize(3)

        val item0 = shelf?.items?.get(0) as? HomeShelfItem.Track
        assertThat(item0?.track?.videoId).isEqualTo("vid_song_1")
        assertThat(item0?.track?.title).isEqualTo("Song One")

        val item1 = shelf?.items?.get(1) as? HomeShelfItem.Playlist
        assertThat(item1?.playlist?.id).isEqualTo("PL_custom_playlist")
        assertThat(item1?.playlist?.title).isEqualTo("Playlist Alpha")

        val item2 = shelf?.items?.get(2) as? HomeShelfItem.Track
        assertThat(item2?.track?.videoId).isEqualTo("vid_responsive_song")
        assertThat(item2?.track?.title).isEqualTo("Responsive Song")
    }

    @Test
    fun parseCarouselShelf_skipsArtistCardsAndInvalidIds() {
        val json = """
            {
                "header": {
                    "musicCarouselShelfBasicHeaderRenderer": {
                        "title": { "runs": [{ "text": "Filter Shelf" }] }
                    }
                },
                "contents": [
                    {
                        "musicTwoRowItemRenderer": {
                            "title": { "runs": [{ "text": "Artist Card" }] },
                            "navigationEndpoint": {
                                "browseEndpoint": { "browseId": "UC1234567890" }
                            }
                        }
                    },
                    {
                        "musicTwoRowItemRenderer": {
                            "title": { "runs": [{ "text": "No ID Card" }] },
                            "navigationEndpoint": {}
                        }
                    },
                    {
                        "musicTwoRowItemRenderer": {
                            "title": { "runs": [{ "text": "Valid Song" }] },
                            "navigationEndpoint": {
                                "watchEndpoint": { "videoId": "valid_vid" }
                            }
                        }
                    }
                ]
            }
        """.trimIndent()
        val carouselObj = Json.parseToJsonElement(json).jsonObject
        val shelf = api.parseCarouselShelf(carouselObj)

        assertThat(shelf).isNotNull()
        assertThat(shelf?.items).hasSize(1)
        val track = shelf?.items?.first() as? HomeShelfItem.Track
        assertThat(track?.track?.videoId).isEqualTo("valid_vid")
    }

    @Test
    fun parseCarouselShelf_returnsNullWhenAllItemsAreSkipped() {
        val json = """
            {
                "header": {
                    "musicCarouselShelfBasicHeaderRenderer": {
                        "title": { "runs": [{ "text": "Only Artists" }] }
                    }
                },
                "contents": [
                    {
                        "musicTwoRowItemRenderer": {
                            "title": { "runs": [{ "text": "Artist Card" }] },
                            "navigationEndpoint": {
                                "browseEndpoint": { "browseId": "UC99999" }
                            }
                        }
                    }
                ]
            }
        """.trimIndent()
        val carouselObj = Json.parseToJsonElement(json).jsonObject
        val shelf = api.parseCarouselShelf(carouselObj)

        assertThat(shelf).isNull()
    }

    @Test
    fun parseHomeShelves_preservesOriginalShelfOrderAndHierarchy() {
        val sampleHomeJson = """
            {
                "contents": {
                    "singleColumnBrowseResultsRenderer": {
                        "tabs": [
                            {
                                "tabRenderer": {
                                    "content": {
                                        "sectionListRenderer": {
                                            "contents": [
                                                {
                                                    "musicCarouselShelfRenderer": {
                                                        "header": {
                                                            "musicCarouselShelfBasicHeaderRenderer": {
                                                                "title": { "runs": [{ "text": "Quick picks" }] },
                                                                "strapline": { "runs": [{ "text": "START RADIO FROM A SONG" }] }
                                                            }
                                                        },
                                                        "contents": [
                                                            {
                                                                "musicResponsiveListItemRenderer": {
                                                                    "flexColumns": [
                                                                        {
                                                                            "musicResponsiveListItemFlexColumnRenderer": {
                                                                                "text": { "runs": [{ "text": "Song A" }] }
                                                                            }
                                                                        }
                                                                    ],
                                                                    "playlistItemData": { "videoId": "song_a_id" }
                                                                }
                                                            }
                                                        ]
                                                    }
                                                },
                                                {
                                                    "musicCarouselShelfRenderer": {
                                                        "header": {
                                                            "musicCarouselShelfBasicHeaderRenderer": {
                                                                "title": { "runs": [{ "text": "Forgotten favorites" }] }
                                                            }
                                                        },
                                                        "contents": [
                                                            {
                                                                "musicTwoRowItemRenderer": {
                                                                    "title": { "runs": [{ "text": "Favorite Mix" }] },
                                                                    "navigationEndpoint": {
                                                                        "browseEndpoint": { "browseId": "VLPL_fav_mix" }
                                                                    }
                                                                }
                                                            }
                                                        ]
                                                    }
                                                },
                                                {
                                                    "musicCarouselShelfRenderer": {
                                                        "header": {
                                                            "musicCarouselShelfBasicHeaderRenderer": {
                                                                "title": { "runs": [{ "text": "Recommended music videos" }] }
                                                            }
                                                        },
                                                        "contents": [
                                                            {
                                                                "musicTwoRowItemRenderer": {
                                                                    "title": { "runs": [{ "text": "Video One" }] },
                                                                    "navigationEndpoint": {
                                                                        "watchEndpoint": { "videoId": "video_1_id" }
                                                                    }
                                                                }
                                                            }
                                                        ]
                                                    }
                                                }
                                            ]
                                        }
                                    }
                                }
                            }
                        ]
                    }
                }
            }
        """.trimIndent()

        val root = Json.parseToJsonElement(sampleHomeJson)
        val shelves = api.parseHomeShelves(root)

        assertThat(shelves).hasSize(3)

        // Exact original order preserved
        assertThat(shelves[0].title).isEqualTo("Quick picks")
        assertThat(shelves[0].subtitle).isEqualTo("START RADIO FROM A SONG")
        assertThat(shelves[0].items).hasSize(1)
        assertThat((shelves[0].items[0] as HomeShelfItem.Track).track.videoId).isEqualTo("song_a_id")

        assertThat(shelves[1].title).isEqualTo("Forgotten favorites")
        assertThat(shelves[1].subtitle).isNull()
        assertThat(shelves[1].items).hasSize(1)
        assertThat((shelves[1].items[0] as HomeShelfItem.Playlist).playlist.id).isEqualTo("PL_fav_mix")

        assertThat(shelves[2].title).isEqualTo("Recommended music videos")
        assertThat(shelves[2].subtitle).isNull()
        assertThat(shelves[2].items).hasSize(1)
        assertThat((shelves[2].items[0] as HomeShelfItem.Track).track.videoId).isEqualTo("video_1_id")
    }
}
