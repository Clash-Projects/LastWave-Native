package com.lastwave.app.playback

/** Artwork belongs to a track and request, even when URLs repeat across skips. Main thread only. */
internal class TrackArtwork<T> {
    private data class Identity(
        val id: String?, val videoId: String?, val title: String,
        val artist: String, val artworkUrl: String?,
    )
    class Request internal constructor(val generation: Long, val url: String)

    private fun identity(track: PlayableTrack?) = track?.let {
        Identity(it.internalTrackId, it.videoId, it.title, it.artist, it.artworkUrl)
    }
    private var owner: Identity? = null
    var generation: Long = 0
        private set
    private var url: String? = null
    private var value: T? = null

    fun select(track: PlayableTrack?): Boolean {
        val next = identity(track)
        if (next == owner) return false
        owner = next
        generation++
        url = null
        value = null
        return true
    }

    fun begin(url: String, track: PlayableTrack?): Request? {
        if (owner == null || owner != identity(track)) return null
        generation++
        this.url = url
        value = null
        return Request(generation, url)
    }

    fun complete(request: Request, track: PlayableTrack?, value: T?): Boolean {
        if (request.generation != generation || request.url != url || owner != identity(track)) return false
        this.value = value
        return true
    }

    fun urlFor(track: PlayableTrack?): String? = url.takeIf { owner == identity(track) }
    fun valueFor(track: PlayableTrack?): T? = value.takeIf { owner == identity(track) }
}
