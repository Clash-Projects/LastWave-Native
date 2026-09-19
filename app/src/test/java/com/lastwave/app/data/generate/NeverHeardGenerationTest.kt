package com.lastwave.app.data.generate

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.local.SessionData
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import com.lastwave.app.data.local.db.RecommendationExclusionDao
import com.lastwave.app.data.local.db.RecommendationExclusionEntity
import com.lastwave.app.data.local.db.SongPlayStatsDao
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.naming.PlaylistNamer
import com.lastwave.app.data.network.LastFmApiService
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.data.repository.ViewingProfileState
import com.lastwave.app.ui.generate.GenerateMode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NeverHeardGenerationTest {

    @Test
    fun playlistNamer_supportsNeverHeardSubtitle() {
        val subtitle = PlaylistNamer.subtitleFor("never-heard")
        assertThat(subtitle).isEqualTo("Never-Heard Mix")
    }

    @Test
    fun generateMode_neverHeard_hasCorrectMetadata() {
        val mode = GenerateMode.NEVER_HEARD
        assertThat(mode.label).isEqualTo("Never Heard")
        assertThat(mode.storageValue).isEqualTo("never-heard")
        assertThat(mode.description).isNotEmpty()
    }

    @Test
    fun fetchNeverHeardTracks_strictlyExcludesAllHeardSourcesAndVariations() = runTest {
        val api: LastFmApiService = mockk(relaxed = true)
        val sessionPreferences: SessionPreferences = mockk(relaxed = true)
        val recommendationExclusionDao: RecommendationExclusionDao = mockk(relaxed = true)
        val tasteProfileProvider: TasteProfileProvider = mockk(relaxed = true)
        val playlistRepository: PlaylistRepository = mockk(relaxed = true)
        val viewingProfileState: ViewingProfileState = mockk(relaxed = true)
        val innerTube: InnerTubeMusicApi = mockk(relaxed = true)
        val songPlayStatsDao: SongPlayStatsDao = mockk(relaxed = true)
        val downloadedTrackDao: DownloadedTrackDao = mockk(relaxed = true)

        every { sessionPreferences.session } returns MutableStateFlow(
            SessionData(username = "testuser", apiKey = "")
        )
        every { viewingProfileState.viewingUsername } returns MutableStateFlow(null)

        // 1. Setup TasteProfile with heard songs across scrobbles, top tracks, YT Music recent and likes
        val heardRecent = GeneratedTrack(name = "Heard Recent Track", artist = "Artist One", url = "https://music.youtube.com/watch?v=11111111111")
        val heardTop = GeneratedTrack(name = "Heard Top Track", artist = "Artist Two", url = "https://music.youtube.com/watch?v=22222222222")
        val heardYtRecent = GeneratedTrack(name = "Heard YT Play", artist = "Artist Three", url = "https://music.youtube.com/watch?v=33333333333")
        val heardYtLiked = GeneratedTrack(name = "Heard YT Liked", artist = "Artist Four", url = "https://music.youtube.com/watch?v=44444444444")

        val profile = TasteProfile(
            topArtistNames = setOf("Artist One", "Artist Two"),
            recentArtists = setOf("Artist Three"),
            topTags = emptySet(),
            topTrackKeys = setOf(heardTop.key),
            recentTrackKeys = setOf(heardRecent.key),
            topTracksRaw = listOf(heardTop),
            recentTracksRaw = listOf(heardRecent),
            topArtistsRaw = listOf("Artist One", "Artist Two"),
            builtAtMillis = System.currentTimeMillis(),
            ytMusicRecentRaw = listOf(heardYtRecent),
            ytMusicLikedRaw = listOf(heardYtLiked),
            hasPersonalSignals = true,
        )
        coEvery { tasteProfileProvider.get() } returns profile

        // 2. Setup Saved Playlists in Room
        val heardSaved = GeneratedTrack(name = "Saved Library Track", artist = "Artist Five", url = "https://music.youtube.com/watch?v=55555555555")
        val savedPlaylist = SavedPlaylist(
            id = 1L,
            title = "My Playlist",
            subtitle = "My Subtitle",
            mode = "mix",
            tracks = listOf(heardSaved),
            createdAtMillis = System.currentTimeMillis(),
        )
        coEvery { playlistRepository.getAll() } returns listOf(savedPlaylist)
        every { playlistRepository.changes } returns MutableSharedFlow()

        // 3. Setup Local Play Stats in Room
        coEvery { songPlayStatsDao.getAllTrackKeys() } returns listOf("played local track|artist six")

        // 4. Setup Downloaded Offline Tracks in Room
        val downloadedTrack = DownloadedTrackEntity(
            id = 1L,
            trackKey = "downloaded track|artist seven",
            title = "Downloaded Track",
            artist = "Artist Seven",
            filePath = "/data/audio/track.m4a",
        )
        coEvery { downloadedTrackDao.getAllList() } returns listOf(downloadedTrack)

        // 5. Setup Recommendation Exclusions in Room
        coEvery { recommendationExclusionDao.getAll() } returns listOf(
            RecommendationExclusionEntity(
                trackKey = "excluded track|artist eight",
                excludedAtMillis = System.currentTimeMillis(),
            )
        )

        // 6. Setup Discovery candidates containing both heard songs (exact and variations) and brand new songs
        val freshDiscovery1 = YouTubeMusicTrack(
            videoId = "vid_fresh_1",
            title = "Fresh Discovery One",
            artist = "Artist One",
        )
        val freshDiscovery2 = YouTubeMusicTrack(
            videoId = "vid_fresh_2",
            title = "Fresh Discovery Two",
            artist = "New Artist Nine",
        )
        val freshDiscovery3 = YouTubeMusicTrack(
            videoId = "vid_fresh_3",
            title = "Fresh Discovery Three",
            artist = "New Artist Ten",
        )
        // Variation of heard track: remaster / featuring clause
        val heardRemasterVariation = YouTubeMusicTrack(
            videoId = "vid_var_1",
            title = "Heard Recent Track (Remastered 2024)",
            artist = "Artist One",
        )
        val exactHeardCandidate = YouTubeMusicTrack(
            videoId = "11111111111",
            title = "Heard Recent Track",
            artist = "Artist One",
        )
        val heardPlayedCandidate = YouTubeMusicTrack(
            videoId = "vid_played",
            title = "Played Local Track",
            artist = "Artist Six",
        )
        val heardDownloadedCandidate = YouTubeMusicTrack(
            videoId = "vid_dl",
            title = "Downloaded Track",
            artist = "Artist Seven",
        )
        val heardSavedCandidate = YouTubeMusicTrack(
            videoId = "55555555555",
            title = "Saved Library Track",
            artist = "Artist Five",
        )

        coEvery { innerTube.fetchRelatedSongs(any(), any(), any()) } returns listOf(
            exactHeardCandidate,
            heardRemasterVariation,
            heardPlayedCandidate,
            heardDownloadedCandidate,
            heardSavedCandidate,
            freshDiscovery1,
            freshDiscovery2,
            freshDiscovery3,
        )
        coEvery { innerTube.fetchHomeSongs() } returns emptyList()
        coEvery { innerTube.fetchCharts() } returns emptyList()

        val repository = GenerateRepository(
            api = api,
            sessionPreferences = sessionPreferences,
            recommendationExclusionDao = recommendationExclusionDao,
            tasteProfileProvider = tasteProfileProvider,
            playlistRepository = playlistRepository,
            viewingProfileState = viewingProfileState,
            innerTube = innerTube,
            songPlayStatsDao = songPlayStatsDao,
            downloadedTrackDao = downloadedTrackDao,
        )

        val results = repository.fetchNeverHeardTracks(total = 10)

        // Ensure fresh discoveries are included
        val titles = results.map { it.name }
        assertThat(titles).contains("Fresh Discovery One")
        assertThat(titles).contains("Fresh Discovery Two")
        assertThat(titles).contains("Fresh Discovery Three")

        // Ensure ALL heard tracks are strictly excluded across all platforms and variations
        assertThat(titles).doesNotContain("Heard Recent Track")
        assertThat(titles).doesNotContain("Heard Recent Track (Remastered 2024)")
        assertThat(titles).doesNotContain("Heard Top Track")
        assertThat(titles).doesNotContain("Heard YT Play")
        assertThat(titles).doesNotContain("Heard YT Liked")
        assertThat(titles).doesNotContain("Saved Library Track")
        assertThat(titles).doesNotContain("Played Local Track")
        assertThat(titles).doesNotContain("Downloaded Track")
        assertThat(titles).doesNotContain("Excluded Track")
    }
}
