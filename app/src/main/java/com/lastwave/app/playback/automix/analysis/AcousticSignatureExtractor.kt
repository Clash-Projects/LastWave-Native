package com.lastwave.app.playback.automix.analysis

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import com.lastwave.app.data.lyrics.LyricsRepository
import com.lastwave.app.data.lyrics.LyricsResult
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.automix.api.CamelotKey
import com.lastwave.app.playback.automix.api.DecibelLufs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AcousticSignatureExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val signatureStore: AcousticSignatureStore,
    private val lyricsRepository: dagger.Lazy<LyricsRepository>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Asynchronously pre-analyzes the upcoming track and generates an AcousticSignature.
     */
    fun preAnalyzeTrack(
        track: PlayableTrack,
        streamUrl: String?,
        onComplete: (AcousticSignature) -> Unit = {},
    ) {
        val trackId = track.internalTrackId?.takeIf { it.isNotBlank() }
            ?: track.videoId
            ?: "${track.artist}:${track.title}"
        val durationMs = track.durationMs ?: 180_000L
        val sourceHash = (streamUrl?.hashCode() ?: 0).toString()

        val cached = signatureStore.getSignature(trackId, durationMs, sourceHash)
        if (cached != null) {
            onComplete(cached)
            return
        }

        scope.launch {
            val signature = withTimeoutOrNull(2500L) {
                extractAcousticSignature(track, trackId, durationMs, streamUrl, sourceHash)
            } ?: fallbackSignature(trackId, durationMs, sourceHash)

            signatureStore.putSignature(signature)
            withContext(Dispatchers.Main) {
                onComplete(signature)
            }
        }
    }

    private fun fallbackSignature(trackId: String, durationMs: Long, sourceHash: String): AcousticSignature {
        return AcousticSignature(
            trackId = trackId,
            durationMs = durationMs,
            sourceHash = sourceHash,
            camelotKey = CamelotKey("8B"),
            beatGrid = DownbeatGridEstimator.estimateGrid(
                pcm = FloatArray(0),
                sampleRate = 22050f,
                durationMs = durationMs,
                metadataBpm = 120.0f,
            ),
            introVocalMask = VocalPresenceGateDetector.createVocalMask(
                windowStartMs = 0L,
                windowDurationMs = minOf(durationMs, 45_000L),
                pcm = FloatArray(0),
                sampleRate = 22050f,
                lyrics = null,
            ),
            outroVocalMask = VocalPresenceGateDetector.createVocalMask(
                windowStartMs = maxOf(0L, durationMs - 45_000L),
                windowDurationMs = minOf(durationMs, 45_000L),
                pcm = FloatArray(0),
                sampleRate = 22050f,
                lyrics = null,
            ),
            integratedLufs = DecibelLufs.TARGET,
        )
    }

    private suspend fun extractAcousticSignature(
        track: PlayableTrack,
        trackId: String,
        durationMs: Long,
        streamUrl: String?,
        sourceHash: String,
    ): AcousticSignature = withContext(Dispatchers.IO) {
        // Retrieve word-synced lyrics if available
        val lyricsLines = runCatching {
            val res = lyricsRepository.get().getLyrics(
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationSeconds = (durationMs / 1000).toInt(),
                videoId = track.videoId,
            )
            if (res is LyricsResult.Success && res.isSynced) res.lines else null
        }.getOrNull()

        // Decode intro 45s and outro 45s into 22.05 kHz float PCM
        val introPcm = decodePcmWindow(streamUrl, startOffsetUs = 0L, durationUs = 45_000_000L)
        val outroStartUs = maxOf(0L, (durationMs - 45_000L) * 1000L)
        val outroPcm = decodePcmWindow(streamUrl, startOffsetUs = outroStartUs, durationUs = 45_000_000L)

        // Classify Camelot Key from raw metadata or decoded chroma
        val rawKey = track.rawKeyString()
        var camelotKey = AutoMixCamelotClassifier.parseToCamelotKey(rawKey)
        if (!camelotKey.isValid && introPcm.isNotEmpty()) {
            camelotKey = AutoMixCamelotClassifier.classifyChromaPcm(introPcm)
        }
        if (!camelotKey.isValid) {
            camelotKey = CamelotKey("8B")
        }

        // Estimate beat grid and structural boundaries
        val analysisPcm = if (introPcm.isNotEmpty()) introPcm else outroPcm
        val beatGrid = DownbeatGridEstimator.estimateGrid(
            pcm = analysisPcm,
            sampleRate = 22050f,
            durationMs = durationMs,
            metadataBpm = null,
        )

        // Generate Vocal Activity Masks
        val introVocalMask = VocalPresenceGateDetector.createVocalMask(
            windowStartMs = 0L,
            windowDurationMs = minOf(durationMs, 45_000L),
            pcm = introPcm,
            sampleRate = 22050f,
            lyrics = lyricsLines,
        )

        val outroVocalMask = VocalPresenceGateDetector.createVocalMask(
            windowStartMs = maxOf(0L, durationMs - 45_000L),
            windowDurationMs = minOf(durationMs, 45_000L),
            pcm = outroPcm,
            sampleRate = 22050f,
            lyrics = lyricsLines,
        )

        AcousticSignature(
            trackId = trackId,
            durationMs = durationMs,
            sourceHash = sourceHash,
            camelotKey = camelotKey,
            beatGrid = beatGrid,
            introVocalMask = introVocalMask,
            outroVocalMask = outroVocalMask,
            integratedLufs = DecibelLufs.TARGET,
        )
    }

    private fun decodePcmWindow(url: String?, startOffsetUs: Long, durationUs: Long): FloatArray {
        if (url.isNullOrBlank()) return FloatArray(0)
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(url)
            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    break
                }
            }

            if (audioTrackIndex < 0) {
                extractor.release()
                return FloatArray(0)
            }

            extractor.selectTrack(audioTrackIndex)
            if (startOffsetUs > 0) {
                extractor.seekTo(startOffsetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            // Approximate reading up to 22.05 kHz float PCM samples
            val maxSamples = 22050 * 45 // 45s of mono 22.05kHz
            val pcmFloats = FloatArray(maxSamples)
            val byteBuffer = ByteBuffer.allocateDirect(8192).order(ByteOrder.nativeOrder())
            var sampleIdx = 0

            while (sampleIdx < maxSamples) {
                val bytesRead = extractor.readSampleData(byteBuffer, 0)
                if (bytesRead < 0) break

                val shortCount = bytesRead / 2
                for (s in 0 until shortCount) {
                    if (sampleIdx >= maxSamples) break
                    val pcm16 = byteBuffer.getShort(s * 2)
                    pcmFloats[sampleIdx++] = pcm16 / 32768.0f
                }
                extractor.advance()
            }

            extractor.release()
            if (sampleIdx < maxSamples) pcmFloats.copyOf(sampleIdx) else pcmFloats
        } catch (e: Exception) {
            FloatArray(0)
        }
    }

    private fun PlayableTrack.rawKeyString(): String? {
        return null
    }
}
