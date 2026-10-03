package com.lastwave.app.data.artwork

import android.util.Log
import com.lastwave.app.data.local.db.ArtworkCacheDao
import com.lastwave.app.data.local.db.ArtworkCacheEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ArtworkPipeline"
private const val CRASH_TAG = "ArtworkCrash"

/** 30 days — same TTL as the original's _ART_DISK_TTL. */
private const val DISK_CACHE_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
private const val MAX_ARTWORK_DB_ENTRIES = 1_000
private const val ARTWORK_DB_CLEANUP_INTERVAL = 100
private const val MAX_ARTWORK_MEMORY_ENTRIES = 600

/** 30s per-track cooldown for the "Refresh Cover Art" force-refresh action —
 *  matches §1.7's spec exactly. */
private const val FORCE_REFRESH_COOLDOWN_MILLIS = 30_000L

/**
 * Faithful port of _resolveTrackArt(): memory cache -> disk (Room) cache ->
 * Last.fm track.getInfo -> iTunes. No provider chain beyond what the
 * original app actually has.
 *
 * Every entry point here is wrapped so a failure anywhere in this pipeline —
 * a Room error, a network exception, a malformed response — is caught,
 * logged, and treated as "no artwork yet" (falls through to the fallback
 * icon). It NEVER rethrows: an artwork lookup failing must never crash the
 * screen it's running on.
 */
@Singleton
class ArtworkRepository @Inject constructor(
    private val cacheDao: ArtworkCacheDao,
    private val lastFm: LastFmTrackInfoProvider,
    private val itunes: ITunesArtworkProvider,
    private val innerTube: com.lastwave.app.data.music.InnerTubeMusicApi,
    private val http: okhttp3.OkHttpClient,
) {
    private val _resolved = MutableStateFlow<Map<String, String>>(emptyMap())
    val resolved: StateFlow<Map<String, String>> = _resolved.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val savesSinceTrim = AtomicInteger(0)

    init {
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                // Only prewarm what the in-memory cache can retain. Reading
                // all 1,000 rows just to discard 400 on the first new cover
                // inflated startup DB work and the first map copy.
                val cachedEntities = cacheDao.getNewest(MAX_ARTWORK_MEMORY_ENTRIES).asReversed()
                val validMap = cachedEntities
                    .filter { it.url.isNotBlank() && now - it.timestampMillis < DISK_CACHE_TTL_MILLIS && it.provider in ArtworkNormalizer.HIGH_RES_PROVIDERS }
                    .associate { it.cacheKey to it.url }
                _resolved.update { validMap + it }
                Log.d(TAG, "Pre-warmed memory cache with ${validMap.size} high-res artwork entries")
                cacheDao.deleteOlderThan(now - DISK_CACHE_TTL_MILLIS)
                cacheDao.trimToNewest(MAX_ARTWORK_DB_ENTRIES)
            } catch (e: Exception) {
                Log.e(CRASH_TAG, "Error pre-warming artwork cache from DB", e)
            }
        }
    }

    // Avoids firing a second lookup for a track that's already mid-resolve.
    private val inFlight = mutableSetOf<String>()
    private val inFlightMutex = Mutex()
    // One visible list can request dozens of missing covers at once. Each
    // cover races three providers, so stay within the shared client's
    // 24-request ceiling while filling more than four visible rows at once.
    private val networkRaceSemaphore = Semaphore(8)

    /** Public entry point. Deliberately catches Throwable, not just
     *  Exception — an artwork miss must never take the app down, full stop. */
    suspend fun resolve(name: String, artist: String) {
        val key = ArtworkNormalizer.cacheKey(name, artist)
        try {
            resolveInternal(key, name, artist)
        } catch (cancellation: CancellationException) {
            inFlightMutex.withLock { inFlight.remove(key) }
            throw cancellation
        } catch (t: Throwable) {
            Log.e(CRASH_TAG, "Artwork lookup crashed and was suppressed | Track: $name | Artist: $artist | Cache key: $key", t)
            inFlightMutex.withLock { inFlight.remove(key) }
        }
    }

    private suspend fun resolveInternal(key: String, name: String, artist: String) {
        // 1. Memory cache — instant, 0ms
        _resolved.value[key]?.let {
            if (it.isNotBlank()) return
        }

        val alreadyRunning = inFlightMutex.withLock {
            if (key in inFlight) true else { inFlight.add(key); false }
        }
        if (alreadyRunning) return

        try {
            // 2. Disk (Room) cache — persists across relaunches.
            val cached = try {
                cacheDao.get(key)
            } catch (e: Exception) {
                null
            }
            if (cached != null && cached.url.isNotBlank() && System.currentTimeMillis() - cached.timestampMillis < DISK_CACHE_TTL_MILLIS) {
                if (cached.provider in ArtworkNormalizer.HIGH_RES_PROVIDERS) {
                    publish(key, cached.url)
                    return
                }
                // Old low-res entry (e.g. YouTube): publish immediately as placeholder, but proceed to resolve high-res master art!
                publish(key, cached.url)
            }

            // 3. Strict priority resolution: Spotify -> Apple Music -> Tidal -> Deezer -> YouTube Music (last fallback)
            val winner = networkRaceSemaphore.withPermit {
                withContext(Dispatchers.IO) {
                    val spotifyJob = async { safeFetch("Spotify", name, artist) { fetchSpotify(name, artist) } }
                    val appleJob = async { safeFetch("Apple Music", name, artist) { itunes.fetchArtworkUrl(name, artist) } }
                    val tidalJob = async { safeFetch("Tidal", name, artist) { fetchTidal(name, artist) } }
                    val deezerJob = async { safeFetch("Deezer", name, artist) { fetchDeezer(name, artist) } }

                    // Priority 1: Spotify
                    val spotifyUrl = spotifyJob.await()
                    if (!spotifyUrl.isNullOrBlank()) {
                        appleJob.cancel()
                        tidalJob.cancel()
                        deezerJob.cancel()
                        return@withContext "spotify" to spotifyUrl
                    }

                    // Priority 2: Apple Music (iTunes)
                    val appleUrl = appleJob.await()
                    if (!appleUrl.isNullOrBlank()) {
                        tidalJob.cancel()
                        deezerJob.cancel()
                        return@withContext "itunes" to appleUrl
                    }

                    // Priority 3: Tidal
                    val tidalUrl = tidalJob.await()
                    if (!tidalUrl.isNullOrBlank()) {
                        deezerJob.cancel()
                        return@withContext "tidal" to tidalUrl
                    }

                    // Priority 4: Deezer
                    val deezerUrl = deezerJob.await()
                    if (!deezerUrl.isNullOrBlank()) {
                        return@withContext "deezer" to deezerUrl
                    }

                    // Priority 5: YouTube Music (Only as last fallback if providers 1-4 found no verified match)
                    val ytUrl = safeFetch("YouTube Music", name, artist) { fetchYouTubeMusic(name, artist) }
                    if (!ytUrl.isNullOrBlank()) {
                        return@withContext "youtube" to ytUrl
                    }

                    null
                }
            }

            if (winner != null) {
                save(key, winner.first, winner.second)
            } else {
                publish(key, "")
            }
        } finally {
            inFlightMutex.withLock { inFlight.remove(key) }
        }
    }

    private val spotifyTrackRegex = Regex("""(?:spotify:track:|open\.spotify\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?track/)([a-zA-Z0-9]+)""")

    private suspend fun fetchSpotify(name: String, artist: String): String? = withContext(Dispatchers.IO) {
        val id = spotifyTrackRegex.find(name)?.groupValues?.getOrNull(1)
            ?: spotifyTrackRegex.find(artist)?.groupValues?.getOrNull(1)
        if (!id.isNullOrBlank()) {
            return@withContext fetchSpotifyOEmbed("https://open.spotify.com/track/$id")
        }
        null
    }

    private suspend fun fetchSpotifyOEmbed(url: String): String? {
        try {
            val oembedUrl = "https://open.spotify.com/oembed?url=${java.net.URLEncoder.encode(url, "UTF-8")}"
            val req = okhttp3.Request.Builder().url(oembedUrl).build()
            val body = http.newCall(req).awaitSuccessfulBodyOrNull() ?: return null
            val root = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject ?: return null
            val thumb = (root["thumbnail_url"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            if (!thumb.isNullOrBlank()) {
                // Upscale Spotify thumbnail from 300x300 (ab67616d00001e02) to 640x640 (ab67616d0000b273)
                return thumb.replace("ab67616d00001e02", "ab67616d0000b273")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        return null
    }

    private suspend fun fetchTidal(name: String, artist: String): String? = withContext(Dispatchers.IO) {
        val direct = queryTidal(name, artist)
        if (!direct.isNullOrBlank()) return@withContext direct
        val cleanedTitle = ArtworkNormalizer.cleanTitle(name)
        val cleanedArtist = ArtworkNormalizer.cleanArtist(artist)
        if (cleanedTitle != name || cleanedArtist != artist) {
            queryTidal(cleanedTitle, cleanedArtist)
        } else null
    }

    private suspend fun queryTidal(name: String, artist: String): String? {
        val query = if (artist.isNotBlank()) "$name $artist" else name
        val countryCode = java.util.Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase(java.util.Locale.ROOT) ?: "US"
        val url = "https://api.tidal.com/v1/search?query=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=5&types=TRACKS&countryCode=$countryCode"
        try {
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("X-Tidal-Token", "vNVdglQOjFJJGG2U")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val body = http.newCall(req).awaitSuccessfulBodyOrNull() ?: return null
            val root = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject ?: return null
            val tracks = root["tracks"] as? kotlinx.serialization.json.JsonObject ?: return null
            val items = (tracks["items"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }.orEmpty()

            for (item in items) {
                val candTitle = (item["title"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: continue
                val candVersion = (item["version"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                val effectiveCandTitle = if (!candVersion.isNullOrBlank()) "$candTitle ($candVersion)" else candTitle

                if (!lyricsTitleOk(effectiveCandTitle, name)) continue

                if (artist.isNotBlank()) {
                    val artistsArray = (item["artists"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { ((it as? kotlinx.serialization.json.JsonObject)?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                        .orEmpty()
                    val artistMatches = artistsArray.any { lyricsArtistOk(it, artist) } ||
                        lyricsArtistOk(artistsArray.joinToString(", "), artist)
                    if (!artistMatches) continue
                }

                val album = item["album"] as? kotlinx.serialization.json.JsonObject
                val coverUuid = (album?.get("cover") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                if (!coverUuid.isNullOrBlank()) {
                    val parts = coverUuid.split("-")
                    if (parts.size == 5) {
                        return "https://resources.tidal.com/images/${parts.joinToString("/")}/1280x1280.jpg"
                    }
                }
            }
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchDeezer(name: String, artist: String): String? = withContext(Dispatchers.IO) {
        val direct = queryDeezer(name, artist)
        if (!direct.isNullOrBlank()) return@withContext direct
        val cleanedTitle = ArtworkNormalizer.cleanTitle(name)
        val cleanedArtist = ArtworkNormalizer.cleanArtist(artist)
        if (cleanedTitle != name || cleanedArtist != artist) {
            queryDeezer(cleanedTitle, cleanedArtist)
        } else null
    }

    private suspend fun queryDeezer(name: String, artist: String): String? {
        val query = if (artist.isNotBlank()) "$name $artist" else name
        val url = "https://api.deezer.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=5"
        try {
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val body = http.newCall(req).awaitSuccessfulBodyOrNull() ?: return null
            val jsonEl = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
            val data = jsonEl?.get("data") as? kotlinx.serialization.json.JsonArray
            val items = data?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }.orEmpty()
            val best = items.maxByOrNull { deezerScore(it, name, artist) }
                ?.takeIf { deezerVerified(it, name, artist) } ?: return null
            val album = best.get("album") as? kotlinx.serialization.json.JsonObject
            return (album?.get("cover_xl") as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: (album?.get("cover_big") as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: ((best.get("artist") as? kotlinx.serialization.json.JsonObject)?.get("picture_xl") as? kotlinx.serialization.json.JsonPrimitive)?.content
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    private fun deezerText(obj: kotlinx.serialization.json.JsonObject, vararg keys: String): String {
        var cur: kotlinx.serialization.json.JsonElement? = obj
        for (k in keys) cur = (cur as? kotlinx.serialization.json.JsonObject)?.get(k)
        return (cur as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
    }

    private fun deezerVerified(item: kotlinx.serialization.json.JsonObject, name: String, artist: String): Boolean {
        if (!lyricsTitleOk(deezerText(item, "title"), name)) return false
        if (artist.isNotBlank() && !lyricsArtistOk(deezerText(item, "artist", "name"), artist)) return false
        return true
    }

    private fun deezerScore(item: kotlinx.serialization.json.JsonObject, name: String, artist: String): Int {
        var score = if (deezerText(item, "title").equals(name, ignoreCase = true)) 3
        else if (lyricsTitleOk(deezerText(item, "title"), name)) 1 else 0
        if (lyricsArtistOk(deezerText(item, "artist", "name"), artist)) score += 2
        return score
    }

    private fun lyricsTitleOk(songTitle: String, title: String): Boolean {
        if (songTitle.isBlank() || title.isBlank()) return false
        if (songTitle.equals(title, ignoreCase = true)) return true
        if (!com.lastwave.app.data.lyrics.LrclibLyricsApi.sameVersion(title, songTitle)) return false
        return com.lastwave.app.data.lyrics.LrclibLyricsApi.titlesMatch(
            com.lastwave.app.data.lyrics.LrclibLyricsApi.cleanTrackTitle(songTitle),
            com.lastwave.app.data.lyrics.LrclibLyricsApi.cleanTrackTitle(title),
        )
    }

    private fun lyricsArtistOk(songArtist: String, artist: String): Boolean {
        if (artist.isBlank() || songArtist.isBlank()) return false
        return com.lastwave.app.data.lyrics.LrclibLyricsApi.artistMatches(
            com.lastwave.app.data.lyrics.LrclibLyricsApi.cleanArtistName(songArtist),
            com.lastwave.app.data.lyrics.LrclibLyricsApi.cleanArtistName(artist),
        )
    }

    private suspend fun fetchYouTubeMusic(name: String, artist: String): String? = withContext(Dispatchers.IO) {
        try {
            val query = if (artist.isNotBlank()) "$name $artist" else name
            val results = innerTube.searchSongs(query, limit = 5, prefetchStreams = false)
            if (results.isEmpty()) return@withContext null
            val best = results
                .filter { !it.artworkUrl.isNullOrBlank() }
                .maxByOrNull { com.lastwave.app.data.music.TextMatch.matchScore(it, name, artist) }
                ?.takeIf { ytVerified(it.title, it.artist, name, artist) }
            val raw = best?.artworkUrl ?: return@withContext null
            ArtworkNormalizer.upscaleYoutubeArtwork(raw)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    /** Title must strictly agree in version and core title, AND artist must agree (when known). */
    private fun ytVerified(candTitle: String, candArtist: String, name: String, artist: String): Boolean {
        if (!lyricsTitleOk(candTitle, name)) return false
        if (artist.isNotBlank() && !lyricsArtistOk(candArtist, artist)) return false
        return true
    }

    /** Runs one provider call with its own try/catch */
    private suspend fun safeFetch(providerName: String, name: String, artist: String, block: suspend () -> String?): String? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.e(CRASH_TAG, "Provider threw and was suppressed | Provider: $providerName | Track: $name | Artist: $artist", e)
            null
        }

    private suspend fun save(key: String, provider: String, url: String) {
        publish(key, url)
        try {
            cacheDao.upsert(ArtworkCacheEntity(key, url, provider, System.currentTimeMillis()))
            if (savesSinceTrim.incrementAndGet() >= ARTWORK_DB_CLEANUP_INTERVAL &&
                savesSinceTrim.getAndSet(0) >= ARTWORK_DB_CLEANUP_INTERVAL
            ) {
                cacheDao.deleteOlderThan(System.currentTimeMillis() - DISK_CACHE_TTL_MILLIS)
                cacheDao.trimToNewest(MAX_ARTWORK_DB_ENTRIES)
            }
        } catch (e: Exception) {
            Log.e(CRASH_TAG, "Room write failed | Cache key: $key | Provider: $provider", e)
        }
    }

    private fun publish(key: String, url: String) {
        _resolved.update { current ->
            val next = current + (key to url)
            if (next.size <= MAX_ARTWORK_MEMORY_ENTRIES) next
            else next.entries.drop(next.size - MAX_ARTWORK_MEMORY_ENTRIES).associate { it.key to it.value }
        }
    }

    // ── Additions for §1.7 "Refresh Cover Art" + §4.2/§4.7 batch pre-warm ──

    private val lastForceRefresh = mutableMapOf<String, Long>()
    private val forceRefreshMutex = Mutex()

    /** Port of the "Refresh Cover Art" menu action: bypasses the memory +
     *  disk cache entirely and re-fetches from network, with a 30s per-
     *  track cooldown to prevent hammering both providers on repeat taps. */
    suspend fun forceRefresh(name: String, artist: String) {
        val key = ArtworkNormalizer.cacheKey(name, artist)
        val now = System.currentTimeMillis()
        val allowed = forceRefreshMutex.withLock {
            val last = lastForceRefresh[key] ?: 0L
            if (now - last < FORCE_REFRESH_COOLDOWN_MILLIS) false else {
                lastForceRefresh[key] = now
                true
            }
        }
        if (!allowed) return

        try {
            val fromSpotify = safeFetch("Spotify (force)", name, artist) { fetchSpotify(name, artist) }
            val resolved = if (!fromSpotify.isNullOrBlank()) {
                save(key, "spotify", fromSpotify)
                fromSpotify
            } else {
                val fromItunes = safeFetch("iTunes (force)", name, artist) { itunes.fetchArtworkUrl(name, artist) }
                if (!fromItunes.isNullOrBlank()) {
                    save(key, "itunes", fromItunes)
                    fromItunes
                } else {
                    val fromTidal = safeFetch("Tidal (force)", name, artist) { fetchTidal(name, artist) }
                    if (!fromTidal.isNullOrBlank()) {
                        save(key, "tidal", fromTidal)
                        fromTidal
                    } else {
                        val fromDeezer = safeFetch("Deezer (force)", name, artist) { fetchDeezer(name, artist) }
                        if (!fromDeezer.isNullOrBlank()) {
                            save(key, "deezer", fromDeezer)
                            fromDeezer
                        } else {
                            val fromYt = safeFetch("YouTube Music (force)", name, artist) { fetchYouTubeMusic(name, artist) }
                            if (!fromYt.isNullOrBlank()) {
                                save(key, "youtube", fromYt)
                                fromYt
                            } else {
                                save(key, "none", "")
                                ""
                            }
                        }
                    }
                }
            }
            Log.d(TAG, "Force-refreshed artwork | Track: $name | Artist: $artist | Result: $resolved")
        } catch (t: Throwable) {
            Log.e(CRASH_TAG, "Force-refresh crashed and was suppressed | Track: $name | Artist: $artist", t)
        }
    }

    /** Fire-and-forget batch warm — used to pre-enrich the first few tracks
     *  of a newly-saved playlist so its cover grid isn't empty on first
     *  render (§4.2, §4.7). Each item runs through the normal cached
     *  resolve() path, not forceRefresh(). */
    suspend fun enrichBatch(items: List<Pair<String, String>>) {
        // Chunk size matched to the OkHttp client's now-higher
        // maxRequestsPerHost (see NetworkModule) — the old chunk of 5 was
        // sized for the previous default limit, artificially throttling
        // batch pre-warms even after the client itself could handle more.
        items.chunked(10).forEach { batch ->
            coroutineScope {
                batch.map { (name, artist) -> launch { resolve(name, artist) } }.forEach { it.join() }
            }
        }
    }
}
