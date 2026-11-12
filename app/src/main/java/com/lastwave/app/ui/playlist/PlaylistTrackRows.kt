package com.lastwave.app.ui.playlist

import com.lastwave.app.data.generate.GeneratedTrack
import java.text.Collator

// Keep source indices through sorting/filtering: a song may occur more than once.
internal fun playlistTrackDisplayIndices(
    tracks: List<GeneratedTrack>,
    folderTracks: List<GeneratedTrack>,
    sort: PlaylistTrackSort,
    ascending: Boolean,
    query: String,
    // YouTube's Liked Music arrives newest-first; every other playlist appends new songs.
    newestFirst: Boolean = false,
): List<Int> {
    val visible = folderTracks.toHashSet()
    val indices = tracks.indices.filter { tracks[it] in visible }
    // Locale collation so "Émile" sorts with "E", like YouTube Music; unknowns always last.
    val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
    fun by(text: (GeneratedTrack) -> String): Comparator<Int> =
        Comparator { a, b -> collator.compare(text(tracks[a]), text(tracks[b])) }
    val sorted = when (sort) {
        PlaylistTrackSort.CUSTOM -> if (ascending) indices else indices.reversed()
        PlaylistTrackSort.DATE_ADDED -> if (ascending != newestFirst) indices else indices.reversed()
        PlaylistTrackSort.NAME -> indices.sortedWith(by { it.name }.let { if (ascending) it else it.reversed() })
        PlaylistTrackSort.ARTIST -> indices.sortedWith(by { it.artist }.let { if (ascending) it else it.reversed() })
        PlaylistTrackSort.PLAY_TIME -> indices.sortedWith(
            compareBy<Int> { tracks[it].durationMs == null }
                .then(compareBy<Int> { tracks[it].durationMs }.let { if (ascending) it else it.reversed() }),
        )
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
