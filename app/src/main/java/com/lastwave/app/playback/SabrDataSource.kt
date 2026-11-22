package com.lastwave.app.playback

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.lastwave.app.data.music.InnerTubeXStreamExtractor
import com.lastwave.app.data.music.InnerTubeXStreamExtractor.SabrPiece
import com.lastwave.app.data.music.InnerTubeXStreamExtractor.SabrTrack
import com.lastwave.app.data.music.SABR_URL_PREFIX
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.TreeMap

/**
 * Serves `lastwave-sabr://` URIs (YouTube's SABR-only audio, which Media3
 * cannot fetch itself) and hands every other URI to [fallback].
 */
class SabrDataSource(
    private val sessions: SabrSessions,
    private val fallback: DataSource,
) : DataSource {
    private var delegate: DataSource? = null
    private var session: SabrSession? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()

    override fun addTransferListener(transferListener: TransferListener) {
        fallback.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        if (!dataSpec.uri.toString().startsWith(SABR_URL_PREFIX)) {
            delegate = fallback
            return fallback.open(dataSpec)
        }
        val opened = sessions.get(dataSpec.uri.toString())
            ?: throw IOException("SABR stream expired: ${dataSpec.uri}")
        session = opened
        position = dataSpec.position
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            opened.contentLength != null -> (opened.contentLength - position).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        delegate?.let { return it.read(buffer, offset, length) }
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val wanted = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val read = checkNotNull(session).read(position, buffer, offset, wanted)
        if (read == C.RESULT_END_OF_INPUT) return read
        position += read
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
        return read
    }

    override fun getUri(): Uri? = delegate?.uri ?: uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
            session = null
            uri = null
        }
    }
}

/**
 * Open SABR streams by URI. Kept across DataSource instances so Media3's
 * reopen after a seek or a retry finds the bytes already spooled.
 */
class SabrSessions(
    private val extractor: InnerTubeXStreamExtractor,
    private val directory: File,
    /** Playhead of [videoId] when it is the playing track, else 0. */
    private val playbackPositionMs: (videoId: String) -> Long,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = LinkedHashMap<String, SabrSession>(8, 0.75f, true)

    init {
        // Spool files from a previous process are unreachable now.
        directory.deleteRecursively()
        directory.mkdirs()
    }

    fun get(url: String): SabrSession? = synchronized(sessions) {
        sessions[url] ?: run {
            val track = extractor.sabrTrack(url) ?: return null
            // Playing + next + crossfade standby; older ones are done.
            while (sessions.size >= MAX_SESSIONS) {
                val eldest = sessions.entries.first()
                sessions.remove(eldest.key)
                eldest.value.close()
            }
            SabrSession(
                track = track,
                file = File(directory, "${url.substringAfterLast('/')}.part"),
                scope = scope,
                open = { initialMs -> extractor.sabrChunks(track, initialMs) { playbackPositionMs(track.videoId) } },
            ).also { sessions[url] = it }
        }
    }

    private companion object {
        const val MAX_SESSIONS = 4
    }
}

/**
 * Spools one SABR stream to a file at the byte offsets its chunks carry, so
 * reads and seeks work as on a progressive file. A read the running
 * producer will not reach soon restarts SABR at the matching media time.
 */
class SabrSession(
    private val track: SabrTrack,
    private val file: File,
    private val scope: CoroutineScope,
    private val open: (initialPlayerTimeMs: Long) -> kotlinx.coroutines.flow.Flow<SabrPiece>,
) {
    val contentLength: Long? = track.contentLength

    private val lock = Object()
    private val spool = RandomAccessFile(file, "rw")
    /** Spooled byte ranges, start -> end exclusive; disjoint and merged. */
    private val ranges = TreeMap<Long, Long>()
    /** Media end time per chunk end byte: where a restart can resume exactly. */
    private val endTimes = TreeMap<Long, Long>()

    private var producer: Job? = null
    private var generation = 0
    private var targetByte = 0L
    private var startMs = 0L
    private var frontier = -1L
    private var sawMedia = false
    private var finished = false
    private var failure: IOException? = null
    private var closed = false
    private var lastProgressAt = SystemClock.elapsedRealtime()

    /** Blocks until [position] is spooled; returns bytes read or END_OF_INPUT. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        synchronized(lock) { readLocked(position, buffer, offset, length) }

    private fun readLocked(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        lastProgressAt = SystemClock.elapsedRealtime()
        while (true) {
            if (closed) throw IOException("SABR session closed")
            if (contentLength != null && position >= contentLength) return C.RESULT_END_OF_INPUT
            val end = ranges.floorEntry(position)?.value
            if (end != null && end > position) {
                val count = minOf(length.toLong(), end - position).toInt()
                spool.seek(position)
                spool.readFully(buffer, offset, count)
                return count
            }
            if (finished && position >= (ranges.lastEntry()?.value ?: 0L)) {
                // Unknown length: the producer's end is the file's end.
                if (contentLength == null) return C.RESULT_END_OF_INPUT
            }
            failure?.let { throw it }
            ensureProducer(position)
            try {
                lock.wait(WAIT_SLICE_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("SABR read interrupted")
            }
            if (SystemClock.elapsedRealtime() - lastProgressAt > READ_TIMEOUT_MS) {
                throw IOException("SABR stream stalled for ${track.videoId}")
            }
        }
    }

    /** Keeps the running producer when it is about to deliver [position]. */
    private fun ensureProducer(position: Long) {
        val running = producer?.isActive == true && !finished
        if (running && position >= targetByte && position <= maxOf(frontier, targetByte) + RESTART_GAP_BYTES) return
        // A spooled chunk ending exactly here knows its media time.
        val resumeMs = endTimes[position] ?: if (position == 0L) 0L else estimateMs(position)
        start(position, resumeMs)
    }

    private fun estimateMs(position: Long): Long {
        val duration = track.durationMs
        val linear = if (duration != null && contentLength != null) position * duration / contentLength else null
        val known = endTimes.floorEntry(position)?.value ?: 0L
        return ((linear ?: known) - SEEK_PREROLL_MS).coerceAtLeast(0L)
    }

    private fun start(position: Long, fromMs: Long) {
        producer?.cancel()
        val current = ++generation
        targetByte = position
        startMs = fromMs
        frontier = -1L
        sawMedia = false
        finished = false
        failure = null
        Log.i(TAG, "start videoId=${track.videoId} byte=$position ms=$fromMs")
        producer = scope.launch {
            try {
                open(fromMs).collect { piece -> append(current, piece) }
                synchronized(lock) {
                    if (current == generation) {
                        finished = true
                        lock.notifyAll()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "producer failed videoId=${track.videoId}", error)
                synchronized(lock) {
                    if (current == generation) {
                        failure = error as? IOException ?: IOException("SABR stream failed: ${error.message}", error)
                        lock.notifyAll()
                    }
                }
            }
        }
    }

    private fun append(current: Int, piece: SabrPiece) {
        synchronized(lock) { appendLocked(current, piece) }
    }

    private fun appendLocked(current: Int, piece: SabrPiece) {
        if (current != generation || closed) return
        val end = piece.startByte + piece.data.size
        if (!piece.isInitialization && !sawMedia) {
            sawMedia = true
            // Started past the wanted byte (the time estimate was late): go back further.
            if (piece.startByte > targetByte && startMs > 0L) {
                start(targetByte, (startMs - OVERSHOOT_STEP_MS).coerceAtLeast(0L))
                return
            }
        }
        spool.seek(piece.startByte)
        spool.write(piece.data)
        addRange(piece.startByte, end)
        piece.endMs?.let { endTimes[end] = it }
        if (!piece.isInitialization) frontier = end
        lastProgressAt = SystemClock.elapsedRealtime()
        lock.notifyAll()
    }

    private fun addRange(start: Long, end: Long) {
        var newStart = start
        var newEnd = end
        ranges.floorEntry(start)?.takeIf { it.value >= start }?.let {
            newStart = it.key
            newEnd = maxOf(newEnd, it.value)
        }
        while (true) {
            val next = ranges.ceilingEntry(newStart)?.takeIf { it.key <= newEnd } ?: break
            newEnd = maxOf(newEnd, next.value)
            ranges.remove(next.key)
        }
        ranges[newStart] = newEnd
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            producer?.cancel()
            runCatching { spool.close() }
            file.delete()
            lock.notifyAll()
        }
    }

    private companion object {
        const val TAG = "LastWaveSabr"
        const val WAIT_SLICE_MS = 250L
        const val READ_TIMEOUT_MS = 20_000L
        /** About a minute of Opus: closer than this, waiting beats a restart. */
        const val RESTART_GAP_BYTES = 1L shl 20
        const val SEEK_PREROLL_MS = 3_000L
        const val OVERSHOOT_STEP_MS = 15_000L
    }
}
