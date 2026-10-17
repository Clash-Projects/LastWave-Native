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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    /** 0 means unknown; never assume 16 for an unmeasured depth. */
    val bitDepth: Int = 0,
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
    /** Addon search flag (HI_RES_LOSSLESS vs LOSSLESS). Upstream answers a
     *  hi_res /stream with HTTP 200 + 16-bit on CD-only masters instead of
     *  an error, so without this the resolver stops at the first
     *  downgraded success and a 24-bit master later in the list is never
     *  tried. */
    val audioQuality: String = "",
    val bitDepth: Int? = null,
    val directStreamUrl: String? = null,
) {
    fun isHiResFlagged(): Boolean =
        (bitDepth ?: 0) > 16 ||
            audioQuality.contains("HI_RES", ignoreCase = true) ||
            audioQuality.contains("HI-RES", ignoreCase = true) ||
            audioQuality.contains("24-BIT", ignoreCase = true) ||
            audioQuality.contains("24BIT", ignoreCase = true) ||
            audioQuality.contains("24/")
}

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
            val addons = settingsPreferences.httpAddons.value
            if (addons.any { it.enabled && it.url.isNotBlank() }) return true
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

        /**
         * Consecutive backend misses before the chain is treated as down and
         * every resolve falls through to YouTube directly.
         */
        private const val FAILURE_COOLDOWN_THRESHOLD = 2

        /** How long a backed-off backend stays out of the resolve path. */
        private const val FAILURE_COOLDOWN_MS = 30_000L

        fun getQualityAttemptOrder(preferred: Int): List<Int> {
            // YouTube is strict: no lossless attempt, no upgrade. Empty = caller goes straight to YouTube.
            if (preferred == QUALITY_YOUTUBE) return emptyList()
            // Atmos only when explicitly selected: atmos -> hi-res -> CD -> 320, then YouTube.
            // Data Saver (96k) is skipped — YouTube 128-256k beats it; tried only when explicitly chosen.
            if (preferred == QUALITY_DOLBY_ATMOS) {
                return listOf(
                    QUALITY_DOLBY_ATMOS,
                    QUALITY_MAX_HI_RES,
                    QUALITY_HI_RES_96,
                    QUALITY_CD_LOSSLESS,
                    QUALITY_MP3_320,
                )
            }
            // Hi-Res steps DOWN only, never up to Atmos. YouTube last.
            if (preferred == QUALITY_MAX_HI_RES) {
                return listOf(
                    QUALITY_MAX_HI_RES,
                    QUALITY_HI_RES_96,
                    QUALITY_CD_LOSSLESS,
                    QUALITY_MP3_320,
                )
            }
            if (preferred == QUALITY_HI_RES_96) {
                return listOf(
                    QUALITY_HI_RES_96,
                    QUALITY_CD_LOSSLESS,
                    QUALITY_MP3_320,
                )
            }
            // Lower tiers: preferred first, then higher above, then lower below.
            // Never includes Atmos (28) — Atmos plays only when explicitly selected.
            // Data Saver is excluded from fallback (YouTube beats 96k) unless explicitly chosen.
            val tiersAscending = listOf(
                QUALITY_DATA_SAVER,
                QUALITY_MP3_320,
                QUALITY_CD_LOSSLESS,
                QUALITY_HI_RES_96,
                QUALITY_MAX_HI_RES,
            )
            val index = tiersAscending.indexOf(preferred)
            if (index == -1) return listOf(QUALITY_MAX_HI_RES, QUALITY_HI_RES_96, QUALITY_CD_LOSSLESS, QUALITY_MP3_320)

            val preferredQuality = tiersAscending[index]
            val above = tiersAscending.subList(index + 1, tiersAscending.size)
            val below = tiersAscending.subList(0, index).reversed()

            return (listOf(preferredQuality) + above + below)
                .distinct()
                .filter { it != QUALITY_DATA_SAVER || preferred == QUALITY_DATA_SAVER }
        }

        private val MANIFEST_CODECS = Regex("""codecs="([^"]+)"""")
        private val MANIFEST_SAMPLE_RATE = Regex("""audioSamplingRate="(\d+)"""", RegexOption.IGNORE_CASE)
        // Tidal rendition id carries ground truth: id="FLAC_HIRES,48000,24".
        // Parsed when present so a missing/slow probe or stale catalogue tier
        // can never downgrade a real 24-bit rendition to a fabricated 16.
        private val MANIFEST_RENDITION = Regex("""Representation[^>]*id="[^"]*,(\d+),(\d+)"""", RegexOption.IGNORE_CASE)

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

        /** Depth from the rendition id (`FLAC_HIRES,48000,24` -> 24). Null when unreadable. */
        fun manifestBitDepthOf(dataUrl: String): Int? {
            val b64 = dataUrl.substringAfter("base64,", "").trim()
            val xml = if (b64.isNotEmpty() && dataUrl.startsWith("data:application/dash+xml")) {
                runCatching {
                    String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                }.getOrNull()
            } else if (dataUrl.trimStart().startsWith("<")) {
                dataUrl
            } else null
            if (xml.isNullOrBlank()) return null
            return MANIFEST_RENDITION.find(xml)?.groupValues?.getOrNull(2)?.toIntOrNull()?.takeIf { it in 8..32 }
        }

        /** True for E-AC-3 spatial codec labels. Pure; safe to unit-test on JVM. */
        fun isAtmosCodec(codec: String?): Boolean {
            val c = codec?.trim()?.lowercase().orEmpty()
            return c.startsWith("ec-3") || c.startsWith("eac3") || c.startsWith("ac-3")
        }

        /** True when the stream bytes are E-AC-3 spatial. Fail-open (false) when unreadable. */
        fun isAtmosStreamUrl(url: String): Boolean {
            if (url.startsWith("data:application/dash+xml")) {
                return isAtmosCodec(manifestCodecOf(url))
            }
            val lower = url.lowercase()
            return lower.contains("atmos") || lower.contains("eac3") || lower.contains("ec-3")
        }

        /** True when the stream points to a known prank or decoy CDN stream. */
        fun isDecoyStream(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val lower = url.lowercase()
            return lower.contains("pranks-cdn") || lower.contains("definatelynagato")
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
        data class TitleParts(
            val words: List<String>,
            val core: String,
            val versions: Set<String>,
            val context: Set<String>,
        )

        private val VERSION_WORDS = setOf(
            "remix", "remixes", "rmx", "refix", "flip", "bootleg", "mashup", "medley",
            "live", "concert", "unplugged", "acoustic", "instrumental", "karaoke",
            "vocals", "vocal", "acapella", "acappella", "backing", "stems", "stem",
            "cover", "demo", "reprise", "remake", "rework", "extended", "edit",
            "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "symphonic",
            "part", "pt", "chapter", "atmos", "dolby", "spatial",
        )

        private val NEUTRAL_SEGMENTS = setOf(
            "albumversion", "originalversion", "originalmix", "singleversion",
            "radioversion", "radioedit", "stereoversion", "monoversion",
            "studioversion", "fullversion", "standardversion", "explicitversion",
            "deluxeversion", "originaltrack", "audio", "officialaudio", "officialvideo",
            "musicvideo", "lyricvideo", "lyrics", "lyric", "visualizer", "4k", "hd",
        )

        private val NOISE_WORDS = setOf(
            "official", "video", "audio", "lyrics", "lyric", "lyrical", "visualizer",
            "song", "songs", "full", "music", "the", "and", "from", "feat", "ft",
            "featuring", "with", "new", "latest", "free", "download", "remaster",
            "remastered", "explicit", "clean", "bonus", "track", "deluxe", "original",
            "album", "single", "hd", "hq", "4k", "mp3", "ost", "soundtrack", "mv",
            "version", "versions", "mix", "mixes",
        )

        private val RECORD_LABELS = setOf(
            "tseries", "t series", "zeemusiccompany", "zeemusic", "zee music", "zee music company",
            "sonymusicindia", "sonymusic", "sony music", "sony music india",
            "yrf", "yashrajfilms", "tips", "tipsmusic", "tips official",
            "venus", "speedrecords", "speed records", "saregama", "geetmp3", "geet mp3",
            "whitehillmusic", "white hill music", "vyrloriginals", "vyrl", "desimusicfactory",
            "eros", "erosnow", "adityamusic", "aditya music", "laharimusic", "lahari music",
            "t-series", "sony", "universal", "warner",
        )

        private fun isRecordLabel(artist: String): Boolean {
            val norm = normalizeText(artist).replace(" ", "")
            return RECORD_LABELS.any { norm == it.replace(" ", "") || (it.length >= 4 && norm.contains(it.replace(" ", ""))) }
        }

        private val TRAILING_NOISE = setOf(
            "song", "songs", "video", "audio", "lyrics", "lyric", "lyrical",
            "official", "full", "hd", "hq", "4k", "mp3", "ost", "soundtrack", "mv", "track",
        )

        private val JOINING_WORDS = setOf("and")
        private val ARTIST_SEPARATORS =
            Regex("""\s*(?:[,&/;·|]|\band\b|\bx\b|\bvs\.?\b|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE)

        private val DIACRITICS = Regex("\\p{M}+")
        private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
        private val MULTI_SPACE = Regex("\\s+")

        fun normalizeText(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase(Locale.ROOT)
            .replace("$", "s")
            .replace(NON_ALPHANUMERIC, " ")
            .replace(MULTI_SPACE, " ")
            .trim()
        private val TOPIC_CHANNEL_SUFFIX = Regex("""(?i)\s*[-–—]\s*topic\s*$|\s+topic\s*$""")
        private val PIPE_NOISE = Regex("""\s*\|.*$""")
        private val SOUNDTRACK_SUFFIX = Regex(
            """(?i)\s*[\[(]\s*(?:from\s+(?:the\s+)?(?:original\s+)?(?:motion\s+picture|movie|film|soundtrack)|soundtrack|ost)\s*(?:["“][^"”\r\n]+["”]|[^\])]+)?\s*[\])]\s*$|\s*[-–—|]\s*(?:from\s+(?:the\s+)?(?:original\s+)?(?:motion\s+picture|movie|film|soundtrack)|soundtrack|ost)\s*.*$""",
        )
        private val FEATURING_CLAUSE = Regex("""(?i)(?:\s*[\[(])?\s*(feat\.?|ft\.?|featuring|with)\s+.*$""")
        private val BRACKETED_DISPLAY_NOISE = Regex(
            """(?i)[\[(]\s*(?:explicit|clean|(?:official\s+)?(?:music\s+)?(?:audio|video|lyrics?|lyric\s+video|visualizer|hd|4k|mv|full\s+song|full\s+audio|prod\.?\s*(?:by\s*)?[^\])]+)|remaster(?:ed)?(?:\s*\d{2,4})?|\d{2,4}\s*remaster(?:ed)?|deluxe(?:\s*edition)?|bonus(?:\s*track)?|special\s*edition|anniversary(?:\s*edition)?|radio\s*edit|single\s*version|album\s*version|with\s+[^\])]+|from\s+[^\])]+)\s*[\])]""",
        )
        private val TRAILING_DISPLAY_NOISE = Regex(
            """(?i)\s*[-–—|]\s*(?:official\s+)?(?:music\s+)?(?:audio|video|lyrics?|visualizer|mv|full\s+song|coke\s*studio(?:\s*season\s*\d+)?|a\s*colors\s*show|tiny\s*desk|live\s*session|unplugged|acoustic\s*version)\s*$""",
        )
        private val VERSION_NOISE_REGEX = Regex(
            """(?i)\s*[\[(]\s*(?:remaster(?:ed)?(?:\s*\d{2,4})?|\d{2,4}\s*remaster(?:ed)?|deluxe(?:\s*edition)?|bonus(?:\s*track)?|radio\s*edit|single\s*version|album\s*version|anniversary(?:\s*edition)?|special\s*edition|original\s*mix|extended\s*mix|club\s*mix|acoustic|live(?:\s*at[^\])]*)?|edit|version|mono|stereo|clean|explicit|re-?recorded|pt\.?\s*\d+|part\s*\d+|from\s+[^\])]+)\s*[\])]|\s*[-–—|]\s*(?:remaster(?:ed)?(?:\s*\d{2,4})?|\d{2,4}\s*remaster(?:ed)?|deluxe(?:\s*edition)?|bonus(?:\s*track)?|radio\s*edit|single\s*version|album\s*version|anniversary(?:\s*edition)?|live(?:\s*at.*)?|acoustic|re-?recorded|mono|stereo|coke\s*studio.*|a\s*colors\s*show)\s*$""",
        )
        /** Atmos/Spatial version markers ("(Dolby Atmos)", "(Atmos)", "- Dolby Atmos",
         *  "(Spatial Audio)", "(360 Reality Audio)"). Stripped for title matching so an
         *  Atmos mix of the same song verifies against the stereo request title; the
         *  "atmos" identity-variant tag (below) still de-preferences it for stereo
         *  tiers while the Atmos boost/ordering prefers it when 28 is selected. */
        private val ATMOS_VERSION_MARKER = Regex(
            """(?i)[\[(]\s*(?:dolby\s+atmos|dolby|atmos|spatial\s+audio|spatial|360(?:\s*reality\s*audio)?|sony\s+360|mpeg-?\s*h)\s*[\])]|\s*[-–—]\s*(?:dolby\s+atmos|dolby|atmos|spatial\s+audio|spatial)\s*$""",
        )

        fun extractCoreTitle(title: String): String {
            return title
                .replace(SOUNDTRACK_SUFFIX, " ")
                .replace(VERSION_NOISE_REGEX, " ")
                .replace(ATMOS_VERSION_MARKER, " ")
                .replace(BRACKETED_DISPLAY_NOISE, " ")
                .replace(TRAILING_DISPLAY_NOISE, " ")
                .replace(FEATURING_CLAUSE, " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }

        fun parseTitle(raw: String, artist: String = "", album: String? = null): TitleParts {
            val versions = sortedSetOf<String>()
            val context = mutableSetOf<String>()
            var text = raw.lowercase(Locale.ROOT)
                .replace("&", " and ")
                .replace("$", "s")

            // 1. Bracketed asides, innermost first: "(From "Satyamev Jayate")", "[Official Audio]"
            val bracketRegex = Regex("""[(\[]([^()\[\]]*)[)\]]""")
            repeat(3) {
                if (!bracketRegex.containsMatchIn(text)) return@repeat
                text = bracketRegex.replace(text) { match ->
                    classifySegment(match.groupValues[1], versions, context)
                    " "
                }
            }
            // Unbalanced bracket
            text.indexOfFirst { it == '(' || it == '[' }.takeIf { it >= 0 }?.let { open ->
                classifySegment(text.substring(open), versions, context)
                text = text.substring(0, open)
            }

            // 2. Dash-, colon-, and pipe-separated tails: "Animal - Arjan Vailly", "Paniyon Sa - Satyamev Jayate"
            val dashRegex = Regex("""\s*[-–—:|]+\s*""")
            repeat(3) {
                val dash = dashRegex.find(text) ?: return@repeat
                val head = text.substring(0, dash.range.first).trim()
                val tail = text.substring(dash.range.last + 1).trim()
                if (head.isBlank() || tail.isBlank()) return@repeat

                if (isArtistOrAlbumName(head, artist, album)) {
                    classifySegment(head, versions, context)
                    text = tail
                } else if (isArtistOrAlbumName(tail, artist, album)) {
                    classifySegment(tail, versions, context)
                    text = head
                } else if (isSegmentMetadata(tail)) {
                    classifySegment(tail, versions, context)
                    text = head
                } else if (isSegmentMetadata(head)) {
                    classifySegment(head, versions, context)
                    text = tail
                } else {
                    classifySegment(head, versions, context)
                    text = tail
                }
            }

            // 3. Featuring clause
            text = text.replace(Regex("""\b(feat|ft|featuring|with)\b.*""", RegexOption.IGNORE_CASE), " ")

            var words = text.split(Regex("""[\s.·/]+"""))
                .map { it.replace(NON_ALPHANUMERIC, "") }
                .filter { it.isNotEmpty() && it !in JOINING_WORDS }

            while (words.size > 1 && words.last() in TRAILING_NOISE) {
                words = words.dropLast(1)
            }

            return TitleParts(
                words = words,
                core = words.joinToString(""),
                versions = versions,
                context = context,
            )
        }

        private fun classifySegment(
            segment: String,
            versions: MutableSet<String>,
            context: MutableSet<String>,
        ) {
            val words = segment.split(Regex("""[\s.·/]+"""))
                .map { it.replace(NON_ALPHANUMERIC, "") }
                .filter { it.isNotEmpty() }
            if (words.isEmpty()) return
            if (words.joinToString("") in NEUTRAL_SEGMENTS) return
            val marks = words.filter { it in VERSION_WORDS }
            if (marks.isNotEmpty()) {
                versions += marks
                return
            }
            context += words.filter { it.length > 2 && it !in NOISE_WORDS }
        }

        private fun isSegmentMetadata(segment: String): Boolean {
            val words = segment.split(Regex("""[\s.·/]+"""))
                .map { it.replace(NON_ALPHANUMERIC, "") }
                .filter { it.isNotEmpty() }
            if (words.isEmpty()) return true
            if (words.joinToString("") in NEUTRAL_SEGMENTS) return true
            if (words.any { it in VERSION_WORDS }) return true
            return words.all { it in NOISE_WORDS || it in NEUTRAL_SEGMENTS || it in VERSION_WORDS }
        }

        private fun isArtistOrAlbumName(text: String, artist: String, album: String?): Boolean {
            if (text.isBlank()) return false
            val words = text.split(Regex("""[\s.·/]+""")).map { it.replace(NON_ALPHANUMERIC, "") }.filter { it.isNotEmpty() }
            if (words.isEmpty()) return false

            val artistWords = artist.lowercase(Locale.ROOT).split(Regex("""[\s.·/]+"""))
                .map { it.replace(NON_ALPHANUMERIC, "") }
                .filter { it.isNotEmpty() }
                .toSet()
            if (words.all { it in artistWords } || (artistWords.isNotEmpty() && artistWords.all { it in words })) return true

            for (part in text.split(ARTIST_SEPARATORS)) {
                val partWords = part.split(Regex("""[\s.·/]+""")).map { it.replace(NON_ALPHANUMERIC, "") }.filter { it.isNotEmpty() }
                if (partWords.isNotEmpty() && artistWords.isNotEmpty() &&
                    (partWords.all { it in artistWords } || artistWords.all { it in partWords })) {
                    return true
                }
            }

            if (!album.isNullOrBlank()) {
                val albumWords = album.lowercase(Locale.ROOT).split(Regex("""[\s.·/]+"""))
                    .map { it.replace(NON_ALPHANUMERIC, "") }
                    .filter { it.isNotEmpty() }
                    .toSet()
                if (words.all { it in albumWords } || (albumWords.isNotEmpty() && albumWords.all { it in words })) return true
            }

            return false
        }

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
            "atmos" to Regex("\\bdolby\\b|\\batmos\\b|\\bspatial\\b|360\\s*reality|sony\\s*360"),
        )
    }

    fun invalidateCredentialsCache() {
        cachedCredentials = null
        consecutiveFailures = 0
        failureCooldownUntilMs = 0L
    }

    /**
     * Records a backend miss. One transient 404/503 must not black out the
     * whole chain; once misses repeat, back off for [FAILURE_COOLDOWN_MS] so
     * every resolve inside that window falls through to YouTube instantly
     * (the callers of [isCoolingDown]) instead of walking the module round
     * trips — the difference between a 200ms fallback and a 5s stall.
     */
    private fun noteBackendFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= FAILURE_COOLDOWN_THRESHOLD) {
            failureCooldownUntilMs = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
        }
    }

    suspend fun getAllEnabledCredentials(): List<BackendCredentials> = withContext(Dispatchers.IO) {
        val addons = settingsPreferences.httpAddons.value
        val enabled = addons.filter { it.enabled && it.url.isNotBlank() }
        if (enabled.isNotEmpty()) {
            return@withContext enabled.map {
                BackendCredentials(baseUrl = AddonClient.normalizeBase(it.url), apiKey = "addon", isAddon = true)
            }
        }
        val addonUrl = settingsPreferences.addonUrl.value
        val addonEnabled = settingsPreferences.addonEnabled.value
        if (addonEnabled && !addonUrl.isNullOrBlank()) {
            return@withContext listOf(
                BackendCredentials(baseUrl = AddonClient.normalizeBase(addonUrl), apiKey = "addon", isAddon = true)
            )
        }
        emptyList()
    }

    suspend fun getCredentials(): BackendCredentials? = withContext(Dispatchers.IO) {
        getAllEnabledCredentials().firstOrNull()
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

        val allCreds = getAllEnabledCredentials()
        if (allCreds.isEmpty()) {
            return@withContext null
        }

        if (allCreds.size == 1) {
            Log.i(TAG, "resolveStream starting for '$title' by '$artist' via addon (preferredQuality=$preferredQuality, isDownload=$isDownload)")
            val resolved = runCatching {
                resolveFromAddon(
                    title = title,
                    artist = artist,
                    expectedDurationSeconds = expectedDurationSeconds,
                    expectedAlbum = expectedAlbum,
                    preferredQuality = preferredQuality,
                    addonBaseUrl = allCreds.first().baseUrl,
                    excludedUrls = excludedUrls,
                    isDownload = isDownload,
                )
            }.getOrNull()
            if (resolved != null) return@withContext resolved

            // Atmos fallback: if Dolby Atmos was selected but not found on the track,
            // fall back to stereo hi-res / lossless rather than failing and cooling down the backend.
            if (preferredQuality == QUALITY_DOLBY_ATMOS) {
                Log.i(TAG, "resolveStream: Dolby Atmos not found for '$title' by '$artist', falling back to stereo lossless")
                return@withContext resolveStream(
                    title = title,
                    artist = artist,
                    expectedDurationSeconds = expectedDurationSeconds,
                    expectedAlbum = expectedAlbum,
                    preferredQuality = QUALITY_MAX_HI_RES,
                    excludedUrls = excludedUrls,
                    isDownload = isDownload,
                )
            }

            noteBackendFailure()
            return@withContext null
        }

        Log.i(TAG, "resolveStream racing for '$title' by '$artist' across ${allCreds.size} addons concurrently (preferredQuality=$preferredQuality, isDownload=$isDownload)")
        val deferreds = allCreds.map { creds ->
            async {
                runCatching {
                    resolveFromAddon(
                        title = title,
                        artist = artist,
                        expectedDurationSeconds = expectedDurationSeconds,
                        expectedAlbum = expectedAlbum,
                        preferredQuality = preferredQuality,
                        addonBaseUrl = creds.baseUrl,
                        excludedUrls = excludedUrls,
                        isDownload = isDownload,
                    )
                }.getOrNull()
            }
        }

        val candidates = deferreds.awaitAll().filterNotNull()
        if (candidates.isEmpty()) {
            // Atmos fallback: if Dolby Atmos was selected but not found across addons,
            // fall back to stereo hi-res / lossless rather than failing and cooling down the backend.
            if (preferredQuality == QUALITY_DOLBY_ATMOS) {
                Log.i(TAG, "resolveStream: Dolby Atmos not found across addons for '$title' by '$artist', falling back to stereo lossless")
                return@withContext resolveStream(
                    title = title,
                    artist = artist,
                    expectedDurationSeconds = expectedDurationSeconds,
                    expectedAlbum = expectedAlbum,
                    preferredQuality = QUALITY_MAX_HI_RES,
                    excludedUrls = excludedUrls,
                    isDownload = isDownload,
                )
            }

            noteBackendFailure()
            return@withContext null
        }

        candidates.maxByOrNull { stream ->
            val tierScore = when (stream.formatId) {
                QUALITY_DOLBY_ATMOS -> 1000
                QUALITY_MAX_HI_RES -> 800
                QUALITY_HI_RES_96 -> 700
                QUALITY_CD_LOSSLESS -> 500
                QUALITY_MP3_320 -> 300
                QUALITY_DATA_SAVER -> 100
                else -> 50
            }
            tierScore + (stream.bitDepth * 5) + stream.samplingRate.toInt()
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
        val parsedTarget = parseTitle(title, cleanArtist, expectedAlbum)
        val searchableTitle = (parsedTarget.words + parsedTarget.versions).joinToString(" ").ifBlank { cleanTitle }

        val artistList = cleanArtist.split(ARTIST_SEPARATORS)
            .map { cleanForSearch(it) }
            .filter { it.isNotBlank() }
        val primaryArtist = artistList.firstOrNull().orEmpty()
        val secondaryArtist = artistList.getOrNull(1).orEmpty()

        val queries = listOfNotNull(
            if (primaryArtist.isNotBlank()) "$searchableTitle $primaryArtist".trim() else null,
            if (secondaryArtist.isNotBlank()) "$searchableTitle $secondaryArtist".trim() else null,
            searchableTitle.trim(),
            if (cleanArtist.isNotBlank() && cleanArtist != primaryArtist) "$searchableTitle $cleanArtist".trim() else null,
            if (parsedTarget.core.length >= 4) foldTransliteration(searchableTitle).trim() else null,
        ).filter { it.isNotBlank() }.distinct()

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
                        isAtmos = track.isDolbyAtmos || track.atmos || track.audioModes.any { it.contains("DOLBY", ignoreCase = true) || it.contains("ATMOS", ignoreCase = true) },
                        isSpatial = track.audioModes.any { it.contains("360", ignoreCase = true) || it.contains("SPATIAL", ignoreCase = true) },
                        rawAddonId = track.id,
                        audioQuality = track.audioQuality,
                        bitDepth = track.bitDepth,
                        directStreamUrl = track.directStreamUrl,
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
                        if (isAtmosPreferred && isAtmosCandidate(item)) finalScore += 200
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

        // Hi-res preference: hi-res-flagged masters first (stable — score
        // order kept within each group). A CD-only master otherwise scores
        // identically to the 24-bit master and backend order wins the coin
        // flip, parking playback at 16-bit forever.
        val wantsHiRes = qualityParam == "hi_res" && !isAtmosPreferred
        val ordered = if (isAtmosPreferred) {
            candidates.sortedWith(compareByDescending<TidalCandidateItem> { isAtmosCandidate(it) })
        } else if (wantsHiRes) {
            candidates.sortedWith(compareByDescending<TidalCandidateItem> { it.isHiResFlagged() })
        } else {
            candidates
        }

        // Tier waterfall (backend search params). Rules:
        // - YouTube: strict, never reaches here (resolveStream returns null early).
        // - Atmos (28): atmos -> hi_res -> lossless -> high, then YouTube. Only path containing "atmos".
        // - Hi-Res (27/7): step DOWN only (hi_res -> lossless -> high), never up to Atmos. YouTube last.
        // - Lower tiers: preferred first, then higher above, then lower. Never Atmos.
        val qualitiesToTry = when {
            isAtmosPreferred -> listOf("atmos", "hi_res", "lossless", "high")
            qualityParam == "hi_res" -> listOf("hi_res", "lossless", "high")
            qualityParam == "lossless" -> listOf("lossless", "hi_res", "high")
            qualityParam == "high" -> listOf("high", "lossless", "hi_res")
            qualityParam == "low" -> listOf("low", "high", "lossless", "hi_res")
            else -> listOf(qualityParam, "lossless", "high")
        }
        for (q in qualitiesToTry) {
            // Each tier declares its own intent: only the immersive tier
            // asks for the spatial mix, later tiers ask for stereo so a
            // missing/unplayable spatial mix still resolves to stereo
            // instead of failing the whole waterfall.
            val wantAtmos = q == "atmos"
            val targetCandidates = if (wantAtmos) {
                val atmosMatches = ordered.filter { isAtmosCandidate(it) }
                if (atmosMatches.isNotEmpty()) atmosMatches else ordered
            } else {
                val stereoMatches = ordered.filter { !isAtmosCandidate(it) }
                if (stereoMatches.isNotEmpty()) stereoMatches else ordered
            }

            // Hi-res / Atmos tiers scan wider: a silently-downgraded answer
            // below must not consume the attempt budget for the whole tier.
            val tierBudget = if (wantAtmos) 3 else if (wantsHiRes && q == "hi_res") 4 else 2
            for (candidate in targetCandidates.take(tierBudget)) {
                currentCoroutineContext().ensureActive()
                val trackId = candidate.rawAddonId.ifBlank { candidate.id.toString() }
                val streamResult = addonClient.stream(trackId, q, wantAtmos, isDownload = isDownload)
                val stream = streamResult.getOrNull()

                val rawUrl = stream?.dataUrl?.takeIf { it.isNotBlank() }
                    ?: stream?.manifestXml?.takeIf { it.isNotBlank() }?.let { xml ->
                        val b64 = android.util.Base64.encodeToString(xml.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                        "data:application/dash+xml;base64,$b64"
                    }
                    ?: stream?.url?.takeIf { it.isNotBlank() }
                    ?: candidate.directStreamUrl?.takeIf { it.isNotBlank() }
                    ?: continue

                if (rawUrl in excludedUrls) continue
                if (isDecoyStream(rawUrl) || (stream?.url?.let(::isDecoyStream) == true)) {
                    Log.w(TAG, "resolveFromAddon: candidate $trackId returned decoy prank stream ($rawUrl); skipping")
                    continue
                }
                if (!wantAtmos && isAtmosStreamUrl(rawUrl)) continue

                // Atmos is a property of the STREAM (audioMode flag or spatial
                // URL), never of the request: a stereo fallback for an Atmos
                // preference must be labeled (and badged) as what it is.
                val isStreamAtmos = stream?.isDolbyAtmos == true ||
                    (stream?.audioMode?.contains("ATMOS", ignoreCase = true) == true) ||
                    isAtmosCodec(stream?.codec) ||
                    isAtmosStreamUrl(rawUrl)

                // Atmos isolation: a hi-res/CD/320 request must never come home
                // as Dolby (the URL check above misses manifests whose
                // spatial-ness is only in the audioMode flag). Atmos plays
                // only when explicitly selected (quality 28).
                if (!isAtmosPreferred && isStreamAtmos) {
                    Log.i(TAG, "resolveFromAddon: skipping spatial stream for track $trackId (stereo tier requested)")
                    continue
                }
                val manifestSampleRate = manifestSampleRateOf(rawUrl)
                val manifestDepth = manifestBitDepthOf(rawUrl)
                val streamSampleRate = stream?.sampleRate ?: 0.0
                val rawSampleRate = if (streamSampleRate > 1000.0) streamSampleRate else if (streamSampleRate > 0.0) streamSampleRate * 1000.0 else 44100.0
                val effectiveSampleRate = manifestSampleRate?.toDouble() ?: rawSampleRate
                val streamCodec = stream?.codec.orEmpty().lowercase()
                val isLossyCodec = streamCodec.contains("mp4a") || streamCodec.contains("aac") || streamCodec.contains("mp3") || streamCodec.contains("opus")
                val isLossyStream = isLossyCodec || stream?.quality.equals("high", ignoreCase = true) || stream?.quality.equals("low", ignoreCase = true) || q == "high" || q == "low"

                val isHiResFlagged = !isLossyStream && (candidate.isHiResFlagged() ||
                    stream?.quality?.contains("HI_RES", ignoreCase = true) == true ||
                    stream?.quality?.contains("HI-RES", ignoreCase = true) == true ||
                    stream?.audioQuality?.contains("HI_RES", ignoreCase = true) == true ||
                    stream?.audioQuality?.contains("HI-RES", ignoreCase = true) == true ||
                    stream?.quality?.contains("24-BIT", ignoreCase = true) == true ||
                    stream?.quality?.contains("24BIT", ignoreCase = true) == true ||
                    stream?.quality?.contains("24/") == true ||
                    stream?.bitDepth == 24 ||
                    (manifestDepth ?: 0) >= 24 ||
                    (wantsHiRes && q == "hi_res") ||
                    effectiveSampleRate > 48000.0)
                // Depth precedence: manifest rendition id (ground truth for
                // these bytes) > addon numbers > hi-res inference. 0 stays
                // unknown — never fabricate 16 for an unmeasured depth.
                // Lossy audio streams (AAC/MP3) have NO integer bit depth.
                val reportedDepth = if (isLossyStream) null else (manifestDepth
                    ?: stream?.bitDepth?.takeIf { it > 0 }
                    ?: candidate.bitDepth?.takeIf { it > 0 })
                val effectiveBitDepth = when {
                    isLossyStream -> null
                    (reportedDepth ?: 0) > 0 -> reportedDepth
                    effectiveSampleRate > 192000.0 -> 32
                    isHiResFlagged -> 24
                    else -> null
                }
                val formatId = when {
                    isStreamAtmos -> QUALITY_DOLBY_ATMOS
                    // 96k / Low / Data Saver tier:
                    q == "low" || stream?.quality.equals("low", ignoreCase = true) || stream?.audioQuality.equals("low", ignoreCase = true) ||
                        (isLossyCodec && (stream?.bitrate ?: 0) in 1..160_000) -> QUALITY_DATA_SAVER
                    // 320k / High tier:
                    q == "high" || stream?.quality.equals("high", ignoreCase = true) || stream?.audioQuality.equals("high", ignoreCase = true) ||
                        (isLossyCodec && (stream?.bitrate ?: 0) > 160_000) -> QUALITY_MP3_320
                    (effectiveBitDepth ?: 0) > 16 || effectiveSampleRate > 48000.0 || isHiResFlagged -> {
                        if (effectiveSampleRate > 96000.0) QUALITY_MAX_HI_RES else QUALITY_HI_RES_96
                    }
                    streamCodec.equals("flac", ignoreCase = true) || effectiveBitDepth == 16 -> QUALITY_CD_LOSSLESS
                    else -> QUALITY_CD_LOSSLESS
                }

                // A hi_res request answered with ≤16-bit/≤48kHz is a silent
                // downgrade (CD-only master), not a hi-res hit: keep
                // scanning candidates instead of parking playback at 16-bit
                // while a 24-bit master sits later in the list. The
                // "lossless" tier below still accepts 16-bit normally.
                val isHiResTierHit = formatId == QUALITY_MAX_HI_RES || formatId == QUALITY_HI_RES_96
                val isHiResAttempt = q == "hi_res"
                if (isHiResAttempt && !isStreamAtmos && !isHiResTierHit) {
                    Log.i(TAG, "resolveFromAddon: candidate $trackId answered hi_res with ${effectiveBitDepth ?: 0}-bit/${effectiveSampleRate}Hz; trying next candidate")
                    continue
                }

                Log.i(TAG, "resolveFromAddon: Acquired stream for track $trackId: formatId=$formatId, bitDepth=${effectiveBitDepth ?: "unknown"}, sampleRate=${effectiveSampleRate}Hz, codec=$streamCodec")
                consecutiveFailures = 0
                failureCooldownUntilMs = 0L

                val lowerUrlPath = rawUrl.substringBefore('?').lowercase()
                val isExplicitDash = rawUrl.startsWith("data:application/dash+xml") ||
                    lowerUrlPath.endsWith(".mpd") || lowerUrlPath.contains("/mpd")
                val streamMimeType = when {
                    isExplicitDash -> "application/dash+xml"
                    lowerUrlPath.endsWith(".m3u8") || lowerUrlPath.contains(".m3u8") -> "application/x-mpegURL"
                    // Atmos arrives as a direct progressive E-AC-3 MP4.
                    isStreamAtmos -> "audio/mp4"
                    lowerUrlPath.endsWith(".flac") -> "audio/flac"
                    lowerUrlPath.endsWith(".mp4") || lowerUrlPath.endsWith(".m4a") -> "audio/mp4"
                    lowerUrlPath.endsWith(".mp3") -> "audio/mpeg"
                    lowerUrlPath.endsWith(".aac") -> "audio/aac"
                    lowerUrlPath.endsWith(".ogg") || lowerUrlPath.endsWith(".opus") -> "audio/ogg"
                    streamCodec.contains("mp3") -> "audio/mpeg"
                    streamCodec.contains("aac") -> "audio/mp4"
                    streamCodec.contains("mp4a") -> "audio/mp4"
                    streamCodec.contains("opus") -> "audio/ogg"
                    streamCodec.contains("flac") -> "audio/flac"
                    formatId == QUALITY_MP3_320 -> "audio/mpeg"
                    formatId == QUALITY_DATA_SAVER -> "audio/mp4"
                    else -> "audio/flac"
                }

                val resolvedBitrateKbps = stream?.bitrate?.let { if (it > 10_000) it / 1000 else it }
                    ?: when (formatId) {
                        QUALITY_MP3_320 -> 320
                        QUALITY_DATA_SAVER -> 96
                        else -> null
                    }

                return LosslessAudioStream(
                    url = rawUrl,
                    mimeType = streamMimeType,
                    bitDepth = if (isLossyStream) 0 else (effectiveBitDepth ?: (if (isHiResFlagged || effectiveSampleRate > 48000.0) 24 else 0)),
                    samplingRate = effectiveSampleRate / 1000.0,
                    formatId = formatId,
                    bitrateKbps = resolvedBitrateKbps,
                    trackId = candidate.id,
                    durationSeconds = candidate.duration,
                    audioCodecOverride = when {
                        isStreamAtmos -> "DOLBY ATMOS"
                        formatId == QUALITY_MP3_320 -> "320 kbps"
                        formatId == QUALITY_DATA_SAVER -> "96 kbps"
                        streamCodec.equals("mp3", ignoreCase = true) -> "320 kbps"
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
        val wanted = parseTitle(title, matchArtist, expectedAlbum)
        val got = parseTitle(item.title, item.performerName, item.albumTitle)
        if (wanted.core.isBlank() || got.core.isBlank()) return null

        // 1. Version agreement: asking for original must never land on remix, live, acoustic, karaoke, etc.
        // And asking for remix must never land on original cut.
        if (wanted.versions != got.versions) {
            Log.d(TAG, "reject candidate id=${item.id}: version mismatch wanted=${wanted.versions} got=${got.versions} for '$title'")
            return null
        }

        // Severe mismatches (karaoke, instrumental, tribute, cover)
        val targetVariants = identityVariants(title, matchArtist)
        val candidateVariants = identityVariants(item.title, matchArtist)
        val severeMismatch = (!targetVariants.contains("instrumental") && candidateVariants.contains("instrumental")) ||
            (!targetVariants.contains("karaoke") && candidateVariants.contains("karaoke")) ||
            (!targetVariants.contains("tribute") && candidateVariants.contains("tribute")) ||
            (!targetVariants.contains("cover") && candidateVariants.contains("cover"))
        if (severeMismatch) {
            Log.d(TAG, "reject candidate id=${item.id}: severe variant mismatch target=$targetVariants candidate=$candidateVariants for '$title'")
            return null
        }

        // 2. Title core matching
        val isExactCore = wanted.core == got.core
        val isNormalizedCore = wanted.core.replace(" ", "") == got.core.replace(" ", "")
        val isPhoneticCore = foldTransliteration(wanted.core) == foldTransliteration(got.core)
        val coreDistance = levenshtein(wanted.core, got.core)
        val minLen = minOf(wanted.core.length, got.core.length)
        val isFuzzyCore = minLen >= 7 && coreDistance <= (if (minLen >= 14) 2 else 1)

        if (!isExactCore && !isNormalizedCore && !isPhoneticCore && !isFuzzyCore) return null

        // 3. Artist verification & exact-master duration tie-break
        val hasArtistMatch = isVerifiedArtistMatch(
            targetArtist = matchArtist,
            performer = item.performerName,
            albumArtist = item.albumArtistName,
            performersText = item.performers,
        )

        val durationDifference = if (expectedDurationSeconds != null && expectedDurationSeconds > 0 && item.duration > 0) {
            kotlin.math.abs(item.duration - expectedDurationSeconds)
        } else null

        val isLabel = isRecordLabel(matchArtist)
        val hasAlbumCtx = hasAlbumOrContextOverlap(wanted, got, expectedAlbum, item.albumTitle)

        // When credits disagree (e.g. composer vs singer in regional music, or channel name like T-Series/Sony Music India):
        // Allow ONLY IF exact title master matches duration to within <= 2 seconds AND (it is a label OR album/context matches)!
        val creditDisagreeOverride = !hasArtistMatch && (isExactCore || isNormalizedCore || isPhoneticCore) &&
            (isLabel || hasAlbumCtx) &&
            durationDifference != null && durationDifference <= 2

        if (!hasArtistMatch && !creditDisagreeOverride) {
            Log.d(TAG, "reject candidate id=${item.id}: artist mismatch target='$matchArtist' performer='${item.performerName}' for '$title'")
            return null
        }

        // Bug #2: same title + same artist in another language (Telugu vs Hindi vs Tamil)
        val expectedLanguages = extractLanguages("$title ${expectedAlbum.orEmpty()}")
        val candidateLanguages = extractLanguages("${item.title} ${item.albumTitle}")
        if (expectedLanguages.isNotEmpty() && candidateLanguages.isNotEmpty() &&
            expectedLanguages.intersect(candidateLanguages).isEmpty()
        ) {
            Log.d(TAG, "reject candidate id=${item.id} title='${item.title}' album='${item.albumTitle}': language mismatch expected=$expectedLanguages candidate=$candidateLanguages for '$title'")
            return null
        }

        // 4. Duration ceiling
        val maxDurationDifference = when {
            hasArtistMatch && isExactCore -> 45
            hasArtistMatch -> 35
            else -> 2 // Credit disagree override strictly capped at 2 seconds
        }
        if (durationDifference != null && durationDifference > maxDurationDifference) {
            Log.d(TAG, "reject candidate id=${item.id}: duration ${item.duration}s vs expected ${expectedDurationSeconds}s (Δ${durationDifference}s > ${maxDurationDifference}s) for '$title'")
            return null
        }

        // 5. Scoring
        var score = 1_000 - coreDistance * 30
        if (isExactCore) score += 400
        else if (isNormalizedCore) score += 300
        else if (isPhoneticCore) score += 200

        if (hasArtistMatch) {
            score += 300
        } else {
            // Master tie-break penalty so any credited candidate beats it
            score -= 300
        }

        // Context alignment (shared movie/album/soundtrack name in brackets or packaging)
        if (wanted.context.isNotEmpty() && got.context.isNotEmpty() && wanted.context.any { it in got.context }) {
            score += 200
        }

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
                            score -= 100
                            Log.d(TAG, "album mismatch penalty id=${item.id}: expected='$album' candidate='${item.albumTitle}' for '$title'")
                        }
                    }
                }
            }
        }
        durationDifference?.let { score += (maxDurationDifference - it) * 5 }
        return score
    }

    private fun extractLanguages(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        return normalizeText(raw).split(' ').toSet().intersect(LANGUAGE_TOKENS)
    }

    private fun hasAlbumOrContextOverlap(
        wanted: TitleParts,
        got: TitleParts,
        expectedAlbum: String?,
        candidateAlbum: String?,
    ): Boolean {
        if (wanted.context.isNotEmpty() && got.context.isNotEmpty() && wanted.context.any { it in got.context }) {
            return true
        }
        if (wanted.context.isNotEmpty() && !candidateAlbum.isNullOrBlank()) {
            val normCand = normalizeText(candidateAlbum).split(' ').filter { it.length > 2 }.toSet()
            if (wanted.context.any { it in normCand }) return true
        }
        if (got.context.isNotEmpty() && !expectedAlbum.isNullOrBlank()) {
            val normExp = normalizeText(expectedAlbum).split(' ').filter { it.length > 2 }.toSet()
            if (got.context.any { it in normExp }) return true
        }
        if (!expectedAlbum.isNullOrBlank() && !candidateAlbum.isNullOrBlank()) {
            val normExp = normalizeText(expectedAlbum)
            val normCand = normalizeText(candidateAlbum)
            if (normExp.isNotBlank() && normCand.isNotBlank()) {
                if (normExp == normCand || normExp.contains(normCand) || normCand.contains(normExp)) return true
                val expTokens = normExp.split(' ').filter { it.length > 2 }.toSet()
                val candTokens = normCand.split(' ').filter { it.length > 2 }.toSet()
                if (expTokens.isNotEmpty() && candTokens.isNotEmpty() && expTokens.any { it in candTokens }) return true
            }
        }
        return false
    }

    /**
     * When multiple language releases exist and the request specifies an
     * expected language, filter candidates to match that language.
     * Never drops all candidates to empty.
     */
    private fun gateAmbiguousLanguage(
        verified: List<TidalCandidateItem>,
        title: String,
        expectedAlbum: String?,
    ): List<TidalCandidateItem> {
        if (verified.size < 2) return verified
        val expectedLanguages = extractLanguages("$title ${expectedAlbum.orEmpty()}")
        if (expectedLanguages.isNotEmpty()) {
            val matching = verified.filter {
                val candidateLangs = extractLanguages("${it.title} ${it.albumTitle}")
                candidateLangs.isEmpty() || candidateLangs.intersect(expectedLanguages).isNotEmpty()
            }
            if (matching.isNotEmpty()) return matching
        }
        return verified
    }

    private fun cleanForSearch(raw: String): String {
        return raw
            .replace(TOPIC_CHANNEL_SUFFIX, "")
            .replace(PIPE_NOISE, "")
            .replace(SOUNDTRACK_SUFFIX, "")
            .replace(FEATURING_CLAUSE, " ")
            .replace(ATMOS_VERSION_MARKER, " ")
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

    /** True for an Atmos/Spatial mix by backend flag OR by title label ("(Dolby Atmos)"
     *  version suffix). Labels matter because some catalogue items omit audioModes; the
     *  "atmos" identity-variant tag keeps such items de-preferenced (never vetoed) for
     *  stereo tiers while the Atmos boost/ordering prefers them when 28 is selected. */
    private fun isAtmosCandidate(item: TidalCandidateItem): Boolean =
        item.isAtmos || item.isSpatial ||
            identityVariants(item.title, item.performerName).contains("atmos")

    private fun foldTransliteration(str: String): String {
        return str
            .replace("aa", "a")
            .replace("ee", "i")
            .replace("oo", "u")
            .replace("th", "t")
            .replace("dh", "d")
            .replace("bh", "b")
            .replace("kh", "k")
            .replace("gh", "g")
            .replace("sh", "s")
            .replace("zh", "z")
            .replace("ph", "f")
            .replace("v", "w")
            .replace("ll", "l")
            .replace("tt", "t")
            .replace("dd", "d")
            .replace("pp", "p")
            .replace("mm", "m")
            .replace("nn", "n")
            .replace("ss", "s")
            .replace("rr", "r")
            .replace("cc", "c")
            .replace("yy", "y")
    }

    private fun cleanArtistIdentity(raw: String): String {
        val normalized = normalizeText(raw)
        // Collapse single-letter initials: "a r rahman" -> "ar rahman", "a p dhillon" -> "ap dhillon"
        val collapsed = normalized.replace(Regex("""\b([a-z])\s+(?=[a-z]\b)"""), "$1")
        return collapsed
            .split(' ')
            .filter { it !in ARTIST_NOISE_WORDS }
            .joinToString(" ")
            .trim()
    }

    internal fun artistNames(value: String): Set<List<String>> {
        val lowered = value.lowercase(Locale.ROOT).replace("$", "s")
        val collapsed = lowered
            .replace(Regex("""\b([a-z])\s*\.\s*(?=[a-z]\b)"""), "$1")
            .replace(Regex("""\b([a-z])\s+(?=[a-z]\b)"""), "$1")
        return collapsed
            .split(ARTIST_SEPARATORS)
            .map { name ->
                name.split(Regex("""[\s.·/]+"""))
                    .map { it.replace(NON_ALPHANUMERIC, "") }
                    .filter { it.length > 1 && it !in ARTIST_NOISE_WORDS }
            }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun runOfWords(outer: List<String>, inner: List<String>): Boolean {
        if (inner.isEmpty() || inner.size > outer.size) return false
        if (inner.size == 1 && outer.size > 1) {
            return false
        }
        return (0..outer.size - inner.size).any { at ->
            outer.subList(at, at + inner.size) == inner
        }
    }

    private fun isVerifiedArtistMatch(
        targetArtist: String,
        performer: String,
        albumArtist: String,
        performersText: String?,
    ): Boolean {
        val target = cleanArtistIdentity(targetArtist)
        if (target.isBlank()) return false
        val primaryIdentities = listOf(performer, albumArtist)
            .map(::cleanArtistIdentity)
            .filter(String::isNotBlank)
        if (primaryIdentities.any { it == target }) return true

        val targetArtists = artistNames(targetArtist)
        val candidateArtists = (listOf(performer, albumArtist) + listOfNotNull(performersText))
            .filter(String::isNotBlank)
            .flatMap { artistNames(it) }
            .toSet()

        if (targetArtists.isEmpty() || candidateArtists.isEmpty()) return false

        for (ta in targetArtists) {
            for (ca in candidateArtists) {
                if (ta == ca) return true
                if (runOfWords(ta, ca) || runOfWords(ca, ta)) return true
            }
        }

        val performingCredits = performersText.orEmpty()
            .split(Regex("""\s+-\s+"""))
            .map(::cleanArtistIdentity)
            .filter { credit -> PERFORMING_ROLE_WORDS.any { role -> role in credit.split(' ') } }
        val performingTokens = (primaryIdentities + performingCredits)
            .flatMap { it.split(' ') }
            .toSet()
        val targetTokens = target.split(' ').filter { it.length > 1 }.toSet()
        return targetTokens.isNotEmpty() && targetTokens.all(performingTokens::contains)
    }
}
