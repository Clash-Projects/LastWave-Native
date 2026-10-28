package com.lastwave.app.data.music

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
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
        assertTrue(cancelled)
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
}
