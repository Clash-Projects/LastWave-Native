package com.lastwave.app.data.canvas

import android.util.Log
import com.lastwave.app.data.network.NetworkMonitor
import com.lastwave.app.data.spotify.SpotifyPreferences
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.util.ArtistHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

private const val REPO_LOG_TAG = "CanvasRepository"
private const val MEMORY_CACHE_CAPACITY = 64

private val collapseSpaceRegex = Regex("\\s+")
private val titleNoiseRegex = Regex(
    """\((?:from|official|lyrical|video|audio)[^)]*\)|\[[^]]*]|""" +
        """\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\b""",
    RegexOption.IGNORE_CASE,
)

@Singleton
class CanvasRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val networkMonitor: NetworkMonitor,
    private val spotifyCanvas: SpotifyCanvas,
    private val spotifyPreferences: SpotifyPreferences,
) {
    private class CachedResult(
        val artwork: CanvasArtwork?,
        val queriedWithAlbum: Boolean,
    ) {
        fun satisfies(needsAlbumContext: Boolean): Boolean =
            artwork != null || queriedWithAlbum || !needsAlbumContext
    }

    private val cacheGuard = Any()
    private val lruCache = object : LinkedHashMap<String, CachedResult>(MEMORY_CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedResult>): Boolean =
            size > MEMORY_CACHE_CAPACITY
    }
    private val fetchMutex = Mutex()

    @Volatile
    private var lastPreferSpotify: Boolean? = null

    suspend fun canvasFor(track: PlayableTrack, cellularAllowed: Boolean = true): CanvasArtwork? {
        val cleanTitle = stripNoise(track.title)
        val cleanArtist = stripNoise(track.artist)
        if (cleanTitle.isBlank() || cleanArtist.isBlank()) return null

        val albumTitle = track.album?.takeIf { it.isNotBlank() }
        val resolvedId = track.videoId?.takeIf { it.isNotBlank() } ?: "$cleanArtist-$cleanTitle"

        return canvasFor(
            title = cleanTitle,
            artist = cleanArtist,
            album = albumTitle,
            trackId = resolvedId,
            durationMs = track.durationMs,
            cellularAllowed = cellularAllowed,
        )
    }

    suspend fun canvasFor(
        title: String,
        artist: String,
        album: String?,
        trackId: String? = null,
        durationMs: Long? = null,
        cellularAllowed: Boolean = true,
    ): CanvasArtwork? {
        val cleanTitle = stripNoise(title)
        val cleanArtist = stripNoise(artist)
        if (cleanTitle.isBlank() || cleanArtist.isBlank()) return null

        val cacheKey = "song|${trackId ?: "$cleanArtist-$cleanTitle"}"
        val spotifyFirst = spotifyPreferences.isPreferSpotifyCanvas()
        invalidateIfOrderChanged(spotifyFirst)

        val hasAlbum = album != null
        val existing = synchronized(cacheGuard) { lruCache[cacheKey] }
        if (existing != null && existing.satisfies(hasAlbum)) {
            return existing.artwork
        }

        if (!cellularAllowed && networkMonitor.isOnCellular()) {
            Log.d(REPO_LOG_TAG, "Skipping online canvas fetch on cellular connection (cellularAllowed=false)")
            return existing?.artwork
        }

        return lookupAndStore(cacheKey, hasAlbum) {
            val providers = orderedSongProviders(
                spotifyFirst = spotifyFirst,
                title = cleanTitle,
                artist = cleanArtist,
                album = album,
                durationMs = durationMs,
            )
            selectMatchingArtwork(providers) { candidate ->
                candidate.matches(cleanTitle, cleanArtist, album)
            }
        }
    }

    fun cached(track: PlayableTrack): CanvasArtwork? {
        val resolvedId = track.videoId?.takeIf { it.isNotBlank() }
            ?: "${stripNoise(track.artist)}-${stripNoise(track.title)}"
        return synchronized(cacheGuard) { lruCache["song|$resolvedId"]?.artwork }
    }

    fun cached(title: String, artist: String, trackId: String? = null): CanvasArtwork? {
        val (idKey, nameKey) = trackCacheKeys(title, artist, trackId)
        return synchronized(cacheGuard) {
            val byId = if (idKey != null) lruCache[idKey]?.artwork else null
            byId ?: lruCache[nameKey]?.artwork
        }
    }

    fun hasResolved(title: String, artist: String, trackId: String? = null): Boolean {
        val (idKey, nameKey) = trackCacheKeys(title, artist, trackId)
        return synchronized(cacheGuard) {
            (idKey != null && lruCache.containsKey(idKey)) || lruCache.containsKey(nameKey)
        }
    }

    suspend fun canvasForAlbum(
        album: String,
        artist: String,
        cellularAllowed: Boolean = true,
    ): CanvasArtwork? {
        val cleanAlbum = stripNoise(album)
        val cleanArtist = stripNoise(artist)
        if (cleanAlbum.isBlank() || cleanArtist.isBlank()) return null

        val cacheKey = "album|$cleanAlbum|$cleanArtist"
        synchronized(cacheGuard) { lruCache[cacheKey] }?.let { return it.artwork }
        if (!cellularAllowed && networkMonitor.isOnCellular()) return null

        return lookupAndStore(cacheKey, withAlbum = true) {
            val providers = listOf<suspend () -> CanvasArtwork?>(
                { AppleMusicCanvas.searchAlbum(okHttpClient, cleanAlbum, cleanArtist) },
                { TidalCanvas.searchAlbum(okHttpClient, cleanAlbum, cleanArtist) },
            )
            selectMatchingArtwork(providers) { candidate ->
                candidate.matches(cleanAlbum, cleanArtist, cleanAlbum)
            }
        }
    }

    suspend fun canvasForArtist(
        artist: String,
        cellularAllowed: Boolean = true,
    ): CanvasArtwork? {
        val cleanArtist = stripNoise(artist)
        if (cleanArtist.isBlank()) return null

        val cacheKey = "artist|$cleanArtist"
        synchronized(cacheGuard) { lruCache[cacheKey] }?.let { return it.artwork }
        if (!cellularAllowed && networkMonitor.isOnCellular()) return null

        return lookupAndStore(cacheKey, withAlbum = false) {
            val found = AppleMusicCanvas.searchArtist(okHttpClient, cleanArtist) ?: return@lookupAndStore null
            val expected = cleanArtist.normalizeForMatch()
            val credited = ArtistHelper.splitArtists(found.artist).map { it.normalizeForMatch() }
            val sameArtist = expected.isNotEmpty() && (
                found.artist?.normalizeForMatch() == expected || credited.any { it == expected }
                )
            if (sameArtist) found else null
        }
    }

    private fun invalidateIfOrderChanged(spotifyFirst: Boolean) {
        val prev = lastPreferSpotify
        if (prev == null) {
            lastPreferSpotify = spotifyFirst
            return
        }
        if (prev != spotifyFirst) {
            Log.d(REPO_LOG_TAG, "Provider order changed; dropping the answers cached under the old one")
            synchronized(cacheGuard) { lruCache.clear() }
            lastPreferSpotify = spotifyFirst
        }
    }

    private fun orderedSongProviders(
        spotifyFirst: Boolean,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
    ): List<suspend () -> CanvasArtwork?> = buildList(3) {
        if (spotifyFirst) {
            add { spotifyCanvas.search(title, artist, album, durationMs) }
        }
        add { AppleMusicCanvas.search(okHttpClient, title, artist, album) }
        add { TidalCanvas.search(okHttpClient, title, artist, album) }
        if (!spotifyFirst) {
            add { spotifyCanvas.search(title, artist, album, durationMs) }
        }
    }

    private fun trackCacheKeys(title: String, artist: String, trackId: String?): Pair<String?, String> {
        val idKey = trackId?.takeIf { it.isNotBlank() }?.let { "song|$it" }
        val fallbackKey = "song|${stripNoise(artist)}-${stripNoise(title)}"
        return idKey to fallbackKey
    }

    private suspend fun lookupAndStore(
        cacheKey: String,
        withAlbum: Boolean,
        loader: suspend () -> CanvasArtwork?,
    ): CanvasArtwork? = fetchMutex.withLock {
        synchronized(cacheGuard) {
            lruCache[cacheKey]?.let { entry ->
                if (entry.satisfies(withAlbum)) return@withLock entry.artwork
            }
        }
        val artwork = withContext(Dispatchers.IO) {
            try {
                loader()
            } catch (ex: Exception) {
                Log.w(REPO_LOG_TAG, "Canvas lookup failed for '$cacheKey': ${ex.message}")
                null
            }
        }
        synchronized(cacheGuard) {
            lruCache[cacheKey] = CachedResult(artwork, withAlbum)
        }
        artwork
    }

    private suspend fun selectMatchingArtwork(
        providers: List<suspend () -> CanvasArtwork?>,
        validator: (CanvasArtwork) -> Boolean,
    ): CanvasArtwork? {
        for (provider in providers) {
            val candidate = try {
                provider()
            } catch (ex: Exception) {
                Log.d(REPO_LOG_TAG, "Source lookup failed: ${ex.message}")
                null
            } ?: continue

            if (!validator(candidate)) {
                Log.d(REPO_LOG_TAG, "Rejected canvas mismatch: '${candidate.title}' by '${candidate.artist}'")
                continue
            }
            return candidate
        }
        Log.d(REPO_LOG_TAG, "No canvas from any provider")
        return null
    }

    private fun stripNoise(raw: String): String {
        val cleaned = raw
            .replace(titleNoiseRegex, " ")
            .substringBefore(" | ")
            .replace(collapseSpaceRegex, " ")
            .trim()
        return cleaned.ifEmpty { raw }
    }
}
