package com.lastwave.app.data.generate

import android.util.Log
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.local.db.RecommendationExclusionDao
import com.lastwave.app.data.local.db.RecommendationExclusionEntity
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.local.db.SongPlayStatsDao
import com.lastwave.app.data.music.TextMatch
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.network.LastFmApiService
import com.lastwave.app.data.playlist.PlaylistRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GenerateRepository"
private const val GENERATE_REQUEST_TIMEOUT_MS = 20_000L
private val YOUTUBE_VIDEO_ID_REGEX = Regex("[A-Za-z0-9_-]{11}")
const val RECOMMENDATION_TRACK_COUNT = 35

/** Floor for regenerated "inspired" mixes — the generator relaxes artist
 *  diversity and widens its discovery net before ever returning fewer. */
const val MIN_TASTE_MIX_SIZE = 20

@Singleton
class GenerateRepository @Inject constructor(
    private val api: LastFmApiService,
    private val sessionPreferences: SessionPreferences,
    private val recommendationExclusionDao: RecommendationExclusionDao,
    private val tasteProfileProvider: TasteProfileProvider,
    private val playlistRepository: PlaylistRepository,
    private val viewingProfileState: com.lastwave.app.data.repository.ViewingProfileState,
    private val innerTube: com.lastwave.app.data.music.InnerTubeMusicApi,
    private val songPlayStatsDao: SongPlayStatsDao,
    private val downloadedTrackDao: DownloadedTrackDao,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightRequests = ConcurrentHashMap<String, Deferred<JsonObject>>()
    private val lastFmRequestGate = Semaphore(5)
    private val exclusionMutex = Mutex()
    private val savedPlaylistKeysMutex = Mutex()
    @Volatile private var exclusionKeysCache: Set<String>? = null
    @Volatile private var savedPlaylistKeysCache: Set<String>? = null

    init {
        requestScope.launch {
            playlistRepository.changes.collect {
                savedPlaylistKeysMutex.withLock { savedPlaylistKeysCache = null }
            }
        }
    }

    // Collapses concurrent, identical in-flight requests (e.g. two parallel
    // branches of fetchMix both wanting the same artist's top tracks at the
    // same moment) into a single network call. Entries are removed the
    // instant their call finishes — this is purely about not paying twice
    // for the same request at the same time, never a longer-lived/stale
    suspend fun call(params: Map<String, String>): JsonObject {
        val session = sessionPreferences.session.first()
        // No shared key: every caller already guards on key presence (or
        // degrades to YouTube/local), so a blank key here is a hard error
        // rather than a silent anonymous call.
        val apiKey = session.apiKey
        if (apiKey.isBlank()) throw IllegalStateException("Add your Last.fm API key in Settings → Integrations")
        val requestParams = params + ("api_key" to apiKey) + ("format" to "json")
        // Keep the in-flight key deterministic without retaining the private
        // API key in memory longer than the request itself.
        val requestKey = buildString {
            append(apiKey.hashCode())
            append('|')
            append(
                params.toSortedMap().entries.joinToString("&") {
                    "${it.key.length}:${it.key}=${it.value.length}:${it.value}"
                },
            )
        }
        val deferred = inFlightRequests.computeIfAbsent(requestKey) {
            requestScope.async {
                val response = withTimeout(GENERATE_REQUEST_TIMEOUT_MS) {
                    lastFmRequestGate.withPermit { api.get(requestParams) }
                }
                val body = response.body()?.string()
                if (!response.isSuccessful || body.isNullOrBlank()) {
                    throw IllegalStateException("Last.fm request failed (${response.code()})")
                }
                val parsed = json.parseToJsonElement(body).jsonObject
                parsed["error"]?.let {
                    throw IllegalStateException(parsed["message"]?.toString() ?: "Last.fm error")
                }
                parsed
            }.also { request ->
                request.invokeOnCompletion { inFlightRequests.remove(requestKey, request) }
            }
        }
        return try {
            deferred.await()
        } finally {
            if (deferred.isCompleted) inFlightRequests.remove(requestKey, deferred)
        }
    }



    /** Whichever profile is currently being viewed on Home (see
     *  ViewingProfileState) — a friend's username if the friend-switcher is
     *  active there, otherwise the signed-in session's own username. */
    private suspend fun username(): String =
        viewingProfileState.viewingUsername.value ?: sessionPreferences.session.first().username

    // ── Shared helpers — exact ports of shuffleArray / deduplicateTracks / _precheckTracks ──

    fun shuffle(tracks: List<GeneratedTrack>): List<GeneratedTrack> = tracks.shuffled()

    fun deduplicate(tracks: List<GeneratedTrack>): List<GeneratedTrack> {
        val seen = mutableSetOf<String>()
        return tracks.filter { seen.add(it.key) }
    }

    /** Port of _precheckTracks(): dedupe + cap at 3 tracks per artist. */
    fun precheck(tracks: List<GeneratedTrack>): List<GeneratedTrack> {
        val valid = tracks.filter { it.name.isNotBlank() && it.artist.isNotBlank() }
        val deduped = deduplicate(valid)
        val artistCount = mutableMapOf<String, Int>()
        return deduped.filter {
            val key = it.artist.lowercase().trim()
            val count = (artistCount[key] ?: 0) + 1
            artistCount[key] = count
            count <= 3
        }
    }

    /** Instant non-blocking pass-through matching web app.js generation speed.
     *  Audio resolution is performed on-demand when playing tracks. */
    suspend fun filterPlayable(tracks: List<GeneratedTrack>): List<GeneratedTrack> = tracks

    private fun YouTubeMusicTrack.toGeneratedTrack() = GeneratedTrack(
        name = title,
        artist = artist,
        artworkUrl = artworkUrl,
        url = "https://music.youtube.com/watch?v=$videoId",
        album = album,
    )

    /**
     * YouTube Music-first blend (replaces the old 50/50 Last.fm interleave).
     * YouTube candidates always lead; Last.fm only fills gaps that YouTube
     * didn't cover. Guest/offline callers pass an empty [lastFm] and get a
     * pure YouTube + local mix.
     */
    private fun blendSources(
        youtube: List<GeneratedTrack>,
        lastFm: List<GeneratedTrack>,
    ): List<GeneratedTrack> = youtubeFirstBlend(youtube, lastFm)

    private fun youtubeFirstBlend(
        youtube: List<GeneratedTrack>,
        lastFm: List<GeneratedTrack>,
    ): List<GeneratedTrack> {
        if (youtube.isEmpty()) return deduplicate(lastFm)
        if (lastFm.isEmpty()) return deduplicate(youtube)
        val seen = mutableSetOf<String>()
        val out = ArrayList<GeneratedTrack>(youtube.size + lastFm.size)
        for (t in youtube) {
            if (seen.add(t.key)) out.add(t)
        }
        for (t in lastFm) {
            if (seen.add(t.key)) out.add(t)
        }
        return out
    }

    /** True when Last.fm can be used as a supplementary source. Guest mode
     *  and signed-out states never touch Last.fm — they stay local-first. */
    private suspend fun isLastFmAvailable(): Boolean {
        val session = sessionPreferences.session.first()
        if (session.username.isBlank() || session.username.equals("Guest User", ignoreCase = true)) return false
        return true
    }

    /**
     * Local-first seed pool for guest/offline mixes: liked songs + saved
     * playlist tracks from Room (the on-device listening history proxy),
     * shuffled. Callers combine this with accountless InnerTube charts /
     * home songs when YouTube Music is unavailable.
     */
    private suspend fun localSeedPool(limit: Int = 40): List<GeneratedTrack> {
        if (limit <= 0) return emptyList()
        return try {
            val liked = try {
                playlistRepository.getLikedSongs()?.tracks.orEmpty()
            } catch (_: Exception) { emptyList() }
            val saved = try {
                playlistRepository.getAll().flatMap { it.tracks }
            } catch (_: Exception) { emptyList() }
            (liked + saved)
                .filter { it.name.isNotBlank() && it.artist.isNotBlank() }
                .distinctBy { it.key }
                .shuffled()
                .take(limit)
        } catch (_: Exception) { emptyList() }
    }

    /** Accountless public fallback: home songs, then charts. Never throws. */
    private suspend fun publicChartsFallback(limit: Int): List<GeneratedTrack> {
        if (limit <= 0) return emptyList()
        return try {
            val home = runCatching { innerTube.fetchHomeSongs() }.getOrDefault(emptyList())
            val charts = if (home.size < limit) {
                runCatching { innerTube.fetchCharts() }.getOrDefault(emptyList())
            } else emptyList()
            (home + charts).map { it.toGeneratedTrack() }.distinctBy { it.key }.take(limit)
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Guest / offline mix builder: local Room seeds (liked + saved) expanded
     * through accountless YouTube radio, padded with public charts. Used when
     * YouTube Music is unavailable and whenever Last.fm is disconnected.
     */
    suspend fun fetchLocalFallbackMix(limit: Int): List<GeneratedTrack> {
        val seeds = localSeedPool(limit = 8)
        val radio = mutableListOf<GeneratedTrack>()
        for (seed in seeds.take(3)) {
            try {
                radio += fetchYouTubeRadio(seed.name, seed.artist, seed.youtubeVideoIdOrNull(), limit = 12)
            } catch (_: Exception) { }
            if (radio.size >= limit) break
        }
        val charts = publicChartsFallback(limit)
        return deduplicate(filterRecommendationExclusions(radio + seeds + charts)).take(limit)
    }

    /**
     * YouTube Music-first mix from explicit seeds (radio generation entry
     * point). Each seed expands via [InnerTubeMusicApi.fetchRelatedSongs];
     * local Room seeds + public charts pad thin results so guest/offline
     * never returns short.
     */
    suspend fun generateMixFromSeeds(
        seeds: List<GeneratedTrack>,
        count: Int,
        onProgress: (String) -> Unit = {},
    ): List<GeneratedTrack> = kotlinx.coroutines.supervisorScope {
        val target = count.coerceIn(5, 50)
        onProgress("Starting your mix with YouTube Music…")
        val cleanSeeds = seeds.filter { it.name.isNotBlank() && it.artist.isNotBlank() }
            .distinctBy { it.key }.shuffled().take(5)
        if (cleanSeeds.isEmpty()) {
            return@supervisorScope fetchLocalFallbackMix(target)
        }
        val jobs = cleanSeeds.map { seed ->
            async(Dispatchers.IO) {
                try {
                    fetchYouTubeRadio(seed.name, seed.artist, seed.youtubeVideoIdOrNull(), limit = 20)
                } catch (_: Exception) { emptyList() }
            }
        }
        val radio = jobs.awaitAll().flatten()
        var pool = deduplicate(filterRecommendationExclusions(radio)).toMutableList()
        if (pool.size < target) {
            onProgress("Adding local favorites…")
            val local = localSeedPool(limit = target).filterNot { it.key in pool.mapTo(mutableSetOf()) { t -> t.key } }
            pool += local
        }
        if (pool.size < target) {
            val charts = publicChartsFallback(target * 2)
                .filterNot { c -> pool.any { it.key == c.key } }
            pool += charts
        }
        // Last.fm is strictly supplementary and only when connected.
        if (pool.size < target && runCatching { isLastFmAvailable() }.getOrDefault(false)) {
            for (seed in cleanSeeds.take(2)) {
                try {
                    val result = call(
                        mapOf("method" to "track.getsimilar", "track" to seed.name, "artist" to seed.artist, "limit" to "20"),
                    )
                    val extra = GenerateJson.normalise(result["similartracks"]?.jsonObject?.get("track"))
                    val known = pool.mapTo(mutableSetOf()) { it.key }
                    pool += extra.filter { it.key !in known }
                } catch (_: Exception) { }
                if (pool.size >= target) break
            }
        }
        precheck(pool).take(target).ifEmpty { deduplicate(pool).take(target) }
    }

    private suspend fun resolveSeedVideoId(
        track: String,
        artist: String,
        seedVideoId: String? = null,
    ): String? {
        if (!seedVideoId.isNullOrBlank() && YOUTUBE_VIDEO_ID_REGEX.matches(seedVideoId)) {
            return seedVideoId
        }
        // 1. Strict best match with scoring
        innerTube.findBestMatchOrNull(track, artist, prefetchStreams = false)?.videoId?.let { return it }

        // 2. Direct search "$track $artist"
        val query1 = listOf(track, artist).filter { it.isNotBlank() }.joinToString(" ")
        if (query1.isNotBlank()) {
            runCatching {
                innerTube.searchSongs(query1, limit = 5, prefetchStreams = false)
            }.getOrNull()?.firstOrNull()?.videoId?.let { return it }
        }

        // 3. Direct search "$artist $track"
        val query2 = listOf(artist, track).filter { it.isNotBlank() }.joinToString(" ")
        if (query2.isNotBlank() && query2 != query1) {
            runCatching {
                innerTube.searchSongs(query2, limit = 5, prefetchStreams = false)
            }.getOrNull()?.firstOrNull()?.videoId?.let { return it }
        }

        return null
    }

    private fun cleanTitleForComparison(title: String): String {
        return title.lowercase()
            .replace(Regex("\\s*[({\\[][^)\\]]*[)}\\]]"), " ")
            .replace(Regex("(?i)\\b(official\\s+video|official\\s+audio|lyrics?|remix|mix|edit|bootleg|flip|vip|slowed|reverb|sped\\s+up|speed\\s+up|cover|version|live|acoustic|instrumental|feat\\.?|ft\\.?)\\b.*"), " ")
            .replace(Regex("[^a-z0-9]"), "")
            .trim()
    }

    private fun isSameSongOrEdit(
        candidateName: String,
        candidateArtist: String,
        seedTrack: String,
        seedArtist: String,
    ): Boolean {
        val cleanSeed = cleanTitleForComparison(seedTrack)
        val cleanCandidate = cleanTitleForComparison(candidateName)
        if (cleanCandidate.isBlank() || cleanSeed.isBlank()) return false

        // 1. Identical normalized base title (e.g. "Deep Swim" vs "Deep Swim (VIP)" or "Deep Swim (Remix)" or covers)
        if (cleanCandidate == cleanSeed) return true

        // 2. Candidate raw title mentions seed track AND has remix/cover/edit/version indicators
        val lowerCandidate = candidateName.lowercase()
        val lowerSeed = seedTrack.lowercase().trim()
        if (lowerSeed.length >= 3 && lowerCandidate.contains(lowerSeed)) {
            val editIndicators = listOf(
                "remix", "edit", "mix", "cover", "version", "flip", "bootleg",
                "vip", "slowed", "reverb", "sped", "speed", "acoustic", "instrumental",
                "tribute", "karaoke", "live", "rework", "dub", "mashup", "rendition",
            )
            if (editIndicators.any { lowerCandidate.contains(it) }) return true

            val lowerArtist = seedArtist.lowercase().trim()
            if (lowerArtist.length >= 3 && lowerCandidate.contains(lowerArtist)) return true
        }

        // 3. For long specific titles (>= 10 chars), check direct containment
        if (cleanSeed.length >= 10 && cleanCandidate.contains(cleanSeed)) return true
        if (cleanCandidate.length >= 10 && cleanSeed.contains(cleanCandidate)) return true

        return false
    }

    private suspend fun fetchYouTubeRadio(
        track: String,
        artist: String,
        seedVideoId: String? = null,
        limit: Int,
    ): List<GeneratedTrack> {
        val seed = resolveSeedVideoId(track, artist, seedVideoId) ?: return emptyList()
        return innerTube.fetchRelatedSongs(seed, limit, prefetchStreams = false)
            .map { it.toGeneratedTrack() }
    }

    private suspend fun fetchYouTubeDiscovery(
        seeds: List<GeneratedTrack>,
        limit: Int,
    ): List<GeneratedTrack> {
        val seed = seeds.filter { it.name.isNotBlank() && it.artist.isNotBlank() }.shuffled().firstOrNull()
        if (seed != null) {
            val radio = try {
                fetchYouTubeRadio(seed.name, seed.artist, seed.youtubeVideoIdOrNull(), limit)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList()
            }
            if (radio.isNotEmpty()) return radio
        }
        val home = innerTube.fetchHomeSongs()
        val fallback = if (home.isNotEmpty()) home else innerTube.fetchCharts()
        return fallback.take(limit).map { it.toGeneratedTrack() }
    }


    // Explicit-only recommendation exclusions. Nothing is added automatically.

    suspend fun excludeFromRecommendations(trackName: String, artistName: String): Boolean {
        if (trackName.isBlank() || artistName.isBlank()) return false
        val track = GeneratedTrack(trackName, artistName, artworkUrl = null)
        return exclusionMutex.withLock {
            try {
                recommendationExclusionDao.upsert(
                    RecommendationExclusionEntity(
                        trackKey = track.key,
                        excludedAtMillis = System.currentTimeMillis(),
                        trackName = trackName.trim(),
                        artistName = artistName.trim(),
                    ),
                )
                exclusionKeysCache = (exclusionKeysCache ?: recommendationExclusionDao.getAll()
                    .mapTo(mutableSetOf()) { it.trackKey }) + track.key
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to exclude track from recommendations", e)
                false
            }
        }
    }

    /** All songs already saved in any local playlist. This is a soft
     *  familiarity signal only; callers must never turn it into a blacklist. */
    suspend fun savedPlaylistTrackKeys(): Set<String> {
        savedPlaylistKeysCache?.let { return it }
        return savedPlaylistKeysMutex.withLock {
            savedPlaylistKeysCache?.let { return@withLock it }
            try {
                playlistRepository.getAll().flatMapTo(mutableSetOf()) { playlist ->
                    playlist.tracks.map { it.key }
                }.also { savedPlaylistKeysCache = it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.d(TAG, "Could not read saved-playlist tracks", error)
                emptySet()
            }
        }
    }

    /** Keeps ranked relevance while limiting familiar songs to roughly one
     *  in six when enough new candidates exist. Familiar songs fill any
     *  shortage, so this deliberately remains a preference, not exclusion. */
    fun preferPlaylistFreshness(
        tracks: List<GeneratedTrack>,
        limit: Int,
        savedKeys: Set<String>,
    ): List<GeneratedTrack> {
        if (limit <= 0) return emptyList()
        val unique = deduplicate(tracks.filter { it.name.isNotBlank() && it.artist.isNotBlank() })
        val target = minOf(limit, unique.size)
        if (target == 0 || savedKeys.isEmpty()) return unique.take(target)

        val familiarCap = maxOf(1, target / 6)
        val selected = ArrayList<GeneratedTrack>(target)
        val deferredFamiliar = ArrayList<GeneratedTrack>()
        var familiarCount = 0
        for (track in unique) {
            if (track.key in savedKeys && familiarCount >= familiarCap) {
                deferredFamiliar += track
                continue
            }
            selected += track
            if (track.key in savedKeys) familiarCount++
            if (selected.size == target) return selected
        }
        selected += deferredFamiliar.take(target - selected.size)
        return selected
    }

    suspend fun recommendationExclusionKeys(): Set<String> {
        exclusionKeysCache?.let { return it }
        return exclusionMutex.withLock {
            exclusionKeysCache ?: try {
                recommendationExclusionDao.getAll().mapTo(mutableSetOf()) { it.trackKey }
                    .also { exclusionKeysCache = it }
            } catch (e: Exception) {
                Log.e(TAG, "Recommendation-exclusion read failed", e)
                emptySet()
            }
        }
    }

    /** Hard exclusion: these tracks never return until the list is cleared. */
    suspend fun filterRecommendationExclusions(tracks: List<GeneratedTrack>): List<GeneratedTrack> {
        if (tracks.isEmpty()) return emptyList()
        val exclusions = recommendationExclusionKeys()
        return tracks.filterNot { it.key in exclusions }
    }

    suspend fun clearRecommendationExclusions() = exclusionMutex.withLock {
        try {
            recommendationExclusionDao.clear()
            exclusionKeysCache = emptySet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear recommendation exclusions", e)
        }
    }

    suspend fun recommendationExclusionCount(): Int = recommendationExclusionKeys().size

    fun observeRecommendationExclusions() = recommendationExclusionDao.observeAll()

    suspend fun removeRecommendationExclusion(trackKey: String): Boolean = exclusionMutex.withLock {
        try {
            recommendationExclusionDao.delete(trackKey)
            exclusionKeysCache = exclusionKeysCache?.minus(trackKey)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore excluded recommendation", e)
            false
        }
    }

    fun invalidateRecommendationExclusionCache() {
        exclusionKeysCache = null
    }

    // ── Fetch modes ──

    suspend fun fetchChartTracks(limit: Int = 30): List<GeneratedTrack> {
        val page = (1..3).random()
        val lastFm = try {
            val result = call(mapOf("method" to "chart.gettoptracks", "limit" to (limit * 2).coerceAtLeast(limit).toString(), "page" to page.toString()))
            val tracks = GenerateJson.normalise(result["tracks"]?.jsonObject?.get("track"))
            filterPlayable(shuffle(filterRecommendationExclusions(tracks))).take(limit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            emptyList()
        }
        if (lastFm.isNotEmpty()) return lastFm
        val charts = innerTube.fetchCharts()
        val fallback = if (charts.isNotEmpty()) charts else innerTube.fetchHomeSongs()
        return filterRecommendationExclusions(fallback.map { it.toGeneratedTrack() }).take(limit)
    }

    suspend fun fetchTopTracks(limit: Int, period: String = "overall"): List<GeneratedTrack> {
        val user = username()
        if (user.isBlank() || user.equals("Guest User", ignoreCase = true)) {
            return fetchChartTracks(limit)
        }
        val page = (1..3).random()
        return try {
            val result = call(
                mapOf("method" to "user.gettoptracks", "user" to user, "period" to period, "limit" to (limit * 2).coerceAtLeast(limit).toString(), "page" to page.toString()),
            )
            val tracks = GenerateJson.normalise(result["toptracks"]?.jsonObject?.get("track"))
            val playable = filterPlayable(shuffle(filterRecommendationExclusions(tracks))).take(limit)
            if (playable.isNotEmpty()) playable else fetchChartTracks(limit)
        } catch (e: Exception) {
            fetchChartTracks(limit)
        }
    }

    suspend fun fetchRecentTracks(limit: Int): List<GeneratedTrack> {
        val user = username()
        if (user.isBlank() || user.equals("Guest User", ignoreCase = true)) {
            return fetchChartTracks(limit)
        }
        return try {
            val result = call(mapOf("method" to "user.getrecenttracks", "user" to user, "limit" to (limit * 2).coerceAtLeast(limit).toString()))
            val raw = result["recenttracks"]?.jsonObject?.get("track")
            val withoutNowPlaying = GenerateJson.asObjectList(raw)
                .filterNot { it["@attr"]?.jsonObject?.get("nowplaying") != null }
            val tracks = filterPlayable(
                shuffle(filterRecommendationExclusions(GenerateJson.normalise(JsonArray(withoutNowPlaying)))),
            ).take(limit)
            if (tracks.isNotEmpty()) tracks else fetchChartTracks(limit)
        } catch (e: Exception) {
            fetchChartTracks(limit)
        }
    }

    suspend fun fetchSimilarTracks(
        track: String,
        artist: String,
        limit: Int,
        seedVideoId: String? = null,
    ): List<GeneratedTrack> = kotlinx.coroutines.supervisorScope {
        val targetSize = limit.coerceIn(25, 35)
        val resolvedVideoId = resolveSeedVideoId(track, artist, seedVideoId)

        // 1. Primary discovery: YouTube Music Radio
        var youtubeTracks: List<YouTubeMusicTrack> = emptyList()
        if (resolvedVideoId != null) {
            try {
                val primaryRadio = innerTube.fetchRelatedSongs(
                    videoId = resolvedVideoId,
                    limit = maxOf(targetSize * 2, 50),
                    prefetchStreams = false,
                )
                val list = primaryRadio.toMutableList()
                if (list.size < 40 && list.isNotEmpty()) {
                    val branchSeed = list.firstOrNull { it.videoId != resolvedVideoId }?.videoId
                    if (branchSeed != null) {
                        val branchRadio = innerTube.fetchRelatedSongs(
                            videoId = branchSeed,
                            limit = 35,
                            prefetchStreams = false,
                        )
                        val existingIds = list.mapTo(mutableSetOf()) { it.videoId }
                        for (item in branchRadio) {
                            if (existingIds.add(item.videoId)) {
                                list.add(item)
                            }
                        }
                    }
                }
                youtubeTracks = list
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.d(TAG, "YouTube Music radio error", error)
            }
        }

        // 2. Secondary fallback: Last.fm
        val lastFmTracks: List<GeneratedTrack> = if (youtubeTracks.size < targetSize) {
            try {
                val result = call(
                    mapOf("method" to "track.getsimilar", "track" to track, "artist" to artist, "limit" to "100"),
                )
                GenerateJson.normalise(result["similartracks"]?.jsonObject?.get("track")).shuffled()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.d(TAG, "Last.fm similar-track fallback unavailable", error)
                emptyList()
            }
        } else {
            emptyList()
        }

        // 3. Combine raw candidates prioritizing YouTube Music radio
        val rawCandidates = (youtubeTracks.map { it.toGeneratedTrack() } + lastFmTracks)

        // 4. Strict filtering:
        //    - No exact seed track
        //    - No edits/remixes/covers of the seed track ("not same song of different artist edit n all")
        //    - Truly different similar songs of that song related taste!
        val seedKey = GeneratedTrack(track, artist, null).key

        val filtered = rawCandidates.filter { candidate ->
            if (candidate.name.isBlank() || candidate.artist.isBlank()) return@filter false
            if (candidate.key == seedKey) return@filter false
            if (resolvedVideoId != null && candidate.youtubeVideoIdOrNull() == resolvedVideoId) return@filter false
            if (isSameSongOrEdit(candidate.name, candidate.artist, track, artist)) return@filter false
            true
        }

        // 5. Unique songs only (no duplicate song titles with different edits/remixes)
        val seenTitles = mutableSetOf<String>()
        val distinctSongs = mutableListOf<GeneratedTrack>()
        for (item in filtered) {
            val cTitle = cleanTitleForComparison(item.name)
            if (cTitle.isNotBlank() && !seenTitles.add(cTitle)) {
                continue
            }
            distinctSongs.add(item)
        }

        // 6. Artist diversity cap: at most 1 track for the seed artist, at most 2 for other artists
        //    so the playlist is full of truly different similar songs by related artists
        fun applyArtistCap(maxSeedArtist: Int, maxOtherArtist: Int): List<GeneratedTrack> {
            val counts = mutableMapOf<String, Int>()
            return distinctSongs.filter { item ->
                val aKey = item.artist.trim().lowercase()
                val isSeedArtist = aKey.equals(artist.trim().lowercase(), ignoreCase = true)
                val maxAllowed = if (isSeedArtist) maxSeedArtist else maxOtherArtist
                val current = counts[aKey] ?: 0
                if (current < maxAllowed) {
                    counts[aKey] = current + 1
                    true
                } else {
                    false
                }
            }
        }

        var curated = applyArtistCap(maxSeedArtist = 1, maxOtherArtist = 2)
        if (curated.size < targetSize) {
            curated = applyArtistCap(maxSeedArtist = 2, maxOtherArtist = 3)
        }
        if (curated.size < targetSize) {
            curated = distinctSongs
        }

        val finalResult = filterPlayable(filterRecommendationExclusions(curated))
        finalResult.take(targetSize)
    }

    suspend fun fetchSimilarArtistTracks(artist: String, limit: Int): List<GeneratedTrack> = kotlinx.coroutines.supervisorScope {
        val lastFm = async(Dispatchers.IO) {
            try {
                val result = call(mapOf("method" to "artist.getsimilar", "artist" to artist, "limit" to "20"))
                val artistNames = GenerateJson.namesOf(result["similarartists"]?.jsonObject?.get("artist")).shuffled().take(8)
                kotlinx.coroutines.supervisorScope {
                    artistNames.map { similarArtist ->
                        async(Dispatchers.IO) {
                            try {
                                val page = (1..4).random()
                                val response = call(
                                    mapOf(
                                        "method" to "artist.gettoptracks",
                                        "artist" to similarArtist,
                                        "limit" to kotlin.math.ceil(limit / 5.0).toInt().toString(),
                                        "page" to page.toString(),
                                    ),
                                )
                                GenerateJson.normalise(response["toptracks"]?.jsonObject?.get("track"))
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (error: Exception) {
                                Log.d(TAG, "artist.gettoptracks failed for $similarArtist", error)
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.d(TAG, "Last.fm similar-artist source unavailable", error)
                emptyList()
            }
        }
        val youtube = async(Dispatchers.IO) {
            try {
                val seed = innerTube.searchSongs("$artist songs", limit = 3, prefetchStreams = false).firstOrNull()
                    ?: return@async emptyList()
                val related = innerTube.fetchRelatedSongs(seed.videoId, minOf(limit * 2, 75), prefetchStreams = false)
                related.map { it.toGeneratedTrack() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.d(TAG, "YouTube artist radio source unavailable", error)
                emptyList()
            }
        }
        val blended = blendSources(youtube.await().shuffled(), lastFm.await().shuffled())
        filterPlayable(filterRecommendationExclusions(blended)).take(limit)
    }

    suspend fun fetchTagTracks(tag: String, limit: Int): List<GeneratedTrack> = kotlinx.coroutines.supervisorScope {
        val lastFm = async(Dispatchers.IO) {
            try {
                val page = (1..8).random()
                val result = call(mapOf("method" to "tag.gettoptracks", "tag" to tag, "limit" to minOf(limit * 3, 100).toString(), "page" to page.toString()))
                GenerateJson.normalise(result["tracks"]?.jsonObject?.get("track")).shuffled()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.d(TAG, "Last.fm tag source unavailable", error)
                emptyList()
            }
        }
        val youtube = async(Dispatchers.IO) {
            try {
                innerTube.searchSongs("$tag music", minOf(limit * 2, 60), prefetchStreams = false)
                    .map { it.toGeneratedTrack() }
                    .shuffled()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.d(TAG, "YouTube tag source unavailable", error)
                emptyList()
            }
        }
        val blended = blendSources(youtube.await(), lastFm.await())
        filterPlayable(filterRecommendationExclusions(blended)).take(limit)
    }

    // ── My Mix — exact port of fetchMix(): 3-tier weighted blend ──

    suspend fun fetchMix(total: Int, onProgress: (String) -> Unit = {}): List<GeneratedTrack> {
        onProgress("Discovering tracks for you\u2026")
        data class Weighted(val track: GeneratedTrack, val weight: Int)
        val weighted = mutableListOf<Weighted>()
        val tasteProfile = runCatching { tasteProfileProvider.get() }.getOrNull()
        var topArtists: List<String> = tasteProfile?.topArtistsRaw.orEmpty()
        val lastFmAvailable = runCatching { isLastFmAvailable() }.getOrDefault(false)

        // ── Primary engine: YouTube Music (personal mixes, taste signals,
        //    related radio) + local Room seeds. Last.fm below is strictly
        //    supplementary and skipped entirely for guest/disconnected. ──
        val youtubeDiscovery = try {
            fetchYouTubeDiscovery(
                seeds = tasteProfile?.recentTracksRaw.orEmpty() + tasteProfile?.topTracksRaw.orEmpty() +
                    tasteProfile?.ytMusicRecentRaw.orEmpty() + tasteProfile?.ytMusicLikedRaw.orEmpty(),
                limit = maxOf(20, total).coerceAtMost(40),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.d(TAG, "YouTube mix source unavailable", error)
            emptyList()
        }
        // Personal/public home mixes as seeds. Bounded to 2 playlists fetched
        // in parallel so a slow playlist cannot stall the whole mix.
        val homeMixSeeds = try {
            val mixes = innerTube.fetchHomeMixes().take(2)
            kotlinx.coroutines.coroutineScope {
                mixes.map { mix ->
                    async(Dispatchers.IO) {
                        runCatching { innerTube.fetchPlaylist(mix.id, maxTracks = 6)?.tracks.orEmpty() }
                            .getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }.map { it.toGeneratedTrack() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) { emptyList() }

        // Weight 4: personal YT mixes + taste feed + discovery radio (primary).
        (tasteProfile?.ytMusicFeedRaw.orEmpty() + homeMixSeeds + youtubeDiscovery)
            .distinctBy(GeneratedTrack::key)
            .shuffled()
            .take(maxOf(12, total / 2).coerceAtMost(24))
            .forEach { weighted += Weighted(it, 4) }

        // Weight 3: local Room seeds (liked + saved) keep guest/offline personal.
        runCatching { localSeedPool(limit = 12) }.getOrDefault(emptyList())
            .shuffled().take(8).forEach { weighted += Weighted(it, 3) }

        // Bucket A — weight 3: recent plays + similar (Last.fm supplementary only)
        if (lastFmAvailable) {
        try {
            onProgress("Personalising from recent plays\u2026")
            val rd = call(mapOf("method" to "user.getrecenttracks", "user" to username(), "limit" to "50"))
            val rRaw = rd["recenttracks"]?.jsonObject?.get("track")
            val withoutNowPlaying = GenerateJson.asObjectList(rRaw).filterNot { it["@attr"]?.jsonObject?.get("nowplaying") != null }
            val recent = GenerateJson.normalise(JsonArray(withoutNowPlaying))
            val recentSeeds = recent.shuffled().take(6)
            recentSeeds.forEach { weighted += Weighted(it, 3) }

            val similarToRecent = coroutineScope {
                recentSeeds.take(4).filter { it.name.isNotBlank() && it.artist.isNotBlank() }.map { t ->
                    async {
                        try {
                            val d = call(mapOf("method" to "track.getsimilar", "track" to t.name, "artist" to t.artist, "limit" to kotlin.math.ceil(total / 6.0).toInt().toString()))
                            GenerateJson.normalise(d["similartracks"]?.jsonObject?.get("track"))
                        } catch (e: Exception) {
                            Log.d(TAG, "fetchMix similar-to-recent miss", e)
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
            similarToRecent.forEach { weighted += Weighted(it, 3) }
        } catch (e: Exception) { Log.d(TAG, "fetchMix bucket A miss", e) }
        } // end Last.fm Bucket A (supplementary only)

        // ── YouTube taste expansion (primary): each YT seed grows its own
        //    related-song radio via InnerTube — no Last.fm needed. ──
        val ytSeeds = buildList {
            addAll(tasteProfile?.ytMusicRecentRaw.orEmpty().shuffled().take(3))
            addAll(tasteProfile?.ytMusicLikedRaw.orEmpty().shuffled().take(2))
            addAll(tasteProfile?.ytMusicFeedRaw.orEmpty().shuffled().take(2))
            addAll(youtubeDiscovery.shuffled().take(2))
        }.distinctBy(GeneratedTrack::key).shuffled().take(6)
        ytSeeds.forEach { weighted += Weighted(it, 3) }
        val similarToYtTaste = coroutineScope {
            ytSeeds.map { seed ->
                async(Dispatchers.IO) {
                    try {
                        fetchYouTubeRadio(seed.name, seed.artist, seed.youtubeVideoIdOrNull(), limit = 12)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        similarToYtTaste.forEach { weighted += Weighted(it, 3) }

        if (lastFmAvailable) {
        // Bucket B — weight 2: confirmed top tracks (randomized period, supplementary)
        try {
            onProgress("Pulling in your top tracks\u2026")
            val r = Math.random()
            val period = if (r < 0.4) "1month" else if (r < 0.7) "3month" else if (r < 0.9) "6month" else "12month"
            val topD = call(mapOf("method" to "user.gettoptracks", "user" to username(), "period" to period, "limit" to "30"))
            GenerateJson.normalise(topD["toptracks"]?.jsonObject?.get("track")).forEach { weighted += Weighted(it, 2) }
        } catch (e: Exception) { Log.d(TAG, "fetchMix bucket B miss", e) }

        // Bucket B2 — weight 2: top artists -> similar-artist top tracks
        try {
            val r = Math.random()
            val period = if (r < 0.5) "overall" else if (r < 0.75) "12month" else "6month"
            val d = call(mapOf("method" to "user.gettopartists", "user" to username(), "period" to period, "limit" to "30"))
            val lastFmTopArtists = GenerateJson.namesOf(d["topartists"]?.jsonObject?.get("artist"))
            if (lastFmTopArtists.isNotEmpty()) topArtists = lastFmTopArtists
        } catch (e: Exception) { Log.d(TAG, "fetchMix bucket B2 top-artists miss", e) }

        val bucketB2 = coroutineScope {
            topArtists.shuffled().take(3).map { artist ->
                async {
                    val result = mutableListOf<Weighted>()
                    try {
                        onProgress("Exploring artists like $artist\u2026")
                        val sim = call(mapOf("method" to "artist.getsimilar", "artist" to artist, "limit" to "12"))
                        val simPool = GenerateJson.namesOf(sim["similarartists"]?.jsonObject?.get("artist")).shuffled().take(3)
                        val perArtist = coroutineScope {
                            simPool.map { saName ->
                                async {
                                    try {
                                        val page = kotlin.math.ceil(Math.random() * 4).toInt().coerceAtLeast(1)
                                        val d = call(mapOf("method" to "artist.gettoptracks", "artist" to saName, "limit" to maxOf(4, kotlin.math.ceil(total / 12.0).toInt()).toString(), "page" to page.toString()))
                                        GenerateJson.normalise(d["toptracks"]?.jsonObject?.get("track"))
                                    } catch (e: Exception) {
                                        Log.d(TAG, "fetchMix similar-artist toptracks miss", e)
                                        emptyList()
                                    }
                                }
                            }.awaitAll().flatten()
                        }
                        perArtist.forEach { result += Weighted(it, 2) }
                    } catch (e: Exception) { Log.d(TAG, "fetchMix artist.getsimilar miss for $artist", e) }
                    result
                }
            }.awaitAll().flatten()
        }
        weighted += bucketB2

        // Bucket C — weight 1: genre/tag discovery pad, only if still thin (Last.fm only)
        if (lastFmAvailable && weighted.size < total * 2) {
            try {
                onProgress("Adding genre discoveries\u2026")
                val td = call(mapOf("method" to "user.gettoptags", "user" to username(), "limit" to "8"))
                val tags = GenerateJson.namesOf(td["toptags"]?.jsonObject?.get("tag"))
                val tag = tags.shuffled().take(minOf(5, tags.size)).randomOrNull()
                if (tag != null) {
                    val page = (Math.random() * 8).toInt() + 1
                    val td2 = call(mapOf("method" to "tag.gettoptracks", "tag" to tag, "limit" to kotlin.math.ceil(total * 0.4).toInt().toString(), "page" to page.toString()))
                    GenerateJson.normalise(td2["tracks"]?.jsonObject?.get("track")).forEach { weighted += Weighted(it, 1) }
                }
            } catch (e: Exception) { Log.d(TAG, "fetchMix bucket C miss", e) }
        }
        } // end Last.fm Buckets B/B2/C (supplementary only)

        // Guest/offline pad: public charts keep the mix full when taste is thin.
        if (weighted.size < total) {
            runCatching { publicChartsFallback(total) }.getOrDefault(emptyList())
                .forEach { weighted += Weighted(it, 1) }
        }

        onProgress("Curating your personalised mix\u2026")

        // Dedup keeping highest weight
        val bestWeight = mutableMapOf<String, Int>()
        val trackOf = mutableMapOf<String, GeneratedTrack>()
        for ((track, weight) in weighted) {
            if (track.name.isBlank() || track.artist.isBlank()) continue
            val k = track.key
            if ((bestWeight[k] ?: -1) < weight) {
                bestWeight[k] = weight
                trackOf[k] = track
            }
        }

        // Sort by weight tier descending, shuffled within tier (4 = YT primary first)
        val merged = listOf(4, 3, 2, 1).flatMap { w ->
            bestWeight.entries.filter { it.value == w }.map { trackOf[it.key]!! }.shuffled()
        }

        // Artist diversity: max 3 per artist
        val artistCount = mutableMapOf<String, Int>()
        val diverse = merged.filter {
            val key = it.artist.lowercase()
            val count = (artistCount[key] ?: 0) + 1
            artistCount[key] = count
            count <= 3
        }

        var pool = filterRecommendationExclusions(diverse)

        // Fallback 1: YouTube radio expansion when thin (primary).
        if (pool.size < total) {
            try {
                onProgress("Finding more recommendations\u2026")
                val seed = pool.shuffled().firstOrNull()
                    ?: weighted.map { it.track }.shuffled().firstOrNull()
                if (seed != null) {
                    val extra = runCatching {
                        fetchYouTubeRadio(seed.name, seed.artist, seed.youtubeVideoIdOrNull(), limit = total)
                    }.getOrDefault(emptyList())
                    val known = pool.mapTo(mutableSetOf()) { it.key }
                    pool = pool + extra.filter { it.key !in known }
                }
            } catch (e: Exception) { Log.d(TAG, "fetchMix YT fallback miss", e) }
        }

        // Fallback 2: Last.fm similar artists only when connected.
        if (pool.size < total && lastFmAvailable && topArtists.isNotEmpty()) {
            try {
                onProgress("Finding more recommendations\u2026")
                val fa = topArtists.random()
                val fd = call(mapOf("method" to "artist.getsimilar", "artist" to fa, "limit" to "10"))
                for (saName in GenerateJson.namesOf(fd["similarartists"]?.jsonObject?.get("artist")).shuffled().take(3)) {
                    try {
                        val d = call(mapOf("method" to "artist.gettoptracks", "artist" to saName, "limit" to "6"))
                        pool = pool + GenerateJson.normalise(d["toptracks"]?.jsonObject?.get("track"))
                    } catch (e: Exception) { Log.d(TAG, "fetchMix fallback similar-artist miss", e) }
                }
            } catch (e: Exception) { Log.d(TAG, "fetchMix fallback miss", e) }
        }

        // Fallback 3: public charts guarantee a full mix for guest/offline.
        if (pool.size < total) {
            val charts = runCatching { publicChartsFallback(total * 2) }.getOrDefault(emptyList())
            val known = pool.mapTo(mutableSetOf()) { it.key }
            pool = pool + charts.filter { it.key !in known }
        }

        return filterPlayable(deduplicate(filterRecommendationExclusions(pool))).take(total)
    }

    /**
     * Builds a brand-new "inspired" mix from a source playlist's taste.
     * The source playlist itself is never modified and none of its tracks
     * are ever repeated — every returned song is fresh, discovered through
     * Last.fm similarity graphs plus accountless YouTube Music radio, so the regenerated
     * playlist feels like a genuinely new selection with the same taste.
     *
     * Targets [count] (30–35) but always tries to land at least
     * [MIN_TASTE_MIX_SIZE] songs by progressively relaxing the artist cap
     * and widening the discovery net before giving up.
     */
    suspend fun fetchTasteMixForPlaylist(playlistTracks: List<GeneratedTrack>, count: Int): List<GeneratedTrack> = kotlinx.coroutines.supervisorScope {
        val originalKeys = playlistTracks.mapTo(mutableSetOf()) { it.key }
        val pool = java.util.Collections.synchronizedList(mutableListOf<GeneratedTrack>())
        val distinctArtists = playlistTracks.map { it.artist.trim() }.filter { it.isNotBlank() }.distinct()
        // A few strong seeds produce a better mix than flooding Last.fm with
        // dozens of nested calls. The old 10/8 fan-out could exceed 70 HTTP
        // requests, hit rate limits and make Regenerate appear frozen.
        val seedArtists = distinctArtists.shuffled().take(3)
        val seedTracks = playlistTracks.filter { it.name.isNotBlank() && it.artist.isNotBlank() }.shuffled().take(4)

        // 1. Similar artists' top tracks (Last.fm) — the core "same taste,
        //    different songs" source.
        val simArtistJobs = seedArtists.map { artist ->
            async(Dispatchers.IO) {
                try {
                    val sim = call(mapOf("method" to "artist.getsimilar", "artist" to artist, "limit" to "12"))
                    val simArtists = GenerateJson.namesOf(sim["similarartists"]?.jsonObject?.get("artist")).shuffled().take(2)
                    kotlinx.coroutines.supervisorScope {
                        simArtists.map { sa ->
                            async(Dispatchers.IO) {
                                try {
                                    val page = (1..3).random()
                                    val d = call(mapOf("method" to "artist.gettoptracks", "artist" to sa, "limit" to "8", "page" to page.toString()))
                                    GenerateJson.normalise(d["toptracks"]?.jsonObject?.get("track"))
                                } catch (_: Exception) {
                                    emptyList()
                                }
                            }
                        }.awaitAll().flatten()
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        // 2. Similar tracks for seed tracks (Last.fm).
        val simTrackJobs = seedTracks.map { seed ->
            async(Dispatchers.IO) {
                try {
                    val d = call(mapOf("method" to "track.getsimilar", "track" to seed.name, "artist" to seed.artist, "limit" to "12"))
                    GenerateJson.normalise(d["similartracks"]?.jsonObject?.get("track"))
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        // 3. Deep cuts from the playlist's own artists (Last.fm) — random
        //    pages surface album tracks beyond the hits the source playlist
        //    already captured.
        val directArtistJobs = seedArtists.map { artist ->
            async(Dispatchers.IO) {
                try {
                    val page = (1..3).random()
                    val d = call(mapOf("method" to "artist.gettoptracks", "artist" to artist, "limit" to "8", "page" to page.toString()))
                    GenerateJson.normalise(d["toptracks"]?.jsonObject?.get("track"))
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        // 4. Anonymous YouTube Music radios from source tracks
        //    resolve each seed into its real related-song radio queue.
        val ytJobs = seedTracks.take(3).map { seed ->
            async(Dispatchers.IO) {
                try {
                    fetchYouTubeRadio(
                        track = seed.name,
                        artist = seed.artist,
                        seedVideoId = seed.youtubeVideoIdOrNull(),
                        limit = 14,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        pool.addAll(simArtistJobs.awaitAll().flatten())
        pool.addAll(simTrackJobs.awaitAll().flatten())
        pool.addAll(directArtistJobs.awaitAll().flatten())
        pool.addAll(ytJobs.awaitAll().flatten())

        // 5. Genre-tag discovery when the pool is still thin.
        if (pool.size < count) {
            try {
                val tagJobs = seedArtists.take(3).map { artist ->
                    async(Dispatchers.IO) {
                        try {
                            val td = call(mapOf("method" to "artist.gettoptags", "artist" to artist, "limit" to "5"))
                            val tags = GenerateJson.namesOf(td["toptags"]?.jsonObject?.get("tag")).shuffled().take(2)
                            kotlinx.coroutines.supervisorScope {
                                tags.map { tag ->
                                    async(Dispatchers.IO) {
                                        try {
                                            val page = (1..4).random()
                                            val td2 = call(mapOf("method" to "tag.gettoptracks", "tag" to tag, "limit" to "10", "page" to page.toString()))
                                            GenerateJson.normalise(td2["tracks"]?.jsonObject?.get("track"))
                                        } catch (_: Exception) {
                                            emptyList()
                                        }
                                    }
                                }.awaitAll().flatten()
                            }
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }
                pool.addAll(tagJobs.awaitAll().flatten())
            } catch (_: Exception) {}
        }

        // Fresh, de-duplicated candidates only — the source playlist's own
        // tracks never leak into the regenerated mix.
        val candidates = deduplicate(
            filterRecommendationExclusions(pool.toList()).filterNot { it.key in originalKeys },
        )

        // 6. Progressive artist-cap relaxation: strict 3/artist first for
        //    variety, widening just enough to secure MIN_TASTE_MIX_SIZE.
        fun capped(cap: Int): List<GeneratedTrack> {
            val counts = mutableMapOf<String, Int>()
            return candidates.filter {
                val key = it.artist.lowercase().trim()
                val c = (counts[key] ?: 0) + 1
                counts[key] = c
                c <= cap
            }
        }

        val minSize = minOf(MIN_TASTE_MIX_SIZE, count)
        var result = capped(3)
        if (result.size < minSize) result = capped(6)
        if (result.size < minSize) result = candidates

        // 7. Last-resort: one more similar-artist sweep, then chart filler —
        //    a slightly-less-on-taste 20+ beats a half-empty playlist.
        if (result.size < minSize) {
            try {
                val extraArtists = distinctArtists.shuffled().take(2)
                val extra = coroutineScope {
                    extraArtists.map { artist ->
                        async(Dispatchers.IO) {
                            try {
                                val d = call(mapOf("method" to "artist.getsimilar", "artist" to artist, "limit" to "20"))
                                GenerateJson.namesOf(d["similarartists"]?.jsonObject?.get("artist")).shuffled().take(3)
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten().distinct().filter { sa -> sa.lowercase() !in distinctArtists.mapTo(mutableSetOf()) { it.lowercase() } }
                }
                val extraTracks = coroutineScope {
                    extra.map { sa ->
                        async(Dispatchers.IO) {
                            try {
                                val d = call(mapOf("method" to "artist.gettoptracks", "artist" to sa, "limit" to "6"))
                                GenerateJson.normalise(d["toptracks"]?.jsonObject?.get("track"))
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten()
                }
                val extraCandidates = deduplicate(
                    filterRecommendationExclusions(extraTracks).filterNot { it.key in originalKeys },
                )
                val seen = result.mapTo(mutableSetOf()) { it.key }
                result = result + extraCandidates.filter { seen.add(it.key) }
            } catch (_: Exception) {}
        }
        if (result.size < minSize) {
            runCatching { fetchChartTracks(minSize * 2) }.getOrDefault(emptyList())
                .filterNot { it.key in originalKeys || result.any { r -> r.key == it.key } }
                .let { filler -> result = (result + filler) }
        }

        return@supervisorScope result.shuffled().take(count)
    }

    suspend fun fetchTasteMixForArtists(artists: List<String>, count: Int): List<GeneratedTrack> = coroutineScope {
        val pool = mutableListOf<GeneratedTrack>()
        val seeds = artists.shuffled().take(6)
        val deferred = seeds.map { artist ->
            async(Dispatchers.IO) {
                try {
                    val sim = call(mapOf("method" to "artist.getsimilar", "artist" to artist, "limit" to "10"))
                    val simArtists = GenerateJson.namesOf(sim["similarartists"]?.jsonObject?.get("artist")).shuffled().take(3)
                    coroutineScope {
                        simArtists.map { sa ->
                            async(Dispatchers.IO) {
                                try {
                                    val page = (1..3).random()
                                    val d = call(mapOf("method" to "artist.gettoptracks", "artist" to sa, "limit" to "6", "page" to page.toString()))
                                    GenerateJson.normalise(d["toptracks"]?.jsonObject?.get("track"))
                                } catch (_: Exception) {
                                    emptyList()
                                }
                            }
                        }.awaitAll().flatten()
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }
        val tracks = deferred.awaitAll().flatten()
        pool.addAll(tracks)
        if (pool.size < count) {
            try {
                val mix = fetchMix(count)
                pool.addAll(mix)
            } catch (_: Exception) {}
        }
        val allowed = filterRecommendationExclusions(pool)
        precheck(shuffle(allowed)).take(count).ifEmpty { deduplicate(allowed).take(count) }
    }

    // ── My Recommendations — delegates the heavy scoring/pipeline logic to
    //    RecommendationEngine, kept as a separate file given its size. ──

    suspend fun fetchRecommendations(
        total: Int,
        onProgress: (String) -> Unit = {},
    ): List<GeneratedTrack> = kotlinx.coroutines.supervisorScope {
        onProgress("Building your taste profile\u2026")
        val profile = tasteProfileProvider.get()
        val lastFmAvailable = runCatching { isLastFmAvailable() }.getOrDefault(false)
        // Primary: YouTube Music discovery from full taste (YT + local seeds).
        val youtubeDeferred = async(Dispatchers.IO) {
            try {
                val seeds = profile.recentTracksRaw + profile.topTracksRaw +
                    profile.ytMusicRecentRaw + profile.ytMusicLikedRaw + profile.ytMusicFeedRaw
                val discovery = fetchYouTubeDiscovery(seeds, total * 2)
                val local = localSeedPool(limit = total)
                (discovery + local).distinctBy { it.key }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.d(TAG, "YouTube recommendation source unavailable", error)
                emptyList()
            }
        }

        val blacklist = (profile.recentTrackKeys + profile.topTrackKeys).toMutableSet()
        blacklist.addAll(recommendationExclusionKeys())
        if (lastFmAvailable) {
            try {
                val lovedRes = call(mapOf("method" to "user.getlovedtracks", "user" to username(), "limit" to "200"))
                GenerateJson.normalise(lovedRes["lovedtracks"]?.jsonObject?.get("track")).forEach { blacklist.add(it.key) }
            } catch (e: Exception) { Log.d(TAG, "fetchRecommendations loved-tracks miss", e) }
        }
        val savedPlaylistKeys = savedPlaylistTrackKeys()

        // Supplementary: Last.fm scoring engine only when connected. Guest /
        // offline skips it entirely and relies on YouTube + local.
        val recommended: List<GeneratedTrack> = if (lastFmAvailable) {
            val engine = RecommendationEngine(
                rawCall = { params -> call(params) },
                isFresh = { tracks -> filterRecommendationExclusions(tracks) },
                onProgress = onProgress,
            )
            try {
                engine.run(
                    total,
                    profile,
                    blacklist,
                    savedPlaylistKeys,
                    youtubeCandidates = youtubeDeferred.await().filterNot { it.key in blacklist },
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.d(TAG, "Last.fm recommendation engine unavailable", error)
                emptyList()
            }
        } else {
            emptyList()
        }
        onProgress("Curating YouTube Music recommendations\u2026")
        val youtube = if (lastFmAvailable) youtubeDeferred.await() else {
            // Already awaited inside engine.run above when connected; for
            // guest we await here (deferred is complete, no extra cost).
            runCatching { youtubeDeferred.await() }.getOrDefault(emptyList())
        }
        val youtubeFresh = youtube.filterNot { it.key in blacklist }
        // YouTube-first: YT leads, Last.fm fills only when connected.
        val blended = if (lastFmAvailable) {
            youtubeFirstBlend(youtube = youtubeFresh, lastFm = recommended)
        } else {
            val charts = publicChartsFallback(total).filterNot { it.key in blacklist }
            deduplicate(youtubeFresh + charts)
        }
        val result = filterPlayable(filterRecommendationExclusions(blended)).take(total)
        if (result.size < total) {
            val pad = publicChartsFallback(total * 2)
                .filterNot { it.key in blacklist || result.any { r -> r.key == it.key } }
            (result + pad).take(total)
        } else result
    }

    // ── Start Mix From Track — YouTube Music Radio primary ──

    suspend fun startMixFromTrack(
        trackName: String,
        artistName: String,
        onProgress: (String) -> Unit = {},
    ): List<GeneratedTrack> {
        onProgress("Finding similar songs with YouTube Music\u2026")
        return fetchSimilarTracks(track = trackName, artist = artistName, limit = 28)
    }

    // ── Seed pickers / search ──

    suspend fun topTracksForSeed(): List<GeneratedTrack> {
        val result = call(mapOf("method" to "user.gettoptracks", "user" to username(), "limit" to "20", "period" to "overall"))
        return GenerateJson.normalise(result["toptracks"]?.jsonObject?.get("track"))
    }

    suspend fun topArtistsForSeed(): List<String> {
        val result = call(mapOf("method" to "user.gettopartists", "user" to username(), "limit" to "20", "period" to "overall"))
        return GenerateJson.namesOf(result["topartists"]?.jsonObject?.get("artist"))
    }

    suspend fun searchTracks(track: String, artist: String?): List<GeneratedTrack> = kotlinx.coroutines.supervisorScope {
        val lastFm = async(Dispatchers.IO) {
            try {
                val params = mutableMapOf("method" to "track.search", "track" to track, "limit" to "15")
                if (!artist.isNullOrBlank()) params["artist"] = artist
                val result = call(params)
                val raw = result["results"]?.jsonObject?.get("trackmatches")?.jsonObject?.get("track")
                GenerateJson.normalise(raw)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList()
            }
        }
        val youtube = async(Dispatchers.IO) {
            try {
                innerTube.searchSongs(listOfNotNull(artist, track).joinToString(" "), 15, prefetchStreams = false)
                    .map { it.toGeneratedTrack() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList()
            }
        }
        blendSources(youtube.await(), lastFm.await()).take(15)
    }

    suspend fun searchArtists(artist: String): List<String> = kotlinx.coroutines.supervisorScope {
        val lastFm = async(Dispatchers.IO) {
            try {
                val result = call(mapOf("method" to "artist.search", "artist" to artist, "limit" to "15"))
                val raw = result["results"]?.jsonObject?.get("artistmatches")?.jsonObject?.get("artist")
                GenerateJson.namesOf(raw)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList()
            }
        }
        val youtube = async(Dispatchers.IO) {
            try {
                innerTube.searchArtists(artist, 15).map { it.name }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList()
            }
        }
        (youtube.await() + lastFm.await()).distinctBy { it.trim().lowercase() }.take(15)
    }

    /**
     * Builds a playlist of completely unheard songs tailored to the user's taste.
     * Strictly excludes any songs the user has heard before across:
     * - Last.fm recent scrobbles, top tracks, and loved tracks
     * - YouTube Music listening history, liked tracks, and feed tracks
     * - Local playback history / play stats (SongPlayStatsDao)
     * - Room database saved playlists and liked songs
     * - Downloaded offline tracks (DownloadedTrackDao)
     * - Recommendation exclusions
     *
     * In addition to exact track keys, it excludes matching YouTube video IDs,
     * normalized composite keys, normalized base titles (stripping feat./remaster clauses),
     * and fuzzy title similarities (>= 80%) for matching artists to prevent live/remix
     * duplicates of heard tracks.
     */
    suspend fun fetchNeverHeardTracks(
        total: Int,
        onProgress: (String) -> Unit = {},
    ): List<GeneratedTrack> = kotlinx.coroutines.supervisorScope {
        onProgress("Building your taste profile and history footprint\u2026")

        val tasteProfile = runCatching { tasteProfileProvider.get() }.getOrNull()
        val lastFmAvailable = runCatching { isLastFmAvailable() }.getOrDefault(false)

        val heardKeys = mutableSetOf<String>()
        val heardVideoIds = mutableSetOf<String>()
        val heardNormalizedKeys = mutableSetOf<String>()
        val heardTitlesByArtist = mutableMapOf<String, MutableSet<String>>()

        fun recordHeard(name: String, artist: String, videoId: String? = null, key: String? = null) {
            val cleanName = name.trim()
            val cleanArtist = artist.trim()
            if (cleanName.isBlank() && cleanArtist.isBlank()) return
            if (key != null) heardKeys.add(key.lowercase())
            else heardKeys.add("$cleanName|$cleanArtist".lowercase())
            if (!videoId.isNullOrBlank()) heardVideoIds.add(videoId)

            val normArtist = TextMatch.normalize(cleanArtist)
            val normTitle = TextMatch.normalize(cleanName)
            val normBaseTitle = TextMatch.normalize(TextMatch.baseTitle(cleanName))
            if (normTitle.isNotBlank() && normArtist.isNotBlank()) {
                heardNormalizedKeys.add("$normTitle|$normArtist")
            }
            if (normBaseTitle.isNotBlank() && normArtist.isNotBlank()) {
                heardNormalizedKeys.add("$normBaseTitle|$normArtist")
                heardTitlesByArtist.getOrPut(normArtist) { mutableSetOf() }.add(normBaseTitle)
            }
        }

        // 1. Signals from TasteProfile (Last.fm recent/top, YT Music recent/liked/feed)
        if (tasteProfile != null) {
            tasteProfile.recentTrackKeys.forEach { heardKeys.add(it.lowercase()) }
            tasteProfile.topTrackKeys.forEach { heardKeys.add(it.lowercase()) }
            tasteProfile.recentTracksRaw.forEach { recordHeard(it.name, it.artist, it.youtubeVideoIdOrNull(), it.key) }
            tasteProfile.topTracksRaw.forEach { recordHeard(it.name, it.artist, it.youtubeVideoIdOrNull(), it.key) }
            tasteProfile.ytMusicRecentRaw.forEach { recordHeard(it.name, it.artist, it.youtubeVideoIdOrNull(), it.key) }
            tasteProfile.ytMusicLikedRaw.forEach { recordHeard(it.name, it.artist, it.youtubeVideoIdOrNull(), it.key) }
            tasteProfile.ytMusicFeedRaw.forEach { recordHeard(it.name, it.artist, it.youtubeVideoIdOrNull(), it.key) }
        }

        // 2. Room: Recommendation exclusions
        runCatching { recommendationExclusionKeys() }.getOrDefault(emptySet()).forEach {
            heardKeys.add(it.lowercase())
        }

        // 3. Room: Saved playlists and liked songs
        runCatching { playlistRepository.getAll() }.getOrDefault(emptyList()).forEach { playlist ->
            playlist.tracks.forEach { recordHeard(it.name, it.artist, it.youtubeVideoIdOrNull(), it.key) }
        }

        // 4. Room: Song play stats (tracks played locally in LastWave)
        runCatching { songPlayStatsDao.getAllTrackKeys() }.getOrDefault(emptyList()).forEach {
            heardKeys.add(it.lowercase())
        }

        // 5. Room: Downloaded tracks
        runCatching { downloadedTrackDao.getAllList() }.getOrDefault(emptyList()).forEach {
            recordHeard(it.title, it.artist, null, it.trackKey)
        }

        // 6. Last.fm: fetch loved tracks and recent scrobbles directly if connected
        if (lastFmAvailable) {
            try {
                val lovedRes = call(mapOf("method" to "user.getlovedtracks", "user" to username(), "limit" to "200"))
                GenerateJson.normalise(lovedRes["lovedtracks"]?.jsonObject?.get("track")).forEach {
                    recordHeard(it.name, it.artist, it.youtubeVideoIdOrNull(), it.key)
                }
            } catch (e: Exception) {
                Log.d(TAG, "fetchNeverHeardTracks loved tracks fetch failed", e)
            }
            try {
                val recentRes = call(mapOf("method" to "user.getrecenttracks", "user" to username(), "limit" to "200"))
                val rRaw = recentRes["recenttracks"]?.jsonObject?.get("track")
                GenerateJson.normalise(rRaw).forEach {
                    recordHeard(it.name, it.artist, it.youtubeVideoIdOrNull(), it.key)
                }
            } catch (e: Exception) {
                Log.d(TAG, "fetchNeverHeardTracks recent tracks fetch failed", e)
            }
        }

        fun isUnheard(track: GeneratedTrack): Boolean {
            val name = track.name.trim()
            val artist = track.artist.trim()
            if (name.isBlank() || artist.isBlank()) return false

            val key = track.key.lowercase()
            if (key in heardKeys) return false

            val vid = track.youtubeVideoIdOrNull()
            if (vid != null && vid in heardVideoIds) return false

            val normArtist = TextMatch.normalize(artist)
            val normTitle = TextMatch.normalize(name)
            val normBase = TextMatch.normalize(TextMatch.baseTitle(name))

            if ("$normTitle|$normArtist" in heardNormalizedKeys) return false
            if ("$normBase|$normArtist" in heardNormalizedKeys) return false

            val artistHeardTitles = heardTitlesByArtist[normArtist]
            if (!artistHeardTitles.isNullOrEmpty()) {
                if (normBase in artistHeardTitles) return false
                for (heardTitle in artistHeardTitles) {
                    if (TextMatch.similarity(normBase, heardTitle) >= 80) return false
                }
            }
            return true
        }

        onProgress("Finding fresh tracks matching your taste\u2026")

        // Gather taste seeds
        val topArtists: List<String> = buildList {
            addAll(tasteProfile?.topArtistsRaw.orEmpty())
            addAll(tasteProfile?.topArtistNames.orEmpty())
            addAll(tasteProfile?.recentArtists.orEmpty())
        }.filter { it.isNotBlank() }.distinct()

        val tasteTracks: List<GeneratedTrack> = buildList {
            addAll(tasteProfile?.ytMusicLikedRaw.orEmpty())
            addAll(tasteProfile?.topTracksRaw.orEmpty())
            addAll(tasteProfile?.recentTracksRaw.orEmpty())
            addAll(tasteProfile?.ytMusicRecentRaw.orEmpty())
            addAll(tasteProfile?.ytMusicFeedRaw.orEmpty())
        }.filter { it.name.isNotBlank() && it.artist.isNotBlank() }.distinctBy { it.key }

        val candidatePool = mutableListOf<GeneratedTrack>()

        // 1. YouTube Discovery from taste tracks
        if (tasteTracks.isNotEmpty()) {
            try {
                val discovery = fetchYouTubeDiscovery(tasteTracks.shuffled().take(10), limit = maxOf(40, total * 3))
                candidatePool.addAll(discovery)
            } catch (e: Exception) {
                Log.d(TAG, "Never-heard YT discovery failed", e)
            }
        }

        // 2. Similar artists discovery
        if (topArtists.isNotEmpty()) {
            val sampledArtists = topArtists.shuffled().take(5)
            val similarTracks = sampledArtists.map { artist ->
                async(Dispatchers.IO) {
                    try {
                        onProgress("Exploring artists similar to $artist\u2026")
                        fetchSimilarArtistTracks(artist, limit = 12)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
            candidatePool.addAll(similarTracks)
        }

        // 3. YouTube Radio on random taste seeds
        if (tasteTracks.isNotEmpty()) {
            val radioSeeds = tasteTracks.shuffled().take(4)
            val radioTracks = radioSeeds.map { seed ->
                async(Dispatchers.IO) {
                    try {
                        fetchYouTubeRadio(seed.name, seed.artist, seed.youtubeVideoIdOrNull(), limit = 15)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
            candidatePool.addAll(radioTracks)
        }

        // 4. Genre tags if available
        val topTags = tasteProfile?.topTags.orEmpty().toList()
        if (topTags.isNotEmpty()) {
            val tag = topTags.shuffled().firstOrNull()
            if (tag != null) {
                try {
                    val tagTracks = fetchTagTracks(tag, limit = 20)
                    candidatePool.addAll(tagTracks)
                } catch (e: Exception) {
                    Log.d(TAG, "Never-heard tag tracks failed", e)
                }
            }
        }

        // Filter strictly for unheard tracks
        onProgress("Excluding previously heard songs\u2026")
        var unheard = candidatePool.filter(::isUnheard)
        unheard = filterRecommendationExclusions(unheard)

        // If we need more candidates, expand via radio on the unheard candidates that match taste!
        if (unheard.size < total * 2 && unheard.isNotEmpty()) {
            val extraSeeds = unheard.shuffled().take(4)
            val extra = extraSeeds.map { seed ->
                async(Dispatchers.IO) {
                    try {
                        fetchYouTubeRadio(seed.name, seed.artist, seed.youtubeVideoIdOrNull(), limit = 15)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten().filter(::isUnheard)
            unheard = unheard + extra
        }

        // Fallback for empty/thin taste (e.g. brand new user with no history):
        if (unheard.size < total) {
            val fallback = publicChartsFallback(total * 3).filter(::isUnheard)
            unheard = unheard + fallback
        }

        // Apply precheck (dedup + artist diversity cap of max 3 tracks per artist)
        val diverse = precheck(unheard.distinctBy { it.key })
        filterPlayable(diverse).take(total)
    }
}
