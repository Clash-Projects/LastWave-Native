package com.lastwave.desktop.data.model

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Int, // in seconds
    val artworkUrl: String?
)