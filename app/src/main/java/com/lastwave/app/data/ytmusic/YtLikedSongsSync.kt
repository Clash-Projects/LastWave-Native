package com.lastwave.app.data.ytmusic

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.StoredTrack
import com.lastwave.app.data.generate.distinctSongs
import com.lastwave.app.data.generate.isLocalFile
import com.lastwave.app.data.generate.toGenerated
import com.lastwave.app.data.generate.toStored
import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.data.local.readSafely
import com.lastwave.app.data.local.recoverPreferences
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.likedMarkers
import com.lastwave.app.data.playlist.isLikedIn
import com.lastwave.app.util.AppVisibility
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class PendingYouTubeLike(
    val track: StoredTrack,
    val liked: Boolean,
    val account: String,
    val revision: Long,
)

@Serializable
internal data class LikeSyncJournal(
    val localTracks: List<StoredTrack>? = null,
    val pending: List<PendingYouTubeLike> = emptyList(),
    val revision: Long = 0,
) {
    fun withIntent(track: GeneratedTrack, liked: Boolean, account: String): LikeSyncJournal =
        withIntents(listOf(track to liked), account)

    private fun withIntents(intents: List<Pair<GeneratedTrack, Boolean>>, account: String): LikeSyncJournal {
        val existing = pending.filter { it.account == account }
            .flatMap { change -> change.track.toGenerated().likedMarkers().map { it to change } }.toMap()
        val changes = intents.filterNot { it.first.isLocalFile() }.mapIndexed { index, (track, liked) ->
            val previous = track.likedMarkers().firstNotNullOfOrNull(existing::get)?.track?.toGenerated()
            val resolved = if (track.youtubeVideoIdOrNull() == null && previous?.youtubeVideoIdOrNull() != null) {
                track.copy(url = previous.url)
            } else track
            PendingYouTubeLike(resolved.toStored(), liked, account, revision + index + 1)
        }
        val replaced = changes.flatMapTo(mutableSetOf()) { it.track.toGenerated().likedMarkers() }
        return copy(
            pending = pending.filterNot { it.account == account && it.track.toGenerated().isLikedIn(replaced) } + changes,
            revision = revision + changes.size,
        )
    }

    fun capture(tracks: List<GeneratedTrack>, account: String): LikeSyncJournal {
        val previous = localTracks.orEmpty().map { it.toGenerated() }
        val previousMarkers = previous.flatMapTo(mutableSetOf()) { it.likedMarkers() }
        val currentMarkers = tracks.flatMapTo(mutableSetOf()) { it.likedMarkers() }
        val unpinnedKeys = previous.filter { it.youtubeVideoIdOrNull() == null }.mapTo(mutableSetOf()) { it.key }
        val removed = previous.filterNot { it.isLikedIn(currentMarkers) }.map { it to false }
        val added = tracks.filter {
            !it.isLikedIn(previousMarkers) || (it.youtubeVideoIdOrNull() != null && it.key in unpinnedKeys)
        }.map { it to true }
        return withIntents(removed + added, account).copy(localTracks = tracks.map { it.toStored() })
    }

    fun adoptGuest(account: String): LikeSyncJournal = copy(pending = pending.filterNot { it.account == GUEST_LIKES })
        .withIntents(pending.filter { it.account == GUEST_LIKES }.map { it.track.toGenerated() to it.liked }, account)

}

private const val GUEST_LIKES = "guest"

internal fun YtConnection.likeAccountKey(): String? {
    if (!isConnected || (channelHandle.isNullOrBlank() && accountName == "Google account")) return null
    val identity = channelHandle?.takeIf { it.isNotBlank() } ?: accountName
    return listOf(identity, pageId, onBehalfOfUser.orEmpty(), authUserIndex?.toString().orEmpty()).joinToString("|")
}

internal fun reconcileLikedTracks(
    local: List<GeneratedTrack>,
    remote: List<GeneratedTrack>,
    pending: List<PendingYouTubeLike>,
): List<GeneratedTrack> {
    val replaced = pending.flatMapTo(mutableSetOf()) { it.track.toGenerated().likedMarkers() }
    return ((local.filter { it.youtubeVideoIdOrNull() == null } + remote).filterNot { it.isLikedIn(replaced) } +
        pending.filter { it.liked }.map { it.track.toGenerated() }).distinctSongs()
}

/**
 * A durable outbox for hearts, independent of optional playlist mirroring.
 * The saved local snapshot also detects a removal made from the playlist screen,
 * including a process death between the Room write and saving its pending change.
 */
@Singleton
class YtLikedSongsSync @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val playlists: PlaylistRepository,
    private val innerTube: InnerTubeMusicApi,
    private val auth: YtMusicAuthManager,
    private val applicationScope: CoroutineScope,
) {
    internal val mutationMutex = Mutex()
    private val syncMutex = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        applicationScope.launch(Dispatchers.IO) {
            for (ignored in requests) {
                delay(250)
                try {
                    syncNow()
                } catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                } catch (error: Exception) {
                    android.util.Log.w("YtLikedSongsSync", "Like sync will retry", error)
                }
            }
        }
        applicationScope.launch {
            auth.connection.map { it.likeAccountKey() }.distinctUntilChanged().collect { requestSync() }
        }
        applicationScope.launch { playlists.changes.collect { requestSync() } }
        applicationScope.launch {
            while (true) {
                delay(60_000)
                // Background work is needed only while a saved user action is outstanding.
                try {
                    if (AppVisibility.isVisible || readJournal().pending.isNotEmpty()) requestSync()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.w("YtLikedSongsSync", "Like retry deferred", error)
                }
            }
        }
        requestSync()
    }

    fun requestSync() { requests.trySend(Unit) }

    /** Caller holds [mutationMutex]. Return the account before any suspended Room write. */
    internal suspend fun captureLocalChanges(account: String? = null): String {
        val boundAccount = account ?: auth.awaitLoadedConnection().likeAccountKey() ?: GUEST_LIKES
        val tracks = playlists.getLikedSongs()?.tracks.orEmpty()
        val journal = readJournal()
        val next = journal.capture(tracks, boundAccount)
        if (next != journal) writeJournal(next)
        return boundAccount
    }

    /** Also records explicit unlikes made before the remote library has finished loading. */
    internal suspend fun recordIntent(track: GeneratedTrack, liked: Boolean, account: String) {
        captureLocalChanges(account)
        writeJournal(readJournal().withIntent(track, liked, account))
        requestSync()
    }

    /** A stale account import must not turn a queued unlike back into a local like. */
    internal suspend fun mergeYouTubeLikes(tracks: List<GeneratedTrack>): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            mutationMutex.withLock {
                val account = captureLocalChanges()
                repeat(3) {
                    val journal = readJournal()
                    val blocked = journal.pending.filter { !it.liked && (it.account == account || it.account == GUEST_LIKES) }
                        .flatMapTo(mutableSetOf()) { it.track.toGenerated().likedMarkers() }
                    val local = playlists.getLikedSongs() ?: playlists.ensureLikedSongs()
                    val merged = (local.tracks + tracks.filterNot { it.isLikedIn(blocked) }).distinctSongs()
                    if (merged == local.tracks) return@withLock true
                    if (playlists.replaceTracksForSync(local.id, merged, local.tracks) != null) {
                        // Imported remote likes are a snapshot, not new user intents.
                        writeJournal(journal.copy(localTracks = merged.map { it.toStored() }))
                        requestSync()
                        return@withLock true
                    }
                    captureLocalChanges(account)
                }
                false
            }
        }

    internal suspend fun syncNow() = withContext(Dispatchers.IO) {
        syncMutex.withLock sync@ {
            val connection = auth.awaitLoadedConnection()
            val account = connection.likeAccountKey()
            if (connection.isConnected && account == null) {
                // Login first publishes a placeholder name. Keep guest actions unassigned
                // until identity is known, and retry when account_menu was initially offline.
                val identity = innerTube.fetchAccountInfo()
                if (identity != null && auth.connection.value == connection) {
                    auth.updateAccountIdentity(identity.accountName, identity.channelHandle, identity.photoUrl)
                    requestSync()
                }
                return@sync
            }
            val initial = mutationMutex.withLock capture@ {
                if (auth.connection.value != connection) return@sync
                captureLocalChanges(account ?: GUEST_LIKES)
                if (auth.connection.value != connection) return@sync
                if (account == null) return@capture readJournal()
                readJournal().adoptGuest(account).also { writeJournal(it) }
            }
            if (account == null) return@sync
            val submitted = mutableSetOf<Long>()
            for (change in initial.pending.filter { it.account == account }) {
                if (auth.connection.value != connection) return@sync
                var track = change.track.toGenerated()
                var videoId = track.youtubeVideoIdOrNull()
                if (videoId == null) {
                    val match = innerTube.findBestMatchOrNull(track.name, track.artist, prefetchStreams = false) ?: continue
                    videoId = match.videoId
                    track = track.copy(url = "https://music.youtube.com/watch?v=$videoId")
                }
                val current = mutationMutex.withLock {
                    val journal = readJournal()
                    val stillPending = journal.pending.any { it.account == account && it.revision == change.revision }
                    if (stillPending && track != change.track.toGenerated()) {
                        writeJournal(journal.copy(pending = journal.pending.map {
                            if (it.account == account && it.revision == change.revision) it.copy(track = track.toStored()) else it
                        }))
                    }
                    stillPending
                }
                if (!current || auth.connection.value != connection) continue
                try {
                    if (innerTube.setVideoLike(videoId, change.liked, connection)) submitted += change.revision
                } catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                } catch (error: Exception) {
                    android.util.Log.w("YtLikedSongsSync", "Pending like retained", error)
                }
                delay(100)
            }
            if (auth.connection.value != connection) return@sync
            val result = innerTube.fetchPlaylist("LM") ?: return@sync
            // A missing continuation page is never evidence of an unlike.
            if (!result.isComplete || auth.connection.value != connection) return@sync
            val remote = result.tracks.map { song ->
                GeneratedTrack(song.title, song.artist, song.artworkUrl,
                    "https://music.youtube.com/watch?v=${song.videoId}", album = song.album)
            }
            mutationMutex.withLock apply@ {
                if (auth.connection.value != connection) return@apply
                captureLocalChanges(account)
                if (auth.connection.value != connection) return@apply
                val journal = readJournal().adoptGuest(account)
                val pending = journal.pending.filter { it.account == account }
                val local = playlists.getLikedSongs() ?: playlists.ensureLikedSongs()
                val tracks = reconcileLikedTracks(local.tracks, remote, pending)
                if (tracks != local.tracks && playlists.replaceTracksForSync(local.id, tracks, local.tracks) == null) {
                    requestSync()
                    return@apply
                }
                // Keep successful writes pending until an authoritative read confirms them;
                // YouTube's eventually-consistent LM response must not undo a fresh tap.
                // A newer opposite tap may happen to match this old snapshot; it still
                // needs its own write to supersede the already-accepted older request.
                val remoteIds = remote.mapNotNullTo(mutableSetOf()) { it.youtubeVideoIdOrNull() }
                val confirmed = pending.filter { change ->
                    val id = change.track.toGenerated().youtubeVideoIdOrNull()
                    change.revision in submitted && id != null && (id in remoteIds) == change.liked
                }.mapTo(mutableSetOf()) { it.revision }
                writeJournal(journal.copy(
                    localTracks = tracks.map { it.toStored() },
                    pending = journal.pending.filterNot { it.account == account && it.revision in confirmed },
                ))
            }
        }
    }

    private suspend fun readJournal(): LikeSyncJournal {
        val raw = dataStore.data.recoverPreferences("YtLikedSongsSync").first().readSafely(JOURNAL_KEY)
            ?: return LikeSyncJournal()
        return try {
            json.decodeFromString(raw)
        } catch (error: Exception) {
            android.util.Log.w("YtLikedSongsSync", "Rebuilding unreadable like journal from saved songs", error)
            LikeSyncJournal()
        }
    }

    private suspend fun writeJournal(journal: LikeSyncJournal) {
        dataStore.edit { it[JOURNAL_KEY] = json.encodeToString(journal) }
    }

    private companion object {
        val JOURNAL_KEY = stringPreferencesKey("yt_liked_songs_journal_v1")
    }
}
