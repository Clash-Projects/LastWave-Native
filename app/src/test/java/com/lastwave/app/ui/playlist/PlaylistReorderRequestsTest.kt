package com.lastwave.app.ui.playlist

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.GenerationStatus
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.playlist.LOCAL_SONGS_MODE
import com.lastwave.app.data.playlist.PlaylistExportEvents
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.data.spotify.SpotifyPreferences
import com.lastwave.app.data.ytmusic.YtMusicLibraryManager
import com.lastwave.app.data.ytmusic.YtMusicPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistReorderRequestsTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<PlaylistRepository>(relaxed = true)
    private val ytPreferences = mockk<YtMusicPreferences>()
    private val library = mockk<YtMusicLibraryManager>()
    private val spotify = mockk<SpotifyPreferences>()
    private val settings = mockk<SettingsPreferences>()
    private val exportEvents = mockk<PlaylistExportEvents>()
    private lateinit var viewModel: PlaylistViewModel
    private val a = GeneratedTrack("A", "Artist")
    private val b = GeneratedTrack("B", "Artist")
    private val c = GeneratedTrack("C", "Artist")
    private val playlist = SavedPlaylist(1, "Playlist", "", "custom", listOf(a, b, c), 1)
    private val firstWrite = CompletableDeferred<SavedPlaylist>()
    private val secondWrite = CompletableDeferred<SavedPlaylist>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.getAll() } returns listOf(playlist, playlist.copy(id = 2, mode = LOCAL_SONGS_MODE))
        coEvery { repository.getById(playlist.id) } returns playlist
        coEvery { repository.moveTrack(playlist.id, 0, 1) } coAnswers { firstWrite.await() }
        coEvery { repository.moveTrack(playlist.id, 1, 2) } coAnswers { secondWrite.await() }
        every { repository.changes } returns MutableSharedFlow()
        every { ytPreferences.syncedPlaylistIds } returns MutableStateFlow(null)
        every { library.playlists } returns MutableStateFlow(emptyList())
        coEvery { library.refresh() } returns Unit
        every { spotify.isConnected } returns MutableStateFlow(false)
        every { settings.settings } returns MutableStateFlow(MiscSettings())
        every { exportEvents.failures } returns MutableSharedFlow()
        viewModel = PlaylistViewModel(
            repository, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), GenerationStatus(), exportEvents, ytPreferences,
            mockk(relaxed = true), library, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), spotify, settings,
        )
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun earlierSaveResponseDoesNotUndoLaterDragMove() = runTest(dispatcher) {
        runCurrent()
        viewModel.loadDetail(playlist.id)
        runCurrent()
        viewModel.moveTrack(playlist.id, 0, 1)
        runCurrent()
        viewModel.moveTrack(playlist.id, 1, 2)
        runCurrent()
        val latestOrder = listOf(b, c, a)
        assertThat(viewModel.uiState.value.detailPlaylist?.tracks).isEqualTo(latestOrder)

        firstWrite.complete(playlist.copy(tracks = listOf(b, a, c)))
        runCurrent()
        assertThat(viewModel.uiState.value.detailPlaylist?.tracks).isEqualTo(latestOrder)
        assertThat(viewModel.uiState.value.playlists.first { it.id == playlist.id }.tracks).isEqualTo(latestOrder)

        // The debounced repository-change refresh may still read an earlier persisted order.
        viewModel.load()
        runCurrent()
        assertThat(viewModel.uiState.value.detailPlaylist?.tracks).isEqualTo(latestOrder)
        assertThat(viewModel.uiState.value.playlists.first { it.id == playlist.id }.tracks).isEqualTo(latestOrder)

        secondWrite.complete(playlist.copy(tracks = latestOrder))
        runCurrent()
        assertThat(viewModel.uiState.value.detailPlaylist?.tracks).isEqualTo(latestOrder)
    }

    @Test
    fun leavingScreenDoesNotCancelAnAlreadyRequestedReorder() = runTest(dispatcher) {
        runCurrent()
        viewModel.loadDetail(playlist.id)
        runCurrent()
        viewModel.moveTrack(playlist.id, 0, 1)
        runCurrent()
        viewModel.viewModelScope.cancel()
        firstWrite.complete(playlist.copy(tracks = listOf(b, a, c)))
        runCurrent()

        coVerify(exactly = 1) { repository.moveTrack(playlist.id, 0, 1) }
        assertThat(viewModel.uiState.value.detailPlaylist?.tracks).containsExactly(b, a, c).inOrder()
    }
}
