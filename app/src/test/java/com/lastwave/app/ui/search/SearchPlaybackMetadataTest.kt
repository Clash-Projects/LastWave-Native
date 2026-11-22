package com.lastwave.app.ui.search

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.search.SearchResultItem
import com.lastwave.app.playback.MediaType
import org.junit.Test

class SearchPlaybackMetadataTest {
    @Test
    fun searchRecordingKeepsItsVideoAndAlbumForMenuActions() {
        val live = SearchResultItem(name = "Song (Live)", artist = "Artist", videoId = "live_video1",
            subtitle = "Live album", artworkUrl = "https://example.com/live.jpg", durationMs = 245_000)
        val playable = live.toPlayableTrack()
        assertThat(playable.videoId).isEqualTo("live_video1")
        assertThat(playable.album).isEqualTo("Live album")
        assertThat(playable.artworkUrl).isEqualTo(live.artworkUrl)
        assertThat(playable.durationMs).isEqualTo(245_000)
        assertThat(playable.mediaType).isEqualTo(MediaType.SONG)
    }

    @Test
    fun episodeMenuKeepsPodcastTypeAndActualShowIdentity() {
        val episode = SearchResultItem(name = "Episode", videoId = "episode_id1",
            entityId = "episode_id1", showId = "MPSP_show", durationMs = 3_600_000)
        val playable = episode.toPlayableTrack(podcast = true)
        assertThat(playable.videoId).isEqualTo("episode_id1")
        assertThat(playable.mediaType).isEqualTo(MediaType.PODCAST_EPISODE)
        assertThat(playable.showId).isEqualTo("MPSP_show")
        assertThat(playable.durationMs).isEqualTo(3_600_000)
        assertThat(playable.artist).isEqualTo("Podcast")
    }
}
