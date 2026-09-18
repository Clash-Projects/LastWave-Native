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
 * (`\p{L}` letters + `\p{N}` numbers from any script) and case folding
 * uses [Locale.ROOT] so devices in locales like Turkish (where `I`
 * lowercases to `ı`) match identically everywhere.
 */
internal object TextMatch {
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val DIACRITICS = Regex("\\p{M}+")
    private val MULTI_SPACE = Regex("\\s+")
    val VARIANT_WORDS = setOf(
        "live", "remix", "karaoke", "cover", "instrumental", "slowed", "sped", "nightcore",
        "acoustic", "demo", "edit", "remaster", "remastered", "mono", "stereo",
    )
    val MATCH_NOISE_WORDS = setOf("official", "audio", "video", "visualizer", "lyrics", "lyric")
    private val FEATURING_CLAUSE = Regex("(?i)[(\\[]\\s*(feat(?:uring)?|ft)\\.?\\s+.*?[)\\]]")
    private val VERSION_CLAUSE =
        Regex("(?i)[(\\[][^)\\]]*(live|remix|acoustic|demo|edit|remaster(?:ed)?|mono|stereo)[^)\\]]*[)\\]]")

    fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .replace(NON_WORD, " ")
            .trim()
            .replace(MULTI_SPACE, " ")

    fun tokens(value: String): Set<String> = normalize(value)
        .split(' ')
        .filter { it.isNotBlank() && it !in MATCH_NOISE_WORDS }
        .toSet()

    fun baseTitle(value: String): String = value
        .replace(FEATURING_CLAUSE, " ")
        .replace(VERSION_CLAUSE, " ")

    fun similarity(a: String, b: String): Int {
        val normA = normalize(a)
        val normB = normalize(b)
        if (normA == normB) return 100
        if (normA.isNotBlank() && normB.isNotBlank()) {
            if (normA.contains(normB) || normB.contains(normA)) {
                val ratio = (minOf(normA.length, normB.length) * 100) / maxOf(normA.length, normB.length)
                if (ratio >= 45) return maxOf(85, ratio)
            }
        }
        val left = tokens(a)
        val right = tokens(b)
        if (left.isEmpty() || right.isEmpty()) return 0
        val common = left.intersect(right).size
        val dice = (200 * common) / (left.size + right.size)
        val subset = if (common == minOf(left.size, right.size) && common > 0) 80 else 0
        return maxOf(dice, subset)
    }

    fun matchScore(candidate: YouTubeMusicTrack, title: String, artist: String): Int {
        val wantedTitle = normalize(title)
        val wantedArtist = normalize(artist)
        val candidateTitle = normalize(candidate.title)
        val candidateArtist = normalize(candidate.artist)
        var score = maxOf(
            similarity(candidate.title, title),
            similarity(baseTitle(candidate.title), baseTitle(title)),
        ) * 5 + similarity(candidate.artist, artist) * 3
        if (candidateTitle == wantedTitle) score += 600
        if (wantedArtist.isNotBlank() && candidateArtist == wantedArtist) score += 350
        val wantedVariants = tokens(title).intersect(VARIANT_WORDS)
        val unexpectedVariants = tokens(candidate.title).intersect(VARIANT_WORDS) - wantedVariants
        score -= unexpectedVariants.size * 250
        return score
    }
}
