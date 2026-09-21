package com.lastwave.app.playback.transition

/**
 * Immutable metadata used by [SmartTransitionSelector] to determine the optimal transition plan.
 *
 * @property trackId Unique identifier for the track.
 * @property durationMs Total duration of the track in milliseconds.
 * @property bpm Estimated or measured beats per minute (e.g. 120.0f).
 * @property loudnessDb Integrated loudness or ReplayGain value in dB.
 * @property energy Perceived acoustic energy level normalized from 0.0f (quiet/ambient) to 1.0f (high energy/dance).
 * @property musicalKey Optional musical key identifier (e.g., "Am", "C", "F#m").
 * @property hookStartMs Millisecond position where the track's chorus/hook begins.
 * @property hookEndMs Millisecond position where the track's chorus/hook ends.
 * @property isSeekable Whether the track supports instantaneous zero-latency seeks (true for local/downloaded).
 */
data class TrackTransitionMetadata(
    val trackId: String,
    val durationMs: Long = 0L,
    val bpm: Float? = null,
    val loudnessDb: Float? = null,
    val energy: Float? = null,
    val musicalKey: String? = null,
    val hookStartMs: Long? = null,
    val hookEndMs: Long? = null,
    val isSeekable: Boolean = true
) {
    val hasBpm: Boolean
        get() = bpm != null && bpm > 0f

    val hasEnergy: Boolean
        get() = energy != null && energy in 0f..1f

    val hasHook: Boolean
        get() = hookStartMs != null && hookEndMs != null && hookEndMs > hookStartMs
}
