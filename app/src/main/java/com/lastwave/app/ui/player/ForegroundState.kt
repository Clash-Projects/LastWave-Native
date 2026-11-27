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

@Composable
fun rememberIsForeground(): Boolean {
    val ownerLifecycle = LocalLifecycleOwner.current.lifecycle
    var isResumed by remember(ownerLifecycle) {
        mutableStateOf(ownerLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    DisposableEffect(ownerLifecycle) {
        val lifecycleObserver = LifecycleEventObserver { source, _ ->
            isResumed = source.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        ownerLifecycle.addObserver(lifecycleObserver)
        onDispose {
            ownerLifecycle.removeObserver(lifecycleObserver)
        }
    }

    return isResumed
}

@Composable
fun rememberPlayerWindowActive(): Boolean {
    val resumed = rememberIsForeground()
    val focused = LocalWindowInfo.current.isWindowFocused
    return resumed && focused
}
