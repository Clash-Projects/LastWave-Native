package com.lastwave.app.ui.player.lyricsshare

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.ui.player.LyricsUiState
import org.junit.Test

class LyricsSelectionTest {

    private val sampleTrack = TrackInfo(
        title = "Starboy",
        artist = "The Weeknd",
        artworkUrl = "https://example.com/art.jpg",
    )

    @Test
    fun buildLyricsSnapshot_synced_filtersBlankLines() {
        val lines = listOf(
            LyricLine(timeMs = 1000L, text = "I'm tryna put you in the worst mood, ah"),
            LyricLine(timeMs = 3000L, text = "   "),
            LyricLine(timeMs = 5000L, text = ""),
            LyricLine(timeMs = 7000L, text = "P1 cleaner than your church shoes, ah"),
        )
        val state = LyricsUiState.Success(
            lines = lines,
            isSynced = true,
        )

        val snapshot = buildLyricsSnapshot(state, sampleTrack, currentActiveIndex = 0)

        assertThat(snapshot).isNotNull()
        assertThat(snapshot!!.lines).hasSize(2)
        assertThat(snapshot.lines[0].text).isEqualTo("I'm tryna put you in the worst mood, ah")
        assertThat(snapshot.lines[0].originalIndex).isEqualTo(0)
        assertThat(snapshot.lines[1].text).isEqualTo("P1 cleaner than your church shoes, ah")
        assertThat(snapshot.lines[1].originalIndex).isEqualTo(3)
    }

    @Test
    fun buildLyricsSnapshot_plainLyrics_splitsOnNewlinesAndDiscardsEmpty() {
        val plain = "First line\n\n   \nSecond line\nThird line\n"
        val state = LyricsUiState.Success(
            lines = emptyList(),
            isSynced = false,
            plainLyrics = plain,
        )

        val snapshot = buildLyricsSnapshot(state, sampleTrack)

        assertThat(snapshot).isNotNull()
        assertThat(snapshot!!.lines).hasSize(3)
        assertThat(snapshot.lines.map { it.text }).containsExactly("First line", "Second line", "Third line").inOrder()
    }

    @Test
    fun buildLyricsSnapshot_instrumental_returnsNull() {
        val state = LyricsUiState.Success(
            lines = emptyList(),
            isSynced = true,
            isInstrumental = true,
        )

        val snapshot = buildLyricsSnapshot(state, sampleTrack)
        assertThat(snapshot).isNull()
    }

    @Test
    fun buildLyricsSnapshot_empty_returnsNull() {
        val state = LyricsUiState.Success(
            lines = emptyList(),
            isSynced = true,
        )

        val snapshot = buildLyricsSnapshot(state, sampleTrack)
        assertThat(snapshot).isNull()
    }

    @Test
    fun computeDefaultSelection_centersOnActiveIndex() {
        // 10 lines, active is index 5 -> window of 3 centered around 5: [4..6]
        val range = computeDefaultSelection(totalLines = 10, activeIndex = 5)
        assertThat(range).isEqualTo(4..6)

        // Near start: active is 0 -> [0..2]
        val rangeStart = computeDefaultSelection(totalLines = 10, activeIndex = 0)
        assertThat(rangeStart).isEqualTo(0..2)

        // Near end: active is 9 -> [7..9]
        val rangeEnd = computeDefaultSelection(totalLines = 10, activeIndex = 9)
        assertThat(rangeEnd).isEqualTo(7..9)

        // Invalid activeIndex -> starts at top [0..2]
        val rangeInvalid = computeDefaultSelection(totalLines = 10, activeIndex = -1)
        assertThat(rangeInvalid).isEqualTo(0..2)

        // Less than 3 lines total: selects all available lines
        val rangeSmall = computeDefaultSelection(totalLines = 2, activeIndex = 1)
        assertThat(rangeSmall).isEqualTo(0..1)
    }

    @Test
    fun updateContiguousSelection_extendsAdjacentLines() {
        var capCalled = false
        val initial = 2..3

        // Extend after
        val extendedAfter = updateContiguousSelection(initial, tappedIndex = 4, totalLines = 10) { capCalled = true }
        assertThat(extendedAfter).isEqualTo(2..4)
        assertThat(capCalled).isFalse()

        // Extend before
        val extendedBefore = updateContiguousSelection(initial, tappedIndex = 1, totalLines = 10) { capCalled = true }
        assertThat(extendedBefore).isEqualTo(1..3)
        assertThat(capCalled).isFalse()
    }

    @Test
    fun updateContiguousSelection_shrinksEdges() {
        val initial = 2..4

        // Shrink start
        val shrinkStart = updateContiguousSelection(initial, tappedIndex = 2, totalLines = 10)
        assertThat(shrinkStart).isEqualTo(3..4)

        // Shrink end
        val shrinkEnd = updateContiguousSelection(initial, tappedIndex = 4, totalLines = 10)
        assertThat(shrinkEnd).isEqualTo(2..3)
    }

    @Test
    fun updateContiguousSelection_singleLineDoesNotShrinkToZero() {
        val initial = 3..3
        val result = updateContiguousSelection(initial, tappedIndex = 3, totalLines = 10)
        assertThat(result).isEqualTo(3..3)
    }

    @Test
    fun updateContiguousSelection_tappingInnerLineRestartsSelection() {
        val initial = 1..5
        val result = updateContiguousSelection(initial, tappedIndex = 3, totalLines = 10)
        assertThat(result).isEqualTo(3..3)
    }

    @Test
    fun updateContiguousSelection_tappingNonAdjacentLineRestartsSelection() {
        val initial = 2..3
        val result = updateContiguousSelection(initial, tappedIndex = 7, totalLines = 10)
        assertThat(result).isEqualTo(7..7)
    }

    @Test
    fun updateContiguousSelection_capsAtMaxFiveLines() {
        var capCalled = false
        val fiveLines = 1..5

        val result = updateContiguousSelection(
            currentRange = fiveLines,
            tappedIndex = 6,
            totalLines = 10,
            maxLines = 5,
            onCapReached = { capCalled = true },
        )

        assertThat(result).isEqualTo(1..5)
        assertThat(capCalled).isTrue()
    }
}
