package com.lastwave.app.playback

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The OriginOS music card only knows three loop modes and only notices a new
 * track by its media id; each test pins one rule of that translation.
 */
class OriginOsMusicCardTest {

    private val song = PlayableTrack(title = "Song", artist = "Artist", videoId = "abcdefghijk")

    // --- loop mode ---------------------------------------------------------------

    @Test
    fun shuffleShowsAsRandomWhateverTheRepeatMode() {
        assertThat(OriginOsMusicCard.loopModeOf(shuffleEnabled = true, repeatMode = Player.REPEAT_MODE_ONE))
            .isEqualTo(OriginOsMusicCard.LOOP_MODE_RANDOM)
    }

    @Test
    fun repeatOneShowsAsSingle() {
        assertThat(OriginOsMusicCard.loopModeOf(shuffleEnabled = false, repeatMode = Player.REPEAT_MODE_ONE))
            .isEqualTo(OriginOsMusicCard.LOOP_MODE_SINGLE)
    }

    @Test
    fun repeatAllAndRepeatOffShowAsList() {
        assertThat(OriginOsMusicCard.loopModeOf(shuffleEnabled = false, repeatMode = Player.REPEAT_MODE_ALL))
            .isEqualTo(OriginOsMusicCard.LOOP_MODE_LIST)
        assertThat(OriginOsMusicCard.loopModeOf(shuffleEnabled = false, repeatMode = Player.REPEAT_MODE_OFF))
            .isEqualTo(OriginOsMusicCard.LOOP_MODE_LIST)
    }

    @Test
    fun everyLoopModeOfTheCardRoundTrips() {
        listOf(
            OriginOsMusicCard.LOOP_MODE_LIST,
            OriginOsMusicCard.LOOP_MODE_SINGLE,
            OriginOsMusicCard.LOOP_MODE_RANDOM,
        ).forEach { loopMode ->
            val (shuffleEnabled, repeatMode) = OriginOsMusicCard.playModesOf(loopMode)!!
            assertThat(OriginOsMusicCard.loopModeOf(shuffleEnabled, repeatMode)).isEqualTo(loopMode)
        }
    }

    @Test
    fun anUnknownLoopModeChangesNothing() {
        assertThat(OriginOsMusicCard.playModesOf(0)).isNull()
        assertThat(OriginOsMusicCard.playModesOf(4)).isNull()
    }

    // --- media ids ---------------------------------------------------------------

    @Test
    fun aNewQueueStartingAtTheSameIndexStillChangesTheMediaId() {
        val other = song.copy(title = "Other song", videoId = "kjihgfedcba")
        assertThat(OriginOsMusicCard.mediaIdOf(0, song)).isNotEqualTo(OriginOsMusicCard.mediaIdOf(0, other))
    }

    @Test
    fun aMediaIdOfTheCardPlaysTheQueueEntryAtItsIndex() {
        assertThat(OriginOsMusicCard.queueMediaIdOf(OriginOsMusicCard.mediaIdOf(12, song))).isEqualTo("queue:12")
    }

    @Test
    fun otherMediaIdsArePlayedAsTheyAre() {
        assertThat(OriginOsMusicCard.queueMediaIdOf("queue:3")).isEqualTo("queue:3")
        assertThat(OriginOsMusicCard.queueMediaIdOf("playlist:42")).isEqualTo("playlist:42")
        assertThat(OriginOsMusicCard.queueMediaIdOf("download:7")).isEqualTo("download:7")
    }

    // --- lyrics ------------------------------------------------------------------

    @Test
    fun syncedLinesBecomeWholeSongLrc() {
        val lines = listOf(
            com.lastwave.app.data.lyrics.LyricLine(timeMs = 5_230L, text = "First line"),
            com.lastwave.app.data.lyrics.LyricLine(timeMs = 65_004L, text = "Second\nline"),
        )
        assertThat(OriginOsMusicCard.lrcOf(lines))
            .isEqualTo("[00:05.23]First line\n[01:05.00]Second line")
    }

    @Test
    fun unsyncedLyricsAreNotSentAsLrc() {
        val plain = listOf(
            com.lastwave.app.data.lyrics.LyricLine(timeMs = 0L, text = "a"),
            com.lastwave.app.data.lyrics.LyricLine(timeMs = 0L, text = "b"),
        )
        assertThat(OriginOsMusicCard.lrcOf(plain)).isNull()
        assertThat(OriginOsMusicCard.lrcOf(emptyList())).isNull()
    }

    @Test
    fun cardLyricsOnlyOnVivoAndIqoo() {
        assertThat(OriginOsMusicCard.isSupportedDevice("vivo")).isTrue()
        assertThat(OriginOsMusicCard.isSupportedDevice("iQOO")).isTrue()
        assertThat(OriginOsMusicCard.isSupportedDevice("samsung")).isFalse()
    }
}
