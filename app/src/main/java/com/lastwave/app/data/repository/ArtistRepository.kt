package com.lastwave.app.data.repository

import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.artwork.ArtworkNormalizer
import com.lastwave.app.data.model.ArtistAlbumItem
import com.lastwave.app.data.model.ArtistPageData
import com.lastwave.app.data.model.ArtistSummaryItem
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.network.LastFmApiService
import com.lastwave.app.playback.PlayableTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtistRepository @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
    private val lastFmApi: LastFmApiService,
    private val sessionPreferences: SessionPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getArtistDetails(
        artistName: String,
        browseId: String? = null,
        onLoaded: (ArtistPageData) -> Unit = {},
    ): ArtistPageData = withContext(Dispatchers.IO) {
        val cleanName = com.lastwave.app.util.ArtistHelper.primaryArtist(artistName).trim()
        if (cleanName.isBlank()) throw java.io.IOException("Artist name is empty.")

        // The whole resolve + load runs under one timeout so a stalled
        // lookup can never leave the screen on its spinner forever — a
        // timeout surfaces as an error with Retry instead.
        val loaded = kotlinx.coroutines.withTimeoutOrNull(25_000L) {
            var targetBrowseId = browseId?.takeIf { it.startsWith("UC") }
            var searchArtwork: String? = null

            // 1. Resolve browseId if missing
            if (targetBrowseId == null) {
                val searchResults = runCatching { innerTube.searchArtists(cleanName, limit = 5) }.getOrNull().orEmpty()
                val match = searchResults.firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
                    ?: searchResults.firstOrNull()
                targetBrowseId = match?.browseId
                searchArtwork = match?.artworkUrl?.takeIf(ArtworkNormalizer::isRealImage)
            }
            val resolvedId = targetBrowseId

            coroutineScope {
            // Load InnerTube artist data in parallel with Last.fm metadata
            val innerTubeDeferred = async {
                runCatching { resolvedId?.let { innerTube.fetchArtistPage(it, artistNameFallback = cleanName, onLoaded = onLoaded) } }.getOrNull()
            }

            val lastFmDeferred = async {
                runCatching { fetchLastFmArtistInfo(cleanName) }.getOrNull()
            }

            val ytData = innerTubeDeferred.await()
            ytData?.takeIf { it.topSongs.isNotEmpty() }?.let(onLoaded)

            // Merge InnerTube rich playable songs & discography with Last.fm bio & tags
            val finalName = ytData?.name?.takeIf(String::isNotBlank)?.let { com.lastwave.app.util.ArtistHelper.primaryArtist(it).trim() }
                ?: cleanName.ifBlank { "Artist" }
            if (!ArtworkNormalizer.isRealImage(ytData?.artworkUrl) && searchArtwork == null) {
                searchArtwork = runCatching {
                    innerTube.searchArtists(cleanName, limit = 5)
                        .firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
                        ?.artworkUrl?.takeIf(ArtworkNormalizer::isRealImage)
                }.getOrNull()
            }
            val artwork = ytData?.artworkUrl?.takeIf(ArtworkNormalizer::isRealImage)
                ?: searchArtwork
            val banner = ytData?.bannerUrl?.takeIf(ArtworkNormalizer::isRealImage) ?: artwork
            val bio = ytData?.bio?.takeIf(String::isNotBlank)
            val listeners = ytData?.subscribers

            var topSongs = ytData?.topSongs.orEmpty()

            // Search only when the artist page has no playable songs.
            if (topSongs.isEmpty() && finalName.isNotBlank()) {
                val songs = runCatching { innerTube.searchSongs(finalName, limit = 30) }.getOrDefault(emptyList())
                val existingIds = topSongs.mapNotNull { it.videoId }.toSet()
                val existingTitles = topSongs.map { it.title.lowercase().trim() }.toSet()
                val additionalTracks = songs
                    .filterNot { it.videoId in existingIds || it.title.lowercase().trim() in existingTitles }
                    .filter { track ->
                        track.artist.contains(finalName, ignoreCase = true) ||
                        finalName.contains(track.artist, ignoreCase = true) ||
                        track.artist.equals("Unknown artist", ignoreCase = true)
                    }
                    .map { track ->
                        PlayableTrack(
                            title = track.title,
                            artist = track.artist.takeUnless { it == "Unknown artist" } ?: finalName,
                            album = track.album,
                            artworkUrl = track.artworkUrl ?: artwork,
                            videoId = track.videoId,
                        )
                    }
                topSongs = (topSongs + additionalTracks).distinctBy { it.videoId ?: it.title }
            }

            val pageData = ArtistPageData(
                name = finalName,
                browseId = resolvedId.orEmpty(),
                artworkUrl = artwork,
                fallbackArtworkUrl = searchArtwork,
                bannerUrl = banner,
                monthlyListeners = ytData?.monthlyListeners ?: listeners,
                subscribers = ytData?.subscribers ?: listeners,
                bio = bio,
                topSongs = topSongs,
                albums = ytData?.albums.orEmpty(),
                singles = ytData?.singles.orEmpty(),
                similarArtists = ytData?.similarArtists.orEmpty(),
            )
            if (topSongs.isNotEmpty()) onLoaded(pageData)
            val lfmData = lastFmDeferred.await()
            pageData.copy(
                artworkUrl = pageData.artworkUrl ?: lfmData?.artworkUrl?.takeIf(ArtworkNormalizer::isRealImage),
                bannerUrl = pageData.bannerUrl ?: lfmData?.artworkUrl?.takeIf(ArtworkNormalizer::isRealImage),
                bio = pageData.bio ?: lfmData?.bio,
                tags = lfmData?.tags.orEmpty(),
                monthlyListeners = pageData.monthlyListeners ?: lfmData?.listeners,
                subscribers = pageData.subscribers ?: lfmData?.listeners,
                similarArtists = pageData.similarArtists.ifEmpty { lfmData?.similarArtists.orEmpty() },
            )
            }
        } ?: throw java.io.IOException("Couldn't load \"$cleanName\". Check your connection and try again.")

        // Never hand the UI a completely hollow page (no songs, no albums,
        // no artwork): it looks exactly like a stuck loader with dead play
        // buttons. Surface an error with Retry instead.
        if (loaded.topSongs.isEmpty() && loaded.albums.isEmpty() && loaded.singles.isEmpty() &&
            !ArtworkNormalizer.isRealImage(loaded.artworkUrl)
        ) {
            throw java.io.IOException("No playable tracks found for \"$cleanName\" right now.")
        }
        loaded
    }

    private data class LastFmArtistMeta(
        val bio: String? = null,
        val artworkUrl: String? = null,
        val listeners: String? = null,
        val tags: List<String> = emptyList(),
        val similarArtists: List<ArtistSummaryItem> = emptyList(),
    )

    private suspend fun fetchLastFmArtistInfo(artistName: String): LastFmArtistMeta? {
        val session = runCatching { sessionPreferences.session.first() }.getOrNull()
        // No shared key: without a personal key the YT Music path below is used.
        val apiKey = session?.apiKey?.takeIf(String::isNotBlank) ?: return null

        val response = lastFmApi.get(
            mapOf(
                "method" to "artist.getinfo",
                "artist" to artistName,
                "autocorrect" to "1",
                "api_key" to apiKey,
                "format" to "json",
            ),
        )
        if (!response.isSuccessful) return null
        val body = response.body()?.string().orEmpty()
        val artistObj = json.parseToJsonElement(body).jsonObject["artist"]?.jsonObject ?: return null

        val fullBio = artistObj["bio"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?.replace(Regex("<a\\b[^>]*>.*?</a>", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("<[^>]*>"), "")
            ?.trim()?.takeIf(String::isNotBlank)

        val summaryBio = artistObj["bio"]?.jsonObject?.get("summary")?.jsonPrimitive?.contentOrNull
            ?.replace(Regex("<a\\b[^>]*>.*?</a>", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("<[^>]*>"), "")
            ?.trim()?.takeIf(String::isNotBlank)

        val bio = fullBio ?: summaryBio

        val listenersCount = artistObj["stats"]?.jsonObject?.get("listeners")?.jsonPrimitive?.contentOrNull
        val listenersFormatted = listenersCount?.toLongOrNull()?.let { count ->
            when {
                count >= 1_000_000 -> "%.1fM listeners".format(count / 1_000_000.0)
                count >= 1_000 -> "%.1fK listeners".format(count / 1_000.0)
                else -> "$count listeners"
            }
        }

        val tags = artistObj["tags"]?.jsonObject?.get("tag")?.jsonArray?.mapNotNull {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
        }.orEmpty()

        val images = artistObj["image"]?.jsonArray?.mapNotNull { it.jsonObject }
        val artworkUrl = images?.lastOrNull {
            it["#text"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
        }?.get("#text")?.jsonPrimitive?.contentOrNull

        val similar = artistObj["similar"]?.jsonObject?.get("artist")?.jsonArray?.flatMap { elem ->
            val name = elem.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: return@flatMap emptyList()
            val img = elem.jsonObject["image"]?.jsonArray?.lastOrNull()?.jsonObject?.get("#text")?.jsonPrimitive?.contentOrNull
            com.lastwave.app.util.ArtistHelper.splitArtists(name).map { singleName ->
                ArtistSummaryItem(name = singleName, artworkUrl = img)
            }
        }.orEmpty().distinctBy { it.name.lowercase().trim() }

        return LastFmArtistMeta(
            bio = bio,
            artworkUrl = artworkUrl,
            listeners = listenersFormatted,
            tags = tags,
            similarArtists = similar,
        )
    }

    suspend fun getArtistRadio(
        artistName: String,
        seedTrack: PlayableTrack? = null,
    ): List<PlayableTrack> = withContext(Dispatchers.IO) {
        val cleanArtist = artistName.trim()
        val seedVideoId = seedTrack?.videoId?.takeIf(String::isNotBlank)
            ?: runCatching {
                innerTube.searchSongs("$cleanArtist songs", limit = 5, prefetchStreams = false)
                    .firstOrNull { it.artist.contains(cleanArtist, ignoreCase = true) || cleanArtist.contains(it.artist, ignoreCase = true) }
                    ?.videoId
            }.getOrNull()

        val related = if (!seedVideoId.isNullOrBlank()) {
            runCatching {
                innerTube.fetchRelatedSongs(seedVideoId, limit = 30, prefetchStreams = false)
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val relatedPlayable = related.map { track ->
            PlayableTrack(
                title = track.title,
                artist = track.artist,
                album = track.album,
                artworkUrl = track.artworkUrl,
                videoId = track.videoId.takeIf(String::isNotBlank),
            )
        }

        (listOfNotNull(seedTrack) + relatedPlayable).distinctBy { it.videoId ?: it.title }
    }
}
