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

class AddonLanguageMismatchTest {

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
    fun rejectsAmbiguousMultiLanguageEvenWhenAlbumScoreGapExceedsMargin() = runBlocking {
        val searchJson = """
            {"tracks":[
                {"id":"101","title":"Ayudha Pooja","artist":"Kaala Bhairava","album":"Devara Part 1 (Hindi)","duration":180,"audioQuality":"LOSSLESS"},
                {"id":"102","title":"Ayudha Pooja (From \"Devara Part 1 - Telugu\")","artist":"Kaala Bhairava","album":"Ayudha Pooja","duration":180,"audioQuality":"LOSSLESS"}
            ]}
        """.trimIndent()
        val server = startServer(searchJson)
        val http = OkHttpClient()
        try {
            val api = createApi(server.url, http)
            val resolved = api.resolveStream(
                title = "Ayudha Pooja",
                artist = "Kaala Bhairava",
                expectedDurationSeconds = 180,
                expectedAlbum = "Devara Part 1",
                preferredQuality = LosslessMusicApi.QUALITY_CD_LOSSLESS,
            )
            assertNull("Must fall back to YouTube instead of guessing Hindi over Telugu", resolved)
        } finally {
            server.close()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun rejectsExplicitLanguageAlbumEditionWhenRequestNamesNoLanguage() = runBlocking {
        val searchJson = """
            {"tracks":[
                {"id":"201","title":"Ayudha Pooja","artist":"Kaala Bhairava","album":"Devara Part 1 (Hindi)","duration":180,"audioQuality":"LOSSLESS"}
            ]}
        """.trimIndent()
        val server = startServer(searchJson)
        val http = OkHttpClient()
        try {
            val api = createApi(server.url, http)
            val resolved = api.resolveStream(
                title = "Ayudha Pooja",
                artist = "Kaala Bhairava",
                expectedDurationSeconds = 180,
                expectedAlbum = "Devara Part 1",
                preferredQuality = LosslessMusicApi.QUALITY_CD_LOSSLESS,
            )
            assertNull("Must not play an explicit (Hindi) album edition for an unmarked request", resolved)
        } finally {
            server.close()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun rejectsUntaggedPlusTaggedLanguagePairWhenRequestNamesNoLanguage() = runBlocking {
        val searchJson = """
            {"tracks":[
                {"id":"251","title":"Arjan Vailly","artist":"Bhupinder Babbal","album":"ANIMAL","duration":180,"audioQuality":"LOSSLESS"},
                {"id":"252","title":"Arjan Vailly","artist":"Bhupinder Babbal","album":"ANIMAL (Telugu)","duration":180,"audioQuality":"LOSSLESS"}
            ]}
        """.trimIndent()
        val server = startServer(searchJson)
        val http = OkHttpClient()
        try {
            val api = createApi(server.url, http)
            val resolved = api.resolveStream(
                title = "Arjan Vailly",
                artist = "Bhupinder Babbal",
                expectedDurationSeconds = 180,
                expectedAlbum = "ANIMAL",
                preferredQuality = LosslessMusicApi.QUALITY_CD_LOSSLESS,
            )
            assertNull("Must not assume untagged ANIMAL matches when (Telugu) edition also exists", resolved)
        } finally {
            server.close()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun matchesTeluguTitleCandidateWhenLanguageIsSpecifiedInExpectedAlbum() = runBlocking {
        val searchJson = """
            {"tracks":[
                {"id":"301","title":"Ayudha Pooja","artist":"Kaala Bhairava","album":"Devara Part 1 (Hindi)","duration":180,"audioQuality":"LOSSLESS"},
                {"id":"302","title":"Ayudha Pooja (Telugu)","artist":"Kaala Bhairava","album":"Devara Part 1","duration":180,"audioQuality":"LOSSLESS"}
            ]}
        """.trimIndent()
        val server = startServer(searchJson)
        val http = OkHttpClient()
        try {
            val api = createApi(server.url, http)
            val resolved = api.resolveStream(
                title = "Ayudha Pooja",
                artist = "Kaala Bhairava",
                expectedDurationSeconds = 180,
                expectedAlbum = "Devara Part 1 (Telugu)",
                preferredQuality = LosslessMusicApi.QUALITY_CD_LOSSLESS,
            )
            assertNotNull(resolved)
            assertEquals(302L, resolved!!.trackId)
        } finally {
            server.close()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun matchesSpaceSeparatedAlbumLanguageWhenExpectedAlbumNamesLanguage() = runBlocking {
        val searchJson = """
            {"tracks":[
                {"id":"401","title":"Ayudha Pooja","artist":"Kaala Bhairava","album":"Devara Part 1 Hindi","duration":180,"audioQuality":"LOSSLESS"},
                {"id":"402","title":"Ayudha Pooja","artist":"Kaala Bhairava","album":"Devara Part 1 Telugu","duration":180,"audioQuality":"LOSSLESS"}
            ]}
        """.trimIndent()
        val server = startServer(searchJson)
        val http = OkHttpClient()
        try {
            val api = createApi(server.url, http)
            val resolved = api.resolveStream(
                title = "Ayudha Pooja",
                artist = "Kaala Bhairava",
                expectedDurationSeconds = 180,
                expectedAlbum = "Devara Part 1 (Telugu)",
                preferredQuality = LosslessMusicApi.QUALITY_CD_LOSSLESS,
            )
            assertNotNull(resolved)
            assertEquals(402L, resolved!!.trackId)
        } finally {
            server.close()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
