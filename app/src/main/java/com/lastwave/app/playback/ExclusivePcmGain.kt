package com.lastwave.app.playback

/** Software gain shared by all PCM formats entering the exclusive USB queue. */
internal object ExclusivePcmGain {
    fun apply(samples: FloatArray, gain: Float) {
        if (!gain.isFinite()) return
        val normalized = gain.coerceIn(0f, 1f)
        if (normalized >= 1f - 1e-6f) return
        for (index in samples.indices) {
            samples[index] = (samples[index] * normalized).coerceIn(-1f, 1f)
        }
    }
}
