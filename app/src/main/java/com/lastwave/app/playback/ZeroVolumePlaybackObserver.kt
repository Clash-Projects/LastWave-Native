package com.lastwave.app.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlin.math.ceil
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Isolated system media volume observer for auto-pausing/resuming playback at volume 0.
 *
 * Requirements:
 * - Monitors AudioManager.STREAM_MUSIC volume changes (via ContentObserver on
 *   Settings.System.CONTENT_URI and VOLUME_CHANGED_ACTION).
 * - Auto-Pause (> 0 -> 0): When pauseOnZeroVolume is true and volume reaches 0 (or is muted)
 *   while audio is actively playing, pauses playback and sets pausedByZeroVolume = true.
 * - Auto-Resume (0 -> > 0): When volume increases from 0 to > 0 while pausedByZeroVolume is true,
 *   automatically resumes playback and resets pausedByZeroVolume = false.
 * - Manual Action Reset: Immediately resets pausedByZeroVolume = false on any manual or external
 *   action (manual pause, play, stop, skip, audio focus loss, headphone disconnect).
 * - Intercepts user Play / Resume actions when volume is 0 and pauseOnZeroVolume is true,
 *   blocking playback and emitting the easter egg snackbar event: "Increase volume idiot !".
 */
class ZeroVolumePlaybackObserver(
    private val context: Context? = null,
    private val audioManager: AudioManager? = null,
    private val isPauseOnZeroVolumeEnabled: () -> Boolean,
    private val isPlaying: () -> Boolean,
    private val onPausePlayback: () -> Unit,
    private val onResumePlayback: () -> Unit,
    initialVolume: Int? = null,
) {
    companion object {
        const val EASTER_EGG_SNACKBAR_TEXT = "Increase volume idiot !"
    }

    var lastKnownVolume: Int = initialVolume ?: audioManager?.let {
        runCatching { it.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
    } ?: 0
        internal set

    var pausedByZeroVolume: Boolean = false
        internal set

    private val _snackbarEvent = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val snackbarEvent = _snackbarEvent.asSharedFlow()

    internal fun getCurrentStreamVolume(): Int {
        return audioManager?.let {
            runCatching { it.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        } ?: lastKnownVolume
    }

    internal fun isStreamMuted(): Boolean {
        return audioManager?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { it.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrDefault(false)
            } else {
                false
            }
        } ?: false
    }

    /**
     * Checks volume changes and handles auto-pause and auto-resume transitions.
     * When auto-resuming from zero volume, enforces a minimum 10% volume floor
     * so playback does not resume inaudibly.
     */
    fun onVolumeChanged(
        currentVolume: Int = getCurrentStreamVolume(),
        isMuted: Boolean = isStreamMuted()
    ) {
        val isZero = currentVolume <= 0 || isMuted
        val wasPositive = lastKnownVolume > 0
        var effectiveVolume = if (isZero) 0 else currentVolume

        if (isPauseOnZeroVolumeEnabled()) {
            if (wasPositive && isZero) {
                if (isPlaying()) {
                    pausedByZeroVolume = true
                    onPausePlayback()
                }
            } else if (lastKnownVolume <= 0 && !isZero) {
                if (pausedByZeroVolume) {
                    audioManager?.let { am ->
                        val maxVolume = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
                        if (maxVolume > 0) {
                            val tenPercentVolume = ceil(maxVolume * 0.10).toInt().coerceIn(1, maxVolume)
                            if (currentVolume < tenPercentVolume) {
                                runCatching {
                                    am.setStreamVolume(AudioManager.STREAM_MUSIC, tenPercentVolume, 0)
                                }
                                effectiveVolume = tenPercentVolume
                            }
                        }
                    }
                    pausedByZeroVolume = false
                    onResumePlayback()
                }
            }
        } else {
            pausedByZeroVolume = false
        }

        lastKnownVolume = effectiveVolume
    }

    /**
     * Intercepts Play/Resume action if volume is 0 and pauseOnZeroVolume is enabled.
     * Returns true if the play action is blocked.
     */
    fun shouldBlockPlayAction(): Boolean {
        if (!isPauseOnZeroVolumeEnabled()) return false
        val currentVolume = getCurrentStreamVolume()
        val isMuted = isStreamMuted()
        if (currentVolume <= 0 || isMuted) {
            _snackbarEvent.tryEmit(EASTER_EGG_SNACKBAR_TEXT)
            return true
        }
        return false
    }

    /**
     * Resets pausedByZeroVolume flag when user takes a manual transport action
     * or when playback is paused externally.
     */
    fun onManualAction() {
        pausedByZeroVolume = false
    }

    private var isRegistered = false
    private var volumeReceiver: BroadcastReceiver? = null
    private var contentObserver: ContentObserver? = null

    fun register() {
        val ctx = context ?: return
        if (isRegistered) return
        isRegistered = true

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                    onVolumeChanged()
                }
            }
        }
        volumeReceiver = receiver
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
        } catch (_: Exception) {
            runCatching { ctx.registerReceiver(receiver, filter) }
        }

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                onVolumeChanged()
            }
        }
        contentObserver = observer
        runCatching {
            ctx.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                observer,
            )
        }
    }

    fun unregister() {
        if (!isRegistered) return
        isRegistered = false
        volumeReceiver?.let {
            runCatching { context?.unregisterReceiver(it) }
            volumeReceiver = null
        }
        contentObserver?.let {
            runCatching { context?.contentResolver?.unregisterContentObserver(it) }
            contentObserver = null
        }
    }
}
