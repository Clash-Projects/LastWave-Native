package com.lastwave.app.ui.feed.cards

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.music.HomeShelfItem
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistSummary
import org.junit.Test

/** Variant-selection unit tests: real fields -> card variant, safe fallbacks. */
class HomeCardVariantTest {

    private fun track(
        aspect: String? = null,
        caption: String? = null,
    ) = HomeShelfItem.Track(
        YouTubeMusicTrack("v1", "Song", "Artist"),
        aspectRatio = aspect,
        caption = caption,
    )

    private fun playlist(
        aspect: String? = null,
        caption: String? = null,
        collage: List<String> = emptyList(),
        songs: List<YouTubeMusicTrack> = emptyList(),
    ) = HomeShelfItem.Playlist(
        YouTubePlaylistSummary("PL1", "Mix"),
        aspectRatio = aspect,
        caption = caption,
        collageArtworkUrls = collage,
        previewSongs = songs,
    )

    @Test
    fun defaultsToSquare() {
        assertThat(selectCardVariant(track())).isEqualTo(HomeCardVariant.SQUARE)
        assertThat(selectCardVariant(playlist())).isEqualTo(HomeCardVariant.SQUARE)
    }

    @Test
    fun wideAspectSelectsWide() {
        val wide = "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_RECTANGLE_16_9"
        assertThat(selectCardVariant(track(aspect = wide))).isEqualTo(HomeCardVariant.WIDE)
        assertThat(selectCardVariant(playlist(aspect = wide))).isEqualTo(HomeCardVariant.WIDE)
    }

    @Test
    fun captionSelectsFeatured() {
        assertThat(selectCardVariant(track(caption = "Because you liked X")))
            .isEqualTo(HomeCardVariant.FEATURED)
    }

    @Test
    fun previewSongsOrCollageSelectsCommunity() {
        val song = YouTubeMusicTrack("s1", "T", "A")
        assertThat(selectCardVariant(playlist(songs = listOf(song))))
            .isEqualTo(HomeCardVariant.COMMUNITY)
        assertThat(selectCardVariant(playlist(collage = listOf("u1", "u2"))))
            .isEqualTo(HomeCardVariant.COMMUNITY)
    }

    @Test
    fun communityBeatsWideAndFeatured() {
        val song = YouTubeMusicTrack("s1", "T", "A")
        val item = playlist(
            aspect = "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_RECTANGLE_16_9",
            caption = "cap",
            songs = listOf(song),
        )
        assertThat(selectCardVariant(item)).isEqualTo(HomeCardVariant.COMMUNITY)
    }

    @Test
    fun isFeaturedShelfOnlyWhenAllFeatured() {
        assertThat(
            isFeaturedShelf(listOf(track(caption = "c"), track(caption = "d")))
        ).isTrue()
        assertThat(isFeaturedShelf(listOf(track(), track(caption = "c")))).isFalse()
        assertThat(isFeaturedShelf(emptyList())).isFalse()
    }

    @Test
    fun playableTracksRuleForPlayAll() {
        assertThat(shelfIsPlayableTracks(listOf(track(), track()))).isTrue()
        assertThat(shelfIsPlayableTracks(listOf(track(), playlist()))).isFalse()
        assertThat(shelfIsPlayableTracks(emptyList())).isFalse()
    }
}
