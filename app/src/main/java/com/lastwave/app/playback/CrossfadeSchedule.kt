package com.lastwave.app.playback

/**
 * Scheduling model for the two-player crossfade hand-off.
 *
 * The hand-off needs the standby ExoPlayer at [CrossfadeDecision.HandOff] while
 * it is already `STATE_READY`. Arming it only inside the fade window made that
 * impossible for provider modules / addons: their signed FLAC URLs are minted
 * with a short TTL, so a resolve started at track start reads expired by the
 * time the window opened, and the re-resolve then had to resolve, re-prepare and
 * buffer a whole file inside the remaining seconds. The result was a silent hard
 * cut. YouTube/Opus hid the bug because its URLs stay valid for hours.
 *
 * The split that fixes it: the standby is armed (and therefore buffers) as soon
 * as the next item resolves, anywhere in the track, while the signed-URL refresh
 * is scheduled ahead of the window via [CrossfadePlanInput.refreshLeadMs].
 * Arming and hand-off are deliberately separate decisions.
 *
 * Pure by design so the whole policy is unit-testable without Android
 * (same approach as `evaluateSignalPath` in SignalPath.kt).
 */

/** Why no standby work is warranted on this tick. */
enum class CrossfadeIdleReason {
    /** Crossfade is off, or bit-perfect mode owns the single-player pipeline. */
    Disabled,
    /** Bit-Perfect bypass forbids a second player. */
    BitPerfect,
    /** Paused: nothing to overlap into, and a pause must park an armed standby. */
    Paused,
    /** Repeat-one never advances, so there is no next track to overlap. */
    RepeatOne,
    /** No duration yet (TIME_UNSET containers); the fade window cannot be placed. */
    UnknownDuration,
    /** End of queue, or the next index is the current one. */
    NoNextItem,
    /** The position has already reached the duration. */
    Ended,
    /** The next item still needs resolving, but the refresh window has not opened. */
    TooEarly,
}

/** Why the next track's stream has to be (re)requested. */
enum class CrossfadeResolveReason {
    /** The queued item is still a `lastwave://` placeholder. */
    Placeholder,
    /** Resolved, but the signed URL is dead or inside the expiry safety margin. */
    ExpiredSignature,
}

/** What the crossfade ticker should do on this tick. */
sealed interface CrossfadeDecision {
    /** Do nothing this tick. */
    data class Idle(val reason: CrossfadeIdleReason) : CrossfadeDecision

    /** Start (or keep) the background resolve for the next queue item. */
    data class ResolveNext(val reason: CrossfadeResolveReason) : CrossfadeDecision

    /** (Re)install the queue on the standby player and let it prepare. */
    data object Arm : CrossfadeDecision

    /** Armed, but not yet `STATE_READY`. */
    data object WaitingForReady : CrossfadeDecision

    /** Armed and ready; the fade window has not opened yet. */
    data object WaitingForWindow : CrossfadeDecision

    /** Begin the blend now, over [overlapMs]. */
    data class HandOff(val overlapMs: Long) : CrossfadeDecision
}

/** One snapshot of the crossfade inputs, taken on the main thread. */
data class CrossfadePlanInput(
    val crossfadeEnabled: Boolean,
    val bitPerfectEnabled: Boolean,
    val isPlaying: Boolean,
    val repeatOne: Boolean,
    /** Best-known duration of the outgoing track; <= 0 means still unknown. */
    val durationMs: Long,
    val positionMs: Long,
    /** User-configured crossfade length. */
    val crossfadeMs: Long,
    val playbackSpeed: Float,
    /** A next queue item exists and is not the current one. */
    val hasNextItem: Boolean,
    /** The next item already carries a resolved (non-placeholder) stream. */
    val nextItemResolved: Boolean,
    /** The resolved next stream's signed URL is dead or inside the margin. */
    val streamExpired: Boolean,
    /** The standby already holds exactly this queue at this index. */
    val standbyArmedForNext: Boolean,
    /** Standby is `STATE_READY`. */
    val standbyReady: Boolean,
    /** Standby's own duration; <= 0 while it is still preparing. */
    val standbyDurationMs: Long,
    /**
     * Standby's actually-buffered media time. This, not the user's setting, is
     * how much runway the incoming track has before it must wait on the network
     * again, so it bounds the overlap.
     */
    val standbyBufferedMs: Long,
    /** How long before the window the standby is armed (buffering lead). */
    val armLeadMs: Long,
    /** Extra lead so a short-TTL signed URL is refreshed off the critical path. */
    val refreshLeadMs: Long,
) {
    /** Never longer than a third of the track, so short tracks still have a lead-in. */
    val fadeMs: Long get() = minOf((crossfadeMs * playbackSpeed).toLong(), durationMs / 3)

    val remainingMs: Long get() = durationMs - positionMs

    /** Resolve (or re-resolve) this long before the end, well outside the window. */
    val refreshWindowMs: Long get() = fadeMs + armLeadMs + refreshLeadMs
}

/**
 * Decides the single crossfade action for this tick. Exhaustive and side-effect
 * free; every early return names its reason so the caller can log it.
 */
fun planCrossfade(input: CrossfadePlanInput): CrossfadeDecision = with(input) {
    if (!crossfadeEnabled) return CrossfadeDecision.Idle(CrossfadeIdleReason.Disabled)
    if (bitPerfectEnabled) return CrossfadeDecision.Idle(CrossfadeIdleReason.BitPerfect)
    if (!isPlaying) return CrossfadeDecision.Idle(CrossfadeIdleReason.Paused)
    if (repeatOne) return CrossfadeDecision.Idle(CrossfadeIdleReason.RepeatOne)
    if (durationMs <= 0L || crossfadeMs <= 0L) {
        return CrossfadeDecision.Idle(CrossfadeIdleReason.UnknownDuration)
    }
    if (!hasNextItem) return CrossfadeDecision.Idle(CrossfadeIdleReason.NoNextItem)
    if (fadeMs <= 0L) return CrossfadeDecision.Idle(CrossfadeIdleReason.UnknownDuration)
    if (remainingMs <= 0L) return CrossfadeDecision.Idle(CrossfadeIdleReason.Ended)

    val resolveReason = when {
        !nextItemResolved -> CrossfadeResolveReason.Placeholder
        streamExpired -> CrossfadeResolveReason.ExpiredSignature
        else -> null
    }
    if (resolveReason != null) {
        // Nothing can be armed against an unresolved or dead stream, so the
        // request has to land first. Outside the refresh window there is still
        // ample time, and re-requesting early would only churn the module.
        if (remainingMs > refreshWindowMs) {
            return CrossfadeDecision.Idle(CrossfadeIdleReason.TooEarly)
        }
        return CrossfadeDecision.ResolveNext(resolveReason)
    }

    // Resolved and alive: arm as early as possible so the standby spends the
    // whole track buffering instead of racing the fade window.
    if (!standbyArmedForNext) return CrossfadeDecision.Arm
    if (!standbyReady) return CrossfadeDecision.WaitingForReady
    if (remainingMs > fadeMs) return CrossfadeDecision.WaitingForWindow

    // The user's setting is a ceiling, never a promise. The blend runs off the
    // incoming player's position and aborts to a hard cut if that player
    // stalls (MusicPlayer.updateCrossfade), so an overlap longer than the
    // standby has buffered is a fade guaranteed to break partway through.
    // Bounding by the buffer turns "12s that dies at second 4" into "4s that
    // completes" on a slow link, while a well-buffered standby still gets the
    // full 12s.
    //
    // standbyBufferedMs cannot be 0 here: LoadControl only reports READY once
    // bufferForPlaybackMs (1.5s) is buffered, so the floor below is defensive
    // rather than reachable, and never produces a degenerate ~0ms fade.
    val sustainableMs = standbyBufferedMs.takeIf { it > 0L } ?: fadeMs
    val overlapMs = minOf(
        fadeMs,
        remainingMs,
        standbyDurationMs.takeIf { it > 0L }?.div(3) ?: fadeMs,
        sustainableMs,
    ).coerceAtLeast(1L)
    return CrossfadeDecision.HandOff(overlapMs)
}