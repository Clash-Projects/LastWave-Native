package com.lastwave.app.playback.automix.analysis

import android.util.Log
import android.util.LruCache
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** Bump when analysis output changes meaning: older files are then ignored, never misread. */
const val ANALYSIS_VERSION = 2

@Serializable
data class VocalSpan(val startMs: Long, val endMs: Long)

/**
 * Analysis of one exact stream. Only the windows a transition uses are
 * decoded: the first and last [AcousticSignatureExtractor.WINDOW_MS].
 * A null field means "not known", never a guessed default.
 */
@Serializable
data class AcousticSignature(
    val version: Int = ANALYSIS_VERSION,
    val key: String,
    val durationMs: Long,
    val intro: WindowAnalysis?,
    val outro: WindowAnalysis?,
    /** From synced lyrics only; null when the song has none. */
    val vocals: List<VocalSpan>?,
) {
    /** Null when unknown. */
    fun hasVocalsBetween(fromMs: Long, toMs: Long): Boolean? =
        vocals?.any { it.endMs > fromMs && it.startMs < toMs }
}

/** Memory LRU over one small JSON file per stream, so a song is decoded once, not every play. */
class AcousticSignatureStore(private val directory: File) {
    private val memory = LruCache<String, AcousticSignature>(64)
    private val json = Json { ignoreUnknownKeys = true }

    fun get(key: String): AcousticSignature? {
        synchronized(memory) { memory.get(key) }?.let { return it }
        val file = fileFor(key)
        if (!file.isFile) return null
        val loaded = runCatching { json.decodeFromString<AcousticSignature>(file.readText()) }
            .getOrNull()
            ?.takeIf { it.version == ANALYSIS_VERSION && it.key == key }
            ?: return null
        synchronized(memory) { memory.put(key, loaded) }
        return loaded
    }

    fun put(signature: AcousticSignature) {
        synchronized(memory) { memory.put(signature.key, signature) }
        runCatching {
            directory.mkdirs()
            fileFor(signature.key).writeText(json.encodeToString(signature))
        }.onFailure { Log.w(TAG, "Could not persist analysis for ${signature.key}", it) }
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(directory, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    private companion object {
        const val TAG = "AutoMixAnalysis"
    }
}
