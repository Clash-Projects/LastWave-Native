package com.lastwave.app.data.download

import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.ytmusic.YtMusicLibraryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class PlaylistDownloadStage { RESOLVING, DOWNLOADING, COMPLETED, PARTIAL, FAILED, CANCELLED }

data class PlaylistDownloadState(
    val requestKey: String,
    val playlistTitle: String,
    val stage: PlaylistDownloadStage,
    val total: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val message: String,
)

internal data class PlaylistDownloadTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val videoId: String? = null,
) {
    val identity: String
        get() = videoId?.takeIf(String::isNotBlank)?.let { "video:$it" }
            ?: "track:${TrackDownloadManager.makeDownloadKey(title, artist)}"
}

internal data class PlaylistDownloadPlan(
    val tracks: List<PlaylistDownloadTrack>,
    val skipped: Int,
)

internal suspend fun planPlaylistDownloads(
    tracks: List<PlaylistDownloadTrack>,
    isDownloading: (PlaylistDownloadTrack) -> Boolean,
    isDownloaded: suspend (PlaylistDownloadTrack) -> Boolean,
): PlaylistDownloadPlan {
    val seen = HashSet<String>()
    val pending = ArrayList<PlaylistDownloadTrack>(tracks.size)
    var skipped = 0
    for (track in tracks) {
        if (track.title.isBlank() || !seen.add(track.identity)) {
            skipped++
        } else if (isDownloading(track) || isDownloaded(track)) {
            skipped++
        } else {
            pending += track
        }
    }
    return PlaylistDownloadPlan(pending, skipped)
}

/**
 * Application-scoped playlist orchestrator. It resolves complete playlist
 * contents once, then feeds songs through the existing single-track pipeline
 * one at a time. The per-track manager remains the source of truth for files,
 * metadata, artwork, lyrics, notifications, retries and duplicate protection.
 */
@Singleton
class PlaylistDownloadCoordinator @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val ytMusicLibraryManager: YtMusicLibraryManager,
    private val innerTube: InnerTubeMusicApi,
    private val trackDownloadManager: TrackDownloadManager,
    private val applicationScope: CoroutineScope,
) {
    private val activeRequestKeys = ConcurrentHashMap.newKeySet<String>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeTrackKeys = ConcurrentHashMap<String, String>()
    private val _states = MutableStateFlow<Map<String, PlaylistDownloadState>>(emptyMap())
    val states: StateFlow<Map<String, PlaylistDownloadState>> = _states.asStateFlow()
    private val _events = MutableSharedFlow<PlaylistDownloadState>(extraBufferCapacity = 32)
    val events: SharedFlow<PlaylistDownloadState> = _events.asSharedFlow()

    fun downloadLocalPlaylist(playlistId: Long): String {
        val requestKey = localRequestKey(playlistId)
        start(requestKey, "Playlist") { resolveLocal(playlistId) }
        return requestKey
    }

    fun downloadRemotePlaylist(playlistId: String, title: String): String {
        val cleanId = innerTube.extractPlaylistId(playlistId)
        val requestKey = remoteRequestKey(cleanId)
        start(requestKey, title.ifBlank { "Playlist" }) { resolveRemote(cleanId, title) }
        return requestKey
    }

    fun cancelLocalPlaylist(playlistId: Long): Boolean = cancel(localRequestKey(playlistId))

    fun cancel(requestKey: String): Boolean {
        val job = activeJobs[requestKey] ?: return false
        activeTrackKeys[requestKey]?.let(trackDownloadManager::cancelDownload)
        job.cancel()
        return true
    }

    private fun start(
        requestKey: String,
        initialTitle: String,
        resolve: suspend () -> Pair<String, List<PlaylistDownloadTrack>>,
    ) {
        if (!activeRequestKeys.add(requestKey)) {
            _states.value[requestKey]?.let(_events::tryEmit)
            return
        }
        publish(
            PlaylistDownloadState(
                requestKey = requestKey,
                playlistTitle = initialTitle,
                stage = PlaylistDownloadStage.RESOLVING,
                message = "Preparing playlist download…",
            ),
        )
        val job = applicationScope.launch(start = CoroutineStart.LAZY) {
            try {
                val (title, resolvedTracks) = resolve()
                val plan = planPlaylistDownloads(
                    tracks = resolvedTracks,
                    isDownloading = { trackDownloadManager.isDownloading(it.title, it.artist) },
                    isDownloaded = {
                        runCatching { trackDownloadManager.isTrackDownloaded(it.title, it.artist) }
                            .getOrDefault(false)
                    },
                )
                if (plan.tracks.isEmpty()) {
                    publish(
                        PlaylistDownloadState(
                            requestKey = requestKey,
                            playlistTitle = title,
                            stage = PlaylistDownloadStage.COMPLETED,
                            total = resolvedTracks.size,
                            skipped = plan.skipped,
                            message = if (resolvedTracks.isEmpty()) {
                                "No downloadable tracks in this playlist"
                            } else {
                                "Playlist already downloaded"
                            },
                        ),
                    )
                    return@launch
                }

                var completed = 0
                var failed = 0
                var skipped = plan.skipped
                publishProgress(requestKey, title, plan.tracks.size, completed, failed, skipped)
                for (track in plan.tracks) {
                    val accepted = trackDownloadManager.downloadTrack(
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        artworkUrl = track.artworkUrl,
                    )
                    if (!accepted) {
                        skipped++
                        publishProgress(requestKey, title, plan.tracks.size, completed, failed, skipped)
                        continue
                    }
                    val trackKey = TrackDownloadManager.makeDownloadKey(track.title, track.artist)
                    activeTrackKeys[requestKey] = trackKey
                    val result = awaitTerminalTrack(track)
                    activeTrackKeys.remove(requestKey, trackKey)
                    if (result?.isFinished == true) completed++ else failed++
                    publishProgress(requestKey, title, plan.tracks.size, completed, failed, skipped)
                }

                val stage = if (failed == 0) PlaylistDownloadStage.COMPLETED
                else if (completed > 0) PlaylistDownloadStage.PARTIAL
                else PlaylistDownloadStage.FAILED
                val message = when {
                    failed == 0 && completed > 0 -> "Playlist downloaded"
                    failed == 0 -> "Playlist already downloaded"
                    completed > 0 -> "$completed downloaded • $failed failed"
                    else -> "$failed tracks failed"
                }
                publish(
                    PlaylistDownloadState(
                        requestKey = requestKey,
                        playlistTitle = title,
                        stage = stage,
                        total = plan.tracks.size,
                        completed = completed,
                        failed = failed,
                        skipped = skipped,
                        message = message,
                    ),
                )
            } catch (cancelled: CancellationException) {
                publish(
                    (_states.value[requestKey] ?: PlaylistDownloadState(
                        requestKey,
                        initialTitle,
                        PlaylistDownloadStage.CANCELLED,
                        message = "Playlist download cancelled",
                    )).copy(
                        stage = PlaylistDownloadStage.CANCELLED,
                        message = "Playlist download cancelled",
                    ),
                )
            } catch (_: IncompletePlaylistException) {
                publishFailure(requestKey, initialTitle, "Playlist could not be fully loaded")
            } catch (_: Exception) {
                publishFailure(requestKey, initialTitle, "Playlist could not be loaded")
            } finally {
                activeTrackKeys.remove(requestKey)
                activeRequestKeys.remove(requestKey)
                activeJobs.remove(requestKey)
            }
        }
        activeJobs[requestKey] = job
        job.start()
    }

    private suspend fun awaitTerminalTrack(track: PlaylistDownloadTrack): DownloadProgress? {
        val key = TrackDownloadManager.makeDownloadKey(track.title, track.artist)
        while (true) {
            val progress = trackDownloadManager.downloads.value[key]
            if (progress?.isFinished == true || progress?.error != null) return progress
            if (!trackDownloadManager.isDownloading(track.title, track.artist) && progress == null) return null
            delay(100)
        }
    }

    private suspend fun resolveLocal(playlistId: Long): Pair<String, List<PlaylistDownloadTrack>> {
        val playlist = playlistRepository.getById(playlistId)
            ?: (if (playlistId < 0L) ytMusicLibraryManager.loadDetail(playlistId) else null)
            ?: throw IllegalStateException("Playlist unavailable")
        val remoteId = playlist.remotePlaylistId
        if (remoteId != null) return resolveRemote(remoteId, playlist.title)
        return playlist.title to playlist.tracks.map(GeneratedTrack::toDownloadTrack)
    }

    private suspend fun resolveRemote(
        playlistId: String,
        fallbackTitle: String,
    ): Pair<String, List<PlaylistDownloadTrack>> {
        val playlist = innerTube.fetchPlaylist(playlistId)
            ?: throw IllegalStateException("Playlist unavailable")
        if (!playlist.isComplete) throw IncompletePlaylistException()
        return (playlist.title.takeIf(String::isNotBlank) ?: fallbackTitle) to playlist.tracks.map {
            PlaylistDownloadTrack(
                title = it.title,
                artist = it.artist,
                album = it.album,
                artworkUrl = it.artworkUrl,
                videoId = it.videoId,
            )
        }
    }

    private fun publishProgress(
        requestKey: String,
        title: String,
        total: Int,
        completed: Int,
        failed: Int,
        skipped: Int,
    ) = publish(
        PlaylistDownloadState(
            requestKey = requestKey,
            playlistTitle = title,
            stage = PlaylistDownloadStage.DOWNLOADING,
            total = total,
            completed = completed,
            failed = failed,
            skipped = skipped,
            message = if (completed + failed == 0) {
                "Downloading $total tracks"
            } else {
                "Downloading ${completed + failed} of $total"
            },
        ),
    )

    private fun publishFailure(requestKey: String, title: String, message: String) = publish(
        PlaylistDownloadState(
            requestKey = requestKey,
            playlistTitle = title,
            stage = PlaylistDownloadStage.FAILED,
            message = message,
        ),
    )

    private fun publish(state: PlaylistDownloadState) {
        _states.update { it + (state.requestKey to state) }
        _events.tryEmit(state)
    }

    private class IncompletePlaylistException : Exception()

    companion object {
        fun localRequestKey(playlistId: Long) = "local:$playlistId"
        fun remoteRequestKey(playlistId: String) = "remote:$playlistId"
    }
}

private fun GeneratedTrack.toDownloadTrack() = PlaylistDownloadTrack(
    title = name,
    artist = artist,
    album = album,
    artworkUrl = artworkUrl,
    videoId = youtubeVideoIdOrNull(),
)
