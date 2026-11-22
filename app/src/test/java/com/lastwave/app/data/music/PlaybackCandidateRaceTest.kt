package com.lastwave.app.data.music

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class PlaybackCandidateRaceTest {
    @Test fun fallbackDoesNotWaitForSlowDirectClients() = runTest {
        var cancelled = false
        val result = racePlaybackCandidates(
            first = { try { awaitCancellation() } finally { cancelled = true } },
            second = { "validated-newpipe" },
        )
        assertEquals("validated-newpipe", result)
        testScheduler.runCurrent()
        assertTrue(cancelled)
    }

    @Test fun winnerDoesNotWaitForLoserThatIgnoresCancellation() = runTest {
        val result = racePlaybackCandidates(
            first = { delay(600); "direct" },
            second = { withContext(NonCancellable) { delay(2_000) }; null },
        )
        assertEquals("direct", result)
        assertEquals(600L, testScheduler.currentTime)
    }

    @Test fun authenticatedDirectStreamCanStillWin() = runTest {
        assertEquals("authenticated", racePlaybackCandidates(
            first = { "authenticated" }, second = { awaitCancellation() },
        ))
    }

    @Test fun emptyDirectResultWaitsForFallback() = runTest {
        val gate = CompletableDeferred<String?>()
        val result = async { racePlaybackCandidates(first = { null }, second = { gate.await() }) }
        testScheduler.runCurrent()
        assertFalse(result.isCompleted)
        gate.complete("fallback")
        assertEquals("fallback", result.await())
    }

    @Test fun emptyFallbackWaitsForDirect() = runTest {
        val gate = CompletableDeferred<String?>()
        val result = async { racePlaybackCandidates(first = { gate.await() }, second = { null }) }
        testScheduler.runCurrent()
        assertFalse(result.isCompleted)
        gate.complete("direct")
        assertEquals("direct", result.await())
    }

    @Test fun bothMissesReturnNull() = runTest {
        assertNull(racePlaybackCandidates<String>(first = { null }, second = { null }))
    }

    /** Startup shape: race(youtube, race(local, provider-then-probe)). */
    private suspend fun startupRace(
        youtube: suspend () -> String?,
        provider: suspend () -> String?,
        probeOk: suspend (String) -> Boolean,
    ) = racePlaybackCandidates(
        first = youtube,
        second = {
            racePlaybackCandidates(
                first = { null },
                second = { provider()?.takeIf { probeOk(it) } },
            )
        },
    )

    @Test fun providerUrlThatFailsOnOpenDoesNotDelayFallback() = runTest {
        val result = startupRace(
            youtube = { delay(900); "youtube" },
            provider = { delay(300); "provider-502" },
            probeOk = { delay(200); false },
        )
        assertEquals("youtube", result)
        // Audio source is ready exactly when the fallback is: no serial wait.
        assertEquals(900L, testScheduler.currentTime)
    }

    @Test fun playableProviderBeatsSlowerFallback() = runTest {
        var youtubeCancelled = false
        val result = startupRace(
            youtube = { try { delay(2_000); "youtube" } finally { youtubeCancelled = true } },
            provider = { delay(300); "provider-flac" },
            probeOk = { delay(100); true },
        )
        assertEquals("provider-flac", result)
        assertEquals(400L, testScheduler.currentTime)
        testScheduler.runCurrent()
        assertTrue(youtubeCancelled)
    }

    @Test fun fastFallbackDoesNotWaitForProviderMetadata() = runTest {
        val result = startupRace(
            youtube = { delay(500); "youtube" },
            provider = { delay(8_000); "provider-flac" },
            probeOk = { true },
        )
        assertEquals("youtube", result)
        assertEquals(500L, testScheduler.currentTime)
    }

    @Test fun failedFallbackStillGetsWorkingProvider() = runTest {
        val result = startupRace(
            youtube = { delay(100); null },
            provider = { delay(1_200); "provider-flac" },
            probeOk = { true },
        )
        assertEquals("provider-flac", result)
        assertEquals(1_200L, testScheduler.currentTime)
    }
}
