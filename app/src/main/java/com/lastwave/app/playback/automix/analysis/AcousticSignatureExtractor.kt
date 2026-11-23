package com.lastwave.app.playback.automix.analysis

import android.content.Context
import android.os.Process
import android.util.Log
import com.lastwave.app.data.lyrics.LyricsRepository
import com.lastwave.app.data.lyrics.LyricsResult
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decodes and analyses the intro and outro of a stream once, off the main
 * thread, one stream at a time. Callers poll [signature] / [isSettled]: a
 * stream that fails or times out settles with no signature, and the planner
 * then picks a plain fade instead of inventing a tempo or key.
 */
@Singleton
class AcousticSignatureExtractor @Inject constructor(
    @ApplicationContext context: Context,
    private val lyricsRepository: dagger.Lazy<LyricsRepository>,
) {
    private val store = AcousticSignatureStore(File(context.filesDir, "automix"))
    private val scope = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor { runnable ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "AutoMixAnalysis")
        }.asCoroutineDispatcher(),
    )
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    fun signature(key: String): AcousticSignature? = store.get(key)

    /** True once [key] is analysed or has failed; false while pending or never requested. */
    fun isSettled(key: String): Boolean = key in failed || store.get(key) != null

    fun request(key: String, track: PlayableTrack, input: AnalysisInput) {
        if (key in failed || key in inFlight || store.get(key) != null) return
        if (!inFlight.add(key)) return
        scope.launch {
            try {
                val signature = withTimeoutOrNull(ANALYSIS_TIMEOUT_MS) { analyze(key, track, input) }
                if (signature != null) store.put(signature) else failed += key
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.w(TAG, "Analysis failed for '${track.title}'", error)
                failed += key
            } finally {
                inFlight -= key
            }
        }
    }

    private suspend fun analyze(key: String, track: PlayableTrack, input: AnalysisInput): AcousticSignature? {
        val started = System.currentTimeMillis()
        val signature = PcmWindowDecoder(input).use { decoder ->
            val durationMs = decoder.durationMs.takeIf { it > 0L } ?: track.durationMs ?: return null
            val intro = decoder.decode(0L, WINDOW_MS)?.let { TrackAnalyzer.analyze(it.pcm, it.sampleRate, it.startMs) }
            val outroStart = (durationMs - WINDOW_MS).coerceAtLeast(0L)
            val outro = decoder.decode(outroStart, WINDOW_MS)?.let { TrackAnalyzer.analyze(it.pcm, it.sampleRate, it.startMs) }
            if (intro == null && outro == null) return null
            AcousticSignature(
                key = key,
                durationMs = durationMs,
                intro = intro,
                outro = outro,
                vocals = vocalSpans(track, durationMs),
            )
        }
        Log.i(
            TAG,
            "'${track.title}' in ${System.currentTimeMillis() - started}ms: " +
                "intro=${signature.intro?.describe()} outro=${signature.outro?.describe()} " +
                "vocals=${signature.vocals?.size ?: "unknown"}",
        )
        return signature
    }

    /** Synced lyric lines are the only vocal evidence trusted; audio-only guesses were noise. */
    private suspend fun vocalSpans(track: PlayableTrack, durationMs: Long): List<VocalSpan>? {
        val lyrics = runCatching {
            lyricsRepository.get().getLyrics(
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationSeconds = (durationMs / 1000).toInt(),
                videoId = track.videoId,
            )
        }.getOrNull() as? LyricsResult.Success ?: return null
        if (!lyrics.isSynced) return null
        val lines = lyrics.lines.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }
        return lines.mapIndexed { index, line ->
            val nextStart = lines.getOrNull(index + 1)?.timeMs
            val length = line.durationMs.takeIf { it > 0L }
                ?: nextStart?.let { (it - line.timeMs).coerceIn(500L, MAX_LINE_MS) }
                ?: DEFAULT_LINE_MS
            VocalSpan(line.timeMs, line.timeMs + length)
        }
    }

    private fun WindowAnalysis.describe(): String =
        "[${grid?.let { "%.1fbpm c=%.2f bar=%.2f".format(it.bpm, it.confidence, it.barConfidence) } ?: "no-grid"} " +
            "key=${camelotKey ?: "?"}(${"%.2f".format(keyConfidence)}) " +
            "audible=${audibleStartMs}..${audibleEndMs} $ending${fadeStartMs?.let { "@$it" } ?: ""} $intro]"

    companion object {
        const val WINDOW_MS = 40_000L
        private const val ANALYSIS_TIMEOUT_MS = 30_000L
        private const val MAX_LINE_MS = 6_000L
        private const val DEFAULT_LINE_MS = 3_500L
        private const val TAG = "AutoMixAnalysis"
    }
}
