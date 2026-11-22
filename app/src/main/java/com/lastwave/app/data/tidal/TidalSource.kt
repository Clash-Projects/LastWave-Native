package com.lastwave.app.data.tidal

import android.util.Base64
import com.lastwave.app.data.addon.AddonStream
import com.lastwave.app.data.addon.AddonTrack
import com.lastwave.app.data.addon.LosslessSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lossless backend backed by the user's own TIDAL subscription: searches the
 * TIDAL catalog and asks `playbackinfopostpaywall` for the stream, so the
 * resolver can play a YouTube Music queue with TIDAL audio.
 */
@Singleton
class TidalSource @Inject constructor(
    private val account: TidalAccount,
) : LosslessSource {

    override suspend fun search(
        query: String,
        quality: String,
        atmos: Boolean,
        warmTarget: String?,
    ): Result<List<AddonTrack>> = runCatchingIo {
        val q = query.trim()
        if (q.isBlank()) return@runCatchingIo emptyList<AddonTrack>()
        // The player warms the next songs' searches ahead of time; serving the
        // real resolve from here saves a full round trip at the song change.
        synchronized(searchCache) { searchCache[q] }
            ?.takeIf { System.currentTimeMillis() - it.first < SEARCH_CACHE_MS }
            ?.let { return@runCatchingIo it.second }
        val items = account.get("search/tracks", mapOf("query" to q, "limit" to "25")).optJSONArray("items")
            ?: JSONArray()
        val tracks = (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.toAddonTrack() }
        synchronized(searchCache) {
            searchCache[q] = System.currentTimeMillis() to tracks
            tracks.forEach { knownQuality[it.id] = it.audioQuality }
        }
        tracks
    }

    /** Search answers by query, bounded LRU. */
    private val searchCache = object : LinkedHashMap<String, Pair<Long, List<AddonTrack>>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<AddonTrack>>>?) = size > 128
    }

    /** Catalog quality per track id from search ("HI_RES_LOSSLESS", "LOSSLESS"…). */
    private val knownQuality = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 2048
    }

    override suspend fun stream(
        trackId: String,
        quality: String,
        atmos: Boolean,
        isDownload: Boolean,
    ): Result<AddonStream> = runCatchingIo {
        // The PKCE client gets stereo only; Dolby Atmos needs another client
        // id, so let the resolver's waterfall fall through to stereo tiers.
        val audioQuality = when (quality) {
            "hi_res" -> "HI_RES_LOSSLESS"
            "lossless" -> "LOSSLESS"
            "high" -> "HIGH"
            "low" -> "LOW"
            else -> throw UnsupportedOperationException("TIDAL account: tier '$quality' not supported")
        }
        // TIDAL answers a hi-res request for a CD-only master with 16-bit, which
        // the resolver rejects and retries: skip that round trip when search
        // already said the master is not hi-res.
        if (audioQuality == "HI_RES_LOSSLESS") {
            val catalog = synchronized(searchCache) { knownQuality[trackId.trim()] }
            if (catalog != null && !catalog.contains("HI_RES")) error("TIDAL track $trackId has no hi-res master ($catalog)")
        }
        val info = account.get(
            "tracks/${trackId.trim()}/playbackinfopostpaywall",
            mapOf(
                "audioquality" to audioQuality,
                "playbackmode" to "STREAM",
                "assetpresentation" to "FULL",
            ),
        )
        if (info.optString("assetPresentation") == "PREVIEW") {
            error("TIDAL returned a 30s preview (subscription inactive?)")
        }
        val manifest = String(Base64.decode(info.getString("manifest"), Base64.DEFAULT), Charsets.UTF_8)
        val mime = info.optString("manifestMimeType")
        val bitDepth = info.optInt("bitDepth", 0).takeIf { it > 0 }
        val sampleRate = info.optInt("sampleRate", 0).takeIf { it > 0 }
        val streamQuality = info.optString("audioQuality")
        val base = AddonStream(
            quality = streamQuality,
            audioQuality = streamQuality,
            audioMode = info.optString("audioMode").ifBlank { null },
            rawBitDepth = bitDepth?.let { JsonPrimitive(it) },
            rawSampleRate = sampleRate?.let { JsonPrimitive(it) },
        )
        when {
            mime.contains("vnd.tidal.bts") -> {
                val bts = JSONObject(manifest)
                val encryption = bts.optString("encryptionType", "NONE")
                if (encryption != "NONE") error("TIDAL stream is encrypted ($encryption)")
                val url = bts.optJSONArray("urls")?.optString(0).orEmpty()
                if (url.isBlank()) error("TIDAL manifest has no URL")
                val codec = bts.optString("codecs").ifBlank { bts.optString("mimeType").substringAfter('/') }
                base.copy(url = url, codec = codec, format = codec, manifest = "bts")
            }
            mime.contains("dash+xml") -> {
                if (manifest.contains("ContentProtection", ignoreCase = true)) {
                    error("TIDAL DASH stream is DRM protected")
                }
                val codec = CODECS.find(manifest)?.groupValues?.get(1) ?: "flac"
                base.copy(manifestXml = manifest, codec = codec, format = "dash", manifest = "dash")
            }
            else -> error("Unknown TIDAL manifest type '$mime'")
        }
    }

    /**
     * TIDAL's track radio for the song best matching [title]/[artist]: the
     * seed is the first search hit sharing an artist name with the request.
     */
    suspend fun radioFor(title: String, artist: String, limit: Int): List<AddonTrack> {
        val hits = search("$title $artist", "lossless").getOrNull().orEmpty()
        val wanted = artistTokens(artist)
        val seed = hits.firstOrNull { hit -> artistTokens(hit.artist).any(wanted::contains) } ?: return emptyList()
        val items = account.get("tracks/${seed.id}/radio", mapOf("limit" to limit.coerceIn(1, 100).toString()))
            .optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.toAddonTrack() }
            .filter { it.id != seed.id }
    }

    private fun artistTokens(raw: String): Set<String> =
        raw.lowercase().split(',', '&', '/').map { it.replace(Regex("""\b(feat|ft|featuring)\b.*"""), "").trim() }
            .filter { it.isNotBlank() }.toSet()

    private fun JSONObject.toAddonTrack(): AddonTrack? {
        val id = optLong("id", 0L).takeIf { it > 0 } ?: return null
        if (!optBoolean("allowStreaming", true) || !optBoolean("streamReady", true)) return null
        val baseTitle = optString("title")
        if (baseTitle.isBlank()) return null
        // TIDAL keeps "Remastered 2011" / "Live" in `version`; the matcher
        // needs it in the title to tell versions apart like YouTube does.
        val version = optString("version").takeIf { it.isNotBlank() && it != "null" }
        val title = if (version != null && !baseTitle.contains(version, ignoreCase = true)) "$baseTitle ($version)" else baseTitle
        val artists = optJSONArray("artists")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
        }.orEmpty().ifEmpty { listOfNotNull(optJSONObject("artist")?.optString("name")) }
        val tags = optJSONObject("mediaMetadata")?.optJSONArray("tags")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        }.orEmpty()
        val audioModes = optJSONArray("audioModes")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
        val quality = if ("HIRES_LOSSLESS" in tags) "HI_RES_LOSSLESS" else optString("audioQuality")
        return AddonTrack(
            id = id.toString(),
            title = title,
            artist = artists.joinToString(", "),
            album = optJSONObject("album")?.optString("title").orEmpty(),
            duration = optDouble("duration", 0.0),
            audioQuality = quality,
            audioModes = audioModes,
        )
    }

    private suspend fun <T> runCatchingIo(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    companion object {
        /** Base URL the resolver uses to address this source among addon backends. */
        const val BASE_URL = "tidal-account://me"
        private const val SEARCH_CACHE_MS = 10 * 60_000L
        private val CODECS = Regex("""codecs="([^"]+)"""")
    }
}
