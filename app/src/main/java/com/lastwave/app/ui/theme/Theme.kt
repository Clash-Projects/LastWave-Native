package com.lastwave.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.lastwave.app.data.local.AccentMode
import com.lastwave.app.data.repository.ThemeUiState

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.staticCompositionLocalOf
import com.lastwave.app.data.local.ThemeMode

/** Shared composition local indicating whether the active theme is dark mode. */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

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
    // Dynamic Color ON (S+): use the live wallpaper Monet scheme instead of the
    // default manual accent or monochrome. System dynamic auto-tracks wallpaper
    // changes, so no manual refresh is needed. Now-playing artwork wins when it
    // is active; pre-S falls back to the repository's wallpaper-seeded scheme.
    val context = LocalContext.current
    val useSystemDynamic = themeState.mode == AccentMode.DYNAMIC &&
        !themeState.isNowPlayingThemed &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val systemDark = if (useSystemDynamic) {
        val base = dynamicDarkColorScheme(context)
        if (themeState.amoled) {
            base.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceContainerLow = Color.Black,
                surfaceContainerLowest = Color.Black,
            )
        } else base
    } else null
    val systemLight = if (useSystemDynamic) dynamicLightColorScheme(context) else null
    val activeColorScheme = when {
        systemDark != null && isDark -> systemDark
        systemLight != null && !isDark -> systemLight
        isDark -> themeState.darkColorScheme
        else -> themeState.lightColorScheme
    }

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
            CompositionLocalProvider(
                LocalLiquidGlass provides themeState.liquidGlass,
                LocalMd3NavBar provides themeState.md3NavBar,
                LocalIsDarkTheme provides isDark,
            ) {
                content()
            }
        }
    }
}
