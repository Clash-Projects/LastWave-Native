package com.lastwave.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/** False while this content cannot be seen (off-screen tab, or under the fully open player). */
val LocalAmbientAnimationsActive = compositionLocalOf { true }

val LocalArtworkViewModel = staticCompositionLocalOf<ArtworkViewModel?> { null }
val LocalArtworkCellular = compositionLocalOf<Boolean?> { null }

@Composable
fun AmbientAnimationsGate(visible: () -> Boolean, content: @Composable () -> Unit) {
    val parent = LocalAmbientAnimationsActive.current
    CompositionLocalProvider(LocalAmbientAnimationsActive provides (parent && visible()), content = content)
}
