package com.lastwave.app.data.lossless

import android.util.Log
import com.lastwave.app.data.artwork.awaitSuccessfulBodyOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class LosslessAudioStream(
    val url: String,
    val mimeType: String = "audio/flac",
    val bitDepth: Int = 16,
    val samplingRate: Double = 44.1,
    val formatId: Int = 6,
    val bitrateKbps: Int? = null,
    val trackId: Long = 0,
    val durationSeconds: Int = 0,
)

// --- Playback Backend A Models ---

@Serializable
private data class BackendASearchResponse(
    val success: Boolean = false,
    val results: BackendASearchResults? = null,
)

@Serializable
private data class BackendASearchResults(
    val tracks: BackendATrackList? = null,
)

@Serializable
private data class BackendATrackList(
    val items: List<BackendATrackItem> = emptyList(),
)

@Serializable
private data class BackendATrackItem(
    val id: Long,
    val title: String,
    val duration: Int = 0,
    val source: String = "backend_a",
    val version: String? = null,
    val performer: BackendAPerformer? = null,
    val performers: String? = null,
    val album: BackendAAlbumInfo? = null,
    val hires: Boolean = false,
    @SerialName("maximum_bit_depth")
    val maxBitDepth: Int? = null,
    @SerialName("maximum_sampling_rate")
    val maxSamplingRate: Double? = null,
)

@Serializable
private data class BackendAPerformer(
    val name: String,
    val id: Long = 0,
)

@Serializable
private data class BackendAAlbumInfo(
    val title: String? = null,
    val artist: BackendAPerformer? = null,
)

@Serializable
private data class BackendATrackUrlResponse(
    val success: Boolean = false,
    val data: BackendATrackUrlData? = null,
    val error: String? = null,
)

@Serializable
private data class BackendATrackUrlData(
    val url: String? = null,
    @SerialName("format_id")
    val formatId: Int = 6,
    @SerialName("mime_type")
    val mimeType: String = "audio/flac",
    @SerialName("sampling_rate")
    val samplingRate: Double = 44.1,
    @SerialName("bit_depth")
    val bitDepth: Int = 16,
    val duration: Int = 0,
)

// --- Playback Backend B Models ---

@Serializable
private data class BackendBSearchResponse(
    val data: BackendBSearchData? = null,
)

@Serializable
private data class BackendBSearchData(
    val items: List<BackendBTrackItem> = emptyList(),
)

@Serializable
private data class BackendBTrackItem(
    val id: Long,
    val title: String,
    val duration: Int = 0,
    val version: String? = null,
    val artist: BackendBArtist? = null,
    val artists: List<BackendBArtist> = emptyList(),
    val album: BackendBAlbum? = null,
    val audioQuality: String? = null,
)

@Serializable
private data class BackendBArtist(
    val name: String? = null,
    val id: Long = 0,
)

@Serializable
private data class BackendBAlbum(
    val title: String? = null,
    val id: Long = 0,
)

@Serializable
private data class BackendBTrackUrlResponse(
    val data: BackendBTrackData? = null,
)

@Serializable
private data class BackendBTrackData(
    val trackId: Long = 0,
    val audioQuality: String? = null,
    val bitDepth: Int? = null,
    val sampleRate: Double? = null,
    val manifest: String? = null,
    val manifestMimeType: String? = null,
)

@Singleton
class LosslessMusicApi @Inject constructor(
    okHttpClient: OkHttpClient,
) {
    private val client = okHttpClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val resolutionClient = client.newBuilder()
        .callTimeout(6, TimeUnit.SECONDS)
        .build()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    companion object {
        val BACKEND_BASE_URL: String
            get() = decodeSecretBytes(
                com.lastwave.app.BuildConfig.LOSSLESS_BACKEND_URL_BYTES,
                com.lastwave.app.BuildConfig.SECRET_MASK_BYTES
            )

        val BACKEND_API_KEY: String
            get() = decodeSecretBytes(
                com.lastwave.app.BuildConfig.LOSSLESS_API_KEY_BYTES,
                com.lastwave.app.BuildConfig.SECRET_MASK_BYTES
            )

        val BACKEND_B_BASE_URL: String
            get() = decodeSecretBytes(
                com.lastwave.app.BuildConfig.BACKEND_B_URL_BYTES,
                com.lastwave.app.BuildConfig.SECRET_MASK_BYTES
            )

        val BACKEND_B_API_KEY: String
            get() = decodeSecretBytes(
                com.lastwave.app.BuildConfig.BACKEND_B_KEY_BYTES,
                com.lastwave.app.BuildConfig.SECRET_MASK_BYTES
            )

        fun decodeSecretBytes(data: ByteArray, mask: ByteArray): String {
            if (data.isEmpty() || mask.isEmpty()) return ""
            val decoded = ByteArray(data.size) { i ->
                (data[i].toInt() xor mask[i % mask.size].toInt()).toByte()
            }
            return String(decoded, Charsets.UTF_8)
        }

        // Quality presets
        const val QUALITY_MAX_HI_RES = 27 // Up to 24-bit / 192 kHz
        const val QUALITY_HI_RES_96 = 7   // Up to 24-bit / 96 kHz
        const val QUALITY_CD_LOSSLESS = 6 // 16-bit / 44.1 kHz FLAC
        const val QUALITY_MP3_320 = 5     // 320 kbps MP3
        const val QUALITY_YOUTUBE = -1    // YouTube Music standard stream

        /**
         * Resolves the order of quality tiers to attempt.
         * If the requested quality fails, it prioritizes the tiers ABOVE it in ascending order,
         * followed by the tiers below it before falling back to YouTube Music.
         */
        fun getQualityAttemptOrder(preferred: Int): List<Int> {
            if (preferred == QUALITY_YOUTUBE) return emptyList()
            val tiersAscending = listOf(
                QUALITY_MP3_320,     // 5
                QUALITY_CD_LOSSLESS, // 6
                QUALITY_HI_RES_96,   // 7
                QUALITY_MAX_HI_RES,  // 27
            )
            val index = tiersAscending.indexOf(preferred)
            if (index == -1) return listOf(QUALITY_MAX_HI_RES, QUALITY_HI_RES_96, QUALITY_CD_LOSSLESS, QUALITY_MP3_320)

            val preferredQuality = tiersAscending[index]
            val above = tiersAscending.subList(index + 1, tiersAscending.size)
            val below = tiersAscending.subList(0, index).reversed()

            return (listOf(preferredQuality) + above + below).distinct()
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

    /**
     * Resolves a high-confidence, verified direct audio stream URL for a given track.
     * Hits both playback backend engines simultaneously in parallel; whichever responds first wins.
     * If no high-confidence exact match is found across both engines, returns null so playback safely falls back to YouTube Music.
     */
    suspend fun resolveStream(
        title: String,
        artist: String,
        expectedDurationSeconds: Int? = null,
        expectedAlbum: String? = null,
        preferredQuality: Int = QUALITY_MAX_HI_RES,
        excludedUrls: Set<String> = emptySet(),
    ): LosslessAudioStream? = withContext(Dispatchers.IO) {
        if (preferredQuality == QUALITY_YOUTUBE || title.isBlank() || artist.isBlank()) return@withContext null

        val hasBackendA = BACKEND_BASE_URL.isNotBlank()
        val hasBackendB = BACKEND_B_BASE_URL.isNotBlank()

        if (!hasBackendA && !hasBackendB) return@withContext null
        if (hasBackendA && !hasBackendB) {
            return@withContext resolveStreamEngineA(
                title, artist, expectedDurationSeconds, expectedAlbum, preferredQuality, excludedUrls
            )
        }
        if (!hasBackendA && hasBackendB) {
            return@withContext resolveStreamEngineB(
                title, artist, expectedDurationSeconds, expectedAlbum, preferredQuality, excludedUrls
            )
        }

        // Both engines available: execute concurrent race
        coroutineScope {
            val resultChannel = Channel<LosslessAudioStream?>(capacity = 2)

            val jobA = async {
                val stream = try {
                    resolveStreamEngineA(title, artist, expectedDurationSeconds, expectedAlbum, preferredQuality, excludedUrls)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(TAG, "Playback backend engine A resolution failed: ${e.message}")
                    null
                }
                resultChannel.send(stream)
            }

            val jobB = async {
                val stream = try {
                    resolveStreamEngineB(title, artist, expectedDurationSeconds, expectedAlbum, preferredQuality, excludedUrls)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(TAG, "Playback backend engine B resolution failed: ${e.message}")
                    null
                }
                resultChannel.send(stream)
            }

            var winner: LosslessAudioStream? = null
            var completedCount = 0

            while (completedCount < 2) {
                val candidateStream = resultChannel.receive()
                completedCount++
                if (candidateStream != null && candidateStream.url !in excludedUrls) {
                    winner = candidateStream
                    jobA.cancel()
                    jobB.cancel()
                    break
                }
            }

            resultChannel.close()
            winner
        }
    }

    private suspend fun resolveStreamEngineA(
        title: String,
        artist: String,
        expectedDurationSeconds: Int?,
        expectedAlbum: String?,
        preferredQuality: Int,
        excludedUrls: Set<String>,
    ): LosslessAudioStream? {
        try {
            val candidate = findBestVerifiedMatchEngineA(
                title = title,
                artist = artist,
                expectedDurationSeconds = expectedDurationSeconds,
                expectedAlbum = expectedAlbum,
            ) ?: return null

            val directStream = fetchTrackStreamUrlEngineA(candidate, preferredQuality, fallback = true)
            if (directStream != null && directStream.url !in excludedUrls) {
                return directStream
            }

            val qualitiesToTry = getQualityAttemptOrder(preferredQuality).filter { it != preferredQuality }
            for (quality in qualitiesToTry) {
                currentCoroutineContext().ensureActive()
                val stream = fetchTrackStreamUrlEngineA(candidate, quality, fallback = true)
                if (stream != null && stream.url !in excludedUrls) return stream
            }
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Backend A resolution failed gracefully: ${e.message}")
            return null
        }
    }

    private suspend fun resolveStreamEngineB(
        title: String,
        artist: String,
        expectedDurationSeconds: Int?,
        expectedAlbum: String?,
        preferredQuality: Int,
        excludedUrls: Set<String>,
    ): LosslessAudioStream? {
        try {
            val candidate = findBestVerifiedMatchEngineB(
                title = title,
                artist = artist,
                expectedDurationSeconds = expectedDurationSeconds,
                expectedAlbum = expectedAlbum,
            ) ?: return null

            val directStream = fetchTrackStreamUrlEngineB(candidate, preferredQuality)
            if (directStream != null && directStream.url !in excludedUrls) {
                return directStream
            }

            val qualitiesToTry = getQualityAttemptOrder(preferredQuality).filter { it != preferredQuality }
            for (quality in qualitiesToTry) {
                currentCoroutineContext().ensureActive()
                val stream = fetchTrackStreamUrlEngineB(candidate, quality)
                if (stream != null && stream.url !in excludedUrls) return stream
            }
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Backend B resolution failed gracefully: ${e.message}")
            return null
        }
    }

    // --- Backend A Implementation ---

    private suspend fun fetchTrackStreamUrlEngineA(
        candidate: BackendATrackItem,
        quality: Int,
        fallback: Boolean,
    ): LosslessAudioStream? {
        val urlBuilder = "$BACKEND_BASE_URL/api/track/${candidate.id}/url".toHttpUrlOrNull()?.newBuilder()
            ?: return null
        urlBuilder.addQueryParameter("quality", quality.toString())
        urlBuilder.addQueryParameter("fallback", fallback.toString())
        urlBuilder.addQueryParameter("title", candidate.title)
        candidate.performer?.name?.takeIf(String::isNotBlank)?.let {
            urlBuilder.addQueryParameter("artist", it)
        }
        if (candidate.duration > 0) {
            urlBuilder.addQueryParameter("duration", candidate.duration.toString())
        }

        val requestBuilder = Request.Builder().url(urlBuilder.build()).get()
        if (BACKEND_API_KEY.isNotBlank()) requestBuilder.addHeader("X-API-Key", BACKEND_API_KEY)

        return try {
            val body = resolutionClient.newCall(requestBuilder.build()).awaitSuccessfulBodyOrNull()
                ?: return null
            val parsed = json.decodeFromString<BackendATrackUrlResponse>(body)
            if (!parsed.success) {
                Log.w(TAG, "Backend A stream response for track ${candidate.id} quality $quality rejected: ${parsed.error.orEmpty()}")
                return null
            }
            val data = parsed.data ?: return null
            val streamUrl = data.url?.takeIf { it.isNotBlank() } ?: return null

            val bitrateKbps = when (data.formatId) {
                QUALITY_MAX_HI_RES -> ((data.bitDepth * data.samplingRate * 2 * 1000) / 1000).toInt()
                QUALITY_HI_RES_96 -> ((data.bitDepth * data.samplingRate * 2 * 1000) / 1000).toInt()
                QUALITY_CD_LOSSLESS -> 1411
                QUALITY_MP3_320 -> 320
                else -> null
            }

            LosslessAudioStream(
                url = streamUrl,
                mimeType = data.mimeType.ifBlank { "audio/flac" },
                bitDepth = data.bitDepth.takeIf { it > 0 } ?: 16,
                samplingRate = data.samplingRate.takeIf { it > 0 } ?: 44.1,
                formatId = data.formatId,
                bitrateKbps = bitrateKbps,
                trackId = candidate.id,
                durationSeconds = data.duration.takeIf { it > 0 } ?: candidate.duration,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Backend A stream fetch for quality $quality failed: ${e.message}")
            null
        }
    }

    private suspend fun findBestVerifiedMatchEngineA(
        title: String,
        artist: String,
        expectedDurationSeconds: Int?,
        expectedAlbum: String?,
    ): BackendATrackItem? {
        val cleanTitle = cleanForSearch(title)
        val cleanArtist = cleanForSearch(artist)

        val individualArtists = artist.split(Regex("""(?i)\s*(?:&|,|\bx\b|feat\.?|ft\.?|featuring|with|\+)\s*"""))
            .map { cleanForSearch(it) }
            .filter { it.isNotBlank() }

        val queries = listOfNotNull(
            "$cleanTitle $cleanArtist".trim().takeIf { it.isNotBlank() },
            individualArtists.firstOrNull()?.let { "$cleanTitle $it".trim() }?.takeIf { it.isNotBlank() && it != "$cleanTitle $cleanArtist" },
            "$cleanArtist $cleanTitle".trim().takeIf { it.isNotBlank() },
            cleanTitle.takeIf { it.isNotBlank() },
            title.trim().takeIf { it.isNotBlank() },
        ).distinct()

        for (query in queries) {
            currentCoroutineContext().ensureActive()
            val urlBuilder = "$BACKEND_BASE_URL/api/search".toHttpUrlOrNull()?.newBuilder() ?: continue
            urlBuilder.addQueryParameter("q", query)
            urlBuilder.addQueryParameter("type", "track")
            urlBuilder.addQueryParameter("limit", "15")

            val reqBuilder = Request.Builder().url(urlBuilder.build()).get()
            if (BACKEND_API_KEY.isNotBlank()) {
                reqBuilder.addHeader("X-API-Key", BACKEND_API_KEY)
            }

            val items = try {
                val body = resolutionClient.newCall(reqBuilder.build()).awaitSuccessfulBodyOrNull()
                if (body == null) emptyList() else {
                    val searchRes = json.decodeFromString<BackendASearchResponse>(body)
                    if (searchRes.success) searchRes.results?.tracks?.items.orEmpty() else emptyList()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Backend A search failed gracefully: ${e.message}")
                emptyList()
            }

            if (items.isEmpty()) continue

            items.asSequence()
                .mapNotNull { item ->
                    verifiedMatchScore(
                        candidateTitle = item.title,
                        candidateVersion = item.version,
                        candidatePerformer = item.performer?.name.orEmpty(),
                        candidateAlbumArtist = item.album?.artist?.name.orEmpty(),
                        candidatePerformersText = item.performers,
                        candidateAlbumTitle = item.album?.title.orEmpty(),
                        candidateDuration = item.duration,
                        title = title,
                        artist = artist,
                        expectedDurationSeconds = expectedDurationSeconds,
                        expectedAlbum = expectedAlbum,
                    )?.let { score -> item to score }
                }
                .sortedByDescending { it.second }
                .firstOrNull()
                ?.first
                ?.let { return it }
        }

        return null
    }

    // --- Backend B Implementation ---

    private fun mapQualityToBackendB(quality: Int): String = when (quality) {
        QUALITY_MAX_HI_RES, QUALITY_HI_RES_96 -> "HI_RES_LOSSLESS"
        QUALITY_CD_LOSSLESS -> "LOSSLESS"
        QUALITY_MP3_320 -> "HIGH"
        else -> "LOSSLESS"
    }

    private suspend fun fetchTrackStreamUrlEngineB(
        candidate: BackendBTrackItem,
        quality: Int,
    ): LosslessAudioStream? {
        val qualityParam = mapQualityToBackendB(quality)
        val urlBuilder = "$BACKEND_B_BASE_URL/track/".toHttpUrlOrNull()?.newBuilder() ?: return null
        urlBuilder.addQueryParameter("id", candidate.id.toString())
        urlBuilder.addQueryParameter("quality", qualityParam)

        val requestBuilder = Request.Builder().url(urlBuilder.build()).get()
        if (BACKEND_B_API_KEY.isNotBlank()) requestBuilder.addHeader("X-API-Key", BACKEND_B_API_KEY)

        return try {
            val body = resolutionClient.newCall(requestBuilder.build()).awaitSuccessfulBodyOrNull() ?: return null
            val parsed = json.decodeFromString<BackendBTrackUrlResponse>(body)
            val data = parsed.data ?: return null

            val manifestB64 = data.manifest?.takeIf { it.isNotBlank() } ?: return null
            val manifestUrl = "data:${data.manifestMimeType ?: "application/dash+xml"};base64,$manifestB64"

            val bitDepth = data.bitDepth ?: when (quality) {
                QUALITY_MAX_HI_RES, QUALITY_HI_RES_96 -> 24
                else -> 16
            }
            val samplingRate = data.sampleRate ?: 44100.0
            val samplingRateKHz = if (samplingRate > 1000.0) samplingRate / 1000.0 else samplingRate

            val resolvedFormatId = when {
                bitDepth > 16 || samplingRateKHz > 48.0 -> QUALITY_MAX_HI_RES
                qualityParam == "HIGH" -> QUALITY_MP3_320
                else -> QUALITY_CD_LOSSLESS
            }

            val bitrateKbps = when (resolvedFormatId) {
                QUALITY_MAX_HI_RES, QUALITY_HI_RES_96 -> ((bitDepth * samplingRateKHz * 2 * 1000) / 1000).toInt()
                QUALITY_CD_LOSSLESS -> 1411
                QUALITY_MP3_320 -> 320
                else -> null
            }

            LosslessAudioStream(
                url = manifestUrl,
                mimeType = data.manifestMimeType ?: "application/dash+xml",
                bitDepth = bitDepth,
                samplingRate = samplingRateKHz,
                formatId = resolvedFormatId,
                bitrateKbps = bitrateKbps,
                trackId = candidate.id,
                durationSeconds = candidate.duration,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Backend B stream fetch failed: ${e.message}")
            null
        }
    }

    private suspend fun findBestVerifiedMatchEngineB(
        title: String,
        artist: String,
        expectedDurationSeconds: Int?,
        expectedAlbum: String?,
    ): BackendBTrackItem? {
        val cleanTitle = cleanForSearch(title)
        val cleanArtist = cleanForSearch(artist)

        val individualArtists = artist.split(Regex("""(?i)\s*(?:&|,|\bx\b|feat\.?|ft\.?|featuring|with|\+)\s*"""))
            .map { cleanForSearch(it) }
            .filter { it.isNotBlank() }

        val queries = listOfNotNull(
            "$cleanTitle $cleanArtist".trim().takeIf { it.isNotBlank() },
            individualArtists.firstOrNull()?.let { "$cleanTitle $it".trim() }?.takeIf { it.isNotBlank() && it != "$cleanTitle $cleanArtist" },
            "$cleanArtist $cleanTitle".trim().takeIf { it.isNotBlank() },
            cleanTitle.takeIf { it.isNotBlank() },
            title.trim().takeIf { it.isNotBlank() },
        ).distinct()

        for (query in queries) {
            currentCoroutineContext().ensureActive()
            val urlBuilder = "$BACKEND_B_BASE_URL/search/".toHttpUrlOrNull()?.newBuilder() ?: continue
            urlBuilder.addQueryParameter("s", query)

            val reqBuilder = Request.Builder().url(urlBuilder.build()).get()
            if (BACKEND_B_API_KEY.isNotBlank()) {
                reqBuilder.addHeader("X-API-Key", BACKEND_B_API_KEY)
            }

            val items = try {
                val body = resolutionClient.newCall(reqBuilder.build()).awaitSuccessfulBodyOrNull()
                if (body == null) emptyList() else {
                    val searchRes = json.decodeFromString<BackendBSearchResponse>(body)
                    searchRes.data?.items.orEmpty()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Backend B search failed gracefully: ${e.message}")
                emptyList()
            }

            if (items.isEmpty()) continue

            items.asSequence()
                .mapNotNull { item ->
                    val primaryArtist = item.artist?.name.orEmpty()
                    val artistsCombined = item.artists.mapNotNull { it.name }.filter(String::isNotBlank).joinToString(" - ")
                    verifiedMatchScore(
                        candidateTitle = item.title,
                        candidateVersion = item.version,
                        candidatePerformer = primaryArtist,
                        candidateAlbumArtist = primaryArtist,
                        candidatePerformersText = artistsCombined,
                        candidateAlbumTitle = item.album?.title.orEmpty(),
                        candidateDuration = item.duration,
                        title = title,
                        artist = artist,
                        expectedDurationSeconds = expectedDurationSeconds,
                        expectedAlbum = expectedAlbum,
                    )?.let { score -> item to score }
                }
                .sortedByDescending { it.second }
                .firstOrNull()
                ?.first
                ?.let { return it }
        }

        return null
    }

    private fun verifiedMatchScore(
        candidateTitle: String,
        candidateVersion: String?,
        candidatePerformer: String,
        candidateAlbumArtist: String,
        candidatePerformersText: String?,
        candidateAlbumTitle: String,
        candidateDuration: Int,
        title: String,
        artist: String,
        expectedDurationSeconds: Int?,
        expectedAlbum: String?,
    ): Int? {
        val matchArtist = cleanForSearch(artist).ifBlank { artist }
        val targetTitle = normalizeTitle(title, matchArtist)
        val candidateTitleNorm = normalizeTitle(candidateTitle, matchArtist)
        if (targetTitle.isBlank()) return null

        val primaryIdentities = listOf(candidatePerformer, candidateAlbumArtist)
            .map(::normalizeText)
            .filter(String::isNotBlank)

        val targetArtists = matchArtist.split(Regex("""(?i)\s*(?:&|,|\bx\b|feat\.?|ft\.?|featuring|with|\+)\s*"""))
            .map(::normalizeText)
            .filter(String::isNotBlank)

        val artistExact = primaryIdentities.any { iden ->
            targetArtists.any { ta -> iden == ta }
        }

        val titleDistance = levenshtein(targetTitle, candidateTitleNorm)
        val isExactMatch = targetTitle == candidateTitleNorm
        val maxFuzz = (targetTitle.length / 5).coerceIn(1, 2)
        val isFuzzyMatch = artistExact && titleDistance <= maxFuzz
        val isDescriptorMatch = artistExact && targetTitle.length >= 4 && candidateTitleNorm.length >= 4 && (
            (candidateTitleNorm.startsWith(targetTitle) && listOf("rap", "song", "theme", "track", "audio", "music").contains(candidateTitleNorm.substring(targetTitle.length).trim())) ||
            (targetTitle.startsWith(candidateTitleNorm) && listOf("rap", "song", "theme", "track", "audio", "music").contains(targetTitle.substring(candidateTitleNorm.length).trim()))
        )

        if (!isExactMatch && !isFuzzyMatch && !isDescriptorMatch) return null

        val targetVariants = identityVariants(title, matchArtist)
        val candidateVariants = identityVariants("$candidateTitle ${candidateVersion.orEmpty()}", matchArtist)
        if (targetVariants != candidateVariants) return null

        if (!isVerifiedArtistMatch(matchArtist, candidatePerformer, candidateAlbumArtist, candidatePerformersText)) return null

        val durationDifference = if (expectedDurationSeconds != null && expectedDurationSeconds > 0) {
            if (candidateDuration <= 0) return null
            kotlin.math.abs(candidateDuration - expectedDurationSeconds).also { if (it > MAX_DURATION_DIFFERENCE_SECONDS) return null }
        } else null

        var score = 1_000 - titleDistance * 50
        if (artistExact) score += 300
        expectedAlbum?.takeIf(String::isNotBlank)?.let { album ->
            if (normalizeTitle(album, "") == normalizeTitle(candidateAlbumTitle, "")) score += 120
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

        // Exact match of any individual artist in multi-artist target (e.g. "Lady Gaga" in "Lady Gaga & Bruno Mars")
        if (primaryIdentities.any { iden -> targetArtists.any { ta -> iden == ta } }) return true

        // Token containment for any individual artist (e.g. "Lady Gaga" in "Lady Gaga, Bruno Mars")
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
