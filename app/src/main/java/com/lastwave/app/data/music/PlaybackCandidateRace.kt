package com.lastwave.app.data.music

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/** An empty result is a miss; only a playable candidate wins and cancels the other path. */
internal suspend fun <T> racePlaybackCandidates(
    first: suspend () -> T?,
    second: suspend () -> T?,
): T? = coroutineScope {
    val a = async { first() }
    val b = async { second() }
    try {
        select {
            a.onAwait { it ?: b.await() }
            b.onAwait { it ?: a.await() }
        }
    } finally {
        a.cancel()
        b.cancel()
    }
}
