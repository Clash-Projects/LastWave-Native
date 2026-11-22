package com.lastwave.app.data.artwork

import com.lastwave.app.data.model.ImageDto

/**
 * Faithful port of app.js's image-selection helpers (_isRealImg /
 * _isRealHomeImg and the repeated
 * `find(extralarge) || find(large) || find(medium) || find(any)` chain used
 * everywhere a Last.fm image array is read). Kept as free functions, not a
 * class, because the original has no state here — just filtering.
 */
object ArtworkNormalizer {

    /** The exact hash Last.fm embeds in its own gray "no artwork" placeholder
     *  image. Last.fm returns this as a real, non-blank URL, so a blank
     *  check alone isn't enough to tell real art from a placeholder — this
     *  is the check app.js's _isRealImg() does before trusting any image URL. */
    private const val LASTFM_NO_ART_HASH = "2a96cbd8b46e442fc41c2b86b821562f"

    fun isRealImage(url: String?): Boolean =
        !url.isNullOrBlank() && !url.contains(LASTFM_NO_ART_HASH)

    /** extralarge > large > medium > (any remaining real image) — the exact
     *  priority order used at every image-array read site in app.js/home.js. */
    fun bestImageUrl(images: List<ImageDto>): String? {
        val bySize = { size: String -> images.firstOrNull { it.size == size && isRealImage(it.url) }?.url }
        return bySize("extralarge")
            ?: bySize("large")
            ?: bySize("medium")
            ?: images.firstOrNull { isRealImage(it.url) }?.url
    }

    /** `t:${name}|${artist}`.toLowerCase() — the exact cache key format
     *  _resolveTrackArt() uses. No punctuation/feat./remaster stripping:
     *  the original app doesn't do any of that, so neither does this. */
    fun cacheKey(name: String, artist: String): String = "t:${name}|${artist}".lowercase()

    private val FEAT_REGEX = Regex("(?i)\\s*[(|\\[](feat|ft|with|featuring)\\.?\\s+.*?[)|\\]]")
    private val REMASTER_REGEX = Regex("(?i)\\s*[(|\\[].*?(remaster|live|version|edit|mono|stereo|deluxe|bonus).*?[)|\\]]")

    const val ITUNES_ARTIST_MATCH_CACHE_PROVIDER = "itunes_artist_credits_v2"

    // Older iTunes covers may have been selected with loose artist matching.
    // Exclude their marker so they resolve again with the stricter matcher.
    val HIGH_RES_PROVIDERS: Set<String> = setOf(
        "spotify", "apple", ITUNES_ARTIST_MATCH_CACHE_PROVIDER,
        "tidal", "deezer", "lastfm", "youtube_music", "ytmusic",
    )

    fun isYtMusicThumbnail(url: String?): Boolean =
        !url.isNullOrBlank() && (url.contains("googleusercontent.com") || url.contains("ggpht.com")) && !url.contains("ytimg.com")

    fun isYoutubeVideoThumbnail(url: String?): Boolean =
        !url.isNullOrBlank() && (url.contains("ytimg.com") || url.contains("youtube.com/vi"))

    private val YT_SIZE_REGEX = Regex("""=w\d+-h\d+[^?#]*|=s\d+[^?#]*""")

    /**
     * Upscales YouTube / Googleusercontent thumbnails from compressed low-res
     * dimensions (e.g. w120-h120 or s544) to high-resolution master dimensions (s1200 or maxresdefault).
     */
    fun upscaleYoutubeArtwork(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (!isRealImage(url)) return url
        var result = url
        if (result.contains("googleusercontent.com") || result.contains("ggpht.com")) {
            result = if (YT_SIZE_REGEX.containsMatchIn(result)) {
                result.replace(YT_SIZE_REGEX, "=w1200-h1200-l90-rj")
            } else if ('=' in result) {
                result.substringBeforeLast('=') + "=w1200-h1200-l90-rj"
            } else {
                "$result=w1200-h1200-l90-rj"
            }
        } else if (result.contains("i.ytimg.com/vi/") || result.contains("img.youtube.com/vi/")) {
            result = result.replace("/hqdefault.jpg", "/maxresdefault.jpg")
                .replace("/sddefault.jpg", "/maxresdefault.jpg")
        }
        return result
    }

    /**
     * Return a lower-data artwork URL when the source exposes a smaller variant.
     * On cellular, prefer a provider's smaller image, then a smaller embedded
     * thumbnail, and finally the original embedded URL when no variant exists.
     */
    fun mobileDataArtwork(primaryUrl: String?, embeddedUrl: String?): String? {
        downscaleProviderArtwork(primaryUrl)?.let { return it }
        downscaleProviderArtwork(embeddedUrl)?.let { return it }
        return embeddedUrl?.takeIf {
            isRealImage(it) && it != primaryUrl && !it.startsWith("file://")
        }
    }

    private fun downscaleYoutubeArtwork(url: String?): String? {
        if (url.isNullOrBlank() || !isRealImage(url)) return null
        if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) {
            return if (YT_SIZE_REGEX.containsMatchIn(url)) {
                url.replace(YT_SIZE_REGEX, "=w320-h320-l90-rj")
            } else if ('=' in url) {
                url.substringBeforeLast('=') + "=w320-h320-l90-rj"
            } else {
                "$url=w320-h320-l90-rj"
            }
        }
        if (url.contains("i.ytimg.com/vi/") || url.contains("img.youtube.com/vi/")) {
            return url.replace("/maxresdefault.jpg", "/hqdefault.jpg")
                .replace("/sddefault.jpg", "/hqdefault.jpg")
                .takeIf { it != url }
        }
        return null
    }

    private val APPLE_SIZE_REGEX = Regex("""\d{2,4}x\d{2,4}(?=(?:bb|cc)?\.(?:jpg|jpeg|png|webp))""", RegexOption.IGNORE_CASE)
    private val TIDAL_SIZE_REGEX = Regex("""(/images/[^/]{1,80}/)\d{2,4}x\d{2,4}(?=\.)""")
    private val DEEZER_SIZE_REGEX = Regex("""(/images/cover/[^/]+/)\d{2,4}x\d{2,4}(?=-)""")
    private val LASTFM_SIZE_SEGMENT = Regex("""/i/u/\d+x\d+/""")

    private fun isLastFmArtwork(url: String): Boolean =
        url.contains("last.fm", ignoreCase = true) ||
            url.contains("lastfm", ignoreCase = true) ||
            url.contains("audioscrobbler", ignoreCase = true)

    private fun downscaleProviderArtwork(url: String?): String? {
        if (url.isNullOrBlank() || !isRealImage(url)) return null
        downscaleYoutubeArtwork(url)?.let { return it }

        return when {
            url.contains("mzstatic.com", ignoreCase = true) && APPLE_SIZE_REGEX.containsMatchIn(url) ->
                APPLE_SIZE_REGEX.replace(url, "300x300")
            url.contains("resources.tidal.com/images/", ignoreCase = true) && TIDAL_SIZE_REGEX.containsMatchIn(url) ->
                TIDAL_SIZE_REGEX.replace(url) { "${it.groupValues[1]}320x320" }
            url.contains("dzcdn.net/images/cover/", ignoreCase = true) && DEEZER_SIZE_REGEX.containsMatchIn(url) ->
                DEEZER_SIZE_REGEX.replace(url) { "${it.groupValues[1]}250x250" }
            isLastFmArtwork(url) && LASTFM_SIZE_SEGMENT.containsMatchIn(url) ->
                LASTFM_SIZE_SEGMENT.replace(url, "/i/u/300x300/")
            else -> null
        }
    }

    /** Last.fm serves 300x300 by default; the same image exists at 770px. */
    fun upgradeLastFm(url: String): String =
        if (isLastFmArtwork(url)) url.replace(LASTFM_SIZE_SEGMENT, "/i/u/770x0/") else url

    private val TOPIC_SUFFIX = Regex("""(?i)\s*-\s*topic\s*$""")
    private val VEVO_SUFFIX = Regex("""VEVO\s*$""")
    private val VIDEO_NOISE = Regex(
        """(?i)\s*[(\[]\s*(?:official\s*)?(?:hd\s*|4k\s*)?(?:music\s*|lyric\s*|lyrics\s*)?(?:video|audio|visuali[sz]er|lyrics?|mv|m/v)(?:\s*video)?\s*[)\]]""",
    )
    private val PIPE_TAIL = Regex("""\s+[|｜].*$""")

    private fun lettersOnly(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Search terms for the cover lookups. YouTube metadata carries upload
     * noise ("Artist - Topic", "ArtistVEVO", "Artist - Song (Official
     * Video)", "Song | Album") that makes the Apple Music / Tidal / Deezer
     * searches miss, which is why so many covers stayed on the low-res
     * YouTube thumbnail. Only noise is removed; the cache key never changes.
     */
    fun searchTerms(name: String, artist: String): Pair<String, String> {
        val cleanArtist = artist.replace(TOPIC_SUFFIX, "").replace(VEVO_SUFFIX, "").trim()
        var cleanName = name.replace(VIDEO_NOISE, "").replace(PIPE_TAIL, "").trim()
        val dash = cleanName.indexOf(" - ")
        if (dash > 0 && cleanArtist.isNotBlank()) {
            val left = lettersOnly(cleanName.substring(0, dash))
            val right = cleanName.substring(dash + 3).trim()
            val who = lettersOnly(cleanArtist)
            if (right.isNotEmpty() && left.isNotEmpty() && who.isNotEmpty() &&
                (left == who || left.contains(who) || who.contains(left))
            ) {
                cleanName = right
            }
        }
        return cleanName.ifBlank { name } to cleanArtist.ifBlank { artist }
    }

    fun cleanTitle(title: String): String = title
        .replace(FEAT_REGEX, "")
        .replace(REMASTER_REGEX, "")
        .trim()

    fun cleanArtist(artist: String): String = artist
        .replace(Regex("(?i)\\s*(feat|ft|with|&|,|/|x)\\s+.*"), "")
        .trim()
}
