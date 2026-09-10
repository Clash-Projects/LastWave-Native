package com.lastwave.desktop.data.remote

import com.lastwave.desktop.data.model.Album
import com.lastwave.desktop.data.model.Track
import java.util.List

interface LastWaveApi {
    suspend fun getNowPlayingTrack(): Track?
    suspend fun getAlbums(): List<Album>
    suspend fun getFavorites(): List<Track>
    suspend fun searchTracks(query: String): List<Track>
    suspend fun likeTrack(trackId: String): Boolean
    suspend fun unlikeTrack(trackId: String): Boolean
}