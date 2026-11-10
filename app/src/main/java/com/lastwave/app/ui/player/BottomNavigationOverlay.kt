package com.lastwave.app.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.lastwave.app.ui.theme.LocalMd3NavBar

typealias BottomNavigationContent = @Composable (material3NavigationEnabled: Boolean) -> Unit

/**
 * The current destination owns visibility, even while NavHost retains the outgoing
 * MainShell for its exit transition. Player expansion can fade the bar; leaving
 * the shell must remove both the drawing and its touch targets immediately.
 */
@Composable
internal fun BottomNavigationOverlay(
    hasBottomNavigation: Boolean,
    material3NavigationEnabled: Boolean,
    playerExpanded: Boolean,
    content: BottomNavigationContent,
) {
    if (!hasBottomNavigation) return

    AnimatedVisibility(
        visible = !playerExpanded,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(150)),
    ) {
        // Pass appearance at invocation time: the registered content can outlive
        // the composition in which MainShell originally registered it.
        CompositionLocalProvider(LocalMd3NavBar provides material3NavigationEnabled) {
            content(material3NavigationEnabled)
        }
    }
}
