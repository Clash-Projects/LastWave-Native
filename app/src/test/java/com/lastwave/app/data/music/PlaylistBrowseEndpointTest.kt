package com.lastwave.app.data.music

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** Browse endpoints as returned on an artist page (shapes taken from a real WEB_REMIX response). */
class PlaylistBrowseEndpointTest {
    private fun endpoint(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun albumIsNotAPlaylist() {
        val album = endpoint(
            """{"browseId":"MPREb_eIEQTho6yB8","params":"ggMr",
               "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":
               {"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}"""
        )
        assertThat(isPlaylistBrowseEndpoint(album)).isFalse()
    }

    @Test
    fun featuredOnPlaylistIsAPlaylist() {
        val playlist = endpoint(
            """{"browseId":"VLRDCLAK5uy_m9t0TCPOp3z-nlKLyQu94e3U6HjmbiPjE",
               "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":
               {"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}}"""
        )
        assertThat(isPlaylistBrowseEndpoint(playlist)).isTrue()
    }

    @Test
    fun playlistIdWithoutPageTypeIsAPlaylist() {
        assertThat(isPlaylistBrowseEndpoint(endpoint("""{"browseId":"VLPLIq9HIlWjabc"}"""))).isTrue()
    }

    @Test
    fun releaseWithoutPageTypeIsNotAPlaylist() {
        assertThat(isPlaylistBrowseEndpoint(endpoint("""{"browseId":"MPREb_TPuOvMabc"}"""))).isFalse()
    }

    @Test
    fun pageTypeWinsOverBrowseIdPrefix() {
        val album = endpoint(
            """{"browseId":"VLOLAK5uy_abc",
               "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":
               {"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}"""
        )
        assertThat(isPlaylistBrowseEndpoint(album)).isFalse()
    }

    @Test
    fun missingEndpointIsNotAPlaylist() {
        assertThat(isPlaylistBrowseEndpoint(null)).isFalse()
    }
}
