package com.lastwave.app.ui.playlist

import com.lastwave.app.data.generate.GeneratedTrack

// Keep source indices through sorting/filtering: a song may occur more than once.
internal fun playlistTrackDisplayIndices(
    tracks: List<GeneratedTrack>,
    folderTracks: List<GeneratedTrack>,
    sort: PlaylistTrackSort,
    ascending: Boolean,
    query: String,
): List<Int> {
    val visible = folderTracks.toHashSet()
    val indices = tracks.indices.filter { tracks[it] in visible }
    val sorted = when (sort) {
        PlaylistTrackSort.CUSTOM, PlaylistTrackSort.DATE_ADDED -> if (ascending) indices else indices.reversed()
        PlaylistTrackSort.NAME -> if (ascending) indices.sortedBy { tracks[it].name.lowercase() }
            else indices.sortedByDescending { tracks[it].name.lowercase() }
        PlaylistTrackSort.ARTIST -> if (ascending) indices.sortedBy { tracks[it].artist.lowercase() }
            else indices.sortedByDescending { tracks[it].artist.lowercase() }
        PlaylistTrackSort.PLAY_TIME -> if (ascending) indices.sortedBy { tracks[it].playcount ?: tracks[it].listeners ?: 0L }
            else indices.sortedByDescending { tracks[it].playcount ?: tracks[it].listeners ?: 0L }
    }
    if (query.isBlank()) return sorted
    val clean = query.lowercase()
    return sorted.filter { tracks[it].name.lowercase().contains(clean) || tracks[it].artist.lowercase().contains(clean) }
}

internal fun selectedPlaylistTrackIndex(tracks: List<GeneratedTrack>, sourceIndex: Int, selected: GeneratedTrack): Int? {
    fun GeneratedTrack.matchesSelected() = this == selected || (selected.url.isNotBlank() && url == selected.url)
    if (tracks.getOrNull(sourceIndex)?.matchesSelected() == true) return sourceIndex
    return tracks.indexOf(selected).takeIf { it >= 0 }
        ?: tracks.indexOfFirst { it.matchesSelected() }.takeIf { it >= 0 }
}
