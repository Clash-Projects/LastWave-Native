package com.lastwave.app.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Whether the app is currently in the foreground (RESUMED).
 *
 * Gating video playback on this ensures that the video decoder doesn't consume CPU/GPU/battery
 * when the screen is off or app is in the background.
 *
 * Audio playback itself is completely separate (running in [PlaybackService]) and continues
 * uninterrupted in the background.
 */
@Composable
fun rememberIsForeground(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { owner, _ ->
            foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return foreground
}

/**
 * Whether player motion should run: the activity is resumed and this window owns
 * focus. Focus goes false while the notification shade / Quick Settings are
 * expanded, while a dialog or popup owns the window stack, and while another
 * split-screen pane (or another app) is focused.
 *
 * Screen-off and backgrounding also fall out of this because the lifecycle is
 * no longer RESUMED. Playback and progress tracking are independent and keep
 * running in the service/player.
 */
@Composable
fun rememberPlayerWindowActive(): Boolean =
    rememberIsForeground() && LocalWindowInfo.current.isWindowFocused
