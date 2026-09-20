package com.lastwave.app.data.download

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistResult
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.ytmusic.YtMusicLibraryManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDownloadCoordinatorTest {
    private val playlistRepository = mockk<PlaylistRepository>(relaxed = true)
    private val libraryManager = mockk<YtMusicLibraryManager>(relaxed = true)
    private val innerTube = mockk<InnerTubeMusicApi>()
    private val trackDownloadManager = mockk<TrackDownloadManager>()
    private val downloadStates = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())

    @Before
    fun setUp() {
        every { innerTube.extractPlaylistId(any()) } answers { firstArg<String>().removePrefix("VL") }
        every { trackDownloadManager.downloads } returns downloadStates
        every { trackDownloadManager.isDownloading(any(), any()) } returns false
        coEvery { trackDownloadManager.isTrackDownloaded(any(), any()) } returns false
        every { trackDownloadManager.downloadTrack(any(), any(), any(), any(), any()) } returns true
    }

    @Test
    fun wholePlaylistQueuesEveryValidTrackInOrder() = runTest {
        val tracks = tracks(3)
        completeEveryTrack(tracks)
        coEvery { innerTube.fetchPlaylist("playlist", any(), any(), any()) } returns playlist(tracks)
        val coordinator = coordinator()

        coordinator.downloadRemotePlaylist("playlist", "Mix")
        advanceUntilIdle()

        tracks.forEach { track ->
            verify(exactly = 1) {
                trackDownloadManager.downloadTrack(track.title, track.artist, track.album, track.artworkUrl, null)
            }
        }
        assertThat(coordinator.states.value["remote:playlist"]?.completed).isEqualTo(3)
    }

    @Test
    fun alreadyDownloadedTracksAreSkipped() = runTest {
        val tracks = tracks(2)
        coEvery { trackDownloadManager.isTrackDownloaded(tracks[0].title, tracks[0].artist) } returns true
        completeEveryTrack(listOf(tracks[1]))
        coEvery { innerTube.fetchPlaylist("playlist", any(), any(), any()) } returns playlist(tracks)
        val coordinator = coordinator()

        coordinator.downloadRemotePlaylist("playlist", "Mix")
        advanceUntilIdle()

        verify(exactly = 0) { trackDownloadManager.downloadTrack(tracks[0].title, any(), any(), any(), any()) }
        assertThat(coordinator.states.value["remote:playlist"]?.skipped).isEqualTo(1)
    }

    @Test
    fun alreadyQueuedTracksAreNotDuplicated() = runTest {
        val tracks = tracks(2)
        every { trackDownloadManager.isDownloading(tracks[0].title, tracks[0].artist) } returns true
        completeEveryTrack(listOf(tracks[1]))
        coEvery { innerTube.fetchPlaylist("playlist", any(), any(), any()) } returns playlist(tracks)
        val coordinator = coordinator()

        coordinator.downloadRemotePlaylist("playlist", "Mix")
        advanceUntilIdle()

        verify(exactly = 0) { trackDownloadManager.downloadTrack(tracks[0].title, any(), any(), any(), any()) }
        assertThat(coordinator.states.value["remote:playlist"]?.skipped).isEqualTo(1)
    }

    @Test
    fun duplicateVideoIdsAreDeduplicated() = runTest {
        val original = track(1)
        val duplicate = original.copy(title = "Alternate metadata")
        completeEveryTrack(listOf(original))
        coEvery { innerTube.fetchPlaylist("playlist", any(), any(), any()) } returns playlist(listOf(original, duplicate))
        val coordinator = coordinator()

        coordinator.downloadRemotePlaylist("playlist", "Mix")
        advanceUntilIdle()

        verify(exactly = 1) { trackDownloadManager.downloadTrack(any(), any(), any(), any(), any()) }
        assertThat(coordinator.states.value["remote:playlist"]?.skipped).isEqualTo(1)
    }

    @Test
    fun failedTrackDoesNotStopRemainingTracksAndSummaryIsAccurate() = runTest {
        val tracks = tracks(2)
        downloadStates.value = mapOf(
            key(tracks[0]) to progress(tracks[0], error = "network"),
            key(tracks[1]) to progress(tracks[1], finished = true),
        )
        coEvery { innerTube.fetchPlaylist("playlist", any(), any(), any()) } returns playlist(tracks)
        val coordinator = coordinator()

        coordinator.downloadRemotePlaylist("playlist", "Mix")
        advanceUntilIdle()

        val state = coordinator.states.value["remote:playlist"]
        verify(exactly = 2) { trackDownloadManager.downloadTrack(any(), any(), any(), any(), any()) }
        assertThat(state?.stage).isEqualTo(PlaylistDownloadStage.PARTIAL)
        assertThat(state?.message).isEqualTo("1 downloaded • 1 failed")
    }

    @Test
    fun emptyPlaylistCompletesGracefully() = runTest {
        coEvery { innerTube.fetchPlaylist("playlist", any(), any(), any()) } returns playlist(emptyList())
        val coordinator = coordinator()

        coordinator.downloadRemotePlaylist("playlist", "Mix")
        advanceUntilIdle()

        assertThat(coordinator.states.value["remote:playlist"]?.message)
            .isEqualTo("No downloadable tracks in this playlist")
        verify(exactly = 0) { trackDownloadManager.downloadTrack(any(), any(), any(), any(), any()) }
    }

    @Test
    fun rapidDuplicateRequestsStartOnlyOneResolution() = runTest {
        val tracks = tracks(1)
        completeEveryTrack(tracks)
        coEvery { innerTube.fetchPlaylist("playlist", any(), any(), any()) } returns playlist(tracks)
        val coordinator = coordinator()

        coordinator.downloadRemotePlaylist("playlist", "Mix")
        coordinator.downloadRemotePlaylist("playlist", "Mix")
        advanceUntilIdle()

        io.mockk.coVerify(exactly = 1) { innerTube.fetchPlaylist("playlist", any(), any(), any()) }
        verify(exactly = 1) { trackDownloadManager.downloadTrack(any(), any(), any(), any(), any()) }
    }

    @Test
    fun completeLargePlaylistProcessesAllContinuationResults() = runTest {
        val tracks = tracks(101)
        coEvery { trackDownloadManager.isTrackDownloaded(any(), any()) } returns true
        coEvery { innerTube.fetchPlaylist("playlist", any(), any(), any()) } returns playlist(tracks, complete = true)
        val coordinator = coordinator()

        coordinator.downloadRemotePlaylist("playlist", "Large mix")
        advanceUntilIdle()

        val state = coordinator.states.value["remote:playlist"]
        assertThat(state?.total).isEqualTo(101)
        assertThat(state?.skipped).isEqualTo(101)
    }

    @Test
    fun incompleteContinuationIsRejectedInsteadOfSilentlyDownloadingPrefix() = runTest {
        coEvery { innerTube.fetchPlaylist("playlist", any(), any(), any()) } returns
            playlist(tracks(100), complete = false)
        val coordinator = coordinator()

        coordinator.downloadRemotePlaylist("playlist", "Large mix")
        advanceUntilIdle()

        val state = coordinator.states.value["remote:playlist"]
        assertThat(state?.stage).isEqualTo(PlaylistDownloadStage.FAILED)
        assertThat(state?.message).isEqualTo("Playlist could not be fully loaded")
        verify(exactly = 0) { trackDownloadManager.downloadTrack(any(), any(), any(), any(), any()) }
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator() = PlaylistDownloadCoordinator(
        playlistRepository = playlistRepository,
        ytMusicLibraryManager = libraryManager,
        innerTube = innerTube,
        trackDownloadManager = trackDownloadManager,
        applicationScope = this,
    )

    private fun completeEveryTrack(tracks: List<YouTubeMusicTrack>) {
        downloadStates.value = tracks.associate { track -> key(track) to progress(track, finished = true) }
    }

    private fun tracks(count: Int) = (1..count).map(::track)

    private fun track(index: Int) = YouTubeMusicTrack(
        videoId = "video-$index",
        title = "Track $index",
        artist = "Artist $index",
        album = "Album",
        artworkUrl = "https://example.com/$index.jpg",
    )

    private fun playlist(tracks: List<YouTubeMusicTrack>, complete: Boolean = true) = YouTubePlaylistResult(
        id = "playlist",
        title = "Mix",
        trackCount = tracks.size,
        tracks = tracks,
        isComplete = complete,
    )

    private fun key(track: YouTubeMusicTrack) = TrackDownloadManager.makeDownloadKey(track.title, track.artist)

    private fun progress(
        track: YouTubeMusicTrack,
        finished: Boolean = false,
        error: String? = null,
    ) = DownloadProgress(
        key = key(track),
        title = track.title,
        artist = track.artist,
        isFinished = finished,
        error = error,
    )
}
