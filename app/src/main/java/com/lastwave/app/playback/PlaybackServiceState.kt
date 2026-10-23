package com.lastwave.app.playback

// Suppress progress ticks while allowing periodic media-session clock resynchronization.
internal fun sameServiceState(previous: MusicPlayerState, current: MusicPlayerState): Boolean =
    previous.current == current.current &&
        previous.queue == current.queue &&
        previous.currentIndex == current.currentIndex &&
        previous.isPlaying == current.isPlaying &&
        previous.isBuffering == current.isBuffering &&
        previous.durationMs == current.durationMs &&
        previous.speed == current.speed &&
        previous.shuffleEnabled == current.shuffleEnabled &&
        previous.repeatMode == current.repeatMode &&
        (if (previous.isPlaying && current.isPlaying)
            previous.positionMs / 5_000 == current.positionMs / 5_000
        else previous.positionMs == current.positionMs)
