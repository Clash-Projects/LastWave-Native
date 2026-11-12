package com.lastwave.app.data.lossless

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Put in the context of a lossless resolve to learn how far it got: set once
 * an addon has found the song and moved on to fetching its stream. "Best
 * quality first" reads it to decide whether a late addon is worth a longer
 * wait (still fetching) or not (still searching).
 */
internal class AddonTrackMatched : AbstractCoroutineContextElement(Key) {
    @Volatile
    var matched = false

    companion object Key : CoroutineContext.Key<AddonTrackMatched>
}
