package com.lastwave.app.data.music

import java.text.Normalizer
import java.util.Locale

/**
 * Script-agnostic title/artist matching used by InnerTube search, radio
 * seeds and taste-profile scoring.
 *
 * Previously these helpers lived inside [InnerTubeMusicApi] with a
 * Latin-only word pattern (`[^a-z0-9]+`), which reduced every non-Latin
 * query — Cyrillic, CJK, Arabic, … — to blank and broke matching for
 * those scripts (issue #102). The word pattern is now Unicode-aware
 * (`\p{L}` letters + `\p{N}` numbers + `\p{M}` marks from any script) and
 * case folding uses [Locale.ROOT] so devices in locales like Turkish (where
 * `I` lowercases to `ı`) match identically everywhere. Marks ride along with
 * words because [normalize] strips diacritics only off Latin bases — Hindi
 * matras, Thai vowels and the like are integral letters, not noise.
 */
internal object TextMatch {
    private val NON_WORD = Regex("[^\\p{L}\\p{N}\\p{M}]+")
    private val MULTI_SPACE = Regex("\\s+")
    val VARIANT_WORDS = setOf(
        "live", "remix", "karaoke", "cover", "instrumental", "slowed", "sped", "nightcore",
        "acoustic", "demo", "edit", "remaster", "remastered", "mono", "stereo",
        "version", "deluxe", "bonus", "mix", "extended", "radio", "clean", "explicit",
        "original", "orchestral", "unplugged", "rerecorded", "anniversary", "edition",
        // Same rendition markers in the languages this app serves, stored
        // normalized to match [normalize] output. They feed the subset matcher
        // and [matchScore]'s unexpected-variant penalty like the English ones.
        "vivo", "acustico", "direct", "acoustique", "akustik", "canli",
        "ライブ", "カバー", "リミックス", "アコースティック",
        "라이브", "커버", "리믹스", "어쿠스틱",
        "مباشر", "ريمكس",
        "लाइव", "कवर", "रीमिक्स",
        "现场", "翻唱", "混音",
        "лайв", "кавер", "ремикс",
    )
    val MATCH_NOISE_WORDS = setOf(
        "official", "audio", "video", "visualizer", "lyrics", "lyric", "hd", "hq", "4k", "track", "music",
        "ost", "soundtrack", "lyrical", "full"
    )
    private val FEATURING_CLAUSE = Regex("(?i)[(\\[]\\s*(feat(?:uring)?|ft)\\.?\\s+.*?[)\\]]")
    private val SOUNDTRACK_OR_OST_CLAUSE =
        Regex("(?i)[(\\[]\\s*(from\\s+[\"']?|ost\\b|original\\s+(?:motion\\s+picture\\s+)?soundtrack)[^)\\]]*[)\\]]")
    private val VERSION_OR_LABEL_CLAUSE =
        Regex("(?i)[(\\[][^)\\]]*(official|music\\s*video|audio|video|visualizer|lyrics?|hd|hq|4k|live|remix|acoustic|demo|edit|remaster(?:ed)?|mono|stereo|deluxe|bonus|version|mix)[^)\\]]*[)\\]]")

    fun normalize(value: String): String {
        val lower = value.lowercase(Locale.ROOT)
        val nfd = Normalizer.normalize(lower, Normalizer.Form.NFD)
        // Strip diacritics only off Latin bases (Café → cafe). In other scripts
        // marks are integral letters: Hindi matras (तुम → तम), Thai vowels,
        // Arabic vocalization, Korean jamo (which NFKC below recomposes).
        // Stripping them merges distinct words across those languages.
        val stripped = nfd.replace(LATIN_DIACRITICS, "")
        val composed = Normalizer.normalize(stripped, Normalizer.Form.NFKC)
        return foldArabic(composed)
            .replace(NON_WORD, " ")
            .trim()
            .replace(MULTI_SPACE, " ")
    }

    /** Combining marks following a Latin base character. Java supports this
     *  bounded lookbehind; a leading stray mark falls through to NON_WORD. */
    private val LATIN_DIACRITICS = Regex("(?<=[A-Za-z])\\p{M}+")

    /** Standard Arabic search folding: hamza-alef variants have no NFD
     *  decomposition, so mark-stripping alone never folds them (أ stays أ).
     *  Tashkeel (optional vocalization) is stripped; base letters are kept. */
    private fun foldArabic(value: String): String = value
        .replace(Regex("[أإآٱ]"), "ا")
        .replace(Regex("ؤ"), "و")
        .replace(Regex("ئ"), "ي")
        .replace(Regex("ة"), "ه")
        .replace(Regex("ى"), "ي")
        .replace(Regex("[\u064B-\u0655\u0670]"), "")

    /** True when the string carries letters outside A-Z: CJK, Thai, Indic,
     *  Arabic, Cyrillic, Hangul, … — scripts where partial overlap at modest
     *  ratios is normal, unlike Latin prefix confusion. */
    private fun hasNonLatinLetter(value: String): Boolean =
        value.any { it.isLetter() && it !in 'a'..'z' && it !in 'A'..'Z' }

    fun tokens(value: String): Set<String> = normalize(value)
        .split(' ')
        .filter { it.isNotBlank() && it !in MATCH_NOISE_WORDS }
        .toSet()

    fun baseTitle(value: String): String = value
        .substringBefore(" | ")
        .substringBefore(" // ")
        .replace(FEATURING_CLAUSE, " ")
        .replace(SOUNDTRACK_OR_OST_CLAUSE, " ")
        .replace(VERSION_OR_LABEL_CLAUSE, " ")

    fun similarity(a: String, b: String): Int {
        val normA = normalize(a)
        val normB = normalize(b)
        if (normA == normB) return 100
        if (normA.isBlank() || normB.isBlank()) return 0
        if (normA.contains(normB) || normB.contains(normA)) {
            val ratio = (minOf(normA.length, normB.length) * 100) / maxOf(normA.length, normB.length)
            // 4.0.0 used 45 for everything; 4.2.4 raised it to 75 to stop Latin
            // prefix confusion (Belong/Belonging at 66). Spaceless scripts
            // (CJK/Thai/…) legitimately overlap at modest ratios, so the 45 bar
            // applies only to strings carrying non-Latin letters.
            val bar = if (hasNonLatinLetter(normA) || hasNonLatinLetter(normB)) 45 else 75
            if (ratio >= bar) return maxOf(85, ratio)
        }
        val left = tokens(a)
        val right = tokens(b)
        if (left.isEmpty() || right.isEmpty()) return 0
        val common = left.intersect(right).size
        val dice = (200 * common) / (left.size + right.size)
        val extraWords = (left + right) - left.intersect(right)
        val isSafeSubset = common > 0 && common == minOf(left.size, right.size) &&
            extraWords.all { word -> word in VARIANT_WORDS || word in MATCH_NOISE_WORDS || word.all(Char::isDigit) }
        val subset = if (isSafeSubset) 80 else 0
        return maxOf(dice, subset)
    }

    fun isSafeTitleMatch(candidateTitle: String, wantedTitle: String, artist: String = ""): Boolean {
        val normWanted = normalize(wantedTitle)
        val normCandidate = normalize(candidateTitle)
        if (normWanted == normCandidate) return true
        if (normWanted.isBlank() || normCandidate.isBlank()) return false

        val cleanCandidate = if (artist.isNotBlank()) {
            val normArtist = normalize(artist)
            if (normArtist.isNotBlank() && normCandidate.startsWith(normArtist)) {
                normCandidate.removePrefix(normArtist).trim().removePrefix("-").trim()
            } else normCandidate
        } else normCandidate

        if (cleanCandidate == normWanted) return true

        val baseWanted = normalize(baseTitle(wantedTitle))
        val baseCandidate = normalize(baseTitle(cleanCandidate))
        if (baseWanted.isNotBlank() && baseWanted == baseCandidate) return true

        val wantedTokens = tokens(wantedTitle)
        val candidateTokens = tokens(cleanCandidate)
        if (wantedTokens.isEmpty() || candidateTokens.isEmpty()) return false

        val common = wantedTokens.intersect(candidateTokens)
        if (common.size == wantedTokens.size || common.size == candidateTokens.size) {
            val extraWords = (wantedTokens + candidateTokens) - common
            val artistTokens = if (artist.isNotBlank()) tokens(artist) else emptySet()
            val allAllowedNoise = VARIANT_WORDS + MATCH_NOISE_WORDS + artistTokens
            if (extraWords.all { it in allAllowedNoise || it.all(Char::isDigit) }) {
                return true
            }
        }

        val sim = maxOf(
            similarity(cleanCandidate, wantedTitle),
            similarity(baseCandidate, baseWanted),
        )
        return sim >= 65
    }

    fun matchScore(candidate: YouTubeMusicTrack, title: String, artist: String): Int {
        val wantedTitle = normalize(title)
        val wantedArtist = normalize(artist)
        val candidateTitle = normalize(candidate.title)
        val candidateArtist = normalize(candidate.artist)
        val titleSim = maxOf(
            similarity(candidate.title, title),
            similarity(baseTitle(candidate.title), baseTitle(title)),
        )
        val artistSim = similarity(candidate.artist, artist)
        var score = titleSim * 5 + artistSim * 3
        if (candidateTitle == wantedTitle) score += 600
        if (wantedArtist.isNotBlank() && candidateArtist == wantedArtist) score += 350
        val wantedVariants = tokens(title).intersect(VARIANT_WORDS)
        val unexpectedVariants = tokens(candidate.title).intersect(VARIANT_WORDS) - wantedVariants
        score -= unexpectedVariants.size * 250
        if (wantedArtist.isNotBlank()) {
            val artistContains = candidateArtist.contains(wantedArtist) || wantedArtist.contains(candidateArtist)
            val titleContainsArtist = candidateTitle.contains(wantedArtist)
            if (artistSim < 35 && !artistContains && !titleContainsArtist) {
                score -= 1000
            }
        }
        if (titleSim < 50) {
            score -= 1500
        }

        // Strongly prefer official YouTube Music tracks (with album and YT Music thumbnails)
        // over general YouTube videos (with ytimg video thumbnails or video keywords).
        val art = candidate.artworkUrl.orEmpty()
        val isYtMusicThumb = (art.contains("googleusercontent.com") || art.contains("ggpht.com")) && !art.contains("ytimg.com")
        if (isYtMusicThumb) score += 350
        if (!candidate.album.isNullOrBlank()) score += 200

        val isVideoThumb = art.contains("ytimg.com") || art.contains("youtube.com/vi")
        if (isVideoThumb) score -= 400

        val candidateTokens = tokens(candidate.title)
        val videoNoise = setOf("video", "mv", "musicvideo")
        val unwantedVideoWords = candidateTokens.intersect(videoNoise) - tokens(title).intersect(videoNoise)
        if (unwantedVideoWords.isNotEmpty()) {
            score -= unwantedVideoWords.size * 200
        }

        return score
    }
}
