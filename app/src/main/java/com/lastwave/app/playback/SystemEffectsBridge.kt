package com.lastwave.app.playback

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.media3.common.C

/**
 * Publishes ExoPlayer's audio session to Android's standard effect-control
 * protocol so external equalizer apps (Wavelet, Poweramp EQ, …) and OEM
 * Dolby panels can attach to LastWave playback.
 *
 * Inactive by default: [setModeActive] follows the "System Audio Effects"
 * setting combined with route state (bypass routes suspend it). Every
 * broadcast is fire-and-forget and exception-proof — a missing effect panel
 * or a hostile ROM must never disturb playback.
 */
class SystemEffectsBridge(private val appContext: Context) {

    @Volatile private var lastSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    @Volatile private var modeActive: Boolean = false
    @Volatile private var publishedSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    /** Follows ExoPlayer whenever Android creates or replaces its session. */
    @Synchronized
    fun onSessionChanged(audioSessionId: Int) {
        lastSessionId = audioSessionId
        publishIfNeededLocked()
    }

    /** Effective mode (pref ON and on a mixer route). Publishes or retracts. */
    @Synchronized
    fun setModeActive(active: Boolean) {
        if (modeActive == active && active) {
            // Re-announce: some panels only catch the broadcast while open.
            publishIfNeededLocked(force = true)
            return
        }
        modeActive = active
        publishIfNeededLocked()
    }

    @Synchronized
    fun close() {
        closePublishedLocked()
        lastSessionId = C.AUDIO_SESSION_ID_UNSET
        modeActive = false
    }

    private fun publishIfNeededLocked(force: Boolean = false) {
        if (!modeActive || lastSessionId == C.AUDIO_SESSION_ID_UNSET) {
            closePublishedLocked()
            return
        }
        if (!force && publishedSessionId == lastSessionId) return
        closePublishedLocked()
        val open = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, lastSessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, appContext.packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }
        // No effect panel installed (or a ROM that hides it): stay silent
        // instead of broadcasting into the void on every track.
        val hasPanel = runCatching {
            open.resolveActivity(appContext.packageManager) != null
        }.getOrDefault(false)
        if (!hasPanel) return
        runCatching { appContext.sendBroadcast(open) }
            .onSuccess {
                publishedSessionId = lastSessionId
                android.util.Log.i(
                    "SystemEffects",
                    "Published audio session $lastSessionId for external effects",
                )
            }
            .onFailure {
                android.util.Log.w("SystemEffects", "Session publish failed", it)
            }
    }

    private fun closePublishedLocked() {
        val published = publishedSessionId
        if (published == C.AUDIO_SESSION_ID_UNSET) return
        publishedSessionId = C.AUDIO_SESSION_ID_UNSET
        val close = Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, published)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, appContext.packageName)
        }
        runCatching { appContext.sendBroadcast(close) }
    }
}
