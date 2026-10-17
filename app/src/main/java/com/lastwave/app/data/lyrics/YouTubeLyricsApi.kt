package com.lastwave.app.data.lyrics

import com.lastwave.app.data.music.InnerTubeMusicApi
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lyrics tied to the exact playing video rather than to a name: the
 * YouTube Music "Lyrics" tab (plain text) and the video's timed captions
 * (transcript). Neither can return the wrong edit — they are the video.
 */
@Singleton
class YouTubeLyricsApi @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
) {
    /** Plain lyrics from the playing video's Lyrics tab, when it has one. */
    suspend fun fetchMusicTabLyrics(videoId: String?): String? {
        val id = videoId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            innerTube.fetchLyricsTabText(id)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    /** Timed caption lines for the video, in start order. */
    suspend fun fetchCaptionLines(videoId: String?): List<LyricLine>? {
        val id = videoId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            innerTube.fetchTranscriptCues(id)
                ?.map { (timeMs, text) -> LyricLine(timeMs = timeMs, text = text) }
                ?.takeIf { it.isNotEmpty() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }
}
