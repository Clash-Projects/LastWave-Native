package com.lastwave.app.playback

/**
 * True only while synced lyrics are on screen, the one UI that needs the
 * 60 ms playhead. Seek bars (mini and full player) update at 4 fps.
 */
internal object PlaybackUiDemand {
    @Volatile
    var needsHighFrequency: Boolean = false
}
