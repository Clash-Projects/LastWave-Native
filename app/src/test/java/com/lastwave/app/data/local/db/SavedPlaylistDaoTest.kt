package com.lastwave.app.data.local.db

import android.app.Application
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class SavedPlaylistDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SavedPlaylistDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.savedPlaylistDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun readsAndUpdatesOversizedPlaylistAboveCursorWindowWithNonBmpChars() = runBlocking {
        // Exceeds Android's 2 MB CursorWindow cap and spans multiple 250k-code-point chunks,
        // with surrogate-pair emojis so code-point SUBSTR indexing is exercised directly.
        val bigTracksJson = buildString {
            append('[')
            repeat(9_000) { index ->
                if (index > 0) append(',')
                append("""{"name":"Song 🎵 $index","artist":"Artist 🎶","url":"https://music.youtube.com/watch?v=id$index","image":"https://lh3.googleusercontent.com/cover_$index=w544-h544-l90-rj","album":"Album","durationMs":210000}""")
            }
            append(']')
        }
        assertThat(bigTracksJson.length).isGreaterThan(2_200_000)

        val liked = SavedPlaylistEntity(
            id = 1L,
            title = "Liked Songs",
            subtitle = "Songs you like in LastWave",
            mode = "liked",
            tracksJson = bigTracksJson,
            createdAtMillis = 100L,
            isPinned = true,
        )
        val small = SavedPlaylistEntity(
            id = 2L,
            title = "Road Trip",
            subtitle = "Custom playlist",
            mode = "custom",
            tracksJson = """[{"name":"Short","artist":"Tester","url":"https://music.youtube.com/watch?v=short"}]""",
            createdAtMillis = 200L,
        )
        dao.upsertAll(listOf(liked, small))

        assertThat(dao.getAll()).containsExactly(liked, small).inOrder()
        assertThat(dao.getById(1L)).isEqualTo(liked)
        assertThat(dao.getNewestByMode("liked")).isEqualTo(liked)
        assertThat(dao.getNewestByMode("custom")).isEqualTo(small)

        val updatedJson = """[{"name":"Newly Liked ❤️","artist":"New","url":"https://music.youtube.com/watch?v=new1"},""" +
            bigTracksJson.drop(1)
        assertThat(dao.updateTracksIfUnchanged(1L, "stale", updatedJson)).isEqualTo(0)
        assertThat(dao.updateTracksIfUnchanged(1L, bigTracksJson, updatedJson)).isEqualTo(1)
        assertThat(dao.getNewestByMode("liked")?.tracksJson).isEqualTo(updatedJson)
    }
}
