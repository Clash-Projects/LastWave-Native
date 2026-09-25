package com.lastwave.app.data.lossless

import android.util.Log
import com.lastwave.app.data.artwork.awaitSuccessfulBodyOrNull
import com.lastwave.app.data.plugin.ModuleManager
import com.lastwave.app.data.addon.AddonClient
import com.lastwave.app.data.addon.AddonTrack
import com.lastwave.app.data.addon.AddonStream
import com.lastwave.app.data.local.SettingsPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
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
    val isAddon: Boolean = false,
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
    val rawAddonId: String = "",
)

@Singleton
class LosslessMusicApi @Inject constructor(
    okHttpClient: OkHttpClient,
    private val moduleManager: ModuleManager,
    private val nativeSecrets: NativeSecrets,
    private val settingsPreferences: SettingsPreferences,
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
            val addonUrl = settingsPreferences.addonUrl.value
            return settingsPreferences.addonEnabled.value && !addonUrl.isNullOrBlank()
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
            if (preferred == QUALITY_DOLBY_ATMOS) {
                return listOf(
                    QUALITY_DOLBY_ATMOS,
                    QUALITY_MAX_HI_RES,
                    QUALITY_CD_LOSSLESS,
                    QUALITY_MP3_320,
                    QUALITY_DATA_SAVER,
                )
            }
            val tiersAscending = listOf(
                QUALITY_DATA_SAVER,
                QUALITY_MP3_320,
                QUALITY_CD_LOSSLESS,
                QUALITY_HI_RES_96,
                QUALITY_MAX_HI_RES,
            )
            val index = tiersAscending.indexOf(preferred)
            if (index == -1) return listOf(QUALITY_MAX_HI_RES, QUALITY_CD_LOSSLESS, QUALITY_MP3_320, QUALITY_DATA_SAVER)

            val preferredQuality = tiersAscending[index]
            val above = tiersAscending.subList(index + 1, tiersAscending.size)
            val below = tiersAscending.subList(0, index).reversed()

            return (listOf(preferredQuality) + above + below).distinct()
        }

        private val MANIFEST_CODECS = Regex("""codecs="([^"]+)"""")
        private val MANIFEST_SAMPLE_RATE = Regex("""audioSamplingRate="(\d+)"""", RegexOption.IGNORE_CASE)

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

        /** Extract audioSamplingRate from base64 DASH data URL or XML. */
        fun manifestSampleRateOf(dataUrl: String): Int? {
            val b64 = dataUrl.substringAfter("base64,", "").trim()
            val xml = if (b64.isNotEmpty() && dataUrl.startsWith("data:application/dash+xml")) {
                runCatching {
                    String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                }.getOrNull()
            } else if (dataUrl.trimStart().startsWith("<")) {
                dataUrl
            } else null
            if (xml.isNullOrBlank()) return null
            return MANIFEST_SAMPLE_RATE.find(xml)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
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
        // Bug #2 ("same song, different language audio"): Tidal returns one
        // entry per language for Indian soundtracks (e.g. Devara Part 1 in
        // Telugu/Hindi/Tamil share title "Ayudha Pooja" and artist
        // "Kaala Bhairava"). 8s tolerated cross-language duration overlap,
        // so tighten to ±5s. Duration only vets when the caller supplies it.
        private const val MAX_DURATION_DIFFERENCE_SECONDS = 5
        // Language markers found in YouTube/Tidal titles and album names.
        // Used to veto same-title different-language matches (bug #2) and to
        // detect ambiguous candidate sets that must fall back to YouTube.
        private val LANGUAGE_TOKENS = setOf(
            "telugu", "tamil", "hindi", "kannada", "malayalam", "punjabi",
            "marathi", "gujarati", "bengali", "bhojpuri", "odia", "oriya",
            "assamese", "urdu", "sanskrit", "english", "spanish", "french",
            "german", "italian", "portuguese", "japanese", "korean", "chinese",
            "arabic", "turkish",
        )
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
        val addonUrl = settingsPreferences.addonUrl.value
        val addonEnabled = settingsPreferences.addonEnabled.value
        if (addonEnabled && !addonUrl.isNullOrBlank()) {
            val normalized = AddonClient.normalizeBase(addonUrl)
            return@withContext BackendCredentials(baseUrl = normalized, apiKey = "addon", isAddon = true)
        }

        null
    }

    suspend fun resolveStream(
        title: String,
        artist: String,
        expectedDurationSeconds: Int? = null,
        expectedAlbum: String? = null,
        preferredQuality: Int = QUALITY_MAX_HI_RES,
        excludedUrls: Set<String> = emptySet(),
        isDownload: Boolean = false,
    ): LosslessAudioStream? = withContext(Dispatchers.IO) {
        if (preferredQuality == QUALITY_YOUTUBE || title.isBlank() || artist.isBlank()) {
            return@withContext null
        }

        val creds = getCredentials()
        if (creds == null || creds.baseUrl.isBlank() || !creds.isAddon) {
            return@withContext null
        }
        Log.i(TAG, "resolveStream starting for '$title' by '$artist' via addon (preferredQuality=$preferredQuality, isDownload=$isDownload)")

        return@withContext resolveFromAddon(
            title = title,
            artist = artist,
            expectedDurationSeconds = expectedDurationSeconds,
            expectedAlbum = expectedAlbum,
            preferredQuality = preferredQuality,
            addonBaseUrl = creds.baseUrl,
            excludedUrls = excludedUrls,
            isDownload = isDownload,
        )
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

    private suspend fun resolveFromAddon(
        title: String,
        artist: String,
        expectedDurationSeconds: Int?,
        expectedAlbum: String?,
        preferredQuality: Int,
        addonBaseUrl: String,
        excludedUrls: Set<String>,
        isDownload: Boolean = false,
    ): LosslessAudioStream? {
        val addonClient = AddonClient(addonBaseUrl, client, nativeSecrets = nativeSecrets)
        val cleanArtist = cleanForSearch(artist).ifBlank { artist }
        val cleanTitle = cleanForSearch(title).ifBlank { title }
        val queries = listOf(
            "$cleanTitle $cleanArtist".trim(),
            cleanTitle.trim(),
        ).distinct()

        val isAtmosPreferred = preferredQuality == QUALITY_DOLBY_ATMOS
        val qualityParam = when (preferredQuality) {
            QUALITY_DOLBY_ATMOS -> "lossless"
            QUALITY_MAX_HI_RES, QUALITY_HI_RES_96 -> "hi_res"
            QUALITY_CD_LOSSLESS -> "lossless"
            QUALITY_MP3_320 -> "high"
            QUALITY_DATA_SAVER -> "low"
            else -> "lossless"
        }

        var candidates: List<TidalCandidateItem> = emptyList()
        for (query in queries) {
            currentCoroutineContext().ensureActive()
            val searchResult = addonClient.search(query, qualityParam, isAtmosPreferred)
            val tracks = searchResult.getOrNull() ?: continue
            if (tracks.isEmpty()) continue

            val verified = tracks.asSequence()
                .map { track ->
                    TidalCandidateItem(
                        id = track.id.toLongOrNull() ?: track.id.hashCode().toLong(),
                        title = track.title,
                        duration = track.duration.toInt(),
                        performerName = track.artist,
                        albumArtistName = track.artist,
                        albumTitle = track.album,
                        performers = track.artist,
                        isAtmos = track.atmos || track.audioModes.any { it.contains("DOLBY", ignoreCase = true) || it.contains("ATMOS", ignoreCase = true) },
                        isSpatial = track.audioModes.any { it.contains("360", ignoreCase = true) || it.contains("SPATIAL", ignoreCase = true) },
                        rawAddonId = track.id,
                    )
                }
                .mapNotNull { item ->
                    verifiedMatchScore(
                        item = item,
                        title = title,
                        artist = artist,
                        expectedDurationSeconds = expectedDurationSeconds,
                        expectedAlbum = expectedAlbum,
                    )?.let { score ->
                        var finalScore = score
                        if (isAtmosPreferred && (item.isAtmos || item.isSpatial)) finalScore += 200
                        item to finalScore
                    }
                }
                .sortedWith(compareByDescending { it.second })
                .map { it.first }
                .distinctBy { it.rawAddonId.ifBlank { it.id.toString() } }
                .toList()

            if (verified.isNotEmpty()) {
                val gated = gateAmbiguousLanguage(verified, title, expectedAlbum)
                if (gated.isNotEmpty()) {
                    candidates = gated
                    break
                }
            }
        }

        if (candidates.isEmpty()) {
            Log.w(TAG, "resolveFromAddon: No matching candidate found for '$title' by '$artist'")
            return null
        }

        val qualitiesToTry = if (isAtmosPreferred) listOf("atmos", "lossless", "high") else listOf(qualityParam, "lossless", "high")
        for (q in qualitiesToTry) {
            val wantAtmos = q == "atmos" || isAtmosPreferred
            val targetCandidates = if (wantAtmos) {
                val atmosMatches = candidates.filter { it.isAtmos || it.isSpatial }
                if (atmosMatches.isNotEmpty()) atmosMatches else listOf(candidates.first())
            } else {
                val stereoMatches = candidates.filter { !it.isAtmos && !it.isSpatial }
                if (stereoMatches.isNotEmpty()) stereoMatches else candidates
            }

            for (candidate in targetCandidates.take(2)) {
                currentCoroutineContext().ensureActive()
                val trackId = candidate.rawAddonId.ifBlank { candidate.id.toString() }
                val streamResult = addonClient.stream(trackId, q, wantAtmos, isDownload = isDownload)
                val stream = streamResult.getOrNull() ?: continue

                val rawUrl = stream.dataUrl?.takeIf { it.isNotBlank() }
                    ?: stream.url.takeIf { it.isNotBlank() }
                    ?: stream.manifestXml?.takeIf { it.isNotBlank() }?.let { xml ->
                        val b64 = android.util.Base64.encodeToString(xml.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                        "data:application/dash+xml;base64,$b64"
                    }
                    ?: continue

                if (rawUrl in excludedUrls) continue
                if (!wantAtmos && isAtmosStreamUrl(rawUrl)) continue

                val isStreamAtmos = wantAtmos || (stream.audioMode?.contains("ATMOS", ignoreCase = true) == true) || isAtmosStreamUrl(rawUrl)
                val manifestSampleRate = manifestSampleRateOf(rawUrl)
                val rawSampleRate = if (stream.sampleRate > 1000) stream.sampleRate else stream.sampleRate * 1000.0
                val effectiveSampleRate = manifestSampleRate?.toDouble() ?: rawSampleRate
                val effectiveBitDepth = if (stream.bitDepth > 16) stream.bitDepth
                    else if (effectiveSampleRate > 48000.0) 24
                    else stream.bitDepth
                val formatId = when {
                    isStreamAtmos -> QUALITY_DOLBY_ATMOS
                    effectiveBitDepth > 16 || effectiveSampleRate > 48000.0 -> QUALITY_MAX_HI_RES
                    stream.codec.equals("flac", ignoreCase = true) || effectiveBitDepth == 16 -> QUALITY_CD_LOSSLESS
                    stream.quality.equals("high", ignoreCase = true) -> QUALITY_MP3_320
                    else -> QUALITY_CD_LOSSLESS
                }

                Log.i(TAG, "resolveFromAddon: Acquired stream for track $trackId: formatId=$formatId, bitDepth=$effectiveBitDepth, sampleRate=${effectiveSampleRate}Hz, codec=${stream.codec}")
                consecutiveFailures = 0
                failureCooldownUntilMs = 0L

                return LosslessAudioStream(
                    url = rawUrl,
                    mimeType = "application/dash+xml",
                    bitDepth = effectiveBitDepth,
                    samplingRate = effectiveSampleRate / 1000.0,
                    formatId = formatId,
                    bitrateKbps = stream.bitrate?.let { if (it > 10_000) it / 1000 else it },
                    trackId = candidate.id,
                    durationSeconds = candidate.duration,
                    audioCodecOverride = when {
                        isStreamAtmos -> "DOLBY ATMOS"
                        stream.codec.equals("mp3", ignoreCase = true) -> "MP3 320k"
                        else -> null
                    },
                )
            }
        }
        return null
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

        // Bug #2: same title + same artist in another language (Telugu vs
        // Hindi vs Tamil). Veto when both sides declare a language and they
        // are disjoint. One-sided markers (Tidal omits the tag) stay playable.
        val expectedLanguages = extractLanguages("$title ${expectedAlbum.orEmpty()}")
        val candidateLanguages = extractLanguages("${item.title} ${item.albumTitle}")
        if (expectedLanguages.isNotEmpty() && candidateLanguages.isNotEmpty() &&
            expectedLanguages.intersect(candidateLanguages).isEmpty()
        ) {
            Log.d(TAG, "reject candidate id=${item.id} title='${item.title}' album='${item.albumTitle}': language mismatch expected=$expectedLanguages candidate=$candidateLanguages for '$title'")
            return null
        }

        val durationDifference = if (expectedDurationSeconds != null && expectedDurationSeconds > 0) {
            if (item.duration <= 0) {
                Log.d(TAG, "reject candidate id=${item.id}: missing duration for '$title'")
                return null
            }
            kotlin.math.abs(item.duration - expectedDurationSeconds).also {
                if (it > MAX_DURATION_DIFFERENCE_SECONDS) {
                    Log.d(TAG, "reject candidate id=${item.id}: duration ${item.duration}s vs expected ${expectedDurationSeconds}s (Δ${it}s) for '$title'")
                    return null
                }
            }
        } else null

        var score = 1_000 - titleDistance * 50
        if (artistExact) score += 300
        if (variantMismatch) score -= 400
        // Bug #2: album was only +120, so a wrong-language album with the
        // same title/artist tied the correct one and backend order won.
        // Exact album match now dominates; containment still scores well
        // ("Devara Part 1" vs "Devara Part 1 - Telugu"); true mismatches
        // are penalized so the right language outranks the wrong one.
        expectedAlbum?.takeIf(String::isNotBlank)?.let { album ->
            val normExpected = normalizeTitle(album, "")
            val normCandidate = normalizeTitle(item.albumTitle, "")
            if (normExpected.isNotBlank() && normCandidate.isNotBlank()) {
                when {
                    normExpected == normCandidate -> score += 500
                    normCandidate.contains(normExpected) || normExpected.contains(normCandidate) -> score += 300
                    else -> {
                        val expTokens = normExpected.split(' ').filter { it.length > 1 }.toSet()
                        val candTokens = normCandidate.split(' ').filter { it.length > 1 }.toSet()
                        val expNumbers = Regex("""\b\d+\b""").findAll(normExpected).map { it.value }.toSet()
                        val candNumbers = Regex("""\b\d+\b""").findAll(normCandidate).map { it.value }.toSet()
                        val numbersClash = expNumbers.isNotEmpty() && candNumbers.isNotEmpty() && expNumbers != candNumbers
                        val overlap = expTokens.intersect(candTokens).size
                        if (!numbersClash && expTokens.isNotEmpty() && overlap >= minOf(2, expTokens.size) && overlap * 2 >= expTokens.size) {
                            score += 150
                        } else {
                            score -= 250
                            Log.d(TAG, "album mismatch penalty id=${item.id}: expected='$album' candidate='${item.albumTitle}' for '$title'")
                        }
                    }
                }
            }
        }
        durationDifference?.let { score += (MAX_DURATION_DIFFERENCE_SECONDS - it) * 10 }
        return score
    }

    private fun extractLanguages(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        return normalizeText(raw).split(' ').toSet().intersect(LANGUAGE_TOKENS)
    }

    /**
     * Bug #2 gate: when the request carries no language marker but the
     * verified set spans ≥2 languages (Telugu/Hindi/Tamil variants of the
     * same title+artist), confidence is low — return empty so the caller
     * falls back to YouTube (correct language) instead of playing the
     * backend's first ordering. Returns the input unchanged when confident.
     */
    private fun gateAmbiguousLanguage(
        verified: List<TidalCandidateItem>,
        title: String,
        expectedAlbum: String?,
    ): List<TidalCandidateItem> {
        if (verified.size < 2) return verified
        if (extractLanguages("$title ${expectedAlbum.orEmpty()}").isNotEmpty()) return verified
        val distinct = verified
            .flatMap { extractLanguages("${it.title} ${it.albumTitle}").toList() }
            .toSet()
        if (distinct.size >= 2) {
            Log.w(TAG, "ambiguous language $distinct among ${verified.size} candidates for '$title'; falling back to YouTube")
            return emptyList()
        }
        return verified
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
