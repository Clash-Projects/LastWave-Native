package com.lastwave.desktop.di

import com.lastwave.desktop.data.model.Album
import com.lastwave.desktop.data.model.Track
import com.lastwave.desktop.data.remote.LastWaveApi
import com.lastwave.desktop.data.remote.LastWaveApiClient
import com.lastwave.desktop.data.repository.AlbumRepository
import com.lastwave.desktop.data.repository.AlbumRepositoryImpl
import javax.inject.Singleton

// Manual DI module for Desktop (no Android plugin needed)
object ApplicationGraph {
    @Singleton
    fun provideLastWaveApi(): LastWaveApi = LastWaveApiClient()

    @Singleton
    fun provideAlbumRepository(): AlbumRepository = AlbumRepositoryImpl(provideLastWaveApi())
}