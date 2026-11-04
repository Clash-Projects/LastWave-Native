package com.lastwave.app.data.addon

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AddonHttpResponse(
    val code: Int,
    val body: String,
    val cacheState: String? = null,
    val timingMs: Map<String, Double> = emptyMap(),
    val elapsedMs: Long = 0,
) {
    val isSuccessful: Boolean get() = code in 200..299
}

/** Cancellation covers both waiting for headers and reading the JSON body. */
internal suspend fun Call.awaitAddonResponse(): AddonHttpResponse = suspendCancellableCoroutine { continuation ->
    val started = System.nanoTime()
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) {
                response.close()
                return
            }
            val result = try {
                response.use {
                    val body = if (it.isSuccessful) it.body?.string().orEmpty() else ""
                    val state = (it.header("X-Stream-Cache") ?: it.header("X-Search-Cache"))
                        ?.takeIf { value -> value in setOf("HIT", "MISS", "COALESCED") }
                    val allowedTimings = setOf("session", "cache", "upstream", "write", "coalesce", "manifest", "init", "key", "plan", "quota", "total", "prepare", "auth", "plan_cache", "plan_write")
                    val timings = Regex("(?:^|,)\\s*([a-z_]+);dur=([0-9.]+)")
                        .findAll(it.header("Server-Timing").orEmpty().take(2048))
                        .mapNotNull { match ->
                            val name = match.groupValues[1]
                            val value = match.groupValues[2].toDoubleOrNull()
                            if (name in allowedTimings && value != null && value.isFinite() && value >= 0) name to value else null
                        }.take(16).toMap()
                    AddonHttpResponse(it.code, body, state, timings, (System.nanoTime() - started) / 1_000_000)
                }
            } catch (e: Exception) {
                continuation.resumeWithException(e)
                return
            }
            continuation.resume(result)
        }
    })
}
