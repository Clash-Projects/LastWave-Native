package com.lastwave.app.ui.genres

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.generate.GenerateRepository
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.GenresRepository
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.playback.MusicPlayer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GenresDetailRequestsTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<GenresViewModel>()
    private val repository = mockk<GenresRepository>(relaxed = true)
    private val rock = GeneratedTrack(name = "Rock song", artist = "Rock artist")
    private val jazz = GeneratedTrack(name = "Jazz song", artist = "Jazz artist")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.fetchGenreStats(any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun viewModel() = GenresViewModel(
        repository,
        mockk<GenerateRepository>(relaxed = true),
        mockk<PlaylistRepository>(relaxed = true),
        GenreExplorer(),
        mockk<MusicPlayer>(relaxed = true),
    ).also { viewModels += it }

    @Test
    fun oldGenreResponseCannotReplaceNewlyOpenedGenre() = runTest(dispatcher) {
        val oldResponse = CompletableDeferred<List<GeneratedTrack>>()
        coEvery { repository.fetchGenreTracks("rock", 1) } coAnswers {
            withContext(NonCancellable) { oldResponse.await() }
        }
        coEvery { repository.fetchGenreTracks("jazz", 1) } returns listOf(jazz)
        val vm = viewModel()
        vm.openDetail("rock")
        runCurrent()
        vm.closeDetail()
        vm.openDetail("jazz")
        runCurrent()
        oldResponse.complete(listOf(rock))
        runCurrent()
        assertThat(vm.uiState.value.detailGenre).isEqualTo("jazz")
        assertThat(vm.uiState.value.detailTracks).containsExactly(jazz)
        assertThat(vm.uiState.value.detailPage).isEqualTo(2)
    }

    @Test
    fun dismissedSheetIgnoresItsLateResponse() = runTest(dispatcher) {
        val response = CompletableDeferred<List<GeneratedTrack>>()
        coEvery { repository.fetchGenreTracks("rock", 1) } coAnswers {
            withContext(NonCancellable) { response.await() }
        }
        val vm = viewModel()
        vm.openDetail("rock")
        runCurrent()
        vm.closeDetail()
        response.complete(listOf(rock))
        runCurrent()
        assertThat(vm.uiState.value.detailGenre).isNull()
        assertThat(vm.uiState.value.detailTracks).isEmpty()
        assertThat(vm.uiState.value.detailLoading).isFalse()
    }

    @Test
    fun switchingBackToYourTracksIgnoresLateDiscovery() = runTest(dispatcher) {
        val discovery = CompletableDeferred<List<GeneratedTrack>>()
        coEvery { repository.fetchGenreTracks("rock", 1) } returns listOf(rock)
        coEvery { repository.discoverMore("rock") } coAnswers {
            withContext(NonCancellable) { discovery.await() }
        }
        val vm = viewModel()
        vm.openDetail("rock")
        runCurrent()
        vm.discoverMore("rock")
        runCurrent()
        vm.showYourTracks()
        runCurrent()
        discovery.complete(listOf(jazz))
        runCurrent()
        assertThat(vm.uiState.value.isDiscoverMode).isFalse()
        assertThat(vm.uiState.value.detailTracks).containsExactly(rock)
        assertThat(vm.uiState.value.detailHasMore).isTrue()
    }

    @Test
    fun repeatedLoadMoreBeforeDispatchIssuesOnlyOnePageRequest() = runTest(dispatcher) {
        coEvery { repository.fetchGenreTracks("rock", any()) } returns listOf(rock)
        val vm = viewModel()
        vm.openDetail("rock")
        runCurrent()
        vm.loadDetailPage()
        vm.loadDetailPage()
        runCurrent()
        coVerify(exactly = 1) { repository.fetchGenreTracks("rock", 2) }
        assertThat(vm.uiState.value.detailPage).isEqualTo(3)
    }
}
