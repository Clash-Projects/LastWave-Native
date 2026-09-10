package com.lastwave.desktop.util

import com.lastwave.desktop.data.repository.AlbumRepository
import com.lastwave.desktop.data.remote.LastWaveApi
import com.lastwave.desktop.di.applicationGraph
import com.lastwave.desktop.di.provideAlbumRepository
import com.lastwave.desktop.di.provideLastWaveApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

object DependencyInjectionKt {
    @Volatile
    private var initialized = false

    fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // Initialize shared modules, database, API clients, etc.
            // This mirrors what the Android app does with Hilt/Dagger
            applicationGraph()
            initialized = true
        }
    }

    @Composable
    fun withRepository<T>(block: (T) -> Unit): T {
        // Provide the repository instance for the compose tree
        // In a real app, this would use proper dependency injection
        throw UnsupportedOperationException("Use provideRepository composable instead")
    }
}