package com.lastwave.desktop.data.repository

import com.lastwave.desktop.data.model.Album
import com.lastwave.desktop.data.remote.LastWaveApi
import com.lastwave.desktop.di.provideLastWaveApi
import kotlinx.coroutines.suspendCancellableCoroutine

class AlbumRepositoryImpl(
    private val api: LastWaveApi = provideLastWaveApi()
) : AlbumRepository {

    override suspend fun getAlbums(): List<Album> {
        return suspendCancellableCoroutine { cont ->
            kotlinx.coroutines.delay(300)
            cont.resumeWith(
                kotlinx.coroutines.CoroutineSuccess(
                    api.getAlbums()
                )
            )
        }
    }

    override suspend fun getFavorites(): List<Track> {
        return suspendCancellableCoroutine { cont ->
            kotlinx.coroutines.delay(300)
            cont.resumeWith(
                kotlinx.coroutines.CoroutineSuccess(
                    api.getFavorites()
                )
            )
        }
    }

    override suspend fun searchTracks(query: String): List<Track> {
        return suspendCancellableCoroutine { cont ->
            kotlinx.coroutines.delay(500)
            cont.resumeWith(
                kotlinx.coroutines.CoroutineSuccess(
                    api.searchTracks(query)
                )
            )
        }
    }
}