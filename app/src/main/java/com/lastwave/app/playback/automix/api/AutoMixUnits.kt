package com.lastwave.app.playback.automix.api

import androidx.compose.runtime.Immutable

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
