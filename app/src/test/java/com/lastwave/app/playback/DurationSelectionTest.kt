package com.lastwave.app.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The regression here is a song being abandoned seconds in: the crossfade timer
 * read a duration belonging to the *previous* track, so its window opened early
 * and the hand-off swapped the next item in. Each test pins one rule of the
 * ownership check.
 */
class DurationSelectionTest {

    private fun request(
        playerMs: Long = 0L,
        knownForCurrentItemMs: Long = 0L,
        publishedMs: Long = 0L,
        publishedBelongsToCurrentItem: Boolean = false,
    ) = DurationRequest(
        playerMs = playerMs,
        knownForCurrentItemMs = knownForCurrentItemMs,
        publishedMs = publishedMs,
        publishedBelongsToCurrentItem = publishedBelongsToCurrentItem,
    )

    // --- the leak this change exists to close -------------------------------------

    @Test
    fun previousTracksDurationIsNeverInheritedAcrossATrackChange() {
        // A 4s outro track published 4000ms, then the queue moved to a 3-minute
        // track whose container has not parsed yet. Reusing 4000 here is what
        // made the crossfade hand off ~2s into the new track.
        val dur = selectDuration(
            request(
                playerMs = 0L,
                knownForCurrentItemMs = 0L,
                publishedMs = 4_000L,
                publishedBelongsToCurrentItem = false,
            ),
        )

        assertThat(dur).isEqualTo(0L)
    }

    @Test
    fun unknownDurationReportsZeroRatherThanGuessing() {
        // Nothing knows this track's length yet. 0 disables the denominator
        // until ExoPlayer reports a real one, instead of showing the old
        // track's length as if it were this one's.
        assertThat(selectDuration(request())).isEqualTo(0L)
    }

    // --- the reuse that must keep working -----------------------------------------

    @Test
    fun publishedDurationSurvivesRebufferingOfTheSameItem() {
        // The reason this function cannot simply always return 0: a stream
        // rebuffering mid-track reports TIME_UNSET again, and dropping the
        // denominator there froze the seek bar at 0:00 and disabled seeking.
        val dur = selectDuration(
            request(
                playerMs = 0L,
                knownForCurrentItemMs = 0L,
                publishedMs = 187_000L,
                publishedBelongsToCurrentItem = true,
            ),
        )

        assertThat(dur).isEqualTo(187_000L)
    }

    @Test
    fun exactPlayerDurationAlwaysWins() {
        val dur = selectDuration(
            request(
                playerMs = 125_481L,
                knownForCurrentItemMs = 999L,
                publishedMs = 4_000L,
                publishedBelongsToCurrentItem = false,
            ),
        )

        assertThat(dur).isEqualTo(125_481L)
    }

    @Test
    fun aSeedForThisItemBeatsThePublishedValue() {
        // Catalog/resolve seeds are keyed to the item, so they are trustworthy
        // even mid-transition, and they are what lets the seek bar have a
        // denominator from t=0.
        val dur = selectDuration(
            request(
                playerMs = 0L,
                knownForCurrentItemMs = 210_000L,
                publishedMs = 4_000L,
                publishedBelongsToCurrentItem = false,
            ),
        )

        assertThat(dur).isEqualTo(210_000L)
    }

    // --- sign and boundary handling ------------------------------------------------

    @Test
    fun timeUnsetSentinelIsTreatedAsUnknown() {
        // ExoPlayer's C.TIME_UNSET. A naive `> 0` guard is what makes this a
        // real hazard rather than a theoretical one: this exact value appears
        // in the field diagnostics at every track start.
        val dur = selectDuration(
            request(
                playerMs = -9_223_372_036_854_775_807L,
                publishedMs = 110_749L,
                publishedBelongsToCurrentItem = false,
            ),
        )

        assertThat(dur).isEqualTo(0L)
    }

    @Test
    fun negativeSeedsAndPublishedValuesAreIgnored() {
        assertThat(selectDuration(request(knownForCurrentItemMs = -1L))).isEqualTo(0L)
        assertThat(
            selectDuration(
                request(publishedMs = -1L, publishedBelongsToCurrentItem = true),
            ),
        ).isEqualTo(0L)
    }
}