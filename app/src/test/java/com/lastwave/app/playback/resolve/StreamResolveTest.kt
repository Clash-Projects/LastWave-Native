package com.lastwave.app.playback.resolve

import com.lastwave.app.data.music.YouTubeAudioStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StreamResolveTest {
    private val idA = TrackIdentity("track-a", "aaaaaaaaaaa")
    private val idB = TrackIdentity("track-b", "bbbbbbbbbbb")

    @Test
    fun cacheKeyIsTrackIdPlusVideoId() {
        assertEquals("track-a+aaaaaaaaaaa", idA.cacheKey)
        assertFalse(idA.cacheKey.contains("title"))
    }

    @Test
    fun playbackIdentityIgnoresTitle() {
        val track = com.lastwave.app.playback.PlayableTrack(
            title = "Wrong Name",
            artist = "Wrong Artist",
            album = "Wrong Album",
            videoId = "abcdefghijk",
            internalTrackId = "lib-1",
        )
        val identity = track.playbackIdentity()
        assertEquals(TrackIdentity("lib-1", "abcdefghijk"), identity)
        assertEquals("lib-1+abcdefghijk", identity?.cacheKey)
    }

    @Test
    fun missingVideoIdHasNoIdentity() {
        val track = com.lastwave.app.playback.PlayableTrack(title = "Only Title", artist = "Only Artist")
        assertNull(track.playbackIdentity())
    }

    @Test
    fun prefetchCannotReplacePlaybackEntry() {
        val cache = PlaybackStreamCache(tempDir())
        cache.putFromPlayback(entry(idA, "https://playback.example/a"))
        val replaced = cache.putIfAbsent(entry(idA, "https://prefetch.example/a"))
        assertFalse(replaced)
        assertEquals("https://playback.example/a", cache.get(idA)?.streamUrl)
    }

    @Test
    fun differentVideosDoNotShareACacheEntry() {
        val cache = PlaybackStreamCache(tempDir())
        cache.putFromPlayback(entry(idA, "https://cdn.example/a"))
        cache.putFromPlayback(entry(idB, "https://cdn.example/b"))
        assertEquals("https://cdn.example/a", cache.get(idA)?.streamUrl)
        assertEquals("https://cdn.example/b", cache.get(idB)?.streamUrl)
    }

    @Test
    fun expiredEntryIsAMiss() {
        val cache = PlaybackStreamCache(tempDir(), nowMs = { 10_000L })
        cache.putFromPlayback(entry(idA, "https://cdn.example/a", expiresAt = 10_000L))
        assertNull(cache.get(idA))
    }

    @Test
    fun diskReloadKeepsIdentityKey() {
        val dir = tempDir()
        PlaybackStreamCache(dir, nowMs = { 1_000L }).putFromPlayback(
            entry(idA, "https://cdn.example/a", expiresAt = 60_000_000L),
        )
        val reloaded = PlaybackStreamCache(dir, nowMs = { 1_000L })
        assertEquals("https://cdn.example/a", reloaded.get(idA)?.streamUrl)
        assertNull(reloaded.get(idB))
    }

    @Test
    fun rematchDoesNotTakeOverNewSelection() {
        val resolver = PlaybackResolver(PlaybackStreamCache(tempDir()), noopLogger)
        val replacement = TrackIdentity("track-c", "ccccccccccc")
        resolver.begin(idA)
        resolver.begin(idB)

        val failure = runCatching { resolver.replaceClaim(idA, replacement) }.exceptionOrNull()

        assertTrue(failure is StaleResolveException)
        assertEquals(idB, resolver.requested)
        resolver.replaceClaim(idB, replacement)
        assertEquals(replacement, resolver.requested)
    }

    @Test
    fun staleTokenDoesNotPublish() = runBlocking {
        val cache = PlaybackStreamCache(tempDir())
        val resolver = PlaybackResolver(cache, noopLogger)
        val first = resolver.begin(idA)
        resolver.begin(idB)
        var extracted = false
        try {
            resolver.resolve(first, idA, 1_000L) {
                extracted = true
                stream(it)
            }
            throw AssertionError("stale resolve was applied")
        } catch (error: StaleResolveException) {
            assertEquals(idA, error.identity)
        }
        assertFalse(extracted)
        assertNull(cache.get(idA))
    }

    @Test
    fun mismatchIsRejectedAndRetriedThenDiscarded() = runBlocking {
        val cache = PlaybackStreamCache(tempDir())
        val resolver = PlaybackResolver(cache, noopLogger)
        val token = resolver.begin(idA)
        var calls = 0
        try {
            resolver.resolve(token, idA, 3_000L) {
                calls++
                stream("zzzzzzzzzzz")
            }
            throw AssertionError("mismatched stream was playable")
        } catch (error: MismatchedStreamException) {
            assertEquals(idA, error.requested)
        }
        assertEquals(2, calls)
        assertNull(cache.get(idA))
    }

    @Test
    fun cacheHitSkipsNetwork() = runBlocking {
        val cache = PlaybackStreamCache(tempDir())
        cache.putFromPlayback(entry(idA, "https://cdn.example/cached", expiresAt = System.currentTimeMillis() + 600_000L))
        val resolver = PlaybackResolver(cache, noopLogger)
        val token = resolver.begin(idA)
        var calls = 0
        val resolved = resolver.resolve(token, idA, 4_000L) {
            calls++
            stream(it)
        }
        assertEquals(0, calls)
        assertEquals("https://cdn.example/cached", resolved.streamUrl)
        assertEquals(idA.trackId, resolved.trackId)
        assertEquals(idA.youtubeVideoId, resolved.youtubeVideoId)
    }

    @Test
    fun cancelDiscardsAnInFlightResult() = runBlocking {
        val dir = tempDir()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = StreamResolveCoordinator(
            cacheDir = dir,
            scope = this,
            extract = {
                started.complete(Unit)
                release.await()
                stream(it)
            },
            logger = noopLogger,
        )
        val first = async { coordinator.resolvePlayback(idA, 1_000L) }
        started.await()
        coordinator.cancelAll("skip")
        release.complete(Unit)
        try {
            first.await()
            throw AssertionError("cancelled resolve was applied")
        } catch (error: StaleResolveException) {
            assertEquals(idA, error.identity)
        }
        val second = coordinator.resolvePlayback(idB, 2_000L)
        assertEquals(idB.youtubeVideoId, second.youtubeVideoId)
        // The cancelled call must not become the playing track, but the URL it
        // already extracted stays cached so the next tap of A does not hit YouTube again.
        assertEquals("https://cdn.example/${idA.youtubeVideoId}", coordinator.cache.get(idA)?.streamUrl)
        assertEquals("https://cdn.example/${idB.youtubeVideoId}", second.streamUrl)
    }

    @Test
    fun prefetchDoesNotClaimPlaybackIdentity() = runBlocking {
        val installed = mutableListOf<String>()
        val coordinator = StreamResolveCoordinator(
            cacheDir = tempDir(),
            scope = this,
            extract = { videoId ->
                delay(20)
                stream(videoId)
            },
            logger = noopLogger,
        )
        coordinator.playback.begin(idA)
        coordinator.prefetchNext(idB, 1_000L) { entry ->
            installed += entry.youtubeVideoId
        }
        delay(200)
        assertEquals(listOf(idB.youtubeVideoId), installed)
        assertEquals(idA, coordinator.playback.requested)
        assertEquals(idB.youtubeVideoId, coordinator.cache.get(idB)?.youtubeVideoId)
    }

    @Test
    fun sameVideoIdReusesUrlAcrossTrackIds() {
        val cache = PlaybackStreamCache(tempDir())
        val videoId = "ccccccccccc"
        val library = TrackIdentity("library-row", videoId)
        val bare = TrackIdentity(videoId, videoId)
        cache.putFromPlayback(entry(library, "https://cdn.example/shared"))
        val hit = cache.get(bare)
        assertEquals("https://cdn.example/shared", hit?.streamUrl)
        assertEquals(bare.trackId, hit?.trackId)
        assertEquals(videoId, hit?.youtubeVideoId)
    }

    @Test
    fun sameVideoJoinsOneExtraction() = runBlocking {
        val calls = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = StreamResolveCoordinator(
            cacheDir = tempDir(),
            scope = this,
            extract = {
                if (calls.incrementAndGet() == 1) started.complete(Unit)
                release.await()
                stream(it)
            },
            logger = noopLogger,
        )
        val first = async { coordinator.resolvePlayback(idA, 1_000L) }
        started.await()
        val second = async { coordinator.resolvePlayback(idA, 1_000L) }
        delay(50)
        release.complete(Unit)
        val left = first.await()
        val right = second.await()
        assertEquals(1, calls.get())
        assertEquals(left.streamUrl, right.streamUrl)
        assertEquals(left.streamUrl, coordinator.cache.get(idA)?.streamUrl)
    }

    @Test
    fun cancelledPrefetchStillCachesTheUrl() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = StreamResolveCoordinator(
            cacheDir = tempDir(),
            scope = this,
            extract = {
                started.complete(Unit)
                release.await()
                stream(it)
            },
            logger = noopLogger,
        )
        coordinator.prefetchNext(idB, 1_000L) { }
        withTimeout(3_000) { started.await() }
        coordinator.prefetch.cancel()
        release.complete(Unit)
        withTimeout(3_000) {
            while (coordinator.cache.get(idB) == null) delay(10)
        }
        assertEquals("https://cdn.example/${idB.youtubeVideoId}", coordinator.cache.get(idB)?.streamUrl)
    }

    @Test
    fun secondPrefetchOfTheSameVideoDoesNotExtractAgain() = runBlocking {
        val calls = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = StreamResolveCoordinator(
            cacheDir = tempDir(),
            scope = this,
            extract = {
                calls.incrementAndGet()
                started.complete(Unit)
                release.await()
                stream(it)
            },
            logger = noopLogger,
        )
        coordinator.prefetchNext(idB, 1_000L) { }
        withTimeout(3_000) { started.await() }
        coordinator.prefetchNext(idB, 1_000L) { }
        release.complete(Unit)
        withTimeout(3_000) {
            while (coordinator.cache.get(idB) == null) delay(10)
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun prefetchExtractionFailureStaysInsideTheJob() = runBlocking {
        val escaped = AtomicBoolean(false)
        val threw = CompletableDeferred<Unit>()
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ ->
                escaped.set(true)
            },
        )
        val coordinator = StreamResolveCoordinator(
            cacheDir = tempDir(),
            scope = scope,
            extract = {
                threw.complete(Unit)
                throw IOException("Unable to resolve a playable audio stream for video")
            },
            logger = noopLogger,
        )
        coordinator.prefetchNext(idB, 1_000L) { error("prefetch must not publish a failed extract") }
        withTimeout(3_000) { threw.await() }
        delay(100)
        assertFalse(escaped.get())
        assertNull(coordinator.cache.get(idB))
        scope.cancel()
    }

    @Test
    fun playbackTimeoutIsARecoverableIoFailure() = runBlocking {
        val coordinator = StreamResolveCoordinator(
            cacheDir = tempDir(),
            scope = this,
            extract = { withTimeout(1) { delay(10_000); stream(it) } },
            logger = noopLogger,
        )
        try {
            coordinator.resolvePlayback(idA, 1_000L)
            throw AssertionError("timeout must not resolve")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains(idA.youtubeVideoId))
        }
    }

    private fun entry(
        identity: TrackIdentity,
        url: String,
        expiresAt: Long = System.currentTimeMillis() + 600_000L,
    ) = stream(identity.youtubeVideoId, expiresAt).toStreamCache(identity).copy(streamUrl = url)

    private fun stream(videoId: String, expiresAt: Long = System.currentTimeMillis() + 600_000L) =
        YouTubeAudioStream(
            videoId = videoId,
            url = "https://cdn.example/$videoId",
            itag = 251,
            mimeType = "audio/webm",
            codec = "opus",
            bitrate = 160_000,
            sampleRateHz = 48_000,
            durationMs = 180_000L,
            contentLength = 1_000L,
            isAdaptive = true,
            clientProfile = "ANDROID_VR",
            authScope = "anon",
            expiresAtEpochMs = expiresAt,
        )

    private fun tempDir(): File = File.createTempFile("stream-cache", "").apply {
        delete()
        mkdirs()
    }

    private val noopLogger = ResolverLogger { _, _, _, _, _ -> }
}
