package com.lastwave.app.data.video

private const val PRELOAD_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

/** Preloading spends data and CPU, so it needs recent video use and no battery saver. */
internal fun shouldPreloadVideo(lastVideoUseMs: Long?, nowMs: Long, batterySaverOn: Boolean): Boolean {
    if (batterySaverOn || lastVideoUseMs == null) return false
    return nowMs - lastVideoUseMs in 0..PRELOAD_WINDOW_MS
}
