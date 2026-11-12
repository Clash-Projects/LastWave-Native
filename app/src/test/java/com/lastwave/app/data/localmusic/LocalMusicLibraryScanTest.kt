package com.lastwave.app.data.localmusic

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.SavedPlaylist
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = android.app.Application::class)
class LocalMusicLibraryScanTest {
    private val music = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
    private val other = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AAlbums")
    private val resolver = mockk<ContentResolver>()
    private val context = mockk<Context>()
    private val repository = mockk<PlaylistRepository>()
    private val offline = mockk<LocalOfflineRepository>(relaxed = true)
    private var playlist = SavedPlaylist(1L, "Local songs", "", "local", emptyList(), 0L)
    private val files = mutableMapOf<String, List<Pair<String, String>>>()

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        application.getSharedPreferences("local_music", Context.MODE_PRIVATE).edit()
            .clear().putBoolean("auto_scan_enabled", false).commit()
        every { context.filesDir } returns application.filesDir
        every { context.contentResolver } returns resolver
        every { context.getSharedPreferences(any(), any()) } answers {
            application.getSharedPreferences(firstArg(), secondArg())
        }
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED
        every { resolver.takePersistableUriPermission(any(), any()) } just Runs
        every { resolver.releasePersistableUriPermission(any(), any()) } just Runs
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val uri = firstArg<Uri>()
            val children = files[DocumentsContract.getDocumentId(uri)] ?: throw SecurityException("Unavailable folder")
            MatrixCursor(arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )).apply {
                children.forEach { (id, name) -> addRow(arrayOf<Any>(id, name, "audio/mpeg", 1234L)) }
            }
        }
        coEvery { repository.ensureLocalSongs() } answers { playlist }
        coEvery { repository.mergeLocalSongs(any(), any(), any()) } answers {
            val updates = firstArg<Map<String, GeneratedTrack>>()
            val additions = secondArg<List<GeneratedTrack>>()
            val removals = thirdArg<Set<String>>()
            playlist = playlist.copy(tracks = playlist.tracks.filterNot { it.url in removals }
                .map { updates[it.url] ?: it } + additions)
            playlist
        }
    }

    @Test
    fun selectedFolderReplacesDeviceIndexAndNeverQueriesMediaStore() = runBlocking<Unit> {
        val selectedSong = track(music, "primary:Music/Song.mp3")
        val outsideSong = track(other, "primary:Albums/Other.mp3")
        playlist = playlist.copy(tracks = listOf(selectedSong, outsideSong))
        files["primary:Music"] = listOf("primary:Music/Song.mp3" to "Song.mp3")
        val library = LocalMusicLibrary(context, repository, offline)
        library.setAutoScanEnabled(true)
        awaitScan(library)
        library.acknowledgeReport()

        library.addFolderAndImport(music)
        awaitScan(library)

        assertThat(playlist.tracks).containsExactly(selectedSong)
        verify(exactly = 0) { resolver.query(match { it.authority == "media" }, any(), any(), any(), any()) }
        coVerify(exactly = 0) { offline.removeTracks(any()) }
    }

    @Test
    fun multipleSelectedFoldersStayIndexedAndForgettingOneRemovesOnlyItsSongs() = runBlocking<Unit> {
        val song = track(music, "primary:Music/Song.mp3")
        val second = track(other, "primary:Albums/Other.mp3")
        playlist = playlist.copy(tracks = listOf(song, second))
        files["primary:Music"] = listOf("primary:Music/Song.mp3" to "Song.mp3")
        files["primary:Albums"] = listOf("primary:Albums/Other.mp3" to "Other.mp3")
        val prefs = context.getSharedPreferences("local_music", Context.MODE_PRIVATE)
        prefs.edit().putString("folders", "$music\n$other").commit()
        val library = LocalMusicLibrary(context, repository, offline)
        awaitScan(library)
        assertThat(playlist.tracks).containsExactly(song, second)
        library.acknowledgeReport()

        library.forgetFolder(music)
        awaitScan(library)

        assertThat(playlist.tracks).containsExactly(second)
        coVerify(exactly = 0) { offline.removeTracks(any()) }
    }

    @Test
    fun unavailableSelectedSourceKeepsItsPriorSongsButRemovesUnselectedSources() = runBlocking<Unit> {
        val selectedSong = track(music, "primary:Music/Song.mp3")
        val outsideSong = track(other, "primary:Albums/Other.mp3")
        playlist = playlist.copy(tracks = listOf(selectedSong, outsideSong))
        val library = LocalMusicLibrary(context, repository, offline)

        library.addFolderAndImport(music)
        val report = awaitScan(library)

        assertThat(report.unreachableFolders).isEqualTo(1)
        assertThat(playlist.tracks).containsExactly(selectedSong)
    }

    @Test
    fun safRescanRemovesExistingVoiceNotesAndShortClips() = runBlocking<Unit> {
        val song = track(music, "primary:Music/Song.mp3")
        val voice = track(music, "primary:Music/PTT-20261112-WA0001.opus")
        val short = track(music, "primary:Music/clip.mp3").copy(durationMs = 5_000L)
        playlist = playlist.copy(tracks = listOf(song, voice, short))
        files["primary:Music"] = listOf(
            "primary:Music/Song.mp3" to "Song.mp3",
            "primary:Music/PTT-20261112-WA0001.opus" to "PTT-20261112-WA0001.opus",
            "primary:Music/clip.mp3" to "clip.mp3",
        )
        every { resolver.openAssetFileDescriptor(any(), any()) } throws SecurityException("No direct file access")
        val library = LocalMusicLibrary(context, repository, offline)

        library.addFolderAndImport(music)
        awaitScan(library)

        assertThat(playlist.tracks).containsExactly(song)
    }

    private fun track(tree: Uri, documentId: String) = GeneratedTrack(
        name = documentId.substringAfterLast('/'), artist = "Artist", durationMs = 180_000L,
        url = DocumentsContract.buildDocumentUriUsingTree(tree, documentId).toString(),
    )

    private suspend fun awaitScan(library: LocalMusicLibrary): LocalMusicLibrary.ImportReport =
        withTimeout(5_000L) {
            (library.state.first { it is LocalMusicLibrary.ImportState.Finished } as LocalMusicLibrary.ImportState.Finished).report
        }
}
