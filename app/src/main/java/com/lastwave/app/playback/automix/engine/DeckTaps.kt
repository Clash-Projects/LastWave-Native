package com.lastwave.app.playback.automix.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/** Everything AutoMix hooks into one deck's processed float output. */
class DeckTaps {
    val eq = DeckEqStage()
    val kicks = KickTap()
    val level = LevelMeter()
}

/**
 * Short-term power (~400 ms) of a deck's output after the AutoMix EQ and
 * before its player volume: what that deck alone would sound like at full
 * level. Audio thread writes, main thread reads. Off unless [enabled].
 */
class LevelMeter {
    @Volatile var enabled = false
        set(value) {
            if (value && !field) power = 0.0
            field = value
        }

    /** Mean square, 0 when unknown. */
    @Volatile var power = 0.0
        private set

    private var rate = 0
    private var decayPerFrame = 0.0
    private var state = 0.0

    fun process(output: ByteBuffer, channelCount: Int, sampleRate: Int) {
        if (!enabled || channelCount !in 1..2 || sampleRate <= 0) return
        if (sampleRate != rate) {
            rate = sampleRate
            decayPerFrame = exp(-1.0 / (sampleRate * TIME_CONSTANT_S))
        }
        val samples = output.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val frames = samples.remaining() / channelCount
        val base = samples.position()
        var value = state
        for (frame in 0 until frames) {
            var square = 0.0
            for (channel in 0 until channelCount) {
                val sample = samples.get(base + frame * channelCount + channel).toDouble()
                square += sample * sample
            }
            value = value * decayPerFrame + square / channelCount * (1 - decayPerFrame)
        }
        state = value
        power = value
    }

    private companion object {
        const val TIME_CONSTANT_S = 0.4
    }
}
