package com.lastwave.app.widget.wave

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Cold-start-safe one-shot bridge from a widget tap to the Compose player host. */
object WaveWidgetNavigation {
    const val ACTION_OPEN_NOW_PLAYING = "com.lastwave.app.action.OPEN_NOW_PLAYING"

    private val sequence = AtomicLong()
    private val consumed = AtomicLong()
    private val _requests = MutableStateFlow(0L)
    val requests = _requests.asStateFlow()

    fun requestOpen() {
        _requests.value = sequence.incrementAndGet()
    }

    fun consume(requestId: Long): Boolean {
        if (requestId <= 0L) return false
        while (true) {
            val previous = consumed.get()
            if (requestId <= previous) return false
            if (consumed.compareAndSet(previous, requestId)) return true
        }
    }
}
