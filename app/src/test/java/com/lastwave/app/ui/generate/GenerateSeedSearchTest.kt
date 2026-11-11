package com.lastwave.app.ui.generate

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.artwork.ArtworkRepository
import com.lastwave.app.data.generate.GenerateRepository
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.GenerationStatus
import com.lastwave.app.data.playlist.PlaylistRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GenerateSeedSearchTest {
    private val repository = mockk<GenerateRepository>()
    private lateinit var viewModel: GenerateViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = GenerateViewModel(repository, mockk<PlaylistRepository>(), mockk<ArtworkRepository>(), GenerationStatus(), MixLauncher())
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun slowerOldSearchCannotReplaceNewResults() {
        val oldResponse = CompletableDeferred<List<GeneratedTrack>>()
        val latest = GeneratedTrack(name = "New song", artist = "Artist")
        coEvery { repository.searchTracks("Old", null) } coAnswers { withContext(NonCancellable) { oldResponse.await() } }
        coEvery { repository.searchTracks("New", null) } returns listOf(latest)
        viewModel.setSeedTrackName("Old")
        viewModel.searchSeedTrack()
        viewModel.setSeedTrackName("New")
        viewModel.searchSeedTrack()
        oldResponse.complete(listOf(GeneratedTrack(name = "Old song", artist = "Artist")))
        assertThat(viewModel.uiState.value.seedTrackResults).containsExactly(latest)
        assertThat(viewModel.uiState.value.isSearchingSeed).isFalse()
    }

    @Test
    fun editingInputClearsPreviewAndRejectsDelayedArtists() {
        val response = CompletableDeferred<List<String>>()
        coEvery { repository.searchArtists("Old") } coAnswers { withContext(NonCancellable) { response.await() } }
        viewModel.setSeedArtistQuery("Old")
        viewModel.searchSeedArtist()
        viewModel.setSeedArtistQuery("")
        response.complete(listOf("Old artist"))
        assertThat(viewModel.uiState.value.seedArtistResults).isEmpty()
        assertThat(viewModel.uiState.value.isSearchingSeed).isFalse()
    }

    @Test
    fun changingModeRejectsPendingTopTracks() {
        val response = CompletableDeferred<List<GeneratedTrack>>()
        coEvery { repository.topTracksForSeed() } coAnswers { withContext(NonCancellable) { response.await() } }
        viewModel.selectMode(GenerateMode.SIMILAR_TRACKS)
        viewModel.loadTopTracksForSeed()
        viewModel.selectMode(GenerateMode.SIMILAR_ARTISTS)
        response.complete(listOf(GeneratedTrack(name = "Old song", artist = "Artist")))
        assertThat(viewModel.uiState.value.seedTrackResults).isEmpty()
        assertThat(viewModel.uiState.value.selectedMode).isEqualTo(GenerateMode.SIMILAR_ARTISTS)
    }

    @Test
    fun selectingArtistRejectsPendingTopArtists() {
        val response = CompletableDeferred<List<String>>()
        coEvery { repository.topArtistsForSeed() } coAnswers { withContext(NonCancellable) { response.await() } }
        viewModel.loadTopArtistsForSeed()
        viewModel.pickSeedArtist("Selected")
        response.complete(listOf("Old artist"))
        assertThat(viewModel.uiState.value.seedArtistQuery).isEqualTo("Selected")
        assertThat(viewModel.uiState.value.seedArtistResults).isEmpty()
    }
}
