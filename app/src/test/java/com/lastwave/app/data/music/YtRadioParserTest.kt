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
}
