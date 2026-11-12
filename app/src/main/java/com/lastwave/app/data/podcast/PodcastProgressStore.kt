package com.lastwave.app.data.podcast

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PodcastProgressStore @Inject constructor(
    private val dao: PodcastProgressDao,
) {
    suspend fun resumePositionMs(videoId: String, durationMs: Long): Long {
        val saved = dao.get(videoId) ?: return 0L
        if (saved.completed || saved.positionMs < MIN_RESUME_POSITION_MS) return 0L
        if (durationMs > 0L && saved.positionMs >= completionThreshold(durationMs)) return 0L
        return saved.positionMs
    }

    suspend fun latestIncompleteForShow(showId: String): PodcastProgress? =
        dao.latestIncompleteForShow(showId, MIN_RESUME_POSITION_MS)?.toModel()
    /**
     * Resume positions per episode for one show. Finished episodes are left
     * out: their progress bar resets instead of showing a full bar.
     */
    fun observeForShow(showId: String): Flow<Map<String, PodcastProgress>> =
        dao.observeForShow(showId).map { entities ->
            entities.filterNot { it.completed }.associate { it.videoId to it.toModel() }
        }

    suspend fun save(
        videoId: String,
        showId: String?,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean,
    ) {
        if (videoId.isBlank()) return
        val normalizedDuration = durationMs.coerceAtLeast(0L)
        val normalizedPosition = positionMs.coerceIn(0L, normalizedDuration.takeIf { it > 0L } ?: Long.MAX_VALUE)
        if (!completed && normalizedPosition < MIN_RESUME_POSITION_MS) return
        if (!completed && normalizedDuration > 0L &&
            normalizedPosition >= completionThreshold(normalizedDuration)
        ) {
            return save(videoId, showId, normalizedPosition, normalizedDuration, completed = true)
        }
        dao.upsert(
            PodcastProgressEntity(
                videoId = videoId,
                showId = showId,
                positionMs = normalizedPosition,
                durationMs = normalizedDuration,
                completed = completed,
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun clear() = dao.clear()

    /** Position at (or past) which an episode counts as finished. */
    private fun completionThreshold(durationMs: Long): Long =
        (durationMs * COMPLETION_PERCENT) / 100L

    companion object {
        const val MIN_RESUME_POSITION_MS = 10_000L
        const val COMPLETION_PERCENT = 95L
    }
}
