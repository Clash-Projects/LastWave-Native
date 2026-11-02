package com.lastwave.app.data.addon

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bounded range read whose socket and body read are both canceled with its coroutine. */
internal suspend fun Call.awaitHybridRange(range: HybridRange): ByteArray = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) { response.close(); return }
            val bytes = try {
                response.use { res ->
                    if (range.size !in 1..MAX_HYBRID_RANGE_BYTES) throw IOException("Invalid hybrid range size")
                    if (res.code != 206) throw IOException("Hybrid range requires HTTP 206")
                    val returned = RANGE_HEADER.matchEntire(res.header("Content-Range").orEmpty())
                    if (returned == null || returned.groupValues[1].toLongOrNull() != range.start ||
                        returned.groupValues[2].toLongOrNull() != range.end) throw IOException("Hybrid Content-Range mismatch")
                    val body = res.body ?: throw IOException("Empty hybrid range response")
                    if (body.contentLength() != -1L && body.contentLength() != range.size) throw IOException("Hybrid range length mismatch")
                    val source = body.source()
                    val data = source.readByteArray(range.size)
                    if (!source.exhausted()) throw IOException("Hybrid range response too long")
                    data
                }
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
                return
            }
            continuation.resume(bytes)
        }
    })
}

private val RANGE_HEADER = Regex("bytes (\\d+)-(\\d+)/(?:\\d+|\\*)", RegexOption.IGNORE_CASE)
private const val MAX_HYBRID_RANGE_BYTES = 32L * 1024 * 1024
