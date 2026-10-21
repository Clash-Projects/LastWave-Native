package com.lastwave.app.data.spotify

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpotifyWebPlayerTest {

    @Test
    fun testScriptsAreReadOutOfThePage() {
        val html = """
            <script src="https://open.spotifycdn.com/cdn/build/web-player/web-player.abc1234.js"></script>
            <script src="https://open.spotifycdn.com/cdn/build/web-player/web-player.abc1234.js"></script>
            <script src="https://open.spotifycdn.com/cdn/build/web-player/vendor~web-player.def5678.js"></script>
        """.trimIndent()
        assertThat(SpotifyWebPlayer.scripts(html)).containsExactly(
            "https://open.spotifycdn.com/cdn/build/web-player/web-player.abc1234.js",
            "https://open.spotifycdn.com/cdn/build/web-player/vendor~web-player.def5678.js",
        ).inOrder()
    }

    @Test
    fun testQueryHashIsReadOutOfTheDeclaration() {
        val script = """["searchTracks","query","$searchHash"],["canvas","query","$canvasHash"]"""
        assertThat(SpotifyWebPlayer.queryHash(script, "canvas")).isEqualTo(canvasHash)
        assertThat(SpotifyWebPlayer.queryHash(script, "searchTracks")).isEqualTo(searchHash)
    }

    @Test
    fun testQueryHashIsNullForAnOperationThePlayerDoesNotDeclare() {
        val script = """["canvas","query","$canvasHash"]"""
        assertThat(SpotifyWebPlayer.queryHash(script, "searchTracks")).isNull()
    }

    @Test
    fun testSecretsAreReadOutOfThePlayer() {
        // The player holds a short list of them and uses the newest. The quotes around
        // each one differ, and the value itself can contain either kind of quote.
        val script = """let a=[{secret:',7/*F("rLJ2oxaKL^f+E1xvP@N',version:61},""" +
            """{secret:'OmE{ZA.J^":0FG\\Uz?[@WW',version:60},""" +
            """{secret:"{iOFn;4}<1PFYKPV?5{%u14]M>/V0hDH",version:59}]"""
        val secrets = SpotifyWebPlayer.secrets(script)
        assertThat(secrets).hasSize(3)
        assertThat(secrets.first().version).isEqualTo(61)
        assertThat(secrets.first().value).isEqualTo(",7/*F(\"rLJ2oxaKL^f+E1xvP@N")
        assertThat(secrets[1].value).isEqualTo("OmE{ZA.J^\":0FG\\Uz?[@WW")
        assertThat(secrets[2].value).isEqualTo("{iOFn;4}<1PFYKPV?5{%u14]M>/V0hDH")
    }

    @Test
    fun testSecretsAreEmptyWhenThePlayerCarriesNone() {
        assertThat(SpotifyWebPlayer.secrets("var a = 1;")).isEmpty()
    }

    private companion object {
        const val searchHash = "f78953bf9207d73493c27284103f5aeb6e728876d5793851bf79bc706127ff70"
        const val canvasHash = "575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f"
    }
}
