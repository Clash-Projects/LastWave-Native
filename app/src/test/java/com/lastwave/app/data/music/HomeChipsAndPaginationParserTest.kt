package com.lastwave.app.data.music

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for YouTube Music Home chips, continuation token extraction,
 * and continuation shelf parsing.
 */
class HomeChipsAndPaginationParserTest {

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
    fun parseHomeChips_extractsTitlesAndParams() {
        val json = """
            {
                "contents": {
                    "singleColumnBrowseResultsRenderer": {
                        "tabs": [
                            {
                                "tabRenderer": {
                                    "content": {
                                        "sectionListRenderer": {
                                            "header": {
                                                "chipCloudRenderer": {
                                                    "chips": [
                                                        {
                                                            "chipCloudChipRenderer": {
                                                                "text": { "runs": [{ "text": "Relax" }] },
                                                                "navigationEndpoint": {
                                                                    "browseEndpoint": {
                                                                        "browseId": "FEmusic_home",
                                                                        "params": "relax_params_123"
                                                                    }
                                                                },
                                                                "isSelected": false
                                                            }
                                                        },
                                                        {
                                                            "chipCloudChipRenderer": {
                                                                "text": { "runs": [{ "text": "Energize" }] },
                                                                "navigationEndpoint": {
                                                                    "browseEndpoint": {
                                                                        "browseId": "FEmusic_home",
                                                                        "params": "energize_params_456"
                                                                    }
                                                                },
                                                                "isSelected": true
                                                            }
                                                        }
                                                    ]
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        ]
                    }
                }
            }
        """.trimIndent()

        val root = Json.parseToJsonElement(json)
        val chips = api.parseHomeChips(root)

        assertThat(chips).hasSize(2)
        assertThat(chips[0].title).isEqualTo("Relax")
        assertThat(chips[0].params).isEqualTo("relax_params_123")
        assertThat(chips[0].browseId).isEqualTo("FEmusic_home")

        assertThat(chips[1].title).isEqualTo("Energize")
        assertThat(chips[1].params).isEqualTo("energize_params_456")
        assertThat(chips[1].browseId).isEqualTo("FEmusic_home")
    }

    @Test
    fun parseHomeChips_skipsChipsWithMissingTitleOrParams() {
        val json = """
            {
                "contents": {
                    "singleColumnBrowseResultsRenderer": {
                        "tabs": [
                            {
                                "tabRenderer": {
                                    "content": {
                                        "sectionListRenderer": {
                                            "header": {
                                                "chipCloudRenderer": {
                                                    "chips": [
                                                        {
                                                            "chipCloudChipRenderer": {
                                                                "text": { "runs": [] },
                                                                "navigationEndpoint": {
                                                                    "browseEndpoint": { "params": "param1" }
                                                                }
                                                            }
                                                        },
                                                        {
                                                            "chipCloudChipRenderer": {
                                                                "text": { "runs": [{ "text": "No Params" }] },
                                                                "navigationEndpoint": {
                                                                    "browseEndpoint": {}
                                                                }
                                                            }
                                                        },
                                                        {
                                                            "chipCloudChipRenderer": {
                                                                "text": { "runs": [{ "text": "Blank Params" }] },
                                                                "navigationEndpoint": {
                                                                    "browseEndpoint": { "params": "   " }
                                                                }
                                                            }
                                                        },
                                                        {
                                                            "unknownRenderer": {}
                                                        },
                                                        {
                                                            "chipCloudChipRenderer": {
                                                                "text": { "runs": [{ "text": "Valid Chip" }] },
                                                                "navigationEndpoint": {
                                                                    "browseEndpoint": { "params": "valid_params" }
                                                                }
                                                            }
                                                        }
                                                    ]
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        ]
                    }
                }
            }
        """.trimIndent()

        val root = Json.parseToJsonElement(json)
        val chips = api.parseHomeChips(root)

        assertThat(chips).hasSize(1)
        assertThat(chips[0].title).isEqualTo("Valid Chip")
        assertThat(chips[0].params).isEqualTo("valid_params")
    }

    @Test
    fun parseHomeChips_supportsSimpleTextFallback() {
        val json = """
            {
                "contents": {
                    "singleColumnBrowseResultsRenderer": {
                        "tabs": [
                            {
                                "tabRenderer": {
                                    "content": {
                                        "sectionListRenderer": {
                                            "header": {
                                                "chipCloudRenderer": {
                                                    "chips": [
                                                        {
                                                            "chipCloudChipRenderer": {
                                                                "text": { "simpleText": "Workout" },
                                                                "navigationEndpoint": {
                                                                    "browseEndpoint": { "params": "workout_params" }
                                                                }
                                                            }
                                                        }
                                                    ]
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        ]
                    }
                }
            }
        """.trimIndent()

        val root = Json.parseToJsonElement(json)
        val chips = api.parseHomeChips(root)

        assertThat(chips).hasSize(1)
        assertThat(chips[0].title).isEqualTo("Workout")
        assertThat(chips[0].params).isEqualTo("workout_params")
    }

    @Test
    fun parseHomeContinuationToken_fromPage1() {
        val json = """
            {
                "contents": {
                    "singleColumnBrowseResultsRenderer": {
                        "tabs": [
                            {
                                "tabRenderer": {
                                    "content": {
                                        "sectionListRenderer": {
                                            "continuations": [
                                                {
                                                    "nextContinuationData": {
                                                        "continuation": "token_page_1_to_2"
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

        val root = Json.parseToJsonElement(json)
        val token = api.parseHomeContinuationToken(root)

        assertThat(token).isEqualTo("token_page_1_to_2")
    }

    @Test
    fun parseHomeContinuationToken_fromContinuationResponse() {
        val json = """
            {
                "continuationContents": {
                    "sectionListContinuation": {
                        "continuations": [
                            {
                                "nextContinuationData": {
                                    "continuation": "token_page_2_to_3"
                                }
                            }
                        ]
                    }
                }
            }
        """.trimIndent()

        val root = Json.parseToJsonElement(json)
        val token = api.parseHomeContinuationToken(root)

        assertThat(token).isEqualTo("token_page_2_to_3")
    }

    @Test
    fun parseHomeContinuationToken_returnsNullWhenNoToken() {
        val json = """
            {
                "continuationContents": {
                    "sectionListContinuation": {
                        "contents": []
                    }
                }
            }
        """.trimIndent()

        val root = Json.parseToJsonElement(json)
        val token = api.parseHomeContinuationToken(root)

        assertThat(token).isNull()
    }

    @Test
    fun parseHomeContinuationShelves_extractsCarousels() {
        val json = """
            {
                "continuationContents": {
                    "sectionListContinuation": {
                        "contents": [
                            {
                                "musicCarouselShelfRenderer": {
                                    "header": {
                                        "musicCarouselShelfBasicHeaderRenderer": {
                                            "title": { "runs": [{ "text": "Page 2 Shelf" }] },
                                            "strapline": { "runs": [{ "text": "SUBTITLE" }] }
                                        }
                                    },
                                    "contents": [
                                        {
                                            "musicTwoRowItemRenderer": {
                                                "title": { "runs": [{ "text": "Song Page 2" }] },
                                                "navigationEndpoint": {
                                                    "watchEndpoint": { "videoId": "vid_page_2" }
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
        """.trimIndent()

        val root = Json.parseToJsonElement(json)
        val shelves = api.parseHomeContinuationShelves(root)

        assertThat(shelves).hasSize(1)
        assertThat(shelves[0].title).isEqualTo("Page 2 Shelf")
        assertThat(shelves[0].subtitle).isEqualTo("SUBTITLE")
        assertThat(shelves[0].items).hasSize(1)
        assertThat((shelves[0].items[0] as HomeShelfItem.Track).track.videoId).isEqualTo("vid_page_2")
    }
}
