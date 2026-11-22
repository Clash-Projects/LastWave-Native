package com.lastwave.app.data.playlist

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.spotify.SpotifyAuthRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList

class SpotifyUserPlaylistsTest {
    private val authRepository = mockk<SpotifyAuthRepository>()
    private val innerTube = mockk<InnerTubeMusicApi>(relaxed = true)
    private val requestedUrls = CopyOnWriteArrayList<String>()
    private var responder: (String) -> Pair<Int, String> = { 404 to "" }

    private val client = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val fullUrl = request.url.toString()
            requestedUrls += fullUrl
            val (code, body) = responder(fullUrl)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Error")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        })
        .build()

    private lateinit var importer: SpotifyPlaylistImporter

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.encode(any()) } answers {
            URLEncoder.encode(firstArg<String>(), "UTF-8").replace("+", "%20")
        }
        coEvery { authRepository.getPersonalToken() } returns "personal-token"
        coEvery { authRepository.getClientToken() } returns "client-token"
        importer = SpotifyPlaylistImporter(client, innerTube, authRepository)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun parseUserPlaylistsPreservesMissingCountsAsNullInsteadOfZero() {
        val body = """
            {
              "data": {
                "me": {
                  "libraryV3": {
                    "items": [
                      {
                        "item": {
                          "__typename": "PlaylistResponseWrapper",
                          "data": {
                            "uri": "spotify:playlist:pl_no_count",
                            "name": "No Summary Count"
                          }
                        }
                      },
                      {
                        "item": {
                          "__typename": "PlaylistResponseWrapper",
                          "data": {
                            "uri": "spotify:playlist:pl_empty",
                            "name": "Empty Explicitly",
                            "content": { "totalCount": 0 }
                          }
                        }
                      },
                      {
                        "item": {
                          "__typename": "PlaylistResponseWrapper",
                          "data": {
                            "uri": "spotify:playlist:pl_with_count",
                            "name": "With Summary Count",
                            "tracks": { "total": 12 }
                          }
                        }
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val parsed = importer.parseUserPlaylists(body)

        assertThat(parsed.map { it.id to it.trackCount }).containsExactly(
            "pl_no_count" to null,
            "pl_empty" to 0,
            "pl_with_count" to 12,
        ).inOrder()
    }

    @Test
    fun fetchUserPlaylistsResolvesMissingTrackCountsWithoutMaskingFailuresAsZero() = runBlocking {
        responder = { url ->
            when {
                url.contains("operationName=libraryV3") -> 200 to """
                    {
                      "data": {
                        "me": {
                          "libraryV3": {
                            "items": [
                              {
                                "item": {
                                  "__typename": "PlaylistResponseWrapper",
                                  "data": { "uri": "spotify:playlist:pl_songs", "name": "Has Songs" }
                                }
                              },
                              {
                                "item": {
                                  "__typename": "PlaylistResponseWrapper",
                                  "data": { "uri": "spotify:playlist:pl_zero", "name": "Empty" }
                                }
                              },
                              {
                                "item": {
                                  "__typename": "PlaylistResponseWrapper",
                                  "data": { "uri": "spotify:playlist:pl_error", "name": "Unresolved" }
                                }
                              },
                              {
                                "item": {
                                  "__typename": "PlaylistResponseWrapper",
                                  "data": {
                                    "uri": "spotify:playlist:pl_cached",
                                    "name": "Already Known",
                                    "content": { "totalCount": 7 }
                                  }
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                """.trimIndent()
                url.contains("operationName=fetchPlaylist") && url.contains("pl_songs") ->
                    200 to """{"data":{"playlistV2":{"content":{"totalCount":24}}}}"""
                url.contains("operationName=fetchPlaylist") && url.contains("pl_zero") ->
                    200 to """{"data":{"playlistV2":{"content":{"totalCount":0}}}}"""
                else -> 404 to """{"error":"not found"}"""
            }
        }

        val playlists = importer.fetchUserPlaylists()

        assertThat(playlists.map { it.id to it.trackCount }).containsExactly(
            "pl_songs" to 24,
            "pl_zero" to 0,
            "pl_error" to null,
            "pl_cached" to 7,
        ).inOrder()
        val fetchPlaylistRequests = requestedUrls.filter { it.contains("operationName=fetchPlaylist") }
        assertThat(fetchPlaylistRequests).hasSize(3)
        assertThat(fetchPlaylistRequests.none { it.contains("pl_cached") }).isTrue()
        assertThat(fetchPlaylistRequests.all { it.contains("%22limit%22%3A1") }).isTrue()
    }

    @Test
    fun fetchLikedSongsCountReadsTotalCountFromMinimalLibraryTracksQuery() = runBlocking {
        responder = { url ->
            if (url.contains("operationName=fetchLibraryTracks")) {
                200 to """{"data":{"me":{"library":{"tracks":{"totalCount":137}}}}}"""
            } else {
                404 to ""
            }
        }

        val count = importer.fetchLikedSongsCount()

        assertThat(count).isEqualTo(137)
        val req = requestedUrls.single()
        assertThat(req).contains("operationName=fetchLibraryTracks")
        assertThat(req).contains("%22limit%22%3A1")
    }
}
