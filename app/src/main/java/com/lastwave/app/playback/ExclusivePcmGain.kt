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

    /**
     * Like [apply], but glides from [from] to [to] over the first [rampFrames]
     * frames instead of jumping. An instant gain step between two chunks is
     * audible as zipper noise while the volume slider is dragged.
     */
    fun applyRamped(samples: FloatArray, from: Float, to: Float, channels: Int, rampFrames: Int) {
        if (!from.isFinite() || !to.isFinite()) return
        val a = from.coerceIn(0f, 1f)
        val b = to.coerceIn(0f, 1f)
        if (a == b || rampFrames <= 0 || channels <= 0) {
            apply(samples, b)
            return
        }
        val frames = samples.size / channels
        for (frame in 0 until frames) {
            val g = if (frame >= rampFrames) b else a + (b - a) * (frame.toFloat() / rampFrames)
            if (g >= 1f - 1e-6f) continue
            val base = frame * channels
            for (c in 0 until channels) {
                samples[base + c] = (samples[base + c] * g).coerceIn(-1f, 1f)
            }
        }
    }
}
