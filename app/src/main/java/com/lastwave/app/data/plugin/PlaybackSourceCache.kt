package com.lastwave.app.data.plugin

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Expiry-aware cache of resolved module playback sources.
 *
 * Same song + module + quality while the source is still valid -> zero
 * provider calls (URLs typically live ~6h). Expired entries are kept for
 * cheap refresh (manifest-only, no search) via [peekExpired].
 */
@Singleton
class PlaybackSourceCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Serializable
    private data class Entry(
        val descriptorJson: String,
        val expiresAt: Long,
    )

    @Serializable
    private data class DiskState(
        val entries: Map<String, Entry> = emptyMap(),
    )

    companion object {
        private const val SKEW_MS = 120_000L
        private const val MAX_ENTRIES = 128
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val memory = LinkedHashMap<String, Entry>()
    private var diskLoaded = false

    fun keyFor(moduleId: String, title: String, artist: String, quality: String): String =
        "$moduleId|${normalize(title)}|${normalize(artist)}|${quality.uppercase()}"

    suspend fun get(key: String, nowMs: Long = System.currentTimeMillis()): String? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                ensureLoaded()
                val entry = memory[key] ?: return@withLock null
                if (nowMs > entry.expiresAt - SKEW_MS) return@withLock null
                entry.descriptorJson
            }
        }

    /** Last known descriptor regardless of expiry (refresh seed). */
    suspend fun peekExpired(key: String): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoaded()
            memory[key]?.descriptorJson
        }
    }

    suspend fun put(key: String, descriptorJson: String, expiresAt: Long) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                ensureLoaded()
                memory[key] = Entry(descriptorJson, expiresAt)
                while (memory.size > MAX_ENTRIES) {
                    memory.remove(memory.keys.first())
                }
                persist()
            }
        }

    suspend fun invalidate(key: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoaded()
            if (memory.remove(key) != null) persist()
        }
    }

    private fun stateFile(): File =
        File(File(context.filesDir, "provider_modules"), "sources.json")

    private fun ensureLoaded() {
        if (diskLoaded) return
        diskLoaded = true
        runCatching {
            val f = stateFile()
            if (f.isFile) {
                json.decodeFromString<DiskState>(f.readText()).entries
                    .entries.take(MAX_ENTRIES)
                    .forEach { (k, v) -> memory[k] = v }
            }
        }
    }

    private fun persist() {
        runCatching {
            stateFile().apply { parentFile?.mkdirs() }.writeText(
                json.encodeToString(DiskState.serializer(), DiskState(memory.toMap())),
            )
        }
    }

    private fun normalize(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
