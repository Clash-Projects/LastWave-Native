package com.lastwave.app.playback.autodj

import android.os.SystemClock
import android.util.Log
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.music.isCooperativeCancellation
import com.lastwave.app.data.music.resolutionFailureDetail
import com.lastwave.app.playback.AutoDJDecision
import com.lastwave.app.playback.AutoDJLatencyStats
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.NativeAutoDJEngine
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.TrackLifecycleStage
import com.lastwave.app.playback.mediaIdKey
import com.lastwave.app.playback.resolve.StreamResolveCoordinator
import com.lastwave.app.playback.resolve.playbackIdentity
import com.lastwave.app.playback.toPlayerStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Host interface implemented by [com.lastwave.app.playback.MusicPlayer] to decouple
 * player mechanics from Auto DJ queue evaluation.
 */
interface AutoDJPlaybackHost {
    fun getTrackAt(index: Int): PlayableTrack?
    fun getQueueSize(): Int
    fun getCurrentIndex(): Int
    fun isCurrentItem(index: Int): Boolean
    fun registerResolvedStream(index: Int, track: PlayableTrack, stream: MusicPlayer.ResolvedStream)
    fun armStandbyForNext(index: Int, track: PlayableTrack)
    fun triggerAutoTransition(overlapMs: Long)
    fun setLateTrackBuffering(isWaiting: Boolean)
    fun skipToNextPlayableItem(failedIndex: Int)
    fun requestQueueRefill()
    fun requestTrackResolution(index: Int, track: PlayableTrack)
    fun decodeHeadWindowForNext(index: Int, track: PlayableTrack)
    fun getRecentPlayedTrackIds(): List<String>
    fun scoreAndReorderCandidates(fromIndex: Int, count: Int, currentBpm: Float)
    fun getStandbyBufferedMs(): Long
}

sealed interface AutoDJStatus {
    object Disabled : AutoDJStatus
    object Idle : AutoDJStatus
    data class Resolving(val title: String, val index: Int, val attempt: Int) : AutoDJStatus
    data class Decoding(val title: String, val index: Int) : AutoDJStatus
    data class ArmedReady(val title: String, val index: Int) : AutoDJStatus
    data class Transitioning(val title: String, val index: Int) : AutoDJStatus
    data class WaitingLate(val title: String, val index: Int) : AutoDJStatus
}

/**
 * High-performance autonomous queue coordinator and asynchronous prefetch controller.
 * Evaluates queue readiness, manages multi-stage track resolution lifecycle,
 * bounds concurrent resolvers to 2, and preserves armed standby for manual skip-mixing.
 */
@Singleton
class AutoDJController @Inject constructor(
    private val nativeEngine: NativeAutoDJEngine,
    private val settingsPreferences: SettingsPreferences,
) {
    private var host: AutoDJPlaybackHost? = null
    private var scope: CoroutineScope? = null
    private var streamResolver: StreamResolveCoordinator? = null

    private val trackStages = ConcurrentHashMap<String, TrackLifecycleStage>()
    private val retryCounts = ConcurrentHashMap<String, Int>()
    private val inFlightJobs = ConcurrentHashMap<String, Job>()

    // Bounded concurrency: maxConcurrentResolvers = 2
    private val prefetchSemaphore = Semaphore(2)

    private val _status = MutableStateFlow<AutoDJStatus>(AutoDJStatus.Disabled)
    val status: StateFlow<AutoDJStatus> = _status.asStateFlow()

    @Volatile
    var isEnabled: Boolean = false
        private set

    @Volatile
    var isAutomixEnabled: Boolean = false
        private set

    @Volatile
    var isAutomixOnSkip: Boolean = true
        private set

    fun attachHost(
        host: AutoDJPlaybackHost,
        scope: CoroutineScope,
        streamResolver: StreamResolveCoordinator,
    ) {
        this.host = host
        this.scope = scope
        this.streamResolver = streamResolver
        scope.launch(Dispatchers.Main.immediate) {
            settingsPreferences.settings.collect { settings ->
                isEnabled = settings.autoDJEnabled
                isAutomixEnabled = settings.automixEnabled
                isAutomixOnSkip = settings.automixOnSkip

                if (!isEnabled && !isAutomixEnabled) {
                    cancelAllPrefetches("autodj-and-automix-disabled")
                    _status.value = AutoDJStatus.Disabled
                } else {
                    if (_status.value is AutoDJStatus.Disabled) {
                        _status.value = AutoDJStatus.Idle
                    }
                    val h = host
                    if (h != null && isEnabled) {
                        val unplayed = maxOf(0, h.getQueueSize() - h.getCurrentIndex() - 1)
                        if (unplayed <= 2) {
                            h.requestQueueRefill()
                        }
                    }
                }
            }
        }
    }

    fun detachHost() {
        cancelAllPrefetches("host-detached")
        this.host = null
        this.scope = null
    }

    fun getTrackStage(trackId: String): TrackLifecycleStage {
        return trackStages[trackId] ?: TrackLifecycleStage.QUEUED
    }

    fun setTrackStage(trackId: String, stage: TrackLifecycleStage) {
        trackStages[trackId] = stage
    }

    fun onQueueChanged() {
        val h = host ?: return
        val currentKeys = HashSet<String>()
        val queueSize = h.getQueueSize()
        val currentIndex = h.getCurrentIndex()

        for (i in 0 until queueSize) {
            h.getTrackAt(i)?.mediaIdKey()?.let { currentKeys.add(it) }
        }
        val iterator = trackStages.keys.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key !in currentKeys) {
                inFlightJobs.remove(key)?.cancel()
                iterator.remove()
                retryCounts.remove(key)
            }
        }

        // Smart track ordering: when Auto DJ is ON, score next 5 candidates
        if (isEnabled && queueSize - currentIndex - 1 >= 2) {
            h.scoreAndReorderCandidates(currentIndex + 1, 5, 120f)
        }

        // Always resolve index + 1 immediately on queue change
        if (currentIndex in 0 until queueSize - 1) {
            val nextIndex = currentIndex + 1
            val nextTrack = h.getTrackAt(nextIndex)
            if (nextTrack != null) {
                val nextKey = nextTrack.mediaIdKey()
                val stage = getTrackStage(nextKey)
                if (stage == TrackLifecycleStage.QUEUED) {
                    prefetchTrack(nextIndex, nextTrack)
                }
            }
        }

        if (isEnabled) {
            val unplayed = maxOf(0, queueSize - currentIndex - 1)
            if (unplayed <= 2) {
                h.requestQueueRefill()
            }
        }
    }

    fun onTrackTransition(newIndex: Int) {
        val h = host ?: return
        val prevIndex = newIndex - 1
        if (prevIndex >= 0) {
            h.getTrackAt(prevIndex)?.mediaIdKey()?.let { prevKey ->
                trackStages[prevKey] = TrackLifecycleStage.COMPLETED
            }
        }

        val newTrack = h.getTrackAt(newIndex)
        val newKey = newTrack?.mediaIdKey()
        if (newKey != null) {
            trackStages[newKey] = TrackLifecycleStage.PLAYING
        }
        _status.value = AutoDJStatus.Idle

        // Immediately start resolving index + 1 as soon as track starts playing!
        val nextIndex = newIndex + 1
        if (nextIndex in 0 until h.getQueueSize()) {
            val nextTrack = h.getTrackAt(nextIndex)
            if (nextTrack != null) {
                prefetchTrack(nextIndex, nextTrack)
            }
        }
    }

    fun onPlaybackStarted() {
        val h = host ?: return
        val nextIndex = h.getCurrentIndex() + 1
        if (nextIndex in 0 until h.getQueueSize()) {
            val nextTrack = h.getTrackAt(nextIndex)
            if (nextTrack != null && getTrackStage(nextTrack.mediaIdKey()) == TrackLifecycleStage.QUEUED) {
                prefetchTrack(nextIndex, nextTrack)
            }
        }
    }

    fun onDeckBArmedReady(index: Int) {
        val h = host ?: return
        if (index in 0 until h.getQueueSize()) {
            val track = h.getTrackAt(index)
            if (track != null) {
                val key = track.mediaIdKey()
                trackStages[key] = TrackLifecycleStage.ARMED_READY
                _status.value = AutoDJStatus.ArmedReady(track.title, index)
            }
        }
    }

    fun cancelAllPrefetches(reason: String) {
        inFlightJobs.values.forEach { it.cancel() }
        inFlightJobs.clear()
        Log.d(TAG, "[CANCEL_ALL_PREFETCHES] reason=$reason")
    }

    /**
     * Periodic evaluation tick driven by playback ticker (every 50-60ms).
     */
    fun onPlaybackTick(
        currentPositionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        transitionDurationMs: Long,
    ) {
        // Even when Auto DJ is OFF, if automixEnabled is true, keep index + 1 armed for skip-mix
        if (!isEnabled && !isAutomixEnabled) return

        val h = host ?: return
        val currentIndex = h.getCurrentIndex()
        val queueSize = h.getQueueSize()
        if (currentIndex < 0 || queueSize <= 0) return

        val nextIndex = currentIndex + 1
        val unplayedCount = maxOf(0, queueSize - currentIndex - 1)
        val nextTrack = if (nextIndex in 0 until queueSize) h.getTrackAt(nextIndex) else null
        val nextKey = nextTrack?.mediaIdKey() ?: ""
        val nextStage = if (nextKey.isNotEmpty()) getTrackStage(nextKey) else TrackLifecycleStage.COMPLETED
        val retryCount = if (nextKey.isNotEmpty()) retryCounts[nextKey] ?: 0 else 0

        // Eager prefetch trigger within 2s of playback start if track is still queued
        if (isPlaying && currentPositionMs <= 2000L && nextTrack != null && nextStage == TrackLifecycleStage.QUEUED) {
            prefetchTrack(nextIndex, nextTrack)
        }

        // Check if head window decoding has reached ready threshold (>= 8.0s buffered)
        if (nextStage == TrackLifecycleStage.DECODING_HEAD_WINDOW && nextTrack != null) {
            val bufferedMs = h.getStandbyBufferedMs()
            val trackDur = nextTrack.durationMs
            if (bufferedMs >= 8_000L || (trackDur != null && trackDur in 1L..7999L && bufferedMs >= trackDur)) {
                trackStages[nextKey] = TrackLifecycleStage.ARMED_READY
                h.armStandbyForNext(nextIndex, nextTrack)
                _status.value = AutoDJStatus.ArmedReady(nextTrack.title, nextIndex)
                Log.i(TAG, "[HEAD_WINDOW_READY] index=$nextIndex title='${nextTrack.title}' buffered=${bufferedMs}ms >= 8000ms -> ARMED_READY")
            }
        }

        val effectiveStage = if (nextKey.isNotEmpty()) getTrackStage(nextKey) else TrackLifecycleStage.COMPLETED

        val decision = nativeEngine.evaluate(
            currentPositionMs = currentPositionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            transitionDurationMs = transitionDurationMs,
            nextStage = effectiveStage,
            retryCount = retryCount,
            unplayedCount = unplayedCount,
        )

        when (decision) {
            AutoDJDecision.DO_NOTHING -> {
                if (nextTrack != null) {
                    when (nextStage) {
                        TrackLifecycleStage.ARMED_READY -> {
                            _status.value = AutoDJStatus.ArmedReady(nextTrack.title, nextIndex)
                        }
                        TrackLifecycleStage.DECODING_HEAD_WINDOW -> {
                            _status.value = AutoDJStatus.Decoding(nextTrack.title, nextIndex)
                        }
                        TrackLifecycleStage.RESOLVING_STREAM -> {
                            _status.value = AutoDJStatus.Resolving(nextTrack.title, nextIndex, retryCount + 1)
                        }
                        TrackLifecycleStage.TRANSITIONING -> {
                            _status.value = AutoDJStatus.Transitioning(nextTrack.title, nextIndex)
                        }
                        else -> {
                            if (_status.value !is AutoDJStatus.Idle) _status.value = AutoDJStatus.Idle
                        }
                    }
                }
            }

            AutoDJDecision.RESOLVE_NEXT_STREAM -> {
                if (nextTrack != null && nextIndex in 0 until queueSize) {
                    prefetchTrack(nextIndex, nextTrack)
                }
            }

            AutoDJDecision.DECODE_HEAD_WINDOW -> {
                if (nextTrack != null && nextIndex in 0 until queueSize) {
                    trackStages[nextKey] = TrackLifecycleStage.DECODING_HEAD_WINDOW
                    _status.value = AutoDJStatus.Decoding(nextTrack.title, nextIndex)
                    h.decodeHeadWindowForNext(nextIndex, nextTrack)
                }
            }

            AutoDJDecision.ARM_STANDBY -> {
                if (nextTrack != null && nextIndex in 0 until queueSize) {
                    val bufferedMs = h.getStandbyBufferedMs()
                    val trackDur = nextTrack.durationMs
                    if (bufferedMs >= 8_000L || (trackDur != null && trackDur in 1L..7999L && bufferedMs >= trackDur)) {
                        trackStages[nextKey] = TrackLifecycleStage.ARMED_READY
                        h.armStandbyForNext(nextIndex, nextTrack)
                        _status.value = AutoDJStatus.ArmedReady(nextTrack.title, nextIndex)
                    } else {
                        trackStages[nextKey] = TrackLifecycleStage.DECODING_HEAD_WINDOW
                        _status.value = AutoDJStatus.Decoding(nextTrack.title, nextIndex)
                    }
                }
            }

            AutoDJDecision.TRIGGER_TRANSITION -> {
                if (isEnabled || isAutomixEnabled) {
                    trackStages[nextKey] = TrackLifecycleStage.TRANSITIONING
                    _status.value = AutoDJStatus.Transitioning(nextTrack?.title.orEmpty(), nextIndex)
                    h.setLateTrackBuffering(false)
                    h.triggerAutoTransition(transitionDurationMs)
                }
            }

            AutoDJDecision.WAIT_FOR_LATE_TRACK -> {
                if (nextTrack != null) {
                    Log.i(TAG, "[WAIT_FOR_LATE_TRACK] Holding for '${nextTrack.title}' (stage=$nextStage)")
                    _status.value = AutoDJStatus.WaitingLate(nextTrack.title, nextIndex)
                    h.setLateTrackBuffering(true)
                }
            }

            AutoDJDecision.SKIP_UNPLAYABLE -> {
                Log.w(TAG, "[SKIP_UNPLAYABLE] Track index $nextIndex permanently failed after $retryCount retries")
                trackStages[nextKey] = TrackLifecycleStage.SKIPPED_UNPLAYABLE
                h.setLateTrackBuffering(false)
                h.skipToNextPlayableItem(nextIndex)
            }

            AutoDJDecision.REFILL_QUEUE -> {
                if (isEnabled) {
                    Log.i(TAG, "[REFILL_QUEUE] Queue approaching exhaustion ($unplayedCount tracks remaining)")
                    h.requestQueueRefill()
                }
            }

            AutoDJDecision.REFRESH_EXPIRED_STREAM -> {
                if (nextTrack != null) {
                    Log.i(TAG, "[REFRESH_EXPIRED_STREAM] Silently refreshing expired stream for '${nextTrack.title}'")
                    prefetchTrack(nextIndex, nextTrack, isRefresh = true)
                }
            }
        }
    }

    private fun prefetchTrack(index: Int, track: PlayableTrack, isRefresh: Boolean = false) {
        val key = track.mediaIdKey()
        if (inFlightJobs[key]?.isActive == true) return
        val currentStage = getTrackStage(key)
        if (!isRefresh && (currentStage == TrackLifecycleStage.ARMED_READY || currentStage == TrackLifecycleStage.PLAYING)) return

        val coroutineScope = scope ?: return
        val identity = track.playbackIdentity()
        if (identity == null) {
            trackStages[key] = TrackLifecycleStage.RESOLVING_STREAM
            _status.value = AutoDJStatus.Resolving(track.title, index, 1)
            host?.requestTrackResolution(index, track)
            return
        }

        val attempt = (retryCounts[key] ?: 0) + 1
        trackStages[key] = TrackLifecycleStage.RESOLVING_STREAM
        _status.value = AutoDJStatus.Resolving(track.title, index, attempt)

        val resolver = streamResolver ?: return
        val job = coroutineScope.launch(Dispatchers.IO) {
            // Respect bounded concurrency (maxConcurrentResolvers = 2)
            prefetchSemaphore.withPermit {
                val startMs = SystemClock.elapsedRealtime()
                try {
                    Log.d(TAG, "[PREFETCH_START] index=$index title='${track.title}' attempt=$attempt")
                    resolver.prefetchNext(identity, track.durationMs) { cacheEntry ->
                        val elapsedMs = SystemClock.elapsedRealtime() - startMs
                        nativeEngine.recordLatency(elapsedMs, wasSuccess = true)

                        val resolvedStream = cacheEntry.toPlayerStream()
                        trackStages[key] = TrackLifecycleStage.STREAM_RESOLVED
                        _status.value = AutoDJStatus.Decoding(track.title, index)
                        Log.i(TAG, "[PREFETCH_SUCCESS] index=$index title='${track.title}' in ${elapsedMs}ms")

                        host?.registerResolvedStream(index, track, resolvedStream)

                        // Immediately decode first 35 seconds into Deck B ring buffer
                        host?.decodeHeadWindowForNext(index, track)
                        trackStages[key] = TrackLifecycleStage.DECODING_HEAD_WINDOW
                        _status.value = AutoDJStatus.Decoding(track.title, index)

                        // Optionally warm-prefetch stream URL for index + 2
                        prefetchWarmUpcoming(index + 1)
                    }
                } catch (error: Throwable) {
                    if (error.isCooperativeCancellation()) {
                        trackStages[key] = TrackLifecycleStage.CANCELLED
                        throw error
                    }
                    val elapsedMs = SystemClock.elapsedRealtime() - startMs
                    nativeEngine.recordLatency(elapsedMs, wasSuccess = false)
                    retryCounts[key] = attempt

                    if (attempt < 2) {
                        trackStages[key] = TrackLifecycleStage.FAILED_RETRYING
                        val backoffMs = 1000L * (1 shl (attempt - 1))
                        Log.w(TAG, "[PREFETCH_RETRY_SCHEDULED] index=$index backoff=${backoffMs}ms")
                        delay(backoffMs)
                        prefetchTrack(index, track)
                    } else {
                        trackStages[key] = TrackLifecycleStage.SKIPPED_UNPLAYABLE
                        val detail = resolutionFailureDetail(error)
                        Log.w(TAG, "[PREFETCH_FAILED_PERMANENT] index=$index title='${track.title}': $detail")
                        host?.skipToNextPlayableItem(index)
                    }
                } finally {
                    inFlightJobs.remove(key)
                }
            }
        }
        inFlightJobs[key] = job
    }

    private fun prefetchWarmUpcoming(upcomingIndex: Int) {
        val h = host ?: return
        if (upcomingIndex !in 0 until h.getQueueSize()) return
        val upcomingTrack = h.getTrackAt(upcomingIndex) ?: return
        val upcomingKey = upcomingTrack.mediaIdKey()
        if (getTrackStage(upcomingKey) != TrackLifecycleStage.QUEUED || inFlightJobs[upcomingKey]?.isActive == true) return
        val identity = upcomingTrack.playbackIdentity() ?: return
        val resolver = streamResolver ?: return

        val coroutineScope = scope ?: return
        val job = coroutineScope.launch(Dispatchers.IO) {
            prefetchSemaphore.withPermit {
                try {
                    Log.d(TAG, "[WARM_PREFETCH_START] index=$upcomingIndex title='${upcomingTrack.title}'")
                    resolver.prefetchNext(identity, upcomingTrack.durationMs) { cacheEntry ->
                        val resolvedStream = cacheEntry.toPlayerStream()
                        trackStages[upcomingKey] = TrackLifecycleStage.STREAM_RESOLVED
                        host?.registerResolvedStream(upcomingIndex, upcomingTrack, resolvedStream)
                        Log.i(TAG, "[WARM_PREFETCH_DONE] index=$upcomingIndex title='${upcomingTrack.title}'")
                    }
                } catch (ignored: Throwable) {
                    // Secondary warm prefetch failure is non-fatal
                } finally {
                    inFlightJobs.remove(upcomingKey)
                }
            }
        }
        inFlightJobs[upcomingKey] = job
    }

    fun getLatencyStats(transitionDurationMs: Long): AutoDJLatencyStats {
        return nativeEngine.getLatencyStats(transitionDurationMs)
    }

    companion object {
        private const val TAG = "AutoDJController"

        /**
         * Scores candidate track for BPM proximity (±10% or half/double-time ratio 0.5x / 2.0x)
         * and energy continuity.
         */
        fun scoreBpmAndEnergy(currentBpm: Float, candidateBpm: Float): Float {
            val cBpm = if (currentBpm in 50f..220f) currentBpm else 120f
            val candBpm = if (candidateBpm in 50f..220f) candidateBpm else 120f

            val ratio = cBpm / candBpm
            val isExact = abs(ratio - 1.0f) <= 0.10f
            val isHalf = abs(ratio - 0.5f) <= 0.05f
            val isDouble = abs(ratio - 2.0f) <= 0.10f

            return when {
                isExact -> 1.0f - abs(ratio - 1.0f)
                isHalf -> 0.85f - abs(ratio - 0.5f)
                isDouble -> 0.85f - abs(ratio - 2.0f)
                else -> maxOf(0.1f, 1.0f - abs(ratio - 1.0f).coerceAtMost(1.0f))
            }
        }
    }
}
