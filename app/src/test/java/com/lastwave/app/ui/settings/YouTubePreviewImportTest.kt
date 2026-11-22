package com.lastwave.app.ui.settings

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistResult
import com.lastwave.app.data.playlist.PlaylistImportManager
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.data.ytmusic.YtConnection
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import com.lastwave.app.data.ytmusic.YtMusicLibraryManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
class YouTubePreviewImportTest {
    private val dispatcher = StandardTestDispatcher()
    private val innerTube = mockk<InnerTubeMusicApi>()
    private val importManager = mockk<PlaylistImportManager>(relaxed = true)
    private val auth = mockk<YtMusicAuthManager>()
    private val library = mockk<YtMusicLibraryManager>()
    private lateinit var viewModel: YouTubePlaylistImportViewModel
    private val playlist = YouTubePlaylistResult(
        id = "PL_preview",
        title = "Reviewed playlist",
        tracks = listOf(mockk<YouTubeMusicTrack>(relaxed = true)),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { auth.connection } returns MutableStateFlow(YtConnection.DISCONNECTED)
        every { library.accountPlaylists } returns MutableStateFlow(emptyList())
        every { innerTube.extractPlaylistId(any()) } returns ""
        coEvery { innerTube.searchPlaylists(any()) } returns emptyList()
        coEvery { innerTube.fetchPlaylist(playlist.id) } returns playlist
        viewModel = YouTubePlaylistImportViewModel(mockk(relaxed = true), innerTube, importManager, auth, library)
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun previewingAndDismissingDoesNotSelectPlaylistForBulkImport() = runTest(dispatcher) {
        viewModel.togglePlaylistSelection("PL_other")
        viewModel.loadPreview(playlist.id)
        runCurrent()
        assertThat(viewModel.uiState.value.previewPlaylist).isEqualTo(playlist)
        assertThat(viewModel.uiState.value.selectedPlaylistIds).containsExactly("PL_other")

        viewModel.dismissPreview()
        assertThat(viewModel.uiState.value.selectedPlaylistIds).containsExactly("PL_other")
    }

    @Test
    fun previewImportSavesOnlyReviewedPlaylistWithOtherSelectionsPresent() = runTest(dispatcher) {
        val saved = mockk<SavedPlaylist>()
        coEvery { importManager.importYouTubePlaylist(playlist) } returns saved
        viewModel.togglePlaylistSelection("PL_other")
        viewModel.loadPreview(playlist.id)
        runCurrent()
        var imported: List<SavedPlaylist>? = null

        viewModel.importPreview { imported = it }
        runCurrent()

        assertThat(imported).containsExactly(saved)
        assertThat(viewModel.uiState.value.selectedPlaylistIds).containsExactly("PL_other")
        coVerify(exactly = 1) { importManager.importYouTubePlaylist(playlist) }
        coVerify(exactly = 0) { innerTube.fetchPlaylist("PL_other") }
    }
}
