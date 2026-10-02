package com.lastwave.app.playback

/**
 * Which duration a track's seek bar and crossfade timer are allowed to use.
 *
 * ExoPlayer reports `C.TIME_UNSET` until the incoming container is parsed, and
 * that window is ordinary for streaming sources (YouTube WebM/MP4, provider
 * module FLAC). The published duration therefore has to survive re-reads while
 * the *same* item keeps buffering, or the seek bar loses its denominator and
 * freezes at 0:00 until the next event.
 *
 * Reusing the published value across a track change is a different thing
 * entirely, and it was the bug. The previous value was always accepted, so a
 * track whose duration was not yet known inherited the outgoing track's length:
 *
 *  - the seek bar clamped its position to that wrong ceiling, so it pinned at
 *    the old track's length instead of the real one;
 *  - worse, the crossfade timer computed `remaining = wrongDuration - position`,
 *    which crosses its threshold seconds into a full-length track. The hand-off
 *    then swapped the *next* item in and abandoned the rest of the current one.
 *    With a short track ahead of it in the queue that lands at roughly 3-4
 *    seconds, which is what users reported as "the song skipped after a few
 *    seconds".
 *
 * So a published duration is reusable only while it still describes the item
 * that produced it. Callers track that ownership; this policy just applies it.
 *
 * Pure by design so the whole rule is unit-testable without Android (same
 * approach as [planCrossfade] in CrossfadeSchedule.kt).
 */

/** The inputs one duration decision is made from. */
data class DurationRequest(
    /**
     * ExoPlayer's own duration for the item it currently holds. `<= 0` covers
     * both `C.TIME_UNSET` and an unparsed container, which is the case this
     * whole policy exists for.
     */
    val playerMs: Long,
    /**
     * A duration known for *this* item specifically: a catalog seed, a
     * resolve-time duration, or an entry in `knownDurations` keyed by this
     * item's media id. Safe to trust regardless of what was published before.
     */
    val knownForCurrentItemMs: Long,
    /** The duration currently published to the UI. */
    val publishedMs: Long,
    /**
     * Whether [publishedMs] was derived from the item now playing, as opposed
     * to whatever was playing when it was published. False across a track
     * change, which is exactly when reusing it would be wrong.
     */
    val publishedBelongsToCurrentItem: Boolean,
)

/**
 * Picks the duration to publish, or `0` when nothing trustworthy is known.
 *
 * Returning `0` is a real answer, not a fallback to paper over: the caller
 * shows no denominator until a trustworthy value arrives, which is far better
 * than showing the previous track's length.
 */
fun selectDuration(request: DurationRequest): Long = with(request) {
    if (playerMs > 0L) return playerMs
    if (knownForCurrentItemMs > 0L) return knownForCurrentItemMs
    if (publishedMs > 0L && publishedBelongsToCurrentItem) return publishedMs
    return 0L
}