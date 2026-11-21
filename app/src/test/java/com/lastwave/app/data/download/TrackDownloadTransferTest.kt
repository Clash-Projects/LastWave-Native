package com.lastwave.app.data.download

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class TrackDownloadTransferTest {
    @get:Rule val temp = TemporaryFolder()
    private val manager = mockk<TrackDownloadManager>()
    private val context = mockk<Context>()
    private val requests = CopyOnWriteArrayList<Request>()
    private lateinit var client: OkHttpClient
    private lateinit var target: File
    private lateinit var respond: (Interceptor.Chain) -> Response
    private val youtubeUrl = "https://rr1.googlevideo.com/videoplayback"
    private val userAgent = "minting-client/1.0"

    @Before fun setUp() {
        target = temp.newFile("audio.tmp")
        client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            respond(chain)
        }.build()
        every { manager.downloadClient } returns client
        every { manager.context } returns context
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns null
        every { manager.reconnectGenerations } returns ConcurrentHashMap<String, AtomicLong>().apply {
            put("track", AtomicLong())
        }
    }

    @After fun tearDown() {
        client.dispatcher.executorService.shutdown()
        if (!client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)) {
            client.dispatcher.executorService.shutdownNow()
        }
        client.connectionPool.evictAll()
        unmockkAll()
    }

    @Test fun knownYouTubeLengthUsesBoundedRangesAndPreservesUserAgent() = runBlocking {
        val audio = audioBytes((YOUTUBE_RANGE_BYTES * 2 + 123).toInt())
        respond = { rangeResponse(it.request(), audio) }

        val transfer = download(audio.size.toLong())

        assertThat(target.readBytes()).isEqualTo(audio)
        assertThat(transfer.bytesDownloaded).isEqualTo(audio.size.toLong())
        assertThat(requests).hasSize(3)
        assertBoundedYouTubeRequests()
    }

    @Test fun unknownYouTubeLengthProbesOneByteThenDownloadsBoundedRanges() = runBlocking {
        val audio = audioBytes((YOUTUBE_RANGE_BYTES + 123).toInt())
        respond = { rangeResponse(it.request(), audio) }

        download(null)

        assertThat(requests.first().header("Range")).isEqualTo("bytes=0-0")
        assertThat(requests).hasSize(3)
        assertThat(target.readBytes()).isEqualTo(audio)
        assertBoundedYouTubeRequests()
    }

    @Test fun refusedYouTubeRangeNeverFallsBackToUnboundedGet() = runBlocking {
        respond = { response(it.request(), 403, ByteArray(0)) }

        val failure = expectFailure<DownloadHttpException> { download(8_192) }

        assertThat(failure.statusCode).isEqualTo(403)
        assertThat(requests).hasSize(1)
        assertBoundedYouTubeRequests()
    }

    @Test fun refusedYouTubeProbePreservesHttpFailureWithoutUnboundedGet() = runBlocking {
        respond = { response(it.request(), 403, ByteArray(0)) }

        val failure = expectFailure<DownloadHttpException> { download(null) }

        assertThat(failure.statusCode).isEqualTo(403)
        assertThat(requests).hasSize(1)
        assertThat(target.length()).isEqualTo(0)
        assertBoundedYouTubeRequests()
    }

    @Test fun missingYouTubeLengthFailsInsteadOfStartingUnboundedTransfer() = runBlocking {
        respond = { response(it.request(), 206, byteArrayOf(1)) }

        expectFailure<DownloadProtocolException> { download(null) }

        assertThat(requests).hasSize(1)
        assertThat(target.length()).isEqualTo(0)
        assertBoundedYouTubeRequests()
    }

    @Test fun transientProbeFailureRetriesTheBoundedProbe() = runBlocking {
        val first = AtomicBoolean(true)
        val audio = audioBytes(8_192)
        respond = {
            if (first.compareAndSet(true, false)) throw SocketTimeoutException("temporary failure")
            rangeResponse(it.request(), audio)
        }

        download(null)

        assertThat(requests.map { it.header("Range") })
            .containsExactly("bytes=0-0", "bytes=0-0", "bytes=0-8191").inOrder()
        assertThat(target.readBytes()).isEqualTo(audio)
        assertBoundedYouTubeRequests()
    }

    @Test fun interruptedYouTubeRangeResumesAtWrittenByteWithSameBound() = runBlocking {
        val first = AtomicBoolean(true)
        val audio = audioBytes(8_192)
        respond = {
            val response = rangeResponse(it.request(), audio)
            if (first.compareAndSet(true, false)) {
                response.newBuilder().body(interruptedBody(audio, 2_048)).build()
            } else response
        }

        download(audio.size.toLong())

        assertThat(requests.map { it.header("Range") })
            .containsExactly("bytes=0-8191", "bytes=2048-8191").inOrder()
        assertThat(target.readBytes()).isEqualTo(audio)
        assertBoundedYouTubeRequests()
    }

    @Test fun nonYouTubeServerWithoutRangeSupportStillUsesSingleStream() = runBlocking {
        val audio = audioBytes((MIN_PARALLEL_DOWNLOAD_BYTES + 123).toInt())
        respond = { response(it.request(), 200, audio) }

        download(audio.size.toLong(), "https://provider.example/audio.flac")

        assertThat(requests.any { it.header("Range") != null }).isTrue()
        assertThat(requests.last().header("Range")).isNull()
        assertThat(target.readBytes()).isEqualTo(audio)
    }

    @Test fun cancellingUnknownLengthProbeCancelsHttpCall() = runBlocking {
        val entered = CompletableDeferred<Call>()
        val release = CountDownLatch(1)
        respond = { chain ->
            entered.complete(chain.call())
            check(release.await(5, TimeUnit.SECONDS))
            rangeResponse(chain.request(), audioBytes(8_192))
        }
        try {
            val download = async { download(null) }
            val call = withTimeout(5_000) { entered.await() }
            download.cancelAndJoin()
            assertThat(call.isCanceled()).isTrue()
            assertThat(requests).hasSize(1)
        } finally {
            release.countDown()
        }
    }

    private suspend fun download(length: Long?, url: String = youtubeUrl): DownloadTransfer = withTimeout(10_000) {
        manager.downloadToTempFile(
            downloadKey = "track",
            url = url,
            target = target,
            requestHeaders = mapOf("User-Agent" to userAgent),
            expectedContentLength = length,
            useParallelRanges = true,
            onConnectionStateChanged = {},
            onProgress = { _, _ -> },
        )
    }

    private fun assertBoundedYouTubeRequests() {
        requests.forEach { request ->
            assertThat(request.header("User-Agent")).isEqualTo(userAgent)
            val (start, end) = range(request)
            assertThat(end - start + 1).isAtMost(YOUTUBE_RANGE_BYTES)
            assertThat(end).isAtLeast(start)
        }
    }

    private fun range(request: Request): Pair<Long, Long> {
        val parts = requireNotNull(request.header("Range")).removePrefix("bytes=").split('-')
        return parts[0].toLong() to parts[1].toLong()
    }

    private fun rangeResponse(request: Request, audio: ByteArray): Response {
        val (start, end) = range(request)
        return response(request, 206, audio.copyOfRange(start.toInt(), end.toInt() + 1))
            .newBuilder().header("Content-Range", "bytes $start-$end/${audio.size}").build()
    }

    private fun response(request: Request, code: Int, bytes: ByteArray): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
        .body(bytes.toResponseBody("audio/webm".toMediaType())).build()

    private fun interruptedBody(audio: ByteArray, failAfter: Int): ResponseBody = object : ResponseBody() {
        override fun contentType() = "audio/webm".toMediaType()
        override fun contentLength() = audio.size.toLong()
        private val source = object : Source {
            private var position = 0
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (position == failAfter) throw IOException("connection interrupted")
                val count = minOf(byteCount, (failAfter - position).toLong()).toInt()
                sink.write(audio, position, count)
                position += count
                return count.toLong()
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        override fun source() = source
    }

    private fun audioBytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit): T {
        val error = runCatching { block() }.exceptionOrNull()
        assertThat(error).isInstanceOf(T::class.java)
        return error as T
    }
}
