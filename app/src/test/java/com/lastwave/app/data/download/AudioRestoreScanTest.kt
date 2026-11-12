package com.lastwave.app.data.download

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AudioRestoreScanTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val stored = Collections.synchronizedList(mutableListOf<DownloadedTrackEntity>())
    private val dao = mockk<DownloadedTrackDao>(relaxed = true)
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val settings = mockk<SettingsPreferences>(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var manager: TrackDownloadManager
    private lateinit var prefsDir: File
    private lateinit var noBackupDir: File

    @Before
    fun setUp() {
        prefsDir = temp.newFolder("shared_prefs")
        noBackupDir = temp.newFolder("no_backup")
        val filesDir = temp.newFolder("files")
        val cacheDir = temp.newFolder("cache")

        every { context.applicationContext } returns context
        every { context.contentResolver } returns resolver
        every { context.cacheDir } returns cacheDir
        every { context.filesDir } returns filesDir
        every { context.noBackupFilesDir } returns noBackupDir
        every { context.getSystemService(any<String>()) } returns null
        every { settings.settings } returns flowOf(MiscSettings())

        val inMemoryPrefs = HashMap<String, Any?>()
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getBoolean(any(), any()) } answers {
            val key = firstArg<String>()
            val def = secondArg<Boolean>()
            (inMemoryPrefs[key] as? Boolean) ?: def
        }
        every { prefs.edit() } returns editor
        every { editor.putBoolean(any(), any()) } answers {
            val key = firstArg<String>()
            val v = secondArg<Boolean>()
            inMemoryPrefs[key] = v
            editor
        }
        every { editor.apply() } answers { }

        coEvery { dao.getAllList() } coAnswers { stored.toList() }
        coEvery { dao.insert(any()) } coAnswers {
            val track = firstArg<DownloadedTrackEntity>()
            stored.add(track)
            track.id
        }
        coEvery { dao.findByTrackKey(any()) } answers {
            val key = firstArg<String>()
            stored.firstOrNull { it.trackKey == key }
        }

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
    }

    @Test
    fun requiredPermission_onSdk33_isReadMediaAudio() {
        assertThat(manager.getRequiredAudioPermission()).isEqualTo(Manifest.permission.READ_MEDIA_AUDIO)
    }

    @Test
    fun initialScannedFlag_isFalse() {
        assertThat(manager.isDownloadsScanned()).isFalse()
    }

    @Test
    fun restoreDownloads_withPermission_setsScannedFlagAndDoesNotDuplicate() = runBlocking {
        every { context.checkPermission(Manifest.permission.READ_MEDIA_AUDIO, any(), any()) } returns PackageManager.PERMISSION_GRANTED

        val firstResult = manager.restoreDownloadsAfterReinstall()
        assertThat(firstResult).isTrue()
        assertThat(manager.isDownloadsScanned()).isTrue()

        // Calling a second time should immediately short-circuit without re-scanning or adding duplicates
        val countBefore = stored.size
        val secondResult = manager.restoreDownloadsAfterReinstall()
        assertThat(secondResult).isTrue()
        assertThat(stored.size).isEqualTo(countBefore)
    }

    @Test
    fun restoreDownloads_withoutPermission_leavesScannedFlagUnset() = runBlocking {
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED

        val result = manager.restoreDownloadsAfterReinstall()
        assertThat(result).isFalse()
        assertThat(manager.isDownloadsScanned()).isFalse()
    }
}
