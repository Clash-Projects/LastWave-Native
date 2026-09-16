package com.lastwave.app.data.plugin

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a track through installed provider modules — one call per song.
 *
 * Order per module: valid cached source (zero calls) -> cheap refresh of an
 * expired source (manifest only) -> full resolvePlayback (match + manifest).
 * First success wins; null (never an exception) so the normal fallback chain
 * proceeds. Concurrent identical player resolves are already deduplicated
 * upstream by the player's resolution cache.
 */
@Singleton
class ModulePlaybackResolver @Inject constructor(
    private val manager: ModuleManager,
    private val runner: ModuleRunner,
    private val sourceCache: PlaybackSourceCache,
) {
    companion object {
        private const val TAG = "ModulePlaybackResolver"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Repo quality tier (27/7/6/5) mapped to the module quality id. */
    fun moduleQuality(repoQuality: Int): String = when (repoQuality) {
        7 -> "HI_RES_96"
        6 -> "HD"
        5 -> "SD"
        else -> "UHD"
    }

    suspend fun resolve(title: String, artist: String, repoQuality: Int): SegmentedStreamDescriptor? =
        withContext(Dispatchers.IO) {
            if (title.isBlank() || artist.isBlank()) return@withContext null
            val quality = moduleQuality(repoQuality)
            val handles = runCatching { manager.enabledHandles() }.getOrDefault(emptyList())
            for (handle in handles) {
                val descriptor = runCatching { resolveVia(handle, title, artist, quality) }.getOrNull()
                if (descriptor != null) return@withContext descriptor
            }
            null
        }

    private suspend fun resolveVia(
        handle: ProviderHandle,
        title: String,
        artist: String,
        quality: String,
    ): SegmentedStreamDescriptor? {
        val key = sourceCache.keyFor(handle.id, title, artist, quality)

        // 1. Valid cached source -> reuse, zero provider calls.
        sourceCache.get(key)?.let { cached ->
            decode(cached)?.let { return it }
            sourceCache.invalidate(key)
        }

        // 2. Expired source -> cheap refresh (manifest only, no search).
        sourceCache.peekExpired(key)?.let { stale ->
            decode(stale)?.let { prior ->
                val refreshed = runCatching {
                    runner.refreshPlayback(
                        handle, prior.trackId, quality,
                        prior.title, prior.artist, prior.album, prior.durationSec,
                    )
                }.getOrNull()?.let { decode(it) }
                if (refreshed != null && accept(refreshed)) {
                    sourceCache.put(key, encode(refreshed), effectiveExpiry(refreshed))
                    return refreshed
                }
                sourceCache.invalidate(key)
            }
        }

        // 3. Full single-op resolve (match + manifest).
        val raw = runner.resolvePlayback(handle, title, artist, 0, quality)
        if (raw.isBlank() || raw == "null") return null
        val descriptor = decode(raw) ?: return null
        if (!accept(descriptor)) return null
        sourceCache.put(key, raw, effectiveExpiry(descriptor))
        return descriptor
    }

    /** Quality badge from the extension's own labels; fallback when unreachable. */
    suspend fun badgeFor(descriptor: SegmentedStreamDescriptor, fallback: String): String {
        currentCoroutineContext().ensureActive()
        val handle = try {
            manager.findHandleById(descriptor.provider)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        } ?: return fallback
        val labels = try {
            runner.modulePolicy(handle)?.labels
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        } ?: return fallback
        return labels[descriptor.stream.quality.uppercase()]
            ?: labels[descriptor.stream.quality]
            ?: fallback
    }

    /** Whether the extension wants its downloads transcoded (default yes). */
    suspend fun shouldTranscode(descriptor: SegmentedStreamDescriptor): Boolean {
        currentCoroutineContext().ensureActive()
        val handle = try {
            manager.findHandleById(descriptor.provider)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        } ?: return true
        return try {
            runner.modulePolicy(handle)?.transcode != "never"
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            true
        }
    }
    private fun accept(descriptor: SegmentedStreamDescriptor): Boolean {
        if (descriptor.stream.baseUrl.isBlank()) return false
        // Progressive clear streams carry no segments; segmented ones must.
        if (descriptor.stream.type != "progressive" && descriptor.stream.segments.isEmpty()) return false
        if (descriptor.isExpired()) {
            Log.d(TAG, "descriptor expired, dropping")
            return false
        }
        return true
    }

    /** Direct URLs without server expiry are cached bounded (6h), not forever. */
    private fun effectiveExpiry(descriptor: SegmentedStreamDescriptor): Long =
        if (descriptor.expiresAt > 0) descriptor.expiresAt
        else System.currentTimeMillis() + 6 * 3600 * 1000L

    private fun decode(raw: String): SegmentedStreamDescriptor? =
        runCatching { json.decodeFromString<SegmentedStreamDescriptor>(raw) }.getOrNull()

    private fun encode(descriptor: SegmentedStreamDescriptor): String =
        json.encodeToString(SegmentedStreamDescriptor.serializer(), descriptor)
}
