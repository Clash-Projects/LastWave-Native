package com.lastwave.app.data.music

/**
 * Result of a YouTube Music native radio request (/next).
 *
 * @property tracks The parsed tracks in exact YouTube Music radio sequence.
 * @property continuationToken Token to fetch the subsequent page of radio tracks, if present.
 * @property automixPlaylistId Chained automix playlist ID (e.g. RDAMVM...) to follow when continuations end.
 */
data class YtRadioResult(
    val tracks: List<YouTubeMusicTrack>,
    val continuationToken: String? = null,
    val automixPlaylistId: String? = null,
)
