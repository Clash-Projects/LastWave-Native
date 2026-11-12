package com.lastwave.app.ui.feed

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.music.HomeShelfItem
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistSummary
import org.junit.Test

class CommunityDiscoverShelfTest {
    @Test fun cardWidthsLeaveAPeekOnPhonesAndClampOnTabletsAndLandscape() {
        for (width in listOf(320, 360, 390, 430)) {
            val card = largeShelfCardWidth(width.dp)
            assertThat(card.value + 16 + 12).isLessThan(width.toFloat())
        }
        assertThat(largeShelfCardWidth(800.dp)).isEqualTo(420.dp)
        assertThat(largeShelfCardWidth(1200.dp)).isEqualTo(420.dp)
    }

    @Test fun duplicateIdsHaveUniqueKeysAndOtherInsertionsDoNotChangeThem() {
        val song = HomeShelfItem.Track(YouTubeMusicTrack("same", "Song", "Artist"))
        val playlist = HomeShelfItem.Playlist(YouTubePlaylistSummary("same", "Playlist"))
        val original = stableShelfCardKeys(listOf(song, playlist, song))
        assertThat(original.distinct()).hasSize(3)
        val inserted = HomeShelfItem.Track(YouTubeMusicTrack("another", "Another song", "Artist"))
        assertThat(stableShelfCardKeys(listOf(inserted, song, playlist, song)).drop(1)).isEqualTo(original)
    }
}
