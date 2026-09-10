package com.lastwave.desktop.data.model

data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val releaseYear: Int,
    val trackCount: Int
)