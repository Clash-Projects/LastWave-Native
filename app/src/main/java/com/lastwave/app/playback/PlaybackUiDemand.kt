package com.lastwave.app.playback

/**
 * True only while a UI that needs the 60 ms playhead cadence is on screen
 * (the open full player / lyrics seek bar). Collapsed mini-player screens
 * update at 4 fps, so they do not need to wake the ticker every 60 ms.
 */
internal object PlaybackUiDemand {
    @Volatile
    var needsHighFrequency: Boolean = false
}
