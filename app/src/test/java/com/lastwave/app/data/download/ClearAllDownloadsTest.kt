package com.lastwave.app.data.download

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Collections

/**
 * Clear-all must delete only the files and content URIs recorded for each
 * download, after in-flight workers have been cancelled and joined.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ClearAllDownloadsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val stored = Collections.synchronizedList(mutableListOf<DownloadedTrackEntity>())
    private val dao = mockk<DownloadedTrackDao>(relaxed = true)
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val settings = mockk<SettingsPreferences>(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var manager: TrackDownloadManager
    private var denyContentUris = false

    @Before
    fun setUp() {
        every { context.applicationContext } returns context
        every { context.contentResolver } returns resolver
        every { context.cacheDir } returns temp.newFolder("cache")
        every { context.filesDir } returns temp.newFolder("files")
        every { context.getSystemService(any<String>()) } returns null
        every { settings.settings } returns flowOf(MiscSettings())
        every { resolver.delete(any<Uri>(), isNull<String>(), isNull<Array<String>>()) } answers {
            val uri = firstArg<Uri>().toString()
            when {
                denyContentUris -> throw SecurityException("denied")
                uri.contains("missing") -> throw FileNotFoundException(uri)
                else -> 1
            }
        }
        every { resolver.openInputStream(any()) } throws FileNotFoundException("gone")
        coEvery { dao.getAllList() } coAnswers { stored.toList() }
        coEvery { dao.delete(any()) } coAnswers {
            val track = firstArg<DownloadedTrackEntity>()
            stored.removeAll { it.id == track.id }
        }
        coEvery { dao.insert(any()) } coAnswers {
            val track = firstArg<DownloadedTrackEntity>()
            stored.add(track)
            track.id
        }
        coEvery { dao.clearAll() } coAnswers {
            stored.clear()
        }
        coEvery { dao.findByTrackKey(any()) } returns null
        coEvery { dao.findByTitleAndArtist(any(), any()) } returns null
        manager = TrackDownloadManager(
            context = context,
            innerTube = mockk(relaxed = true),
            artworkRepository = mockk(relaxed = true),
            lyricsRepository = mockk(relaxed = true),
            audioTagWriter = mockk(relaxed = true),
            okHttpClient = OkHttpClient(),
            downloadedTrackDao = dao,
            settingsPreferences = settings,
            applicationScope = scope,
            moduleResolver = mockk(relaxed = true),
            segBridge = mockk(relaxed = true),
            offlineLicense = mockk(relaxed = true),
            moduleManager = mockk(relaxed = true),
            flacTranscoder = mockk(relaxed = true),
            losslessMusicApi = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        runBlocking {
            withTimeoutOrNull(2_000) { scope.coroutineContext[Job]?.join() }
        }
        unmockkAll()
    }

    @Test
    fun clearAllDownloads_deletesTrackedAudioLyricsAndUris_andKeepsUnrelatedFiles() = runBlocking {
        val audio = temp.newFile("artist - song.flac").apply { writeBytes(ByteArray(32) { 1 }) }
        val lyrics = temp.newFile("artist - song.lrc").apply { writeText("[00:01.00]line") }
        val mixedAudio = temp.newFile("mixed.flac").apply { writeBytes(ByteArray(16) { 2 }) }
        val mixedLyrics = temp.newFile("mixed.lrc").apply { writeText("lyric") }
        val unrelated = temp.newFile("keep-me.mp3").apply { writeBytes(byteArrayOf(7)) }
        val missingPath = File(temp.root, "already-gone.flac")
        stored += track(1, "Song", filePath = audio.absolutePath, lrc = lyrics.absolutePath)
        stored += track(
            id = 2,
            title = "Stream",
            filePath = "",
            mediaStoreUri = "content://media/audio/2",
            lrc = "content://downloads/lyrics/2",
        )
        stored += track(
            id = 3,
            title = "Mixed",
            filePath = mixedAudio.absolutePath,
            mediaStoreUri = "content://media/audio/3",
            lrc = mixedLyrics.absolutePath,
        )
        stored += track(
            id = 4,
            title = "Missing",
            filePath = missingPath.absolutePath,
            mediaStoreUri = "content://media/audio/missing",
        )

        manager.clearAllDownloads()

        assertThat(audio.exists()).isFalse()
        assertThat(lyrics.exists()).isFalse()
        assertThat(mixedAudio.exists()).isFalse()
        assertThat(mixedLyrics.exists()).isFalse()
        assertThat(unrelated.exists()).isTrue()
        assertThat(stored).isEmpty()
        coVerify(exactly = 0) { dao.clearAll() }
        assertThat(manager.wiping()).isFalse()

        manager.clearAllDownloads()
        assertThat(stored).isEmpty()
        assertThat(unrelated.exists()).isTrue()
    }

    @Test
    fun clearAllDownloads_joinsActiveAndQueuedWorkersBeforeEnumerating() = runBlocking {
        val activeHeld = CompletableDeferred<Unit>()
        coEvery { dao.findByTrackKey(any()) } coAnswers {
            activeHeld.complete(Unit)
            suspendCancellableCoroutine { }
        }
        manager.downloadTrack(title = "Active Song", artist = "Artist")
        withTimeout(5_000) { activeHeld.await() }
        manager.downloadTrack(title = "Queued Song", artist = "Artist")
        withTimeout(5_000) { awaitActiveJobCount(2) }
        val tracked = manager.activeJobs().values.toList()
        assertThat(tracked).hasSize(2)

        var enumeratedAfterJoin = false
        coEvery { dao.getAllList() } coAnswers {
            enumeratedAfterJoin = tracked.all { it.isCompleted }
            stored.toList()
        }

        withTimeout(5_000) { manager.clearAllDownloads() }

        assertThat(enumeratedAfterJoin).isTrue()
        assertThat(tracked.all { it.isCompleted }).isTrue()
        coVerify(exactly = 0) { dao.insert(any()) }
        assertThat(stored).isEmpty()
        assertThat(manager.wiping()).isFalse()
        assertThat(manager.activeKeys()).isEmpty()
    }

    @Test
    fun clearAllDownloads_dropsFilesCreatedWhileWorkersShutDown() = runBlocking {
        val escapedFile = File(temp.root, "escaped.flac")
        val escaped = track(9, "Escaped", filePath = escapedFile.absolutePath)
        val started = CompletableDeferred<Unit>()
        val job = scope.launch(Dispatchers.IO) {
            try {
                started.complete(Unit)
                suspendCancellableCoroutine<Unit> { }
            } finally {
                escapedFile.writeBytes(byteArrayOf(1, 2, 3, 4))
                stored.add(escaped)
            }
        }
        withTimeout(5_000) { started.await() }
        manager.activeKeys().add(escaped.trackKey)
        manager.activeJobs()[escaped.trackKey] = job

        withTimeout(5_000) { manager.clearAllDownloads() }

        assertThat(job.isCompleted).isTrue()
        assertThat(escapedFile.exists()).isFalse()
        assertThat(stored).isEmpty()
        assertThat(manager.wiping()).isFalse()
    }

    @Test
    fun clearAllDownloads_rejectsAdmissionUntilTheWipeFinishes_thenDownloadsWorkAgain() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { dao.getAllList() } coAnswers {
            entered.complete(Unit)
            release.await()
            stored.toList()
        }
        var lookups = 0
        coEvery { dao.findByTrackKey(any()) } coAnswers {
            lookups += 1
            suspendCancellableCoroutine { }
        }
        val wipe = scope.launch { manager.clearAllDownloads() }
        withTimeout(5_000) { entered.await() }
        assertThat(manager.wiping()).isTrue()

        manager.downloadTrack(title = "During", artist = "Wipe")
        assertThat(lookups).isEqualTo(0)
        assertThat(manager.activeJobs()).isEmpty()
        coVerify(exactly = 0) { dao.insert(any()) }

        release.complete(Unit)
        withTimeout(5_000) { wipe.join() }
        assertThat(manager.wiping()).isFalse()

        val admitted = CompletableDeferred<Unit>()
        coEvery { dao.findByTrackKey(any()) } coAnswers {
            lookups += 1
            admitted.complete(Unit)
            suspendCancellableCoroutine { }
        }
        manager.downloadTrack(title = "After", artist = "Wipe")
        withTimeout(5_000) { admitted.await() }
        assertThat(lookups).isEqualTo(1)
        assertThat(manager.activeKeys()).contains(manager.makeDownloadKey("After", "Wipe"))
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun clearAllDownloads_keepsFailedRows_releasesTheGuard_andRetries() = runBlocking {
        val good = temp.newFile("good.flac").apply { writeBytes(ByteArray(8) { 3 }) }
        val blockedDir = temp.newFolder("blocked-song")
        File(blockedDir, "child.flac").writeBytes(byteArrayOf(9))
        val unrelated = temp.newFile("notes.txt").apply { writeText("leave me") }
        denyContentUris = true
        stored += track(1, "Good", filePath = good.absolutePath)
        stored += track(2, "Blocked", filePath = blockedDir.absolutePath)
        stored += track(3, "Denied", filePath = "", mediaStoreUri = "content://media/audio/3")

        val failure = runCatching { manager.clearAllDownloads() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure).hasMessageThat().contains("Failed to delete 2")
        assertThat(good.exists()).isFalse()
        assertThat(File(blockedDir, "child.flac").exists()).isTrue()
        assertThat(blockedDir.exists()).isTrue()
        assertThat(unrelated.exists()).isTrue()
        assertThat(stored.map { it.id }).containsExactly(2L, 3L)
        coVerify(exactly = 0) { dao.clearAll() }
        assertThat(manager.wiping()).isFalse()

        var lookups = 0
        val admitted = CompletableDeferred<Unit>()
        coEvery { dao.findByTrackKey(any()) } coAnswers {
            lookups += 1
            admitted.complete(Unit)
            suspendCancellableCoroutine { }
        }
        manager.downloadTrack(title = "Retry", artist = "Later")
        withTimeout(5_000) { admitted.await() }
        assertThat(lookups).isEqualTo(1)

        File(blockedDir, "child.flac").delete()
        denyContentUris = false
        manager.clearAllDownloads()
        assertThat(stored).isEmpty()
        assertThat(blockedDir.exists()).isFalse()
        assertThat(unrelated.exists()).isTrue()
        assertThat(manager.wiping()).isFalse()

        manager.clearAllDownloads()
        assertThat(stored).isEmpty()
    }

    @Test
    fun clearAllDownloads_cancellationReleasesTheGuardSoAnotherWipeCanRun() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { dao.getAllList() } coAnswers {
            entered.complete(Unit)
            release.await()
            stored.toList()
        }
        val wipe = scope.launch { manager.clearAllDownloads() }
        withTimeout(5_000) { entered.await() }
        assertThat(manager.wiping()).isTrue()
        wipe.cancel()
        withTimeout(5_000) { wipe.join() }
        assertThat(manager.wiping()).isFalse()

        coEvery { dao.getAllList() } coAnswers { stored.toList() }
        manager.clearAllDownloads()
        assertThat(manager.wiping()).isFalse()
        assertThat(stored).isEmpty()
    }

    private fun track(
        id: Long,
        title: String,
        filePath: String,
        mediaStoreUri: String? = null,
        lrc: String? = null,
    ) = DownloadedTrackEntity(
        id = id,
        trackKey = "artist_${title.lowercase()}",
        title = title,
        artist = "Artist",
        filePath = filePath,
        mediaStoreUri = mediaStoreUri,
        lrcFilePath = lrc,
    )

    private suspend fun awaitActiveJobCount(count: Int) {
        while (manager.activeJobs().size < count) {
            kotlinx.coroutines.yield()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun TrackDownloadManager.activeJobs(): MutableMap<String, Job> {
        val field = TrackDownloadManager::class.java.getDeclaredField("activeJobs")
        field.isAccessible = true
        return field.get(this) as MutableMap<String, Job>
    }

    @Suppress("UNCHECKED_CAST")
    private fun TrackDownloadManager.activeKeys(): MutableSet<String> {
        val field = TrackDownloadManager::class.java.getDeclaredField("activeKeys")
        field.isAccessible = true
        return field.get(this) as MutableSet<String>
    }

    private fun TrackDownloadManager.wiping(): Boolean {
        val field = TrackDownloadManager::class.java.getDeclaredField("downloadsWiping")
        field.isAccessible = true
        return field.getBoolean(this)
    }
}
