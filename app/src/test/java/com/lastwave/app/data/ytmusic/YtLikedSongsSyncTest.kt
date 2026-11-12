package com.lastwave.app.data.ytmusic

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.toGenerated
import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistResult
import com.lastwave.app.data.music.YtAccountInfo
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.playlist.LikedSongsManager
import com.lastwave.app.data.playlist.LIKED_SONGS_MODE
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.SavedPlaylist
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class YtLikedSongsSyncTest {
    private val song = GeneratedTrack("Song", "Artist", url = "https://music.youtube.com/watch?v=abcdefghijk")
    private val other = GeneratedTrack("Other", "Artist", url = "https://music.youtube.com/watch?v=lmnopqrstuv")

    @Test
    fun awaitingAuthAfterOptimisticLoginDoesNotWaitForAnObsoleteGuestSnapshot() = runTest {
        val preferences = mockk<YtMusicPreferences>(relaxed = true)
        every { preferences.connection } returns MutableStateFlow(YtConnection.DISCONNECTED)
        val auth = YtMusicAuthManager(preferences)
        auth.awaitLoadedConnection()
        // Persistence has not emitted the new account yet, but connect already
        // published the account used by immediate like/login requests.
        auth.connect("SAPISID=test", "A", null, null)
        assertThat(withTimeout(1_000) { auth.awaitLoadedConnection() }.accountName).isEqualTo("A")
    }

    @Test
    fun guestLikesPersistAndMergeWithAccountOnLogin() = runTest {
        val store = Store(listOf(song), YtConnection.DISCONNECTED)
        val sync = store.sync(backgroundScope)
        sync.syncNow()
        assertThat(store.writes).isEmpty()
        assertThat(store.journal().pending.single().liked).isTrue()
        store.connection.value = account("A")
        store.remote = listOf(other)
        sync.syncNow()
        assertThat(store.writes).containsExactly("abcdefghijk" to true)
        assertThat(store.saved.tracks.map { it.youtubeVideoIdOrNull() }).containsExactly("abcdefghijk", "lmnopqrstuv")
        assertThat(store.journal().pending).isEmpty()
    }

    @Test
    fun unlikeWhileGuestCannotBeResurrectedAtLogin() = runTest {
        val store = Store(listOf(song), YtConnection.DISCONNECTED)
        val sync = store.sync(backgroundScope)
        sync.syncNow()
        store.saved = store.saved.copy(tracks = emptyList())
        sync.syncNow()
        store.connection.value = account("A")
        store.remote = listOf(song)
        sync.syncNow()
        assertThat(store.writes).containsExactly("abcdefghijk" to false)
        assertThat(store.saved.tracks).isEmpty()
        assertThat(store.remote).isEmpty()
    }

    @Test
    fun failedWriteSurvivesRestartAndStaleRemoteRead() = runTest {
        val store = Store(listOf(song), account("A"))
        store.acceptWrites = false
        store.sync(backgroundScope).syncNow()
        assertThat(store.saved.tracks).containsExactly(song)
        assertThat(store.journal().pending.single().liked).isTrue()
        store.acceptWrites = true
        store.sync(backgroundScope).syncNow()
        assertThat(store.writes).containsExactly("abcdefghijk" to true, "abcdefghijk" to true)
        assertThat(store.remote).containsExactly(song)
        assertThat(store.journal().pending).isEmpty()
    }

    @Test
    fun accountRemovalPropagatesOnlyFromACompleteSnapshot() = runTest {
        val store = Store(emptyList(), account("A"))
        val sync = store.sync(backgroundScope)
        store.remote = listOf(song)
        sync.syncNow()
        store.remote = emptyList()
        store.complete = false
        sync.syncNow()
        assertThat(store.saved.tracks).containsExactly(song)
        store.complete = true
        sync.syncNow()
        assertThat(store.saved.tracks).isEmpty()
        assertThat(store.writes).isEmpty()
    }

    @Test
    fun playlistRemovalDuringRemoteFetchWinsOverTheOlderResponse() = runTest {
        val store = Store(emptyList(), account("A"))
        val sync = store.sync(backgroundScope)
        store.remote = listOf(song)
        sync.syncNow()
        store.beforeFetch = { store.saved = store.saved.copy(tracks = emptyList()) }
        sync.syncNow()
        assertThat(store.saved.tracks).isEmpty()
        assertThat(store.journal().pending.single().liked).isFalse()
        store.beforeFetch = {}
        sync.syncNow()
        assertThat(store.remote).isEmpty()
        assertThat(store.writes).containsExactly("abcdefghijk" to false)
    }

    @Test
    fun newerUnlikeIsNotAcknowledgedByAStaleEmptySnapshotBeforeItIsSent() = runTest {
        val store = Store(listOf(song), account("A"))
        val sync = store.sync(backgroundScope)
        store.publishWrites = false
        store.beforeFetch = { store.saved = store.saved.copy(tracks = emptyList()) }
        sync.syncNow()
        assertThat(store.writes).containsExactly("abcdefghijk" to true)
        assertThat(store.journal().pending.single().liked).isFalse()
        assertThat(store.saved.tracks).isEmpty()
        // The accepted first like becomes visible after that stale LM read.
        store.remote = listOf(song)
        store.publishWrites = true
        store.beforeFetch = {}
        sync.syncNow()
        assertThat(store.writes).containsExactly("abcdefghijk" to true, "abcdefghijk" to false).inOrder()
        assertThat(store.remote).isEmpty()
        assertThat(store.saved.tracks).isEmpty()
        assertThat(store.journal().pending).isEmpty()
    }

    @Test
    fun queuedActionsAreNeverSentToAnotherAccount() = runTest {
        val store = Store(listOf(song), account("A"))
        val sync = store.sync(backgroundScope)
        sync.mutationMutex.withLock { sync.captureLocalChanges() }
        store.connection.value = account("B")
        sync.syncNow()
        assertThat(store.writes).isEmpty()
        assertThat(store.journal().pending.single().account).isEqualTo(account("A").likeAccountKey())
        assertThat(store.saved.tracks).isEmpty()
    }

    @Test
    fun deviceFilesArePreservedAndNeverSentToYouTube() = runTest {
        val local = song.copy(url = "/storage/emulated/0/Music/song.flac")
        val store = Store(listOf(local), account("A"))
        store.sync(backgroundScope).syncNow()
        assertThat(store.saved.tracks).containsExactly(local)
        assertThat(store.writes).isEmpty()
        assertThat(store.journal().pending).isEmpty()
    }

    @Test
    fun provisionalLoginDoesNotStrandGuestLikesWhenIdentityIsResolved() = runTest {
        val store = Store(listOf(song), YtConnection.DISCONNECTED)
        val sync = store.sync(backgroundScope)
        sync.syncNow()
        store.connection.value = account("Google account")
        sync.syncNow()
        assertThat(store.writes).isEmpty()
        assertThat(store.journal().pending.single().account).isEqualTo("guest")
        // account_menu can become available after the login screen has finished.
        store.identity = YtAccountInfo("A", "@a")
        sync.syncNow()
        assertThat(store.journal().pending.single().account).isEqualTo("guest")
        sync.syncNow()
        assertThat(store.writes).containsExactly("abcdefghijk" to true)
        assertThat(store.journal().pending).isEmpty()
        assertThat(store.remote).containsExactly(song)
    }

    @Test
    fun staleYouTubeImportCannotReplaceAPendingUnlike() = runTest {
        val store = Store(emptyList(), account("A"))
        val sync = store.sync(backgroundScope)
        store.remote = listOf(song)
        sync.syncNow()
        store.manager(backgroundScope, sync).setLiked(song, false)
        store.remote = listOf(song, other)
        assertThat(sync.mergeYouTubeLikes(listOf(song, other))).isTrue()
        assertThat(store.saved.tracks).containsExactly(other)
        assertThat(store.journal().pending.single().liked).isFalse()
        sync.syncNow()
        assertThat(store.writes).containsExactly("abcdefghijk" to false)
        assertThat(store.saved.tracks).containsExactly(other)
    }

    @Test
    fun accountSwitchDuringRoomWriteKeepsTapBoundToTheOriginalAccount() = runTest {
        val store = Store(emptyList(), account("A"))
        val sync = store.sync(backgroundScope)
        sync.syncNow()
        store.beforeLocalWrite = { store.connection.value = account("B") }
        store.manager(backgroundScope, sync).setLiked(song, true)
        assertThat(store.journal().pending.single().account).isEqualTo(account("A").likeAccountKey())
        store.beforeLocalWrite = {}
        sync.syncNow()
        assertThat(store.writes).isEmpty()
        assertThat(store.journal().pending.single().account).isEqualTo(account("A").likeAccountKey())
    }

    private fun account(name: String) = YtConnection(cookies = mapOf("SAPISID" to "test"), accountName = name)

    private class Store(tracks: List<GeneratedTrack>, account: YtConnection) {
        var saved = SavedPlaylist(1L, "Liked Songs", "", LIKED_SONGS_MODE, tracks, 0L)
        var remote: List<GeneratedTrack> = emptyList()
        var acceptWrites = true
        var publishWrites = true
        var complete = true
        var beforeFetch: () -> Unit = {}
        var beforeLocalWrite: () -> Unit = {}
        var identity: YtAccountInfo? = null
        val writes = mutableListOf<Pair<String, Boolean>>()
        val connection = MutableStateFlow(account)
        private val dataStore = MemoryStore()
        private val repository = mockk<PlaylistRepository>()
        private val api = mockk<InnerTubeMusicApi>()
        private val auth = mockk<YtMusicAuthManager>()

        init {
            every { auth.connection } returns connection
            coEvery { auth.awaitLoadedConnection() } answers { connection.value }
            coEvery { repository.getLikedSongs() } answers { saved }
            coEvery { repository.ensureLikedSongs() } answers { saved }
            coEvery { repository.replaceTracksForSync(any(), any(), any()) } answers {
                if (thirdArg<List<GeneratedTrack>>() != saved.tracks) null else {
                    beforeLocalWrite()
                    saved = saved.copy(tracks = secondArg())
                    saved
                }
            }
            coEvery { api.fetchAccountInfo() } answers { identity }
            coEvery { auth.updateAccountIdentity(any(), any(), any()) } answers {
                connection.value = connection.value.copy(accountName = firstArg(), channelHandle = secondArg(), photoUrl = thirdArg())
            }
            coEvery { api.setVideoLike(any(), any(), any()) } answers { write(firstArg(), secondArg()) }
            coEvery { api.fetchPlaylist("LM", any(), any(), any()) } answers {
                beforeFetch()
                YouTubePlaylistResult("LM", "Liked Music", tracks = remote.map {
                    YouTubeMusicTrack(videoId = it.youtubeVideoIdOrNull()!!, title = it.name, artist = it.artist, artworkUrl = it.artworkUrl)
                }, isComplete = complete)
            }
        }

        private fun write(id: String, liked: Boolean): Boolean {
            writes += id to liked
            if (acceptWrites && publishWrites) {
                remote = if (liked) {
                    val track = journal().pending.first { it.track.toGenerated().youtubeVideoIdOrNull() == id }.track.toGenerated()
                    remote.filterNot { it.youtubeVideoIdOrNull() == id } + track
                } else remote.filterNot { it.youtubeVideoIdOrNull() == id }
            }
            return acceptWrites
        }

        fun manager(scope: CoroutineScope, sync: YtLikedSongsSync) =
            LikedSongsManager(mockk<Context>(), repository, scope, mockk<SettingsPreferences>(), sync)
        fun sync(scope: CoroutineScope) = YtLikedSongsSync(dataStore, repository, api, auth, scope)
        fun journal(): LikeSyncJournal = dataStore.data.value[stringPreferencesKey("yt_liked_songs_journal_v1")]
            ?.let { Json.decodeFromString<LikeSyncJournal>(it) } ?: LikeSyncJournal()
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        private val lock = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            transform(data.value).also { data.value = it }
        }
    }
}
