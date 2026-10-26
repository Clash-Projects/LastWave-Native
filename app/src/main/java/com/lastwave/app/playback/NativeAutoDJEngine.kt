package com.lastwave.app.playback

import android.util.Log
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.sqrt

enum class TrackLifecycleStage(val nativeValue: Int) {
    QUEUED(0),
    RESOLVING_STREAM(1),
    STREAM_RESOLVED(2),
    DECODING_HEAD_WINDOW(3),
    ARMED_READY(4),
    TRANSITIONING(5),
    PLAYING(6),
    COMPLETED(7),
    EXPIRED(8),
    FAILED_RETRYING(9),
    SKIPPED_UNPLAYABLE(10),
    CANCELLED(11);

    companion object {
        fun fromNative(value: Int): TrackLifecycleStage = entries.firstOrNull { it.nativeValue == value } ?: QUEUED
    }
}

enum class AutoDJDecision(val nativeValue: Int) {
    DO_NOTHING(0),
    RESOLVE_NEXT_STREAM(1),
    DECODE_HEAD_WINDOW(2),
    ARM_STANDBY(3),
    TRIGGER_TRANSITION(4),
    WAIT_FOR_LATE_TRACK(5),
    SKIP_UNPLAYABLE(6),
    REFILL_QUEUE(7),
    REFRESH_EXPIRED_STREAM(8);

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
            nativeCreate(maxRetries = 2, refillThreshold = 2)
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

        // Fallback pure Kotlin evaluation
        if (unplayedCount <= 2) {
            return AutoDJDecision.REFILL_QUEUE
        }
        if (nextStage == TrackLifecycleStage.EXPIRED) {
            return AutoDJDecision.REFRESH_EXPIRED_STREAM
        }
        if (nextStage == TrackLifecycleStage.FAILED_RETRYING) {
            return if (retryCount >= 2) AutoDJDecision.SKIP_UNPLAYABLE else AutoDJDecision.RESOLVE_NEXT_STREAM
        }
        val remainingMs = (durationMs - currentPositionMs).coerceAtLeast(0L)
        val leadWindowMs = (transitionDurationMs + fallbackMeanMs + (2.5 * sqrt(max(0.0, fallbackVarianceMs))) + 5000.0)
            .coerceIn(20000.0, 70000.0).toLong()
        val armLeadThresholdMs = 40000L + transitionDurationMs

        if (durationMs > 0 && remainingMs <= 0) {
            return when (nextStage) {
                TrackLifecycleStage.ARMED_READY -> AutoDJDecision.TRIGGER_TRANSITION
                TrackLifecycleStage.STREAM_RESOLVED -> AutoDJDecision.DECODE_HEAD_WINDOW
                TrackLifecycleStage.DECODING_HEAD_WINDOW, TrackLifecycleStage.RESOLVING_STREAM, TrackLifecycleStage.QUEUED -> AutoDJDecision.WAIT_FOR_LATE_TRACK
                else -> AutoDJDecision.DO_NOTHING
            }
        }

        if (isPlaying && durationMs > 0) {
            if (remainingMs <= transitionDurationMs && nextStage == TrackLifecycleStage.ARMED_READY) {
                return AutoDJDecision.TRIGGER_TRANSITION
            }
            if (remainingMs <= armLeadThresholdMs) {
                if (nextStage == TrackLifecycleStage.STREAM_RESOLVED) return AutoDJDecision.DECODE_HEAD_WINDOW
                if (nextStage == TrackLifecycleStage.DECODING_HEAD_WINDOW) return AutoDJDecision.ARM_STANDBY
            }
            if (nextStage == TrackLifecycleStage.QUEUED) {
                return AutoDJDecision.RESOLVE_NEXT_STREAM
            }
            if (remainingMs <= transitionDurationMs && (nextStage == TrackLifecycleStage.RESOLVING_STREAM || nextStage == TrackLifecycleStage.DECODING_HEAD_WINDOW)) {
                return AutoDJDecision.WAIT_FOR_LATE_TRACK
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
        val lead = (transitionDurationMs + fallbackMeanMs + (2.5 * stdDev) + 5000.0).coerceIn(20000.0, 70000.0).toFloat()
        return AutoDJLatencyStats(fallbackMeanMs.toFloat(), stdDev, lead)
    }

    override fun close() {
        synchronized(handleLock) {
            val handle = nativeHandle
            if (handle != 0L) {
                nativeHandle = 0L
                nativeDestroy(handle)
            }
        }
    }

    private inline fun <T> withHandle(fallback: T, block: (Long) -> T): T {
        val handle = nativeHandle
        return if (handle != 0L) {
            try {
                block(handle)
            } catch (error: LinkageError) {
                Log.e(TAG, "Native AutoDJ engine invocation failed", error)
                fallback
            }
        } else {
            fallback
        }
    }

    companion object {
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
