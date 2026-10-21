package com.lastwave.app.data.podcast

import androidx.compose.runtime.Immutable
import com.lastwave.app.playback.MediaType
import com.lastwave.app.playback.PlayableTrack

@Immutable
data class PodcastShow(
    val showId: String,
    val title: String,
    val publisher: String? = null,
    val description: String? = null,
    val artworkUrl: String? = null,
)

@Immutable
data class PodcastEpisode(
    val videoId: String,
    val title: String,
    val showId: String? = null,
    val showTitle: String? = null,
    val description: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
    val publishedLabel: String? = null,
)

@Immutable
data class PodcastShelf(
    val title: String,
    val shows: List<PodcastShow> = emptyList(),
    val episodes: List<PodcastEpisode> = emptyList(),
)

@Immutable
data class PodcastShowPage(
    val show: PodcastShow,
    val episodes: List<PodcastEpisode>,
    val continuation: String? = null,
)

@Immutable
data class PodcastEpisodePage(
    val episodes: List<PodcastEpisode>,
    val continuation: String? = null,
)

@Immutable
data class PodcastProgress(
    val videoId: String,
    val showId: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val completed: Boolean = false,
    val updatedAtMillis: Long = 0L,
) {
    val fraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

fun PodcastEpisode.toPlayableTrack(): PlayableTrack = PlayableTrack(
    title = title,
    artist = showTitle?.takeIf { it.isNotBlank() } ?: "Podcast",
    album = null,
    artworkUrl = artworkUrl,
    videoId = videoId,
    durationMs = durationMs,
    mediaType = MediaType.PODCAST_EPISODE,
    showId = showId,
)
