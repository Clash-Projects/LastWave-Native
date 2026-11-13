package com.lastwave.app.util

/**
 * Formats accumulated listening time in milliseconds into a concise
 * human-readable duration (e.g., "42h 36m", "18m", "45s", "0m").
 */
fun formatListeningDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "0m"
    val totalSeconds = durationMs / 1_000L
    if (totalSeconds <= 0L) return "< 1s"
    val totalMinutes = totalSeconds / 60L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
        hours > 0L -> "${hours}h"
        totalMinutes > 0L -> "${totalMinutes}m"
        else -> "${totalSeconds}s"
    }
}

