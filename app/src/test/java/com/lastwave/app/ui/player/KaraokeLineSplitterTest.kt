package com.lastwave.app.ui.player

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.data.lyrics.LyricSyllable
import org.junit.Test

class KaraokeLineSplitterTest {

    private val measure10: (String) -> Float = { it.length * 10f }

    private fun wordLine(text: String, startMs: Long = 0L, stepMs: Long = 500L): LyricLine {
        var t = startMs
        val syllables = text.split(' ').map { word ->
            LyricSyllable(timeMs = t, durationMs = 400L, text = word).also { t += stepMs }
        }
        return LyricLine(
            timeMs = startMs,
            durationMs = stepMs * text.split(' ').size,
            text = text,
            syllables = syllables,
        )
    }

    @Test
    fun renderedSyllableContents_separatesWordsButNotLast() {
        val syllables = listOf(
            LyricSyllable(timeMs = 0L, durationMs = 100L, text = "Where"),
            LyricSyllable(timeMs = 100L, durationMs = 100L, text = "two"),
        )
        assertThat(renderedSyllableContents(syllables, needsSpacing = true))
            .containsExactly("Where ", "two")
            .inOrder()
    }

    @Test
    fun renderedSyllableContents_partContinuationHasNoSeparator() {
        val syllables = listOf(
            LyricSyllable(timeMs = 0L, durationMs = 100L, text = "with"),
            LyricSyllable(timeMs = 100L, durationMs = 100L, text = "drawals", appendToPrevious = true),
            LyricSyllable(timeMs = 200L, durationMs = 100L, text = "song"),
        )
        assertThat(renderedSyllableContents(syllables, needsSpacing = true))
            .containsExactly("with", "drawals ", "song")
            .inOrder()
    }

    @Test
    fun renderedSyllableContents_noSpacingForSpacelessLines() {
        val syllables = listOf(
            LyricSyllable(timeMs = 0L, durationMs = 100L, text = "你"),
            LyricSyllable(timeMs = 100L, durationMs = 100L, text = "好"),
        )
        assertThat(renderedSyllableContents(syllables, needsSpacing = false))
            .containsExactly("你", "好")
            .inOrder()
    }

    @Test
    fun packBalanced_singleRowWhenEverythingFits() {
        assertThat(packBalanced(listOf(30f, 40f, 20f), 100f))
            .containsExactly(0..2)
    }

    @Test
    fun packBalanced_prefersBalancedRowsOverGreedy() {
        // Greedy sequential would emit [80,30],[30,30],[80]; the balanced
        // optimum is [80],[30,30,30],[80].
        assertThat(packBalanced(listOf(80f, 30f, 30f, 30f, 80f), 110f))
            .containsExactly(0..0, 1..3, 4..4)
            .inOrder()
    }

    @Test
    fun packBalanced_isolatesOverWideUnits() {
        assertThat(packBalanced(listOf(120f, 30f), 100f))
            .containsExactly(0..0, 1..1)
            .inOrder()
    }

    @Test
    fun splitKaraokeToFit_shortLine_returnsIdentity() {
        val line = wordLine("Sandbag and hide")
        val result = line.splitKaraokeToFit(1000f, measure10)
        assertThat(result).containsExactly(line)
    }

    @Test
    fun splitKaraokeToFit_plainLine_returnsIdentity() {
        val line = LyricLine(timeMs = 0L, durationMs = 2000L, text = "A very long plain line without syllables")
        assertThat(line.splitKaraokeToFit(10f, measure10)).containsExactly(line)
    }

    @Test
    fun splitKaraokeToFit_longLine_breaksIntoFittingBalancedChunks() {
        val line = wordLine("Where two and two alone will never meet")
        val result = line.splitKaraokeToFit(200f, measure10)

        // Two rows cannot hold 390 units under a 200 budget; three can.
        assertThat(result).hasSize(3)
        result.forEach { chunk ->
            assertThat(measure10(chunk.text)).isAtMost(200f)
            assertThat(chunk.hasSyllables).isTrue()
        }
        // Text round-trips back to the original line.
        assertThat(result.joinToString(" ") { it.text }).isEqualTo(line.text)
        // Timing partitions the original range contiguously.
        assertThat(result.first().timeMs).isEqualTo(line.timeMs)
        assertThat(result.last().timeMs + result.last().durationMs)
            .isEqualTo(line.timeMs + line.durationMs)
        result.zipWithNext { current, next ->
            assertThat(current.timeMs + current.durationMs).isEqualTo(next.timeMs)
        }
    }

    @Test
    fun splitKaraokeToFit_backgroundSyllablesRideWithTheirChunk() {
        val line = wordLine("Where two and two alone will never meet").let {
            it.copy(
                syllables = it.syllables + LyricSyllable(
                    timeMs = 3000L,
                    durationMs = 400L,
                    text = "ooh",
                    isBackground = true,
                ),
            )
        }
        val result = line.splitKaraokeToFit(200f, measure10)
        assertThat(result).hasSize(3)
        val owner = result.single { chunk -> chunk.syllables.any { it.isBackground } }
        assertThat(owner.syllables.single { it.isBackground }.text).isEqualTo("ooh")
        // The "ooh" at 3000ms belongs to the chunk covering that timestamp.
        assertThat(owner.timeMs).isAtMost(3000L)
        assertThat(owner.timeMs + owner.durationMs).isAtLeast(3000L)
    }

    @Test
    fun splitKaraokeToFit_spacelessLine_splitsByCharacter() {
        val syllables = "你好世界和平".mapIndexed { index, char ->
            LyricSyllable(timeMs = index * 200L, durationMs = 150L, text = char.toString())
        }
        val line = LyricLine(timeMs = 0L, durationMs = 1200L, text = "你好世界和平", syllables = syllables)
        val result = line.splitKaraokeToFit(30f, measure10)
        assertThat(result.size).isAtLeast(2)
        result.forEach { chunk ->
            assertThat(measure10(chunk.text)).isAtMost(30f)
        }
    }

    @Test
    fun splitKaraokeToFit_singleUnbreakableWord_returnsIdentity() {
        val line = LyricLine(
            timeMs = 0L,
            durationMs = 1000L,
            text = "Supercalifragilistic",
            syllables = listOf(LyricSyllable(timeMs = 0L, durationMs = 900L, text = "Supercalifragilistic")),
        )
        assertThat(line.splitKaraokeToFit(150f, measure10)).containsExactly(line)
    }
}
