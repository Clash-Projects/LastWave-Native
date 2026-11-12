package com.lastwave.app.ui.feed.cards

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.music.HomeShelfItem
import com.lastwave.app.data.music.HomeShelf
import com.lastwave.app.data.music.HomeCardData
import com.lastwave.app.data.music.HomePlayAllButton
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
    fun captionAloneDoesNotSelectFeatured() {
        assertThat(selectCardVariant(track(caption = "Because you liked X")))
            .isEqualTo(HomeCardVariant.SQUARE)
    }

    @Test
    fun previewSongsOrCollageAloneDoNotSelectCommunity() {
        val song = YouTubeMusicTrack("s1", "T", "A")
        assertThat(selectCardVariant(playlist(songs = listOf(song))))
            .isEqualTo(HomeCardVariant.SQUARE)
        assertThat(selectCardVariant(playlist(collage = listOf("u1", "u2"))))
            .isEqualTo(HomeCardVariant.SQUARE)
    }

    @Test
    fun wideStaysWideWithoutCommunityEndpointEvidence() {
        val song = YouTubeMusicTrack("s1", "T", "A")
        val item = playlist(
            aspect = "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_RECTANGLE_16_9",
            caption = "cap",
            songs = listOf(song),
        )
        assertThat(selectCardVariant(item)).isEqualTo(HomeCardVariant.WIDE)
    }

    @Test
    fun isFeaturedShelfRequiresSongRenderersAndHeaderEndpoint() {
        val item = track(aspect = "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_SQUARE")
            .copy(cardData = HomeCardData(isTwoRow = true, watchVideoId = "v1"))
        val shelf = HomeShelf("Any title", items = listOf(item), playAllButton = HomePlayAllButton("Play all", "v1"))
        assertThat(isFeaturedShelf(shelf)).isTrue()
        assertThat(selectCardVariant(item, shelf)).isEqualTo(HomeCardVariant.FEATURED)
        assertThat(selectCardVariant(item)).isEqualTo(HomeCardVariant.SQUARE)
        assertThat(isFeaturedShelf(shelf.copy(playAllButton = null))).isFalse()
        assertThat(isFeaturedShelf(shelf.copy(items = emptyList()))).isFalse()
    }

    @Test
    fun playableTracksRuleForPlayAll() {
        assertThat(shelfIsPlayableTracks(listOf(track(), track()))).isTrue()
        assertThat(shelfIsPlayableTracks(listOf(track(), playlist()))).isFalse()
        assertThat(shelfIsPlayableTracks(emptyList())).isFalse()
    }
}
