package com.lastwave.app.ui.player

import com.lastwave.app.playback.PlayableTrack

object QueueStatsFormatter {
    /**
     * Formats queue song count and total duration into a compact stats string:
     * - "0 songs"
     * - "1 song" (if duration unknown)
     * - "1 song · 3m"
     * - "58 songs · 3h 37m"
     * - "58 songs · 42m" (under one hour)
     * - "58 songs · 3h 37m+" (if some songs have unknown duration)
     * - "58 songs" (if none of the songs have known duration)
     */
    fun formatQueueStats(songs: List<PlayableTrack>): String {
        val totalCount = songs.size
        val countStr = if (totalCount == 1) "1 song" else "$totalCount songs"
        if (totalCount == 0) return "0 songs"

        val knownDurations = songs.mapNotNull { it.durationMs }.filter { it > 0 }
        if (knownDurations.isEmpty()) {
            return countStr
        }

        val totalMs = knownDurations.sum()
        val totalSeconds = totalMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60

        val durationStr = if (hours > 0) {
            "${hours}h ${minutes}m"
        } else {
            val mins = (totalSeconds / 60).coerceAtLeast(if (totalSeconds > 0) 1 else 0)
            "${mins}m"
        }

        val hasUnknown = knownDurations.size < totalCount
        val formattedDuration = if (hasUnknown) "$durationStr+" else durationStr
        return "$countStr · $formattedDuration"
    }
}
