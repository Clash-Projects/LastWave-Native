package com.lastwave.app.data.addon

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Minimal GET-only loopback HTTP fixture that also compiles against the Android SDK. */
internal class LoopbackHttpServer(private val handle: (Socket) -> Unit) : AutoCloseable {
    private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val handlerFailure = AtomicReference<Throwable?>()
    val url: String get() = "http://127.0.0.1:${listener.localPort}"

    init {
        executor.execute {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: IOException) { break }
                connections += socket
                executor.execute {
                    try {
                        socket.use {
                            socket.soTimeout = 2_000
                            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            while (true) {
                                val line = reader.readLine() ?: return@use
                                if (line.isEmpty()) break
                            }
                            handle(socket)
                        }
                    } catch (_: IOException) {
                        // Expected when a canceled client closes its connection.
                    } catch (_: InterruptedException) {
                        // Teardown interrupts deliberately delayed responses.
                    } catch (failure: Throwable) {
                        handlerFailure.compareAndSet(null, failure)
                    } finally {
                        connections -= socket
                    }
                }
            }
        }
    }

    override fun close() {
        listener.close()
        connections.forEach { runCatching { it.close() } }
        executor.shutdownNow()
        handlerFailure.get()?.let { throw AssertionError("Loopback HTTP handler failed", it) }
    }

    companion object {
        fun writeHeaders(socket: Socket, length: Int) {
            val headers = "HTTP/1.1 200 OK\r\nContent-Length: $length\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
        }
    }
}
