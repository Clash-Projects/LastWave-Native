package com.lastwave.app.data.music

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AwaitSharedStreamTest {
    @Test fun returnsSuccessfulSharedResult() = runTest {
        val request = CompletableDeferred("resolved-url")
        assertEquals("resolved-url", request.awaitSharedStream(40_000, "song"))
    }

    @Test fun preservesOrdinaryResolutionFailure() = runTest {
        val error = IOException("No usable client")
        val request = CompletableDeferred<String>().apply { completeExceptionally(error) }
        val failure = runCatching { request.awaitSharedStream(40_000, "song") }.exceptionOrNull()
        assertSame(error, failure)
    }

    @Test fun sharedInvalidationBecomesFailureWithoutCancellingWaiter() = runTest {
        val request = CompletableDeferred<String>()
        request.cancel(CancellationException("cache invalidated"))
        val failure = runCatching { request.awaitSharedStream(40_000, "song") }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure?.cause is CancellationException)
        assertEquals("Audio stream resolution interrupted for song", failure?.message)
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun sharedRequestTimeoutBecomesFailureBeforeWaiterDeadline() = runTest {
        val request = backgroundScope.async<String> {
            withTimeout(100) { awaitCancellation() }
        }
        val failure = runCatching { request.awaitSharedStream(40_000, "song") }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure?.cause is TimeoutCancellationException)
        assertEquals("Timed out resolving audio stream for song", failure?.message)
        assertEquals(100L, testScheduler.currentTime)
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun waiterTimeoutLeavesSharedRequestAvailableForAnotherWaiter() = runTest {
        val request = CompletableDeferred<String>()
        val failure = runCatching { request.awaitSharedStream(100, "song") }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure?.cause is TimeoutCancellationException)
        assertEquals(100L, testScheduler.currentTime)
        assertTrue(request.isActive)
        request.complete("late-url")
        assertEquals("late-url", request.awaitSharedStream(40_000, "song"))
    }

    @Test fun userCancellationStaysCancellationAndDoesNotCancelAnotherWaiter() = runTest {
        val request = CompletableDeferred<String>()
        var failure: Throwable? = null
        val cancelledWaiter = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                request.awaitSharedStream(40_000, "song")
            } catch (error: Throwable) {
                failure = error
                throw error
            }
        }
        val remainingWaiter = async { request.awaitSharedStream(40_000, "song") }
        cancelledWaiter.cancel(CancellationException("user cancelled"))
        cancelledWaiter.join()
        assertTrue(cancelledWaiter.isCancelled)
        assertTrue(failure is CancellationException)
        assertFalse(failure is IOException)
        assertTrue(request.isActive)
        request.complete("resolved-url")
        assertEquals("resolved-url", remainingWaiter.await())
    }
}
