package com.lastwave.app.data.addon

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loopback origin for hybrid-v1 addon audio.
 *
 * Encrypted FLAC chunks are fetched straight from the CDN in parallel,
 * decrypted locally, appended in order to a cache file, and served to the
 * player over 127.0.0.1 with Range support. A read waits for bytes that are
 * still downloading, so playback starts after the first segment.
 */
@Singleton
class HybridMediaServer @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
) {
    private val http = okHttpClient.newBuilder()
        // 206 range reads are never cacheable; skip the disk-cache lookup.
        .cache(null)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val sessionSequence = AtomicLong(0)
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val dir: File by lazy { File(context.cacheDir, "hybrid_media").apply { mkdirs() } }
    private var server: Server? = null

    @Synchronized
    private fun ensureStarted(): Int {
        server?.let { return it.listeningPort }
        // Leftovers from a previous process are never reused (token changed).
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        val created = Server()
        created.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        server = created
        return created.listeningPort
    }

    private val prefs by lazy { context.getSharedPreferences("hybrid_media", Context.MODE_PRIVATE) }
    @Volatile private var lastWarmAt = 0L
    @Volatile private var warmJob: Job? = null

    /**
     * Takes the one-time listener start (which also deletes the previous
     * process's cache files) and the CDN TCP/TLS handshake off the tap path.
     * Fire-and-forget and throttled; safe to call on every resolve.
     */
    fun prewarm() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWarmAt < PREWARM_INTERVAL_MS || warmJob?.isActive == true) return
        lastWarmAt = now
        warmJob = scope.launch {
            runCatching { ensureStarted() }
            val origin = prefs.getString(KEY_CDN_ORIGIN, null) ?: return@launch
            val started = SystemClock.elapsedRealtime()
            val code = runCatching {
                // Any complete response leaves a pooled keep-alive connection behind.
                http.newCall(Request.Builder().url("$origin/").head().build()).execute().use { it.code }
            }.getOrDefault(-1)
            Log.i("HybridStartup", "event=cdn_prewarm status=$code durationMs=${SystemClock.elapsedRealtime() - started}")
        }
    }

    private fun rememberCdnOrigin(cdnUrl: String) {
        val origin = runCatching {
            val uri = java.net.URI(cdnUrl)
            if (uri.scheme != "https" || uri.host.isNullOrBlank()) null
            else "https://${uri.host}" + (if (uri.port > 0) ":${uri.port}" else "")
        }.getOrNull() ?: return
        if (prefs.getString(KEY_CDN_ORIGIN, null) != origin) prefs.edit().putString(KEY_CDN_ORIGIN, origin).apply()
    }

    /** Registers [plan] and returns the loopback URL to hand the player, or null on failure. */
    fun urlFor(plan: HybridPlan, cacheName: String): String? = runCatching {
        rememberCdnOrigin(plan.cdnUrl)
        val port = ensureStarted()
        val safe = cacheName.replace(Regex("[^A-Za-z0-9_-]"), "_")
        sessions[safe]?.takeIf { it.failed }?.let { sessions.remove(safe)?.release() }
        if (sessions.size > MAX_SESSIONS) evictOldest()
        val session = sessions.getOrPut(safe) { Session(safe, plan) }
        session.touch()
        session.start()
        "http://127.0.0.1:$port/h/$safe.m4a?token=$token"
    }.onFailure { Log.w(TAG, "urlFor failed (${it.javaClass.simpleName})") }.getOrNull()

    /** Drops the in-memory session and cached bytes for a track (used on playback failure). */
    fun evict(cacheName: String) {
        val safe = cacheName.replace(Regex("[^A-Za-z0-9_-]"), "_")
        sessions.remove(safe)?.release()
    }

    /** Release the loopback listener and pending network work when this owner is discarded. */
    @Synchronized
    internal fun shutdown() {
        sessions.values.forEach { it.release() }
        sessions.clear()
        server?.stop()
        server = null
        scope.cancel()
    }

    private fun evictOldest() {
        sessions.values.sortedBy { it.lastUsed }.take(sessions.size - MAX_SESSIONS / 2).forEach {
            sessions.remove(it.name)?.release()
        }
    }

    private inner class Session(val name: String, private val plan: HybridPlan) {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        private val lock = java.lang.Object()
        private val sequence = sessionSequence.incrementAndGet()
        // A canceled writer must never truncate a replacement session's file.
        // The logical loopback URL stays stable; physical storage is session-owned.
        private val file = File(dir, "${name}_$sequence.m4a")
        private var started = false
        private var sessionJob: Job? = null
        private var startedAt = 0L

        @Volatile var failed = false
        @Volatile var done = false
        @Volatile var total = -1L
        @Volatile var written = 0L
        @Volatile var lastUsed = System.currentTimeMillis()

        fun touch() { lastUsed = System.currentTimeMillis() }

        fun start() {
            synchronized(lock) {
                if (started) return
                started = true
                startedAt = SystemClock.elapsedRealtime()
                span("session_start")
                sessionJob = scope.launch {
                    try {
                        run()
                    } catch (cancelled: CancellationException) {
                        failed = true
                        span("session_cancelled")
                        throw cancelled
                    } catch (failure: Exception) {
                        failed = true
                        Log.w("HybridStartup", "event=session_failed session=$sequence error=${failure.javaClass.simpleName}")
                    } finally {
                        // run() has closed its writer; only this session's path is removed.
                        if (failed) runCatching { file.delete() }
                        synchronized(lock) { lock.notifyAll() }
                    }
                }
            }
        }

        fun release() {
            synchronized(lock) {
                failed = true
                sessionJob?.cancel()
                lock.notifyAll()
            }
            runCatching { file.delete() }
        }

        private fun span(event: String, durationMs: Long? = null) {
            Log.i("HybridStartup", "event=$event session=$sequence elapsedMs=${SystemClock.elapsedRealtime() - startedAt}" +
                (durationMs?.let { " durationMs=$it" } ?: ""))
        }

        private suspend fun run() = coroutineScope {
            val planInit = plan.initRange ?: error("hybrid plan has no init range")
            // The CDN file carries its segment index (sidx) between the init
            // and the first fragment; the plan's initRange stops before it.
            // Without a sidx Media3 treats the fMP4 as unseekable and every
            // seek snaps back to 0. When the segments are contiguous from
            // there, fetch the gap too: the served file then keeps the
            // original layout byte-for-byte, so the sidx offsets stay valid.
            val firstStart = plan.ranges.firstOrNull()?.start ?: (planInit.end + 1)
            val contiguous = plan.ranges.zipWithNext().all { (a, b) -> b.start == a.end + 1 }
            val gap = firstStart - (planInit.end + 1)
            val initRange = if (contiguous && planInit.start == 0L && gap in 1..MAX_INDEX_GAP_BYTES) {
                HybridRange(planInit.start, firstStart - 1)
            } else {
                planInit
            }
            if (plan.encrypted && plan.key.isBlank()) error("encrypted hybrid plan missing decryption key")
            val key = if (plan.key.isNotBlank()) HybridCenc.importContentKey(plan.key) else ByteArray(0)
            val constantIvPlan = if (plan.constantIv.isNotBlank()) {
                runCatching { Base64.decode(plan.constantIv, Base64.DEFAULT) }.getOrDefault(ByteArray(0))
            } else ByteArray(0)

            // Keep the two CDN reads concurrent, but let the extractor see the
            // patched moov/sidx while the first media segment is still arriving.
            val pendingInit = async {
                fetchInit(initRange)?.also { span("init_ready") }
            }
            val pendingFirst = async {
                fetchRange(plan.ranges[0])?.also { span("seg0_ready") }
            }
            val init = pendingInit.await() ?: error("hybrid init fetch failed")
            var ivSize = plan.ivSize
            var constantIv = constantIvPlan
            if (plan.encrypted) {
                val tenc = HybridCenc.patchInitSegment(init)
                ivSize = tenc.ivSize
                if (tenc.constantIv.isNotEmpty()) constantIv = tenc.constantIv
            }
            val decrypt = plan.encrypted && key.isNotEmpty()
            val knownTotal = plan.ranges.fold(initRange.size) { bytes, range -> Math.addExact(bytes, range.size) }
            // Init patching is synchronous and may finish after eviction/cancellation.
            currentCoroutineContext().ensureActive()
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(0)
                // Create the file before exposing Content-Length to loopback readers.
                total = knownTotal
                synchronized(lock) { lock.notifyAll() }
                out.write(init)
                publish(out)
                span("init_publish")

                val seg0 = pendingFirst.await() ?: error("hybrid seg0 fetch failed")
                val decryptStarted = SystemClock.elapsedRealtime()
                if (decrypt) HybridCenc.decryptSegment(seg0, key, ivSize, constantIv, plan.ranges[0].start)
                span("decrypt_done", SystemClock.elapsedRealtime() - decryptStarted)
                currentCoroutineContext().ensureActive()
                out.write(seg0)
                publish(out)
                span("seg0_publish")

                var base = 1
                while (base < plan.ranges.size) {
                    val end = minOf(base + PARALLEL, plan.ranges.size)
                    val batch = (base until end).map { i ->
                        async { i to fetchRange(plan.ranges[i]) }
                    }.awaitAll()
                    for ((i, bytes) in batch) {
                        val chunk = bytes ?: error("hybrid segment fetch failed")
                        if (decrypt) HybridCenc.decryptSegment(chunk, key, ivSize, constantIv, plan.ranges[i].start)
                        currentCoroutineContext().ensureActive()
                        out.write(chunk)
                    }
                    publish(out)
                    base = end
                }
            }
            done = true
            span("session_complete")
        }

        private fun publish(out: RandomAccessFile) {
            written = out.filePointer
            synchronized(lock) { lock.notifyAll() }
        }

        private suspend fun fetchInit(range: HybridRange): ByteArray? {
            // Quick retries: a stalled first attempt must not eat the whole budget.
            for (secs in longArrayOf(3, 3, 20)) {
                withTimeoutOrNull(secs * 1000) { fetchRange(range, retries = 0) }?.let { return it }
            }
            return null
        }

        private suspend fun fetchRange(range: HybridRange, retries: Int = 2): ByteArray? {
            for (attempt in 0..retries) {
                currentCoroutineContext().ensureActive()
                val bytes = try {
                    val req = Request.Builder().url(plan.cdnUrl)
                        .header("Range", "bytes=${range.start}-${range.end}").build()
                    http.newCall(req).awaitHybridRange(range)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    null
                }
                if (bytes != null && bytes.isNotEmpty()) return bytes
                if (attempt < retries) delay(100L * (attempt + 1))
            }
            return null
        }

        /** Blocks until byte [pos] is on disk. Returns bytes readable at [pos], or -1 at end. */
        fun awaitAvailable(pos: Long): Long {
            val deadline = System.currentTimeMillis() + READ_WAIT_MS
            synchronized(lock) {
                while (true) {
                    if (failed) throw IOException("hybrid session failed")
                    if (written > pos) return written - pos
                    if (done && pos >= total) return -1
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) throw IOException("timed out waiting for hybrid bytes")
                    lock.wait(minOf(left, 1000L))
                }
            }
        }

        /** Waits for the file and known total; media bytes may still be downloading. */
        fun awaitTotal(): Long {
            val deadline = System.currentTimeMillis() + READ_WAIT_MS
            synchronized(lock) {
                while (total < 0) {
                    if (failed) throw IOException("hybrid session failed")
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) throw IOException("timed out waiting for hybrid size")
                    lock.wait(minOf(left, 1000L))
                }
            }
            if (failed) throw IOException("hybrid session failed")
            return total
        }

        fun open(start: Long, length: Long): InputStream {
            val raf = RandomAccessFile(file, "r")
            raf.seek(start)
            var pos = start
            val end = start + length
            return object : InputStream() {
                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (pos >= end) return -1
                    val avail = awaitAvailable(pos)
                    if (avail < 0) return -1
                    val n = raf.read(b, off, minOf(len.toLong(), avail, end - pos).toInt())
                    if (n > 0) pos += n
                    return n
                }

                override fun close() { runCatching { raf.close() } }
            }
        }
    }

    private inner class Server : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri.orEmpty()
            val name = Regex("^/h/([A-Za-z0-9_-]+)\\.m4a$").matchEntire(uri)?.groupValues?.get(1)
            val ok = name != null && session.parms["token"] == token
            val s = if (ok) sessions[name] else null
            if (s == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            s.touch()
            val total = try { s.awaitTotal() } catch (e: IOException) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Stream unavailable")
            }
            if (total <= 0) return newFixedLengthResponse(Response.Status.OK, MIME, "")

            val range = session.headers["range"]
            var start = 0L
            var end = total - 1
            if (range != null) {
                val m = Regex("bytes=(\\d*)-(\\d*)").matchEntire(range.trim()) ?: return rangeError(total)
                val first = m.groupValues[1]
                val last = m.groupValues[2]
                if (first.isEmpty()) {
                    val suffix = last.toLongOrNull()?.takeIf { it > 0 } ?: return rangeError(total)
                    start = (total - suffix).coerceAtLeast(0)
                } else {
                    start = first.toLongOrNull() ?: return rangeError(total)
                    if (last.isNotEmpty()) end = minOf(last.toLongOrNull() ?: return rangeError(total), end)
                }
                if (start >= total || end < start) return rangeError(total)
            }
            val length = end - start + 1
            val response = newFixedLengthResponse(
                if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT,
                MIME, s.open(start, length), length,
            )
            response.addHeader("Accept-Ranges", "bytes")
            if (range != null) response.addHeader("Content-Range", "bytes $start-$end/$total")
            return response
        }

        private fun rangeError(total: Long): Response =
            newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "Invalid range")
                .apply { addHeader("Content-Range", "bytes */$total") }
    }

    private companion object {
        const val TAG = "HybridMediaServer"
        const val MIME = "audio/mp4"
        const val PARALLEL = 6
        const val MAX_SESSIONS = 16
        const val READ_WAIT_MS = 30_000L
        /** Upper bound for the init→first-fragment gap we fetch as a segment index. */
        const val MAX_INDEX_GAP_BYTES = 256L * 1024
        /** CloudFront keeps idle keep-alive connections well beyond this. */
        const val PREWARM_INTERVAL_MS = 20_000L
        const val KEY_CDN_ORIGIN = "cdn_origin"
    }
}
