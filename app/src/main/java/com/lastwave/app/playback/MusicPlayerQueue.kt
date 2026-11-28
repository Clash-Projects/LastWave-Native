package com.lastwave.app.playback

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.lastwave.app.data.local.musicHapticsConfig
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.data.lyrics.LyricsResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import android.media.MediaCodecList
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.ts.AdtsExtractor
import com.lastwave.app.R
import com.lastwave.app.data.download.Fmp4IndexBuilder
import com.lastwave.app.data.plugin.SegmentedMpdBuilder
import coil.imageLoader
import coil.request.ImageRequest
import com.lastwave.app.data.discover.DiscoverRepository
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.normalizeTrackText
import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.CacheStoragePolicy
import com.lastwave.app.data.local.AdaptiveCacheEvictor
import com.lastwave.app.data.local.EqualizerPreferences
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.ConfirmedUnplayableMediaException
import com.lastwave.app.data.music.YouTubeAudioStream
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YOUTUBE_WEB_USER_AGENT
import com.lastwave.app.data.lossless.LosslessAudioStream
import com.lastwave.app.data.lossless.LosslessMusicApi
import com.lastwave.app.data.network.NetworkMonitor
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.plugin.ModuleDrmFactory
import com.lastwave.app.data.plugin.SegmentedDashBridge
import com.lastwave.app.data.plugin.stableCacheKey
import com.lastwave.app.data.recommend.RecommendationRepository
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import com.lastwave.app.playback.resolve.MetadataLog
import com.lastwave.app.playback.resolve.PlaybackStartupLog
import com.lastwave.app.playback.resolve.StreamCache
import com.lastwave.app.playback.resolve.StreamResolveCoordinator
import com.lastwave.app.playback.resolve.TrackIdentity
import com.lastwave.app.playback.resolve.playbackIdentity
import com.lastwave.app.playback.resolve.toYouTubeAudioStream
import com.lastwave.app.playback.usb.UsbExclusivePrefs
import com.lastwave.app.widget.WidgetUpdater
import kotlinx.coroutines.flow.first
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.isActive
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random


    @MainThread
internal fun MusicPlayer.currentPlayOrder(target: Player = player): List<Int> {
        val timeline = target.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val order = ArrayList<Int>(target.mediaItemCount)
        var cursor = timeline.getFirstWindowIndex(target.shuffleModeEnabled)
        while (cursor != C.INDEX_UNSET && order.size < target.mediaItemCount) {
            order.add(cursor)
            cursor = timeline.getNextWindowIndex(cursor, Player.REPEAT_MODE_OFF, target.shuffleModeEnabled)
        }
        return order
    }

    /**
     * Appends auto-added songs (radio / discover) after everything already
     * queued. With shuffle on, ExoPlayer would scatter them at random spots of
     * the shuffle order, including before the current song, so they either
     * never play or push unplayed queue songs behind them; here they always
     * follow the last song still waiting to be played.
     */
    @MainThread
internal fun MusicPlayer.appendToQueueEnd(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val count = player.mediaItemCount
        val order = if (player.shuffleModeEnabled) currentPlayOrder() else emptyList()
        player.addMediaItems(items)
        if (player.shuffleModeEnabled && order.size == count) {
            val added = (count until player.mediaItemCount).toList()
            runCatching {
                player.setShuffleOrder(DefaultShuffleOrder((order + added).toIntArray(), Random.nextLong()))
            }
        }
    }

    /**
     * Under shuffle, an inserted timeline item lands at a random permutation
     * spot â€” "Play next" wouldn't play next and "Add to queue" wouldn't play
     * last. Splice [index] into the live shuffle permutation instead:
     * [last] = false puts it directly after the current track, true appends
     * it at the end of the play order. Main thread only.
     */
    @MainThread
internal fun MusicPlayer.placeInsertedIndexInShuffleOrder(index: Int, last: Boolean) {
        if (!player.shuffleModeEnabled) return
        val timeline = player.currentTimeline
        if (timeline.isEmpty || index !in 0 until player.mediaItemCount) return
        val order = mutableListOf<Int>()
        var cursor = timeline.getFirstWindowIndex(true)
        var guard = 0
        while (cursor != C.INDEX_UNSET && guard++ < player.mediaItemCount + 1) {
            order.add(cursor)
            cursor = timeline.getNextWindowIndex(cursor, Player.REPEAT_MODE_OFF, true)
        }
        if (index !in order) return
        order.remove(index)
        val currentIndex = player.currentMediaItemIndex
        val currentPos = order.indexOf(currentIndex)
        if (last || currentPos == -1) {
            order.add(index)
        } else {
            val at = (currentPos + 1).coerceIn(0, order.size)
            order.add(at, index)
        }
        player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), Random.nextLong()))
    }

    /**
     * In ExoPlayer, replaceMediaItem() internally removes and re-inserts the
     * item into ShuffleOrder at a completely random position (using
     * DefaultShuffleOrder.cloneAndInsert). This scrambles the upcoming order,
     * causes the current track to be placed into the future and repeat, or
     * changes which track plays next. Preserve the exact active permutation.
     */
    @MainThread
internal fun MusicPlayer.replaceMediaItemPreservingShuffle(index: Int, mediaItem: MediaItem) {
        if (index !in 0 until player.mediaItemCount) return
        if (!player.shuffleModeEnabled || player.currentTimeline.isEmpty) {
            player.replaceMediaItem(index, mediaItem)
        } else {
            val timeline = player.currentTimeline
            val count = player.mediaItemCount
            val order = mutableListOf<Int>()
            var cursor = timeline.getFirstWindowIndex(true)
            var guard = 0
            while (cursor != C.INDEX_UNSET && guard++ < count + 1) {
                order.add(cursor)
                cursor = timeline.getNextWindowIndex(cursor, Player.REPEAT_MODE_OFF, true)
            }
            player.replaceMediaItem(index, mediaItem)
            if (order.size == count && index in order) {
                player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), Random.nextLong()))
            }
        }
        val standby = secondaryPlayer
        if (standby != null && crossfadeStandbyArmed && index in 0 until standby.mediaItemCount) {
            standby.replaceMediaItem(index, mediaItem)
        }
    }

/**
 * Snapshot + reordered queue produced by enabling or disabling One-Time Queue Shuffle.
 */
internal data class OneTimeShuffleResult<T>(
    val queue: List<T>,
    val currentIndex: Int,
    val originalQueue: List<T>?,
)

/**
 * Pure, unit-testable queue & index transitions for One-Time Queue Shuffle
 * (`persistentShuffle == false`) and Persistent Shuffle (`persistentShuffle == true`).
 */
internal object ShuffleQueueLogic {

    /**
     * Stable key for matching tracks across stream resolution / metadata enrichment:
     * genuine local files keep their path/URI; streamed tracks match by normalized artist|title
     * (never by transient resolved URLs or lazily-learned videoIds).
     */
    fun stableTrackKey(track: PlayableTrack): String =
        if (track.isLocalTrack()) {
            "local:${track.playbackUrl.orEmpty()}"
        } else {
            "${track.artist.normalizeTrackText().lowercase()}|${track.title.normalizeTrackText().lowercase()}"
        }

    fun <T> enableOneTimeShuffle(
        queue: List<T>,
        currentIndex: Int,
        originalSnapshot: List<T>? = null,
        random: Random = Random.Default,
    ): OneTimeShuffleResult<T> {
        if (queue.isEmpty()) {
            return OneTimeShuffleResult(queue = emptyList(), currentIndex = -1, originalQueue = null)
        }
        val safeIndex = currentIndex.coerceIn(queue.indices)
        val snapshot = originalSnapshot?.takeIf { it.isNotEmpty() } ?: queue.toList()
        if (queue.size == 1) {
            return OneTimeShuffleResult(queue = queue.toList(), currentIndex = 0, originalQueue = snapshot)
        }
        val currentTrack = queue[safeIndex]
        val remaining = queue.indices
            .filter { it != safeIndex }
            .map { queue[it] }
            .shuffled(random)
        return OneTimeShuffleResult(
            queue = listOf(currentTrack) + remaining,
            currentIndex = 0,
            originalQueue = snapshot,
        )
    }

    fun <T> disableOneTimeShuffle(
        shuffledQueue: List<T>,
        currentIndex: Int,
        originalQueue: List<T>?,
        keyOf: (T) -> String,
    ): OneTimeShuffleResult<T> {
        if (shuffledQueue.isEmpty()) {
            return OneTimeShuffleResult(queue = emptyList(), currentIndex = -1, originalQueue = null)
        }
        val safeIndex = currentIndex.coerceIn(shuffledQueue.indices)
        val activeTrack = shuffledQueue[safeIndex]
        if (originalQueue.isNullOrEmpty()) {
            return OneTimeShuffleResult(
                queue = shuffledQueue.toList(),
                currentIndex = safeIndex,
                originalQueue = null,
            )
        }

        val remainingCounts = HashMap<String, Int>()
        for (item in shuffledQueue) {
            val k = keyOf(item)
            remainingCounts[k] = (remainingCounts[k] ?: 0) + 1
        }
        val restored = ArrayList<T>(shuffledQueue.size)
        for (orig in originalQueue) {
            val k = keyOf(orig)
            val left = remainingCounts[k] ?: 0
            if (left > 0) {
                restored.add(orig)
                remainingCounts[k] = left - 1
            }
        }
        if (remainingCounts.values.any { it > 0 }) {
            val usedInOriginal = HashMap<String, Int>()
            for (orig in restored) {
                val k = keyOf(orig)
                usedInOriginal[k] = (usedInOriginal[k] ?: 0) + 1
            }
            for (item in shuffledQueue) {
                val k = keyOf(item)
                val skip = usedInOriginal[k] ?: 0
                if (skip > 0) {
                    usedInOriginal[k] = skip - 1
                } else {
                    restored.add(item)
                }
            }
        }

        val activeKey = keyOf(activeTrack)
        val restoredIndex = restored.indexOfFirst { it === activeTrack }
            .takeIf { it >= 0 }
            ?: restored.indexOfFirst { keyOf(it) == activeKey }
                .takeIf { it >= 0 }
            ?: 0

        return OneTimeShuffleResult(
            queue = restored,
            currentIndex = restoredIndex,
            originalQueue = null,
        )
    }

    fun <T> resolveNextIndex(
        queue: List<T>,
        currentIndex: Int,
        shuffleEnabled: Boolean,
        persistentShuffle: Boolean,
        repeatMode: Int,
        unavailableKeys: Set<String> = emptySet(),
        playedKeys: Set<String> = emptySet(),
        wrapWhenPersistentOrSequential: Boolean = true,
        keyOf: (T) -> String,
        random: Random = Random.Default,
    ): Int {
        if (queue.isEmpty()) return C.INDEX_UNSET
        val start = (currentIndex + 1).coerceAtLeast(0)
        val useDynamicRandom = shuffleEnabled && persistentShuffle
        val ordered = if (useDynamicRandom) {
            val historyFilter = if (wrapWhenPersistentOrSequential) emptySet() else playedKeys
            queue.indices.toList()
                .shuffled(random)
                .sortedBy { keyOf(queue[it]) in playedKeys }
                .filter { keyOf(queue[it]) !in historyFilter }
        } else {
            val shouldWrap = if (shuffleEnabled) {
                repeatMode == Player.REPEAT_MODE_ALL
            } else {
                wrapWhenPersistentOrSequential || repeatMode == Player.REPEAT_MODE_ALL
            }
            (start until queue.size) + if (shouldWrap) (0 until start.coerceAtMost(queue.size)) else emptyList()
        }
        return ordered.firstOrNull { it != currentIndex && keyOf(queue[it]) !in unavailableKeys }
            ?: C.INDEX_UNSET
    }

    fun <T> resolvePreviousIndex(
        queue: List<T>,
        currentIndex: Int,
        repeatMode: Int,
        unavailableKeys: Set<String> = emptySet(),
        keyOf: (T) -> String,
    ): Int {
        if (queue.isEmpty()) return C.INDEX_UNSET
        val start = currentIndex - 1
        val wrapRepeat = repeatMode == Player.REPEAT_MODE_ALL
        val ordered = (start downTo 0) + if (wrapRepeat) (queue.lastIndex downTo 0) else emptyList()
        return ordered.firstOrNull { keyOf(queue[it]) !in unavailableKeys }
            ?: C.INDEX_UNSET
    }
}

/**
 * Shuffled [indices] with songs not yet played this queue session first.
 * A plain random pick ignored history, so users heard the same songs again
 * and never reached the rest of the playlist.
 */
internal fun MusicPlayer.shuffledUnplayedFirst(indices: List<Int>, keyAt: (Int) -> String?): List<Int> {
        val played = synchronized(playHistory) { playHistory.toHashSet() }
        return indices.shuffled().sortedBy { keyAt(it) in played }
    }

/** [wrap] = false stops at the end of the queue (shuffle: no replays) instead
 *  of starting the songs over; manual next keeps wrapping when persistent or sequential. */
internal fun MusicPlayer.nextQueueIndex(
    state: MusicPlayerState,
    wrap: Boolean = true,
    persistentShuffle: Boolean = persistentShufflePref,
): Int {
        val played = synchronized(playHistory) { playHistory.toHashSet() }
        return ShuffleQueueLogic.resolveNextIndex(
            queue = state.queue,
            currentIndex = state.currentIndex,
            shuffleEnabled = state.shuffleEnabled,
            persistentShuffle = persistentShuffle,
            repeatMode = state.repeatMode,
            unavailableKeys = unavailableMediaIds,
            playedKeys = played,
            wrapWhenPersistentOrSequential = wrap,
            keyOf = PlayableTrack::mediaIdKey,
        )
    }

internal fun MusicPlayer.previousQueueIndex(state: MusicPlayerState): Int =
    ShuffleQueueLogic.resolvePreviousIndex(
        queue = state.queue,
        currentIndex = state.currentIndex,
        repeatMode = state.repeatMode,
        unavailableKeys = unavailableMediaIds,
        keyOf = PlayableTrack::mediaIdKey,
    )

@MainThread
internal fun MusicPlayer.enableOneTimeShuffleOnPlayer(currentIndex: Int) {
        val count = player.mediaItemCount
        if (count == 0) {
            oneTimeShuffleActive = true
            player.shuffleModeEnabled = false
            return
        }
        val safeCurrent = currentIndex.coerceIn(0, count - 1)
        val allItems = (0 until count).map { player.getMediaItemAt(it) }
        val currentTracks = allItems.map { it.toPlayableTrack() }
        val plan = ShuffleQueueLogic.enableOneTimeShuffle(
            queue = currentTracks.indices.toList(),
            currentIndex = safeCurrent,
            originalSnapshot = null,
        )
        originalUnshuffledQueue = originalUnshuffledQueue ?: currentTracks
        if (count > 1) {
            if (safeCurrent != 0) {
                player.moveMediaItem(safeCurrent, 0)
            }
            val restMediaItems = plan.queue.drop(1).map { allItems[it] }
            player.removeMediaItems(1, count)
            player.addMediaItems(1, restMediaItems)
        }
        player.shuffleModeEnabled = false
        oneTimeShuffleActive = true
    }

@MainThread
internal fun MusicPlayer.disableOneTimeShuffleOnPlayer(currentIndex: Int) {
        val count = player.mediaItemCount
        val savedOriginal = originalUnshuffledQueue
        oneTimeShuffleActive = false
        originalUnshuffledQueue = null
        player.shuffleModeEnabled = false
        if (count == 0 || savedOriginal.isNullOrEmpty()) return
        val safeCurrent = currentIndex.coerceIn(0, count - 1)
        val liveItems = (0 until count).map { player.getMediaItemAt(it) }
        val liveTracks = liveItems.map { it.toPlayableTrack() }
        val plan = ShuffleQueueLogic.disableOneTimeShuffle(
            shuffledQueue = liveTracks,
            currentIndex = safeCurrent,
            originalQueue = savedOriginal,
            keyOf = ShuffleQueueLogic::stableTrackKey,
        )
        val pool = HashMap<String, ArrayDeque<MediaItem>>()
        for (i in 0 until count) {
            if (i == safeCurrent) continue
            val k = ShuffleQueueLogic.stableTrackKey(liveTracks[i])
            pool.getOrPut(k) { ArrayDeque() }.addLast(liveItems[i])
        }
        fun takeMediaItem(track: PlayableTrack): MediaItem {
            val k = ShuffleQueueLogic.stableTrackKey(track)
            return pool[k]?.removeFirstOrNull() ?: track.toMediaItem()
        }
        val targetIndex = plan.currentIndex.coerceIn(plan.queue.indices)
        val beforeItems = plan.queue.subList(0, targetIndex).map(::takeMediaItem)
        val afterItems = plan.queue.subList(targetIndex + 1, plan.queue.size).map(::takeMediaItem)

        if (safeCurrent + 1 < count) {
            player.removeMediaItems(safeCurrent + 1, count)
        }
        if (safeCurrent > 0) {
            player.removeMediaItems(0, safeCurrent)
        }
        if (beforeItems.isNotEmpty()) {
            player.addMediaItems(0, beforeItems)
        }
        if (afterItems.isNotEmpty()) {
            player.addMediaItems(beforeItems.size + 1, afterItems)
        }
    }

@MainThread
internal fun MusicPlayer.flattenPersistentShuffleToOneTimeOnPlayer() {
        val count = player.mediaItemCount
        if (count == 0) {
            player.shuffleModeEnabled = false
            oneTimeShuffleActive = true
            return
        }
        val liveOrder = currentPlayOrder(player)
        val currentIndex = player.currentMediaItemIndex.coerceIn(0, count - 1)
        val allItems = (0 until count).map { player.getMediaItemAt(it) }
        val unshuffledTracks = allItems.map { it.toPlayableTrack() }
        originalUnshuffledQueue = originalUnshuffledQueue ?: unshuffledTracks
        if (count > 1 && liveOrder.size == count) {
            val pos = liveOrder.indexOf(currentIndex).takeIf { it >= 0 } ?: 0
            val rotatedIndices = liveOrder.subList(pos, liveOrder.size) + liveOrder.subList(0, pos)
            if (currentIndex != 0) {
                player.moveMediaItem(currentIndex, 0)
            }
            val restMediaItems = rotatedIndices.drop(1).map { allItems[it] }
            player.removeMediaItems(1, count)
            player.addMediaItems(1, restMediaItems)
        }
        player.shuffleModeEnabled = false
        oneTimeShuffleActive = true
        preloadNextQueueItem(player.currentMediaItemIndex)
        refresh(player)
    }

    @MainThread
internal fun MusicPlayer.playPendingQueueItem(index: Int, pendingState: MusicPlayerState) {
        if (index !in pendingState.queue.indices) return
        if (index in 0 until player.mediaItemCount) {
            resolveAndPlayQueueItem(index)
        } else {
            startResolvedQueuePlayback(
                tracks = pendingState.queue,
                selectedIndex = index,
                startPositionMs = 0L,
                sourceLabel = pendingState.sourceLabel,
                endlessDiscover = pendingState.isEndlessQueue,
                startShuffled = pendingState.shuffleEnabled,
                preserveExistingQueueOrder = true,
            )
        }
    }

    /**
     * Takes over playback of [index] after (re)installing its media source.
     * The loader thread may have already opened its own resolve of this
     * placeholder and started audible playback while the app-level resolve
     * was still in flight: rewinding to zero then replays the intro seconds
     * ("plays a moment then jumps"). Only seek when still at the very start.
     */
    @MainThread
internal fun MusicPlayer.takeOverPlayback(index: Int, expectedMediaId: String) {
        val alreadyAudible = player.currentMediaItemIndex == index &&
            player.currentMediaItem?.mediaId == expectedMediaId &&
            (player.isPlaying || player.currentPosition > 1_500L)
        if (!alreadyAudible) {
            player.seekToDefaultPosition(index)
        }
        resetRenderWatchdog()
        if (player.playbackState == Player.STATE_IDLE) {
            PlaybackStartupLog.prepareStart()
            player.prepare()
        }
        player.play()
    }

    @MainThread
internal fun MusicPlayer.resolveAndPlayQueueItem(index: Int) {
        if (index !in 0 until player.mediaItemCount) {
            playPendingQueueItem(index, _state.value)
            return
        }
        cancelCrossfade()
        ensureForegroundService()
        val generation = playRequestGeneration.incrementAndGet()
        playRequest?.cancel()
        cancelStaleResolverWork("queue-item")
        cancelActiveUpgrade()
        preloadJob?.cancel()
        unavailableSkipJob?.cancel()
        unavailableSkipJob = null
        val mediaItem = player.getMediaItemAt(index)
        val track = mediaItem.toPlayableTrack()
        PlaybackStartupLog.begin(
            trackId = track.internalTrackId?.takeIf { it.isNotBlank() } ?: track.videoId.orEmpty(),
            youtubeVideoId = track.videoId,
        )
        track.playbackIdentity()?.let { streamResolver.playback.begin(it) }
        val requestedQuality = selectedStreamingQuality(latestPlaybackSettings)
        val rawPrepared = mediaItem.localConfiguration?.customCacheKey?.let(preparedStreams::get)
            ?: preparedStreams.values.firstOrNull { it.url == mediaItem.localConfiguration?.uri?.toString() }
        val profileMismatch = rawPrepared != null &&
            !isStreamAllowedForQuality(rawPrepared, requestedQuality, latestPlaybackSettings)
        val prepared = rawPrepared?.takeUnless { profileMismatch }
            ?: findPreparedStreamFor(track, track.videoId, track.mediaIdKey() in losslessBypassMediaIds)
        val preparedMatches = prepared?.youtubeCandidate?.videoId?.let { resolvedId ->
            track.videoId.isNullOrBlank() || resolvedId == track.videoId
        } ?: true
        if (!preparedMatches && prepared != null) {
            preparedStreams.remove(prepared.cacheKey)
            track.playbackIdentity()?.let { streamResolver.cache.invalidate(it) }
        }
        val isLocal = mediaItem.mediaId.startsWith("local:") || track.isLocalTrack()
        val hasNonPlaceholderUri = mediaItem.localConfiguration?.uri?.scheme?.let { it != "lastwave" } == true
        val isAlreadyResolved = if (isLocal) {
            hasNonPlaceholderUri
        } else {
            hasNonPlaceholderUri && !profileMismatch && (prepared == null || !prepared.isExpired())
        }
        if (preparedMatches && isAlreadyResolved) {
            // Already resolved: publish quality synchronously so the badge is
            // correct from the first frame (no transition may fire for a
            // same-item play to republish it later). The stream is also staged,
            // because takeOverPlayback's seek fires the transition reset that
            // clears these very fields.
            prepared?.let { stream ->
                stagePendingQuality(mediaItem.mediaId, stream)
                stagePendingQuality(track.mediaIdKey(), stream)
                track.videoId?.let { stagePendingQuality(it, stream) }
                publishResolvedQuality(stream, expectedMediaId = mediaItem.mediaId)
            }
            PlaybackStartupLog.cacheLookupStart()
            PlaybackStartupLog.cacheLookupEnd(hit = true)
            takeOverPlayback(index, mediaItem.mediaId)
            val playing = prepared
            scheduleStartupFollowUp { preloadNextQueueItem(index) }
            if (playing != null && !playing.isLossless && playing.audioCodec != "DOLBY ATMOS") {
                scheduleQualityUpgrade(
                    track = track,
                    expectedMediaId = mediaItem.mediaId,
                    generation = generation,
                    currentStream = playing,
                )
            }
            return
        }

        val expectedMediaId = mediaItem.mediaId
        resetPlayhead(0L, expectedMediaId)
        resetRenderWatchdog()
        resolvingMediaIds[expectedMediaId] = generation
        // Screen-off continuity: do NOT player.pause() here. Pausing drops
        // ExoPlayer's WAKE_MODE_NETWORK wake/wifi lock and lets refresh()
        // flip state to not-playing, which releases the service WifiLock â€”
        // then a locked-screen resolve stalls until unlock. The placeholder
        // at [index] has no audible output yet, and when jumping from a
        // different playing index keeping playback running avoids a silent
        // gap; the seek below cuts over once the lossless-first stream is
        // ready.
        _state.update {
            it.copy(
                current = track,
                currentIndex = index,
                positionMs = 0L,
                bufferedPositionMs = 0L,
                durationMs = track.durationMs ?: findKnownDuration(track) ?: 0L,
                isPlaying = true,
                isBuffering = true,
                error = null,
                // New track owns its badge: clear quality so the pill never
                // shows the previous song's format, and never lets a stale
                // explicit badge shield a generic publish via the
                // same-track guard in publishResolvedQuality.
                audioCodec = null,
                bitrateKbps = null,
                isLossless = false,
                bitDepth = null,
                samplingRateKHz = null,
            )
        }
        decodedSampleRateHz = 0
        playRequest = applicationScope.launch(Dispatchers.IO) {
            try {
                val resolved = resolveTrackAudioStreamWithRetry(
                    track = track,
                    videoId = track.videoId,
                    allowLossless = expectedMediaId !in losslessBypassMediaIds,
                )
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.Main.immediate) {
                    if (generation != playRequestGeneration.get() ||
                        index !in 0 until player.mediaItemCount ||
                        player.getMediaItemAt(index).mediaId != expectedMediaId
                    ) {
                        return@withContext
                    }
                    registerPreparedStream(resolved)
                    // Staged so the transition reset fired by takeOverPlayback's
                    // seek hands the resolved depth straight back instead of
                    // blanking the pill.
                    stagePendingQuality(expectedMediaId, resolved)
                    stagePendingQuality(track.mediaIdKey(), resolved)
                    track.videoId?.let { stagePendingQuality(it, resolved) }
                    publishResolvedQuality(resolved, expectedMediaId = expectedMediaId)
                    applyDacRoutingFor(resolved)
                    logStreamEvent("queue-prepare", resolved, retry = 0)
                    cacheCurrentTrackStream(resolved)
                    learnQueueVideoId(index, track, resolved.youtubeCandidate?.videoId, resolved.rematchedFromVideoId)
                    replaceMediaItemPreservingShuffle(index, track.toMediaItem(resolved))
                    takeOverPlayback(index, expectedMediaId)
                    enrichUpcomingQueue(index)
                    if (!resolved.isLossless && resolved.audioCodec != "DOLBY ATMOS") {
                        scheduleQualityUpgrade(
                            track = track,
                            expectedMediaId = expectedMediaId,
                            generation = generation,
                            currentStream = resolved,
                        )
                    }
                    scheduleStartupFollowUp {
                        preloadNextQueueItem(index)
                        scheduleDeferredRecommendations()
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                logResolutionFailure(track, "queue-resolve", 0, error)
                if (expectedMediaId !in losslessBypassMediaIds) {
                    losslessBypassMediaIds += expectedMediaId
                    val ytFallback = try {
                        resolveYoutubeTrackAudioStream(track, track.videoId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        null
                    }
                    if (ytFallback != null && generation == playRequestGeneration.get()) {
                        withContext(Dispatchers.Main.immediate) {
                            if (generation != playRequestGeneration.get() ||
                                index !in 0 until player.mediaItemCount ||
                                player.getMediaItemAt(index).mediaId != expectedMediaId
                            ) {
                                return@withContext
                            }
                            registerPreparedStream(ytFallback)
                            stagePendingQuality(expectedMediaId, ytFallback)
                            stagePendingQuality(track.mediaIdKey(), ytFallback)
                            track.videoId?.let { stagePendingQuality(it, ytFallback) }
                            publishResolvedQuality(ytFallback, expectedMediaId = expectedMediaId)
                            applyDacRoutingFor(ytFallback)
                            logStreamEvent("queue-prepare-yt-fallback", ytFallback, retry = 0)
                            cacheCurrentTrackStream(ytFallback)
                            replaceMediaItemPreservingShuffle(index, track.toMediaItem(ytFallback))
                            takeOverPlayback(index, expectedMediaId)
                            enrichUpcomingQueue(index)
                            scheduleDeferredRecommendations()
                            preloadNextQueueItem(index)
                            scheduleQualityUpgrade(
                                track = track,
                                expectedMediaId = expectedMediaId,
                                generation = generation,
                                currentStream = ytFallback,
                            )
                        }
                        return@launch
                    }
                }
                withContext(Dispatchers.Main.immediate) {
                    if (generation == playRequestGeneration.get()) {
                        // This item is audibly playing right now (TTL refresh or
                        // a mid-song re-resolve): a failed re-resolve must not
                        // stop or skip it. Hand it to the keep-alive loop, which
                        // restores the stream in place.
                        val aliveHere = playerDelegate.isInitialized() &&
                            player.isPlaying &&
                            player.currentMediaItemIndex == index &&
                            player.currentMediaItem?.mediaId == expectedMediaId
                        if (aliveHere) {
                            startPlaybackRecoveryLoop(
                                failedIndex = index,
                                failedMediaId = expectedMediaId,
                                track = track,
                                videoId = track.videoId,
                                generation = generation,
                                resumePositionMs = player.currentPosition.coerceAtLeast(0L),
                                failedLosslessStream = false,
                                rejectedUrl = null,
                            )
                            return@withContext
                        }
                        _state.update {
                            it.copy(
                                isPlaying = false,
                                isBuffering = false,
                                error = error.message ?: "Unable to resolve audio",
                            )
                        }
                        scheduleUnavailableMediaSkip(index, expectedMediaId, generation, error, allowAutoSkip = true, exhausted = true)
                    }
                }
            } finally {
                resolvingMediaIds.remove(expectedMediaId, generation)
            }
        }
    }

internal fun MusicPlayer.cancelActiveUpgrade() {
        activeUpgradeJob?.cancel()
        activeUpgradeJob = null
        activeUpgradeMediaId = null
        activeUpgradeTargetQuality = null
        activeUpgradeDeferred?.cancel()
        activeUpgradeDeferred = null
    }

internal fun MusicPlayer.cancelPendingPlaybackResolution() {
        playRequestGeneration.incrementAndGet()
        playRequest?.cancel()
        playRequest = null
        playbackRecoveryJob?.cancel()
        playbackRecoveryJob = null
        cancelStaleResolverWork("playback-cancelled")
        cancelActiveUpgrade()
        preloadJob?.cancel()
        preloadJob = null
        currentTrackCacheJob?.cancel()
        currentTrackCacheJob = null
        unavailableSkipJob?.cancel()
        unavailableSkipJob = null
    }

    /**
     * Drops every in-flight YouTube resolve except the one the caller is
     * about to start. Shared [resolutionRequests] live on [applicationScope],
     * so cancelling [playRequest] alone used to let them finish and publish.
     */
internal fun MusicPlayer.cancelStaleResolverWork(reason: String) {
        startupFallbackJob?.cancel()
        startupFallbackJob = null
        streamResolver.cancelAll(reason)
        resolutionRequests.values.forEach { it.second.cancel() }
        resolutionRequests.clear()
        queueEnrichmentJob?.cancel()
        queueEnrichmentJob = null
        startupFollowUp = null
    }

internal fun MusicPlayer.scheduleStartupFollowUp(block: () -> Unit) {
        startupFollowUp = block
    }

internal fun MusicPlayer.runStartupFollowUp() {
        val block = startupFollowUp ?: return
        startupFollowUp = null
        block()
    }

