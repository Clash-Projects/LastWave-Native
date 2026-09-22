package com.lastwave.app.data.lossless

import android.util.Log
import com.lastwave.app.data.artwork.awaitSuccessfulBodyOrNull
import com.lastwave.app.data.plugin.ModuleManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class LosslessAudioStream(
    val url: String,
    val mimeType: String = "application/dash+xml",
    val bitDepth: Int = 16,
    val samplingRate: Double = 44.1,
    val formatId: Int = 6,
    val bitrateKbps: Int? = null,
    val trackId: Long = 0,
    val durationSeconds: Int = 0,
    val audioCodecOverride: String? = null,
)

data class BackendCredentials(
    val baseUrl: String = "",
    val apiKey: String = "",
)

/** URI or inline MPD extracted from `/trackManifests`. */
data class AtmosManifestRef(
    val mpdUri: String? = null,
    val mpdXml: String? = null,
    val mpdBase64: String? = null,
)

private data class TidalCandidateItem(
    val id: Long,
    val title: String,
    val duration: Int = 0,
    val performerName: String = "",
    val albumArtistName: String = "",
    val albumTitle: String = "",
    val performers: String = "",
    val isAtmos: Boolean = false,
    val isSpatial: Boolean = false,
)

@Singleton
class LosslessMusicApi @Inject constructor(
    okHttpClient: OkHttpClient,
    private val moduleManager: ModuleManager,
    private val nativeSecrets: NativeSecrets,
) {
    private val client = okHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val resolutionClient = client.newBuilder()
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedCredentials: BackendCredentials? = null
    @Volatile
    private var consecutiveFailures = 0
    @Volatile
    private var failureCooldownUntilMs = 0L

    val isConfigured: Boolean
        get() {
            if (System.currentTimeMillis() < failureCooldownUntilMs) return false
            val cached = cachedCredentials
            if (cached != null) {
                return cached.baseUrl.isNotBlank() && cached.apiKey.isNotBlank()
            }
            val nativeCreds = runCatching { nativeSecrets.credentials() }.getOrNull()
            if (nativeCreds != null && nativeCreds.baseUrl.isNotBlank() && nativeCreds.apiKey.isNotBlank()) {
                cachedCredentials = nativeCreds
                return true
            }
            return false
        }

    /**
     * True only while a recent backend failure is backing off. Unlike
     * [isConfigured] (false on cold start before JNI loads — gating on it
     * killed lossless entirely, see d625587), this is safe to skip on:
     * the backend just failed, so attempting would only burn the resolve
     * timeout before falling back to YouTube anyway.
     */
    val isCoolingDown: Boolean
        get() = System.currentTimeMillis() < failureCooldownUntilMs

    companion object {
        // Quality presets
        const val QUALITY_DOLBY_ATMOS = 28 // Dolby Atmos Spatial Audio
        const val QUALITY_MAX_HI_RES = 27 // Up to 24-bit / 192 kHz
        const val QUALITY_HI_RES_96 = 7   // Up to 24-bit / 96 kHz
        const val QUALITY_CD_LOSSLESS = 6 // 16-bit / 44.1 kHz FLAC
        const val QUALITY_MP3_320 = 5     // 320 kbps MP3 / AAC High
        const val QUALITY_DATA_SAVER = 4  // 96 kbps HE-AAC Data Saver
        const val QUALITY_YOUTUBE = -1    // YouTube Music standard stream

        fun getQualityAttemptOrder(preferred: Int): List<Int> {
            if (preferred == QUALITY_YOUTUBE) return emptyList()
            val tiersAscending = listOf(
                QUALITY_DATA_SAVER,
                QUALITY_MP3_320,
                QUALITY_CD_LOSSLESS,
                QUALITY_HI_RES_96,
                QUALITY_MAX_HI_RES,
            )
            val index = tiersAscending.indexOf(preferred)
            if (index == -1) return listOf(QUALITY_MAX_HI_RES, QUALITY_HI_RES_96, QUALITY_CD_LOSSLESS, QUALITY_MP3_320, QUALITY_DATA_SAVER)

            val preferredQuality = tiersAscending[index]
            val above = tiersAscending.subList(index + 1, tiersAscending.size)
            val below = tiersAscending.subList(0, index).reversed()

            return (listOf(preferredQuality) + above + below).distinct()
        }

        private val MANIFEST_CODECS = Regex("""codecs="([^"]+)"""")

        /**
         * True when DASH manifest XML carries E-AC-3 / Dolby Atmos (or JOC).
         * Tidal's atmos endpoint answers stereo-only tracks with FLAC/AAC, so
         * those stay false. Channel-count is not required: Atmos JOC often
         * declares a Dolby hex mask (`F801`) or 16ch, not `value="6"`.
         */
        fun isAtmosManifest(mpdXml: String): Boolean {
            if (mpdXml.isBlank()) return false
            val lower = mpdXml.lowercase()
            return lower.contains("ec-3") || lower.contains("eac3") || lower.contains("ec3") ||
                lower.contains("atmos") || lower.contains("joc")
        }

        fun isSpatialManifest(mpdXml: String): Boolean {
            if (mpdXml.isBlank()) return false
            val lower = mpdXml.lowercase()
            return lower.contains("mha1") || lower.contains("mhm1") || lower.contains("mpeg-h") ||
                lower.contains("360ra") || lower.contains("sony_360") || lower.contains("spatial")
        }

        /** First `codecs=` value inside a base64 DASH data URL, or null when unreadable. */
        fun manifestCodecOf(dataUrl: String): String? {
            val b64 = dataUrl.substringAfter("base64,", "").trim()
            if (b64.isEmpty()) return null
            return runCatching {
                val xml = String(
                    android.util.Base64.decode(b64, android.util.Base64.DEFAULT),
                    Charsets.UTF_8,
                ).lowercase()
                MANIFEST_CODECS.find(xml)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        /** True for E-AC-3 spatial codec labels. Pure; safe to unit-test on JVM. */
        fun isAtmosCodec(codec: String?): Boolean {
            val c = codec?.trim()?.lowercase().orEmpty()
            return c.startsWith("ec-3") || c.startsWith("eac3") || c.startsWith("ac-3")
        }

        /** True when the stream bytes are E-AC-3 spatial. Fail-open (false) when unreadable. */
        fun isAtmosStreamUrl(url: String): Boolean {
            if (!url.startsWith("data:application/dash+xml")) return false
            return isAtmosCodec(manifestCodecOf(url))
        }

        /**
         * Pull an MPD URI or inline XML/base64 out of the many JSON shapes the
         * backend has used for `/trackManifests/?atmos=true`.
         */
        fun extractAtmosManifestRef(json: JSONObject): AtmosManifestRef? {
            fun fromObject(obj: JSONObject?): AtmosManifestRef? {
                if (obj == null) return null
                sequenceOf("uri", "url", "manifestUrl", "mpdUrl").forEach { key ->
                    val value = obj.optString(key)?.takeIf { it.isNotBlank() } ?: return@forEach
                    if (value.startsWith("http", ignoreCase = true)) {
                        return AtmosManifestRef(mpdUri = value)
                    }
                }
                val manifest = obj.optString("manifest")
                if (manifest.isNotBlank()) {
                    val trimmed = manifest.trimStart()
                    return when {
                        trimmed.startsWith("<") -> AtmosManifestRef(mpdXml = manifest)
                        trimmed.startsWith("http", ignoreCase = true) -> AtmosManifestRef(mpdUri = manifest)
                        else -> AtmosManifestRef(mpdBase64 = manifest)
                    }
                }
                return null
            }
            fun walk(obj: JSONObject?, depth: Int): AtmosManifestRef? {
                if (obj == null || depth > 6) return null
                fromObject(obj)?.let { return it }
                obj.optJSONObject("attributes")?.let { walk(it, depth + 1) }?.let { return it }
                obj.optJSONObject("data")?.let { walk(it, depth + 1) }?.let { return it }
                return null
            }
            return walk(json, 0)
        }

        fun parseSpatialFlags(item: JSONObject): Pair<Boolean, Boolean> {
            var atmos = false
            var spatial = false
            fun consider(raw: String?) {
                val m = raw?.uppercase().orEmpty()
                if (m.isBlank()) return
                if (m.contains("DOLBY") || m.contains("ATMOS")) atmos = true
                if (m.contains("360") || m.contains("SONY") || (m.contains("SPATIAL") && !m.contains("ATMOS"))) {
                    spatial = true
                }
            }
            val modes = item.optJSONArray("audioModes")
            val modeCount = modes?.length() ?: 0
            for (i in 0 until modeCount) consider(modes?.optString(i))
            val tags = item.optJSONObject("mediaMetadata")?.optJSONArray("tags")
                ?: item.optJSONArray("mediaMetadataTags")
            val tagCount = tags?.length() ?: 0
            for (i in 0 until tagCount) consider(tags?.optString(i))
            consider(item.optString("audioQuality"))
            return atmos to spatial
        }

        private const val TAG = "LosslessMusicApi"
        private const val MAX_DURATION_DIFFERENCE_SECONDS = 8
        private val DIACRITICS = Regex("\\p{M}+")
        private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
        private val MULTI_SPACE = Regex("\\s+")
        private val TOPIC_CHANNEL_SUFFIX = Regex("""(?i)\s*[-–—]\s*topic\s*$|\s+topic\s*$""")
        private val PIPE_NOISE = Regex("""\s*\|.*$""")
        private val SOUNDTRACK_SUFFIX = Regex(
            """(?i)\s*[\[(]\s*from\s+(?:the\s+(?:original\s+)?(?:motion\s+picture|movie|film|soundtrack)\s+)?["“][^"”\r\n]+["”]\s*[\])]\s*$""",
        )
        private val FEATURING_CLAUSE = Regex("""(?i)(?:\s*[\[(])?\s*(feat\.?|ft\.?|featuring)\s+.*$""")
        private val BRACKETED_DISPLAY_NOISE = Regex(
            """(?i)[\[(]\s*(?:explicit|clean|(?:official\s+)?(?:music\s+)?(?:audio|video|lyrics?|lyric\s+video|visualizer|hd|4k|mv|full\s+song|full\s+audio|prod\.?\s*(?:by\s*)?[^\])]+))\s*[\])]""",
        )
        private val TRAILING_DISPLAY_NOISE = Regex("""(?i)\s*[-–—]\s*(?:official\s+)?(?:music\s+)?(?:audio|video|lyrics?|visualizer|mv|full\s+song)\s*$""")
        private val ARTIST_NOISE_WORDS = setOf("the", "and", "feat", "ft", "featuring", "with", "x", "topic")
        private val PERFORMING_ROLE_WORDS = setOf(
            "mainartist", "featuredartist", "performer", "vocal", "vocals", "vocalist", "singer",
        )
        private val IDENTITY_VARIANT_PATTERNS = listOf(
            "live" to Regex("\\blive\\b"),
            "acoustic" to Regex("\\bacoustic\\b"),
            "karaoke" to Regex("\\bkaraoke\\b"),
            "instrumental" to Regex("\\binstrumental\\b"),
            "tribute" to Regex("\\btribute\\b"),
            "cover" to Regex("\\bcover\\b"),
            "remix" to Regex("\\bremix(?:ed)?\\b"),
            "mashup" to Regex("""\bmash[ -]?up\b|\b[a-z0-9]+\s+x\s+[a-z0-9]+\b"""),
            "demo" to Regex("\\bdemo\\b"),
            "slowed" to Regex("\\bslowed\\b"),
            "reverb" to Regex("\\breverb\\b"),
            "sped-up" to Regex("\\bsped up\\b"),
            "nightcore" to Regex("\\bnightcore\\b"),
            "radio-edit" to Regex("\\bradio edit\\b"),
            "extended" to Regex("\\bextended(?: version| mix)?\\b"),
        )
    }

    fun invalidateCredentialsCache() {
        cachedCredentials = null
        consecutiveFailures = 0
        failureCooldownUntilMs = 0L
    }

    suspend fun getCredentials(): BackendCredentials? = withContext(Dispatchers.IO) {
        cachedCredentials?.let { return@withContext it }

        // 1. Primary: Native secrets (ARM code + signature verification)
        val nativeCreds = runCatching { nativeSecrets.credentials() }.getOrNull()
        if (nativeCreds != null && nativeCreds.baseUrl.isNotBlank() && nativeCreds.apiKey.isNotBlank()) {
            cachedCredentials = nativeCreds
            Log.i(TAG, "Backend credentials loaded from native secrets: baseUrl=${nativeCreds.baseUrl}")
            return@withContext nativeCreds
        } else {
            Log.w(TAG, "Native credentials missing or incomplete (baseUrl='${nativeCreds?.baseUrl}', apiKey blank=${nativeCreds?.apiKey.isNullOrBlank()})")
        }

        // 2. Fallback: .lwp module config
        val handles = runCatching { moduleManager.enabledHandles() }.getOrNull() ?: emptyList()
        for (handle in handles) {
            val json = moduleManager.readDecryptedConfig(handle)
            if (json != null) {
                val url = json.optString("baseUrl").ifBlank { json.optJSONObject("tidal")?.optString("baseUrl").orEmpty() }
                if (url.isNotBlank()) {
                    val key = nativeCreds?.apiKey.orEmpty().ifBlank { json.optString("apiKey") }
                    if (key.isNotBlank()) {
                        val creds = BackendCredentials(baseUrl = url.trimEnd('/'), apiKey = key)
                        cachedCredentials = creds
                        Log.i(TAG, "Backend credentials loaded from .lwp module config: baseUrl=${creds.baseUrl}")
                        return@withContext creds
                    }
                }
            }
        }
        Log.w(TAG, "No backend credentials found in native secrets or .lwp module configs")
        null
    }

    suspend fun resolveStream(
        title: String,
        artist: String,
        expectedDurationSeconds: Int? = null,
        expectedAlbum: String? = null,
        preferredQuality: Int = QUALITY_MAX_HI_RES,
        excludedUrls: Set<String> = emptySet(),
    ): LosslessAudioStream? = withContext(Dispatchers.IO) {
        if (preferredQuality == QUALITY_YOUTUBE || title.isBlank() || artist.isBlank()) {
            Log.d(TAG, "resolveStream skipped: preferredQuality=$preferredQuality, title='$title', artist='$artist'")
            return@withContext null
        }

        val creds = getCredentials()
        if (creds == null || creds.baseUrl.isBlank() || creds.apiKey.isBlank()) {
            Log.w(TAG, "resolveStream aborted for '$title': credentials are null or blank")
            return@withContext null
        }
        Log.i(TAG, "resolveStream starting for '$title' by '$artist' (preferredQuality=$preferredQuality)")

        try {
            // 1. Search Tidal via backend
            val candidate = findBestVerifiedMatch(
                title = title,
                artist = artist,
                expectedDurationSeconds = expectedDurationSeconds,
                expectedAlbum = expectedAlbum,
                creds = creds,
                preferredQuality = preferredQuality,
            )
            if (candidate == null) {
                Log.w(TAG, "resolveStream: No matching Tidal candidate found for '$title' by '$artist'")
                return@withContext null
            }
            Log.i(TAG, "resolveStream: Matched Tidal track id=${candidate.id}, title='${candidate.title}', performer='${candidate.performerName}', atmos=${candidate.isAtmos}")

            // 2. Fetch Tidal direct streaming manifest
            val directStream = fetchTrackStreamUrl(candidate, preferredQuality, creds = creds)
            if (directStream != null && directStream.url !in excludedUrls &&
                (preferredQuality == QUALITY_DOLBY_ATMOS || !isAtmosStreamUrl(directStream.url))
            ) {
                Log.i(TAG, "resolveStream: Acquired stream for track ${candidate.id}: formatId=${directStream.formatId}, bitDepth=${directStream.bitDepth}, sampleRate=${directStream.samplingRate}kHz, bitrate=${directStream.bitrateKbps}kbps")
                consecutiveFailures = 0
                failureCooldownUntilMs = 0L
                return@withContext directStream
            }

            val qualitiesToTry = getQualityAttemptOrder(preferredQuality).filter { it != preferredQuality }
            for (quality in qualitiesToTry) {
                currentCoroutineContext().ensureActive()
                val stream = fetchTrackStreamUrl(candidate, quality, creds = creds)
                if (stream == null || stream.url in excludedUrls) continue
                // Never leak an Atmos (E-AC-3) mix into a stereo request:
                // devices without an EC-3 decoder fail on it outright.
                if (preferredQuality != QUALITY_DOLBY_ATMOS && isAtmosStreamUrl(stream.url)) continue
                consecutiveFailures = 0
                failureCooldownUntilMs = 0L
                return@withContext stream
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Lossless Tidal resolution failed: ${e.message}")
            // Network blips/timeouts must not trigger the 60s backend
            // cooldown: the backend is healthy, the radio isn't. Only real
            // backend failures back off; transient IO just returns null and
            // the next track tries again immediately.
            if (!isNetworkException(e)) {
                consecutiveFailures++
                if (consecutiveFailures >= 2) {
                    failureCooldownUntilMs = System.currentTimeMillis() + 60_000L
                }
            }
            null
        }
    }

    private fun isNetworkException(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is java.net.UnknownHostException ||
                cause is java.net.ConnectException ||
                cause is java.net.SocketTimeoutException ||
                cause is java.net.NoRouteToHostException ||
                (cause is java.io.IOException && cause.message?.contains("Unable to resolve host", ignoreCase = true) == true)
            ) return true
            cause = cause.cause
        }
        return false
    }

    private suspend fun findBestVerifiedMatch(
        title: String,
        artist: String,
        expectedDurationSeconds: Int?,
        expectedAlbum: String?,
        creds: BackendCredentials,
        preferredQuality: Int,
    ): TidalCandidateItem? {
        val cleanArtist = cleanForSearch(artist).ifBlank { artist }
        val cleanTitle = cleanForSearch(title).ifBlank { title }
        val queries = listOf(
            "$cleanTitle $cleanArtist".trim(),
            cleanTitle.trim(),
        ).distinct()

        for (query in queries) {
            currentCoroutineContext().ensureActive()
            val url = "${creds.baseUrl}/search/?s=" + URLEncoder.encode(query, "UTF-8")
            val reqBuilder = Request.Builder().url(url).get()
            if (creds.apiKey.isNotBlank()) {
                reqBuilder.addHeader("X-API-Key", creds.apiKey)
            }

            val body = resolutionClient.newCall(reqBuilder.build()).awaitSuccessfulBodyOrNull() ?: continue
            val items = parseTidalSearchItems(body)
            if (items.isEmpty()) continue

            items.asSequence()
                .mapNotNull { item ->
                    verifiedMatchScore(
                        item = item,
                        title = title,
                        artist = artist,
                        expectedDurationSeconds = expectedDurationSeconds,
                        expectedAlbum = expectedAlbum,
                    )?.let { score ->
                        var finalScore = score
                        if (preferredQuality == QUALITY_DOLBY_ATMOS && (item.isAtmos || item.isSpatial)) finalScore += 200
                        item to finalScore
                    }
                }
                .sortedWith(
                    compareByDescending<Pair<TidalCandidateItem, Int>> { it.second },
                )
                .firstOrNull()
                ?.first
                ?.let { return it }
            // Backend answered but scoring vetoed every candidate — log it:
            // silent misses here are the #1 reason lossless degrades to
            // YouTube with a generic badge.
            Log.d(TAG, "no verified match for '$title' / '$artist' among ${items.size} backend candidates")
        }

        return null
    }

    private fun parseTidalSearchItems(body: String): List<TidalCandidateItem> {
        return runCatching {
            val json = JSONObject(body)
            val dataObj = json.optJSONObject("data") ?: return emptyList()
            val items = dataObj.optJSONArray("items") ?: return emptyList()
            val result = mutableListOf<TidalCandidateItem>()

            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val id = item.optLong("id")
                val itemTitle = item.optString("title")
                if (id <= 0 || itemTitle.isBlank()) continue

                val duration = item.optInt("duration", 0)
                val artistsArray = item.optJSONArray("artists")
                val performer = artistsArray?.optJSONObject(0)?.optString("name")
                    ?: item.optJSONObject("artist")?.optString("name").orEmpty()
                val performers = (0 until (artistsArray?.length() ?: 0))
                    .mapNotNull { artistsArray?.optJSONObject(it)?.optString("name") }
                    .joinToString(", ")
                val albumTitle = item.optJSONObject("album")?.optString("title").orEmpty()
                val (isAtmos, isSpatial) = parseSpatialFlags(item)

                result.add(
                    TidalCandidateItem(
                        id = id,
                        title = itemTitle,
                        duration = duration,
                        performerName = performer,
                        albumArtistName = performer,
                        albumTitle = albumTitle,
                        performers = performers,
                        isAtmos = isAtmos,
                        isSpatial = isSpatial,
                    ),
                )
            }
            result
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchTrackStreamUrl(
        candidate: TidalCandidateItem,
        quality: Int,
        creds: BackendCredentials,
    ): LosslessAudioStream? {
        if (quality == QUALITY_DOLBY_ATMOS) {
            fetchTidalAtmosStream(candidate, creds)?.let { return it }
            fetchTidalSpatialStream(candidate, creds)?.let { return it }
            for (param in listOf("DOLBY_ATMOS", "ATMOS", "DOLBY")) {
                val spatial = fetchTrackQualityParam(candidate, param, creds) ?: continue
                if (spatial.audioCodecOverride != null) return spatial
            }
            return null
        }

        val qualityParam = when (quality) {
            QUALITY_MAX_HI_RES, QUALITY_HI_RES_96 -> "HI_RES_LOSSLESS"
            QUALITY_CD_LOSSLESS -> "LOSSLESS"
            QUALITY_MP3_320 -> "HIGH"
            QUALITY_DATA_SAVER -> "LOW"
            else -> "LOSSLESS"
        }
        return loadTrackManifest(candidate, qualityParam, quality, creds)
    }

    private suspend fun fetchTrackQualityParam(
        candidate: TidalCandidateItem,
        qualityParam: String,
        creds: BackendCredentials,
    ): LosslessAudioStream? =
        loadTrackManifest(candidate, qualityParam, QUALITY_DOLBY_ATMOS, creds)

    private suspend fun loadTrackManifest(
        candidate: TidalCandidateItem,
        qualityParam: String,
        quality: Int,
        creds: BackendCredentials,
    ): LosslessAudioStream? {
        val url = "${creds.baseUrl}/track/?id=${candidate.id}&quality=$qualityParam"
        val reqBuilder = Request.Builder().url(url).get()
        if (creds.apiKey.isNotBlank()) reqBuilder.addHeader("X-API-Key", creds.apiKey)

        return try {
            val body = resolutionClient.newCall(reqBuilder.build()).awaitSuccessfulBodyOrNull()
            if (body == null) {
                Log.w(TAG, "fetchTrackStreamUrl: HTTP response null or failed for track ${candidate.id} ($url)")
                return null
            }
            val json = JSONObject(body)
            val data = json.optJSONObject("data")
            if (data == null) {
                Log.w(TAG, "fetchTrackStreamUrl: 'data' object missing in response: ${body.take(160)}")
                return null
            }
            val manifest = data.optString("manifest")
            if (manifest.isBlank()) {
                Log.w(TAG, "fetchTrackStreamUrl: manifest field is blank in response for track ${candidate.id}")
                return null
            }

            val bitDepth = data.optInt("bitDepth", 16)
            val sampleRate = data.optDouble("sampleRate", 44100.0)
            val audioQuality = data.optString("audioQuality", "LOSSLESS")
            val manifestUrl = "data:application/dash+xml;base64,$manifest"
            val manifestIsAtmos = isAtmosStreamUrl(manifestUrl) || isAtmosManifest(
                runCatching {
                    String(android.util.Base64.decode(manifest, android.util.Base64.DEFAULT), Charsets.UTF_8)
                }.getOrDefault(""),
            )
            val manifestIsSpatial = !manifestIsAtmos && runCatching {
                isSpatialManifest(String(android.util.Base64.decode(manifest, android.util.Base64.DEFAULT), Charsets.UTF_8))
            }.getOrDefault(false)
            val qualityUpper = audioQuality.uppercase()
            val formatId = when {
                manifestIsAtmos || manifestIsSpatial -> QUALITY_DOLBY_ATMOS
                qualityUpper == "HI_RES_LOSSLESS" || qualityUpper == "HI_RES" -> QUALITY_MAX_HI_RES
                qualityUpper == "LOSSLESS" -> QUALITY_CD_LOSSLESS
                qualityUpper == "HIGH" -> QUALITY_MP3_320
                qualityUpper == "LOW" -> QUALITY_DATA_SAVER
                else -> quality
            }
            val codecOverride = when {
                manifestIsAtmos -> "DOLBY ATMOS"
                manifestIsSpatial -> "SPATIAL AUDIO"
                else -> null
            }
            // A spatial request that came back as stereo FLAC is not Atmos.
            if (quality == QUALITY_DOLBY_ATMOS && codecOverride == null) return null
            val samplingRateKHz = if (sampleRate > 1000) sampleRate / 1000.0 else sampleRate
            val bitrateKbps = if (formatId == QUALITY_MP3_320) 320 else if (formatId == QUALITY_DATA_SAVER) 96
            else ((bitDepth * samplingRateKHz * 2 * 1000) / 1000).toInt()

            LosslessAudioStream(
                url = "data:application/dash+xml;base64,$manifest",
                mimeType = "application/dash+xml",
                bitDepth = bitDepth,
                samplingRate = samplingRateKHz,
                formatId = formatId,
                bitrateKbps = bitrateKbps,
                trackId = candidate.id,
                durationSeconds = candidate.duration,
                audioCodecOverride = codecOverride,
            )
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchTidalAtmosStream(
        candidate: TidalCandidateItem,
        creds: BackendCredentials,
    ): LosslessAudioStream? =
        fetchSpatialManifest(
            candidate = candidate,
            creds = creds,
            query = "atmos=true",
            accept = { isAtmosManifest(it) },
            formatId = QUALITY_DOLBY_ATMOS,
            codecOverride = "DOLBY ATMOS",
            logLabel = "Atmos",
        )

    private suspend fun fetchTidalSpatialStream(
        candidate: TidalCandidateItem,
        creds: BackendCredentials,
    ): LosslessAudioStream? =
        fetchSpatialManifest(
            candidate = candidate,
            creds = creds,
            query = "spatial=true",
            accept = { isSpatialManifest(it) || isAtmosManifest(it) },
            formatId = QUALITY_DOLBY_ATMOS,
            codecOverride = "SPATIAL AUDIO",
            logLabel = "Spatial",
        )

    private suspend fun fetchSpatialManifest(
        candidate: TidalCandidateItem,
        creds: BackendCredentials,
        query: String,
        accept: (String) -> Boolean,
        formatId: Int,
        codecOverride: String,
        logLabel: String,
    ): LosslessAudioStream? {
        val url = "${creds.baseUrl}/trackManifests/?id=${candidate.id}&$query"
        val reqBuilder = Request.Builder().url(url).get()
        if (creds.apiKey.isNotBlank()) reqBuilder.addHeader("X-API-Key", creds.apiKey)

        return try {
            val body = resolutionClient.newCall(reqBuilder.build()).awaitSuccessfulBodyOrNull() ?: return null
            val json = JSONObject(body)
            val mpdXml = resolveManifestXml(json) ?: return null
            if (!accept(mpdXml)) {
                Log.d(TAG, "$logLabel manifest is not spatial for track ${candidate.id}; falling back")
                return null
            }
            val b64 = android.util.Base64.encodeToString(mpdXml.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            LosslessAudioStream(
                url = "data:application/dash+xml;base64,$b64",
                mimeType = "application/dash+xml",
                bitDepth = 24,
                samplingRate = 48.0,
                formatId = formatId,
                bitrateKbps = 768,
                trackId = candidate.id,
                durationSeconds = candidate.duration,
                audioCodecOverride = codecOverride,
            )
        } catch (error: Exception) {
            Log.w(TAG, "$logLabel fetch failed for track ${candidate.id}: ${error.message}")
            null
        }
    }

    private suspend fun resolveManifestXml(json: JSONObject): String? {
        val ref = extractAtmosManifestRef(json) ?: return null
        ref.mpdXml?.takeIf { it.isNotBlank() }?.let { return it }
        ref.mpdBase64?.takeIf { it.isNotBlank() }?.let { encoded ->
            val decoded = runCatching {
                String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
        }
        val mpdUri = ref.mpdUri?.takeIf { it.isNotBlank() } ?: return null
        val mpdReq = Request.Builder().url(mpdUri).get().build()
        return resolutionClient.newCall(mpdReq).awaitSuccessfulBodyOrNull()
    }

    private fun verifiedMatchScore(
        item: TidalCandidateItem,
        title: String,
        artist: String,
        expectedDurationSeconds: Int?,
        expectedAlbum: String?,
    ): Int? {
        val matchArtist = cleanForSearch(artist).ifBlank { artist }
        val targetTitle = normalizeTitle(title, matchArtist)
        val candidateTitle = normalizeTitle(item.title, matchArtist)
        if (targetTitle.isBlank()) return null

        val primaryIdentities = listOf(item.performerName, item.albumArtistName)
            .map(::normalizeText)
            .filter(String::isNotBlank)

        val targetArtists = matchArtist.split(Regex("""(?i)\s*(?:&|,|\bx\b|feat\.?|ft\.?|featuring|with|\+)\s*"""))
            .map(::normalizeText)
            .filter(String::isNotBlank)

        val artistExact = primaryIdentities.any { iden ->
            targetArtists.any { ta -> iden == ta }
        }

        val titleDistance = levenshtein(targetTitle, candidateTitle)
        val isExactMatch = targetTitle == candidateTitle
        val maxFuzz = (targetTitle.length / 5).coerceIn(1, 2)
        val isFuzzyMatch = artistExact && titleDistance <= maxFuzz
        val isDescriptorMatch = artistExact && targetTitle.length >= 4 && candidateTitle.length >= 4 && (
            (candidateTitle.startsWith(targetTitle) && listOf("rap", "song", "theme", "track", "audio", "music").contains(candidateTitle.substring(targetTitle.length).trim())) ||
            (targetTitle.startsWith(candidateTitle) && listOf("rap", "song", "theme", "track", "audio", "music").contains(targetTitle.substring(candidateTitle.length).trim()))
        )

        if (!isExactMatch && !isFuzzyMatch && !isDescriptorMatch) return null

        val targetVariants = identityVariants(title, matchArtist)
        val candidateVariants = identityVariants(item.title, matchArtist)
        // Version mismatch (remaster/live/acoustic on one side only) must
        // NOT veto: YouTube-sourced titles carry display noise the clean
        // Tidal title lacks, so a veto silently kills lossless for exactly
        // the tracks users actually play. De-preference instead — a
        // same-version candidate still outranks this one when present.
        val variantMismatch = targetVariants != candidateVariants

        if (!isVerifiedArtistMatch(matchArtist, item.performerName, item.albumArtistName, item.performers)) return null

        val durationDifference = if (expectedDurationSeconds != null && expectedDurationSeconds > 0) {
            if (item.duration <= 0) return null
            kotlin.math.abs(item.duration - expectedDurationSeconds).also { if (it > MAX_DURATION_DIFFERENCE_SECONDS) return null }
        } else null

        var score = 1_000 - titleDistance * 50
        if (artistExact) score += 300
        if (variantMismatch) score -= 400
        expectedAlbum?.takeIf(String::isNotBlank)?.let { album ->
            if (normalizeTitle(album, "") == normalizeTitle(item.albumTitle, "")) score += 120
        }
        durationDifference?.let { score += (MAX_DURATION_DIFFERENCE_SECONDS - it) * 10 }
        return score
    }

    private fun cleanForSearch(raw: String): String {
        return raw
            .replace(TOPIC_CHANNEL_SUFFIX, "")
            .replace(PIPE_NOISE, "")
            .replace(SOUNDTRACK_SUFFIX, "")
            .replace(FEATURING_CLAUSE, " ")
            .replace(BRACKETED_DISPLAY_NOISE, " ")
            .replace(TRAILING_DISPLAY_NOISE, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizeTitle(raw: String, artist: String): String {
        var cleaned = cleanForSearch(raw)
        if (artist.isNotBlank()) {
            val cleanArt = cleanForSearch(artist).ifBlank { artist }
            cleaned = cleaned.replaceFirst(
                Regex("""^\s*${Regex.escape(cleanArt)}\s*[-–—:]\s*""", RegexOption.IGNORE_CASE),
                "",
            )
            cleaned = cleaned.replace(
                Regex("""(?i)\s*[-–—:]\s*${Regex.escape(cleanArt)}\s*$"""),
                "",
            )
        }
        return normalizeText(cleaned)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private fun normalizeText(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .lowercase(Locale.ROOT)
        .replace(NON_ALPHANUMERIC, " ")
        .replace(MULTI_SPACE, " ")
        .trim()

    private fun identityVariants(raw: String, artist: String): Set<String> {
        val withoutArtistPrefix = if (artist.isBlank()) raw else raw.replaceFirst(
            Regex("""^\s*${Regex.escape(artist)}\s*[-–—:]\s*""", RegexOption.IGNORE_CASE),
            "",
        )
        val normalized = normalizeText(withoutArtistPrefix)
        return IDENTITY_VARIANT_PATTERNS.mapNotNullTo(linkedSetOf()) { (name, pattern) ->
            name.takeIf { pattern.containsMatchIn(normalized) }
        }
    }

    private fun isVerifiedArtistMatch(
        targetArtist: String,
        performer: String,
        albumArtist: String,
        performersText: String?,
    ): Boolean {
        val target = normalizeText(targetArtist)
        if (target.isBlank()) return false
        val primaryIdentities = listOf(performer, albumArtist)
            .map(::normalizeText)
            .filter(String::isNotBlank)
        if (primaryIdentities.any { it == target }) return true

        val targetArtists = targetArtist.split(Regex("""(?i)\s*(?:&|,|\bx\b|feat\.?|ft\.?|featuring|with|\+)\s*"""))
            .map(::normalizeText)
            .filter(String::isNotBlank)

        if (primaryIdentities.any { iden -> targetArtists.any { ta -> iden == ta } }) return true

        for (ta in targetArtists) {
            val taTokens = ta.split(' ').filter { it !in ARTIST_NOISE_WORDS }.toSet()
            if (taTokens.isNotEmpty() && primaryIdentities.any { iden -> taTokens.all(iden.split(' ').toSet()::contains) }) {
                return true
            }
        }

        val targetTokens = target.split(' ').filter { it !in ARTIST_NOISE_WORDS }.toSet()
        if (targetTokens.isEmpty()) return false
        if (primaryIdentities.any { identity -> targetTokens.all(identity.split(' ').toSet()::contains) }) return true

        val performingCredits = performersText.orEmpty()
            .split(Regex("""\s+-\s+"""))
            .map(::normalizeText)
            .filter { credit -> PERFORMING_ROLE_WORDS.any { role -> role in credit.split(' ') } }
        val performingTokens = (primaryIdentities + performingCredits)
            .flatMap { it.split(' ') }
            .toSet()
        return targetTokens.all(performingTokens::contains)
    }
}
