package com.lastwave.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.local.SessionData
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.network.LastFmApiService
import com.lastwave.app.data.network.LastFmRateGuard
import com.lastwave.app.data.scrobbler.ExtraScrobblers
import com.lastwave.app.data.scrobbler.Listen
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ScrobbleRepositoryTest {
    private val session = MutableStateFlow(SessionData())
    private val preferences = mockk<SessionPreferences>().apply {
        every { session } returns this@ScrobbleRepositoryTest.session
    }
    private val api = mockk<LastFmApiService>()
    private val extras = mockk<ExtraScrobblers>(relaxUnitFun = true)
    private val repository = ScrobbleRepository(api, preferences, mockk<LastFmRateGuard>(), extras)

    @Test
    fun disconnectedLastFmStillDispatchesBothPlaybackEvents() = runTest {
        assertThat(repository.updateNowPlaying("Artist", "Song", "Album"))
            .isEqualTo(ScrobbleRepository.Result.NoSessionKey)
        assertThat(repository.scrobble("Artist", "Song", "Album", 123L))
            .isEqualTo(ScrobbleRepository.Result.NoSessionKey)

        verify(exactly = 1) { extras.nowPlaying(Listen("Artist", "Song", "Album")) }
        verify(exactly = 1) { extras.submit(Listen("Artist", "Song", "Album", 123L)) }
        coVerify(exactly = 0) { api.post(any()) }
    }

    @Test
    fun invalidLastFmCredentialsDoNotBlockOtherProviders() = runTest {
        session.value = SessionData(sessionKey = "lastfm-session")
        assertThat(repository.scrobble("Artist", "Song", null, 123L))
            .isEqualTo(ScrobbleRepository.Result.Failed("API credentials missing"))

        verify(exactly = 1) { extras.submit(Listen("Artist", "Song", null, 123L)) }
        coVerify(exactly = 0) { api.post(any()) }
    }

    @Test
    fun podcastMetadataIsExcludedFromEveryProvider() = runTest {
        repository.updateNowPlaying("Podcast", "Episode", null)
        repository.scrobble("Artist", "Podcast", null, 123L)

        verify(exactly = 0) { extras.nowPlaying(any()) }
        verify(exactly = 0) { extras.submit(any()) }
        coVerify(exactly = 0) { api.post(any()) }
    }
}
