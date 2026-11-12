package com.lastwave.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.lastwave.app.data.repository.ThemeUiState

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.staticCompositionLocalOf
import com.lastwave.app.data.local.ThemeMode

/** Shared composition local indicating whether the active theme is dark mode. */
val LocalIsDarkTheme = staticCompositionLocalOf { true }
/** Shared composition local indicating whether bottom navigation tabs are hidden. */
val LocalHideNavTabs = staticCompositionLocalOf { false }

/** Swallows every haptic request; provided app-wide when Settings > Haptic Feedback is off. */
private object SilentHapticFeedback : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

private const val THEME_TRANSITION_MS = 600

@Composable
private fun animateColorScheme(target: ColorScheme): ColorScheme {
    @Composable
    fun a(color: Color) = animateColorAsState(color, tween(THEME_TRANSITION_MS), label = "themeColor").value
    return target.copy(
        primary = a(target.primary),
        onPrimary = a(target.onPrimary),
        primaryContainer = a(target.primaryContainer),
        onPrimaryContainer = a(target.onPrimaryContainer),
        inversePrimary = a(target.inversePrimary),
        secondary = a(target.secondary),
        onSecondary = a(target.onSecondary),
        secondaryContainer = a(target.secondaryContainer),
        onSecondaryContainer = a(target.onSecondaryContainer),
        tertiary = a(target.tertiary),
        onTertiary = a(target.onTertiary),
        tertiaryContainer = a(target.tertiaryContainer),
        onTertiaryContainer = a(target.onTertiaryContainer),
        background = a(target.background),
        onBackground = a(target.onBackground),
        surface = a(target.surface),
        onSurface = a(target.onSurface),
        surfaceVariant = a(target.surfaceVariant),
        onSurfaceVariant = a(target.onSurfaceVariant),
        surfaceTint = a(target.surfaceTint),
        surfaceContainerLowest = a(target.surfaceContainerLowest),
        surfaceContainerLow = a(target.surfaceContainerLow),
        surfaceContainer = a(target.surfaceContainer),
        surfaceContainerHigh = a(target.surfaceContainerHigh),
        surfaceContainerHighest = a(target.surfaceContainerHighest),
        outline = a(target.outline),
        outlineVariant = a(target.outlineVariant),
    )
}

/**
 * Wraps the whole app. Supports System Default, Light, and Dark modes.
 * Kyant0 Backdrop captures underlying content into a hardware-accelerated layer
 * shared app-wide; screens add their own sibling sources for local content
 * (nav bar, player, headers).
 */
@Composable
fun LastWaveTheme(
    themeState: ThemeUiState,
    content: @Composable () -> Unit,
) {
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themeState.themeMode) {
        ThemeMode.SYSTEM -> isSystemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    // ThemeRepository resolves the scheme (wallpaper Monet on S+, with
    // now-playing artwork blended in), so the UI, widgets and notification
    // agree. Animated so the theme glides between songs instead of jumping.
    val activeColorScheme = animateColorScheme(
        if (isDark) themeState.darkColorScheme else themeState.lightColorScheme,
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            runCatching {
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !isDark
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    window.isStatusBarContrastEnforced = false
                    window.isNavigationBarContrastEnforced = false
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = activeColorScheme,
        typography = if (themeState.useCustomFont) LastWaveTypography else SystemTypography,
        shapes = LastWaveShapes,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            // Settings > Haptic Feedback gates every LocalHapticFeedback call in
            // the app from this single point (the system "touch feedback"
            // setting still applies on top when it is on).
            val systemHaptics = LocalHapticFeedback.current
            val appHaptics = remember(systemHaptics, themeState.uiHaptics) {
                if (themeState.uiHaptics) systemHaptics else SilentHapticFeedback
            }
            CompositionLocalProvider(
                LocalHapticFeedback provides appHaptics,
                LocalLiquidGlass provides themeState.liquidGlass,
                LocalMd3NavBar provides themeState.md3NavBar,
                LocalHideNavTabs provides themeState.hideNavTabs,
                LocalIsDarkTheme provides isDark,
            ) {
                content()
            }
        }
    }
}
