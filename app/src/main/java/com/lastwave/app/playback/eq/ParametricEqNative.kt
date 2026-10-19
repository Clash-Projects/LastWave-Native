package com.lastwave.app.playback.eq

import android.util.Log
import androidx.media3.common.C
import java.nio.ByteBuffer

/**
 * JNI surface of the native parametric EQ (ParametricEq.cpp).
 *
 * [publish] sets the single process-wide configuration that both the native
 * DSP chain and every [DirectPcmEq] pick up on their next block.
 */
object ParametricEqNative {
    private const val TAG = "ParametricEqNative"

    val isAvailable: Boolean = runCatching {
        System.loadLibrary("lastwave_audio")
        true
    }.getOrElse { error ->
        Log.w(TAG, "Native parametric EQ unavailable", error)
        false
    }

    fun publish(enabled: Boolean, preampDb: Float, filters: List<NativeFilter>) {
        if (!isAvailable) return
        runCatching {
            nativePublish(
                enabled,
                preampDb,
                IntArray(filters.size) { filters[it].type },
                FloatArray(filters.size) { filters[it].frequencyHz },
                FloatArray(filters.size) { filters[it].gainDb },
                FloatArray(filters.size) { filters[it].q },
                BooleanArray(filters.size) { filters[it].enabled },
            )
        }.onFailure { Log.w(TAG, "publish failed", it) }
    }

    /** True when an enabled configuration actually changes the signal. */
    fun isEngaged(): Boolean = isAvailable && runCatching { nativeIsEngaged() }.getOrDefault(false)

    data class NativeFilter(
        val type: Int,
        val frequencyHz: Float,
        val gainDb: Float,
        val q: Float,
        val enabled: Boolean,
    )

    /** Media3 PCM encoding -> native encoding id (see ParametricEq.h), or null when unsupported. */
    fun nativeEncodingOf(media3Encoding: Int): Int? = when (media3Encoding) {
        C.ENCODING_PCM_16BIT -> 0
        C.ENCODING_PCM_24BIT -> 1
        C.ENCODING_PCM_32BIT -> 2
        C.ENCODING_PCM_FLOAT -> 3
        else -> null
    }

    @JvmStatic external fun nativePublish(
        enabled: Boolean,
        preampDb: Float,
        types: IntArray,
        frequencies: FloatArray,
        gains: FloatArray,
        qs: FloatArray,
        filterEnabled: BooleanArray,
    )
    @JvmStatic external fun nativeIsEngaged(): Boolean
    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeConfigure(handle: Long, sampleRate: Int, channels: Int)
    @JvmStatic external fun nativeReset(handle: Long)
    @JvmStatic external fun nativeProcessDirect(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        length: Int,
        encoding: Int,
    ): Boolean
    @JvmStatic external fun nativeProcessArray(
        handle: Long,
        array: ByteArray,
        offset: Int,
        length: Int,
        encoding: Int,
    ): Boolean
}

/**
 * In-place parametric EQ for the direct routes (USB exclusive, internal
 * direct output, Bit-Perfect AudioTrack), where PCM never enters the native
 * Float32 DSP chain. Works in the source packing so the route keeps its format.
 * Renderer thread only.
 */
class DirectPcmEq {
    private var handle = 0L
    private var encoding: Int? = null
    private var frameBytes = 0

    fun configure(sampleRate: Int, channels: Int, media3Encoding: Int) {
        if (!ParametricEqNative.isAvailable) return
        if (handle == 0L) handle = runCatching { ParametricEqNative.nativeCreate() }.getOrDefault(0L)
        if (handle == 0L) return
        encoding = ParametricEqNative.nativeEncodingOf(media3Encoding)
        frameBytes = when (encoding) {
            0 -> 2
            1 -> 3
            2, 3 -> 4
            else -> 0
        } * channels.coerceAtLeast(1)
        runCatching { ParametricEqNative.nativeConfigure(handle, sampleRate, channels.coerceIn(1, 2)) }
        if (channels !in 1..2) encoding = null
    }

    fun reset() {
        if (handle != 0L) runCatching { ParametricEqNative.nativeReset(handle) }
    }

    /** Applies the EQ to buffer[position, limit). Returns true when samples changed. */
    fun process(buffer: ByteBuffer): Boolean {
        val enc = encoding ?: return false
        if (handle == 0L || frameBytes <= 0 || !buffer.hasRemaining()) return false
        val start = buffer.position()
        val length = (buffer.remaining() / frameBytes) * frameBytes
        if (length <= 0) return false
        return runCatching {
            when {
                buffer.isDirect -> ParametricEqNative.nativeProcessDirect(handle, buffer, start, length, enc)
                buffer.hasArray() && !buffer.isReadOnly -> ParametricEqNative.nativeProcessArray(
                    handle,
                    buffer.array(),
                    buffer.arrayOffset() + start,
                    length,
                    enc,
                )
                else -> false
            }
        }.getOrDefault(false)
    }

    fun release() {
        if (handle != 0L) {
            runCatching { ParametricEqNative.nativeDestroy(handle) }
            handle = 0L
        }
    }
}
