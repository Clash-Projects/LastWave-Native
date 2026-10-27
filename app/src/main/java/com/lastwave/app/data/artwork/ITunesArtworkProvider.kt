package com.lastwave.app.data.artwork

import com.lastwave.app.data.network.ITunesSearchClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ITunesArtworkProvider @Inject constructor(
    private val iTunesSearchClient: ITunesSearchClient,
) {
    private val upscalePattern = Regex("""/\d+x\d+bb\.(jpg|png|webp)$""", RegexOption.IGNORE_CASE)

    suspend fun fetchArtworkUrl(track: String, artist: String): String? = withContext(Dispatchers.IO) {
        val song = iTunesSearchClient.searchBestMatch(track, artist)
            ?: run {
                val cleanedTrack = ArtworkNormalizer.cleanTitle(track)
                val cleanedArtist = ArtworkNormalizer.cleanArtist(artist)
                if (cleanedTrack != track || cleanedArtist != artist) {
                    iTunesSearchClient.searchBestMatch(cleanedTrack, cleanedArtist)
                } else null
            }
        val raw = song?.artworkUrl100 ?: song?.artworkUrl60
        raw?.let { upscale(it) }
    }

    /** …/100x100bb.jpg -> …/1200x1200bb.jpg master artwork */
    private fun upscale(rawUrl: String): String = upscalePattern.replace(rawUrl, "/1200x1200bb.jpg")
}
