package com.lastwave.app.data.addon

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

class AddonHttpTest {
    private class Server(private val delayBody: Boolean = false) : AutoCloseable {
        val started = CompletableDeferred<Unit>()
        private val server = LoopbackHttpServer { socket ->
            if (delayBody) {
                LoopbackHttpServer.writeHeaders(socket, 2)
                socket.getOutputStream().write('o'.code)
                socket.getOutputStream().flush()
            }
            started.complete(Unit)
            Thread.sleep(600)
            if (!delayBody) LoopbackHttpServer.writeHeaders(socket, 2)
            socket.getOutputStream().write(if (delayBody) byteArrayOf('k'.code.toByte()) else "ok".toByteArray())
            socket.getOutputStream().flush()
        }
        val url: String get() = "${server.url}/"
        override fun close() { server.close() }
    }

    @Test fun responseDiagnosticsAcceptOnlyKnownStatesAndNumericTimings() = runBlocking {
        LoopbackHttpServer { socket ->
            val headers = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\nX-Stream-Cache: COALESCED\r\nServer-Timing: manifest;dur=123.4, key;dur=12.0, private_field;dur=999\r\n\r\n{}"
            socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
        }.use { server ->
            val client = OkHttpClient()
            try {
                val result = client.newCall(Request.Builder().url(server.url).build()).awaitAddonResponse()
                assertEquals("COALESCED", result.cacheState)
                assertEquals(mapOf("manifest" to 123.4, "key" to 12.0), result.timingMs)
                assertEquals("{}", result.body)
                assertTrue(result.elapsedMs >= 0)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

    @Test fun readsAndClosesSuccessfulResponse() = runBlocking {
        Server().use { server ->
            val client = OkHttpClient()
            try {
                val result = client.newCall(Request.Builder().url(server.url).build()).awaitAddonResponse()
                assertEquals(200, result.code)
                assertEquals("ok", result.body)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

    @Test fun cancellationAbortsHeaderWait() = runBlocking { checkCancellation(delayBody = false) }
    @Test fun cancellationAbortsBodyRead() = runBlocking { checkCancellation(delayBody = true) }

    private suspend fun checkCancellation(delayBody: Boolean) {
        Server(delayBody).use { server ->
            val bodyReadStarted = CompletableDeferred<Unit>()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) { bodyReadStarted.complete(Unit) }
            }).build()
            val call = client.newCall(Request.Builder().url(server.url).build())
            try {
                coroutineScope {
                    val waiting = launch { call.awaitAddonResponse(); error("Canceled request completed") }
                    withTimeout(2_000) { server.started.await() }
                    if (delayBody) withTimeout(2_000) { bodyReadStarted.await() }
                    withTimeout(400) { waiting.cancelAndJoin() }
                    assertTrue(call.isCanceled())
                }
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

    @Test fun cancellingRaceLoserDoesNotWaitForItsNetworkReply() = runBlocking {
        val legacy = runRace(legacyBlocking = true)
        val cancellable = runRace(legacyBlocking = false)
        println("Addon HTTP controlled race: blocking=${legacy}ms cancellable=${cancellable}ms slowReply=600ms")
        assertTrue("Blocking baseline must wait for slow reply: $legacy", legacy >= 550)
        assertTrue("Winner delayed by loser after cancellation: $cancellable", cancellable < 400)
    }

    private suspend fun runRace(legacyBlocking: Boolean): Long {
        Server().use { server ->
            val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
            try {
                return measureTimeMillis {
                    coroutineScope {
                        val results = Channel<String>(2)
                        val slow = launch(Dispatchers.IO) {
                            if (legacyBlocking) {
                                client.newCall(Request.Builder().url(server.url).build()).execute().use { it.body?.string() }
                            } else {
                                client.newCall(Request.Builder().url(server.url).build()).awaitAddonResponse()
                            }
                            results.send("slow")
                        }
                        val fast = launch {
                            server.started.await()
                            delay(10)
                            results.send("verified-winner")
                        }
                        assertEquals("verified-winner", withTimeout(2_000) { results.receive() })
                        slow.cancel()
                        fast.cancel()
                    }
                }
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
}
