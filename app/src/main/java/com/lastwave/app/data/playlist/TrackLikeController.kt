package com.lastwave.app.data.playlist

import com.lastwave.app.data.repository.ScrobbleRepository
import com.lastwave.app.data.ytmusic.YtMusicLibraryManager
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.toGeneratedTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * The player's heart: a like lands in the local Liked Songs playlist, then is
 * mirrored to Last.fm and YouTube Music. Shared by the in-app player and the
 * playback service, so a heart on an external controller (the OriginOS music
 * card) does exactly what the in-app heart does.
 */
@Singleton
class TrackLikeController @Inject constructor(
    private val likedSongsManager: LikedSongsManager,
    private val ytMusicLibraryManager: YtMusicLibraryManager,
    private val scrobbleRepository: ScrobbleRepository,
) {
    val likedTrackKeys: StateFlow<Set<String>> get() = likedSongsManager.likedTrackKeys

    fun isLiked(track: PlayableTrack, likedKeys: Set<String> = likedTrackKeys.value): Boolean =
        track.toGeneratedTrack().key in likedKeys

    /** Throws when Liked Songs could not be updated; the mirrors never do. */
    suspend fun toggle(track: PlayableTrack): LikeChange {
        val liked = likedSongsManager.toggle(track.toGeneratedTrack())
        return mirror(track, liked)
    }

    /** Idempotent like for artwork double-tap: it never unlikes. */
    suspend fun like(track: PlayableTrack): LikeChange {
        likedSongsManager.like(track.toGeneratedTrack())
        return mirror(track, liked = true)
    }

    private suspend fun mirror(track: PlayableTrack, liked: Boolean): LikeChange {
        mirrorLastFmLove(track, liked)
        return LikeChange(liked = liked, youTubeMirrored = mirrorYouTubeLove(track, liked))
    }

    /**
     * YouTube Music mirror of the local like. The local state is the source of
     * truth and already saved, so a failed mirror is reported and left alone —
     * the next toggle can retry it.
     */
    private suspend fun mirrorYouTubeLove(track: PlayableTrack, loved: Boolean): Boolean = try {
        ytMusicLibraryManager.mirrorLikeToYouTube(track.toGeneratedTrack(), loved)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        android.util.Log.w("TrackLikeController", "YouTube like mirror failed", error)
        false
    }

    private suspend fun mirrorLastFmLove(track: PlayableTrack, loved: Boolean) {
        when (val result = scrobbleRepository.setTrackLoved(track.artist, track.title, loved)) {
            ScrobbleRepository.Result.Success,
            ScrobbleRepository.Result.NoSessionKey -> Unit
            is ScrobbleRepository.Result.Failed ->
                android.util.Log.w("TrackLikeController", "Last.fm love sync failed: ${result.message}")
        }
    }
}

data class LikeChange(val liked: Boolean, val youTubeMirrored: Boolean)
