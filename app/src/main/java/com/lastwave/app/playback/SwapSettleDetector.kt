package com.lastwave.app.playback

import kotlin.math.abs

/**
 * Detects when the standby deck's reported position has stopped moving relative
 * to the live deck.
 *
 * The fixed 1.5 s settle exists because a player's position is a blend for a
 * while after its output starts. On outputs where it stabilizes sooner, the
 * remaining wait is dead time; on slower/Bluetooth outputs the same detector
 * keeps waiting to the full ceiling. It only decides when it is safe to take
 * the final reading, so a wrong early decision cannot skip the correction step.
 */
internal class SwapSettleDetector(
    private val toleranceMs: Long = 3L,
    private val stableReads: Int = 2,
) {
    private var previousDriftMs: Long? = null
    private var streak = 0

    /** Feed one averaged drift reading; true once consecutive readings agree. */
    fun observe(driftMs: Long): Boolean {
        val previous = previousDriftMs
        previousDriftMs = driftMs
        if (previous == null) return false
        if (abs(driftMs - previous) <= toleranceMs) {
            streak++
        } else {
            streak = 0
        }
        return streak >= stableReads
    }
}
