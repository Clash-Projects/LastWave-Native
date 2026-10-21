package com.lastwave.app.data.podcast

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.music.InnerTubeMusicApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * The single Podcasts toggle is the gate for every podcast surface: with it
 * off the repository must not even ask YouTube Music for content, and with it
 * on the fetched shelf must reach the caller untouched.
 */
class PodcastRepositoryTest {

    private val innerTube: InnerTubeMusicApi = mockk(relaxed = true)
    private val progressStore: PodcastProgressStore = mockk(relaxed = true)
    private val settings = MutableStateFlow(MiscSettings())

    private val settingsPreferences: SettingsPreferences = mockk(relaxed = true)

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun repository(): PodcastRepository {
        every { settingsPreferences.settings } returns settings
        return PodcastRepository(innerTube, settingsPreferences, progressStore)
    }

    @Test
    fun home_whenPodcastsDisabled_returnsEmptyAndNeverCallsYouTube() = runTest {
        settings.value = MiscSettings(podcastsEnabled = false)
        coEvery { innerTube.fetchPodcastHome() } returns emptyList()

        val shelves = repository().home()

        assertThat(shelves).isEmpty()
        coVerify(exactly = 0) { innerTube.fetchPodcastHome() }
    }

    @Test
    fun home_whenPodcastsEnabled_returnsTheFetchedShelf() = runTest {
        settings.value = MiscSettings(podcastsEnabled = true)
        val show = PodcastShow(showId = "MPSPPLabc", title = "Waveform Weekly")
        coEvery { innerTube.fetchPodcastHome() } returns listOf(PodcastShelf(title = "Popular shows", shows = listOf(show)))

        val shelves = repository().home()

        assertThat(shelves).hasSize(1)
        assertThat(shelves.first().shows.first().showId).isEqualTo("MPSPPLabc")
        coVerify(exactly = 1) { innerTube.fetchPodcastHome() }
    }

    @Test
    fun show_whenPodcastsDisabled_returnsNullAndNeverCallsYouTube() = runTest {
        settings.value = MiscSettings(podcastsEnabled = false)

        val page = repository().show("MPSPPLabc")

        assertThat(page).isNull()
        coVerify(exactly = 0) { innerTube.fetchPodcastShow(any()) }
    }

    @Test
    fun searchEpisodes_whenPodcastsDisabled_returnsEmptyAndNeverCallsYouTube() = runTest {
        settings.value = MiscSettings(podcastsEnabled = false)

        val episodes = repository().searchEpisodes("rust")

        assertThat(episodes).isEmpty()
        coVerify(exactly = 0) { innerTube.searchPodcastEpisodes(any()) }
    }

    @Test
    fun moreEpisodes_whenPodcastsDisabled_returnsEmptyPage() = runTest {
        settings.value = MiscSettings(podcastsEnabled = false)

        val page = repository().moreEpisodes("MPSPPLabc", "token")

        assertThat(page.episodes).isEmpty()
        coVerify(exactly = 0) { innerTube.fetchPodcastShowContinuation(any(), any()) }
    }
}
