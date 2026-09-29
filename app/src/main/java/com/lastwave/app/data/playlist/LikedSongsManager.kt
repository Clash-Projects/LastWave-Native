package com.lastwave.app.data.playlist

import android.content.Context
import android.os.Build
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.distinctSongs
import com.lastwave.app.data.generate.normalizeTrackText
import com.lastwave.app.data.generate.sameSongAs
import com.lastwave.app.widget.ActiveMediaSessionHolder
import com.lastwave.app.widget.WidgetSnapshot
import com.lastwave.app.widget.WidgetUpdater
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns the built-in local Liked Songs playlist and the player's heart state. */
@Singleton
class LikedSongsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistRepository: PlaylistRepository,
    private val applicationScope: CoroutineScope,
) {
    private val mutationMutex = Mutex()
    private val _likedTrackKeys = MutableStateFlow<Set<String>>(emptySet())
    val likedTrackKeys: StateFlow<Set<String>> = _likedTrackKeys.asStateFlow()
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        applicationScope.launch(Dispatchers.IO) {
            try {
                bootstrapForInstalledVersion()
                refresh()
                // One-time heal for duplicates stacked by older builds (weak
                // key dedup + unlocked YT-liked import). No-op when clean.
                runCatching { dedupe() }
                    .onSuccess { removed ->
                        if (removed > 0) android.util.Log.i("LikedSongsManager", "Healed $removed duplicate liked tracks")
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
    }

    /** Artwork double-tap is intentionally idempotent: it never unlikes. */
    suspend fun like(track: GeneratedTrack): Boolean = mutationMutex.withLock {
        val existing = playlistRepository.getLikedSongs()
        if (existing?.tracks?.any { it.sameSongAs(track) } == true) return@withLock true
        val playlist = existing ?: playlistRepository.ensureLikedSongs()
        playlistRepository.replaceTracksForSync(playlist.id, (playlist.tracks + track).distinctSongs())
        refresh()
        true
    }

    /**
     * Merges externally sourced tracks (YT liked import) into Liked Songs.
     * Runs under the same [mutationMutex] as [toggle]/[like], so a heart-tap
     * racing an import can no longer interleave a stale read-modify-write
     * that resurrects duplicates.
     */
    suspend fun mergeTracks(tracks: List<GeneratedTrack>): Boolean = mutationMutex.withLock {
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
        val keys = playlistRepository.getLikedSongs()
            ?.tracks
            ?.mapTo(mutableSetOf()) { it.key }
            .orEmpty()
        _likedTrackKeys.value = keys

        runCatching {
            val currentTrack = ActiveMediaSessionHolder.player?.state?.value?.current
            val trackKey = if (currentTrack != null) {
                "${currentTrack.title.normalizeTrackText()}|${currentTrack.artist.normalizeTrackText()}".lowercase()
            } else {
                val snapshot = WidgetSnapshot.read(context)
                if (snapshot.title.isNotBlank()) {
                    "${snapshot.title.normalizeTrackText()}|${snapshot.artist.normalizeTrackText()}".lowercase()
                } else null
            }
            if (trackKey != null) {
                val isFav = trackKey in keys
                WidgetUpdater.setFavorite(context, isFav)
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
    }
}
