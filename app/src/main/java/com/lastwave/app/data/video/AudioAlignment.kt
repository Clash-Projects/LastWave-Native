package com.lastwave.app.data.video

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** Spacing of envelope samples; also the resolution of a measured offset. */
internal const val ALIGNMENT_HOP_MS = 50

private const val MIN_OVERLAP_MS = 20_000
private const val MIN_CORRELATION = 0.45f

/** Music repeats, so the best match must clearly beat the best match elsewhere. */
private const val MIN_PEAK_MARGIN = 1.15f
private const val PEAK_EXCLUSION_MS = 1_000
private const val MAX_HALF_DISAGREEMENT_MS = 150L

/**
 * Turns per-hop loudness into onsets: how sharply the sound got louder. Onsets
 * survive different masters and bitrates better than raw loudness does.
 */
internal fun onsetEnvelope(rms: FloatArray): FloatArray {
    val logEnergy = FloatArray(rms.size) { ln(1f + rms[it] * 1_000f) }
    return FloatArray(rms.size) { i ->
        if (i == 0) 0f else (logEnergy[i] - logEnergy[i - 1]).coerceAtLeast(0f)
    }
}

/**
 * Finds where [song] lines up inside [video]. The result is what to add to a song
 * position to get the matching video position, positive when the video has an
 * intro. Returns null unless one match clearly stands out, because no correction
 * is better than a wrong one.
 */
internal fun estimateVideoOffsetMs(song: FloatArray, video: FloatArray): Long? {
    val minOverlap = MIN_OVERLAP_MS / ALIGNMENT_HOP_MS
    if (song.size < minOverlap || video.size < minOverlap) return null

    val minLag = -(song.size - minOverlap)
    val maxLag = video.size - minOverlap
    val correlations = FloatArray(maxLag - minLag + 1) { index ->
        correlationAtLag(song, video, minLag + index)
    }
    val bestIndex = correlations.indices.maxByOrNull { correlations[it] } ?: return null
    val best = correlations[bestIndex]
    if (best < MIN_CORRELATION) return null

    val exclusion = PEAK_EXCLUSION_MS / ALIGNMENT_HOP_MS
    val runnerUp = correlations.indices
        .filter { abs(it - bestIndex) > exclusion }
        .maxOfOrNull { correlations[it] }
        ?: 0f
    if (runnerUp > 0f && best / runnerUp < MIN_PEAK_MARGIN) return null

    return (minLag + bestIndex).toLong() * ALIGNMENT_HOP_MS
}

/**
 * Accepts the whole-song offset only if one half of the song independently lands
 * on it and neither half points elsewhere. A chance match on repeating music rarely
 * confirms itself this way, and a video re-edited partway through gets contradicted.
 * A half may find nothing at all, since official videos often replace part of the
 * song's opening with dialogue or footage sound.
 */
internal fun estimateConsistentVideoOffsetMs(song: FloatArray, video: FloatArray): Long? {
    val whole = estimateVideoOffsetMs(song, video) ?: return null
    val middle = song.size / 2
    val first = estimateVideoOffsetMs(song.copyOfRange(0, middle), video)
    // The second half starts later in the song, so its raw result includes that start time.
    val second = estimateVideoOffsetMs(song.copyOfRange(middle, song.size), video)
        ?.minus(middle.toLong() * ALIGNMENT_HOP_MS)
    val halves = listOfNotNull(first, second)
    val confirmed = halves.isNotEmpty() && halves.all { abs(it - whole) <= MAX_HALF_DISAGREEMENT_MS }
    return whole.takeIf { confirmed }
}

/** Pearson correlation of the overlap when song[i] is compared with video[i + lag]. */
private fun correlationAtLag(song: FloatArray, video: FloatArray, lag: Int): Float {
    val start = maxOf(0, -lag)
    val end = minOf(song.size, video.size - lag)
    val count = end - start
    if (count <= 0) return 0f

    var sumSong = 0f
    var sumVideo = 0f
    for (i in start until end) {
        sumSong += song[i]
        sumVideo += video[i + lag]
    }
    val meanSong = sumSong / count
    val meanVideo = sumVideo / count

    var covariance = 0f
    var varianceSong = 0f
    var varianceVideo = 0f
    for (i in start until end) {
        val a = song[i] - meanSong
        val b = video[i + lag] - meanVideo
        covariance += a * b
        varianceSong += a * a
        varianceVideo += b * b
    }
    val denominator = sqrt(varianceSong * varianceVideo)
    return if (denominator > 0f) covariance / denominator else 0f
}
