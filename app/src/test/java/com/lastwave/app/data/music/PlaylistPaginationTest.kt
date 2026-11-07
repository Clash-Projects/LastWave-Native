package com.lastwave.app.data.music

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Test

class PlaylistPaginationTest {
    private val api = InnerTubeMusicApi(
        http = OkHttpClient(),
        streamExtractor = mockk(relaxed = true),
        innerTubeXExtractor = mockk(relaxed = true),
        ytAuth = mockk(relaxed = true),
        settingsPreferences = mockk(relaxed = true),
        appContext = mockk(relaxed = true),
    )

    @Test
    fun finishedPlaylistDoesNotFollowRecommendationToken() {
        val root = Json.parseToJsonElement("""
            {"contents":[
                {"musicPlaylistShelfRenderer":{"contents":[]}},
                {"musicShelfRenderer":{
                    "title":{"runs":[{"text":"Recommended songs"}]},
                    "contents":[{"continuationItemRenderer":{
                        "continuationEndpoint":{"continuationCommand":{"token":"suggestions"}}
                    }}]
                }}
            ]}
        """.trimIndent())

        assertThat(api.playlistPageContinuationToken(root)).isNull()
    }

    @Test
    fun playlistTokenWinsOverEarlierRecommendationToken() {
        val root = Json.parseToJsonElement("""
            {"contents":[
                {"musicShelfRenderer":{
                    "title":{"runs":[{"text":"Suggestions"}]},
                    "continuations":[{"nextContinuationData":{"continuation":"suggestions"}}]
                }},
                {"musicPlaylistShelfRenderer":{
                    "contents":[{"continuationItemRenderer":{
                        "continuationEndpoint":{"continuationCommand":{"token":"playlist-page-2"}}
                    }}]
                }}
            ]}
        """.trimIndent())

        assertThat(api.playlistPageContinuationToken(root)).isEqualTo("playlist-page-2")
    }

    @Test
    fun knownEmptyFinalShelfIsComplete() {
        val root = Json.parseToJsonElement("""
            {"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[]}}}
        """.trimIndent())

        assertThat(api.isFinalPlaylistContinuation(root)).isTrue()
    }

    @Test
    fun emptyAppendResponseIsComplete() {
        val root = Json.parseToJsonElement("""
            {"onResponseReceivedActions":[
                {"appendContinuationItemsAction":{"continuationItems":[]}}
            ]}
        """.trimIndent())

        assertThat(api.isFinalPlaylistContinuation(root)).isTrue()
    }

    @Test
    fun unavailableOnlyFinalPageIsComplete() {
        val root = Json.parseToJsonElement("""
            {"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[
                {"musicResponsiveListItemRenderer":{
                    "displayPolicy":"MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT"
                }},
                {"playlistVideoRenderer":{"isPlayable":false}}
            ]}}}
        """.trimIndent())

        assertThat(api.isFinalPlaylistContinuation(root)).isTrue()
    }

    @Test
    fun unavailablePageWithAnotherTokenIsNotFinal() {
        val root = Json.parseToJsonElement("""
            {"continuationContents":{"musicPlaylistShelfContinuation":{
                "contents":[{"playlistVideoRenderer":{"isPlayable":false}}],
                "continuations":[{"nextContinuationData":{"continuation":"next-page"}}]
            }}}
        """.trimIndent())

        assertThat(api.playlistPageContinuationToken(root)).isEqualTo("next-page")
        assertThat(api.isFinalPlaylistContinuation(root)).isFalse()
    }

    @Test
    fun missingAndMalformedResponsesRemainIncomplete() {
        val malformedResponses = listOf(
            "{}",
            """{"error":{"code":403}}""",
            """{"continuationContents":{"musicPlaylistShelfContinuation":{}}}""",
            """{"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[{"unknownRenderer":{}}]}}}""",
            """{"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[{"musicResponsiveListItemRenderer":{}}]}}}""",
        )

        malformedResponses.forEach { response ->
            assertThat(api.isFinalPlaylistContinuation(Json.parseToJsonElement(response))).isFalse()
        }
    }
}
