package com.lastwave.app.widget

import android.media.session.MediaController

object ActiveMediaSessionHolder {
    @Volatile var isPlaybackServiceRunning: Boolean = false
    @Volatile var isAppForeground: Boolean = false
    @Volatile var activeActivityCount: Int = 0
    @Volatile var controller: MediaController? = null
    @Volatile var ownToken: android.media.session.MediaSession.Token? = null
    @Volatile var player: com.lastwave.app.playback.MusicPlayer? = null
    @Volatile var likedSongsManager: com.lastwave.app.data.playlist.LikedSongsManager? = null
    @Volatile var currentArtwork: android.graphics.Bitmap? = null

    fun clear(expected: MediaController) {
        if (controller?.sessionToken == expected.sessionToken) controller = null
    }

    fun clearToken(expected: android.media.session.MediaSession.Token?) {
        if (expected != null && ownToken == expected) ownToken = null
    }
}
