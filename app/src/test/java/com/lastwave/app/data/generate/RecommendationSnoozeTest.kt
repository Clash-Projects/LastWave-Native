package com.lastwave.app.data.generate

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.local.db.RecommendationExclusionDao
import com.lastwave.app.data.local.db.RecommendationSnoozeDao
import com.lastwave.app.data.local.db.RecommendationSnoozeEntity
import com.lastwave.app.data.local.db.SnoozeDuration
import com.lastwave.app.data.local.db.SongPlayStatsDao
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.network.LastFmApiService
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.recommend.RecommendationRepository
import com.lastwave.app.data.repository.ViewingProfileState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RecommendationSnoozeTest {

    private fun repository(
        exclusionDao: RecommendationExclusionDao,
        snoozeDao: RecommendationSnoozeDao,
    ): GenerateRepository {
        val playlists = mockk<PlaylistRepository>(relaxed = true)
        every { playlists.changes } returns MutableSharedFlow()
        return GenerateRepository(
            api = mockk<LastFmApiService>(relaxed = true),
            sessionPreferences = mockk<SessionPreferences>(relaxed = true),
            recommendationExclusionDao = exclusionDao,
            recommendationSnoozeDao = snoozeDao,
            tasteProfileProvider = mockk(relaxed = true),
            playlistRepository = playlists,
            viewingProfileState = mockk<ViewingProfileState>(relaxed = true),
            innerTube = mockk<InnerTubeMusicApi>(relaxed = true),
            recommendationRepository = mockk<RecommendationRepository>(relaxed = true),
            songPlayStatsDao = mockk<SongPlayStatsDao>(relaxed = true),
            downloadedTrackDao = mockk<DownloadedTrackDao>(relaxed = true),
        )
    }

    @Test
    fun `snoozed track is hidden but its artist stays allowed`() = runTest {
        val exclusionDao = mockk<RecommendationExclusionDao>(relaxed = true)
        val snoozeDao = mockk<RecommendationSnoozeDao>(relaxed = true)
        val stored = slot<RecommendationSnoozeEntity>()
        coEvery { snoozeDao.upsert(capture(stored)) } returns Unit
        coEvery { snoozeDao.getActive(any()) } answers { listOf(stored.captured) }
        val repository = repository(exclusionDao, snoozeDao)

        assertThat(repository.snoozeTrack("Overplayed", "Artist A", SnoozeDuration.ONE_WEEK)).isTrue()
        val allowed = repository.filterRecommendationExclusions(
            listOf(
                GeneratedTrack("Overplayed", "Artist A"),
                GeneratedTrack("Fresh", "Artist A"),
            ),
        )

        assertThat(allowed.map { it.name }).containsExactly("Fresh")
    }

    @Test
    fun `snoozed artist hides every track including collaborations`() = runTest {
        val exclusionDao = mockk<RecommendationExclusionDao>(relaxed = true)
        val snoozeDao = mockk<RecommendationSnoozeDao>(relaxed = true)
        val stored = slot<RecommendationSnoozeEntity>()
        coEvery { snoozeDao.upsert(capture(stored)) } returns Unit
        coEvery { snoozeDao.getActive(any()) } answers { listOf(stored.captured) }
        val repository = repository(exclusionDao, snoozeDao)

        assertThat(repository.snoozeArtist("Artist A", SnoozeDuration.ONE_MONTH)).isTrue()
        val allowed = repository.filterRecommendationExclusions(
            listOf(
                GeneratedTrack("Solo", "Artist A"),
                GeneratedTrack("Collab", "Artist A, Artist B"),
                GeneratedTrack("Other", "Artist B"),
            ),
        )

        assertThat(allowed.map { it.name }).containsExactly("Other")
    }

    @Test
    fun `snoozed composite artist hides its collaborations`() = runTest {
        val exclusionDao = mockk<RecommendationExclusionDao>(relaxed = true)
        val snoozeDao = mockk<RecommendationSnoozeDao>(relaxed = true)
        val stored = slot<RecommendationSnoozeEntity>()
        coEvery { snoozeDao.upsert(capture(stored)) } returns Unit
        coEvery { snoozeDao.getActive(any()) } answers { listOf(stored.captured) }
        val repository = repository(exclusionDao, snoozeDao)

        assertThat(repository.snoozeArtist("Artist A, Artist B", SnoozeDuration.ONE_WEEK)).isTrue()
        val allowed = repository.filterRecommendationExclusions(
            listOf(
                GeneratedTrack("Collab", "Artist A, Artist B"),
                GeneratedTrack("Solo", "Artist A"),
            ),
        )

        assertThat(allowed.map { it.name }).containsExactly("Solo")
    }

    @Test
    fun `expired snooze no longer hides anything`() = runTest {
        val exclusionDao = mockk<RecommendationExclusionDao>(relaxed = true)
        val snoozeDao = mockk<RecommendationSnoozeDao>(relaxed = true)
        coEvery { snoozeDao.getActive(any()) } returns listOf(
            RecommendationSnoozeEntity(
                targetType = RecommendationSnoozeEntity.TYPE_TRACK,
                targetKey = GeneratedTrack("Overplayed", "Artist A").key,
                displayName = "Overplayed",
                artistName = "Artist A",
                snoozedAtMillis = 0L,
                expiresAtMillis = 1L,
            ),
        )
        val repository = repository(exclusionDao, snoozeDao)

        val allowed = repository.filterRecommendationExclusions(
            listOf(GeneratedTrack("Overplayed", "Artist A")),
        )

        assertThat(allowed).hasSize(1)
    }

    @Test
    fun `playing a snoozed track wakes the track and each of its artists`() = runTest {
        val exclusionDao = mockk<RecommendationExclusionDao>(relaxed = true)
        val snoozeDao = mockk<RecommendationSnoozeDao>(relaxed = true)
        coEvery { snoozeDao.delete(any(), any()) } returns 1
        val repository = repository(exclusionDao, snoozeDao)

        repository.clearSnoozesForPlayedTrack("Song", "Artist A, Artist B")

        coVerify {
            snoozeDao.delete(
                RecommendationSnoozeEntity.TYPE_TRACK,
                GeneratedTrack("Song", "Artist A, Artist B").key,
            )
        }
        coVerify { snoozeDao.delete(RecommendationSnoozeEntity.TYPE_ARTIST, "artist a") }
        coVerify { snoozeDao.delete(RecommendationSnoozeEntity.TYPE_ARTIST, "artist b") }
        coVerify { snoozeDao.delete(RecommendationSnoozeEntity.TYPE_ARTIST, "artist a, artist b") }
    }
}
