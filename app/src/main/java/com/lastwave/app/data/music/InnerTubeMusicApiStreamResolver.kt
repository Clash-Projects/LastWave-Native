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


internal fun InnerTubeMusicApi.peekCachedStreamImpl(videoId: String, maxBitrateBps: Int?): YouTubeAudioStream? {
        if (videoId.isBlank()) return null
        val now = System.currentTimeMillis()
        val authScope = playbackAuthScope()
        val cached = latestCachedStream(videoId, authScope, maxBitrateBps)?.value ?: return null
        return if (cached.stream.isFresh(cached.cachedAtEpochMs, now)) {
            cached.stream
        } else {
            null
        }
    }

private fun InnerTubeMusicApi.latestCachedStream(videoId: String, authScope: String, maxBitrateBps: Int?) =
        streamCache.entries.asSequence()
            .filter {
                it.key.videoId == videoId && it.key.lookupAuthScope == authScope &&
                    it.key.maxBitrateBps == maxBitrateBps
            }
            .maxByOrNull { it.value.cachedAtEpochMs }

    /**
     * Resolves and byte-probes an expiring googlevideo URL. A bitrate limit selects
     * a known audio rendition within that limit and gets its own cache entry and request.
     */
internal suspend fun InnerTubeMusicApi.resolveAudioStreamImpl(
        videoId: String,
        maxBitrateBps: Int?,
    ): YouTubeAudioStream = withContext(Dispatchers.IO) {
        require(videoId.isNotBlank()) { "Missing YouTube Music video id" }
        require(maxBitrateBps == null || maxBitrateBps > 0) { "Invalid YouTube audio bitrate limit" }
        val startedAt = SystemClock.elapsedRealtime()
        logStage(videoId, "start")
        val now = System.currentTimeMillis()
        val authScope = playbackAuthScope()
        val requestKey = resolutionKey(videoId, authScope, maxBitrateBps)

        latestCachedStream(videoId, authScope, maxBitrateBps)
            ?.let { entry ->
                val cached = entry.value
                val stream = cached.stream
                if (stream.isFresh(cached.cachedAtEpochMs, now)) {
                    lastResolvedStreams[requestKey] = stream
                    logStreamEvent("cache-hit", stream)
                    return@withContext stream
                }
                streamCache.remove(entry.key, entry.value)
                lastResolvedStreams.remove(requestKey, stream)
                if (stream.expiresAtEpochMs == null || stream.expiresAtEpochMs - now > URL_EXPIRY_MARGIN_MS) {
                    reportPlaybackFailure(videoId, stream)
                }
                logStreamEvent("cache-rejected", stream)
            }

        val shared = activeStreamRequests.computeIfAbsent(requestKey) {
            lateinit var request: SharedStreamRequest
            val deferred = apiScope.async(start = CoroutineStart.LAZY) {
                kotlinx.coroutines.withTimeout(STREAM_RESOLVE_TOTAL_TIMEOUT_MS) {
                    resolveAudioStreamInternal(videoId, authScope, startedAt, maxBitrateBps)
                }
            }
            request = SharedStreamRequest(deferred)
            deferred.invokeOnCompletion {
                activeStreamRequests.remove(requestKey, request)
            }
            request
        }
        shared.waiters.incrementAndGet()
        shared.deferred.start()
        try {
            // Hard cap on one full resolution: without it a bad network could
            // chain every fallback stage into a 60-90s wait before the caller
            // ever got a chance to retry with fresh state.
            kotlinx.coroutines.withTimeoutOrNull(STREAM_RESOLVE_TOTAL_TIMEOUT_MS) {
                shared.deferred.await()
            } ?: throw IOException("Timed out resolving audio stream for $videoId")
        } finally {
            shared.waiters.decrementAndGet()
            // Do not cancel the shared deferred when the last waiter leaves.
            // Prefetch is cancelled the moment the user taps that same track,
            // which used to abort a player response that had already arrived
            // and force a second 2s youtubei/player round trip. The request
            // lives on apiScope, writes the stream cache, and the next waiter
            // joins it via activeStreamRequests.
        }
    }

    /**
     * Download-only NewPipe extraction preferring the Opus (WebM) rendition, which
     * the download pipeline remuxes losslessly into a fully tagged .opus file.
     * Falls back to [resolveAudioStream] if NewPipe fails (e.g. age-restricted content).
     */
internal suspend fun InnerTubeMusicApi.resolveDownloadStreamImpl(videoId: String): YouTubeAudioStream = withContext(Dispatchers.IO) {
        require(videoId.isNotBlank()) { "Missing YouTube Music video id" }
        try {
            streamExtractor.resolveAudioStream(videoId, preferOpus = true)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            resolveAudioStream(videoId)
        }
    }

    /**
     * Resolves video through the same InnerTube clients and PoToken session as
     * audio. Returns null when no client yields a URL that passes a probe, so
     * the caller can fall back to NewPipe.
     *
     * The signed-in WEB_REMIX session goes last because its cipher decode is
     * slower, but it is the only client that passes YouTube's LOGIN_REQUIRED
     * bot check on flagged networks such as full-tunnel VPN exits.
     */
internal suspend fun InnerTubeMusicApi.resolveVideoStreamBundleImpl(
        videoId: String,
        avoidClientName: String? = null,
    ): SyncedVideoStreamBundle? = withContext(Dispatchers.IO) {
        val config = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(VISITOR_BOOTSTRAP_BUDGET_MS) { getWebConfig() }
        }.getOrNull()
        val visitorData = config?.visitorData?.takeIf { isValidVisitorData(it) }
        val poToken = fastPoToken(videoId, visitorData)
        val clients = buildList {
            addAll(VIDEO_CLIENT_ORDER.mapNotNull { name -> PLAYER_CLIENTS.firstOrNull { it.name == name } })
            if (config != null && ytAuth.connection.value.isConnected) {
                PLAYER_CLIENTS.firstOrNull { it.name == "WEB_REMIX" }
                    ?.let { add(it.copy(version = config.clientVersion, apiKey = config.apiKey)) }
            }
        }
        // Clients blocked on this network stay blocked, so lead with the one that last worked.
        // A client whose links just failed mid-play goes last, but stays available.
        val ordered = clients.sortedWith(
            compareBy<PlayerClient> { it.name == avoidClientName }.thenBy { it.name != lastVideoClientName },
        )
        for (client in ordered) {
            val budgetMs = if (client.name == "WEB_REMIX") AUTHENTICATED_CIPHER_CLIENT_BUDGET_MS else VIDEO_CLIENT_TIMEOUT_MS
            val bundle = try {
                kotlinx.coroutines.withTimeoutOrNull(budgetMs) {
                    resolveVideoBundleFromClient(videoId, client, visitorData, poToken)
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                android.util.Log.w(VIDEO_LOG_TAG, "Video client ${client.name} failed for $videoId: ${error.message}")
                null
            }
            if (bundle != null) {
                lastVideoClientName = client.name
                android.util.Log.i(VIDEO_LOG_TAG, "Video client ${client.name} resolved $videoId adaptive=${bundle.adaptiveManifestUri != null} qualities=${bundle.availableStreams.map { "${it.resolution}/${it.codec?.substringBefore('.')}/${(it.bitrate ?: 0) / 1000}k" }}")
                return@withContext bundle
            }
        }
        null
    }

    /**
     * Audio of a video from a client whose links survive a multi-chunk download. IOS
     * links pass the first chunk and then get HTTP 403, which breaks offset measuring.
     */
internal suspend fun InnerTubeMusicApi.resolveVideoAudioForAlignmentImpl(videoId: String): YouTubeAudioStream? = withContext(Dispatchers.IO) {
        val visitorData = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(VISITOR_BOOTSTRAP_BUDGET_MS) { getWebConfig() }?.visitorData
        }.getOrNull()?.takeIf { isValidVisitorData(it) }
        for (name in ALIGNMENT_AUDIO_CLIENT_ORDER) {
            val client = PLAYER_CLIENTS.firstOrNull { it.name == name } ?: continue
            try {
                return@withContext resolveDirectClientStream(
                    videoId = videoId,
                    client = client,
                    visitorData = visitorData,
                    signatureTimestamp = null,
                    cipherContext = null,
                    playerPoToken = null,
                    gvsPoToken = null,
                    authScope = playbackAuthScope(),
                    allowCipherFormats = false,
                    maxAttempts = 1,
                )
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                android.util.Log.i(VIDEO_LOG_TAG, "Alignment audio client ${client.name} failed for $videoId: ${error.message}")
            }
        }
        null
    }

    /**
     * Fetches the player script pair before the first signed-in resolve, so
     * the first fresh track after launch does not pay the watch-page fetch.
     */
internal suspend fun InnerTubeMusicApi.warmPlaybackCipherImpl(videoId: String) {
        if (videoId.isBlank() || !ytAuth.connection.value.isConnected || hasReusableCipherContext()) return
        try {
            val context = videoCipherContext(videoId, allowReuse = true) ?: return
            innerTubeXExtractor.preloadCipher(context.playerUrl)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // The first resolve fetches it instead.
        }
    }

internal fun InnerTubeMusicApi.hasReusableCipherContext(): Boolean =
        cachedVideoCipherContext != null &&
            SystemClock.elapsedRealtime() - cachedVideoCipherContextAtMs < VIDEO_CIPHER_CONTEXT_TTL_MS

internal suspend fun InnerTubeMusicApi.videoCipherContext(videoId: String, allowReuse: Boolean): PlaybackCipherContext? {
        val now = SystemClock.elapsedRealtime()
        if (allowReuse) {
            cachedVideoCipherContext?.takeIf { now - cachedVideoCipherContextAtMs < VIDEO_CIPHER_CONTEXT_TTL_MS }
                ?.forVideo(videoId, ytAuth.connection.value)
                ?.let { return it }
        }
        return innerTubeXExtractor.fetchCipherContext(videoId)?.also {
            cachedVideoCipherContext = it
            cachedVideoCipherContextAtMs = now
        }
    }

internal suspend fun InnerTubeMusicApi.resolveVideoBundleFromClient(
        videoId: String,
        client: PlayerClient,
        visitorData: String?,
        poToken: PoTokenResult?,
        allowCipherReuse: Boolean = true,
    ): SyncedVideoStreamBundle? {
        val signedIn = client.name == "WEB_REMIX"
        val contextBefore = cachedVideoCipherContext
        // Signed-in ciphers only decode with the player script paired to this request.
        val cipherContext = if (signedIn) {
            videoCipherContext(videoId, allowCipherReuse) ?: run {
                android.util.Log.w(VIDEO_LOG_TAG, "Video client ${client.name} has no cipher context for $videoId")
                return null
            }
        } else null
        val root = post(
            url = "${if (signedIn) MUSIC_API else YOUTUBE_API}/player?key=${client.apiKey}&prettyPrint=false",
            body = playbackPlayerBody(
                videoId,
                playerRequestContext(client, visitorData),
                cipherContext?.signatureTimestamp,
                poToken?.playerToken.takeIf { client.needsPoToken },
            ),
            clientName = client.name,
            clientVersion = client.version,
            userAgent = client.userAgent,
            authenticated = signedIn,
            origin = client.origin,
            referer = client.referer,
            visitorData = visitorData,
            maxAttempts = 1,
            callTimeoutMs = PLAYER_REQUEST_CALL_TIMEOUT_MS,
        )
        val streaming = root.obj("streamingData") ?: run {
            android.util.Log.w(VIDEO_LOG_TAG, "Video client ${client.name} returned no streams: ${youtubePlayerResponseSummary(root)}")
            return null
        }
        val headers = buildMap {
            putAll(client.streamRequestHeaders)
            if (!visitorData.isNullOrBlank()) put("X-Goog-Visitor-Id", visitorData)
            if (signedIn) {
                ytAuth.cookieHeaderValue()?.let { put("Cookie", it) }
                ytAuth.authorizationHeaderValue()?.let { put("Authorization", it) }
            }
        }
        val responseExpiry = streaming.string("expiresInSeconds")?.toLongOrNull()
            ?.let { System.currentTimeMillis() + it.coerceAtLeast(1L) * 1_000L }
        val token = poToken?.sessionToken.takeIf { client.needsGvsPoToken }
        // Pick formats while url still holds the undecoded source, so only the kept ones get deciphered.
        val allFormats = streaming.array("formats").orEmpty() + streaming.array("adaptiveFormats").orEmpty()
        // The video's own audio lets the player measure how this edit lines up with the song.
        val pairedAudioFormat = allFormats
            .filterIsInstance<JsonObject>()
            .filter { it.string("mimeType")?.startsWith("audio/") == true }
            .minByOrNull { it.int("bitrate") ?: Int.MAX_VALUE }
        val pairedAudioSource = pairedAudioFormat?.let {
            it.string("url") ?: it.string("signatureCipher") ?: it.string("cipher")
        }
        val undecoded = allFormats
            .mapNotNull { element ->
                val format = element as? JsonObject ?: return@mapNotNull null
                val mime = format.string("mimeType")?.takeIf { it.startsWith("video/mp4") || it.startsWith("video/webm") }
                    ?: return@mapNotNull null
                val source = format.string("url")
                    ?: format.string("signatureCipher")
                    ?: format.string("cipher")
                    ?: return@mapNotNull null
                YouTubeVideoStream(
                    videoId = videoId,
                    url = source,
                    itag = format.int("itag"),
                    mimeType = mime.substringBefore(';'),
                    codec = extractCodec(mime),
                    resolution = format.string("qualityLabel"),
                    width = format.int("width"),
                    height = format.int("height"),
                    bitrate = format.int("bitrate"),
                    durationMs = format.string("approxDurationMs")?.toLongOrNull(),
                    requestHeaders = headers,
                    initRange = format.obj("initRange")?.byteRange(),
                    indexRange = format.obj("indexRange")?.byteRange(),
                )
            }
        val preferred = preferredVideoStreamPerHeight(
            undecoded.filter { VideoDecoderSupport.canDecode(it.codec, it.width ?: 0, it.height ?: 0) },
        )
        // Audio goes first because the cipher batch holds 8; past that, the lowest video quality drops.
        val (directSources, cipherSources) = (listOfNotNull(pairedAudioSource) + preferred.map { it.url })
            .partition { it.startsWith("https://") }
        val decoded: Map<String, String> = if (cipherContext != null) {
            innerTubeXExtractor.decipherAudioUrls(videoId, cipherSources, cipherContext, directSources)
        } else {
            directSources.associateWith { it } + cipherSources.mapNotNull { cipher ->
                streamExtractor.decipherStreamUrl(videoId, cipher)?.let { cipher to it }
            }
        }
        val available = preferred.mapNotNull { stream ->
            val url = appendPoToken(decoded[stream.url] ?: return@mapNotNull null, token)
            stream.copy(
                url = url,
                expiresAtEpochMs = listOfNotNull(streamExpiryEpochMs(url), responseExpiry).minOrNull()
                    ?: (System.currentTimeMillis() + UNKNOWN_STREAM_EXPIRY_TTL_MS),
            )
        }
        val playable = selectProbedCandidate(
            candidates = rankedForAutoPlayback(available),
            urlOf = { it.url },
            probe = streamExtractor::probePlayableVideo,
            limit = MAX_VIDEO_PROBES_PER_CLIENT,
        )
        if (playable == null) {
            android.util.Log.w(VIDEO_LOG_TAG, "Video client ${client.name} had no playable URL for $videoId (${available.size} candidates)")
            // A stale player script produces rejected URLs, so retry once with a fresh one.
            val reusedContext = signedIn && contextBefore != null && cachedVideoCipherContext === contextBefore
            cachedVideoCipherContext = null
            return if (reusedContext) {
                resolveVideoBundleFromClient(videoId, client, visitorData, poToken, allowCipherReuse = false)
            } else {
                null
            }
        }
        return SyncedVideoStreamBundle(
            videoId = videoId,
            clientName = client.name,
            defaultStream = playable,
            availableStreams = available,
            pairedAudioStreamUrl = pairedAudioSource?.let(decoded::get)?.let { appendPoToken(it, token) },
            pairedAudioHeaders = headers,
            pairedAudioBitrate = pairedAudioFormat?.int("bitrate"),
            adaptiveManifestUri = buildAdaptiveVideoManifest(available)?.let { manifest ->
                "data:application/dash+xml;base64," +
                    android.util.Base64.encodeToString(manifest.toByteArray(), android.util.Base64.NO_WRAP)
            },
        )
    }

internal fun JsonObject.byteRange(): String? {
        val start = string("start") ?: return null
        val end = string("end") ?: return null
        return "$start-$end"
    }

    /**
     * One youtubei/player POST. The client that served the previous song goes
     * first; the next client runs only if this one returns no direct URL.
     * Parallel hedges used to open VISIONOS, ANDROID_VR, and TVHTML5 together
     * and cancel the losers after the winner, which showed up as several
     * player requests and a cancellation immediately after a ~2s extract.
     */
internal suspend fun InnerTubeMusicApi.resolveDirectUrlFastPath(
        videoId: String,
        authScope: String,
        maxBitrateBps: Int?,
    ): YouTubeAudioStream? {
        // Visitor data is mandatory, not best-effort: VISIONOS/ANDROID_VR
        // answer LOGIN_REQUIRED with zero formats when it is missing, so a
        // resolve without it burns the fast-path budget on misses. Bootstrap
        // it here (bounded, throttled) when the startup pre-warm hasn't —
        // including when the cached value is only the synthetic placeholder,
        // so a transient scrape miss self-heals instead of sticking.
        var visitorData = webConfig?.visitorData?.takeIf { isValidVisitorData(it) }
        if (visitorData.isNullOrBlank()) {
            val now = System.currentTimeMillis()
            if (now - lastVisitorBootstrapAttemptMs >= VISITOR_BOOTSTRAP_RETRY_COOLDOWN_MS) {
                lastVisitorBootstrapAttemptMs = now
                visitorData = runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(VISITOR_BOOTSTRAP_BUDGET_MS) {
                        getWebConfig()
                    }?.visitorData
                }.getOrNull()?.takeIf { isValidVisitorData(it) } ?: visitorData
            }
            noteAttempt("direct-fast=visitor-${if (!visitorData.isNullOrBlank()) "ok" else "missing"}")
        }
        // One PoToken per resolve, shared across clients: the minter serves
        // cache hits in milliseconds (engine is pre-warmed at startup) and
        // anything slower proceeds without a token instead of stalling audio.
        val poToken = fastPoToken(videoId, visitorData)
        val nowMs = System.currentTimeMillis()
        val config = webConfig
        val ordered = DIRECT_FAST_CLIENT_ORDER
            .mapNotNull { name ->
                val client = PLAYER_CLIENTS.firstOrNull { it.name == name } ?: return@mapNotNull null
                if (client.name == "WEB_REMIX" && config != null) {
                    client.copy(version = config.clientVersion, apiKey = config.apiKey)
                } else {
                    client
                }
            }
            .filter { nowMs >= (failedClientsUntil[clientFailureKey(videoId, it.key, authScope)] ?: 0L) }
            .sortedBy { playbackClientPriority(it.name, ytAuth.connection.value.isConnected, lastSuccessfulClientName) }
        if (ordered.isEmpty()) {
            noteAttempt("direct-fast=no-clients")
            return null
        }

        val signedIn = ytAuth.connection.value.isConnected
        val deadline = SystemClock.elapsedRealtime() +
            if (signedIn) AUTHENTICATED_CIPHER_PATH_BUDGET_MS else DIRECT_FAST_PATH_BUDGET_MS
        for (client in ordered) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                noteAttempt("direct-fast=budget-exhausted")
                return null
            }
            var failed = false
            val usesCipherContext = signedIn && client.name == "WEB_REMIX"
            // The player script and its signature timestamp are not per-video:
            // reuse a recent pair instead of a ~1 s watch-page fetch per track.
            // A reused pair that fails gets one retry with a fresh fetch.
            suspend fun attempt(allowCipherReuse: Boolean, budgetMs: Long): YouTubeAudioStream? =
                kotlinx.coroutines.withTimeoutOrNull(budgetMs) {
                    val cipherContext = if (usesCipherContext) {
                        videoCipherContext(videoId, allowReuse = allowCipherReuse)
                    } else null
                    val sigTimestamp = cipherContext?.signatureTimestamp ?: if (client.needsSignatureTimestamp) {
                        streamExtractor.getSignatureTimestamp(videoId)
                    } else null
                    if (cipherContext != null) {
                        noteAttempt("client=${client.name} cipherContext=paired")
                    }
                    resolveDirectClientStream(
                        videoId = videoId,
                        client = client,
                        visitorData = visitorData,
                        signatureTimestamp = sigTimestamp,
                        cipherContext = cipherContext,
                        playerPoToken = if (client.needsPoToken) poToken?.playerToken else null,
                        gvsPoToken = if (client.needsGvsPoToken) poToken?.sessionToken else null,
                        authScope = authScope,
                        probeCandidates = true,
                        allowCipherFormats = client.name == "TVHTML5_SIMPLY_EMBEDDED_PLAYER" || client.name == "WEB_REMIX" || client.name == "IOS" || client.name == "TVHTML5",
                        maxAttempts = 1,
                        maxBitrateBps = maxBitrateBps,
                    )
                }
            val clientBudget = if (usesCipherContext) {
                AUTHENTICATED_CIPHER_CLIENT_BUDGET_MS
            } else DIRECT_FAST_CLIENT_TIMEOUT_MS
            val reusedContext = usesCipherContext && hasReusableCipherContext()
            val stream = try {
                try {
                    attempt(allowCipherReuse = true, budgetMs = minOf(clientBudget, remaining))
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException || !reusedContext) throw error
                    noteAttempt("client=${client.name} reused-cipher failed ${resolutionFailureDetail(error)}")
                    null
                } ?: if (reusedContext) {
                    // A rotated player script makes the reused pair produce
                    // rejected URLs. Drop it and fetch a fresh one once.
                    cachedVideoCipherContext = null
                    val left = deadline - SystemClock.elapsedRealtime()
                    if (left > 0L) attempt(allowCipherReuse = false, budgetMs = minOf(clientBudget, left)) else null
                } else null
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                failed = true
                noteAttempt("client=${client.name} outcome=error ${resolutionFailureDetail(error)}")
                null
            }
            if (stream != null) {
                noteAttempt("client=${client.name} outcome=direct-url itag=${stream.itag ?: -1}")
                lastSuccessfulClientName = client.name
                return stream
            }
            if (!failed) noteAttempt("client=${client.name} outcome=timeout")
        }
        noteAttempt("direct-fast=exhausted")
        return null
    }

internal suspend fun InnerTubeMusicApi.resolveAudioStreamInternal(
        videoId: String,
        authScope: String,
        startedAt: Long,
        maxBitrateBps: Int?,
    ): YouTubeAudioStream = kotlinx.coroutines.withContext(ResolutionAttemptLog()) {
        kotlinx.coroutines.coroutineScope {
        val now = System.currentTimeMillis()

        // Use whichever validated source finishes first. In particular, a usable
        // NewPipe URL must not wait for every authenticated client to miss.
        val resolved = try {
            racePlaybackCandidates(
                first = { resolveDirectUrlFastPath(videoId, authScope, maxBitrateBps) },
                second = { racePlayableFallback(videoId, authScope, now, startedAt, maxBitrateBps) },
            )
        } catch (error: Throwable) {
            if (error.isCooperativeCancellation()) throw error
            noteAttempt("fallback=failed ${resolutionFailureDetail(error)}")
            logResolveAttempts(videoId, failed = true)
            throw error
        }
        if (resolved == null) {
            noteAttempt("fallback=exhausted")
            logResolveAttempts(videoId, failed = true)
            throw IOException("Unable to resolve a playable audio stream for $videoId")
        }
        cacheResolvedStream(resolved, now, authScope, maxBitrateBps)
        lastResolvedStreams[resolutionKey(videoId, authScope, maxBitrateBps)] = resolved

        logResolveAttempts(videoId, failed = false)
        resolved
        }
    }

internal suspend fun InnerTubeMusicApi.racePlayableFallback(
        videoId: String,
        authScope: String,
        now: Long,
        startedAt: Long,
        maxBitrateBps: Int?,
    ): YouTubeAudioStream? = kotlinx.coroutines.coroutineScope {
        // Signed in, InnerTubeX is the only path to age-restricted songs: it
        // detects the age gate, fetches the signed-in watch config and retries on
        // WEB_REMIX/TVHTML5 with the account's cookies, deciphering their formats
        // — more round trips than 7s allows. The race returns on the first
        // success, so ordinary songs are no slower.
        val innerTubeXBudgetMs = if (ytAuth.connection.value.isConnected) {
            SIGNED_IN_INNERTUBEX_STAGE_TIMEOUT_MS
        } else {
            INNERTUBEX_STAGE_TIMEOUT_MS
        }
        val channel = kotlinx.coroutines.channels.Channel<YouTubeAudioStream>(2)
        val jobs = listOf(
            launch(Dispatchers.IO) {
                var failed = false
                val candidate = try {
                    kotlinx.coroutines.withTimeoutOrNull(innerTubeXBudgetMs) {
                        val visitorData = try {
                            getWebConfig().visitorData
                        } catch (cancellation: kotlinx.coroutines.CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            null
                        }
                        innerTubeXExtractor.resolve(videoId, visitorData, authScope, maxBitrateBps)
                    }
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    failed = true
                    logClientFailure(videoId, "INNERTUBEX", failure)
                    noteAttempt("client=INNERTUBEX outcome=error ${resolutionFailureDetail(failure)}")
                    null
                }
                if (candidate == null) {
                    if (!failed) noteAttempt("client=INNERTUBEX outcome=empty")
                    return@launch
                }
                val compatible = candidate.isAdaptive || isCompatibleAudioCandidate(candidate)
                if (!compatible) {
                    noteAttempt("client=INNERTUBEX outcome=incompatible itag=${candidate.itag ?: -1}")
                    logStreamEvent("innertubex-rejected", candidate, detail = "compatible=false")
                    innerTubeXExtractor.reportPlaybackFailure(
                        videoId = videoId,
                        authScope = authScope,
                        clientProfile = candidate.clientProfile,
                    )
                    return@launch
                }
                if (probeStream(candidate, "innertubex-probe")) channel.trySend(candidate)
            },
            launch(Dispatchers.IO) {
                var failed = false
                val stream = try {
                    kotlinx.coroutines.withTimeoutOrNull(NEWPIPE_FALLBACK_TIMEOUT_MS) {
                        streamExtractor.resolveAudioStream(videoId, maxBitrateBps = maxBitrateBps)
                    }
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    failed = true
                    logClientFailure(videoId, "NEWPIPE", failure)
                    noteAttempt("client=NEWPIPE outcome=error ${resolutionFailureDetail(failure)}")
                    null
                }
                // resolveAudioStream already rejected URLs that answer 403.
                if (stream != null) {
                    noteAttempt("client=NEWPIPE outcome=accepted itag=${stream.itag ?: -1} mime=${stream.mimeType.orEmpty()}")
                    channel.trySend(stream)
                } else if (!failed) {
                    noteAttempt("client=NEWPIPE outcome=empty")
                }
            },
        )
        val remaining = AtomicInteger(jobs.size)
        jobs.forEach { job ->
            job.invokeOnCompletion {
                if (remaining.decrementAndGet() == 0) channel.close()
            }
        }
        try {
            val winner = kotlinx.coroutines.withTimeoutOrNull(maxOf(NEWPIPE_FALLBACK_TIMEOUT_MS, innerTubeXBudgetMs)) {
                channel.receiveCatching().getOrNull()
            } ?: return@coroutineScope null
            cacheResolvedStream(winner, now, authScope, maxBitrateBps)
            lastResolvedStreams[resolutionKey(videoId, authScope, maxBitrateBps)] = winner
            val stage = if (winner.clientProfile == NEWPIPE_SOURCE) "newpipe" else "innertubex"
            logStreamEvent("resolved", winner)
            logStage(videoId, stage, startedAt)
            winner
        } finally {
            channel.close()
            jobs.forEach { it.cancel() }
        }
    }

    /**
     * One video-bound PoToken for the fast path, served from the minter's
     * cache whenever the pre-warmed engine is alive (milliseconds). Uses the
     * real visitor session supplied to the player request. Missing visitor
     * data skips minting. Anything slower — cold WebView boot, missing WebView, dead
     * challenge — returns null and the resolve proceeds without a token
     * instead of blocking audio on it.
     */
internal suspend fun InnerTubeMusicApi.fastPoToken(videoId: String, visitorData: String?): PoTokenResult? {
        if (videoId.isBlank()) return null
        // Tokens must be bound to the visitor actually sent in the player request.
        val session = visitorData?.takeIf { isValidVisitorData(it) }
            ?: return null
        return try {
            kotlinx.coroutines.withTimeoutOrNull(POTOKEN_FAST_BUDGET_MS) {
                BotGuardTokenGenerator.mintToken(videoId, session)
            }
        } catch (error: Throwable) {
            // A timed-out mint reports as cancellation while the parent is
            // still alive; only a genuinely cancelled parent rethrows.
            if (error is kotlinx.coroutines.CancellationException && !currentCoroutineContext().isActive) throw error
            null
        }
    }

internal fun InnerTubeMusicApi.playerRequestContext(client: PlayerClient, visitorData: String?): JsonObject = buildJsonObject {
        put("client", buildJsonObject {
            put("clientName", client.name)
            put("clientVersion", client.version)
            put("hl", "en")
            put("gl", "US")
            if (isValidVisitorData(visitorData)) put("visitorData", visitorData)
            if (!client.osName.isNullOrBlank()) put("osName", client.osName)
            if (!client.osVersion.isNullOrBlank()) put("osVersion", client.osVersion)
            if (!client.deviceMake.isNullOrBlank()) put("deviceMake", client.deviceMake)
            if (!client.deviceModel.isNullOrBlank()) put("deviceModel", client.deviceModel)
            if (!client.androidSdkVersion.isNullOrBlank()) {
                put("androidSdkVersion", client.androidSdkVersion)
            }
        })
        if (client.name == "WEB_REMIX" && ytAuth.connection.value.isConnected) {
            put("user", buildJsonObject {
                put("lockedSafetyMode", false)
                val delegate = ytAuth.connection.value.onBehalfOfUser
                if (!delegate.isNullOrBlank()) {
                    put("onBehalfOfUser", delegate)
                }
            })
        }
    }

internal suspend fun InnerTubeMusicApi.resolveDirectClientStream(
        videoId: String,
        client: PlayerClient,
        visitorData: String?,
        signatureTimestamp: Int?,
        cipherContext: PlaybackCipherContext?,
        playerPoToken: String?,
        gvsPoToken: String?,
        authScope: String,
        probeCandidates: Boolean = true,
        allowCipherFormats: Boolean = true,
        maxAttempts: Int = MAX_PLAYER_REQUEST_ATTEMPTS,
        maxBitrateBps: Int? = null,
    ): YouTubeAudioStream {
        val playerContext = playerRequestContext(client, visitorData)
        cipherContext?.requireMatches(videoId, ytAuth.connection.value)
        val body = playbackPlayerBody(videoId, playerContext, signatureTimestamp, playerPoToken)
        val playerApi = if (client.name == "WEB_REMIX") MUSIC_API else YOUTUBE_API
        val root = post(
            url = "$playerApi/player?key=${client.apiKey}&prettyPrint=false",
            body = body,
            clientName = client.name,
            clientVersion = client.version,
            userAgent = client.userAgent,
            authenticated = client.name == "WEB_REMIX" && ytAuth.connection.value.isConnected,
            origin = client.origin,
            referer = client.referer,
            visitorData = visitorData,
            maxAttempts = maxAttempts,
            // Whole-call cap (connect + read + body): the injected client's
            // 15s socket timeouts otherwise let one hung POST stall a hedged
            // racer for 30s+ across its two attempts.
            callTimeoutMs = PLAYER_REQUEST_CALL_TIMEOUT_MS,
        )
        noteAttempt("client=${client.name} response ${youtubePlayerResponseSummary(root)} " +
            "signedIn=${client.name == "WEB_REMIX" && ytAuth.connection.value.isConnected} " +
            "visitorPresent=${!visitorData.isNullOrBlank()} " +
            "playerTokenPresent=${!playerPoToken.isNullOrBlank()} " +
            "signatureTimestampPresent=${signatureTimestamp != null}")
        val status = root.obj("playabilityStatus")
        val state = status?.string("status")
        if (state != "OK") {
            val streaming = root.obj("streamingData")
            val hasFormats = streaming?.let {
                !it.array("formats").isNullOrEmpty() || !it.array("adaptiveFormats").isNullOrEmpty()
            } ?: false
            if (!hasFormats) {
                val reason = status?.string("reason").orEmpty()
                if (state == "UNPLAYABLE" && reason.isConfirmedUnavailableReason()) {
                    throw ConfirmedUnplayableMediaException(reason.ifBlank { state.orEmpty() })
                }
                throw IOException(reason.ifBlank { "Player status ${state ?: "missing"}" })
            }
        }

        val streaming = root.obj("streamingData")
        val streamRequestHeaders = buildMap {
            putAll(client.streamRequestHeaders)
            if (!client.userAgent.isNullOrBlank()) {
                put("User-Agent", client.userAgent)
            }
            put("Origin", "https://music.youtube.com")
            put("Referer", "https://music.youtube.com/")
            if (!visitorData.isNullOrBlank()) put("X-Goog-Visitor-Id", visitorData)
            if (client.name == "WEB_REMIX" && ytAuth.connection.value.isConnected) {
                ytAuth.cookieHeaderValue()?.let { put("Cookie", it) }
                ytAuth.authorizationHeaderValue()?.let { put("Authorization", it) }
            }
        }
        val responseExpiry = streaming?.string("expiresInSeconds")
            ?.toLongOrNull()
            ?.let { System.currentTimeMillis() + it.coerceAtLeast(1L) * 1_000L }
        val formats = buildList {
            streaming?.array("formats").orEmpty().forEach { add(it to false) }
            streaming?.array("adaptiveFormats").orEmpty().forEach { add(it to true) }
        }.filter { (element, _) ->
            maxBitrateBps == null || (element as? JsonObject)?.int("bitrate")?.let { it in 1..maxBitrateBps } == true
        }
        val audioCiphers = if (allowCipherFormats) formats.mapNotNull { (element, _) ->
            val format = element as? JsonObject ?: return@mapNotNull null
            if (format.string("mimeType")?.startsWith("audio/") != true) return@mapNotNull null
            format.string("signatureCipher") ?: format.string("cipher")
        }.distinct().take(8) else emptyList()
        val directAudioUrls = if (cipherContext != null) formats.mapNotNull { (element, _) ->
            val format = element as? JsonObject ?: return@mapNotNull null
            format.string("url")?.takeIf { format.string("mimeType")?.startsWith("audio/") == true }
        }.distinct().take(8) else emptyList()
        // A decoder from a separately fetched player can return a plausible but
        // invalid signature. Use the exact script paired with this request first.
        val deciphered = if (cipherContext != null) {
            innerTubeXExtractor.decipherAudioUrls(videoId, audioCiphers, cipherContext, directAudioUrls).toMutableMap()
        } else {
            audioCiphers.mapNotNull { cipher ->
                streamExtractor.decipherStreamUrl(videoId, cipher)?.let { cipher to it }
            }.toMap().toMutableMap().also { decoded ->
                val unresolved = audioCiphers.filterNot(decoded::containsKey)
                if (client.name == "WEB_REMIX" && unresolved.isNotEmpty()) {
                    decoded.putAll(innerTubeXExtractor.decipherAudioUrls(videoId, unresolved))
                }
            }
        }
        val candidates = formats.mapNotNull { (element, isAdaptive) ->
                val format = element as? JsonObject ?: return@mapNotNull null
                val url = format.string("url")?.let { direct ->
                    if (cipherContext != null) deciphered[direct] else direct
                } ?: if (allowCipherFormats) {
                        (format.string("signatureCipher") ?: format.string("cipher"))
                            ?.let(deciphered::get)
                    } else {
                        null
                    }
                    ?: return@mapNotNull null
                val mime = format.string("mimeType")
                if (mime?.startsWith("audio/") != true) return@mapNotNull null
                val finalUrl = appendPoToken(url, gvsPoToken)
                YouTubeAudioStream(
                    videoId = videoId,
                    url = finalUrl,
                    itag = format.int("itag"),
                    mimeType = mime.substringBefore(';'),
                    codec = extractCodec(mime),
                    bitrate = format.int("bitrate") ?: 0,
                    sampleRateHz = format.string("audioSampleRate")?.toIntOrNull(),
                    durationMs = format.string("approxDurationMs")?.toLongOrNull(),
                    contentLength = format.string("contentLength")?.toLongOrNull(),
                    isAdaptive = isAdaptive,
                    clientProfile = client.key,
                    authScope = authScope,
                    requestHeaders = streamRequestHeaders,
                    expiresAtEpochMs = listOfNotNull(streamExpiryEpochMs(finalUrl), responseExpiry).minOrNull()
                        ?: System.currentTimeMillis() + UNKNOWN_STREAM_EXPIRY_TTL_MS,
                    userAgent = client.userAgent,
                    isDrc = format.string("isDrc") == "true" || isDrcAudioUrl(finalUrl),
                )
            }
            .filter(::isCompatibleAudioCandidate)
            .filter { it.meetsBitrateLimit(maxBitrateBps) }
            .sortedWith(
                // DRC shares itag 251/140 with the full master, often at a higher
                // bitrate, so bitrate alone kept picking the quiet variant.
                compareBy<YouTubeAudioStream> { it.isDrc }
                    .thenBy { it.isAdaptive }
                    .thenByDescending { it.bitrate },
            )
        noteAttempt("client=${client.name} compatibleCandidates=${candidates.size} cipherAllowed=$allowCipherFormats")
        // Probe full playback ranges before choosing a format. Tiny probes can
        // succeed even when the media request itself will receive HTTP 403.
        if (!probeCandidates) {
            return candidates.firstOrNull()
                ?: throw IOException("${client.key} returned no usable audio URL")
        }
        for (candidate in candidates.take(MAX_FORMAT_PROBES_PER_CLIENT)) {
            if (probeStream(candidate, "client-probe")) return candidate
        }
        throw IOException("${client.key} returned no usable audio URL")
    }

internal fun InnerTubeMusicApi.cacheResolvedStream(
        stream: YouTubeAudioStream,
        cachedAt: Long,
        authScope: String,
        maxBitrateBps: Int?,
    ) {
        val lookupAuthScope = if (maxBitrateBps == null) stream.authScope else authScope
        streamCache[StreamCacheKey.from(stream, lookupAuthScope, maxBitrateBps)] = CachedStream(cachedAt, stream)
        pruneStreamCache()
    }

internal suspend fun InnerTubeMusicApi.probeStream(
        stream: YouTubeAudioStream,
        stage: String,
        retry: Int = 0,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (stream.expiresAtEpochMs != null && stream.expiresAtEpochMs - now <= URL_EXPIRY_MARGIN_MS) {
            logStreamEvent(stage, stream, retry = retry, detail = "expired=true")
            noteAttempt("probe stage=$stage client=${stream.clientProfile} itag=${stream.itag ?: -1} outcome=expired")
            return false
        }
        val isHls = stream.mimeType?.contains("mpegurl", true) == true ||
            stream.url.substringBefore('?').endsWith(".m3u8", true) ||
            stream.codec?.equals("hls", true) == true
        val request = if (isHls) {
            Request.Builder().url(stream.url)
                .header("Accept-Encoding", "identity")
                .apply { stream.requestHeaders.forEach { (name, value) -> header(name, value) } }
                .build()
        } else {
            playbackProbeRequest(stream.url, stream.requestHeaders)
        }
        val call = innerTubeClient.newCall(request)
        call.timeout().timeout(STREAM_PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        return try {
            val (status, contentType, valid) = call.readResponse { response ->
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                val validType = !contentType.contains("text/html") &&
                    !contentType.contains("application/json") &&
                    !contentType.contains("text/plain")
                val valid = (response.code == 200 || response.code == 206) &&
                    validType && response.body?.source()?.request(1L) == true
                Triple(response.code, contentType, valid)
            }
            logStreamEvent(
                stage = stage,
                stream = stream,
                httpStatus = status,
                retry = retry,
                detail = "valid=$valid type=${contentType.substringBefore(';').take(40)}",
            )
            noteAttempt(
                "probe stage=$stage client=${stream.clientProfile} itag=${stream.itag ?: -1} " +
                    "http=$status valid=$valid",
            )
            valid
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logStreamEvent(stage, stream, retry = retry, detail = "error=${error::class.java.simpleName}")
            noteAttempt(
                "probe stage=$stage client=${stream.clientProfile} itag=${stream.itag ?: -1} " +
                    resolutionFailureDetail(error),
            )
            false
        }
    }

internal fun YouTubeAudioStream.isFresh(cachedAt: Long, now: Long): Boolean =
        now - cachedAt < STREAM_TTL_MS &&
            (expiresAtEpochMs == null || expiresAtEpochMs - now > URL_EXPIRY_MARGIN_MS)

internal fun InnerTubeMusicApi.appendPoToken(url: String, token: String?): String {
        if (token.isNullOrBlank()) return url
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (parsed.queryParameter("pot") != null) return url
        val fragmentIndex = url.indexOf('#').takeIf { it >= 0 } ?: url.length
        val base = url.substring(0, fragmentIndex)
        val fragment = url.substring(fragmentIndex)
        val separator = when {
            base.endsWith('?') || base.endsWith('&') -> ""
            '?' in base -> "&"
            else -> "?"
        }
        return "$base${separator}${Uri.encode("pot")}=${Uri.encode(token)}$fragment"
    }

internal fun InnerTubeMusicApi.streamExpiryEpochMs(url: String): Long? = url.toHttpUrlOrNull()
        ?.queryParameter("expire")
        ?.toLongOrNull()
        ?.times(1_000L)

internal fun InnerTubeMusicApi.clientFailureKey(videoId: String, clientKey: String, authScope: String): String =
        "$videoId|$clientKey|$authScope"

internal fun InnerTubeMusicApi.resolutionKey(videoId: String, authScope: String, maxBitrateBps: Int? = null): String =
        if (maxBitrateBps == null) "$videoId|$authScope" else "$videoId|$authScope|$maxBitrateBps"

internal fun InnerTubeMusicApi.playbackAuthScope(): String {
        val connection = ytAuth.connection.value
        if (!connection.isConnected) return ANONYMOUS_AUTH_SCOPE
        val cookieDigest = ytAuth.cookieHeaderValue()
            ?.let { cookie ->
                MessageDigest.getInstance("SHA-256")
                    .digest(cookie.toByteArray())
                    .take(8)
                    .joinToString("") { byte ->
                        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                    }
            }
            ?: "none"
        return "account:${connection.connectedAtMillis}:$cookieDigest"
    }

internal fun InnerTubeMusicApi.extractCodec(mimeType: String): String? = CODEC_PATTERN
        .find(mimeType)
        ?.groupValues
        ?.getOrNull(1)
        ?.substringBefore(',')
        ?.trim()

internal fun InnerTubeMusicApi.isCompatibleAudioCandidate(stream: YouTubeAudioStream): Boolean {
        val mime = stream.mimeType.orEmpty().lowercase()
        val codec = stream.codec.orEmpty().lowercase()
        return when {
            mime == "audio/webm" -> codec.isBlank() || codec.contains("opus") || codec.contains("vorbis")
            mime == "audio/mp4" || mime == "audio/m4a" -> codec.isBlank() || codec.contains("mp4a") || codec.contains("aac")
            mime == "audio/ogg" -> codec.isBlank() || codec.contains("opus") || codec.contains("vorbis")
            mime == "audio/mpeg" -> true
            else -> false
        }
    }

internal fun InnerTubeMusicApi.logStreamEvent(
        stage: String,
        stream: YouTubeAudioStream,
        httpStatus: Int? = null,
        retry: Int = 0,
        detail: String? = null,
    ) {
        android.util.Log.i(STREAM_LOG_TAG, "[YOUTUBE] stage=$stage videoId=${stream.videoId} client=${stream.clientProfile} status=$httpStatus retry=$retry detail=$detail")
        val now = System.currentTimeMillis()
        val expiryState = when {
            stream.expiresAtEpochMs == null -> "unknown"
            stream.expiresAtEpochMs <= now -> "expired"
            else -> "fresh"
        }
        android.util.Log.d(
            STREAM_LOG_TAG,
            "stage=$stage videoId=${stream.videoId} client=${stream.clientProfile} " +
                "itag=${stream.itag ?: -1} mime=${stream.mimeType.orEmpty()} " +
                "expiry=$expiryState retry=$retry http=${httpStatus ?: 0} ${detail?.take(80).orEmpty()}",
        )
    }

internal suspend fun InnerTubeMusicApi.noteAttempt(entry: String) {
        currentCoroutineContext()[ResolutionAttemptLog]?.record(entry)
    }

internal suspend fun InnerTubeMusicApi.logResolveAttempts(videoId: String, failed: Boolean) {
        val summary = currentCoroutineContext()[ResolutionAttemptLog]?.summary() ?: "none"
        android.util.Log.i(
            STREAM_LOG_TAG,
            "[YOUTUBE] id=$videoId resolve=${if (failed) "failed" else "ok"} $summary",
        )
    }

internal fun InnerTubeMusicApi.logStage(videoId: String, stage: String, startedAt: Long? = null) {
        val elapsed = startedAt?.let { SystemClock.elapsedRealtime() - it }
        android.util.Log.i(STREAM_LOG_TAG, "[YOUTUBE] id=$videoId stage=$stage" + (elapsed?.let { " +${it}ms" } ?: ""))
    }

internal fun InnerTubeMusicApi.logClientFailure(videoId: String, client: String, error: Throwable?) {
        val status = generateSequence(error) { it.cause }
            .filterIsInstance<InnerTubeHttpException>()
            .firstOrNull()
            ?.responseCode ?: 0
        android.util.Log.d(
            STREAM_LOG_TAG,
            "stage=client-resolve-failed videoId=$videoId client=$client itag=-1 " +
                "mime=unknown expiry=unknown retry=0 http=$status error=${error?.javaClass?.simpleName.orEmpty()}",
        )
    }

internal fun InnerTubeMusicApi.playerClients(config: InnerTubeMusicApi.WebConfig): List<PlayerClient> = PLAYER_CLIENTS.map { client ->
        if (client.name == "WEB_REMIX") {
            client.copy(version = config.clientVersion, apiKey = config.apiKey)
        } else {
            client
        }
    }

internal fun Throwable.confirmedUnavailableReasonOrNull(): String? {
        val causes = generateSequence(this) { it.cause }.take(10).toList()
        causes.filterIsInstance<ConfirmedUnplayableMediaException>()
            .firstOrNull()
            ?.message
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        return causes.firstNotNullOfOrNull { cause ->
            cause.message?.takeIf { it.isConfirmedUnavailableReason() }
        }
    }

internal fun String.isConfirmedUnavailableReason(): Boolean {
        val reason = lowercase()
        return CONFIRMED_UNAVAILABLE_REASONS.any(reason::contains)
    }

    /** Keeps the stream cache from growing without bound over long sessions:
     *  drops expired entries first, then trims the oldest inserts. */
internal fun InnerTubeMusicApi.pruneStreamCache() {
        if (streamCache.size <= MAX_STREAM_CACHE_ENTRIES) return
        val now = System.currentTimeMillis()
        streamCache.entries.removeIf { entry ->
            !entry.value.stream.isFresh(entry.value.cachedAtEpochMs, now)
        }
        if (streamCache.size > MAX_STREAM_CACHE_ENTRIES) {
            streamCache.entries
                .sortedBy { it.value.cachedAtEpochMs }
                .take(streamCache.size - MAX_STREAM_CACHE_ENTRIES)
                .forEach { streamCache.remove(it.key, it.value) }
        }
    }

internal suspend fun InnerTubeMusicApi.findBestMatchImpl(
        title: String,
        artist: String,
        prefetchStreams: Boolean = false,
        excludedVideoId: String? = null,
        excludedVideoIds: Set<String> = emptySet(),
        /** Catalog length of the wanted recording; favours the same cut. */
        expectedDurationMs: Long? = null,
    ): YouTubeMusicTrack {
        val cleanArtist = artist.takeUnless { it.equals("Unknown artist", ignoreCase = true) }.orEmpty()
        // Same title and artist can be different recordings (album vs single
        // edit); keep the runtime in the key so one never answers for the other.
        val cacheKey = "${normalize(cleanArtist)}|${normalize(title)}|${expectedDurationMs?.div(1000L) ?: ""}"
        matchCache[cacheKey]?.takeIf { it.videoId != excludedVideoId && it.videoId !in excludedVideoIds }?.let { return it }
        val searchTitle = baseTitle(title).trim().ifBlank { title.trim() }
        val query = listOf(searchTitle, cleanArtist).filter { it.isNotBlank() }.joinToString(" ")
        val results = searchSongs(
            query = query,
            limit = 30,
            prefetchStreams = prefetchStreams,
        )
        val validCandidates = results.filter {
            it.videoId.isNotBlank() && it.videoId != excludedVideoId && it.videoId !in excludedVideoIds
        }
        val isCandidateMatch: (YouTubeMusicTrack) -> Boolean = { candidate ->
            // Hard vetoes first: a different take (live/remix/cover/sped up…)
            // or a runtime far from the catalog's is never the same recording,
            // however well the rest of the title reads. Scoring only demoted
            // these, so they still played whenever no clean upload ranked.
            TextMatch.isSameTake(candidate.title, title, cleanArtist, candidate.artist) &&
                !TextMatch.isSevereDurationMismatch(expectedDurationMs, candidate.durationSeconds) &&
            (maxOf(
                similarity(candidate.title, title),
                similarity(baseTitle(candidate.title), baseTitle(title)),
            ) >= 60 || TextMatch.isSafeTitleMatch(candidate.title, title, cleanArtist)) &&
                (cleanArtist.isBlank() ||
                    similarity(candidate.artist, cleanArtist) >= 35 ||
                    normalize(candidate.artist).contains(normalize(cleanArtist)) ||
                    normalize(cleanArtist).contains(normalize(candidate.artist)) ||
                    normalize(candidate.title).contains(normalize(cleanArtist)))
        }
        val best = validCandidates.asSequence()
            .filter(isCandidateMatch)
            .maxByOrNull { candidate -> TextMatch.matchScore(candidate, title, cleanArtist, expectedDurationMs) }
            ?: (if (searchTitle != title.trim()) {
                val fallbackResults = searchSongs(
                    query = listOf(title.trim(), cleanArtist).filter { it.isNotBlank() }.joinToString(" "),
                    limit = 30,
                    prefetchStreams = prefetchStreams,
                )
                fallbackResults.filter {
                    it.videoId.isNotBlank() && it.videoId != excludedVideoId && it.videoId !in excludedVideoIds
                }.asSequence()
                    .filter(isCandidateMatch)
                    .maxByOrNull { candidate -> TextMatch.matchScore(candidate, title, cleanArtist, expectedDurationMs) }
            } else null)
            ?: throw IOException("No reliable YouTube Music match found for $title by $artist")
        return best.also {
            if (matchCache.size > MAX_MATCH_CACHE_ENTRIES) matchCache.clear()
            matchCache[cacheKey] = it
        }
    }

internal suspend fun InnerTubeMusicApi.findBestMatchOrNullImpl(
        title: String,
        artist: String,
        prefetchStreams: Boolean = false,
    ): YouTubeMusicTrack? = try {
        findBestMatchImpl(title, artist, prefetchStreams)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    /**
     * SimpMusic / Metrolist / ArchiveTune style broad search for downloads.
     * Same scoring as playback but across multiple query variants
     * (title+artist, title-only, base/cleaned title) so a track that plays
     * still resolves for download even when the first strict query misses.
     * Never throws (except cancellation) — returns ranked candidates,
     * best first, possibly empty.
     */
internal suspend fun InnerTubeMusicApi.findDownloadCandidatesImpl(
        title: String,
        artist: String,
        excludedVideoIds: Set<String> = emptySet(),
        limit: Int = 6,
    ): List<YouTubeMusicTrack> = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext emptyList()
        val cleanArtist = artist.takeUnless {
            it.isBlank() || it.equals("Unknown artist", ignoreCase = true)
        }.orEmpty()
        val cleanTitle = baseTitle(title).trim().ifBlank { title.trim() }
        val queries = linkedSetOf(
            listOf(title.trim(), cleanArtist).filter { it.isNotBlank() }.joinToString(" "),
            title.trim(),
            listOf(cleanTitle, cleanArtist).filter { it.isNotBlank() }.joinToString(" "),
            cleanTitle,
        ).filter { it.isNotBlank() }.take(4)
        val merged = linkedMapOf<String, YouTubeMusicTrack>()
        for (query in queries) {
            try {
                val results = searchSongs(query = query, limit = 15, prefetchStreams = false)
                for (track in results) {
                    if (track.videoId.isBlank() || track.videoId in excludedVideoIds) continue
                    merged.putIfAbsent(track.videoId, track)
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Exception) {
            }
            // Enough to try: downloads resolve candidates serially, so a
            // huge list only burns the per-track budget. Ten ranked
            // candidates across the query variants is plenty.
            if (merged.size >= 10) break
        }
        if (merged.isEmpty()) return@withContext emptyList()
        val ranked = merged.values.sortedByDescending { matchScore(it, title, cleanArtist) }
        val plausible = ranked.filter { candidate ->
            TextMatch.isSafeTitleMatch(candidate.title, title, cleanArtist)
        }
        plausible.take(limit.coerceIn(1, 10))
    }

internal suspend fun InnerTubeMusicApi.isPlayableImpl(title: String, artist: String): Boolean =
        findBestMatchOrNullImpl(title, artist) != null

    /** Warms the cached web config (api key/version/visitor data) so the
     *  first playback's fast path can attach visitor data without an extra
     *  blocking fetch. Safe to call from a startup coroutine; never throws. */
internal suspend fun InnerTubeMusicApi.preWarmPlaybackImpl() {
        val config = runCatching { getWebConfig() }.getOrNull()
        val visitor = config?.visitorData?.takeIf { isValidVisitorData(it) } ?: FALLBACK_TOKEN_SESSION
        runCatching { BotGuardTokenGenerator.preWarm(visitor) }
    }

