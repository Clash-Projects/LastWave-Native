package com.lastwave.app.data.music

import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class YtRadioParserTest {

    private lateinit var api: InnerTubeMusicApi
    private val json = Json { ignoreUnknownKeys = true }

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
    fun parsePlaylistPanelItem_plainRenderer_extractsSong() {
        val sampleJson = """
            {
                "playlistPanelVideoRenderer": {
                    "videoId": "vid123",
                    "title": { "runs": [{ "text": "Song One" }] },
                    "longBylineText": {
                        "runs": [
                            {
                                "text": "Artist One",
                                "navigationEndpoint": {
                                    "browseEndpoint": { "browseId": "UC12345" }
                                }
                            }
                        ]
                    },
                    "lengthText": { "runs": [{ "text": "3:45" }] },
                    "thumbnail": {
                        "thumbnails": [
                            { "url": "https://lh3.googleusercontent.com/test.jpg" }
                        ]
                    }
                }
            }
        """.trimIndent()

        val element = json.parseToJsonElement(sampleJson)
        val track = api.parsePlaylistPanelItem(element)

        assertNotNull(track)
        assertEquals("vid123", track?.videoId)
        assertEquals("Song One", track?.title)
        assertEquals("Artist One", track?.artist)
        assertEquals(225, track?.durationSeconds)
        assertEquals("https://lh3.googleusercontent.com/test.jpg", track?.artworkUrl)
    }

    @Test
    fun parsePlaylistPanelItem_wrapperRenderer_extractsSong() {
        val sampleJson = """
            {
                "playlistPanelVideoWrapperRenderer": {
                    "primaryRenderer": {
                        "playlistPanelVideoRenderer": {
                            "videoId": "wrappedVid456",
                            "title": { "runs": [{ "text": "Wrapped Song" }] },
                            "shortBylineText": {
                                "runs": [{ "text": "Wrapped Artist" }]
                            },
                            "lengthText": { "runs": [{ "text": "2:10" }] }
                        }
                    }
                }
            }
        """.trimIndent()

        val element = json.parseToJsonElement(sampleJson)
        val track = api.parsePlaylistPanelItem(element)

        assertNotNull(track)
        assertEquals("wrappedVid456", track?.videoId)
        assertEquals("Wrapped Song", track?.title)
        assertEquals("Wrapped Artist", track?.artist)
        assertEquals(130, track?.durationSeconds)
    }

    @Test
    fun extractRadioContinuationToken_nextRadioContinuationData_extractsToken() {
        val sampleJson = """
            {
                "continuations": [
                    {
                        "nextRadioContinuationData": {
                            "continuation": "token_abc_123"
                        }
                    }
                ]
            }
        """.trimIndent()

        val element = json.parseToJsonElement(sampleJson)
        val token = api.extractRadioContinuationToken(element)

        assertEquals("token_abc_123", token)
    }

    @Test
    fun extractRadioContinuationToken_continuationCommand_extractsToken() {
        val sampleJson = """
            {
                "continuationEndpoint": {
                    "continuationCommand": {
                        "token": "token_command_789"
                    }
                }
            }
        """.trimIndent()

        val element = json.parseToJsonElement(sampleJson)
        val token = api.extractRadioContinuationToken(element)

        assertEquals("token_command_789", token)
    }

    @Test
    fun extractAutomixPlaylistId_automixRenderer_extractsPlaylistId() {
        val sampleJson = """
            {
                "automixPreviewVideoRenderer": {
                    "content": {
                        "automixPlaylistVideoRenderer": {
                            "navigationEndpoint": {
                                "watchPlaylistEndpoint": {
                                    "playlistId": "RDAMVMsample_automix_id"
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        val element = json.parseToJsonElement(sampleJson)
        val playlistId = api.extractAutomixPlaylistId(element)

        assertEquals("RDAMVMsample_automix_id", playlistId)
    }

    @Test
    fun parseRadioResponse_fullStructure_extractsTracksTokensAndAutomixInOrder() {
        val sampleJson = """
            {
                "contents": {
                    "singleColumnMusicWatchNextResultsRenderer": {
                        "tabbedRenderer": {
                            "watchNextTabbedResultsRenderer": {
                                "tabs": [
                                    {
                                        "tabRenderer": {
                                            "content": {
                                                "musicQueueRenderer": {
                                                    "content": {
                                                        "playlistPanelRenderer": {
                                                            "contents": [
                                                                {
                                                                    "playlistPanelVideoRenderer": {
                                                                        "videoId": "song1",
                                                                        "title": { "runs": [{ "text": "First Song" }] },
                                                                        "shortBylineText": { "runs": [{ "text": "Artist A" }] }
                                                                    }
                                                                },
                                                                {
                                                                    "playlistPanelVideoWrapperRenderer": {
                                                                        "primaryRenderer": {
                                                                            "playlistPanelVideoRenderer": {
                                                                                "videoId": "song2",
                                                                                "title": { "runs": [{ "text": "Second Song" }] },
                                                                                "shortBylineText": { "runs": [{ "text": "Artist B" }] }
                                                                            }
                                                                        }
                                                                    }
                                                                },
                                                                {
                                                                    "automixPreviewVideoRenderer": {
                                                                        "content": {
                                                                            "automixPlaylistVideoRenderer": {
                                                                                "navigationEndpoint": {
                                                                                    "watchPlaylistEndpoint": {
                                                                                        "playlistId": "RDAMVMchained"
                                                                                    }
                                                                                }
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            ],
                                                            "continuations": [
                                                                {
                                                                    "nextRadioContinuationData": {
                                                                        "continuation": "radio_cont_token"
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
                }
            }
        """.trimIndent()

        val root = json.parseToJsonElement(sampleJson)
        val result = api.parseRadioResponse(root)

        assertEquals(2, result.tracks.size)
        assertEquals("song1", result.tracks[0].videoId)
        assertEquals("song2", result.tracks[1].videoId)
        assertEquals("radio_cont_token", result.continuationToken)
        assertEquals("RDAMVMchained", result.automixPlaylistId)
    }
}
