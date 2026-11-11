package com.lastwave.app.ui.genres

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.generate.GenreStat
import com.lastwave.app.data.generate.GenresRepository
import io.mockk.coEvery
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
class GenresStatsRequestsTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<GenresViewModel>()
    private val repository = mockk<GenresRepository>(relaxed = true)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun viewModel() = GenresViewModel(repository, mockk(relaxed = true),
        mockk(relaxed = true), GenreExplorer(), mockk(relaxed = true)).also { viewModels += it }

    @Test
    fun slowOverallResponseCannotReplaceSelectedWeek() = runTest(dispatcher) {
        val oldResponse = CompletableDeferred<List<GenreStat>>()
        coEvery { repository.fetchGenreStats("overall") } coAnswers {
            withContext(NonCancellable) { oldResponse.await() }
        }
        val week = GenreStat("jazz", 20, 1f)
        coEvery { repository.fetchGenreStats("7day") } returns listOf(week)
        val vm = viewModel()
        runCurrent()
        vm.setPeriod("7day")
        runCurrent()
        oldResponse.complete(listOf(GenreStat("rock", 100, 1f)))
        runCurrent()
        assertThat(vm.uiState.value.period).isEqualTo("7day")
        assertThat(vm.uiState.value.stats).containsExactly(week)
        assertThat(vm.uiState.value.isLoading).isFalse()
    }

    @Test
    fun lateFailureCannotSetErrorForNewSelectedPeriod() = runTest(dispatcher) {
        val oldResponse = CompletableDeferred<Unit>()
        coEvery { repository.fetchGenreStats("overall") } coAnswers {
            withContext(NonCancellable) { oldResponse.await() }
            throw IllegalStateException("Old period failed")
        }
        coEvery { repository.fetchGenreStats("7day") } returns listOf(GenreStat("jazz", 20, 1f))
        val vm = viewModel()
        runCurrent()
        vm.setPeriod("7day")
        runCurrent()
        oldResponse.complete(Unit)
        runCurrent()
        assertThat(vm.uiState.value.error).isNull()
        assertThat(vm.uiState.value.stats.first().name).isEqualTo("jazz")
    }
}
