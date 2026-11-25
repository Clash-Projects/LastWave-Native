package com.lastwave.app.data.lossless

import com.lastwave.app.data.addon.HybridMediaServer
import com.lastwave.app.data.addon.LoopbackHttpServer
import com.lastwave.app.data.local.HttpAddonConfig
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.plugin.ModuleManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AddonVariantMismatchTest {

    private fun createApi(serverUrl: String, http: OkHttpClient): LosslessMusicApi {
        val settings = mockk<SettingsPreferences>()
        every { settings.settings } returns MutableStateFlow(
            MiscSettings(
                httpAddons = listOf(HttpAddonConfig(url = "$serverUrl/a/test-token", enabled = true)),
            ),
        )
        val native = mockk<NativeSecrets>()
        every { native.signAddonRequest(any(), any()) } returns null
        return LosslessMusicApi(
            okHttpClient = http,
            moduleManager = mockk<ModuleManager>(),
            nativeSecrets = native,
            settingsPreferences = settings,
            hybridServer = mockk<HybridMediaServer>(relaxUnitFun = true),
        )
    }

    private fun startServer(searchJson: String): LoopbackHttpServer {
        var firstRequest = true
        return LoopbackHttpServer { socket ->
            val streamJson = """{"url":"https://cdn.example.com/track.flac","format":"flac","codec":"flac","bitDepth":16,"sampleRate":44100}"""
            val payload = if (firstRequest) {
                firstRequest = false
                searchJson
            } else {
                streamJson
            }
            val bytes = payload.toByteArray()
            LoopbackHttpServer.writeHeaders(socket, bytes.size)
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        }
    }

    @Test
    fun rejectsInstrumentalAlbumAndAcapellaAnd8dTracks() = runBlocking {
        val searchJson = """
            {"tracks":[
                {"id":"1","title":"Shape of You","artist":"Ed Sheeran","album":"Shape of You (Instrumental)","duration":233,"audioQuality":"LOSSLESS"},
                {"id":"2","title":"Shape of You (A Cappella)","artist":"Ed Sheeran","album":"÷","duration":233,"audioQuality":"LOSSLESS"},
                {"id":"3","title":"Shape of You (8D Audio)","artist":"Ed Sheeran","album":"÷","duration":233,"audioQuality":"LOSSLESS"},
                {"id":"4","title":"Shape of You (Off Vocal)","artist":"Ed Sheeran","album":"÷","duration":233,"audioQuality":"LOSSLESS"},
                {"id":"5","title":"Shape of You","artist":"Ed Sheeran Karaoke","album":"÷","duration":233,"audioQuality":"LOSSLESS"}
            ]}
        """.trimIndent()
        val server = startServer(searchJson)
        val http = OkHttpClient()
        try {
            val api = createApi(server.url, http)
            val resolved = api.resolveStream(
                title = "Shape of You",
                artist = "Ed Sheeran",
                expectedDurationSeconds = 233,
                expectedAlbum = "÷",
                preferredQuality = LosslessMusicApi.QUALITY_CD_LOSSLESS,
            )
            assertNull("Must reject instrumental, acapella, 8D, off-vocal, and karaoke candidates", resolved)
        } finally {
            server.close()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun picksFullSongOverInstrumentalAndStrippedVariants() = runBlocking {
        val searchJson = """
            {"tracks":[
                {"id":"11","title":"Blinding Lights","artist":"The Weeknd","album":"After Hours (Instrumentals)","duration":200,"audioQuality":"HI_RES_LOSSLESS"},
                {"id":"12","title":"Blinding Lights","artist":"The Weeknd","album":"After Hours (Slowed + Reverb)","duration":200,"audioQuality":"HI_RES_LOSSLESS"},
                {"id":"13","title":"Blinding Lights","artist":"The Weeknd","album":"After Hours","duration":200,"audioQuality":"LOSSLESS"}
            ]}
        """.trimIndent()
        val server = startServer(searchJson)
        val http = OkHttpClient()
        try {
            val api = createApi(server.url, http)
            val resolved = api.resolveStream(
                title = "Blinding Lights",
                artist = "The Weeknd",
                expectedDurationSeconds = 200,
                expectedAlbum = "After Hours",
                preferredQuality = LosslessMusicApi.QUALITY_MAX_HI_RES,
            )
            assertNotNull(resolved)
            assertEquals(13L, resolved!!.trackId)
        } finally {
            server.close()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
