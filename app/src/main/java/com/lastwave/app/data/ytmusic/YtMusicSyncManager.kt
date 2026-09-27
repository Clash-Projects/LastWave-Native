package com.lastwave.app.data.ytmusic

import android.util.Log
import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YtOwnedPlaylist
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.SavedPlaylist
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface YtSyncState {
    /** Connected + enabled, waiting for the next change/interval. */
    data object Idle : YtSyncState

    data class Running(val current: Int, val total: Int, val label: String) : YtSyncState

    data class Completed(
        val atMillis: Long,
        val syncedPlaylists: Int,
        val failedPlaylists: Int,
        val unmatchedTracks: Int,
    ) : YtSyncState

    data class Failed(val message: String) : YtSyncState
}

/**
 * Keeps every LastWave playlist mirrored to the user's YouTube Music account
 * ("sync here → there, 24/7") — including the built-in Liked Songs, which
 * syncs as its own private "Liked Songs" mirror. Importing FROM YT Music
 * stays selective — the user picks which playlists to import — but everything
 * saved in LastWave is pushed up automatically.
 *
 * Reconcile model (idempotent full-diff per playlist — safe to run any number
 * of times, on any trigger):
 *   1. Ensure a remote counterpart exists (mapping table in DataStore;
 *      created PRIVATE so nothing goes public without consent).
 *   2. Match each local track to a videoId via InnerTube search (cached).
 *   3. Read back the owned playlist with setVideoIds.
 *   4. Remove entries no longer desired; append missing ones (batched ≤50).
 *   5. Rename the remote when the local title changed.
 * Local deletions propagate too: orphaned mappings delete their remote.
 *
 * Triggers: app start, every playlist mutation (debounced), and a periodic
 * timer — single-flight mutex keeps overlapping runs harmless.
 */
@Singleton
class YtMusicSyncManager @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val innerTube: InnerTubeMusicApi,
    private val ytAuth: YtMusicAuthManager,
    private val preferences: YtMusicPreferences,
    private val libraryManager: YtMusicLibraryManager,
    private val applicationScope: CoroutineScope,
) {
    private val _state = MutableStateFlow<YtSyncState>(YtSyncState.Idle)
    val state: StateFlow<YtSyncState> = _state.asStateFlow()

    private val negativeMatchCache = ConcurrentHashMap<String, Long>()
    @Volatile private var started = false
    @Volatile private var sessionExpired = false

    fun start() {
        if (started) return
        started = true

        // Every playlist mutation eventually mirrors up. Debounced so bulk
        // operations (imports, restores) produce ONE sync pass, not hundreds.
        applicationScope.launch {
            playlistRepository.changes
                .debounce(DEBOUNCE_MS)
                .collect { runCatching { syncNow("change") } }
        }

        applicationScope.launch {
            delay(INITIAL_DELAY_MS)
            runCatching { syncNow("startup") }
        }

        // Standing heartbeat — catches anything missed while offline and
        // repairs drift made directly in YT Music's own apps.
        applicationScope.launch {
            while (true) {
                delay(PERIODIC_INTERVAL_MS)
                runCatching { syncNow("periodic") }
            }
        }
    }

    /**
     * One full reconcile pass. Returns false when skipped (not connected /
     * sync disabled / already running) or when it failed outright.
     */
    suspend fun syncNow(reason: String = "manual"): Boolean = preferences.playlistSyncMutex.withLock {
        val conn = ytAuth.connection.value
        if (!conn.isConnected) {
            sessionExpired = false
            _state.value = YtSyncState.Idle
            return false
        }
        if (!preferences.isSyncActive()) {
            _state.value = YtSyncState.Idle
            return false
        }

        // Proactively refresh credentials from system CookieManager if available or expired
        if (sessionExpired) {
            val refreshed = ytAuth.refreshCookiesFromCookieManager()
            if (!refreshed) {
                _state.value = YtSyncState.Failed("YouTube Music session expired. Please sign in again.")
                return false
            }
            sessionExpired = false
        } else {
            ytAuth.refreshCookiesFromCookieManager()
        }

        try {
            // Liked Songs included: it mirrors as a private "Liked Songs"
            // playlist. Selective-sync users opt in via the sync picker.
            val allPlaylists = playlistRepository.getAll().filterNot { it.remotePlaylistId != null }
            val syncedIds = preferences.syncedPlaylistIds.first()
            val playlists = if (syncedIds != null) allPlaylists.filter { it.id in syncedIds } else allPlaylists

            if (playlists.isEmpty()) {
                _state.value = YtSyncState.Completed(System.currentTimeMillis(), 0, 0, 0)
                preferences.setLastSyncAt(System.currentTimeMillis())
                libraryManager.refresh()
                return true
            }

            val mappings = preferences.mappings().toMutableMap()
            var unmatchedTotal = 0
            var failed = 0

            // Deletion propagation: mappings whose local playlist vanished
            // mean "deleted in LastWave" — delete the remote mirror too.
            val liveIds = allPlaylists.mapTo(mutableSetOf()) { it.id }
            val orphans = mappings.keys.filterNot { it in liveIds }
            var removedAnyMapping = false
            for (orphanId in orphans) {
                val removedMapping = mappings.remove(orphanId)
                val remoteId = removedMapping?.remotePlaylistId
                removedAnyMapping = true
                if (remoteId != null && removedMapping?.deleteRemoteWithLocal == true) {
                    runCatching { innerTube.deleteRemotePlaylist(remoteId) }
                        .onFailure { Log.w(TAG, "Orphan remote delete failed ($remoteId)", it) }
                }
            }
            // One persistence pass for ALL orphan removals instead of one
            // serialized full-map write per deleted playlist.
            if (removedAnyMapping) preferences.setMappings(mappings)

            var authFailure = false
            playlists.forEachIndexed { index, playlist ->
                if (authFailure) return@forEachIndexed
                _state.value = YtSyncState.Running(index + 1, playlists.size, playlist.title)
                try {
                    unmatchedTotal += reconcile(playlist, mappings)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    Log.w(TAG, "YT sync failed for \"${playlist.title}\"", e)
                    val isAuthError = (e is InnerTubeMusicApi.InnerTubeHttpException && (e.responseCode == 401 || e.responseCode == 403)) ||
                        (e.cause is InnerTubeMusicApi.InnerTubeHttpException && ((e.cause as InnerTubeMusicApi.InnerTubeHttpException).responseCode == 401 || (e.cause as InnerTubeMusicApi.InnerTubeHttpException).responseCode == 403))
                    if (isAuthError) {
                        authFailure = true
                    }
                }
                delay(WRITE_PACE_MS)
            }

            if (authFailure) {
                val recovered = ytAuth.refreshCookiesFromCookieManager()
                if (!recovered) {
                    sessionExpired = true
                    _state.value = YtSyncState.Failed("YouTube Music session expired. Please sign in again.")
                    return false
                }
            }

            val now = System.currentTimeMillis()
            preferences.setLastSyncAt(now)
            _state.value = YtSyncState.Completed(now, playlists.size - failed, failed, unmatchedTotal)
            libraryManager.refresh()
            Log.d(TAG, "YT sync ($reason): ${playlists.size - failed}/${playlists.size} ok, $unmatchedTotal unmatched")
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "YT sync pass failed", e)
            _state.value = YtSyncState.Failed(e.message ?: "Sync failed")
            return false
        }
    }

    /** Reconciles one playlist against its remote mirror; returns unmatched track count. */
    private suspend fun reconcile(playlist: SavedPlaylist, allMappings: MutableMap<Long, YtPlaylistMapping>): Int {
        var mapping = allMappings[playlist.id]
        var remoteId = mapping?.remotePlaylistId

        var remote: YtOwnedPlaylist? = null
        if (remoteId != null) {
            try {
                remote = innerTube.fetchOwnedPlaylist(remoteId)
            } catch (e: InnerTubeMusicApi.InnerTubeHttpException) {
                if (e.responseCode == 404) {
                    Log.w(TAG, "Linked remote playlist $remoteId for \"${playlist.title}\" was deleted on YouTube; recreating mirror")
                    remoteId = null
                    mapping = null
                    allMappings.remove(playlist.id)
                    preferences.setMappings(allMappings)
                } else {
                    throw e
                }
            }
        }
        var mutatedRemote = false
        if (remoteId != null && remote == null) {
            // Check if remote playlist was deleted / unlinked on YouTube
            val remoteExists = innerTube.fetchPlaylist(remoteId) != null
            if (!remoteExists) {
                Log.w(TAG, "Linked remote playlist $remoteId for \"${playlist.title}\" no longer exists on YouTube; recreating mirror")
                remoteId = null
                mapping = null
                allMappings.remove(playlist.id)
                preferences.setMappings(allMappings)
            } else {
                throw IllegalStateException("Could not read linked YouTube Music playlist: $remoteId")
            }
        }
        if (remoteId == null) {
            remoteId = innerTube.createRemotePlaylist(playlist.title)
                ?: throw IllegalStateException("Could not create YouTube Music playlist")
            mapping = YtPlaylistMapping(remoteId, playlist.title)
            allMappings[playlist.id] = mapping
            preferences.setMappings(allMappings)
            mutatedRemote = true
        } else if (remoteId != null && mapping?.remoteTitle != playlist.title && playlist.title.isNotBlank()) {
            innerTube.renameRemotePlaylist(remoteId, playlist.title)
            mapping = (mapping ?: YtPlaylistMapping(remoteId, "")).copy(remoteTitle = playlist.title)
            allMappings[playlist.id] = mapping
            preferences.setMappings(allMappings)
        }
        remoteId ?: throw IllegalStateException("Unreachable")

        // Resolve every local track to a videoId (order-preserving).
        // Bounded parallel matching: a 200-track playlist used to chain up to
        // 200 sequential InnerTube searches (~2.5s cap each) while holding the
        // single sync mutex; six-way parallelism cuts that ~6x with identical
        // results.
        if (negativeMatchCache.size > MAX_NEGATIVE_CACHE_ENTRIES) {
            val t = System.currentTimeMillis()
            negativeMatchCache.entries.removeIf { it.value < t }
        }
        val now = System.currentTimeMillis()
        val matchLimiter = Semaphore(MATCH_CONCURRENCY)
        val resolvedVideoIds = coroutineScope {
            playlist.tracks.map { track ->
                async {
                    track.youtubeVideoIdOrNull()?.let { return@async it }
                    matchLimiter.withPermit {
                        val cacheKey = "${track.name}|${track.artist}".lowercase().trim()
                        val negativeUntil = negativeMatchCache[cacheKey] ?: 0L
                        if (now < negativeUntil) {
                            null
                        } else {
                            innerTube.findBestMatchOrNull(track.name, track.artist)?.videoId.also { resolved ->
                                if (resolved == null) negativeMatchCache[cacheKey] = now + NEGATIVE_MATCH_TTL_MS
                                else negativeMatchCache.remove(cacheKey)
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        val desiredVideoIds = mutableListOf<String>()
        var unmatched = 0
        for (videoId in resolvedVideoIds) {
            if (videoId != null) desiredVideoIds += videoId else unmatched++
        }

        // Fresh read-back only when we mutated the remote this pass (create /
        // rename); otherwise the top verification fetch is already current.
        val currentRemote = if (mutatedRemote) {
            innerTube.fetchOwnedPlaylist(remoteId)
        } else {
            remote
        } ?: innerTube.fetchOwnedPlaylist(remoteId)
        ?: throw IllegalStateException("Could not read complete YouTube Music playlist")
        val remoteItems = currentRemote.items
        val remoteVideoIds = remoteItems.map { it.videoId }
        val baseline = mapping?.lastSyncedVideoIds.orEmpty().toSet()
        val localSet = desiredVideoIds.toSet()
        val remoteSet = remoteVideoIds.toSet()

        // Three-way merge against the previous successful baseline. Additions
        // from either app flow to the other; a removal on either side remains
        // a removal instead of being resurrected by the unchanged copy.
        val removedLocally = baseline - localSet
        val removedRemotely = baseline - remoteSet
        val finalSet = if (baseline.isEmpty()) {
            // A newly linked mirror has no shared history yet; LastWave is
            // authoritative for that first pass so stale remote entries do
            // not get imported as random local songs.
            localSet
        } else {
            (baseline - removedLocally - removedRemotely) +
                (localSet - baseline) + (remoteSet - baseline)
        }
        val finalVideoIds = buildList {
            desiredVideoIds.filterTo(this) { it in finalSet }
            remoteVideoIds.filterTo(this) { it in finalSet && it !in this }
        }

        val toRemove = remoteItems
            .filter { it.videoId !in finalSet }
            .map { item ->
                checkNotNull(item.setVideoId) { "Missing YouTube Music removal token" } to item.videoId
            }
        val toAdd = finalVideoIds.filter { it !in remoteSet }

        if (toRemove.isNotEmpty()) {
            check(innerTube.removeVideosFromRemotePlaylist(remoteId, toRemove)) {
                "YouTube Music rejected playlist removals"
            }
        }
        if (toAdd.isNotEmpty()) {
            check(innerTube.addVideosToRemotePlaylist(remoteId, toAdd)) {
                "YouTube Music rejected playlist additions"
            }
        }

        // Pull account-side additions/removals into the local copy. Unmatched
        // local tracks are preserved because they have no reliable video ID.
        if (finalVideoIds != desiredVideoIds) {
            val remoteMetadata = checkNotNull(innerTube.fetchPlaylist(remoteId)) {
                "Could not read YouTube Music track metadata"
            }.tracks
                .associateBy { it.videoId }
            val localByVideoId = resolvedVideoIds.mapIndexedNotNull { index, videoId ->
                videoId?.let { it to playlist.tracks[index] }
            }.toMap()
            val mergedTracks = buildList {
                playlist.tracks.forEachIndexed { index, track ->
                    val videoId = resolvedVideoIds[index]
                    if (videoId == null || videoId in finalSet) add(track)
                }
                val represented = resolvedVideoIds.filterNotNull().toSet()
                finalVideoIds.filterNot { it in represented }.forEach { videoId ->
                    val track = localByVideoId[videoId]
                        ?: remoteMetadata[videoId]?.toGeneratedTrack()
                    add(checkNotNull(track) { "Missing YouTube Music track metadata" })
                }
            }
            checkNotNull(playlistRepository.replaceTracksForSync(playlist.id, mergedTracks, playlist.tracks)) {
                "Playlist changed during sync; retry on the next pass"
            }
        }

        allMappings[playlist.id] = (allMappings[playlist.id] ?: YtPlaylistMapping(remoteId, playlist.title))
            .copy(
                remoteTitle = playlist.title,
                lastSyncAtMillis = now,
                lastSyncedVideoIds = finalVideoIds,
            )
        preferences.setMappings(allMappings)
        return unmatched
    }

    private companion object {
        const val TAG = "YtMusicSyncManager"
        const val DEBOUNCE_MS = 750L
        const val INITIAL_DELAY_MS = 2_000L
        const val PERIODIC_INTERVAL_MS = 60_000L
        const val WRITE_PACE_MS = 350L
        const val NEGATIVE_MATCH_TTL_MS = 6 * 60 * 60_000L
        const val MATCH_CONCURRENCY = 6
        const val MAX_NEGATIVE_CACHE_ENTRIES = 2048
    }
}
