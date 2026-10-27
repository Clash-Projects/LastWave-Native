package com.lastwave.app.playback

/**
 * Scheduling model for the Mixxx-style AutoDJ Automix dual-deck hand-off.
 *
 * Automix replaces static-timer crossfades with musical phrase-based transitions
 * (Auto, 8, or 16 bars) dynamically computed from track BPM, kick-band onset sync,
 * 200Hz/180Hz Linkwitz-Riley bass-swap, vocal-pocket ducking, and a 1.4s echo reverb tail.
 */

enum class AutoMixIdleReason {
    Disabled,
    BitPerfect,
    SequentialAlbumBypass,
    Paused,
    RepeatOne,
    UnknownDuration,
    NoNextItem,
    Ended,
    TooEarly,
    OutsideClamped30sWindow,
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
    val sequentialAlbumBypass: Boolean = false,
    val isPlaying: Boolean,
    val repeatOne: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    val barMode: Int, // 0 = Auto, 8 or 16 bars
    val bpm: Float,   // Track A detected tempo
    val hasNextItem: Boolean,
    val nextItemResolved: Boolean,
    val streamExpired: Boolean,
    val standbyArmedForNext: Boolean,
    val standbyReady: Boolean,
    val standbyDurationMs: Long,
    val standbyBufferedMs: Long,
    val armLeadMs: Long = 40_000L,
    val refreshLeadMs: Long = 5_000L,
) {
    /** 1 bar = 4 beats at current BPM */
    val barMs: Long
        get() {
            val safeBpm = if (bpm in 50f..220f) bpm else 120f
            return ((4.0 * (60.0 / safeBpm)) * 1000.0).toLong().coerceIn(1000L, 5000L)
        }

    /** Phrase duration based on PhraseLengthMode, clamped per specification */
    val transitionMs: Long
        get() {
            val bars = when (barMode) {
                16 -> 16
                8 -> 8
                else -> 8 // Auto defaults to 8-bar phrase base
            }
            val rawMs = bars * barMs
            val clampedMs = when (bars) {
                16 -> rawMs.coerceIn(10_000L, 26_000L)
                else -> rawMs.coerceIn(6_000L, 18_000L)
            }
            return if (durationMs > 0L) minOf(clampedMs, durationMs / 3).coerceAtLeast(4000L) else clampedMs
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
    if (sequentialAlbumBypass) return AutoMixDecision.Idle(AutoMixIdleReason.SequentialAlbumBypass)
    if (!isPlaying) return AutoMixDecision.Idle(AutoMixIdleReason.Paused)
    if (repeatOne) return AutoMixDecision.Idle(AutoMixIdleReason.RepeatOne)
    if (durationMs <= 0L || transitionMs <= 0L) {
        return AutoMixDecision.Idle(AutoMixIdleReason.UnknownDuration)
    }
    if (!hasNextItem) return AutoMixDecision.Idle(AutoMixIdleReason.NoNextItem)
    if (remainingMs <= 0L) return AutoMixDecision.Idle(AutoMixIdleReason.Ended)

    // Hard 30-Second Window Clamp:
    // Strictly forbidden to trigger an automatic transition earlier than durationMs - 30_000
    if (remainingMs > 30_000L) {
        val resolveReason = when {
            !nextItemResolved -> AutoMixResolveReason.Placeholder
            streamExpired -> AutoMixResolveReason.ExpiredSignature
            else -> null
        }
        if (resolveReason != null) {
            return AutoMixDecision.ResolveNext(resolveReason)
        }
        if (!standbyArmedForNext) {
            return AutoMixDecision.Arm
        }
        return AutoMixDecision.Idle(AutoMixIdleReason.OutsideClamped30sWindow)
    }

    val resolveReason = when {
        !nextItemResolved -> AutoMixResolveReason.Placeholder
        streamExpired -> AutoMixResolveReason.ExpiredSignature
        else -> null
    }
    if (resolveReason != null) {
        return AutoMixDecision.ResolveNext(resolveReason)
    }

    if (!standbyArmedForNext) return AutoMixDecision.Arm
    if (!standbyReady || (standbyBufferedMs in 1L until 8000L)) return AutoMixDecision.WaitingForReady
    if (remainingMs > transitionMs) return AutoMixDecision.WaitingForWindow

    val sustainableMs = standbyBufferedMs.takeIf { it > 0L } ?: transitionMs
    val overlapMs = minOf(
        transitionMs,
        remainingMs,
        standbyDurationMs.takeIf { it > 0L }?.div(3) ?: transitionMs,
        sustainableMs,
    ).coerceAtLeast(1000L)

    return AutoMixDecision.HandOff(overlapMs, if (barMode in listOf(8, 16)) barMode else 8)
}
