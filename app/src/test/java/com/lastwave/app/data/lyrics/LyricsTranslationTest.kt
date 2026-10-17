package com.lastwave.app.data.lyrics

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test

class LyricsTranslationTest {

    // --- flatten: which lines are sent, and as what ---

    @Test
    fun flatten_skipsBlankLinesAndIndexesBySourceLine() {
        val lines = listOf(
            LyricLine(timeMs = 0, text = "   "),
            LyricLine(timeMs = 1000, text = "Hello"),
            LyricLine(timeMs = 2000, text = "World"),
        )
        val slots = LyricsTranslation.flatten(lines)
        assertThat(slots.map { it.lineIndex }).containsExactly(1, 2).inOrder()
        assertThat(slots.map { it.text }).containsExactly("Hello", "World").inOrder()
    }

    @Test
    fun flatten_stripsSectionHeaderBrackets() {
        val lines = listOf(
            LyricLine(timeMs = 0, text = "[Chorus]"),
            LyricLine(timeMs = 1000, text = "Sing it"),
        )
        val slots = LyricsTranslation.flatten(lines)
        assertThat(slots[0].sectionHeader).isTrue()
        assertThat(slots[0].text).isEqualTo("Chorus")
        assertThat(slots[1].sectionHeader).isFalse()
        assertThat(slots[1].text).isEqualTo("Sing it")
    }

    // --- batches / payload / markers ---

    @Test
    fun batches_preserveOrderAndSplitUnderTheCharBudget() {
        val slots = List(10) { index ->
            LyricsTranslation.TextSlot(index, "x".repeat(1000), sectionHeader = false)
        }
        val batches = LyricsTranslation.batches(slots)
        assertThat(batches.size).isGreaterThan(1)
        assertThat(batches.flatMap { it.slots }).isEqualTo(slots)
        batches.forEach { batch ->
            assertThat(batch.payload.length).isAtMost(3_500)
        }
    }

    @Test
    fun batches_separateSlotsWithMarkers() {
        val slots = listOf(
            LyricsTranslation.TextSlot(0, "first", sectionHeader = false),
            LyricsTranslation.TextSlot(1, "second", sectionHeader = false),
        )
        val batch = LyricsTranslation.batches(slots).single()
        assertThat(batch.payload).isEqualTo("first\n\uE0000001\uE001\nsecond")
    }

    @Test
    fun parseTranslatedAnswer_mapsSegmentsToSlotsAndReadsSource() {
        val slots = listOf(
            LyricsTranslation.TextSlot(0, "你好", sectionHeader = false),
            LyricsTranslation.TextSlot(1, "世界", sectionHeader = false),
        )
        val payload = LyricsTranslation.batches(slots).single().payload
        val marker = "\uE0000001\uE001"
        val response =
            """[[["Hello","你好",null,null,10],["$marker","$marker",null,null,10],["World","世界",null,null,10]],null,"zh-CN"]"""
        val answer = LyricsTranslation.parseTranslatedAnswer(response, slots.size, payload.length)
        assertThat(answer).isNotNull()
        assertThat(answer!!.translations).containsExactly("Hello", "World").inOrder()
        assertThat(answer.sourceLanguage).isEqualTo("zh-CN")
    }

    @Test
    fun parseTranslatedAnswer_toleratesDigitTransformedMarkers() {
        // Input Tools rewrites ASCII digits into the destination script; the
        // marker match must survive that or every romanized batch would fail.
        val slots = listOf(
            LyricsTranslation.TextSlot(0, "你好", sectionHeader = false),
            LyricsTranslation.TextSlot(1, "世界", sectionHeader = false),
        )
        val response = "[[[\"Hello\uE000٠٠٠١\uE001World\",\"\"]]]"
        val answer = LyricsTranslation.parseTranslatedAnswer(response, slots.size, 10)
        assertThat(answer).isNotNull()
        assertThat(answer!!.translations).containsExactly("Hello", "World").inOrder()
    }

    @Test
    fun parseTranslatedAnswer_rejectsMismatchedChunkCount() {
        val slots = listOf(
            LyricsTranslation.TextSlot(0, "a", sectionHeader = false),
            LyricsTranslation.TextSlot(1, "b", sectionHeader = false),
        )
        val response = """[[["only one chunk"]],null,"en"]"""
        assertThat(LyricsTranslation.parseTranslatedAnswer(response, slots.size, 10)).isNull()
    }

    @Test
    fun parseTranslatedAnswer_rejectsBlankChunksAndGarbage() {
        val twoChunks = "[[[\"Hello\uE0000001\uE001  \",\"\"]]]"
        assertThat(LyricsTranslation.parseTranslatedAnswer(twoChunks, 2, 10)).isNull()
        assertThat(LyricsTranslation.parseTranslatedAnswer("not json", 1, 10)).isNull()
        assertThat(LyricsTranslation.parseTranslatedAnswer("{}", 1, 10)).isNull()
    }

    @Test
    fun parseRomanizedAnswer_readsSegmentThreeAndRequiresSource() {
        val response = """[[["你好",null,"nǐ hǎo","nǐ hǎo",10]],null,"zh-CN"]"""
        val answer = LyricsTranslation.parseRomanizedAnswer(response, 1, 10)
        assertThat(answer).isNotNull()
        assertThat(answer!!.translations).containsExactly("nǐ hǎo")
        assertThat(answer.sourceLanguage).isEqualTo("zh-CN")

        val noSource = """[[["你好",null,"nǐ hǎo","nǐ hǎo",10]],null,""]"""
        assertThat(LyricsTranslation.parseRomanizedAnswer(noSource, 1, 10)).isNull()
    }

    // --- rebuild: sub-line alignment for the player ---

    @Test
    fun rebuild_alignsByIndex_nullsBlanksAndUnchangedLines() {
        val lines = listOf(
            LyricLine(timeMs = 0, text = "Hello"),
            LyricLine(timeMs = 1000, text = ""),
            LyricLine(timeMs = 2000, text = "[Chorus]"),
            LyricLine(timeMs = 3000, text = "OK"),
        )
        val slots = listOf(
            LyricsTranslation.TextSlot(0, "Hello", sectionHeader = false),
            LyricsTranslation.TextSlot(2, "Chorus", sectionHeader = true),
            LyricsTranslation.TextSlot(3, "OK", sectionHeader = false),
        )
        val subLines = LyricsTranslation.rebuild(lines, slots, listOf("Bonjour", "Refrain", "OK"))
        assertThat(subLines).containsExactly("Bonjour", null, "[Refrain]", null).inOrder()
    }

    // --- guards that must not hit the network ---

    @Test
    fun translate_blankTargetIsUnavailable() = runBlocking {
        val result = LyricsTranslation.translate(
            trackId = "track",
            lines = listOf(LyricLine(timeMs = 0, text = "Hello")),
            targetLanguageTag = "  ",
        )
        assertThat(result).isEqualTo(LyricsTranslation.Result.Unavailable)
    }

    @Test
    fun translate_emptyLinesIsUnavailable() = runBlocking {
        val result = LyricsTranslation.translate("track", emptyList(), "fr")
        assertThat(result).isEqualTo(LyricsTranslation.Result.Unavailable)
    }

    @Test
    fun romanize_allLatinLyricsIsAlreadyRomanized() = runBlocking {
        val lines = listOf(
            LyricLine(timeMs = 0, text = "Nothing to Do"),
            LyricLine(timeMs = 1000, text = "With You"),
        )
        val result = LyricsTranslation.romanize("track", lines)
        assertThat(result).isEqualTo(LyricsTranslation.RomanizationResult.AlreadyRomanized)
    }

    @Test
    fun romanize_emptyLinesIsUnavailable() = runBlocking {
        assertThat(LyricsTranslation.romanize("track", emptyList()))
            .isEqualTo(LyricsTranslation.RomanizationResult.Unavailable)
    }

    // --- language handling ---

    @Test
    fun canonicalLanguage_normalisesLegacyTagsAndRegions() {
        assertThat(LyricsTranslation.canonicalLanguage("iw")).isEqualTo("he")
        assertThat(LyricsTranslation.canonicalLanguage("in")).isEqualTo("id")
        assertThat(LyricsTranslation.canonicalLanguage("ji")).isEqualTo("yi")
        assertThat(LyricsTranslation.canonicalLanguage("pt-BR")).isEqualTo("pt")
        assertThat(LyricsTranslation.canonicalLanguage("EN")).isEqualTo("en")
    }

    @Test
    fun translationLanguageName_prefersPlatformThenFallback() {
        val english = java.util.Locale.ENGLISH
        assertThat(translationLanguageName("fr", english)).isEqualTo("French")
        // Curated name wins for script-carrying codes: the platform would say
        // "Chinese (China)", which names the wrong axis.
        assertThat(translationLanguageName("zh-TW", english)).isEqualTo("Chinese (Traditional)")
        assertThat(translationLanguageName("xx", english)).isNotEmpty()
    }

    @Test
    fun translationWireTarget_passesConfiguredCodeThrough() {
        assertThat(translationWireTarget(" fr ")).isEqualTo("fr")
        assertThat(translationWireTarget("zh-TW")).isEqualTo("zh-TW")
        assertThat(translationWireTarget("").isNotBlank()).isTrue()
    }

    @Test
    fun unchangedWeight_countsOnlyLinesIdenticalAfterNormalisation() {
        val slots = listOf(
            LyricsTranslation.TextSlot(0, "Hello", sectionHeader = false),
            LyricsTranslation.TextSlot(1, "World", sectionHeader = false),
        )
        assertThat(LyricsTranslation.unchangedWeight(slots, listOf("hello ", "Monde")))
            .isEqualTo(5)
        assertThat(LyricsTranslation.unchangedWeight(slots, listOf("Bonjour", "Monde")))
            .isEqualTo(0)
    }

    // --- cache ---

    @Test
    fun cacheKey_isStableAndSensitiveToEveryInput() {
        val slots = listOf(LyricsTranslation.TextSlot(0, "a", sectionHeader = false))
        val base = LyricsTranslation.cacheKey("track", "fr", slots)
        assertThat(LyricsTranslation.cacheKey("track", "fr", listOf(LyricsTranslation.TextSlot(0, "a", sectionHeader = false))))
            .isEqualTo(base)
        assertThat(LyricsTranslation.cacheKey("other", "fr", slots)).isNotEqualTo(base)
        assertThat(LyricsTranslation.cacheKey("track", "de", slots)).isNotEqualTo(base)
        assertThat(LyricsTranslation.cacheKey("track", "fr", listOf(LyricsTranslation.TextSlot(0, "b", sectionHeader = false))))
            .isNotEqualTo(base)
    }

    @Test
    fun cache_roundTripsThroughGzipAndRejectsStaleVersions() = runBlocking {
        val directory = Files.createTempDirectory("lw-lyrics-translation-test").toFile()
        LyricsTranslation.cacheDir = directory
        try {
            val key = LyricsTranslation.cacheKey(
                "track",
                "fr",
                listOf(LyricsTranslation.TextSlot(0, "hello", sectionHeader = false)),
            )
            val entry = LyricsTranslation.CachedTranslation(
                sourceLanguage = "en",
                targetLanguage = "fr",
                texts = listOf("bonjour"),
            )
            LyricsTranslation.writeCache(key, entry)
            assertThat(LyricsTranslation.readCache(key)).isEqualTo(entry)

            val staleKey = key + "-stale"
            LyricsTranslation.writeCache(
                staleKey,
                LyricsTranslation.CachedTranslation(
                    version = 99,
                    sourceLanguage = "en",
                    targetLanguage = "fr",
                    texts = listOf("vieux"),
                ),
            )
            assertThat(LyricsTranslation.readCache(staleKey)).isNull()
        } finally {
            LyricsTranslation.cacheDir = null
            directory.deleteRecursively()
        }
    }
}
