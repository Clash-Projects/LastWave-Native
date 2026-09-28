package com.lastwave.app.ui.settings

import java.util.Locale

/**
 * Result of a fuzzy search match against a [SettingsEntry].
 *
 * @property entry The matched settings entry
 * @property score Calculated relevance score (higher is more relevant)
 * @property titleMatchedRanges Consecutive character ranges in [SettingsEntry.title] that matched the query
 * @property subtitleMatchedRanges Consecutive character ranges in [SettingsEntry.subtitle] that matched the query
 */
data class MatchResult(
    val entry: SettingsEntry,
    val score: Int,
    val titleMatchedRanges: List<IntRange> = emptyList(),
    val subtitleMatchedRanges: List<IntRange> = emptyList(),
)

/**
 * Pure-Kotlin weighted fuzzy subsequence matcher for settings search.
 */
object FuzzyMatcher {

    private const val MIN_SCORE_THRESHOLD = 20

    /**
     * Searches a list of [SettingsEntry] items using weighted fuzzy matching.
     *
     * @param query The user's search text
     * @param entries The list of all cataloged settings entries
     * @return Ranked list of matching results, sorted descending by relevance score
     */
    fun search(query: String, entries: List<SettingsEntry>): List<MatchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val lowerQuery = trimmed.lowercase(Locale.ROOT)
        val queryWords = lowerQuery.split("\\s+".toRegex()).filter { it.isNotEmpty() }

        val results = ArrayList<MatchResult>(entries.size)

        for (entry in entries) {
            val titleLower = entry.title.lowercase(Locale.ROOT)
            val subLower = entry.subtitle.lowercase(Locale.ROOT)

            var score = 0
            val titleRanges = mutableListOf<IntRange>()
            val subRanges = mutableListOf<IntRange>()

            // 1. Direct whole-query checks on title
            when {
                titleLower == lowerQuery -> {
                    score += 150
                    titleRanges.add(0 until entry.title.length)
                }
                titleLower.startsWith(lowerQuery) -> {
                    score += 110
                    titleRanges.add(0 until lowerQuery.length)
                }
                titleLower.contains(lowerQuery) -> {
                    val idx = titleLower.indexOf(lowerQuery)
                    val isWordBoundary = idx == 0 || !titleLower[idx - 1].isLetterOrDigit()
                    score += if (isWordBoundary) 95 else 80
                    titleRanges.add(idx until (idx + lowerQuery.length))
                }
                else -> {
                    // Subsequence fuzzy match on title
                    val fuzzyScore = fuzzySubsequenceMatch(lowerQuery, titleLower, titleRanges)
                    if (fuzzyScore > 0) {
                        score += (fuzzyScore * 0.9f).toInt()
                    }
                }
            }

            // 2. Keyword matching
            var maxKeywordScore = 0
            for (keyword in entry.keywords) {
                val kwLower = keyword.lowercase(Locale.ROOT)
                val kwScore = when {
                    kwLower == lowerQuery -> 85
                    kwLower.startsWith(lowerQuery) -> 70
                    kwLower.contains(lowerQuery) -> {
                        val idx = kwLower.indexOf(lowerQuery)
                        val isWordBoundary = idx == 0 || !kwLower[idx - 1].isLetterOrDigit()
                        if (isWordBoundary) 60 else 45
                    }
                    else -> {
                        val subFuzzy = fuzzySubsequenceMatch(lowerQuery, kwLower, null)
                        if (subFuzzy > 0) (subFuzzy * 0.5f).toInt() else 0
                    }
                }
                if (kwScore > maxKeywordScore) {
                    maxKeywordScore = kwScore
                }
            }
            score += maxKeywordScore

            // 3. Subtitle matching
            when {
                subLower.startsWith(lowerQuery) -> {
                    score += 45
                    subRanges.add(0 until lowerQuery.length)
                }
                subLower.contains(lowerQuery) -> {
                    val idx = subLower.indexOf(lowerQuery)
                    val isWordBoundary = idx == 0 || !subLower[idx - 1].isLetterOrDigit()
                    score += if (isWordBoundary) 40 else 30
                    subRanges.add(idx until (idx + lowerQuery.length))
                }
                else -> {
                    val fuzzyScore = fuzzySubsequenceMatch(lowerQuery, subLower, subRanges)
                    if (fuzzyScore > 0) {
                        score += (fuzzyScore * 0.35f).toInt()
                    }
                }
            }

            // 4. Multi-word query bonus (all words present in title/keywords/subtitle)
            if (queryWords.size > 1) {
                var allWordsFound = true
                for (word in queryWords) {
                    val inTitle = titleLower.contains(word)
                    val inKeywords = entry.keywords.any { it.lowercase(Locale.ROOT).contains(word) }
                    val inSubtitle = subLower.contains(word)
                    if (!inTitle && !inKeywords && !inSubtitle) {
                        allWordsFound = false
                        break
                    }
                }
                if (allWordsFound) {
                    score += 35
                }
            }

            // 5. Section and parent tab matching bonus
            val sectionLower = entry.section.lowercase(Locale.ROOT)
            val tabTitleLower = entry.parentTab.title.lowercase(Locale.ROOT)
            if (sectionLower.contains(lowerQuery) || tabTitleLower.contains(lowerQuery)) {
                score += 25
            }

            if (score >= MIN_SCORE_THRESHOLD) {
                results.add(
                    MatchResult(
                        entry = entry,
                        score = score,
                        titleMatchedRanges = compactRanges(titleRanges),
                        subtitleMatchedRanges = compactRanges(subRanges),
                    )
                )
            }
        }

        results.sortByDescending { it.score }
        return results
    }

    /**
     * Calculates subsequence matching score and collects matched ranges.
     * Characters in [pattern] must appear in [target] in the same order.
     */
    private fun fuzzySubsequenceMatch(
        pattern: String,
        target: String,
        outRanges: MutableList<IntRange>?,
    ): Int {
        if (pattern.isEmpty() || target.isEmpty()) return 0
        if (pattern.length > target.length) return 0

        var patternIdx = 0
        var score = 0
        var consecutiveCount = 0
        val matchedIndices = mutableListOf<Int>()

        for (targetIdx in target.indices) {
            if (patternIdx < pattern.length && pattern[patternIdx] == target[targetIdx]) {
                matchedIndices.add(targetIdx)
                patternIdx++

                // Base match points
                var matchPoints = 12

                // Word boundary bonus
                val isWordStart = targetIdx == 0 || !target[targetIdx - 1].isLetterOrDigit()
                if (isWordStart) {
                    matchPoints += 15
                }

                // Consecutive match bonus
                consecutiveCount++
                matchPoints += (consecutiveCount * 6)

                // Match position penalty (penalize matches far down the string slightly)
                matchPoints -= (targetIdx / 4).coerceAtMost(10)

                score += matchPoints.coerceAtLeast(1)
            } else {
                consecutiveCount = 0
            }
        }

        // Did we match the entire pattern?
        if (patternIdx < pattern.length) {
            return 0
        }

        // Add matched ranges for UI highlighting if requested
        if (outRanges != null && matchedIndices.isNotEmpty()) {
            var rangeStart = matchedIndices[0]
            var prev = rangeStart
            for (i in 1 until matchedIndices.size) {
                val curr = matchedIndices[i]
                if (curr == prev + 1) {
                    prev = curr
                } else {
                    outRanges.add(rangeStart..prev)
                    rangeStart = curr
                    prev = curr
                }
            }
            outRanges.add(rangeStart..prev)
        }

        return score
    }

    /**
     * Merges overlapping or adjacent [IntRange] items and removes duplicates.
     */
    private fun compactRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        var current = sorted[0]

        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.first <= current.last + 1) {
                current = current.first..maxOf(current.last, next.last)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }
}
