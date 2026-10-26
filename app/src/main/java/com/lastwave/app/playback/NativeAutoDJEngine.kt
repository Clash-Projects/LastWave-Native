package com.lastwave.app.playback

import android.util.Log
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.sqrt

enum class TrackLifecycleStage(val nativeValue: Int) {
    QUEUED(0),
    RESOLVING(1),
    RESOLVED(2),
    PREPARING(3),
    READY(4),
    PLAYING(5),
    FINISHED(6),
    WAITING_LATE(7),
    FAILED(8),
    CANCELLED(9);

    companion object {
        fun fromNative(value: Int): TrackLifecycleStage = entries.firstOrNull { it.nativeValue == value } ?: QUEUED
    }
}

enum class AutoDJDecision(val nativeValue: Int) {
    DO_NOTHING(0),
    PREFETCH_NEXT(1),
    PREPARE_STANDBY(2),
    TRIGGER_TRANSITION(3),
    WAIT_FOR_LATE_TRACK(4),
    SKIP_FAILED_TRACK(5),
    REFILL_QUEUE(6);

    companion object {
        fun fromNative(value: Int): AutoDJDecision = entries.firstOrNull { it.nativeValue == value } ?: DO_NOTHING
    }
}

data class AutoDJLatencyStats(
    val meanMs: Float,
    val stdDevMs: Float,
    val leadWindowMs: Float,
)

/**
 * Kotlin owner and JNI bridge for the native C++ AutoDJ engine.
 * Coordinates Mixxx-style predictive prefetching, statistical latency estimation,
 * lifecycle state tracking, and autonomous queue decision-making.
 */
@Singleton
class NativeAutoDJEngine @Inject constructor() : Closeable {
    private val handleLock = Any()

    @Volatile
    private var nativeHandle = if (NativeAudioEngine.libraryLoaded) {
        try {
            nativeCreate(maxRetries = 3, refillThreshold = 5)
        } catch (error: LinkageError) {
            Log.e(TAG, "Native AutoDJ engine unavailable", error)
            0L
        }
    } else {
        0L
    }

    val isAvailable: Boolean
        get() = nativeHandle != 0L

    // Pure Kotlin fallback state if native library is unavailable
    private var fallbackMeanMs = 2500.0
    private var fallbackVarianceMs = 1000.0 * 1000.0

    fun recordLatency(latencyMs: Long, wasSuccess: Boolean) {
        if (nativeHandle != 0L) {
            withHandle(Unit) { nativeRecordLatency(it, latencyMs, wasSuccess) }
        } else {
            if (!wasSuccess) {
                fallbackMeanMs = (fallbackMeanMs * 1.15).coerceAtMost(15000.0)
            } else {
                val alpha = 0.2
                val diff = latencyMs.toDouble() - fallbackMeanMs
                fallbackMeanMs += alpha * diff
                fallbackVarianceMs = (1.0 - alpha) * fallbackVarianceMs + alpha * (diff * diff)
            }
        }
    }

    fun evaluate(
        currentPositionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        transitionDurationMs: Long,
        nextStage: TrackLifecycleStage,
        retryCount: Int,
        unplayedCount: Int,
    ): AutoDJDecision {
        if (nativeHandle != 0L) {
            val decisionInt = withHandle(AutoDJDecision.DO_NOTHING.nativeValue) {
                nativeEvaluate(
                    it,
                    currentPositionMs,
                    durationMs,
                    isPlaying,
                    transitionDurationMs,
                    nextStage.nativeValue,
                    retryCount,
                    unplayedCount,
                )
            }
            return AutoDJDecision.fromNative(decisionInt)
        }

        // Fallback pure Kotlin evaluation (maintain 6-song Auto DJ queue horizon)
        if (unplayedCount < 5) {
            return AutoDJDecision.REFILL_QUEUE
        }
        if (nextStage == TrackLifecycleStage.FAILED) {
            return if (retryCount >= 3) AutoDJDecision.SKIP_FAILED_TRACK else AutoDJDecision.PREFETCH_NEXT
        }
        val remainingMs = (durationMs - currentPositionMs).coerceAtLeast(0L)
        val leadWindowMs = (transitionDurationMs + fallbackMeanMs + (2.5 * sqrt(max(0.0, fallbackVarianceMs))) + 5000.0)
            .coerceIn(15000.0, 60000.0).toLong()

        if (durationMs > 0 && remainingMs <= 0) {
            return when (nextStage) {
                TrackLifecycleStage.READY -> AutoDJDecision.TRIGGER_TRANSITION
                TrackLifecycleStage.RESOLVED -> AutoDJDecision.PREPARE_STANDBY
                TrackLifecycleStage.RESOLVING, TrackLifecycleStage.PREPARING, TrackLifecycleStage.QUEUED -> AutoDJDecision.WAIT_FOR_LATE_TRACK
                else -> AutoDJDecision.DO_NOTHING
            }
        }

        if (isPlaying && durationMs > 0) {
            if (remainingMs <= transitionDurationMs && nextStage == TrackLifecycleStage.READY) {
                return AutoDJDecision.TRIGGER_TRANSITION
            }
            if (remainingMs <= leadWindowMs) {
                if (nextStage == TrackLifecycleStage.QUEUED) return AutoDJDecision.PREFETCH_NEXT
                if (nextStage == TrackLifecycleStage.RESOLVED) return AutoDJDecision.PREPARE_STANDBY
                if (remainingMs <= transitionDurationMs && (nextStage == TrackLifecycleStage.RESOLVING || nextStage == TrackLifecycleStage.PREPARING)) {
                    return AutoDJDecision.WAIT_FOR_LATE_TRACK
                }
            }
        }
        return AutoDJDecision.DO_NOTHING
    }

    fun getLatencyStats(transitionDurationMs: Long): AutoDJLatencyStats {
        if (nativeHandle != 0L) {
            val stats = withHandle(floatArrayOf(2500f, 1000f, 15000f)) {
                nativeGetLatencyStats(it, transitionDurationMs)
            }
            return AutoDJLatencyStats(stats[0], stats[1], stats[2])
        }
        val stdDev = sqrt(max(0.0, fallbackVarianceMs)).toFloat()
        val lead = (transitionDurationMs + fallbackMeanMs + (2.5 * stdDev) + 5000.0).coerceIn(15000.0, 60000.0).toFloat()
        return AutoDJLatencyStats(fallbackMeanMs.toFloat(), stdDev, lead)
    }

    override fun close() {
        val handleToDestroy = synchronized(handleLock) {
            val handle = nativeHandle
            nativeHandle = 0L
            handle
        }
        if (handleToDestroy != 0L) {
            nativeDestroy(handleToDestroy)
        }
    }

    private inline fun <T> withHandle(fallback: T, block: (Long) -> T): T {
        val handle = nativeHandle
        return if (handle != 0L) {
            try {
                block(handle)
            } catch (error: Throwable) {
                Log.e(TAG, "Native AutoDJ call failed", error)
                fallback
            }
        } else {
            fallback
        }
    }

    private companion object {
        private const val TAG = "NativeAutoDJEngine"

        @JvmStatic
        private external fun nativeCreate(maxRetries: Int, refillThreshold: Int): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeRecordLatency(handle: Long, latencyMs: Long, wasSuccess: Boolean)

        @JvmStatic
        private external fun nativeEvaluate(
            handle: Long,
            currentPositionMs: Long,
            durationMs: Long,
            isPlaying: Boolean,
            transitionDurationMs: Long,
            nextStage: Int,
            retryCount: Int,
            unplayedCount: Int,
        ): Int

        @JvmStatic
        private external fun nativeGetLatencyStats(handle: Long, transitionDurationMs: Long): FloatArray
    }
}
