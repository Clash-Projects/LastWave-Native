package com.lastwave.app.data.repository

import android.app.Application
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.local.db.AppDatabase
import com.lastwave.app.data.local.db.SongPlayStatsDao
import com.lastwave.app.data.local.db.SongPlayStatsEntity
import com.lastwave.app.util.formatListeningDuration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class ListeningInsightsRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SongPlayStatsDao
    private lateinit var repository: ListeningInsightsRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        dao = db.songPlayStatsDao()
        repository = ListeningInsightsRepository(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun emptyStatistics_returnsEmptyInsightsWithZeroSkipRate() {
        val insights = repository.buildInsights(emptyList())

        assertThat(insights.isEmpty).isTrue()
        assertThat(insights.totalListeningTimeMs).isEqualTo(0L)
        assertThat(insights.totalCompletedPlays).isEqualTo(0)
        assertThat(insights.totalSkips).isEqualTo(0)
        assertThat(insights.uniqueTracks).isEqualTo(0)
        assertThat(insights.uniqueArtists).isEqualTo(0)
        assertThat(insights.skipRate).isEqualTo(0f)
        assertThat(insights.topTracks).isEmpty()
        assertThat(insights.topArtists).isEmpty()
    }

    @Test
    fun oneTrack_computesAccurateTotalsAndSingleArtist() {
        val track = SongPlayStatsEntity(
            trackKey = "nude|radiohead",
            title = "Nude",
            artist = "Radiohead",
            videoId = "vid1",
            artworkUrl = "https://example.com/nude.jpg",
            totalPlayTimeMs = 255_000L,
            playCount = 1,
            skipCount = 0,
            lastPlayedAtMillis = 1_000L,
        )

        val insights = repository.buildInsights(listOf(track))

        assertThat(insights.isEmpty).isFalse()
        assertThat(insights.totalListeningTimeMs).isEqualTo(255_000L)
        assertThat(insights.totalCompletedPlays).isEqualTo(1)
        assertThat(insights.totalSkips).isEqualTo(0)
        assertThat(insights.uniqueTracks).isEqualTo(1)
        assertThat(insights.uniqueArtists).isEqualTo(1)
        assertThat(insights.skipRate).isEqualTo(0f)
        assertThat(insights.topTracks).containsExactly(
            TrackInsight(
                trackKey = "nude|radiohead",
                title = "Nude",
                artist = "Radiohead",
                artworkUrl = "https://example.com/nude.jpg",
                videoId = "vid1",
                playCount = 1,
                listeningTimeMs = 255_000L,
            ),
        )
        assertThat(insights.topArtists).containsExactly(
            ArtistInsight(
                name = "Radiohead",
                totalListeningTimeMs = 255_000L,
                totalPlays = 1,
                uniqueTracks = 1,
                artworkUrl = "https://example.com/nude.jpg",
            ),
        )
    }

    @Test
    fun multipleTracksSameArtist_aggregatesArtistCorrectlyCaseInsensitively() {
        val tracks = listOf(
            SongPlayStatsEntity(
                trackKey = "reckoner|radiohead",
                title = "Reckoner",
                artist = "Radiohead",
                artworkUrl = null,
                totalPlayTimeMs = 300_000L,
                playCount = 2,
                skipCount = 0,
            ),
            SongPlayStatsEntity(
                trackKey = "jigsaw|radiohead",
                title = "Jigsaw Falling Into Place",
                artist = "radiohead",
                artworkUrl = "https://example.com/inrainbows.jpg",
                totalPlayTimeMs = 500_000L,
                playCount = 3,
                skipCount = 1,
            ),
        )

        val insights = repository.buildInsights(tracks)

        assertThat(insights.totalListeningTimeMs).isEqualTo(800_000L)
        assertThat(insights.totalCompletedPlays).isEqualTo(5)
        assertThat(insights.totalSkips).isEqualTo(1)
        assertThat(insights.uniqueTracks).isEqualTo(2)
        assertThat(insights.uniqueArtists).isEqualTo(1)
        assertThat(insights.topArtists).hasSize(1)
        val artist = insights.topArtists.single()
        assertThat(artist.name).isEqualTo("radiohead")
        assertThat(artist.totalListeningTimeMs).isEqualTo(800_000L)
        assertThat(artist.totalPlays).isEqualTo(5)
        assertThat(artist.uniqueTracks).isEqualTo(2)
        assertThat(artist.artworkUrl).isEqualTo("https://example.com/inrainbows.jpg")
    }

    @Test
    fun multipleArtists_ranksByTotalListeningTimeAndCountsUniqueArtists() {
        val tracks = listOf(
            SongPlayStatsEntity(
                trackKey = "a1|artist a",
                title = "A1",
                artist = "Artist A",
                totalPlayTimeMs = 100_000L,
                playCount = 5,
            ),
            SongPlayStatsEntity(
                trackKey = "b1|artist b",
                title = "B1",
                artist = "Artist B",
                totalPlayTimeMs = 250_000L,
                playCount = 1,
            ),
            SongPlayStatsEntity(
                trackKey = "b2|artist b",
                title = "B2",
                artist = "Artist B",
                totalPlayTimeMs = 250_000L,
                playCount = 1,
            ),
            SongPlayStatsEntity(
                trackKey = "c1|artist c",
                title = "C1",
                artist = "Artist C",
                totalPlayTimeMs = 300_000L,
                playCount = 2,
            ),
        )

        val insights = repository.buildInsights(tracks)

        assertThat(insights.totalListeningTimeMs).isEqualTo(900_000L)
        assertThat(insights.uniqueTracks).isEqualTo(4)
        assertThat(insights.uniqueArtists).isEqualTo(3)
        // Artist B has 500s total across 2 tracks, C has 300s, A has 100s (despite 5 plays)
        assertThat(insights.topArtists.map { it.name })
            .containsExactly("Artist B", "Artist C", "Artist A")
            .inOrder()
        assertThat(insights.topArtists.first().totalListeningTimeMs).isEqualTo(500_000L)
    }

    @Test
    fun skipRateCalculation_handlesNormalAndZeroSessionCases() {
        // 2 skips out of (8 plays + 2 skips) = 0.2f
        val normal = listOf(
            SongPlayStatsEntity(
                trackKey = "s1|a",
                title = "S1",
                artist = "A",
                totalPlayTimeMs = 60_000L,
                playCount = 8,
                skipCount = 2,
            ),
        )
        assertThat(repository.buildInsights(normal).skipRate).isWithin(0.0001f).of(0.2f)

        // Zero plays and zero skips must never divide by zero
        val zeroSessions = listOf(
            SongPlayStatsEntity(
                trackKey = "s2|b",
                title = "S2",
                artist = "B",
                totalPlayTimeMs = 15_000L,
                playCount = 0,
                skipCount = 0,
            ),
        )
        val zeroInsights = repository.buildInsights(zeroSessions)
        assertThat(zeroInsights.totalCompletedPlays).isEqualTo(0)
        assertThat(zeroInsights.totalSkips).isEqualTo(0)
        assertThat(zeroInsights.skipRate).isEqualTo(0f)
        assertThat(zeroInsights.isEmpty).isFalse()
    }

    @Test
    fun topLists_capAtFiveWhileTotalsCoverAllEntries() {
        val tracks = (1..7).map { i ->
            SongPlayStatsEntity(
                trackKey = "track$i|artist$i",
                title = "Track $i",
                artist = "Artist $i",
                totalPlayTimeMs = i * 10_000L,
                playCount = i,
            )
        }

        val insights = repository.buildInsights(tracks)

        assertThat(insights.uniqueTracks).isEqualTo(7)
        assertThat(insights.uniqueArtists).isEqualTo(7)
        assertThat(insights.topTracks).hasSize(5)
        assertThat(insights.topArtists).hasSize(5)
        assertThat(insights.topTracks.first().title).isEqualTo("Track 7")
        assertThat(insights.topArtists.first().name).isEqualTo("Artist 7")
    }

    @Test
    fun observeInsights_emitsUpdatesFromRoomDao() = runTest {
        val initial = repository.observeInsights().first()
        assertThat(initial.isEmpty).isTrue()

        dao.upsert(
            SongPlayStatsEntity(
                trackKey = "track|artist",
                title = "Track",
                artist = "Artist",
                totalPlayTimeMs = 120_000L,
                playCount = 2,
                skipCount = 0,
            ),
        )

        val updated = repository.observeInsights().first()
        assertThat(updated.isEmpty).isFalse()
        assertThat(updated.totalListeningTimeMs).isEqualTo(120_000L)
        assertThat(updated.totalCompletedPlays).isEqualTo(2)
        assertThat(updated.topArtists.single().name).isEqualTo("Artist")
    }

    @Test
    fun formatListeningDuration_formatsZeroSecondsMinutesAndHours() {
        assertThat(formatListeningDuration(-500L)).isEqualTo("0m")
        assertThat(formatListeningDuration(0L)).isEqualTo("0m")
        assertThat(formatListeningDuration(500L)).isEqualTo("< 1s")
        assertThat(formatListeningDuration(45_000L)).isEqualTo("45s")
        assertThat(formatListeningDuration(18 * 60_000L)).isEqualTo("18m")
        assertThat(formatListeningDuration(2 * 3_600_000L)).isEqualTo("2h")
        assertThat(formatListeningDuration((42 * 60L + 36L) * 60_000L)).isEqualTo("42h 36m")
    }
}

