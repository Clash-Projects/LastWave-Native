package com.lastwave.desktop.data.repository

import com.lastwave.desktop.data.model.Album
import com.lastwave.desktop.data.model.Track
import kotlin.List

interface AlbumRepository {
    suspend fun getAlbums(): List<Album>
    suspend fun getFavorites(): List<Track>
    suspend fun searchTracks(query: String): List<Track>
}