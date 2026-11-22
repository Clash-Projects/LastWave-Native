package com.lastwave.app.data.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.selects.select

/**
 * An empty result is a miss; only a playable candidate wins and cancels the other path.
 * The loser is cancelled but not awaited: a blocking extractor that ignores
 * cancellation held a finished winner back until its own request returned.
 */
internal suspend fun <T> racePlaybackCandidates(
    first: suspend () -> T?,
    second: suspend () -> T?,
): T? {
    val candidates = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
    val a = candidates.async { first() }
    val b = candidates.async { second() }
    return try {
        select {
            a.onAwait { it ?: b.await() }
            b.onAwait { it ?: a.await() }
        }
    } finally {
        candidates.cancel()
    }
}
