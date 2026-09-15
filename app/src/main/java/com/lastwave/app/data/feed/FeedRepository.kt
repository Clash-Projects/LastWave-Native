package com.lastwave.app.data.feed

import androidx.compose.runtime.Immutable
import com.lastwave.app.data.artwork.ArtworkNormalizer
import com.lastwave.app.util.ArtistHelper
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.data.model.ArtistRef
import com.lastwave.app.data.model.ImageDto
import com.lastwave.app.data.generate.TasteProfileProvider
import com.lastwave.app.data.model.FriendEntry
import com.lastwave.app.data.model.RecentTrack
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.repository.HomeRepository
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Singleton

@Immutable
data class FeedQuickTile(
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val actionVideoId: String? = null,
    val playlistId: String? = null,
    val localPlaylistId: Long? = null,
    val isLiked: Boolean = false,
    val collection: String? = null,
)

@Immutable
data class FeedMix(val title: String, val seed: YouTubeMusicTrack)

@Immutable
data class FeedSectionData<T>(
    val title: String,
    val subtitle: String? = null,
    val items: List<T>,
)

@Immutable
data class FeedArtist(
    val name: String,
    val browseId: String? = null,
    val artworkUrl: String? = null,
)

@Immutable
data class FeedAlbum(
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val browseId: String? = null,
)

@Immutable
data class FeedSpotlight(
    val artistName: String,
    val artworkUrl: String? = null,
    val browseId: String? = null,
    val description: String? = null,
    val topTrackTitle: String? = null,
)

@Immutable
data class FeedData(
    val isYtConnected: Boolean = false,
    val ytAccountName: String? = null,
    val userName: String? = null,
    val hasYtRecommendations: Boolean = false,
    val hasYtMixes: Boolean = false,
    val hasPersonalContent: Boolean = false,
    val tasteTags: List<String> = emptyList(),
    val ytSuggestedPlaylists: List<YouTubePlaylistSummary> = emptyList(),
    val spotlight: FeedSpotlight? = null,
    val quickTiles: List<FeedQuickTile> = emptyList(),
    val quickPicks: List<YouTubeMusicTrack> = emptyList(),
    val newReleases: List<YouTubePlaylistSummary> = emptyList(),
    val charts: List<YouTubeMusicTrack> = emptyList(),
    val mixes: List<FeedMix> = emptyList(),
    val jumpBackIn: List<RecentTrack> = emptyList(),
    val recentAlbums: List<FeedAlbum> = emptyList(),
    val topArtists: List<FeedArtist> = emptyList(),
    val heavyRotation: List<GeneratedTrack> = emptyList(),
    val ytLikedSongs: List<YouTubeMusicTrack> = emptyList(),
    val ytRecentSongs: List<YouTubeMusicTrack> = emptyList(),
    val becauseYouListenTo: FeedSectionData<YouTubeMusicTrack>? = null,
    val freshFinds: List<YouTubeMusicTrack> = emptyList(),
    val friends: List<FriendEntry> = emptyList(),
    val lastUpdatedMillis: Long = 0L,
)

@Singleton
class FeedRepository @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
    private val homeRepository: HomeRepository,
    private val tasteProfileProvider: TasteProfileProvider,
    private val ytAuth: YtMusicAuthManager,
    private val playlistRepository: PlaylistRepository,
) {
    private var cachedFeed: FeedData? = null
    private var cachedKey: String? = null

    fun getCachedFeed(): FeedData? = cachedFeed

    suspend fun loadFeed(
        username: String?,
        onUpdate: (FeedData) -> Unit = {},
    ): FeedData = coroutineScope {
        val connectionPre = ytAuth.awaitLoadedConnection()
        val cacheKey = "${username.orEmpty()}|$connectionPre"
        val previous = cachedFeed?.takeIf { cachedKey == cacheKey }
        previous?.let(onUpdate)
        val random = kotlin.random.Random(System.nanoTime())
        val connection = connectionPre
        val isYtConnected = connection.isConnected

        val newReleasesDef = async(Dispatchers.IO) { runCatching { innerTube.fetchNewReleases() }.getOrDefault(emptyList()) }
        val chartsDef = async(Dispatchers.IO) { runCatching { innerTube.fetchCharts() }.getOrDefault(emptyList()) }
        val homeMixesDef = async(Dispatchers.IO) { runCatching { innerTube.fetchHomeMixes() }.getOrDefault(emptyList()) }
        val homeSongsDef = async(Dispatchers.IO) {
            if (isYtConnected) emptyList() else runCatching { innerTube.fetchHomeSongs() }.getOrDefault(emptyList())
        }

        val ytTasteDef = async(Dispatchers.IO) {
            if (isYtConnected) {
                runCatching { innerTube.fetchTasteSignals(recentLimit = 40, likedLimit = 40, feedLimit = 60) }.getOrNull()
            } else null
        }

        val recentTracksDef = async(Dispatchers.IO) {
            if (!username.isNullOrBlank()) {
                homeRepository.fetchRecentTracks(page = 1, limit = 30, username = username).getOrNull()?.tracks.orEmpty()
            } else emptyList()
        }
        val friendsDef = async(Dispatchers.IO) {
            if (!username.isNullOrBlank()) {
                homeRepository.fetchFriends(limit = 20).getOrNull().orEmpty()
            } else emptyList()
        }
        val tasteProfileDef = async(Dispatchers.IO) {
            runCatching { tasteProfileProvider.get() }.getOrNull()
        }
        val likedSongsIdDef = async(Dispatchers.IO) {
            runCatching { playlistRepository.ensureLikedSongs().id }.getOrNull()
        }
        // Local-first fallback for guest/disconnected: liked songs + saved
        // playlist tracks from Room (on-device history proxy). Never throws.
        val localLibraryDef = async(Dispatchers.IO) {
            runCatching {
                val liked = playlistRepository.getLikedSongs()?.tracks.orEmpty()
                val saved = playlistRepository.getAll().flatMap { it.tracks }
                (liked + saved).filter { it.name.isNotBlank() && it.artist.isNotBlank() }
                    .distinctBy { it.key }
            }.getOrDefault(emptyList())
        }
        // Real Last.fm albums for the taste — fetched up-front in parallel.
        // Never derive albums from per-track `album` strings here: those are
        // usually just the single name and open a whole different record.
        val lastFmTopAlbumsDef = async(Dispatchers.IO) {
            if (!username.isNullOrBlank()) {
                runCatching { homeRepository.fetchTopAlbums(limit = 20, username = username).getOrNull().orEmpty() }
                    .getOrDefault(emptyList())
            } else emptyList()
        }

        val releaseCandidates = newReleasesDef.await()
        val charts = chartsDef.await()
        val homePlaylists = homeMixesDef.await().filter {
            it.id.startsWith("PL") || it.id.startsWith("RD") || it.id.startsWith("OLAK") || it.id == "LM"
        }
        val homeSongs = homeSongsDef.await()
        val recentTracks = recentTracksDef.await()
        val friends = friendsDef.await()
        val tasteProfile = tasteProfileDef.await()
        val ytTaste = ytTasteDef.await()
        val likedSongsId = likedSongsIdDef.await()

        val localLibrary = localLibraryDef.await()
        val localQuickPicks = localLibrary.map {
            YouTubeMusicTrack(it.youtubeVideoIdOrNull().orEmpty(), it.name, it.artist, it.album, it.artworkUrl)
        }

        // Connected: personal mixes + taste signals + related artists lead.
        // Guest/disconnected: local-first — liked + saved Room tracks plus
        // accountless public trending (homeSongs/charts). Account-only
        // shelves (ytLikedSongs) stay empty so the UI suppresses them.
        val ytLikedSongs = ytTaste?.likedTracks.orEmpty().ifEmpty { previous?.ytLikedSongs.orEmpty() }
            .takeIf { isYtConnected } .orEmpty()
        val ytRecentSongs = ytTaste?.recentTracks.orEmpty().ifEmpty { previous?.ytRecentSongs.orEmpty() }
            .takeIf { isYtConnected } .orEmpty()
        val ytQuickPicks = if (isYtConnected) {
            ytTaste?.feedTracks.orEmpty().ifEmpty { homeSongs }.ifEmpty { previous?.quickPicks.orEmpty() }
        } else {
            // Guest: local liked + public trending, never account-only.
            (localQuickPicks + homeSongs).ifEmpty { previous?.quickPicks.orEmpty() }
        }

        val affinity = tasteProfile?.artistAffinity.orEmpty()
        val previousPickIds = previous?.quickPicks.orEmpty().mapTo(mutableSetOf()) { it.videoId }
        fun trackScore(t: YouTubeMusicTrack, index: Int, sourceBoost: Double): Double {
            val keys = ArtistHelper.splitArtists(t.artist).map { it.trim().lowercase() }.ifEmpty { listOf(t.artist.trim().lowercase()) }
            val aff = keys.maxOfOrNull { affinity[it] ?: 0.0 } ?: 0.0
            val hasVideo = t.videoId.isNotBlank()
            val hasArt = ArtworkNormalizer.isRealImage(t.artworkUrl)
            val positionDecay = 1.0 / (1.0 + index / 9.0)
            val jitter = random.nextDouble()
            return aff * 60.0 + sourceBoost * 14.0 * positionDecay +
                (if (hasVideo) 10.0 else -6.0) + (if (hasArt) 4.0 else 0.0) + jitter * 35.0 - (if (t.videoId in previousPickIds) 30.0 else 0.0)
        }
        fun <T> diversify(
            items: List<T>,
            artistOf: (T) -> String,
            maxPerArtist: Int = 2,
        ): List<T> {
            val counts = mutableMapOf<String, Int>()
            val out = ArrayList<T>(items.size)
            val deferred = ArrayList<T>()
            for (item in items) {
                val key = ArtistHelper.splitArtists(artistOf(item)).firstOrNull()?.trim()?.lowercase()
                    ?: artistOf(item).trim().lowercase()
                if ((counts[key] ?: 0) < maxPerArtist) {
                    counts[key] = (counts[key] ?: 0) + 1
                    out.add(item)
                } else deferred.add(item)
            }
            // Second pass fills remaining slots so shelves never look short.
            for (item in deferred) {
                val key = ArtistHelper.splitArtists(artistOf(item)).firstOrNull()?.trim()?.lowercase()
                    ?: artistOf(item).trim().lowercase()
                if ((counts[key] ?: 0) < maxPerArtist + 1) {
                    counts[key] = (counts[key] ?: 0) + 1
                    out.add(item)
                }
            }
            return out
        }

        fun <T> blend(first: List<T>, second: List<T>): List<T> = buildList {
            repeat(maxOf(first.size, second.size)) { index ->
                first.getOrNull(index)?.let { add(it) }
                second.getOrNull(index)?.let { add(it) }
            }
        }

        val regularPicks = tasteProfile?.topTracksRaw.orEmpty().map {
            YouTubeMusicTrack(it.youtubeVideoIdOrNull().orEmpty(), it.name, it.artist, it.album, it.artworkUrl)
        }
        val quickCandidates = buildList {
            ytQuickPicks.forEachIndexed { i, t -> add(t to trackScore(t, i, 3.0)) }
            ytLikedSongs.forEachIndexed { i, t -> add(t to trackScore(t, i, 2.2)) }
            ytRecentSongs.forEachIndexed { i, t -> add(t to trackScore(t, i, 1.6)) }
            regularPicks.forEachIndexed { i, t -> add(t to trackScore(t, i, 2.6)) }
            // Local-first: liked + saved Room tracks score as personal picks
            // for guest/disconnected so Quick Picks never looks generic.
            localQuickPicks.forEachIndexed { i, t -> add(t to trackScore(t, i, 2.4)) }
            homeSongs.forEachIndexed { i, t -> add(t to trackScore(t, i, 1.2)) }
            charts.forEachIndexed { i, t -> add(t to trackScore(t, i, 1.0)) }
        }
            .distinctBy { (t, _) -> t.artist.trim().lowercase() to t.title.trim().lowercase() }
            .sortedByDescending { it.second }
            .map { it.first }
        val quickPicks = diversify(quickCandidates, YouTubeMusicTrack::artist, maxPerArtist = 2)
            .take(18)
            .ifEmpty { charts }
            .distinctBy { it.artist.trim().lowercase() to it.title.trim().lowercase() }
            .take(15)

        onUpdate((previous ?: FeedData()).copy(
            isYtConnected = isYtConnected,
            ytAccountName = connection.accountName.takeIf { isYtConnected },
            userName = username,
            hasYtRecommendations = ytTaste?.feedTracks?.isNotEmpty() == true,
            hasPersonalContent = tasteProfile?.hasPersonalSignals == true || ytLikedSongs.isNotEmpty() || ytRecentSongs.isNotEmpty(),
            quickPicks = quickPicks,
            ytLikedSongs = ytLikedSongs,
            ytRecentSongs = ytRecentSongs,
        ))

        val knownArtists = buildSet {
            addAll(affinity.keys)
            addAll((ytLikedSongs + ytRecentSongs).flatMap { ArtistHelper.splitArtists(it.artist) }.map { it.trim().lowercase() })
            addAll(recentTracks.flatMap { ArtistHelper.splitArtists(it.artist.displayName) }.map { it.trim().lowercase() })
        }

        val artistSignalTracks = ytRecentSongs + ytLikedSongs + ytQuickPicks + homeSongs + charts
        val ytArtistNames = (ytRecentSongs + ytLikedSongs + if (isYtConnected) ytQuickPicks else emptyList())
            .flatMap { ArtistHelper.splitArtists(it.artist) }
            .filter { it.isNotBlank() && !it.equals("Unknown artist", ignoreCase = true) }
            .groupBy { it.trim().lowercase() }
            .values.sortedByDescending { it.size }
            .map { it.first().trim() }
        val listeningArtists = tasteProfile?.topArtistsRaw.orEmpty().flatMap(ArtistHelper::splitArtists)
        val tasteArtists = (blend(ytArtistNames, listeningArtists) +
            recentTracks.flatMap { ArtistHelper.splitArtists(it.artist.displayName) } +
            regularPicks.flatMap { ArtistHelper.splitArtists(it.artist) })
            .filter { it.isNotBlank() && !it.equals("Unknown artist", ignoreCase = true) }
            .map { it.lowercase() }.distinct()
        val artistRanks = tasteArtists.withIndex().associate { it.value to it.index }
        val matchedReleases = if (artistRanks.isEmpty()) releaseCandidates else releaseCandidates
            .mapNotNull { release ->
                val rank = ArtistHelper.splitArtists(release.author).mapNotNull { artistRanks[it.lowercase()] }.minOrNull()
                rank?.let { release to it }
            }.sortedBy { it.second }.map { it.first }.distinctBy { it.id }.take(15)
        val topArtistNames = buildList {
            repeat(maxOf(ytArtistNames.size, listeningArtists.size).coerceAtMost(8)) { index ->
                ytArtistNames.getOrNull(index)?.let { add(it) }
                listeningArtists.getOrNull(index)?.let { add(it) }
            }
            addAll(recentTracks.map { it.artist.displayName })
            if (isEmpty()) addAll(artistSignalTracks.map(YouTubeMusicTrack::artist))
        }
            .flatMap(ArtistHelper::splitArtists)
            .filter { it.isNotBlank() && !it.equals("Unknown artist", ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .take(8)
            .shuffled(random)

        val discoverySeeds = (ytLikedSongs + ytRecentSongs + quickPicks).filter { it.videoId.isNotBlank() }
            .shuffled(random).distinctBy { it.artist.lowercase() }.take(3)
        val discoveryDef = async(Dispatchers.IO) {
            discoverySeeds.map { seed ->
                async {
                    try {
                        innerTube.fetchRelatedSongs(seed.videoId, limit = 30, prefetchStreams = false)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
        }

        val topArtists = topArtistNames.map { name ->
            async(Dispatchers.IO) {
                val trackArtwork = artistSignalTracks
                    .firstOrNull { ArtistHelper.splitArtists(it.artist).any { artist -> artist.equals(name, ignoreCase = true) } }
                    ?.artworkUrl
                previous?.topArtists?.firstOrNull { it.name.equals(name, ignoreCase = true) && it.browseId != null }
                    ?.let { return@async it }
                val entity = runCatching {
                    innerTube.searchArtists(name, limit = 3)
                        .firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
                }.getOrNull()
                FeedArtist(
                    name = name,
                    browseId = entity?.browseId,
                    artworkUrl = entity?.artworkUrl?.takeIf(ArtworkNormalizer::isRealImage) ?: trackArtwork,
                )
            }
        }.awaitAll()

        val releaseYear = java.time.Year.now().value.toString()
        val artistReleases = if (artistRanks.isEmpty()) emptyList() else topArtists
            .filter { it.name.lowercase() in artistRanks }
            .take(4).map { artist ->
                async(Dispatchers.IO) {
                    val page = artist.browseId?.let { id ->
                        runCatching { innerTube.fetchArtistPage(id, artist.name) }.getOrNull()
                    }
                    (page?.albums.orEmpty() + page?.singles.orEmpty())
                        .filter { it.year == releaseYear && it.browseId.isNotBlank() }
                        .take(3).map { release ->
                            YouTubePlaylistSummary(id = release.browseId, title = release.title,
                                author = artist.name, artworkUrl = release.artworkUrl)
                        }
                }
            }.awaitAll().flatten()
        val newReleases = (matchedReleases + artistReleases)
            .ifEmpty { previous?.newReleases.orEmpty() }
            .distinctBy { it.id }.shuffled(random).take(15)

        val discoveryResults = discoverySeeds.zip(discoveryDef.await())
        val discoveryTracks = discoveryResults.flatMap { it.second }.distinctBy { it.videoId }
        val familiarIds = (ytLikedSongs + ytRecentSongs + regularPicks).mapTo(mutableSetOf()) { it.videoId }
        val freshPool = (discoveryTracks + charts + homeSongs + ytQuickPicks)
            .filter { it.videoId.isNotBlank() && it.videoId !in familiarIds }
            .distinctBy { it.videoId }.shuffled(random)
        val previousFreshIds = previous?.freshFinds.orEmpty().mapTo(mutableSetOf()) { it.videoId }
        val freshFinds = diversify(
            freshPool.sortedWith(compareBy<YouTubeMusicTrack> { it.videoId in previousFreshIds }
                .thenBy { track -> ArtistHelper.splitArtists(track.artist).any { it.trim().lowercase() in knownArtists } }),
            YouTubeMusicTrack::artist,
            maxPerArtist = 1,
        ).take(12)
        val previousMixIds = previous?.mixes.orEmpty().mapTo(mutableSetOf()) { it.seed.videoId }
        val mixes = (quickPicks + ytLikedSongs + discoveryTracks)
            .filter { it.videoId.isNotBlank() }.distinctBy { it.videoId }.shuffled(random)
            .sortedBy { it.videoId in previousMixIds }
            .distinctBy { ArtistHelper.primaryArtist(it.artist).trim().lowercase() }.take(8)
            .map { FeedMix(title = "${ArtistHelper.primaryArtist(it.artist)} mix", seed = it) }

        // Heavy rotation: connected blends long-term taste + liked signals;
        // guest/disconnected builds it from local Room listening history
        // (liked + saved) so "Heavy Rotation" never empties.
        val heavyCandidates = buildList {
            tasteProfile?.topTracksRaw?.forEachIndexed { i, t ->
                val aff = ArtistHelper.splitArtists(t.artist).maxOfOrNull { affinity[it.trim().lowercase()] ?: 0.0 } ?: 0.0
                add(t to (aff * 40 + 20.0 / (1 + i / 6.0)))
            }
            blend(ytRecentSongs, ytLikedSongs)
                .distinctBy { it.artist.trim().lowercase() to it.title.trim().lowercase() }
                .forEachIndexed { i, it ->
                    add(
                        GeneratedTrack(
                            it.title, it.artist, it.artworkUrl,
                            url = "https://www.youtube.com/watch?v=${it.videoId}", album = it.album,
                        ) to (12.0 / (1 + i / 6.0) + (affinity[ArtistHelper.primaryArtist(it.artist).trim().lowercase()] ?: 0.0) * 30),
                    )
                }
            // Local-first: Room liked + saved rank by affinity like taste.
            localLibrary.forEachIndexed { i, t ->
                val aff = ArtistHelper.splitArtists(t.artist).maxOfOrNull { affinity[it.trim().lowercase()] ?: 0.0 } ?: 0.0
                add(t to (aff * 35 + 16.0 / (1 + i / 6.0)))
            }
        }.distinctBy { (t, _) -> t.key }
            .sortedByDescending { it.second }
            .map { it.first }
        val heavyRotation = heavyCandidates.distinctBy(GeneratedTrack::key).take(15)
            .ifEmpty { localLibrary.take(15) }

        // Preserve each provider's history order while sharing the existing shelf.
        val ytJumpCandidates = buildList {
            ytRecentSongs.forEach {
                val pArtist = ArtistHelper.primaryArtist(it.artist)
                add(
                    RecentTrack(
                        name = it.title,
                        artist = ArtistRef(name = pArtist),
                        album = ArtistRef(name = it.album.orEmpty()),
                        image = it.artworkUrl?.let { url -> listOf(ImageDto(url, "extralarge")) }.orEmpty(),
                        url = "https://www.youtube.com/watch?v=${it.videoId}",
                    ),
                )
            }
        }
        val lastFmJumpCandidates = recentTracks.map {
            it.copy(artist = ArtistRef(name = ArtistHelper.primaryArtist(it.artist.displayName)))
        }
        // Local-first "Jump Back In": Room liked + saved as recent history
        // when Last.fm/YT history is unavailable (guest/offline).
        val localJumpCandidates = localLibrary.take(20).map {
            RecentTrack(
                name = it.name,
                artist = ArtistRef(name = ArtistHelper.primaryArtist(it.artist)),
                album = ArtistRef(name = it.album.orEmpty()),
                image = it.artworkUrl?.let { url -> listOf(ImageDto(url, "extralarge")) }.orEmpty(),
                url = it.url,
            )
        }
        val jumpCandidates = (blend(ytJumpCandidates, lastFmJumpCandidates) + localJumpCandidates)
            .distinctBy { it.artist.displayName.trim().lowercase() to it.name.trim().lowercase() }
        val jumpBackIn = diversify(jumpCandidates, { it.artist.displayName }, maxPerArtist = 2).take(15)

        // ── Albums for you: REAL albums only. ─────────────────────────────
        // Never derive albums from per-track `album` strings (YT track.album
        // / Last.fm recent-track album): those are usually just the single
        // name, so opening them lands on a whole different record.
        // Sources, both taste-ranked:
        //  1. Last.fm user.gettopalbums (real albums, playcount-ranked).
        //  2. YT Music artist discography Albums for our top taste artists
        //     (real MPRE entities with browseId — no fuzzy search needed).
        // An entry is shown only with a verified browseId, so tap always
        // opens that exact album and never a name-search guess.
        val albumArtworkRequests = Semaphore(4)
        val artistPageRequests = Semaphore(3)
        val lastFmTopAlbums = lastFmTopAlbumsDef.await()
        fun isStrictAlbumMatch(
            candidateName: String,
            candidateArtist: String?,
            wantTitle: String,
            wantArtist: String,
        ): Boolean {
            if (!candidateName.equals(wantTitle, ignoreCase = true)) return false
            if (candidateArtist.isNullOrBlank()) return true
            val wantParts = ArtistHelper.splitArtists(wantArtist).map { it.trim().lowercase() }
            if (wantParts.isEmpty()) return true
            return ArtistHelper.splitArtists(candidateArtist).any { it.trim().lowercase() in wantParts }
        }
        // YT Music real albums: Album shelf only — Singles/EPs are skipped so
        // a one-track "album" can never appear here.
        val lastFmArtByKey = lastFmTopAlbums.associate { top ->
            "${top.artist.trim().lowercase()}_${top.name.trim().lowercase()}" to top.artworkUrl
        }
        val ytRealAlbums = topArtists
            .filter { !it.browseId.isNullOrBlank() }
            .take(6)
            .map { artist ->
                async(Dispatchers.IO) {
                    artistPageRequests.withPermit {
                        val browseId = artist.browseId?.takeIf(String::isNotBlank)
                            ?: return@withPermit emptyList<FeedAlbum>()
                        val page = runCatching { innerTube.fetchArtistPage(browseId, artist.name) }.getOrNull()
                        page?.albums.orEmpty()
                            .filter { item ->
                                item.browseId.isNotBlank() && item.title.isNotBlank() &&
                                    (item.type == null || item.type.equals("Album", ignoreCase = true))
                            }
                            .take(3)
                            .map { item ->
                                // YT discography art is the cover; Last.fm top-album
                                // art is the backup so the card never has no cover.
                                val lastFmArt = lastFmArtByKey[
                                    "${artist.name.trim().lowercase()}_${item.title.trim().lowercase()}",
                                ]?.takeIf(ArtworkNormalizer::isRealImage)
                                FeedAlbum(
                                    title = item.title,
                                    artist = artist.name,
                                    artworkUrl = item.artworkUrl?.takeIf(ArtworkNormalizer::isRealImage)
                                        ?: lastFmArt,
                                    browseId = item.browseId,
                                )
                            }
                    }
                }
            }.awaitAll().flatten()
        // Last.fm top albums → strict YT Music verification. No exact
        // title+artist MPRE match = dropped, never guessed.
        val lastFmRealAlbums = lastFmTopAlbums
            .filter { it.name.isNotBlank() && it.artist.isNotBlank() }
            .distinctBy { "${it.artist.trim().lowercase()}_${it.name.trim().lowercase()}" }
            .take(20)
            .map { topAlbum ->
                async(Dispatchers.IO) {
                    albumArtworkRequests.withPermit {
                        val candidates = runCatching {
                            innerTube.searchAlbums("${topAlbum.name} ${topAlbum.artist}", limit = 5)
                        }.getOrNull().orEmpty()
                        val match = candidates.firstOrNull {
                            isStrictAlbumMatch(
                                candidateName = it.name,
                                candidateArtist = it.artist,
                                wantTitle = topAlbum.name,
                                wantArtist = topAlbum.artist,
                            )
                        } ?: return@withPermit null
                        FeedAlbum(
                            title = match.name,
                            artist = topAlbum.artist,
                            artworkUrl = match.artworkUrl?.takeIf(ArtworkNormalizer::isRealImage)
                                ?: topAlbum.artworkUrl?.takeIf(ArtworkNormalizer::isRealImage),
                            browseId = match.browseId,
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        val recentAlbums = blend(ytRealAlbums, lastFmRealAlbums)
            .distinctBy { "${it.artist.trim().lowercase()}_${it.title.trim().lowercase()}" }
            .filter { !it.browseId.isNullOrBlank() }
            // Cover-art backfill: a card without a real cover is dropped, so
            // resolve the album page once to grab its artwork before dropping.
            .take(20)
            .map { album ->
                async(Dispatchers.IO) {
                    if (ArtworkNormalizer.isRealImage(album.artworkUrl)) {
                        album
                    } else albumArtworkRequests.withPermit {
                        val pageArt = album.browseId?.takeIf(String::isNotBlank)?.let { id ->
                            runCatching { innerTube.fetchAlbumPage(id) }.getOrNull()?.artworkUrl
                        }?.takeIf(ArtworkNormalizer::isRealImage)
                        album.copy(artworkUrl = pageArt ?: album.artworkUrl)
                    }
                }
            }.awaitAll()
            .filter { ArtworkNormalizer.isRealImage(it.artworkUrl) }
            .take(12)
            .ifEmpty {
                previous?.recentAlbums.orEmpty()
                    .filter { !it.browseId.isNullOrBlank() && ArtworkNormalizer.isRealImage(it.artworkUrl) }
                    .take(12)
            }

        val topSpotlightArtist = topArtists.filterNot { it.name == previous?.spotlight?.artistName }
            .randomOrNull(random) ?: topArtists.firstOrNull()
        val spotlight = if (topSpotlightArtist != null) {
            val topTrackTitle = heavyRotation
                .firstOrNull { it.artist.equals(topSpotlightArtist.name, ignoreCase = true) }
                ?.name
                ?: artistSignalTracks
                    .firstOrNull { it.artist.equals(topSpotlightArtist.name, ignoreCase = true) }
                    ?.title
            FeedSpotlight(
                artistName = topSpotlightArtist.name,
                artworkUrl = topSpotlightArtist.artworkUrl,
                browseId = topSpotlightArtist.browseId,
                description = "Spotlight Artist",
                topTrackTitle = topTrackTitle,
            )
        } else null

        val topArtist = topArtistNames.firstOrNull()
        // Affinity-weighted radio seed: the strongest known artist among
        // recent + liked + top picks becomes "Because you listen to X".
        fun seedScore(t: YouTubeMusicTrack, boost: Double): Double {
            val aff = ArtistHelper.splitArtists(t.artist).maxOfOrNull { affinity[it.trim().lowercase()] ?: 0.0 } ?: 0.0
            return aff * 50 + boost + (if (t.videoId.isNotBlank()) 8.0 else -10.0)
        }
        val seedCandidates = buildList {
            ytRecentSongs.forEach { add(it to seedScore(it, 12.0)) }
            ytLikedSongs.forEach { add(it to seedScore(it, 10.0)) }
            quickPicks.take(6).forEach { add(it to seedScore(it, 6.0)) }
        }.sortedByDescending { it.second }
        val personalRadioSeed = discoveryResults.firstOrNull { it.second.isNotEmpty() }?.first
            ?: seedCandidates.filter { it.first.videoId.isNotBlank() }.take(15).randomOrNull(random)?.first
            ?: heavyRotation.firstOrNull()?.takeIf { tasteProfile?.hasPersonalSignals == true }?.let { seed ->
                try {
                    innerTube.findBestMatchOrNull(seed.name, seed.artist, prefetchStreams = false)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
            }
        val radioSeed = personalRadioSeed ?: (quickPicks + homeSongs + charts).firstOrNull { it.videoId.isNotBlank() }
            ?: (quickPicks + homeSongs + charts).firstOrNull()
        val radioTracks = discoveryResults.firstOrNull { it.first.videoId == radioSeed?.videoId }?.second
            ?.takeIf { it.isNotEmpty() }
            ?: radioSeed?.takeIf { it.videoId.isNotBlank() }?.let { seed ->
                try {
                    innerTube.fetchRelatedSongs(seed.videoId, limit = 15, prefetchStreams = false)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    emptyList()
                }
            }.orEmpty()
        val radioArtist = radioSeed?.artist ?: topArtist
        val radioFallback = if (radioTracks.isEmpty() && !radioArtist.isNullOrBlank()) {
            try {
                innerTube.searchSongs("$radioArtist radio", limit = 15, prefetchStreams = false)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()
        val becauseTracks = diversify(
            (radioTracks.ifEmpty { radioFallback }).distinctBy { it.videoId.ifBlank { it.title + "|" + it.artist } },
            YouTubeMusicTrack::artist,
            maxPerArtist = 2,
        ).take(15)
        val becauseSection = becauseTracks
            .takeIf { it.isNotEmpty() }
            ?.let { tracks ->
                FeedSectionData(
                    title = personalRadioSeed?.let { "Because you listen to ${it.artist}" } ?: "Discover something new",
                    subtitle = radioSeed?.let { "A mix inspired by ${it.title}" } ?: "Fresh tracks for your next listen",
                    items = tracks,
                )
            }

        val quickTiles = buildList {
            likedSongsId?.let {
                add(
                    FeedQuickTile(
                        title = "Liked Songs",
                        subtitle = "Your collection",
                        localPlaylistId = it,
                        isLiked = true,
                    ),
                )
            }
            if (isYtConnected) {
                add(FeedQuickTile(
                    title = "Liked on YouTube",
                    subtitle = "Your favorites",
                    artworkUrl = ytLikedSongs.firstOrNull()?.artworkUrl,
                    playlistId = "yt_liked",
                    collection = "yt_liked",
                    isLiked = true,
                ))
            }
            val savedMix = homePlaylists.firstOrNull { it.title.equals("Mix", ignoreCase = true) }
            add(savedMix?.let {
                FeedQuickTile(title = it.title, subtitle = it.author, artworkUrl = it.artworkUrl, playlistId = it.id)
            } ?: FeedQuickTile(title = "Mix", subtitle = "Made for you",
                artworkUrl = quickPicks.firstOrNull()?.artworkUrl, collection = "radio"))
            add(
                FeedQuickTile(
                    title = "New releases",
                    subtitle = "Fresh drops",
                    artworkUrl = newReleases.firstOrNull()?.artworkUrl,
                    collection = "new_releases",
                ),
            )
        }

        val tasteTags = tasteProfile?.topTags.orEmpty().take(8)
        val hasPersonalContent = tasteProfile?.hasPersonalSignals == true ||
            ytRecentSongs.isNotEmpty() || ytLikedSongs.isNotEmpty() || recentTracks.isNotEmpty()

        val result = FeedData(
            isYtConnected = isYtConnected,
            ytAccountName = connection.accountName.takeIf { isYtConnected },
            userName = username?.takeIf { it.isNotBlank() },
            hasYtRecommendations = ytTaste?.feedTracks?.isNotEmpty() == true,
            hasYtMixes = false,
            hasPersonalContent = hasPersonalContent,
            tasteTags = tasteTags,
            ytSuggestedPlaylists = homePlaylists.filter { it.id != "LM" }.take(12),
            spotlight = spotlight,
            quickTiles = quickTiles,
            quickPicks = quickPicks,
            newReleases = newReleases,
            charts = charts,
            mixes = mixes,
            jumpBackIn = jumpBackIn,
            recentAlbums = recentAlbums,
            topArtists = topArtists,
            heavyRotation = heavyRotation,
            ytLikedSongs = ytLikedSongs,
            ytRecentSongs = ytRecentSongs,
            becauseYouListenTo = becauseSection,
            freshFinds = freshFinds,
            friends = friends.take(10),
            lastUpdatedMillis = System.currentTimeMillis(),
        )
        ensureActive()
        cachedFeed = result
        cachedKey = cacheKey
        result
    }

}
