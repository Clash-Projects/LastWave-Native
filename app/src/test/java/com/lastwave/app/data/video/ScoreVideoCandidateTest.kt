package com.lastwave.app.data.video

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.music.videoCodecMime
import com.lastwave.app.data.music.videoCodecPreference
import org.junit.Test

class ScoreVideoCandidateTest {
    private fun score(
        title: String,
        channel: String,
        wantedTitle: String = "Starboy",
        wantedArtist: String = "The Weeknd",
        durationSec: Int? = 230,
    ) = scoreVideoCandidate(title, channel, durationSec, wantedTitle, wantedArtist, 230_000L)

    @Test
    fun acceptsTheOfficialVideo() {
        assertThat(score("The Weeknd - Starboy ft. Daft Punk (Official Video)", "TheWeekndVEVO"))
            .isAtLeast(MIN_VIDEO_CANDIDATE_SCORE)
    }

    @Test
    fun officialVideoOfAnotherSongIsRejected() {
        assertThat(score("The Weeknd - Blinding Lights (Official Video)", "TheWeekndVEVO")).isNull()
    }

    @Test
    fun sameTitleByAnotherArtistIsRejected() {
        assertThat(score("Starboy (Official Video)", "Someone Else")).isNull()
    }

    @Test
    fun gameplayUsingTheSongIsRejectedEvenWhenItNamesTheArtist() {
        assertThat(score("Starboy The Weeknd BGMI Montage", "ProPlayer")).isNull()
        assertThat(score("The Weeknd - Starboy (Official Video) BGMI gameplay", "ProPlayer")).isNull()
        assertThat(score("The Weeknd Starboy", "Ace Gaming")).isNull()
    }

    @Test
    fun randomUploadNamingTheArtistDoesNotQualify() {
        assertThat(score("The Weeknd Starboy", "ProPlayer")).isNull()
    }

    @Test
    fun labelChannelVideoSongQualifies() {
        val score = score(
            "Tum Hi Ho Full Video Song | Aashiqui 2 | Arijit Singh",
            "T-Series",
            wantedTitle = "Tum Hi Ho",
            wantedArtist = "Arijit Singh",
        )
        assertThat(score).isAtLeast(MIN_VIDEO_CANDIDATE_SCORE)
    }

    @Test
    fun labelUploadWithoutTheArtistsNameQualifies() {
        val score = score(
            "Kasturi | Amar Prem Ki Prem Kahani",
            "Tips",
            wantedTitle = "Kasturi",
            wantedArtist = "Asha Singer",
        )
        assertThat(score).isAtLeast(MIN_VIDEO_CANDIDATE_SCORE)
    }

    @Test
    fun plainAudioUploadDoesNotQualify() {
        assertThat(score("The Weeknd - Starboy (Official Audio)", "The Weeknd", durationSec = null))
            .isLessThan(MIN_VIDEO_CANDIDATE_SCORE)
    }

    @Test
    fun liveIsMatchedAsAWordNotInsideOtherWords() {
        val alive = score("Alive (Official Video)", "Sia", wantedTitle = "Alive", wantedArtist = "Sia")
        val live = score("Alive Live (Official Video)", "Sia", wantedTitle = "Alive", wantedArtist = "Sia")
        assertThat(alive).isAtLeast(MIN_VIDEO_CANDIDATE_SCORE)
        // A live take is a different recording, not this song's video.
        assertThat(live).isNull()
    }

    @Test
    fun wrongLengthUploadWithoutOfficialMarkerDoesNotQualify() {
        assertThat(score("The Weeknd - Starboy", "The Weeknd", durationSec = 600)).isNull()
    }

    @Test
    fun nonLatinTitlesMatch() {
        assertThat(score("तुम ही हो (Official Video)", "T-Series", wantedTitle = "तुम ही हो", wantedArtist = "T-Series"))
            .isAtLeast(MIN_VIDEO_CANDIDATE_SCORE)
    }

    @Test
    fun h264BeatsAv1AtTheSameResolution() {
        assertThat(videoCodecPreference("avc1.640028")).isGreaterThan(videoCodecPreference("av01.0.08M.08"))
        assertThat(videoCodecPreference("vp9")).isGreaterThan(videoCodecPreference("av01.0.08M.08"))
    }

    @Test
    fun codecStringsMapToDecoderTypes() {
        assertThat(videoCodecMime("avc1.42001E, mp4a.40.2")).isEqualTo("video/avc")
        assertThat(videoCodecMime("av01.0.08M.08")).isEqualTo("video/av01")
        assertThat(videoCodecMime(null)).isNull()
    }

    @Test
    fun differentTakeIsNeverTheVideoEvenFromTheArtist() {
        assertThat(score("The Weeknd - Starboy (Remix) [Official Video]", "TheWeekndVEVO")).isNull()
        assertThat(score("The Weeknd - Starboy (Sped Up)", "The Weeknd")).isNull()
    }
}
