package com.lastwave.app.ui.playlist

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.generate.GeneratedTrack
import org.junit.Test

class PlaylistTrackRowsTest {
    private val first = GeneratedTrack("Song", "Artist", url = "https://music.youtube.com/watch?v=version1")
    private val second = first.copy(url = "https://music.youtube.com/watch?v=version2")
    private val other = GeneratedTrack("Other", "Artist", url = "https://music.youtube.com/watch?v=other")

    @Test
    fun reversedFilteredRowsKeepTheirSourceOccurrence() {
        val tracks = listOf(first, other, second, first.copy())
        val indices = playlistTrackDisplayIndices(tracks, tracks, PlaylistTrackSort.CUSTOM, false, "song")

        assertThat(indices).containsExactly(3, 2, 0).inOrder()
        assertThat(selectedPlaylistTrackIndex(tracks, indices.first(), tracks[indices.first()])).isEqualTo(3)
    }

    @Test
    fun sameTitleDifferentVersionRemovesSelectedUrl() {
        val tracks = listOf(first, other, second)
        assertThat(selectedPlaylistTrackIndex(tracks, 2, second)).isEqualTo(2)
        // Even after another row was removed, metadata alone cannot match version 1.
        assertThat(selectedPlaylistTrackIndex(listOf(first, second), 2, second)).isEqualTo(1)
    }

    @Test
    fun folderFilteringAndNameSortPreserveOriginalIndices() {
        val tracks = listOf(first, other, second)
        val indices = playlistTrackDisplayIndices(tracks, listOf(other, second), PlaylistTrackSort.NAME, true, "")
        assertThat(indices).containsExactly(1, 2).inOrder()
    }

    @Test
    fun missingSelectedTrackDoesNotDeleteSameTitleAlternative() {
        assertThat(selectedPlaylistTrackIndex(listOf(first), 0, second)).isNull()
    }

    @Test
    fun dateAddedDescendingShowsNewestFirstForBothSourceOrders() {
        val tracks = listOf(first, other, second)
        assertThat(playlistTrackDisplayIndices(tracks, tracks, PlaylistTrackSort.DATE_ADDED, false, ""))
            .containsExactly(2, 1, 0).inOrder()
        assertThat(playlistTrackDisplayIndices(tracks, tracks, PlaylistTrackSort.DATE_ADDED, false, "", newestFirst = true))
            .containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun playTimeSortsByDurationWithUnknownLast() {
        val tracks = listOf(first.copy(durationMs = 300), other, second.copy(durationMs = 100))
        assertThat(playlistTrackDisplayIndices(tracks, tracks, PlaylistTrackSort.PLAY_TIME, true, ""))
            .containsExactly(2, 0, 1).inOrder()
        assertThat(playlistTrackDisplayIndices(tracks, tracks, PlaylistTrackSort.PLAY_TIME, false, ""))
            .containsExactly(0, 2, 1).inOrder()
    }
}
