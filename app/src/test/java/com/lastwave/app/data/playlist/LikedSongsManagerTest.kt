package com.lastwave.app.data.playlist

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.ytmusic.YtLikedSongsSync
import com.lastwave.app.data.local.SettingsPreferences
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.every
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class LikedSongsManagerTest {
    private val song = GeneratedTrack("Song", "Artist", url = "https://music.youtube.com/watch?v=abcdefghijk")
    private val alias = song.copy(name = "Song (Official Video)")

    @Test
    fun firstTapUsesSavedLikeEvenBeforeTheHeartFlowLoads() = runTest {
        val store = Store(listOf(song))
        val manager = store.manager(backgroundScope)
        assertThat(manager.likedTrackKeys.value).isEmpty()
        assertThat(manager.toggle(alias)).isFalse()
        assertThat(store.saved.tracks).isEmpty()
        assertThat(manager.likedTrackKeys.value).isEmpty()
    }

    @Test
    fun concurrentTapsDoNotReuseAStaleDesiredState() = runTest {
        val store = Store(emptyList())
        val manager = store.manager(backgroundScope)
        val results = List(10) { async { manager.toggle(song) } }.awaitAll()
        assertThat(results.count { it }).isEqualTo(5)
        assertThat(store.saved.tracks).isEmpty()
    }

    @Test
    fun repeatedMediaRatingsAreIdempotent() = runTest {
        val store = Store(emptyList())
        val manager = store.manager(backgroundScope)
        manager.setLiked(song, true)
        manager.setLiked(alias, true)
        assertThat(store.saved.tracks).containsExactly(song)
        manager.setLiked(alias, false)
        manager.setLiked(song, false)
        assertThat(store.saved.tracks).isEmpty()
    }

    @Test
    fun compareAndSwapConflictRetriesWithoutLosingAnotherSong() = runTest {
        val store = Store(emptyList())
        val other = GeneratedTrack("Other", "Artist", url = "https://music.youtube.com/watch?v=lmnopqrstuv")
        store.conflictOnce = other
        val manager = store.manager(backgroundScope)
        manager.like(song)
        assertThat(store.saved.tracks).containsExactly(other, song)
    }

    private class Store(tracks: List<GeneratedTrack>) {
        var saved = SavedPlaylist(1L, "Liked Songs", "", LIKED_SONGS_MODE, tracks, 0L)
        var conflictOnce: GeneratedTrack? = null
        private val repository = mockk<PlaylistRepository>()

        init {
            coEvery { repository.getLikedSongs() } answers { saved }
            coEvery { repository.ensureLikedSongs() } answers { saved }
            coEvery { repository.replaceTracksForSync(any(), any(), any()) } answers {
                val conflict = conflictOnce
                if (conflict != null) {
                    conflictOnce = null
                    saved = saved.copy(tracks = saved.tracks + conflict)
                    null
                } else if (thirdArg<List<GeneratedTrack>>() != saved.tracks) {
                    null
                } else {
                    saved = saved.copy(tracks = secondArg())
                    saved
                }
            }
        }

        fun manager(scope: kotlinx.coroutines.CoroutineScope) = LikedSongsManager(
            mockk<Context>(), repository, scope, mockk<SettingsPreferences>(),
            mockk<YtLikedSongsSync>(relaxed = true).also { sync -> every { sync.mutationMutex } returns Mutex() },
        )
    }
}
