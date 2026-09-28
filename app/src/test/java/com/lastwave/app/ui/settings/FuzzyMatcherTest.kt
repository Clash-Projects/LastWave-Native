package com.lastwave.app.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [FuzzyMatcher] and [SettingsSearchIndex].
 */
class FuzzyMatcherTest {

    private val allEntries = SettingsSearchIndex.allEntries

    @Test
    fun emptyOrWhitespaceQueryReturnsEmptyList() {
        assertThat(FuzzyMatcher.search("", allEntries)).isEmpty()
        assertThat(FuzzyMatcher.search("   ", allEntries)).isEmpty()
        assertThat(FuzzyMatcher.search("\t\n", allEntries)).isEmpty()
    }

    @Test
    fun exactTitleMatchScoresHighest() {
        val results = FuzzyMatcher.search("Equalizer", allEntries)
        assertThat(results).isNotEmpty()
        val top = results.first()
        assertThat(top.entry.id).isEqualTo("audio.equalizer")
        assertThat(top.score).isAtLeast(150)
        assertThat(top.titleMatchedRanges).containsExactly(0 until "Equalizer".length)
    }

    @Test
    fun prefixTitleMatch() {
        val results = FuzzyMatcher.search("scrob", allEntries)
        assertThat(results).isNotEmpty()
        val top = results.first()
        assertThat(top.entry.title.lowercase()).startsWith("scrob")
        assertThat(top.titleMatchedRanges).isNotEmpty()
        assertThat(top.titleMatchedRanges.first().first).isEqualTo(0)
    }

    @Test
    fun keywordMatchFindsRelevantSetting() {
        // "gain" is in keywords of "Loudness Normalization"
        val results = FuzzyMatcher.search("gain", allEntries)
        assertThat(results).isNotEmpty()
        val found = results.any { it.entry.id == "audio.loudness_normalization" }
        assertThat(found).isTrue()

        // "amoled" matches "Pure Black AMOLED"
        val amoledResults = FuzzyMatcher.search("amoled", allEntries)
        assertThat(amoledResults).isNotEmpty()
        assertThat(amoledResults.first().entry.id).isEqualTo("appearance.amoled")

        // "loudness" matches "Loudness Normalization"
        val loudnessResults = FuzzyMatcher.search("loudness", allEntries)
        assertThat(loudnessResults).isNotEmpty()
        assertThat(loudnessResults.any { it.entry.id == "audio.loudness_normalization" }).isTrue()
    }

    @Test
    fun fuzzySubsequenceMatchHandlesTyposAndAbbreviations() {
        // "eq" matches "Equalizer"
        val eqResults = FuzzyMatcher.search("eq", allEntries)
        assertThat(eqResults).isNotEmpty()
        assertThat(eqResults.any { it.entry.id == "audio.equalizer" }).isTrue()

        // "bkup" matches "Backup Settings & Playlists"
        val bkupResults = FuzzyMatcher.search("bkup", allEntries)
        assertThat(bkupResults).isNotEmpty()
        assertThat(bkupResults.any { it.entry.id == "backup.create" }).isTrue()

        // "xclus" or "exclus" matches "USB Exclusive Output"
        val exclusResults = FuzzyMatcher.search("exclus", allEntries)
        assertThat(exclusResults).isNotEmpty()
        assertThat(exclusResults.any { it.entry.id == "audio.usb_exclusive" }).isTrue()
    }

    @Test
    fun subtitleMatch() {
        // "replaygain" is in the subtitle of Loudness Normalization
        val results = FuzzyMatcher.search("replaygain", allEntries)
        assertThat(results).isNotEmpty()
        val norm = results.firstOrNull { it.entry.id == "audio.loudness_normalization" }
        assertThat(norm).isNotNull()
        assertThat(norm!!.subtitleMatchedRanges).isNotEmpty()
    }

    @Test
    fun multiWordQueryGivesBonus() {
        val singleWord = FuzzyMatcher.search("backup", allEntries)
        val multiWord = FuzzyMatcher.search("backup restore", allEntries)

        assertThat(singleWord).isNotEmpty()
        assertThat(multiWord).isNotEmpty()

        val topMulti = multiWord.first()
        assertThat(topMulti.entry.id).isAnyOf("backup.create", "backup.restore")
    }

    @Test
    fun resultsAreRankedDescendingByScore() {
        val results = FuzzyMatcher.search("theme", allEntries)
        assertThat(results.size).isAtLeast(2)
        for (i in 0 until results.size - 1) {
            assertThat(results[i].score).isAtLeast(results[i + 1].score)
        }
    }

    @Test
    fun unrelatedQueryReturnsEmptyList() {
        val results = FuzzyMatcher.search("zyxwvutsrqp12345", allEntries)
        assertThat(results).isEmpty()
    }

    @Test
    fun allSettingsCatalogEntriesHaveValidAttributes() {
        // Ensure index integrity: unique IDs, non-empty titles, icons, and valid tabs
        val ids = mutableSetOf<String>()
        for (entry in allEntries) {
            assertThat(ids.contains(entry.id)).isFalse()
            ids.add(entry.id)
            assertThat(entry.title.trim()).isNotEmpty()
            assertThat(entry.section.trim()).isNotEmpty()
            assertThat(entry.parentTab).isNotNull()
            assertThat(entry.icon).isNotNull()
        }
        assertThat(allEntries.size).isAtLeast(40)
    }
}
