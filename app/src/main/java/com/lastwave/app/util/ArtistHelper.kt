package com.lastwave.app.util

/**
 * Utility for splitting and handling composite multi-artist strings cleanly.
 * Handles separators such as commas, ampersands, slashes, "feat.", "ft.", "with", and "x".
 */
object ArtistHelper {
    private val SEPARATOR_REGEX = Regex(
        "(?:\\s*,\\s*|\\s*&\\s*|\\s*;\\s*|\\s*\\/\\s*|\\s*\\+\\s*|\\s+(?:ft\\.?|feat\\.?|featuring|with|and|x|X)\\s+)",
        RegexOption.IGNORE_CASE
    )

    // Matches "973 plays", "3.4K plays", "15M listens", "15 ml listens",
    // "15 mln listeners", "2.3M monthly listeners", "10K subscribers", etc.
    // Suffix covers K/M/B/T plus long "mln"/"mil"/"mn"/"ml" abbreviations
    // seen in localized YouTube Music / Last.fm counters.
    private val STAT_PATTERN = Regex(
        """^[\d.,\s]+(?:(?:mln|mil|mn|ml)|[kKmMbBtT])?\s*(?:monthly\s+)?(?:plays?|views?|streams?|listens?|listeners?|scrobbles?|tracks?|songs?|subscribers?|followers?|fans?)$""",
        RegexOption.IGNORE_CASE
    )

    private val NUMBER_PREFIX = Regex("""^[\d.,\s]+(?:(?:mln|mil|mn|ml)|[kKmMbBtT])?$""")

    // "Track 16" / "Song 5" / "Side A - Track 3" style labels that InnerTube
    // sometimes exposes in the artist flex column for album tracks.
    private val TRACK_LABEL_PATTERN = Regex(
        """^(?:side\s+[a-z\d]+\s*[\u2022\u00B7\-–]?\s*)?(?:track|song|part|no\.?|n\u00B0|#)\s*\d+\s*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Detects strings like "973 plays", "3.4K plays", "4.1K views", "15M listens",
     * "15 ml listens", "2.3M monthly listeners", "10K subscribers", "Track 16"
     * that InnerTube / YouTube Music sometimes exposes in place of an artist.
     */
    fun isPlayCountOrStat(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        // Normalize exotic spaces (NBSP, narrow NBSP, thin space) so localized
        // counters match the ASCII patterns below.
        val normalized = raw.trim()
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .replace('\u2009', ' ')
            .replace(Regex("\\s+"), " ")
            .trim().trim('\u2022', '\u00B7', '-', '\u2013', '\u2014', ',').trim()
        if (normalized.isBlank()) return false
        val trimmed = normalized
        if (STAT_PATTERN.matches(trimmed)) return true
        if (TRACK_LABEL_PATTERN.matches(trimmed)) return true
        val lower = trimmed.lowercase()
        // Any counter phrase with a numeric prefix is a stat, even with extra
        // words ("monthly", locale suffixes) the strict regex above missed.
        // This is what previously leaked counters through as artist names.
        val hasStatWord = lower.contains("play") ||
            lower.contains("view") ||
            lower.contains("stream") ||
            lower.contains("listen") ||
            lower.contains("scrobbl") ||
            lower.contains("subscrib") ||
            lower.contains("follow") ||
            lower.contains("monthly") ||
            lower.contains(" fan") || lower == "fans" || lower.endsWith(" fans") ||
            lower.endsWith(" songs") || lower.endsWith(" song") ||
            lower.endsWith(" tracks") || lower.endsWith(" track")
        if (hasStatWord) {
            // Starts with a number ("15 ...", "3.4K ...", "1,234 ...") -> stat.
            if (trimmed.first().isDigit()) return true
            // Ends with a stat word and the prefix before it is numeric
            // ("15 ml listens" -> prefix "15 ml" is numeric+suffix).
            val withoutLastWord = trimmed.substringBeforeLast(' ').trim()
                .removeSuffix("ly").trim()
            if (withoutLastWord.isNotBlank()) {
                val prefixCandidate = if (lower.endsWith("listeners") || lower.endsWith("listener") ||
                    lower.endsWith("listens") || lower.endsWith("listen")
                ) {
                    // Strip optional "monthly" so "15M monthly" -> "15M".
                    withoutLastWord.removeSuffix("monthly").removeSuffix("Monthly")
                        .removeSuffix("MONTHLY").trim()
                } else {
                    withoutLastWord
                }
                if (prefixCandidate.matches(NUMBER_PREFIX)) return true
                if (prefixCandidate.firstOrNull()?.isDigit() == true &&
                    prefixCandidate.split(' ', '\u00A0').any { it.matches(Regex("""(?i)^\d.*""")) }
                ) {
                    return true
                }
            } else if (lower.contains("monthly") || lower.contains("listen") ||
                lower.contains("subscrib") || lower.contains("follow")
            ) {
                return true
            }
        }
        // Durations ("3:45") and years ("2024") are never artists here.
        if (trimmed.matches(Regex("""^\d{1,3}:\d{2}(?::\d{2})?$"""))) return true
        if (trimmed.matches(Regex("""^(19|20)\d{2}$"""))) return true
        return false
    }

    /**
     * Splits multi-artist strings (e.g. "Arijit Singh, Badshah", "Alan Walker feat. Au/Ra", "Drake & 21 Savage")
     * into clean, individual artist names.
     */
    fun splitArtists(rawArtist: String?): List<String> {
        if (rawArtist.isNullOrBlank() || isPlayCountOrStat(rawArtist)) return emptyList()
        val trimmed = rawArtist.trim()
        val parts = trimmed.split(SEPARATOR_REGEX)
            .map { it.trim().trim(',', '&', '/', ';').trim() }
            .filter { it.isNotBlank() && !isPlayCountOrStat(it) }
            .distinct()
        return if (parts.isNotEmpty()) parts else listOf(trimmed)
    }

    /**
     * Returns the primary / first artist from a composite string.
     */
    fun primaryArtist(rawArtist: String?): String {
        if (rawArtist.isNullOrBlank() || isPlayCountOrStat(rawArtist)) return ""
        return splitArtists(rawArtist).firstOrNull() ?: rawArtist.orEmpty()
    }
}
