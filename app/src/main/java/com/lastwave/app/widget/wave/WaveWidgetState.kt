package com.lastwave.app.widget.wave

import android.content.Context
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlayableTrack
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WaveQueueTrack(
    val trackId: String,
    val queueIndex: Int,
    val title: String,
    val artist: String,
    val artworkUri: String? = null,
    val videoId: String? = null,
    /** Only durable local media sources; streaming URLs are resolved again on replay. */
    val playbackUri: String? = null,
)

@Serializable
data class WavePlaylistShortcut(
    val id: String,
    val title: String,
)

/** One durable snapshot shared by every Wave widget size. */
@Serializable
data class WaveWidgetState(
    val trackId: String = "",
    val title: String = "",
    val artist: String = "",
    val artworkUri: String? = null,
    val artworkPath: String? = null,
    val isPlaying: Boolean = false,
    val isLiked: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = 0,
    val nextTracks: List<WaveQueueTrack> = emptyList(),
    val recentTracks: List<WaveQueueTrack> = emptyList(),
    val playlists: List<WavePlaylistShortcut> = defaultShortcuts(),
    val updatedAtEpochMs: Long = 0L,
) {
    val hasTrack: Boolean get() = trackId.isNotBlank() && title.isNotBlank()

    fun progressAt(nowEpochMs: Long = System.currentTimeMillis()): Float {
        if (durationMs <= 0L) return 0f
        val elapsed = if (isPlaying && updatedAtEpochMs > 0L) {
            (nowEpochMs - updatedAtEpochMs).coerceAtLeast(0L)
        } else {
            0L
        }
        return ((positionMs + elapsed).coerceIn(0L, durationMs).toDouble() / durationMs.toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
    }

    companion object {
        internal const val STORE = "lastwave_wave_widget"
        private const val STATE_KEY = "state_v1"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun defaultShortcuts(): List<WavePlaylistShortcut> = listOf(
            WavePlaylistShortcut(WaveWidgetActions.PLAYLIST_LIKED, "Liked"),
            WavePlaylistShortcut(WaveWidgetActions.PLAYLIST_RECENT, "Recent"),
            WavePlaylistShortcut(WaveWidgetActions.PLAYLIST_MOST_PLAYED, "Most played"),
        )

        fun read(context: Context): WaveWidgetState = runCatching {
            val encoded = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .getString(STATE_KEY, null)
                ?: return WaveWidgetState()
            json.decodeFromString<WaveWidgetState>(encoded).sanitized().withRestoredHistory()
        }.getOrDefault(WaveWidgetState())

        fun write(context: Context, state: WaveWidgetState): Boolean = runCatching {
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .edit()
                .putString(STATE_KEY, json.encodeToString(state.sanitized()))
                .commit()
        }.getOrDefault(false)

        fun fromPlayer(
            playerState: MusicPlayerState,
            isLiked: Boolean,
            artworkUri: String? = playerState.current?.artworkUrl,
            artworkPath: String? = null,
            playlists: List<WavePlaylistShortcut> = defaultShortcuts(),
            nowEpochMs: Long = System.currentTimeMillis(),
        ): WaveWidgetState {
            val current = playerState.current ?: return WaveWidgetState(
                playlists = playlists.withDefaults(),
                updatedAtEpochMs = nowEpochMs,
            )
            val currentIndex = playerState.currentIndex.takeIf { it in playerState.queue.indices }
                ?: playerState.queue.indexOf(current)
            val next = if (currentIndex >= 0) {
                (currentIndex + 1..currentIndex + 4).mapNotNull { index ->
                    playerState.queue.getOrNull(index)?.toWaveQueueTrack(index)
                }
            } else {
                emptyList()
            }
            return WaveWidgetState(
                trackId = current.waveTrackId(),
                title = current.title,
                artist = current.artist,
                artworkUri = artworkUri?.takeIf(String::isNotBlank) ?: current.artworkUrl,
                artworkPath = artworkPath,
                isPlaying = playerState.isPlaying,
                isLiked = isLiked,
                positionMs = playerState.positionMs.coerceAtLeast(0L),
                durationMs = playerState.durationMs.coerceAtLeast(0L),
                shuffleEnabled = playerState.shuffleEnabled,
                repeatMode = playerState.repeatMode,
                nextTracks = next,
                recentTracks = listOf(
                    current.toWaveQueueTrack(-1).copy(
                        artworkUri = artworkUri?.takeIf(String::isNotBlank) ?: current.artworkUrl,
                    ),
                ),
                playlists = playlists.withDefaults(),
                updatedAtEpochMs = nowEpochMs,
            ).sanitized()
        }
    }
}

internal fun PlayableTrack.waveTrackId(): String = when {
    !internalTrackId.isNullOrBlank() -> "internal:$internalTrackId"
    !videoId.isNullOrBlank() -> "video:$videoId"
    !playbackUrl.isNullOrBlank() -> "media:${playbackUrl.hashCode()}"
    else -> "song:${title.trim().lowercase()}|${artist.trim().lowercase()}"
}

private fun PlayableTrack.toWaveQueueTrack(index: Int) = WaveQueueTrack(
    trackId = waveTrackId(),
    queueIndex = index,
    title = title,
    artist = artist,
    artworkUri = artworkUrl,
    videoId = videoId,
    playbackUri = playbackUrl?.takeIf { uri ->
        uri.startsWith("/") || uri.startsWith("content://") || uri.startsWith("file://")
    },
)

/** Merge only tracks that were current, never the upcoming queue. */
internal fun WaveWidgetState.withRecentHistory(previous: WaveWidgetState): WaveWidgetState = copy(
    recentTracks = (
        listOfNotNull(asRecentTrack(previous), previous.asRecentTrack()) +
            previous.recentTracks + recentTracks
        ).sanitizedRecentTracks(),
)

/** The app's recorded playback order takes precedence over older widget snapshots. */
internal fun WaveWidgetState.withRecordedHistory(
    recorded: List<WaveQueueTrack>,
    includeCurrent: Boolean = true,
): WaveWidgetState {
    val current = asRecentTrack().takeIf { includeCurrent }?.let { track ->
        val recordedMatch = recorded.firstOrNull { it.sameRecentTrack(track) }
        track.copy(videoId = track.videoId?.takeIf(String::isNotBlank) ?: recordedMatch?.videoId)
    }
    val rememberedTracks = recentTracks.toMutableList()
    val enriched = recorded.map { track ->
        // A remembered track without a video ID can match one known version;
        // consume it once so different versions retain distinct replay IDs.
        val index = rememberedTracks.indexOfFirst { it.sameRecentTrack(track) }
        val remembered = if (index >= 0) rememberedTracks.removeAt(index) else null
        track.copy(
            trackId = remembered?.trackId ?: track.trackId,
            artworkUri = track.artworkUri ?: remembered?.artworkUri,
            videoId = track.videoId?.takeIf(String::isNotBlank) ?: remembered?.videoId,
            playbackUri = remembered?.playbackUri,
        )
    }
    return copy(recentTracks = (listOfNotNull(current) + enriched + recentTracks).sanitizedRecentTracks())
}

/** Migrate old snapshots without moving a stale current track ahead of recorded history. */
internal fun WaveWidgetState.withRestoredHistory(): WaveWidgetState =
    if (recentTracks.isEmpty()) withRecentHistory(WaveWidgetState()) else this

private fun WaveWidgetState.asRecentTrack(previous: WaveWidgetState? = null): WaveQueueTrack? {
    if (!hasTrack) return null
    val remembered = recentTracks.firstOrNull { it.trackId == trackId }
        ?: previous?.recentTracks?.firstOrNull { it.trackId == trackId }
    return WaveQueueTrack(
        trackId = trackId,
        queueIndex = -1,
        title = title,
        artist = artist,
        artworkUri = artworkUri?.takeIf(String::isNotBlank) ?: remembered?.artworkUri,
        videoId = remembered?.videoId ?: trackId.takeIf { it.startsWith("video:") }?.removePrefix("video:"),
        playbackUri = remembered?.playbackUri,
    )
}

private fun List<WaveQueueTrack>.sanitizedRecentTracks(): List<WaveQueueTrack> =
    filter { it.trackId.isNotBlank() && it.title.isNotBlank() }
        .fold(emptyList<WaveQueueTrack>()) { tracks, track ->
            if (tracks.any { it.sameRecentTrack(track) }) tracks else tracks + track
        }
        .take(4)
        .map { it.copy(queueIndex = -1, title = it.title.trim(), artist = it.artist.trim()) }

private fun WaveQueueTrack.sameRecentTrack(other: WaveQueueTrack): Boolean {
    val knownVideo = videoId?.takeIf(String::isNotBlank)
    val otherVideo = other.videoId?.takeIf(String::isNotBlank)
    return if (knownVideo != null && otherVideo != null) knownVideo == otherVideo
    else title.trim().equals(other.title.trim(), ignoreCase = true) &&
        artist.trim().equals(other.artist.trim(), ignoreCase = true)
}

private fun List<WavePlaylistShortcut>.withDefaults(): List<WavePlaylistShortcut> =
    (WaveWidgetState.defaultShortcuts() + this)
        .filter { it.id.isNotBlank() && it.title.isNotBlank() }
        .distinctBy { it.id }
        .take(8)

private fun WaveWidgetState.sanitized(): WaveWidgetState = copy(
    title = title.trim(),
    artist = artist.trim(),
    positionMs = positionMs.coerceAtLeast(0L).let { position ->
        if (durationMs > 0L) position.coerceAtMost(durationMs) else position
    },
    durationMs = durationMs.coerceAtLeast(0L),
    nextTracks = nextTracks
        .filter { it.queueIndex >= 0 && it.title.isNotBlank() }
        .distinctBy { it.queueIndex }
        .take(4),
    recentTracks = recentTracks.sanitizedRecentTracks(),
    playlists = playlists.withDefaults(),
)
