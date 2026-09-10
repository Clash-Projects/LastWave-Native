package com.lastwave.desktop.data.remote

import com.lastwave.desktop.data.model.Album
import com.lastwave.desktop.data.model.Track
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit

class LastWaveApiClient : LastWaveApi {

    override suspend fun getNowPlayingTrack(): Track? {
        // In a real app, this would query the media session or playback service
        suspendCancellableCoroutine { cont ->
            // Simulate API call
            kotlinx.coroutines.delay(500)
            // Return a mock track
            cont.resumeWith(
                kotlinx.coroutines.CoroutineSuccess(
                    Track(
                        id = "1",
                        title = "Midnight City",
                        artist = "M83",
                        album = "Hurry Up, We're Dreaming",
                        duration = 225,
                        artworkUrl = "https://i.scdn.co/image/ab6ix7yK..."
                    )
                )
            )
        }
    }

    override suspend fun getAlbums(): List<Album> {
        return listOf(
            Album(
                id = "1",
                title = "Hurry Up, We're Dreaming",
                artist = "M83",
                artworkUrl = "https://i.scdn.co/image/ab6ix7yK...",
                releaseYear = 2011,
                trackCount = 22
            ),
            Album(
                id = "2",
                title = "Before the Dawn Heals Us",
                artist = "M83",
                artworkUrl = "https://i.scdn.co/image/ab6ix7yK...",
                releaseYear = 2005,
                trackCount = 8
            ),
            Album(
                id = "3",
                title = "Dead Cities, Red Village, Lost Light",
                artist = "M83",
                artworkUrl = "https://i.scdn.co/image/ab6ix7yK...",
                releaseYear = 2009,
                trackCount = 9
            )
        )
    }

    override suspend fun getFavorites(): List<Track> {
        return listOf(
            Track(
                id = "1",
                title = "Midnight City",
                artist = "M83",
                album = "Hurry Up, We're Dreaming",
                duration = 225,
                artworkUrl = "https://i.scdn.co/image/ab6ix7yK..."
            ),
            Track(
                id = "2",
                title = "Kim & Jones",
                artist = "M83",
                album = "Dead Cities, Red Village, Lost Light",
                duration = 189,
                artworkUrl = "https://i.scdn.co/image/ab6ix7yK..."
            )
        )
    }

    override suspend fun searchTracks(query: String): List<Track> {
        // Simulated search
        return listOf(
            Track(
                id = "10",
                title = "Wait",
                artist = "M83",
                album = "Hurry Up, We're Dreaming",
                duration = 310,
                artworkUrl = "https://i.scdn.co/image/ab6ix7yK..."
            ),
            Track(
                id = "11",
                title = "Racine Carrée",
                artist = "M83",
                album = "Dead Cities, Red Village, Lost Light",
                duration = 267,
                artworkUrl = "https://i.scdn.co/image/ab6ix7yK..."
            )
        )
    }

    override suspend fun likeTrack(trackId: String): Boolean {
        // Simulate liking a track
        return true
    }

    override suspend fun unlikeTrack(trackId: String): Boolean {
        // Simulate unliking a track
        return true
    }
}