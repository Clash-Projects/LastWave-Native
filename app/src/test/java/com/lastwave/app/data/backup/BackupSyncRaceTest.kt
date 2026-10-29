package com.lastwave.app.data.backup

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.local.db.AppDatabase
import com.lastwave.app.data.local.db.SavedPlaylistEntity
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class BackupSyncRaceTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: BackupRepository

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        val prefsFile = File(app.filesDir, "race_test.preferences_pb").apply { delete() }
        repo = BackupRepository(
            context = app,
            dataStore = PreferenceDataStoreFactory.create(produceFile = { prefsFile }),
            playlistDao = db.savedPlaylistDao(),
            recommendationExclusionDao = db.recommendationExclusionDao(),
            playlistPublicMirror = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun playlist(vararg names: String) = SavedPlaylistEntity(
        id = 1L,
        title = "Mix",
        subtitle = "",
        mode = "custom",
        tracksJson = names.joinToString(",", "[", "]") { """{"name":"$it","artist":"X"}""" },
        createdAtMillis = 1L,
    )

    @Test
    fun editDuringUploadIsKeptAndSyncRetries() = runBlocking {
        val dao = db.savedPlaylistDao()
        dao.upsert(playlist("A"))
        val file = repo.buildBackup("test")

        val result = repo.sync(file, "test") {
            // The user adds a track while the merged file uploads.
            dao.upsert(playlist("A", "B"))
        }

        assertThat(result).isInstanceOf(RestoreResult.Failed::class.java)
        assertThat(dao.getById(1L)!!.tracksJson).contains("\"B\"")
    }

    @Test
    fun quietSyncReplacesTheLibrary() = runBlocking {
        val dao = db.savedPlaylistDao()
        dao.upsert(playlist("A"))
        val file = repo.buildBackup("test")

        val result = repo.sync(file, "test") {}

        assertThat(result).isInstanceOf(RestoreResult.Success::class.java)
        assertThat(dao.getById(1L)!!.tracksJson).contains("\"A\"")
    }
}
