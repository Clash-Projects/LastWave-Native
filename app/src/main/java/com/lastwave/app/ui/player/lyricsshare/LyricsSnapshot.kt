package com.lastwave.app.ui.player.lyricsshare

import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.data.lyrics.isRtlText
import com.lastwave.app.ui.player.LyricsUiState

/**
 * Immutable metadata of the track at the moment the share flow opened.
 */
data class TrackInfo(
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val resolvedArtworkUrl: String? = null,
)

/**
 * A selectable line of lyrics with directional metadata.
 */
data class ShareLine(
    val text: String,
    val isRtl: Boolean = isRtlText(text),
    val originalIndex: Int = -1,
)

/**
 * Point-in-time immutable snapshot of lyrics and track info for the share sheet.
 * Playback keeps advancing underneath, but this snapshot remains stable.
 */
data class LyricsSnapshot(
    val trackInfo: TrackInfo,
    val lines: List<ShareLine>,
    val initialActiveLineIndex: Int = -1,
)

/**
 * Creates an immutable snapshot from current [LyricsUiState.Success] lines.
 * Filters out blank lines and maps raw text only (never offset-shifted timestamps).
 * Supports both line-synced / word-synced and plain unsynced lyrics.
 */
fun buildLyricsSnapshot(
    lyricsState: LyricsUiState,
    trackInfo: TrackInfo,
    currentActiveIndex: Int = -1,
): LyricsSnapshot? {
    val success = lyricsState as? LyricsUiState.Success ?: return null
    if (success.isInstrumental) return null

    val shareLines: List<ShareLine> = if (success.isSynced && success.lines.isNotEmpty()) {
        success.lines.mapIndexedNotNull { index, line ->
            val trimmed = line.text.trim()
            if (trimmed.isNotBlank()) {
                ShareLine(
                    text = trimmed,
                    isRtl = line.isRtl || isRtlText(trimmed),
                    originalIndex = index,
                )
            } else null
        }
    } else {
        // Plain (unsynced) lyrics fallback: split on newlines, discarding empty lines
        val plain = success.plainLyrics ?: return null
        plain.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapIndexed { index, text ->
                ShareLine(
                    text = text,
                    isRtl = isRtlText(text),
                    originalIndex = index,
                )
            }
    }

    if (shareLines.isEmpty()) return null

    // Map active index to filtered lines list
    val mappedActiveIndex = if (currentActiveIndex >= 0 && success.isSynced) {
        val found = shareLines.indexOfFirst { it.originalIndex >= currentActiveIndex }
        if (found >= 0) found else shareLines.lastIndex
    } else {
        -1
    }

    return LyricsSnapshot(
        trackInfo = trackInfo,
        lines = shareLines,
        initialActiveLineIndex = mappedActiveIndex,
    )
}

/**
 * Computes default 1..3 line selection window centered on the active line.
 * If there is no active line yet, starts at the top (first 3 lines).
 */
fun computeDefaultSelection(totalLines: Int, activeIndex: Int): IntRange {
    if (totalLines <= 0) return IntRange.EMPTY
    if (totalLines <= 3) return 0 until totalLines

    return if (activeIndex in 0 until totalLines) {
        val idealStart = (activeIndex - 1).coerceAtLeast(0)
        val idealEnd = (idealStart + 2).coerceAtMost(totalLines - 1)
        val finalStart = (idealEnd - 2).coerceAtLeast(0)
        finalStart..idealEnd
    } else {
        0..minOf(2, totalLines - 1)
    }
}

/**
 * Implements contiguous selection rules for 1 to 5 lines:
 * - Tapping a line adjacent to the range extends it (clamped at [maxLines]).
 * - Tapping an edge line shrinks it (minimum 1 line).
 * - Tapping an inner line restarts selection from that single line.
 * - Tapping a non-adjacent line restarts selection from that single line.
 * - At [maxLines], invokes [onCapReached] without extending.
 */
fun updateContiguousSelection(
    currentRange: IntRange,
    tappedIndex: Int,
    totalLines: Int,
    maxLines: Int = 5,
    onCapReached: () -> Unit = {},
): IntRange {
    if (tappedIndex !in 0 until totalLines) return currentRange
    if (currentRange.isEmpty()) return tappedIndex..tappedIndex

    val start = currentRange.first
    val end = currentRange.last
    val currentCount = end - start + 1

    return when {
        // Tapping edge of a multi-line range: shrink
        tappedIndex == start && currentCount > 1 -> (start + 1)..end
        tappedIndex == end && currentCount > 1 -> start..(end - 1)

        // Tapping single selected line: keep it (minimum 1)
        tappedIndex == start && currentCount == 1 -> currentRange

        // Tapping immediately adjacent before
        tappedIndex == start - 1 -> {
            if (currentCount >= maxLines) {
                onCapReached()
                currentRange
            } else {
                tappedIndex..end
            }
        }

        // Tapping immediately adjacent after
        tappedIndex == end + 1 -> {
            if (currentCount >= maxLines) {
                onCapReached()
                currentRange
            } else {
                start..tappedIndex
            }
        }

        // Any non-adjacent or interior tap: restart single-line selection
        else -> tappedIndex..tappedIndex
    }
}
