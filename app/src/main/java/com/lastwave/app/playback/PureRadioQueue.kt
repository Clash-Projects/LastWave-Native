package com.lastwave.app.playback

import com.lastwave.app.data.generate.normalizeTrackText
import com.lastwave.app.playback.resolve.playbackIdentity

/**
 * Pure, unit-testable helpers for maintaining a strict YouTube Music radio queue.
 *
 * Enforces the invariant:
 * The queue after the playing song must equal the exact concatenation of YTM responses
 * in order, minus dedupe removals against current queue and the last ~50 played tracks.
 * No foreign songs, no local re-ranking, no text searches, and no Last.fm mixing.
 */
object PureRadioQueue {

    /**
     * Normalized song key for deduplicating audio/video counterparts of the same track.
     */
    fun normalizedSongKey(title: String, artist: String): String {
        val cleanTitle = title.normalizeTrackText().lowercase()
        val cleanArtist = artist.normalizeTrackText().lowercase()
        return "$cleanTitle|$cleanArtist"
    }

    /**
     * Stable queue key for identity and playback cache.
     */
    fun queueKey(track: PlayableTrack): String =
        track.playbackIdentity()?.cacheKey ?: when {
            !track.playbackUrl.isNullOrBlank() -> "local:${track.playbackUrl}"
            !track.videoId.isNullOrBlank() -> track.videoId
            else -> "query:${track.artist.lowercase()}|${track.title.lowercase()}"
        }

    /**
     * Filters an incoming batch of YTM tracks in exact YTM order, removing only:
     * - The current seed track itself (by videoId or normalized song key)
     * - Tracks already present in the active queue (by videoId, queueKey, or normalized song key)
     * - Tracks played recently (last ~50) (by videoId, queueKey, or normalized song key)
     * - Duplicates within the incoming batch itself
     */
    fun filterStrictYtmRadioBatch(
        incomingTracks: List<PlayableTrack>,
        existingVideoIds: Set<String>,
        existingKeys: Set<String>,
        existingSongKeys: Set<String> = emptySet(),
        recentVideoIds: Set<String> = emptySet(),
        recentKeys: Set<String> = emptySet(),
        recentSongKeys: Set<String> = emptySet(),
        seedVideoId: String? = null,
        seedSongKey: String? = null,
    ): List<PlayableTrack> {
        val seenVideoIdsInBatch = mutableSetOf<String>()
        val seenKeysInBatch = mutableSetOf<String>()
        val seenSongKeysInBatch = mutableSetOf<String>()

        return incomingTracks.filter { track ->
            val vid = track.videoId?.takeIf(String::isNotBlank) ?: return@filter false
            if (vid == seedVideoId) return@filter false

            val key = queueKey(track)
            val songKey = normalizedSongKey(track.title, track.artist)
            if (seedSongKey != null && songKey == seedSongKey) return@filter false

            val isDuplicate = vid in existingVideoIds ||
                vid in recentVideoIds ||
                key in existingKeys ||
                key in recentKeys ||
                songKey in existingSongKeys ||
                songKey in recentSongKeys ||
                !seenVideoIdsInBatch.add(vid) ||
                !seenKeysInBatch.add(key) ||
                !seenSongKeysInBatch.add(songKey)

            !isDuplicate
        }
    }

    /**
     * Validates the queue invariant:
     * The upcoming queue's video IDs must strictly match the ordered deduplicated items
     * from all concatenated YTM responses.
     */
    fun verifyQueueInvariant(
        upcomingQueue: List<PlayableTrack>,
        ytmResponsesInOrder: List<PlayableTrack>,
        excludedVideoIds: Set<String>,
    ): Boolean {
        val seen = excludedVideoIds.toMutableSet()
        val expected = mutableListOf<String>()

        for (track in ytmResponsesInOrder) {
            val vid = track.videoId?.takeIf(String::isNotBlank) ?: continue
            if (seen.add(vid)) {
                expected.add(vid)
            }
        }

        val actual = upcomingQueue.mapNotNull { it.videoId?.takeIf(String::isNotBlank) }
        return actual == expected
    }
}
