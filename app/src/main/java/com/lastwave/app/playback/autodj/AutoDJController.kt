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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

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
}

sealed interface AutoDJStatus {
    object Disabled : AutoDJStatus
    object Idle : AutoDJStatus
    data class Resolving(val title: String, val index: Int, val attempt: Int) : AutoDJStatus
    data class Ready(val title: String, val index: Int) : AutoDJStatus
    data class WaitingLate(val title: String, val index: Int) : AutoDJStatus
}

/**
 * High-performance autonomous queue coordinator based on Mixxx AutoDJ architecture.
 * Evaluates queue readiness, manages multi-stage track resolution lifecycle,
 * and tracks predictive resolution latency.
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

    private val _status = MutableStateFlow<AutoDJStatus>(AutoDJStatus.Idle)
    val status: StateFlow<AutoDJStatus> = _status.asStateFlow()

    @Volatile
    var isEnabled: Boolean = true
        private set

    fun attachHost(
        host: AutoDJPlaybackHost,
        scope: CoroutineScope,
        streamResolver: StreamResolveCoordinator,
    ) {
        this.host = host
        this.scope = scope
        this.streamResolver = streamResolver
        scope.launch {
            settingsPreferences.settings.collect { settings ->
                isEnabled = settings.autoDJEnabled
                if (!isEnabled) {
                    cancelAllPrefetches("autodj-disabled")
                    _status.value = AutoDJStatus.Disabled
                } else if (_status.value is AutoDJStatus.Disabled) {
                    _status.value = AutoDJStatus.Idle
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
        // Clear cached stages for items no longer in queue to avoid memory leaks
        val h = host ?: return
        val currentKeys = HashSet<String>()
        val queueSize = h.getQueueSize()
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
    }

    fun onTrackTransition(newIndex: Int) {
        val h = host ?: return
        val newTrack = h.getTrackAt(newIndex)
        val newKey = newTrack?.mediaIdKey()
        if (newKey != null) {
            trackStages[newKey] = TrackLifecycleStage.PLAYING
        }
        _status.value = AutoDJStatus.Idle
    }

    fun cancelAllPrefetches(reason: String) {
        inFlightJobs.values.forEach { it.cancel() }
        inFlightJobs.clear()
        Log.d(TAG, "[CANCEL_ALL_PREFETCHES] reason=$reason")
    }

    /**
     * Periodic evaluation tick driven by playback ticker (every 60-250ms).
     */
    fun onPlaybackTick(
        currentPositionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        transitionDurationMs: Long,
    ) {
        if (!isEnabled) return
        val h = host ?: return
        val currentIndex = h.getCurrentIndex()
        val queueSize = h.getQueueSize()
        if (currentIndex < 0 || queueSize <= 0) return

        val nextIndex = currentIndex + 1
        val unplayedCount = maxOf(0, queueSize - currentIndex - 1)
        val nextTrack = if (nextIndex in 0 until queueSize) h.getTrackAt(nextIndex) else null
        val nextKey = nextTrack?.mediaIdKey() ?: ""
        val nextStage = if (nextKey.isNotEmpty()) getTrackStage(nextKey) else TrackLifecycleStage.FINISHED
        val retryCount = if (nextKey.isNotEmpty()) retryCounts[nextKey] ?: 0 else 0

        val decision = nativeEngine.evaluate(
            currentPositionMs = currentPositionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            transitionDurationMs = transitionDurationMs,
            nextStage = nextStage,
            retryCount = retryCount,
            unplayedCount = unplayedCount,
        )

        when (decision) {
            AutoDJDecision.DO_NOTHING -> {
                // Keep status in sync
                if (nextTrack != null) {
                    when (nextStage) {
                        TrackLifecycleStage.RESOLVED, TrackLifecycleStage.PREPARING, TrackLifecycleStage.READY -> {
                            _status.value = AutoDJStatus.Ready(nextTrack.title, nextIndex)
                        }
                        TrackLifecycleStage.RESOLVING -> {
                            _status.value = AutoDJStatus.Resolving(nextTrack.title, nextIndex, retryCount + 1)
                        }
                        else -> {
                            if (_status.value !is AutoDJStatus.Idle) _status.value = AutoDJStatus.Idle
                        }
                    }
                }
            }

            AutoDJDecision.PREFETCH_NEXT -> {
                if (nextTrack != null && nextIndex in 0 until queueSize) {
                    prefetchTrack(nextIndex, nextTrack)
                }
            }

            AutoDJDecision.PREPARE_STANDBY -> {
                if (nextTrack != null && nextIndex in 0 until queueSize) {
                    trackStages[nextKey] = TrackLifecycleStage.PREPARING
                    h.armStandbyForNext(nextIndex, nextTrack)
                    _status.value = AutoDJStatus.Ready(nextTrack.title, nextIndex)
                }
            }

            AutoDJDecision.TRIGGER_TRANSITION -> {
                h.setLateTrackBuffering(false)
                h.triggerAutoTransition(transitionDurationMs)
            }

            AutoDJDecision.WAIT_FOR_LATE_TRACK -> {
                // Outgoing track finished or at transition edge, but incoming is still in flight
                if (nextTrack != null) {
                    Log.i(TAG, "[WAIT_FOR_LATE_TRACK] Holding for '${nextTrack.title}' (stage=$nextStage)")
                    _status.value = AutoDJStatus.WaitingLate(nextTrack.title, nextIndex)
                    h.setLateTrackBuffering(true)
                }
            }

            AutoDJDecision.SKIP_FAILED_TRACK -> {
                Log.w(TAG, "[SKIP_FAILED_TRACK] Track at index $nextIndex permanently failed ($retryCount retries)")
                h.setLateTrackBuffering(false)
                h.skipToNextPlayableItem(nextIndex)
            }

            AutoDJDecision.REFILL_QUEUE -> {
                Log.i(TAG, "[REFILL_QUEUE] Unplayed tracks running low ($unplayedCount remaining); requesting refill")
                h.requestQueueRefill()
            }
        }
    }

    private fun prefetchTrack(index: Int, track: PlayableTrack) {
        val key = track.mediaIdKey()
        if (inFlightJobs[key]?.isActive == true) return
        val currentStage = getTrackStage(key)
        if (currentStage == TrackLifecycleStage.READY || currentStage == TrackLifecycleStage.PLAYING) return

        val coroutineScope = scope ?: return
        val identity = track.playbackIdentity() ?: return
        val attempt = (retryCounts[key] ?: 0) + 1
        trackStages[key] = TrackLifecycleStage.RESOLVING
        _status.value = AutoDJStatus.Resolving(track.title, index, attempt)

        val resolver = streamResolver ?: return
        val job = coroutineScope.launch(Dispatchers.IO) {
            val startMs = SystemClock.elapsedRealtime()
            try {
                Log.d(TAG, "[PREFETCH_START] index=$index title='${track.title}' attempt=$attempt")
                resolver.prefetchNext(identity, track.durationMs) { cacheEntry ->
                    val elapsedMs = SystemClock.elapsedRealtime() - startMs
                    nativeEngine.recordLatency(elapsedMs, wasSuccess = true)

                    val resolvedStream = cacheEntry.toPlayerStream()
                    trackStages[key] = TrackLifecycleStage.RESOLVED
                    _status.value = AutoDJStatus.Ready(track.title, index)
                    Log.i(TAG, "[PREFETCH_SUCCESS] index=$index title='${track.title}' in ${elapsedMs}ms")

                    host?.registerResolvedStream(index, track, resolvedStream)

                    // Opportunistically pre-resolve index + 1 (N+2) if queue allows
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
                trackStages[key] = TrackLifecycleStage.FAILED
                val detail = resolutionFailureDetail(error)
                Log.w(TAG, "[PREFETCH_FAILED] index=$index title='${track.title}' attempt=$attempt: $detail")
            } finally {
                inFlightJobs.remove(key)
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
            try {
                Log.d(TAG, "[WARM_PREFETCH_START] index=$upcomingIndex title='${upcomingTrack.title}'")
                resolver.prefetchNext(identity, upcomingTrack.durationMs) { cacheEntry ->
                    val resolvedStream = cacheEntry.toPlayerStream()
                    trackStages[upcomingKey] = TrackLifecycleStage.RESOLVED
                    host?.registerResolvedStream(upcomingIndex, upcomingTrack, resolvedStream)
                    Log.i(TAG, "[WARM_PREFETCH_DONE] index=$upcomingIndex title='${upcomingTrack.title}'")
                }
            } catch (ignored: Throwable) {
                // Secondary warm prefetch failure is non-fatal
            } finally {
                inFlightJobs.remove(upcomingKey)
            }
        }
        inFlightJobs[upcomingKey] = job
    }

    fun getLatencyStats(transitionDurationMs: Long): AutoDJLatencyStats {
        return nativeEngine.getLatencyStats(transitionDurationMs)
    }

    companion object {
        private const val TAG = "AutoDJController"
    }
}
