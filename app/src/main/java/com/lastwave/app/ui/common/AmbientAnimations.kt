package com.lastwave.app.ui.common

import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** False while this content cannot be seen (off-screen tab, or under the fully open player). */
val LocalAmbientAnimationsActive = compositionLocalOf { true }

val LocalArtworkViewModel = staticCompositionLocalOf<ArtworkViewModel?> { null }
val LocalArtworkCellular = compositionLocalOf<Boolean?> { null }

@Composable
fun AmbientAnimationsGate(visible: () -> Boolean, content: @Composable () -> Unit) {
    val parent = LocalAmbientAnimationsActive.current
    CompositionLocalProvider(LocalAmbientAnimationsActive provides (parent && visible()), content = content)
}

// An endless marquee on a long title redrew the paused player every frame (~50 fps, measured).
// 0 iterations stops it and returns the text to its start.
fun Modifier.playingMarquee(isPlaying: Boolean): Modifier =
    basicMarquee(iterations = if (isPlaying) Int.MAX_VALUE else 0)
