package com.lastwave.app.data.addon

/**
 * What the lossless resolver needs from a backend: a catalog search and a
 * stream for one of its hits. Implemented by HTTP addons ([AddonClient]) and
 * by the user's own TIDAL account ([com.lastwave.app.data.tidal.TidalSource]).
 */
interface LosslessSource {
    suspend fun search(
        query: String,
        quality: String,
        atmos: Boolean = false,
        warmTarget: String? = null,
    ): Result<List<AddonTrack>>

    suspend fun stream(
        trackId: String,
        quality: String,
        atmos: Boolean = false,
        isDownload: Boolean = false,
    ): Result<AddonStream>

    /** Optional server-side warm-up; true only when the backend accepted it. */
    suspend fun prewarm(trackId: String, quality: String, atmos: Boolean): Boolean = false
}
