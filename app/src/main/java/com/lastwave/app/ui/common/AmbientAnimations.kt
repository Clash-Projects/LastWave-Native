package com.lastwave.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

/** False while this content cannot be seen (off-screen tab, or under the fully open player). */
val LocalAmbientAnimationsActive = compositionLocalOf { true }

@Composable
fun AmbientAnimationsGate(visible: () -> Boolean, content: @Composable () -> Unit) {
    val parent = LocalAmbientAnimationsActive.current
    CompositionLocalProvider(LocalAmbientAnimationsActive provides (parent && visible()), content = content)
}
