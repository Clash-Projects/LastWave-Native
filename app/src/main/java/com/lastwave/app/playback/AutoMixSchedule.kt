package com.lastwave.app.playback

/**
 * Scheduling model for the Mixxx-style AutoDJ Automix dual-deck hand-off.
 *
 * Automix replaces static-timer crossfades with musical phrase-based transitions
 * (8 or 16 bars) dynamically computed from track BPM, kick-band onset sync,
 * 160Hz Linkwitz-Riley bass-swap, and a 1.2s echo fadeout tail.
 */

enum class AutoMixIdleReason {
    Disabled,
    BitPerfect,
    Paused,
    RepeatOne,
    UnknownDuration,
    NoNextItem,
    Ended,
    TooEarly,
}

enum class AutoMixResolveReason {
    Placeholder,
    ExpiredSignature,
}

sealed interface AutoMixDecision {
    data class Idle(val reason: AutoMixIdleReason) : AutoMixDecision
    data class ResolveNext(val reason: AutoMixResolveReason) : AutoMixDecision
    data object Arm : AutoMixDecision
    data object WaitingForReady : AutoMixDecision
    data object WaitingForWindow : AutoMixDecision
    data class HandOff(val overlapMs: Long, val bars: Int) : AutoMixDecision
}

data class AutoMixPlanInput(
    val automixEnabled: Boolean,
    val bitPerfectEnabled: Boolean,
    val isPlaying: Boolean,
    val repeatOne: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    val barMode: Int, // 8 or 16 bars
    val bpm: Float,   // Track A detected tempo
    val hasNextItem: Boolean,
    val nextItemResolved: Boolean,
    val streamExpired: Boolean,
    val standbyArmedForNext: Boolean,
    val standbyReady: Boolean,
    val standbyDurationMs: Long,
    val standbyBufferedMs: Long,
    val armLeadMs: Long = 35_000L,
    val refreshLeadMs: Long = 5_000L,
) {
    /** 1 bar = 4 beats at current BPM */
    val barMs: Long
        get() {
            val safeBpm = if (bpm in 50f..220f) bpm else 120f
            return ((4.0 * (60.0 / safeBpm)) * 1000.0).toLong().coerceIn(1000L, 5000L)
        }

    /** 8-bar or 16-bar outro phrase duration, snapped to 12s–24s beat boundary */
    val transitionMs: Long
        get() {
            val bars = barMode.coerceIn(8, 16)
            val fullPhraseMs = (bars * barMs).coerceIn(12_000L, 24_000L)
            val snappedMs = (fullPhraseMs / barMs) * barMs
            return if (durationMs > 0L) minOf(snappedMs, durationMs / 3).coerceAtLeast(4000L) else snappedMs
        }

    val remainingMs: Long
        get() = durationMs - positionMs

    val refreshWindowMs: Long
        get() = transitionMs + armLeadMs + refreshLeadMs
}

/**
 * Pure state decision function for AutoMix scheduling.
 */
fun planAutoMix(input: AutoMixPlanInput): AutoMixDecision = with(input) {
    if (!automixEnabled) return AutoMixDecision.Idle(AutoMixIdleReason.Disabled)
    if (bitPerfectEnabled) return AutoMixDecision.Idle(AutoMixIdleReason.BitPerfect)
    if (!isPlaying) return AutoMixDecision.Idle(AutoMixIdleReason.Paused)
    if (repeatOne) return AutoMixDecision.Idle(AutoMixIdleReason.RepeatOne)
    if (durationMs <= 0L || transitionMs <= 0L) {
        return AutoMixDecision.Idle(AutoMixIdleReason.UnknownDuration)
    }
    if (!hasNextItem) return AutoMixDecision.Idle(AutoMixIdleReason.NoNextItem)
    if (remainingMs <= 0L) return AutoMixDecision.Idle(AutoMixIdleReason.Ended)

    val resolveReason = when {
        !nextItemResolved -> AutoMixResolveReason.Placeholder
        streamExpired -> AutoMixResolveReason.ExpiredSignature
        else -> null
    }
    if (resolveReason != null) {
        if (remainingMs > refreshWindowMs) {
            return AutoMixDecision.Idle(AutoMixIdleReason.TooEarly)
        }
        return AutoMixDecision.ResolveNext(resolveReason)
    }

    if (!standbyArmedForNext) return AutoMixDecision.Arm
    if (!standbyReady) return AutoMixDecision.WaitingForReady
    if (remainingMs > transitionMs) return AutoMixDecision.WaitingForWindow

    val sustainableMs = standbyBufferedMs.takeIf { it > 0L } ?: transitionMs
    val overlapMs = minOf(
        transitionMs,
        remainingMs,
        standbyDurationMs.takeIf { it > 0L }?.div(3) ?: transitionMs,
        sustainableMs,
    ).coerceAtLeast(1000L)

    return AutoMixDecision.HandOff(overlapMs, barMode)
}
