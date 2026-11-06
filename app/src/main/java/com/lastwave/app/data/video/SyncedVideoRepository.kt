package com.lastwave.app.data.video

import android.content.Context
import android.util.Log
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.SyncedVideoStreamBundle
import com.lastwave.app.data.music.TextMatch
import com.lastwave.app.data.music.YouTubeStreamExtractor
import com.lastwave.app.data.music.isSameSongVideo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SyncedVideoRepo"
private const val CACHE_EXPIRY_MS = 3 * 60 * 60 * 1000L // 3 hours
internal const val MIN_VIDEO_CANDIDATE_SCORE = 60
private const val MAX_VIDEO_DURATION_DRIFT_SEC = 90
private val UNRELATED_UPLOAD_WORDS = setOf(
    "bgmi", "pubg", "freefire", "gameplay", "gaming", "montage", "fortnite", "minecraft", "valorant",
    "gta", "amv", "fmv", "edit", "edits", "status", "tiktok", "reels",
)
/** Labels often upload a song under their own channel, without the artist's name. */
private val LABEL_CHANNELS = listOf(
    "tseries", "tips", "saregama", "sonymusic", "zeemusic", "yrf", "records", "entertainment",
)
private val ARTIST_SEPARATOR = Regex("""\s*(?:,|&|/|\bfeat\.?\b|\bft\.?\b|\bx\b|\band\b)\s*""", RegexOption.IGNORE_CASE)

/**
 * Scores a search result as the video for a track, or null when it is not that
 * track at all. The title has to match and a known artist has to appear:
 * "official video" on its own only proves it is somebody's official video.
 */
internal fun scoreVideoCandidate(
    candidateTitle: String,
    candidateArtist: String,
    candidateDurationSec: Int?,
    wantedTitle: String,
    wantedArtist: String,
    expectedDurationMs: Long?,
): Int? {
    val wantedTokens = TextMatch.tokens(TextMatch.baseTitle(wantedTitle))
        .ifEmpty { TextMatch.normalize(wantedTitle).split(' ').filter(String::isNotBlank).toSet() }
    val title = TextMatch.normalize(candidateTitle)
    val words = title.split(' ').toSet()
    if (wantedTokens.isEmpty() || !words.containsAll(wantedTokens)) return null

    // Songs get used as background for these. Naming the song in the title
    // doesn't make the upload its video.
    // A different take (live, remix, cover, sped up…) is another recording,
    // not this song's video, in either direction.
    if (!TextMatch.isSameTake(candidateTitle, wantedTitle, wantedArtist, candidateArtist)) return null
    // Visual intros/outros stretch a video, but not by minutes: past this it
    // is a loop, a compilation or a different song.
    if (TextMatch.isSevereDurationMismatch(expectedDurationMs, candidateDurationSec, MAX_VIDEO_DURATION_DRIFT_SEC)) return null

    val wantedWordsForUploads = TextMatch.normalize(wantedTitle).split(' ').toSet()
    if (UNRELATED_UPLOAD_WORDS.any { it in words && it !in wantedWordsForUploads } || "free fire" in title) return null

    fun compact(value: String) = TextMatch.normalize(value).replace(" ", "")
    val channel = compact(candidateArtist)
    if ("gaming" in channel || "gamer" in channel) return null
    val compactTitle = title.replace(" ", "")
    val fromLabel = LABEL_CHANNELS.any { it in channel }
    var artistOwnsChannel = false
    if (wantedArtist.isNotBlank()) {
        val artists = wantedArtist.split(ARTIST_SEPARATOR).map(::compact).filter { it.length >= 2 }
        artistOwnsChannel = artists.any { it in channel } ||
            (channel.length >= 2 && channel in compact(wantedArtist))
        if (!artistOwnsChannel && !fromLabel && artists.none { it in compactTitle }) return null
    }

    // Matching words and length never qualify on their own: a random upload has
    // those too. It takes the artist's channel, or a title that says it's the video.
    val claimsToBeTheVideo = "official music video" in title || "official video" in title ||
        "music video" in title || "official visualizer" in title || "video song" in title ||
        "full video" in title || "mv" in words || " m v " in " $title " || "vevo" in channel
    if (!artistOwnsChannel && !fromLabel && !claimsToBeTheVideo) return null

    val wantedWords = TextMatch.normalize(wantedTitle).split(' ').toSet()
    fun unwanted(vararg variants: String) = variants.any { it in words && it !in wantedWords }

    var score = 40
    // Anyone can type an artist's name into a title, so that alone can't qualify.
    if (artistOwnsChannel) score += 30 else if (wantedArtist.isNotBlank()) score += 15
    if ("official music video" in title || "official video" in title) score += 50
    else if ("music video" in title || "official visualizer" in title || "video song" in title || "full video" in title) score += 30
    if ("mv" in words || " m v " in " $title ") score += 25
    if ("vevo" in channel) score += 35
    if (fromLabel) score += 20 else if ("official" in channel) score += 15

    if ("lyric" in words || "lyrics" in words) score -= 30
    if ("audio" in words || "topic" in words || candidateArtist.endsWith("- Topic")) score -= 40
    if (unwanted("live")) score -= 50
    if (unwanted("remix")) score -= 70
    if (unwanted("cover", "karaoke", "reaction")) score -= 80
    if (unwanted("mashup", "parody", "tribute")) score -= 90
    if (unwanted("slowed", "reverb", "sped", "8d")) score -= 80
    if (unwanted("teaser", "trailer", "preview", "bts", "short", "shorts")) score -= 100

    if (expectedDurationMs != null && expectedDurationMs > 30_000L &&
        candidateDurationSec != null && candidateDurationSec > 0
    ) {
        val expectedSec = (expectedDurationMs / 1000L).toInt()
        val delta = kotlin.math.abs(candidateDurationSec - expectedSec)
        when {
            delta <= 15 -> score += 35
            delta <= 45 -> score += 15
            delta > 120 -> score -= 80
            delta > 60 -> score -= 40
        }
        if (candidateDurationSec < 60 && expectedSec > 90) score -= 120
    }
    return score
}
private const val ALIGNMENT_BUDGET_MS = 45_000L
private const val SONG_ALIGNMENT_SECONDS = 50
/** Longer than the song window so a long video intro still leaves the song in range. */
private const val VIDEO_ALIGNMENT_SECONDS = 100
private const val FALLBACK_AUDIO_BITRATE = 160_000
private const val OFFSET_PREFS_NAME = "synced_video_offsets"
private const val MAX_SAVED_OFFSETS = 2_000
private const val NO_CONFIDENT_OFFSET = Long.MIN_VALUE

/**
 * Resolves and caches direct video streams for synchronized music video playback.
 * Implements a 2-tier pipeline:
 *  Tier 1: Innertube /next counterpart lookup (official YouTube Music OMV link)
 *  Tier 2: Verified Innertube search fallback with candidate scoring
 * Plus SponsorBlock API offset integration for frame-accurate music intro synchronization.
 */
@Singleton
class SyncedVideoRepository @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
    private val streamExtractor: YouTubeStreamExtractor,
    private val http: OkHttpClient,
    @ApplicationContext private val appContext: Context,
) {
    private val cache = ConcurrentHashMap<String, CachedVideoBundle>()
    private val envelopeReader by lazy { AudioEnvelopeReader(http, appContext.cacheDir) }

    /** An edit's timing never changes, so results outlive the app and a song is measured only once. */
    private val savedOffsets by lazy { appContext.getSharedPreferences(OFFSET_PREFS_NAME, Context.MODE_PRIVATE) }

    private fun offsetKey(bundle: SyncedVideoStreamBundle, audioVideoId: String?): String? =
        audioVideoId?.takeIf { it.isNotBlank() && it != bundle.videoId }?.let { "$it|${bundle.videoId}" }

    /** The offset from an earlier measurement, available before the video starts. */
    fun savedOffsetMs(bundle: SyncedVideoStreamBundle, audioVideoId: String?): Long? {
        val key = offsetKey(bundle, audioVideoId) ?: return null
        return savedOffsets.getLong(key, NO_CONFIDENT_OFFSET).takeIf { it != NO_CONFIDENT_OFFSET }
    }

    private fun saveOffset(key: String, offsetMs: Long?) {
        if (savedOffsets.all.size >= MAX_SAVED_OFFSETS) savedOffsets.edit().clear().apply()
        savedOffsets.edit().putLong(key, offsetMs ?: NO_CONFIDENT_OFFSET).apply()
    }

    /**
     * Music videos are often a different edit from the audio track, so equal
     * timestamps can still look out of sync. Compares the song's audio with the
     * video's own audio to find the real offset, or null when they don't clearly match.
     */
    suspend fun measureContentOffsetMs(bundle: SyncedVideoStreamBundle, audioVideoId: String?): Long? =
        withContext(Dispatchers.IO) {
            val key = offsetKey(bundle, audioVideoId) ?: return@withContext null
            // Also covers "no confident match", so a song that can't be aligned isn't re-downloaded.
            if (savedOffsets.contains(key)) return@withContext savedOffsetMs(bundle, audioVideoId)
            try {
                withTimeoutOrNull(ALIGNMENT_BUDGET_MS) {
                    val songStream = innerTube.resolveAudioStream(audioVideoId!!)
                    val song = envelopeReader.read(
                        url = songStream.url,
                        headers = songStream.requestHeaders,
                        bitrate = songStream.bitrate.takeIf { it > 0 } ?: FALLBACK_AUDIO_BITRATE,
                        seconds = SONG_ALIGNMENT_SECONDS,
                    )
                    val alignmentAudio = if (bundle.clientName == "IOS") {
                        innerTube.resolveVideoAudioForAlignment(bundle.videoId)
                    } else {
                        null
                    }
                    val video = envelopeReader.read(
                        url = alignmentAudio?.url ?: bundle.pairedAudioStreamUrl ?: return@withTimeoutOrNull null,
                        headers = alignmentAudio?.requestHeaders ?: bundle.pairedAudioHeaders,
                        bitrate = alignmentAudio?.bitrate?.takeIf { it > 0 }
                            ?: bundle.pairedAudioBitrate?.takeIf { it > 0 }
                            ?: FALLBACK_AUDIO_BITRATE,
                        seconds = VIDEO_ALIGNMENT_SECONDS,
                    )
                    val offset = estimateConsistentVideoOffsetMs(onsetEnvelope(song), onsetEnvelope(video))
                    Log.i(TAG, "Measured offset for ${bundle.videoId}: $offset ms")
                    saveOffset(key, offset)
                    offset
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, "Could not measure offset for ${bundle.videoId}: ${error.message}")
                null
            }
        }

    private data class CachedVideoBundle(
        val bundle: SyncedVideoStreamBundle,
        val expiresAtMs: Long,
    )

    fun invalidate(videoId: String?) {
        if (!videoId.isNullOrBlank()) {
            // Bundles are keyed by the official video id, which can differ from the resolved one.
            cache.entries.removeIf { (key, cached) -> key == videoId || cached.bundle.videoId == videoId }
        }
    }

    private suspend fun resolveBundle(videoId: String, avoidClientName: String?): SyncedVideoStreamBundle =
        innerTube.resolveVideoStreamBundle(videoId, avoidClientName) ?: streamExtractor.resolveVideoStreamBundle(videoId)

    suspend fun resolveVideo(
        trackTitle: String,
        trackArtist: String,
        videoId: String?,
        expectedDurationMs: Long? = null,
        avoidClientName: String? = null,
    ): Result<SyncedVideoStreamBundle> = withContext(Dispatchers.IO) {
        runCatching {
            // Find the Official Music Video (OMV) ID
            val targetVideoId = findOfficialVideoId(
                trackTitle = trackTitle,
                trackArtist = trackArtist,
                audioTrackVideoId = videoId,
                expectedDurationMs = expectedDurationMs,
            )
                ?: videoId?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Could not find music video for $trackTitle — $trackArtist")

            val now = System.currentTimeMillis()
            cache[targetVideoId]?.let { cached ->
                if (cached.expiresAtMs > now) {
                    return@runCatching cached.bundle
                } else {
                    cache.remove(targetVideoId)
                }
            }

            val bundle = coroutineScope {
                // Fetch SponsorBlock intro skit offset (e.g. 42.5s for Starboy) alongside the stream.
                // The song's own upload shares its timeline, so there is no intro to skip.
                val introOffsetMs = async { if (targetVideoId != videoId) fetchIntroOffsetMs(targetVideoId) else 0L }

                val rawBundle = try {
                    resolveBundle(targetVideoId, avoidClientName)
                } catch (e: Exception) {
                    // If official video extraction failed and we had a different audioTrackVideoId,
                    // try that as fallback
                    if (!videoId.isNullOrBlank() && videoId != targetVideoId) {
                        resolveBundle(videoId, avoidClientName)
                    } else {
                        throw e
                    }
                }

                // A fallback to the song's own upload can't use the official video's intro.
                rawBundle.copy(
                    introOffsetMs = introOffsetMs.await().takeIf { rawBundle.videoId == targetVideoId } ?: 0L,
                    isDirectOmv = rawBundle.videoId != videoId,
                )
            }

            cache[targetVideoId] = CachedVideoBundle(
                bundle = bundle,
                expiresAtMs = now + CACHE_EXPIRY_MS,
            )
            bundle
        }.onFailure { error ->
            Log.w(TAG, "Failed to resolve video stream for $trackTitle: ${error.message}", error)
        }
    }

    suspend fun findOfficialVideoId(
        trackTitle: String,
        trackArtist: String,
        audioTrackVideoId: String? = null,
        expectedDurationMs: Long? = null,
    ): String? = withContext(Dispatchers.IO) {
        // Tier 1: Native Innertube next endpoint counterpart lookup
        if (!audioTrackVideoId.isNullOrBlank()) {
            val counterpartId = runCatching {
                innerTube.findCounterpartVideoId(audioTrackVideoId)
            }.getOrNull()
            // Equal to the track's own id means the track already is the official video.
            if (!counterpartId.isNullOrBlank()) {
                Log.i(TAG, "Found Tier 1 official video for '$trackTitle': $counterpartId")
                return@withContext counterpartId
            }
        }

        // Tier 2: Verified Innertube search fallback
        val cleanTitle = cleanTrackTitle(trackTitle)
        val cleanArtist = trackArtist.takeUnless { it.equals("Unknown artist", ignoreCase = true) }.orEmpty()
        val queries = listOfNotNull(
            "$cleanArtist - $cleanTitle Official Music Video".trim().ifBlank { null },
            "$cleanTitle $cleanArtist official video".trim().ifBlank { null },
            "$cleanTitle $cleanArtist".trim().ifBlank { null },
        )

        for (query in queries) {
            val videoResults = runCatching {
                innerTube.searchVideos(query, limit = 8)
            }.getOrNull().orEmpty()

            val candidates = videoResults.filter { candidate ->
                candidate.videoId.isNotBlank() && isSameSongVideo(
                    candidateTitle = candidate.title,
                    candidateChannel = candidate.artist,
                    candidateSeconds = candidate.durationSeconds,
                    songTitle = cleanTitle,
                    songArtist = cleanArtist,
                    songDurationMs = expectedDurationMs,
                )
            }
            if (candidates.isEmpty()) continue

            val scored = candidates.mapNotNull { track ->
                val score = scoreVideoCandidate(
                    candidateTitle = track.title,
                    candidateArtist = track.artist,
                    candidateDurationSec = track.durationSeconds,
                    wantedTitle = cleanTitle,
                    wantedArtist = cleanArtist,
                    expectedDurationMs = expectedDurationMs,
                ) ?: return@mapNotNull null
                track to if (track.videoId == audioTrackVideoId) score + 10 else score
            }.sortedByDescending { it.second }

            val best = scored.firstOrNull { it.second >= MIN_VIDEO_CANDIDATE_SCORE }?.first
            if (best != null) {
                Log.i(TAG, "Found Tier 2 official video for '$trackTitle': ${best.videoId} (${best.title}) score=${scored.first().second}")
                return@withContext best.videoId
            }
        }
        null
    }

    suspend fun findVideoId(
        trackTitle: String,
        trackArtist: String,
        excludeId: String? = null,
        expectedDurationMs: Long? = null,
    ): String? = findOfficialVideoId(trackTitle, trackArtist, audioTrackVideoId = excludeId, expectedDurationMs = expectedDurationMs)

    suspend fun fetchIntroOffsetMs(videoId: String): Long = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext 0L
        runCatching {
            val request = okhttp3.Request.Builder()
                .url("https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&category=music_offtopic")
                .header("User-Agent", "LastWave-Native/1.0")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching 0L
                val body = response.body?.string() ?: return@runCatching 0L
                val array = org.json.JSONArray(body)
                for (i in 0 until array.length()) {
                    val seg = array.optJSONObject(i) ?: continue
                    val segment = seg.optJSONArray("segment") ?: continue
                    val start = segment.optDouble(0, 0.0)
                    val end = segment.optDouble(1, 0.0)
                    if (start <= 2.5 && end > start) {
                        return@runCatching ((end - start) * 1000.0).toLong()
                    }
                }
                0L
            }
        }.getOrDefault(0L)
    }

    private fun cleanTrackTitle(title: String): String {
        return title
            .replace(Regex("""\s*\(feat\..*?\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(with.*?\)""", RegexOption.IGNORE_CASE), "")
            // Video titles rarely repeat (From "Film"). Version clauses stay: they mean another rendition.
            .replace(Regex("""\s*[(\[]\s*from\s+[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\[feat\..*?\]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*remaster(?:ed)?(?:\s*\d{4})?""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(official\s*(?:music\s*)?video\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\[official\s*(?:music\s*)?video\]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(official\s*audio\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\[official\s*audio\]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(visualizer\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\[visualizer\]""", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
