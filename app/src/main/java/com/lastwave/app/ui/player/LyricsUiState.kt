package com.lastwave.app.ui.player

import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.data.lyrics.isRtlText

sealed interface LyricsUiState {
    data object Idle : LyricsUiState
    data object Loading : LyricsUiState
    data class Success(
        val lines: List<LyricLine>,
        val isSynced: Boolean,
        val isWordSynced: Boolean = false,
        val plainLyrics: String? = null,
        val isInstrumental: Boolean = false,
        val source: String? = null,
    ) : LyricsUiState {
        val isRtl: Boolean get() = lines.any { it.isRtl } || isRtlText(plainLyrics)
    }
    data object Empty : LyricsUiState
    data class Error(val message: String) : LyricsUiState
}
