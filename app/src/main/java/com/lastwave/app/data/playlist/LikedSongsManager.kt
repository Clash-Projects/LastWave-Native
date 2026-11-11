package com.lastwave.app.data.playlist

import android.content.Context
import android.os.Build
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.StoredTrack
import com.lastwave.app.data.generate.distinctSongs
import com.lastwave.app.data.generate.isLocalFile
import com.lastwave.app.data.generate.sameSongAs
import com.lastwave.app.data.generate.toGenerated
import com.lastwave.app.data.generate.toStored
import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.data.local.SettingsPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What the heart state set holds for one liked song: its title|artist key and,
 * when it has one, its YouTube id. Storage treats two tracks as the same song
 * when either matches ([sameSongAs]), so the heart must too: with only the key,
 * a song whose id was already liked under another title (YouTube titles carry
 * "(Official Video)" etc.) showed as not liked, and liking it wrote nothing.
 */
internal fun GeneratedTrack.likedMarkers(): List<String> =
    if (isLocalFile()) {
        val normalizedUrl = url.trim().removePrefix("file://")
        listOfNotNull(normalizedUrl.takeIf { it.isNotBlank() }?.let { "local:$it" })
    } else {
        listOfNotNull(key, youtubeVideoIdOrNull()?.let { "vid:$it" })
    }

/** Whether this track is liked according to a heart-state set. */
fun GeneratedTrack.isLikedIn(likedKeys: Set<String>): Boolean =
    likedMarkers().any { it in likedKeys }

/** Owns the built-in local Liked Songs playlist and the player's heart state. */
@Singleton
class LikedSongsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistRepository: PlaylistRepository,
    private val applicationScope: CoroutineScope,
    private val settingsPreferences: SettingsPreferences,
) {
    private val mutationMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val _likedTrackKeys = MutableStateFlow<Set<String>>(emptySet())
    val likedTrackKeys: StateFlow<Set<String>> = _likedTrackKeys.asStateFlow()
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        applicationScope.launch(Dispatchers.IO) {
            try {
                // watchLocalSync() used to sit here: it collected the (now removed)
                // "Save to Liked Songs" setting forever, so nothing below it ever
                // ran — no startup refresh, no heals, no change listener.
                migrateLocalOnlyLikes()
                bootstrapForInstalledVersion()
                refresh()
                // One-time heal for duplicates stacked by older builds (weak
                // key dedup + unlocked YT-liked import). No-op when clean.
                runCatching { dedupe() }
                    .onSuccess { removed ->
                        if (removed > 0) android.util.Log.i("LikedSongsManager", "Healed $removed duplicate liked tracks")
                    }
                // Older builds could bootstrap a second Liked Songs row when
                // a playlist read briefly failed; each duplicate row then
                // mirrored as its own YouTube playlist. Merge them into the
                // one with the most tracks and delete the leftovers — the
                // next sync pass sweeps their remote mirrors as orphans.
                runCatching { collapseDuplicateLikedRows() }
                    .onSuccess { removed ->
                        if (removed > 0) android.util.Log.i("LikedSongsManager", "Collapsed $removed duplicate liked playlists")
                    }
                playlistRepository.changes.collect { refresh() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // The built-in playlist is optional launch data. A corrupt
                // legacy database or a vendor filesystem failure must not
                // terminate the process; a later start() may retry it.
                started = false
                android.util.Log.e("LikedSongsManager", "Liked Songs startup disabled", error)
            } catch (error: LinkageError) {
                started = false
                android.util.Log.e("LikedSongsManager", "Liked Songs unsupported on this ROM", error)
            }
        }
    }

    // Mutations read and re-encode the whole Liked Songs playlist. Callers
    // include viewModelScope (main thread), so the work runs on Default: on a
    // library of thousands of songs it otherwise froze the UI for seconds per
    // heart tap (ANR).
    //
    // The write still takes ~1 s on a few thousand songs, so the heart flips
    // at the tap and the write saves exactly that state. Waiting for the write
    // left the heart a tap behind, and a second tap during the wait looked
    // like the like undoing itself.

    /** Flips the heart and saves the state it now shows. Returns that state. */
    suspend fun toggle(track: GeneratedTrack): Boolean {
        val markers = track.likedMarkers()
        val liked = synchronized(heartLock) {
            beginHeartTapLocked()
            val nowLiked = !track.isLikedIn(_likedTrackKeys.value)
            _likedTrackKeys.value = if (nowLiked) _likedTrackKeys.value + markers else _likedTrackKeys.value - markers.toSet()
            nowLiked
        }
        return finishHeartTap { setLikedStored(track, liked) }
    }

    /** Artwork double-tap is intentionally idempotent: it never unlikes. */
    suspend fun like(track: GeneratedTrack): Boolean {
        synchronized(heartLock) {
            beginHeartTapLocked()
            _likedTrackKeys.value = _likedTrackKeys.value + track.likedMarkers()
        }
        return finishHeartTap { setLikedStored(track, liked = true) }
    }

    /**
     * Heart state is published under this lock. [pendingHeartTaps] counts
     * taps whose write has not finished: while any is pending, a read-back
     * would show a state without it and the heart would flick back, so
     * read-backs are not published. [heartTapGeneration] changes on every
     * tap, so a read taken before a tap is never published after it.
     */
    private val heartLock = Any()
    private var pendingHeartTaps = 0
    private var heartTapGeneration = 0L

    private fun beginHeartTapLocked() {
        pendingHeartTaps++
        heartTapGeneration++
    }

    /**
     * Ends a heart tap however it ends (saved, failed or cancelled). The last
     * pending tap re-reads the stored state, so the heart can never be left
     * showing a guess that was not saved.
     */
    private suspend fun <T> finishHeartTap(write: suspend () -> T): T = try {
        write()
    } finally {
        val last = synchronized(heartLock) { --pendingHeartTaps == 0 }
        if (last) withContext(NonCancellable + Dispatchers.Default) { refresh() }
    }

    private suspend fun setLikedStored(track: GeneratedTrack, liked: Boolean): Boolean =
        withContext(Dispatchers.Default) {
            mutationMutex.withLock {
                // Every like is saved to Liked Songs. There is no switch for it.
                writeLikedPlaylist(track, liked)
                liked
            }
        }

    /**
     * Adds or removes [track] in the Liked Songs playlist as a compare-and-swap.
     *
     * The old blind write ignored replaceTracksForSync()'s result: when another
     * writer changed the list between the read and the write (playback pinning
     * a videoId, the startup backfill, a sync), the like was silently dropped
     * and never reached Liked Songs. A conflict now re-reads and retries; if it
     * still cannot land, this throws so the player says the like failed.
     */
    private suspend fun writeLikedPlaylist(track: GeneratedTrack, liked: Boolean) {
        repeat(LIKE_WRITE_ATTEMPTS) {
            val playlist = playlistRepository.getLikedSongs() ?: playlistRepository.ensureLikedSongs()
            val present = playlist.tracks.any { it.sameSongAs(track) }
            if (liked == present) return
            val updated = if (liked) playlist.tracks + track else playlist.tracks.filterNot { it.sameSongAs(track) }
            if (playlistRepository.replaceTracksForSync(playlist.id, updated, expectedTracks = playlist.tracks) != null) return
        }
        throw IllegalStateException("Liked Songs kept changing during the write")
    }

    /**
     * One-time move of likes made while the removed "Save to Liked Songs"
     * switch was off. Those were kept in a side record instead of the
     * playlist; they go into Liked Songs and the record is cleared.
     */
    private suspend fun migrateLocalOnlyLikes() {
        val raw = runCatching { settingsPreferences.localOnlyLikedTracks.first() }.getOrDefault("")
        if (raw.isBlank()) return
        val pending = runCatching {
            json.decodeFromString<List<StoredTrack>>(raw).map { it.toGenerated() }
        }.getOrDefault(emptyList())
        if (pending.isNotEmpty() && !mergeTracks(pending)) return
        settingsPreferences.setLocalOnlyLikedTracks("")
        if (pending.isNotEmpty()) {
            android.util.Log.i("LikedSongsManager", "Moved ${pending.size} likes into Liked Songs")
        }
    }

    /**
     * Merges externally sourced tracks (YT liked import) into Liked Songs.
     * Runs under the same [mutationMutex] as [toggle]/[like], so a heart-tap
     * racing an import can no longer interleave a stale read-modify-write
     * that resurrects duplicates.
     */
    suspend fun mergeTracks(tracks: List<GeneratedTrack>): Boolean = withContext(Dispatchers.Default) { mutationMutex.withLock {
        if (tracks.isEmpty()) return@withLock true
        // CAS retry: a blind writer (sync reconcile, manual add) may commit
        // between our read and write — re-read and re-merge instead of
        // overwriting their change or duplicating ours.
        repeat(3) {
            val liked = playlistRepository.ensureLikedSongs()
            val merged = (liked.tracks + tracks).distinctSongs()
            if (merged.size == liked.tracks.size) return@withLock true
            if (playlistRepository.replaceTracksForSync(liked.id, merged, liked.tracks) != null) {
                refresh()
                return@withLock true
            }
        }
        // Final attempt without CAS rather than dropping the import.
        val liked = playlistRepository.ensureLikedSongs()
        playlistRepository.replaceTracksForSync(liked.id, (liked.tracks + tracks).distinctSongs())
        refresh()
        true
    } }

    /**
     * One-time heal: collapses duplicate `liked`-mode rows into the one with
     * the most tracks, merging every removed row's songs into the keeper and
     * deleting the leftovers. A duplicate row's remote mirror is then swept
     * as an orphan by the next sync pass. Best-effort — never throws.
     *
     * @return number of duplicate playlists collapsed.
     */
    private suspend fun collapseDuplicateLikedRows(): Int = mutationMutex.withLock {
        val liked = try {
            playlistRepository.getAll().filter { it.mode == LIKED_SONGS_MODE }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            android.util.Log.w("LikedSongsManager", "Duplicate liked-row heal skipped", error)
            return@withLock 0
        }
        if (liked.size <= 1) return@withLock 0
        val keeper = liked.maxBy { it.tracks.size }
        val others = liked.filter { it.id != keeper.id }
        val merged = (keeper.tracks + others.flatMap { it.tracks }).distinctSongs()
        playlistRepository.replaceTracksForSync(keeper.id, merged, keeper.tracks)
        others.forEach { duplicate -> playlistRepository.delete(duplicate.id) }
        refresh()
        others.size
    }

    /**
     * One-time heal: collapses pre-existing duplicates (same videoId or same
     * normalized title+artist) keeping the first occurrence. Best-effort —
     * never throws, so startup can safely invoke it.
     *
     * @return number of duplicate entries removed.
     */
    suspend fun dedupe(): Int = mutationMutex.withLock {
        val liked = playlistRepository.getLikedSongs() ?: return@withLock 0
        val healed = liked.tracks.distinctSongs()
        val removed = liked.tracks.size - healed.size
        if (removed <= 0) return@withLock 0
        val written = runCatching {
            playlistRepository.replaceTracksForSync(liked.id, healed, liked.tracks)
        }.getOrNull()
        if (written != null) {
            refresh()
            removed
        } else {
            0
        }
    }

    private suspend fun refresh() {
        // Runs after the mutation already landed: a failed read-back must
        // neither fail the like nor blank the heart state, so keep the last
        // known keys instead.
        val generation = synchronized(heartLock) { heartTapGeneration }
        val keys = run {
            val liked = try {
                playlistRepository.getLikedSongs()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                android.util.Log.w("LikedSongsManager", "Liked Songs refresh failed", error)
                return
            }
            liked?.tracks?.flatMapTo(mutableSetOf()) { it.likedMarkers() }.orEmpty()
        }
        synchronized(heartLock) {
            // A tap that started during this read owns the heart until its own
            // final read-back; publishing this older read would flick it back.
            if (pendingHeartTaps == 0 && heartTapGeneration == generation) {
                _likedTrackKeys.value = keys
            }
        }
    }

    private suspend fun bootstrapForInstalledVersion() {
        val prefs = context.getSharedPreferences(BOOTSTRAP_PREFS, Context.MODE_PRIVATE)
        val versionCode = installedVersionCode()
        if (prefs.getLong(BOOTSTRAPPED_VERSION_KEY, Long.MIN_VALUE) == versionCode) return
        try {
            playlistRepository.ensureLikedSongs()
            prefs.edit().putLong(BOOTSTRAPPED_VERSION_KEY, versionCode).apply()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Do not mark the version: the next process launch retries safely.
        }
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()
    }

    private companion object {
        const val BOOTSTRAP_PREFS = "liked_songs_bootstrap"
        const val BOOTSTRAPPED_VERSION_KEY = "bootstrapped_version_code"
        const val LIKE_WRITE_ATTEMPTS = 4
    }
}
