package com.lastwave.app.playback

import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

enum class AutoMixState(val nativeValue: Int) {
    IDLE(0),
    PREBUFFERING_DECK_B(1),
    ANALYZING(2),
    MONITORING_OUTRO(3),
    FADING_AUTO(4),
    FADING_FAST(5),
    POST_RESTORE(6);

    companion object {
        fun fromNative(value: Int): AutoMixState = entries.firstOrNull { it.nativeValue == value } ?: IDLE
    }
}

enum class PhraseLengthMode(val nativeValue: Int) {
    AUTO(0),
    BARS_8(8),
    BARS_16(16),
    FAST_SKIP_4(4);

    companion object {
        fun fromNative(value: Int): PhraseLengthMode = entries.firstOrNull { it.nativeValue == value } ?: AUTO
    }
}

data class TransitionPlan(
    val outCueMs: Long,
    val inCueMs: Long,
    val durationMs: Long,
    val bars: Int,
    val bpmA: Float,
    val bpmB: Float,
    val bpmRatio: Float,
    val gainOffsetDb: Float,
    val textureFx: Int,
    val isVocalSafeLoop: Boolean,
    val isSkitSkipped: Boolean,
)

/**
 * Kotlin owner for the native C++ AutoDJ Automix engine.
 * Coordinates Mixxx-style dual-deck state transitions, silence trimming,
 * kick-band BPM & phase matching via WSOLA, 200Hz/180Hz Linkwitz-Riley bass-swap,
 * vocal-pocket ducking, and 1.4s echo reverb tail.
 */
@Singleton
class NativeAutoMixEngine @Inject constructor() : Closeable {
    private val handleLock = Any()

    @Volatile
    private var nativeHandle = if (NativeAudioEngine.libraryLoaded) {
        try {
            nativeCreate()
        } catch (error: LinkageError) {
            Log.e(TAG, "Native AutoMix engine unavailable", error)
            0L
        }
    } else {
        0L
    }

    val isAvailable: Boolean
        get() = nativeHandle != 0L

    fun configure(sampleRate: Int) {
        withHandle(Unit) { nativeConfigure(it, sampleRate.toDouble()) }
    }

    fun setEnabled(enabled: Boolean) {
        withHandle(Unit) { nativeSetEnabled(it, enabled) }
    }

    fun setBarMode(bars: Int) {
        withHandle(Unit) { nativeSetBarMode(it, bars) }
    }

    fun setPaused(paused: Boolean) {
        withHandle(Unit) { nativeSetPaused(it, paused) }
    }

    fun setDeckBVocalsActive(active: Boolean) {
        withHandle(Unit) { nativeSetDeckBVocalsActive(it, active) }
    }

    fun setDeckAVocalFree(free: Boolean) {
        withHandle(Unit) { nativeSetDeckAVocalFree(it, free) }
    }

    fun triggerFadeNow() {
        withHandle(Unit) { nativeTriggerFadeNow(it) }
    }

    fun startTransition(durationMs: Int) {
        withHandle(Unit) { nativeStartTransition(it, durationMs) }
    }

    fun setTransitionProgress(progress: Float) {
        withHandle(Unit) { nativeSetTransitionProgress(it, progress) }
    }

    fun reset() {
        withHandle(Unit) { nativeReset(it) }
    }

    fun writeDeckBPcm(buffer: ByteBuffer, frameCount: Int): Int {
        if (!buffer.isDirect) return 0
        val offset = buffer.position()
        return withHandle(0) { nativeWriteDeckBPcm(it, buffer, offset, frameCount) }
    }

    fun process(deckABuffer: ByteBuffer, frameCount: Int) {
        if (!deckABuffer.isDirect) return
        val offset = deckABuffer.position()
        withHandle(Unit) { nativeProcess(it, deckABuffer, offset, frameCount) }
    }

    val state: AutoMixState
        get() = withHandle(AutoMixState.IDLE) { AutoMixState.fromNative(nativeGetState(it)) }

    val transitionProgress: Float
        get() = withHandle(0.0F, ::nativeGetTransitionProgress)

    val deckBLeadingTrimFrames: Long
        get() = withHandle(0L, ::nativeGetDeckBLeadingTrimFrames)

    val deckBAvailableSeconds: Float
        get() = withHandle(0.0F, ::nativeGetDeckBAvailableSeconds)

    val deckBAvailableFrames: Long
        get() = withHandle(0L, ::nativeGetDeckBAvailableFrames)

    val deckBFirstBeatMs: Long
        get() = withHandle(0L, ::nativeGetDeckBFirstBeatMs)

    val deckBDropBeatMs: Long
        get() = withHandle(0L, ::nativeGetDeckBDropBeatMs)

    val deckABpm: Float
        get() = withHandle(120.0F, ::nativeGetDeckABpm)

    val deckBBpm: Float
        get() = withHandle(120.0F, ::nativeGetDeckBBpm)

    fun processDeckBuffer(buffer: ByteBuffer, encoding: Int, sampleRate: Int, isDeckA: Boolean): Boolean {
        if (!buffer.isDirect) return false
        val offset = buffer.position()
        val length = buffer.remaining()
        return withHandle(false) {
            nativeProcessDeckBuffer(it, buffer, offset, length, encoding, sampleRate, isDeckA)
        }
    }

    fun planTransition(
        durationMsA: Long,
        durationMsB: Long,
        sampleRate: Double,
        phraseMode: PhraseLengthMode,
        isManualSkip: Boolean = false,
        currentDeckAPosMs: Long = 0L,
        lyricsAIntervals: LongArray? = null,
        lyricsBIntervals: LongArray? = null,
    ): TransitionPlan? {
        if (nativeHandle == 0L) return null
        val arr = withHandle(null) {
            nativePlanTransition(
                it,
                durationMsA,
                durationMsB,
                sampleRate,
                phraseMode.nativeValue,
                isManualSkip,
                currentDeckAPosMs,
                lyricsAIntervals,
                lyricsBIntervals,
            )
        } ?: return null

        if (arr.size < 11) return null
        return TransitionPlan(
            outCueMs = arr[0].toLong(),
            inCueMs = arr[1].toLong(),
            durationMs = arr[2].toLong(),
            bars = arr[3].toInt(),
            bpmA = arr[4].toFloat(),
            bpmB = arr[5].toFloat(),
            bpmRatio = arr[6].toFloat(),
            gainOffsetDb = arr[7].toFloat(),
            textureFx = arr[8].toInt(),
            isVocalSafeLoop = arr[9] > 0.5,
            isSkitSkipped = arr[10] > 0.5,
        )
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
        return if (handle == 0L) fallback else block(handle)
    }

    companion object {
        private const val TAG = "NativeAutoMixEngine"

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeConfigure(handle: Long, sampleRate: Double)
        @JvmStatic private external fun nativeSetEnabled(handle: Long, enabled: Boolean)
        @JvmStatic private external fun nativeSetBarMode(handle: Long, bars: Int)
        @JvmStatic private external fun nativeSetPaused(handle: Long, paused: Boolean)
        @JvmStatic private external fun nativeSetDeckBVocalsActive(handle: Long, active: Boolean)
        @JvmStatic private external fun nativeSetDeckAVocalFree(handle: Long, free: Boolean)
        @JvmStatic private external fun nativeTriggerFadeNow(handle: Long)
        @JvmStatic private external fun nativeStartTransition(handle: Long, durationMs: Int)
        @JvmStatic private external fun nativeSetTransitionProgress(handle: Long, progress: Float)
        @JvmStatic private external fun nativeReset(handle: Long)
        @JvmStatic private external fun nativeWriteDeckBPcm(
            handle: Long,
            buffer: ByteBuffer,
            offsetBytes: Int,
            frameCount: Int,
        ): Int
        @JvmStatic private external fun nativeProcess(
            handle: Long,
            buffer: ByteBuffer,
            offsetBytes: Int,
            frameCount: Int,
        )
        @JvmStatic private external fun nativeProcessDeckBuffer(
            handle: Long,
            buffer: ByteBuffer,
            offsetBytes: Int,
            lengthBytes: Int,
            encoding: Int,
            sampleRate: Int,
            isDeckA: Boolean,
        ): Boolean
        @JvmStatic private external fun nativePlanTransition(
            handle: Long,
            durationMsA: Long,
            durationMsB: Long,
            sampleRate: Double,
            phraseModeInt: Int,
            isManualSkip: Boolean,
            currentDeckAPosMs: Long,
            lyricsAIntervals: LongArray?,
            lyricsBIntervals: LongArray?,
        ): DoubleArray?
        @JvmStatic private external fun nativeGetState(handle: Long): Int
        @JvmStatic private external fun nativeGetTransitionProgress(handle: Long): Float
        @JvmStatic private external fun nativeGetDeckBLeadingTrimFrames(handle: Long): Long
        @JvmStatic private external fun nativeGetDeckBAvailableSeconds(handle: Long): Float
        @JvmStatic private external fun nativeGetDeckBAvailableFrames(handle: Long): Long
        @JvmStatic private external fun nativeGetDeckBFirstBeatMs(handle: Long): Long
        @JvmStatic private external fun nativeGetDeckBDropBeatMs(handle: Long): Long
        @JvmStatic private external fun nativeGetDeckABpm(handle: Long): Float
        @JvmStatic private external fun nativeGetDeckBBpm(handle: Long): Float
    }
}
