package com.lastwave.app.data.music

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.ytmusic.YtConnection
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class YtMusicRecommendationsTest {
    private val songId = "targetSong1"

    @Test
    fun selectsTheExactSongEvenWhenNeighbourAndGenericFeedbackComeFirst() {
        val root = json("""{"contents":[
            {"playlistPanelVideoRenderer":{"videoId":"neighbour11","menu":${menu("WRONG_SONG")}}},
            {"playlistPanelVideoRenderer":{"videoId":"$songId","menu":{"menuRenderer":{"items":[
                {"menuServiceItemRenderer":{"icon":{"iconType":"REMOVE_FROM_HISTORY"},
                    "serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"HISTORY"}}}},
                {"toggleMenuServiceItemRenderer":{"defaultIcon":{"iconType":"BOOKMARK_BORDER"},
                    "defaultServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"LIBRARY"}}}},
                ${action("TARGET")}
            ]}}}}
        ]}""")
        assertThat(notInterestedToken(root, songId)).isEqualTo("TARGET")
    }

    @Test
    fun regularMenuUsesTheActionTokenAndNeverItsNestedUndoToken() {
        // Mirrors the service/undo structure in the captured Soncle YTM home fixture.
        val root = json("""{"playlistPanelVideoRenderer":{"videoId":"$songId","menu":{"menuRenderer":{"items":[
            {"menuServiceItemRenderer":{"text":{"runs":[{"text":"Not interested"}]},
                "icon":{"iconType":"NOT_INTERESTED"},"serviceEndpoint":{"feedbackEndpoint":{
                    "feedbackToken":"EXCLUDE","actions":[{"openPopupAction":{"popup":{
                        "notificationActionRenderer":{"actionButton":{"buttonRenderer":{
                            "command":{"feedbackEndpoint":{"feedbackToken":"UNDO"}}
                        }}}
                    }}}]
                }}}}
        ]}}}}""")
        assertThat(notInterestedToken(root, songId)).isEqualTo("EXCLUDE")
    }

    @Test
    fun toggleMenuUsesOnlyTheNotInterestedDefaultEndpoint() {
        val root = json("""{"playlistPanelVideoRenderer":{"videoId":"$songId","menu":{"menuRenderer":{"items":[
            {"toggleMenuServiceItemRenderer":{"defaultIcon":{"iconType":"NOT_INTERESTED"},
                "defaultServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"EXCLUDE"}},
                "toggledServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"UNDO"}}}}
        ]}}}}""")
        assertThat(notInterestedToken(root, songId)).isEqualTo("EXCLUDE")
    }

    @Test
    fun absentActionDoesNotFallBackToLibraryOrUndoFeedback() {
        val root = json("""{"playlistPanelVideoRenderer":{"videoId":"$songId","menu":{"menuRenderer":{"items":[
            {"toggleMenuServiceItemRenderer":{"defaultIcon":{"iconType":"BOOKMARK"},
                "defaultServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"REMOVE_LIBRARY"}},
                "toggledServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"ADD_LIBRARY"}}}},
            {"menuServiceItemRenderer":{"icon":{"iconType":"NOT_INTERESTED"},
                "serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"", "actions":[{
                    "undo":{"feedbackEndpoint":{"feedbackToken":"UNDO"}}
                }]}}}}
        ]}}}}""")
        assertThat(notInterestedToken(root, songId)).isNull()
        assertThat(notInterestedToken(json("{}"), songId)).isNull()
        assertThat(notInterestedToken(root, "")).isNull()
        assertThat(notInterestedToken(root, "absentSong1")).isNull()
    }

    @Test
    fun playlistItemAndWatchNavigationIdentifyOnlyTheirOwnSongMenu() {
        val playlistItem = json("""{"musicResponsiveListItemRenderer":{
            "playlistItemData":{"videoId":"$songId"},"menu":${menu("ITEM")}
        }}""")
        val watchNavigation = json("""{"musicTwoRowItemRenderer":{
            "navigationEndpoint":{"watchEndpoint":{"videoId":"$songId"}},"menu":${menu("WATCH")}
        }}""")
        assertThat(notInterestedToken(playlistItem, songId)).isEqualTo("ITEM")
        assertThat(notInterestedToken(watchNavigation, songId)).isEqualTo("WATCH")
    }

    @Test
    fun overlaySongIdentityCanResolveASongCard() {
        val root = json("""{"musicResponsiveListItemRenderer":{
            "overlay":${overlay(songId)},"menu":${menu("SONG")}
        }}""")
        assertThat(notInterestedToken(root, songId)).isEqualTo("SONG")
    }

    @Test
    fun albumShowAndPlaylistFeedbackCannotBeBorrowedFromTheirPlayOverlaySong() {
        listOf("MPREbalbum", "MPSPPLshow", "VLPLplaylist").forEach { browseId ->
            val root = json("""{"musicTwoRowItemRenderer":{
                "navigationEndpoint":{"browseEndpoint":{"browseId":"$browseId"}},
                "overlay":${overlay(songId)},"menu":${menu("CONTAINER_NOT_SONG")}
            }}""")
            assertThat(notInterestedToken(root, songId)).isNull()
        }
    }

    @Test
    fun requiresAnExplicitNonEmptyAllProcessedServerResponse() {
        assertThat(feedbackWasProcessed(json("""{"feedbackResponses":[{"isProcessed":true}]}"""))).isTrue()
        assertThat(feedbackWasProcessed(json("""{"feedbackResponses":[{"isProcessed":true},{"isProcessed":true}]}"""))).isTrue()
        listOf(
            "{}", """{"feedbackResponses":[]}""", """{"feedbackResponses":[{}]}""",
            """{"feedbackResponses":[{"isProcessed":false}]}""",
            """{"feedbackResponses":[{"isProcessed":true},{"isProcessed":false}]}""",
        ).forEach { response -> assertThat(feedbackWasProcessed(json(response))).isFalse() }
    }

    @Test
    fun authenticatedFeedbackUsesTheCapturedAccountAndExactToken() = runBlocking {
        val account = YtConnection(cookies = mapOf("SAPISID" to "fixture-cookie"), accountName = "Fixture")
        val connection = MutableStateFlow(account)
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val api = api(connection)
        coEvery { api.post(
            url = any(), body = any(), clientName = "WEB_REMIX", clientVersion = "test-version",
            userAgent = WEB_USER_AGENT, authenticated = true, authenticatedAccount = account,
        ) } answers {
            val url = firstArg<String>()
            calls += url to secondArg<JsonObject>()
            if (url.contains("/next?")) json("""{"playlistPanelVideoRenderer":{"videoId":"$songId","menu":${menu("EXACT_TOKEN")}}}""")
            else json("""{"feedbackResponses":[{"isProcessed":true}]}""")
        }

        assertThat(api.markNotInterested(songId, account)).isTrue()
        assertThat(calls.map { it.first.substringBefore('?').substringAfterLast('/') }).containsExactly("next", "feedback").inOrder()
        assertThat(calls.last().second["feedbackTokens"].toString()).isEqualTo("[\"EXACT_TOKEN\"]")
    }

    @Test
    fun switchingAccountsAfterTokenLookupNeverPostsThatAccountsToken() = runBlocking {
        val account = YtConnection(cookies = mapOf("SAPISID" to "fixture-cookie"), accountName = "First")
        val connection = MutableStateFlow(account)
        val calls = mutableListOf<String>()
        val api = api(connection)
        coEvery { api.post(
            url = any(), body = any(), clientName = "WEB_REMIX", clientVersion = "test-version",
            userAgent = WEB_USER_AGENT, authenticated = true, authenticatedAccount = account,
        ) } answers {
            calls += firstArg<String>()
            connection.value = account.copy(accountName = "Second", pageId = "different-channel")
            json("""{"playlistPanelVideoRenderer":{"videoId":"$songId","menu":${menu("FIRST_ACCOUNT_TOKEN")}}}""")
        }

        assertThat(api.markNotInterested(songId, account)).isFalse()
        assertThat(calls).hasSize(1)
        assertThat(calls.single()).contains("/next?")
    }

    private fun api(connection: MutableStateFlow<YtConnection>): InnerTubeMusicApi {
        val auth = mockk<YtMusicAuthManager>()
        every { auth.connection } returns connection
        val api = mockk<InnerTubeMusicApi>()
        every { api.ytAuth } returns auth
        coEvery { api.getWebConfig() } returns InnerTubeMusicApi.WebConfig("test-key", "test-version", null)
        every { api.context("WEB_REMIX", "test-version", null, authenticated = true) } returns JsonObject(emptyMap())
        return api
    }

    private fun menu(token: String) = """{"menuRenderer":{"items":[${action(token)}]}}"""
    private fun action(token: String) = """{"menuServiceItemRenderer":{
        "icon":{"iconType":"NOT_INTERESTED"},
        "serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"$token"}}
    }}"""
    private fun overlay(videoId: String) = """{"musicItemThumbnailOverlayRenderer":{"content":{
        "musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchEndpoint":{"videoId":"$videoId"}}}
    }}}"""
    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
