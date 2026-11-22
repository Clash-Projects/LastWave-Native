package com.lastwave.app.data.localmusic

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Isolated offline-only library state for local audio files (offline likes and
 * user-curated offline playlists). Kept completely separate from online YouTube
 * Music / Spotify sync and Last.fm scrobbling.
 */
@Singleton
class LocalOfflineRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Serializable
    data class OfflinePlaylist(
        val id: String,
        val name: String,
        val trackUrls: List<String> = emptyList(),
        val createdAtMs: Long = System.currentTimeMillis(),
        val updatedAtMs: Long = System.currentTimeMillis(),
    )

    @Serializable
    private data class StoreSnapshot(
        val likedUrls: List<String> = emptyList(),
        val playlists: List<OfflinePlaylist> = emptyList(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()

    private val storeFile: File
        get() = File(File(context.filesDir, "local_music").apply { mkdirs() }, "offline_library.json")

    private var snapshot: StoreSnapshot = loadFromDisk()

    private val _likedUrls = MutableStateFlow(snapshot.likedUrls.mapTo(LinkedHashSet()) { normalizeUrl(it) })
    val likedUrls: StateFlow<Set<String>> = _likedUrls.asStateFlow()

    private val _playlists = MutableStateFlow(snapshot.playlists)
    val playlists: StateFlow<List<OfflinePlaylist>> = _playlists.asStateFlow()

    fun isLiked(url: String?): Boolean {
        val norm = url?.let(::normalizeUrl)?.takeIf { it.isNotBlank() } ?: return false
        return norm in _likedUrls.value
    }

    suspend fun toggleLike(url: String): Boolean = withContext(Dispatchers.IO) {
        val norm = normalizeUrl(url)
        if (norm.isBlank()) return@withContext false
        mutex.withLock {
            val current = snapshot.likedUrls.mapTo(LinkedHashSet()) { normalizeUrl(it) }
            val nowLiked = if (current.remove(norm)) false else { current.add(norm); true }
            commitLocked(snapshot.copy(likedUrls = current.toList()))
            nowLiked
        }
    }

    suspend fun setLiked(url: String, liked: Boolean) = withContext(Dispatchers.IO) {
        val norm = normalizeUrl(url)
        if (norm.isBlank()) return@withContext
        mutex.withLock {
            val current = snapshot.likedUrls.mapTo(LinkedHashSet()) { normalizeUrl(it) }
            val changed = if (liked) current.add(norm) else current.remove(norm)
            if (changed) commitLocked(snapshot.copy(likedUrls = current.toList()))
        }
    }

    suspend fun setAllLiked(urls: Collection<String>, liked: Boolean) = withContext(Dispatchers.IO) {
        val normalized = urls.map(::normalizeUrl).filter { it.isNotBlank() }
        if (normalized.isEmpty()) return@withContext
        mutex.withLock {
            val current = snapshot.likedUrls.mapTo(LinkedHashSet()) { normalizeUrl(it) }
            var changed = false
            for (u in normalized) {
                if (liked) {
                    if (current.add(u)) changed = true
                } else {
                    if (current.remove(u)) changed = true
                }
            }
            if (changed) commitLocked(snapshot.copy(likedUrls = current.toList()))
        }
    }

    suspend fun createPlaylist(
        name: String,
        initialTrackUrls: Collection<String> = emptyList(),
    ): OfflinePlaylist = withContext(Dispatchers.IO) {
        val cleanName = name.trim().ifBlank { "Offline Playlist" }
        val cleanUrls = initialTrackUrls.map(::normalizeUrl).filter { it.isNotBlank() }.distinct()
        val now = System.currentTimeMillis()
        mutex.withLock {
            val created = OfflinePlaylist(
                id = "offline_${UUID.randomUUID().toString().take(8)}",
                name = cleanName,
                trackUrls = cleanUrls,
                createdAtMs = now,
                updatedAtMs = now,
            )
            commitLocked(snapshot.copy(playlists = listOf(created) + snapshot.playlists))
            created
        }
    }

    suspend fun renamePlaylist(playlistId: String, newName: String) = withContext(Dispatchers.IO) {
        val clean = newName.trim()
        if (clean.isBlank()) return@withContext
        mutex.withLock {
            val updated = snapshot.playlists.map { pl ->
                if (pl.id == playlistId) pl.copy(name = clean, updatedAtMs = System.currentTimeMillis()) else pl
            }
            if (updated != snapshot.playlists) {
                commitLocked(snapshot.copy(playlists = updated))
            }
        }
    }

    suspend fun deletePlaylist(playlistId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val filtered = snapshot.playlists.filterNot { it.id == playlistId }
            if (filtered.size != snapshot.playlists.size) {
                commitLocked(snapshot.copy(playlists = filtered))
            }
        }
    }

    suspend fun addTracksToPlaylist(playlistId: String, urls: Collection<String>): Int = withContext(Dispatchers.IO) {
        val normalized = urls.map(::normalizeUrl).filter { it.isNotBlank() }
        if (normalized.isEmpty()) return@withContext 0
        mutex.withLock {
            var addedCount = 0
            val now = System.currentTimeMillis()
            val updated = snapshot.playlists.map { pl ->
                if (pl.id != playlistId) pl
                else {
                    val existing = pl.trackUrls.toMutableList()
                    val set = existing.mapTo(HashSet()) { normalizeUrl(it) }
                    for (u in normalized) {
                        if (set.add(u)) {
                            existing += u
                            addedCount++
                        }
                    }
                    if (addedCount > 0) pl.copy(trackUrls = existing, updatedAtMs = now) else pl
                }
            }
            if (addedCount > 0) {
                commitLocked(snapshot.copy(playlists = updated))
            }
            addedCount
        }
    }

    suspend fun removeTrackFromPlaylist(playlistId: String, url: String) = withContext(Dispatchers.IO) {
        val norm = normalizeUrl(url)
        mutex.withLock {
            val now = System.currentTimeMillis()
            val updated = snapshot.playlists.map { pl ->
                if (pl.id != playlistId) pl
                else {
                    val next = pl.trackUrls.filterNot { normalizeUrl(it) == norm }
                    if (next.size != pl.trackUrls.size) pl.copy(trackUrls = next, updatedAtMs = now) else pl
                }
            }
            if (updated != snapshot.playlists) {
                commitLocked(snapshot.copy(playlists = updated))
            }
        }
    }

    suspend fun removeTracks(urls: Collection<String>) = withContext(Dispatchers.IO) {
        val targets = urls.mapTo(HashSet()) { normalizeUrl(it) }
        if (targets.isEmpty()) return@withContext
        mutex.withLock {
            val nextLikes = snapshot.likedUrls.filterNot { normalizeUrl(it) in targets }
            val now = System.currentTimeMillis()
            val nextPlaylists = snapshot.playlists.map { pl ->
                val filtered = pl.trackUrls.filterNot { normalizeUrl(it) in targets }
                if (filtered.size != pl.trackUrls.size) pl.copy(trackUrls = filtered, updatedAtMs = now) else pl
            }
            val next = StoreSnapshot(likedUrls = nextLikes, playlists = nextPlaylists)
            if (next != snapshot) commitLocked(next)
        }
    }

    suspend fun pruneMissing(existingUrls: Set<String>) = withContext(Dispatchers.IO) {
        val valid = existingUrls.mapTo(HashSet()) { normalizeUrl(it) }
        mutex.withLock {
            val nextLikes = snapshot.likedUrls.filter { normalizeUrl(it) in valid }
            val now = System.currentTimeMillis()
            val nextPlaylists = snapshot.playlists.map { pl ->
                val kept = pl.trackUrls.filter { normalizeUrl(it) in valid }
                if (kept.size != pl.trackUrls.size) pl.copy(trackUrls = kept, updatedAtMs = now) else pl
            }
            val next = StoreSnapshot(likedUrls = nextLikes, playlists = nextPlaylists)
            if (next != snapshot) commitLocked(next)
        }
    }

    private fun commitLocked(next: StoreSnapshot) {
        snapshot = next
        _likedUrls.value = next.likedUrls.mapTo(LinkedHashSet()) { normalizeUrl(it) }
        _playlists.value = next.playlists
        persistToDisk(next)
    }

    private fun loadFromDisk(): StoreSnapshot {
        val f = storeFile
        if (!f.isFile || f.length() <= 0L) return StoreSnapshot()
        return runCatching {
            json.decodeFromString<StoreSnapshot>(f.readText())
        }.getOrDefault(StoreSnapshot())
    }

    private fun persistToDisk(data: StoreSnapshot) {
        runCatching {
            val target = storeFile
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(json.encodeToString(data))
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    companion object {
        fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim()
            return if (trimmed.startsWith("file://")) trimmed.removePrefix("file://") else trimmed
        }
    }
}
