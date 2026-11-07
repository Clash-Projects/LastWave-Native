package com.lastwave.app.ui.player

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.data.lyrics.LyricSyllable
import com.lastwave.app.data.lyrics.VocalRole
import org.junit.Test

class LyricsStagingTest {

    private fun syl(t: Long, d: Long, text: String, bg: Boolean = false) =
        LyricSyllable(timeMs = t, durationMs = d, text = text, isBackground = bg)

    @Test
    fun displayParts_copyAuthoredPunctuationWithSingleSpaces() {
        val parts = buildDisplayParts("Hello,   world!", listOf(syl(0, 100, "Hello"), syl(100, 100, "world")))
        assertThat(parts).containsExactly("Hello,", " world!").inOrder()
    }

    @Test
    fun displayParts_joinFragmentsOfOneWord() {
        val parts = buildDisplayParts(
            "Hello world",
            listOf(syl(0, 100, "Hel"), syl(100, 100, "lo"), syl(200, 100, "world")),
        )
        assertThat(parts.joinToString("")).isEqualTo("Hello world")
        assertThat(parts).hasSize(3)
    }

    @Test
    fun displayParts_spacelessScriptsGetNoSpaces() {
        val parts = buildDisplayParts(null, listOf(syl(0, 100, "你"), syl(100, 100, "好")))
        assertThat(parts.joinToString("")).isEqualTo("你好")
    }

    @Test
    fun displayParts_neverPullWordsFromOtherVocals() {
        // A backing word that also appears in the lead text must not drag the
        // lead words in front of it.
        val parts = buildDisplayParts("I love you", listOf(syl(0, 100, "you"), syl(100, 100, "too")))
        assertThat(parts.joinToString("")).isEqualTo("you too")
    }

    @Test
    fun stage_insertsInterludeForLongBreak() {
        val stage = buildLyricsStage(
            listOf(
                LyricLine(timeMs = 1000L, durationMs = 2000L, text = "First line"),
                LyricLine(timeMs = 10_000L, durationMs = 2000L, text = "Second line"),
            ),
        )
        assertThat(stage.items).hasSize(3)
        val interlude = stage.items[1] as LyricInterlude
        assertThat(interlude.startMs).isEqualTo(3000L)
        assertThat(interlude.endMs).isEqualTo(10_000L)
        assertThat(stage.items[0].stanzaGapAfter).isTrue()
        assertThat(stage.activeIndex(5000L)).isEqualTo(1)
        assertThat(stage.activeIndex(10_000L)).isEqualTo(2)
        assertThat(stage.activeIndex(500L)).isEqualTo(-1)
    }

    @Test
    fun stage_markerRowOpensInterlude() {
        val stage = buildLyricsStage(
            listOf(
                LyricLine(timeMs = 0L, durationMs = 5000L, text = "Sung"),
                LyricLine(timeMs = 5000L, durationMs = 6000L, text = ""),
                LyricLine(timeMs = 11_000L, durationMs = 2000L, text = "Back again"),
            ),
        )
        assertThat(stage.items.map { it::class }).containsExactly(
            LyricLineGroup::class, LyricInterlude::class, LyricLineGroup::class,
        ).inOrder()
        assertThat(stage.items[1].startMs).isEqualTo(5000L)
    }

    @Test
    fun stage_nestsBackingVocalsUnderTheLead() {
        val stage = buildLyricsStage(
            listOf(
                LyricLine(
                    timeMs = 0L,
                    durationMs = 3000L,
                    text = "Lead line",
                    syllables = listOf(syl(0, 500, "Lead"), syl(500, 500, "line")),
                ),
                LyricLine(
                    timeMs = 1500L,
                    durationMs = 1000L,
                    text = "(ooh)",
                    syllables = listOf(syl(1500, 1000, "(ooh)", bg = true)),
                ),
            ),
        )
        assertThat(stage.items).hasSize(1)
        val group = stage.items.single() as LyricLineGroup
        assertThat(group.lead?.text).isEqualTo("Lead line")
        assertThat(group.backgrounds.single().text).isEqualTo("ooh")
        assertThat(group.backgrounds.single().lineIndex).isEqualTo(1)
    }

    @Test
    fun stage_splitsMixedRowIntoLeadAndBacking() {
        val stage = buildLyricsStage(
            listOf(
                LyricLine(
                    timeMs = 0L,
                    durationMs = 3000L,
                    text = "Hold on",
                    syllables = listOf(
                        syl(0, 500, "Hold"),
                        syl(400, 600, "(yeah", bg = true),
                        syl(500, 500, "on"),
                        syl(1000, 500, "yeah)", bg = true),
                    ),
                ),
            ),
        )
        val group = stage.items.single() as LyricLineGroup
        assertThat(group.lead?.text).isEqualTo("Hold on")
        assertThat(group.backgrounds.single().text).isEqualTo("yeah yeah")
    }

    @Test
    fun stage_isStagedOnlyForDuets() {
        val solo = buildLyricsStage(
            listOf(
                LyricLine(timeMs = 0L, durationMs = 1000L, text = "a"),
                LyricLine(timeMs = 1000L, durationMs = 1000L, text = "b"),
            ),
        )
        assertThat(solo.isStaged).isFalse()
        val duet = buildLyricsStage(
            listOf(
                LyricLine(timeMs = 0L, durationMs = 1000L, text = "a", role = VocalRole.LEAD_LEFT),
                LyricLine(timeMs = 1000L, durationMs = 1000L, text = "b", role = VocalRole.DUET_RIGHT),
            ),
        )
        assertThat(duet.isStaged).isTrue()
        assertThat((duet.items[1] as LyricLineGroup).role).isEqualTo(VocalRole.DUET_RIGHT)
    }
}
