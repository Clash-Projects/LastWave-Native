package com.lastwave.app.playback

import com.lastwave.app.data.music.YtRadioResult

/**
 * Pure planning and execution helper for YouTube Music radio queue refills.
 *
 * Invariant:
 * Every refill fetches successive pages (continuation -> automix -> re-seed from last song)
 * until at least [TARGET_REFILL_SONGS] (~50) new unique songs are collected, or all sources
 * are exhausted, with a hard cap of [MAX_PAGE_REQUESTS_PER_REFILL] (4) page fetches per refill.
 * All tracks are preserved in exact YouTube Music order, deduplicated against existing queue
 * and recent play history.
 */
object RadioRefillPlanner {
    const val TARGET_REFILL_SONGS = 50
    const val MAX_PAGE_REQUESTS_PER_REFILL = 4

    sealed interface RefillFetchSource {
        data class Continuation(val token: String) : RefillFetchSource
        data class Automix(val playlistId: String) : RefillFetchSource
        data class Reseed(
            val seedVideoId: String,
            val seedTitle: String,
            val seedArtist: String,
        ) : RefillFetchSource
    }

    data class RefillExecutionResult(
        val tracksToAdd: List<PlayableTrack>,
        val nextContinuationToken: String?,
        val nextAutomixPlaylistId: String?,
        val pagesFetched: Int,
        val isExhausted: Boolean,
        val usedSeedVideoIds: Set<String>,
    )

    /**
     * Executes the multi-page refill loop.
     *
     * @param initialQueue Current tracks in the active playback queue.
     * @param initialContinuationToken Current active continuation token, if any.
     * @param initialAutomixPlaylistId Current automix playlist ID, if any.
     * @param initialUsedSeeds Set of video IDs already used as radio seeds.
     * @param recentHistoryKeys Video IDs and queue keys from recent playback history (~last 50).
     * @param targetNewSongs Minimum target number of new unique songs to add (default 50).
     * @param maxPages Hard upper bound on page requests for this refill (default 4).
     * @param isStale Lambda returning true if the playback generation changed or radio is inactive.
     * @param fetchSource Suspend lambda executing the network fetch for a given [RefillFetchSource].
     */
    suspend fun executeRefill(
        initialQueue: List<PlayableTrack>,
        initialContinuationToken: String?,
        initialAutomixPlaylistId: String?,
        initialUsedSeeds: Set<String> = emptySet(),
        recentHistoryKeys: Set<String> = emptySet(),
        targetNewSongs: Int = TARGET_REFILL_SONGS,
        maxPages: Int = MAX_PAGE_REQUESTS_PER_REFILL,
        isStale: () -> Boolean = { false },
        fetchSource: suspend (RefillFetchSource) -> YtRadioResult?,
    ): RefillExecutionResult {
        if (isStale()) {
            return RefillExecutionResult(
                tracksToAdd = emptyList(),
                nextContinuationToken = initialContinuationToken,
                nextAutomixPlaylistId = initialAutomixPlaylistId,
                pagesFetched = 0,
                isExhausted = false,
                usedSeedVideoIds = initialUsedSeeds,
            )
        }

        val knownVideoIds = initialQueue.mapNotNullTo(mutableSetOf()) { it.videoId }
        val knownKeys = initialQueue.mapTo(mutableSetOf()) { PureRadioQueue.queueKey(it) }
        val knownSongKeys = initialQueue.mapTo(mutableSetOf()) {
            PureRadioQueue.normalizedSongKey(it.title, it.artist)
        }
        val usedSeeds = initialUsedSeeds.toMutableSet()

        var currentToken: String? = initialContinuationToken?.takeIf(String::isNotBlank)
        var currentAutomix: String? = initialAutomixPlaylistId?.takeIf(String::isNotBlank)

        val accumulatedTracks = mutableListOf<PlayableTrack>()
        var pagesFetched = 0
        var isExhausted = false

        while (accumulatedTracks.size < targetNewSongs && pagesFetched < maxPages) {
            if (isStale()) {
                return RefillExecutionResult(
                    tracksToAdd = emptyList(),
                    nextContinuationToken = currentToken,
                    nextAutomixPlaylistId = currentAutomix,
                    pagesFetched = pagesFetched,
                    isExhausted = false,
                    usedSeedVideoIds = usedSeeds,
                )
            }

            val source: RefillFetchSource = when {
                !currentToken.isNullOrBlank() -> {
                    RefillFetchSource.Continuation(currentToken)
                }
                !currentAutomix.isNullOrBlank() -> {
                    val automix = currentAutomix
                    currentAutomix = null
                    RefillFetchSource.Automix(automix)
                }
                else -> {
                    val lastTrack = (accumulatedTracks.asReversed().asSequence() + initialQueue.asReversed().asSequence())
                        .firstOrNull { !it.videoId.isNullOrBlank() && it.videoId !in usedSeeds }
                    val seedId = lastTrack?.videoId
                    if (seedId == null) {
                        isExhausted = true
                        break
                    }
                    usedSeeds.add(seedId)
                    RefillFetchSource.Reseed(
                        seedVideoId = seedId,
                        seedTitle = lastTrack.title,
                        seedArtist = lastTrack.artist,
                    )
                }
            }

            pagesFetched++
            val result = fetchSource(source)

            if (isStale()) {
                return RefillExecutionResult(
                    tracksToAdd = emptyList(),
                    nextContinuationToken = currentToken,
                    nextAutomixPlaylistId = currentAutomix,
                    pagesFetched = pagesFetched,
                    isExhausted = false,
                    usedSeedVideoIds = usedSeeds,
                )
            }

            if (result != null && result.tracks.isNotEmpty()) {
                currentToken = result.continuationToken?.takeIf(String::isNotBlank)
                if (!result.automixPlaylistId.isNullOrBlank()) {
                    currentAutomix = result.automixPlaylistId
                }

                val incomingPlayable = result.tracks.map { it.toPlayableTrack() }
                val (seedVid, seedSongKey) = when (source) {
                    is RefillFetchSource.Reseed -> source.seedVideoId to PureRadioQueue.normalizedSongKey(source.seedTitle, source.seedArtist)
                    else -> null to null
                }

                val filtered = PureRadioQueue.filterStrictYtmRadioBatch(
                    incomingTracks = incomingPlayable,
                    existingVideoIds = knownVideoIds,
                    existingKeys = knownKeys,
                    existingSongKeys = knownSongKeys,
                    recentVideoIds = recentHistoryKeys,
                    recentKeys = recentHistoryKeys,
                    seedVideoId = seedVid,
                    seedSongKey = seedSongKey,
                )

                for (track in filtered) {
                    accumulatedTracks.add(track)
                    track.videoId?.let { knownVideoIds.add(it) }
                    knownKeys.add(PureRadioQueue.queueKey(track))
                    knownSongKeys.add(PureRadioQueue.normalizedSongKey(track.title, track.artist))
                }
            } else {
                if (source is RefillFetchSource.Continuation) {
                    currentToken = null
                } else if (source is RefillFetchSource.Automix) {
                    currentAutomix = null
                } else if (source is RefillFetchSource.Reseed) {
                    isExhausted = true
                    break
                }
            }
        }

        return RefillExecutionResult(
            tracksToAdd = accumulatedTracks,
            nextContinuationToken = currentToken,
            nextAutomixPlaylistId = currentAutomix,
            pagesFetched = pagesFetched,
            isExhausted = isExhausted,
            usedSeedVideoIds = usedSeeds,
        )
    }
}
