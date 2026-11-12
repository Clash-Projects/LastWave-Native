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


internal suspend fun InnerTubeMusicApi.fetchAccountInfoImpl(): YtAccountInfo? = withContext(Dispatchers.IO) {
        if (!ytAuth.connection.value.isConnected) return@withContext null
        val config = getWebConfig()
        val root = runCatching {
            post(
                url = "$MUSIC_API/account/account_menu?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
                authenticated = true,
            )
        }.getOrNull() ?: return@withContext null

        val headers = mutableListOf<JsonObject>()
        collectObjects(root, "activeAccountHeaderRenderer", headers)
        val header = headers.firstOrNull() ?: return@withContext null
        val accountName = header.obj("accountName")?.array("runs")?.firstOrNull()
            ?.asObject()?.string("text")?.trim().orEmpty()
        if (accountName.isBlank()) return@withContext null
        YtAccountInfo(
            accountName = accountName,
            channelHandle = header.obj("channelHandle")?.array("runs")?.firstOrNull()
                ?.asObject()?.string("text"),
            photoUrl = header.obj("accountPhoto")?.obj("thumbnails")?.array("thumbnails")
                ?.lastOrNull()?.asObject()?.string("url"),
        )
    }

    /**
     * Every channel/profile switchable inside the current session, active
     * entry first. Primary source is `GET /getAccountSwitcherEndpoint` — the
     * same endpoint music.youtube.com fires on the avatar → switch-account
     * button — whose `accountItem` entries carry the brand-channel delegation
     * token (`pageIdToken.pageId`, surfaced in the web client's
     * `signin?...&pageid=` switch request) and/or a multi-login
     * `authuser=N` index. `account_menu` (`accountItemRenderer` /
     * `onBehalfOfUser`) is kept as a fallback. Cookies stay identical across
     * channels, which is why cookie-only clients are stuck on the first one.
     */
internal suspend fun InnerTubeMusicApi.fetchAvailableChannelsImpl(): List<YtChannelOption> = withContext(Dispatchers.IO) {
        if (!ytAuth.connection.value.isConnected) return@withContext emptyList()
        val config = getWebConfig()
        val switcherRoot = runCatching { fetchAccountSwitcherRoot(config) }.getOrNull()
        val switcherOptions = switcherRoot?.let(::parseAccountSwitcherChannels).orEmpty()
        if (switcherOptions.isNotEmpty()) return@withContext markActiveChannel(switcherOptions)

        val root = runCatching {
            post(
                url = "$MUSIC_API/account/account_menu?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
                authenticated = true,
            )
        }.getOrNull() ?: return@withContext emptyList()

        val options = mutableListOf<YtChannelOption>()
        // Active session channel — the baseline "default" choice.
        val activeHeaders = mutableListOf<JsonObject>()
        collectObjects(root, "activeAccountHeaderRenderer", activeHeaders)
        activeHeaders.firstOrNull()?.let { header ->
            val name = header.obj("accountName")?.array("runs")?.firstOrNull()
                ?.asObject()?.string("text")?.trim().orEmpty()
            if (name.isNotBlank()) {
                options += YtChannelOption(
                    accountName = name,
                    channelHandle = header.obj("channelHandle")?.array("runs")?.firstOrNull()
                        ?.asObject()?.string("text"),
                    photoUrl = header.obj("accountPhoto")?.obj("thumbnails")?.array("thumbnails")
                        ?.lastOrNull()?.asObject()?.string("url"),
                    isActive = true,
                )
            }
        }

        val items = mutableListOf<JsonObject>()
        collectObjects(root, "accountItemRenderer", items)
        for (item in items) {
            val name = item.obj("accountName")?.string("simpleText")?.trim()
                ?: item.obj("accountName")?.array("runs")?.firstOrNull()
                    ?.asObject()?.string("text")?.trim()
                ?: continue
            if (name.isBlank()) continue
            val (channelId, authUser) = findChannelToken(item)
            val photo = item.obj("accountPhoto")?.obj("thumbnails")?.array("thumbnails")
                ?.lastOrNull()?.asObject()?.string("url")
                ?: extractThumbnailsUrl(item)
            val handle = item.obj("channelHandle")?.array("runs")?.firstOrNull()
                ?.asObject()?.string("text")
            // Skip the row that merely repeats the active session entry.
            if (channelId == null && authUser == null &&
                options.any { it.accountName.equals(name, ignoreCase = true) }
            ) continue
            options += YtChannelOption(
                channelId = channelId,
                authUserIndex = authUser,
                accountName = name,
                channelHandle = handle,
                photoUrl = photo,
                isActive = false,
            )
        }
        markActiveChannel(
            options.distinctBy { Triple(it.channelId, it.authUserIndex, it.accountName.lowercase()) },
        )
    }

    /**
     * GETs music.youtube.com's own account-switcher endpoint (the one the
     * avatar → switch-account button fires), which answers as `)]}\'` +
     * `{"code":"SUCCESS","data":{...}}`. Returns the `data` subtree, or
     * throws on any failure (callers fall back to `account_menu`).
     */
internal suspend fun InnerTubeMusicApi.fetchAccountSwitcherRoot(config: InnerTubeMusicApi.WebConfig): JsonObject = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val account = ytAuth.connection.value
        if (!account.isConnected) throw IOException("YouTube Music not connected")
        val builder = Request.Builder()
            .url("$YOUTUBE_MUSIC_ORIGIN/getAccountSwitcherEndpoint")
            .get()
            .header("User-Agent", WEB_USER_AGENT)
            .header("Origin", YOUTUBE_MUSIC_ORIGIN)
            .header("Referer", "$YOUTUBE_MUSIC_ORIGIN/")
            .header("X-YouTube-Client-Name", CLIENT_IDS["WEB_REMIX"] ?: "WEB_REMIX")
            .header("X-YouTube-Client-Version", config.clientVersion)
        ytAuth.cookieHeaderValue(account)?.let { builder.header("Cookie", it) }
        ytAuth.authorizationHeaderValue(account = account)?.let { builder.header("Authorization", it) }
        // The web client always sends an explicit authuser here (0 for the
        // first login); brand rows under it carry `authuser=0&pageid=...`.
        builder.header("X-Goog-AuthUser", (account.authUserIndex ?: 0).toString())
        webConfig?.visitorData?.takeIf { isValidVisitorData(it) }?.let { builder.header("X-Goog-Visitor-Id", it) }
        val call = innerTubeClient.newCall(builder.build())
        call.timeout().timeout(ACCOUNT_SWITCHER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val (status, text) = call.readResponseBody()
        if (status !in 200..299) throw InnerTubeHttpException(status)
        // Anti-hijack prefix (`)]}\'`): the JSON payload starts at the first `{`.
        val body = text.substringAfter('{', missingDelimiterValue = "")
        if (body.isEmpty()) throw IOException("Empty account switcher response")
        val parsed = try {
            json.parseToJsonElement("{$body").jsonObject
        } catch (error: Exception) {
            throw IOException("Invalid account switcher response", error)
        }
        if (parsed["code"]?.jsonPrimitive?.contentOrNull != "SUCCESS") {
            throw IOException("Account switcher rejected session")
        }
        parsed["data"]?.jsonObject ?: parsed
    }

    /**
     * Parses `getAccountSwitcherEndpoint` rows (`accountItem`, not
     * `accountItemRenderer`). Brand channels carry
     * `supportedTokens[].pageIdToken.pageId`; the main channel has no
     * `pageIdToken`. Multi-login index comes from
     * `accountSigninToken.signinUrl`'s `authuser=N` (brand rows under the
     * first login carry `authuser=0&pageid=...`).
     */
internal fun InnerTubeMusicApi.parseAccountSwitcherChannels(root: JsonObject): List<YtChannelOption> {
        val items = mutableListOf<JsonObject>()
        collectObjects(root, "accountItem", items)
        if (items.isEmpty()) return emptyList()
        val options = mutableListOf<YtChannelOption>()
        for (item in items) {
            val name = item.obj("accountName")?.string("simpleText")?.trim()
                ?: item.obj("accountName")?.array("runs")?.firstOrNull()
                    ?.asObject()?.string("text")?.trim()
                ?: continue
            if (name.isBlank()) continue
            // "Other accounts" rows without a channel are still useful as
            // multi-login targets, but nameless rows never are.
            val (pageId, authUser, isSelected) = findSwitcherToken(item)
            val photo = item.obj("accountPhoto")?.obj("thumbnails")?.array("thumbnails")
                ?.lastOrNull()?.asObject()?.string("url")
                ?: extractThumbnailsUrl(item)
            val handle = item.obj("channelHandle")?.array("runs")?.firstOrNull()
                ?.asObject()?.string("text")
            options += YtChannelOption(
                channelId = null,
                authUserIndex = authUser,
                accountName = name,
                channelHandle = handle,
                photoUrl = photo,
                isActive = isSelected,
                pageId = pageId.orEmpty(),
            )
        }
        return options.distinctBy { Triple(it.pageId, it.authUserIndex, it.accountName.lowercase()) }
    }

    /** Marks the locally selected channel active (server `isSelected` is only
     *  a fallback for fresh logins — we switch per-request, never server-side). */
internal fun InnerTubeMusicApi.markActiveChannel(options: List<YtChannelOption>): List<YtChannelOption> {
        if (options.isEmpty()) return options
        val current = ytAuth.connection.value
        val matchIndex = options.indexOfFirst { option ->
            option.pageId == current.pageId &&
                option.channelId == current.onBehalfOfUser &&
                option.authUserIndex == current.authUserIndex
        }
        val resolved = if (matchIndex >= 0) {
            options.mapIndexed { index, option -> option.copy(isActive = index == matchIndex) }
        } else if (options.none { it.isActive }) {
            // No stored selection and no server hint: default (blank pageId) first.
            options.mapIndexed { index, option ->
                option.copy(isActive = index == options.indexOfFirst { it.pageId.isBlank() }.takeIf { it >= 0 } ?: 0)
            }
        } else {
            options
        }
        return resolved.sortedByDescending { it.isActive }
    }

internal data class SwitcherToken(
        val pageId: String?,
        val authUserIndex: Int?,
        val isSelected: Boolean,
    )

internal fun InnerTubeMusicApi.findSwitcherToken(item: JsonObject): SwitcherToken {
        var pageId: String? = null
        var pageIdFromUrl: String? = null
        var authUser: Int? = null
        var isSelected = false
        fun visit(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, child) ->
                    if (child is JsonPrimitive && child.isString) {
                        val value = child.contentOrNull.orEmpty()
                        when (key) {
                            "pageId" -> if (pageId == null && value.isNotBlank()) pageId = value
                            "signinUrl", "signinurl" -> {
                                if (authUser == null) {
                                    Regex("authuser=(\\d+)").find(value)?.groupValues
                                        ?.getOrNull(1)?.toIntOrNull()?.let { authUser = it }
                                }
                                if (pageIdFromUrl == null) {
                                    Regex("[?&]pageid=(\\d+)").find(value)?.groupValues
                                        ?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { pageIdFromUrl = it }
                                }
                            }
                        }
                        if (authUser == null) {
                            Regex("authuser=(\\d+)").find(value)?.groupValues
                                ?.getOrNull(1)?.toIntOrNull()?.let { authUser = it }
                        }
                    } else if (child is JsonPrimitive) {
                        if (key == "isSelected" && child.toString() == "true") isSelected = true
                    }
                    visit(child)
                }
                is JsonArray -> element.forEach(::visit)
                else -> Unit
            }
        }
        visit(item)
        return SwitcherToken(pageId ?: pageIdFromUrl, authUser, isSelected)
    }

    /** Scoped search for a channel delegation token inside one menu entry. */
internal fun InnerTubeMusicApi.findChannelToken(item: JsonObject): Pair<String?, Int?> {
        var onBehalf: String? = null
        var channelId: String? = null
        var browseId: String? = null
        var authUser: Int? = null
        fun visit(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, child) ->
                    if (child is JsonPrimitive && child.isString) {
                        val value = child.contentOrNull.orEmpty()
                        when (key) {
                            "onBehalfOfUser" -> if (value.isNotBlank()) onBehalf = value
                            "channelId" -> if (value.isChannelId()) channelId = value
                            "browseId" -> if (value.isChannelId()) browseId = value
                        }
                        if (authUser == null) {
                            Regex("authuser=(\\d+)").find(value)?.groupValues
                                ?.getOrNull(1)?.toIntOrNull()?.let { authUser = it }
                        }
                    }
                    visit(child)
                }
                is JsonArray -> element.forEach(::visit)
                else -> Unit
            }
        }
        visit(item)
        return Pair(onBehalf ?: channelId ?: browseId, authUser)
    }

internal fun String.isChannelId(): Boolean =
        startsWith("UC") && length >= 20 && all { it.isLetterOrDigit() || it == '_' || it == '-' }

    /** Creates a PRIVATE playlist owned by the connected account; returns its id. */
internal suspend fun InnerTubeMusicApi.createRemotePlaylistImpl(title: String): String? = withContext(Dispatchers.IO) {
        if (!ytAuth.connection.value.isConnected) return@withContext null
        val cleanTitle = title.replace("<", "(").replace(">", ")").take(150)
            .ifBlank { "LastWave Playlist" }
        val config = getWebConfig()
        val root = runCatching {
            post(
                url = "$MUSIC_API/playlist/create?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                    put("title", cleanTitle)
                    put("privacyStatus", "PRIVATE")
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
                authenticated = true,
            )
        }.onFailure {
            android.util.Log.w(PLAYLIST_LOG_TAG, "createRemotePlaylist failed for \"$cleanTitle\"", it)
        }.getOrNull() ?: return@withContext null
        root.string("playlistId")?.takeIf { it.isNotBlank() }
            ?: findString(root, "playlistId")?.takeIf { it.isNotBlank() }
    }

    /** Renames an owned remote playlist via ACTION_SET_PLAYLIST_NAME. */
internal suspend fun InnerTubeMusicApi.renameRemotePlaylistImpl(playlistId: String, title: String): Boolean = withContext(Dispatchers.IO) {
        editRemotePlaylist(
            playlistId = playlistId,
            actions = listOf(buildJsonObject {
                put("action", "ACTION_SET_PLAYLIST_NAME")
                put("playlistName", title.take(150))
            }),
        )
    }

    /** Deletes a remote playlist owned by the connected account. */
internal suspend fun InnerTubeMusicApi.deleteRemotePlaylistImpl(playlistId: String): Boolean = withContext(Dispatchers.IO) {
        if (!ytAuth.connection.value.isConnected) return@withContext false
        val config = getWebConfig()
        // HTTP success is authoritative for this endpoint; some responses omit
        // the "status" field entirely, so don't require it.
        runCatching {
            post(
                url = "$MUSIC_API/playlist/delete?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                    put("playlistId", playlistId)
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
                authenticated = true,
            )
            true
        }.getOrDefault(false)
    }

    /** Appends videos (deduped server-side); batched at InnerTube's ~50 actions/request. */
internal suspend fun InnerTubeMusicApi.addVideosToRemotePlaylistImpl(playlistId: String, videoIds: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            if (videoIds.isEmpty()) return@withContext true
            videoIds.chunked(WRITE_ACTIONS_PER_REQUEST).all { chunk ->
                editRemotePlaylist(
                    playlistId = playlistId,
                    actions = chunk.map { videoId ->
                        buildJsonObject {
                            put("action", "ACTION_ADD_VIDEO")
                            put("addedVideoId", videoId)
                            put("dedupeOption", "DEDUPE_OPTION_SKIP")
                        }
                    },
                )
            }
        }

    /** Removes entries by their per-entry setVideoId (from [fetchOwnedPlaylist]). */
internal suspend fun InnerTubeMusicApi.removeVideosFromRemotePlaylistImpl(
        playlistId: String,
        removals: List<Pair<String, String>>,
    ): Boolean = withContext(Dispatchers.IO) {
        if (removals.isEmpty()) return@withContext true
        removals.chunked(WRITE_ACTIONS_PER_REQUEST).all { chunk ->
            editRemotePlaylist(
                playlistId = playlistId,
                actions = chunk.map { (setVideoId, removedVideoId) ->
                    buildJsonObject {
                        put("action", "ACTION_REMOVE_VIDEO")
                        put("setVideoId", setVideoId)
                        put("removedVideoId", removedVideoId)
                    }
                },
            )
        }
    }

    /**
     * Likes a video on the connected YouTube Music account (the same
     * `like/like` the web player posts). Best effort: a failure returns
     * false for the caller to report, never throws.
     */
internal suspend fun InnerTubeMusicApi.likeVideoImpl(videoId: String): Boolean = setVideoLiked(videoId, liked = true)

    /** Clears a like (unliking in the player removes it on YouTube too). */
internal suspend fun InnerTubeMusicApi.removeVideoLikeImpl(videoId: String): Boolean = setVideoLiked(videoId, liked = false)

    /**
     * Whether the connected account has liked [videoId] on YouTube Music, read
     * the way SimpMusic's getLikedInfo does: an authenticated WEB_REMIX `next`
     * and the player overlay's likeButtonRenderer.likeStatus ("LIKE" /
     * "INDIFFERENT" / "DISLIKE"). Null when signed out or the read failed.
     */
internal suspend fun InnerTubeMusicApi.getYouTubeLikeStatusImpl(videoId: String): Boolean? = withContext(Dispatchers.IO) {
        if (videoId.isBlank() || !ytAuth.connection.value.isConnected) return@withContext null
        runCatching {
            val config = getWebConfig()
            val root = post(
                url = "$MUSIC_API/next?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData, authenticated = true))
                    put("videoId", videoId)
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
                authenticated = true,
                callTimeoutMs = RELATED_REQUEST_TIMEOUT_MS,
            )
            val status = root.obj("playerOverlays")
                ?.obj("playerOverlayRenderer")
                ?.array("actions")
                ?.firstNotNullOfOrNull { it.obj("likeButtonRenderer")?.string("likeStatus") }
            when (status) {
                "LIKE" -> true
                "INDIFFERENT", "DISLIKE" -> false
                else -> null
            }
        }.onFailure {
            android.util.Log.w(PLAYLIST_LOG_TAG, "like status read failed for $videoId", it)
        }.getOrNull()
    }

internal suspend fun InnerTubeMusicApi.setVideoLiked(
    videoId: String,
    liked: Boolean,
    account: YtConnection = ytAuth.connection.value,
): Boolean =
        withContext(Dispatchers.IO) {
            if (videoId.isBlank()) return@withContext false
            if (!account.isConnected || ytAuth.connection.value != account) return@withContext false
            val config = getWebConfig()
            val endpoint = if (liked) LIKE_ENDPOINT else REMOVE_LIKE_ENDPOINT
            runCatching {
                post(
                    url = "$MUSIC_API/$endpoint?key=${config.apiKey}&prettyPrint=false",
                    body = buildJsonObject {
                        put("context", context("WEB_REMIX", config.clientVersion, config.visitorData, authenticated = true))
                        put("target", likeTargetBody(videoId))
                    },
                    clientName = "WEB_REMIX",
                    clientVersion = config.clientVersion,
                    userAgent = WEB_USER_AGENT,
                    authenticated = true,
                    authenticatedAccount = account,
                )
                true
            }.onFailure {
                android.util.Log.w(PLAYLIST_LOG_TAG, "$endpoint failed for $videoId", it)
            }.getOrDefault(false)
        }

    /** Reads back an OWNED playlist with each item's setVideoId for diffs/removals. */
internal suspend fun InnerTubeMusicApi.fetchOwnedPlaylistImpl(
        playlistIdOrUrl: String,
        stopAfterVideoId: String? = null,
    ): YtOwnedPlaylist? = withContext(Dispatchers.IO) {
        if (!ytAuth.connection.value.isConnected) return@withContext null
        val rawId = extractPlaylistId(playlistIdOrUrl)
        if (rawId.isBlank()) return@withContext null
        val browseId = when {
            rawId.startsWith("VL") || rawId.startsWith("RDCLAK") || rawId.startsWith("FE") || rawId.startsWith("MPRE") || rawId.startsWith("UC") -> rawId
            else -> "VL$rawId"
        }

        val rootResult = runCatching { browseRoot(browseId, authenticated = true) }
        val root = rootResult.getOrNull()
            ?: (if (browseId != rawId) runCatching { browseRoot(rawId, authenticated = true) }.getOrNull() else null)
            ?: run {
                android.util.Log.w(PLAYLIST_LOG_TAG, "fetchOwnedPlaylist failed for browseId=$browseId", rootResult.exceptionOrNull())
                return@withContext null
            }

        val header = playlistHeader(root)
        val title = extractTitleFromHeader(header, root)
            ?: root.obj("header")?.obj("musicEditablePlaylistDetailHeaderRenderer")
                ?.obj("header")?.obj("musicResponsiveHeaderRenderer")?.obj("title")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: root.obj("header")?.obj("musicDetailHeaderRenderer")?.obj("title")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: root.obj("header")?.obj("musicResponsiveHeaderRenderer")?.obj("title")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: "Playlist"

        val shelves = playlistTrackContainers(root)
        val containers = if (shelves.isNotEmpty()) shelves else listOf(root)

        val items = mutableListOf<YtOwnedPlaylistItem>()
        val seenEntries = mutableSetOf<String>()
        fun absorb(element: JsonElement) {
            val renderers = mutableListOf<JsonObject>()
            collectObjects(element, "musicResponsiveListItemRenderer", renderers)
            collectObjects(element, "playlistVideoRenderer", renderers)
            for (renderer in renderers) {
                if (isSuggestionItem(renderer)) continue
                val videoId = renderer.obj("playlistItemData")?.string("videoId")
                    ?: (renderer["videoId"] as? JsonPrimitive)?.contentOrNull
                    ?: findString(renderer, "videoId")
                    ?: continue
                val setVideoId = extractSetVideoId(renderer)
                val entryKey = setVideoId ?: videoId
                if (videoId.isBlank() || !seenEntries.add(entryKey)) continue
                items += YtOwnedPlaylistItem(videoId, setVideoId)
            }
        }
        containers.forEach(::absorb)

        var token = containers.firstNotNullOfOrNull(::playlistTrackContinuationToken)
        val seenTokens = mutableSetOf<String>()
        var page = 0
        fun targetFound() = stopAfterVideoId != null && items.any {
            it.videoId == stopAfterVideoId && !it.setVideoId.isNullOrBlank()
        }
        var complete = true
        while (!targetFound() && !token.isNullOrBlank() && page < MAX_CONTINUATION_PAGES) {
            val currentToken = token ?: break
            if (!seenTokens.add(currentToken)) break
            val nextPage = runCatching { browseContinuation(currentToken, authenticated = true) }
                .getOrNull()
            if (nextPage == null) {
                // A continuation page failed to load (e.g. a 429). items is now a
                // truncated view of the real playlist — flag it so diffing callers
                // skip the merge instead of treating present tracks as "removed".
                complete = false
                break
            }
            val pageContainers = playlistTrackContainers(nextPage)
            val effectivePageContainers = if (pageContainers.isNotEmpty()) pageContainers else listOf(nextPage)
            effectivePageContainers.forEach(::absorb)
            token = effectivePageContainers.firstNotNullOfOrNull(::playlistTrackContinuationToken)
            page++
        }
        // Hitting the page cap with a token still pending is also a truncated view
        // (unless we stopped early because the target was already found).
        if (!targetFound() && !token.isNullOrBlank() && page >= MAX_CONTINUATION_PAGES) {
            complete = false
        }

        YtOwnedPlaylist(id = rawId, title = title, items = items, complete = complete)
    }

internal suspend fun InnerTubeMusicApi.editRemotePlaylist(playlistId: String, actions: List<JsonElement>): Boolean =
        withContext(Dispatchers.IO) {
            if (!ytAuth.connection.value.isConnected) return@withContext false
            val cleanId = playlistId.removePrefix("VL")
            val config = getWebConfig()
            runCatching {
                val root = post(
                    url = "$MUSIC_API/browse/edit_playlist?key=${config.apiKey}&prettyPrint=false",
                    body = buildJsonObject {
                        put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                        put("playlistId", cleanId)
                        put("actions", JsonArray(actions))
                    },
                    clientName = "WEB_REMIX",
                    clientVersion = config.clientVersion,
                    userAgent = WEB_USER_AGENT,
                    authenticated = true,
                )
                val status = root.string("status").orEmpty()
                status.isBlank() || status.contains("SUCCEEDED", ignoreCase = true) || root["actions"] != null
            }.getOrElse { false }
        }

