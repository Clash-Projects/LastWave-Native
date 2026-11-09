package com.lastwave.app.data.playlist

import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.toGeneratedTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * Likes work the way SimpMusic's do:
 *
 * - The heart is LOCAL only: it adds the song to (or removes it from) Liked
 *   Songs and touches nothing else — not YouTube, not Last.fm. It is shared by
 *   the in-app player and the playback service, so a heart on an external
 *   controller (the OriginOS music card) does exactly what the in-app heart does.
 * - YouTube Music has its own button, shown only while an account is
 *   connected. It reflects the account's real like state (read from YouTube,
 *   not guessed from the local heart) and toggles only that.
 */
@Singleton
class TrackLikeController @Inject constructor(
    private val likedSongsManager: LikedSongsManager,
    private val innerTube: InnerTubeMusicApi,
    private val ytAuth: YtMusicAuthManager,
) {
    val likedTrackKeys: StateFlow<Set<String>> get() = likedSongsManager.likedTrackKeys

    fun isLiked(track: PlayableTrack, likedKeys: Set<String> = likedTrackKeys.value): Boolean =
        track.toGeneratedTrack().isLikedIn(likedKeys)

    /** Flips the local heart. Returns the new state; throws when Liked Songs could not be updated. */
    suspend fun toggle(track: PlayableTrack): Boolean =
        likedSongsManager.toggle(track.toGeneratedTrack())

    /** Idempotent local like for artwork double-tap: it never unlikes. */
    suspend fun like(track: PlayableTrack): Boolean =
        likedSongsManager.like(track.toGeneratedTrack())

    /** Whether the YouTube Music button applies: an account is connected and the song has a YouTube id. */
    fun canUseYouTubeLike(track: PlayableTrack?): Boolean =
        track != null && ytAuth.connection.value.isConnected && youTubeIdOf(track) != null

    /** The account's like state for [track] on YouTube Music; null when unknown. */
    suspend fun youTubeLikeStatus(track: PlayableTrack): Boolean? {
        val videoId = youTubeIdOf(track) ?: return null
        return guard { innerTube.getYouTubeLikeStatus(videoId) }
    }

    /**
     * Adds the YouTube Music like when [currentlyLiked] is false, removes it
     * otherwise (SimpMusic's addToYouTubeLiked / removeFromYouTubeLiked).
     * Returns whether YouTube accepted the change.
     */
    suspend fun setYouTubeLike(track: PlayableTrack, currentlyLiked: Boolean): Boolean {
        val videoId = youTubeIdOf(track) ?: return false
        return guard {
            if (currentlyLiked) innerTube.removeVideoLike(videoId) else innerTube.likeVideo(videoId)
        } == true
    }

    private fun youTubeIdOf(track: PlayableTrack): String? =
        track.videoId?.takeIf { it.isNotBlank() } ?: track.toGeneratedTrack().youtubeVideoIdOrNull()

    private suspend fun <T> guard(block: suspend () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        android.util.Log.w("TrackLikeController", "YouTube like request failed", error)
        null
    }
}
