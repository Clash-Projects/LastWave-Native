package com.lastwave.app.data.music

import kotlin.math.abs

private const val SAME_LENGTH_TOLERANCE_SECONDS = 20

/** Shorter artist words ("DJ", "The") turn up in too many unrelated titles to count. */
private const val MIN_ARTIST_WORD_LENGTH = 4

/**
 * Whether a search result is really this song. Popularity and "official video"
 * wording say nothing about that, so this gate runs before any scoring: the result
 * must carry the full song title as whole words, because video titles add extras but
 * keep the song's name. Songs can share a title, so it must also name the artist or
 * run about as long as the song, since labels often upload under their own channel.
 */
internal fun isSameSongVideo(
    candidateTitle: String,
    candidateChannel: String,
    candidateSeconds: Int?,
    songTitle: String,
    songArtist: String,
    songDurationMs: Long?,
): Boolean {
    val wantedTitle = TextMatch.normalize(songTitle)
    if (wantedTitle.isBlank()) return false
    val title = TextMatch.normalize(candidateTitle)
    if (" $wantedTitle " !in " $title ") return false

    val candidateWords = (title.split(' ') + TextMatch.normalize(candidateChannel).split(' ')).toSet()
    val namesArtist = TextMatch.normalize(songArtist).split(' ')
        .any { it.length >= MIN_ARTIST_WORD_LENGTH && it in candidateWords }
    if (namesArtist) return true

    if (candidateSeconds == null || candidateSeconds <= 0 || songDurationMs == null || songDurationMs <= 0) return false
    return abs(candidateSeconds - (songDurationMs / 1_000L).toInt()) <= SAME_LENGTH_TOLERANCE_SECONDS
}
