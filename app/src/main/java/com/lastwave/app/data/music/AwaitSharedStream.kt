package com.lastwave.app.data.music

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

internal suspend fun <T> Deferred<T>.awaitSharedStream(timeoutMs: Long, videoId: String): T = try {
    withTimeout(timeoutMs) { await() }
} catch (cancelled: CancellationException) {
    // A shared request can expire or be invalidated while its download waiter is still active.
    currentCoroutineContext().ensureActive()
    val reason = if (cancelled is TimeoutCancellationException) {
        "Timed out resolving audio stream"
    } else {
        "Audio stream resolution interrupted"
    }
    throw IOException("$reason for $videoId", cancelled)
}
