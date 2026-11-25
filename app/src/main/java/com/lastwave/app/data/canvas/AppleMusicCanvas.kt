package com.lastwave.app.data.canvas

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.Date
import java.util.Locale

object AppleMusicCanvas {

    private const val LOG_TAG = "AppleMusicCanvas"
    private const val CATALOG_BASE = "https://amp-api.music.apple.com/v1/catalog"
    private const val PLAYER_ENTRY_URL = "https://music.apple.com/us/new"
    private const val SCORE_THRESHOLD = 12
    private const val RETRY_COOLDOWN_MS = 30L * 60 * 1000

    private val decoder = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val releaseTags = arrayOf(
        "deluxe", "expanded", "remastered", "remix", "version", "edit", "mix", "bonus",
    )

    private val compilationKeywords = arrayOf(
        "playlist", "set list", "essentials", "dj mix", "mixed",
        "apple music", "today's hits", "session",
    )

    private val bundleScriptRegex = Regex(
        """/assets/(?:index|app)(?:-legacy)?[~-][A-Za-z0-9_-]+\.js|/assets/index[~-][A-Za-z0-9_-]+\.js""",
    )
    private val bearerJwtRegex = Regex("""ey[A-Za-z0-9_-]+\.ey[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
    private val expFieldRegex = Regex("\"exp\"\\s*:\\s*(\\d+)")

    private data class VideoAssets(
        val primary: String,
        val alternate: String?,
        val tall: String?,
        val wide: String?,
    )

    val storefront: String by lazy {
        val region = Locale.getDefault().country
        if (region.length == 2) region.lowercase(Locale.ROOT) else "us"
    }

    @Volatile
    private var activeBearer: String? = null

    @Volatile
    private var bearerExpiryMs = 0L

    @Volatile
    private var nextAttemptAfterMs = 0L

    private val invalidatedTokens = HashSet<String>()

    fun search(client: OkHttpClient, title: String, artist: String, album: String?): CanvasArtwork? {
        val jwt = resolveToken(client) ?: return null
        val queryTerm = buildSongQuery(title, artist, album)
        val songHits = queryCatalog(client, jwt, queryTerm, "songs", limit = 10, include = "albums")
            ?: return null

        val candidates = ArrayList<Pair<Int, JsonObject>>()
        for (element in songHits) {
            val obj = element as? JsonObject ?: continue
            val points = scoreCandidate(obj, title, artist, album, treatAsAlbum = false) ?: continue
            candidates.add(points to obj)
        }
        candidates.sortByDescending { it.first }

        for ((points, songNode) in candidates) {
            if (points < SCORE_THRESHOLD) {
                Log.d(LOG_TAG, "No hit scored above $SCORE_THRESHOLD for '$title' (best was $points)")
                break
            }

            val attrs = songNode["attributes"]?.jsonObject ?: continue
            val trackName = attrs.stringOrNull("name")
            val artistName = attrs.stringOrNull("artistName")
            val albumName = attrs.stringOrNull("albumName")

            val inlineAssets = attrs["editorialVideo"]?.jsonObject?.let(::extractVideoAssets)
            if (inlineAssets != null) {
                Log.d(LOG_TAG, "Inline motion artwork found for '$trackName'")
                return buildArtwork(inlineAssets, inlineAssets.primary, trackName, artistName, albumName)
            }

            val albumId = extractAlbumId(songNode) ?: continue
            loadAlbumVideo(client, albumId, jwt, trackName, artistName)?.let { return it }
        }
        return null
    }

    fun searchAlbum(client: OkHttpClient, album: String, artist: String): CanvasArtwork? {
        val jwt = resolveToken(client) ?: return null
        val queryTerm = if (album.contains(artist, ignoreCase = true)) album else "$artist $album"
        val albumHits = queryCatalog(client, jwt, queryTerm, "albums", limit = 10) ?: return null

        val candidates = ArrayList<Pair<Int, JsonObject>>()
        for (element in albumHits) {
            val obj = element as? JsonObject ?: continue
            val points = scoreCandidate(obj, album, artist, album, treatAsAlbum = true) ?: continue
            candidates.add(points to obj)
        }
        candidates.sortByDescending { it.first }

        for ((points, albumNode) in candidates) {
            if (points < SCORE_THRESHOLD) break

            val attrs = albumNode["attributes"]?.jsonObject ?: continue
            val albumTitle = attrs.stringOrNull("name")
            if (albumTitle != null && looksLikeCompilation(albumTitle)) continue

            val assets = attrs["editorialVideo"]?.jsonObject?.let(::extractVideoAssets) ?: continue
            Log.d(LOG_TAG, "Motion artwork found for album '$albumTitle'")
            return buildArtwork(
                assets = assets,
                chosenUrl = assets.primary,
                title = albumTitle,
                artist = attrs.stringOrNull("artistName"),
                album = albumTitle,
            )
        }
        return null
    }

    fun searchArtist(client: OkHttpClient, artist: String): CanvasArtwork? {
        val jwt = resolveToken(client) ?: return null
        val cleanedArtist = artist.trim()
        if (cleanedArtist.isEmpty()) return null

        val artistHits = queryCatalog(client, jwt, cleanedArtist, "artists", limit = 5) ?: return null
        val wantedNorm = artist.normalizeForMatch()

        for (element in artistHits) {
            val artistNode = element as? JsonObject ?: continue
            val attrs = artistNode["attributes"]?.jsonObject ?: continue
            val name = attrs.stringOrNull("name") ?: continue
            val hitNorm = name.normalizeForMatch()
            val matches = hitNorm == wantedNorm || hitNorm.contains(wantedNorm) || wantedNorm.contains(hitNorm)
            if (!matches) continue

            val directAssets = attrs["editorialVideo"]?.jsonObject?.let(::extractVideoAssets)
            if (directAssets != null) {
                Log.d(LOG_TAG, "Motion artwork found for artist '$name'")
                val chosen = directAssets.wide ?: directAssets.tall ?: directAssets.primary
                return buildArtwork(directAssets, chosen, title = name, artist = name, album = null)
            }

            val artistId = artistNode.stringOrNull("id") ?: continue
            loadArtistVideo(client, artistId, jwt, name)?.let { return it }
        }
        return null
    }

    private fun buildSongQuery(title: String, artist: String, album: String?): String = buildString {
        if (!title.contains(artist, ignoreCase = true)) {
            append(artist).append(' ')
        }
        append(title)
        if (!album.isNullOrBlank() && !title.contains(album, ignoreCase = true)) {
            append(' ').append(album)
        }
    }

    private fun queryCatalog(
        client: OkHttpClient,
        jwt: String,
        term: String,
        type: String,
        limit: Int,
        include: String? = null,
    ): JsonArray? {
        val urlBuilder = "$CATALOG_BASE/$storefront/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("types", type)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("extend", "editorialVideo")
        if (include != null) {
            urlBuilder.addQueryParameter("include", include)
        }

        val payload = authorizedRequest(client, urlBuilder.build().toString(), jwt) ?: return null
        val root = parseJsonObject(payload) ?: return null
        return root["results"]?.jsonObject?.get(type)?.jsonObject?.get("data")?.jsonArray
    }

    private fun loadArtistVideo(
        client: OkHttpClient,
        artistId: String,
        jwt: String,
        artistName: String,
    ): CanvasArtwork? {
        val endpoint = "$CATALOG_BASE/$storefront/artists/$artistId".toHttpUrl().newBuilder()
            .addQueryParameter("include", "albums")
            .addQueryParameter("extend", "editorialVideo")
            .build()
            .toString()

        val payload = authorizedRequest(client, endpoint, jwt) ?: return null
        val artistNode = parseJsonObject(payload)
            ?.get("data")?.jsonArray?.firstOrNull()?.jsonObject ?: return null

        val directAssets = artistNode["attributes"]?.jsonObject
            ?.get("editorialVideo")?.jsonObject
            ?.let(::extractVideoAssets)
        if (directAssets != null) {
            Log.d(LOG_TAG, "Motion artwork found on artist detail '$artistName' ($artistId)")
            val chosen = directAssets.wide ?: directAssets.tall ?: directAssets.primary
            return buildArtwork(directAssets, chosen, title = artistName, artist = artistName, album = null)
        }

        val albumList = artistNode["relationships"]?.jsonObject
            ?.get("albums")?.jsonObject
            ?.get("data")?.jsonArray ?: return null

        for (element in albumList) {
            val attrs = (element as? JsonObject)?.get("attributes")?.jsonObject ?: continue
            val albumTitle = attrs.stringOrNull("name").orEmpty()
            if (looksLikeCompilation(albumTitle)) continue

            val albumAssets = attrs["editorialVideo"]?.jsonObject?.let(::extractVideoAssets) ?: continue
            Log.d(LOG_TAG, "Motion artwork found on album '$albumTitle' for artist '$artistName'")
            val chosen = albumAssets.wide ?: albumAssets.tall ?: albumAssets.primary
            return buildArtwork(albumAssets, chosen, title = artistName, artist = artistName, album = albumTitle)
        }
        return null
    }

    private fun loadAlbumVideo(
        client: OkHttpClient,
        albumId: String,
        jwt: String,
        songTitle: String?,
        songArtist: String?,
    ): CanvasArtwork? {
        val endpoint = "$CATALOG_BASE/$storefront/albums/$albumId".toHttpUrl().newBuilder()
            .addQueryParameter("extend", "editorialVideo")
            .build()
            .toString()

        val payload = authorizedRequest(client, endpoint, jwt) ?: return null
        val albumNode = parseJsonObject(payload)
            ?.get("data")?.jsonArray?.firstOrNull()?.jsonObject ?: return null

        val attrs = albumNode["attributes"]?.jsonObject ?: return null
        val albumTitle = attrs.stringOrNull("name").orEmpty()
        if (looksLikeCompilation(albumTitle)) return null

        val assets = attrs["editorialVideo"]?.jsonObject?.let(::extractVideoAssets) ?: return null
        Log.d(LOG_TAG, "Motion artwork found on album '$albumTitle' ($albumId)")
        return buildArtwork(
            assets = assets,
            chosenUrl = assets.primary,
            title = songTitle,
            artist = songArtist ?: attrs.stringOrNull("artistName"),
            album = albumTitle,
        )
    }

    private fun scoreCandidate(
        node: JsonObject,
        wantedTitle: String,
        wantedArtist: String,
        wantedAlbum: String?,
        treatAsAlbum: Boolean,
    ): Int? {
        val attrs = node["attributes"]?.jsonObject ?: return null
        val hitTitle = attrs.stringOrNull("name").orEmpty()
        val hitArtist = attrs.stringOrNull("artistName").orEmpty()
        val hitAlbum = if (treatAsAlbum) hitTitle else attrs.stringOrNull("albumName").orEmpty()

        if (looksLikeCompilation(hitTitle) || looksLikeCompilation(hitAlbum)) return null

        val wantedArtists = splitArtists(wantedArtist)
        val creditedArtists = splitArtists(hitArtist)
        if (wantedArtists.isEmpty() || creditedArtists.isEmpty()) return null
        val allArtistsMatched = wantedArtists.all { expected ->
            creditedArtists.any { candidate ->
                candidate == expected || candidate.contains(expected) || expected.contains(candidate)
            }
        }
        if (!allArtistsMatched) return null

        var points = 10

        val normWantedTitle = wantedTitle.normalizeForMatch()
        val normHitTitle = hitTitle.normalizeForMatch()
        points += when {
            normHitTitle == normWantedTitle -> 15
            normHitTitle.contains(normWantedTitle) || normWantedTitle.contains(normHitTitle) -> 7
            else -> -10
        }

        if (!wantedAlbum.isNullOrBlank() && hitAlbum.isNotBlank()) {
            val normWantedAlbum = wantedAlbum.normalizeForMatch()
            val normHitAlbum = hitAlbum.normalizeForMatch()
            points += when {
                normHitAlbum == normWantedAlbum -> 20
                normHitAlbum.contains(normWantedAlbum) || normWantedAlbum.contains(normHitAlbum) -> 10
                else -> 0
            }
        }

        for (tag in releaseTags) {
            val expectedHas = wantedTitle.contains(tag, ignoreCase = true)
            val hitHas = hitTitle.contains(tag, ignoreCase = true)
            if (expectedHas && hitHas) {
                points += 5
            } else if (hitHas) {
                points -= 3
            }
        }
        return points
    }

    private fun looksLikeCompilation(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return compilationKeywords.any { marker -> lower.contains(marker) }
    }

    private fun extractAlbumId(songNode: JsonObject): String? {
        val relId = songNode["relationships"]?.jsonObject
            ?.get("albums")?.jsonObject
            ?.get("data")?.jsonArray?.firstOrNull()
            ?.jsonObject?.stringOrNull("id")
        if (relId != null) return relId.takeUnless { it.startsWith("pl.") }

        val webUrl = songNode["attributes"]?.jsonObject?.stringOrNull("url") ?: return null
        val slug = webUrl.substringAfter("/album/", "").substringBefore("?").substringAfterLast("/")
        return slug.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    private fun extractVideoAssets(videoNode: JsonObject): VideoAssets? {
        fun pickUrl(field: String): String? {
            val asset = videoNode[field]?.jsonObject ?: return null
            val raw = asset.stringOrNull("video")
                ?: asset.stringOrNull("videoUrl")
                ?: asset.stringOrNull("hlsUrl")
                ?: asset.stringOrNull("url")
            return raw?.takeIf { it.isNotBlank() }
        }

        val square = pickUrl("motionDetailSquare")
            ?: pickUrl("motionSquareVideo1x1")
            ?: pickUrl("motionArtistSquare1x1")
            ?: pickUrl("motionArtistSquare")
        val raw = pickUrl("motionDetailRaw")
        val tall = pickUrl("motionDetailTall")
            ?: pickUrl("motionTallVideo3x4")
            ?: pickUrl("motionArtistFullscreen16x9")
            ?: pickUrl("motionArtistTall")
            ?: pickUrl("motionArtistFullscreen")
        val wide = pickUrl("motionWideVideo")
            ?: pickUrl("motionWideVideo16x9")
            ?: pickUrl("motionDetailWide")
            ?: pickUrl("motionArtistWide16x9")
            ?: pickUrl("motionArtistWide")

        val anyAvailable = videoNode.keys.firstNotNullOfOrNull(::pickUrl)
        val primary = square ?: raw ?: tall ?: wide ?: anyAvailable ?: return null
        val alternate = listOfNotNull(square, raw, tall, wide).firstOrNull { it != primary }
        return VideoAssets(primary, alternate, tall, wide)
    }

    private fun buildArtwork(
        assets: VideoAssets,
        chosenUrl: String,
        title: String?,
        artist: String?,
        album: String?,
    ): CanvasArtwork = CanvasArtwork(
        url = chosenUrl,
        fallbackUrl = assets.alternate,
        tallUrl = assets.tall,
        wideUrl = assets.wide,
        title = title,
        artist = artist,
        album = album,
        source = CanvasSource.APPLE,
    )

    @Synchronized
    private fun resolveToken(client: OkHttpClient): String? {
        val now = System.currentTimeMillis()
        activeBearer?.let { current ->
            if (now < bearerExpiryMs - 60_000L) return current
        }
        if (now < nextAttemptAfterMs) return null

        val pageHtml = canvasGet(client, PLAYER_ENTRY_URL, mapOf("User-Agent" to CANVAS_UA))
            ?: canvasGet(client, "https://music.apple.com/us/browse", mapOf("User-Agent" to CANVAS_UA))
        val scriptPaths = pageHtml?.let { html ->
            bundleScriptRegex.findAll(html).map(MatchResult::value).distinct().toList()
        }.orEmpty()

        for (scriptPath in scriptPaths) {
            val jsSource = canvasGet(client, "https://music.apple.com$scriptPath", mapOf("User-Agent" to CANVAS_UA))
                ?: continue
            val validTokens = bearerJwtRegex.findAll(jsSource)
                .map(MatchResult::value)
                .distinct()
                .filter { it !in invalidatedTokens }
                .mapNotNull { jwt -> parseExpiryMillis(jwt)?.let { exp -> jwt to exp } }
                .filter { (_, exp) -> exp > now }
                .toList()
            if (validTokens.isEmpty()) continue

            val (chosenJwt, expiresAt) = validTokens.firstOrNull { (jwt, _) -> isWebPlayerJwt(jwt) }
                ?: validTokens[0]
            activeBearer = chosenJwt
            bearerExpiryMs = expiresAt
            Log.d(LOG_TAG, "Apple Music web player token good until ${Date(expiresAt)}")
            return chosenJwt
        }

        Log.w(LOG_TAG, "No usable web player token in ${scriptPaths.size} bundle(s); backing off")
        nextAttemptAfterMs = now + RETRY_COOLDOWN_MS
        return null
    }

    private fun authorizedRequest(client: OkHttpClient, url: String, jwt: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $jwt")
            .header("Origin", "https://music.apple.com")
            .header("Referer", "https://music.apple.com/")
            .header("User-Agent", CANVAS_UA)
            .build()

        return try {
            client.newCall(req).execute().use { res ->
                when {
                    res.isSuccessful -> res.body?.string()
                    res.code == 401 -> {
                        Log.w(LOG_TAG, "Apple Music token rejected (401); will re-scrape")
                        synchronized(this) {
                            invalidatedTokens.add(jwt)
                            if (activeBearer == jwt) {
                                activeBearer = null
                                bearerExpiryMs = 0L
                            }
                        }
                        null
                    }
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isWebPlayerJwt(jwt: String): Boolean = try {
        val segments = jwt.split('.')
        val header = String(Base64.getUrlDecoder().decode(segments[0]), Charsets.UTF_8)
        val payload = String(Base64.getUrlDecoder().decode(segments[1]), Charsets.UTF_8)
        header.contains("WebPlayKid") || payload.contains("AMPWebPlay")
    } catch (_: Exception) {
        false
    }

    private fun parseExpiryMillis(jwt: String): Long? = try {
        val payloadSegment = jwt.split('.')[1]
        val payloadJson = String(Base64.getUrlDecoder().decode(payloadSegment), Charsets.UTF_8)
        expFieldRegex.find(payloadJson)?.groupValues?.get(1)?.toLong()?.times(1000L)
    } catch (_: Exception) {
        null
    }

    private fun parseJsonObject(raw: String): JsonObject? = try {
        decoder.parseToJsonElement(raw).jsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull
}
