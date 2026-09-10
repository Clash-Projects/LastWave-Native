package com.lastwave.desktop

import com.lastwave.desktop.ui.main.MainScreen
import com.lastwave.desktop.ui.theme.LastWaveDesktopTheme
import com.lastwave.desktop.util.DependencyInjectionKt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.compose.desktop.api.window.AbstractWindow
import org.jetbrains.compose.desktop.api.window.Window
import org.jetbrains.compose.desktop.api.window.WindowProvider
import org.jetbrains.compose.desktop.api.WindowFeatures
import org.jetbrains.compose.desktop.settings.DefaultWindowFeatures
import org.jetbrains.compose.desktop.settings.WindowWidth
import org.jetbrains.compose.desktop.settings.WindowHeight

// Initialize dependency injection before the event loop
fun main() {
    DependencyInjectionKt.initialize()

    val defaultFeatures = DefaultWindowFeatures

    WindowProvider.window(
        title = "LastWave Desktop",
        width = WindowWidth(1280),
        height = WindowHeight(720),
        resizable = true,
        features = defaultFeatures
    ) { window ->
        abstractWindow -> {
            LastWaveDesktopTheme(darkTheme = true) {
                MainScreen()
            }
        }
    }
}