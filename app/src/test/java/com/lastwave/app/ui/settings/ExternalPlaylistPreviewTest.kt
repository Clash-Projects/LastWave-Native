package com.lastwave.app.ui.settings

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.playlist.AppleMusicPlaylistImporter
import com.lastwave.app.data.playlist.ExternalPlaylistResult
import com.lastwave.app.data.playlist.ExternalPlaylistSource
import com.lastwave.app.data.playlist.ExternalTrackRow
import com.lastwave.app.data.playlist.PlaylistImportManager
import com.lastwave.app.data.playlist.SpotifyPlaylistImporter
import com.lastwave.app.data.spotify.SpotifyPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalPlaylistPreviewTest {
    private val dispatcher = StandardTestDispatcher()
    private val spotify = mockk<SpotifyPlaylistImporter>()
    private val apple = mockk<AppleMusicPlaylistImporter>()
    private val importManager = mockk<PlaylistImportManager>(relaxed = true)
    private val preferences = mockk<SpotifyPreferences>()
    private lateinit var viewModel: ExternalPlaylistImportViewModel
    private val linkA = "https://open.spotify.com/playlist/playlistA"
    private val linkB = "https://open.spotify.com/playlist/playlistB"
    private val previewA = preview("Playlist A")
    private val previewB = preview("Playlist B")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { preferences.isConnected } returns MutableStateFlow(false)
        viewModel = ExternalPlaylistImportViewModel(importManager, spotify, apple, preferences)
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun editingLoadedLinkRequiresANewPreviewBeforeImport() = runTest(dispatcher) {
        coEvery { spotify.fetchPlaylist(linkA) } returns previewA
        viewModel.onLinkChange(linkA)
        viewModel.loadPreview()
        runCurrent()
        assertThat(viewModel.uiState.value.preview).isEqualTo(previewA)

        viewModel.onLinkChange(linkB)
        viewModel.import { error("No unpreviewed playlist should be imported") }
        runCurrent()

        assertThat(viewModel.uiState.value.link).isEqualTo(linkB)
        assertThat(viewModel.uiState.value.preview).isNull()
        coVerify(exactly = 0) { importManager.importExternalPlaylist(any()) }
    }

    @Test
    fun oldResponseCannotReplaceNewPlaylistPreview() = runTest(dispatcher) {
        val oldResponse = CompletableDeferred<ExternalPlaylistResult>()
        // Also covers providers which finish work after cancellation.
        coEvery { spotify.fetchPlaylist(linkA) } coAnswers {
            withContext(NonCancellable) { oldResponse.await() }
        }
        coEvery { spotify.fetchPlaylist(linkB) } returns previewB
        viewModel.onLinkChange(linkA)
        viewModel.loadPreview()
        runCurrent()
        viewModel.onLinkChange(linkB)
        viewModel.loadPreview()
        runCurrent()
        oldResponse.complete(previewA)
        runCurrent()

        assertThat(viewModel.uiState.value.link).isEqualTo(linkB)
        assertThat(viewModel.uiState.value.preview).isEqualTo(previewB)
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    @Test
    fun clearingPendingLikedSongsDoesNotReopenPreview() = runTest(dispatcher) {
        val oldResponse = CompletableDeferred<ExternalPlaylistResult>()
        coEvery { spotify.fetchLikedSongs() } coAnswers {
            withContext(NonCancellable) { oldResponse.await() }
        }
        viewModel.selectLikedSongs()
        runCurrent()
        viewModel.clearPreview()
        oldResponse.complete(previewA)
        runCurrent()

        assertThat(viewModel.uiState.value.preview).isNull()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    private fun preview(title: String) = ExternalPlaylistResult(
        source = ExternalPlaylistSource.SPOTIFY,
        title = title,
        rows = listOf(ExternalTrackRow("Song", "Artist")),
    )
}
