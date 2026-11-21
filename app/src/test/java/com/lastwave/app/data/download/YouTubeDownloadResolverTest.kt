package com.lastwave.app.data.download

import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeAudioStream
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class YouTubeDownloadResolverTest {
    private val api = mockk<InnerTubeMusicApi>()
    private val opus = stream("audio/webm", "opus", 251)
    private val aac = stream("audio/mp4", "mp4a.40.2", 140)
    private val hls = stream("application/x-mpegURL", "hls", null)

    init {
        every { api.peekCachedStream("song") } returns null
    }

    @Test fun adaptiveOpusIsDownloadableWithoutAnotherExtraction() = runTest {
        coEvery { api.resolveAudioStream("song") } returns opus
        assertSame(opus, api.resolveDownloadCandidate("song", emptySet()) { 1_000 })
        coVerify(exactly = 0) { api.resolveDownloadStream(any()) }
    }

    @Test fun adaptiveAacSurvivesFailedOpusExtraction() = runTest {
        coEvery { api.resolveAudioStream("song") } returns aac
        coEvery { api.resolveDownloadStream("song") } throws IOException("NewPipe unavailable")
        assertSame(aac, api.resolveDownloadCandidate("song", emptySet()) { 1_000 })
    }

    @Test fun rejectedOpusDoesNotExcludeAacFromTheSameClient() = runTest {
        coEvery { api.resolveAudioStream("song") } returns aac
        coEvery { api.resolveDownloadStream("song") } returns opus.copy(url = "https://media.googlevideo.com/renewed")
        assertSame(aac, api.resolveDownloadCandidate("song", setOf(opus.downloadRenditionKey())) { 1_000 })
    }

    @Test fun cachedRejectedStreamDoesNotHideFreshFallback() = runTest {
        every { api.peekCachedStream("song") } returns opus
        coEvery { api.resolveAudioStream("song") } returns aac
        coEvery { api.resolveDownloadStream("song") } returns opus
        assertSame(aac, api.resolveDownloadCandidate("song", setOf(opus.downloadRenditionKey())) { 1_000 })
        coVerify(exactly = 1) { api.resolveAudioStream("song") }
    }

    @Test fun renewedUrlIsAllowedWhenNoAlternativeRenditionExists() = runTest {
        val renewed = opus.copy(url = "https://media.googlevideo.com/renewed")
        every { api.peekCachedStream("song") } returns opus
        coEvery { api.resolveAudioStream("song") } returns renewed
        coEvery { api.resolveDownloadStream("song") } returns renewed
        assertSame(renewed, api.resolveDownloadCandidate("song", setOf(opus.downloadRenditionKey())) { 1_000 })
        coVerify(exactly = 1) { api.resolveAudioStream("song") }
    }

    @Test fun manifestIsRejectedByMimePathOrCodec() {
        assertTrue(hls.isHlsPlaylist())
        assertTrue(aac.copy(url = "https://example.test/audio.M3U8?sig=x").isHlsPlaylist())
        assertTrue(aac.copy(codec = "HLS").isHlsPlaylist())
        assertFalse(aac.isHlsPlaylist())
        assertFalse(opus.isHlsPlaylist())
    }

    @Test fun hlsOnlyResultHasAnExplicitDownloadFailure() = runTest {
        every { api.peekCachedStream("song") } returns hls
        coEvery { api.resolveAudioStream("song") } returns hls
        coEvery { api.resolveDownloadStream("song") } returns hls
        try {
            api.resolveDownloadCandidate("song", emptySet()) { 1_000 }
            fail("An HLS playlist must not be saved as an audio file")
        } catch (error: IOException) {
            assertEquals("YouTube returned no downloadable audio file", error.message)
        }
    }

    @Test fun optionalOpusTimeoutKeepsWorkingAac() = runTest {
        coEvery { api.resolveAudioStream("song") } returns aac
        coEvery { api.resolveDownloadStream("song") } coAnswers { awaitCancellation() }
        assertSame(aac, api.resolveDownloadCandidate("song", emptySet()) { 100 })
        assertEquals(100L, testScheduler.currentTime)
    }

    @Test fun resolverTimeoutIsReportedInsteadOfNoPlayableStream() = runTest {
        coEvery { api.resolveAudioStream("song") } coAnswers { awaitCancellation() }
        coEvery { api.resolveDownloadStream("song") } coAnswers { awaitCancellation() }
        try {
            api.resolveDownloadCandidate("song", emptySet()) { 100 - testScheduler.currentTime }
            fail("Resolution should time out")
        } catch (error: IOException) {
            assertEquals("YouTube stream resolution timed out", error.message)
        }
        assertEquals(100L, testScheduler.currentTime)
    }

    @Test fun callerCancellationDoesNotStartFallback() = runTest {
        val started = CompletableDeferred<Unit>()
        coEvery { api.resolveAudioStream("song") } coAnswers {
            started.complete(Unit)
            awaitCancellation()
        }
        val job = launch { api.resolveDownloadCandidate("song", emptySet()) { 1_000 } }
        started.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        coVerify(exactly = 0) { api.resolveDownloadStream(any()) }
    }

    private fun stream(mime: String, codec: String, itag: Int?) = YouTubeAudioStream(
        videoId = "song", url = "https://media.googlevideo.com/audio", itag = itag,
        mimeType = mime, codec = codec, bitrate = 128_000, sampleRateHz = 48_000,
        durationMs = 180_000, contentLength = 3_000_000, isAdaptive = true,
        clientProfile = "NEWPIPE", authScope = "anonymous",
    )
}
