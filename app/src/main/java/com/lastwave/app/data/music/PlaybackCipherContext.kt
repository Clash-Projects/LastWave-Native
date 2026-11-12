package com.lastwave.app.data.music

import com.lastwave.app.data.ytmusic.YtConnection
import kotlinx.coroutines.CancellationException

/** One player request and its cipher decoder must share this configuration and account. */
internal class PlaybackCipherContext(
    val videoId: String,
    val playerUrl: String,
    val signatureTimestamp: Int,
    private val account: YtConnection,
) {
    /** The player script is not per-video, so a recent context can serve another video on this account. */
    fun forVideo(otherVideoId: String, currentAccount: YtConnection): PlaybackCipherContext? =
        if (currentAccount == account) PlaybackCipherContext(otherVideoId, playerUrl, signatureTimestamp, account) else null

    fun requireMatches(requestedVideoId: String, currentAccount: YtConnection) {
        if (requestedVideoId != videoId || currentAccount != account) {
            throw CancellationException("Playback cipher context changed")
        }
    }

    // Never include the account, its cookies, or cipher material in diagnostics.
    override fun toString(): String = "PlaybackCipherContext(timestampPresent=true)"
}
