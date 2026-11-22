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
        val items = account.get("search/tracks", mapOf("query" to q, "limit" to "25")).optJSONArray("items")
            ?: JSONArray()
        (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.toAddonTrack() }
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
        private val CODECS = Regex("""codecs="([^"]+)"""")
    }
}
