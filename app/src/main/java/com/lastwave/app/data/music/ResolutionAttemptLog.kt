package com.lastwave.app.data.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.util.concurrent.TimeoutException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * One resolve attempt. Child coroutines inherit it, so each client, probe,
 * and fallback can append a line without sharing a signed URL.
 */
internal class ResolutionAttemptLog : AbstractCoroutineContextElement(ResolutionAttemptLog) {
    private val lines = ArrayList<String>(8)

    fun record(entry: String) {
        val safe = redactResolutionLog(entry)
        if (safe.isBlank()) return
        synchronized(lines) {
            if (lines.size < MAX_LINES) lines += safe
        }
    }

    fun summary(): String = synchronized(lines) {
        if (lines.isEmpty()) "none" else lines.joinToString(" | ")
    }

    companion object : CoroutineContext.Key<ResolutionAttemptLog> {
        private const val MAX_LINES = 24
    }
}

private val URL_IN_TEXT = Regex("""https?://\S+""")
private val SECRET_IN_TEXT = Regex(
    """(?i)\b(cookie|authorization|pot|po_token|signature|sig|n)=([^&\s]+)""",
)

/** Drops stream URLs and request secrets before anything is written to logcat. */
internal fun redactResolutionLog(text: String): String =
    text.replace(URL_IN_TEXT, "[url]")
        .replace(SECRET_IN_TEXT) { "${it.groupValues[1]}=[redacted]" }
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(220)

/** User or parent cancellation. A resolve timeout is not one of these. */
internal fun Throwable.isCooperativeCancellation(): Boolean =
    this is CancellationException && this !is TimeoutCancellationException

/**
 * A miss the player can show and recover from: network, HTTP, extractor,
 * or a bounded resolve timeout. Programming errors stay outside this set.
 */
internal fun Throwable.isExpectedAudioResolutionFailure(): Boolean {
    if (isCooperativeCancellation()) return false
    return generateSequence(this) { it.cause }.take(8).any { cause ->
        cause is IOException ||
            cause is TimeoutCancellationException ||
            cause is TimeoutException ||
            cause is kotlinx.serialization.SerializationException
    }
}

internal fun resolutionFailureDetail(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }
        .take(6)
        .joinToString("<-") { it.javaClass.simpleName }
    return "error=$chain message=${redactResolutionLog(error.message.orEmpty())}"
}

/**
 * An [kotlinx.coroutines.async] child of a [kotlinx.coroutines.SupervisorJob] reports
 * a failure here even when a caller is also awaiting it. Expected audio misses
 * are logged and contained. Anything else is rethrown so the process handler
 * still sees a real defect.
 */
internal fun containedAudioResolutionFailure(tag: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, error ->
        if (error.isCooperativeCancellation()) return@CoroutineExceptionHandler
        if (error.isExpectedAudioResolutionFailure()) {
            runCatching {
                android.util.Log.w(
                    tag,
                    "[RESOLVE_CONTAINED] error=${error.javaClass.simpleName} ${redactResolutionLog(error.message.orEmpty())}",
                )
            }
            return@CoroutineExceptionHandler
        }
        throw error
    }

/**
 * Set by the player around its stream resolves. The deep probe past the first
 * MiB cost 0.25-2 s before every first audio; the player already recovers a
 * rejected URL at the same position, so the probe runs after the URL is used.
 */
internal object DeferredStreamProbe : CoroutineContext.Element, CoroutineContext.Key<DeferredStreamProbe> {
    override val key: CoroutineContext.Key<*> get() = this
}
