package com.lastwave.app.data.canvas

import android.util.Log
import com.lastwave.app.data.network.NetworkMonitor
import com.lastwave.app.playback.PlayableTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds the looping video that belongs behind a track's or a release's cover
 * art (Apple motion artwork, Tidal video cover, or Community Canvas manifest).
 *
 * Sources are queried in order:
 * 1. Spotify Canvas (official looping video loops, portrait 9:16)
 * 2. Apple Music (highest quality, richest animated album catalog)
 * 3. Tidal (1280x1280 square video covers)
 * 4. Community Canvas (ViMusic manifest for catalog gaps and indie tracks)
 *
 * Waterfall lookups are serialized with a Mutex to avoid overwhelming the network
 * on rapid track skips. Results (both hits and misses) are cached in a 64-entry LRU cache.
 */
@Singleton
class CanvasRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val networkMonitor: NetworkMonitor,
    private val spotifyCanvas: SpotifyCanvas,
) {

    private class Entry(val artwork: CanvasArtwork?, val withAlbum: Boolean)

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > CACHE_SIZE
    }

    private val lock = Mutex()

    /**
     * Resolves canvas for a [track], respecting cellular data settings. Never throws.
     */
    suspend fun canvasFor(track: PlayableTrack, cellularAllowed: Boolean = true): CanvasArtwork? {
        val title = track.title.cleaned()
        val artist = track.artist.cleaned()
        if (title.isBlank() || artist.isBlank()) return null

        val album = track.album?.takeIf { it.isNotBlank() }
        val id = track.videoId?.takeIf { it.isNotBlank() } ?: "$artist-$title"

        return canvasFor(
            title = title,
            artist = artist,
            album = album,
            trackId = id,
            durationMs = track.durationMs,
            cellularAllowed = cellularAllowed,
        )
    }

    /**
     * Resolves canvas for raw title/artist/album strings.
     */
    suspend fun canvasFor(
        title: String,
        artist: String,
        album: String?,
        trackId: String? = null,
        durationMs: Long? = null,
        cellularAllowed: Boolean = true,
    ): CanvasArtwork? {
        val cleanTitle = title.cleaned()
        val cleanArtist = artist.cleaned()
        if (cleanTitle.isBlank() || cleanArtist.isBlank()) return null

        val id = trackId ?: "$cleanArtist-$cleanTitle"
        val key = "song|$id"

        val cachedAnswer = synchronized(cache) { cache[key] }
        if (cachedAnswer != null && cachedAnswer.reusable(album != null)) {
            return cachedAnswer.artwork
        }

        if (!cellularAllowed && networkMonitor.isOnCellular()) {
            Log.d(TAG, "Skipping online canvas fetch on cellular connection (cellularAllowed=false)")
            return cachedAnswer?.artwork
        }

        return resolve(key, album != null) {
            firstHit(
                { spotifyCanvas.search(cleanTitle, cleanArtist, album, durationMs) },
                { AppleMusicCanvas.search(okHttpClient, cleanTitle, cleanArtist, album) },
                { TidalCanvas.search(okHttpClient, cleanTitle, cleanArtist, album) },
                { CommunityCanvas.search(okHttpClient, cleanTitle, cleanArtist, album) },
            ) { it.matches(cleanTitle, cleanArtist, album) }
        }
    }

    /**
     * Cached canvas for [track] if already resolved in memory.
     */
    fun cached(track: PlayableTrack): CanvasArtwork? {
        val id = track.videoId?.takeIf { it.isNotBlank() } ?: "${track.artist.cleaned()}-${track.title.cleaned()}"
        val key = "song|$id"
        return synchronized(cache) { cache[key]?.artwork }
    }

    /**
     * Resolves canvas for an entire album release.
     */
    suspend fun canvasForAlbum(
        album: String,
        artist: String,
        cellularAllowed: Boolean = true,
    ): CanvasArtwork? {
        val name = album.cleaned()
        val credit = artist.cleaned()
        if (name.isBlank() || credit.isBlank()) return null

        val key = "album|$name|$credit"
        val cachedAnswer = synchronized(cache) { cache[key] }
        if (cachedAnswer != null) return cachedAnswer.artwork

        if (!cellularAllowed && networkMonitor.isOnCellular()) {
            return null
        }

        return resolve(key, withAlbum = true) {
            firstHit(
                { AppleMusicCanvas.searchAlbum(okHttpClient, name, credit) },
                { TidalCanvas.searchAlbum(okHttpClient, name, credit) },
                { CommunityCanvas.search(okHttpClient, name, credit, name) },
            ) { it.matches(name, credit, name) }
        }
    }

    /**
     * Resolves Apple Music motion artwork video for an artist profile.
     */
    suspend fun canvasForArtist(
        artist: String,
        cellularAllowed: Boolean = true,
    ): CanvasArtwork? {
        val credit = artist.cleaned()
        if (credit.isBlank()) return null

        val key = "artist|$credit"
        val cachedAnswer = synchronized(cache) { cache[key] }
        if (cachedAnswer != null) return cachedAnswer.artwork

        if (!cellularAllowed && networkMonitor.isOnCellular()) {
            return null
        }

        return resolve(key, withAlbum = false) {
            AppleMusicCanvas.searchArtist(okHttpClient, credit)
        }
    }

    private suspend fun resolve(
        key: String,
        withAlbum: Boolean,
        lookUp: suspend () -> CanvasArtwork?,
    ): CanvasArtwork? = lock.withLock {
        synchronized(cache) {
            cache[key]?.let { if (it.reusable(withAlbum)) return@withLock it.artwork }
        }
        val found = withContext(Dispatchers.IO) { lookUp() }
        synchronized(cache) { cache[key] = Entry(found, withAlbum) }
        found
    }

    private fun Entry.reusable(withAlbum: Boolean): Boolean =
        artwork != null || this.withAlbum || !withAlbum

    private suspend fun firstHit(
        vararg sources: suspend () -> CanvasArtwork?,
        accept: (CanvasArtwork) -> Boolean,
    ): CanvasArtwork? {
        for (source in sources) {
            val found = runCatching { source() }
                .onFailure { Log.d(TAG, "Source lookup failed: ${it.message}") }
                .getOrNull() ?: continue
            if (!accept(found)) {
                Log.d(TAG, "Rejected canvas mismatch: '${found.title}' by '${found.artist}'")
                continue
            }
            return found
        }
        return null
    }

    private fun String.cleaned(): String = replace(NOISE, " ")
        .substringBefore(" | ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { this }

    private companion object {
        private const val TAG = "CanvasRepository"
        private const val CACHE_SIZE = 64

        private val NOISE = Regex(
            """\((?:from|official|lyrical|video|audio)[^)]*\)|\[[^]]*]|""" +
                """\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\b""",
            RegexOption.IGNORE_CASE,
        )
    }
}
