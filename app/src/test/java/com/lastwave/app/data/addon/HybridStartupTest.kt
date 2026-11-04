package com.lastwave.app.data.addon

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.EventListener
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HybridStartupTest {
    private class Cdn(private val bytes: ByteArray, private val invalid: Boolean = false) : AutoCloseable {
        val segmentStarted = CompletableDeferred<Unit>()
        val initReturned = CompletableDeferred<Unit>()
        val firstSegmentRelease = CountDownLatch(1)
        val requests = AtomicInteger()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val executor = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
        private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val url: String get() = "http://127.0.0.1:${listener.localPort}/audio"
        init {
            executor.execute {
                while (!listener.isClosed) {
                    val socket = try { listener.accept() } catch (_: IOException) { break }
                    sockets += socket
                    executor.execute {
                        try {
                            socket.use {
                                val input = socket.getInputStream().bufferedReader()
                                val headers = ArrayList<String>()
                                while (true) {
                                    val line = input.readLine() ?: return@use
                                    if (line.isEmpty()) break
                                    headers += line
                                }
                                requests.incrementAndGet()
                                val range = headers.first { it.startsWith("Range:", true) }.substringAfter("bytes=").split('-')
                                val start = range[0].toInt()
                                val end = range[1].toInt()
                                if (start == 20) {
                                    segmentStarted.complete(Unit)
                                    check(firstSegmentRelease.await(5, TimeUnit.SECONDS))
                                }
                                val status = if (invalid) "200 OK" else "206 Partial Content"
                                val head = "HTTP/1.1 $status\r\nContent-Length: ${end - start + 1}\r\nContent-Range: bytes $start-$end/${bytes.size}\r\nConnection: close\r\n\r\n"
                                socket.getOutputStream().write(head.toByteArray())
                                socket.getOutputStream().write(bytes.copyOfRange(start, end + 1))
                                socket.getOutputStream().flush()
                                if (start == 0) initReturned.complete(Unit)
                            }
                        } catch (_: IOException) {
                        } catch (_: InterruptedException) {
                        } finally { sockets -= socket }
                    }
                }
            }
        }
        override fun close() {
            firstSegmentRelease.countDown()
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow()
        }
    }

    private class Fixture(val cdn: Cdn, encrypted: Boolean = false, listener: EventListener? = null) : AutoCloseable {
        private val cache = Files.createTempDirectory("hybrid-startup").toFile()
        val http = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).apply {
            if (listener != null) eventListener(listener)
        }.build()
        private val context = mockk<Context>().also { every { it.cacheDir } returns cache }
        val media = HybridMediaServer(context, http)
        val plan = HybridPlan(cdnUrl = cdn.url, initRange = HybridRange(0, 9),
            ranges = listOf(HybridRange(20, 39), HybridRange(40, 59)),
            encrypted = encrypted, key = if (encrypted) "00".repeat(16) else "")
        val url = media.urlFor(plan, "startup-test")!!
        override fun close() {
            media.shutdown()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
            cache.deleteRecursively()
        }
    }

    @Test fun initAndHeadersAreReadableBeforeFirstMediaAndFinalLayoutPreservesSidxGap() = runBlocking {
        val original = ByteArray(60) { it.toByte() }
        Cdn(original).use { cdn ->
            Fixture(cdn).use { fixture ->
                withTimeout(2_000) { cdn.segmentStarted.await(); cdn.initReturned.await() }
                val response = withTimeout(2_000) { withContext(Dispatchers.IO) {
                    fixture.http.newCall(Request.Builder().url(fixture.url).build()).execute()
                } }
                response.use {
                    assertEquals(200, it.code)
                    assertEquals(60L, it.body!!.contentLength())
                    val source = it.body!!.source()
                    val init = withTimeout(2_000) { withContext(Dispatchers.IO) { source.readByteArray(20) } }
                    assertArrayEquals(original.copyOfRange(0, 20), init)
                    assertEquals("First media remains held while init is readable", 1L, cdn.firstSegmentRelease.count)
                    cdn.firstSegmentRelease.countDown()
                    val media = withContext(Dispatchers.IO) { source.readByteArray() }
                    assertArrayEquals(original, init + media)
                }
                fixture.http.newCall(Request.Builder().url(fixture.url).header("Range", "bytes=40-59").build()).execute().use {
                    assertEquals(206, it.code)
                    assertEquals("bytes 40-59/60", it.header("Content-Range"))
                    assertArrayEquals(original.copyOfRange(40, 60), it.body!!.bytes())
                }
            }
        }
    }

    @Test fun encryptedMediaIsNotPublishedUntilDecryptionCompletes() = runBlocking {
        val decryptStarted = CompletableDeferred<Unit>()
        val decryptRelease = CountDownLatch(1)
        mockkObject(HybridCenc)
        every { HybridCenc.patchInitSegment(any()) } returns TencInfo(16, ByteArray(0))
        every { HybridCenc.decryptSegment(any(), any(), any(), any(), any()) } answers {
            decryptStarted.complete(Unit)
            check(decryptRelease.await(5, TimeUnit.SECONDS))
            firstArg<ByteArray>().fill(42)
        }
        try {
            Cdn(ByteArray(60) { 7 }).use { cdn ->
                Fixture(cdn, encrypted = true).use { fixture ->
                    cdn.firstSegmentRelease.countDown()
                    withTimeout(2_000) { decryptStarted.await() }
                    val readingMedia = async(Dispatchers.IO) {
                        fixture.http.newCall(Request.Builder().url(fixture.url).header("Range", "bytes=20-39").build()).execute().use { it.body!!.bytes() }
                    }
                    assertFalse(readingMedia.isCompleted)
                    decryptRelease.countDown()
                    assertArrayEquals(ByteArray(20) { 42 }, withTimeout(2_000) { readingMedia.await() })
                }
            }
        } finally {
            decryptRelease.countDown()
            unmockkObject(HybridCenc)
        }
    }

    @Test fun evictedPausedInitCannotOverwriteReplacementWithSameCacheName() = runBlocking {
        val oldPatchStarted = CompletableDeferred<Unit>()
        val oldPatchRelease = CountDownLatch(1)
        mockkObject(HybridCenc)
        every { HybridCenc.patchInitSegment(any()) } answers {
            if (firstArg<ByteArray>()[0] == 3.toByte()) {
                oldPatchStarted.complete(Unit)
                check(oldPatchRelease.await(5, TimeUnit.SECONDS))
            }
            TencInfo(16, ByteArray(0))
        }
        every { HybridCenc.decryptSegment(any(), any(), any(), any(), any()) } answers { Unit }
        try {
            Cdn(ByteArray(60) { 3 }).use { oldCdn ->
                oldCdn.firstSegmentRelease.countDown()
                Fixture(oldCdn, encrypted = true).use { fixture ->
                    withTimeout(2_000) { oldPatchStarted.await() }
                    // Retain the old job solely so the test can await its final cleanup.
                    val sessionsField = HybridMediaServer::class.java.getDeclaredField("sessions").apply { isAccessible = true }
                    val oldSession = (sessionsField.get(fixture.media) as Map<*, *>)["startup-test"]!!
                    val jobField = oldSession.javaClass.getDeclaredField("sessionJob").apply { isAccessible = true }
                    val oldJob = jobField.get(oldSession) as Job
                    fixture.media.evict("startup-test")
                    Cdn(ByteArray(60) { 9 }).use { replacementCdn ->
                        replacementCdn.firstSegmentRelease.countDown()
                        val replacementUrl = fixture.media.urlFor(fixture.plan.copy(cdnUrl = replacementCdn.url), "startup-test")!!
                        assertEquals("Logical URL remains stable across session replacement", fixture.url, replacementUrl)
                        val replacementBefore = withTimeout(2_000) { withContext(Dispatchers.IO) {
                            fixture.http.newCall(Request.Builder().url(replacementUrl).build()).execute().use { it.body!!.bytes() }
                        } }
                        assertArrayEquals(ByteArray(60) { 9 }, replacementBefore)
                        oldPatchRelease.countDown()
                        withTimeout(2_000) { oldJob.join() }
                        val replacementAfter = withTimeout(2_000) { withContext(Dispatchers.IO) {
                            fixture.http.newCall(Request.Builder().url(replacementUrl).build()).execute().use { it.body!!.bytes() }
                        } }
                        assertArrayEquals("Old writer/final cleanup must leave replacement bytes intact", replacementBefore, replacementAfter)
                    }
                }
            }
        } finally {
            oldPatchRelease.countDown()
            unmockkObject(HybridCenc)
        }
    }

    @Test fun invalidRangeResponseFailsBeforeAnyInitOrMediaIsServed() = runBlocking {
        Cdn(ByteArray(60), invalid = true).use { cdn ->
            Fixture(cdn).use { fixture ->
                cdn.firstSegmentRelease.countDown()
                fixture.http.newCall(Request.Builder().url(fixture.url).build()).execute().use {
                    assertEquals(500, it.code)
                    assertEquals("Stream unavailable", it.body!!.string())
                }
            }
        }
    }

    @Test fun evictionCancelsPendingRangeCallAndRemovesLoopbackSession() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        val listener = object : EventListener() {
            override fun callFailed(call: Call, ioe: IOException) {
                if (call.isCanceled()) cancelled.complete(Unit)
            }
        }
        Cdn(ByteArray(60)).use { cdn ->
            Fixture(cdn, listener = listener).use { fixture ->
                withTimeout(2_000) { cdn.segmentStarted.await(); cdn.initReturned.await() }
                fixture.media.evict("startup-test")
                withTimeout(2_000) { cancelled.await() }
                fixture.http.newCall(Request.Builder().url(fixture.url).build()).execute().use {
                    assertEquals(404, it.code)
                }
            }
        }
    }
}
