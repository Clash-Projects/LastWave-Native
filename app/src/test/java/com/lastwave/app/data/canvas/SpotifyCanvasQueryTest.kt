package com.lastwave.app.data.canvas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpotifyCanvasQueryTest {

    private val hash = "a".repeat(64)

    @Test
    fun testRequestBodyCarriesTheTrackAndTheHash() {
        val body = SpotifyCanvasQuery.requestBody("spotify:track:abc123", hash)
        assertThat(body).contains("\"operationName\":\"canvas\"")
        assertThat(body).contains("\"trackUri\":\"spotify:track:abc123\"")
        assertThat(body).contains(hash)
    }

    @Test
    fun testParseFindsAVideoCanvas() {
        val answer = SpotifyCanvasQuery.parse(
            """
            {"data":{"trackUnion":{"canvas":{
              "type":"VIDEO","url":"https://canvaz.scdn.co/video/loop.cnvs.mp4"
            }}}}
            """.trimIndent(),
        )
        assertThat(answer).isInstanceOf(SpotifyCanvasQuery.Answer.Found::class.java)
        val found = answer as SpotifyCanvasQuery.Answer.Found
        assertThat(found.url).isEqualTo("https://canvaz.scdn.co/video/loop.cnvs.mp4")
        assertThat(found.type).isEqualTo("VIDEO")
    }

    @Test
    fun testParseReportsACanvasThatIsNotAVideo() {
        // The web player plays a video canvas from its file id and only carries a url for
        // images, so a canvas that comes back without one is not playable here.
        val answer = SpotifyCanvasQuery.parse(
            """
            {"data":{"trackUnion":{"canvas":{
              "type":"VIDEO","fileId":"some-file-id","url":null
            }}}}
            """.trimIndent(),
        )
        assertThat(answer).isInstanceOf(SpotifyCanvasQuery.Answer.NoCanvas::class.java)
    }

    @Test
    fun testParseReportsAnImageCanvasAsNotPlayable() {
        val answer = SpotifyCanvasQuery.parse(
            """
            {"data":{"trackUnion":{"canvas":{
              "type":"IMAGE","url":"https://i.scdn.co/image/loop.gif"
            }}}}
            """.trimIndent(),
        )
        assertThat(answer).isInstanceOf(SpotifyCanvasQuery.Answer.NoCanvas::class.java)
    }

    @Test
    fun testParseFlagsARetiredHash() {
        val answer = SpotifyCanvasQuery.parse(
            """
            {"errors":[{"message":"PersistedQueryNotFound"}],"data":null}
            """.trimIndent(),
        )
        assertThat(answer).isInstanceOf(SpotifyCanvasQuery.Answer.Failed::class.java)
        assertThat((answer as SpotifyCanvasQuery.Answer.Failed).staleHash).isTrue()
    }

    @Test
    fun testParseReportsAGraphQLErrorWithoutData() {
        val answer = SpotifyCanvasQuery.parse("""{"errors":[{"message":"Bad request"}]}""")
        assertThat(answer).isInstanceOf(SpotifyCanvasQuery.Answer.Failed::class.java)
        assertThat((answer as SpotifyCanvasQuery.Answer.Failed).staleHash).isFalse()
    }

    @Test
    fun testParseReportsAnUnusableBody() {
        assertThat(SpotifyCanvasQuery.parse("not json"))
            .isInstanceOf(SpotifyCanvasQuery.Answer.Failed::class.java)
    }
}
