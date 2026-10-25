package com.lastwave.app.data.backup

import com.lastwave.app.data.generate.StoredTrack
import com.lastwave.app.data.playlist.LIKED_SONGS_MODE
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Three-way merge of two backup libraries, so the phone and LastWave
 * Desktop can both edit and then sync through one shared file without a
 * server. Kept in step with the desktop's `backup_sync.dart`.
 *
 * `base` is the file as this device last synced it (null on the first
 * sync), `local` this device's library, `remote` the file now. Comparing
 * each side with the base tells who added, edited or deleted what:
 * - playlists match by id, except Liked Songs which match by mode;
 * - tracks match by "name|artist" (lowercased), exclusions by trackKey;
 * - an edit beats a delete; without a base everything is kept (union);
 * - track order follows the file, local additions go at the end.
 *
 * `holds` tells which tracks the local device can keep (the desktop
 * cannot keep the phone's local songs); the phone keeps everything.
 */
object BackupMerge {
    data class Result(
        val playlists: List<BackupPlaylistSnapshot>,
        val exclusions: List<BackupRecommendationExclusionSnapshot>,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun trackKey(track: StoredTrack): String =
        "${track.name.orEmpty().trim().lowercase()}|${track.artist.orEmpty().trim().lowercase()}"

    fun playlistKey(playlist: BackupPlaylistSnapshot): String =
        if (playlist.mode == LIKED_SONGS_MODE) LIKED_SONGS_MODE else playlist.id.toString()

    fun tracksOf(playlist: BackupPlaylistSnapshot): List<StoredTrack> =
        json.decodeFromString(playlist.tracksJson)

    fun merge(
        base: BackupFile?,
        local: BackupFile,
        remote: BackupFile,
        holds: (StoredTrack) -> Boolean = { true },
    ): Result {
        val b = base?.playlists.orEmpty().associateBy(::playlistKey)
        val l = local.playlists.associateBy(::playlistKey)
        val r = remote.playlists.associateBy(::playlistKey)

        fun same(x: BackupPlaylistSnapshot, old: BackupPlaylistSnapshot) =
            meta(x) == meta(old) && visibleKeys(x, holds) == visibleKeys(old, holds)

        val playlists = (r.keys + l.keys).mapNotNull { key ->
            val old = if (base == null) null else b[key]
            val mine = l[key]
            val theirs = r[key]
            when {
                mine != null && theirs != null -> mergePlaylist(old, mine, theirs, holds)
                // Only here: new locally, or deleted in the file (kept if edited since).
                mine != null -> mine.takeIf { old == null || !same(it, old) }
                theirs != null -> theirs.takeIf { old == null || !same(it, old) }
                else -> null
            }
        }
        // Schema 6 and older files say nothing about exclusions: keep ours.
        val remoteExclusions = if (remote.schemaVersion >= 7) {
            remote.recommendationExclusions
        } else {
            local.recommendationExclusions
        }
        return Result(
            playlists,
            mergeByKey(
                base?.recommendationExclusions,
                local.recommendationExclusions,
                remoteExclusions,
            ) { it.trackKey },
        )
    }

    private fun meta(p: BackupPlaylistSnapshot) =
        listOf(p.title, p.subtitle, p.isPinned, p.customCoverUri, p.discoverSignature)

    private fun visibleKeys(p: BackupPlaylistSnapshot, holds: (StoredTrack) -> Boolean) =
        tracksOf(p).filter(holds).map(::trackKey)

    private fun mergePlaylist(
        base: BackupPlaylistSnapshot?,
        mine: BackupPlaylistSnapshot,
        theirs: BackupPlaylistSnapshot,
        holds: (StoredTrack) -> Boolean,
    ): BackupPlaylistSnapshot {
        val remoteTracks = tracksOf(theirs)
        val localTracks = tracksOf(mine)
        val baseTracks = base?.let(::tracksOf).orEmpty()
        val baseKeys = baseTracks.map(::trackKey).toSet()
        val localKeys = localTracks.map(::trackKey).toSet()
        val removed = baseTracks.filter { holds(it) && trackKey(it) !in localKeys }.map(::trackKey).toSet()
        val result = remoteTracks.filter { trackKey(it) !in removed }.toMutableList()
        val present = result.map(::trackKey).toMutableSet()
        for (track in localTracks) {
            val key = trackKey(track)
            if (key !in baseKeys && present.add(key)) result += track
        }
        fun <T> pick(get: (BackupPlaylistSnapshot) -> T): T =
            if (base != null && get(mine) != get(base)) get(mine) else get(theirs)
        return theirs.copy(
            title = pick { it.title },
            subtitle = pick { it.subtitle },
            isPinned = pick { it.isPinned },
            customCoverUri = pick { it.customCoverUri },
            discoverSignature = pick { it.discoverSignature },
            tracksJson = json.encodeToString(result),
        )
    }

    private fun <T> mergeByKey(
        base: List<T>?,
        local: List<T>,
        remote: List<T>,
        keyOf: (T) -> String,
    ): List<T> {
        val baseKeys = base?.map(keyOf)?.toSet()
        val localKeys = local.map(keyOf).toSet()
        // Deleted here since the last sync: drop it from the file too.
        val result = remote.filter { baseKeys == null || keyOf(it) !in baseKeys || keyOf(it) in localKeys }
            .toMutableList()
        val present = result.map(keyOf).toMutableSet()
        for (entry in local) {
            val key = keyOf(entry)
            if ((baseKeys == null || key !in baseKeys) && present.add(key)) result += entry
        }
        return result
    }
}
