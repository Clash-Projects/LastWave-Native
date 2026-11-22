package com.lastwave.app.ui.settings

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistResult
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.data.playlist.PlaylistImportManager
import com.lastwave.app.data.ytmusic.YtConnection
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import com.lastwave.app.data.ytmusic.YtMusicLibraryManager
import io.mockk.coEvery
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
class YouTubeImportRequestsTest {
    private val dispatcher = StandardTestDispatcher()
    private val innerTube = mockk<InnerTubeMusicApi>()
    private val importManager = mockk<PlaylistImportManager>(relaxed = true)
    private val auth = mockk<YtMusicAuthManager>()
    private val library = mockk<YtMusicLibraryManager>()
    private lateinit var viewModel: YouTubePlaylistImportViewModel
    private val preview = YouTubePlaylistResult(
        id = "PL_preview",
        title = "Preview",
        tracks = listOf(mockk<YouTubeMusicTrack>(relaxed = true)),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { auth.connection } returns MutableStateFlow(YtConnection.DISCONNECTED)
        every { library.accountPlaylists } returns MutableStateFlow(emptyList())
        every { innerTube.extractPlaylistId(any()) } returns ""
        coEvery { innerTube.searchPlaylists(any()) } returns emptyList()
        viewModel = YouTubePlaylistImportViewModel(mockk(relaxed = true), innerTube, importManager, auth, library)
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun olderSearchCannotOverwriteNewerResults() = runTest(dispatcher) {
        val oldResponse = CompletableDeferred<List<YouTubePlaylistSummary>>()
        coEvery { innerTube.searchPlaylists("Old") } coAnswers {
            withContext(NonCancellable) { oldResponse.await() }
        }
        val newer = listOf(YouTubePlaylistSummary("PL_new", "New playlist"))
        coEvery { innerTube.searchPlaylists("New") } returns newer
        viewModel.search("Old")
        runCurrent()
        viewModel.search("New")
        runCurrent()
        oldResponse.complete(listOf(YouTubePlaylistSummary("PL_old", "Old playlist")))
        runCurrent()

        assertThat(viewModel.uiState.value.searchResults).isEqualTo(newer)
        assertThat(viewModel.uiState.value.isSearching).isFalse()
        assertThat(viewModel.uiState.value.errorMessage).isNull()
    }

    @Test
    fun clearingQueryInvalidatesPendingSearch() = runTest(dispatcher) {
        val oldResponse = CompletableDeferred<List<YouTubePlaylistSummary>>()
        coEvery { innerTube.searchPlaylists("Old") } coAnswers {
            withContext(NonCancellable) { oldResponse.await() }
        }
        viewModel.onQueryChange("Old")
        viewModel.search()
        runCurrent()
        viewModel.onQueryChange("")
        oldResponse.complete(listOf(YouTubePlaylistSummary("PL_old", "Old playlist")))
        runCurrent()

        assertThat(viewModel.uiState.value.searchResults).isEmpty()
        assertThat(viewModel.uiState.value.isSearching).isFalse()
        assertThat(viewModel.uiState.value.errorMessage).isNull()
    }

    @Test
    fun changingLinkPreventsOldPreviewFromOpening() = runTest(dispatcher) {
        val oldResponse = CompletableDeferred<YouTubePlaylistResult>()
        coEvery { innerTube.fetchPlaylist(preview.id) } coAnswers {
            withContext(NonCancellable) { oldResponse.await() }
        }
        viewModel.onDirectLinkChange("Old link")
        viewModel.loadPreview(preview.id)
        runCurrent()
        viewModel.onDirectLinkChange("New link")
        oldResponse.complete(preview)
        runCurrent()

        assertThat(viewModel.uiState.value.previewPlaylist).isNull()
        assertThat(viewModel.uiState.value.isPreviewLoading).isFalse()
        assertThat(viewModel.uiState.value.errorMessage).isNull()
    }

    @Test
    fun leavingTabPreventsPendingPreviewFromOpeningThere() = runTest(dispatcher) {
        val oldResponse = CompletableDeferred<YouTubePlaylistResult>()
        coEvery { innerTube.fetchPlaylist(preview.id) } coAnswers {
            withContext(NonCancellable) { oldResponse.await() }
        }
        viewModel.loadPreview(preview.id)
        runCurrent()
        viewModel.selectTab(ImportTab.CSV)
        oldResponse.complete(preview)
        runCurrent()

        assertThat(viewModel.uiState.value.previewPlaylist).isNull()
        assertThat(viewModel.uiState.value.isPreviewLoading).isFalse()
    }
}
