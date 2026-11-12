package com.lastwave.app.playback.haptics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.HapticGenerator
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class MusicHapticsController @Inject constructor(
    @ApplicationContext private val context: Context
) : HapticPulseListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val hapticThread = HandlerThread("LastWave-MusicHaptics", Thread.NORM_PRIORITY).apply {
        start()
    }
    private val hapticHandler = Handler(hapticThread.looper)

    /**
     * Every tap ever created, held weakly so released players drop out.
     * An AudioProcessor owns per-stream filter state and output buffers, so each
     * DefaultAudioSink needs its own instance. Sharing one between the
     * enhanced/fallback sinks and the crossfade standby player corrupted the
     * filters (two playback threads) and let the muted standby fire pulses.
     */
    private val allTaps: MutableSet<MusicHapticsAnalyzer> =
        Collections.synchronizedSet(
            Collections.newSetFromMap(WeakHashMap<MusicHapticsAnalyzer, Boolean>())
        )
    private val tapsByPlayer: MutableMap<Player, List<MusicHapticsAnalyzer>> =
        Collections.synchronizedMap(WeakHashMap())
    @Volatile private var activeTaps: List<MusicHapticsAnalyzer> = emptyList()
    @Volatile private var dspActive: Boolean = false
    private var boundPlayer: WeakReference<Player>? = null

    /** Pending beat-aligned pulses carry this token so they can be dropped at once. */
    private val pulseToken = Any()
    @Volatile private var lastPulseUptimeMs = 0L
    @Volatile private var lastPulseWasKick = false

    private var hapticGenerator: HapticGenerator? = null
    private var currentAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var isPlayerPlaying: Boolean = false
    private var isUsbExclusiveActive: Boolean = false
    private var isCastingOrAuto: Boolean = false
    private var sessionStabilizeJob: Job? = null

    private val _config = MutableStateFlow(MusicHapticsConfig())
    val config: StateFlow<MusicHapticsConfig> = _config.asStateFlow()

    /** Battery saver can toggle mid-song; re-evaluate instead of only at play/pause. */
    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scope.launch { reconcileEngineState() }
        }
    }

    init {
        runCatching {
            context.registerReceiver(
                powerSaveReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            )
        }
    }

    private val hasAmplitudeControl: Boolean by lazy {
        runCatching { vibrator?.hasAmplitudeControl() == true }.getOrDefault(false)
    }

    fun isDeviceHapticsCapable(): Boolean = vibrator?.hasVibrator() == true

    fun isHardwareHapticGeneratorAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { HapticGenerator.isAvailable() }.getOrDefault(false)
    }

    /** Creates a fresh analyser for one player audio sink. */
    fun createAnalyzer(): MusicHapticsAnalyzer =
        MusicHapticsAnalyzer(this).also { allTaps.add(it) }

    /** Associates the taps installed in [player]'s sinks with that player. */
    fun registerPlayerTaps(player: Player, taps: List<MusicHapticsAnalyzer>) {
        tapsByPlayer[player] = taps.toList()
        if (boundPlayer?.get() === player) {
            activeTaps = taps.toList()
            reconcileEngineState()
        }
    }

    /** Seek or other non-gapless discontinuity on the bound player. */
    fun onPositionDiscontinuity() {
        clearPendingPulses()
    }

    fun updateConfig(newConfig: MusicHapticsConfig) {
        _config.value = newConfig
        reconcileEngineState()
    }

    fun setUsbExclusiveModeActive(active: Boolean) {
        if (isUsbExclusiveActive != active) {
            isUsbExclusiveActive = active
            reconcileEngineState()
        }
    }

    fun setCastingOrAutoActive(active: Boolean) {
        if (isCastingOrAuto != active) {
            isCastingOrAuto = active
            reconcileEngineState()
        }
    }

    fun bindToPlayer(player: Player) {
        boundPlayer = WeakReference(player)
        activeTaps = tapsByPlayer[player].orEmpty()
        clearPendingPulses()
        currentAudioSessionId = (player as? ExoPlayer)?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET
        isPlayerPlaying = player.isPlaying && player.volume > 0f
        reconcileEngineState()
    }

    fun onAudioSessionIdChanged(newSessionId: Int) {
        if (currentAudioSessionId == newSessionId) return
        currentAudioSessionId = newSessionId
        releaseHardwareGenerator()
        // Delay 120ms before enabling hardware HapticGenerator to prevent Android 12 volume spike bug
        sessionStabilizeJob?.cancel()
        sessionStabilizeJob = scope.launch {
            delay(120L)
            reconcileEngineState()
        }
    }

    fun onIsPlayingChanged(isPlaying: Boolean, playerVolume: Float = 1f) {
        isPlayerPlaying = isPlaying && playerVolume > 0f
        reconcileEngineState()
    }

    private fun shouldRunHaptics(): Boolean {
        val cfg = _config.value
        if (!cfg.enabled || !isDeviceHapticsCapable()) return false
        if (!isPlayerPlaying || isUsbExclusiveActive || isCastingOrAuto) return false
        if (cfg.pauseOnBatterySaver && powerManager?.isPowerSaveMode == true) return false
        return true
    }

    private fun shouldUseHardwareGenerator(): Boolean {
        val cfg = _config.value
        if (!isHardwareHapticGeneratorAvailable()) return false
        if (currentAudioSessionId == C.AUDIO_SESSION_ID_UNSET || currentAudioSessionId <= 0) return false
        return when (cfg.engineMode) {
            MusicHapticsEngineMode.HARDWARE_NATIVE -> true
            MusicHapticsEngineMode.DSP_PRECISION -> false
            // HapticGenerator only vibrates when the AudioTrack's haptic channels
            // are unmuted; Media3 never unmutes them (platform default is muted),
            // so Automatic silently produced nothing on most phones. The DSP
            // engine works everywhere and honours mode/intensity.
            MusicHapticsEngineMode.AUTO -> false
        }
    }

    private fun setDspActive(active: Boolean) {
        dspActive = active
        val live = activeTaps
        synchronized(allTaps) {
            allTaps.forEach { tap -> tap.isAnalysisActive = active && live.contains(tap) }
        }
        if (!active) clearPendingPulses()
    }

    private fun clearPendingPulses() {
        hapticHandler.removeCallbacksAndMessages(pulseToken)
    }

    @Synchronized
    private fun reconcileEngineState() {
        val active = shouldRunHaptics()
        if (!active) {
            setDspActive(false)
            disableHardwareGenerator()
            runCatching { vibrator?.cancel() }
            return
        }

        if (shouldUseHardwareGenerator()) {
            setDspActive(false)
            enableHardwareGenerator(currentAudioSessionId)
        } else {
            disableHardwareGenerator()
            setDspActive(true)
        }
    }

    private fun enableHardwareGenerator(sessionId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            if (hapticGenerator == null) {
                hapticGenerator = HapticGenerator.create(sessionId)
            }
            hapticGenerator?.enabled = true
        } catch (_: Throwable) {
            // Fallback seamlessly to DSP processor if OEM HAL rejects HapticGenerator
            releaseHardwareGenerator()
            setDspActive(true)
        }
    }

    private fun disableHardwareGenerator() {
        try {
            hapticGenerator?.enabled = false
        } catch (_: Throwable) {
        }
    }

    private fun releaseHardwareGenerator() {
        try {
            hapticGenerator?.enabled = false
            hapticGenerator?.release()
        } catch (_: Throwable) {
        } finally {
            hapticGenerator = null
        }
    }

    override fun onAnalysisFlushed() {
        clearPendingPulses()
    }

    override fun onHapticPulse(
        bassEnergy: Float,
        vocalEnergy: Float,
        isKick: Boolean,
        isSharpTransient: Boolean,
        leadTimeMs: Long,
    ) {
        if (!dspActive) return
        val cfg = _config.value

        // Each mode follows only its own events. Mixing by energy let Bass &
        // Beats tap on vocals and hi-hats, and Vocals Only tap on every kick.
        val rawDrive: Float = when (cfg.mode) {
            MusicHapticsMode.BASS_AND_BEATS -> if (isKick) bassEnergy else return
            MusicHapticsMode.VOCALS_ONLY -> if (isKick) return else vocalEnergy
            MusicHapticsMode.WHOLE_SONG -> if (isKick) bassEnergy else vocalEnergy * 0.85f
        }
        if (rawDrive < 0.08f) return

        val isBassDominant = isKick
        // The tap sees PCM when it is written to the AudioTrack, ~100-500 ms
        // before it is audible. Fire when the sound actually reaches the ear,
        // minus the motor's own start-up time (vibrate() call + spin-up), so the
        // pulse is FELT on the beat instead of a few tens of ms after it.
        val fireAt = SystemClock.uptimeMillis() + (leadTimeMs - actuatorLatencyMs).coerceAtLeast(0L)
        hapticHandler.postAtTime({
            if (!dspActive) return@postAtTime
            // Keep a minimum gap between motor kicks so pulses never smear together.
            // A kick may cut short a preceding accent: the beat matters more, and
            // an accent just before it used to swallow the downbeat entirely.
            val now = SystemClock.uptimeMillis()
            val preempts = isKick && !lastPulseWasKick
            if (!preempts && now - lastPulseUptimeMs < MIN_PULSE_SPACING_MS) return@postAtTime
            lastPulseUptimeMs = now
            lastPulseWasKick = isKick
            dispatchVibrationPulse(
                drive = rawDrive,
                isBassDominant = isBassDominant,
                isSharpTransient = isSharpTransient,
                intensity = cfg.intensity
            )
        }, pulseToken, fireAt)
    }

    fun triggerPreviewPulse(intensity: MusicHapticsIntensity = _config.value.intensity) {
        hapticHandler.post {
            dispatchVibrationPulse(
                drive = 0.85f,
                isBassDominant = true,
                isSharpTransient = true,
                intensity = intensity
            )
        }
    }

    private fun dispatchVibrationPulse(
        drive: Float,
        isBassDominant: Boolean,
        isSharpTransient: Boolean,
        intensity: MusicHapticsIntensity
    ) {
        val vib = vibrator ?: return
        try {
            if (hasAmplitudeControl) {
                // Shaped, full-range pulses (SpatialFlow-style): long and strong
                // enough to be felt in the hand. The old 10-20 ms primitives at
                // low scale were below what most motors can render.
                val strength = (drive * intensity.amplitudeScale).coerceIn(0f, 1f)
                val peak = (motorFloorAmplitude + (255 - motorFloorAmplitude) * strength)
                    .roundToInt().coerceIn(motorFloorAmplitude, 255)
                val stretch = if (isLikelyErm) 1.6f else 1f
                fun ms(v: Long) = (v * stretch).toLong()
                val effect = when {
                    // Peak first: the hit is felt the instant the motor starts,
                    // with no softer lead-in delaying it, then a short tail.
                    isBassDominant && isSharpTransient -> VibrationEffect.createWaveform(
                        longArrayOf(ms(40), ms(24)),
                        intArrayOf(peak, (peak * 0.35f).toInt().coerceAtLeast(1)),
                        -1
                    )
                    isBassDominant -> VibrationEffect.createWaveform(
                        longArrayOf(ms(30), ms(14)),
                        intArrayOf((peak * 0.9f).toInt().coerceAtLeast(1), (peak * 0.5f).toInt().coerceAtLeast(1)),
                        -1
                    )
                    else -> VibrationEffect.createOneShot(
                        ms(28),
                        (peak * 0.75f).toInt().coerceAtLeast(motorFloorAmplitude)
                    )
                }
                vib.vibrate(effect)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // No amplitude control: one-shots always run at full strength,
                // so let drive x intensity pick between the predefined effects.
                val strength = drive * intensity.amplitudeScale
                if (strength < 0.18f) return
                val effectId = when {
                    isBassDominant && (isSharpTransient || strength > 0.6f) -> VibrationEffect.EFFECT_HEAVY_CLICK
                    else -> VibrationEffect.EFFECT_CLICK
                }
                vib.vibrate(VibrationEffect.createPredefined(effectId))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(if (isBassDominant) 45L else 20L)
            }
        } catch (_: Throwable) {
            // Never crash audio playback on OEM Vibrator service exceptions
        }
    }

    /** Lowest amplitude the motor reliably renders; below this pulses are not felt. */
    private val motorFloorAmplitude: Int by lazy {
        val mfg = Build.MANUFACTURER.lowercase()
        when {
            isLikelyErm -> 70
            mfg.contains("xiaomi") || mfg.contains("redmi") || mfg.contains("poco") -> 70
            mfg.contains("samsung") -> 50
            else -> 45
        }
    }

    /**
     * Time from vibrate() until the pulse is felt: binder call plus motor
     * spin-up. LRA motors ~20-30 ms, eccentric-mass (ERM) ones roughly double.
     */
    private val actuatorLatencyMs: Long by lazy { if (isLikelyErm) 50L else 30L }

    /** Budget/eccentric-mass motors respond slowly and need longer pulses. */
    private val isLikelyErm: Boolean by lazy {
        val mfg = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase()
        mfg.contains("motorola") || mfg.contains("infinix") || mfg.contains("tecno") ||
            mfg.contains("nokia") || mfg.contains("itel") ||
            (mfg.contains("samsung") && (model.startsWith("sm-a") || model.startsWith("sm-m")))
    }

    fun release() {
        sessionStabilizeJob?.cancel()
        setDspActive(false)
        releaseHardwareGenerator()
        runCatching { vibrator?.cancel() }
        runCatching { context.unregisterReceiver(powerSaveReceiver) }
        hapticThread.quitSafely()
    }

    private companion object {
        const val MIN_PULSE_SPACING_MS = 90L
    }
}
