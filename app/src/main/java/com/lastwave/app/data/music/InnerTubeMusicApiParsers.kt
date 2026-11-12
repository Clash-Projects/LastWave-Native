package com.lastwave.app.data.music

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import com.lastwave.app.data.local.AppLanguage
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.local.appLocale
import com.lastwave.app.data.generate.normalizeTrackText
import com.lastwave.app.data.podcast.PodcastEpisode
import com.lastwave.app.data.podcast.PodcastEpisodePage
import com.lastwave.app.data.podcast.PodcastParser
import com.lastwave.app.data.podcast.PodcastShelf
import com.lastwave.app.data.podcast.PodcastShowPage
import java.util.Locale
import com.lastwave.app.data.music.potoken.BotGuardTokenGenerator
import com.lastwave.app.data.music.potoken.PoTokenResult
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import com.lastwave.app.data.ytmusic.YtConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton


internal fun InnerTubeMusicApi.parseSongRenderers(root: JsonElement): List<YouTubeMusicTrack> {
        val songs = mutableListOf<YouTubeMusicTrack>()
        val cardRenderers = mutableListOf<JsonObject>()
        collectObjects(root, "musicCardShelfRenderer", cardRenderers)
        songs.addAll(cardRenderers.mapNotNull(::parseCardShelfSong))

        val renderers = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveListItemRenderer", renderers)
        songs.addAll(renderers.filterNot(::isSuggestionItem).mapNotNull(::parseSong))


        val queueRenderers = mutableListOf<JsonObject>()
        collectObjects(root, "playlistPanelVideoRenderer", queueRenderers)
        songs.addAll(queueRenderers.mapNotNull(::parsePlaylistPanelSong))

        val ytVideos = mutableListOf<JsonObject>()
        collectObjects(root, "playlistVideoRenderer", ytVideos)
        songs.addAll(ytVideos.mapNotNull(::parsePlaylistVideoRenderer))

        if (songs.isEmpty()) {
            val generalVideos = mutableListOf<JsonObject>()
            collectObjects(root, "videoRenderer", generalVideos)
            collectObjects(root, "compactVideoRenderer", generalVideos)
            songs.addAll(generalVideos.mapNotNull(::parseVideoRenderer))
        }
        return songs.distinctBy { it.videoId }.filter { it.videoId.isNotBlank() }
    }

internal fun InnerTubeMusicApi.parseVideoRenderer(renderer: JsonObject): YouTubeMusicTrack? {
        val videoId = renderer.string("videoId") ?: return null
        val title = renderer.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: renderer.obj("title")?.string("simpleText")
            ?: return null
        val artist = renderer.obj("ownerText")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
            ?: renderer.obj("shortBylineText")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
            ?: "Unknown artist"
        val duration = renderer.obj("lengthText")?.string("simpleText")?.let(::parseDuration)
            ?: renderer.string("lengthSeconds")?.toIntOrNull()
        val thumbnails = renderer.obj("thumbnail")?.array("thumbnails")
        val artwork = thumbnails?.lastOrNull()?.asObject()?.string("url")?.highResolutionArtwork()
        return YouTubeMusicTrack(videoId, title, artist, null, artwork, duration)
    }

    /** Home carousels use compact two-row cards. Only cards whose own
     * navigation is a direct watch endpoint are songs; album, artist and
     * playlist cards are deliberately ignored. */
internal fun InnerTubeMusicApi.parseHomeFeedSongs(root: JsonElement): List<YouTubeMusicTrack> {
        val rows = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveListItemRenderer", rows)
        val songs = rows.filter { row ->
            directWatchVideoId(row) != null
        }.mapNotNull(::parseSong).toMutableList()
        val renderers = mutableListOf<JsonObject>()
        collectObjects(root, "musicTwoRowItemRenderer", renderers)
        songs += renderers.mapNotNull(::parseTwoRowSong)
        return songs.distinctBy { it.videoId }.filter { it.videoId.isNotBlank() }
    }

    /**
     * Extracts the shelf title, optional strapline/subtitle, and the "more"
     * chevron's browse destination from a carousel renderer header.
     * Pattern follows YouTube Music's musicCarouselShelfBasicHeaderRenderer.
     * The triple is (title, subtitle, moreBrowseId); subtitle folds strapline
     * and subtitle the same way the previous Pair returned it.
     */
internal fun InnerTubeMusicApi.parseCarouselShelfHeaderFull(carouselRenderer: JsonObject): Triple<String, String?, String?>? {
        val header = carouselRenderer.obj("header")?.obj("musicCarouselShelfBasicHeaderRenderer")
            ?: carouselRenderer.obj("header")?.obj("musicShelfHeaderRenderer")
            ?: return null
        val title = header.obj("title")?.array("runs")?.joinToString("") {
            it.asObject()?.string("text").orEmpty()
        }?.trim()?.takeIf { it.isNotBlank() }
            ?: header.obj("title")?.string("simpleText")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val subtitle = (header.obj("strapline") ?: header.obj("subtitle"))?.array("runs")?.joinToString("") {
            it.asObject()?.string("text").orEmpty()
        }?.trim()?.takeIf { it.isNotBlank() }
            ?: (header.obj("strapline") ?: header.obj("subtitle"))?.string("simpleText")?.trim()?.takeIf { it.isNotBlank() }

        val moreBrowseId = header.obj("moreContentButton")
            ?.obj("buttonRenderer")
            ?.obj("navigationEndpoint")
            ?.obj("browseEndpoint")
            ?.string("browseId")
            ?.takeIf { it.isNotBlank() }

        return Triple(title, subtitle, moreBrowseId)
    }

internal fun InnerTubeMusicApi.parseCarouselShelfHeader(carouselRenderer: JsonObject): Pair<String, String?>? {
        val full = parseCarouselShelfHeaderFull(carouselRenderer) ?: return null
        return full.first to full.second
    }

    /**
     * Parses a single musicCarouselShelfRenderer into a HomeShelf.
     * Extracts title and subtitle via [parseCarouselShelfHeader], then parses
     * child items (musicTwoRowItemRenderer and musicResponsiveListItemRenderer) into
     * [HomeShelfItem.Track] or [HomeShelfItem.Playlist], skipping items without a usable ID.
     *
     * Adapted from Metrolist's HomePage.Section.fromMusicCarouselShelfRenderer (GPL-3.0).
     */
internal fun InnerTubeMusicApi.parseCarouselShelf(carouselRenderer: JsonObject): HomeShelf? {
        val headerFull = parseCarouselShelfHeaderFull(carouselRenderer) ?: return null
        val title = headerFull.first
        val subtitle = headerFull.second
        val moreBrowseId = headerFull.third
        val contents = carouselRenderer.array("contents") ?: return null
        val items = mutableListOf<HomeShelfItem>()

        for (contentElement in contents) {
            val contentObj = contentElement.asObject() ?: continue
            val twoRow = contentObj.obj("musicTwoRowItemRenderer")
            if (twoRow != null) {
                val twoRowNav = twoRow.obj("navigationEndpoint")
                val extras = parseHomeItemExtras(twoRow, twoRowNav, ::parseSong)
                val cardData = parseHomeCardData(twoRow)
                // Check if this two-row card is a playable song
                val song = parseTwoRowSong(twoRow)
                if (song != null && song.videoId.isNotBlank()) {
                    items.add(
                        HomeShelfItem.Track(
                            song,
                            aspectRatio = extras.aspectRatio,
                            itemType = extras.itemType,
                            caption = extras.caption,
                            cardData = cardData,
                        )
                    )
                    continue
                }

                // Otherwise check if it's a playlist or album card
                val nav = twoRowNav?.obj("browseEndpoint")
                    ?: twoRow.obj("title")?.array("runs")?.firstOrNull()?.asObject()?.obj("navigationEndpoint")?.obj("browseEndpoint")
                    ?: twoRow.obj("thumbnailOverlay")?.obj("musicItemThumbnailOverlayRenderer")?.obj("content")
                        ?.obj("musicPlayButtonRenderer")?.obj("playNavigationEndpoint")?.obj("watchEndpoint")
                    ?: twoRow.obj("onTap")?.obj("browseEndpoint")
                val browseId = nav?.string("browseId") ?: nav?.string("playlistId")

                // Skip cards with no id or artist cards (UC-prefixed browse IDs)
                if (browseId.isNullOrBlank() || browseId.startsWith("UC")) continue

                val itemTitle = twoRow.obj("title")?.array("runs")?.joinToString("") {
                    it.asObject()?.string("text").orEmpty()
                }?.trim()?.takeIf { it.isNotBlank() }
                    ?: twoRow.obj("title")?.string("simpleText")?.trim()?.takeIf { it.isNotBlank() }
                    ?: continue

                val subtitleRuns = twoRow.obj("subtitle")?.array("runs")?.mapNotNull { it.asObject() }.orEmpty()
                val author = subtitleRuns.firstOrNull {
                    it.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("UC") == true
                }?.string("text") ?: subtitleRuns.firstOrNull()?.string("text")
                    ?.takeUnless { it.equals("Playlist", ignoreCase = true) }
                val artwork = extractThumbnailsUrl(twoRow)

                val pageType = nav?.obj("browseEndpointContextSupportedConfigs")
                    ?.obj("browseEndpointContextMusicConfig")?.string("pageType")
                val isPodcastShow = browseId.startsWith("MPSP") ||
                    pageType == "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE"

                if (isPodcastShow) {
                    items.add(
                        HomeShelfItem.PodcastShow(
                            browseId = browseId,
                            title = itemTitle,
                            author = author?.trim(),
                            artworkUrl = artwork,
                            aspectRatio = extras.aspectRatio ?: "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_SQUARE",
                            itemType = "podcast_show",
                            caption = extras.caption,
                            cardData = cardData,
                        )
                    )
                    continue
                }

                val playlistId = if (browseId.startsWith("VL")) browseId.removePrefix("VL") else browseId
                val trackCountText = subtitleRuns.mapNotNull { it.string("text") }
                    .lastOrNull { "song" in it.lowercase() || "track" in it.lowercase() }

                items.add(
                    HomeShelfItem.Playlist(
                        YouTubePlaylistSummary(
                            id = playlistId,
                            title = itemTitle,
                            author = author?.trim(),
                            trackCountText = trackCountText,
                            artworkUrl = artwork,
                        ),
                        aspectRatio = extras.aspectRatio,
                        itemType = extras.itemType ?: "playlist",
                        caption = extras.caption,
                        collageArtworkUrls = extras.collageArtworkUrls,
                        previewSongs = extras.previewSongs,
                        cardData = cardData,
                    )
                )
                continue
            }

            val multiRow = contentObj.obj("musicMultiRowListItemRenderer")
            if (multiRow != null) {
                val episode = parseMultiRowEpisodeItem(multiRow)
                if (episode != null) {
                    items.add(episode)
                    continue
                }
            }

            val responsive = contentObj.obj("musicResponsiveListItemRenderer")
            if (responsive != null) {
                val song = parseSong(responsive)
                if (song != null && song.videoId.isNotBlank()) {
                    items.add(HomeShelfItem.Track(song))
                    continue
                }

                // Check for playlist/album responsive row
                val nav = responsive.array("flexColumns")?.firstOrNull()?.asObject()
                    ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")
                    ?.array("runs")?.firstOrNull()?.asObject()
                    ?.obj("navigationEndpoint")?.obj("browseEndpoint")
                    ?: responsive.obj("navigationEndpoint")?.obj("browseEndpoint")
                val browseId = nav?.string("browseId") ?: nav?.string("playlistId")
                if (browseId.isNullOrBlank() || browseId.startsWith("UC")) continue

                val playlistId = if (browseId.startsWith("VL")) browseId.removePrefix("VL") else browseId
                val itemTitle = responsive.array("flexColumns")?.firstOrNull()?.asObject()
                    ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")
                    ?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }?.trim()
                    ?: continue
                if (itemTitle.isBlank()) continue

                val subtitleRuns = responsive.array("flexColumns")?.getOrNull(1)?.asObject()
                    ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
                    ?.mapNotNull { it.asObject() }.orEmpty()
                val author = subtitleRuns.firstOrNull {
                    it.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("UC") == true
                }?.string("text") ?: subtitleRuns.firstOrNull()?.string("text")
                val artwork = extractThumbnailsUrl(responsive)

                items.add(
                    HomeShelfItem.Playlist(
                        YouTubePlaylistSummary(
                            id = playlistId,
                            title = itemTitle,
                            author = author?.trim(),
                            trackCountText = null,
                            artworkUrl = artwork,
                        ),
                        itemType = "playlist",
                    )
                )
            }
        }

        if (items.isEmpty()) return null
        return HomeShelf(
            title = title,
            subtitle = subtitle,
            items = items,
            strapline = subtitle,
            moreBrowseId = moreBrowseId,
            playAllButton = parseHomePlayAllButton(carouselRenderer),
            sourceItemCount = contents.size,
        )
    }

internal fun InnerTubeMusicApi.parseMultiRowEpisodeItem(renderer: JsonObject): HomeShelfItem.PodcastEpisode? {
        val videoId = directWatchVideoId(renderer)
            ?: renderer.obj("onTap")?.obj("watchEndpoint")?.string("videoId")
            ?: renderer.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: renderer.obj("title")?.array("runs")?.firstOrNull()?.asObject()
                ?.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: findString(renderer, "videoId")
            ?: return null
        if (videoId.isBlank()) return null

        val title = renderer.obj("title")?.array("runs")?.joinToString("") {
            it.asObject()?.string("text").orEmpty()
        }?.trim()?.takeIf { it.isNotBlank() }
            ?: renderer.obj("title")?.string("simpleText")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val secondTitleRuns = renderer.obj("secondTitle")?.array("runs")?.mapNotNull { it.asObject() }.orEmpty()
        val showName = secondTitleRuns.joinToString("") { it.string("text").orEmpty() }.trim().takeIf { it.isNotBlank() }
        val showBrowseId = secondTitleRuns.firstNotNullOfOrNull {
            it.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")
        } ?: renderer.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")

        val subtitleRuns = renderer.obj("subtitle")?.array("runs")?.mapNotNull { it.asObject() }.orEmpty()
        val subtitle = subtitleRuns.mapNotNull { it.string("text") }
            .filter { it.isNotBlank() && it.trim() != "•" }
            .joinToString(" • ")
            .takeIf { it.isNotBlank() }

        val progressRenderer = renderer.obj("playbackProgress")?.obj("musicPlaybackProgressRenderer")
        val rawDuration = progressRenderer?.obj("durationText")?.array("runs")
            ?.mapNotNull { it.asObject()?.string("text") }
            ?.filter { it.isNotBlank() && it.trim() != "•" }
            ?.joinToString(" ")?.trim()
            ?: progressRenderer?.obj("playbackProgressText")?.array("runs")
                ?.mapNotNull { it.asObject()?.string("text") }
                ?.filter { it.isNotBlank() && it.trim() != "•" }
                ?.joinToString(" ")?.trim()
        val durationText = rawDuration?.takeIf { it.isNotBlank() }

        val pctNum = progressRenderer?.string("playbackProgressPercentage")?.toFloatOrNull()
        val progressFraction = when {
            pctNum == null || pctNum <= 0f -> null
            pctNum > 1f -> (pctNum / 100f).coerceIn(0f, 1f)
            else -> pctNum.coerceIn(0f, 1f)
        }

        val thumbList = renderer.obj("thumbnail")?.obj("musicThumbnailRenderer")
            ?.obj("thumbnail")?.array("thumbnails")?.mapNotNull { it.asObject()?.string("url") }.orEmpty()
            .ifEmpty {
                val collected = mutableListOf<JsonObject>()
                collectObjects(renderer, "thumbnails", collected)
                collected.flatMap { it.array("thumbnails")?.mapNotNull { thumb -> thumb.asObject()?.string("url") }.orEmpty() }
            }
        val artworkUrl = thumbList.lastOrNull() ?: extractThumbnailsUrl(renderer)

        return HomeShelfItem.PodcastEpisode(
            videoId = videoId,
            title = title,
            showName = showName,
            showBrowseId = showBrowseId,
            subtitle = subtitle,
            durationText = durationText,
            progressFraction = progressFraction,
            artworkUrl = artworkUrl,
            thumbnailUrls = thumbList,
        )
    }

internal fun InnerTubeMusicApi.parseBackgroundHeroUrl(root: JsonElement): String? {
        val rootObj = root.asObject() ?: return null
        val bgThumbnails = rootObj.obj("background")
            ?.obj("musicThumbnailRenderer")
            ?.obj("thumbnail")
            ?.array("thumbnails")
            ?: return null
        return bgThumbnails.mapNotNull { it.asObject()?.string("url") }
            .lastOrNull { it.isNotBlank() && !it.contains("REDACTED") }
    }

    /**
     * Parses the Home feed response into native YouTube Music carousel shelves in original order.
     * Walks contents.singleColumnBrowseResultsRenderer.tabs[0].tabRenderer.content.sectionListRenderer.contents
     * and maps each musicCarouselShelfRenderer using [parseCarouselShelf].
     */
internal fun InnerTubeMusicApi.parseHomeShelves(root: JsonElement): List<HomeShelf> {
        val rootObj = root.asObject() ?: return emptyList()
        val sectionContents = rootObj.obj("contents")
            ?.obj("singleColumnBrowseResultsRenderer")
            ?.array("tabs")
            ?.firstOrNull()?.asObject()
            ?.obj("tabRenderer")
            ?.obj("content")
            ?.obj("sectionListRenderer")
            ?.array("contents")

        val carousels = if (sectionContents != null) {
            sectionContents.mapNotNull { it.asObject()?.obj("musicCarouselShelfRenderer") }
        } else {
            val collected = mutableListOf<JsonObject>()
            collectObjects(root, "musicCarouselShelfRenderer", collected)
            collected
        }

        return carousels.mapNotNull { parseCarouselShelf(it) }
    }

    /**
     * Parses the mood/activity chips row from YouTube Music Home response.
     * Walks sectionListRenderer.header.chipCloudRenderer.chips and extracts
     * title and browse parameters. Chips with missing title or params are skipped safely.
     */
internal fun InnerTubeMusicApi.parseHomeChips(root: JsonElement): List<HomeChip> {
        val rootObj = root.asObject() ?: return emptyList()
        val secList = rootObj.obj("contents")
            ?.obj("singleColumnBrowseResultsRenderer")
            ?.array("tabs")
            ?.firstOrNull()?.asObject()
            ?.obj("tabRenderer")
            ?.obj("content")
            ?.obj("sectionListRenderer")

        val chipElements = secList?.obj("header")?.obj("chipCloudRenderer")?.array("chips")
            ?: run {
                val collected = mutableListOf<JsonObject>()
                collectObjects(root, "chipCloudRenderer", collected)
                collected.firstOrNull()?.array("chips")
            } ?: return emptyList()

        return chipElements.mapNotNull { element ->
            val renderer = element.asObject()?.obj("chipCloudChipRenderer") ?: return@mapNotNull null
            val title = renderer.obj("text")?.array("runs")?.firstOrNull()?.asObject()?.string("text")?.trim()
                ?: renderer.obj("text")?.string("simpleText")?.trim()
            if (title.isNullOrBlank()) return@mapNotNull null

            val endpoint = renderer.obj("navigationEndpoint")?.obj("browseEndpoint")
            val params = endpoint?.string("params")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val browseId = endpoint.string("browseId")?.takeIf { it.isNotBlank() }

            HomeChip(
                title = title,
                params = params,
                browseId = browseId,
            )
        }
    }

    /**
     * Extracts the Home continuation token for pagination.
     * Checks sectionListRenderer.continuations on page 1,
     * sectionListContinuation.continuations on subsequent continuation pages,
     * with fallback to [genericContinuationToken].
     */
internal fun InnerTubeMusicApi.parseHomeContinuationToken(root: JsonElement): String? {
        val rootObj = root.asObject() ?: return null

        // 1. Check page 1 sectionListRenderer.continuations
        val page1Continuations = rootObj.obj("contents")
            ?.obj("singleColumnBrowseResultsRenderer")
            ?.array("tabs")
            ?.firstOrNull()?.asObject()
            ?.obj("tabRenderer")
            ?.obj("content")
            ?.obj("sectionListRenderer")
            ?.array("continuations")

        page1Continuations?.mapNotNull { it.asObject() }?.firstNotNullOfOrNull {
            it.obj("nextContinuationData")?.string("continuation")?.takeIf(String::isNotBlank)
        }?.let { return it }

        // 2. Check continuation page sectionListContinuation.continuations
        val contContinuations = rootObj.obj("continuationContents")
            ?.obj("sectionListContinuation")
            ?.array("continuations")

        contContinuations?.mapNotNull { it.asObject() }?.firstNotNullOfOrNull {
            it.obj("nextContinuationData")?.string("continuation")?.takeIf(String::isNotBlank)
        }?.let { return it }

        // 3. Fallback to generic continuation token anywhere in tree
        return genericContinuationToken(root)
    }

    /**
     * Parses additional carousel shelves from a Home continuation response.
     * Walks continuationContents.sectionListContinuation.contents and maps each
     * musicCarouselShelfRenderer using [parseCarouselShelf].
     */
internal fun InnerTubeMusicApi.parseHomeContinuationShelves(root: JsonElement): List<HomeShelf> {
        val rootObj = root.asObject() ?: return emptyList()
        val secContContents = rootObj.obj("continuationContents")
            ?.obj("sectionListContinuation")
            ?.array("contents")

        val carousels = if (secContContents != null) {
            secContContents.mapNotNull { it.asObject()?.obj("musicCarouselShelfRenderer") }
        } else {
            val collected = mutableListOf<JsonObject>()
            collectObjects(root, "musicCarouselShelfRenderer", collected)
            collected
        }

        return carousels.mapNotNull { parseCarouselShelf(it) }
    }

internal fun InnerTubeMusicApi.directWatchVideoId(renderer: JsonObject): String? =
        renderer.obj("playlistItemData")?.string("videoId")
            ?: renderer.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: (renderer.obj("overlay") ?: renderer.obj("thumbnailOverlay"))
                ?.obj("musicItemThumbnailOverlayRenderer")
                ?.obj("content")?.obj("musicPlayButtonRenderer")
                ?.obj("playNavigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: renderer.obj("onTap")?.obj("watchEndpoint")?.string("videoId")
            ?: renderer.array("flexColumns")?.firstOrNull()?.asObject()
                ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")
                ?.array("runs")?.firstOrNull()?.asObject()
                ?.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: renderer.obj("doubleTapCommand")?.obj("watchEndpoint")?.string("videoId")

internal fun InnerTubeMusicApi.parseCardShelfSong(renderer: JsonObject): YouTubeMusicTrack? {
        val videoId = directWatchVideoId(renderer)
            ?: renderer.obj("title")?.array("runs")?.firstOrNull()?.asObject()
                ?.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: renderer.obj("onTap")?.obj("watchEndpoint")?.string("videoId")
            ?: findString(renderer, "videoId")
            ?: return null
        val titleRuns = renderer.obj("title")?.array("runs")
        val title = titleRuns?.joinToString("") { it.asObject()?.string("text").orEmpty() }?.trim()?.takeIf(String::isNotBlank)
            ?: renderer.obj("title")?.string("simpleText")?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val subRuns = renderer.obj("subtitle")?.array("runs")?.mapNotNull { it.asObject() }.orEmpty()
        val artist = subRuns.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("UC") == true
        }?.string("text") ?: subRuns.mapNotNull { it.string("text") }
            .firstOrNull { it.isLikelyArtistDetail() }
            ?: "Unknown artist"
        val album = subRuns.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("MPRE") == true
        }?.string("text")
        val duration = subRuns.mapNotNull { it.string("text") }.firstNotNullOfOrNull(::parseDuration)
        val artwork = extractThumbnailsUrl(renderer)
        return YouTubeMusicTrack(videoId, title, artist, album, artwork, duration)
    }

internal fun InnerTubeMusicApi.parseTwoRowSong(renderer: JsonObject): YouTubeMusicTrack? {
        val titleRuns = renderer.obj("title")?.array("runs")
        val videoId = directWatchVideoId(renderer)
            ?: titleRuns?.firstOrNull()?.asObject()
                ?.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: return null
        val title = titleRuns?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?.trim()?.takeIf(String::isNotBlank)
            ?: renderer.obj("title")?.string("simpleText")?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val details = renderer.obj("subtitle")?.array("runs")
            ?.mapNotNull { it.asObject() }.orEmpty()
        val artist = details.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?.string("browseId")?.startsWith("UC") == true
        }?.string("text") ?: details.mapNotNull { it.string("text") }
            .firstOrNull { it.isLikelyArtistDetail() }
            ?: "Unknown artist"
        val album = details.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?.string("browseId")?.startsWith("MPRE") == true
        }?.string("text")
        val duration = details.mapNotNull { it.string("text") }.firstNotNullOfOrNull(::parseDuration)
        val artwork = extractThumbnailsUrl(renderer)
            ?: (renderer.obj("thumbnailRenderer") ?: renderer.obj("thumbnail"))
                ?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.array("thumbnails")
                ?.lastOrNull()?.asObject()?.string("url")?.let {
                    if (it.startsWith("//")) "https:$it" else it
                }?.highResolutionArtwork()
        return YouTubeMusicTrack(videoId, title, artist, album, artwork, duration)
    }

internal fun InnerTubeMusicApi.parsePlaylistVideoRenderer(renderer: JsonObject): YouTubeMusicTrack? {
        val videoId = renderer.string("videoId") ?: return null
        val title = renderer.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: renderer.obj("title")?.string("simpleText")
            ?: return null
        val artist = renderer.obj("shortBylineText")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
            ?: "Unknown artist"
        val duration = renderer.string("lengthSeconds")?.toIntOrNull()
        val thumbnails = renderer.obj("thumbnail")?.array("thumbnails")
        val artwork = thumbnails?.lastOrNull()?.asObject()?.string("url")?.highResolutionArtwork()
            ?: extractThumbnailsUrl(renderer)
        return YouTubeMusicTrack(videoId, title, artist, null, artwork, duration)
    }

    /**
     * Parses a YouTube Music /next radio response, returning tracks in exact order,
     * the continuation token (if present), and any chained automix playlistId.
     */
internal fun InnerTubeMusicApi.parseRadioResponse(root: JsonElement): YtRadioResult {
        val tracks = mutableListOf<YouTubeMusicTrack>()
        val seenVideoIds = mutableSetOf<String>()

        fun addTrack(track: YouTubeMusicTrack?) {
            if (track != null && track.videoId.isNotBlank() && seenVideoIds.add(track.videoId)) {
                tracks.add(track)
            }
        }

        val panel = root.asObject()?.obj("continuationContents")?.obj("playlistPanelContinuation")
            ?: run {
                val panels = mutableListOf<JsonObject>()
                collectObjects(root, "playlistPanelRenderer", panels)
                panels.firstOrNull()
            }

        val contents = panel?.array("contents")
        if (contents != null) {
            for (i in 0 until contents.size) {
                addTrack(parsePlaylistPanelItem(contents[i]))
            }
        }

        if (tracks.isEmpty()) {
            val queueRenderers = mutableListOf<JsonObject>()
            collectObjects(root, "playlistPanelVideoRenderer", queueRenderers)
            for (renderer in queueRenderers) {
                addTrack(parsePlaylistPanelSong(renderer))
            }
        }

        val continuationToken = extractRadioContinuationToken(root)
        val automixPlaylistId = extractAutomixPlaylistId(root)

        return YtRadioResult(
            tracks = tracks,
            continuationToken = continuationToken,
            automixPlaylistId = automixPlaylistId,
        )
    }

internal fun InnerTubeMusicApi.parsePlaylistPanelItem(element: JsonElement): YouTubeMusicTrack? {
        val obj = element.asObject() ?: return null
        obj.obj("playlistPanelVideoRenderer")?.let { return parsePlaylistPanelSong(it) }
        obj.obj("playlistPanelVideoWrapperRenderer")
            ?.obj("primaryRenderer")
            ?.obj("playlistPanelVideoRenderer")
            ?.let { return parsePlaylistPanelSong(it) }
        return null
    }

internal fun InnerTubeMusicApi.parsePlaylistPanelSong(renderer: JsonObject): YouTubeMusicTrack? {
        if (renderer.obj("unplayableText") != null) return null
        val videoId = renderer.string("videoId")
            ?: renderer.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: return null
        val title = renderer.obj("title")?.array("runs")
            ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?.trim()?.takeIf(String::isNotBlank)
            ?: renderer.obj("title")?.string("simpleText")?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val detailRuns = (renderer.obj("longBylineText") ?: renderer.obj("shortBylineText"))
            ?.array("runs")?.mapNotNull { it.asObject() }.orEmpty()
        val artist = detailRuns.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?.string("browseId")?.startsWith("UC") == true
        }?.string("text") ?: detailRuns.mapNotNull { it.string("text") }
            .firstOrNull { it.isLikelyArtistDetail() }
            ?: "Unknown artist"
        val album = detailRuns.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?.string("browseId")?.startsWith("MPRE") == true
        }?.string("text")
        val duration = renderer.obj("lengthText")?.array("runs")
            ?.mapNotNull { it.asObject()?.string("text") }
            ?.firstNotNullOfOrNull(::parseDuration)
        val artwork = extractThumbnailsUrl(renderer)
            ?: renderer.obj("thumbnail")?.array("thumbnails")
                ?.lastOrNull()?.asObject()?.string("url")?.let {
                    if (it.startsWith("//")) "https:$it" else it
                }?.highResolutionArtwork()
        return YouTubeMusicTrack(videoId, title, artist, album, artwork, duration)
    }

internal fun InnerTubeMusicApi.parseSong(renderer: JsonObject): YouTubeMusicTrack? {
        if (isSuggestionItem(renderer)) return null
        val videoId = directWatchVideoId(renderer)
            ?: findString(renderer, "videoId")
            ?: return null
        val columns = renderer.array("flexColumns")
        val titleRuns = columns?.getOrNull(0)?.asObject()
            ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
        val title = titleRuns?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: columns?.getOrNull(0)?.asObject()
                ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.string("simpleText")
                ?.trim()?.takeIf { it.isNotBlank() }
            ?: renderer.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?.trim()?.takeIf { it.isNotBlank() }
            ?: renderer.obj("title")?.string("simpleText")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val detailRuns = (1 until (columns?.size ?: 0)).flatMap { idx ->
            columns?.getOrNull(idx)?.asObject()
                ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
                ?.mapNotNull { it.asObject() }.orEmpty()
        }
        val fixedRuns = renderer.array("fixedColumns")?.flatMap { col ->
            col.asObject()?.obj("musicResponsiveListItemFixedColumnRenderer")?.obj("text")?.array("runs")
                ?.mapNotNull { it.asObject() }.orEmpty()
        }.orEmpty()
        val allDetailRuns = detailRuns + fixedRuns + (renderer.obj("subtitle")?.array("runs")?.mapNotNull { it.asObject() }.orEmpty())

        val artist = allDetailRuns.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("UC") == true
        }?.string("text") ?: allDetailRuns.mapNotNull { it.string("text") }
            .firstOrNull { it.isLikelyArtistDetail() }
            ?: "Unknown artist"
        val album = allDetailRuns.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("MPRE") == true
        }?.string("text")
        val duration = allDetailRuns.mapNotNull { it.string("text") }.firstNotNullOfOrNull(::parseDuration)
            ?: renderer.string("lengthSeconds")?.toIntOrNull()
        val artwork = extractThumbnailsUrl(renderer)
        return YouTubeMusicTrack(videoId, title, artist, album, artwork, duration)
    }

internal fun InnerTubeMusicApi.parseEntityRenderers(root: JsonElement, kind: YouTubeMusicEntityKind): List<YouTubeMusicEntity> {
        val renderers = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveListItemRenderer", renderers)
        val fromList = renderers.mapNotNull { renderer -> parseEntity(renderer, kind) }
        val twoRowRenderers = mutableListOf<JsonObject>()
        collectObjects(root, "musicTwoRowItemRenderer", twoRowRenderers)
        val fromTwoRow = twoRowRenderers.mapNotNull { renderer -> parseTwoRowEntity(renderer, kind) }
        return (fromList + fromTwoRow).distinctBy { it.browseId }
    }

internal fun InnerTubeMusicApi.parseEntity(renderer: JsonObject, kind: YouTubeMusicEntityKind): YouTubeMusicEntity? {
        val navigation = renderer.obj("navigationEndpoint")?.obj("browseEndpoint") ?: return null
        val browseId = navigation.string("browseId") ?: return null
        if (kind == YouTubeMusicEntityKind.ARTIST && !browseId.startsWith("UC")) return null
        if (kind == YouTubeMusicEntityKind.ALBUM &&
            !browseId.startsWith("MPRE") && !browseId.startsWith("VL") &&
            !browseId.startsWith("OLAK") && !browseId.startsWith("PL")
        ) return null

        val columns = renderer.array("flexColumns")
        val name = columns?.getOrNull(0)?.asObject()
            ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
            ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?.trim()?.takeIf(String::isNotBlank) ?: return null
        val details = columns?.getOrNull(1)?.asObject()
            ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
            ?.mapNotNull { it.asObject() }.orEmpty()

        if (kind == YouTubeMusicEntityKind.ALBUM && !browseId.startsWith("MPRE")) {
            val subtitleLower = details.mapNotNull { it.string("text") }.joinToString(" ").lowercase()
            val nameLower = name.lowercase()
            val isRelease = subtitleLower.contains("album") || subtitleLower.contains("single") ||
                subtitleLower.contains("ep") || subtitleLower.contains("disc") ||
                (subtitleLower.contains("playlist") && (nameLower.contains("album") || nameLower.contains("ep")))
            if (!isRelease) return null
        }

        val artist = if (kind == YouTubeMusicEntityKind.ALBUM) {
            details.firstOrNull { run ->
                run.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("UC") == true
            }?.string("text") ?: details.mapNotNull { it.string("text") }.firstOrNull { it.isLikelyArtistDetail() }
        } else null
        val subtitle = details.mapNotNull { it.string("text")?.trim() }
            .filter { it.isNotBlank() && it !in setOf("•", "·", "Artist", "Album", "EP", "Single") }
            .joinToString(" \u2022 ")
            .trim()
            .takeIf(String::isNotBlank)
        val thumbnails = renderer.obj("thumbnail")?.obj("musicThumbnailRenderer")
            ?.obj("thumbnail")?.array("thumbnails")
        val artwork = thumbnails?.lastOrNull()?.asObject()?.string("url")?.let {
            (if (it.startsWith("//")) "https:$it" else it).highResolutionArtwork()
        }
        return YouTubeMusicEntity(
            kind = kind,
            name = name,
            artist = artist,
            subtitle = subtitle,
            browseId = browseId,
            playlistId = findString(renderer, "playlistId"),
            artworkUrl = artwork,
        )
    }

internal fun InnerTubeMusicApi.parseTwoRowEntity(renderer: JsonObject, kind: YouTubeMusicEntityKind): YouTubeMusicEntity? {
        val navigation = renderer.obj("navigationEndpoint")?.obj("browseEndpoint")
            ?: renderer.obj("title")?.array("runs")?.firstOrNull()?.asObject()?.obj("navigationEndpoint")?.obj("browseEndpoint")
            ?: return null
        val browseId = navigation.string("browseId") ?: return null
        if (kind == YouTubeMusicEntityKind.ARTIST && !browseId.startsWith("UC")) return null
        if (kind == YouTubeMusicEntityKind.ALBUM &&
            !browseId.startsWith("MPRE") && !browseId.startsWith("VL") &&
            !browseId.startsWith("OLAK") && !browseId.startsWith("PL")
        ) return null

        val name = renderer.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: renderer.obj("title")?.string("simpleText")
            ?: return null
        if (name.isBlank()) return null

        val subtitleRuns = renderer.obj("subtitle")?.array("runs")?.mapNotNull { it.asObject() }.orEmpty()

        if (kind == YouTubeMusicEntityKind.ALBUM && !browseId.startsWith("MPRE")) {
            val subtitleLower = subtitleRuns.mapNotNull { it.string("text") }.joinToString(" ").lowercase()
            val nameLower = name.lowercase()
            val isRelease = subtitleLower.contains("album") || subtitleLower.contains("single") ||
                subtitleLower.contains("ep") || subtitleLower.contains("disc") ||
                (subtitleLower.contains("playlist") && (nameLower.contains("album") || nameLower.contains("ep")))
            if (!isRelease) return null
        }

        val artist = if (kind == YouTubeMusicEntityKind.ALBUM) {
            subtitleRuns.firstOrNull { run ->
                run.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("UC") == true
            }?.string("text") ?: subtitleRuns.mapNotNull { it.string("text") }.firstOrNull { it.isLikelyArtistDetail() }
        } else null
        val subtitle = subtitleRuns.mapNotNull { it.string("text")?.trim() }
            .filter { it.isNotBlank() && it !in setOf("•", "·", "Artist", "Album", "EP", "Single") }
            .joinToString(" \u2022 ")
            .trim()
            .takeIf(String::isNotBlank)
        val thumbs = renderer.obj("thumbnailRenderer")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.array("thumbnails")
            ?: renderer.obj("thumbnail")?.array("thumbnails")
        val artwork = thumbs?.lastOrNull()?.asObject()?.string("url")?.let {
            (if (it.startsWith("//")) "https:$it" else it).highResolutionArtwork()
        }
        return YouTubeMusicEntity(
            kind = kind,
            name = name.trim(),
            artist = artist?.trim(),
            subtitle = subtitle,
            browseId = browseId,
            playlistId = findString(renderer, "playlistId"),
            artworkUrl = artwork,
        )
    }

internal suspend fun InnerTubeMusicApi.searchEntitiesFallback(
        query: String,
        kind: YouTubeMusicEntityKind,
        limit: Int,
    ): List<YouTubeMusicEntity> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val config = getWebConfig()
        val root = runCatching {
            post(
                url = "$MUSIC_API/search?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                    put("query", query.trim())
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
            )
        }.getOrNull() ?: return@withContext emptyList()

        parseEntityRenderers(root, kind).take(limit)
    }

internal fun InnerTubeMusicApi.parsePlaylistRenderers(root: JsonElement): List<YouTubePlaylistSummary> {
        val renderers = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveListItemRenderer", renderers)
        collectObjects(root, "musicTwoRowItemRenderer", renderers)
        collectObjects(root, "gridPlaylistRenderer", renderers)
        collectObjects(root, "musicGridItemRenderer", renderers)
        collectObjects(root, "playlistRenderer", renderers)
        return renderers.mapNotNull { renderer ->
            val nav = renderer.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?: renderer.obj("title")?.array("runs")?.firstOrNull()?.asObject()?.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?: renderer.obj("thumbnailOverlay")?.obj("musicItemThumbnailOverlayRenderer")?.obj("content")?.obj("musicPlayButtonRenderer")?.obj("playNavigationEndpoint")?.obj("watchEndpoint")
                ?: renderer.obj("onTap")?.obj("browseEndpoint")
            val browseId = nav?.string("browseId") ?: nav?.string("playlistId") ?: return@mapNotNull null
            val playlistId = if (browseId.startsWith("VL")) browseId.removePrefix("VL") else browseId
            // The playlist-filtered search response can still include artist,
            // album, and podcast rows. Only PL (user playlists) and RD (YouTube
            // Music mixes) browse IDs identify playlists here.
            if (!playlistId.startsWith("PL") && !playlistId.startsWith("RD")) return@mapNotNull null

            val title = renderer.array("flexColumns")?.getOrNull(0)?.asObject()
                ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?: renderer.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?: renderer.obj("title")?.string("simpleText")
                ?: return@mapNotNull null

            val subtitleRuns = renderer.array("flexColumns")?.getOrNull(1)?.asObject()
                ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
                ?: renderer.obj("subtitle")?.array("runs")
            val author = subtitleRuns?.firstOrNull {
                it.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("UC") == true
            }?.string("text") ?: subtitleRuns?.firstOrNull()?.string("text")
                ?.takeUnless { it.equals("Playlist", ignoreCase = true) }

            val trackCountText = subtitleRuns?.mapNotNull { it.asObject()?.string("text") }?.lastOrNull { "song" in it.lowercase() || "track" in it.lowercase() }

            val artwork = extractThumbnailsUrl(renderer)

            YouTubePlaylistSummary(
                id = playlistId,
                title = title.trim(),
                author = author?.trim(),
                trackCountText = trackCountText,
                artworkUrl = artwork,
            )
        }.distinctBy { it.id }
    }

internal fun InnerTubeMusicApi.extractThumbnailsUrl(renderer: JsonElement): String? {
        val foundArrays = mutableListOf<JsonArray>()
        fun findThumbnails(el: JsonElement) {
            when (el) {
                is JsonObject -> {
                    el.array("thumbnails")?.takeIf { it.isNotEmpty() }?.let { foundArrays += it }
                    el.values.forEach { findThumbnails(it) }
                }
                is JsonArray -> el.forEach { findThumbnails(it) }
                else -> Unit
            }
        }
        val thumbnailNode = renderer.asObject()?.let {
            it.obj("thumbnail") ?: it.obj("thumbnailRenderer") ?: it
        } ?: renderer
        findThumbnails(thumbnailNode)
        val bestArray = foundArrays.firstOrNull { it.isNotEmpty() } ?: return null
        val bestUrl = bestArray.lastOrNull()?.asObject()?.string("url")
            ?: bestArray.firstOrNull()?.asObject()?.string("url")
            ?: return null
        val formatted = if (bestUrl.startsWith("//")) "https:$bestUrl" else bestUrl
        return formatted.highResolutionArtwork()
    }


internal fun InnerTubeMusicApi.findString(element: JsonElement, key: String): String? = when (element) {
        is JsonObject -> {
            (element[key] as? JsonPrimitive)?.contentOrNull
                ?: element.values.firstNotNullOfOrNull { findString(it, key) }
        }
        is JsonArray -> element.firstNotNullOfOrNull { findString(it, key) }
        else -> null
    }

internal fun String.isUsefulDetail(): Boolean =
        trim().isNotBlank() && trim() !in setOf("•", "·", "Song", "Video")

internal fun String.isLikelyArtistDetail(): Boolean {
        val value = trim()
        if (!value.isUsefulDetail()) return false
        if (value.equals("Album", true) || value.equals("Single", true) ||
            value.equals("EP", true) || value.equals("Playlist", true)
        ) return false
        if (parseDuration(value) != null || value.matches(Regex("^(19|20)\\d{2}$"))) return false
        // Centralized stat detection covers "15M listens", "15 ml listens",
        // "2.3M monthly listeners", "10K subscribers", "Track 16", etc.
        // The explicit contains-checks below stay as a fast pre-filter so a
        // future ArtistHelper regression can never leak counters as artists.
        if (value.contains(" view", ignoreCase = true) ||
            value.contains(" views", ignoreCase = true) ||
            value.contains(" song", ignoreCase = true) ||
            value.contains(" play", ignoreCase = true) ||
            value.contains(" plays", ignoreCase = true) ||
            value.contains(" stream", ignoreCase = true) ||
            value.contains(" track", ignoreCase = true) ||
            value.contains(" listen", ignoreCase = true) ||
            value.contains(" subscrib", ignoreCase = true) ||
            value.contains(" follow", ignoreCase = true) ||
            value.contains(" monthly", ignoreCase = true) ||
            value.contains(" fan", ignoreCase = true)
        ) return false
        if (com.lastwave.app.util.ArtistHelper.isPlayCountOrStat(value)) return false
        // Leading digit + stat word anywhere ("15 ml listens") is never an artist.
        // Real artists starting with digits ("1975", "21 Savage", "30 Seconds")
        // don't contain counter words, so this is safe.
        val lower = value.lowercase()
        if (value.firstOrNull()?.isDigit() == true &&
            (lower.contains("listen") || lower.contains("subscrib") ||
                lower.contains("follow") || lower.contains("monthly") ||
                lower.contains("play") || lower.contains("view") ||
                lower.contains("stream") || lower.contains("scrobbl"))
        ) return false
        return true
    }

internal fun parseDuration(value: String): Int? {
        val parts = value.trim().split(':').mapNotNull(String::toIntOrNull)
        if (parts.size !in 2..3) return null
        return parts.fold(0) { total, part -> total * 60 + part }
    }

internal fun InnerTubeMusicApi.similarity(a: String, b: String): Int = TextMatch.similarity(a, b)

internal fun InnerTubeMusicApi.matchScore(candidate: YouTubeMusicTrack, title: String, artist: String): Int =
        TextMatch.matchScore(candidate, title, artist)

internal fun InnerTubeMusicApi.tokens(value: String): Set<String> = TextMatch.tokens(value)

internal fun InnerTubeMusicApi.baseTitle(value: String): String = TextMatch.baseTitle(value)

internal fun String.highResolutionArtwork(): String {
        val url = if (startsWith("//")) "https:$this" else this
        return when {
            (url.contains("googleusercontent.com") || url.contains("ggpht.com")) && '=' in url ->
                url.substringBeforeLast('=') + "=w1200-h1200-l90-rj"
            else -> url
        }
    }

internal fun InnerTubeMusicApi.normalize(value: String): String = TextMatch.normalize(value)

