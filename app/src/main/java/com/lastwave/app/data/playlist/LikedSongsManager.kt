package com.lastwave.app.data.playlist

import android.content.Context
import android.os.Build
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.StoredTrack
import com.lastwave.app.data.generate.distinctSongs
import com.lastwave.app.data.generate.sameSongAs
import com.lastwave.app.data.generate.toGenerated
import com.lastwave.app.data.generate.toStored
import com.lastwave.app.data.local.SettingsPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
                loadLocalOnlyTracks()
                watchLocalSync()
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

    suspend fun toggle(track: GeneratedTrack): Boolean = mutationMutex.withLock {
        val localSync = isLocalSyncEnabled()
        if (localSync) {
            val existing = playlistRepository.getLikedSongs()
            val currentlyLiked = existing?.tracks?.any { it.sameSongAs(track) } == true
            if (currentlyLiked) {
                playlistRepository.replaceTracksForSync(
                    existing!!.id,
                    existing.tracks.filterNot { it.sameSongAs(track) },
                )
                refresh()
                false
            } else {
                // Deletion is respected until the next actual Like action.
                val playlist = existing ?: playlistRepository.ensureLikedSongs()
                playlistRepository.replaceTracksForSync(playlist.id, (playlist.tracks + track).distinctSongs())
                refresh()
                true
            }
        } else {
            // The local playlist is switched off, so the like is kept in the app's own
            // record instead. The heart still has to tell the truth about it.
            val currentlyLiked = localOnlyTracks.any { it.sameSongAs(track) }
            if (currentlyLiked) {
                setLocalOnlyTracks(localOnlyTracks.filterNot { it.sameSongAs(track) })
                refresh()
                false
            } else {
                setLocalOnlyTracks((localOnlyTracks + track).distinctSongs())
                refresh()
                true
            }
        }
    }

    /** Artwork double-tap is intentionally idempotent: it never unlikes. */
    suspend fun like(track: GeneratedTrack): Boolean = mutationMutex.withLock {
        if (isLocalSyncEnabled()) {
            val existing = playlistRepository.getLikedSongs()
            if (existing?.tracks?.any { it.sameSongAs(track) } == true) return@withLock true
            val playlist = existing ?: playlistRepository.ensureLikedSongs()
            playlistRepository.replaceTracksForSync(playlist.id, (playlist.tracks + track).distinctSongs())
            refresh()
            true
        } else {
            if (localOnlyTracks.any { it.sameSongAs(track) }) return@withLock true
            setLocalOnlyTracks((localOnlyTracks + track).distinctSongs())
            refresh()
            true
        }
    }

    /** Whether a like is added to this app's own Liked Songs playlist. */
    private suspend fun isLocalSyncEnabled(): Boolean =
        settingsPreferences.likedSongsLocalSync.first()

    /**
     * The likes made while the local playlist was switched off.
     *
     * The playlist is the app's record of likes and the heart reads it. With the playlist
     * switched off there is nothing to read, so the likes are kept here instead - the heart
     * still has to tell the truth about what the user liked.
     */
    @Volatile
    private var localOnlyTracks: List<GeneratedTrack> = emptyList()

    private suspend fun setLocalOnlyTracks(tracks: List<GeneratedTrack>) {
        localOnlyTracks = tracks
        settingsPreferences.setLocalOnlyLikedTracks(
            json.encodeToString(tracks.map { it.toStored() }),
        )
    }

    private suspend fun loadLocalOnlyTracks() {
        val raw = settingsPreferences.localOnlyLikedTracks.first()
        localOnlyTracks = if (raw.isBlank()) {
            emptyList()
        } else {
            runCatching {
                json.decodeFromString<List<StoredTrack>>(raw).map { it.toGenerated() }
            }.getOrDefault(emptyList())
        }
    }

    /**
     * Merges externally sourced tracks (YT liked import) into Liked Songs.
     * Runs under the same [mutationMutex] as [toggle]/[like], so a heart-tap
     * racing an import can no longer interleave a stale read-modify-write
     * that resurrects duplicates.
     */
    suspend fun mergeTracks(tracks: List<GeneratedTrack>): Boolean = mutationMutex.withLock {
        if (tracks.isEmpty()) return@withLock true
        if (!isLocalSyncEnabled()) {
            // The local playlist is switched off, so the merged tracks go into the app's own
            // record instead - the same place the heart's likes go. Importing YouTube's
            // Liked Music must not quietly fill a list the user switched off.
            val merged = (localOnlyTracks + tracks).distinctSongs()
            if (merged.size == localOnlyTracks.size) return@withLock true
            setLocalOnlyTracks(merged)
            refresh()
            return@withLock true
        }
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
    }

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

    /**
     * Carries the likes across the local playlist being switched off and on again.
     *
     * Switching the local playlist off moves what is in it into the app's own record, so
     * the heart does not lose the likes it already had. Switching it back on puts them
     * into the playlist and empties the record.
     */
    private suspend fun watchLocalSync() {
        settingsPreferences.likedSongsLocalSync
            .distinctUntilChanged()
            .collect { enabled ->
                // A failed read or write must not end the collection: the sync would
                // then silently stop for the rest of the process.
                runCatching {
                    if (enabled) {
                        val pending = localOnlyTracks
                        if (pending.isNotEmpty()) {
                            val existing = playlistRepository.getLikedSongs()
                            val playlist = existing ?: playlistRepository.ensureLikedSongs()
                            playlistRepository.replaceTracksForSync(
                                playlist.id,
                                (playlist.tracks + pending).distinctSongs(),
                            )
                            setLocalOnlyTracks(emptyList())
                            refresh()
                            android.util.Log.i(
                                "LikedSongsManager",
                                "Restored ${pending.size} likes into Liked Songs",
                            )
                        }
                    } else {
                        val existing = playlistRepository.getLikedSongs()
                        val fromPlaylist = existing?.tracks.orEmpty()
                        if (fromPlaylist.isNotEmpty()) {
                            setLocalOnlyTracks((localOnlyTracks + fromPlaylist).distinctSongs())
                        }
                        refresh()
                    }
                }.onFailure {
                    android.util.Log.w("LikedSongsManager", "Following the local liked-song setting failed", it)
                }
            }
    }

    private suspend fun refresh() {
        // Runs after the mutation already landed: a failed read-back must
        // neither fail the like nor blank the heart state, so keep the last
        // known keys instead.
        if (!isLocalSyncEnabled()) {
            _likedTrackKeys.value = localOnlyTracks.mapTo(mutableSetOf()) { it.key }
            return
        }
        val liked = try {
            playlistRepository.getLikedSongs()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            android.util.Log.w("LikedSongsManager", "Liked Songs refresh failed", error)
            return
        }
        _likedTrackKeys.value = liked
            ?.tracks
            ?.mapTo(mutableSetOf()) { it.key }
            .orEmpty()
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
    }
}
