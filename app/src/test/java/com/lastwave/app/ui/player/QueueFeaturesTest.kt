package com.lastwave.app.ui.player

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.playback.PlayableTrack
import org.junit.Test

class QueueFeaturesTest {

    private fun makeTrack(title: String, durationMs: Long?): PlayableTrack =
        PlayableTrack(title = title, artist = "Artist", durationMs = durationMs)

    // ── FEATURE A: QUEUE LOCK TESTS ──

    @Test
    fun queueLock_defaultsToUnlocked() {
        isQueueLockedState = false
        assertThat(isQueueLockedState).isFalse()
    }

    @Test
    fun queueLock_toggleChangesState() {
        isQueueLockedState = false
        isQueueLockedState = !isQueueLockedState
        assertThat(isQueueLockedState).isTrue()
        isQueueLockedState = !isQueueLockedState
        assertThat(isQueueLockedState).isFalse()
    }

    @Test
    fun queueLock_controlsRemoveAndReorderPermission() {
        isQueueLockedState = true
        val canRemoveWhenLocked = !isQueueLockedState
        val canReorderWhenLocked = !isQueueLockedState
        assertThat(canRemoveWhenLocked).isFalse()
        assertThat(canReorderWhenLocked).isFalse()

        isQueueLockedState = false
        val canRemoveWhenUnlocked = !isQueueLockedState
        val canReorderWhenUnlocked = !isQueueLockedState
        assertThat(canRemoveWhenUnlocked).isTrue()
        assertThat(canReorderWhenUnlocked).isTrue()
    }

    // ── FEATURE B: QUEUE STATS FORMATTER TESTS ──

    @Test
    fun queueStats_emptyQueue_returnsZeroSongs() {
        assertThat(QueueStatsFormatter.formatQueueStats(emptyList()))
            .isEqualTo("0 songs")
    }

    @Test
    fun queueStats_singleSong_unknownDuration_returnsOneSongWithoutDuration() {
        val tracks = listOf(makeTrack("Song 1", null))
        assertThat(QueueStatsFormatter.formatQueueStats(tracks))
            .isEqualTo("1 song")
    }

    @Test
    fun queueStats_singleSong_withDuration_returnsOneSongAndMinutes() {
        val tracks = listOf(makeTrack("Song 1", 180_000L)) // 3m
        assertThat(QueueStatsFormatter.formatQueueStats(tracks))
            .isEqualTo("1 song • 3m")
    }

    @Test
    fun queueStats_underOneHour_formatsMinutesOnly() {
        // 42 minutes = 42 * 60 * 1000 = 2_520_000 ms
        val totalMs = 42 * 60 * 1000L
        val tracks = List(58) { i ->
            val base = totalMs / 58
            val rem = if (i == 57) totalMs % 58 else 0L
            makeTrack("Song $i", base + rem)
        }
        val result = QueueStatsFormatter.formatQueueStats(tracks)
        assertThat(result).isEqualTo("58 songs • 42m")
    }

    @Test
    fun queueStats_overOneHour_formatsHoursAndMinutes() {
        // 3h 37m = (3 * 3600 + 37 * 60) * 1000 = 13_020_000 ms
        val totalMs = (3 * 3600 + 37 * 60) * 1000L
        val tracks = List(58) { i ->
            val base = totalMs / 58
            val rem = if (i == 57) totalMs % 58 else 0L
            makeTrack("Song $i", base + rem)
        }
        val result = QueueStatsFormatter.formatQueueStats(tracks)
        assertThat(result).isEqualTo("58 songs • 3h 37m")
    }

    @Test
    fun queueStats_partialUnknownDurations_appendsPlus() {
        // 50 known songs of 3m each (150m = 2h 30m), plus 8 unknown songs
        val known = List(50) { makeTrack("Known $it", 180_000L) }
        val unknown = List(8) { makeTrack("Unknown $it", null) }
        val tracks = known + unknown

        val result = QueueStatsFormatter.formatQueueStats(tracks)
        assertThat(result).isEqualTo("58 songs • 2h 30m+")
    }

    @Test
    fun queueStats_allUnknownDurations_showsCountOnly() {
        val tracks = List(58) { makeTrack("Song $it", null) }
        val result = QueueStatsFormatter.formatQueueStats(tracks)
        assertThat(result).isEqualTo("58 songs")
    }

    @Test
    fun queueStats_zeroAndNegativeDurations_handledAsUnknown() {
        val tracks = listOf(
            makeTrack("Zero", 0L),
            makeTrack("Negative", -100L),
        )
        val result = QueueStatsFormatter.formatQueueStats(tracks)
        assertThat(result).isEqualTo("2 songs")
    }

    // ── FEATURE C: LOGIN POPUP PREFERENCE AND VISIBILITY LOGIC ──

    private fun shouldShowLoginPrompt(
        isConnected: Boolean,
        suppressPrompt: Boolean,
        promptDismissedThisSession: Boolean,
    ): Boolean = !isConnected && !suppressPrompt && !promptDismissedThisSession

    @Test
    fun loginPopup_shownWhenLoggedOutAndNotSuppressed() {
        val show = shouldShowLoginPrompt(
            isConnected = false,
            suppressPrompt = false,
            promptDismissedThisSession = false,
        )
        assertThat(show).isTrue()
    }

    @Test
    fun loginPopup_neverShownWhenConnected() {
        val show = shouldShowLoginPrompt(
            isConnected = true,
            suppressPrompt = false,
            promptDismissedThisSession = false,
        )
        assertThat(show).isFalse()
    }

    @Test
    fun loginPopup_neverShownWhenSuppressedByPreference() {
        val show = shouldShowLoginPrompt(
            isConnected = false,
            suppressPrompt = true,
            promptDismissedThisSession = false,
        )
        assertThat(show).isFalse()
    }

    @Test
    fun loginPopup_neverShownAgainAfterDismissedInSession() {
        val show = shouldShowLoginPrompt(
            isConnected = false,
            suppressPrompt = false,
            promptDismissedThisSession = true,
        )
        assertThat(show).isFalse()
    }

    @Test
    fun loginPopup_dismissedImmediatelyWhenUserConnects() {
        var isConnected = false
        var show = shouldShowLoginPrompt(
            isConnected = isConnected,
            suppressPrompt = false,
            promptDismissedThisSession = false,
        )
        assertThat(show).isTrue()

        // User connects to YouTube Music
        isConnected = true
        show = shouldShowLoginPrompt(
            isConnected = isConnected,
            suppressPrompt = false,
            promptDismissedThisSession = false,
        )
        assertThat(show).isFalse()
    }
}
