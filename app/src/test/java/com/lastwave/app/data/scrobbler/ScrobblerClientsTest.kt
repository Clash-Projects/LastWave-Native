package com.lastwave.app.data.scrobbler

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.network.LastFmSigner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class ScrobblerClientsTest {
    private val prefs = mockk<ExtraScrobblerPreferences> {
        coEvery { save(any(), any()) } just Runs
    }

    private fun http(code: Int, body: String, capture: (Request) -> Unit = {}) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            capture(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("test").body(body.toResponseBody()).build()
        }.build()

    @Test fun listenBrainzValidatesTokenWithHeaderAndPersistsReturnedAccount() = runBlocking {
        var request: Request? = null
        val client = ListenBrainzClient(http(200, """{"valid":true,"user_name":"listener"}""") { request = it }, prefs)
        assertThat(client.connect(" token ", "")).isNull()
        assertThat(request?.url.toString()).isEqualTo("https://api.listenbrainz.org/1/validate-token")
        assertThat(request?.header("Authorization")).isEqualTo("Token token")
        assertThat(request?.url?.queryParameter("token")).isNull()
        assertThat(request?.header("User-Agent")).contains("LastWave/")
        coVerify { prefs.save(ExtraService.LISTENBRAINZ, ScrobblerAccount("token", "listener", "")) }
    }

    @Test fun listenBrainzDoesNotSaveTokenFromFailedHttpResponse() = runBlocking {
        val client = ListenBrainzClient(http(503, """{"valid":true,"user_name":"listener"}"""), prefs)
        assertThat(client.connect("token", "")).isNotNull()
        coVerify(exactly = 0) { prefs.save(any(), any()) }
    }

    @Test fun listenBrainzSubmitsUtcTimestampOnlyForCompletedListen() = runBlocking {
        val listen = Listen("Artist", "Song", "Album", 1_800_000_000)
        for (completed in listOf(false, true)) {
            var request: Request? = null
            val client = ListenBrainzClient(http(200, """{"status":"ok"}""") { request = it }, prefs)
            val account = ScrobblerAccount("token", "listener")
            assertThat(if (completed) client.submit(listen, account) else client.nowPlaying(listen, account)).isTrue()
            val buffer = Buffer()
            request?.body?.writeTo(buffer)
            val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val payload = body.getValue("payload").jsonArray.single().jsonObject
            assertThat(body.getValue("listen_type").jsonPrimitive.content).isEqualTo(if (completed) "single" else "playing_now")
            assertThat(payload.containsKey("listened_at")).isEqualTo(completed)
            if (completed) assertThat(payload.getValue("listened_at").jsonPrimitive.content).isEqualTo("1800000000")
            assertThat(payload.getValue("track_metadata").jsonObject.getValue("release_name").jsonPrimitive.content).isEqualTo("Album")
        }
    }

    @Test fun listenBrainzConsumesInvalidPayloadButRetriesTemporaryFailures() = runBlocking {
        val listen = Listen("Artist", "Song", startedAtSec = 1_800_000_000)
        val account = ScrobblerAccount("token", "listener")
        assertThat(ListenBrainzClient(http(400, "{}"), prefs).submit(listen, account)).isTrue()
        for (code in listOf(401, 429, 503)) {
            assertThat(ListenBrainzClient(http(code, "{}"), prefs).submit(listen, account)).isFalse()
        }
    }

    @Test fun libreFmUsesMobileAuthHashAndStoresOnlySession() = runBlocking {
        var request: Request? = null
        val client = LibreFmClient(http(200, """{"session":{"name":"Mixed","key":"session"}}""") { request = it }, prefs)
        assertThat(client.connect(" Mixed ", "password", "")).isNull()
        val form = request?.body as FormBody
        val params = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertThat(request?.url.toString()).isEqualTo("https://libre.fm/2.0/")
        assertThat(params["method"]).isEqualTo("auth.getMobileSession")
        assertThat(params["username"]).isEqualTo("Mixed")
        assertThat(params["authToken"]).isEqualTo(LastFmSigner.md5("mixed5f4dcc3b5aa765d61d8327deb882cf99"))
        assertThat(params).doesNotContainKey("password")
        assertThat(params["api_sig"]?.length).isEqualTo(32)
        coVerify { prefs.save(ExtraService.LIBREFM, ScrobblerAccount("session", "Mixed", "")) }
    }

    @Test fun libreFmConsumesDuplicateAndPermanentRejectionButRetriesServerFailure() = runBlocking {
        val listen = Listen("Artist", "Song", startedAtSec = 1_800_000_000)
        val account = ScrobblerAccount("session", "listener")
        for (code in listOf(0, 1, 2, 3, 4, 91)) {
            val body = """{"scrobbles":{"scrobble":{"ignoredMessage":{"code":"$code"}}}}"""
            assertThat(LibreFmClient(http(200, body), prefs).submit(listen, account)).isTrue()
        }
        assertThat(LibreFmClient(http(503, "{}"), prefs).submit(listen, account)).isFalse()
        assertThat(LibreFmClient(http(200, """{"error":9,"message":"invalid session"}"""), prefs).submit(listen, account)).isFalse()
    }

    @Test fun malformedServerReturnsErrorWithoutSendingOrSavingCredentials() = runBlocking {
        val client = ListenBrainzClient(http(200, "{}") { error("Must not send") }, prefs)
        assertThat(client.connect("token", "https://host/?token=secret")).isNotNull()
        coVerify(exactly = 0) { prefs.save(any(), any()) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun cancellingConnectionCancelsOkHttpCall() = runTest {
        val call = mockk<Call> {
            every { enqueue(any()) } just Runs
            every { cancel() } just Runs
        }
        val http = mockk<OkHttpClient> { every { newCall(any()) } returns call }
        val client = ListenBrainzClient(http, prefs)
        val job = launch { client.connect("token", "") }
        runCurrent()
        job.cancelAndJoin()
        verify(exactly = 1) { call.cancel() }
        coVerify(exactly = 0) { prefs.save(any(), any()) }
    }
}
