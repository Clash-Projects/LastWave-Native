package com.lastwave.app.data.lyrics

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/**
 * BetterLyrics answers 401 (not 404) for every query it has not cached; the body
 * says "Uncached queries require a valid API key via X-API-Key header". The
 * provider is called without a key, so a 401 is a definitive "this song is not
 * available", and every sibling endpoint would 401 too.
 */
class BetterLyricsApiNegativeCacheTest {

    private val calls = AtomicInteger()

    private fun client(code: Int, body: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                calls.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code in 200..299) "OK" else "Unauthorized")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

    @Test
    fun portato401StopsFurtherRequestsForTheSameSong() = runTest {
        val api = BetterLyricsApi(client(401, """{"error":"API key required"}"""))

        assertThat(api.fetchPortatoLyrics("Zaalima", "Arijit Singh")).isNull()
        assertThat(calls.get()).isEqualTo(1)

        // Second attempt for the same track must not hit the network again.
        assertThat(api.fetchPortatoLyrics("Zaalima", "Arijit Singh")).isNull()
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun wordLyrics401SkipsSiblingEndpointsAndTitleVariants() = runTest {
        val api = BetterLyricsApi(client(401, """{"error":"API key required"}"""))

        // The negative-cache key is the cleaned title, so the "(Official Video)"
        // variant maps to the same key: one endpoint 401s, the other two
        // endpoints and the cleaned-title retry are all skipped.
        assertThat(api.fetchWordLyrics("Song Name (Official Video)", "Some Artist")).isNull()
        assertThat(calls.get()).isEqualTo(1)

        assertThat(api.fetchWordLyrics("Song Name (Official Video)", "Some Artist")).isNull()
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun successfulResponseIsParsedAndNotCachedAsUnavailable() = runTest {
        val api = BetterLyricsApi(client(200, """{"lyrics":"[00:01.00]first line"}"""))

        val lines = api.fetchPortatoLyrics("Zaalima", "Arijit Singh")
        assertThat(lines).isNotNull()
        assertThat(lines!!.map { it.text }).containsExactly("first line")
        assertThat(calls.get()).isEqualTo(1)
    }
}
