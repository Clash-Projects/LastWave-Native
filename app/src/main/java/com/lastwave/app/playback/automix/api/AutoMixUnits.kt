package com.lastwave.app.playback.automix.api

import androidx.compose.runtime.Immutable

/**
 * Type-safe value class wrappers for physical and musical units.
 */
@JvmInline
@Immutable
value class Bpm(val value: Float) : Comparable<Bpm> {
    init {
        require(value.isNaN() || value in 0f..400f) { "BPM must be in realistic range (0..400) or NaN: $value" }
    }

    override fun compareTo(other: Bpm): Int = value.compareTo(other.value)
    fun isValid(): Boolean = !value.isNaN() && value in 40f..260f

    companion object {
        val UNKNOWN = Bpm(Float.NaN)
        val DEFAULT = Bpm(120.0f)
    }
}

@JvmInline
@Immutable
value class DecibelLufs(val value: Float) : Comparable<DecibelLufs> {
    override fun compareTo(other: DecibelLufs): Int = value.compareTo(other.value)

    companion object {
        val SILENCE = DecibelLufs(-70.0f)
        val TARGET = DecibelLufs(-14.0f) // Standard streaming target
    }
}

@JvmInline
@Immutable
value class Millis(val value: Long) : Comparable<Millis> {
    override fun compareTo(other: Millis): Int = value.compareTo(other.value)

    operator fun plus(other: Millis): Millis = Millis(value + other.value)
    operator fun minus(other: Millis): Millis = Millis(value - other.value)
    operator fun times(scalar: Double): Millis = Millis((value * scalar).toLong())

    companion object {
        val ZERO = Millis(0L)
    }
}

@JvmInline
@Immutable
value class CamelotKey(val code: String) {
    val number: Int
        get() = code.filter { it.isDigit() }.toIntOrNull() ?: 0

    val letter: Char
        get() = code.lastOrNull { it.isLetter() }?.uppercaseChar() ?: '?'

    val isMinor: Boolean get() = letter == 'A'
    val isMajor: Boolean get() = letter == 'B'
    val isValid: Boolean get() = number in 1..12 && (letter == 'A' || letter == 'B')

    override fun toString(): String = if (isValid) "${number}$letter" else "UNKNOWN"

    companion object {
        val UNKNOWN = CamelotKey("?")
    }
}

@JvmInline
@Immutable
value class NormalizedFloat(val value: Float) : Comparable<NormalizedFloat> {
    val clamped: Float
        get() = value.coerceIn(0f, 1f)

    override fun compareTo(other: NormalizedFloat): Int = value.compareTo(other.value)

    companion object {
        val ZERO = NormalizedFloat(0.0f)
        val HALF = NormalizedFloat(0.5f)
        val ONE = NormalizedFloat(1.0f)
    }
}
