package com.lastwave.app.playback.rhythm

import android.os.SystemClock
import com.lastwave.app.playback.haptics.HapticPulseListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.exp
import kotlin.math.max

/**
 * Beat envelope for the beat-synced fluid background: follows the bass level
 * from the sink's [com.lastwave.app.playback.haptics.MusicHapticsAnalyzer],
 * so the artwork swells with every kick and bass hit and settles between them.
 * Discrete kick triggers missed hits and re-fired on 808 rolls.
 */
object FluidRhythmEngine : HapticPulseListener {

    private const val ATTACK_MS = 25f
    private const val RELEASE_MS = 120f

    private val _rhythm = MutableStateFlow(0f)
    /** Current smoothed rhythm value in range 0f..1f */
    val rhythm: StateFlow<Float> = _rhythm.asStateFlow()

    @Volatile
    var currentRhythmValue: Float = 0f
        private set

    @Volatile
    var isEnabled: Boolean = false

    // Only the playing deck's sink may feed levels: the crossfade standby sent
    // zeros 8x a second that cut swells short mid-beat.
    @Volatile
    var liveSink: com.lastwave.app.playback.NativeProcessingAudioSink? = null

    // PCM is analysed when it enters the sink, a whole AudioTrack buffer (2.7 s
    // on a tablet) before it is heard. Levels wait here as (due uptime ms,
    // level) until they are audible.
    private val pendingLevels = ArrayDeque<Pair<Long, Float>>()
    private var target = 0f

    override fun onHapticPulse(
        bassEnergy: Float,
        vocalEnergy: Float,
        isKick: Boolean,
        isSharpTransient: Boolean,
        leadTimeMs: Long,
    ) = Unit

    override fun onBassLevel(level: Float, leadTimeMs: Long) {
        synchronized(pendingLevels) { pendingLevels.addLast(SystemClock.uptimeMillis() + leadTimeMs to level) }
    }

    override fun onAnalysisFlushed() = synchronized(pendingLevels) { pendingLevels.clear() }

    /** Advanced every animation frame by the UI. */
    fun tick(dtSeconds: Float, isPlaying: Boolean): Float {
        val dtMs = (dtSeconds * 1000f).coerceIn(1f, 100f)
        if (!isEnabled || !isPlaying) {
            onAnalysisFlushed()
            target = 0f
        } else {
            val now = SystemClock.uptimeMillis()
            synchronized(pendingLevels) {
                // Several 10 ms windows come due per frame: keep the loudest so no hit is skipped.
                var due = -1f
                while (pendingLevels.isNotEmpty() && pendingLevels.first().first <= now) {
                    due = max(due, pendingLevels.removeFirst().second)
                }
                if (due >= 0f) target = due
            }
        }
        val tau = if (target > currentRhythmValue) ATTACK_MS else RELEASE_MS
        currentRhythmValue = (currentRhythmValue + (target - currentRhythmValue) * (1f - exp(-dtMs / tau)))
            .coerceIn(0f, 1f)
        _rhythm.value = currentRhythmValue
        return currentRhythmValue
    }
}
